package ru.maronamo.simpleworldsync.mixin;

import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldListWidget.WorldEntry.class)
public interface WorldEntryAccessor {
    @Accessor("level")
    LevelSummary simpleworldsync$getLevel();
}
