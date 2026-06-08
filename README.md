# Simple World Sync

`Simple World Sync` — клиентский Fabric-мод для Minecraft 1.21.1. Он помогает переносить одиночные миры между ПК, Steam Deck и другими устройствами через обычную общую папку: Syncthing, NAS, SMB, WebDAV или любой другой способ синхронизации файлов.

Мод не использует облачные API, GitHub API, токены, Gitea или внешний сервер. Он работает только с локальными путями файловой системы.

## Возможности

- настройка sync-папки в `config/simpleworldsync.json` или через экран мода;
- ручной upload выбранного мира в sync-папку;
- ручное восстановление выбранного мира из sync-папки;
- автоматический upload после выхода из одиночного мира;
- backup локального мира перед восстановлением;
- исключение `session.lock` и файлов Distant Horizons из архива;
- хранение `latest.zip` и `metadata.json` рядом в sync-папке;
- понятные сообщения об ошибках и обычный logger.

## Сборка

Требования:

- Java 21;
- интернет при первой сборке, чтобы Gradle скачал зависимости Fabric;
- Minecraft/Fabric target: 1.21.1.

Команда:

```bash
./gradlew build
```

На Windows можно использовать:

```powershell
.\gradlew.bat build
```

Готовый `.jar` будет лежать в:

```text
build/libs/simple-world-sync-0.1.0.jar
```

Файл `*-sources.jar` нужен только для исходников, в Prism Launcher его ставить не нужно.

## Установка в Prism Launcher

1. Открой нужный instance.
2. Убедись, что instance использует Minecraft `1.21.1`, Fabric Loader и Java 21.
3. Открой `Mods`.
4. Нажми `Add file`.
5. Выбери `build/libs/simple-world-sync-0.1.0.jar`.
6. Запусти игру.

## Настройка sync-папки

После первого запуска появится файл:

```text
config/simpleworldsync.json
```

Пример:

```json
{
  "syncFolder": "",
  "autoUploadOnWorldExit": true,
  "autoCheckOnWorldListOpen": true,
  "createBackupBeforeRestore": true,
  "excludedPatterns": [
    "session.lock",
    "**/DistantHorizons.sqlite",
    "**/DistantHorizons.sqlite-wal",
    "**/DistantHorizons.sqlite-shm",
    "**/DistantHorizons.sqlite-journal",
    "**/DistantHorizons.sqlite-*"
  ]
}
```

Можно указать путь вручную в JSON или открыть экран `World Sync` из меню одиночных миров и нажать `Set Sync Folder`.

Примеры путей:

```text
D:\Syncthing\MinecraftWorlds
/home/deck/Sync/MinecraftWorlds
\\NAS\minecraft-worlds
```

## Использование

1. На первом устройстве открой меню одиночных миров.
2. Нажми `World Sync`.
3. Выбери мир.
4. Нажми `Upload World`.
5. Дождись, пока Syncthing/NAS/SMB доставит файлы на другое устройство.
6. На втором устройстве открой `World Sync`.
7. Выбери такой же мир или создай локальную папку мира с тем же именем.
8. Нажми `Check Remote Version`.
9. Если remote-версия новее, нажми `Download / Restore World`.

В sync-папке структура такая:

```text
syncFolder/
  world-slug/
    latest.zip
    metadata.json
```

`metadata.json` содержит имя мира, имя папки мира, версию, время upload, имя устройства, версию Minecraft и версию мода.

## Syncthing и NAS

Мод сам не синхронизирует сеть. Он только кладёт архивы в локальную папку и читает их оттуда. Syncthing, NAS, SMB, WebDAV или другой инструмент должны сами доставить содержимое sync-папки между устройствами.

Для Syncthing лучше дождаться статуса `Up to Date` перед запуском restore на другом устройстве.

## Важная безопасность

Не открывай один и тот же мир одновременно на двух устройствах. Minecraft-мир не рассчитан на параллельную запись: можно получить конфликт файлов, потерю чанков или повреждение данных. Перед первым тестом вручную сделай копию мира через Minecraft или обычным копированием папки `saves`.

Перед restore мод создаёт backup в папке:

```text
simpleworldsync-backups/
```

Если backup отключён, restore существующего локального мира будет заблокирован.

## Почему исключён Distant Horizons

Distant Horizons создаёт крупные SQLite-файлы и журналы рядом с миром. Они быстро становятся очень большими, часто меняются и плохо подходят для простой папочной синхронизации. Поэтому мод по умолчанию исключает:

```text
**/DistantHorizons.sqlite
**/DistantHorizons.sqlite-wal
**/DistantHorizons.sqlite-shm
**/DistantHorizons.sqlite-journal
**/DistantHorizons.sqlite-*
```

## Ограничения первой версии

- UI использует простой экран из меню одиночных миров, без зависимости от Mod Menu.
- Выбор папки пока вводится текстом; системный file picker не используется.
- Версии определяются по metadata мода. Для мира, который ещё ни разу не загружался этим модом, локальная версия считается неизвестной.
- Мод не решает конфликты Syncthing/NAS автоматически. Если два устройства одновременно загрузили разные версии, нужно выбрать нужную вручную.
