package net.evadeclient.mixin;

import net.evadeclient.module.client.HudToggle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** End of every frame (world, HUD and screens drawn): HUD Toggle takes a held screenshot from it. */
@Mixin(GameRenderer.class)
public abstract class PerfFrameEndMixin {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("RETURN"))
    private void evade$perfFrameEnd(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        HudToggle.onFrameEnd(MinecraftClient.getInstance().getFramebuffer());
    }
}
