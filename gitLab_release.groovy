@Library('1c-utils@master') _
import io.libs.v8_utils

// ----------------------- helpers (NonCPS safe) -----------------------
@NonCPS
Map<String, String> extractUniqueTasks(List<String> lines) {
    // формат: #PROJ-1234 #Комментарий
    // prefix: буквы/цифры/подчерк/точка/дефис (на всякий), номер: цифры
    def pattern = ~/^#([A-Za-z0-9_.]+-\d+)\s+#(.+)$/

    // LinkedHashMap сохраняет порядок добавления (в git log по умолчанию сначала новые)
    Map<String, String> tasks = new LinkedHashMap<>()

    for (String raw : lines) {
        if (raw == null) continue
        String line = raw.trim()
        if (!line) continue

        def m = (line =~ pattern)
        if (m.matches()) {
            String key = m[0][1].toString().trim()
            String text = m[0][2].toString().trim()
            if (!tasks.containsKey(key)) {
                tasks.put(key, text)
            }
        }
    }
    return tasks
}

@NonCPS
String extractVersionFromXml(String xml) {
    if (!xml) return null
    def m = (xml =~ /<VERSION>\s*([0-9]+)\s*<\/VERSION>/)
    if (m.find()) return m.group(1)
    return null
}

// --------------------------------------------------------------------
pipeline {
    agent { label 'localhost' }

    options {
        timestamps()
        timeout(time: 1, unit: 'HOURS')
        disableConcurrentBuilds()
    }

    environment {
        STATE_DIR = 'D:\\DevOps\\deployment_state\\ERP' 
    }

    stages {
        stage('Checkout master with tags') {
            steps {
                cleanWs()

                timeout(time: 25, unit: 'MINUTES') {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/master']],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [
                            [$class: 'CloneOption', noTags: false, shallow: false, depth: 0, timeout: 120],
                            [$class: 'PruneStaleBranch'],
                            [$class: 'CleanBeforeCheckout']
                        ],
                        userRemoteConfigs: [[
                            url: params.rep_git_remote,
                            credentialsId: 'token'
                        ]]
                    ])
                }

                bat """
                    if not exist "${env.STATE_DIR}" mkdir "${env.STATE_DIR}"
                """
            }
        }

        stage('Collect release data') {
            steps {
                script {
                    if (!params.GITLAB_PROJECT_ID?.trim()) {
                        error "Не задан GITLAB_PROJECT_ID. Укажи ID проекта erp/erp в параметрах джобы."
                    }

                    def ws = pwd()
                    env.RELEASE_STATE_DIR  = "${ws}\\release_state"
                    env.RELEASE_STATE_FILE = "${env.RELEASE_STATE_DIR}\\last_release_tag.txt"

                    bat """if not exist "${env.RELEASE_STATE_DIR}" mkdir "${env.RELEASE_STATE_DIR}" """

                    // прошлый тег
                    def lastTag = null
                    if (fileExists(env.RELEASE_STATE_FILE)) {
                        lastTag = readFile(env.RELEASE_STATE_FILE).trim()
                    }
                    echo "Последний релизный тег (из state): ${lastTag ?: '(нет, первый релиз)'}"

                    // текущий тег
                    def rawTag = bat(
                        script: '''
                            chcp 65001 > nul
                            git describe --tags --abbrev=0
                        ''',
                        returnStdout: true
                    ).trim()
                    env.CURRENT_TAG = rawTag.readLines()[-1].trim()
                    echo "Текущий тег из Git: ${env.CURRENT_TAG}"

                    // есть ли новый тег
                    env.HAS_NEW_TAG = (!lastTag || lastTag != env.CURRENT_TAG) ? 'true' : 'false'
                    if (env.HAS_NEW_TAG != 'true') {
                        echo "Нового тега нет: ${env.CURRENT_TAG}. Релиз создавать/обновлять не будем."
                    }

                    // версия хранилища из src/cf/VERSION
                    def versionText = null
                    if (fileExists('src/cf/VERSION')) {
                        def xml = readFile('src/cf/VERSION')
                        versionText = extractVersionFromXml(xml)
                    }
                    env.STORAGE_VERSION = versionText ?: 'UNKNOWN'
                    echo "Версия хранилища из src/cf/VERSION: ${env.STORAGE_VERSION}"

                    // диапазон логов
                    def rangeArg = lastTag ? "${lastTag}..${env.CURRENT_TAG}" : env.CURRENT_TAG

                    // получаем subject'ы коммитов
                    def rawLog = bat(
                        script: """
                            chcp 65001 > nul
                            git log ${rangeArg} --pretty=format:"%s"
                        """,
                        returnStdout: true
                    ).trim()

                    def logLines = rawLog ? rawLog.readLines().findAll { it?.trim() } : []

                    // вырезаем только задачи формата #PROJ-1234 #Комментарий и без дублей
                    def tasks = extractUniqueTasks(logLines)

                    def sb = new StringBuilder()
                    sb.append("Собранные release notes:\\n")
                    sb.append("Версия храна: ").append(env.STORAGE_VERSION).append("\\n\\n")

                    if (tasks.isEmpty()) {
                        sb.append("_Коммитов по задачам формата #PROJ-1234 #Комментарий в диапазоне не найдено._\\n")
                    } else {
                        tasks.each { k, v ->
                            sb.append("• #").append(k).append(" #").append(v).append("\\n")
                        }
                    }

                    env.RELEASE_NAME = "ERP ${env.STORAGE_VERSION} (${env.CURRENT_TAG})"
                    env.RELEASE_NOTES = sb.toString()

                    echo env.RELEASE_NOTES
                    writeFile file: 'release_notes.txt', text: env.RELEASE_NOTES
                }
            }
        }

        stage('Create/Update GitLab release') {
            when {
                expression { return env.HAS_NEW_TAG == 'true' }
            }
            steps {
                script {
                    def apiBase = "${params.GITLAB_API_URL}/api/v4/projects/${params.GITLAB_PROJECT_ID}/releases"
                    def tag = env.CURRENT_TAG

                    // Пишем ответ и код отдельно, чтобы не гадать почему curl “просто 22”
                    def createCmd = """
                        chcp 65001 > nul
                        curl -sS -o gl_resp.json -w "HTTP_CODE:%{http_code}" ^
                          --request POST "${apiBase}" ^
                          --header "PRIVATE-TOKEN: ${params.token}" ^
                          --data "name=${env.RELEASE_NAME}" ^
                          --data "tag_name=${tag}" ^
                          --data "ref=${tag}" ^
                          --data-urlencode "description@release_notes.txt"
                    """

                    def out = bat(returnStdout: true, script: createCmd).trim()
                    def httpCode = out.replaceAll('.*HTTP_CODE:', '').trim()
                    echo "GitLab create release HTTP: ${httpCode}"

                    if (httpCode == '201') {
                        echo "Release создан."
                    } else {
                        echo "Ответ GitLab (body):"
                        bat 'type gl_resp.json'
                        error "Не удалось создать релиз, HTTP ${httpCode}"
                    }

                    echo "GitLab API response body:"
                    bat 'type gl_resp.json'
                }
            }
        }

        stage('Save release state') {
            when {
                expression { return env.HAS_NEW_TAG == 'true' }
            }
            steps {
                script {
                    writeFile(file: env.RELEASE_STATE_FILE, text: env.CURRENT_TAG)
                    echo "Тег ${env.CURRENT_TAG} записан в ${env.RELEASE_STATE_FILE}"
                }
            }
        }
    }

    post {
        success {
            script {
                if (env.HAS_NEW_TAG != 'true') return

                def notes = fileExists('release_notes.txt') ? readFile('release_notes.txt') : ''
                if (notes && notes.length() > 3500) {
                    notes = notes.substring(0, 3500) + "\\n\\n... (обрезано, полный текст в GitLab release)"
                }

                def repoUrl = params.rep_git_remote?.replaceAll(/\.git$/, '')
                def msg = """✅ Создан GitLab release:
Тег: ${env.CURRENT_TAG}
Релиз: ${env.RELEASE_NAME}

${notes}

Сборка: ${env.BUILD_URL}
Репозиторий: ${repoUrl}
"""

                // utils создаём здесь, чтобы не ловить CPS/Binding сюрпризы
                def utils = new v8_utils()
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, msg, true)
            }
        }
    }
}
