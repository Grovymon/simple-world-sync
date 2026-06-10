package ru.maronamo.simpleworldsync.service;

public enum SyncOperation {
    UPLOAD("Выгрузка мира"),
    RESTORE("Восстановление мира");

    private final String title;

    SyncOperation(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
