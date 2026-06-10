package ru.maronamo.simpleworldsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.EditWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.level.storage.LevelStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.maronamo.simpleworldsync.config.ConfigManager;
import ru.maronamo.simpleworldsync.mixin.EditWorldScreenAccessor;
import ru.maronamo.simpleworldsync.service.IncrementalSyncService;
import ru.maronamo.simpleworldsync.service.SyncOperation;
import ru.maronamo.simpleworldsync.service.SyncResult;
import ru.maronamo.simpleworldsync.service.VersionSummary;
import ru.maronamo.simpleworldsync.service.WorldSyncIdentity;
import ru.maronamo.simpleworldsync.service.WorldSyncService;
import ru.maronamo.simpleworldsync.ui.AutoSyncProgressScreen;
import ru.maronamo.simpleworldsync.ui.SimpleWorldSyncScreen;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SimpleWorldSyncClient implements ClientModInitializer {
    public static final String MOD_ID = "simpleworldsync";
    public static final String MOD_NAME = "Simple World Sync";
    public static final String MOD_VERSION = "0.1.0";
    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    private static final int EDIT_SYNC_BUTTON_WIDTH = 240;
    private static final List<String> EDIT_SYNC_BUTTON_TEXTS = List.of(
            "Синхронизировать этот мир",
            "Включить синхронизацию",
            "Отключить синхронизацию",
            "Синхронизация: выкл",
            "Синхронизация: вкл"
    );

    private static ConfigManager configManager;
    private static WorldSyncService worldSyncService;
    private static IncrementalSyncService incrementalSyncService;
    private static AutoSyncController autoSyncController;

    private ActiveWorld openedWorld;
    private ActiveWorld closingWorld;
    private boolean autoUploadQueued;
    private int closedWorldTicks;

    @Override
    public void onInitializeClient() {
        configManager = ConfigManager.create();
        configManager.load();

        Path runDirectory = MinecraftClient.getInstance().runDirectory.toPath();
        worldSyncService = new WorldSyncService(configManager, runDirectory);
        incrementalSyncService = new IncrementalSyncService(configManager, runDirectory);
        autoSyncController = new AutoSyncController();

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

    public static IncrementalSyncService incrementalSyncService() {
        return incrementalSyncService;
    }

    public static AutoSyncController autoSyncController() {
        return autoSyncController;
    }

    private void registerWorldScreenButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof SelectWorldScreen) {
                ButtonWidget button = ButtonWidget.builder(Text.literal("Синхронизация миров"), clicked -> {
                    client.setScreen(new SimpleWorldSyncScreen(screen));
                }).dimensions(Math.max(8, scaledWidth - 168), 8, 160, 20).build();
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
            }

            if (screen instanceof EditWorldScreen editWorldScreen) {
                addEditWorldSyncButton(client, editWorldScreen, scaledWidth, scaledHeight);
            }
        });
    }

    private void addEditWorldSyncButton(MinecraftClient client, EditWorldScreen screen, int scaledWidth, int scaledHeight) {
        LevelStorage.Session session = ((EditWorldScreenAccessor) screen).simpleworldsync$getStorageSession();
        String folderName = session.getDirectoryName();
        removeExistingEditSyncButtons(screen);

        String label = incrementalSyncService.isWorldSyncEnabled(folderName)
                ? "Отключить синхронизацию"
                : "Включить синхронизацию";

        int x = scaledWidth / 2 - EDIT_SYNC_BUTTON_WIDTH / 2;
        int y = editSyncButtonY(screen, scaledHeight);
        ButtonWidget syncButton = ButtonWidget.builder(Text.literal(label), clicked -> {
            if (incrementalSyncService.isWorldSyncEnabled(folderName)) {
                SyncResult result = incrementalSyncService.disableWorldSync(folderName);
                clicked.setMessage(Text.literal("Включить синхронизацию"));
                LOGGER.info(result.message());
                return;
            }

            if (incrementalSyncService.syncFolder().isEmpty()) {
                client.setScreen(new SimpleWorldSyncScreen(screen));
                return;
            }

            client.setScreen(new AutoSyncProgressScreen(
                    screen,
                    Text.literal("Выгрузка мира"),
                    folderName,
                    SyncOperation.UPLOAD,
                    (progress, isCancelled) -> incrementalSyncService.enableWorldSyncAndUpload(folderName, folderName, progress, isCancelled),
                    result -> client.setScreen(screen)
            ));
        }).dimensions(x, y, EDIT_SYNC_BUTTON_WIDTH, 20).build();
        Screens.getButtons(screen).add(syncButton);
    }

    private int editSyncButtonY(Screen screen, int scaledHeight) {
        int fallback = Math.max(70, scaledHeight / 2 + 74);
        int saveButtonY = Integer.MAX_VALUE;
        int optimizeButtonY = -1;

        for (ClickableWidget widget : Screens.getButtons(screen)) {
            String message = widget.getMessage().getString();
            if (message.contains("Оптимиз") || message.toLowerCase().contains("optimize")) {
                optimizeButtonY = widget.getY();
            } else if (message.contains("Сохран") || message.toLowerCase().contains("save")) {
                saveButtonY = Math.min(saveButtonY, widget.getY());
            }
        }

        if (optimizeButtonY >= 0) {
            int belowOptimize = optimizeButtonY + 24;
            int aboveSave = saveButtonY == Integer.MAX_VALUE ? belowOptimize : saveButtonY - 24;
            return Math.max(70, Math.min(belowOptimize, aboveSave));
        }

        return Math.min(fallback, Math.max(70, scaledHeight - 56));
    }

    private void removeExistingEditSyncButtons(Screen screen) {
        Screens.getButtons(screen).removeIf(widget -> EDIT_SYNC_BUTTON_TEXTS.contains(widget.getMessage().getString()));
    }

    private void registerAutoUpload() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            markWorldClosing();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::trackWorldForAutoUpload);
    }

    private void trackWorldForAutoUpload(MinecraftClient client) {
        if (client.getServer() != null) {
            closedWorldTicks = 0;
            rememberOpenedWorld(client);
            return;
        }

        if (openedWorld != null && client.world == null && closingWorld == null) {
            markWorldClosing();
        }

        if (closingWorld != null && client.world == null && !autoUploadQueued) {
            closedWorldTicks++;
            if (closedWorldTicks < 3) {
                return;
            }

            ActiveWorld world = closingWorld;
            openedWorld = null;
            closingWorld = null;
            closedWorldTicks = 0;
            scheduleAutoUpload(client, world);
        }
    }

    private void rememberOpenedWorld(MinecraftClient client) {
        try {
            Path savePath = client.getServer().getSavePath(WorldSavePath.ROOT);
            String worldName = client.getServer().getSaveProperties().getLevelName();
            WorldSyncIdentity identity = incrementalSyncService.resolveWorldIdentity(savePath, worldName);
            ActiveWorld current = new ActiveWorld(
                    identity.worldPath(),
                    identity.worldFolderName(),
                    identity.worldDisplayName(),
                    identity.worldId(),
                    identity.syncEnabled(),
                    identity.remoteSlug(),
                    identity.identityPresent()
            );

            if (shouldLogOpenedWorld(current)) {
                LOGGER.info(
                        "World opened: {}, folder={}, worldId={}, syncEnabled={}",
                        current.worldName(),
                        current.folderName(),
                        printableWorldId(current.worldId()),
                        current.syncEnabled()
                );
                autoUploadQueued = false;
            }

            openedWorld = current;
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not track opened world for automatic upload", exception);
        }
    }

    private boolean shouldLogOpenedWorld(ActiveWorld current) {
        if (openedWorld == null || !openedWorld.folderName().equals(current.folderName())) {
            return true;
        }

        return openedWorld.syncEnabled() != current.syncEnabled()
                || !openedWorld.worldId().equals(current.worldId())
                || openedWorld.identityPresent() != current.identityPresent();
    }

    private void markWorldClosing() {
        if (openedWorld == null || closingWorld != null) {
            return;
        }

        closingWorld = openedWorld;
        LOGGER.info("World closing detected");
    }

    private void scheduleAutoUpload(MinecraftClient client, ActiveWorld world) {
        autoUploadQueued = true;

        if (!configManager.getConfig().autoUploadOnWorldExit) {
            LOGGER.info("Auto upload skipped: autoUploadOnWorldExit=false");
            return;
        }

        if (incrementalSyncService.syncFolder().isEmpty()) {
            LOGGER.info("Auto upload skipped: syncFolder missing");
            return;
        }

        WorldSyncIdentity latestIdentity = incrementalSyncService.resolveWorldIdentity(world.worldPath(), world.worldName());
        if (!latestIdentity.identityPresent()) {
            if (world.syncEnabled()) {
                LOGGER.warn(
                        "GUI/auto-upload sync state mismatch: remembered syncEnabled=true but world metadata is missing for folder={}",
                        world.folderName()
                );
            }
            LOGGER.info("Auto upload skipped: world metadata missing");
            return;
        }

        if (!latestIdentity.syncEnabled()) {
            if (world.syncEnabled()) {
                LOGGER.warn(
                        "GUI/auto-upload sync state mismatch: remembered syncEnabled=true but latest syncEnabled=false for folder={}",
                        latestIdentity.worldFolderName()
                );
            }
            LOGGER.info("Auto upload skipped: syncEnabled=false");
            return;
        }

        if (latestIdentity.worldId().isBlank()) {
            LOGGER.info("Auto upload skipped: worldId missing");
            return;
        }

        if (latestIdentity.remoteSlug().isBlank()) {
            LOGGER.warn(
                    "Auto upload metadata warning: remoteSlug missing for folder={}, using worldId fallback",
                    latestIdentity.worldFolderName()
            );
        }

        LOGGER.info(
                "World closed, scheduling auto upload: {}, worldId={}",
                latestIdentity.worldDisplayName(),
                latestIdentity.worldId()
        );
        client.setScreen(new AutoSyncProgressScreen(
                autoUploadParent(client),
                Text.literal("Выгрузка мира"),
                latestIdentity.worldDisplayName(),
                SyncOperation.UPLOAD,
                (progress, isCancelled) -> {
                    LOGGER.info("Auto upload started");
                    SyncResult result = incrementalSyncService.incrementalUploadWorldAfterExit(
                            latestIdentity.worldFolderName(),
                            latestIdentity.worldDisplayName(),
                            progress,
                            isCancelled
                    );
                    if (result.success()) {
                        LOGGER.info("Auto upload completed");
                    } else {
                        LOGGER.warn("Auto upload failed: {}", result.message());
                    }
                    return result;
                },
                null
        ));
    }

    private Screen autoUploadParent(MinecraftClient client) {
        if (client.currentScreen instanceof SelectWorldScreen) {
            return client.currentScreen;
        }

        return new SelectWorldScreen(client.currentScreen);
    }

    private String printableWorldId(String worldId) {
        return worldId == null || worldId.isBlank() ? "-" : worldId;
    }

    private record ActiveWorld(
            Path worldPath,
            String folderName,
            String worldName,
            String worldId,
            boolean syncEnabled,
            String remoteSlug,
            boolean identityPresent
    ) {
    }
}
