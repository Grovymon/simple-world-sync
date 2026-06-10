package ru.maronamo.simpleworldsync.config;

import java.util.ArrayList;
import java.util.List;

public final class SimpleWorldSyncConfig {
    private static final List<String> REQUIRED_EXCLUDED_PATTERNS = List.of(
            "session.lock",
            ".git",
            ".git/**",
            "**/.git",
            "**/.git/**",
            "**/DistantHorizons.sqlite",
            "**/DistantHorizons.sqlite-wal",
            "**/DistantHorizons.sqlite-shm",
            "**/DistantHorizons.sqlite-journal",
            "**/DistantHorizons.sqlite-*",
            "latest.zip",
            "latest.zip.tmp",
            "metadata.json",
            "manifest.json",
            "simpleworldsync-state.json"
    );

    public String syncFolder = "";
    public boolean autoUploadOnWorldExit = true;
    public boolean autoCheckOnWorldListOpen = true;
    public boolean createBackupBeforeRestore = true;
    public List<String> excludedPatterns = new ArrayList<>(REQUIRED_EXCLUDED_PATTERNS);

    public boolean hasSyncFolder() {
        return syncFolder != null && !syncFolder.isBlank();
    }

    public List<String> effectiveExcludedPatterns() {
        if (excludedPatterns == null || excludedPatterns.isEmpty()) {
            excludedPatterns = new ArrayList<>();
        }

        for (String requiredPattern : REQUIRED_EXCLUDED_PATTERNS) {
            if (!excludedPatterns.contains(requiredPattern)) {
                excludedPatterns.add(requiredPattern);
            }
        }

        return excludedPatterns;
    }
}
