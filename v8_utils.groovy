package io.libs

import java.util.Random
import org.apache.commons.lang.RandomStringUtils

/**
 * Утилитарная библиотека для CI/CD 1С
 * Содержит методы для:
 * - работы с Git
 * - вызова vrunner и ibcmd
 * - сборки и деплоя .cf / .cfe
 * - синхронизации хранилища 1С с Git
 */

def cmd(String command, String workDir = "") {
    if (workDir?.trim()) command = "cd /D \"${workDir}\" & ${command}"
    return bat(script: "chcp 65001 > nul\n${command}", returnStatus: true)
}

/** Проверка и создание директорий */
def ensureDirs(String... dirs) {
    for (def d : dirs) {
        if (d?.trim()) bat(script: "if not exist \"${d}\" mkdir \"${d}\"", returnStatus: true)
    }
}

/**
 * Парсит JSON-файл с описанием расширений.
 * @param jsonContent Содержимое JSON-файла в виде строки.
 * @return Список объектов, где каждый объект описывает одно расширение.
 */
@NonCPS
def parseExtensionsJson(String jsonContent) {
    def config = new groovy.json.JsonSlurper().parseText(jsonContent)
    def result = []
    if (config?.extensions) {
        config.extensions.each { ext ->
            result.add([
                name: ext.name.toString(),
                repo: ext.repo.toString(),
                path: ext.path.toString()
            ])
        }
    }
    return result
}


/** -------------------------- GIT ----------------------------- */
def git(String repoDir, String args) {
    return cmd("git ${args}", repoDir)
}

/**
 * Ищет и извлекает ключ задачи (например, 'ERP-1234') из текста коммита.
 * @param message Текст коммита.
 * @return Строка с номером задачи в верхнем регистре или null, если не найдено.
 */
@NonCPS
def extractIssueKey(String message) {
    if (!message) return null
    def m = (message =~ /(?i)#?([A-Z][A-Z0-9_]+-\d+)/)
    return m.find() ? m.group(1).toUpperCase() : null
}

/** ------------------------ Синхронизация хранилища 1С (gitsync) ------------------------- */

def executorId() {
    def raw = env.EXECUTOR_NUMBER ?: '0'
    def id = raw.replaceAll('[^0-9]', '')
    return id ? id : '0'
}

def shortWorkspace() {
    return env.SHORT_WS?.trim() ? env.SHORT_WS : env.WORKSPACE
}

/**
 * Junction с коротким путём на workspace: конфигуратор 1С не умеет писать
 * исходники ERP в длинный каталог Jenkins.
 * Снимается через unmountShortWorkspace() — rmdir только по junction.
 */
def mountShortWorkspace(String workspace) {
    if (!workspace?.trim()) error "Не задан каталог workspace для gitsync"
    def drive = workspace.substring(0, 2)
    if (!(drive ==~ /[A-Za-z]:/)) error "Ожидался путь с буквой диска, получен: ${workspace}"

    def id = executorId()
    def link = "${drive}\\t\\e${id}"
    def temp = "${drive}\\t\\tmp${id}"
    def rc = bat(script: """
        @echo off
        if not exist "${drive}\\t" mkdir "${drive}\\t"
        if exist "${link}" rmdir "${link}"
        if exist "${link}" (
            echo Не удалось снять ${link} . Каталог занят или это не junction.
            exit /b 1
        )
        if not exist "${temp}" mkdir "${temp}"
        mklink /J "${link}" "${workspace}"
        if errorlevel 1 exit /b 1
        exit /b 0
    """.stripIndent(), returnStatus: true)
    if (rc != 0) error "Не удалось создать короткий путь ${link} -> ${workspace}"

    env.SHORT_WS = link
    env.GITSYNC_SHORT_TEMP = temp
    echo "Короткий путь gitsync: ${link} -> ${workspace}. Временные файлы: ${temp}"
    return link
}

def unmountShortWorkspace() {
    def id = executorId()
    def link = env.SHORT_WS
    if (link?.trim()) {
        def drive = link.length() >= 2 ? link.substring(0, 2) : ''
        def expected = "${drive}\\t\\e${id}"
        if (link == expected) {
            // rmdir по junction снимает только связь, каталог репозитория не удаляется
            bat(script: "if exist \"${link}\" rmdir \"${link}\"", returnStatus: true)
        } else {
            echo "Пропускаю снятие короткого пути, неожиданный каталог: ${link}"
        }
        env.SHORT_WS = ''
    }

    def temp = env.GITSYNC_SHORT_TEMP
    if (temp?.trim()) {
        def drive = temp.length() >= 2 ? temp.substring(0, 2) : ''
        def expectedTemp = "${drive}\\t\\tmp${id}"
        if (temp == expectedTemp) {
            bat(script: "if exist \"${temp}\" rd /s /q \"${temp}\"", returnStatus: true)
        } else {
            echo "Пропускаю удаление временного каталога, неожиданный путь: ${temp}"
        }
        env.GITSYNC_SHORT_TEMP = ''
    }
}

def gitsyncTempDir() {
    if (env.GITSYNC_SHORT_TEMP?.trim()) return env.GITSYNC_SHORT_TEMP
    def sample = env.SHORT_WS ?: env.WORKSPACE
    if (!sample?.trim()) error "Не задан каталог для временных файлов gitsync"
    def temp = "${sample.substring(0, 2)}\\t\\tmp${executorId()}"
    bat(script: "if not exist \"${sample.substring(0, 2)}\\t\" mkdir \"${sample.substring(0, 2)}\\t\" & if not exist \"${temp}\" mkdir \"${temp}\"", returnStatus: true)
    env.GITSYNC_SHORT_TEMP = temp
    return temp
}

/**
 * Пытается включить плагины increment и limit.
 * Возвращает false, если после этого limit так и не появился в списке включённых.
 * В части сборок gitsync этих плагинов нет: тогда sync выгружает все версии целиком.
 */
def ensureGitsyncPlugins() {
    // call обязателен: gitsync.bat без call завершает весь командный файл на первой команде.
    // Список читаем из вывода команды: запись в файл после chcp 65001 на этом агенте не создаёт файл.
    bat(script: """
        @echo off
        call gitsync plugins init
        call gitsync plugins enable increment
        call gitsync plugins enable limit
    """.stripIndent(), returnStatus: true)

    def listed = bat(script: "@echo off\r\ncall gitsync plugins list -q", returnStdout: true)
    def names = listed.readLines().collect { it.trim().toLowerCase() }
    def hasLimit = names.contains('limit')
    def hasIncrement = names.contains('increment')
    env.GITSYNC_HAS_LIMIT = hasLimit ? 'true' : 'false'
    env.GITSYNC_HAS_INCREMENT = hasIncrement ? 'true' : 'false'
    echo "Включённые плагины:\n${listed}\nПлагин increment включён: ${env.GITSYNC_HAS_INCREMENT}. Плагин limit включён: ${env.GITSYNC_HAS_LIMIT}."
    return hasLimit
}

def runGitsyncCommand(String command) {
    def temp = gitsyncTempDir()
    return bat(script: """
        @echo off
        chcp 65001 > nul
        set "TEMP=${temp}"
        set "TMP=${temp}"
        set "GITSYNC_TEMP=${temp}"
        set "GITSYNC_VERBOSE=true"
        powershell -NoProfile -Command "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ${command}"
    """.stripIndent(), returnStatus: true)
}

/**
 * Синхронизирует хранилище 1С с Git-репозиторием через gitsync sync.
 * Выгрузка инкрементальная: в каталог исходников пишутся только изменения версии.
 * @param rep_1c Путь к хранилищу 1С.
 * @param rep_git_local_src_cf Локальный путь к каталогу /src/cf в Git-репозитории.
 * @return Код возврата процесса gitsync.
 */
def sync_hran(rep_1c, rep_git_local_src_cf, rep_git_remote, ext = "", aditional_parameters, server1c, repo_user, repo_pass) {
    if (ext?.trim()) { ext = "--ext ${ext.trim()}" } else { ext = "" }
    def command = "gitsync sync --storage-user \"${repo_user}\" --storage-pwd \"${repo_pass}\" ${ext} ${aditional_parameters} \"${rep_1c}\" \"${rep_git_local_src_cf}\""
    return runGitsyncCommand(command)
}

/**
 * Инициализирует репозиторий для выгрузки из хранилища 1С через gitsync init.
 * @param rep_1c Путь к хранилищу 1С.
 * @param rep_git_local_src_cf Локальный путь к каталогу /src/cf в Git-репозитории.
 * @return Код возврата процесса gitsync.
 */
def init_hran(rep_1c, rep_git_local_src_cf, ext = "", server1c = "", repo_user, repo_pass) {
    if (ext?.trim()) { ext = "--ext ${ext.trim()}" } else { ext = "" }
    def command = "gitsync init --storage-user \"${repo_user}\" --storage-pwd \"${repo_pass}\" ${ext} \"${rep_1c}\" \"${rep_git_local_src_cf}\""
    return runGitsyncCommand(command)
}


/** ------------------------ КОМПИЛЯЦИЯ ------------------------- */

/**
 * Сборка основной конфигурации (.cf) из исходников src\cf
 */
def compileCF_to_file_safe(String srcDir, String outputCfFile, String v8version = '8.3.27.2214') {
    ensureDirs(new File(outputCfFile).getParent())
    def cmdline = "vrunner compile --src \"${srcDir}\" --out \"${outputCfFile}\" --v8version \"${v8version}\""
    echo "Компиляция основной конфигурации в файл .cf..."
    def rc = cmd(cmdline)
    if (rc != 0) error "Ошибка компиляции .cf (код ${rc})"
    return rc
}

/**
 * Сборка расширения (.cfe) из исходников src\cfe
 */
def compileCFE_to_file_safe(String extName, String srcDir, String outputCfeFile, String v8version = '8.3.27.2214') {
    ensureDirs(new File(outputCfeFile).getParent())
    def cmdline = "vrunner compileexttocfe --src \"${srcDir}\" --out \"${outputCfeFile}\" --v8version \"${v8version}\""
    echo "Компиляция расширения '${extName}' в файл .cfe..."
    def rc = cmd(cmdline)
    if (rc != 0) error "Ошибка компиляции расширения (код ${rc})"
    return rc
}

/** ---------------------- ОБНОВЛЕНИЕ КОНФИГА ------------------- */

/**
 * Обновление основной конфигурации (.cf) через ibcmd (предпочтительно)
 * или fallback на vrunner (без --ibcmd, т.к. он не работает корректно).
 */
def updateDB_via_ibcmd_or_vrunner(String cfFile,
                                  String server,
                                  String serverSQL,
                                  String dbName,
                                  String sqlUser,
                                  String sqlPass,
                                  String v8version = '8.3.27.2214') {
    if (!fileExists(cfFile)) {
        error "Файл конфигурации не найден: ${cfFile}"
    }

    echo "=== Обновление конфигурации базы '${dbName}' ==="

    // 1) Загрузка CF через vrunner
    def rcLoad = bat(
        returnStatus: true,
        script: """
            @echo off
            chcp 65001 >nul
            setlocal enableextensions

            rem Опционально добавим bin платформы в PATH, если нужно
            if exist "C:\\Program Files\\1cv8\\${v8version}\\bin" (
                set "PATH=C:\\Program Files\\1cv8\\${v8version}\\bin;%PATH%"
            )

            echo [1/2] Загрузка CF: %DATE% %TIME%
            vrunner load ^
              --src "${cfFile}" ^
              --v8version "${v8version}" ^
              --ibconnection "/S${server}\\${dbName}" ^
              --db-user "${sqlUser}" --db-pwd "${sqlPass}" ^
              --uccode "ОбновлениеКонфигурации"

            exit /b %ERRORLEVEL%
        """
    )

    if (rcLoad != 0) {
        error "Ошибка при загрузке конфигурации через vrunner (код ${rcLoad})"
    }

    // 2) Применение конфигурации через ibcmd
    def rcApply = bat(
        returnStatus: true,
        script: """
            @echo off
            chcp 65001 >nul
            setlocal enableextensions

            rem Опционально добавим bin платформы в PATH, если нужно
            if exist "C:\\Program Files\\1cv8\\${v8version}\\bin" (
                set "PATH=C:\\Program Files\\1cv8\\${v8version}\\bin;%PATH%"
            )

            echo [2/2] Применение конфигурации ibcmd: %DATE% %TIME%
            ibcmd infobase config apply ^
              --dbms MSSQLServer ^
              --db-server="${serverSQL}" ^
              --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" ^
              --user="${sqlUser}" --password="${sqlPass}" ^
              --force

            exit /b %ERRORLEVEL%
        """
    )

    if (rcApply != 0) {
        error "Ошибка при применении конфигурации через ibcmd (код ${rcApply})"
    }

    echo "✅ Конфигурация '${dbName}' успешно обновлена."
    return 0
}


/**
 * Установка или обновление расширения (.cfe) через ibcmd (предпочтительно)
 * или fallback на vrunner loadext.
 */
def updateExtension_via_ibcmd_or_vrunner(String cfePath, String extName,
                                         String server, String dbName,
                                         String sqlUser, String sqlPass,
                                         String v8version = '8.3.27.2214') {
    if (!fileExists(cfePath)) error "Файл расширения не найден: ${cfePath}"

    echo "=== Обновление расширения '${extName}' в базе '${dbName}' ==="
    def hasIbcmd = (bat(script: "where ibcmd >nul 2>nul", returnStatus: true) == 0)
/**
    if (hasIbcmd) {
        echo "ibcmd найден — выполняем установку расширения напрямую."
        def rc = cmd("""
            ibcmd infobase config load --extension=${extName} "${cfePath}" ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --user="${sqlUser}" --password="${sqlPass}" --force
            && ibcmd infobase config apply --extension=${extName} ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --user="${sqlUser}" --password="${sqlPass}" --force
        """)
        if (rc != 0) error "Ошибка установки расширения через ibcmd (код ${rc})"
    } else {
        echo "ibcmd не найден — fallback на vrunner loadext."
        def rc = cmd("""
            vrunner loadext --file "${cfePath}" --extension ${extName} --updatedb \
              --v8version "${v8version}" \
              --ibconnection "/S${server}\\${dbName}" \
              --dbms-type mssql --dbms-server "${server}" --dbms-base "${dbName}" \
              --dbms-user "${sqlUser}" --dbms-pwd "${sqlPass}"
        """)
        if (rc != 0) error "Ошибка установки расширения через vrunner (код ${rc})"
    }
*/

    def rc = cmd("""
            vrunner loadext --file "${cfePath}" --extension ${extName} --updatedb \
              --v8version "${v8version}" \
              --ibconnection "/S${server}\\${dbName}" \
              --db-user "${sqlUser}" --db-pwd "${sqlPass}" --uccode "ОбновлениеКонфигурации"
        """)
    if (rc != 0) error "Ошибка установки расширения через vrunner (код ${rc})"

    echo "✅ Расширение '${extName}' успешно обновлено."
    return 0
}

/**
 * Устойчивое обновление PRE-PROD после RESTORE:
 * - ждём готовность SQL
 * - (опционально) повторно добиваем сессии
 * - vrunner load с ретраями, потому что после RESTORE кластер 1С иногда
 *   прибивает управляемый сеанс (в логах: «Сеанс работы завершен администратором»)
 *
 * Важно: здесь оставляем именно vrunner load (как ты хочешь), чтобы поддержка конфы
 * не ломалась нестандартным способом.
 */
def updateDB_preprod_vrunner_resilient_after_restore(String cfFile,
                                                     String server1c,
                                                     String serverSQL,
                                                     String dbName,
                                                     String sqlUser,
                                                     String sqlPass,
                                                     String v8version = '8.3.27.2214',
                                                     int attempts = 3,
                                                     int warmupWaitSec = 30) {

    if (!fileExists(cfFile)) {
        error "Файл конфигурации не найден: ${cfFile}"
    }

    echo "=== PRE-PROD: обновление конфигурации '${dbName}' через vrunner load (устойчивый режим) ==="

    // 1) Ждём SQL после restore
    waitSqlReady(serverSQL, dbName, sqlUser, sqlPass, 60, 5)

    // 2) Небольшой прогрев (после RESTORE иногда ещё дергаются фоновые/служебные вещи)
    echo "⏳ Прогрев после RESTORE: ${warmupWaitSec}s"
    sleep time: warmupWaitSec, unit: 'SECONDS'

    // 3) Несколько попыток vrunner load
    for (int i = 1; i <= attempts; i++) {
        echo "🔁 Попытка ${i}/${attempts}: добиваем сессии и грузим CF..."

        // на всякий: ещё раз блок+kill, чтобы никто не мешал
        def rcLock = bat(
            returnStatus: true,
            script: """
                @echo off
                chcp 65001 >nul
                vrunner session lock --ras "${server1c}" --db "${dbName}" ^
                  --cluster-admin "${sqlUser}" --cluster-pwd "${sqlPass}" ^
                  --db-user "${sqlUser}" --db-pwd "${sqlPass}" ^
                  --uccode "ОбновлениеКонфигурации"
                exit /b %ERRORLEVEL%
            """
        )
        echo "DEBUG: session lock rc=${rcLock} (для PRE-PROD не критично, если уже заблокировано)"

        def rcKill = bat(
            returnStatus: true,
            script: """
                @echo off
                chcp 65001 >nul
                vrunner session kill --ras "${server1c}" --db "${dbName}" ^
                  --cluster-admin "${sqlUser}" --cluster-pwd "${sqlPass}" ^
                  --db-user "${sqlUser}" --db-pwd "${sqlPass}" ^
                  --uccode "ОбновлениеКонфигурации"
                exit /b %ERRORLEVEL%
            """
        )
        echo "DEBUG: session kill rc=${rcKill} (для PRE-PROD не критично)"

        // контрольная пауза
        sleep time: 10, unit: 'SECONDS'

        // vrunner load 
        def rcLoad = bat(
            returnStatus: true,
            script: """
                @echo off
                chcp 65001 >nul
                setlocal enableextensions

                if exist "C:\\Program Files\\1cv8\\${v8version}\\bin" (
                    set "PATH=C:\\Program Files\\1cv8\\${v8version}\\bin;%PATH%"
                )

                echo [VRUNNER LOAD] %DATE% %TIME%
                vrunner load ^
                  --src "${cfFile}" ^
                  --v8version "${v8version}" ^
                  --ibconnection "/S${server1c}\\${dbName}" ^
                  --db-user "${sqlUser}" --db-pwd "${sqlPass}" ^
                  --uccode "ОбновлениеКонфигурации"

                exit /b %ERRORLEVEL%
            """
        )

        if (rcLoad == 0) {
            echo "✅ PRE-PROD: загрузка CF завершена успешно."

            // 2) Применение конфигурации через ibcmd
            def rcApply = bat(
                returnStatus: true,
                script: """
                    @echo off
                    chcp 65001 >nul
                    setlocal enableextensions

                    echo [2/2] Применение конфигурации ibcmd: %DATE% %TIME%
                    ibcmd infobase config apply ^
                    --dbms MSSQLServer ^
                    --db-server="${serverSQL}" ^
                    --db-name="${dbName}" ^
                    --db-user="${sqlUser}" --db-pwd="${sqlPass}" ^
                    --user="${sqlUser}" --password="${sqlPass}" ^
                    --force

                    exit /b %ERRORLEVEL%
                """
            )

            if (rcApply != 0) {
                error "Ошибка при применении конфигурации через ibcmd (код ${rcApply})"
            }
            return 0
        }

        echo "⚠ PRE-PROD: vrunner load упал (rc=${rcLoad})."

        // если это не последняя попытка, ждём и пробуем ещё раз
        if (i < attempts) {
            int backoff = 20 * i
            echo "⏳ Подождём ${backoff}s и повторим (после RESTORE кластер бывает в ступоре)."
            sleep time: backoff, unit: 'SECONDS'
        }
    }

    error "PRE-PROD: не удалось загрузить CF через vrunner после ${attempts} попыток."
}

/** ---------------------- TELEGRAM --------------------- */

/** Уведомление в Telegram */
def telegram_send_message(TOKEN, CHAT_ID, messageText, success) {
    // Префикс по статусу: зелёная галка или красный крестик
    def prefix = success ? "✅ " : "❌ "
    messageText = prefix + (messageText ?: "")

    def details = []

    // Ссылка на сборку
    if (env.BUILD_URL) {
        details << "Сборка: ${env.BUILD_URL}"
    }

    // Репозиторий
    def repoUrl = ""
    if (env.rep_git_remote?.trim()) {
        repoUrl = env.rep_git_remote.trim()
    } else if (env.GIT_REPO_URL?.trim()) {
        repoUrl = env.GIT_REPO_URL.trim()
    }
    if (repoUrl) {
        if (!repoUrl.toLowerCase().startsWith("http")) {
            repoUrl = "https://${repoUrl}"
        }
        repoUrl = repoUrl.replaceAll(/\.git$/, "")
        details << "Репозиторий: ${repoUrl}"
    }

    // Ветки, если переданы
    def branches = env.GITSYNC_UPDATED_BRANCHES?.trim()
    if (branches) {
        details << "Ветки: ${branches}"
    }

    if (!details.isEmpty()) {
        messageText = messageText + "\n" + details.join("\n")
    }

    telegram_send_safe(TOKEN, CHAT_ID, messageText, true)
}

/**
 * Безопасная отправка в MAX через curl
 * (НЕ валит pipeline при сетевых проблемах, использует ретраи)
 */
def telegram_send_safe(String token, String chatId, String text, boolean disablePreview = true) {
    try {
        def ws = pwd()
        def msgFile = "${ws}\\tmp_max_message.json"

        def jsonText = groovy.json.JsonOutput.toJson([
            text: text,
            disable_link_preview: disablePreview,
            notify: true
        ])

        writeFile file: msgFile, text: jsonText, encoding: "UTF-8"

        int rc = bat(
            returnStatus: true,
            script: """
                chcp 65001 >nul
                curl --http1.1 --tlsv1.2 --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 90 ^
                  -X POST "https://platform-api.max.ru/messages?chat_id=${chatId}" ^
                  -H "Authorization: ${token}" ^
                  -H "Content-Type: application/json; charset=utf-8" ^
                  --data-binary "@${msgFile}"
            """.stripIndent()
        )

        if (rc != 0) {
            echo "⚠️ MAX notify failed (exit code ${rc}). Продолжаем выполнение пайплайна."
        }
    } catch (Throwable e) {
        echo "⚠️ MAX notify threw exception: ${e}. Продолжаем выполнение пайплайна."
    }
}

/**
 * Простое уведомление для пользовательского чата без тех. деталей
 */
def telegram_send_user_message(String token, String chatId, String messageText, boolean success = true) {
    def prefix = success ? "✅ " : "❌ "
    def finalText = prefix + (messageText ?: "")
    telegram_send_safe(token, chatId, finalText, true)
}

/** ---------------------- BACKUP --------------------- */

/**
 * Бэкап MSSQL базы в конкретный файл
 */
def mssqlBackupToFile(String server, String dbName, String fullBackupPath, String sqlUser, String sqlPass) {
    ensureDirs(new File(fullBackupPath).getParent())
    def script = """
        sqlcmd -S "${server}" -U "${sqlUser}" -P "${sqlPass}" -b -Q "BACKUP DATABASE [${dbName}] TO DISK='${fullBackupPath}' WITH COPY_ONLY, INIT, COMPRESSION, STATS=5"
        exit /b %errorlevel%
    """.trim()
    def rc = bat(script: "chcp 65001 > nul\n${script}", returnStatus: true)
    if (rc != 0) error "Ошибка резервного копирования MSSQL в файл ${fullBackupPath}"
    return rc
}

/**
 * Восстановление MSSQL базы из файла (.bak)
 * ВНИМАНИЕ: Перезаписывает существующую базу (WITH REPLACE) и сбрасывает соединения!
 */
def mssqlRestore(String server, String dbName, String fullBackupPath, String sqlUser, String sqlPass) {
    if (!fileExists(fullBackupPath)) {
        // Если путь сетевой, fileExists может врать, но попробуем довериться sqlcmd.
        // Но лучше проверить, если это локальный путь. Для сетевого шары Jenkins может не видеть, а SQL видеть.
        // Оставим проверку на совесть sqlcmd, или добавим check.
        echo "Внимание: Файл бэкапа ${fullBackupPath} будет передан SQL серверу для восстановления."
    }

    // Скрипт:
    // 1. Перевод в SINGLE_USER с ROLLBACK IMMEDIATE (киляем сессии)
    // 2. RESTORE DATABASE ... WITH REPLACE
    // 3. Перевод обратно в MULTI_USER (обычно restore сам делает, но на всякий случай)
    
    // Важно: нужно знать логические имена файлов (Move 'LogicalName' TO 'PhysicalFile'), 
    // если пути на серверах отличаются.
    // Пока предположим, что пути дефолтные или совпадают, либо используем просто REPLACE если имена файлов совпадают.
    // Если пути разные, restore может упасть.
    // Для надежности часто делают RESTORE FILELISTONLY, парсят, и подставляют MOVE.
    // Но для начала сделаем простой RESTORE WITH REPLACE. Если упадет - будем усложнять.
    
    def script = """
        sqlcmd -S "${server}" -U "${sqlUser}" -P "${sqlPass}" -b -Q "ALTER DATABASE [${dbName}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; RESTORE DATABASE [${dbName}] FROM DISK='${fullBackupPath}' WITH REPLACE; ALTER DATABASE [${dbName}] SET MULTI_USER;"
        exit /b %errorlevel%
    """.trim()

    echo "=== Восстановление базы '${dbName}' из '${fullBackupPath}' на сервере '${server}' ==="
    def rc = bat(script: "chcp 65001 > nul\n${script}", returnStatus: true)
    if (rc != 0) error "Ошибка восстановления MSSQL базы ${dbName}"
    echo "✅ База '${dbName}' успешно восстановлена."
    return rc
}

/**
 * Очистка лога транзакций (перевод в SIMPLE + shrink)
 */
def mssqlShrinkLog(String server, String dbName, String sqlUser, String sqlPass) {
    echo "=== Очистка лога транзакций базы '${dbName}' ==="
    // Переводим в SIMPLE и делаем SHRINKDATABASE (TRUNCATEONLY), чтобы срезать лог.
    // Для боевых баз лучше быть осторожнее, но для Pre-Prod - это норма.
    def script = """
        sqlcmd -S "${server}" -U "${sqlUser}" -P "${sqlPass}" -b -Q "ALTER DATABASE [${dbName}] SET RECOVERY SIMPLE; DBCC SHRINKDATABASE ([${dbName}], 10, TRUNCATEONLY);"
        exit /b %errorlevel%
    """.trim()

    def rc = bat(script: "chcp 65001 > nul\n${script}", returnStatus: true)
    if (rc != 0) {
        echo "⚠️ Ошибка при очистке лога транзакций (код ${rc}). Не критично."
    } else {
        echo "✅ Лог транзакций очищен."
    }
    return rc
}

/** Бэкап MSSQL базы (перед деплоем) */
def mssqlBackup(String server, String dbName, String backupDir, String sqlUser, String sqlPass) {
    ensureDirs(backupDir)
    def script = """
        setlocal enableextensions
        for /f %%a in ('powershell -NoProfile -Command "(Get-Date).ToString(\\"yyyyMMdd_HHmmss\\")"') do set "TS=%%a"
        set "BAK=${backupDir}\\${dbName}_%TS%.bak"
        sqlcmd -S "${server}" -U "${sqlUser}" -P "${sqlPass}" -b -Q "BACKUP DATABASE [${dbName}] TO DISK='%BAK%' WITH COPY_ONLY, INIT, COMPRESSION, STATS=5"
        exit /b %errorlevel%
    """.trim()
    def rc = bat(script: "chcp 65001 > nul\n${script}", returnStatus: true)
    if (rc != 0) error "Ошибка резервного копирования MSSQL"
    return rc
}

/**
 * Ждём, пока SQL база начнёт отвечать после RESTORE/переключений.
 * Пытаемся выполнить простой SELECT 1 в контексте нужной базы.
 */
def waitSqlReady(String serverSQL,
                 String dbName,
                 String sqlUser,
                 String sqlPass,
                 int attempts = 60,
                 int sleepSec = 5) {

    echo "⏳ Ожидание доступности SQL базы '${dbName}' на '${serverSQL}' (attempts=${attempts}, sleep=${sleepSec}s)..."

    for (int i = 1; i <= attempts; i++) {
        def rc = bat(
            returnStatus: true,
            script: """
                @echo off
                chcp 65001 >nul
                sqlcmd -S "${serverSQL}" -U "${sqlUser}" -P "${sqlPass}" -d "${dbName}" -b -Q "SET NOCOUNT ON; SELECT 1;"
                exit /b %ERRORLEVEL%
            """
        )

        if (rc == 0) {
            echo "✅ SQL база '${dbName}' отвечает."
            return 0
        }

        echo "…попытка ${i}/${attempts} неудачна (rc=${rc}). Ждём ${sleepSec}s."
        sleep time: sleepSec, unit: 'SECONDS'
    }

    error "SQL база '${dbName}' не стала доступной за ${attempts * sleepSec} секунд."
}

/** -----------------------------------------------------------
 *  УПРАВЛЕНИЕ СЕАНСАМИ ПОЛЬЗОВАТЕЛЕЙ 1С
 * ----------------------------------------------------------- */

/**
 * Захватывает эксклюзивную блокировку сеансов пользователей.
 * Использует rac-доступ к кластеру 1С.
 */
def lockSessions(String ras, String dbName, String racUser, String racPass, String reason = "Обновление конфигурации") {
    echo "🔒 Блокировка сеансов пользователей перед обновлением (${dbName})..."
    def rcLock = cmd("""
        vrunner session lock --ras ${ras} --db ${dbName} \
          --cluster-admin "${racUser}" --cluster-pwd "${racPass}" \
          --db-user "${racUser}" --db-pwd "${racPass}" \
          --uccode "ОбновлениеКонфигурации"
    """)
    if (rcLock != 0) error "Не удалось заблокировать сеансы пользователей (код ${rcLock})"
    echo "✅ Сеансы пользователей заблокированы."

    echo "🗡 Удаление активных сессий (${dbName})..."
    def rcKill = cmd("""
        vrunner session kill --ras ${ras} --db ${dbName} \
          --cluster-admin "${racUser}" --cluster-pwd "${racPass}" \
          --db-user "${racUser}" --db-pwd "${racPass}" \
          --uccode "ОбновлениеКонфигурации" --debuglog
    """)
    if (rcKill != 0) {
        echo "⚠ Не удалось корректно завершить все сессии (код ${rcKill}). Продолжаем, так как блокировка активна."
    } else {
        echo "✅ Активные сессии завершены."
    }
}

/**
 * Снимает блокировку сеансов.
 */
def unlockSessions(String ras, String dbName, String racUser, String racPass) {
    echo "🔓 Снятие блокировки сеансов пользователей (${dbName})..."
    def rc = cmd("""
        vrunner session unlock --ras ${ras} --db ${dbName} \
          --cluster-admin "${racUser}" --cluster-pwd "${racPass}" \
          --db-user "${racUser}" --db-pwd "${racPass}" \
          --uccode "ОбновлениеКонфигурации"
    """)
    if (rc != 0) echo "⚠ Не удалось корректно снять блокировку (код ${rc})"
    else echo "✅ Блокировка снята."
}

// ========================================================================
// Логика cherry-pick
// ========================================================================

/**
 * Проверка, является ли коммит merge-коммитом.
 * Если у коммита больше одного родителя — это merge.
 */
def isMergeCommit(String repoDir, String commit) {
    git(repoDir, "rev-list --parents -n 1 ${commit} > .git\\parents.txt")
    def content = readFile(file: "${repoDir}\\.git\\parents.txt", encoding: 'UTF-8').trim()
    cmd("cd /D \"${repoDir}\" & del /Q .git\\parents.txt 2>nul")
    if (!content) return false
    def parts = content.split(/\s+/)
    return parts.size() > 2
}

/**
 * Главный метод распределения коммитов из 1C_REPO по feature-веткам.
 * Анализирует новые коммиты, определяет по их тексту номер задачи и выполняет
 * cherry-pick в соответствующую feature-ветку.
 * Приоритет: состояние из хранилища 1С (ветка 1C_REPO / коммит), а не то,
 * что уже в feature-ветке.
 */
/**
 * В индексе и в переносимом коммите один путь может отличаться только регистром.
 * На Windows cherry-pick тогда останавливается. Переименовывает такие пути
 * к варианту из коммита и фиксирует это отдельным коммитом.
 */
def normalizeCaseForCommit(String repoDir, String commit) {
    writeFile file: '.git/normalize-case.ps1', encoding: 'UTF-8', text: '''
chcp 65001 > $null
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
Set-Location -LiteralPath $env:CASE_REPO
$index = @{}
git -c core.quotepath=false ls-files | ForEach-Object {
    if ($_) { $index[$_.ToLowerInvariant()] = $_ }
}
$pairs = @()
$diskMoves = @{}
git -c core.quotepath=false diff-tree --no-commit-id --name-only -r $env:CASE_COMMIT | ForEach-Object {
    $p = $_
    if (-not $p) { return }
    $key = $p.ToLowerInvariant()
    if (-not $index.ContainsKey($key)) { return }
    $oldFull = $index[$key]
    if ($oldFull -ceq $p) { return }
    $pairs += ,@($oldFull, $p)
    $a = $oldFull -split '/'
    $b = $p -split '/'
    $accA = New-Object System.Collections.Generic.List[string]
    $accB = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt [Math]::Min($a.Count, $b.Count); $i++) {
        $accA.Add($a[$i])
        $accB.Add($b[$i])
        if ($a[$i] -cne $b[$i]) {
            $diskMoves[($accA -join '/')] = ($accB -join '/')
            break
        }
    }
}
if ($pairs.Count -eq 0) { exit 0 }
$ordered = $diskMoves.GetEnumerator() | Sort-Object { ($_.Key -split '/').Count }
foreach ($m in $ordered) {
    $parentRel = ($m.Key -split '/') | Select-Object -SkipLast 1
    $parent = if ($parentRel) { Join-Path (Get-Location) ($parentRel -join [char]92) } else { Get-Location }
    $leafOld = ($m.Key -split '/')[-1]
    $leafNew = ($m.Value -split '/')[-1]
    $from = Join-Path $parent $leafOld
    if (-not (Test-Path -LiteralPath $from)) { continue }
    $tmp = Join-Path $parent ($leafOld + '.__case_tmp__')
    $to = Join-Path $parent $leafNew
    Move-Item -LiteralPath $from -Destination $tmp -Force
    Move-Item -LiteralPath $tmp -Destination $to -Force
    Write-Output "disk-rename $($m.Key) -> $($m.Value)"
}
foreach ($pair in $pairs) {
    $old = $pair[0]
    $new = $pair[1]
    $line = git -c core.quotepath=false ls-files -s -- $old | Select-Object -First 1
    if (-not $line) { Write-Output "skip missing $old"; continue }
    $bits = $line.Split(@([char]32, [char]9), 4, [StringSplitOptions]::RemoveEmptyEntries)
    git update-index --force-remove -- $old
    if ($LASTEXITCODE -ne 0) { Write-Output "update-index remove failed: $old"; exit 1 }
    git update-index --add --cacheinfo "$($bits[0]),$($bits[1]),$new"
    if ($LASTEXITCODE -ne 0) { Write-Output "update-index add failed: $new"; exit 1 }
    Write-Output "case-rename $old -> $new"
}
git diff --cached --quiet
if ($LASTEXITCODE -eq 0) { exit 0 }
git commit -m "normalize path case for cherry-pick"
exit $LASTEXITCODE
'''
    withEnv(["CASE_REPO=${repoDir}", "CASE_COMMIT=${commit}"]) {
        def rc = bat(script: "powershell -NoProfile -ExecutionPolicy Bypass -File \"${repoDir}\\.git\\normalize-case.ps1\"", returnStatus: true)
        if (rc != 0) {
            error "Не удалось нормализовать регистр путей перед cherry-pick ${commit} (код ${rc})"
        }
    }
}

def cherryPickTasksFrom1CRepo(String repoDir, String remoteHttps, String baseBranch = "1C_REPO", String compareBranch = "branch_sync_1c_repo") {

    echo "[cherryPickTasksFrom1CRepo] repoDir=${repoDir}, base=${baseBranch}, compare=${compareBranch}"

    if (!repoDir?.trim()) {
        error "repoDir не задан для cherryPickTasksFrom1CRepo"
    }

    // На всякий случай нормализуем fetch
    git(repoDir, 'config core.commentChar ";"')
    git(repoDir, 'config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"')
    git(repoDir, "fetch --all --prune")

    // Гарантируем наличие baseBranch
    def rc = git(repoDir, "show-ref --verify --quiet refs/heads/${baseBranch}")
    if (rc != 0) {
        rc = git(repoDir, "show-ref --verify --quiet refs/remotes/origin/${baseBranch}")
        if (rc != 0) {
            error "Базовая ветка ${baseBranch} не найдена ни локально, ни в origin"
        }
        git(repoDir, "checkout -B \"${baseBranch}\" \"origin/${baseBranch}\"")
    } else {
        git(repoDir, "checkout \"${baseBranch}\"")
    }

    // Гарантируем наличие compareBranch
    rc = git(repoDir, "show-ref --verify --quiet refs/heads/${compareBranch}")
    if (rc != 0) {
        rc = git(repoDir, "show-ref --verify --quiet refs/remotes/origin/${compareBranch}")
        if (rc == 0) {
            echo "[cherryPickTasksFrom1CRepo] Локальной ${compareBranch} нет, но есть origin – чекаутим"
            git(repoDir, "checkout -B \"${compareBranch}\" \"origin/${compareBranch}\"")
        } else {
            echo "[cherryPickTasksFrom1CRepo] Ветка ${compareBranch} не найдена ни локально, ни в origin – создаю от ${baseBranch}"
            git(repoDir, "checkout -B \"${compareBranch}\" \"${baseBranch}\"")
            git(repoDir, "push -u origin \"${compareBranch}\"")
        }
    }

    // Список коммитов, которых нет в compareBranch
    git(repoDir, "checkout \"${baseBranch}\"")
    git(repoDir, "log --reverse ${compareBranch}..${baseBranch} --pretty=format:\"%%h;%%s\" > .git\\commit_list.txt")
    def listContent = readFile(file: "${repoDir}\\.git\\commit_list.txt", encoding: 'UTF-8')
    cmd("cd /D \"${repoDir}\" & del /Q .git\\commit_list.txt 2>nul")

    if (!listContent?.trim()) {
        echo "[cherryPickTasksFrom1CRepo] Новых коммитов между ${compareBranch} и ${baseBranch} нет"
        env.GITSYNC_NO_NEW_COMMITS = "true"
        env.GITSYNC_UPDATED_BRANCHES = ""
        return 0
    }

    // Парсим: каждый коммит → все #TASK-123 из сабжекта
    def entries = []
    listContent.readLines().each { line ->
        line = line.trim()
        if (!line) return

        def parts = line.split(";", 2)
        if (parts.length < 2) return

        def shortHash = parts[0].trim()
        def subject   = parts[1].trim()

        def matcher = (subject =~ /(?i)#?([A-Z][A-Z0-9_]*-\d+)/)
        matcher.each { m ->
            def issueKey = m[1]
            if (issueKey) {
                entries << [commit: shortHash, issueKey: issueKey]
            }
        }
    }

    if (!entries) {
        echo "[cherryPickTasksFrom1CRepo] В новых коммитах не найдено ни одного номера задачи вида #XXX-123"
        env.GITSYNC_NO_NEW_COMMITS = "true"
        env.GITSYNC_UPDATED_BRANCHES = ""
        return 0
    }

    def updatedBranches = [] as Set

    for (def entry in entries) {
        def commit       = entry.commit
        def issueKey     = entry.issueKey
        def featureBranch = "feature/${issueKey}"

        echo "----------------------------------------------"
        echo "Обработка ${featureBranch} / ${commit}"

        // Перед обработкой КАЖДОЙ задачи выметаем все локальные хвосты,
        // чтобы checkout другой ветки не орал про local changes.
        git(repoDir, "reset --hard")
        git(repoDir, "clean -fdx")

        def hasLocalFeature  = (git(repoDir, "show-ref --verify --quiet refs/heads/${featureBranch}") == 0)
        def hasRemoteFeature = (git(repoDir, "show-ref --verify --quiet refs/remotes/origin/${featureBranch}") == 0)

        // -f нужен на Windows: в репозитории есть пути, отличающиеся только регистром,
        // и обычный checkout останавливается на «untracked working tree files».
        if (!hasLocalFeature && !hasRemoteFeature) {
            echo "- Ветка ${featureBranch} не найдена ни локально, ни в origin. Создаю от ${compareBranch}"
            rc = git(repoDir, "checkout -f -B \"${featureBranch}\" \"${compareBranch}\"")
        } else if (!hasLocalFeature && hasRemoteFeature) {
            echo "- Локальной ветки нет, но есть origin/${featureBranch}. Чекаутим её"
            rc = git(repoDir, "checkout -f -B \"${featureBranch}\" \"origin/${featureBranch}\"")
        } else if (hasLocalFeature && !hasRemoteFeature) {
            echo "- Ветка ${featureBranch} есть локально, а в origin нет. Использую локальную"
            rc = git(repoDir, "checkout -f \"${featureBranch}\"")
        } else {
            echo "- Ветка ${featureBranch} есть и локально, и в origin. Синхронизирую с origin"
            rc = git(repoDir, "checkout -f \"${featureBranch}\"")
            if (rc == 0) rc = git(repoDir, "reset --hard \"origin/${featureBranch}\"")
        }
        if (rc != 0) {
            error "Не удалось перейти на ветку ${featureBranch} (код ${rc})"
        }

        def isMerge = isMergeCommit(repoDir, commit)
        def cherryPickCmd = isMerge
                ? "cherry-pick --keep-redundant-commits -X theirs -m 1 ${commit}"
                : "cherry-pick --keep-redundant-commits -X theirs ${commit}"

        rc = git(repoDir, cherryPickCmd)

        if (rc != 0) {
            echo "Cherry-pick коммита ${commit} в ${featureBranch} вернул код ${rc}. Снимаю конфликт регистра и повторяю."
            git(repoDir, "cherry-pick --abort")
            normalizeCaseForCommit(repoDir, commit)
            rc = git(repoDir, cherryPickCmd)
        }

        if (rc != 0) {
            echo "Cherry-pick коммита ${commit} в ${featureBranch} вернул код ${rc}. Пытаюсь авторазрулить конфликты."

            git(repoDir, "diff --name-only --diff-filter=U > .git\\conflicts.txt")
            def conflicts = readFile(file: "${repoDir}\\.git\\conflicts.txt", encoding: 'UTF-8').trim()
            cmd("cd /D \"${repoDir}\" & del /Q .git\\conflicts.txt 2>nul")

            if (conflicts) {
                echo "Найдены конфликтующие файлы:\n${conflicts}"
                // Берём вариант из целевой ветки (theirs) и доклеиваем
                git(repoDir, "checkout --theirs .")
                git(repoDir, "add .")
                rc = git(repoDir, "cherry-pick --continue")
            }

            if (rc != 0) {
                git(repoDir, "cherry-pick --abort || git reset --hard")
                error "Не удалось автоматически разрешить конфликт cherry-pick коммита ${commit} в ветке ${featureBranch}. Код ${rc}"
            }
        }

        rc = git(repoDir, "push origin \"${featureBranch}\"")
        if (rc != 0) {
            error "Не удалось запушить ветку ${featureBranch} в origin (код ${rc})"
        }

        updatedBranches << featureBranch
    }

    git(repoDir, "checkout \"${baseBranch}\"")

    env.GITSYNC_NO_NEW_COMMITS   = "false"
    env.GITSYNC_UPDATED_BRANCHES = updatedBranches.join(' ')

    echo "[cherryPickTasksFrom1CRepo] Обновлены ветки: ${env.GITSYNC_UPDATED_BRANCHES}"
    echo "----------------------------------------------"

    return 0
}


/**
 * Скачивает ветку репозитория, подготавливает директории и подтягивает теги.
 * Эта функция заменяет шаги клонирования и fetch в пайплайнах генерации задач.
 *
 * @param branchName Имя ветки для чекаута
 * @param remoteUrl Репозиторий (без кредов, передается через окружение)
 * @param workspaceDir Рабочая директория (обычно env.WORKSPACE)
 * @param stateDir Директория для хранения стейт-файлов, которая будет создана при отсутствии
 * @param gitUser Пользователь Git
 * @param gitToken Токен или пароль Git
 */
def checkoutBranchAndFetchTags(String branchName, String remoteUrl, String workspaceDir, String stateDir, String gitUser, String gitToken) {
    echo "Checkout ветки ${branchName}..."
    def fullRemoteUrl = "https://${gitUser}:${gitToken}@${remoteUrl}"
    cmd("git clone --branch ${branchName} --single-branch ${fullRemoteUrl} .", workspaceDir)

    echo "Создание директории для стейтов (если не существует): ${stateDir}"
    ensureDirs(stateDir)

    echo "Обновление тегов (git fetch --tags --force)..."
    def rc = cmd("git fetch --tags --force", workspaceDir)
    if (rc != 0) {
        error "Ошибка при получении тегов из репозитория (код ${rc})"
    }
}
/**
 * Финальная синхронизация. Обновляет служебную ветку branch_sync_1c_repo,
 * чтобы отметить коммиты как обработанные и не обрабатывать их в следующий раз.
 */
def updateBranchSyncFrom1CRepo(String repoDir, String remoteHttps, String baseBranch = "1C_REPO", String compareBranch = "branch_sync_1c_repo") {
    if (!repoDir?.trim()) error "updateBranchSync: repoDir is empty"
    git(repoDir, "fetch --all --prune")

    def hasLocalCompare  = (git(repoDir, "show-ref --verify --quiet refs/heads/${compareBranch}") == 0)
    def hasRemoteCompare = (git(repoDir, "show-ref --verify --quiet refs/remotes/origin/${compareBranch}") == 0)

    if (hasLocalCompare) {
        git(repoDir, "checkout -f \"${compareBranch}\"")
    } else if (hasRemoteCompare) {
        git(repoDir, "checkout -f -B \"${compareBranch}\" \"origin/${compareBranch}\"")
    } else {
        echo "Ветка ${compareBranch} не найдена ни локально, ни в origin. Создаю её от ${baseBranch}."
        git(repoDir, "checkout -f -B \"${compareBranch}\" \"${baseBranch}\"")
    }

    git(repoDir, "reset --hard")
    git(repoDir, "merge \"${baseBranch}\" --no-edit")
    git(repoDir, "push origin \"${compareBranch}\"")
    git(repoDir, "checkout \"${baseBranch}\"")
    return 0
}

/**
 * Выполняет обработку удаления fix-расширений
 */
def deleteFixExtensions(String epfPath, String v8version, String server1c, String dbName, String dbUser, String dbPass, String uccode = "ОбновлениеКонфигурации") {
    echo "=== Удаление fix-расширений: ${epfPath} ==="
    
    def rc = bat(
        returnStatus: true,
        script: """
            @echo off
            chcp 65001 >nul
            setlocal enableextensions
            vrunner run --execute "${epfPath}" --v8version "${v8version}" --ibconnection "/S${server1c}\\${dbName}" --db-user "${dbUser}" --db-pwd "${dbPass}" --uccode "${uccode}" --command "/DisableStartupMessages /DisableStartupDialogs" > delete_fix_ext.log 2>&1
            set VRUNNER_RC=%ERRORLEVEL%
            type delete_fix_ext.log
            exit /b %VRUNNER_RC%
        """.stripIndent()
    )

    def logContent = ""
    if (fileExists("delete_fix_ext.log")) {
        logContent = readFile(file: "delete_fix_ext.log", encoding: "UTF-8")
    }

    boolean hasError = false
    def lines = logContent.readLines()
    for (String line : lines) {
        String trimmed = line.trim()
        if (trimmed.startsWith("{") && trimmed.contains("}: ") && trimmed.contains("(")) {
            hasError = true
            echo "❌ Найдена ошибка компиляции/выполнения: ${trimmed}"
        } else if (trimmed.contains("Критическая ошибка") || trimmed.contains("Невосстановимая ошибка")) {
            hasError = true
            echo "❌ Найдена критическая ошибка: ${trimmed}"
        }
    }

    if (rc != 0 || hasError) {
        error "Ошибка удаления fix-расширений через vrunner (код ${rc}, найдены ошибки в логе)"
    }
    
    echo "✅ Удаление fix-расширений завершено."
    return rc
}

/**
 * Удаление расширения через 1cv8 DESIGNER (/DeleteCfg -Extension).
 * Не вызывает error(): при сбое только логирует предупреждение (пайплайн продолжается).
 *
 * @param logFile путь к файлу лога 1С (/Out), каталог создаётся при необходимости
 * @return код возврата процесса 1cv8 или отрицательный при исключении
 */
def deleteCfgExtensionViaDesignerResilient(String v8version, String server1c, String dbName, String dbUser, String dbPass, String extensionName, String logFile) {
    echo "=== Удаление расширения через Конфигуратор (устойчивый режим): ${extensionName} ==="

    def exePath = "C:\\Program Files\\1cv8\\${v8version}\\bin\\1cv8.exe"
    def ibConn = "${server1c}\\${dbName}"
    def psQuote = { String s ->
        if (!s) return ''
        s.replace("'", "''").replace("\r", " ").replace("\n", " ")
    }

    def logParent = new File(logFile).getParent()
    if (logParent?.trim()) {
        ensureDirs(logParent)
    }

    def ps1 = """\$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
\$exe = '${psQuote(exePath)}'
\$ib = '${psQuote(ibConn)}'
\$nu = '${psQuote(dbUser)}'
\$np = '${psQuote(dbPass)}'
\$ne = '${psQuote(extensionName)}'
\$nl = '${psQuote(logFile)}'
if (-not (Test-Path -LiteralPath \$exe)) {
    Write-Host "Не найден 1cv8.exe: \$exe"
    exit 2
}
\$argList = @('DESIGNER', '/S', \$ib, '/N', \$nu, '/P', \$np, '/DeleteCfg', '-Extension', \$ne, '/DisableStartupDialogs', '/Out', \$nl)
\$p = Start-Process -FilePath \$exe -ArgumentList \$argList -Wait -PassThru -NoNewWindow
Write-Host ("ExitCode=" + \$p.ExitCode)
if (Test-Path -LiteralPath \$nl) {
    Get-Content -LiteralPath \$nl -ErrorAction SilentlyContinue
}
exit \$p.ExitCode
"""

    int rc = -1
    try {
        writeFile encoding: 'UTF-8', file: 'delete_ext_designer_wrapper.ps1', text: ps1
        rc = bat(
            returnStatus: true,
            script: """
                @echo off
                chcp 65001 >nul
                powershell -NoProfile -ExecutionPolicy Bypass -File "delete_ext_designer_wrapper.ps1"
                exit /b %ERRORLEVEL%
            """.stripIndent()
        )
    } catch (Exception e) {
        echo "⚠️ Исключение при удалении расширения через Конфигуратор: ${e.toString()}"
        return -1
    } finally {
        bat(script: 'if exist delete_ext_designer_wrapper.ps1 del /f /q delete_ext_designer_wrapper.ps1', returnStatus: true)
    }

    if (rc != 0) {
        echo "⚠️ Удаление расширения через Конфигуратор завершилось с кодом ${rc}. Пайплайн продолжается."
    } else {
        echo "✅ Вызов 1cv8 DESIGNER /DeleteCfg завершён (код 0)."
    }
    return rc
}

/**
 * Проверка работоспособности базы после обновления
 */
def checkDbHealth(String epfPath, String v8version, String server1c, String dbName, String dbUser, String dbPass, String uccode = "ОбновлениеКонфигурации") {
    echo "=== Проверка работоспособности базы: ${epfPath} ==="
    
    def rc = bat(
        returnStatus: true,
        script: """
            @echo off
            chcp 65001 >nul
            setlocal enableextensions
            vrunner run --execute "${epfPath}" --v8version "${v8version}" --ibconnection "/S${server1c}\\${dbName}" --db-user "${dbUser}" --db-pwd "${dbPass}" --uccode "${uccode}" --command "/DisableStartupMessages /DisableStartupDialogs" > check_db_health.log 2>&1
            set VRUNNER_RC=%ERRORLEVEL%
            type check_db_health.log
            exit /b %VRUNNER_RC%
        """.stripIndent()
    )

    def logContent = ""
    if (fileExists("check_db_health.log")) {
        logContent = readFile(file: "check_db_health.log", encoding: "UTF-8")
    }

    boolean hasError = false
    String errorMessage = ""
    def lines = logContent.readLines()
    for (String line : lines) {
        String trimmed = line.trim()
        if (trimmed.startsWith("{") && trimmed.contains("}: ") && trimmed.contains("(")) {
            hasError = true
            errorMessage = trimmed
            echo "❌ Найдена ошибка компиляции/выполнения: ${trimmed}"
            break
        } else if (trimmed.contains("Критическая ошибка") || trimmed.contains("Невосстановимая ошибка")) {
            hasError = true
            errorMessage = trimmed
            echo "❌ Найдена критическая ошибка: ${trimmed}"
            break
        }
    }

    if (rc != 0 || hasError) {
        // Сохраняем текст ошибки в файл, чтобы 100% прочитать его в пайплайне
        if (errorMessage) {
            writeFile(file: "db_health_error.txt", text: errorMessage, encoding: "UTF-8")
        }
        error "Ошибка при проверке работоспособности базы (код ${rc}, найдены ошибки в логе)"
    }
    
    echo "✅ Проверка базы успешно завершена."
    return rc
}

/**
 * Проверка применимости всех расширений
 */
def checkExtensionsApplicability(String epfPath,
                                 String v8version,
                                 String server1c,
                                 String dbName,
                                 String dbUser,
                                 String dbPass,
                                 String uccode = "ОбновлениеКонфигурации") {
    echo "=== Проверка применимости расширений: ${epfPath} ==="

    def logFile = "check_extensions_applicability.log"

    def rc = bat(
        returnStatus: true,
        script: """
            @echo off
            chcp 65001 >nul
            setlocal enableextensions

            vrunner run ^
              --execute "${epfPath}" ^
              --v8version "${v8version}" ^
              --ibconnection "/S${server1c}\\${dbName}" ^
              --db-user "${dbUser}" ^
              --db-pwd "${dbPass}" ^
              --uccode "${uccode}" ^
              --command "/DisableStartupMessages /DisableStartupDialogs" > ${logFile} 2>&1

            set VRUNNER_RC=%ERRORLEVEL%
            type ${logFile}
            exit /b %VRUNNER_RC%
        """.stripIndent()
    )

    def logContent = ""
    if (fileExists(logFile)) {
        logContent = readFile(file: logFile, encoding: "UTF-8")
    }

    def servicePatterns = [
        ~/^\s*vanessa-runner.*$/,
        ~/^\s*ИНФОРМАЦИЯ\s*-\s*.*$/,
        ~/^\s*$/
    ]

    def meaningfulLines = []
    logContent.readLines().each { line ->
        def trimmed = line?.trim() ?: ""
        boolean isServiceLine = servicePatterns.any { p -> trimmed ==~ p }
        if (!isServiceLine) {
            meaningfulLines << trimmed
        }
    }

    if (rc != 0 || !meaningfulLines.isEmpty()) {
        if (!meaningfulLines.isEmpty()) {
            writeFile(
                file: "extensions_applicability_error.txt",
                text: meaningfulLines.join(System.lineSeparator()),
                encoding: "UTF-8"
            )
            echo "❌ Найдены ошибки применимости расширений:"
            meaningfulLines.each { echo it }
        }

        error "Ошибка при проверке применимости расширений (код ${rc})"
    }

    echo "✅ Ошибок применимости расширений не обнаружено."
    return 0
}

/**
 * Формирует список задач релиза на основе коммитов с предыдущего тега до текущего.
 * @param lastReleaseTagFile Путь к файлу с предыдущим релизным тегом
 * @param lastReleaseTasksFile Путь к выходному файлу со списком задач
 */
def generateReleaseTasksFile(String lastReleaseTagFile, String lastReleaseTasksFile) {
    String lastTag = ''
    if (fileExists(lastReleaseTagFile)) {
        lastTag = readFile(
            file: lastReleaseTagFile,
            encoding: 'UTF-8'
        ).trim()
    }

    echo "Предыдущий релизный тег: ${lastTag ?: '(не найден)'}"

    String psGetCurrentTag = '''
$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$tags = @(git tag --sort=-creatordate)
if ($LASTEXITCODE -ne 0) {
    throw "Ошибка выполнения команды git tag --sort=-creatordate"
}

if ($tags.Count -eq 0) {
    throw "Не удалось определить текущий тег. В репозитории нет тегов."
}

$tag = "$($tags[0])".Trim()
if ([string]::IsNullOrWhiteSpace($tag)) {
    throw "Текущий тег пустой"
}

[System.IO.File]::WriteAllText("current_tag.txt", $tag, $utf8NoBom)
'''

    writeFile(
        file: 'get_current_tag.ps1',
        text: psGetCurrentTag,
        encoding: 'UTF-8'
    )

    bat '''
@echo off
chcp 65001 > nul
powershell -NoProfile -ExecutionPolicy Bypass -File get_current_tag.ps1
if errorlevel 1 exit /b 1
'''

    if (!fileExists('current_tag.txt')) {
        error 'Файл current_tag.txt не был создан'
    }

    String currentTag = readFile(
        file: 'current_tag.txt',
        encoding: 'UTF-8'
    ).trim()

    if (!currentTag) {
        error 'Не удалось определить текущий тег'
    }

    echo "Текущий тег: ${currentTag}"

    String gitRange
    if (lastTag) {
        int verifyRc = bat(
            script: """@echo off
git rev-parse --verify "refs/tags/${lastTag}" >nul 2>nul
""",
            returnStatus: true
        )

        if (verifyRc == 0) {
            gitRange = "${lastTag}..${currentTag}"
        } else {
            echo "Предыдущий тег ${lastTag} не найден в репозитории. Будет использован диапазон до текущего тега."
            gitRange = currentTag
        }
    } else {
        gitRange = currentTag
    }

    echo "Диапазон коммитов: ${gitRange}"

    String psScript = '''
$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$range = $env:GIT_RANGE
if ([string]::IsNullOrWhiteSpace($range)) {
    throw "Переменная GIT_RANGE пустая"
}

$logLines = @(git log "$range" --pretty=format:%s)
if ($LASTEXITCODE -ne 0) {
    throw "Ошибка выполнения git log для диапазона: $range"
}

$seen = @{}
$result = New-Object System.Collections.Generic.List[string]

foreach ($line in $logLines) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }

    $text = $line.Trim()

    $matchesFound = [regex]::Matches($text, '(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*-\\d+)')
    foreach ($m in $matchesFound) {
        $taskKey = $m.Groups[1].Value.ToUpper()

        if (-not $seen.ContainsKey($taskKey)) {
            $seen[$taskKey] = $true
            [void]$result.Add($taskKey)
        }
    }
}

$content = ""
if ($result.Count -gt 0) {
    $content = ($result -join [Environment]::NewLine) + [Environment]::NewLine
}

[System.IO.File]::WriteAllText("tasks_unique.txt", $content, $utf8NoBom)
'''

    writeFile(
        file: 'gen_tasks.ps1',
        text: psScript,
        encoding: 'UTF-8'
    )

    withEnv(["GIT_RANGE=${gitRange}"]) {
        bat '''
@echo off
chcp 65001 > nul
powershell -NoProfile -ExecutionPolicy Bypass -File gen_tasks.ps1
if errorlevel 1 exit /b 1
'''
    }

    List<String> taskKeys = []
    if (fileExists('tasks_unique.txt')) {
        taskKeys = readFile(
            file: 'tasks_unique.txt',
            encoding: 'UTF-8'
        )
        .readLines()
        .collect { it.trim() }
        .findAll { it }
    }

    String fileContent = ''
    if (!taskKeys.isEmpty()) {
        fileContent = taskKeys.join('\n') + '\n'
    }

    writeFile(
        file: lastReleaseTasksFile,
        text: fileContent,
        encoding: 'UTF-8'
    )

    echo "Файл со списком задач сформирован: ${lastReleaseTasksFile}"
    echo "Количество найденных задач: ${taskKeys.size()}"

    if (taskKeys.isEmpty()) {
        echo 'Задачи по коммитам не найдены, файл записан пустым.'
    } else {
        echo 'Найденные задачи:'
        taskKeys.each { task ->
            echo " - ${task}"
        }
    }
}
