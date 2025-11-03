// ========================================================================
//    ПАЙПЛАЙН-ОРКЕСТРАТОР ДЛЯ КОНКРЕТНОЙ СРЕДЫ (например, PROD_FINANCE)
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн отвечает за ПОЛНОЕ обновление ОДНОЙ КОНКРЕТНОЙ среды.
// Он запускается по своему расписанию и последовательно обновляет основную
// конфигурацию и все расширения из файла-манифеста 'extension-prod.json'.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

// [!! НАСТРОЙКА !!] Определите здесь все параметры для ЭТОЙ КОНКРЕТНОЙ СРЕДЫ
// Эти переменные будут использоваться во всем пайплайне.
// Создайте по одному такому файлу на каждую вашу базу (PROD_FINANCE, PROD_LOGISTICS и т.д.)
def ENV_CONFIG = [
    TARGET_ENVIRONMENT: 'PROD_FINANCE', // Имя среды для логов и файлов состояния
    SERVER_1C:          'prod-cluster-01.mycompany.com',
    DATABASE_NAME:      'ERP_Finance',
    SERVER_BD:          'prod-sql-01.mycompany.com', // Сервер СУБД, если отличается
    BACKUP_DIR:         '\\\\prod-storage\\backups\\finance',
    // --- ID credentials, которые нужно заранее создать в Jenkins ---
    IC_CREDS_ID:        'ic-user-pass-finance-prod',
    SQL_CREDS_ID:       'sql-auth-finance-prod',
    RAC_CREDS_ID:       'cluster-admin-prod'
]

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds() }
    
    triggers {
        // [!! НАСТРОЙКА !!] Укажите технологическое окно для ЭТОЙ СРЕДЫ
        cron('H 2 * * 6') 
    }
    
    stages {
        stage('Acquire Global Lock') {
            steps {
                lock('PROD_DATABASE_LOCK') {
                    script {
                        echo "Глобальная блокировка PROD_DATABASE_LOCK захвачена для среды ${ENV_CONFIG.TARGET_ENVIRONMENT}."
                    }
                }
            }
        }

        stage('Deploy Main Configuration') {
            steps {
                script {
                    echo "--- Начало обновления Основной Конфигурации для среды ${ENV_CONFIG.TARGET_ENVIRONMENT} ---"
                    env.TAG_TO_DEPLOY_MAIN = null // Сбрасываем переменную
                    
                    // --- 1. Поиск новой версии ---
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                        def latestTag = bat(script: "git ls-remote --tags --sort=-v:refname ${remoteUrl} \"refs/tags/build-main-*\" | findstr /V \"{}\" | findstr /R /C:\"refs/tags/build-.*\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"", returnStdout: true).trim()
                        
                        if (latestTag.isEmpty()) {
                            echo "Не найдено ни одного тега сборки. Пропускаем обновление основной конфигурации."
                            return // Переходим к следующему stage
                        }
                        
                        def lastDeployedTagFile = "C:\\Jenkins\\deployment_state\\erp_main_config_${ENV_CONFIG.TARGET_ENVIRONMENT}_last_tag.txt"
                        def lastDeployedTag = fileExists(lastDeployedTagFile) ? readFile(lastDeployedTagFile).trim() : ""
                        
                        echo "Последний доступный тег: ${latestTag}"; echo "Последний развернутый тег: ${lastDeployedTag}"
                        
                        if (latestTag != lastDeployedTag) {
                            echo "Обнаружена новая версия для развертывания: ${latestTag}"
                            env.TAG_TO_DEPLOY_MAIN = latestTag
                        } else {
                            echo "Новых версий основной конфигурации для развертывания нет."
                        }
                    }

                    // --- 2. Развертывание (если нужно) ---
                    if (env.TAG_TO_DEPLOY_MAIN) {
                        cleanWs() // Очищаем workspace для чистоты
                        def buildNumber = env.TAG_TO_DEPLOY_MAIN.split('-b').last()
                        def artifactFileName = "Configuration-${env.TAG_TO_DEPLOY_MAIN}.cf"
                        
                        // [!! НАСТРОЙКА !!] Укажите ТОЧНОЕ имя вашего Build Job'а для основной конфигурации
                        copyArtifacts(projectName: 'ERP-Main-Config-Build', selector: specific(buildNumber), filter: "build/${artifactFileName}", target: '.')
                        
                        withCredentials([
                            usernamePassword(credentialsId: ENV_CONFIG.RAC_CREDS_ID, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                            usernamePassword(credentialsId: ENV_CONFIG.SQL_CREDS_ID, usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS'),
                            usernamePassword(credentialsId: ENV_CONFIG.IC_CREDS_ID, usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS')
                        ]) {
                            def ras = "${ENV_CONFIG.SERVER_1C}:${env.CLUSTER_PORT ?: '1545'}"
                            env.UCCODE = "PROD-${env.TAG_TO_DEPLOY_MAIN}"

                            utils.cmd("vrunner session lock --ras ${ras} --db ${ENV_CONFIG.DATABASE_NAME} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\"")
                            env.__LOCK_ACTIVE = '1'
                            
                            utils.mssqlBackup(ENV_CONFIG.SERVER_BD, ENV_CONFIG.DATABASE_NAME, ENV_CONFIG.BACKUP_DIR, SQL_USER, SQL_PASS)
                            
                            def cfPath = "${env.WORKSPACE}\\${artifactFileName}"
                            def rcUpdate = utils.cmd("vrunner updatedb --cffile \"${cfPath}\" --v1c /S${ras}/${ENV_CONFIG.DATABASE_NAME} /N${IC_USER} /P${IC_PASS}")
                            
                            if (rcUpdate != 0) {
                                error("КРИТИЧЕСКАЯ ОШИБКА: Обновление основной конфигурации на среде ${ENV_CONFIG.TARGET_ENVIRONMENT} завершилось неудачно!")
                            }
                            
                            def lastDeployedTagFile = "C:\\Jenkins\\deployment_state\\erp_main_config_${ENV_CONFIG.TARGET_ENVIRONMENT}_last_tag.txt"
                            writeFile(file: lastDeployedTagFile, text: env.TAG_TO_DEPLOY_MAIN)
                        }
                    }
                }
            }
        }
        
        stage('Deploy Extensions from Manifest') {
            steps {
                script {
                    echo "--- Запуск последовательного развертывания расширений для среды ${ENV_CONFIG.TARGET_ENVIRONMENT} ---"
                    
                    // Мы не можем читать файл `extension-prod.json` из workspace предыдущего шага,
                    // так как он мог быть очищен или содержать неактуальную версию.
                    // Поэтому мы снова клонируем репозиторий основной конфигурации.
                    dir('main_config_checkout') {
                        cleanWs()
                        withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                            def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                            utils.cmd("git clone --branch develop --single-branch ${remoteUrl} .")
                        }
                    }
                    
                    def manifestFile = "${env.WORKSPACE}\\main_config_checkout\\extension-prod.json"
                    
                    if (!fileExists(manifestFile)) {
                        echo "Файл-манифест 'extension-prod.json' не найден. Пропускаем развертывание расширений."
                    } else {
                        def jsonContent = readFile(file: manifestFile, encoding: 'UTF-8')
                        def extensions = utils.parseExtensionsJson(jsonContent)

                        if (extensions.isEmpty()) {
                            echo "Список расширений в 'extension-prod.json' пуст."
                        } else {
                            echo "Обнаружено ${extensions.size()} расширений для обновления."

                            for (ext in extensions) {
                                // [!! НАСТРОЙКА !!] Убедитесь, что имена Deploy Job'ов соответствуют этому шаблону
                                def jobName = "ERP-Extension-${ext.name}-Deploy-Sequential"
                                
                                echo "--- Запуск дочернего пайплайна: ${jobName} ---"
                                
                                try {
                                    build job: jobName,
                                          wait: true,
                                          parameters: [
                                              string(name: 'TARGET_ENVIRONMENT', value: ENV_CONFIG.TARGET_ENVIRONMENT)
                                          ]
                                } catch (Exception e) {
                                    echo "ПРЕДУПРЕЖДЕНИЕ: Не удалось запустить Job '${jobName}'. Проверьте, что он существует. ${e.message}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                if (env.__LOCK_ACTIVE == '1') {
                     withCredentials([usernamePassword(credentialsId: ENV_CONFIG.RAC_CREDS_ID, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                        def ras = "${ENV_CONFIG.SERVER_1C}:${env.CLUSTER_PORT ?: '1545'}"
                        utils.cmd("vrunner session unlock --ras ${ras} --db ${ENV_CONFIG.DATABASE_NAME} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\" || echo OK")
                    }
                }
            }
        }
        success {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "ПОЛНОЕ обновление на среде '${ENV_CONFIG.TARGET_ENVIRONMENT}' успешно завершено.", true)
            }
        }
        failure {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "КРИТИЧЕСКАЯ ОШИБКА во время полного обновления на среде '${ENV_CONFIG.TARGET_ENVIRONMENT}'!", false)
            }
        }
    }
}