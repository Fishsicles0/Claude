package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replays, green screen: no sky and no clouds. */
@Mixin(WorldRenderer.class)
public abstract class ReplaySkyMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void evade$noSky(CallbackInfo ci) { if (ReplayOverrides.greenScreen()) ci.cancel(); }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void evade$noClouds(CallbackInfo ci) { if (ReplayOverrides.greenScreen()) ci.cancel(); }
}
