// ========================================================================
//      JENKINS PIPELINE: ОБНОВЛЕНИЕ PRE-PROD ИЗ DEVELOP + PROD DATA
// ========================================================================
//
// ЦЕЛЬ:
// 1. Собрать актуальный CF из ветки develop.
// 2. Сделать бэкап PROD базы.
// 3. Восстановить этот бэкап в PRE-PROD.
// 4. Накатить собранный CF на PRE-PROD.
//
// ИТОГ: Pre-Prod база с актуальными данными из Prod и свежим кодом из develop.
// ========================================================================

library '1c-utils@master'
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    parameters {
        string(name: 'SHARED_BACKUP_PATH', defaultValue: '\\\\opl-dc01-sqlc3\\backup_base\\BACKUP\\ERP\\SHARED', description: 'Сетевой путь для бэкапа, доступный обоим SQL серверам')
        string(name: 'LAST_RELEASE_TASKS_FILE', defaultValue: 'D:\\DevOps\\deployment_state\\ERP\\last_release_tasks.txt', description: 'Файл со списком задач релиза')
    }

    environment {
        // Формируем имя файла бэкапа один раз, чтобы использовать везде
        BACKUP_FILENAME = "ERP_Prod_Transfer_${new Date().format('yyyyMMdd_HHmmss')}.bak"
        FULL_BACKUP_PATH = "${params.SHARED_BACKUP_PATH}\\${BACKUP_FILENAME}"

        // Пути для сборки CF
        SRC_CF_PATH = "${WORKSPACE}\\src\\cf"
        OUTPUT_CF_FILE = "${WORKSPACE}\\build\\Configuration_develop.cf"
        
        // Переменная для хранения текста ошибки из 1С
        DB_HEALTH_CHECK_ERROR = ""

        FIX_DELETE_EPF = "${WORKSPACE}\\tools\\MRS_УдалениеИсправлений.epf"
        CHECK_DB_EPF = "${WORKSPACE}\\tools\\MRS_ПроверкаБД.epf"
        CHECK_EXT_APPLICABILITY_EPF = "${WORKSPACE}\\tools\\MRS_ПроверкаПрименимостиРасширений.epf"
    }

    stages {
        // -----------------------------------------------------------------
        // Уведомление (старт)
        // -----------------------------------------------------------------
        stage('Notify Start') {
            steps {
                script {
                    utils.telegram_send_message(
                        env.TELEGRAM_CHAT_TOKEN,
                        env.TELEGRAM_CHAT_ID,
                        "🚀 Запущено обновление PRE-PROD (${params.IB_PREPROD})\n" +
                        "Ветка: ${params.GIT_BRANCH}\n" +
                        "Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        true
                    )
                }
           }
        }

        // -----------------------------------------------------------------
        // 1. Сборка CF из Git (develop)
        // -----------------------------------------------------------------
        stage('Checkout & Build CF') {
            steps {
                script {
                    cleanWs()

                    echo "Checkout ветки ${params.GIT_BRANCH}..."
                    withCredentials([usernamePassword(
                        credentialsId: 'token',
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_TOKEN'
                    )]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                        utils.cmd("git clone --branch ${params.GIT_BRANCH} --single-branch ${remoteUrl} .", env.WORKSPACE)
                    }

                    echo "Сборка конфигурации..."
                    utils.compileCF_to_file_safe(env.SRC_CF_PATH, env.OUTPUT_CF_FILE)
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. Бэкап Production базы
        // -----------------------------------------------------------------
        stage('Backup Production DB') {
            steps {
                script {
                    echo "Создание бэкапа базы ${params.DB_PROD} на сервере ${params.SQL_PROD_SERVER}..."
                    echo "Путь: ${env.FULL_BACKUP_PATH}"

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlBackupToFile(
                            params.SQL_PROD_SERVER,
                            params.DB_PROD,
                            env.FULL_BACKUP_PATH,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3. Восстановление в Pre-Production
        // -----------------------------------------------------------------
        stage('Restore to Pre-Prod') {
            steps {
                script {
                    echo "Восстановление базы ${params.DB_PREPROD} на сервере ${params.SQL_PREPROD_SERVER}..."
                    echo "Источник: ${env.FULL_BACKUP_PATH}"

                    // Для надежности блокируем сеансы 1С перед restore
                    try {
                        withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                            // Игнорируем ошибки блокировки — база может быть недоступна, важно попытаться
                            utils.lockSessions(params.SERVER_1C_PREPROD, params.IB_PREPROD, RAC_USER, RAC_PASS, "ОбновлениеКонфигурации")
                        }
                    } catch (e) {
                        echo "⚠️ Не удалось заблокировать сеансы 1С (возможно, база недоступна). Продолжаем восстановление SQL."
                    }

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlRestore(
                            params.SQL_PREPROD_SERVER,
                            params.DB_PREPROD,
                            env.FULL_BACKUP_PATH,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3.5. Удаление Fix-расширений
        // -----------------------------------------------------------------
        stage('Delete Fix Extensions') {
            steps {
                script {
                    if (!fileExists(params.LAST_RELEASE_TASKS_FILE)) {
                        echo "Файл задач релиза не найден: ${params.LAST_RELEASE_TASKS_FILE}. Пропускаем удаление."
                        return
                    }

                    def taskKeys = readFile(file: params.LAST_RELEASE_TASKS_FILE, encoding: 'UTF-8')
                        .readLines()
                        .collect { it.trim() }
                        .findAll { it }

                    if (taskKeys.isEmpty()) {
                        echo "Файл задач пустой. Удаление fix-расширений пропускаем."
                        return
                    }

                    if (!fileExists(env.FIX_DELETE_EPF)) {
                        error "Не найдена внешняя обработка: ${env.FIX_DELETE_EPF}"
                    }

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        // Ждём, пока SQL база начнёт отвечать после RESTORE
                        utils.waitSqlReady(params.SQL_PREPROD_SERVER, params.DB_PREPROD, SQL_USER, SQL_PASS, 60, 5)

                        // Небольшой прогрев базы после RESTORE перед подключением 1С
                        int warmupWaitSec = 30
                        echo "⏳ Прогрев после RESTORE: ${warmupWaitSec}s"
                        sleep time: warmupWaitSec, unit: 'SECONDS'

                        utils.deleteFixExtensions(
                            env.FIX_DELETE_EPF,
                            params.v8version,
                            params.SERVER_1C_PREPROD,
                            params.DB_PREPROD,
                            SQL_USER,
                            SQL_PASS,
                            "ОбновлениеКонфигурации"
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 4. Накатывание CF на Pre-Production
        // -----------------------------------------------------------------
        stage('Update Pre-Prod Config') {
            steps {
                script {
                    echo "Накатываем собранный CF на ${params.IB_PREPROD}..."

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.updateDB_preprod_vrunner_resilient_after_restore(
                            env.OUTPUT_CF_FILE,
                            params.SERVER_1C_PREPROD,
                            params.SQL_PREPROD_SERVER,
                            params.IB_PREPROD,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

    
        // -----------------------------------------------------------------
        // 4.5. Проверка работоспособности базы
        // -----------------------------------------------------------------
        stage('Check DB Health') {
            steps {
                script {
                    if (!fileExists(env.CHECK_DB_EPF)) {
                        echo "⚠️ Обработка проверки БД не найдена: ${env.CHECK_DB_EPF}. Пропускаем."
                    } else {
                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_PREPROD_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.checkDbHealth(
                                env.CHECK_DB_EPF,
                                params.v8version,
                                params.SERVER_1C_PREPROD,
                                params.DB_PREPROD,
                                SQL_USER,
                                SQL_PASS,
                                "ОбновлениеКонфигурации"
                            )
                        }
                    }
                }
            }
        }


        // -----------------------------------------------------------------
        // 4.6. Проверка применимости расширений
        // -----------------------------------------------------------------
        stage('Check Extensions Applicability') {
            steps {
                script {
                    if (!fileExists(params.CHECK_EXT_APPLICABILITY_EPF)) {
                        echo "⚠️ Обработка проверки применимости расширений не найдена: ${env.CHECK_EXT_APPLICABILITY_EPF}. Пропускаем."
                    } else {
                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_PREPROD_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.checkExtensionsApplicability(
                                params.CHECK_EXT_APPLICABILITY_EPF,
                                params.v8version,
                                params.SERVER_1C_PREPROD,
                                params.DB_PREPROD,
                                SQL_USER,
                                SQL_PASS,
                                "ОбновлениеКонфигурации"
                            )
                        }
                    }
                }
            }
        }
        
        // -----------------------------------------------------------------
        // 5. Очистка логов (Shrink)
        // -----------------------------------------------------------------
        stage('Shrink Pre-Prod Log') {
            steps {
                script {
                    echo "Очистка лога транзакций на ${params.IB_PREPROD}..."
                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlShrinkLog(
                            params.SQL_PREPROD_SERVER,
                            params.DB_PREPROD,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // Пытаемся разблокировать сеансы
                withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                    utils.unlockSessions(params.SERVER_1C_PREPROD, params.IB_PREPROD, RAC_USER, RAC_PASS)
                }

                // Очистка бэкакапа (чтобы не забивать место)
                def backupFile = env.FULL_BACKUP_PATH
                if (fileExists(backupFile)) {
                    bat "del /Q \"${backupFile}\""
                }
            }
        }
        success {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Обновление PRE-PROD успешно завершено!", true)
            }
        }
        failure {
        script {
            def failMessage = "Ошибка обновления PRE-PROD"

            if (fileExists("extensions_applicability_error.txt")) {
                def errMsg = readFile(file: "extensions_applicability_error.txt", encoding: "UTF-8").trim()
                if (errMsg) {
                    failMessage += "\n\n⚠️ **Ошибка применимости расширений:**\n`" + errMsg + "`"
                }
            } else if (fileExists("db_health_error.txt")) {
                def errMsg = readFile(file: "db_health_error.txt", encoding: "UTF-8").trim()
                if (errMsg) {
                    failMessage += "\n\n⚠️ **Ошибка при проверке базы (1С):**\n`" + errMsg + "`"
                }
            } else if (env.DB_HEALTH_CHECK_ERROR) {
                failMessage += "\n\n⚠️ **Ошибка при проверке базы (1С):**\n`" + env.DB_HEALTH_CHECK_ERROR + "`"
            }

            utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, failMessage, false)
        }
    }
    }
}
