// ========================================================================
//    УНИВЕРСАЛЬНЫЙ PIPELINE: ВЫГРУЗКА ИЗ 1С В GIT + CHERRY-PICK
// ========================================================================
// Параметризованный для основной конфигурации и/или расширения
// (EXTENSION_NAME может быть пустым).
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }
    options { timestamps(); disableConcurrentBuilds(); retry(3) }

    parameters {
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/repo.git', description: 'URL репозитория без https://')
        string(name: 'STORAGE_PATH', defaultValue: '\\\\path\\to\\1c_storage', description: 'Путь к хранилищу 1С')
        string(name: 'EXTENSION_NAME', defaultValue: '', description: 'Тех. имя расширения; пусто для основной конфы')
    }

    stages {
        stage('Checkout Repo') {
            steps {
                script {
                    cleanWs()
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        utils.cmd("git clone --branch 1C_REPO --single-branch ${remoteUrl} . || git clone ${remoteUrl} .", env.WORKSPACE)
                        utils.cmd("git checkout -B 1C_REPO origin/1C_REPO || git checkout -b 1C_REPO", env.WORKSPACE)
                        utils.cmd("git fetch origin branch_sync_1c_repo || git branch --track branch_sync_1c_repo origin/branch_sync_1c_repo || echo ok", env.WORKSPACE)
                        utils.cmd("git checkout -B branch_sync_1c_repo origin/branch_sync_1c_repo || git checkout -b branch_sync_1c_repo", env.WORKSPACE)
                        utils.cmd("git checkout 1C_REPO", env.WORKSPACE)
                    }
                }
            }
        }

        stage('Sync from 1C Storage') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'repo_user_pass', usernameVariable: 'STORAGE_USER', passwordVariable: 'STORAGE_PASS')]) {
                        def srcCfPath = "${env.WORKSPACE}\\src\\cf"
                        def rcInit = utils.init_hran(params.STORAGE_PATH, srcCfPath, params.EXTENSION_NAME, env.server1c, STORAGE_USER, STORAGE_PASS)
                        if (rcInit != 0) error "Ошибка инициализации хранилища: код ${rcInit}"
                        def rcSync = utils.sync_hran(params.STORAGE_PATH, srcCfPath, "https://${params.GIT_REPO_URL}", params.EXTENSION_NAME, env.aditional_parameters ?: '', env.server1c, STORAGE_USER, STORAGE_PASS)
                        if (rcSync != 0) error "Ошибка синхронизации: код ${rcSync}"
                    }
                }
            }
        }

        stage('Push Changes to Git') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        utils.cmd("git push ${remoteUrl} 1C_REPO", env.WORKSPACE)
                    }
                }
            }
        }

        stage('Cherry-pick tasks to feature/*') {
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
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Выгрузка в Git выполнена успешно.", true) }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Выгрузка в Git завершилась ошибкой.", false) }
        }
        aborted {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Выгрузка в Git прервана.", false) }
        }
    }
}
