package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Replays: camera roll (tilting the view) from the camera path. */
@Mixin(Camera.class)
public abstract class ReplayCameraMixin {
    @ModifyArg(method = "setRotation(FF)V", at = @At(value = "INVOKE", target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"), index = 2)
    private float evade$roll(float roll) {
        return roll + ReplayOverrides.roll();
    }
}
