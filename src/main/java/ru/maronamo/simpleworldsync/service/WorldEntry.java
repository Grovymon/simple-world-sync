package ru.maronamo.simpleworldsync.service;

import java.nio.file.Path;

public record WorldEntry(
        String folderName,
        Path path,
        boolean localExists,
        String displayName,
        boolean remoteOnly,
        String remoteSlug,
        String worldId
) {
    public WorldEntry(String folderName, Path path, boolean localExists) {
        this(folderName, path, localExists, folderName, !localExists, "", "");
    }

    public WorldEntry {
        folderName = folderName == null || folderName.isBlank() ? "-" : folderName;
        displayName = displayName == null || displayName.isBlank() ? folderName : displayName;
        remoteSlug = remoteSlug == null ? "" : remoteSlug;
        worldId = worldId == null ? "" : worldId;
    }
}
