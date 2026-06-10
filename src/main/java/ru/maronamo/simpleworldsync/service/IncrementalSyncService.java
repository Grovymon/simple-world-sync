package ru.maronamo.simpleworldsync.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.config.ConfigManager;
import ru.maronamo.simpleworldsync.config.SimpleWorldSyncConfig;
import ru.maronamo.simpleworldsync.metadata.OperationLock;
import ru.maronamo.simpleworldsync.metadata.SyncStateDatabase;
import ru.maronamo.simpleworldsync.metadata.WorldIdentity;
import ru.maronamo.simpleworldsync.metadata.WorldManifest;
import ru.maronamo.simpleworldsync.metadata.WorldMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class IncrementalSyncService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Duration STALE_LOCK_AFTER = Duration.ofHours(1L);
    private static final long WORLD_CLOSE_TIMEOUT_MILLIS = 30_000L;
    private static final long WORLD_CLOSE_POLL_MILLIS = 250L;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String STAGE_WAITING_CLOSE = "Ожидание закрытия мира...";
    private static final String STAGE_SCAN_LOCAL = "Сканирование локального мира...";
    private static final String STAGE_FIND_CHANGES = "Поиск изменений...";
    private static final String STAGE_UPLOAD_CHANGES = "Выгрузка изменений...";
    private static final Consumer<SyncProgress> NO_PROGRESS = progress -> {
    };
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private final ConfigManager configManager;
    private final Path savesDirectory;
    private final Path backupsDirectory;

    public IncrementalSyncService(ConfigManager configManager, Path runDirectory) {
        this.configManager = configManager;
        this.savesDirectory = runDirectory.resolve("saves");
        this.backupsDirectory = runDirectory.resolve("simpleworldsync-backups");
    }

    public Optional<Path> syncFolder() {
        SimpleWorldSyncConfig config = configManager.getConfig();
        if (!config.hasSyncFolder()) {
            return Optional.empty();
        }

        return Optional.of(Path.of(config.syncFolder).toAbsolutePath().normalize());
    }

    public boolean isSyncFolderUsable() {
        return syncFolder().filter(Files::isDirectory).isPresent();
    }

    public boolean isWorldSyncEnabled(String folderName) {
        return resolveWorldIdentity(folderName, folderName).syncEnabled();
    }

    public Optional<WorldIdentity> readIdentity(String folderName) {
        return WorldIdentityResolver.readIdentity(worldPath(folderName));
    }

    public WorldSyncIdentity resolveWorldIdentity(String folderName, String displayName) {
        return WorldIdentityResolver.resolve(savesDirectory, folderName, displayName);
    }

    public WorldSyncIdentity resolveWorldIdentity(Path worldPath, String displayName) {
        return WorldIdentityResolver.resolve(savesDirectory, worldPath, displayName);
    }

    public SyncResult enableWorldSyncAndUpload(
            String folderName,
            String worldName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        if (syncFolder().isEmpty()) {
            return SyncResult.error("Папка синхронизации не выбрана.");
        }

        return incrementalUploadWorld(folderName, worldName, true, progress, isCancelled);
    }

    public SyncResult disableWorldSync(String folderName) {
        Optional<WorldIdentity> identity = readIdentity(folderName);
        if (identity.isEmpty()) {
            return SyncResult.ok("Синхронизация отключена.");
        }

        try {
            identity.get().syncEnabled = false;
            writeIdentity(folderName, identity.get());
            return SyncResult.ok("Синхронизация отключена.");
        } catch (IOException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to disable sync for {}", folderName, exception);
            return SyncResult.error("Не удалось отключить синхронизацию: " + friendlyMessage(exception));
        }
    }

    public SyncResult incrementalUploadWorld(String folderName, String worldName) {
        return incrementalUploadWorld(folderName, worldName, false, NO_PROGRESS, NEVER_CANCELLED);
    }

    public SyncResult incrementalUploadWorldAfterExit(
            String folderName,
            String worldName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        return incrementalUploadWorld(folderName, worldName, false, true, progress, isCancelled);
    }

    public SyncResult incrementalUploadWorld(
            String folderName,
            String worldName,
            boolean force,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        return incrementalUploadWorld(folderName, worldName, force, false, progress, isCancelled);
    }

    private SyncResult incrementalUploadWorld(
            String folderName,
            String worldName,
            boolean force,
            boolean waitForWorldClose,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        Consumer<SyncProgress> progressSink = progress == null ? NO_PROGRESS : progress;
        BooleanSupplier cancellation = isCancelled == null ? NEVER_CANCELLED : isCancelled;
        String displayName = displayName(folderName, worldName);
        Path worldPath = worldPath(folderName);
        WorldIdentity identity = null;
        Optional<WorldIdentity> previousIdentity = readIdentity(folderName);
        boolean previousEnabled = previousIdentity.map(value -> value.syncEnabled).orElse(false);
        long previousVersion = previousIdentity.map(value -> value.lastSyncedVersion).orElse(0L);

        try {
            ensureNotCancelled(cancellation);

            Optional<Path> syncFolder = syncFolder();
            if (syncFolder.isEmpty()) {
                return SyncResult.error("Папка синхронизации не выбрана.");
            }

            if (!Files.isDirectory(worldPath)) {
                return SyncResult.error("Папка локального мира не найдена: " + folderName);
            }

            if (waitForWorldClose) {
                SyncResult waitResult = waitUntilWorldClosed(folderName, displayName, progressSink, cancellation);
                if (!waitResult.success()) {
                    return waitResult;
                }
            } else if (isActualWorldRunning(folderName)) {
                return SyncResult.error("Этот мир сейчас открыт. Закройте его перед выгрузкой.");
            }

            Files.createDirectories(syncFolder.get());
            Files.createDirectories(backupsDirectory);

            identity = ensureIdentity(folderName, true);

            Path remoteWorldDir = remoteWorldDir(syncFolder.get(), identity);
            Files.createDirectories(remoteWorldDir.resolve("files"));
            Files.createDirectories(remoteWorldDir.resolve("locks"));
            Files.createDirectories(remoteWorldDir.resolve("backups"));

            try (LockHandle ignored = acquireLock(remoteWorldDir, identity, force ? "force-upload" : "upload")) {
                Optional<WorldManifest> remoteManifest = readRemoteManifest(identity);
                long nextVersion = Math.max(
                        remoteManifest.map(manifest -> manifest.version).orElse(0L),
                        identity.lastSyncedVersion
                ) + 1L;

                publish(progressSink, SyncOperation.UPLOAD, displayName, STAGE_SCAN_LOCAL, 0L, 0L, "", 0, 0, 0);
                List<IncrementalFile> localFiles = scanWorldFiles(worldPath, SyncOperation.UPLOAD, displayName, progressSink, cancellation);
                Map<String, IncrementalFile> localByPath = localFileMap(localFiles);
                Map<String, WorldManifest.FileRecord> remoteByPath = manifestFiles(remoteManifest.orElse(null));
                publish(progressSink, SyncOperation.UPLOAD, displayName, STAGE_FIND_CHANGES, 0L, 0L, "", 0, 0, 0);
                ChangeSet changes = diffForUpload(localByPath, remoteByPath);

                if (!force && remoteManifest.isPresent() && changes.isEmpty()) {
                    identity.syncEnabled = true;
                    identity.lastSyncedVersion = remoteManifest.get().version;
                    writeIdentity(folderName, identity);
                    saveLastManifest(identity.worldId, remoteManifest.get());
                    updateState(identity, remoteManifest.get());
                    publish(progressSink, SyncOperation.UPLOAD, displayName, "Изменений нет", 1L, 1L, "", 0, 0, 0);
                    return SyncResult.ok("Изменений нет. Мир уже синхронизирован.");
                }

                identity.syncEnabled = true;
                identity.lastSyncedVersion = nextVersion;
                writeIdentity(folderName, identity);

                localByPath.put(".simpleworldsync/world.json", snapshotWorldFile(worldPath, identityPath(folderName)));
                changes = diffForUpload(localByPath, remoteByPath);

                long totalBytes = changes.uploadBytes();
                long[] processedBytes = {0L};
                int newCount = changes.newFiles().size();
                int changedCount = changes.changedFiles().size();
                int deletedCount = changes.deletedPaths().size();
                Path remoteFilesDir = remoteWorldDir.resolve("files");

                publish(progressSink, SyncOperation.UPLOAD, displayName, STAGE_UPLOAD_CHANGES, 0L, totalBytes, "", newCount, changedCount, deletedCount);
                for (IncrementalFile file : changes.filesToCopy()) {
                    ensureNotCancelled(cancellation);
                    Path target = safeResolve(remoteFilesDir, file.relativePath());
                    copyFile(file.path(), target, SyncOperation.UPLOAD, displayName, STAGE_UPLOAD_CHANGES, processedBytes, totalBytes, file.relativePath(), newCount, changedCount, deletedCount, progressSink, cancellation);
                }

                publish(progressSink, SyncOperation.UPLOAD, displayName, "Удаление лишних файлов...", processedBytes[0], totalBytes, "", newCount, changedCount, deletedCount);
                for (String deletedPath : changes.deletedPaths()) {
                    ensureNotCancelled(cancellation);
                    deleteSyncedFile(remoteFilesDir, deletedPath);
                    publish(progressSink, SyncOperation.UPLOAD, displayName, "Удаление лишних файлов...", processedBytes[0], totalBytes, deletedPath, newCount, changedCount, deletedCount);
                }

                WorldManifest manifest = buildManifest(identity, displayName, folderName, nextVersion, localByPath);
                writeJsonAtomic(remoteWorldDir.resolve("manifest.json"), manifest);
                writeJsonAtomic(remoteWorldDir.resolve("metadata.json"), new WorldMetadata(
                        displayName,
                        folderName,
                        nextVersion,
                        manifest.updatedAt,
                        manifest.deviceName,
                        manifest.minecraftVersion,
                        manifest.modVersion
                ));
                saveLastManifest(identity.worldId, manifest);
                updateState(identity, manifest);

                publish(progressSink, SyncOperation.UPLOAD, displayName, "Мир успешно синхронизирован", 1L, 1L, "", newCount, changedCount, deletedCount);
                return SyncResult.ok("Мир успешно синхронизирован (версия " + nextVersion + ").");
            }
        } catch (OperationCancelledException exception) {
            restoreIdentityAfterFailedUpload(folderName, identity, previousEnabled, previousVersion);
            publish(progressSink, SyncOperation.UPLOAD, displayName, "Операция отменена", 0L, 0L, "", 0, 0, 0);
            return SyncResult.error("Операция отменена.");
        } catch (IOException | RuntimeException exception) {
            restoreIdentityAfterFailedUpload(folderName, identity, previousEnabled, previousVersion);
            SimpleWorldSyncClient.LOGGER.error("Failed incremental upload for {}", folderName, exception);
            return SyncResult.error("Ошибка выгрузки изменений: " + friendlyMessage(exception));
        }
    }

    public SyncResult incrementalRestoreWorld(
            String folderName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        Consumer<SyncProgress> progressSink = progress == null ? NO_PROGRESS : progress;
        BooleanSupplier cancellation = isCancelled == null ? NEVER_CANCELLED : isCancelled;
        String displayName = folderName;
        Path worldPath = worldPath(folderName);
        Path backupRoot = null;

        try {
            ensureNotCancelled(cancellation);
            Optional<Path> syncFolder = syncFolder();
            if (syncFolder.isEmpty()) {
                return SyncResult.error("Папка синхронизации не выбрана.");
            }

            WorldIdentity identity = readIdentity(folderName)
                    .orElseThrow(() -> new IOException("world.json не найден для мира: " + folderName));
            Path remoteWorldDir = remoteWorldDir(syncFolder.get(), identity);
            WorldManifest manifest = readJson(remoteWorldDir.resolve("manifest.json"), WorldManifest.class)
                    .orElseThrow(() -> new IOException("manifest.json не найден."));
            displayName = displayName(folderName, manifest.worldName);

            if (Files.isDirectory(worldPath) && isActualWorldRunning(folderName)) {
                return SyncResult.error("Этот мир сейчас открыт. Закройте его перед восстановлением.");
            }

            try (LockHandle ignored = acquireLock(remoteWorldDir, identity, "restore")) {
                Files.createDirectories(worldPath);
                Files.createDirectories(backupsDirectory);

                publish(progressSink, SyncOperation.RESTORE, displayName, STAGE_FIND_CHANGES, 0L, 0L, "", 0, 0, 0);
                List<IncrementalFile> localFiles = scanWorldFiles(worldPath, SyncOperation.RESTORE, displayName, progressSink, cancellation);
                Map<String, IncrementalFile> localByPath = localFileMap(localFiles);
                Map<String, WorldManifest.FileRecord> remoteByPath = manifestFiles(manifest);
                RestoreChangeSet changes = diffForRestore(localByPath, remoteByPath);

                if (changes.isEmpty()) {
                    identity.syncEnabled = true;
                    identity.lastSyncedVersion = manifest.version;
                    writeIdentity(folderName, identity);
                    saveLastManifest(identity.worldId, manifest);
                    updateState(identity, manifest);
                    publish(progressSink, SyncOperation.RESTORE, displayName, "Изменений нет", 1L, 1L, "", 0, 0, 0);
                    return SyncResult.ok("Изменений нет. Мир уже синхронизирован.");
                }

                int newCount = changes.newFiles().size();
                int changedCount = changes.changedFiles().size();
                int deletedCount = changes.deletedPaths().size();
                backupRoot = backupsDirectory.resolve(folderName + "-incremental-" + FILE_TIME.format(Instant.now()));
                createRestoreBackup(worldPath, backupRoot, changes, localByPath, progressSink, cancellation, displayName);

                long totalBytes = changes.restoreBytes();
                long[] processedBytes = {0L};
                Path remoteFilesDir = remoteWorldDir.resolve("files");

                publish(progressSink, SyncOperation.RESTORE, displayName, "Скачивание изменений...", 0L, totalBytes, "", newCount, changedCount, deletedCount);
                for (WorldManifest.FileRecord file : changes.filesToCopy()) {
                    ensureNotCancelled(cancellation);
                    Path source = safeResolve(remoteFilesDir, file.path);
                    Path target = safeResolve(worldPath, file.path);
                    copyFile(source, target, SyncOperation.RESTORE, displayName, "Скачивание изменений...", processedBytes, totalBytes, file.path, newCount, changedCount, deletedCount, progressSink, cancellation);
                    if (file.modifiedAt != null && !file.modifiedAt.isBlank()) {
                        try {
                            Files.setLastModifiedTime(target, FileTime.from(Instant.parse(file.modifiedAt)));
                        } catch (RuntimeException ignoredInstant) {
                            SimpleWorldSyncClient.LOGGER.debug("Could not restore modified time for {}", target, ignoredInstant);
                        }
                    }
                }

                publish(progressSink, SyncOperation.RESTORE, displayName, "Удаление лишних локальных файлов...", processedBytes[0], totalBytes, "", newCount, changedCount, deletedCount);
                for (String deletedPath : changes.deletedPaths()) {
                    ensureNotCancelled(cancellation);
                    deleteSyncedFile(worldPath, deletedPath);
                    publish(progressSink, SyncOperation.RESTORE, displayName, "Удаление лишних локальных файлов...", processedBytes[0], totalBytes, deletedPath, newCount, changedCount, deletedCount);
                }

                identity.syncEnabled = true;
                identity.remoteSlug = manifestSlug(identity);
                identity.lastSyncedVersion = manifest.version;
                writeIdentity(folderName, identity);
                saveLastManifest(identity.worldId, manifest);
                updateState(identity, manifest);

                publish(progressSink, SyncOperation.RESTORE, displayName, "Мир успешно синхронизирован", 1L, 1L, "", newCount, changedCount, deletedCount);
                return SyncResult.ok("Мир успешно синхронизирован (версия " + manifest.version + ").");
            }
        } catch (OperationCancelledException exception) {
            publish(progressSink, SyncOperation.RESTORE, displayName, "Операция отменена", 0L, 0L, "", 0, 0, 0);
            return SyncResult.error("Операция отменена.");
        } catch (IOException | RuntimeException exception) {
            rollbackRestoreBackup(worldPath, backupRoot);
            SimpleWorldSyncClient.LOGGER.error("Failed incremental restore for {}", folderName, exception);
            return SyncResult.error("Ошибка восстановления изменений: " + friendlyMessage(exception));
        }
    }

    public SyncResult downloadRemoteWorld(
            WorldEntry remoteWorld,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        Consumer<SyncProgress> progressSink = progress == null ? NO_PROGRESS : progress;
        BooleanSupplier cancellation = isCancelled == null ? NEVER_CANCELLED : isCancelled;

        try {
            Optional<Path> syncFolder = syncFolder();
            if (syncFolder.isEmpty()) {
                return SyncResult.error("Папка синхронизации не выбрана.");
            }
            if (remoteWorld.remoteSlug().isBlank()) {
                return SyncResult.error("Удалённая папка мира не найдена.");
            }

            Path remoteWorldDir = syncFolder.get().resolve(remoteWorld.remoteSlug()).normalize();
            WorldManifest manifest = readJson(remoteWorldDir.resolve("manifest.json"), WorldManifest.class)
                    .orElseThrow(() -> new IOException("manifest.json не найден."));
            String localFolderName = uniqueLocalFolderName(manifest, remoteWorld.remoteSlug());
            Path localWorldPath = worldPath(localFolderName);
            Files.createDirectories(localWorldPath);

            WorldIdentity identity = new WorldIdentity();
            identity.worldId = manifest.worldId == null || manifest.worldId.isBlank()
                    ? UUID.randomUUID().toString()
                    : manifest.worldId;
            identity.syncEnabled = true;
            identity.remoteSlug = remoteWorld.remoteSlug();
            identity.createdAt = Instant.now().toString();
            identity.lastSyncedVersion = 0L;
            writeIdentity(localFolderName, identity);

            publish(progressSink, SyncOperation.RESTORE, displayName(localFolderName, manifest.worldName), "Скачивание мира на это устройство...", 0L, 0L, "", 0, 0, 0);
            return incrementalRestoreWorld(localFolderName, progressSink, cancellation);
        } catch (OperationCancelledException exception) {
            return SyncResult.error("Операция отменена.");
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to download remote world {}", remoteWorld.remoteSlug(), exception);
            return SyncResult.error("Ошибка скачивания мира: " + friendlyMessage(exception));
        }
    }

    public SyncResult deleteRemoteWorld(WorldEntry world) {
        try {
            Optional<Path> syncFolder = syncFolder();
            if (syncFolder.isEmpty() || !Files.isDirectory(syncFolder.get())) {
                return SyncResult.error("Папка синхронизации недоступна.");
            }

            String remoteSlug = remoteSlugForDeletion(world);
            if (remoteSlug.isBlank()) {
                return SyncResult.error("Удалённая папка мира не найдена.");
            }

            Path syncRoot = syncFolder.get().toAbsolutePath().normalize();
            Path remoteWorldDir = syncRoot.resolve(remoteSlug).toAbsolutePath().normalize();
            if (!isSafeRemoteWorldDir(syncRoot, remoteWorldDir)) {
                return SyncResult.error("Небезопасный путь удалённой папки мира.");
            }

            if (!Files.isDirectory(remoteWorldDir)) {
                return SyncResult.error("Удалённая папка мира не найдена.");
            }

            if (lockState(remoteWorldDir) == LockState.ACTIVE) {
                return SyncResult.error("Не удалось удалить мир: синхронизация сейчас выполняется. Попробуйте позже.");
            }

            deleteDirectory(remoteWorldDir);
            return SyncResult.ok("Мир удалён из синхронизации.");
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to delete remote world {}", world == null ? "-" : world.remoteSlug(), exception);
            return SyncResult.error("Не удалось удалить мир из синхронизации: " + friendlyMessage(exception));
        }
    }

    private String remoteSlugForDeletion(WorldEntry world) {
        if (world == null) {
            return "";
        }
        if (!world.remoteSlug().isBlank()) {
            return world.remoteSlug();
        }

        Optional<WorldIdentity> identity = readIdentity(world.folderName());
        if (identity.isEmpty()) {
            return "";
        }
        if (identity.get().remoteSlug != null && !identity.get().remoteSlug.isBlank()) {
            return identity.get().remoteSlug;
        }
        if (identity.get().worldId == null || identity.get().worldId.isBlank()) {
            return "";
        }

        String compactWorldId = identity.get().worldId.replace("-", "");
        if (compactWorldId.length() < 8) {
            return "";
        }
        return "world-" + compactWorldId.substring(0, 8);
    }

    private boolean isSafeRemoteWorldDir(Path syncRoot, Path remoteWorldDir) {
        if (remoteWorldDir.equals(syncRoot) || !remoteWorldDir.startsWith(syncRoot)) {
            return false;
        }

        Path relative = syncRoot.relativize(remoteWorldDir);
        return relative.getNameCount() == 1;
    }

    private String uniqueLocalFolderName(WorldManifest manifest, String fallback) {
        String preferred = firstPresent(manifest.worldFolderName, manifest.worldName, fallback);
        if ("-".equals(preferred)) {
            preferred = firstPresent(manifest.worldName, fallback);
        }

        String base = sanitizeFolderName(preferred);
        String candidate = base;
        int index = 2;
        while (Files.exists(worldPath(candidate))) {
            candidate = base + "-" + index;
            index++;
        }
        return candidate;
    }

    private String sanitizeFolderName(String value) {
        String sanitized = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitized = sanitized.replaceAll("\\s+", " ");
        return sanitized.isBlank() ? "world" : sanitized;
    }

    private String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String firstPresent(String first, String second, String third) {
        return firstPresent(first, firstPresent(second, third));
    }

    public WorldLaunchCheck checkWorldForLaunch(
            String folderName,
            String worldName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        Consumer<SyncProgress> progressSink = progress == null ? NO_PROGRESS : progress;
        BooleanSupplier cancellation = isCancelled == null ? NEVER_CANCELLED : isCancelled;
        String displayName = displayName(folderName, worldName);

        try {
            Optional<WorldIdentity> identity = readIdentity(folderName);
            if (identity.isEmpty() || !identity.get().syncEnabled) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.DISABLED, "Синхронизация для мира не включена.");
            }

            Optional<Path> syncFolder = syncFolder();
            if (syncFolder.isEmpty() || !Files.isDirectory(syncFolder.get())) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.SYNC_FOLDER_MISSING, "Папка синхронизации недоступна.");
            }

            Path remoteWorldDir = remoteWorldDir(syncFolder.get(), identity.get());
            LockState lockState = lockState(remoteWorldDir);
            if (lockState == LockState.ACTIVE) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.LOCKED, "Синхронизация уже выполняется на другом устройстве.");
            }
            if (lockState == LockState.STALE) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.STALE_LOCK, "Найдена старая блокировка синхронизации. Удалить её?");
            }

            publish(progressSink, SyncOperation.RESTORE, displayName, "Проверка версии мира...", 0L, 0L, "", 0, 0, 0);
            Optional<WorldManifest> remoteManifest = readJson(remoteWorldDir.resolve("manifest.json"), WorldManifest.class);
            if (remoteManifest.isEmpty()) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.READY, "Удалённая версия пока не создана.");
            }

            updateState(identity.get(), remoteManifest.get());
            if (remoteManifest.get().version <= identity.get().lastSyncedVersion) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.READY, "Мир уже синхронизирован.");
            }

            if (hasLocalChangesSinceLastSync(folderName, identity.get(), displayName, progressSink, cancellation)) {
                return new WorldLaunchCheck(WorldLaunchCheck.Action.CONFLICT, "Обнаружен конфликт синхронизации.");
            }

            return new WorldLaunchCheck(WorldLaunchCheck.Action.RESTORE_REQUIRED, "На сервере есть новая версия мира.");
        } catch (OperationCancelledException exception) {
            return new WorldLaunchCheck(WorldLaunchCheck.Action.LOCKED, "Проверка отменена.");
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed launch sync check for {}", folderName, exception);
            return new WorldLaunchCheck(WorldLaunchCheck.Action.SYNC_FOLDER_MISSING, "Проверка синхронизации не удалась: " + friendlyMessage(exception));
        }
    }

    public SyncResult deleteStaleLock(String folderName) {
        try {
            Optional<WorldIdentity> identity = readIdentity(folderName);
            Optional<Path> syncFolder = syncFolder();
            if (identity.isEmpty() || syncFolder.isEmpty()) {
                return SyncResult.error("Папка синхронизации недоступна.");
            }

            Files.deleteIfExists(remoteWorldDir(syncFolder.get(), identity.get()).resolve("locks").resolve("operation.lock"));
            return SyncResult.ok("Старая блокировка удалена.");
        } catch (IOException exception) {
            return SyncResult.error("Не удалось удалить блокировку: " + friendlyMessage(exception));
        }
    }

    private boolean hasLocalChangesSinceLastSync(
            String folderName,
            WorldIdentity identity,
            String displayName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) throws IOException {
        Optional<WorldManifest> baseline = readLastManifest(identity.worldId);
        if (baseline.isEmpty()) {
            return true;
        }

        publish(progress, SyncOperation.RESTORE, displayName, "Проверка локальных изменений...", 0L, 0L, "", 0, 0, 0);
        Map<String, IncrementalFile> localByPath = localFileMap(scanWorldFiles(worldPath(folderName), SyncOperation.RESTORE, displayName, progress, isCancelled));
        return !sameAsBaseline(localByPath, manifestFiles(baseline.get()));
    }

    private boolean sameAsBaseline(Map<String, IncrementalFile> localByPath, Map<String, WorldManifest.FileRecord> baselineByPath) {
        if (!localByPath.keySet().equals(baselineByPath.keySet())) {
            return false;
        }

        for (Map.Entry<String, IncrementalFile> entry : localByPath.entrySet()) {
            WorldManifest.FileRecord baseline = baselineByPath.get(entry.getKey());
            if (baseline == null || isChanged(entry.getValue(), baseline)) {
                return false;
            }
        }

        return true;
    }

    private WorldIdentity ensureIdentity(String folderName, boolean syncEnabled) throws IOException {
        WorldIdentity identity = readIdentity(folderName).orElseGet(WorldIdentity::new);
        boolean changed = false;

        if (identity.worldId == null || identity.worldId.isBlank()) {
            identity.worldId = UUID.randomUUID().toString();
            changed = true;
        }
        if (identity.createdAt == null || identity.createdAt.isBlank()) {
            identity.createdAt = Instant.now().toString();
            changed = true;
        }
        if (identity.remoteSlug == null || identity.remoteSlug.isBlank()) {
            identity.remoteSlug = "world-" + identity.worldId.replace("-", "").substring(0, 8);
            changed = true;
        }
        if (syncEnabled && !identity.syncEnabled) {
            identity.syncEnabled = true;
            changed = true;
        }

        if (changed || !Files.isRegularFile(identityPath(folderName))) {
            writeIdentity(folderName, identity);
        }

        return identity;
    }

    private void writeIdentity(String folderName, WorldIdentity identity) throws IOException {
        writeJsonAtomic(identityPath(folderName), identity);
    }

    private Path identityPath(String folderName) {
        return worldPath(folderName).resolve(".simpleworldsync").resolve("world.json");
    }

    private Path worldPath(String folderName) {
        return savesDirectory.resolve(folderName).normalize();
    }

    private Path remoteWorldDir(Path syncFolder, WorldIdentity identity) {
        return syncFolder.resolve(manifestSlug(identity)).normalize();
    }

    private String manifestSlug(WorldIdentity identity) {
        if (identity.remoteSlug != null && !identity.remoteSlug.isBlank()) {
            return identity.remoteSlug;
        }

        return "world-" + identity.worldId.replace("-", "").substring(0, 8);
    }

    private List<IncrementalFile> scanWorldFiles(
            Path worldPath,
            SyncOperation operation,
            String displayName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) throws IOException {
        List<IncrementalFile> files = new ArrayList<>();
        SimpleWorldSyncConfig config = configManager.getConfig();
        ExclusionMatcher exclusions = new ExclusionMatcher(config.effectiveExcludedPatterns());
        long[] scannedBytes = {0L};
        String scanStage = operation == SyncOperation.UPLOAD ? STAGE_SCAN_LOCAL : STAGE_FIND_CHANGES;

        Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                ensureNotCancelled(isCancelled);
                if (!dir.equals(worldPath)) {
                    Path relative = worldPath.relativize(dir);
                    if (exclusions.isExcluded(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                ensureNotCancelled(isCancelled);
                Path relative = worldPath.relativize(file);
                if (exclusions.isExcluded(relative)) {
                    return FileVisitResult.CONTINUE;
                }

                String relativePath = relative.toString().replace('\\', '/');
                publish(progress, operation, displayName, scanStage, scannedBytes[0], 0L, relativePath, 0, 0, 0);
                String hash = sha256(file);
                scannedBytes[0] += Math.max(0L, attrs.size());
                files.add(new IncrementalFile(file, relativePath, attrs.size(), attrs.lastModifiedTime(), hash));
                publish(progress, operation, displayName, scanStage, scannedBytes[0], 0L, relativePath, 0, 0, 0);
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(IncrementalFile::relativePath, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private IncrementalFile snapshotWorldFile(Path worldPath, Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        String relativePath = worldPath.relativize(file).toString().replace('\\', '/');
        return new IncrementalFile(file, relativePath, attrs.size(), attrs.lastModifiedTime(), sha256(file));
    }

    private Map<String, IncrementalFile> localFileMap(List<IncrementalFile> files) {
        Map<String, IncrementalFile> result = new LinkedHashMap<>();
        for (IncrementalFile file : files) {
            result.put(file.relativePath(), file);
        }
        return result;
    }

    private Map<String, WorldManifest.FileRecord> manifestFiles(WorldManifest manifest) {
        Map<String, WorldManifest.FileRecord> files = new LinkedHashMap<>();
        if (manifest == null || manifest.files == null) {
            return files;
        }

        manifest.files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    WorldManifest.FileRecord record = entry.getValue();
                    if (record != null && record.path != null && !record.path.isBlank()) {
                        files.put(record.path.replace('\\', '/'), record);
                    }
                });
        return files;
    }

    private ChangeSet diffForUpload(
            Map<String, IncrementalFile> localByPath,
            Map<String, WorldManifest.FileRecord> remoteByPath
    ) {
        List<IncrementalFile> newFiles = new ArrayList<>();
        List<IncrementalFile> changedFiles = new ArrayList<>();
        List<String> deletedPaths = new ArrayList<>();

        for (Map.Entry<String, IncrementalFile> entry : localByPath.entrySet()) {
            WorldManifest.FileRecord remote = remoteByPath.get(entry.getKey());
            if (remote == null) {
                newFiles.add(entry.getValue());
            } else if (isChanged(entry.getValue(), remote)) {
                changedFiles.add(entry.getValue());
            }
        }

        for (String remotePath : remoteByPath.keySet()) {
            if (!localByPath.containsKey(remotePath)) {
                deletedPaths.add(remotePath);
            }
        }

        return new ChangeSet(newFiles, changedFiles, deletedPaths);
    }

    private RestoreChangeSet diffForRestore(
            Map<String, IncrementalFile> localByPath,
            Map<String, WorldManifest.FileRecord> remoteByPath
    ) {
        List<WorldManifest.FileRecord> newFiles = new ArrayList<>();
        List<WorldManifest.FileRecord> changedFiles = new ArrayList<>();
        List<String> deletedPaths = new ArrayList<>();

        for (Map.Entry<String, WorldManifest.FileRecord> entry : remoteByPath.entrySet()) {
            IncrementalFile local = localByPath.get(entry.getKey());
            if (local == null) {
                newFiles.add(entry.getValue());
            } else if (isChanged(local, entry.getValue())) {
                changedFiles.add(entry.getValue());
            }
        }

        for (String localPath : localByPath.keySet()) {
            if (!remoteByPath.containsKey(localPath)) {
                deletedPaths.add(localPath);
            }
        }

        return new RestoreChangeSet(newFiles, changedFiles, deletedPaths);
    }

    private boolean isChanged(IncrementalFile local, WorldManifest.FileRecord remote) {
        return local.size() != remote.size || !local.sha256().equalsIgnoreCase(remote.sha256 == null ? "" : remote.sha256);
    }

    private WorldManifest buildManifest(
            WorldIdentity identity,
            String displayName,
            String folderName,
            long version,
            Map<String, IncrementalFile> localByPath
    ) {
        WorldManifest manifest = new WorldManifest();
        manifest.worldId = identity.worldId;
        manifest.worldName = displayName;
        manifest.worldFolderName = folderName;
        manifest.version = version;
        manifest.updatedAt = Instant.now().toString();
        manifest.deviceName = deviceName();
        manifest.minecraftVersion = SimpleWorldSyncClient.MINECRAFT_VERSION;
        manifest.modVersion = SimpleWorldSyncClient.MOD_VERSION;

        for (IncrementalFile file : localByPath.values()) {
            manifest.files.put(file.relativePath(), file.toRecord());
        }

        return manifest;
    }

    private void createRestoreBackup(
            Path worldPath,
            Path backupRoot,
            RestoreChangeSet changes,
            Map<String, IncrementalFile> localByPath,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled,
            String displayName
    ) throws IOException {
        Files.createDirectories(backupRoot);
        Path backupFilesDir = backupRoot.resolve("files");
        long totalBytes = changes.backupBytes(localByPath);
        long[] processedBytes = {0L};
        int newCount = changes.newFiles().size();
        int changedCount = changes.changedFiles().size();
        int deletedCount = changes.deletedPaths().size();

        publish(progress, SyncOperation.RESTORE, displayName, "Создание резервной копии...", 0L, totalBytes, "", newCount, changedCount, deletedCount);
        for (WorldManifest.FileRecord record : changes.changedFiles()) {
            IncrementalFile local = localByPath.get(record.path);
            if (local != null) {
                copyFile(local.path(), safeResolve(backupFilesDir, local.relativePath()), SyncOperation.RESTORE, displayName, "Создание резервной копии...", processedBytes, totalBytes, local.relativePath(), newCount, changedCount, deletedCount, progress, isCancelled);
            }
        }
        for (String deletedPath : changes.deletedPaths()) {
            IncrementalFile local = localByPath.get(deletedPath);
            if (local != null) {
                copyFile(local.path(), safeResolve(backupFilesDir, local.relativePath()), SyncOperation.RESTORE, displayName, "Создание резервной копии...", processedBytes, totalBytes, local.relativePath(), newCount, changedCount, deletedCount, progress, isCancelled);
            }
        }
    }

    private void rollbackRestoreBackup(Path worldPath, Path backupRoot) {
        if (backupRoot == null || !Files.isDirectory(backupRoot.resolve("files"))) {
            return;
        }

        Path backupFilesDir = backupRoot.resolve("files");
        try {
            Files.walkFileTree(backupFilesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = backupFilesDir.relativize(file);
                    Files.createDirectories(worldPath.resolve(relative).getParent());
                    Files.copy(file, worldPath.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException rollbackException) {
            SimpleWorldSyncClient.LOGGER.error("Could not rollback restore from {}", backupRoot, rollbackException);
        }
    }

    private void copyFile(
            Path source,
            Path target,
            SyncOperation operation,
            String displayName,
            String stage,
            long[] processedBytes,
            long totalBytes,
            String relativePath,
            int newFiles,
            int changedFiles,
            int deletedFiles,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) throws IOException {
        Files.createDirectories(target.getParent());
        publish(progress, operation, displayName, stage, processedBytes[0], totalBytes, relativePath, newFiles, changedFiles, deletedFiles);

        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ensureNotCancelled(isCancelled);
                output.write(buffer, 0, read);
                processedBytes[0] += read;
                publish(progress, operation, displayName, stage, processedBytes[0], totalBytes, relativePath, newFiles, changedFiles, deletedFiles);
            }
        }
    }

    private void deleteSyncedFile(Path root, String relativePath) throws IOException {
        Path file = safeResolve(root, relativePath);
        Files.deleteIfExists(file);
        deleteEmptyParents(root, file.getParent());
    }

    private void deleteDirectory(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }

                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteEmptyParents(Path root, Path directory) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path current = directory == null ? null : directory.toAbsolutePath().normalize();
        while (current != null && current.startsWith(normalizedRoot) && !current.equals(normalizedRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                if (stream.iterator().hasNext()) {
                    return;
                }
                Files.deleteIfExists(current);
            } catch (IOException exception) {
                return;
            }
            current = current.getParent();
        }
    }

    private Path safeResolve(Path root, String relativePath) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relativePath.replace('\\', '/')).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Небезопасный путь в manifest.json: " + relativePath);
        }
        return target;
    }

    private void saveLastManifest(String worldId, WorldManifest manifest) throws IOException {
        writeJsonAtomic(localManifestPath(worldId), manifest);
    }

    private Optional<WorldManifest> readLastManifest(String worldId) {
        return readJson(localManifestPath(worldId), WorldManifest.class);
    }

    private Path localManifestPath(String worldId) {
        return configManager.getLocalManifestDir().resolve(worldId + ".json");
    }

    private void updateState(WorldIdentity identity, WorldManifest manifest) throws IOException {
        SyncStateDatabase database = readJson(configManager.getLocalStatePath(), SyncStateDatabase.class)
                .orElseGet(SyncStateDatabase::new);
        if (database.worlds == null) {
            database.worlds = new LinkedHashMap<>();
        }

        SyncStateDatabase.SyncStateEntry entry = database.worlds.computeIfAbsent(identity.worldId, ignored -> new SyncStateDatabase.SyncStateEntry());
        entry.lastSyncedVersion = identity.lastSyncedVersion;
        entry.lastKnownRemoteVersion = manifest.version;
        entry.lastUploadDevice = manifest.deviceName;
        entry.lastSyncTime = Instant.now().toString();
        writeJsonAtomic(configManager.getLocalStatePath(), database);
    }

    private Optional<WorldManifest> readRemoteManifest(WorldIdentity identity) {
        return syncFolder()
                .map(folder -> remoteWorldDir(folder, identity).resolve("manifest.json"))
                .flatMap(path -> readJson(path, WorldManifest.class));
    }

    private <T> Optional<T> readJson(Path path, Class<T> type) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, type));
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.warn("Could not read JSON from {}", path, exception);
            return Optional.empty();
        }
    }

    private void writeJsonAtomic(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(value, writer);
        }
        moveReplacing(temp, path);
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private LockHandle acquireLock(Path remoteWorldDir, WorldIdentity identity, String operation) throws IOException {
        Path lockPath = remoteWorldDir.resolve("locks").resolve("operation.lock");
        Files.createDirectories(lockPath.getParent());

        LockState current = lockState(remoteWorldDir);
        if (current == LockState.ACTIVE) {
            throw new IOException("Синхронизация уже выполняется на другом устройстве.");
        }
        if (current == LockState.STALE) {
            Files.deleteIfExists(lockPath);
        }

        OperationLock lock = new OperationLock(deviceName(), operation, Instant.now().toString(), identity.worldId);
        try (Writer writer = Files.newBufferedWriter(lockPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            GSON.toJson(lock, writer);
        }
        return new LockHandle(lockPath);
    }

    private LockState lockState(Path remoteWorldDir) {
        Path lockPath = remoteWorldDir.resolve("locks").resolve("operation.lock");
        if (!Files.isRegularFile(lockPath)) {
            return LockState.NONE;
        }

        Optional<OperationLock> lock = readJson(lockPath, OperationLock.class);
        if (lock.isEmpty() || lock.get().startedAt == null) {
            return LockState.STALE;
        }

        try {
            Instant startedAt = Instant.parse(lock.get().startedAt);
            return Duration.between(startedAt, Instant.now()).compareTo(STALE_LOCK_AFTER) > 0
                    ? LockState.STALE
                    : LockState.ACTIVE;
        } catch (RuntimeException exception) {
            return LockState.STALE;
        }
    }

    private SyncResult waitUntilWorldClosed(
            String folderName,
            String displayName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        long startedAt = System.currentTimeMillis();
        publish(progress, SyncOperation.UPLOAD, displayName, STAGE_WAITING_CLOSE, 0L, 0L, "", 0, 0, 0);

        while (isActualWorldRunning(folderName) || !canUseWorldFolder(worldPath(folderName))) {
            ensureNotCancelled(isCancelled);
            long elapsed = System.currentTimeMillis() - startedAt;
            if (elapsed >= WORLD_CLOSE_TIMEOUT_MILLIS) {
                return SyncResult.error("Не удалось дождаться закрытия мира. Попробуйте выгрузить мир вручную через несколько секунд.");
            }

            publish(progress, SyncOperation.UPLOAD, displayName, STAGE_WAITING_CLOSE, 0L, 0L, "", 0, 0, 0);
            try {
                Thread.sleep(WORLD_CLOSE_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new OperationCancelledException();
            }
        }

        return SyncResult.ok("Мир закрыт.");
    }

    private boolean isActualWorldRunning(String folderName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || (client.world == null && client.getServer() == null)) {
            return false;
        }

        if (client.getServer() == null) {
            return client.world != null;
        }

        try {
            Path openWorldPath = client.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            return openWorldPath.equals(worldPath(folderName).toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private boolean canUseWorldFolder(Path worldPath) {
        Path sessionLock = worldPath.resolve("session.lock");
        if (!Files.exists(sessionLock)) {
            return true;
        }

        try (FileChannel channel = FileChannel.open(sessionLock, StandardOpenOption.WRITE);
             FileLock ignored = channel.tryLock()) {
            return ignored != null;
        } catch (OverlappingFileLockException exception) {
            return false;
        } catch (IOException exception) {
            SimpleWorldSyncClient.LOGGER.warn("Could not test session.lock for {}", worldPath, exception);
            return false;
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                while (input.read(buffer) >= 0) {
                    // DigestInputStream updates the digest while bytes are read.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 недоступен", exception);
        }
    }

    private void restoreIdentityAfterFailedUpload(String folderName, WorldIdentity identity, boolean previousEnabled, long previousVersion) {
        if (identity == null) {
            return;
        }

        try {
            identity.syncEnabled = previousEnabled;
            identity.lastSyncedVersion = previousVersion;
            writeIdentity(folderName, identity);
        } catch (IOException restoreException) {
            SimpleWorldSyncClient.LOGGER.warn("Could not restore world identity after failed upload: {}", folderName, restoreException);
        }
    }

    private void publish(
            Consumer<SyncProgress> progress,
            SyncOperation operation,
            String worldName,
            String stage,
            long processedBytes,
            long totalBytes,
            String currentFile,
            int newFiles,
            int changedFiles,
            int deletedFiles
    ) {
        progress.accept(new SyncProgress(operation, worldName, stage, processedBytes, totalBytes, currentFile, newFiles, changedFiles, deletedFiles));
    }

    private void ensureNotCancelled(BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean()) {
            throw new OperationCancelledException();
        }
    }

    private String displayName(String folderName, String worldName) {
        return worldName == null || worldName.isBlank() ? folderName : worldName;
    }

    private String deviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return System.getProperty("user.name", "unknown-device");
        }
    }

    private String friendlyMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record IncrementalFile(Path path, String relativePath, long size, FileTime lastModifiedTime, String sha256) {
        private WorldManifest.FileRecord toRecord() {
            return new WorldManifest.FileRecord(relativePath, size, sha256, lastModifiedTime.toInstant().toString());
        }
    }

    private record ChangeSet(List<IncrementalFile> newFiles, List<IncrementalFile> changedFiles, List<String> deletedPaths) {
        private boolean isEmpty() {
            return newFiles.isEmpty() && changedFiles.isEmpty() && deletedPaths.isEmpty();
        }

        private List<IncrementalFile> filesToCopy() {
            List<IncrementalFile> files = new ArrayList<>(newFiles);
            files.addAll(changedFiles);
            return files;
        }

        private long uploadBytes() {
            return filesToCopy().stream().mapToLong(IncrementalFile::size).sum();
        }
    }

    private record RestoreChangeSet(
            List<WorldManifest.FileRecord> newFiles,
            List<WorldManifest.FileRecord> changedFiles,
            List<String> deletedPaths
    ) {
        private boolean isEmpty() {
            return newFiles.isEmpty() && changedFiles.isEmpty() && deletedPaths.isEmpty();
        }

        private List<WorldManifest.FileRecord> filesToCopy() {
            List<WorldManifest.FileRecord> files = new ArrayList<>(newFiles);
            files.addAll(changedFiles);
            return files;
        }

        private long restoreBytes() {
            return filesToCopy().stream().mapToLong(record -> Math.max(0L, record.size)).sum();
        }

        private long backupBytes(Map<String, IncrementalFile> localByPath) {
            long total = 0L;
            for (WorldManifest.FileRecord record : changedFiles) {
                IncrementalFile local = localByPath.get(record.path);
                if (local != null) {
                    total += local.size();
                }
            }
            for (String deletedPath : deletedPaths) {
                IncrementalFile local = localByPath.get(deletedPath);
                if (local != null) {
                    total += local.size();
                }
            }
            return total;
        }
    }

    private enum LockState {
        NONE,
        ACTIVE,
        STALE
    }

    private static final class LockHandle implements AutoCloseable {
        private final Path lockPath;

        private LockHandle(Path lockPath) {
            this.lockPath = lockPath;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(lockPath);
            } catch (IOException exception) {
                SimpleWorldSyncClient.LOGGER.warn("Could not remove sync lock {}", lockPath, exception);
            }
        }
    }

    private static final class OperationCancelledException extends RuntimeException {
    }
}
