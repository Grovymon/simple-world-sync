package ru.maronamo.simpleworldsync.metadata;

public final class WorldMetadata {
    public String worldName;
    public String worldFolderName;
    public long version;
    public String uploadedAt;
    public String deviceName;
    public String minecraftVersion;
    public String modVersion;

    public WorldMetadata() {
    }

    public WorldMetadata(
            String worldName,
            String worldFolderName,
            long version,
            String uploadedAt,
            String deviceName,
            String minecraftVersion,
            String modVersion
    ) {
        this.worldName = worldName;
        this.worldFolderName = worldFolderName;
        this.version = version;
        this.uploadedAt = uploadedAt;
        this.deviceName = deviceName;
        this.minecraftVersion = minecraftVersion;
        this.modVersion = modVersion;
    }
}
