// ========================================================================
//    JENKINS PIPELINE: ТЕГИРОВАНИЕ ОСНОВНОЙ КОНФИГУРАЦИИ (БЕЗ ПРОВЕРКИ)
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн НЕ ВЫПОЛНЯЕТ ПРОВЕРОК. Он исходит из того, что код,
// попавший в эту ветку, уже прошел Code Review. Его единственная задача -
// создать Git-тег (артефакт), фиксирующий готовую к развертыванию версию.
//
// ТРИГГЕР: Запускается по webhook'у из Git при мерже в 'develop' или 'master'.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds() }
    parameters {
        string(name: 'GIT_BRANCH', defaultValue: 'develop', description: 'Из какой ветки создавать артефакт')
    }
    
    stages {
        // --- ЭТАП 1: Получение исходного кода ---
        stage('Checkout Code') {
            steps {
                script {
                    cleanWs()
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                        utils.cmd("git clone --branch ${params.GIT_BRANCH} --single-branch ${remoteUrl} .", env.WORKSPACE)
                    }
                }
            }
        }
        
        // --- ЭТАП 2: Создание и отправка Git-тега ---
        stage('Create and Push Git Tag') {
            steps {
                script {
                    // Формируем имя тега
                    def buildTimestamp = new Date().format('yyyy.MM.dd-HHmm')
                    def tagName = "build-main-${buildTimestamp}-b${env.BUILD_NUMBER}"
                    echo "Создание артефакта в виде Git-тега: ${tagName}"
                    env.GENERATED_TAG_NAME = tagName // Сохраняем имя тега для post-блока

                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        // Устанавливаем личность для создания тега
                        utils.cmd("git config user.name 'Jenkins CI'", env.WORKSPACE)
                        utils.cmd("git config user.email 'jenkins@mycompany.com'", env.WORKSPACE)
                        
                        // Создаем сам тег
                        utils.cmd("git tag -a ${tagName} -m 'CI Build ${env.BUILD_NUMBER}'", env.WORKSPACE)
                        
                        // Отправляем тег обратно в GitLab
                        utils.cmd("git push https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote} ${tagName}", env.WORKSPACE)
                    }
                }
            }
        }
    }
    
    post {
        success {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Артефакт для основной конфигурации успешно создан: ${env.GENERATED_TAG_NAME}", true) }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка создания артефакта для основной конфигурации.", false) }
        }
    }
}