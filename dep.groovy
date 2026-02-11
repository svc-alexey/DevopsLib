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
    agent { label 'OPL-DC01-1CPPD' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    parameters {
        string(name: 'SHARED_BACKUP_PATH', defaultValue: '\\\\opl-dc01-sqlc3\\backup_base\\BACKUP\\ERP\\SHARED', description: 'Сетевой путь для бэкапа, доступный обоим SQL серверам')
    }

    environment {
        // Формируем имя файла бэкапа один раз, чтобы использовать везде
        BACKUP_FILENAME = "ERP_Prod_Transfer_${new Date().format('yyyyMMdd_HHmmss')}.bak"
        FULL_BACKUP_PATH = "${params.SHARED_BACKUP_PATH}\\${BACKUP_FILENAME}"
        
        // Пути для сборки CF
        SRC_CF_PATH = "${WORKSPACE}\\src\\cf"
        OUTPUT_CF_FILE = "${WORKSPACE}\\build\\Configuration_develop.cf"
    }

    stages {

        // -----------------------------------------------------------------
        // 3. Восстановление в Pre-Production
        // -----------------------------------------------------------------
        stage('Restore to Pre-Prod') {
            steps {
                script {
                    
                    
                    try {
                         withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                            // Игнорируем ошибки блокировки, т.к. база может быть "битой" или выключенной, главное попытаться
                            utils.lockSessions(params.SERVER_1C_PREPROD, params.IB_PREPROD, RAC_USER, RAC_PASS, "Обновление конфигурации")
                        }
                    } catch (e) {
                        echo "⚠️ Не удалось заблокировать сеансы 1С (возможно, база недоступна). Продолжаем восстановление SQL."
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

                    // После восстановления базы из Prod, учетки SQL могут "поехать" (orphaned users),
                    // если логины на серверах отличаются. 
                    // Но мы используем SQL аутентификацию при обновлении.
                    // Главное, чтобы пользователь, под которым мы обновляем, имел права db_owner в восстановленной базе.
                    // Обычно пользователь 'sa' или системный админ имеет доступ везде.
                    // Если используется специфический юзер, его, возможно, придется чинить (sp_change_users_login).
                    // Будем считать, что права есть.

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.updateDB_via_ibcmd_or_vrunner(
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
                
                // Очистка бэкапа (опционально, чтобы не забивать место)
                // Если нужно хранить - закомментируйте
                /*
                def backupFile = env.FULL_BACKUP_PATH
                if (fileExists(backupFile)) {
                   // fileExists работает локально на агенте. 
                   // Если путь сетевой и агент его видит как локальный путь (mount) - сработает.
                   // Если это UNC путь, jenkins может не уметь его удалять через file operations.
                   // Можно попробовать через bat del
                   bat "del /Q \"${backupFile}\""
                }
                */
            }
        }
        //success {
            //script {
                //utils.telegram_send_message(
                //    env.TELEGRAM_CHAT_TOKEN,
                //    env.TELEGRAM_CHAT_ID,
                //    "✅ Обновление PRE-PROD успешно завершено!",
                //    true
                //)
            //}
        //}
        //failure {
        //    script {
                //utils.telegram_send_message(
                //    env.TELEGRAM_CHAT_TOKEN,
                //    env.TELEGRAM_CHAT_ID,
                //    "❌ Ошибка обновления PRE-PROD",
                //    false
                //)
        //    }
        //}
    }
}
