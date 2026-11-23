package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayAudio;
import net.minecraft.client.sound.Source;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Slow motion in replays: every sound's pitch follows the replay speed, so it slows and deepens with the picture. */
@Mixin(Source.class)
public abstract class ReplaySourceMixin implements ReplayAudio.ScaledSource {
    @Shadow @Final private int pointer;
    @Unique private float evade$basePitch = 1f;

    @Inject(method = "setPitch", at = @At("HEAD"), cancellable = true)
    private void evade$scaledPitch(float pitch, CallbackInfo ci) {
        evade$basePitch = pitch;
        AL10.alSourcef(pointer, AL10.AL_PITCH, pitch * ReplayAudio.timeScale());
        ci.cancel();
    }

    @Override
    public void evade$rescale() {
        AL10.alSourcef(pointer, AL10.AL_PITCH, evade$basePitch * ReplayAudio.timeScale());
    }
}
