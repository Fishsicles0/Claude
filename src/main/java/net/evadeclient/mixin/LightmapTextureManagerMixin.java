package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.visuals.Fullbright;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fullbright. When the lightmap is rebuilt, vanilla reads the Brightness option with
 * {@code options.getGamma().getValue().floatValue()}; Fullbright raises that one value. The option itself is never
 * written, so options.txt keeps your Brightness and the vanilla slider works as usual.
 *
 * <p>The lightmap is only rebuilt when it is marked dirty (once a tick in vanilla; performance mods skip ticks whose
 * inputs, the Brightness option among them, haven't changed). Switching Fullbright or its Strength changes none of
 * those inputs, so it marks the lightmap dirty itself, and the change shows on the very next frame.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {
    @Shadow private boolean dirty;

    @Inject(method = "update(F)V", at = @At("HEAD"))
    private void evade$refreshForFullbright(float tickProgress, CallbackInfo ci) {
        if (Fullbright.needsLightmapUpdate()) dirty = true;
    }

    @ModifyExpressionValue(method = "update(F)V",
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/option/GameOptions;getGamma()Lnet/minecraft/client/option/SimpleOption;")),
            at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0))
    private float evade$fullbright(float gamma, @Local(argsOnly = true) float tickProgress) {
        return Fullbright.gamma(gamma, tickProgress);
    }
}
