package ru.maronamo.simpleworldsync.metadata;

public final class WorldIdentity {
    public String worldId;
    public boolean syncEnabled;
    public String remoteSlug;
    public String createdAt;
    public long lastSyncedVersion;

    public WorldIdentity() {
    }
}
