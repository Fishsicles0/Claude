package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InactivityFpsLimiter.class)
public abstract class InactivityFpsLimiterMixin {
    /** Performance: idle at a low frame rate while the window isn't focused (saves power and heat). */
    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void evade$idleCap(CallbackInfoReturnable<Integer> cir) {
        Performance p = Performance.active();
        if (p == null || !p.unfocusedCap.get() || MinecraftClient.getInstance().isWindowFocused()) return;
        cir.setReturnValue(Math.min(cir.getReturnValueI(), p.unfocusedFps.getInt()));
    }
}
