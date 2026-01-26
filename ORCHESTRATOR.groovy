// ========================================================================
//      JENKINS PIPELINE: ОРКЕСТРАТОР ОБНОВЛЕНИЯ 1С ПО GIT-ТЕГАМ (ERP)
// ========================================================================
library '1c-utils@master'
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        STATE_DIR      = 'D:\\DevOps\\deployment_state\\ERP'
        CF_STATE_FILE  = "${STATE_DIR}\\${params.IB_NAME}_cf_tag.txt"
        EXT_STATE_DIR  = "${STATE_DIR}\\extensions"
        BACKUP_DIR     = '\\\\opl-dc01-sqlc3\\backup_base\\BACKUP\\NO_DELETE'
        MANIFEST_FILE  = "${WORKSPACE}\\extension-prod.json"

        // чисто информативно, для логов
        HAS_CHANGES    = 'false'
    }

    stages {

         // -----------------------------------------------------------------
        // Уведомление в Telegram о начале обновления
        // -----------------------------------------------------------------
        stage('Notify Start') {
            steps {
                script {
                    utils.telegram_send_message(
                        env.TELEGRAM_CHAT_TOKEN,
                        env.TELEGRAM_CHAT_ID,
                        "🚀 Запущено обновление PROD (${params.IB_NAME}) по тегам\nJob: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        true
                    )
                }
            }
        }
        
        // -----------------------------------------------------------------
        // 0. Чекаут ERP-репозитория + создание служебных папок
        // -----------------------------------------------------------------
        stage('Checkout ERP repository') {
            steps {
                script {
                    cleanWs()

                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/master']],
                        userRemoteConfigs: [[
                            url: "https://${params.rep_git_remote}",
                            credentialsId: 'token'
                        ]],
                        extensions: [
                            [$class: 'CloneOption', shallow: true, noTags: true, timeout: 5, depth: 1]
                        ]
                    ])

                    // служебные директории для хранения последних тегов
                    utils.ensureDirs(env.STATE_DIR, env.EXT_STATE_DIR)
                }
            }
        }

        // -----------------------------------------------------------------
        // 1. Проверяем наличие новых тегов (CF + EXT)
        // -----------------------------------------------------------------
        stage('Check Git tags for updates') {
            steps {
                script {
                    // ================= CF =================
                    def lastCfTag = fileExists(env.CF_STATE_FILE)
                        ? readFile(env.CF_STATE_FILE).trim()
                        : ''

                    echo "Последний установленный CF-тег: ${lastCfTag ?: '(отсутствует)'}"

                    String latestCfTag = ''

                    withCredentials([usernamePassword(
                        credentialsId: 'token',
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_TOKEN'
                    )]) {
                        def latestLine = powershell(
                            script: """
                                \$Token  = "${GIT_TOKEN}"
                                \$RepoUrl = "https://${GIT_USER}:\$Token@${params.rep_git_remote}"
                                git ls-remote --tags --sort=-v:refname \$RepoUrl |
                                  Select-String -NotMatch "\\{\\}" |
                                  Select-Object -First 1
                            """,
                            returnStdout: true
                        ).trim()

                        if (latestLine) {
                            def parts = latestLine.split()
                            if (parts.size() > 1) {
                                latestCfTag = parts[1].replace('refs/tags/', '')
                            }
                        }
                    }

                    env.LATEST_CF_TAG = latestCfTag ?: ''

                    boolean needUpdateCF = (latestCfTag && latestCfTag != lastCfTag)

                    echo "Найден последний тег CF: ${env.LATEST_CF_TAG}"
                    echo "Нужно ли обновлять CF: ${needUpdateCF}"

                    // ================= EXTENSIONS =================
                    def manifest = readJSON file: env.MANIFEST_FILE
                    def extUpdates = [] as List<String>

                    manifest.extensions.each { ext ->
                        def name      = ext.name
                        def repo      = ext.repo
                        def stateFile = "${env.EXT_STATE_DIR}\\${name}_tag.txt"
                        def lastExtTag = fileExists(stateFile) ? readFile(stateFile).trim() : ''

                        String latestExtTag = ''

                        withCredentials([usernamePassword(
                            credentialsId: 'token',
                            usernameVariable: 'GIT_USER',
                            passwordVariable: 'GIT_TOKEN'
                        )]) {
                            def latestLine = powershell(
                                script: """
                                    \$Token  = "${GIT_TOKEN}"
                                    \$RepoUrl = "https://${GIT_USER}:\$Token@${repo.replace('https://','')}"
                                    git ls-remote --tags --sort=-v:refname \$RepoUrl |
                                      Select-String -NotMatch "\\{\\}" |
                                      Select-Object -First 1
                                """,
                                returnStdout: true
                            ).trim()

                            if (latestLine) {
                                def parts = latestLine.split()
                                if (parts.size() > 1) {
                                    latestExtTag = parts[1].replace('refs/tags/', '')
                                }
                            }
                        }

                        if (latestExtTag && latestExtTag != lastExtTag) {
                            echo "Обнаружен новый тег для ${name}: ${latestExtTag} (предыдущий: ${lastExtTag ?: 'нет'})"
                            extUpdates << "${name}:${latestExtTag}"
                        } else {
                            echo "Обновлений для ${name} нет. Текущий тег: ${lastExtTag ?: latestExtTag ?: 'отсутствует'}"
                        }
                    }

                    boolean hasExtChanges = extUpdates.size() > 0

                    // синхронизируем с env
                    env.NEED_UPDATE_CF  = needUpdateCF  ? 'true' : 'false'
                    env.UPDATE_EXT_LIST = hasExtChanges ? extUpdates.join(';') + ';' : ''
                    env.HAS_CHANGES     = (needUpdateCF || hasExtChanges) ? 'true' : 'false'

                    echo "DEBUG: needUpdateCF=${needUpdateCF}, hasExtChanges=${hasExtChanges}"
                    echo "Список расширений для обновления (сырая строка): '${env.UPDATE_EXT_LIST}'"
                    echo "Итог: NEED_UPDATE_CF='${env.NEED_UPDATE_CF}', HAS_CHANGES='${env.HAS_CHANGES}', EXT_LIST='${env.UPDATE_EXT_LIST}'"
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. Блокировка сеансов, если ХОТЯ БЫ что-то меняем (CF или EXT)
        // -----------------------------------------------------------------
        stage('Lock Sessions') {
            when {
                expression {
                    return (
                        env.NEED_UPDATE_CF == 'true' ||
                        ((env.UPDATE_EXT_LIST ?: '').trim() != '')
                    )
                }
            }
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: params.RAC_CRED,
                        usernameVariable: 'RAC_USER',
                        passwordVariable: 'RAC_PASS'
                    )]) {
                        utils.lockSessions(
                            params.SERVER_1C,
                            params.IB_NAME,
                            RAC_USER,
                            RAC_PASS,
                            "Обновление PROD по тегам"
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3. Бэкап базы, если планируются изменения
        // -----------------------------------------------------------------
        stage('Backup DB') {
            when {
                expression {
                    return (
                        env.NEED_UPDATE_CF == 'true' ||
                        ((env.UPDATE_EXT_LIST ?: '').trim() != '')
                    )
                }
            }
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlBackup(
                            params.SERVER_DB,
                            params.DB_NAME,
                            env.BACKUP_DIR,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 4. Обновление основной конфигурации (если изменился CF-тег)
        // -----------------------------------------------------------------
        stage('Update Main Configuration') {
            when { expression { env.NEED_UPDATE_CF == "true" } }
            steps {
                script {
                    def cfJob = params.BUILD_CF_JOB
                    def tag   = env.LATEST_CF_TAG

                    echo "Копируем CF из job '${cfJob}' для тега '${tag}'"

                    copyArtifacts(
                        projectName: cfJob,
                        selector: lastSuccessful(),
                        filter: "build/*${tag}*.cf",
                        target: 'artifacts/',
                        flatten: true
                    )

                    def cfFiles = findFiles(glob: 'artifacts/*.cf')
                    if (cfFiles.length == 0) {
                        error "Не найден .cf-файл в каталоге artifacts"
                    }

                    def cfFile = cfFiles[0].name
                    echo "Найден файл CF: ${cfFile}. Обновляем базу..."

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.updateDB_via_ibcmd_or_vrunner(
                            "artifacts\\${cfFile}",
                            params.SERVER_1C,
                            params.SERVER_DB,
                            params.DB_NAME,
                            SQL_USER,
                            SQL_PASS
                        )
                    }

                    writeFile(file: env.CF_STATE_FILE, text: tag)
                    echo "CF-тег '${tag}' записан в state."
                }
            }
        }

        // -----------------------------------------------------------------
        // 5. Деплой расширений по тегам
        // -----------------------------------------------------------------
        stage('Deploy Extensions by Tags') {
            when {
                expression {
                    return ((env.UPDATE_EXT_LIST ?: '').trim() != '')
                }
            }
            steps {
                script {
                    def manifest = readJSON file: env.MANIFEST_FILE
                    def pairs = env.UPDATE_EXT_LIST.split(';').findAll { it }

                    pairs.each { item ->
                        def parts = item.split(':')
                        def name  = parts[0]
                        def tag   = parts[1]

                        def ext = manifest.extensions.find { it.name == name }
                        if (!ext) {
                            error("В manifest не найдено описание расширения: ${name}")
                        }

                        def jobName = ext.job ?: "Build_CFE_${name}"
                        echo "Развёртывание расширения '${name}' по тегу '${tag}' из job '${jobName}'"

                        copyArtifacts(
                            projectName: jobName,
                            selector: lastSuccessful(),
                            filter: "build/*${tag}*.cfe",
                            target: 'artifacts/',
                            flatten: true
                        )

                        def cfeFiles = findFiles(glob: 'artifacts/*.cfe')
                        if (cfeFiles.length == 0) {
                            error("Не найден .cfe с тегом '${tag}' в артефактах job '${jobName}'")
                        }
                        if (cfeFiles.length > 1) {
                            def names = cfeFiles.collect { it.name }.join(', ')
                            echo "Найдено несколько .cfe, беру первый: ${names}"
                        }

                        def cfeFile = cfeFiles[0].name
                        echo "Найден файл расширения: ${cfeFile}. Загружаем в базу."

                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.updateExtension_via_ibcmd_or_vrunner(
                                "artifacts\\${cfeFile}",
                                name,
                                params.SERVER_1C,
                                params.DB_NAME,
                                SQL_USER,
                                SQL_PASS
                            )
                        }

                        writeFile(
                            file: "${env.EXT_STATE_DIR}\\${name}_tag.txt",
                            text: tag
                        )
                        echo "Тег '${tag}' для '${name}' записан в state."
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 6. Post: всегда снимаем блокировку + уведомления
    // ---------------------------------------------------------------------
    post {
        always {
            script {
                withCredentials([usernamePassword(
                    credentialsId: params.RAC_CRED,
                    usernameVariable: 'RAC_USER',
                    passwordVariable: 'RAC_PASS'
                )]) {
                    utils.unlockSessions(
                        params.SERVER_1C,
                        params.IB_NAME,
                        RAC_USER,
                        RAC_PASS
                    )
                }
            }
        }

        success {
            script {
                def hasAnyChanges = (
                    env.NEED_UPDATE_CF == 'true' ||
                    ((env.UPDATE_EXT_LIST ?: '').trim() != '')
                )

                if (hasAnyChanges) {
                    utils.telegram_send_message(
                        env.TELEGRAM_CHAT_TOKEN,
                        env.TELEGRAM_CHAT_ID,
                        "✅ Обновление PROD (${params.IB_NAME}) по тегам завершено",
                        true
                    )
                } else {
                    echo "Обновлений не было, телеграм не трогаем."
                }
            }
        }

        failure {
            script {
                utils.telegram_send_message(
                    env.TELEGRAM_CHAT_TOKEN,
                    env.TELEGRAM_CHAT_ID,
                    "❌ Ошибка обновления PROD (${params.IB_NAME}) по тегам",
                    false
                )
            }
        }
    }
}
