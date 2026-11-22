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
        if (!texture.getPath().contains("pumpkin")) return;
        var pt = net.evadeclient.module.visuals.PackTweaks.active();
        if (NoRender.hide(n -> n.pumpkin) || (pt != null && pt.pumpkin.get())) ci.cancel();
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

    @org.spongepowered.asm.mixin.Unique private boolean evade$sbScaled;

    /** Scoreboard: hide it, or scale it around its right-centre anchor. */
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$sbHead(DrawContext ctx, net.minecraft.scoreboard.ScoreboardObjective obj, CallbackInfo ci) {
        evade$sbScaled = false;
        var sb = net.evadeclient.module.visuals.Scoreboard.active();
        if (sb == null) return;
        if (sb.hide.get()) { ci.cancel(); return; }
        float s = sb.scale.get().floatValue();
        if (Math.abs(s - 1f) < 0.01f) return;
        float ax = ctx.getScaledWindowWidth(), ay = ctx.getScaledWindowHeight() / 2f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(ax, ay);
        ctx.getMatrices().scale(s, s);
        ctx.getMatrices().translate(-ax, -ay);
        evade$sbScaled = true;
    }

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("RETURN"))
    private void evade$sbTail(DrawContext ctx, net.minecraft.scoreboard.ScoreboardObjective obj, CallbackInfo ci) {
        if (evade$sbScaled) { ctx.getMatrices().popMatrix(); evade$sbScaled = false; }
    }

    /** Scoreboard: swap the score number format for a blank one, hiding the red numbers. */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "method_55439", at = @At("HEAD"), argsOnly = true)
    private net.minecraft.scoreboard.number.NumberFormat evade$hideNumbers(net.minecraft.scoreboard.number.NumberFormat fmt) {
        var sb = net.evadeclient.module.visuals.Scoreboard.active();
        return sb != null && sb.hideNumbers.get() ? net.minecraft.scoreboard.number.BlankNumberFormat.INSTANCE : fmt;
    }
}
