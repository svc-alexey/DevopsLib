// ========================================================================
//    ШАБЛОН JENKINS PIPELINE: ТЕГИРОВАНИЕ РАСШИРЕНИЯ (БЕЗ ПРОВЕРКИ)
// ========================================================================
// НАЗНАЧЕНИЕ:
// Создает Git-тег (артефакт) для расширения, фиксируя готовую к
// развертыванию версию. Не выполняет никаких проверок.
//
// ТРИГГЕР: Запускается по webhook'у из Git-репозитория расширения.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); }
    parameters {
        string(name: 'EXTENSION_NAME', defaultValue: 'YourExtensionName', description: 'Техническое имя расширения')
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/to/repo.git', description: 'URL Git-репозитория этого расширения (без https://)')
        string(name: 'GIT_BRANCH', defaultValue: 'develop', description: 'Из какой ветки создавать артефакт')
    }
    
    stages {
        stage('Checkout Extension Code') {
            steps {
                script {
                    cleanWs()
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        utils.cmd("git clone --branch ${params.GIT_BRANCH} --single-branch ${remoteUrl} .", env.WORKSPACE)
                    }
                }
            }
        }

        stage('Create and Push Git Tag') {
            steps {
                script {
                    def buildTimestamp = new Date().format('yyyy.MM.dd-HHmm')
                    // Имя тега включает имя расширения для уникальности
                    def tagName = "build-${params.EXTENSION_NAME}-${buildTimestamp}-b${env.BUILD_NUMBER}"
                    echo "Создание артефакта в виде Git-тега: ${tagName}"
                    env.GENERATED_TAG_NAME = tagName // Сохраняем имя тега для post-блока

                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        utils.cmd("git config user.name 'Jenkins CI'", env.WORKSPACE)
                        utils.cmd("git config user.email 'jenkins@mycompany.com'", env.WORKSPACE)
                        utils.cmd("git tag -a ${tagName} -m 'CI Build ${env.BUILD_NUMBER}'", env.WORKSPACE)
                        
                        // Пушим тег в репозиторий расширения
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        utils.cmd("git push ${remoteUrl} ${tagName}", env.WORKSPACE)
                    }
                }
            }
        }
    }
    
    post {
        success {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Артефакт для расширения '${params.EXTENSION_NAME}' успешно создан: ${env.GENERATED_TAG_NAME}", true) }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка создания артефакта для расширения '${params.EXTENSION_NAME}'.", false) }
        }
    }
}