package net.evadeclient.mixin;

import net.evadeclient.util.RenderScale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render Scale: marks the world section of a frame. It starts right before renderWorld and ends right after the fog
 * buffer rotates, i.e. after the entity-outline blit, the vanilla post effect (spider/creeper/invert) and Motion Blur
 * (which runs just before that call), and before the HUD, screens and toasts are drawn at full resolution.
 * The panorama screenshot calls renderWorld directly, outside render(), so it is never scaled.
 */
@Mixin(GameRenderer.class)
public abstract class RenderScaleGameRendererMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V"))
    private void evade$renderScaleBegin(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        if (RenderScale.enabled()) RenderScale.beginWorld(client);
    }

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/fog/FogRenderer;rotate()V", shift = At.Shift.AFTER))
    private void evade$renderScaleEnd(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        if (RenderScale.inScaledPass()) RenderScale.endWorld(client);
    }
}
