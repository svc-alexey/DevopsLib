// ========================================================================
//          ШАБЛОН JENKINS PIPELINE: СБОРКА И ОБНОВЛЕНИЕ РАСШИРЕНИЯ 1С
// ========================================================================
// ИНСТРУКЦИЯ ПО ПРИМЕНЕНИЮ:
// 1. НЕ ИСПОЛЬЗУЙТЕ ЭТОТ ФАЙЛ НАПРЯМУЮ.
// 2. Для каждого расширения создайте в Jenkins новый Pipeline Job.
// 3. Скопируйте этот код в созданный Job.
// 4. В настройках Job'а установите значения по умолчанию для параметров
//    'EXTENSION_NAME' и 'GIT_REPO_URL', специфичные для этого расширения.
// 5. Настройте webhook из Git-репозитория расширения на запуск этого Job'а.
// ========================================================================

// --- Загрузка общей библиотеки утилит ---
def loadSharedLibrary() {
    library '1c-utils@master'
}
loadSharedLibrary()
import io.libs.v8_utils
def utils = new v8_utils()

pipeline {
  agent any
  options { timestamps(); skipDefaultCheckout(true); }
  
  // --- Параметры этого пайплайна ---
  // Эти параметры нужно будет настроить для каждого конкретного расширения
  parameters {
    string(name: 'EXTENSION_NAME', defaultValue: 'YourExtensionName', description: 'Техническое имя расширения (должно совпадать с именем в 1С)')
    string(name: 'GIT_REPO_URL', defaultValue: 'gitlab.mycompany.com/path/to/repo.git', description: 'URL Git-репозитория этого расширения (без https://)')
    string(name: 'GIT_BRANCH', defaultValue: 'master', description: 'Из какой ветки брать исходный код')
  }

  stages {
    // --- ЭТАП 1: Подготовка рабочего пространства ---
    stage('Checkout Extension Code') {
        steps {
            script {
                cleanWs()
                // Клонируем репозиторий именно этого расширения
                withCredentials([usernamePassword(credentialsId: 'token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                    def remoteUrl = "https://${GIT_USER}:${GIT_TOKEN}@${params.GIT_REPO_URL}"
                    utils.cmd("git clone --branch ${params.GIT_BRANCH} --single-branch ${remoteUrl} .", env.WORKSPACE)
                }
            }
        }
    }

    // --- ЭТАП 2: Блокировка, Бэкап, Сборка и Обновление Расширения ---
    stage('Lock, Backup, Build & Apply Extension') {
        steps {
            script {
                withCredentials([
                    usernamePassword(credentialsId: 'cluster-admin', usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS'),
                    usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'SQL_USER', passwordVariable: 'SQL_PASS'),
                    usernamePassword(credentialsId: 'ic-user-pass', usernameVariable: 'IC_USER', passwordVariable: 'IC_PASS'),
                    usernamePassword(credentialsId: 'sql-auth', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS')
                ]) {
                    // --- Подготовка ---
                    def ras = "${env.server1c}:${env.CLUSTER_PORT ?: '1545'}"; def db = env.database
                    def shortSha = bat(script: "git rev-parse --short=8 HEAD", returnStdout: true).trim()
                    env.UCCODE = "EXT-${params.EXTENSION_NAME}-${shortSha}"
                    
                    // --- Блокировка сеансов и создание бэкапа ---
                    utils.cmd("vrunner session lock --ras ${ras} --db ${db} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\"")
                    env.__LOCK_ACTIVE = '1'
                    utils.mssqlBackup(env.server1c, env.database, env.BACKUP_DIR, SQL_USER, SQL_PASS)
                    
                    // --- Сборка и применение расширения ---
                    env.IC_USER = IC_USER; env.IC_PASS = IC_PASS;
                    env.DB_USER = DB_USER; env.DB_PASS = DB_PASS;
                    def rc = utils.buildCFE(params.EXTENSION_NAME, env.WORKSPACE, env.UCCODE, env.v8_version ?: '8.3.26.1540', false)
                    if (rc != 0) error("Сборка расширения '${params.EXTENSION_NAME}' провалилась")
                }
            }
        }
    }
  }
  
  // --- Блок POST: Действия после завершения пайплайна ---
  post {
      always {
          script {
              if (env.__LOCK_ACTIVE == '1') {
                  withCredentials([usernamePassword(credentialsId: 'cluster-admin', usernameVariable: 'RAC_USER', passwordVariable: 'RAC_PASS')]) {
                      def ras = "${env.server1c}:${env.CLUSTER_PORT ?: '1545'}"; def db = env.database
                      utils.cmd("vrunner session unlock --ras ${ras} --db ${db} --cluster-admin \"${RAC_USER}\" --cluster-pwd \"${RAC_PASS}\" --uccode \"${env.UCCODE}\" || echo OK")
                  }
              }
          }
      }
      success {
          script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Расширение '${params.EXTENSION_NAME}' успешно обновлено.", true) }
      }
      failure {
          script { utils.telegram_send_message(env.TELEGRAM_CHAT_TOKEN, env.TELEGRAM_CHAT_ID, "Ошибка обновления расширения '${params.EXTENSION_NAME}'.", false) }
      }
  }
}