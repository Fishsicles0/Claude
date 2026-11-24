package net.evadeclient.mixin;

import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pack Tweaks Hide Arrows Stuck In You: your own player is drawn without stuck arrows and bee stingers. */
@Mixin(PlayerEntityRenderer.class)
public abstract class StuckArrowsMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"))
    private void evade$hideStuck(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (PackTweaks.hidesStuckIn(entity)) {
            state.stuckArrowCount = 0;
            state.stingerCount = 0;
        }
    }
}
