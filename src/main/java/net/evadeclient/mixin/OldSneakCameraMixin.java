package net.evadeclient.mixin;

import net.evadeclient.module.visuals.OldAnimations;
import net.evadeclient.util.Guard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 Animations "1.7 Sneak Camera": when you sneak or stand up, the camera goes straight to your eye height instead
 * of gliding there over a few ticks. Only the camera moves; your real eye height (what the server and your
 * crosshair target use) is untouched, and vanilla already reaches the same height a moment later.
 */
@Mixin(Camera.class)
public abstract class OldSneakCameraMixin {
    @Shadow private float cameraY;
    @Shadow private float lastCameraY;
    @Shadow private Entity focusedEntity;

    @Inject(method = "updateEyeHeight", at = @At("TAIL"))
    private void evade$instantSneak(CallbackInfo ci) {
        Guard.run(OldAnimations.class, "sneak camera", () -> {
            Entity e = this.focusedEntity;
            if (e == null || e != MinecraftClient.getInstance().player || OldAnimations.with(o -> o.oldSneak) == null) return;
            this.cameraY = this.lastCameraY = e.getStandingEyeHeight();
        });
    }
}
