// ========================================================================
//    УНИВЕРСАЛЬНЫЙ PIPELINE: ВЫГРУЗКА ИЗ 1С В GIT С CHERRY-PICK
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн может выгружать как основную конфигурацию, так и расширения.
// Он выполняет ПОЛНЫЙ цикл, включая cherry-pick, для ЛЮБОГО компонента.
// Режим работы (передача --ext в gitsync) определяется параметром EXTENSION_NAME.
//
// ТРИГГЕР: Запускается по расписанию.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }
    options { timestamps(); disableConcurrentBuilds(); retry(3)}

    stages {
        stage('Checkout Repo') {
            steps {
                script {
                    cleanWs()
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        utils.cmd("git clone --branch 1C_REPO --single-branch ${remoteUrl} . || git clone ${remoteUrl} .", env.WORKSPACE)
                        utils.cmd("git checkout -B 1C_REPO origin/1C_REPO || git checkout -b 1C_REPO", env.WORKSPACE)
                        utils.cmd("git fetch origin branch_sync_repo", env.WORKSPACE)
                        utils.cmd("git checkout -B branch_sync_repo origin/branch_sync_repo || git checkout -b branch_sync_repo", env.WORKSPACE)
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
                    def rc = utils.cherryPickTasksFrom1CRepo(env.WORKSPACE, "https://${params.GIT_REPO_URL}", "1C_REPO", "branch_sync_repo")
                    if (rc != 0) error "Cherry-pick завершился с ошибкой: код ${rc}"
                }
            }
        }

        stage('Sync branch_sync_repo') {
            steps {
                script {
                    def rc = utils.updateBranchSyncFrom1CRepo(env.WORKSPACE, "https://${params.GIT_REPO_URL}", "1C_REPO", "branch_sync_repo")
                    if (rc != 0) error "Синхронизация branch_sync_repo завершилась с ошибкой: код ${rc}"
                }
            }
        }
    }
    
    // --- Блок POST: Действия после завершения пайплайна ---
    post {
        // Выполняется всегда, независимо от результата (успех/провал)
        always {
            script {
                // Принудительно завершаем процессы, которые могли "зависнуть"
                utils.cmd("taskkill /F /IM oscript.exe /T 2>nul || echo OK")
                utils.cmd("taskkill /F /IM 1cv8c.exe /T 2>nul || echo OK")
                utils.cmd("taskkill /F /IM git.exe /T 2>nul || echo OK")
                utils.cmd("taskkill /F /IM gitsync.exe /T 2>nul || echo OK")
            }
        }
        // Выполняется только при успешном завершении
        success {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Выгрузка в Git выполнена успешно.", true) }
        }
        // Выполняется при ошибке
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Выгрузка в Git завершилась ошибкой.", false) }
        }
        // Выполняется при отмене сборки вручную
        aborted {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Выгрузка в Git прервана.", false) }
        }
    }
}