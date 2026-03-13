@Library('1c-utils@master') _
import io.libs.v8_utils

def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }

    options {
        timestamps()
    }

    parameters {
        string(name: 'rep_git_remote', defaultValue: 'https://gitlab.mriya.me/erp/erp.git', description: 'URL репозитория')
        string(name: 'GITLAB_API_URL', defaultValue: 'https://gitlab.mriya.me', description: 'GitLab URL (без /api/v4)')
        string(name: 'GITLAB_PROJECT_ID', defaultValue: '9', description: 'Числовой project ID в GitLab (erp/erp)')
    }

    environment {
        STATE_DIR = 'D:\\DevOps\\deployment_state\\ERP'
    }

    stages {

        stage('Checkout master with tags') {
            steps {
                cleanWs()

                checkout([
                    $class: 'GitSCM',
                    branches: [[name: '*/master']],
                    doGenerateSubmoduleConfigurations: false,
                    extensions: [
                        [$class: 'CloneOption',
                         noTags: false,
                         reference: '',
                         shallow: false,
                         depth: 0,
                         timeout: 60],
                        [$class: 'PruneStaleBranch'],
                        [$class: 'CleanBeforeCheckout']
                    ],
                    userRemoteConfigs: [[
                        url: params.rep_git_remote,
                        credentialsId: 'all_project_access'
                    ]]
                ])

                bat """
                    if not exist "${env.STATE_DIR}" mkdir "${env.STATE_DIR}"
                """
            }
        }

        stage('Collect release data') {
            steps {
                script {
                    // Постоянный файл состояния, не в workspace
                    def stateDir  = env.STATE_DIR.replace('\\','/')
                    def stateFile = "${stateDir}/last_release_tag.txt"
                    env.RELEASE_STATE_FILE = stateFile

                    bat """
                        if not exist "${env.STATE_DIR}" mkdir "${env.STATE_DIR}"
                    """

                    String lastTag = null
                    if (fileExists(stateFile)) {
                        lastTag = readFile(file: stateFile, encoding: 'UTF-8').trim()
                    }
                    echo "Последний релизный тег (из state): ${lastTag ?: '(нет, первый релиз)'}"

                    // Текущий тег
                    def rawTag = bat(
                        script: '''
@echo off
chcp 65001 > nul
git describe --tags --abbrev=0
                        ''',
                        returnStdout: true
                    ).trim()

                    def tagLines = rawTag.readLines()
                    env.CURRENT_TAG = tagLines[-1].trim()
                    echo "Текущий тег из Git: ${env.CURRENT_TAG}"

                    boolean hasNew = (lastTag == null || lastTag != env.CURRENT_TAG)
                    env.HAS_NEW_TAG = hasNew.toString()

                    if (!hasNew) {
                        echo "Нового тега нет, release делать не будем."
                    }

                    // VERSION из src/cf/VERSION (XML)
                    String versionXml = readFile('src/cf/VERSION').trim()
                    String storageVersion = 'UNKNOWN'
                    String openTag  = '<VERSION>'
                    String closeTag = '</VERSION>'
                    int s = versionXml.indexOf(openTag)
                    int e = versionXml.indexOf(closeTag)
                    if (s >= 0 && e > s) {
                        storageVersion = versionXml.substring(s + openTag.length(), e).trim()
                    }
                    env.STORAGE_VERSION = storageVersion
                    env.RELEASE_NAME = "ERP ${storageVersion} (${env.CURRENT_TAG})"

                    // Диапазон для log
                    String rangeArg = lastTag ? "${lastTag}..${env.CURRENT_TAG}" : env.CURRENT_TAG
                    env.GIT_RANGE = rangeArg

                    // Формирует tasks_unique.txt
                    String ps1 = '''
$ErrorActionPreference = 'Stop'

$range = $env:GIT_RANGE

# subjects (newest -> oldest)
$lines = git log $range --pretty=format:%s

$seen = @{}
$out  = New-Object System.Collections.Generic.List[String]

foreach ($l in $lines) {
    if ([string]::IsNullOrWhiteSpace($l)) { continue }
    $l = $l.Trim()

    if ($l -match '^(#\\S+-\\d+)\\s+(.+)$') {
        $key = $matches[1]

        # ensure digits after '-'
        $num = $key.Substring($key.LastIndexOf('-') + 1)
        if ($num -notmatch '^\\d+$') { continue }

        if (-not $seen.ContainsKey($key)) {
            $seen[$key] = $true
            [void]$out.Add($l)
        }
    }
}

$out | Out-File -Encoding UTF8 tasks_unique.txt
'''
                    writeFile(file: 'gen_tasks.ps1', text: ps1)

                    bat '''
@echo off
chcp 65001 > nul
powershell -NoProfile -ExecutionPolicy Bypass -File gen_tasks.ps1
'''

                    List<String> taskLines = []
                    if (fileExists('tasks_unique.txt')) {
                        taskLines = readFile('tasks_unique.txt')
                            .readLines()
                            .collect { it.trim() }
                            .findAll { it }
                    }

                    String notes = "Собранные release notes:\n" +
                                   "Версия храна: ${env.STORAGE_VERSION}\n\n"

                    if (taskLines.isEmpty()) {
                        notes += "_Новых коммитов по задачам в диапазоне не найдено._\n"
                    } else {
                        for (String line : taskLines) {
                            notes += "• ${line}\n"
                        }
                    }

                    writeFile(file: 'release_notes.txt', text: notes, encoding: 'UTF-8')

                    echo readFile('release_notes.txt')
                }
            }
        }

        stage('Create GitLab release') {
            when { expression { env.HAS_NEW_TAG == 'true' } }
            steps {
                script {
                    if (!params.GITLAB_PROJECT_ID?.trim()) {
                        error "Не задан GITLAB_PROJECT_ID. Укажи числовой ID проекта erp/erp в параметрах джобы."
                    }

                    withCredentials([usernamePassword(
                        credentialsId: 'all_project_access',
                        usernameVariable: 'GL_USER',
                        passwordVariable: 'GL_TOKEN'
                    )]) {

                        String apiUrl = "${params.GITLAB_API_URL}/api/v4/projects/${params.GITLAB_PROJECT_ID}/releases"

                        String cmd = """
@echo off
chcp 65001 > nul
curl --request POST "${apiUrl}" ^
  --header "PRIVATE-TOKEN: %GL_TOKEN%" ^
  --data "name=${env.RELEASE_NAME}" ^
  --data "tag_name=${env.CURRENT_TAG}" ^
  --data "ref=${env.CURRENT_TAG}" ^
  --data-urlencode "description@release_notes.txt"
"""
                        String out = bat(returnStdout: true, script: cmd).trim()
                        echo "GitLab API response: ${out}"
                    }
                }
            }
        }

        stage('Save release state') {
            when { expression { env.HAS_NEW_TAG == 'true' } }
            steps {
                script {
                    writeFile(file: env.RELEASE_STATE_FILE, text: env.CURRENT_TAG, encoding: 'UTF-8')
                    echo "Тег ${env.CURRENT_TAG} записан в ${env.RELEASE_STATE_FILE}"
                }
            }
        }
    }

    post {
        success {
            script {
                if (env.HAS_NEW_TAG != 'true') {
                    echo "Нового тега не было, GitLab release и Telegram не трогаем."
                    return
                }

                String notes = fileExists('release_notes.txt') ? readFile('release_notes.txt') : ''

                if (notes && notes.length() > 3500) {
                    notes = notes.substring(0, 3500) +
                            "\n\n... (обрезано, полный текст в GitLab release)"
                }

                String msg = """Создан GitLab release:
Тег: ${env.CURRENT_TAG}
Релиз: ${env.RELEASE_NAME}

${notes}
"""

                utils.telegram_send_message(
                    env.TELEGRAM_CHAT_TOKEN,
                    env.TELEGRAM_CHAT_ID,
                    msg,
                    true
                )
            }
        }
    }
}