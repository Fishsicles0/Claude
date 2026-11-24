package net.evadeclient.mixin;

import net.evadeclient.gui.EvadeSplash;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Evade's loading screen: drawn over the game's own at the end of each loading frame (see {@link EvadeSplash}). */
@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin {
    @Shadow private float progress;
    @Shadow private long reloadCompleteTime;
    @Shadow private long reloadStartTime;
    @Shadow @Final private boolean reloading;

    @Inject(method = "render", at = @At("TAIL"))
    private void evade$splash(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        EvadeSplash.render(ctx, progress, reloadCompleteTime, reloadStartTime, reloading);
    }
}
