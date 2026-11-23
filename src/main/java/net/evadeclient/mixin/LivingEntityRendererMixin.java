package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.evadeclient.module.visuals.HitColor;
import net.evadeclient.util.EntityVisualsDuck;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hit Color's choice of overlay texel for hurt and dying entities: which row (mob or player colour) and which fade
 * step. Every living entity's body and feature layers ask {@link LivingEntityRenderer#getOverlay}, so one hook covers
 * them all. Not hurt, or the module off: vanilla's value, untouched.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    /** The render state can't see the entity's hurt timer, so the fade is worked out here and carried on the state. */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
            at = @At("RETURN"))
    private void evade$hurtFade(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (entity.hurtTime > 0 && HitColor.active() && state instanceof EntityVisualsDuck duck) {
            duck.evade$setHurtFade(HitColor.hurtFade(entity.hurtTime, entity.maxHurtTime, tickDelta));
        }
    }

    /** Runs for every living entity and feature layer each frame: a return-value hook, so nothing is allocated. */
    @ModifyReturnValue(method = "getOverlay(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)I",
            at = @At("RETURN"))
    private static int evade$hitColorOverlay(int original, LivingEntityRenderState state, float whiteOverlayProgress) {
        int uv = HitColor.overlay(state, whiteOverlayProgress);
        return uv == -1 ? original : uv;
    }
}
