package ru.maronamo.simpleworldsync.service;

public record SyncProgress(
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
    public SyncProgress(
            SyncOperation operation,
            String worldName,
            String stage,
            long processedBytes,
            long totalBytes,
            String currentFile
    ) {
        this(operation, worldName, stage, processedBytes, totalBytes, currentFile, 0, 0, 0);
    }

    public SyncProgress {
        worldName = worldName == null ? "" : worldName;
        stage = stage == null ? "" : stage;
        currentFile = currentFile == null ? "" : currentFile;
        processedBytes = Math.max(0L, processedBytes);
        totalBytes = Math.max(0L, totalBytes);
        newFiles = Math.max(0, newFiles);
        changedFiles = Math.max(0, changedFiles);
        deletedFiles = Math.max(0, deletedFiles);
        if (totalBytes > 0L) {
            processedBytes = Math.min(processedBytes, totalBytes);
        }
    }

    public double fraction() {
        if (totalBytes <= 0L) {
            return 0.0D;
        }

        return Math.min(1.0D, processedBytes / (double) totalBytes);
    }

    public int percent() {
        return (int) Math.round(fraction() * 100.0D);
    }
}
