package ru.maronamo.simpleworldsync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;
import ru.maronamo.simpleworldsync.ui.WorldPreparationScreen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoSyncController {
    private final Set<String> bypassLaunchFolders = ConcurrentHashMap.newKeySet();

    public boolean consumeLaunchBypass(String folderName) {
        return bypassLaunchFolders.remove(folderName);
    }

    public void prepareAndLaunch(WorldListWidget.WorldEntry entry, LevelSummary level) {
        MinecraftClient client = MinecraftClient.getInstance();
        String folderName = level.getName();
        String worldName = level.getDisplayName();
        Screen parent = client.currentScreen;

        Runnable launchAction = () -> launchWithoutCheck(folderName, entry);
        client.setScreen(new WorldPreparationScreen(parent, folderName, worldName, launchAction));
    }

    private void launchWithoutCheck(String folderName, WorldListWidget.WorldEntry entry) {
        bypassLaunchFolders.add(folderName);
        entry.play();
    }
}
