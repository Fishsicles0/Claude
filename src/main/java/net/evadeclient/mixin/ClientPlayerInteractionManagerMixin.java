package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    /**
     * Your own attacks, for the combat timer, Combo Counter and Reach Display. A spectator's "attack" only switches
     * the camera to that entity, so it is not a hit and isn't reported.
     */
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void evade$onAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (player == null || player.isSpectator()) return;
        if (EvadeClient.modules != null) EvadeClient.modules.onAttack(target);
    }

    /**
     * A block you just broke (the client removed it): Saturation's drain estimate adds the exhaustion the server
     * charges for it. Read-only; the break itself is untouched.
     */
    @Inject(method = "breakBlock", at = @At("RETURN"))
    private void evade$onBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) net.evadeclient.module.hud.SaturationHud.onBlockBroken();
    }
}
