// ========================================================================
//      JENKINS PIPELINE: СИНХРОНИЗАЦИЯ ХРАНИЛИЩА 1С С GIT (GITSYNC)
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн выполняет 3 ключевые задачи:
// 1. Выгружает все новые коммиты из хранилища 1С в специальную ветку '1C_REPO' в Git.
// 2. Анализирует сообщения коммитов, находит в них номера задач (например, ERP-1234).
// 3. Автоматически переносит ("cherry-pick") каждый коммит в соответствующую
//    feature-ветку (например, feature/ERP-1234).
//
// ТРИГГЕР: Запускается по расписанию (например, каждые 15 минут).
// ========================================================================

// --- Загрузка общей библиотеки утилит ---
def loadSharedLibrary() {
    def maxRetries = 3
    def retryDelay = 5 // секунд
    for (int i = 0; i < maxRetries; i++) {
        try {
            library '1c-utils@master'
            return true
        } catch (Exception e) {
            if (i < maxRetries - 1) {
                echo "Ошибка загрузки библиотеки (попытка ${i + 1}/${maxRetries}): ${e.message}"
                sleep retryDelay
            } else { throw e }
        }
    }
}

loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    // Агент, на котором будут выполняться шаги
    agent { label 'localhost' }

    // Опции пайплайна
    options {
        timestamps() // Добавлять временные метки в лог
        disableConcurrentBuilds() // Запретить параллельный запуск этого пайплайна
        retry(3)
    }

    stages {
        // --- ЭТАП 1: Подготовка рабочего пространства ---
        stage('Checkout Source Code') {
            steps {
                script {
                    // КЛЮЧЕВОЙ ШАГ: Полностью очищаем рабочую директорию.
                    // Это гарантирует, что каждый запуск начинается с чистого листа,
                    // решая 99% проблем с блокировками (.git/index.lock).
                    cleanWs()

                    // Клонируем свежую копию репозитория из Git
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                        utils.cmd("git clone --branch 1C_REPO --single-branch ${remoteUrl} .", env.WORKSPACE)
                    }
                    
                    // Подтягиваем служебную ветку, необходимую для сравнения
                    utils.cmd("git fetch origin branch_sync_1c_repo:branch_sync_1c_repo", env.WORKSPACE)
                    utils.cmd("git checkout 1C_REPO", env.WORKSPACE)
                }
            }
        }

        // --- ЭТАП 2: Синхронизация с хранилищем 1С ---
        stage('Init and Sync 1C Storage') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'repo_user_pass', usernameVariable: 'STORAGE_USER', passwordVariable: 'STORAGE_PASS')]) {
                        def srcCfPath = "${env.WORKSPACE}\\src\\cf"
                        
                        // Инициализируем репозиторий для gitsync (если нужно)
                        def rcInit = utils.init_hran(env.rep_1c, srcCfPath, '', env.server1c, STORAGE_USER, STORAGE_PASS)
                        if (rcInit != 0) error "Ошибка инициализации хранилища: код ${rcInit}"

                        // Выполняем основную синхронизацию: забираем коммиты из 1С
                        def rcSync = utils.sync_hran(env.rep_1c, srcCfPath, "https://${env.rep_git_remote}", '', env.aditional_parameters ?: '', env.server1c, STORAGE_USER, STORAGE_PASS)
                        if (rcSync != 0) error "Ошибка синхронизации: код ${rcSync}"
                    }
                    sleep 3 // Небольшая пауза на всякий случай
                }
            }
        }

        // --- ЭТАП 3: Отправка изменений в Git ---
        stage('Push to 1C_REPO') {
            steps {
                script {
                    // Отправляем все выгруженные коммиты в служебную ветку 1C_REPO
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        utils.cmd("cd /D \"${env.WORKSPACE}\" & git push https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote} 1C_REPO")
                    }
                }
            }
        }

        // --- ЭТАП 4: Распределение коммитов по feature-веткам ---
        stage('Cherry-pick tasks to feature/*') {
            steps {
                script {
                    // Устанавливаем URL с токеном для аутентификации
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        utils.git(env.WORKSPACE, "remote set-url origin https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}")
                    }
                    // Запускаем главный метод из библиотеки, который делает всю "магию"
                    def rc = utils.cherryPickTasksFrom1CRepo(env.WORKSPACE, "https://${env.rep_git_remote}", "1C_REPO", "branch_sync_1c_repo")
                    if (rc != 0) error "Cherry-pick завершился с ошибкой: код ${rc}"
                }
            }
        }

        // --- ЭТАП 5: Финальная синхронизация служебной ветки ---
        stage('Sync branch_sync_1c_repo') {
            steps {
                script {
                    // Обновляем ветку-маркер, чтобы в следующий раз не обрабатывать уже разнесенные коммиты
                    def rc = utils.updateBranchSyncFrom1CRepo(env.WORKSPACE, "https://${env.rep_git_remote}", "1C_REPO", "branch_sync_1c_repo")
                    if (rc != 0) error "Синхронизация branch_sync_1c_repo завершилась с ошибкой: код ${rc}"
                }
            }
        }
    }
    
    // --- Блок POST: Действия после завершения пайплайна ---
    post {
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