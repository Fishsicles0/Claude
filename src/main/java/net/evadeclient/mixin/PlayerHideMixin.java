package net.evadeclient.mixin;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player cards' "Hide this player": a player you hid isn't drawn on your screen for the rest of the session. Only
 * takes things away from your own view; nothing is sent anywhere.
 */
@Mixin(EntityRenderManager.class)
public abstract class PlayerHideMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void evade$hidePlayer(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PlayerEntity p && net.evadeclient.social.PlayerCards.isHidden(p.getUuid())) cir.setReturnValue(false);
    }
}
