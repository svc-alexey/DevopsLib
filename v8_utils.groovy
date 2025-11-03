package io.libs

import java.util.Random
import org.apache.commons.lang.RandomStringUtils

// ========================================================================
// Вспомогательные методы
// ========================================================================

/**
 * Выполняет системную команду (Windows/Unix) в указанной директории с кодировкой UTF-8.
 * @param command Команда для выполнения.
 * @param workDir Рабочая директория (опционально).
 * @return Код возврата процесса.
 */
def cmd(command, workDir = "") {
    if (!workDir.isEmpty()) {
        command = "cd /D \"${workDir}\" & ${command}"
    }
    def returnCode = 0
    if (isUnix()) {
        returnCode = sh script: "${command}", returnStatus: true
    } else {
        returnCode = bat script: "chcp 65001 > nul\n ${command}", returnStatus: true
    }
    return returnCode
}

/**
 * Создает один или несколько каталогов, если они не существуют.
 * @param dirs Список путей для создания.
 * @return Всегда возвращает 0.
 */
def ensureDirs(String... dirs) {
    for (def d : dirs) {
        if (d?.trim()) {
            if (isUnix()) {
                sh script: "mkdir -p \"${d}\"", returnStatus: true
            } else {
                bat script: "if not exist \"${d}\" mkdir \"${d}\"", returnStatus: true
            }
        }
    }
    return 0
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

// ========================================================================
// Git-утилиты
// ========================================================================

/**
 * Выполняет команду git в указанной директории.
 * @param repoDir Путь к локальному Git-репозиторию.
 * @param args Строка с командой и аргументами git (например, "status" или "commit -m 'My commit'").
 * @return Код возврата процесса git.
 */
def git(String repoDir, String args) {
    if (!repoDir?.trim()) error "git: repoDir is empty"
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

// ========================================================================
// Работа с 1С (сборка, обновление)
// ========================================================================

/**
 * КОМПИЛИРУЕТ ИСХОДНИКИ КОНФИГУРАЦИИ В ФАЙЛ (.cf).
 * Использует параметр --out, соответствующий вашей версии vrunner.
 * @param srcDir Путь к каталогу с исходниками (src/cf).
 * @param outputCfFile Полный путь к выходному .cf файлу.
 * @param v8version Версия платформы 1С.
 * @return 0 при успехе, иначе генерирует error.
 */
def compileCF_to_file_safe(srcDir, outputCfFile, v8version = '8.3.26.1540') {
    ensureDirs(new File(outputCfFile).getParent())
    
    def command = "vrunner compile --src \"${srcDir}\" --out \"${outputCfFile}\" --v8version \"${v8version}\""
    
    echo "Компиляция исходников из '${srcDir}' в файл '${outputCfFile}'..."
    def returnCode = cmd(command)
    
    if (returnCode != 0) {
        error "Компиляция конфигурации в файл завершилась с ошибкой."
    }
    return returnCode
}

/**
 * КОМПИЛИРУЕТ ИСХОДНИКИ РАСШИРЕНИЯ В ФАЙЛ (.cfe).
 * Добавлен обязательный параметр --ext-name.
 * @param extname Техническое имя расширения (например, 'DocumentsExtension').
 * @param srcDir Путь к каталогу с исходниками расширения (src/cf).
 * @param outputCfeFile Полный путь к выходному .cfe файлу.
 * @param v8version Версия платформы 1С.
 * @return 0 при успехе.
 */
def compileCFE_to_file_safe(extname, srcDir, outputCfeFile, v8version = '8.3.26.1540') {
    ensureDirs(new File(outputCfeFile).getParent())
    
    // ИЗМЕНЕНИЕ: Добавлен обязательный параметр --ext-name, который передается
    // из Build-пайплайна (параметр EXTENSION_NAME).
    def command = "vrunner compileexttocfe --src \"${srcDir}\" --ext-name \"${extname}\" --out \"${outputCfeFile}\" --v8version \"${v8version}\""
    
    echo "Компиляция расширения '${extname}' в файл '${outputCfeFile}'..."
    def returnCode = cmd(command)

    if (returnCode != 0) {
        error "Компиляция расширения в файл завершилась с ошибкой."
    }
    return returnCode
}

/**
 * Обновление конфигурации базы данных напрямую через утилиту ibcmd.
 * Это основной способ применения изменений в DEPLOY пайплайнах.
 * @param dir Рабочий каталог с исходниками (по умолчанию env.WORKSPACE).
 * @return Код возврата ibcmd, генерирует error при ошибке.
 */
def updatedb_ibcmd(dir = '', uccode = '', v8version = '8.3.26.1540') {
    if (dir == '') dir = env.WORKSPACE
    def icUser = env.IC_USER ?: ''; def icPass = env.IC_PASS ?: ''
    def sqlUser = env.DB_USER ?: ''; def sqlPass = env.DB_PASS ?: ''
    def dbmsServer = env.serverBD ?: env.server1c; def dbName = env.database
    def srcPath = "${dir}\\src\\cf"
    
    def command = """ibcmd infobase config apply ^
      --dbms=MSSQLServer --db-server="${dbmsServer}" --db-name="${dbName}" ^
      --db-user="${sqlUser}" --db-pwd="${sqlPass}" -u "${icUser}" -P "${icPass}" ^
      --data="${srcPath}" --force"""
    
    def returnCode = cmd(command)
    if (returnCode != 0) { error 'Обновление конфигурации через ibcmd завершилось с ошибкой' }
    return returnCode
}


// ========================================================================
// Синхронизация хранилища 1С (gitsync)
// ========================================================================

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

// ========================================================================
// Уведомления и бэкапы
// ========================================================================

/**
 * Отправка сообщения в чат Telegram через Bot API.
 * @param TOKEN Токен Telegram-бота.
 * @param CHAT_ID ID чата для отправки.
 * @param messageText Текст сообщения.
 * @param success true/false для отображения иконок ✅/❌.
 */
def telegram_send_message(TOKEN, CHAT_ID, messageText, success) {
    if (success) {
        messageText = "✅ ${messageText} \nСборка: ${env.BUILD_URL}"
    } else {
        messageText = "❌ ${messageText} \nСборка: ${env.BUILD_URL}"
    }
    writeFile file: 'tmp_telegram_message.txt', text: messageText, encoding: 'UTF-8'
    def command = "chcp 65001 > nul & curl -s -X POST https://api.telegram.org/bot${TOKEN}/sendMessage -d chat_id=${CHAT_ID} --data-urlencode text@tmp_telegram_message.txt"
    bat(script: command, returnStatus: true)
}

/**
 * Создает сжатый бэкап базы MS SQL.
 * @param server Имя экземпляра SQL Server.
 * @param dbName Имя базы данных.
 * @param backupDir Директория для сохранения бэкапа.
 * @param sqlUser/sqlPass Логин и пароль для подключения к SQL.
 * @return 0 при успехе, иначе генерирует error.
 */
def mssqlBackup(String server, String dbName, String backupDir, String sqlUser, String sqlPass) {
    ensureDirs(backupDir)
    def cmdStr = """
    setlocal enableextensions
    for /f %%a in ('powershell -NoProfile -Command "(Get-Date).ToString(\\"yyyyMMdd_HHmmss\\")"') do set "TS=%%a"
    set "BAK=${backupDir}\\${dbName}_%TS%.bak"
    sqlcmd -S "${server}" -U "${sqlUser}" -P "${sqlPass}" -b -Q "BACKUP DATABASE [${dbName}] TO DISK='%BAK%' WITH COPY_ONLY, INIT, COMPRESSION, STATS=5"
    if errorlevel 1 exit /b 1
    exit /b 0
    """.trim()
    def rc = bat(script: "chcp 65001 > nul\n${cmdStr}", returnStatus: true)
    if (rc != 0) error "MS SQL backup failed"
    return 0
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