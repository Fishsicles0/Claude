package net.evadeclient.mixin;

import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Performance "Max Particles": the live particles, one renderer (queue) per texture sheet, so the cap can count
 * them once a tick without building the F3 debug string. Read-only; render thread only.
 */
@Mixin(ParticleManager.class)
public interface ParticleManagerAccessor {
    @Accessor("particles")
    Map<ParticleTextureSheet, ParticleRenderer<?>> evade$particles();
}
