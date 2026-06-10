package ru.maronamo.simpleworldsync.mixin;

import net.minecraft.client.gui.screen.world.EditWorldScreen;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EditWorldScreen.class)
public interface EditWorldScreenAccessor {
    @Accessor("storageSession")
    LevelStorage.Session simpleworldsync$getStorageSession();
}
