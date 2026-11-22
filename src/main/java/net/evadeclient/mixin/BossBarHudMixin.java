package net.evadeclient.mixin;

import net.evadeclient.module.visuals.BossBar;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {
    @Unique private boolean evade$scaled;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void evade$head(DrawContext ctx, CallbackInfo ci) {
        evade$scaled = false;
        BossBar b = BossBar.active();
        if (b == null) return;
        if (b.hide.get()) { ci.cancel(); return; }
        float s = b.scale.get().floatValue();
        if (Math.abs(s - 1f) < 0.01f) return;
        float cx = ctx.getScaledWindowWidth() / 2f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cx, 0);
        ctx.getMatrices().scale(s, s);
        ctx.getMatrices().translate(-cx, 0);
        evade$scaled = true;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void evade$tail(DrawContext ctx, CallbackInfo ci) {
        if (evade$scaled) { ctx.getMatrices().popMatrix(); evade$scaled = false; }
    }
}
