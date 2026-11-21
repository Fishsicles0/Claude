package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.Freelook;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void evade$freelook(Camera camera, float yaw, float pitch) {
        if (EvadeClient.modules != null) {
            Freelook fl = EvadeClient.modules.get(Freelook.class);
            if (fl != null && fl.active()) {
                ((CameraInvoker) camera).evade$setRotation(fl.yaw(), fl.pitch());
                return;
            }
        }
        ((CameraInvoker) camera).evade$setRotation(yaw, pitch);
    }


    /** CameraClip: skip vanilla's wall check and use our own distance. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void evade$cameraClip(float desired, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        float d = net.evadeclient.module.visuals.CameraClip.distance();
        if (d > 0) cir.setReturnValue(d);
    }
}
