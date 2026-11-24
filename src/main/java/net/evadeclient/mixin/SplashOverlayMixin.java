package net.evadeclient.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.evadeclient.gui.EvadeSplash;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
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
 * swaps its parts: the background colour, the logo (where Evade draws its whole animated scene) and the progress
 * bar (where Evade draws its bar and text). See {@link EvadeSplash}.
 */
@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin {
    @Shadow @Final private ResourceReload reload;
    @Shadow @Final private boolean reloading;
    @Shadow private long reloadCompleteTime;
    @Unique private boolean evade$logoDrawn;

    /** The background: Evade's dark instead of the brand red (a near black with the monochrome option). */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
    private int evade$background(IntSupplier brand) {
        return EvadeSplash.background();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void evade$frame(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        evade$logoDrawn = false;
    }

    /** The logo is drawn in two halves: Evade's scene takes the first (its colour carries the fade), the second is skipped. */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIIIIII)V"))
    private void evade$logo(DrawContext ctx, RenderPipeline pipeline, Identifier id, int x, int y, float u, float v,
                            int w, int h, int rw, int rh, int tw, int th, int color) {
        if (evade$logoDrawn) return;
        evade$logoDrawn = true;
        EvadeSplash.drawScene(ctx, this, (color >>> 24) / 255f, reloading, reloadCompleteTime > -1L, reload.getProgress());
    }

    @Inject(method = "renderProgressBar", at = @At("HEAD"), cancellable = true)
    private void evade$bar(DrawContext ctx, int minX, int minY, int maxX, int maxY, float opacity, CallbackInfo ci) {
        EvadeSplash.drawBar(ctx, this, opacity, reloading, reloadCompleteTime > -1L, reload.getProgress());
        ci.cancel();
    }
}
