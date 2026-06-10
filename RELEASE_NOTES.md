# Simple World Sync 0.1.0

[Русский](#русский) | [English](#english)

## Русский

Первый публичный релиз Simple World Sync.

### Что умеет мод

- синхронизация одиночных миров Minecraft через папку;
- поддержка NAS, Syncthing, Google Drive, Яндекс Диск и похожих решений;
- автоматическая проверка мира перед запуском;
- автоматическая выгрузка после выхода из мира;
- инкрементальная синхронизация файлов;
- отображение прогресса;
- защита от одновременной записи;
- возможность удалить серверную копию мира из папки синхронизации;
- исключение `session.lock`, `.git` и Distant Horizons SQLite-файлов.

### Требования

- Minecraft 1.21.1
- Fabric Loader
- Fabric API
- Java 21

### Важно

Перед первым использованием сделайте резервную копию мира.

Не запускайте один и тот же мир одновременно на двух устройствах. Дождитесь завершения синхронизации перед запуском мира на другом устройстве.

### Обратная связь

Если у вас есть предложения по изменению или доработке мода, баг-репорты или идеи новых функций — создавайте Issue на GitHub.

## English

First public release of Simple World Sync.

### Features

- sync Minecraft singleplayer worlds through a folder;
- supports NAS, Syncthing, Google Drive, Yandex Disk, and similar tools;
- automatic world check before launch;
- automatic upload after leaving a world;
- incremental file-based synchronization;
- progress screen;
- protection against simultaneous writes;
- ability to delete a remote/server-side copy from the sync folder;
- excludes `session.lock`, `.git`, and Distant Horizons SQLite files.

### Requirements

- Minecraft 1.21.1
- Fabric Loader
- Fabric API
- Java 21

### Important

Make a manual backup before first use.

Do not open the same world on two devices at the same time. Wait for synchronization to finish before launching the world on another device.

### Feedback

If you have suggestions, bug reports, or feature requests, feel free to open an Issue on GitHub.
