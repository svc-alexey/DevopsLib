// ========================================================================
//      JENKINS PIPELINE: СИНХРОНИЗАЦИЯ ХРАНИЛИЩА РАСШИРЕНИЯ 1С С GIT
// ========================================================================
// Назначение:
// 1. Выгружает расширение из хранилища 1С в ветку '1C_REPO' репозитория Git.
// 2. Анализирует коммиты и раскладывает их по feature-веткам.
// Триггер: запускается по расписанию (например, каждые 15 минут).
// ========================================================================

def loadSharedLibrary() {
    library '1c-utils@master'
}
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }
    options { timestamps(); disableConcurrentBuilds(); retry(3) }

    stages {
        stage('Checkout Source Code') {
            steps {
                script {
                    cleanWs()
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"

                        // Клонируем репозиторий и сразу получаем все ветки
                        utils.cmd("git clone ${remoteUrl} .", env.WORKSPACE)
                        utils.cmd("git fetch --all", env.WORKSPACE)

                        // Убедимся, что нужные ветки есть локально
                        utils.cmd("git checkout -B 1C_REPO origin/1C_REPO || git checkout -b 1C_REPO", env.WORKSPACE)
                        utils.cmd("git checkout -B branch_sync_1c_repo origin/branch_sync_1c_repo || git checkout -b branch_sync_1c_repo", env.WORKSPACE)

                        // Возвращаемся на рабочую ветку
                        utils.cmd("git checkout 1C_REPO", env.WORKSPACE)
                    }
                }

            }
        }

        stage('Init and Sync 1C Storage') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'repo_user_pass', usernameVariable: 'STORAGE_USER', passwordVariable: 'STORAGE_PASS')]) {
                        def srcDir = "${env.WORKSPACE}\\src\\cf"
                        // Инициализация и синхронизация именно расширения
                        def rcInit = utils.init_hran(params.STORAGE_PATH, srcDir, params.EXTENSION_NAME, env.server1c, STORAGE_USER, STORAGE_PASS)
                        if (rcInit != 0) error "Ошибка инициализации хранилища: ${rcInit}"
                        def rcSync = utils.sync_hran(params.STORAGE_PATH, srcDir, "https://${params.GIT_REPO_URL}", params.EXTENSION_NAME, env.aditional_parameters ?: '', env.server1c, STORAGE_USER, STORAGE_PASS)
                        if (rcSync != 0) error "Ошибка синхронизации: ${rcSync}"
                    }
                    sleep 3
                }
            }
        }

        stage('Push to 1C_REPO') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        utils.cmd("git push https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL} 1C_REPO", env.WORKSPACE)
                    }
                }
            }
        }

        stage('Cherry-pick to feature/*') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        utils.git(env.WORKSPACE, "remote set-url origin https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}")
                    }
                    def rc = utils.cherryPickTasksFrom1CRepo(env.WORKSPACE, "https://${params.GIT_REPO_URL}", "1C_REPO", "branch_sync_1c_repo")
                    if (rc != 0) error "Cherry-pick завершился с ошибкой: код ${rc}"
                }
            }
        }

        stage('Sync branch_sync_1c_repo') {
            steps {
                script {
                    def rc = utils.updateBranchSyncFrom1CRepo(env.WORKSPACE, "https://${params.GIT_REPO_URL}", "1C_REPO", "branch_sync_1c_repo")
                    if (rc != 0) error "Синхронизация branch_sync_1c_repo завершилась с ошибкой: код ${rc}"
                }
            }
        }
    }

    post {
        success {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "✅ Выгрузка расширения ${params.EXTENSION_NAME} в Git выполнена успешно", true)
            }
        }
        failure {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "❌ Ошибка выгрузки расширения ${params.EXTENSION_NAME}", false)
            }
        }
        aborted {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "⚠ Выгрузка расширения ${params.EXTENSION_NAME} прервана", false)
            }
        }
    }
}
