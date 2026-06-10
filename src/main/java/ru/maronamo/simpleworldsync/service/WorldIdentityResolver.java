package ru.maronamo.simpleworldsync.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.metadata.WorldIdentity;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class WorldIdentityResolver {
    private static final Gson GSON = new GsonBuilder().create();

    private WorldIdentityResolver() {
    }

    public static WorldSyncIdentity resolve(Path savesDirectory, String folderName, String displayName) {
        return resolve(savesDirectory, savesDirectory.resolve(folderName), displayName);
    }

    public static WorldSyncIdentity resolve(Path savesDirectory, Path worldPath, String displayName) {
        Path rootPath = worldRootPath(savesDirectory, worldPath);
        String folderName = worldFolderName(savesDirectory, rootPath);
        Optional<WorldIdentity> identity = readIdentity(rootPath);

        return new WorldSyncIdentity(
                rootPath,
                folderName,
                displayName == null || displayName.isBlank() ? folderName : displayName,
                identity.map(value -> value.worldId).orElse(""),
                identity.map(value -> value.syncEnabled).orElse(false),
                identity.map(value -> value.remoteSlug).orElse(""),
                identity.map(value -> value.lastSyncedVersion).orElse(0L),
                identity.isPresent()
        );
    }

    public static Optional<WorldIdentity> readIdentity(Path worldPath) {
        Path identityPath = worldPath.resolve(".simpleworldsync").resolve("world.json");
        if (!Files.isRegularFile(identityPath)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(identityPath, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, WorldIdentity.class));
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.warn("Failed to read world identity from {}", identityPath, exception);
            return Optional.empty();
        }
    }

    private static Path worldRootPath(Path savesDirectory, Path worldPath) {
        Path normalizedWorldPath = worldPath.toAbsolutePath().normalize();
        Path normalizedSavesDirectory = savesDirectory.toAbsolutePath().normalize();
        if (!normalizedWorldPath.startsWith(normalizedSavesDirectory)) {
            return normalizedWorldPath;
        }

        Path relativePath = normalizedSavesDirectory.relativize(normalizedWorldPath);
        if (relativePath.getNameCount() == 0) {
            return normalizedWorldPath;
        }

        return normalizedSavesDirectory.resolve(relativePath.getName(0)).normalize();
    }

    private static String worldFolderName(Path savesDirectory, Path worldPath) {
        Path normalizedWorldPath = worldPath.toAbsolutePath().normalize();
        Path normalizedSavesDirectory = savesDirectory.toAbsolutePath().normalize();
        if (normalizedWorldPath.startsWith(normalizedSavesDirectory)) {
            Path relativePath = normalizedSavesDirectory.relativize(normalizedWorldPath);
            if (relativePath.getNameCount() > 0) {
                return relativePath.getName(0).toString();
            }
        }

        Path fileName = normalizedWorldPath.getFileName();
        return fileName == null ? "-" : fileName.toString();
    }
}
