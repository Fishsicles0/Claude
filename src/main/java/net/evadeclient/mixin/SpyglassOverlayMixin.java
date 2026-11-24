package net.evadeclient.mixin;

import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pack Tweaks Spyglass Overlay %: the spyglass scope and the black around it drawn fainter (0 hides them). At 100 the
 * vanilla code runs untouched. The zoom itself is vanilla's either way.
 */
@Mixin(InGameHud.class)
public abstract class SpyglassOverlayMixin {
    @Shadow @Final private static Identifier SPYGLASS_SCOPE;

    @Inject(method = "renderSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void evade$spyglass(DrawContext context, float scale, CallbackInfo ci) {
        int pct = PackTweaks.spyglassPercent();
        if (pct >= 100) return;
        ci.cancel();
        if (pct <= 0) return;
        // vanilla's layout, with every colour at pct opacity
        float a = pct / 100f;
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        float side = Math.min(w, h);
        float k = Math.min(w / side, h / side) * scale;
        int sw = MathHelper.floor(side * k), sh = MathHelper.floor(side * k);
        int x1 = (w - sw) / 2, y1 = (h - sh) / 2, x2 = x1 + sw, y2 = y1 + sh;
        int black = ColorHelper.withAlpha(a, 0);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, SPYGLASS_SCOPE, x1, y1, 0f, 0f, sw, sh, sw, sh, ColorHelper.getWhite(a));
        context.fill(RenderPipelines.GUI, 0, y2, w, h, black);
        context.fill(RenderPipelines.GUI, 0, 0, w, y1, black);
        context.fill(RenderPipelines.GUI, 0, y1, x1, y2, black);
        context.fill(RenderPipelines.GUI, x2, y1, w, y2, black);
    }
}
