package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.Fullbright;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleOption.class)
public abstract class SimpleOptionMixin {
    /** Fullbright: report a high gamma to the lightmap without touching your saved setting. */
    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private void evade$gamma(CallbackInfoReturnable<Object> cir) {
        if (EvadeClient.modules == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null || (Object) this != mc.options.getGamma()) return;
        Fullbright fb = EvadeClient.modules.get(Fullbright.class);
        if (fb != null && fb.isEnabled()) cir.setReturnValue(fb.gamma());
    }
}
