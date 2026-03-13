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
        string(name: 'FIX_DELETE_EPF', defaultValue: 'D:\\DevOps\\ERP\\MRS_УдалениеИсправлений.epf', description: 'Путь к внешней обработке удаления fix-расширений')
        string(name: 'CHECK_DB_EPF', defaultValue: 'D:\\DevOps\\ERP\\MRS_ПроверкаБД.epf', description: 'Путь к внешней обработке проверки БД')
        string(name: 'V8_VERSION', defaultValue: '8.3.27.1859', description: 'Версия платформы 1С для vrunner')
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
    }

    stages {
        
        // -----------------------------------------------------------------
        // 4.5. Проверка работоспособности базы
        // -----------------------------------------------------------------
        stage('Check DB Health') {
            steps {
                script {
                    if (!fileExists(params.CHECK_DB_EPF)) {
                        echo "⚠️ Обработка проверки БД не найдена: ${params.CHECK_DB_EPF}. Пропускаем."
                    } else {
                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_PREPROD_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.checkDbHealth(
                                params.CHECK_DB_EPF,
                                params.V8_VERSION,
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
                if (fileExists("db_health_error.txt")) {
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
