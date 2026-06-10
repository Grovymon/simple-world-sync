package ru.maronamo.simpleworldsync.metadata;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldManifest {
    public String worldId;
    public String worldName;
    public String worldFolderName;
    public long version;
    public String updatedAt;
    public String deviceName;
    public String minecraftVersion;
    public String modVersion;
    public Map<String, FileRecord> files = new LinkedHashMap<>();

    public WorldManifest() {
    }

    public static final class FileRecord {
        public String path;
        public long size;
        public String sha256;
        public String modifiedAt;

        public FileRecord() {
        }

        public FileRecord(String path, long size, String sha256, String modifiedAt) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.modifiedAt = modifiedAt;
        }
    }
}
