package net.evadeclient.mixin;

import net.evadeclient.module.hud.ActionBarHud;
import net.evadeclient.module.hud.TitlesHud;
import net.evadeclient.module.hud.VanillaTextHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Titles and Action Bar as HUD elements: while one is on, vanilla's own text is drawn at the element's spot and
 * scale (moved and scaled as a whole, so fades, colours and backgrounds stay vanilla's). Off, nothing changes.
 */
@Mixin(InGameHud.class)
public abstract class HudTextPlacementMixin {
    @Unique private boolean evade$titleMoved, evade$overlayMoved;

    @Inject(method = "renderTitleAndSubtitle", at = @At("HEAD"), cancellable = true)
    private void evade$titleHead(DrawContext ctx, RenderTickCounter tc, CallbackInfo ci) {
        int r = VanillaTextHud.begin(TitlesHud.class, ctx);
        if (r < 0) ci.cancel();
        evade$titleMoved = r > 0;
    }

    @Inject(method = "renderTitleAndSubtitle", at = @At("RETURN"))
    private void evade$titleTail(DrawContext ctx, RenderTickCounter tc, CallbackInfo ci) {
        if (evade$titleMoved) ctx.getMatrices().popMatrix();
        evade$titleMoved = false;
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void evade$overlayHead(DrawContext ctx, RenderTickCounter tc, CallbackInfo ci) {
        int r = VanillaTextHud.begin(ActionBarHud.class, ctx);
        if (r < 0) ci.cancel();
        evade$overlayMoved = r > 0;
    }

    @Inject(method = "renderOverlayMessage", at = @At("RETURN"))
    private void evade$overlayTail(DrawContext ctx, RenderTickCounter tc, CallbackInfo ci) {
        if (evade$overlayMoved) ctx.getMatrices().popMatrix();
        evade$overlayMoved = false;
    }
}
