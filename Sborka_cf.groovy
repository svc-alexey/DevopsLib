// ================================================================
//  PIPELINE: Сборка основной конфигурации (.cf) после merge
// ================================================================

def utils = new io.libs.v8_utils()

pipeline {
    agent any
    options { timestamps(); disableConcurrentBuilds(); skipDefaultCheckout(true) }

    parameters {
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.company.com/1c/main-config.git')
        string(name: 'GIT_BRANCH', defaultValue: 'develop')
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

        stage('Build .cf Artifact') {
            steps {
                script {
                    def cfName = "MainConfig_${new Date().format('yyyyMMdd_HHmm')}.cf"
                    utils.compileCF_to_file_safe("${env.WORKSPACE}\\src\\cf", "${env.WORKSPACE}\\build\\${cfName}")
                    archiveArtifacts("build/${cfName}")
                }
            }
        }
    }

    post {
        success { script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Сборка .cf успешно выполнена", true) } }
        failure { script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка сборки .cf", false) } }
    }
}
