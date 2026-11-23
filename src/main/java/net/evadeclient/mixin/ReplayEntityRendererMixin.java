package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replays: hide chosen players and mobs, and name tags. */
@Mixin(EntityRenderer.class)
public abstract class ReplayEntityRendererMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z", at = @At("HEAD"), cancellable = true)
    private void evade$hide(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (ReplayOverrides.hidden(entity)) cir.setReturnValue(false);
    }

    @Inject(method = "hasLabel(Lnet/minecraft/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true)
    private void evade$hideLabel(Entity entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (ReplayOverrides.hideNametags()) cir.setReturnValue(false);
    }
}
