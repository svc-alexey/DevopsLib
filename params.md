Конечно. Это очень важный шаг, чтобы всё заработало правильно.

Вот подробный список всех параметров, которые вам нужно будет настроить для каждого типа пайплайна.

### **Общая информация: Типы параметров**

В Jenkins мы будем использовать 3 вида параметров:

1.  **Глобальные переменные Jenkins:** Настраиваются один раз в `Manage Jenkins` -> `Configure System` -> `Global properties`. Они доступны всем пайплайнам и идеально подходят для общих настроек, как адрес сервера 1С или токен Telegram.
2.  **Параметры Job'а (Job Parameters):** Настраиваются в конфигурации **каждого конкретного Job'а**. Они делают пайплайны переиспользуемыми. Например, URL репозитория для расширения будет параметром Job'а.
3.  **Credentials:** Безопасное хранилище для паролей и токенов. Настраиваются в `Manage Jenkins` -> `Credentials`. Мы будем ссылаться на них по их `ID`.

---

### **Пайплайн 1: Выгрузка из Хранилища 1С в Git (`ERP-Gitsync`)**

Этот пайплайн использует в основном глобальные переменные и credentials.

| Имя параметра | Тип | Где настраивается | Пример значения и описание |
| :--- | :--- | :--- | :--- |
| `rep_1c` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | `\\server\share\1C\storage` <br> Сетевой путь к вашему хранилищу конфигурации 1С. |
| `rep_git_remote` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | `gitlab.mycompany.com/group/erp-main-config.git` <br> URL основного репозитория **без** `https://` и токена. |
| `server1c` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | `prod-cluster-01` <br> Имя кластера серверов 1С. |
| `TELEGRAM_CHAT_TOKEN` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | `123456:ABC-DEF1234ghIkl-zyx57W2v1u123` <br> Токен вашего Telegram-бота. |
| `TELEGRAM_CHAT_ID` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | `-1001234567890` <br> ID вашего чата или канала в Telegram. |
| `aditional_parameters`| Глобальная переменная | `Manage Jenkins` -> `Configure System` | `--versions-path .versions` <br> **Необязательно.** Дополнительные параметры для `gitsync`. |
| `token` | Credential | `Manage Jenkins` -> `Credentials` | **Тип:** `Username with password`. <br> **Username:** `jenkins_bot`. <br> **Password:** `glpat-.....` (ваш токен GitLab). <br> **ID:** `token`. |
| `repo_user_pass` | Credential | `Manage Jenkins` -> `Credentials` | **Тип:** `Username with password`. <br> **Username:** `ПользовательХранилища`. <br> **Password:** `Пароль`. <br> **ID:** `repo_user_pass`. |

---

### **Пайплайн 2: Сборка Артефакта (`...-Build`)**

#### **2.1 Для Основной Конфигурации (`ERP-Main-Config-Build`)**

| Имя параметра | Тип | Где настраивается | Пример значения и описание |
| :--- | :--- | :--- | :--- |
| `GIT_BRANCH` | Параметр Job'а | Настройки Job'а -> `This project is parameterized` | **Тип:** `String`. <br> **Name:** `GIT_BRANCH`. <br> **Default Value:** `develop`. <br> Указывает, из какой ветки создавать артефакт. |
| `rep_git_remote` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | (Используется тот же, что и для выгрузки). |
| `v8_version` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | `8.3.26.1540` <br> Версия платформы 1С для компиляции. |
| `TELEGRAM...` | Глобальные переменные | `Manage Jenkins` -> `Configure System` | (Используются те же, что и для выгрузки). |
| `token` | Credential | `Manage Jenkins` -> `Credentials` | (Используется тот же, что и для выгрузки). <br> **ID:** `token`. |

#### **2.2 Для Расширений (Шаблон `ERP-Extension-*-Build`)**

| Имя параметра | Тип | Где настраивается | Пример значения и описание |
| :--- | :--- | :--- | :--- |
| `EXTENSION_NAME` | Параметр Job'а | Настройки Job'а -> `This project is parameterized` | **Тип:** `String`. <br> **Name:** `EXTENSION_NAME`. <br> **Default Value:** `DocumentsExtension`. <br> **Важно:** У каждого Job'а расширения здесь должно быть свое уникальное имя. |
| `GIT_REPO_URL` | Параметр Job'а | Настройки Job'а -> `This project is parameterized` | **Тип:** `String`. <br> **Name:** `GIT_REPO_URL`. <br> **Default Value:** `gitlab.mycompany.com/erp-extensions/documents.git`. <br> URL репозитория **конкретно этого расширения**. |
| `GIT_BRANCH` | Параметр Job'а | Настройки Job'а -> `This project is parameterized` | **Тип:** `String`. <br> **Name:** `GIT_BRANCH`. <br> **Default Value:** `develop`. <br> Указывает, из какой ветки создавать артефакт. |
| `v8_version` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | (Используется та же). |
| `TELEGRAM...` | Глобальные переменные | `Manage Jenkins` -> `Configure System` | (Используются те же). |
| `token` | Credential | `Manage Jenkins` -> `Credentials` | (Используется тот же). <br> **ID:** `token`. |

---

### **Пайплайн 3: Развертывание по Расписанию (`...-Deploy-Schedule`)**

#### **3.1 Для Основной Конфигурации (`ERP-Main-Config-Deploy-Schedule`)**

| Имя параметра | Тип | Где настраивается | Пример значения и описание |
| :--- | :--- | :--- | :--- |
| `rep_git_remote` | Глобальная переменная | `Manage Jenkins` -> `Configure System` | (Используется тот же). |
| `server1c`, `database`, `serverBD`, `BACKUP_DIR`, `v8_version` | Глобальные переменные | `Manage Jenkins` -> `Configure System`| `prod-cluster-01`, `ERP_PROD`, `prod-sql-01`, `\\backupserver\erp`, `8.3.26.1540`. <br> Параметры вашей **единственной** прод-базы. |
| `token` | Credential | `Manage Jenkins` -> `Credentials` | (Используется тот же). <br> **ID:** `token`. |
| `cluster-admin` | Credential | `Manage Jenkins` -> `Credentials` | **Тип:** `Username with password`. <br> **Username:** `АдминКластера`. <br> **Password:** `Пароль`. <br> **ID:** `cluster-admin`. |
| `ic-user-pass` | Credential | `Manage Jenkins` -> `Credentials` | **Тип:** `Username with password`. <br> **Username:** `Администратор`. <br> **Password:** `Пароль1С`. <br> **ID:** `ic-user-pass`. |
| `sql-auth` | Credential | `Manage Jenkins` -> `Credentials` | **Тип:** `Username with password`. <br> **Username:** `sa`. <br> **Password:** `ПарольSQL`. <br> **ID:** `sql-auth`. |

#### **3.2 Для Расширений (Шаблон `ERP-Extension-*-Deploy-Schedule`)**

Этот пайплайн самый сложный, так как он "знает" о нескольких базах. **Глобальные переменные** для подключения он **не использует**. Вместо этого он использует **credentials**, а параметры подключения прописаны прямо в `switch` блоке пайплайна.

| Имя параметра | Тип | Где настраивается | Пример значения и описание |
| :--- | :--- | :--- | :--- |
| `EXTENSION_NAME` | Параметр Job'а | Настройки Job'а -> `This project is parameterized` | **Тип:** `String`. <br> **Name:** `EXTENSION_NAME`. <br> **Default Value:** `DocumentsExtension`. <br> Имя расширения, за которое отвечает этот Job. |
| `GIT_REPO_URL` | Параметр Job'а | Настройки Job'а -> `This project is parameterized` | **Тип:** `String`. <br> **Name:** `GIT_REPO_URL`. <br> **Default Value:** `gitlab.mycompany.com/erp-extensions/documents.git`. <br> URL репозитория этого расширения. |
| `TARGET_ENVIRONMENT`| Параметр, передаваемый из триггера `cron` | Внутри `triggers` блока самого пайплайна | `PROD_FINANCE`, `PROD_LOGISTICS` <br> Этот параметр **не нужно** настраивать в интерфейсе Jenkins. Он задается автоматически при запуске по расписанию. |
| `token` | Credential | `Manage Jenkins` -> `Credentials` | (Используется тот же). <br> **ID:** `token`. |
| **Credentials для каждой базы** | Credential | `Manage Jenkins` -> `Credentials` | Вам нужно создать **отдельный набор** credentials для **каждой** вашей базы. Например: <br> `ic-user-pass-finance-prod` (ID) <br> `sql-auth-finance-prod` (ID) <br> `ic-user-pass-logistics-prod` (ID) <br> `sql-auth-logistics-prod` (ID) <br> `cluster-admin-prod` (ID) <br> Пайплайн будет выбирать нужный `ID` на основе параметра `TARGET_ENVIRONMENT`.|