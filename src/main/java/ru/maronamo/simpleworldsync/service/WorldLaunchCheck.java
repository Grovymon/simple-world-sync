package ru.maronamo.simpleworldsync.service;

public record WorldLaunchCheck(Action action, String message) {
    public enum Action {
        DISABLED,
        READY,
        RESTORE_REQUIRED,
        CONFLICT,
        SYNC_FOLDER_MISSING,
        LOCKED,
        STALE_LOCK
    }
}
