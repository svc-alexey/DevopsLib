// ========================================================================
//    ПОСЛЕДОВАТЕЛЬНОЕ РАЗВЕРТЫВАНИЕ РАСШИРЕНИЯ (вызов из Оркестратора)
// ========================================================================

def loadSharedLibrary() { library '1c-utils@master' }
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent any
    options { timestamps(); skipDefaultCheckout(true); disableConcurrentBuilds() }

    parameters {
        string(name: 'EXTENSION_NAME', defaultValue: 'YourExtensionName', description: 'Техническое имя расширения')
        string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/to/repo.git', description: 'URL репозитория без https://')
        string(name: 'TARGET_ENVIRONMENT', defaultValue: '', description: 'Целевая среда (из оркестратора)')
    }

    stages {
        stage('Check and Deploy Extension') {
            steps {
                script {
                    if (params.TARGET_ENVIRONMENT.trim().isEmpty()) {
                        error("Этот пайплайн не запускается вручную без TARGET_ENVIRONMENT.")
                    }

                    def envConfig = [:]
                    switch (params.TARGET_ENVIRONMENT) {
                        case 'PROD_FINANCE':
                            envConfig.SERVER_1C = 'prod-cluster-01.mycompany.com'; envConfig.DATABASE_NAME = 'ERP_Finance'
                            envConfig.IC_CREDS_ID = 'ic-user-pass-finance-prod'; envConfig.RAC_CREDS_ID = 'cluster-admin-prod'
                            break
                        case 'PROD_LOGISTICS':
                            envConfig.SERVER_1C = 'prod-cluster-01.mycompany.com'; envConfig.DATABASE_NAME = 'ERP_Logistics'
                            envConfig.IC_CREDS_ID = 'ic-user-pass-logistics-prod'; envConfig.RAC_CREDS_ID = 'cluster-admin-prod'
                            break
                        default:
                            error "Неизвестная целевая среда: ${params.TARGET_ENVIRONMENT}"
                    }

                    echo "--- Обновление расширения '${params.EXTENSION_NAME}' для среды ${params.TARGET_ENVIRONMENT} ---"
                    env.TAG_TO_DEPLOY_EXT = null

                    withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                        def latestTag = bat(script: "git ls-remote --tags --sort=-v:refname ${remoteUrl} \"refs/tags/build-${params.EXTENSION_NAME}-*\" | findstr /V \"{}\" | for /f %%i in ('more') do @echo %%i | sed \"s/refs\\/tags\\///\"", returnStdout: true).trim()
                        if (!latestTag?.trim()) { echo "Нет тегов для '${params.EXTENSION_NAME}'. Пропуск."; return }
                        def lastDeployedTagFile = "C:\\Jenkins\\deployment_state\\${params.EXTENSION_NAME}_${params.TARGET_ENVIRONMENT}_last_tag.txt"
                        def lastDeployedTag = fileExists(lastDeployedTagFile) ? readFile(lastDeployedTagFile).trim() : ""
                        echo "Последний доступный тег: ${latestTag}"; echo "Последний развернутый тег: ${lastDeployedTag}"
                        if (latestTag != lastDeployedTag) { env.TAG_TO_DEPLOY_EXT = latestTag }
                    }

                    if (!env.TAG_TO_DEPLOY_EXT) { echo "Новых версий расширения нет."; return }

                    cleanWs()
                    def buildNumber = env.TAG_TO_DEPLOY_EXT.split('-b').last()
                    def artifactFileName = "${params.EXTENSION_NAME}-${env.TAG_TO_DEPLOY_EXT}.cfe"
                    def buildJobName = "ERP-Extension-${params.EXTENSION_NAME}-Build"
                    copyArtifacts(projectName: buildJobName, selector: specific(buildNumber), filter: "build/${artifactFileName}", target: '.')

                    withCredentials([
                        usernamePassword(credentialsId: envConfig.RAC_CREDS_ID, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                        usernamePassword(credentialsId: envConfig.IC_CREDS_ID,  usernameVariable: 'IC_USER',  passwordVariable: 'IC_PASS')
                    ]) {
                        def ras = "${envConfig.SERVER_1C}:${env.CLUSTER_PORT ?: '1545'}"
                        def cfePath = "${env.WORKSPACE}\\${artifactFileName}"

                        // ВАЖНО: установка расширения, а НЕ основной конфигурации.
                        // Пример с Designer (у тебя может отличаться путь и ключи):
                        def rcUpdate = utils.cmd("\"C:\\Program Files\\1cv8\\8.3.26.1540\\bin\\1cv8.exe\" DESIGNER /S\"${ras}\\${envConfig.DATABASE_NAME}\" /N\"${IC_USER}\" /P\"${IC_PASS}\" /LoadExtCfg \"${cfePath}\" /UpdateDBCfg -force")
                        if (rcUpdate != 0) { error("Обновление расширения '${params.EXTENSION_NAME}' провалилось.") }

                        writeFile(file: "C:\\Jenkins\\deployment_state\\${params.EXTENSION_NAME}_${params.TARGET_ENVIRONMENT}_last_tag.txt", text: env.TAG_TO_DEPLOY_EXT)
                    }
                }
            }
        }
    }
}
