// ================================================================
//  PIPELINE: Сборка расширения (.cfe)
// ================================================================

def utils = new io.libs.v8_utils()

pipeline {
    agent any
    options { timestamps(); disableConcurrentBuilds(); skipDefaultCheckout(true) }

    parameters {
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.company.com/1c/ext.git')
        string(name: 'GIT_BRANCH', defaultValue: 'develop')
        string(name: 'EXTENSION_NAME', defaultValue: 'MyExtension')
    }

    stages {
        stage('Checkout Source') {
            steps {
                script {
                    cleanWs()
                    checkout([$class: 'GitSCM', branches: [[name: params.GIT_BRANCH]],
                              userRemoteConfigs: [[url: "https://${params.GIT_REPO_URL}"]]])
                }
            }
        }

        stage('Build .cfe Artifact') {
            steps {
                script {
                    def fileName = "${params.EXTENSION_NAME}_${new Date().format('yyyyMMdd_HHmm')}.cfe"
                    utils.compileCFE_to_file_safe(params.EXTENSION_NAME, "${env.WORKSPACE}\\src\\cfe", "${env.WORKSPACE}\\build\\${fileName}")
                    archiveArtifacts("build/${fileName}")
                }
            }
        }
    }

    post {
        success { script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Сборка .cfe успешно выполнена", true) } }
        failure { script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка сборки .cfe", false) } }
    }
}
