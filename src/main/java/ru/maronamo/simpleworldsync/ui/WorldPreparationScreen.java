package ru.maronamo.simpleworldsync.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.service.IncrementalSyncService;
import ru.maronamo.simpleworldsync.service.SyncOperation;
import ru.maronamo.simpleworldsync.service.SyncProgress;
import ru.maronamo.simpleworldsync.service.SyncResult;
import ru.maronamo.simpleworldsync.service.WorldLaunchCheck;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldPreparationScreen extends Screen {
    private final Screen parent;
    private final String folderName;
    private final String worldName;
    private final Runnable launchAction;
    private final IncrementalSyncService syncService;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private CompletableFuture<WorldLaunchCheck> checkTask;
    private SyncProgress progress;

    public WorldPreparationScreen(Screen parent, String folderName, String worldName, Runnable launchAction) {
        super(Text.literal("Подготовка мира"));
        this.parent = parent;
        this.folderName = folderName;
        this.worldName = worldName == null || worldName.isBlank() ? folderName : worldName;
        this.launchAction = launchAction;
        this.syncService = SimpleWorldSyncClient.incrementalSyncService();
        this.progress = new SyncProgress(SyncOperation.RESTORE, this.worldName, "Проверка версии мира...", 0L, 0L, "");
    }

    @Override
    protected void init() {
        if (checkTask == null) {
            startCheck();
        }

        clearChildren();
        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> cancel())
                .dimensions(width / 2 - 60, height - 30, 120, 20)
                .build());
    }

    private void startCheck() {
        checkTask = CompletableFuture.supplyAsync(() -> syncService.checkWorldForLaunch(
                folderName,
                worldName,
                this::publishProgress,
                cancelled::get
        ));

        checkTask.whenComplete((check, throwable) -> {
            if (client == null || cancelled.get()) {
                return;
            }

            client.execute(() -> {
                if (throwable != null) {
                    SimpleWorldSyncClient.LOGGER.error("Failed to prepare world launch", throwable);
                    showLaunchLocalPrompt("Проверка синхронизации не удалась. Запустить локальную версию мира?");
                    return;
                }

                handleCheck(check);
            });
        });
    }

    private void publishProgress(SyncProgress snapshot) {
        if (client == null) {
            return;
        }

        client.execute(() -> progress = snapshot);
    }

    private void handleCheck(WorldLaunchCheck check) {
        if (client == null) {
            return;
        }

        switch (check.action()) {
            case DISABLED, READY -> launchAction.run();
            case RESTORE_REQUIRED -> client.setScreen(new AutoSyncProgressScreen(
                    parent,
                    Text.literal("Подготовка мира"),
                    worldName,
                    SyncOperation.RESTORE,
                    (progress, isCancelled) -> syncService.incrementalRestoreWorld(folderName, progress, isCancelled),
                    result -> launchAction.run()
            ));
            case CONFLICT -> client.setScreen(new SyncConflictScreen(parent, folderName, worldName, launchAction));
            case SYNC_FOLDER_MISSING -> showLaunchLocalPrompt("Папка синхронизации недоступна. Запустить локальную версию мира?");
            case LOCKED -> showLaunchLocalPrompt(check.message() + " Запустить локальную версию мира?");
            case STALE_LOCK -> showStaleLockPrompt(check.message());
        }
    }

    private void showLaunchLocalPrompt(String message) {
        if (client == null) {
            return;
        }

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (client == null) {
                return;
            }

            if (confirmed) {
                launchAction.run();
            } else {
                client.setScreen(parent);
            }
        }, Text.literal("Папка синхронизации недоступна"),
                Text.literal(message),
                Text.literal("Запустить локально"),
                Text.literal("Отмена")));
    }

    private void showStaleLockPrompt(String message) {
        if (client == null) {
            return;
        }

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (client == null) {
                return;
            }

            if (confirmed) {
                SyncResult result = syncService.deleteStaleLock(folderName);
                if (result.success()) {
                    client.setScreen(new WorldPreparationScreen(parent, folderName, worldName, launchAction));
                } else {
                    showLaunchLocalPrompt(result.message() + " Запустить локальную версию мира?");
                }
            } else {
                client.setScreen(parent);
            }
        }, Text.literal("Блокировка синхронизации"),
                Text.literal(message),
                Text.literal("Удалить"),
                Text.literal("Отмена")));
    }

    private void cancel() {
        cancelled.set(true);
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void close() {
        cancel();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int contentWidth = Math.min(360, width - 24);
        int left = (width - contentWidth) / 2;
        int y = 48;

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y, 0xFFFFFF);
        y += 32;
        context.drawTextWithShadow(textRenderer, Text.literal("Мир"), left, y, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal(trimToWidth(progress.worldName(), contentWidth - 112)), left + 112, y, 0xFFFFFF);
        y += 18;
        context.drawTextWithShadow(textRenderer, Text.literal("Этап"), left, y, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal(trimToWidth(progress.stage(), contentWidth - 112)), left + 112, y, 0xD6D6D6);
        y += 18;
        context.drawTextWithShadow(textRenderer, Text.literal("Файл"), left, y, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal(trimToWidth(progress.currentFile().isBlank() ? "-" : progress.currentFile(), contentWidth - 112)), left + 112, y, 0xD6D6D6);
    }

    private String trimToWidth(String value, int maxWidth) {
        if (value == null || value.isBlank()) {
            return "";
        }

        if (textRenderer.getWidth(value) <= maxWidth) {
            return value;
        }

        String ellipsis = "...";
        int available = Math.max(0, maxWidth - textRenderer.getWidth(ellipsis));
        return textRenderer.trimToWidth(value, available) + ellipsis;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
