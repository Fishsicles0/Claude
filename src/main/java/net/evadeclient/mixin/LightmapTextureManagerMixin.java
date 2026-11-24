package net.evadeclient.mixin;

import net.evadeclient.module.visuals.Fullbright;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fullbright. When the lightmap is rebuilt, vanilla writes the shader's inputs one float at a time into the
 * {@code LightmapInfo} uniform buffer, in this order (1.21.11): 0 ambient light factor, 1 sky factor, 2 block factor,
 * 3 night-vision factor, 4 darkness pulse, 5 world darkening (bosses), 6 brightness (the Brightness option minus the
 * Darkness fade). Fullbright raises one of them, depending on its mode: 6 for Gamma, 3 for Night Vision, 0 for Full
 * Light. Nothing is written to the game's options and no status effect is faked, so options.txt keeps your
 * Brightness and vanilla's own Darkness pulse still plays on top.
 *
 * <p>The lightmap is only rebuilt when it is marked dirty (once a tick in vanilla; performance mods skip ticks whose
 * inputs haven't changed). Fullbright's level changes none of those inputs, so while it fades, or right after it
 * was switched or its settings changed, it marks the lightmap dirty itself and the change shows on the next frame.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {
    private static final String PUT_FLOAT =
            "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;";

    @Shadow private boolean dirty;

    /** Every frame (the update is called each frame and returns early unless dirty): step the fade first. */
    @Inject(method = "update(F)V", at = @At("HEAD"))
    private void evade$fullbrightFrame(float tickProgress, CallbackInfo ci) {
        if (Fullbright.frame(tickProgress)) dirty = true;
        if (dirty) Fullbright.building();
    }

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 0))
    private float evade$fullLight(float ambientLight) {
        return Fullbright.ambientLight(ambientLight);
    }

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 3))
    private float evade$nightVision(float nightVision) {
        return Fullbright.nightVision(nightVision);
    }

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 6))
    private float evade$gamma(float brightness) {
        return Fullbright.brightness(brightness);
    }
}
