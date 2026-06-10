package ru.maronamo.simpleworldsync.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.maronamo.simpleworldsync.SimpleWorldSyncClient;

@Mixin(WorldListWidget.WorldEntry.class)
public abstract class WorldEntryMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void simpleworldsync$prepareBeforePlay(CallbackInfo ci) {
        LevelSummary level = ((WorldEntryAccessor) (Object) this).simpleworldsync$getLevel();
        String folderName = level.getName();

        if (SimpleWorldSyncClient.autoSyncController().consumeLaunchBypass(folderName)) {
            return;
        }

        if (!SimpleWorldSyncClient.incrementalSyncService().isWorldSyncEnabled(folderName)) {
            return;
        }

        ci.cancel();
        SimpleWorldSyncClient.autoSyncController().prepareAndLaunch((WorldListWidget.WorldEntry) (Object) this, level);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void simpleworldsync$renderSyncMarker(
            DrawContext context,
            int index,
            int y,
            int x,
            int entryWidth,
            int entryHeight,
            int mouseX,
            int mouseY,
            boolean hovered,
            float tickDelta,
            CallbackInfo ci
    ) {
        LevelSummary level = ((WorldEntryAccessor) (Object) this).simpleworldsync$getLevel();
        if (!SimpleWorldSyncClient.incrementalSyncService().isWorldSyncEnabled(level.getName())) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        boolean syncFolderUsable = SimpleWorldSyncClient.incrementalSyncService().isSyncFolderUsable();
        String visibleMarker = markerText(textRenderer, level, syncFolderUsable, x, entryWidth);
        int markerWidth = textRenderer.getWidth(visibleMarker);
        int markerX = x + entryWidth - markerWidth - 8;
        int markerY = y + 2;
        int color = syncFolderUsable ? 0x66FF66 : 0xFFD966;

        context.drawTextWithShadow(textRenderer, Text.literal(visibleMarker), markerX, markerY, color);
    }

    private String markerText(TextRenderer textRenderer, LevelSummary level, boolean syncFolderUsable, int x, int entryWidth) {
        String[] variants = syncFolderUsable
                ? new String[]{"✓ Синхронизируется", "✓ Sync", "✓"}
                : new String[]{"⚠ Недоступна", "! Sync", "!"};
        int textLeft = x + 35;
        int titleRight = textLeft + textRenderer.getWidth(level.getDisplayName());
        int rightEdge = x + entryWidth - 8;

        for (String variant : variants) {
            int markerLeft = rightEdge - textRenderer.getWidth(variant);
            if (markerLeft > titleRight + 8) {
                return variant;
            }
        }

        return variants[variants.length - 1];
    }
}
