package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.Freelook;
import net.evadeclient.module.visuals.CameraClip;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    /**
     * Freelook: replace the entity's rotation with the free camera's. Only the two calls that copy the entity's
     * yaw/pitch (minecart and normal path) are wrapped; the front-view flip that follows still works on top of them,
     * and the sleeping view stays vanilla.
     */
    @WrapOperation(method = "update", require = 2, at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 0),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 1)})
    private void evade$freelook(Camera camera, float yaw, float pitch, Operation<Void> original) {
        if (EvadeClient.modules != null) {
            Freelook fl = EvadeClient.modules.get(Freelook.class);
            if (fl != null && fl.active()) {
                original.call(camera, fl.yaw(), fl.pitch());
                return;
            }
        }
        original.call(camera, yaw, pitch);
    }

    /**
     * Camera Distance: scale how far back the third-person camera wants to sit. Vanilla's own wall check still runs
     * on the result, so the camera always stays in front of blocks, and the mount/scale distance is kept.
     */
    @ModifyVariable(method = "clipToSpace", at = @At("HEAD"), argsOnly = true)
    private float evade$distance(float desired) {
        float d = CameraClip.distance();
        return d > 0 ? desired * (d / 4f) : desired;
    }
}
