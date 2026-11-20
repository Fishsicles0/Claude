package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.combat.Reach;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "getEntityInteractionRange", at = @At("RETURN"), cancellable = true)
    private void evade$entityReach(CallbackInfoReturnable<Double> cir) {
        Reach reach = reach();
        if (reach != null) cir.setReturnValue(Math.max(cir.getReturnValue(), reach.entityRange()));
    }

    @Inject(method = "getBlockInteractionRange", at = @At("RETURN"), cancellable = true)
    private void evade$blockReach(CallbackInfoReturnable<Double> cir) {
        Reach reach = reach();
        if (reach != null && reach.blockRangeEnabled()) cir.setReturnValue(Math.max(cir.getReturnValue(), reach.blockRange()));
    }

    private Reach reach() {
        // Only the local player on the client; never the integrated server's copy.
        if ((Object) this != MinecraftClient.getInstance().player || EvadeClient.modules == null) return null;
        Reach reach = EvadeClient.modules.get(Reach.class);
        return reach != null && reach.isEnabled() ? reach : null;
    }

    /** SafeWalk: behave as if sneaking at ledges so you can't walk off. */
    @Inject(method = "clipAtLedge", at = @At("HEAD"), cancellable = true)
    private void evade$safeWalk(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != MinecraftClient.getInstance().player || EvadeClient.modules == null) return;
        var sw = EvadeClient.modules.get(net.evadeclient.module.movement.SafeWalk.class);
        var sc = EvadeClient.modules.get(net.evadeclient.module.movement.Scaffold.class);
        if ((sw != null && sw.isEnabled()) || (sc != null && sc.isEnabled() && sc.safeWalk())) cir.setReturnValue(true);
    }
}
