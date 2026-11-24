package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.mods.SoundTweaks;
import net.evadeclient.util.Guard;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sound Tweaks: scales (or mutes) individual sounds by their sound event id; Performance: turns every sound down
 * while tabbed out. Never makes anything louder.
 */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {
    @Unique
    private static final String PLAY = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;";

    /** A sound turned all the way down isn't started at all (no channel, no subtitle). */
    @Inject(method = PLAY, at = @At("HEAD"), cancellable = true)
    private void evade$skipMuted(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
        Guard.run(SoundTweaks.class, "muted sound", () -> {
            if (sound != null && SoundTweaks.volume(sound.getId()) <= 0f) cir.setReturnValue(SoundSystem.PlayResult.NOT_STARTED);
        });
    }

    /** The volume a sound starts at. */
    @WrapOperation(method = PLAY, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sound/SoundSystem;getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F"))
    private float evade$scaleStart(SoundSystem self, float volume, SoundCategory category, Operation<Float> original,
                                   @Local(argsOnly = true) SoundInstance sound) {
        float v = original.call(self, volume, category);
        return sound == null ? v : v * SoundTweaks.volume(sound.getId());
    }

    /** The volume of sounds already playing (ticking and looping sounds, and when a vanilla slider moves). */
    @ModifyReturnValue(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"))
    private float evade$scaleLive(float volume, @Local(argsOnly = true) SoundInstance sound) {
        return Guard.getFloat(SoundTweaks.class, "volume", () -> sound == null ? volume : volume * SoundTweaks.volume(sound.getId()), volume);
    }

    /**
     * Performance "Volume When Tabbed Out %": every sound, starting or playing, is scaled while the window isn't
     * focused. This is the one place both paths meet (the SoundInstance overload calls it), so nothing is scaled
     * twice; the factor is 1 when focused or off. Your volume sliders and options.txt are never touched, and even at
     * 0 % a sound started while tabbed out still plays (inaudibly), so looping sounds are back when you return.
     */
    @ModifyReturnValue(method = "getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F", at = @At("RETURN"))
    private float evade$backgroundVolume(float volume) {
        return Guard.getFloat(Performance.class, "background volume", () -> {
            float factor = Performance.backgroundVolumeFactor();
            return factor == 1f ? volume : volume * factor;
        }, volume);
    }
}
