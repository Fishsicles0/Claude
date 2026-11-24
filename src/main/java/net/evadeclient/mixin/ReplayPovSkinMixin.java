package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replay player POV: the first-person arms wear the watched player's skin. */
@Mixin(HeldItemRenderer.class)
public abstract class ReplayPovSkinMixin {
    @WrapOperation(method = {"renderArm", "renderArmHoldingItem"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;getSkin()Lnet/minecraft/entity/player/SkinTextures;"))
    private SkinTextures evade$povSkin(AbstractClientPlayerEntity player, Operation<SkinTextures> original) {
        AbstractClientPlayerEntity pov = ReplayOverrides.povPlayer();
        return pov != null ? pov.getSkin() : original.call(player);
    }
}
