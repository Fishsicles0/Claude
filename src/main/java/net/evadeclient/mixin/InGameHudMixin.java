package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NoRender;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noPumpkin(DrawContext ctx, Identifier texture, float opacity, CallbackInfo ci) {
        if (texture.getPath().contains("pumpkin") && NoRender.hide(n -> n.pumpkin)) ci.cancel();
    }

    @Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noVignette(DrawContext ctx, Entity entity, CallbackInfo ci) {
        if (NoRender.hide(n -> n.vignette)) ci.cancel();
    }

    @Inject(method = "renderNauseaOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noNausea(DrawContext ctx, float strength, CallbackInfo ci) {
        if (NoRender.hide(n -> n.nausea)) ci.cancel();
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noPortal(DrawContext ctx, float strength, CallbackInfo ci) {
        if (NoRender.hide(n -> n.nausea)) ci.cancel();
    }
}
