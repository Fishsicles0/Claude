package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NoRender;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private static void evade$noFire(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        if (NoRender.hide(n -> n.fire)) ci.cancel();
    }

    @org.spongepowered.asm.mixin.Unique private static boolean evade$fireLowered;

    /** Pack Tweaks: draw the first-person fire overlay lower on screen. */
    @Inject(method = "renderFireOverlay", at = @At("HEAD"))
    private static void evade$lowFireHead(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        evade$fireLowered = false;
        var pt = net.evadeclient.module.visuals.PackTweaks.active();
        if (pt == null || !pt.lowFire.get() || NoRender.hide(n -> n.fire)) return;
        matrices.push();
        matrices.translate(0, -pt.fireHeight.get(), 0);
        evade$fireLowered = true;
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    private static void evade$lowFireTail(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        if (evade$fireLowered) { matrices.pop(); evade$fireLowered = false; }
    }

    /** Pack Tweaks Fire Opacity: fade the first-person fire (vanilla draws both flames at 90%). */
    @ModifyArg(method = "renderFireOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/VertexConsumer;color(FFFF)Lnet/minecraft/client/render/VertexConsumer;"), index = 3)
    private static float evade$fireOpacity(float alpha) {
        var pt = net.evadeclient.module.visuals.PackTweaks.active();
        return pt == null ? alpha : pt.fireOpacity.getFloat() / 100f;
    }

    /** Clear Water: skip the blue underwater screen overlay. */
    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private static void evade$noWaterOverlay(net.minecraft.client.MinecraftClient client, MatrixStack matrices, VertexConsumerProvider consumers, CallbackInfo ci) {
        var cw = net.evadeclient.module.visuals.ClearWater.active();
        if (cw != null && cw.overlay.get()) ci.cancel();
    }
}
