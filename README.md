# Simple World Sync

<p align="center">
  <img src="src/main/resources/assets/simpleworldsync/icon.png" alt="Simple World Sync" width="192">
</p>

<p align="center">
  <strong>Синхронизация одиночных миров Minecraft между компьютерами, NAS и Яндекс Диском.</strong><br>
  <strong>Synchronize Minecraft singleplayer worlds between computers, NAS devices, and Yandex Disk.</strong>
</p>

<p align="center">
  <a href="#-русский">Русский</a> ·
  <a href="#-english">English</a>
</p>

---

## 🇷🇺 Русский

### 🌍 Что это за мод

**Simple World Sync** — клиентский Fabric-мод для Minecraft `1.21.1`, который синхронизирует одиночные миры между несколькими устройствами.

Поддерживаемые сценарии:

- 🖥️ ПК ↔ NAS ↔ Steam Deck;
- 📁 ПК ↔ общая локальная или сетевая папка;
- ☁️ ПК ↔ Яндекс Диск ↔ другое устройство;
- 🔄 автоматическая проверка мира перед запуском и выгрузка после выхода.

Мод не создаёт Minecraft-сервер и не отправляет миры на собственный сервер разработчика. Данные хранятся только в выбранной вами папке или в вашем аккаунте Яндекс Диска.

### 💾 Поддерживаемые хранилища

| Хранилище | Состояние | Как используется |
| --- | --- | --- |
| 📁 **Локальная папка / NAS** | ✅ Работает | Общая папка, сетевой диск, SMB/NFS, Syncthing или внешний накопитель |
| ☁️ **Яндекс Диск** | ✅ Работает | Авторизация в браузере и синхронизация напрямую через API |
| ☁️ **Google Drive** | 🚧 В разработке | В интерфейсе отображается как недоступная заглушка |

### ⚙️ Как работает синхронизация

```text
Устройство 1
    ↓
проверка manifest.json и изменённых файлов
    ↓
NAS / локальная папка / Яндекс Диск
    ↓
проверка перед запуском
    ↓
Устройство 2
```

1. Для подключённого мира создаётся постоянный `worldId`.
2. Мод записывает список файлов и их состояние в `manifest.json`.
3. Перед запуском мира проверяется версия в выбранном хранилище.
4. Если удалённая версия новее, мод предлагает скачать изменения.
5. После выхода из мира мод ждёт его полного закрытия и выгружает изменения.
6. Передаются только новые, изменённые и удалённые файлы.
7. Lock-файл защищает мир от одновременной записи с двух устройств.

### ✨ Возможности

- автоматическая выгрузка после выхода из мира;
- автоматическая проверка перед запуском;
- инкрементальная синхронизация без повторной отправки всех файлов;
- экран прогресса с процентом, скоростью, текущим файлом и оставшимся временем;
- отдельное включение синхронизации для каждого мира;
- отметка синхронизируемого мира в стандартном списке Minecraft;
- экран **«Облачные миры»** для просмотра, скачивания и удаления удалённых миров;
- обнаружение конфликтов локальной и удалённой версий;
- защита от одновременной записи;
- русский и английский интерфейс;
- адаптация интерфейса для небольших экранов и Steam Deck.

### 📁 Настройка локальной папки или NAS

#### 1. Подготовьте общую папку

Папка должна быть доступна на каждом устройстве, где установлен мод.

Примеры:

```text
Windows:    Z:\Minecraft Worlds
Windows:    \\192.168.1.10\Minecraft\Worlds
Linux:      /mnt/nas/minecraft-worlds
Steam Deck: /run/media/deck/NAS/minecraft-worlds
```

Проверьте, что пользователь может читать, создавать и удалять файлы в этой папке.

#### 2. Выберите хранилище

1. Откройте настройки **Simple World Sync** через Mod Menu.
2. Выберите **«Локальная папка / NAS»**.
3. Нажмите **«Выбрать папку»**.
4. Укажите подготовленную общую папку.
5. Нажмите **«Использовать это хранилище»**.

Мод автоматически проверит чтение и запись с помощью тестового файла.

#### 3. Подключите мир

1. Откройте список одиночных миров.
2. Выберите нужный мир.
3. Нажмите стандартную кнопку Minecraft **«Настроить»**.
4. Нажмите **«Включить синхронизацию мира»**.
5. Дождитесь первой полной выгрузки.

#### 4. Подключите второе устройство

1. Подключите на втором устройстве ту же папку NAS.
2. Выберите её в настройках Simple World Sync.
3. Откройте экран **«Облачные миры»** в меню выбора миров.
4. Выберите мир и нажмите **«Скачать»**.

### ☁️ Настройка Яндекс Диска

#### 1. Авторизуйтесь

1. Откройте настройки **Simple World Sync**.
2. Выберите **«Яндекс Диск»**.
3. Нажмите **«Войти в Яндекс»**.
4. В браузере войдите в нужный аккаунт Яндекса.
5. Подтвердите доступ приложения.
6. Если Яндекс покажет код, скопируйте его и вставьте в поле Minecraft.

Логин и пароль не вводятся в моде. OAuth-токен создаётся Яндексом для вашего аккаунта и сохраняется в защищённом хранилище операционной системы.

#### 2. Активируйте хранилище

После успешной проверки появится сообщение:

```text
Авторизован. Яндекс Диск доступен. Чтение и запись работают.
```

Нажмите **«Использовать это хранилище»**. Мод создаст папку:

```text
/SimpleWorldSync
```

#### 3. Подключите и выгрузите мир

1. В списке миров выберите мир и нажмите **«Настроить»**.
2. Нажмите **«Включить синхронизацию мира»**.
3. Дождитесь первой выгрузки.

На другом устройстве войдите в тот же аккаунт Яндекса, активируйте Яндекс Диск и скачайте мир через экран **«Облачные миры»**.

### ☁️ Управление облачными мирами

Кнопка с облаком в меню выбора миров открывает отдельный экран. На нём можно:

- увидеть миры в активном хранилище;
- узнать название, версию и идентификатор мира;
- скачать отсутствующий локально мир;
- обновить список;
- удалить удалённую копию мира.

Экран показывает миры только из **активного хранилища**. При смене хранилища список загружается заново.

### 🔐 Безопасность

- мод не запрашивает пароль от Яндекса;
- OAuth-токены не отображаются в интерфейсе и не записываются в обычный конфиг;
- токены сохраняются в защищённом хранилище операционной системы;
- локальные и удалённые пути проверяются перед удалением;
- `session.lock`, `.git` и служебные файлы не переносятся как данные мира.

### ⚠️ Важно

- Не открывайте один мир одновременно на двух устройствах.
- Дождитесь окончания синхронизации перед запуском мира на другом устройстве.
- Перед первой синхронизацией сделайте резервную копию мира.
- Не отключайте NAS и интернет во время передачи файлов.
- Первая выгрузка большого мира может занять заметное время.
- Мод предназначен для одиночных миров и не заменяет Minecraft-сервер.

### 📦 Установка

1. Установите Fabric Loader для Minecraft `1.21.1`.
2. Установите Fabric API.
3. Скачайте JAR из раздела Releases.
4. Поместите файл в папку `mods`.
5. Запустите Minecraft.

Для Prism Launcher:

```text
Instance → Edit → Mods → Add File
```

### 🛠️ Сборка из исходников

```powershell
git clone https://github.com/Grovymon/simple-world-sync.git
cd simple-world-sync
.\gradlew.bat build
```

Готовый файл:

```text
build/libs/simple-world-sync-0.1.4.jar
```

### 🐞 Обратная связь

При создании Issue укажите:

- версию Minecraft, Fabric Loader и мода;
- операционную систему и устройство;
- выбранное хранилище;
- последовательность действий;
- ожидаемый и фактический результат;
- соответствующий фрагмент лога без OAuth-токенов.

---

## 🇬🇧 English

### 🌍 What is this mod?

**Simple World Sync** is a client-side Fabric mod for Minecraft `1.21.1` that synchronizes singleplayer worlds between multiple devices.

Supported scenarios:

- 🖥️ PC ↔ NAS ↔ Steam Deck;
- 📁 PC ↔ shared local or network folder;
- ☁️ PC ↔ Yandex Disk ↔ another device;
- 🔄 automatic checks before launch and uploads after leaving a world.

The mod does not create a Minecraft server and does not upload worlds to a server owned by the developer. Your data stays in the folder or Yandex Disk account selected by you.

### 💾 Supported storage providers

| Storage | Status | Usage |
| --- | --- | --- |
| 📁 **Local folder / NAS** | ✅ Available | Shared folder, network drive, SMB/NFS, Syncthing, or external drive |
| ☁️ **Yandex Disk** | ✅ Available | Browser authorization and direct synchronization through the API |
| ☁️ **Google Drive** | 🚧 In development | Displayed as an unavailable placeholder in the interface |

### ⚙️ How synchronization works

```text
Device 1
    ↓
manifest.json and changed-file check
    ↓
NAS / local folder / Yandex Disk
    ↓
check before launch
    ↓
Device 2
```

1. Every synchronized world receives a persistent `worldId`.
2. The mod stores the file list and state in `manifest.json`.
3. The remote version is checked before the world starts.
4. If the remote version is newer, the mod offers to download it.
5. After you leave the world, the mod waits for it to close and uploads changes.
6. Only new, changed, and deleted files are transferred.
7. A lock file protects the world from simultaneous writes by two devices.

### ✨ Features

- automatic upload after leaving a world;
- automatic remote-version check before launch;
- incremental synchronization without uploading every file again;
- progress screen with percentage, speed, current file, and ETA;
- synchronization can be enabled separately for each world;
- synchronized-world marker in Minecraft's standard world list;
- **Cloud Worlds** screen for listing, downloading, and deleting remote worlds;
- local and remote conflict detection;
- protection against simultaneous writes;
- Russian and English interfaces;
- layouts adapted for small displays and Steam Deck.

### 📁 Local folder or NAS setup

#### 1. Prepare a shared folder

The folder must be available on every device that uses the mod.

Examples:

```text
Windows:    Z:\Minecraft Worlds
Windows:    \\192.168.1.10\Minecraft\Worlds
Linux:      /mnt/nas/minecraft-worlds
Steam Deck: /run/media/deck/NAS/minecraft-worlds
```

Make sure the current user can read, create, and delete files in this folder.

#### 2. Select the storage

1. Open **Simple World Sync** settings through Mod Menu.
2. Select **Local folder / NAS**.
3. Click **Choose folder**.
4. Select the prepared shared folder.
5. Click **Use this storage**.

The mod automatically checks read and write access with a temporary test file.

#### 3. Enable synchronization for a world

1. Open the singleplayer world list.
2. Select the required world.
3. Click Minecraft's standard **Edit** button.
4. Click **Enable world synchronization**.
5. Wait for the first full upload to finish.

#### 4. Connect another device

1. Mount the same NAS folder on the second device.
2. Select it in Simple World Sync settings.
3. Open **Cloud Worlds** from the world-selection screen.
4. Select the world and click **Download**.

### ☁️ Yandex Disk setup

#### 1. Sign in

1. Open **Simple World Sync** settings.
2. Select **Yandex Disk**.
3. Click **Sign in to Yandex**.
4. Sign in to the required Yandex account in your browser.
5. Grant access to the application.
6. If Yandex displays a code, copy it and paste it into the Minecraft field.

Your login and password are never entered into the mod. The OAuth token is created by Yandex for your account and stored in the operating system's secure credential storage.

#### 2. Activate the storage

After a successful check, the mod displays:

```text
Authorized. Yandex Disk is available. Reading and writing work.
```

Click **Use this storage**. The mod creates:

```text
/SimpleWorldSync
```

#### 3. Upload and connect a world

1. Select a world in the world list and click **Edit**.
2. Click **Enable world synchronization**.
3. Wait for the first upload to finish.

On another device, sign in to the same Yandex account, activate Yandex Disk, and download the world from **Cloud Worlds**.

### ☁️ Managing cloud worlds

The cloud button on the world-selection screen opens a separate management screen. It can:

- list worlds in the active storage;
- show the world name, version, and identifier;
- download a world that is not present locally;
- refresh the list;
- delete a remote world copy.

Only worlds from the **active storage provider** are displayed. The list is reloaded after switching providers.

### 🔐 Security

- the mod never requests your Yandex password;
- OAuth tokens are not displayed in the UI or stored in the normal config file;
- tokens are kept in the operating system's secure credential storage;
- local and remote paths are validated before deletion;
- `session.lock`, `.git`, and service files are excluded from world data.

### ⚠️ Important

- Never open the same world on two devices at the same time.
- Wait for synchronization to finish before launching the world elsewhere.
- Make a manual backup before the first synchronization.
- Do not disconnect the NAS or internet connection during a transfer.
- The first upload of a large world may take a while.
- This mod is designed for singleplayer worlds and is not a Minecraft server.

### 📦 Installation

1. Install Fabric Loader for Minecraft `1.21.1`.
2. Install Fabric API.
3. Download the JAR from Releases.
4. Put it into the `mods` folder.
5. Launch Minecraft.

Prism Launcher:

```text
Instance → Edit → Mods → Add File
```

### 🛠️ Building from source

```powershell
git clone https://github.com/Grovymon/simple-world-sync.git
cd simple-world-sync
.\gradlew.bat build
```

Output:

```text
build/libs/simple-world-sync-0.1.4.jar
```

### 🐞 Feedback

When opening an Issue, include:

- Minecraft, Fabric Loader, and mod versions;
- operating system and device;
- selected storage provider;
- exact steps to reproduce;
- expected and actual behavior;
- a relevant log excerpt without OAuth tokens.
