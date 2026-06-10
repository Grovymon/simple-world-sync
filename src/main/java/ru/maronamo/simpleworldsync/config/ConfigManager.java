package ru.maronamo.simpleworldsync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configPath;
    private final Path localMetadataDir;
    private final Path localManifestDir;
    private final Path localStatePath;
    private SimpleWorldSyncConfig config = new SimpleWorldSyncConfig();

    private ConfigManager(Path configPath) {
        this.configPath = configPath;
        this.localMetadataDir = configPath.getParent().resolve("simpleworldsync-worlds");
        this.localManifestDir = configPath.getParent().resolve("simpleworldsync-manifests");
        this.localStatePath = configPath.getParent().resolve("simpleworldsync-state.json");
    }

    public static ConfigManager create() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("simpleworldsync.json");
        return new ConfigManager(configPath);
    }

    public void load() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.createDirectories(localMetadataDir);
            Files.createDirectories(localManifestDir);

            if (!Files.exists(configPath)) {
                save();
                return;
            }

            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                SimpleWorldSyncConfig loaded = GSON.fromJson(reader, SimpleWorldSyncConfig.class);
                if (loaded != null) {
                    config = loaded;
                    config.effectiveExcludedPatterns();
                }
            }
        } catch (IOException | RuntimeException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to load Simple World Sync config from {}", configPath, exception);
            config = new SimpleWorldSyncConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.createDirectories(localMetadataDir);
            Files.createDirectories(localManifestDir);
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException exception) {
            SimpleWorldSyncClient.LOGGER.error("Failed to save Simple World Sync config to {}", configPath, exception);
        }
    }

    public SimpleWorldSyncConfig getConfig() {
        return config;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public Path getLocalMetadataDir() {
        return localMetadataDir;
    }

    public Path getLocalManifestDir() {
        return localManifestDir;
    }

    public Path getLocalStatePath() {
        return localStatePath;
    }

    public void setSyncFolder(String syncFolder) {
        config.syncFolder = syncFolder == null ? "" : syncFolder.trim();
        save();
    }
}
