// ========================================================================
// JENKINS PIPELINE: РАЗВЕРТЫВАНИЕ ОСНОВНОЙ КОНФИГУРАЦИИ ПО РАСПИСАНИЮ
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн находит последнюю готовую к установке версию (Git-тег),
// и если она новая, обновляет рабочую базу данных.
//
// ТРИГГЕР: Запускается СТРОГО ПО РАСПИСАНИЮ в технологическое окно.
// Пример: 'H 2 * * 6' - Каждую субботу в 2 часа ночи (с небольшим разбросом).
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

// --- Путь к файлу, где хранится имя последнего установленного тега ---
// ВАЖНО: Этот путь должен быть доступен агенту Jenkins.
// Укажите абсолютный путь вне папки workspace.
def LAST_DEPLOYED_TAG_FILE = "C:\\Jenkins\\deployment_state\\erp_main_config_last_tag.txt"

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds() }
    
    // --- Запуск по расписанию (cron) ---
    triggers {
        // H(0-2) - час ночи, H(0-59) - минуты, * - день, * - месяц, 6 - суббота
        cron('H 2 * * 6') 
    }
    
    stages {
        // --- ЭТАП 1: Определение версии для развертывания ---
        stage('Check for New Version') {
            steps {
                script {
                    // Устанавливаем URL с токеном для доступа к репозиторию
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        env.GIT_AUTH_URL = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                    }

                    // Получаем имя самого последнего тега сборки из Git
                    // Команда вернет, например, 'build-main-2025.11.08-0130-b58'
                    def latestTag = bat(
                        script: "git ls-remote --tags --sort=-v:refname ${env.GIT_AUTH_URL} \"refs/tags/build-main-*\" | findstr /V \"{}\" | findstr /R /C:\"refs/tags/build-.*\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"",
                        returnStdout: true
                    ).trim()

                    if (latestTag.isEmpty()) {
                        echo "Не найдено ни одного тега сборки. Пропускаем развертывание."
                        // Прерываем пайплайн, так как нечего развертывать
                        currentBuild.result = 'NOT_BUILT' 
                        return
                    }
                    
                    // Читаем имя тега, который был развернут в прошлый раз
                    def lastDeployedTag = ""
                    if (fileExists(LAST_DEPLOYED_TAG_FILE)) {
                        lastDeployedTag = readFile(LAST_DEPLOYED_TAG_FILE).trim()
                    }

                    echo "Последний доступный тег: ${latestTag}"
                    echo "Последний развернутый тег: ${lastDeployedTag}"
                    
                    // --- КЛЮЧЕВАЯ ЛОГИКА ---
                    // Если теги совпадают, значит, новых версий нет, и делать ничего не нужно.
                    if (latestTag == lastDeployedTag) {
                        echo "Новых версий для развертывания нет."
                        currentBuild.result = 'NOT_BUILT'
                        return
                    }
                    
                    // Если мы здесь, значит, есть новая версия. Сохраняем ее имя для следующего этапа.
                    env.TAG_TO_DEPLOY = latestTag
                }
            }
        }
        
        // --- ЭТАП 2: Развертывание новой версии ---
        stage('Deploy New Version') {
            steps {
                script {
                    echo "Начинаем развертывание новой версии: ${env.TAG_TO_DEPLOY}"
                    
                    // Скачиваем код именно этой версии
                    cleanWs()
                    utils.cmd("git clone --branch ${env.TAG_TO_DEPLOY} --single-branch ${env.GIT_AUTH_URL} .", env.WORKSPACE)

                    // Выполняем полный цикл обновления на проде
                    withCredentials([
                        usernamePassword(credentialsId: 'cluster-admin', usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                        usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS'),
                        usernamePassword(credentialsId: 'ic-user-pass', usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS'),
                        usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS')
                    ]) {
                        // --- Подготовка ---
                        def ras = "${env.server1c}:${env.CLUSTER_PORT ?: '1545'}"; def db = env.database
                        env.UCCODE = "PROD-${env.TAG_TO_DEPLOY}"

                        // --- Блокировка, Бэкап, Обновление, Разблокировка ---
                        utils.cmd("vrunner session lock --ras ${ras} --db ${db} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\"")
                        env.__LOCK_ACTIVE = '1'
                        utils.mssqlBackup(env.server1c, env.database, env.BACKUP_DIR, SQL_USER, SQL_PASS)
                        
                        env.IC_USER = IC_USER; env.IC_PASS = IC_PASS;
                        env.DB_USER = DB_USER; env.DB_PASS = DB_PASS;
                        def rcUpdate = utils.updatedb_ibcmd(env.WORKSPACE, env.UCCODE, env.v8_version ?: '8.3.26.1540')
                        if (rcUpdate != 0) {
                            error("КРИТИЧЕСКАЯ ОШИБКА: Обновление производственной БД завершилось неудачно!")
                        }
                        
                        // Если обновление прошло успешно, записываем новый тег в файл состояния
                        writeFile(file: LAST_DEPLOYED_TAG_FILE, text: env.TAG_TO_DEPLOY)
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                // Гарантированно разблокируем сеансы, даже если сборка упала
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
                if (env.TAG_TO_DEPLOY) { // Отправляем уведомление, только если было развертывание
                    utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Основная конфигурация на ПРОД успешно обновлена до версии ${env.TAG_TO_DEPLOY}.", true)
                }
            }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "КРИТИЧЕСКАЯ ОШИБКА обновления основной конфигурации на ПРОД!", false) }
        }
    }
}