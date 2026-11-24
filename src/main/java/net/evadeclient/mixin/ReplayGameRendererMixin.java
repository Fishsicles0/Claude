package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replays: the camera path moves the camera each frame and sets the field of view. */
@Mixin(GameRenderer.class)
public abstract class ReplayGameRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"))
    private void evade$replayFrame(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        ReplayOverrides.onFrame(tickCounter);
    }

    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
    private void evade$replayFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        float fov = ReplayOverrides.fov();
        if (fov > 0) cir.setReturnValue(fov);
    }
}
