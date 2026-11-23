package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NoRender;
import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
    /** NoRender Fire Overlay (with Fire: Only When Immune, only while fire can't hurt you). */
    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private static void evade$noFire(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        if (NoRender.hideFire()) ci.cancel();
    }

    @Unique private static boolean evade$fireLowered;

    /** Pack Tweaks: draw the first-person fire overlay lower on screen. */
    @Inject(method = "renderFireOverlay", at = @At("HEAD"))
    private static void evade$lowFireHead(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        evade$fireLowered = false;
        PackTweaks pt = PackTweaks.active();
        if (pt == null || !pt.lowFire.get() || NoRender.hideFire()) return;
        matrices.push();
        matrices.translate(0, -pt.fireHeight.get(), 0);
        evade$fireLowered = true;
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    private static void evade$lowFireTail(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        if (evade$fireLowered) { matrices.pop(); evade$fireLowered = false; }
    }

    /** Pack Tweaks Fire Opacity %: fade the first-person fire (vanilla draws both flames at 90%). Combines with Low Fire. */
    @ModifyArg(method = "renderFireOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/VertexConsumer;color(FFFF)Lnet/minecraft/client/render/VertexConsumer;"), index = 3)
    private static float evade$fireOpacity(float alpha) {
        PackTweaks pt = PackTweaks.active();
        return pt == null ? alpha : pt.fireOpacity.getFloat() / 100f;
    }

    /**
     * NoRender Totem Size %: shrink the totem pop animation. Right after vanilla's own (only) scale, 0.8, so it shrinks
     * in place along its whole flight. Totem Animation (hide) wins: a hidden pop never gets here.
     */
    @Inject(method = "renderFloatingItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V", shift = At.Shift.AFTER))
    private void evade$totemSize(MatrixStack matrices, float tickProgress, OrderedRenderCommandQueue queue, CallbackInfo ci) {
        float k = NoRender.totemScale();
        if (k < 0.999f) matrices.scale(k, k, k);
    }

    /** Clear Water: skip the blue underwater screen overlay (it stays under Blindness/Darkness, like the fog). */
    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private static void evade$noWaterOverlay(net.minecraft.client.MinecraftClient client, MatrixStack matrices, VertexConsumerProvider consumers, CallbackInfo ci) {
        if (net.evadeclient.module.visuals.ClearWater.hideOverlay()) ci.cancel();
    }
}
