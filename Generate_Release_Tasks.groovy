@Library('1c-utils@master') _

import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        string(
            name: 'TARGET_BRANCH',
            defaultValue: 'master',
            description: 'Ветка, из которой читаются теги и коммиты'
        )
        string(
            name: 'STATE_DIR',
            defaultValue: 'D:\\DevOps\\deployment_state\\ERP',
            description: 'Каталог для state-файлов'
        )
        string(
            name: 'LAST_RELEASE_TAG_FILE',
            defaultValue: 'D:\\DevOps\\deployment_state\\ERP\\last_release_tag_test.txt',
            description: 'Файл с предыдущим релизным тегом'
        )
        string(
            name: 'LAST_RELEASE_TASKS_FILE',
            defaultValue: 'D:\\DevOps\\deployment_state\\ERP\\last_release_tasks.txt',
            description: 'Файл со списком задач релиза'
        )
    }

    stages {
        stage('Checkout repository') {
            steps {
                script {
                    cleanWs()

                    withCredentials([usernamePassword(
                        credentialsId: 'token',
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_TOKEN'
                    )]) {
                        utils.checkoutBranchAndFetchTags(
                            params.TARGET_BRANCH,
                            env.rep_git_remote,
                            env.WORKSPACE,
                            params.STATE_DIR,
                            GIT_USER,
                            GIT_TOKEN
                        )
                    }
                }
            }
        }

        stage('Build task list file') {
            steps {
                script {
                    utils.generateReleaseTasksFile(
                        params.LAST_RELEASE_TAG_FILE,
                        params.LAST_RELEASE_TASKS_FILE
                    )
                }
            }
        }
    }
}
