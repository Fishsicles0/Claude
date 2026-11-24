package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pack Tweaks No Map Bobbing: while you hold a filled map, the hand (and so the map) skips view bobbing. The world's
 * own bobbing is a separate call and is left alone.
 */
@Mixin(GameRenderer.class)
public abstract class MapBobbingMixin {
    @WrapWithCondition(method = "renderHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;bobView(Lnet/minecraft/client/util/math/MatrixStack;F)V"))
    private boolean evade$stillMap(GameRenderer self, MatrixStack matrices, float tickProgress) {
        return !PackTweaks.stillMap();
    }
}
