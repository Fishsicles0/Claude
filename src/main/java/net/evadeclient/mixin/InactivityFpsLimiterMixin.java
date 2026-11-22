package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InactivityFpsLimiter.class)
public abstract class InactivityFpsLimiterMixin {
    @Shadow private long lastInputTime;

    /**
     * Performance: idle at a low frame rate while the window isn't focused, or after a while with no input
     * (AFK). Nothing on screen needs a high frame rate then, so this saves power and heat; it snaps back the
     * moment you touch a key or the mouse.
     */
    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void evade$idleCap(CallbackInfoReturnable<Integer> cir) {
        Performance p = Performance.active();
        if (p == null) return;
        int fps = cir.getReturnValueI();
        if (p.unfocusedCap.get() && !MinecraftClient.getInstance().isWindowFocused()) fps = Math.min(fps, p.unfocusedFps.getInt());
        if (p.afkCap.get() && Util.getMeasuringTimeMs() - lastInputTime > p.afkSeconds.get() * 1000) fps = Math.min(fps, p.afkFps.getInt());
        if (fps != cir.getReturnValueI()) cir.setReturnValue(fps);
    }

    /** Test hook: pretend the last input was {@code ms} ago. */
    public void evade$pretendIdle(long ms) { lastInputTime = Util.getMeasuringTimeMs() - ms; }
}
