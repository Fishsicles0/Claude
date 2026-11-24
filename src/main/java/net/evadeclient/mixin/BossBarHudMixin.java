package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.evadeclient.module.visuals.BossBar;
import net.evadeclient.util.Guard;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boss Bar: hide the boss bars, or scale, move down, trim and slim them. Vanilla still draws them. */
@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {
    /** Whether this draw pushed a matrix (popped at its end). */
    @Unique private boolean evade$pushed;
    /** Bars drawn so far this draw (for Max Bars). */
    @Unique private int evade$bars;

    /** Hide, or scale around the top centre of the screen and move down by Y Offset. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void evade$head(DrawContext ctx, CallbackInfo ci) {
        Guard.run(BossBar.class, "layout", () -> {
            evade$pushed = false;
            evade$bars = 0;
            BossBar b = BossBar.active();
            if (b == null) return;
            if (b.hide.get()) { ci.cancel(); return; }
            float s = b.scale.getFloat();
            int dy = b.yOffset.getInt();
            if (Math.abs(s - 1f) < 0.001f && dy == 0) return;
            float cx = ctx.getScaledWindowWidth() / 2f;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx, dy);
            ctx.getMatrices().scale(s, s);
            ctx.getMatrices().translate(-cx, 0);
            evade$pushed = true;
        });
    }

    /**
     * Max Bars. After each bar vanilla checks whether the next would start past a third of the screen height (read
     * here) and stops if so; once Max Bars have been drawn a height of 0 makes that check stop it too.
     */
    @ModifyExpressionValue(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;getScaledWindowHeight()I"))
    private int evade$maxBars(int height) {
        return Guard.getInt(BossBar.class, "max bars", () -> {
            BossBar b = BossBar.active();
            if (b == null) return height;
            return ++evade$bars >= b.maxBars.getInt() ? 0 : height;
        }, height);
    }

    /** Mode Text Only: no bar, just the boss's name. */
    @WrapWithCondition(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/BossBarHud;renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;)V"))
    private boolean evade$drawBar(BossBarHud self, DrawContext ctx, int x, int y, net.minecraft.entity.boss.BossBar bar) {
        BossBar b = BossBar.active();
        return b == null || !b.mode.is("Text Only");
    }

    /** Mode Bar Only: no name above the bar. */
    @WrapWithCondition(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"))
    private boolean evade$drawName(DrawContext ctx, TextRenderer tr, Text name, int x, int y, int color) {
        BossBar b = BossBar.active();
        return b == null || !b.mode.is("Bar Only");
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void evade$tail(DrawContext ctx, CallbackInfo ci) {
        if (evade$pushed) {
            ctx.getMatrices().popMatrix();
            evade$pushed = false;
        }
    }
}
