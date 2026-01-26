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
        string(name: 'GIT_BRANCH', defaultValue: 'develop', description: 'Ветка Git для сборки конфигурации')
        
        string(name: 'SQL_PROD_SERVER', defaultValue: 'prod-sql-server', description: 'SQL Server Production')
        string(name: 'DB_PROD', defaultValue: 'erp_prod', description: 'Имя базы данных Production')
        string(name: 'SQL_PROD_CRED', defaultValue: 'sql_prod_cred', description: 'ID credentials (login/pass) для Prod SQL')

        string(name: 'SQL_PREPROD_SERVER', defaultValue: 'preprod-sql-server', description: 'SQL Server Pre-Production')
        string(name: 'DB_PREPROD', defaultValue: 'erp_preprod', description: 'Имя базы данных Pre-Production')
        string(name: 'SQL_PREPROD_CRED', defaultValue: 'sql_preprod_cred', description: 'ID credentials (login/pass) для Pre-Prod SQL')

        string(name: 'SERVER_1C_PREPROD', defaultValue: 'preprod-1c-server', description: 'Сервер 1С Предприятия (Pre-Prod)')
        string(name: 'IB_PREPROD', defaultValue: 'erp_preprod', description: 'Имя ИБ в кластере 1С (Pre-Prod)')
        string(name: 'RAC_CRED', defaultValue: 'rac_cred', description: 'ID credentials (admin/pass) для RAC (администрирование кластера 1С)')

        string(name: 'SHARED_BACKUP_PATH', defaultValue: '\\\\nas\\backups\\transfer', description: 'Сетевой путь для бэкапа, доступный обоим SQL серверам')
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
        // Уведомление
        // -----------------------------------------------------------------
        stage('Notify Start') {
            steps {
                script {
                    utils.telegram_send_message(
                        env.TELEGRAM_CHAT_TOKEN,
                        env.TELEGRAM_CHAT_ID,
                        "🚀 Запущено обновление PRE-PROD (${params.IB_PREPROD})\nВетка: ${params.GIT_BRANCH}\nJob: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
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
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
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

                    // Перед восстановлением можно (и нужно) завершить сеансы 1С, если сервер запущен
                    // Но при restore with replace и kill connections sql сервер сам порвет соединения.
                    // Однако кластер 1С может "удивиться". 
                    // Хорошей практикой было бы заблокировать сеансы 1С на Pre-Prod, но это опционально,
                    // так как база все равно будет перезаписана на уровне SQL.
                    // Для надежности заблокируем, чтобы никто не сидел.
                    
                    try {
                         withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                            // Игнорируем ошибки блокировки, т.к. база может быть "битой" или выключенной, главное попытаться
                            utils.lockSessions(params.SERVER_1C_PREPROD, params.IB_PREPROD, RAC_USER, RAC_PASS, "Обновление Pre-Prod из Prod")
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
        success {
            script {
                utils.telegram_send_message(
                    env.TELEGRAM_CHAT_TOKEN,
                    env.TELEGRAM_CHAT_ID,
                    "✅ Обновление PRE-PROD успешно завершено!",
                    true
                )
            }
        }
        failure {
            script {
                utils.telegram_send_message(
                    env.TELEGRAM_CHAT_TOKEN,
                    env.TELEGRAM_CHAT_ID,
                    "❌ Ошибка обновления PRE-PROD",
                    false
                )
            }
        }
    }
}
