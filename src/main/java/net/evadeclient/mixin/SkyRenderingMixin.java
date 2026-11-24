package net.evadeclient.mixin;

import net.evadeclient.module.mods.TimeChanger;
import net.evadeclient.util.Guard;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.MoonPhase;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Time Changer's Moon Phase and Bright Stars. Vanilla fills the sky's render state once per frame from the camera's
 * environment attributes; this only adjusts what it just filled in, and only for the Overworld-style sky.
 */
@Mixin(SkyRendering.class)
public abstract class SkyRenderingMixin {
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void evade$timeChangerSky(ClientWorld world, float tickProgress, Camera camera, SkyRenderState state, CallbackInfo ci) {
        Guard.run(TimeChanger.class, "sky", () -> {
            if (state.skybox != DimensionType.Skybox.OVERWORLD) return;
            TimeChanger tc = TimeChanger.active();
            if (tc == null) return;
            MoonPhase phase = tc.moonPhase();
            if (phase != null) state.moonPhase = phase;
            // Stars fade in at dusk (brightness 0 by day): brighten only stars vanilla already shows, never daytime ones.
            if (tc.brightStars() && state.starBrightness > 0f) state.starBrightness = Math.min(1f, state.starBrightness * 4f);
        });
    }
}
