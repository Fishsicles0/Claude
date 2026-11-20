package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.movement.NoSlow;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    /** NoSlow: the 80% slowdown while using an item checks isUsingItem(); answer "no" there. */
    @Redirect(method = "applyMovementSpeedFactors", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"))
    private boolean evade$noSlow(ClientPlayerEntity self) {
        NoSlow ns = EvadeClient.modules == null ? null : EvadeClient.modules.get(NoSlow.class);
        if (ns != null && ns.isEnabled() && ns.items.get()) return false;
        return self.isUsingItem();
    }

    /** NoSlow "Keep Sprint": using an item no longer stops your sprint. */
    @Redirect(method = "isBlockedFromSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"))
    private boolean evade$keepSprint(ClientPlayerEntity self) {
        NoSlow ns = EvadeClient.modules == null ? null : EvadeClient.modules.get(NoSlow.class);
        if (ns != null && ns.isEnabled() && ns.sprint.get()) return false;
        return self.isUsingItem();
    }
}
