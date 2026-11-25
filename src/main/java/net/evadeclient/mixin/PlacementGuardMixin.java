package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.BreakGuard;
import net.evadeclient.module.mods.PlacementLock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placement Lock and Break Guard: both only hold back your own clicks before anything is sent to the server, so a
 * held-back placement or break simply never happens. Nothing here places, breaks, turns or speeds anything up.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class PlacementGuardMixin {
    @Unique private BlockPos evade$pendingPlace;

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void evade$lockPlacement(ClientPlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        evade$pendingPlace = null;
        if (EvadeClient.modules == null) return;
        PlacementLock lock = EvadeClient.modules.get(PlacementLock.class);
        if (lock == null || !lock.isEnabled()) return;
        if (!lock.allows(player, hand, hit)) {
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }
        evade$pendingPlace = PlacementLock.placementTarget(player, hand, hit);
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void evade$placed(ClientPlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        BlockPos target = evade$pendingPlace;
        evade$pendingPlace = null;
        if (target == null || EvadeClient.modules == null || !cir.getReturnValue().isAccepted()) return;
        PlacementLock lock = EvadeClient.modules.get(PlacementLock.class);
        if (lock != null) lock.placed(target, hit.getSide(), player.getHorizontalFacing());
    }

    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void evade$guardAttack(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (!evade$breakAllowed(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void evade$guardProgress(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (!evade$breakAllowed(pos)) cir.setReturnValue(false);
    }

    @Unique
    private static boolean evade$breakAllowed(BlockPos pos) {
        if (EvadeClient.modules == null) return true;
        BreakGuard guard = EvadeClient.modules.get(BreakGuard.class);
        return guard == null || guard.allows(net.minecraft.client.MinecraftClient.getInstance().player, pos);
    }
}
