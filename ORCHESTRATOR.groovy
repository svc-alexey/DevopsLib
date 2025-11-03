// ================================================================
//  PIPELINE: Оркестратор PROD-обновлений 1С (с блокировкой сеансов)
// ================================================================

def utils = new io.libs.v8_utils()

pipeline {
    agent any
    options { timestamps(); disableConcurrentBuilds(); skipDefaultCheckout(true) }

    parameters {
        string(name: 'SERVER', defaultValue: 'prod-sql-01', description: 'SQL/1C сервер')
        string(name: 'DBNAME', defaultValue: 'ERP_Finance', description: 'Имя базы данных 1С')
        string(name: 'SQL_USER', defaultValue: 'sa', description: 'Пользователь SQL')
        password(name: 'SQL_PASS', defaultValue: '', description: 'Пароль SQL')
        string(name: 'RAC_USER', defaultValue: 'cluster-admin', description: 'Пользователь кластера 1С')
        password(name: 'RAC_PASS', defaultValue: '', description: 'Пароль пользователя кластера 1С')
        string(name: 'RAS', defaultValue: 'prod-cluster-01:1545', description: 'RAS сервер 1С')
    }

    environment {
        MANIFEST_FILE = "${WORKSPACE}\\extension-prod.json"
        CF_FILE       = "${WORKSPACE}\\build\\MainConfig.cf"
        BACKUP_DIR    = "\\\\backups\\ERP"
    }

    stages {
        stage('Lock Sessions') {
            steps {
                script {
                    utils.lockSessions(params.RAS, params.DBNAME, params.RAC_USER, params.RAC_PASS, "Обновление PROD")
                }
            }
        }

        stage('Backup Before Update') {
            steps {
                script {
                    utils.mssqlBackup(params.SERVER, params.DBNAME, env.BACKUP_DIR, params.SQL_USER, params.SQL_PASS)
                }
            }
        }

        stage('Update Main Configuration') {
            steps {
                script {
                    utils.updateDB_via_ibcmd_or_vrunner(env.CF_FILE, params.SERVER, params.DBNAME, params.SQL_USER, params.SQL_PASS)
                }
            }
        }

        stage('Deploy Extensions from Manifest') {
            steps {
                script {
                    utils.deployExtensionsFromManifest(env.MANIFEST_FILE, params.SERVER, params.DBNAME, params.SQL_USER, params.SQL_PASS)
                }
            }
        }
    }

    post {
        always {
            script {
                utils.unlockSessions(params.RAS, params.DBNAME, params.RAC_USER, params.RAC_PASS)
            }
        }
        success { script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "✅ PROD-обновление успешно завершено", true) } }
        failure { script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "❌ Ошибка PROD-обновления", false) } }
    }
}
