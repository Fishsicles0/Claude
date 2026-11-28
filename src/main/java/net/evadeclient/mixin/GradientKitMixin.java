package net.evadeclient.mixin;

import net.evadeclient.gui.kit.Button;
import net.evadeclient.render.ui.UiShape;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vertical gradients ({@code DrawContext.fillGradient}: scrims, fades, the in-game menu backdrop) on Evade's own
 * screens go through the shape shader, which dithers them, so they no longer band in 8-bit steps. Elsewhere, in the
 * Classic theme, inside the HUD, on the title screen (its owner routes its veils itself) or without the shader they
 * stay vanilla.
 */
@Mixin(DrawContext.class)
public abstract class GradientKitMixin {
    @Inject(method = "fillGradient(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void evade$ditheredGradient(int x1, int y1, int x2, int y2, int top, int bottom, CallbackInfo ci) {
        if (top == bottom || !Button.evadeScreen() || !UiShape.available()) return;
        // The title screen's veils are full-screen and its frame budget is tight on software GL: the title
        // owner moves them to UiShape deliberately (WP2), so they stay vanilla here.
        if (net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof net.evadeclient.gui.EvadeTitleScreen) return;
        UiShape.shape(x1, y1, x2, y2).vertical(top, bottom).raw().draw((DrawContext) (Object) this);
        ci.cancel();
    }
}
