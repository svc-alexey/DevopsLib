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

/** -------------------------- GIT ----------------------------- */
def git(String repoDir, String args) {
    return cmd("git ${args}", repoDir)
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
    def hasIbcmd = (bat(script: "where ibcmd >nul 2>nul", returnStatus: true) == 0)

    if (hasIbcmd) {
        echo "ibcmd найден — выполняем обновление напрямую."
        def rc = cmd("""
            ibcmd infobase config load "${cfFile}" ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --force
            && ibcmd infobase config apply ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --force
        """)
        if (rc != 0) error "Ошибка при обновлении конфигурации через ibcmd (код ${rc})"
    } else {
        echo "ibcmd не найден — fallback на vrunner."
        def rc = cmd("""
            vrunner load --src "${cfFile}" \
              --v8version "${v8version}" \
              --dbms-type mssql --dbms-server "${server}" --dbms-base "${dbName}" \
              --dbms-user "${sqlUser}" --dbms-pwd "${sqlPass}"
            && vrunner updatedb \
              --v8version "${v8version}" \
              --dbms-type mssql --dbms-server "${server}" --dbms-base "${dbName}" \
              --dbms-user "${sqlUser}" --dbms-pwd "${sqlPass}"
        """)
        if (rc != 0) error "Ошибка при обновлении конфигурации через vrunner (код ${rc})"
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
                                         String v8version = '8.3.26.1540') {
    if (!fileExists(cfePath)) error "Файл расширения не найден: ${cfePath}"

    echo "=== Обновление расширения '${extName}' в базе '${dbName}' ==="
    def hasIbcmd = (bat(script: "where ibcmd >nul 2>nul", returnStatus: true) == 0)

    if (hasIbcmd) {
        echo "ibcmd найден — выполняем установку расширения напрямую."
        def rc = cmd("""
            ibcmd infobase config load --extension=${extName} "${cfePath}" ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --force
            && ibcmd infobase config apply --extension=${extName} ^
              --dbms MSSQLServer --db-server="${server}" --db-name="${dbName}" ^
              --db-user="${sqlUser}" --db-pwd="${sqlPass}" --force
        """)
        if (rc != 0) error "Ошибка установки расширения через ibcmd (код ${rc})"
    } else {
        echo "ibcmd не найден — fallback на vrunner loadext."
        def rc = cmd("""
            vrunner loadext --file "${cfePath}" --extension ${extName} --updatedb \
              --v8version "${v8version}" \
              --dbms-type mssql --dbms-server "${server}" --dbms-base "${dbName}" \
              --dbms-user "${sqlUser}" --dbms-pwd "${sqlPass}"
        """)
        if (rc != 0) error "Ошибка установки расширения через vrunner (код ${rc})"
    }

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
    def rc = cmd("""
        vrunner session lock --ras ${ras} --db ${dbName} \
          --cluster-admin "${racUser}" --cluster-pwd "${racPass}" \
          --uccode "${reason}"
    """)
    if (rc != 0) error "Не удалось заблокировать сеансы пользователей (код ${rc})"
    echo "✅ Сеансы пользователей заблокированы."
}

/**
 * Снимает блокировку сеансов.
 */
def unlockSessions(String ras, String dbName, String racUser, String racPass) {
    echo "🔓 Снятие блокировки сеансов пользователей (${dbName})..."
    def rc = cmd("""
        vrunner session unlock --ras ${ras} --db ${dbName} \
          --cluster-admin "${racUser}" --cluster-pwd "${racPass}"
    """)
    if (rc != 0) echo "⚠ Не удалось корректно снять блокировку (код ${rc})"
    else echo "✅ Блокировка снята."
}

