package ru.maronamo.simpleworldsync.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.service.IncrementalSyncService;
import ru.maronamo.simpleworldsync.service.SyncOperation;
import ru.maronamo.simpleworldsync.service.SyncResult;
import ru.maronamo.simpleworldsync.service.WorldEntry;
import ru.maronamo.simpleworldsync.service.WorldLaunchCheck;
import ru.maronamo.simpleworldsync.service.WorldSyncService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SimpleWorldSyncScreen extends Screen {
    private static final int WORLDS_PER_PAGE = 6;
    private static final int WORLD_BUTTON_WIDTH = 210;
    private static final int ACTION_BUTTON_WIDTH = 260;

    private final Screen parent;
    private final WorldSyncService syncService;
    private final IncrementalSyncService incrementalSyncService;
    private TextFieldWidget syncFolderField;
    private List<WorldEntry> worlds = List.of();
    private WorldEntry selectedWorld;
    private String status = "Выберите мир.";

    public SimpleWorldSyncScreen(Screen parent) {
        super(Text.literal("Синхронизация миров"));
        this.parent = parent;
        this.syncService = SimpleWorldSyncClient.worldSyncService();
        this.incrementalSyncService = SimpleWorldSyncClient.incrementalSyncService();
    }

    @Override
    protected void init() {
        refreshWorlds();
        rebuild();
        startStatusCheck(selectedWorld);
    }

    public void updateAfterOperation(SyncResult result) {
        status = result.message();
        refreshWorlds();
    }

    private void refreshWorlds() {
        worlds = syncService.listWorlds();
        if (selectedWorld != null) {
            selectedWorld = worlds.stream()
                    .filter(world -> world.folderName().equals(selectedWorld.folderName()))
                    .findFirst()
                    .orElse(null);
        }
        if (selectedWorld == null && !worlds.isEmpty()) {
            selectedWorld = worlds.get(0);
        }
        updateSelectedStatus();
    }

    private void rebuild() {
        clearChildren();

        int center = width / 2;
        int left = Math.max(12, center - 260);
        int fieldY = 38;

        syncFolderField = new TextFieldWidget(textRenderer, left, fieldY, 250, 20, Text.literal("Папка синхронизации"));
        syncFolderField.setMaxLength(1024);
        syncFolderField.setText(SimpleWorldSyncClient.configManager().getConfig().syncFolder);
        addDrawableChild(syncFolderField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Выбрать папку синхронизации"), button -> {
            SimpleWorldSyncClient.configManager().setSyncFolder(syncFolderField.getText());
            status = "Папка синхронизации сохранена.";
            refreshWorlds();
            rebuild();
            startStatusCheck(selectedWorld);
        }).dimensions(left + 256, fieldY, 184, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Открыть папку"), button -> openSyncFolder())
                .dimensions(left, fieldY + 26, 150, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Обновить"), button -> {
            refreshWorlds();
            status = "Список миров обновлён.";
            rebuild();
            startStatusCheck(selectedWorld);
        }).dimensions(left + 156, fieldY + 26, 106, 20).build());

        int contentTop = fieldY + 60;
        addWorldButtons(left, contentTop);
        int actionLeft = left + 230;
        addMainActionButton(actionLeft, contentTop);
        addDeleteRemoteButton(actionLeft, contentTop + 24);

        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), button -> close())
                .dimensions(center - 50, height - 30, 100, 20)
                .build());
    }

    private void addWorldButtons(int left, int top) {
        int visibleRows = Math.max(1, Math.min(WORLDS_PER_PAGE, (height - top - 70) / 24));
        int end = Math.min(worlds.size(), visibleRows);

        for (int index = 0; index < end; index++) {
            WorldEntry world = worlds.get(index);
            String prefix = selectedWorld != null && selectedWorld.folderName().equals(world.folderName()) ? "> " : "";
            String syncPrefix = world.remoteOnly() ? "" : incrementalSyncService.isWorldSyncEnabled(world.folderName()) ? "✓ " : "";
            ButtonWidget worldButton = ButtonWidget.builder(Text.literal(prefix + syncPrefix + trim(world.displayName(), 32)), button -> {
                selectWorld(world);
                rebuild();
            }).dimensions(left, top + index * 24, WORLD_BUTTON_WIDTH, 20).build();
            addDrawableChild(worldButton);
        }
    }

    private void addMainActionButton(int actionLeft, int top) {
        ButtonWidget main = ButtonWidget.builder(Text.literal(mainActionLabel()), button -> runMainAction())
                .dimensions(actionLeft, top, ACTION_BUTTON_WIDTH, 20)
                .build();
        main.active = selectedWorld != null;
        addDrawableChild(main);
    }

    private void addDeleteRemoteButton(int actionLeft, int top) {
        if (!canDeleteRemoteWorld()) {
            return;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Удалить с сервера"), button -> confirmDeleteRemoteWorld(selectedWorld))
                .dimensions(actionLeft, top, ACTION_BUTTON_WIDTH, 20)
                .build());
    }

    private boolean canDeleteRemoteWorld() {
        if (selectedWorld == null) {
            return false;
        }

        return selectedWorld.remoteOnly()
                || (selectedWorld.localExists() && incrementalSyncService.isWorldSyncEnabled(selectedWorld.folderName()));
    }

    private String mainActionLabel() {
        if (selectedWorld == null) {
            return "Выберите мир";
        }
        if (selectedWorld.remoteOnly()) {
            return "Скачать на это устройство";
        }
        if (incrementalSyncService.isWorldSyncEnabled(selectedWorld.folderName())) {
            return "Синхронизировать сейчас";
        }
        return "Синхронизировать этот мир";
    }

    private void runMainAction() {
        if (selectedWorld == null || client == null) {
            status = "Сначала выберите мир.";
            rebuild();
            return;
        }

        if (selectedWorld.remoteOnly()) {
            downloadRemoteWorld(selectedWorld);
            return;
        }

        if (!incrementalSyncService.isWorldSyncEnabled(selectedWorld.folderName())) {
            confirmEnableSync(selectedWorld);
            return;
        }

        smartSyncSelectedWorld(selectedWorld);
    }

    private void confirmEnableSync(WorldEntry world) {
        if (client == null) {
            return;
        }

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (client == null) {
                return;
            }

            if (confirmed) {
                client.setScreen(new AutoSyncProgressScreen(
                        this,
                        Text.literal("Синхронизация мира"),
                        world.displayName(),
                        SyncOperation.UPLOAD,
                        (progress, isCancelled) -> incrementalSyncService.enableWorldSyncAndUpload(world.folderName(), world.displayName(), progress, isCancelled),
                        this::returnAfterOperation
                ));
            } else {
                client.setScreen(this);
            }
        }, Text.literal("Синхронизировать мир?"),
                Text.literal("Этот мир ещё не синхронизирован. Синхронизировать этот мир с вашим устройством?"),
                Text.literal("Да, синхронизировать"),
                Text.literal("Нет")));
    }

    private void downloadRemoteWorld(WorldEntry world) {
        if (client == null) {
            return;
        }

        client.setScreen(new AutoSyncProgressScreen(
                this,
                Text.literal("Скачивание мира"),
                world.displayName(),
                SyncOperation.RESTORE,
                (progress, isCancelled) -> incrementalSyncService.downloadRemoteWorld(world, progress, isCancelled),
                this::returnAfterOperation
        ));
    }

    private void confirmDeleteRemoteWorld(WorldEntry world) {
        if (client == null || world == null) {
            return;
        }

        Text message = world.remoteOnly()
                ? Text.literal("Локального мира на этом устройстве нет.\nЭто удалит серверную копию мира.")
                : Text.literal("Это удалит серверную копию мира.\nЛокальный мир на этом устройстве останется.");

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (client == null) {
                return;
            }

            if (!confirmed) {
                client.setScreen(this);
                return;
            }

            status = "Удаление мира из синхронизации...";
            client.setScreen(this);
            rebuild();

            CompletableFuture.supplyAsync(() -> incrementalSyncService.deleteRemoteWorld(world))
                    .whenComplete((result, throwable) -> {
                        if (client == null) {
                            return;
                        }

                        client.execute(() -> {
                            if (throwable != null) {
                                status = "Не удалось удалить мир из синхронизации: " + friendlyMessage(throwable);
                            } else {
                                refreshWorlds();
                                status = result.message();
                            }
                            rebuild();
                            startStatusCheck(selectedWorld);
                        });
                    });
        }, Text.literal("Удалить этот мир из папки синхронизации?"),
                message,
                Text.literal("Удалить"),
                Text.literal("Отмена")));
    }

    private void smartSyncSelectedWorld(WorldEntry world) {
        status = "Проверка версии мира...";
        rebuild();

        CompletableFuture.supplyAsync(() -> incrementalSyncService.checkWorldForLaunch(
                world.folderName(),
                world.displayName(),
                progress -> {
                },
                () -> false
        )).whenComplete((check, throwable) -> {
            if (client == null) {
                return;
            }

            client.execute(() -> {
                if (throwable != null) {
                    status = "Проверка синхронизации не удалась: " + friendlyMessage(throwable);
                    rebuild();
                    return;
                }

                handleSmartSyncDecision(world, check);
            });
        });
    }

    private void handleSmartSyncDecision(WorldEntry world, WorldLaunchCheck check) {
        if (client == null) {
            return;
        }

        switch (check.action()) {
            case RESTORE_REQUIRED -> client.setScreen(new AutoSyncProgressScreen(
                    this,
                    Text.literal("Синхронизация мира"),
                    world.displayName(),
                    SyncOperation.RESTORE,
                    (progress, isCancelled) -> incrementalSyncService.incrementalRestoreWorld(world.folderName(), progress, isCancelled),
                    this::returnAfterOperation
            ));
            case CONFLICT -> client.setScreen(new SyncConflictScreen(this, world.folderName(), world.displayName(), () -> {
                updateAfterOperation(SyncResult.ok("Мир успешно синхронизирован."));
                if (client != null) {
                    client.setScreen(this);
                }
            }));
            case SYNC_FOLDER_MISSING, LOCKED, STALE_LOCK -> {
                status = check.message();
                rebuild();
            }
            case DISABLED, READY -> client.setScreen(new AutoSyncProgressScreen(
                    this,
                    Text.literal("Синхронизация мира"),
                    world.displayName(),
                    SyncOperation.UPLOAD,
                    (progress, isCancelled) -> incrementalSyncService.incrementalUploadWorld(world.folderName(), world.displayName(), false, progress, isCancelled),
                    this::returnAfterOperation
            ));
        }
    }

    private void returnAfterOperation(SyncResult result) {
        updateAfterOperation(result);
        updateSelectedStatus();
        if (client != null) {
            client.setScreen(this);
        }
    }

    private void selectWorld(WorldEntry world) {
        selectedWorld = world;
        updateSelectedStatus();
        startStatusCheck(world);
    }

    private void updateSelectedStatus() {
        if (selectedWorld == null) {
            status = "Выберите мир.";
            return;
        }

        if (selectedWorld.remoteOnly()) {
            status = "☁ Мир найден на сервере\nМожно скачать на это устройство.";
            return;
        }

        if (incrementalSyncService.isWorldSyncEnabled(selectedWorld.folderName())) {
            status = syncEnabledStatus("Состояние: проверяется...");
        } else {
            status = "Синхронизация выключена\nЭтот мир ещё не синхронизирован.";
        }
    }

    private void startStatusCheck(WorldEntry world) {
        if (world == null || world.remoteOnly() || !world.localExists() || !incrementalSyncService.isWorldSyncEnabled(world.folderName())) {
            return;
        }

        CompletableFuture.supplyAsync(() -> incrementalSyncService.checkWorldForLaunch(
                world.folderName(),
                world.displayName(),
                progress -> {
                },
                () -> false
        )).whenComplete((check, throwable) -> {
            if (client == null) {
                return;
            }

            client.execute(() -> {
                if (selectedWorld == null || !selectedWorld.folderName().equals(world.folderName())) {
                    return;
                }

                if (throwable != null) {
                    status = syncEnabledStatus("Состояние: проверка не удалась");
                } else {
                    status = statusForCheck(check);
                }
                rebuild();
            });
        });
    }

    private String statusForCheck(WorldLaunchCheck check) {
        return switch (check.action()) {
            case READY -> syncEnabledStatus("Состояние: Версия мира актуальна");
            case RESTORE_REQUIRED -> syncEnabledStatus("Состояние: Нужно скачать изменения");
            case CONFLICT -> syncEnabledStatus("Состояние: Обнаружен конфликт синхронизации");
            case SYNC_FOLDER_MISSING -> syncEnabledStatus("Состояние: Папка синхронизации недоступна");
            case LOCKED, STALE_LOCK -> syncEnabledStatus("Состояние: " + check.message());
            case DISABLED -> "Синхронизация выключена";
        };
    }

    private String syncEnabledStatus(String detail) {
        String suffix = SimpleWorldSyncClient.configManager().getConfig().autoUploadOnWorldExit
                ? ""
                : "\nАвтовыгрузка после выхода отключена";
        return "✓ Синхронизация включена\n" + detail + suffix;
    }

    private void openSyncFolder() {
        Path syncFolder = syncService.syncFolder().orElse(null);
        if (syncFolder == null) {
            status = "Папка синхронизации не выбрана.";
            rebuild();
            return;
        }

        try {
            Files.createDirectories(syncFolder);
            Util.getOperatingSystem().open(syncFolder.toFile());
            status = "Папка открыта.";
        } catch (RuntimeException | java.io.IOException exception) {
            status = "Не удалось открыть папку: " + exception.getMessage();
            SimpleWorldSyncClient.LOGGER.error("Failed to open sync folder {}", syncFolder, exception);
        }
        rebuild();
    }

    private String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int left = Math.max(12, width / 2 - 260);
        int actionLeft = left + 230;
        int contentTop = 98;

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Папка синхронизации"), left, 27, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal("Миры"), left, 89, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal("Состояние"), actionLeft, 89, 0xA0A0A0);

        int statusY = contentTop + (canDeleteRemoteWorld() ? 58 : 30);
        drawStatus(context, actionLeft, statusY, ACTION_BUTTON_WIDTH);
        context.drawTextWithShadow(textRenderer, Text.literal(trimToWidth(firstStatusLine(), width - 24)), left, height - 52, statusColor());
    }

    private void drawStatus(DrawContext context, int left, int y, int width) {
        String[] lines = status.split("\\R", 3);
        for (int index = 0; index < lines.length; index++) {
            int color = index == 0 ? statusColor() : 0xD6D6D6;
            context.drawTextWithShadow(textRenderer, Text.literal(trimToWidth(lines[index], width)), left, y + index * 14, color);
        }
    }

    private String firstStatusLine() {
        int newline = status.indexOf('\n');
        return newline >= 0 ? status.substring(0, newline) : status;
    }

    private int statusColor() {
        String lower = status.toLowerCase();
        if (lower.contains("ошиб") || lower.contains("не удалось") || lower.contains("не найден") || lower.contains("конфликт") || lower.contains("недоступ")) {
            return 0xFFB4A8;
        }

        if (lower.contains("нужно") || lower.contains("провер") || lower.contains("сначала") || lower.contains("не выбрана") || lower.contains("сервер")) {
            return 0xFFD966;
        }

        return 0xA5F3A5;
    }

    private String friendlyMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
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
