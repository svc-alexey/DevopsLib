// ========================================================================
//    ШАБЛОН JENKINS PIPELINE: СБОРКА АРТЕФАКТА (.cfe) ДЛЯ РАСШИРЕНИЯ
// ========================================================================
// НАЗНАЧЕНИЕ:
// Этот пайплайн компилирует исходники расширения в готовый .cfe файл и
// публикует его как артефакт, а также создает Git-тег для версионирования.
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
        string(name: 'GIT_BRANCH', defaultValue: 'develop', description: 'Из какой ветки собирать артефакт')
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

        stage('Build Artifact') {
            steps {
                script {
                    def buildTimestamp = new Date().format('yyyy.MM.dd-HHmm')
                    def tagName = "build-${params.EXTENSION_NAME}-${buildTimestamp}-b${env.BUILD_NUMBER}"
                    def artifactFileName = "${params.EXTENSION_NAME}-${tagName}.cfe"
                    
                    env.GENERATED_TAG_NAME = tagName
                    env.ARTIFACT_FILE_NAME = artifactFileName
                    
                    // Компилируем исходники расширения в .cfe файл
                    def srcPath = "${env.WORKSPACE}\\src\\cf"
                    def outputPath = "${env.WORKSPACE}\\build\\${artifactFileName}"
                    utils.compileCFE_to_file_safe(params.EXTENSION_NAME, srcPath, outputPath)
                }
            }
        }

        stage('Publish Artifact and Tag') {
            steps {
                script {
                    archiveArtifacts(artifacts: "build/${env.ARTIFACT_FILE_NAME}", fingerprint: true, allowEmptyArchive: true)
                    
                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        utils.cmd("git config user.name 'Jenkins CI'", env.WORKSPACE)
                        utils.cmd("git tag -a ${env.GENERATED_TAG_NAME} -m 'CI Build ${env.BUILD_NUMBER}'", env.WORKSPACE)
                        utils.cmd("git push ${remoteUrl} ${env.GENERATED_TAG_NAME}", env.WORKSPACE)
                    }
                }
            }
        }
    }
    
    post {
        success {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Артефакт (.cfe) для расширения '${params.EXTENSION_NAME}' успешно создан: ${env.GENERATED_TAG_NAME}", true) }
        }
        failure {
            script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка создания артефакта (.cfe) для расширения '${params.EXTENSION_NAME}'.", false) }
        }
    }
}