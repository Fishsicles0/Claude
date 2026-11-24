package net.evadeclient.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.util.FramePacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Performance's Smooth Pacing: Evade's even-spaced frame limiter (sleep, then spin) replaces the game's. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemPacingMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void evade$smoothPacing(int fps, CallbackInfo ci) {
        Performance p = Performance.active();
        if (p == null || !p.smoothPacingActive()) return;
        FramePacer.limit(fps);
        ci.cancel();
    }
}
