package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.InactivityFpsLimit;
import net.minecraft.client.option.InactivityFpsLimiter;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InactivityFpsLimiter.class)
public abstract class InactivityFpsLimiterMixin {
    @Shadow private long lastInputTime;
    @Shadow private int maxFps;
    @Shadow @Final private GameOptions options;
    @Shadow @Final private MinecraftClient client;

    /**
     * Performance: idle at a low frame rate while the window isn't focused, or after a while with no input
     * (AFK), and even lower than the game's own 10 FPS while minimised. Nothing on screen needs a high frame rate
     * then, so this saves power and heat; it snaps back the moment you touch a key or the mouse.
     *
     * Every cap only ever lowers the frame rate. The tabbed-out and AFK caps work on top of the game's own "Reduce
     * FPS when" choice and never against it: with "Reduce FPS when: Minimized" (you want the game running smoothly
     * in the background, e.g. on a second screen) neither applies. The minimised cap applies either way, as the
     * game's own does, and in a world never goes below {@link Performance#MIN_WORLD_FPS}, so the game keeps
     * running all 20 ticks a second (it runs at most 10 per frame).
     */
    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void evade$idleCap(CallbackInfoReturnable<Integer> cir) {
        Performance p = Performance.active();
        if (p == null) return;
        int vanilla = cir.getReturnValueI();
        int fps = vanilla;
        if (client.getWindow().isMinimized()) fps = Math.min(fps, p.minimisedCap(client.world != null));
        if (options.getInactivityFpsLimit().getValue() == InactivityFpsLimit.AFK) {
            if (p.unfocusedCap.get() && !client.isWindowFocused()) fps = Math.min(fps, p.unfocusedFps.getInt());
            if (p.afkCap.get() && Util.getMeasuringTimeMs() - lastInputTime > p.afkSeconds.get() * 1000) fps = Math.min(fps, p.afkFps.getInt());
        }
        // Adaptive Quality ignores frames an idle cap holds down (the game's or Evade's).
        p.noteIdleCap(fps < maxFps);
        if (fps != vanilla) cir.setReturnValue(fps);
    }

    /** Test hook: pretend the last input was {@code ms} ago. */
    public void evade$pretendIdle(long ms) { lastInputTime = Util.getMeasuringTimeMs() - ms; }
}
