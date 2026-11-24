package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.evadeclient.module.visuals.OldAnimations;
import net.evadeclient.util.Guard;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.7 Animations "1.7 Hurt Tilt": the hurt shake always tilts the same way, as before damage had a direction. Camera only. */
@Mixin(GameRenderer.class)
public abstract class OldHurtTiltMixin {
    @ModifyExpressionValue(method = "tiltViewWhenHurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/LivingEntity;getDamageTiltYaw()F"))
    private float evade$oldHurtTilt(float yaw) {
        return Guard.getFloat(OldAnimations.class, "hurt tilt", () -> OldAnimations.with(o -> o.oldHurtTilt) != null ? 0f : yaw, yaw);
    }
}
