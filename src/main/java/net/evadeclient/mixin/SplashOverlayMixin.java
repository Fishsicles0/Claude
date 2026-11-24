package net.evadeclient.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.evadeclient.gui.EvadeSplash;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

/**
 * Evade's loading screen. The game's own screen keeps doing its job (fading, layering, finishing the load); Evade
 * swaps its parts: the background colour, the logo and the progress bar (see {@link EvadeSplash}).
 */
@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin {
    @Shadow private float progress;
    @Unique private boolean evade$logoDrawn;

    /** The background: Evade's dark instead of the brand red (or the monochrome option's black). */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
    private int evade$background(IntSupplier brand) {
        return EvadeSplash.BACKGROUND;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void evade$frame(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        evade$logoDrawn = false;
    }

    /** The logo is drawn in two halves: Evade's logo takes the first, the second is skipped. */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIIIII)V"))
    private void evade$logo(DrawContext ctx, RenderPipeline pipeline, Identifier id, int x, int y, float u, float v,
                            int w, int h, int rw, int rh, int tw, int th, int color) {
        if (evade$logoDrawn) return;
        evade$logoDrawn = true;
        EvadeSplash.drawLogo(ctx, ctx.getScaledWindowWidth() / 2, y, h, color);
    }

    @Inject(method = "renderProgressBar", at = @At("HEAD"), cancellable = true)
    private void evade$bar(DrawContext ctx, int minX, int minY, int maxX, int maxY, float opacity, CallbackInfo ci) {
        EvadeSplash.drawBar(ctx, minX, minY, maxX, maxY, opacity, progress);
        ci.cancel();
    }
}
