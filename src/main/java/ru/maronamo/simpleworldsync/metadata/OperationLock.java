package ru.maronamo.simpleworldsync.metadata;

public final class OperationLock {
    public String deviceName;
    public String operation;
    public String startedAt;
    public String worldId;

    public OperationLock() {
    }

    public OperationLock(String deviceName, String operation, String startedAt, String worldId) {
        this.deviceName = deviceName;
        this.operation = operation;
        this.startedAt = startedAt;
        this.worldId = worldId;
    }
}
