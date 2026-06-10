package ru.maronamo.simpleworldsync.metadata;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SyncStateDatabase {
    public Map<String, SyncStateEntry> worlds = new LinkedHashMap<>();

    public SyncStateDatabase() {
    }

    public static final class SyncStateEntry {
        public long lastSyncedVersion;
        public long lastKnownRemoteVersion;
        public String lastUploadDevice;
        public String lastSyncTime;

        public SyncStateEntry() {
        }
    }
}
