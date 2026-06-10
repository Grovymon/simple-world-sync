package ru.maronamo.simpleworldsync.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.config.ConfigManager;
import ru.maronamo.simpleworldsync.config.SimpleWorldSyncConfig;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class WorldSyncService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final Consumer<SyncProgress> NO_PROGRESS = progress -> {
    };
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private static final String STAGE_SCANNING = "Подсчёт файлов мира...";
    private static final String STAGE_ARCHIVING = "Создание архива...";
    private static final String STAGE_BACKUP = "Создание резервной копии...";
    private static final String STAGE_EXTRACTING = "Распаковка файлов...";
    private static final String STAGE_METADATA = "Запись metadata.json...";
    private static final String STAGE_UPLOAD_DONE = "Мир успешно выгружен";
    private static final String STAGE_RESTORE_DONE = "Мир успешно восстановлен";
    private static final String STAGE_CANCELLED = "Операция отменена";

    private final ConfigManager configManager;
    private final Path runDirectory;
    private final Path savesDirectory;
    private final Path backupsDirectory;

    public WorldSyncService(ConfigManager configManager, Path runDirectory) {
        this.configManager = configManager;
        this.runDirectory = runDirectory;
        this.savesDirectory = runDirectory.resolve("saves");
        this.backupsDirectory = runDirectory.resolve("simpleworldsync-backups");
    }

    public List<WorldEntry> listWorlds() {
        Map<String, WorldEntry> worlds = new LinkedHashMap<>();
        Map<String, String> localFolderByWorldId = new LinkedHashMap<>();

        if (Files.isDirectory(savesDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDirectory)) {
                for (Path worldPath : stream) {
                    if (Files.isDirectory(worldPath) && !worldPath.getFileName().toString().startsWith(".")) {
                        String folderName = worldPath.getFileName().toString();
                        String displayName = readLevelName(worldPath).orElse(folderName);
                        WorldSyncIdentity identity = WorldIdentityResolver.resolve(savesDirectory, worldPath, displayName);
                        if (!identity.worldId().isBlank()) {
                            localFolderByWorldId.put(identity.worldId(), folderName);
                        }

                        worlds.put(folderName, new WorldEntry(
                                folderName,
                                worldPath,
                                true,
                                identity.worldDisplayName(),
                                false,
                                identity.remoteSlug(),
                                identity.worldId()
                        ));
                    }
                }
            } catch (IOException exception) {
                SimpleWorldSyncClient.LOGGER.error("Failed to list local worlds in {}", savesDirectory, exception);
            }
        }

        syncFolder().ifPresent(syncFolder -> {
            if (!Files.isDirectory(syncFolder)) {
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(syncFolder)) {
                for (Path remoteWorldDir : stream) {
                    if (!Files.isDirectory(remoteWorldDir)) {
                        continue;
                    }

                    Optional<WorldMetadata> metadata = readMetadata(remoteWorldDir.resolve("metadata.json"));
                    Optional<WorldManifest> manifest = readManifest(remoteWorldDir.resolve("manifest.json"));

                    if (metadata.isEmpty() && manifest.isEmpty()) {
                        continue;
                    }

                    String worldId = manifest.map(value -> value.worldId).orElse("");
                    if (!worldId.isBlank() && localFolderByWorldId.containsKey(worldId)) {
                        continue;
                    }

                    String folderName = metadata.map(value -> value.worldFolderName)
                            .filter(value -> value != null && !value.isBlank())
                            .orElseGet(() -> manifest.map(value -> value.worldFolderName)
                                    .filter(value -> value != null && !value.isBlank())
                                    .orElse(remoteWorldDir.getFileName().toString()));
                    String worldName = metadata.map(value -> value.worldName)
                            .filter(value -> value != null && !value.isBlank())
                            .orElseGet(() -> manifest.map(value -> value.worldName)
                                    .filter(value -> value != null && !value.isBlank())
                                    .orElse(folderName));
                    Path localPath = savesDirectory.resolve(folderName);
                    boolean localExists = Files.isDirectory(localPath);

                    if (localExists) {
                        worlds.putIfAbsent(folderName, new WorldEntry(
                                folderName,
                                localPath,
                                true,
                                readLevelName(localPath).orElse(worldName),
                                false,
                                remoteWorldDir.getFileName().toString(),
                                worldId
                        ));
                    } else {
                        worlds.putIfAbsent(folderName, new WorldEntry(
                                folderName,
                                localPath,
                                false,
                                "☁ " + worldName,
                                true,
                                remoteWorldDir.getFileName().toString(),
                                worldId
                        ));
                    }
                }
            } catch (IOException exception) {
                SimpleWorldSyncClient.LOGGER.error("Failed to list remote worlds in {}", syncFolder, exception);
            }
        });

        return worlds.values().stream()
                .sorted(Comparator.comparing(WorldEntry::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public SyncResult uploadWorld(String folderName) {
        return uploadWorld(folderName, folderName);
    }

    public SyncResult uploadWorld(String folderName, String worldName) {
        return uploadWorld(folderName, worldName, NO_PROGRESS, NEVER_CANCELLED);
    }

    public SyncResult uploadWorld(String folderName, Consumer<SyncProgress> progress, BooleanSupplier isCancelled) {
        return uploadWorld(folderName, folderName, progress, isCancelled);
    }

    public SyncResult uploadWorld(
            String folderName,
            String worldName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled
    ) {
        Consumer<SyncProgress> progressSink = progress == null ? NO_PROGRESS : progress;
        BooleanSupplier cancellation = isCancelled == null ? NEVER_CANCELLED : isCancelled;
        String displayName = worldName == null || worldName.isBlank() ? folderName : worldName;
        ProgressLog progressLog = new ProgressLog("upload", folderName);

        Optional<Path> syncFolder = syncFolder();
        if (syncFolder.isEmpty()) {
            return SyncResult.error("Папка синхронизации не выбрана.");
        }

        Path worldPath = savesDirectory.resolve(folderName).normalize();
        if (!Files.isDirectory(worldPath)) {
            return SyncResult.error("Папка локального мира не найдена: " + folderName);
        }

        if (!canUseWorldFolder(worldPath)) {
            return SyncResult.error("Этот мир сейчас открыт. Закройте его перед выгрузкой.");
        }

        Path tempZip = null;
        Path tempMetadata = null;

        try {
            ensureNotCancelled(cancellation);
            Files.createDirectories(syncFolder.get());

            String slug = WorldSlug.fromFolderName(folderName);
            Path remoteWorldDir = syncFolder.get().resolve(slug);
            Files.createDirectories(remoteWorldDir);

            Optional<WorldMetadata> remoteMetadata = readRemoteMetadata(folderName);
            Optional<WorldMetadata> localMetadata = readLocalMetadata(folderName);
            long nextVersion = Math.max(
                    remoteMetadata.map(metadata -> metadata.version).orElse(0L),
                    localMetadata.map(metadata -> metadata.version).orElse(0L)
            ) + 1L;

            WorldMetadata metadata = new WorldMetadata(
                    displayName,
                    folderName,
                    nextVersion,
                    Instant.now().toString(),
                    deviceName(),
                    SimpleWorldSyncClient.MINECRAFT_VERSION,
                    SimpleWorldSyncClient.MOD_VERSION
            );

            tempZip = remoteWorldDir.resolve("latest.zip.tmp");
            Path latestZip = remoteWorldDir.resolve("latest.zip");
            tempMetadata = remoteWorldDir.resolve("metadata.json.tmp");
            Path metadataPath = remoteWorldDir.resolve("metadata.json");

            SimpleWorldSyncConfig config = configManager.getConfig();
            ExclusionMatcher exclusions = new ExclusionMatcher(config.effectiveExcludedPatterns());

            SimpleWorldSyncClient.LOGGER.info("Starting upload: world={}, syncFolder={}", folderName, syncFolder.get());
            archiveWorld(
                    worldPath,
                    tempZip,
                    exclusions,
                    SyncOperation.UPLOAD,
                    displayName,
                    STAGE_SCANNING,
                    STAGE_ARCHIVING,
                    progressSink,
                    cancellation,
                    progressLog
            );

            ensureNotCancelled(cancellation);
            publish(progressSink, progressLog, SyncOperation.UPLOAD, displayName, STAGE_METADATA, 0L, 0L, "metadata.json");
            writeMetadata(tempMetadata, metadata);

            ensureNotCancelled(cancellation);
            moveReplacing(tempZip, latestZip);
            moveReplacing(tempMetadata, metadataPath);
            writeMetadata(localMetadataPath(folderName), metadata);

            publish(progressSink, progressLog, SyncOperation.UPLOAD, displayName, STAGE_UPLOAD_DONE, 1L, 1L, "");
            SimpleWorldSyncClient.LOGGER.info("Upload completed: world={}, version={}, archive={}", folderName, nextVersion, latestZip);
            return SyncResult.ok("Мир успешно выгружен (версия " + nextVersion + ").");
        } catch (OperationCancelledException exception) {
            cleanupFile(tempZip);
            cleanupFile(tempMetadata);
            publish(progressSink, progressLog, SyncOperation.UPLOAD, displayName, STAGE_CANCELLED, 0L, 0L, "");
            SimpleWorldSyncClient.LOGGER.info("Upload cancelled: world={}", folderName);
            return SyncResult.error("Операция отменена.");
        } catch (IOException | RuntimeException exception) {
            cleanupFile(tempZip);
            cleanupFile(tempMetadata);
            SimpleWorldSyncClient.LOGGER.error("Failed to upload world {}", folderName, exception);
            return SyncResult.error("Ошибка выгрузки: " + friendlyMessage(exception));
        }
    }

    public SyncResult restoreWorld(String folderName) {
        return restoreWorld(folderName, NO_PROGRESS, NEVER_CANCELLED);
    }

    public SyncResult restoreWorld(String folderName, Consumer<SyncProgress> progress, BooleanSupplier isCancelled) {
        Consumer<SyncProgress> progressSink = progress == null ? NO_PROGRESS : progress;
        BooleanSupplier cancellation = isCancelled == null ? NEVER_CANCELLED : isCancelled;
        ProgressLog progressLog = new ProgressLog("restore", folderName);

        Optional<Path> syncFolder = syncFolder();
        if (syncFolder.isEmpty()) {
            return SyncResult.error("Папка синхронизации не выбрана.");
        }

        Path worldPath = savesDirectory.resolve(folderName).normalize();
        if (Files.isDirectory(worldPath) && !canUseWorldFolder(worldPath)) {
            return SyncResult.error("Этот мир сейчас открыт. Закройте его перед восстановлением.");
        }

        String slug = WorldSlug.fromFolderName(folderName);
        Path remoteWorldDir = syncFolder.get().resolve(slug);
        Path latestZip = remoteWorldDir.resolve("latest.zip");
        Path remoteMetadataPath = remoteWorldDir.resolve("metadata.json");

        if (!Files.isRegularFile(latestZip)) {
            return SyncResult.error("Удалённый архив для мира не найден: " + folderName);
        }

        Optional<WorldMetadata> remoteMetadata = readMetadata(remoteMetadataPath);
        if (remoteMetadata.isEmpty()) {
            return SyncResult.error("metadata.json не найден. Восстановление остановлено.");
        }

        String displayName = remoteMetadata.get().worldName == null || remoteMetadata.get().worldName.isBlank()
                ? folderName
                : remoteMetadata.get().worldName;
        Path tempExtractDir = savesDirectory.resolve(".simpleworldsync-restore-" + slug + "-" + FILE_TIME.format(Instant.now()));
        Path movedOriginal = null;
        Path backupZip = null;

        try {
            ensureNotCancelled(cancellation);
            Files.createDirectories(savesDirectory);
            Files.createDirectories(backupsDirectory);

            if (Files.isDirectory(worldPath)) {
                if (!configManager.getConfig().createBackupBeforeRestore) {
                    return SyncResult.error("Резервные копии отключены. Включите backup перед восстановлением существующего мира.");
                }

                backupZip = backupsDirectory.resolve(folderName + "-" + FILE_TIME.format(Instant.now()) + ".zip");
                SimpleWorldSyncClient.LOGGER.info("Creating backup before restore: world={}, backup={}", folderName, backupZip);
                archiveWorld(
                        worldPath,
                        backupZip,
                        new ExclusionMatcher(configManager.getConfig().effectiveExcludedPatterns()),
                        SyncOperation.RESTORE,
                        displayName,
                        STAGE_BACKUP,
                        STAGE_BACKUP,
                        progressSink,
                        cancellation,
                        progressLog
                );
            } else {
                publish(progressSink, progressLog, SyncOperation.RESTORE, displayName, STAGE_BACKUP, 0L, 0L, "");
            }

            ensureNotCancelled(cancellation);
            deleteIfExists(tempExtractDir);
            Files.createDirectories(tempExtractDir);
            SimpleWorldSyncClient.LOGGER.info("Extracting remote archive for restore: world={}, archive={}", folderName, latestZip);
            extractZip(latestZip, tempExtractDir, SyncOperation.RESTORE, displayName, progressSink, cancellation, progressLog);

            ensureNotCancelled(cancellation);
            try {
                if (Files.isDirectory(worldPath)) {
                    movedOriginal = backupsDirectory.resolve(folderName + "-" + FILE_TIME.format(Instant.now()) + "-folder");
                    moveReplacing(worldPath, movedOriginal);
                }

                moveReplacing(tempExtractDir, worldPath);
            } catch (IOException moveException) {
                restoreMovedOriginal(worldPath, movedOriginal);
                throw moveException;
            }

            writeMetadata(localMetadataPath(folderName), remoteMetadata.get());
            publish(progressSink, progressLog, SyncOperation.RESTORE, displayName, STAGE_RESTORE_DONE, 1L, 1L, "");
            SimpleWorldSyncClient.LOGGER.info(
                    "Restore completed: world={}, version={}, backup={}",
                    folderName,
                    remoteMetadata.get().version,
                    backupZip
            );
            return SyncResult.ok("Мир успешно восстановлен (версия " + remoteMetadata.get().version + ").");
        } catch (OperationCancelledException exception) {
            cleanupDirectory(tempExtractDir);
            restoreMovedOriginal(worldPath, movedOriginal);
            publish(progressSink, progressLog, SyncOperation.RESTORE, displayName, STAGE_CANCELLED, 0L, 0L, "");
            SimpleWorldSyncClient.LOGGER.info("Restore cancelled: world={}", folderName);
            return SyncResult.error("Операция отменена.");
        } catch (IOException | RuntimeException exception) {
            cleanupDirectory(tempExtractDir);
            restoreMovedOriginal(worldPath, movedOriginal);
            SimpleWorldSyncClient.LOGGER.error("Failed to restore world {}", folderName, exception);
            return SyncResult.error("Ошибка восстановления: " + friendlyMessage(exception));
        }
    }

    public VersionComparison compareWorld(String folderName) {
        Optional<Path> syncFolder = syncFolder();
        if (syncFolder.isEmpty()) {
            return new VersionComparison(folderName, Optional.empty(), Optional.empty(), VersionState.NOT_CONFIGURED, "Папка синхронизации не выбрана.");
        }

        Optional<WorldMetadata> localMetadata = readLocalMetadata(folderName);
        Optional<WorldMetadata> remoteMetadata = readRemoteMetadata(folderName);

        if (remoteMetadata.isEmpty() && localMetadata.isEmpty()) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.UNKNOWN, "Версия мира пока неизвестна.");
        }

        if (remoteMetadata.isEmpty()) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.NO_REMOTE, "Удалённая версия не найдена. Сначала выгрузите мир.");
        }

        if (localMetadata.isEmpty()) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.REMOTE_NEWER, "Локальная версия неизвестна. Удалённая версия: " + remoteMetadata.get().version + ".");
        }

        long localVersion = localMetadata.get().version;
        long remoteVersion = remoteMetadata.get().version;

        if (remoteVersion > localVersion) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.REMOTE_NEWER, "Удалённая версия " + remoteVersion + " новее локальной " + localVersion + ".");
        }

        if (localVersion > remoteVersion) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.LOCAL_NEWER, "Локальная версия " + localVersion + " новее удалённой " + remoteVersion + ".");
        }

        return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.SAME, "Локальная и удалённая версии совпадают: " + localVersion + ".");
    }

    public VersionSummary checkAllWorlds() {
        int remoteNewer = 0;
        int localNewer = 0;
        int same = 0;
        int unknown = 0;
        List<WorldEntry> worlds = listWorlds();

        for (WorldEntry world : worlds) {
            VersionComparison comparison = compareWorld(world.folderName());
            switch (comparison.state()) {
                case REMOTE_NEWER -> remoteNewer++;
                case LOCAL_NEWER -> localNewer++;
                case SAME -> same++;
                default -> unknown++;
            }
        }

        return new VersionSummary(worlds.size(), remoteNewer, localNewer, same, unknown);
    }

    public Optional<Path> syncFolder() {
        SimpleWorldSyncConfig config = configManager.getConfig();
        if (!config.hasSyncFolder()) {
            return Optional.empty();
        }

        return Optional.of(Path.of(config.syncFolder).toAbsolutePath().normalize());
    }

    private Optional<WorldMetadata> readRemoteMetadata(String folderName) {
        return syncFolder()
                .map(folder -> folder.resolve(WorldSlug.fromFolderName(folderName)).resolve("metadata.json"))
                .flatMap(this::readMetadata);
    }

    private Optional<WorldMetadata> readLocalMetadata(String folderName) {
        return readMetadata(localMetadataPath(folderName));
    }

    private Path localMetadataPath(String folderName) {
        return configManager.getLocalMetadataDir().resolve(WorldSlug.fromFolderName(folderName) + ".json");
    }

    private Optional<WorldMetadata> readMetadata(Path metadataPath) {
        if (!Files.isRegularFile(metadataPath)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, WorldMetadata.class));
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to read metadata from {}", metadataPath, exception);
            return Optional.empty();
        }
    }

    private Optional<WorldManifest> readManifest(Path manifestPath) {
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, WorldManifest.class));
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to read manifest from {}", manifestPath, exception);
            return Optional.empty();
        }
    }

    private Optional<String> readLevelName(Path worldPath) {
        Path levelDat = worldPath.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return Optional.empty();
        }

        try {
            NbtCompound root = NbtIo.readCompressed(levelDat, NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound data = root.getCompound("Data");
            String levelName = data.getString("LevelName");
            return levelName == null || levelName.isBlank() ? Optional.empty() : Optional.of(levelName);
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.warn("Failed to read world name from {}", levelDat, exception);
            return Optional.empty();
        }
    }

    private void writeMetadata(Path metadataPath, WorldMetadata metadata) throws IOException {
        Files.createDirectories(metadataPath.getParent());
        try (Writer writer = Files.newBufferedWriter(metadataPath, StandardCharsets.UTF_8)) {
            GSON.toJson(metadata, writer);
        }
    }

    private void archiveWorld(
            Path worldPath,
            Path zipPath,
            ExclusionMatcher exclusions,
            SyncOperation operation,
            String worldName,
            String scanStage,
            String archiveStage,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled,
            ProgressLog progressLog
    ) throws IOException {
        Files.createDirectories(zipPath.getParent());
        List<FileSnapshot> files = scanArchiveFiles(worldPath, exclusions, operation, worldName, scanStage, progress, isCancelled, progressLog);
        long totalBytes = files.stream().mapToLong(FileSnapshot::size).sum();
        long[] processedBytes = {0L};

        SimpleWorldSyncClient.LOGGER.info(
                "File scan completed: operation={}, world={}, files={}, totalBytes={}",
                operation,
                worldPath.getFileName(),
                files.size(),
                totalBytes
        );
        publish(progress, progressLog, operation, worldName, archiveStage, 0L, totalBytes, "");

        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            for (FileSnapshot file : files) {
                ensureNotCancelled(isCancelled);
                String currentFile = file.relativePath().toString().replace('\\', '/');
                publish(progress, progressLog, operation, worldName, archiveStage, processedBytes[0], totalBytes, currentFile);

                ZipEntry entry = new ZipEntry(currentFile);
                entry.setLastModifiedTime(file.lastModifiedTime());
                output.putNextEntry(entry);

                try (InputStream input = Files.newInputStream(file.path())) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        ensureNotCancelled(isCancelled);
                        output.write(buffer, 0, read);
                        processedBytes[0] += read;
                        publish(progress, progressLog, operation, worldName, archiveStage, processedBytes[0], totalBytes, currentFile);
                    }
                }

                output.closeEntry();
            }
        }

        publish(progress, progressLog, operation, worldName, archiveStage, totalBytes, totalBytes, "");
    }

    private List<FileSnapshot> scanArchiveFiles(
            Path worldPath,
            ExclusionMatcher exclusions,
            SyncOperation operation,
            String worldName,
            String scanStage,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled,
            ProgressLog progressLog
    ) throws IOException {
        List<FileSnapshot> files = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        long[] scannedBytes = {0L};

        publish(progress, progressLog, operation, worldName, scanStage, 0L, 0L, "");
        Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                ensureNotCancelled(isCancelled);
                if (!dir.equals(worldPath)) {
                    Path relative = worldPath.relativize(dir);
                    if (exclusions.isExcluded(relative)) {
                        excluded.add(relative.toString());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                ensureNotCancelled(isCancelled);
                Path relative = worldPath.relativize(file);
                if (exclusions.isExcluded(relative)) {
                    excluded.add(relative.toString());
                    return FileVisitResult.CONTINUE;
                }

                files.add(new FileSnapshot(file, relative, attrs.size(), attrs.lastModifiedTime()));
                scannedBytes[0] += Math.max(0L, attrs.size());
                publish(progress, progressLog, operation, worldName, scanStage, scannedBytes[0], 0L, relative.toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });

        for (String file : excluded) {
            SimpleWorldSyncClient.LOGGER.info("Excluded from archive: {}", file);
        }

        return files;
    }

    private void extractZip(
            Path zipPath,
            Path targetDirectory,
            SyncOperation operation,
            String worldName,
            Consumer<SyncProgress> progress,
            BooleanSupplier isCancelled,
            ProgressLog progressLog
    ) throws IOException {
        Path normalizedTarget = targetDirectory.toAbsolutePath().normalize();
        long totalBytes = zipUncompressedSize(zipPath);
        long[] processedBytes = {0L};
        byte[] buffer = new byte[COPY_BUFFER_SIZE];

        publish(progress, progressLog, operation, worldName, STAGE_EXTRACTING, 0L, totalBytes, "");
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ensureNotCancelled(isCancelled);
                ZipEntry entry = entries.nextElement();
                Path outputPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(normalizedTarget)) {
                    throw new IOException("Архив содержит небезопасный путь: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }

                Files.createDirectories(outputPath.getParent());
                String currentFile = entry.getName();
                publish(progress, progressLog, operation, worldName, STAGE_EXTRACTING, processedBytes[0], totalBytes, currentFile);

                try (InputStream input = zipFile.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        ensureNotCancelled(isCancelled);
                        output.write(buffer, 0, read);
                        processedBytes[0] += read;
                        publish(progress, progressLog, operation, worldName, STAGE_EXTRACTING, processedBytes[0], totalBytes, currentFile);
                    }
                }
            }
        }

        publish(progress, progressLog, operation, worldName, STAGE_EXTRACTING, totalBytes, totalBytes, "");
    }

    private long zipUncompressedSize(Path zipPath) throws IOException {
        long totalBytes = 0L;
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getSize() > 0L) {
                    totalBytes += entry.getSize();
                }
            }
        }

        return totalBytes;
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
            return true;
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteIfExists(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanupFile(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupException) {
            SimpleWorldSyncClient.LOGGER.warn("Could not delete temporary file {}", path, cleanupException);
        }
    }

    private void cleanupDirectory(Path path) {
        try {
            deleteIfExists(path);
        } catch (IOException cleanupException) {
            SimpleWorldSyncClient.LOGGER.warn("Could not delete temporary directory {}", path, cleanupException);
        }
    }

    private void restoreMovedOriginal(Path worldPath, Path movedOriginal) {
        if (movedOriginal == null || !Files.isDirectory(movedOriginal) || Files.exists(worldPath)) {
            return;
        }

        try {
            moveReplacing(movedOriginal, worldPath);
            SimpleWorldSyncClient.LOGGER.info("Restored original world folder after failed restore: {}", worldPath);
        } catch (IOException rollbackException) {
            SimpleWorldSyncClient.LOGGER.error(
                    "Could not restore original world folder from {} to {}",
                    movedOriginal,
                    worldPath,
                    rollbackException
            );
        }
    }

    private void publish(
            Consumer<SyncProgress> progress,
            ProgressLog progressLog,
            SyncOperation operation,
            String worldName,
            String stage,
            long processedBytes,
            long totalBytes,
            String currentFile
    ) {
        SyncProgress snapshot = new SyncProgress(operation, worldName, stage, processedBytes, totalBytes, currentFile);
        progress.accept(snapshot);
        progressLog.maybeLog(snapshot);
    }

    private void ensureNotCancelled(BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean()) {
            throw new OperationCancelledException();
        }
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

    private record FileSnapshot(Path path, Path relativePath, long size, FileTime lastModifiedTime) {
    }

    private static final class OperationCancelledException extends RuntimeException {
    }

    private static final class ProgressLog {
        private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;

        private final String operation;
        private final String folderName;
        private final long startNanos = System.nanoTime();
        private long lastLogNanos;

        private ProgressLog(String operation, String folderName) {
            this.operation = operation;
            this.folderName = folderName;
        }

        private void maybeLog(SyncProgress progress) {
            long now = System.nanoTime();
            boolean completed = progress.totalBytes() > 0L && progress.processedBytes() >= progress.totalBytes();
            if (!completed && now - lastLogNanos < LOG_INTERVAL_NANOS) {
                return;
            }

            lastLogNanos = now;
            double elapsedSeconds = Math.max(0.001D, (now - startNanos) / 1_000_000_000.0D);
            long bytesPerSecond = Math.round(progress.processedBytes() / elapsedSeconds);
            SimpleWorldSyncClient.LOGGER.info(
                    "{} progress: world={}, stage={}, processedBytes={}, totalBytes={}, percent={}%, speedBytesPerSecond={}, currentFile={}",
                    operation,
                    folderName,
                    progress.stage(),
                    progress.processedBytes(),
                    progress.totalBytes(),
                    progress.percent(),
                    bytesPerSecond,
                    progress.currentFile()
            );
        }
    }
}
