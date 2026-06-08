package ru.maronamo.simpleworldsync.service;

import java.nio.file.Path;

public record WorldEntry(String folderName, Path path, boolean localExists) {
    public String displayName() {
        return localExists ? folderName : folderName + " (remote only)";
    }
}
