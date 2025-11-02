// ========================================================================
// ШАБЛОН: РАЗВЕРТЫВАНИЕ РАСШИРЕНИЯ ИЗ АРТЕФАКТА ПО РАСПИСАНИЮ
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн запускается по нескольким расписаниям. При каждом запуске
// он определяет, для какой базы сейчас технологическое окно, и, если есть
// новая версия (тег), обновляет ИМЕННО ЭТУ БАЗУ.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); }
    
    parameters {
        string(name: 'EXTENSION_NAME', defaultValue: 'YourExtensionName', description: 'Техническое имя расширения')
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/to/repo.git', description: 'URL Git-репозитория этого расширения (без https://)')
    }

    triggers {
        cron('''
            # Тех. окно для Финансовой базы: Каждую субботу в 2:30 ночи
            H(30-59) 2 * * 6 % TARGET_ENVIRONMENT=PROD_FINANCE
            
            # Тех. окно для Логистической базы: Каждое воскресенье в 3:30 ночи
            H(30-59) 3 * * 7 % TARGET_ENVIRONMENT=PROD_LOGISTICS
        ''')
    }
    
    stages {
        stage('Acquire Lock') {
            steps {
                lock('PROD_DATABASE_LOCK') {
                    script {
                        echo "Блокировка PROD_DATABASE_LOCK успешно захвачена для обновления расширения."
                    }
                }
            }
        }

        stage('Check and Deploy') {
            steps {
                script {
                    // --- Сопоставление среды с параметрами подключения ---
                    def envConfig = [:]
                    switch (params.TARGET_ENVIRONMENT) {
                        case 'PROD_FINANCE':
                            envConfig.SERVER_1C = 'prod-cluster-01.mycompany.com'; envConfig.DATABASE_NAME = 'ERP_Finance';
                            envConfig.IC_CREDS_ID = 'ic-user-pass-finance-prod'; envConfig.SQL_CREDS_ID = 'sql-auth-finance-prod';
                            envConfig.RAC_CREDS_ID = 'cluster-admin-prod'; envConfig.BACKUP_DIR = '\\\\prod-storage\\backups\\finance'
                            break
                        case 'PROD_LOGISTICS':
                            envConfig.SERVER_1C = 'prod-cluster-01.mycompany.com'; envConfig.DATABASE_NAME = 'ERP_Logistics';
                            envConfig.IC_CREDS_ID = 'ic-user-pass-logistics-prod'; envConfig.SQL_CREDS_ID = 'sql-auth-logistics-prod';
                            envConfig.RAC_CREDS_ID = 'cluster-admin-prod'; envConfig.BACKUP_DIR = '\\\\prod-storage\\backups\\logistics'
                            break
                        default:
                            error "Неизвестная целевая среда: ${params.TARGET_ENVIRONMENT}"
                    }
                    
                    // --- Поиск новой версии ---
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        def latestTag = bat(script: "git ls-remote --tags --sort=-v:refname ${remoteUrl} \"refs/tags/build-${params.EXTENSION_NAME}-*\" | findstr /V \"{}\" | findstr /R /C:\"refs/tags/build-.*\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"", returnStdout: true).trim()
                        
                        if (latestTag.isEmpty()) {
                            echo "Не найдено тегов для расширения '${params.EXTENSION_NAME}'."; currentBuild.result = 'NOT_BUILT'; return
                        }
                        
                        def lastDeployedTagFile = "D:\\DevOps\\deployment_state\\${params.EXTENSION_NAME}_${params.TARGET_ENVIRONMENT}_last_tag.txt"
                        def lastDeployedTag = fileExists(lastDeployedTagFile) ? readFile(lastDeployedTagFile).trim() : ""
                        
                        echo "Проверка для среды: ${params.TARGET_ENVIRONMENT}"; echo "Последний доступный тег: ${latestTag}"; echo "Последний развернутый тег: ${lastDeployedTag}"
                        
                        if (latestTag == lastDeployedTag) {
                            echo "Новых версий для развертывания нет."; currentBuild.result = 'NOT_BUILT'; return
                        }
                        
                        env.TAG_TO_DEPLOY = latestTag
                    }

                    // --- Развертывание ---
                    echo "Начинаем развертывание версии '${env.TAG_TO_DEPLOY}' на среду '${params.TARGET_ENVIRONMENT}'"
                    cleanWs()
                    def buildNumber = env.TAG_TO_DEPLOY.split('-b').last()
                    def artifactFileName = "${params.EXTENSION_NAME}-${env.TAG_TO_DEPLOY}.cfe"
                    
                    copyArtifacts(projectName: "ERP-Extension-${params.EXTENSION_NAME}-Build", selector: specific(buildNumber), filter: "build/${artifactFileName}", target: '.')
                    
                    withCredentials([
                        usernamePassword(credentialsId: envConfig.RAC_CREDS_ID, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                        usernamePassword(credentialsId: envConfig.SQL_CREDS_ID, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS'),
                        usernamePassword(credentialsId: envConfig.IC_CREDS_ID, usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS')
                    ]) {
                        def ras = "${envConfig.SERVER_1C}:${env.CLUSTER_PORT ?: '1545'}"
                        env.UCCODE = "PROD-EXT-${params.EXTENSION_NAME}-${env.TAG_TO_DEPLOY}"
                        
                        utils.cmd("vrunner session lock --ras ${ras} --db ${envConfig.DATABASE_NAME} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\"")
                        env.__LOCK_ACTIVE = '1'
                        
                        utils.mssqlBackup(envConfig.SERVER_1C, envConfig.DATABASE_NAME, envConfig.BACKUP_DIR, SQL_USER, SQL_PASS)
                        
                        // Используем стандартный пакетный запуск для установки .cfe
                        def cfePath = "${env.WORKSPACE}\\${artifactFileName}"
                        def rcUpdate = utils.cmd("... команда пакетного запуска для установки расширения из файла ${cfePath} ...") // ВАМ НУЖНО БУДЕТ УТОЧНИТЬ ЭТУ КОМАНДУ
                        
                        if (rcUpdate != 0) { error("КРИТИЧЕСКАЯ ОШИБКА: Обновление расширения '${params.EXTENSION_NAME}' на '${params.TARGET_ENVIRONMENT}' провалилось!") }
                        
                        def lastDeployedTagFile = "D:\\DevOps\\deployment_state\\${params.EXTENSION_NAME}_${params.TARGET_ENVIRONMENT}_last_tag.txt"
                        writeFile(file: lastDeployedTagFile, text: env.TAG_TO_DEPLOY)
                    }
                }
            }
        }
    }
    
    post {
        always {
            // ... (блок post с разблокировкой сеансов) ...
        }
        success {
            script { 
                if (env.TAG_TO_DEPLOY) {
                    utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Расширение '${params.EXTENSION_NAME}' (${env.TAG_TO_DEPLOY}) успешно развернуто на '${params.TARGET_ENVIRONMENT}'.", true)
                }
            }
        }
        // ... (failure блок) ...
    }
}