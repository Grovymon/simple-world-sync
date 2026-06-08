package ru.maronamo.simpleworldsync.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.service.SyncResult;
import ru.maronamo.simpleworldsync.service.VersionComparison;
import ru.maronamo.simpleworldsync.service.VersionState;
import ru.maronamo.simpleworldsync.service.WorldEntry;
import ru.maronamo.simpleworldsync.service.WorldSyncService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class SimpleWorldSyncScreen extends Screen {
    private static final int WORLDS_PER_PAGE = 6;

    private final Screen parent;
    private final WorldSyncService syncService;
    private TextFieldWidget syncFolderField;
    private List<WorldEntry> worlds = List.of();
    private WorldEntry selectedWorld;
    private int page;
    private String status = "Ready.";

    public SimpleWorldSyncScreen(Screen parent) {
        super(Text.literal("Simple World Sync"));
        this.parent = parent;
        this.syncService = SimpleWorldSyncClient.worldSyncService();
    }

    @Override
    protected void init() {
        worlds = syncService.listWorlds();
        if (selectedWorld != null) {
            selectedWorld = worlds.stream()
                    .filter(world -> world.folderName().equals(selectedWorld.folderName()))
                    .findFirst()
                    .orElse(null);
        }
        rebuild();
    }

    private void rebuild() {
        clearChildren();

        int center = width / 2;
        int left = Math.max(12, center - 170);
        int fieldY = 38;

        syncFolderField = new TextFieldWidget(textRenderer, left, fieldY, 250, 20, Text.literal("Sync folder"));
        syncFolderField.setMaxLength(1024);
        syncFolderField.setText(SimpleWorldSyncClient.configManager().getConfig().syncFolder);
        addDrawableChild(syncFolderField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Set Sync Folder"), button -> {
            SimpleWorldSyncClient.configManager().setSyncFolder(syncFolderField.getText());
            status = "Sync folder saved.";
            worlds = syncService.listWorlds();
            rebuild();
        }).dimensions(left + 256, fieldY, 116, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Open Sync Folder"), button -> openSyncFolder())
                .dimensions(left, fieldY + 26, 136, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> {
            worlds = syncService.listWorlds();
            status = "World list refreshed.";
            rebuild();
        }).dimensions(left + 142, fieldY + 26, 80, 20).build());

        addWorldButtons(left, fieldY + 60);
        addActionButtons(center, fieldY + 60);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(center - 50, height - 30, 100, 20)
                .build());
    }

    private void addWorldButtons(int left, int top) {
        int maxPage = maxPage();
        if (page > maxPage) {
            page = maxPage;
        }

        int start = page * WORLDS_PER_PAGE;
        int end = Math.min(worlds.size(), start + WORLDS_PER_PAGE);

        for (int index = start; index < end; index++) {
            WorldEntry world = worlds.get(index);
            int row = index - start;
            String prefix = selectedWorld != null && selectedWorld.folderName().equals(world.folderName()) ? "> " : "";
            ButtonWidget worldButton = ButtonWidget.builder(Text.literal(prefix + trim(world.displayName(), 34)), button -> {
                selectedWorld = world;
                VersionComparison comparison = syncService.compareWorld(world.folderName());
                status = comparison.message();
                rebuild();
            }).dimensions(left, top + row * 24, 210, 20).build();
            addDrawableChild(worldButton);
        }

        ButtonWidget previous = ButtonWidget.builder(Text.literal("<"), button -> {
            page = Math.max(0, page - 1);
            rebuild();
        }).dimensions(left, top + WORLDS_PER_PAGE * 24 + 4, 34, 20).build();
        previous.active = page > 0;
        addDrawableChild(previous);

        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), button -> {
            page = Math.min(maxPage(), page + 1);
            rebuild();
        }).dimensions(left + 176, top + WORLDS_PER_PAGE * 24 + 4, 34, 20).build();
        next.active = page < maxPage;
        addDrawableChild(next);
    }

    private void addActionButtons(int center, int top) {
        int actionLeft = center + 58;
        ButtonWidget upload = ButtonWidget.builder(Text.literal("Upload World"), button -> {
            if (selectedWorld == null) {
                status = "Select a world first.";
                rebuild();
                return;
            }
            runAsync("Uploading " + selectedWorld.folderName() + "...", () -> syncService.uploadWorld(selectedWorld.folderName()));
        }).dimensions(actionLeft, top, 154, 20).build();
        upload.active = selectedWorld != null && selectedWorld.localExists();
        addDrawableChild(upload);

        ButtonWidget restore = ButtonWidget.builder(Text.literal("Download / Restore World"), button -> confirmRestore())
                .dimensions(actionLeft, top + 26, 154, 20)
                .build();
        restore.active = selectedWorld != null;
        addDrawableChild(restore);

        ButtonWidget check = ButtonWidget.builder(Text.literal("Check Remote Version"), button -> {
            if (selectedWorld == null) {
                status = "Select a world first.";
            } else {
                VersionComparison comparison = syncService.compareWorld(selectedWorld.folderName());
                status = comparison.message();
            }
            rebuild();
        }).dimensions(actionLeft, top + 52, 154, 20).build();
        check.active = selectedWorld != null;
        addDrawableChild(check);
    }

    private void confirmRestore() {
        if (selectedWorld == null || client == null) {
            status = "Select a world first.";
            rebuild();
            return;
        }

        String folderName = selectedWorld.folderName();
        VersionComparison comparison = syncService.compareWorld(folderName);
        Text message = comparison.state() == VersionState.UNKNOWN
                ? Text.literal("Version is unknown. Restore only if you are sure. A backup will be created first.")
                : Text.literal("A backup will be created before replacing local files.");

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (client != null) {
                client.setScreen(this);
            }

            if (confirmed) {
                runAsync("Restoring " + folderName + "...", () -> syncService.restoreWorld(folderName));
            }
        }, Text.literal("Restore " + folderName + "?"), message));
    }

    private void openSyncFolder() {
        Path syncFolder = syncService.syncFolder().orElse(null);
        if (syncFolder == null) {
            status = "Set a sync folder first.";
            rebuild();
            return;
        }

        try {
            Files.createDirectories(syncFolder);
            Util.getOperatingSystem().open(syncFolder.toFile());
            status = "Opened sync folder.";
        } catch (RuntimeException | java.io.IOException exception) {
            status = "Could not open sync folder: " + exception.getMessage();
            SimpleWorldSyncClient.LOGGER.error("Failed to open sync folder {}", syncFolder, exception);
        }
        rebuild();
    }

    private void runAsync(String workingMessage, Supplier<SyncResult> task) {
        status = workingMessage;
        rebuild();

        CompletableFuture.supplyAsync(task).whenComplete((result, throwable) -> {
            if (client == null) {
                return;
            }

            client.execute(() -> {
                if (client.currentScreen != this) {
                    return;
                }

                if (throwable != null) {
                    status = "Error: " + throwable.getMessage();
                    SimpleWorldSyncClient.LOGGER.error("Simple World Sync action failed", throwable);
                } else {
                    status = result.message();
                }

                worlds = syncService.listWorlds();
                rebuild();
            });
        });
    }

    private int maxPage() {
        if (worlds.isEmpty()) {
            return 0;
        }

        return (worlds.size() - 1) / WORLDS_PER_PAGE;
    }

    private String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Sync Folder"), Math.max(12, width / 2 - 170), 27, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal("Worlds"), Math.max(12, width / 2 - 170), 89, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal("Actions"), width / 2 + 58, 89, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal(status), Math.max(12, width / 2 - 170), height - 52, statusColor());
        super.render(context, mouseX, mouseY, delta);
    }

    private int statusColor() {
        String lower = status.toLowerCase();
        if (lower.contains("failed") || lower.contains("error") || lower.contains("could not") || lower.contains("missing")) {
            return 0xFF6666;
        }

        if (lower.contains("newer") || lower.contains("unknown") || lower.contains("first")) {
            return 0xFFD966;
        }

        return 0xA5F3A5;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
