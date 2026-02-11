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

/**
 * Синхронизирует хранилище 1С с Git-репозиторием через gitsync sync.
 * @param rep_1c Путь к хранилищу 1С.
 * @param rep_git_local_src_cf Локальный путь к каталогу /src/cf в Git-репозитории.
 * @return Код возврата процесса gitsync.
 */
def sync_hran(rep_1c, rep_git_local_src_cf, rep_git_remote, ext = "", aditional_parameters, server1c, repo_user, repo_pass) {
    if (ext?.trim()) { ext = "--ext ${ext.trim()}" } else { ext = "" }
    def command = "gitsync sync --storage-user \"${repo_user}\" --storage-pwd \"${repo_pass}\" ${ext} ${aditional_parameters} \"${rep_1c}\" \"${rep_git_local_src_cf}\""
    return bat(script: "powershell -Command \"[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ${command}\"", returnStatus: true)
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
    return bat(script: "powershell -Command \"[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ${command}\"", returnStatus: true)
}


/** ------------------------ КОМПИЛЯЦИЯ ------------------------- */

/**
 * Сборка основной конфигурации (.cf) из исходников src\cf
 */
def compileCF_to_file_safe(String srcDir, String outputCfFile, String v8version = '8.3.27.1859') {
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
def compileCFE_to_file_safe(String extName, String srcDir, String outputCfeFile, String v8version = '8.3.27.1859') {
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
                                  String v8version = '8.3.27.1859') {
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
                                         String v8version = '8.3.27.1859') {
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
                                                     String v8version = '8.3.27.1859',
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

    // Путь к файлу сообщения в текущем workspace
    def ws = pwd()
    def msgFile = "${ws}/tmp_telegram_message.txt"

    // Пишем основной текст в файл
    writeFile file: msgFile, text: messageText, encoding: 'UTF-8'

    // Базовая часть curl-команды
    def curlBase = "curl -X POST https://api.telegram.org/bot${TOKEN}/sendMessage -d chat_id=${CHAT_ID}"

    def command

    if (fileExists(msgFile)) {
        // Нормальный сценарий: шлём содержимое файла
        command = "chcp 65001 > nul & ${curlBase} --data-urlencode text@\"${msgFile}\""
    } else {
        // Файл не создался/куда-то делся – шлём запасную строку
        def fallbackText = "Build success, not file in folder"
        command = "chcp 65001 > nul & ${curlBase} --data-urlencode \"text=${fallbackText}\""
    }

    // Логируем ответ от Telegram
    def out = bat(script: command, returnStdout: true).trim()
    echo "telegram_send_message response: ${out}"
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

        if (!hasLocalFeature && !hasRemoteFeature) {
            echo "- Ветка ${featureBranch} не найдена ни локально, ни в origin. Создаю от ${compareBranch}"
            git(repoDir, "checkout -B \"${featureBranch}\" \"${compareBranch}\"")
        } else if (!hasLocalFeature && hasRemoteFeature) {
            echo "- Локальной ветки нет, но есть origin/${featureBranch}. Чекаутим её"
            git(repoDir, "checkout -B \"${featureBranch}\" \"origin/${featureBranch}\"")
        } else if (hasLocalFeature && !hasRemoteFeature) {
            echo "- Ветка ${featureBranch} есть локально, а в origin нет. Использую локальную"
            git(repoDir, "checkout \"${featureBranch}\"")
        } else {
            echo "- Ветка ${featureBranch} есть и локально, и в origin. Синхронизирую с origin"
            git(repoDir, "checkout \"${featureBranch}\"")
            git(repoDir, "reset --hard \"origin/${featureBranch}\"")
        }

        def isMerge = isMergeCommit(repoDir, commit)
        def cherryPickCmd = isMerge
                ? "cherry-pick --keep-redundant-commits -X theirs -m 1 ${commit}"
                : "cherry-pick --keep-redundant-commits -X theirs ${commit}"

        rc = git(repoDir, cherryPickCmd)

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
 * Финальная синхронизация. Обновляет служебную ветку branch_sync_1c_repo,
 * чтобы отметить коммиты как обработанные и не обрабатывать их в следующий раз.
 */
def updateBranchSyncFrom1CRepo(String repoDir, String remoteHttps, String baseBranch = "1C_REPO", String compareBranch = "branch_sync_1c_repo") {
    if (!repoDir?.trim()) error "updateBranchSync: repoDir is empty"
    git(repoDir, "fetch --all --prune")

    def hasLocalCompare  = (git(repoDir, "show-ref --verify --quiet refs/heads/${compareBranch}") == 0)
    def hasRemoteCompare = (git(repoDir, "show-ref --verify --quiet refs/remotes/origin/${compareBranch}") == 0)

    if (hasLocalCompare) {
        git(repoDir, "checkout \"${compareBranch}\"")
    } else if (hasRemoteCompare) {
        git(repoDir, "checkout -B \"${compareBranch}\" \"origin/${compareBranch}\"")
    } else {
        echo "Ветка ${compareBranch} не найдена ни локально, ни в origin. Создаю её от ${baseBranch}."
        git(repoDir, "checkout -B \"${compareBranch}\" \"${baseBranch}\"")
    }

    git(repoDir, "reset --hard")
    git(repoDir, "merge \"${baseBranch}\" --no-edit")
    git(repoDir, "push origin \"${compareBranch}\"")
    git(repoDir, "checkout \"${baseBranch}\"")
    return 0
}
