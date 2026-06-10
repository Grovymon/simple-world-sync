package ru.maronamo.simpleworldsync.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.service.IncrementalSyncService;
import ru.maronamo.simpleworldsync.service.SyncOperation;

public final class SyncConflictScreen extends Screen {
    private final Screen parent;
    private final String folderName;
    private final String worldName;
    private final Runnable launchAction;
    private final IncrementalSyncService syncService;

    public SyncConflictScreen(Screen parent, String folderName, String worldName, Runnable launchAction) {
        super(Text.literal("Обнаружен конфликт синхронизации"));
        this.parent = parent;
        this.folderName = folderName;
        this.worldName = worldName == null || worldName.isBlank() ? folderName : worldName;
        this.launchAction = launchAction;
        this.syncService = SimpleWorldSyncClient.incrementalSyncService();
    }

    @Override
    protected void init() {
        clearChildren();

        int center = width / 2;
        int top = Math.max(110, height / 2 - 20);

        addDrawableChild(ButtonWidget.builder(Text.literal("Скачать версию с сервера"), button -> {
            if (client != null) {
                client.setScreen(new AutoSyncProgressScreen(
                        parent,
                        Text.literal("Подготовка мира"),
                        worldName,
                        SyncOperation.RESTORE,
                        (progress, isCancelled) -> syncService.incrementalRestoreWorld(folderName, progress, isCancelled),
                        result -> launchAction.run()
                ));
            }
        }).dimensions(center - 120, top, 240, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Выгрузить локальную версию принудительно"), button -> {
            if (client != null) {
                client.setScreen(new AutoSyncProgressScreen(
                        parent,
                        Text.literal("Выгрузка мира"),
                        worldName,
                        SyncOperation.UPLOAD,
                        (progress, isCancelled) -> syncService.incrementalUploadWorld(folderName, worldName, true, progress, isCancelled),
                        result -> launchAction.run()
                ));
            }
        }).dimensions(center - 150, top + 26, 300, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> {
            if (client != null) {
                client.setScreen(parent);
            }
        }).dimensions(center - 60, top + 52, 120, 20).build());
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int y = 42;
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y, 0xFFFFFF);
        y += 28;
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("На сервере есть новая версия мира, но локальный мир тоже был изменён."),
                width / 2,
                y,
                0xFFD966
        );
        y += 18;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(worldName), width / 2, y, 0xD6D6D6);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
