// ========================================================================
// ШАБЛОН: РАЗВЕРТЫВАНИЕ РАСШИРЕНИЯ ПО РАСПИСАНИЮ
// ========================================================================
// ТРИГГЕР: Запускается СТРОГО ПО РАСПИСАНИЮ в технологическое окно.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds() }
    
    // --- Параметры, специфичные для этого расширения ---
    parameters {
        string(name: 'EXTENSION_NAME', defaultValue: 'YourExtensionName', description: 'Техническое имя расширения')
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/to/repo.git', description: 'URL Git-репозитория этого расширения (без https://)')
    }

    // --- Запуск по расписанию (cron) ---
    triggers {
        cron('H 2 * * 6') // Пример: Каждую субботу в 2 часа ночи
    }
    
    // --- Путь к файлу, где хранится имя последнего установленного тега ДЛЯ ЭТОГО РАСШИРЕНИЯ ---
    // ВАЖНО: Для каждого расширения должен быть свой файл!
    // Мы определяем его здесь, чтобы можно было использовать параметр EXTENSION_NAME
    def properties(Map config) {
        // Устанавливаем путь к файлу состояния как свойство пайплайна
        properties([
            buildDiscarder(logRotator(numToKeepStr: '10')),
            parameters(config.parameters),
            pipelineTriggers(config.triggers)
        ])
        env.LAST_DEPLOYED_TAG_FILE = "C:\\Jenkins\\deployment_state\\erp_extension_${config.parameters[0].defaultValue}_last_tag.txt"
    }

    stages {
        // --- ЭТАП 1: Определение версии для развертывания ---
        stage('Check for New Version') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        env.GIT_AUTH_URL = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                    }

                    // Получаем имя самого последнего тега сборки из Git для этого расширения
                    def latestTag = bat(
                        script: "git ls-remote --tags --sort=-v:refname ${env.GIT_AUTH_URL} \"refs/tags/build-${params.EXTENSION_NAME}-*\" | findstr /V \"{}\" | findstr /R /C:\"refs/tags/build-.*\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"",
                        returnStdout: true
                    ).trim()

                    if (latestTag.isEmpty()) {
                        echo "Не найдено ни одного тега сборки для расширения '${params.EXTENSION_NAME}'. Пропускаем."
                        currentBuild.result = 'NOT_BUILT'; return
                    }
                    
                    def lastDeployedTag = fileExists(env.LAST_DEPLOYED_TAG_FILE) ? readFile(env.LAST_DEPLOYED_TAG_FILE).trim() : ""
                    echo "Последний доступный тег: ${latestTag}"; echo "Последний развернутый тег: ${lastDeployedTag}"
                    
                    if (latestTag == lastDeployedTag) {
                        echo "Новых версий для развертывания нет."; currentBuild.result = 'NOT_BUILT'; return
                    }
                    
                    env.TAG_TO_DEPLOY = latestTag
                }
            }
        }
        
        // --- ЭТАП 2: Развертывание новой версии ---
        stage('Deploy New Version') {
            steps {
                script {
                    echo "Начинаем развертывание расширения '${params.EXTENSION_NAME}' версии: ${env.TAG_TO_DEPLOY}"
                    
                    cleanWs()
                    utils.cmd("git clone --branch ${env.TAG_TO_DEPLOY} --single-branch ${env.GIT_AUTH_URL} .", env.WORKSPACE)

                    withCredentials([
                        usernamePassword(credentialsId: 'cluster-admin', usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                        usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS'),
                        usernamePassword(credentialsId: 'ic-user-pass', usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS'),
                        usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS')
                    ]) {
                        // --- Подготовка ---
                        def ras = "${env.server1c}:${env.CLUSTER_PORT ?: '1545'}"; def db = env.database
                        env.UCCODE = "PROD-EXT-${params.EXTENSION_NAME}-${env.TAG_TO_DEPLOY}"
                        
                        // --- Блокировка, Бэкап, Обновление, Разблокировка ---
                        utils.cmd("vrunner session lock --ras ${ras} --db ${db} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\"")
                        env.__LOCK_ACTIVE = '1'
                        utils.mssqlBackup(env.server1c, env.database, env.BACKUP_DIR, SQL_USER, SQL_PASS)

                        env.IC_USER = IC_USER; env.IC_PASS = IC_PASS;
                        env.DB_USER = DB_USER; env.DB_PASS = DB_PASS;
                        def rc = utils.buildCFE(params.EXTENSION_NAME, env.WORKSPACE, env.UCCODE, env.v8_version ?: '8.3.26.1540', false)
                        if (rc != 0) error("КРИТИЧЕСКАЯ ОШИБКА: Обновление расширения '${params.EXTENSION_NAME}' на проде провалилось!")
                        
                        // Если обновление прошло успешно, записываем новый тег в файл состояния
                        writeFile(file: env.LAST_DEPLOYED_TAG_FILE, text: env.TAG_TO_DEPLOY)
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                if (env.__LOCK_ACTIVE == '1') {
                    withCredentials([usernamePassword(credentialsId: 'cluster-admin', usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                        def ras = "${env.server1c}:${env.CLUSTER_PORT ?: '1545'}"; def db = env.database
                        utils.cmd("vrunner session unlock --ras ${ras} --db ${db} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\" || echo OK")
                    }
                }
            }
        }
        success {
            script { 
                if (env.TAG_TO_DEPLOY) {
                    utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Расширение '${params.EXTENSION_NAME}' на ПРОД успешно обновлено до версии ${env.TAG_TO_DEPLOY}.", true)
                }
            }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "КРИТИЧЕСКАЯ ОШИБКА обновления расширения '${params.EXTENSION_NAME}' на ПРОД!", false) }
        }
    }