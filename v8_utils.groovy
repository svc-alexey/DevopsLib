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
def compileCF_to_file_safe(String srcDir, String outputCfFile, String v8version = '8.3.26.1540') {
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
def compileCFE_to_file_safe(String extName, String srcDir, String outputCfeFile, String v8version = '8.3.26.1540') {
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
def updateDB_via_ibcmd_or_vrunner(String cfFile, String server, String dbName,
                                  String sqlUser, String sqlPass,
                                  String v8version = '8.3.26.1540') {
    if (!fileExists(cfFile)) error "Файл конфигурации не найден: ${cfFile}"

    echo "=== Обновление конфигурации базы '${dbName}' ==="
    /**
    def hasIbcmd = (bat(script: "where ibcmd >nul 2>nul", returnStatus: true) == 0)

    if (hasIbcmd) {
        echo "ibcmd найден — выполняем обновление напрямую."
        def rc = cmd("""
            ibcmd infobase config load "${cfFile}" ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --user="${sqlUser}" --password="${sqlPass}" --force
            && ibcmd infobase config apply ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --user="${sqlUser}" --password="${sqlPass}" --force
        """)
        if (rc != 0) error "Ошибка при обновлении конфигурации через ibcmd (код ${rc})"
    } else {
        echo "ibcmd не найден — fallback на vrunner."
        def rc = cmd("""
            vrunner load --src "${cfFile}" \
              --v8version "${v8version}" \
              --dbms-type mssql --dbms-server "${server}" --dbms-base "${dbName}" \
              --dbms-user "${sqlUser}" --dbms-pwd "${sqlPass}" --uccode "ОбновлениеКонфигурации"
            && vrunner updatedb \
              --v8version "${v8version}" \
              --dbms-type mssql --dbms-server "${server}" --dbms-base "${dbName}" \
              --dbms-user "${sqlUser}" --dbms-pwd "${sqlPass}" --uccode "ОбновлениеКонфигурации"
        """)
        if (rc != 0) error "Ошибка при обновлении конфигурации через vrunner (код ${rc})"
    }
*/

    def rc = cmd("""
            vrunner load --src "${cfFile}" \
              --v8version "${v8version}" \
              --ibconnection "/S${server}\\${dbName}" \
              --db-user "${sqlUser}" --db-pwd "${sqlPass}" --uccode "ОбновлениеКонфигурации"
            && ibcmd infobase config apply ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --user="${sqlUser}" --password="${sqlPass}" --force
        """)
        if (rc != 0) error "Ошибка при обновлении конфигурации через vrunner (код ${rc})"

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
                                         String v8version = '8.3.26.1540') {
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

/** ---------------------- TELEGRAM --------------------- */

/** Простое уведомление в Telegram */
def telegram_send_message(TOKEN, CHAT_ID, messageText, success) {
    messageText = (success ? "✅ " : "❌ ") + messageText + "\nСборка: ${env.BUILD_URL}"
    writeFile file: 'tmp_telegram_message.txt', text: messageText, encoding: 'UTF-8'
    def command = "chcp 65001 > nul & curl -s -X POST https://api.telegram.org/bot${TOKEN}/sendMessage -d chat_id=${CHAT_ID} --data-urlencode text@tmp_telegram_message.txt"
    bat(script: command, returnStatus: true)
}

/** ---------------------- BACKUP --------------------- */

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
 * Главный метод распределения коммитов из 1C_REPO по feature-веткам.
 * Анализирует новые коммиты, определяет по их тексту номер задачи и выполняет
 * cherry-pick в соответствующую feature-ветку. Автоматически разрешает
 * конфликты в служебных файлах (VERSION, dumplist.txt).
 * @param repoDir Путь к локальному Git-репозиторию.
 * @return 0 при успехе, иначе генерирует error.
 */
def cherryPickTasksFrom1CRepo(String repoDir, String remoteHttps, String baseBranch = "1C_REPO", String compareBranch = "branch_sync_1c_repo") {
    if (!repoDir?.trim()) error "cherryPick: repoDir is empty"

    git(repoDir, "fetch --all --prune")
    git(repoDir, "checkout -B \"${baseBranch}\" \"origin/${baseBranch}\"")
    git(repoDir, "checkout -B \"${compareBranch}\" \"origin/${compareBranch}\"")
    git(repoDir, "checkout \"${baseBranch}\"")

    def pretty = isUnix() ? "%h;%s" : "%%h;%%s"
    def logCmd = "log --reverse ${compareBranch}..${baseBranch} --pretty=format:\"${pretty}\""
    
    def tmpFile = ".git/commit_list.txt"
    git(repoDir, "${logCmd} > ${tmpFile}")
    def listContent = readFile(file: "${repoDir}\\${tmpFile}", encoding: 'UTF-8')
    cmd("cd /D \"${repoDir}\" & del /Q ${tmpFile} 2>nul")

    if (!listContent?.trim()) {
        echo "Нет новых коммитов для обработки"
        return 0
    }

    for (def line : listContent.readLines().findAll { it?.trim() }) {
        def parts = line.split(";", 2)
        if (parts.size() < 2) continue
        def commit = parts[0].trim()
        def message = parts[1].trim()
        def issueKey = extractIssueKey(message)
        if (!issueKey) continue

        def featureBranch = "feature/${issueKey}"
        echo "Обработка ${featureBranch} / ${commit}"

        def rc = git(repoDir, "checkout -B \"${featureBranch}\" \"origin/${featureBranch}\"")
        if (rc != 0) {
            git(repoDir, "checkout -B \"${featureBranch}\"")
        }

        rc = git(repoDir, "cherry-pick ${commit} --keep-redundant-commits")

        if (rc != 0) {
            echo "Возник конфликт при cherry-pick коммита ${commit}. Анализируем..."
            git(repoDir, "diff --name-only --diff-filter=U > .git\\conflicts.txt")
            def conflictsContent = readFile(file: "${repoDir}\\.git\\conflicts.txt", encoding: 'UTF-8')
            def conflictFiles = conflictsContent.readLines().collect { it.trim().replace('/', '\\') }
            cmd("cd /D \"${repoDir}\" & del /Q .git\\conflicts.txt 2>nul")
            
            def knownServiceFiles = ["src\\cf\\VERSION", "src\\cf\\dumplist.txt"]
            def isOnlyServiceFilesConflict = !conflictFiles.isEmpty() && conflictFiles.every { knownServiceFiles.contains(it) }

            if (isOnlyServiceFilesConflict) {
                echo "Конфликт только в служебных файлах. Разрешаем автоматически."
                git(repoDir, "checkout ${commit} -- src/cf/VERSION src/cf/dumplist.txt")
                git(repoDir, "add .")
                rc = git(repoDir, "cherry-pick --continue")
                if (rc != 0) {
                    echo "Не удалось продолжить cherry-pick. Отменяем."
                    git(repoDir, "cherry-pick --abort")
                    continue
                }
            } else {
                echo "Обнаружен серьезный конфликт в коде. Отмена cherry-pick для ${commit}."
                git(repoDir, "cherry-pick --abort")
                continue
            }
        }
        
        git(repoDir, "push --set-upstream origin \"${featureBranch}\"")
    }
    
    git(repoDir, "checkout \"${baseBranch}\"")
    return 0
}

/**
 * Финальная синхронизация. Обновляет служебную ветку branch_sync_1c_repo,
 * чтобы отметить коммиты как обработанные и не обрабатывать их в следующий раз.
 * @param repoDir Путь к локальному Git-репозиторию.
 * @return 0 при успехе.
 */
def updateBranchSyncFrom1CRepo(String repoDir, String remoteHttps, String baseBranch = "1C_REPO", String compareBranch = "branch_sync_1c_repo") {
    if (!repoDir?.trim()) error "updateBranchSync: repoDir is empty"
    git(repoDir, "fetch --all --prune")
    git(repoDir, "checkout -B \"${compareBranch}\" \"origin/${compareBranch}\"")
    git(repoDir, "reset --hard")
    git(repoDir, "merge \"${baseBranch}\" --no-edit")
    git(repoDir, "push origin \"${compareBranch}\"")
    git(repoDir, "checkout \"${baseBranch}\"")
    return 0
}
