package net.evadeclient.mixin;

import net.minecraft.client.sound.SoundExecutor;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundSystem.class)
public interface SoundSystemAccessor {
    @Accessor("taskQueue") SoundExecutor evade$taskQueue();
    @Accessor("started") boolean evade$started();
    @Accessor("channel") net.minecraft.client.sound.Channel evade$channel();
}
