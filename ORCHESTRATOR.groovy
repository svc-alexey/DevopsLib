// ========================================================================
//      JENKINS PIPELINE: ОРКЕСТРАТОР ОБНОВЛЕНИЯ 1С ПО GIT-ТЕГАМ
// ========================================================================
library '1c-utils@master'
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }
    options { timestamps(); disableConcurrentBuilds() }

    environment {
        STATE_DIR     = 'D:\\DevOps\\deployment_state'
        CF_STATE_FILE = "${STATE_DIR}\\${params.DBNAME}_cf_tag.txt"
        EXT_STATE_DIR = "${STATE_DIR}\\extensions"
        BACKUP_DIR    = '\\\\opl-dc01-sqlc3\\backup_base\\BACKUP\\NO_DELETE'
        MANIFEST_FILE = "${WORKSPACE}\\extension-prod.json"
    }

    stages {

        stage('Checkout ERP repository') {
            steps {
                script {
                    cleanWs()
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/master']],
                        userRemoteConfigs: [[
                            url: "https://${params.rep_git_remote}",
                            credentialsId: 'token'
                        ]],
                        extensions: [
                            [$class: 'CloneOption', shallow: true, noTags: true, timeout: 5, depth: 1]
                        ]
                    ])
                }
            }
        }

        // ------------------------------------------------------------
        // 1. Проверяем наличие новых тегов
        // ------------------------------------------------------------
        stage('Check Git tags for updates') {
            steps {
                script {
                    def lastCfTag = fileExists(env.CF_STATE_FILE) ? readFile(env.CF_STATE_FILE).trim() : ''
                    echo "Последний установленный CF-тег: ${lastCfTag ?: '(отсутствует)'}"

                    withCredentials([usernamePassword(credentialsId: 'token',
                                                     usernameVariable: 'GIT_USER',
                                                     passwordVariable: 'GIT_TOKEN')]) {
                        def latestCfTag = powershell(
                            script: """
                                \$Token = "${GIT_TOKEN}"
                                \$RepoUrl = "https://${GIT_USER}:\$Token@${params.rep_git_remote}"
                                git ls-remote --tags --sort=-v:refname \$RepoUrl |
                                Select-String -NotMatch "\\{\\}" |
                                Select-Object -First 1
                            """,
                            returnStdout: true
                        ).trim().split()[1]?.replace('refs/tags/', '')

                        env.LATEST_CF_TAG = latestCfTag ?: ''
                        echo "Найден последний тег CF: ${env.LATEST_CF_TAG}"
                        env.NEED_UPDATE_CF = (env.LATEST_CF_TAG && env.LATEST_CF_TAG != lastCfTag) ? "true" : "false"
                    }

                    // читаем extension-prod.json безопасно
                    def manifest = readJSON file: env.MANIFEST_FILE
                    env.UPDATE_EXT_LIST = ''
                    manifest.extensions.each { ext ->
                        def name = ext.name
                        def repo = ext.repo
                        def stateFile = "${env.EXT_STATE_DIR}\\${name}_tag.txt"
                        def lastExtTag = fileExists(stateFile) ? readFile(stateFile).trim() : ''

                        withCredentials([usernamePassword(credentialsId: 'token',
                                                         usernameVariable: 'GIT_USER',
                                                         passwordVariable: 'GIT_TOKEN')]) {
                            def latestExtTag = powershell(
                                script: """
                                    \$Token = "${GIT_TOKEN}"
                                    \$RepoUrl = "https://${GIT_USER}:\$Token@${repo.replace('https://','')}"
                                    git ls-remote --tags --sort=-v:refname \$RepoUrl |
                                    Select-String -NotMatch "\\{\\}" |
                                    Select-Object -First 1
                                """,
                                returnStdout: true
                            ).trim().split()[1]?.replace('refs/tags/', '')

                            if (latestExtTag && latestExtTag != lastExtTag) {
                                echo "Обнаружен новый тег для ${name}: ${latestExtTag}"
                                env.UPDATE_EXT_LIST += "${name}:${latestExtTag};"
                            }
                        }
                    }

                    if (env.NEED_UPDATE_CF == "false" && !env.UPDATE_EXT_LIST) {
                        utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID,
                            "⚙ Обновлений по тегам нет для ${params.DBNAME}", true)
                        currentBuild.result = 'SUCCESS'
                        error("Пропуск деплоя: теги не изменились")
                    }
                }
            }
        }

        // ------------------------------------------------------------
        // 2. Остальные стадии (Lock / Backup / Update / Deploy)
        // ------------------------------------------------------------
        stage('Lock Sessions') {
            when { expression { env.NEED_UPDATE_CF == "true" || env.UPDATE_EXT_LIST } }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: params.RAC_CRED,
                                                      usernameVariable: 'RAC_USER',
                                                      passwordVariable: 'RAC_PASS')]) {
                        utils.lockSessions(params.SERVER_1C, params.IB_NAME,
                                           RAC_USER, RAC_PASS, "Обновление PROD по тегам")
                    }
                }
            }
        }

        

       stage('Update Main Configuration') {
    when { expression { env.NEED_UPDATE_CF == "true" } }
    steps {
        script {
            def cfJob = params.BUILD_CF_JOB
            def tag   = env.LATEST_CF_TAG

            echo "🚚 Копируем CF из job '${cfJob}' для тега '${tag}'"
            copyArtifacts(
                projectName: cfJob,
                selector: lastSuccessful(),
                filter: "build/*${tag}*.cf",
                target: 'artifacts/',
                flatten: true
            )

            // Вместо new File — используем стандартный listFiles из Pipeline Utility Steps
            def cfFiles = findFiles(glob: 'artifacts/*.cf')
            if (cfFiles.length == 0) {
                error "❌ Не найден .cf-файл в каталоге artifacts"
            }

            def cfFile = cfFiles[0].name
            echo "✅ Найден файл CF: ${cfFile}. Обновляем базу..."

            withCredentials([usernamePassword(
                credentialsId: params.SQL_CRED,
                usernameVariable: 'SQL_USER',
                passwordVariable: 'SQL_PASS'
            )]) {
                utils.updateDB_via_ibcmd_or_vrunner(
                    "artifacts\\${cfFile}",
                    params.SERVER_DB,
                    params.DB_NAME,
                    SQL_USER,
                    SQL_PASS
                )
            }

            writeFile(file: env.CF_STATE_FILE, text: tag)
            echo "📌 CF-тег '${tag}' записан в state."
        }
    }
}





        stage('Deploy Extensions by Tags') {
    when { expression { env.UPDATE_EXT_LIST } }
    steps {
        script {
            def manifest = readJSON file: env.MANIFEST_FILE
            def pairs = env.UPDATE_EXT_LIST.split(';').findAll { it }

            pairs.each { item ->
                def parts = item.split(':')
                def name = parts[0]
                def tag = parts[1]

                def ext = manifest.extensions.find { it.name == name }
                if (!ext) {
                    error("❌ В manifest не найдено описание расширения: ${name}")
                }

                def jobName = ext.job ?: "Build_CFE_${name}"
                echo "🚀 Развёртывание расширения '${name}' по тегу '${tag}' из job '${jobName}'"

                copyArtifacts(
                    projectName: jobName,
                    selector: lastSuccessful(),
                    filter: "build/*${tag}*.cfe",
                    target: 'artifacts/',
                    flatten: true
                )

                // Чистое извлечение имени файла без мусора
                def cfeFile = bat(
                    script: '@echo off & for /f "delims=" %f in (\'dir /b artifacts\\*.cfe\') do @echo %f',
                    returnStdout: true
                ).trim()

                if (!cfeFile || !fileExists("artifacts\\${cfeFile}")) {
                    error("❌ Не найден .cfe с тегом '${tag}' в артефактах job '${jobName}'")
                }

                echo "✅ Найден файл расширения: ${cfeFile}. Загружаем в базу..."

                withCredentials([usernamePassword(
                    credentialsId: params.SQL_CRED,
                    usernameVariable: 'SQL_USER',
                    passwordVariable: 'SQL_PASS'
                )]) {
                    utils.updateExtension_via_ibcmd_or_vrunner(
                        "artifacts\\${cfeFile}",
                        name,
                        params.SERVER_DB,
                        params.DB_NAME,
                        SQL_USER,
                        SQL_PASS
                    )
                }

                writeFile(
                    file: "${env.EXT_STATE_DIR}\\${name}_tag.txt",
                    text: tag
                )
                echo "📌 Тег '${tag}' для '${name}' записан в state."
            }
        }
    }
}



    }

    post {
        always {
            script {
                withCredentials([usernamePassword(credentialsId: params.RAC_CRED,
                                                  usernameVariable: 'RAC_USER',
                                                  passwordVariable: 'RAC_PASS')]) {
                    utils.unlockSessions(params.SERVER_1C, params.IB_NAME,
                                         RAC_USER, RAC_PASS)
                }
            }
        }
        success {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN,
                    env.TELEGRAM_CHAT_ID,
                    "✅ Обновление PROD (${params.IB_NAME}) по тегам завершено", true)
            }
        }
        failure {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN,
                    env.TELEGRAM_CHAT_ID,
                    "❌ Ошибка обновления PROD (${params.IB_NAME}) по тегам", false)
            }
        }
    }
}
