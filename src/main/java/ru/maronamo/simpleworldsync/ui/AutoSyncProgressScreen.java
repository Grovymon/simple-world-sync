package ru.maronamo.simpleworldsync.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;
import ru.maronamo.simpleworldsync.service.CancelableSyncTask;
import ru.maronamo.simpleworldsync.service.SyncOperation;
import ru.maronamo.simpleworldsync.service.SyncProgress;
import ru.maronamo.simpleworldsync.service.SyncResult;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class AutoSyncProgressScreen extends Screen {
    private static final int CONTENT_WIDTH = 420;
    private static final int BAR_HEIGHT = 12;

    private final Screen parent;
    private final String worldName;
    private final SyncOperation operation;
    private final ProgressOperation operationTask;
    private final Consumer<SyncResult> onSuccess;

    private CancelableSyncTask task;
    private SyncProgress progress;
    private SyncResult result;
    private boolean cancelRequested;
    private long stageStartedNanos;
    private String activeStage;

    public AutoSyncProgressScreen(
            Screen parent,
            Text title,
            String worldName,
            SyncOperation operation,
            ProgressOperation operationTask,
            Consumer<SyncResult> onSuccess
    ) {
        super(title);
        this.parent = parent;
        this.worldName = worldName == null || worldName.isBlank() ? "-" : worldName;
        this.operation = operation;
        this.operationTask = operationTask;
        this.onSuccess = onSuccess;
        this.progress = new SyncProgress(operation, this.worldName, "Подготовка...", 0L, 0L, "");
        this.activeStage = this.progress.stage();
        this.stageStartedNanos = System.nanoTime();
    }

    @Override
    protected void init() {
        if (task == null) {
            startTask();
        }

        rebuildButtons();
    }

    private void startTask() {
        task = CancelableSyncTask.start(isCancelled -> operationTask.run(this::publishProgress, isCancelled));
        task.future().whenComplete((syncResult, throwable) -> {
            if (client == null) {
                return;
            }

            client.execute(() -> {
                if (throwable != null) {
                    SimpleWorldSyncClient.LOGGER.error("Automatic world sync failed", throwable);
                    result = SyncResult.error("Ошибка операции: " + friendlyMessage(throwable));
                } else {
                    result = syncResult;
                }

                if (result.success() && onSuccess != null) {
                    onSuccess.accept(result);
                    return;
                }

                rebuildButtons();
            });
        });
    }

    private void publishProgress(SyncProgress snapshot) {
        if (client == null) {
            return;
        }

        client.execute(() -> {
            if (!snapshot.stage().equals(activeStage)) {
                activeStage = snapshot.stage();
                stageStartedNanos = System.nanoTime();
            }
            progress = snapshot;
        });
    }

    public Consumer<SyncProgress> progressSink() {
        return this::publishProgress;
    }

    private void rebuildButtons() {
        clearChildren();

        int center = width / 2;
        if (isRunning()) {
            ButtonWidget cancel = ButtonWidget.builder(
                    Text.literal(cancelRequested ? "Отмена..." : "Отмена"),
                    button -> requestCancel()
            ).dimensions(center - 60, height - 30, 120, 20).build();
            cancel.active = !cancelRequested;
            addDrawableChild(cancel);
            return;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), button -> returnToParent())
                .dimensions(center - 60, height - 30, 120, 20)
                .build());
    }

    private boolean isRunning() {
        return result == null;
    }

    private void requestCancel() {
        if (task == null || task.isDone() || cancelRequested) {
            return;
        }

        cancelRequested = true;
        task.cancel();
        progress = new SyncProgress(
                operation,
                progress.worldName(),
                "Операция отменяется...",
                progress.processedBytes(),
                progress.totalBytes(),
                progress.currentFile(),
                progress.newFiles(),
                progress.changedFiles(),
                progress.deletedFiles()
        );
        rebuildButtons();
    }

    private void returnToParent() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void close() {
        if (client == null) {
            return;
        }

        if (!isRunning()) {
            returnToParent();
            return;
        }

        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                requestCancel();
            }

            if (client != null) {
                client.setScreen(this);
            }
        }, Text.literal("Отменить операцию?"),
                Text.literal("Операция ещё выполняется. Отменить её и дождаться остановки?"),
                Text.literal("Отменить"),
                Text.literal("Продолжить")));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int contentWidth = Math.min(CONTENT_WIDTH, width - 24);
        int left = (width - contentWidth) / 2;
        int y = 28;

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y, 0xFFFFFF);
        y += 28;

        drawRow(context, left, y, contentWidth, "Мир", progress.worldName(), 0xFFFFFF);
        y += 18;

        drawProgressBar(context, left, y, contentWidth);
        y += BAR_HEIGHT + 8;

        String percent = progress.totalBytes() > 0L ? progress.percent() + "%" : "0%";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(percent), width / 2, y, 0xFFFFFF);
        y += 22;

        drawRow(context, left, y, contentWidth, "Обработано", formatProgressBytes(), 0xD6D6D6);
        y += 16;
        drawRow(context, left, y, contentWidth, "Скорость", speedText(), 0xD6D6D6);
        y += 16;
        drawRow(context, left, y, contentWidth, "Осталось", etaText(), 0xD6D6D6);
        y += 16;
        drawRow(context, left, y, contentWidth, "Файл", currentFileText(contentWidth - 112), 0xD6D6D6);
        y += 16;
        drawRow(context, left, y, contentWidth, "Этап", trimToWidth(progress.stage(), contentWidth - 112), 0xD6D6D6);
        y += 16;
        drawRow(context, left, y, contentWidth, "Файлы", fileCounters(), 0xD6D6D6);
        y += 24;

        if (result != null) {
            int color = result.success() ? 0xA5F3A5 : 0xFFB4A8;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(trimToWidth(result.message(), contentWidth)), width / 2, y, color);
        }
    }

    private void drawProgressBar(DrawContext context, int left, int top, int width) {
        int border = 0xFF777777;
        int background = 0xFF202020;
        int fill = result != null && !result.success() ? 0xFFC75646 : 0xFF4FA3FF;
        int filledWidth = progress.totalBytes() > 0L ? (int) Math.round((width - 2) * progress.fraction()) : 0;

        context.fill(left, top, left + width, top + BAR_HEIGHT, border);
        context.fill(left + 1, top + 1, left + width - 1, top + BAR_HEIGHT - 1, background);
        if (filledWidth > 0) {
            context.fill(left + 1, top + 1, left + 1 + filledWidth, top + BAR_HEIGHT - 1, fill);
        }
    }

    private void drawRow(DrawContext context, int left, int y, int width, String label, String value, int color) {
        context.drawTextWithShadow(textRenderer, Text.literal(label), left, y, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.literal(trimToWidth(value, width - 112)), left + 112, y, color);
    }

    private String formatProgressBytes() {
        if (progress.totalBytes() <= 0L) {
            return formatBytes(progress.processedBytes()) + " / считается...";
        }

        return formatBytes(progress.processedBytes()) + " / " + formatBytes(progress.totalBytes());
    }

    private String fileCounters() {
        return "новые " + progress.newFiles()
                + ", изменённые " + progress.changedFiles()
                + ", удалённые " + progress.deletedFiles();
    }

    private String speedText() {
        double bytesPerSecond = bytesPerSecond();
        if (bytesPerSecond <= 0.0D) {
            return "считается...";
        }

        return formatBytes(bytesPerSecond) + "/с";
    }

    private String etaText() {
        double bytesPerSecond = bytesPerSecond();
        if (progress.totalBytes() <= 0L || progress.processedBytes() <= 0L || bytesPerSecond <= 0.0D) {
            return "считается...";
        }

        long remainingBytes = Math.max(0L, progress.totalBytes() - progress.processedBytes());
        if (remainingBytes == 0L) {
            return "0 сек";
        }

        long seconds = Math.max(1L, (long) Math.ceil(remainingBytes / bytesPerSecond));
        if (seconds < 60L) {
            return seconds + " сек";
        }

        long minutes = seconds / 60L;
        long restSeconds = seconds % 60L;
        if (minutes < 60L) {
            return minutes + " мин " + restSeconds + " сек";
        }

        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        return hours + " ч " + restMinutes + " мин";
    }

    private double bytesPerSecond() {
        if (progress.processedBytes() <= 0L) {
            return 0.0D;
        }

        double elapsedSeconds = Math.max(0.001D, (System.nanoTime() - stageStartedNanos) / 1_000_000_000.0D);
        return progress.processedBytes() / elapsedSeconds;
    }

    private String currentFileText(int maxWidth) {
        if (progress.currentFile().isBlank()) {
            return "-";
        }

        return trimToWidth(progress.currentFile(), maxWidth);
    }

    private String formatBytes(double bytes) {
        String[] units = {"Б", "КБ", "МБ", "ГБ"};
        double value = Math.max(0.0D, bytes);
        int unit = 0;

        while (value >= 1024.0D && unit < units.length - 1) {
            value /= 1024.0D;
            unit++;
        }

        if (unit == 0 || value >= 100.0D) {
            return String.format(Locale.ROOT, "%.0f %s", value, units[unit]);
        }

        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
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

    private String friendlyMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @FunctionalInterface
    public interface ProgressOperation {
        SyncResult run(Consumer<SyncProgress> progress, BooleanSupplier isCancelled);
    }
}
