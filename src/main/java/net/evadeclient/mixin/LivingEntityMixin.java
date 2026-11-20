package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.movement.HighJump;
import net.evadeclient.module.movement.Spider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "getJumpVelocity()F", at = @At("RETURN"), cancellable = true)
    private void evade$highJump(CallbackInfoReturnable<Float> cir) {
        if ((Object) this != MinecraftClient.getInstance().player || EvadeClient.modules == null) return;
        HighJump hj = EvadeClient.modules.get(HighJump.class);
        if (hj != null && hj.isEnabled()) cir.setReturnValue(cir.getReturnValue() * hj.multiplier());
    }

    /** Spider: walls count as ladders while you're pushing into them. */
    @Inject(method = "isClimbing", at = @At("RETURN"), cancellable = true)
    private void evade$spider(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != MinecraftClient.getInstance().player || EvadeClient.modules == null) return;
        Spider sp = EvadeClient.modules.get(Spider.class);
        if (sp != null && sp.isEnabled() && MinecraftClient.getInstance().player.horizontalCollision) cir.setReturnValue(true);
    }
}
