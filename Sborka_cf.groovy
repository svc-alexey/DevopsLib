// ========================================================================
//    JENKINS PIPELINE: СБОРКА АРТЕФАКТА (.cf) ДЛЯ ОСНОВНОЙ КОНФИГУРАЦИИ
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн компилирует исходники в готовый .cf файл и публикует
// его как артефакт, а также создает Git-тег для версионирования.
//
// ТРИГГЕР: Запускается по webhook'у из Git при мерже в 'develop' или 'master'.
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds(); retry(3)}
    parameters {
        string(name: 'GIT_BRANCH', defaultValue: 'master', description: 'Из какой ветки собирать артефакт')
    }
    
    stages {
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
        
        // --- ЭТАП 2: Сборка артефакта (.cf файл) ---
        stage('Build Artifact') {
            steps {
                script {
                    def buildTimestamp = new Date().format('yyyy.MM.dd-HHmm')
                    def tagName = "build-main-${buildTimestamp}-b${env.BUILD_NUMBER}"
                    // Имя файла-артефакта теперь уникально для каждой сборки
                    def artifactFileName = "Configuration-${tagName}.cf"
                    
                    env.GENERATED_TAG_NAME = tagName
                    env.ARTIFACT_FILE_NAME = artifactFileName

                    // Компилируем исходники напрямую в наш целевой .cf файл
                    def srcCfPath = "${env.WORKSPACE}\\src\\cf"
                    def outputCfFile = "${env.WORKSPACE}\\build\\${artifactFileName}"
                    utils.compileCF_to_file_safe(srcCfPath, outputCfFile)
                }
            }
        }
        
        // --- ЭТАП 3: Публикация артефакта и тега ---
        stage('Publish Artifact and Tag') {
            steps {
                script {
                    // Сохраняем собранный .cf файл. Он будет доступен для скачивания
                    // со страницы этой сборки в Jenkins.
                    archiveArtifacts(
                        artifacts: "build/${env.ARTIFACT_FILE_NAME}", 
                        fingerprint: true,
                        allowEmptyArchive: true // Важно, если по какой-то причине файл не создался
                    )
                    
                    // Создаем и пушим Git-тег
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        utils.cmd("git config user.name 'Jenkins CI'", env.WORKSPACE)
                        utils.cmd("git tag -a ${env.GENERATED_TAG_NAME} -m \"CI Build ${env.BUILD_NUMBER}\"", env.WORKSPACE)
                        utils.cmd("git push https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote} ${env.GENERATED_TAG_NAME}", env.WORKSPACE)
                    }
                }
            }
        }
    }
    
    post {
        success {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Артефакт (.cf) для основной конфигурации успешно создан: ${env.GENERATED_TAG_NAME}", true) }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка создания артефакта (.cf) для основной конфигурации.", false) }
        }
    }
}