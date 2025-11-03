// ========================================================================
//      JENKINS PIPELINE: ОРКЕСТРАТОР ОБНОВЛЕНИЯ 1С ПО GIT-ТЕГАМ
// ========================================================================
// ЛОГИКА:
// 1. Читает файлы D:\DevOps\deployment_state\<db>_last_tag.txt,
//    где хранятся теги последнего развернутого билда (.cf и .cfe).
// 2. Получает список последних тегов из Git для основной конфы и расширений.
// 3. Сравнивает их: если новый тег — выполняет обновление соответствующего компонента.
// 4. После успешного обновления пишет новые теги обратно в state.
// ========================================================================

library '1c-utils@master'
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }
    options { timestamps(); disableConcurrentBuilds() }

    parameters {
        string(name: 'SERVER_1C', defaultValue: 'cluster1c.domain.local:1545', description: 'RAS-сервер 1С')
        string(name: 'SERVER_DB', defaultValue: 'sql1.domain.local', description: 'Сервер СУБД')
        string(name: 'DBNAME', defaultValue: 'ERP_Finance', description: 'Имя базы данных')
        credentials(name: 'SQL_CRED', defaultValue: 'sql-auth-prod', description: 'Доступ к MSSQL')
        credentials(name: 'RAC_CRED', defaultValue: 'cluster-admin-prod', description: 'RAC доступ к кластеру 1С')
    }

    environment {
        STATE_DIR     = 'D:\\DevOps\\deployment_state'
        CF_STATE_FILE = "${STATE_DIR}\\${params.DBNAME}_cf_tag.txt"
        EXT_STATE_DIR = "${STATE_DIR}\\extensions"
        BACKUP_DIR    = '\\\\backups\\ERP'
        MANIFEST_FILE = "${WORKSPACE}\\extension-prod.json"
    }

    stages {
        // ------------------------------------------------------------
        // 1. Определяем какие компоненты нужно обновлять
        // ------------------------------------------------------------
        stage('Check Git tags for updates') {
            steps {
                script {
                    // читаем предыдущие теги
                    def lastCfTag = fileExists(env.CF_STATE_FILE) ? readFile(env.CF_STATE_FILE).trim() : ''
                    echo "Последний установленный CF-тег: ${lastCfTag ?: '(отсутствует)'}"

                    // получаем последний тег из Git (Build_CF репо)
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@gitlab.company.com/holding/ERP.git"
                        def latestCfTag = bat(script: "git ls-remote --tags --sort=-v:refname ${remoteUrl} | findstr /V \"{}\" | head -n 1", returnStdout: true).trim().split()[1]?.replace('refs/tags/', '')
                        env.LATEST_CF_TAG = latestCfTag ?: ''
                        echo "Найден последний тег CF: ${env.LATEST_CF_TAG}"

                        env.NEED_UPDATE_CF = (env.LATEST_CF_TAG && env.LATEST_CF_TAG != lastCfTag) ? "true" : "false"
                    }

                    // Проверяем расширения по extension-prod.json
                    def manifest = new groovy.json.JsonSlurper().parse(new File(env.MANIFEST_FILE))
                    env.UPDATE_EXT_LIST = ''
                    manifest.extensions.each { ext ->
                        def name = ext.name
                        def repo = ext.repo
                        def branch = ext.branch ?: 'master'
                        def stateFile = "${env.EXT_STATE_DIR}\\${name}_tag.txt"
                        def lastExtTag = fileExists(stateFile) ? readFile(stateFile).trim() : ''

                        withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                            def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${repo.replace('https://','')}"
                            def latestExtTag = bat(script: "git ls-remote --tags --sort=-v:refname ${remoteUrl} ${branch} | findstr /V \"{}\" | head -n 1", returnStdout: true).trim().split()[1]?.replace('refs/tags/', '')
                            if (latestExtTag && latestExtTag != lastExtTag) {
                                echo "Обнаружен новый тег для ${name}: ${latestExtTag}"
                                env.UPDATE_EXT_LIST += "${name}:${latestExtTag};"
                            }
                        }
                    }

                    if (env.NEED_UPDATE_CF == "false" && !env.UPDATE_EXT_LIST) {
                        utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "⚙ Обновлений по тегам нет для ${params.DBNAME}", true)
                        currentBuild.result = 'SUCCESS'
                        error("Пропуск деплоя: теги не изменились")
                    }
                }
            }
        }

        // ------------------------------------------------------------
        // 2. Блокировка пользователей
        // ------------------------------------------------------------
        stage('Lock Sessions') {
            when { expression { env.NEED_UPDATE_CF == "true" || env.UPDATE_EXT_LIST } }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                        utils.lockSessions(params.SERVER_1C, params.DBNAME, RAC_USER, RAC_PASS, "Обновление PROD по тегам")
                    }
                }
            }
        }

        // ------------------------------------------------------------
        // 3. Бэкап MSSQL
        // ------------------------------------------------------------
        stage('Backup Database') {
            when { expression { env.NEED_UPDATE_CF == "true" || env.UPDATE_EXT_LIST } }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: params.SQL_CRED, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                        utils.mssqlBackup(params.SERVER_DB, params.DBNAME, env.BACKUP_DIR, SQL_USER, SQL_PASS)
                    }
                }
            }
        }

        // ------------------------------------------------------------
        // 4. Обновление основной конфигурации
        // ------------------------------------------------------------
        stage('Update Main Configuration') {
            when { expression { env.NEED_UPDATE_CF == "true" } }
            steps {
                script {
                    copyArtifacts(projectName: 'Build_CF', selector: specific(env.LATEST_CF_TAG), filter: 'build/*.cf', target: 'artifacts/', flatten: true)
                    def cfFile = bat(script: 'dir /B artifacts\\*.cf', returnStdout: true).trim()
                    withCredentials([usernamePassword(credentialsId: params.SQL_CRED, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                        utils.updateDB_via_ibcmd_or_vrunner("artifacts\\${cfFile}", params.SERVER_DB, params.DBNAME, SQL_USER, SQL_PASS)
                    }
                    writeFile(file: env.CF_STATE_FILE, text: env.LATEST_CF_TAG)
                    echo "Тег CF ${env.LATEST_CF_TAG} записан в state."
                }
            }
        }

        // ------------------------------------------------------------
        // 5. Обновление расширений
        // ------------------------------------------------------------
        stage('Deploy Extensions by Tags') {
            when { expression { env.UPDATE_EXT_LIST } }
            steps {
                script {
                    def pairs = env.UPDATE_EXT_LIST.split(';').findAll { it }
                    pairs.each { item ->
                        def parts = item.split(':')
                        def name = parts[0]
                        def tag = parts[1]
                        def manifest = new groovy.json.JsonSlurper().parse(new File(env.MANIFEST_FILE))
                        def ext = manifest.extensions.find { it.name == name }
                        def repo = ext.repo

                        echo "Развёртывание ${name} по тегу ${tag}"
                        copyArtifacts(projectName: "Build_CFE_${name}", selector: specific(tag), filter: 'build/*.cfe', target: 'artifacts/', flatten: true)
                        def cfeFile = bat(script: 'dir /B artifacts\\*.cfe', returnStdout: true).trim()

                        withCredentials([usernamePassword(credentialsId: params.SQL_CRED, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS')]) {
                            utils.updateExtension_via_ibcmd_or_vrunner("artifacts\\${cfeFile}", name, params.SERVER_DB, params.DBNAME, SQL_USER, SQL_PASS)
                        }
                        writeFile(file: "${env.EXT_STATE_DIR}\\${name}_tag.txt", text: tag)
                        echo "Тег ${tag} для ${name} записан в state."
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                    utils.unlockSessions(params.SERVER_1C, params.DBNAME, RAC_USER, RAC_PASS)
                }
            }
        }
        success {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "✅ Обновление PROD (${params.DBNAME}) по тегам завершено", true)
            }
        }
        failure {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "❌ Ошибка обновления PROD (${params.DBNAME}) по тегам", false)
            }
        }
    }
}
