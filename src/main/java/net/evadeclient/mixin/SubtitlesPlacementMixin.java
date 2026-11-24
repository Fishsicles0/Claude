package net.evadeclient.mixin;

import net.evadeclient.module.hud.SoundSubtitlesHud;
import net.evadeclient.module.hud.VanillaTextHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.SubtitlesHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sound subtitles as a HUD element: vanilla's subtitles drawn at the element's spot and scale while it is on. */
@Mixin(SubtitlesHud.class)
public abstract class SubtitlesPlacementMixin {
    @Unique private boolean evade$moved;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void evade$head(DrawContext ctx, CallbackInfo ci) {
        int r = VanillaTextHud.begin(SoundSubtitlesHud.class, ctx);
        if (r < 0) ci.cancel();
        evade$moved = r > 0;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void evade$tail(DrawContext ctx, CallbackInfo ci) {
        if (evade$moved) ctx.getMatrices().popMatrix();
        evade$moved = false;
    }
}
