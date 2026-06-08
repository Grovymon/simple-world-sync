package ru.maronamo.simpleworldsync.config;

import java.util.ArrayList;
import java.util.List;

public final class SimpleWorldSyncConfig {
    public String syncFolder = "";
    public boolean autoUploadOnWorldExit = true;
    public boolean autoCheckOnWorldListOpen = true;
    public boolean createBackupBeforeRestore = true;
    public List<String> excludedPatterns = new ArrayList<>(List.of(
            "session.lock",
            "**/DistantHorizons.sqlite",
            "**/DistantHorizons.sqlite-wal",
            "**/DistantHorizons.sqlite-shm",
            "**/DistantHorizons.sqlite-journal",
            "**/DistantHorizons.sqlite-*"
    ));

    public boolean hasSyncFolder() {
        return syncFolder != null && !syncFolder.isBlank();
    }

    public List<String> effectiveExcludedPatterns() {
        if (excludedPatterns == null || excludedPatterns.isEmpty()) {
            excludedPatterns = new ArrayList<>();
        }

        if (!excludedPatterns.contains("session.lock")) {
            excludedPatterns.add("session.lock");
        }

        return excludedPatterns;
    }
}
