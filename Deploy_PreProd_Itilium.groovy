// ========================================================================
//      JENKINS PIPELINE: ОБНОВЛЕНИЕ PRE-PROD ИЗ DEVELOP + PROD DATA
// ========================================================================
//
// ЦЕЛЬ:
// 1. Собрать актуальный CF из ветки develop.
// 2. Сделать бэкап PROD базы.
// 3. Восстановить этот бэкап в PRE-PROD.
// 4. Накатить собранный CF на PRE-PROD.
//
// ИТОГ: Pre-Prod база с актуальными данными из Prod и свежим кодом из develop.
// ========================================================================

library '1c-utils@master'
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
    agent { label 'localhost' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    parameters {
        string(name: 'SHARED_BACKUP_PATH', defaultValue: '\\\\opl-dc01-sqlc3\\backup_base\\BACKUP\\itilium\\SHARED', description: 'Сетевой путь для бэкапа, доступный обоим SQL серверам')
        string(name: 'LAST_RELEASE_TASKS_FILE', defaultValue: 'D:\\DevOps\\deployment_state\\ITILIUM\\last_release_tasks.txt', description: 'Файл со списком задач релиза')
    }

    environment {
        // Формируем имя файла бэкапа один раз, чтобы использовать везде
        BACKUP_FILENAME = "ITILIUM_Prod_Transfer_${new Date().format('yyyyMMdd_HHmmss')}.bak"
        FULL_BACKUP_PATH = "${params.SHARED_BACKUP_PATH}\\${BACKUP_FILENAME}"

        // Пути для сборки CF
        SRC_CF_PATH = "${WORKSPACE}\\src\\cf"
        OUTPUT_CF_FILE = "${WORKSPACE}\\build\\Configuration_develop.cf"
        
        // Переменная для хранения текста ошибки из 1С
        DB_HEALTH_CHECK_ERROR = ""

        FIX_DELETE_EPF = "${WORKSPACE}\\tools\\MRS_УдалениеИсправлений.epf"
        CHECK_DB_EPF = "${WORKSPACE}\\tools\\MRS_ПроверкаБД.epf"
        CHECK_EXT_APPLICABILITY_EPF = "${WORKSPACE}\\tools\\MRS_ПроверкаПрименимостиРасширений.epf"
        MANIFEST_FILE = "${WORKSPACE}\\extension-prod.json"
        EXT_STATE_DIR = "D:\\DevOps\\deployment_state\\ITILIUM\\extensions"
        UPDATE_EXT_LIST = ""
    }

    stages {
        // -----------------------------------------------------------------
        // Уведомление (старт)
        // -----------------------------------------------------------------
        stage('Notify Start') {
            steps {
                script {
                    utils.telegram_send_message(
                        env.TELEGRAM_CHAT_TOKEN,
                        env.TELEGRAM_CHAT_ID,
                        "🚀 Запущено обновление PRE-PROD (${params.IB_PREPROD})\n" +
                        "Ветка: ${params.GIT_BRANCH}\n" +
                        "Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        true
                    )
                }
           }
        }

        // -----------------------------------------------------------------
        // 1. Сборка CF из Git (develop)
        // -----------------------------------------------------------------
        stage('Checkout & Build CF') {
            steps {
                script {
                    cleanWs()

                    echo "Checkout ветки ${params.GIT_BRANCH}..."
                    withCredentials([usernamePassword(
                        credentialsId: 'token',
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_TOKEN'
                    )]) {
                        def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${env.rep_git_remote}"
                        utils.cmd("git clone --branch ${params.GIT_BRANCH} --single-branch ${remoteUrl} .", env.WORKSPACE)
                    }

                    echo "Сборка конфигурации..."
                    utils.compileCF_to_file_safe(env.SRC_CF_PATH, env.OUTPUT_CF_FILE)
                }
            }
        }

        // -----------------------------------------------------------------
        // 1.5. Проверка следующих тегов расширений
        // -----------------------------------------------------------------
        stage('Check Next Extension Tags') {
            steps {
                script {
                    if (!fileExists(env.MANIFEST_FILE)) {
                        echo "Файл manifest не найден: ${env.MANIFEST_FILE}. Обновление расширений пропускаем."
                        env.UPDATE_EXT_LIST = ''
                        return
                    }

                    def manifest = readJSON file: env.MANIFEST_FILE
                    def extUpdates = [] as List<String>

                    manifest.extensions.each { ext ->
                        def name = ext.name
                        def repo = ext.repo

                        if (!name || !repo) {
                            echo "Пропускаем некорректное описание расширения в manifest: ${ext}"
                            return
                        }

                        def stateFile = "${env.EXT_STATE_DIR}\\${name}_tag.txt"
                        def lastExtTag = fileExists(stateFile) ? readFile(stateFile).trim() : ''

                        if (!lastExtTag) {
                            echo "Для расширения '${name}' не найден state-файл '${stateFile}'. PRE-PROD не обновляет это расширение."
                            return
                        }

                        def tagsRaw = ''
                        withCredentials([usernamePassword(
                            credentialsId: 'token',
                            usernameVariable: 'GIT_USER',
                            passwordVariable: 'GIT_TOKEN'
                        )]) {
                            tagsRaw = powershell(
                                script: """
                                    \$Token  = "${GIT_TOKEN}"
                                    \$RepoUrl = "https://${GIT_USER}:\$Token@${repo.replace('https://','')}"
                                    git ls-remote --tags --sort=v:refname \$RepoUrl |
                                      Select-String -NotMatch "\\{\\}" |
                                      ForEach-Object { (\$_ -split '\\s+')[1].Replace('refs/tags/', '') }
                                """,
                                returnStdout: true
                            ).trim()
                        }

                        def tags = tagsRaw
                            ? tagsRaw.readLines().collect { it.trim() }.findAll { it }
                            : []

                        if (tags.isEmpty()) {
                            echo "Для расширения '${name}' не найдены git-теги."
                            return
                        }

                        def currentIndex = tags.indexOf(lastExtTag)
                        if (currentIndex < 0) {
                            echo "Текущий PROD-тег '${lastExtTag}' для '${name}' не найден в репозитории. PRE-PROD обновление пропускаем."
                            return
                        }

                        if (currentIndex + 1 < tags.size()) {
                            def nextTag = tags[currentIndex + 1]
                            echo "Для '${name}' найден следующий тег: ${nextTag} (текущий PROD-тег: ${lastExtTag})"
                            extUpdates << "${name}:${nextTag}"
                        } else {
                            echo "Для '${name}' следующего тега после '${lastExtTag}' нет."
                        }
                    }

                    env.UPDATE_EXT_LIST = extUpdates ? (extUpdates.join(';') + ';') : ''
                    echo "Расширения для обновления в PRE-PROD: '${env.UPDATE_EXT_LIST}'"
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. Бэкап Production базы
        // -----------------------------------------------------------------
        stage('Backup Production DB') {
            steps {
                script {
                    echo "Создание бэкапа базы ${params.DB_PROD} на сервере ${params.SQL_PROD_SERVER}..."
                    echo "Путь: ${env.FULL_BACKUP_PATH}"

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlBackupToFile(
                            params.SQL_PROD_SERVER,
                            params.DB_PROD,
                            env.FULL_BACKUP_PATH,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3. Восстановление в Pre-Production
        // -----------------------------------------------------------------
        stage('Restore to Pre-Prod') {
            steps {
                script {
                    echo "Восстановление базы ${params.DB_PREPROD} на сервере ${params.SQL_PREPROD_SERVER}..."
                    echo "Источник: ${env.FULL_BACKUP_PATH}"

                    // Для надежности блокируем сеансы 1С перед restore
                    try {
                        withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                            // Игнорируем ошибки блокировки — база может быть недоступна, важно попытаться
                            utils.lockSessions(params.SERVER_1C_PREPROD, params.IB_PREPROD, RAC_USER, RAC_PASS, "ОбновлениеКонфигурации")
                        }
                    } catch (e) {
                        echo "⚠️ Не удалось заблокировать сеансы 1С (возможно, база недоступна). Продолжаем восстановление SQL."
                    }

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlRestore(
                            params.SQL_PREPROD_SERVER,
                            params.DB_PREPROD,
                            env.FULL_BACKUP_PATH,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3.5. Удаление Fix-расширений
        // -----------------------------------------------------------------
        stage('Delete Fix Extensions') {
            steps {
                script {
                    if (!fileExists(params.LAST_RELEASE_TASKS_FILE)) {
                        echo "Файл задач релиза не найден: ${params.LAST_RELEASE_TASKS_FILE}. Пропускаем удаление."
                        return
                    }

                    def taskKeys = readFile(file: params.LAST_RELEASE_TASKS_FILE, encoding: 'UTF-8')
                        .readLines()
                        .collect { it.trim() }
                        .findAll { it }

                    if (taskKeys.isEmpty()) {
                        echo "Файл задач пустой. Удаление fix-расширений пропускаем."
                        return
                    }

                    if (!fileExists(env.FIX_DELETE_EPF)) {
                        error "Не найдена внешняя обработка: ${env.FIX_DELETE_EPF}"
                    }

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        // Ждём, пока SQL база начнёт отвечать после RESTORE
                        utils.waitSqlReady(params.SQL_PREPROD_SERVER, params.DB_PREPROD, SQL_USER, SQL_PASS, 60, 5)

                        // Небольшой прогрев базы после RESTORE перед подключением 1С
                        int warmupWaitSec = 30
                        echo "⏳ Прогрев после RESTORE: ${warmupWaitSec}s"
                        sleep time: warmupWaitSec, unit: 'SECONDS'

                        utils.deleteFixExtensions(
                            env.FIX_DELETE_EPF,
                            params.v8version,
                            params.SERVER_1C_PREPROD,
                            params.DB_PREPROD,
                            SQL_USER,
                            SQL_PASS,
                            "ОбновлениеКонфигурации"
                        )
                    }
                }
            }
        }

        

        // -----------------------------------------------------------------
        // 4. Накатывание CF на Pre-Production
        // -----------------------------------------------------------------
        stage('Update Pre-Prod Config') {
            steps {
                script {
                    echo "Накатываем собранный CF на ${params.IB_PREPROD}..."

                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.updateDB_preprod_vrunner_resilient_after_restore(
                            env.OUTPUT_CF_FILE,
                            params.SERVER_1C_PREPROD,
                            params.SQL_PREPROD_SERVER,
                            params.IB_PREPROD,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 4.1. Деплой расширений по следующим тегам
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
                        def targetDir = "artifacts\\${name}"

                        echo "Развёртывание расширения '${name}' по следующему тегу '${tag}' из job '${jobName}'"

                        dir(targetDir) {
                            deleteDir()
                        }

                        copyArtifacts(
                            projectName: jobName,
                            selector: lastSuccessful(),
                            filter: "build/*${tag}*.cfe",
                            target: targetDir,
                            flatten: true
                        )

                        def cfeFiles = findFiles(glob: "${targetDir}/*.cfe")
                        if (cfeFiles.length == 0) {
                            error("Не найден .cfe с тегом '${tag}' в артефактах job '${jobName}'")
                        }
                        if (cfeFiles.length > 1) {
                            def names = cfeFiles.collect { it.path }.join(', ')
                            echo "Найдено несколько .cfe, беру первый: ${names}"
                        }

                        def cfeFile = cfeFiles[0].path
                        echo "Найден файл расширения: ${cfeFile}. Загружаем в базу PRE-PROD."

                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_PREPROD_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.updateExtension_via_ibcmd_or_vrunner(
                                cfeFile,
                                name,
                                params.SERVER_1C_PREPROD,
                                params.DB_PREPROD,
                                SQL_USER,
                                SQL_PASS
                            )
                        }

                        echo "Тег state-файла для '${name}' НЕ обновляем, чтобы PROD потом обновился этим же тегом."
                    }
                }
            }
        }

    
        // -----------------------------------------------------------------
        // 4.5. Проверка работоспособности базы
        // -----------------------------------------------------------------
        stage('Check DB Health') {
            steps {
                script {
                    if (!fileExists(env.CHECK_DB_EPF)) {
                        echo "⚠️ Обработка проверки БД не найдена: ${env.CHECK_DB_EPF}. Пропускаем."
                    } else {
                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_PREPROD_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.checkDbHealth(
                                env.CHECK_DB_EPF,
                                params.v8version,
                                params.SERVER_1C_PREPROD,
                                params.DB_PREPROD,
                                SQL_USER,
                                SQL_PASS,
                                "ОбновлениеКонфигурации"
                            )
                        }
                    }
                }
            }
        }


        // -----------------------------------------------------------------
        // 4.6. Проверка применимости расширений
        // -----------------------------------------------------------------
        stage('Check Extensions Applicability') {
            steps {
                script {
                    if (!fileExists(env.CHECK_EXT_APPLICABILITY_EPF)) {
                        echo "⚠️ Обработка проверки применимости расширений не найдена: ${env.CHECK_EXT_APPLICABILITY_EPF}. Пропускаем."
                    } else {
                        withCredentials([usernamePassword(
                            credentialsId: params.SQL_PREPROD_CRED,
                            usernameVariable: 'SQL_USER',
                            passwordVariable: 'SQL_PASS'
                        )]) {
                            utils.checkExtensionsApplicability(
                                env.CHECK_EXT_APPLICABILITY_EPF,
                                params.v8version,
                                params.SERVER_1C_PREPROD,
                                params.DB_PREPROD,
                                SQL_USER,
                                SQL_PASS,
                                "ОбновлениеКонфигурации"
                            )
                        }
                    }
                }
            }
        }
        
        // -----------------------------------------------------------------
        // 5. Очистка логов (Shrink)
        // -----------------------------------------------------------------
        stage('Shrink Pre-Prod Log') {
            steps {
                script {
                    echo "Очистка лога транзакций на ${params.IB_PREPROD}..."
                    withCredentials([usernamePassword(
                        credentialsId: params.SQL_PREPROD_CRED,
                        usernameVariable: 'SQL_USER',
                        passwordVariable: 'SQL_PASS'
                    )]) {
                        utils.mssqlShrinkLog(
                            params.SQL_PREPROD_SERVER,
                            params.DB_PREPROD,
                            SQL_USER,
                            SQL_PASS
                        )
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // Пытаемся разблокировать сеансы
                withCredentials([usernamePassword(credentialsId: params.RAC_CRED, usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                    utils.unlockSessions(params.SERVER_1C_PREPROD, params.IB_PREPROD, RAC_USER, RAC_PASS)
                }

                // Очистка бэкакапа (чтобы не забивать место)
                def backupFile = env.FULL_BACKUP_PATH
                if (fileExists(backupFile)) {
                    bat "del /Q \"${backupFile}\""
                }
            }
        }
        success {
            script {
                utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Обновление PRE-PROD успешно завершено!", true)
            }
        }
        failure {
        script {
            def failMessage = "Ошибка обновления PRE-PROD"

            if (fileExists("extensions_applicability_error.txt")) {
                def errMsg = readFile(file: "extensions_applicability_error.txt", encoding: "UTF-8").trim()
                if (errMsg) {
                    failMessage += "\n\n⚠️ **Ошибка применимости расширений:**\n`" + errMsg + "`"
                }
            } else if (fileExists("db_health_error.txt")) {
                def errMsg = readFile(file: "db_health_error.txt", encoding: "UTF-8").trim()
                if (errMsg) {
                    failMessage += "\n\n⚠️ **Ошибка при проверке базы (1С):**\n`" + errMsg + "`"
                }
            } else if (env.DB_HEALTH_CHECK_ERROR) {
                failMessage += "\n\n⚠️ **Ошибка при проверке базы (1С):**\n`" + env.DB_HEALTH_CHECK_ERROR + "`"
            }

            utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, failMessage, false)
        }
    }
    }
}
