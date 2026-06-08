package ru.maronamo.simpleworldsync.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.config.ConfigManager;
import ru.maronamo.simpleworldsync.config.SimpleWorldSyncConfig;
import ru.maronamo.simpleworldsync.metadata.WorldMetadata;

import java.io.IOException;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class WorldSyncService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

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

        if (Files.isDirectory(savesDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDirectory)) {
                for (Path worldPath : stream) {
                    if (Files.isDirectory(worldPath) && !worldPath.getFileName().toString().startsWith(".")) {
                        String folderName = worldPath.getFileName().toString();
                        worlds.put(folderName, new WorldEntry(folderName, worldPath, true));
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

                    readMetadata(remoteWorldDir.resolve("metadata.json")).ifPresent(metadata -> {
                        if (metadata.worldFolderName == null || metadata.worldFolderName.isBlank()) {
                            return;
                        }

                        worlds.putIfAbsent(
                                metadata.worldFolderName,
                                new WorldEntry(
                                        metadata.worldFolderName,
                                        savesDirectory.resolve(metadata.worldFolderName),
                                        Files.isDirectory(savesDirectory.resolve(metadata.worldFolderName))
                                )
                        );
                    });
                }
            } catch (IOException exception) {
                SimpleWorldSyncClient.LOGGER.error("Failed to list remote worlds in {}", syncFolder, exception);
            }
        });

        return worlds.values().stream()
                .sorted(Comparator.comparing(WorldEntry::folderName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public SyncResult uploadWorld(String folderName) {
        return uploadWorld(folderName, folderName);
    }

    public SyncResult uploadWorld(String folderName, String worldName) {
        Optional<Path> syncFolder = syncFolder();
        if (syncFolder.isEmpty()) {
            return SyncResult.error("Set a sync folder first.");
        }

        Path worldPath = savesDirectory.resolve(folderName).normalize();
        if (!Files.isDirectory(worldPath)) {
            return SyncResult.error("Local world folder was not found: " + folderName);
        }

        if (!canUseWorldFolder(worldPath)) {
            return SyncResult.error("World appears to be open. Close it before uploading.");
        }

        try {
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
                    worldName == null || worldName.isBlank() ? folderName : worldName,
                    folderName,
                    nextVersion,
                    Instant.now().toString(),
                    deviceName(),
                    SimpleWorldSyncClient.MINECRAFT_VERSION,
                    SimpleWorldSyncClient.MOD_VERSION
            );

            Path tempZip = remoteWorldDir.resolve("latest.zip.tmp");
            Path latestZip = remoteWorldDir.resolve("latest.zip");
            Path tempMetadata = remoteWorldDir.resolve("metadata.json.tmp");
            Path metadataPath = remoteWorldDir.resolve("metadata.json");

            SimpleWorldSyncConfig config = configManager.getConfig();
            SimpleWorldSyncClient.LOGGER.info("Sync folder: {}", syncFolder.get());
            SimpleWorldSyncClient.LOGGER.info("Archiving world {} from {}", folderName, worldPath);
            archiveWorld(worldPath, tempZip, new ExclusionMatcher(config.effectiveExcludedPatterns()));
            writeMetadata(tempMetadata, metadata);
            moveReplacing(tempZip, latestZip);
            moveReplacing(tempMetadata, metadataPath);
            writeMetadata(localMetadataPath(folderName), metadata);

            SimpleWorldSyncClient.LOGGER.info("Created archive for {} at {}", folderName, latestZip);
            SimpleWorldSyncClient.LOGGER.info("Upload finished for {} version {}", folderName, nextVersion);
            return SyncResult.ok("Uploaded " + folderName + " as version " + nextVersion + ".");
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to upload world {}", folderName, exception);
            return SyncResult.error("Upload failed: " + friendlyMessage(exception));
        }
    }

    public SyncResult restoreWorld(String folderName) {
        Optional<Path> syncFolder = syncFolder();
        if (syncFolder.isEmpty()) {
            return SyncResult.error("Set a sync folder first.");
        }

        Path worldPath = savesDirectory.resolve(folderName).normalize();
        if (Files.isDirectory(worldPath) && !canUseWorldFolder(worldPath)) {
            return SyncResult.error("World appears to be open. Close it before restoring.");
        }

        String slug = WorldSlug.fromFolderName(folderName);
        Path remoteWorldDir = syncFolder.get().resolve(slug);
        Path latestZip = remoteWorldDir.resolve("latest.zip");
        Path remoteMetadataPath = remoteWorldDir.resolve("metadata.json");

        if (!Files.isRegularFile(latestZip)) {
            return SyncResult.error("Remote archive was not found for " + folderName + ".");
        }

        Optional<WorldMetadata> remoteMetadata = readMetadata(remoteMetadataPath);
        if (remoteMetadata.isEmpty()) {
            return SyncResult.error("Remote metadata is missing. Refusing to restore without confirmation data.");
        }

        try {
            Files.createDirectories(savesDirectory);
            Files.createDirectories(backupsDirectory);

            Path tempExtractDir = savesDirectory.resolve(".simpleworldsync-restore-" + slug + "-" + FILE_TIME.format(Instant.now()));
            deleteIfExists(tempExtractDir);
            Files.createDirectories(tempExtractDir);
            extractZip(latestZip, tempExtractDir);

            Path movedOriginal = null;
            if (Files.isDirectory(worldPath)) {
                if (!configManager.getConfig().createBackupBeforeRestore) {
                    deleteIfExists(tempExtractDir);
                    return SyncResult.error("Backups are disabled. Enable backups before restoring an existing world.");
                }

                Path backupZip = backupsDirectory.resolve(folderName + "-" + FILE_TIME.format(Instant.now()) + ".zip");
                SimpleWorldSyncClient.LOGGER.info("Creating local backup for {} at {}", folderName, backupZip);
                archiveWorld(worldPath, backupZip, new ExclusionMatcher(configManager.getConfig().effectiveExcludedPatterns()));

                movedOriginal = backupsDirectory.resolve(folderName + "-" + FILE_TIME.format(Instant.now()) + "-folder");
                moveReplacing(worldPath, movedOriginal);
            }

            try {
                moveReplacing(tempExtractDir, worldPath);
            } catch (IOException moveException) {
                if (movedOriginal != null && Files.isDirectory(movedOriginal) && !Files.exists(worldPath)) {
                    moveReplacing(movedOriginal, worldPath);
                }
                throw moveException;
            }

            writeMetadata(localMetadataPath(folderName), remoteMetadata.get());
            SimpleWorldSyncClient.LOGGER.info("Restore finished for {} version {}", folderName, remoteMetadata.get().version);
            return SyncResult.ok("Restored " + folderName + " from version " + remoteMetadata.get().version + ".");
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to restore world {}", folderName, exception);
            return SyncResult.error("Restore failed: " + friendlyMessage(exception));
        }
    }

    public VersionComparison compareWorld(String folderName) {
        Optional<Path> syncFolder = syncFolder();
        if (syncFolder.isEmpty()) {
            return new VersionComparison(folderName, Optional.empty(), Optional.empty(), VersionState.NOT_CONFIGURED, "Set a sync folder first.");
        }

        Optional<WorldMetadata> localMetadata = readLocalMetadata(folderName);
        Optional<WorldMetadata> remoteMetadata = readRemoteMetadata(folderName);

        if (remoteMetadata.isEmpty() && localMetadata.isEmpty()) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.UNKNOWN, "No local or remote version metadata yet.");
        }

        if (remoteMetadata.isEmpty()) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.NO_REMOTE, "No remote version found. Upload this world first.");
        }

        if (localMetadata.isEmpty()) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.REMOTE_NEWER, "Local version is unknown. Remote version is " + remoteMetadata.get().version + ".");
        }

        long localVersion = localMetadata.get().version;
        long remoteVersion = remoteMetadata.get().version;

        if (remoteVersion > localVersion) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.REMOTE_NEWER, "Remote version " + remoteVersion + " is newer than local version " + localVersion + ".");
        }

        if (localVersion > remoteVersion) {
            return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.LOCAL_NEWER, "Local version " + localVersion + " is newer than remote version " + remoteVersion + ".");
        }

        return new VersionComparison(folderName, localMetadata, remoteMetadata, VersionState.SAME, "Local and remote versions are both " + localVersion + ".");
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

    private void writeMetadata(Path metadataPath, WorldMetadata metadata) throws IOException {
        Files.createDirectories(metadataPath.getParent());
        try (Writer writer = Files.newBufferedWriter(metadataPath, StandardCharsets.UTF_8)) {
            GSON.toJson(metadata, writer);
        }
    }

    private void archiveWorld(Path worldPath, Path zipPath, ExclusionMatcher exclusions) throws IOException {
        Files.createDirectories(zipPath.getParent());
        List<String> excluded = new ArrayList<>();

        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
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
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = worldPath.relativize(file);
                    if (exclusions.isExcluded(relative)) {
                        excluded.add(relative.toString());
                        return FileVisitResult.CONTINUE;
                    }

                    ZipEntry entry = new ZipEntry(relative.toString().replace('\\', '/'));
                    entry.setLastModifiedTime(attrs.lastModifiedTime());
                    output.putNextEntry(entry);
                    Files.copy(file, output);
                    output.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        for (String file : excluded) {
            SimpleWorldSyncClient.LOGGER.info("Excluded from archive: {}", file);
        }
    }

    private void extractZip(Path zipPath, Path targetDirectory) throws IOException {
        Path normalizedTarget = targetDirectory.toAbsolutePath().normalize();

        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path outputPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(normalizedTarget)) {
                    throw new IOException("Archive contains an unsafe path: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    Files.copy(input, outputPath, StandardCopyOption.REPLACE_EXISTING);
                }

                input.closeEntry();
            }
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
        if (!Files.exists(path)) {
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
}
