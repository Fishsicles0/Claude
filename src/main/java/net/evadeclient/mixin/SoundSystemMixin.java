package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.mods.SoundTweaks;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sound Tweaks: scales (or mutes) individual sounds by their sound event id. Never makes anything louder. */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {
    @Unique
    private static final String PLAY = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;";

    /** A sound turned all the way down isn't started at all (no channel, no subtitle). */
    @Inject(method = PLAY, at = @At("HEAD"), cancellable = true)
    private void evade$skipMuted(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
        if (sound != null && SoundTweaks.volume(sound.getId()) <= 0f) cir.setReturnValue(SoundSystem.PlayResult.NOT_STARTED);
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
        return sound == null ? volume : volume * SoundTweaks.volume(sound.getId());
    }
}
