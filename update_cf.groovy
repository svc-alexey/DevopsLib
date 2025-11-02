// ========================================================================
// JENKINS PIPELINE: РАЗВЕРТЫВАНИЕ ОСНОВНОЙ КОНФИГУРАЦИИ ИЗ АРТЕФАКТА
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн находит последнюю версию (тег), скачивает
// соответствующий ГОТОВЫЙ .cf файл и обновляет им рабочую базу.
//
// ТРИГГЕР: Запускается по расписанию в технологическое окно.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

// --- Путь к файлу, где хранится имя последнего установленного тега ---
def LAST_DEPLOYED_TAG_FILE = "D:\\DevOps\\deployment_state\\erp_main_config_last_tag.txt"

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds() }
    triggers {
        // Пример: Каждую субботу в 2 часа ночи
        cron('H 2 * * 6') 
    }
    
    stages {
        // --- ЭТАП 0: Захват блокировки ---
        stage('Acquire Lock') {
            steps {
                // Ждем, пока ресурс PROD_DATABASE_LOCK не освободится,
                // чтобы гарантировать последовательное выполнение обновлений.
                lock('PROD_DATABASE_LOCK') {
                    script {
                        echo "Блокировка PROD_DATABASE_LOCK успешно захвачена."
                    }
                }
            }
        }

        stage('Check for New Version') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        env.GIT_AUTH_URL = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                    }

                    def latestTag = bat(
                        script: "git ls-remote --tags --sort=-v:refname ${env.GIT_AUTH_URL} \"refs/tags/build-main-*\" | findstr /V \"{}\" | findstr /R /C:\"refs/tags/build-.*\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"",
                        returnStdout: true
                    ).trim()

                    if (latestTag.isEmpty()) {
                        echo "Не найдено ни одного тега сборки. Пропускаем развертывание."
                        currentBuild.result = 'NOT_BUILT'; return
                    }
                    
                    def lastDeployedTag = fileExists(LAST_DEPLOYED_TAG_FILE) ? readFile(LAST_DEPLOYED_TAG_FILE).trim() : ""
                    echo "Последний доступный тег: ${latestTag}"; echo "Последний развернутый тег: ${lastDeployedTag}"
                    
                    if (latestTag == lastDeployedTag) {
                        echo "Новых версий для развертывания нет."; currentBuild.result = 'NOT_BUILT'; return
                    }
                    
                    env.TAG_TO_DEPLOY = latestTag
                }
            }
        }
        
        stage('Download and Deploy Artifact') {
            steps {
                script {
                    echo "Начинаем развертывание версии: ${env.TAG_TO_DEPLOY}"
                    
                    def buildNumber = env.TAG_TO_DEPLOY.split('-b').last()
                    if (buildNumber.isEmpty()) { error "Не удалось определить номер сборки из тега: ${env.TAG_TO_DEPLOY}" }
                    
                    def artifactFileName = "Configuration-${env.TAG_TO_DEPLOY}.cf"
                    echo "Скачивание артефакта '${artifactFileName}' из сборки #${buildNumber}..."
                    
                    copyArtifacts(
                        projectName: 'ERP-Main-Config-Build', // ИМЯ JOB'А, КОТОРЫЙ СОЗДАЕТ АРТЕФАКТЫ
                        selector: specific(buildNumber),
                        filter: "build/${artifactFileName}",
                        target: '.'
                    )
                    
                    withCredentials([
                        usernamePassword(credentialsId: 'cluster-admin', usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                        usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS'),
                        usernamePassword(credentialsId: 'ic-user-pass', usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS')
                    ]) {
                        def ras = "${env.server1c}:${env.CLUSTER_PORT ?: '1545'}"; def db = env.database
                        env.UCCODE = "PROD-${env.TAG_TO_DEPLOY}"

                        utils.cmd("vrunner session lock --ras ${ras} --db ${db} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\"")
                        env.__LOCK_ACTIVE = '1'
                        
                        utils.mssqlBackup(env.server1c, env.database, env.BACKUP_DIR, SQL_USER, SQL_PASS)
                        
                        def cfPath = "${env.WORKSPACE}\\${artifactFileName}"
                        def rcUpdate = utils.cmd("vrunner updatedb --cffile \"${cfPath}\" --v1c /S${ras}/${db} /N${IC_USER} /P${IC_PASS}")
                        
                        if (rcUpdate != 0) {
                            error("КРИТИЧЕСКАЯ ОШИБКА: Обновление производственной БД из файла ${artifactFileName} завершилось неудачно!")
                        }
                        
                        writeFile(file: LAST_DEPLOYED_TAG_FILE, text: env.TAG_TO_DEPLOY)
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
                    utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Основная конфигурация на ПРОД успешно обновлена до версии ${env.TAG_TO_DEPLOY}.", true)
                }
            }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "КРИТИЧЕСКАЯ ОШИБКА обновления основной конфигурации на ПРОД!", false) }
        }
    }
}