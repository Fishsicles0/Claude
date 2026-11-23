package net.evadeclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.mods.Freelook;
import net.evadeclient.module.visuals.NoRender;
import net.evadeclient.module.visuals.PackTweaks;
import net.evadeclient.module.visuals.Scoreboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    /**
     * Full-screen overlays (the carved pumpkin view, powder snow frost): NoRender's Pumpkin Blur hides the pumpkin
     * outright, and Pack Tweaks' Pumpkin Overlay % / Freezing Overlay % at 0 hide theirs.
     */
    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noPumpkin(DrawContext ctx, Identifier texture, float opacity, CallbackInfo ci) {
        if (texture.getPath().contains("pumpkin") && NoRender.hide(n -> n.pumpkin)) ci.cancel();
        else if (PackTweaks.overlayPercent(texture) <= 0) ci.cancel();
    }

    /** Pack Tweaks Pumpkin Overlay % / Freezing Overlay %: vanilla's opacity times the percentage. */
    @ModifyArg(method = "renderOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/ColorHelper;getWhite(F)I"),
            index = 0)
    private float evade$overlayOpacity(float opacity, @Local(argsOnly = true) Identifier texture) {
        int pct = PackTweaks.overlayPercent(texture);
        return pct >= 100 ? opacity : opacity * pct / 100f;
    }

    @Inject(method = "renderNauseaOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noNausea(DrawContext ctx, float strength, CallbackInfo ci) {
        if (NoRender.hide(n -> n.nausea)) ci.cancel();
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noPortal(DrawContext ctx, float strength, CallbackInfo ci) {
        if (NoRender.hide(n -> n.nausea)) ci.cancel();
    }

    /**
     * Freelook in first person (Perspective First Person or Keep Current): the camera looks away from where the player aims, so vanilla's
     * crosshair would point at nothing. The custom Crosshair hides itself the same way.
     */
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void evade$freelookCrosshair(DrawContext ctx, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (EvadeClient.modules == null) return;
        Freelook fl = EvadeClient.modules.get(Freelook.class);
        if (fl != null && fl.active() && MinecraftClient.getInstance().options.getPerspective().isFirstPerson()) ci.cancel();
    }

    /**
     * Scoreboard: while the module is on, Evade draws the sidebar itself (at the element's spot, with its layout
     * settings), in vanilla's place: same layer, same objective (team colour slot first, then the sidebar), and like
     * vanilla it still shows with F3 open and goes with F1.
     */
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$sbHead(DrawContext ctx, ScoreboardObjective obj, CallbackInfo ci) {
        Scoreboard sb = Scoreboard.active();
        if (sb == null) return;
        ci.cancel();
        try {
            sb.drawSidebar(ctx, obj);
        } catch (RuntimeException e) {
            ModuleManager.crashed(sb, e, "HUD");
        }
    }
}
