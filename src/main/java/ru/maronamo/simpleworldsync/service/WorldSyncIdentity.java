package ru.maronamo.simpleworldsync.service;

import java.nio.file.Path;

public record WorldSyncIdentity(
        Path worldPath,
        String worldFolderName,
        String worldDisplayName,
        String worldId,
        boolean syncEnabled,
        String remoteSlug,
        long lastSyncedVersion,
        boolean identityPresent
) {
    public WorldSyncIdentity {
        worldFolderName = worldFolderName == null || worldFolderName.isBlank() ? "-" : worldFolderName;
        worldDisplayName = worldDisplayName == null || worldDisplayName.isBlank() ? worldFolderName : worldDisplayName;
        worldId = worldId == null ? "" : worldId;
        remoteSlug = remoteSlug == null ? "" : remoteSlug;
    }
}
