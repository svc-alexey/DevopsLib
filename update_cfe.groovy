// ШАБЛОН: ПОСЛЕДОВАТЕЛЬНОЕ РАЗВЕРТЫВАНИЕ РАСШИРЕНИЯ
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн НЕ запускается сам. Его вызывает пайплайн-оркестратор.
// Он НЕ захватывает блокировку, а работает под уже существующей.
// Он получает на вход имя среды (TARGET_ENVIRONMENT) и обновляет на ней
// свой компонент (расширение).
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); }
    
    // [!! ИЗМЕНЕНИЕ !!] Триггеры cron ПОЛНОСТЬЮ УДАЛЕНЫ
    
    parameters {
        // [!! НАСТРОЙКА !!] Эти параметры нужно будет переопределить в настройках КАЖДОГО Job'а, созданного из этого шаблона
        string(name: 'EXTENSION_NAME', defaultValue: 'YourExtensionName', description: 'Техническое имя расширения')
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/to/repo.git', description: 'URL Git-репозитория этого расширения (без https://)')
        
        // Этот параметр передается из пайплайна-оркестратора
        string(name: 'TARGET_ENVIRONMENT', defaultValue: '', description: 'Целевая среда (передается из оркестратора)')
    }
    
    stages {
        // [!! ИЗМЕНЕНИЕ !!] Этап захвата блокировки ПОЛНОСТЬЮ УДАЛЕН

        stage('Check and Deploy Extension') {
            steps {
                script {
                    if (params.TARGET_ENVIRONMENT.trim().isEmpty()) {
                        error("Этот пайплайн не предназначен для ручного запуска без указания TARGET_ENVIRONMENT.")
                    }
                    
                    echo "--- Начало обновления расширения '${params.EXTENSION_NAME}' для среды ${params.TARGET_ENVIRONMENT} ---"
                    env.TAG_TO_DEPLOY_EXT = null // Сбрасываем переменную

                    // --- 1. Сопоставление среды с параметрами подключения ---
                    def envConfig = [:]
                    switch (params.TARGET_ENVIRONMENT) {
                        case 'PROD_FINANCE':
                            envConfig.SERVER_1C = 'prod-cluster-01.mycompany.com'; envConfig.DATABASE_NAME = 'ERP_Finance';
                            envConfig.IC_CREDS_ID = 'ic-user-pass-finance-prod'; envConfig.SQL_CREDS_ID = 'sql-auth-finance-prod';
                            envConfig.RAC_CREDS_ID = 'cluster-admin-prod';
                            break
                        case 'PROD_LOGISTICS':
                            envConfig.SERVER_1C = 'prod-cluster-01.mycompany.com'; envConfig.DATABASE_NAME = 'ERP_Logistics';
                            envConfig.IC_CREDS_ID = 'ic-user-pass-logistics-prod'; envConfig.SQL_CREDS_ID = 'sql-auth-logistics-prod';
                            envConfig.RAC_CREDS_ID = 'cluster-admin-prod';
                            break
                        // [!! НАСТРОЙКА !!] Добавьте 'case' для каждой вашей базы...
                        default:
                            error "Неизвестная целевая среда: ${params.TARGET_ENVIRONMENT}"
                    }
                    
                    // --- 2. Поиск новой версии ---
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        def latestTag = bat(script: "git ls-remote --tags --sort=-v:refname ${remoteUrl} \"refs/tags/build-${params.EXTENSION_NAME}-*\" | findstr /V \"{}\" | findstr /R /C:\"refs/tags/build-.*\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"", returnStdout: true).trim()
                        
                        if (latestTag.isEmpty()) {
                            echo "Не найдено тегов для расширения '${params.EXTENSION_NAME}'. Пропускаем обновление."; return
                        }
                        
                        def lastDeployedTagFile = "C:\\Jenkins\\deployment_state\\${params.EXTENSION_NAME}_${params.TARGET_ENVIRONMENT}_last_tag.txt"
                        def lastDeployedTag = fileExists(lastDeployedTagFile) ? readFile(lastDeployedTagFile).trim() : ""
                        
                        echo "Последний доступный тег: ${latestTag}"; echo "Последний развернутый тег: ${lastDeployedTag}"
                        
                        if (latestTag != lastDeployedTag) {
                            echo "Обнаружена новая версия для развертывания: ${latestTag}"
                            env.TAG_TO_DEPLOY_EXT = latestTag
                        } else {
                            echo "Новых версий расширения '${params.EXTENSION_NAME}' для развертывания нет."
                        }
                    }

                    // --- 3. Развертывание (если нужно) ---
                    if (env.TAG_TO_DEPLOY_EXT) {
                        cleanWs()
                        def buildNumber = env.TAG_TO_DEPLOY_EXT.split('-b').last()
                        def artifactFileName = "${params.EXTENSION_NAME}-${env.TAG_TO_DEPLOY_EXT}.cfe"
                        
                        // [!! НАСТРОЙКА !!] Убедитесь, что имя Build Job'а соответствует этому шаблону
                        def buildJobName = "ERP-Extension-${params.EXTENSION_NAME}-Build"
                        
                        copyArtifacts(projectName: buildJobName, selector: specific(buildNumber), filter: "build/${artifactFileName}", target: '.')
                        
                        withCredentials([
                            usernamePassword(credentialsId: envConfig.RAC_CREDS_ID, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                            usernamePassword(credentialsId: envConfig.IC_CREDS_ID, usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS')
                        ]) {
                            def ras = "${envConfig.SERVER_1C}:${env.CLUSTER_PORT ?: '1545'}"
                            
                            // [!!! ВАЖНО !!!] Используйте вашу реальную команду для установки/обновления расширения из .cfe файла.
                            def cfePath = "${env.WORKSPACE}\\${artifactFileName}"
                            def rcUpdate = utils.cmd("\"C:\\Program Files\\1cv8\\8.3.26.1540\\bin\\1cv8.exe\" DESIGNER /S\"${ras}\\${envConfig.DATABASE_NAME}\" /N\"${IC_USER}\" /P\"${IC_PASS}\" /LoadCfg \"${cfePath}\" /UpdateDBCfg -force")
                            
                            if (rcUpdate != 0) {
                                error("КРИТИЧЕСКАЯ ОШИБКА: Обновление расширения '${params.EXTENSION_NAME}' на '${params.TARGET_ENVIRONMENT}' провалилось!")
                            }
                            
                            def lastDeployedTagFile = "C:\\Jenkins\\deployment_state\\${params.EXTENSION_NAME}_${params.TARGET_ENVIRONMENT}_last_tag.txt"
                            writeFile(file: lastDeployedTagFile, text: env.TAG_TO_DEPLOY_EXT)
                        }
                    }
                }
            }
        }
    }
    
}