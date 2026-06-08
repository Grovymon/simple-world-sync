package ru.maronamo.simpleworldsync.service;

public record SyncResult(boolean success, String message) {
    public static SyncResult ok(String message) {
        return new SyncResult(true, message);
    }

    public static SyncResult error(String message) {
        return new SyncResult(false, message);
    }
}
