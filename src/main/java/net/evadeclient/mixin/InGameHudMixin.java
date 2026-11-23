package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.Freelook;
import net.evadeclient.module.visuals.NoRender;
import net.evadeclient.module.visuals.Scoreboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    /** NoRender: no pumpkin blur over the screen. */
    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$noPumpkin(DrawContext ctx, Identifier texture, float opacity, CallbackInfo ci) {
        if (texture.getPath().contains("pumpkin") && NoRender.hide(n -> n.pumpkin)) ci.cancel();
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
     * Freelook in first person (Third Person off): the camera looks away from where the player aims, so vanilla's
     * crosshair would point at nothing. The custom Crosshair hides itself the same way.
     */
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void evade$freelookCrosshair(DrawContext ctx, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (EvadeClient.modules == null) return;
        Freelook fl = EvadeClient.modules.get(Freelook.class);
        if (fl != null && fl.active() && MinecraftClient.getInstance().options.getPerspective().isFirstPerson()) ci.cancel();
    }

    // ------------------------------------------------------------------ Scoreboard: the sidebar

    /** Whether this sidebar draw pushed a matrix (popped at its end). */
    @Unique private boolean evade$sbPushed;
    /** Whether the sidebar has been moved into place this draw (done at its first fill, the title bar). */
    @Unique private boolean evade$sbPlaced;
    /** Lines in the sidebar being drawn. */
    @Unique private int evade$sbLines;

    /** Hide it (Hide Scoreboard, Hide In Menus, Only In Combat), and start a fresh draw. */
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$sbHead(DrawContext ctx, ScoreboardObjective obj, CallbackInfo ci) {
        evade$sbPushed = false;
        evade$sbPlaced = false;
        evade$sbLines = 0;
        Scoreboard sb = Scoreboard.active();
        if (sb != null && !sb.sidebarAllowed()) ci.cancel();
    }

    /** The lines vanilla is about to draw (at most 15), for the height of the box. */
    @ModifyExpressionValue(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"))
    private Object[] evade$sbCount(Object[] entries) {
        evade$sbLines = entries.length;
        return entries;
    }

    /**
     * The two background fills (title bar, then the lines). The first one is where vanilla's box starts, so the
     * sidebar is moved and scaled onto the Scoreboard element there, before anything is drawn. Background switches
     * the fills off; Background Opacity fades them.
     */
    @WrapOperation(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void evade$sbFill(DrawContext ctx, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        Scoreboard sb = Scoreboard.active();
        if (sb == null) {
            original.call(ctx, x1, y1, x2, y2, color);
            return;
        }
        if (!evade$sbPlaced) {
            evade$sbPlaced = true;
            // The title bar is 9 pixels tall and starts the box; a 1-pixel gap and a 9-pixel row per line follow.
            evade$sbPushed = sb.beginSidebar(ctx, x1, y1, x2 - x1, y2 - y1 + 1 + evade$sbLines * 9);
        }
        if (sb.background.get()) original.call(ctx, x1, y1, x2, y2, sb.backgroundColor(color));
    }

    /** Text Shadow: the title, names and scores (vanilla draws them flat). */
    @ModifyArg(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V"),
            index = 5)
    private boolean evade$sbShadow(boolean shadow) {
        Scoreboard sb = Scoreboard.active();
        return shadow || sb != null && sb.textShadow.get();
    }

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("RETURN"))
    private void evade$sbTail(DrawContext ctx, ScoreboardObjective obj, CallbackInfo ci) {
        if (evade$sbPushed) {
            ctx.getMatrices().popMatrix();
            evade$sbPushed = false;
        }
    }

    /**
     * Hide Numbers: a score drawn as a number (the objective's red numbers, or a score's own styled numbers) is drawn
     * as nothing, so vanilla also drops the gap before it. Fixed text a server puts in the score column stays.
     * {@code method_55439} is the lambda that builds each sidebar line.
     */
    @WrapOperation(method = "method_55439", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/scoreboard/ScoreboardEntry;formatted(Lnet/minecraft/scoreboard/number/NumberFormat;)Lnet/minecraft/text/MutableText;"))
    private MutableText evade$hideNumbers(ScoreboardEntry entry, NumberFormat objectiveFormat, Operation<MutableText> original) {
        Scoreboard sb = Scoreboard.active();
        if (sb != null) {
            NumberFormat own = entry.numberFormatOverride();
            if (sb.hidesNumber(own != null ? own : objectiveFormat)) return Text.empty();
        }
        return original.call(entry, objectiveFormat);
    }
}
