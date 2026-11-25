package net.evadeclient.mixin;

import net.evadeclient.cosmetics.CosmeticsEngine;
import net.evadeclient.cosmetics.Emotes;
import net.evadeclient.cosmetics.RenderData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmetics: an emote poses the player model on top of vanilla's angles (only while one plays). */
@Mixin(PlayerEntityModel.class)
public abstract class CosmeticsEmotePoseMixin {
    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void evade$emote(PlayerEntityRenderState state, CallbackInfo ci) {
        RenderData d = CosmeticsEngine.data(state);
        if (d != null && d.emote != null) Emotes.apply((PlayerEntityModel) (Object) this, d.emote);
    }
}
