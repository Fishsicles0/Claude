package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayAudio;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/** While a replay renders with sound, the game's audio goes to a virtual (loopback) device the render reads from. */
@Mixin(SoundEngine.class)
public abstract class ReplaySoundEngineMixin {
    @Shadow private long devicePointer;
    @Shadow private long contextPointer;
    @Shadow private boolean disconnectExtensionPresent;

    @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
    private static void evade$loopback(String specifier, CallbackInfoReturnable<Long> cir) {
        long device = ReplayAudio.openLoopback();
        if (device != 0L) cir.setReturnValue(device);
    }

    @Inject(method = "createAttributes", at = @At("RETURN"), cancellable = true)
    private void evade$loopbackFormat(MemoryStack stack, boolean directional, CallbackInfoReturnable<IntBuffer> cir) {
        if (ReplayAudio.device() != 0L) cir.setReturnValue(ReplayAudio.attributes(stack, cir.getReturnValue()));
    }

    /**
     * Forget the closed device. Otherwise, if the next one fails to open (no speakers plugged in), the game keeps
     * asking the closed device whether it's still connected, and restarts the sound engine every tick.
     */
    @Inject(method = "close", at = @At("TAIL"))
    private void evade$forgetDevice(CallbackInfo ci) {
        devicePointer = 0L;
        contextPointer = 0L;
        disconnectExtensionPresent = false;
    }
}
