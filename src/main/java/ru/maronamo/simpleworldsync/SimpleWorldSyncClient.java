package ru.maronamo.simpleworldsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.maronamo.simpleworldsync.config.ConfigManager;
import ru.maronamo.simpleworldsync.service.SyncResult;
import ru.maronamo.simpleworldsync.service.VersionSummary;
import ru.maronamo.simpleworldsync.service.WorldSyncService;
import ru.maronamo.simpleworldsync.ui.SimpleWorldSyncScreen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class SimpleWorldSyncClient implements ClientModInitializer {
    public static final String MOD_ID = "simpleworldsync";
    public static final String MOD_NAME = "Simple World Sync";
    public static final String MOD_VERSION = "0.1.0";
    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static ConfigManager configManager;
    private static WorldSyncService worldSyncService;

    @Override
    public void onInitializeClient() {
        configManager = ConfigManager.create();
        configManager.load();

        Path runDirectory = MinecraftClient.getInstance().runDirectory.toPath();
        worldSyncService = new WorldSyncService(configManager, runDirectory);

        registerWorldScreenButton();
        registerAutoUpload();

        LOGGER.info("{} initialized. Config: {}", MOD_NAME, configManager.getConfigPath());
    }

    public static ConfigManager configManager() {
        return configManager;
    }

    public static WorldSyncService worldSyncService() {
        return worldSyncService;
    }

    private void registerWorldScreenButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof SelectWorldScreen)) {
                return;
            }

            ButtonWidget button = ButtonWidget.builder(Text.literal("World Sync"), clicked -> {
                client.setScreen(new SimpleWorldSyncScreen(screen));
            }).dimensions(Math.max(8, scaledWidth - 116), 8, 108, 20).build();
            Screens.getButtons(screen).add(button);

            if (configManager.getConfig().autoCheckOnWorldListOpen) {
                CompletableFuture.runAsync(() -> {
                    VersionSummary summary = worldSyncService.checkAllWorlds();
                    if (summary.remoteNewerCount() > 0 || summary.localNewerCount() > 0) {
                        LOGGER.info(
                                "World version check: {} remote newer, {} local newer, {} same, {} unknown.",
                                summary.remoteNewerCount(),
                                summary.localNewerCount(),
                                summary.sameCount(),
                                summary.unknownCount()
                        );
                    }
                });
            }
        });
    }

    private void registerAutoUpload() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (!configManager.getConfig().autoUploadOnWorldExit || client.getServer() == null) {
                return;
            }

            Path savePath = client.getServer().getSavePath(WorldSavePath.ROOT);
            String folderName = savePath.getFileName().toString();
            String worldName = client.getServer().getSaveProperties().getLevelName();

            LOGGER.info("Scheduling automatic upload after world exit: {}", folderName);
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }

                SyncResult result = worldSyncService.uploadWorld(folderName, worldName);
                if (result.success()) {
                    LOGGER.info(result.message());
                } else {
                    LOGGER.warn(result.message());
                }
            });
        });
    }
}
