package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.combat.Hitbox;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /** Vanilla's crosshair raycast expands every entity box by this margin. */
    @Inject(method = "getTargetingMargin", at = @At("RETURN"), cancellable = true)
    private void evade$hitbox(CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        if (EvadeClient.modules == null || !self.getEntityWorld().isClient()) return;
        Hitbox hitbox = EvadeClient.modules.get(Hitbox.class);
        if (hitbox != null && hitbox.isEnabled() && hitbox.applies(self)) {
            cir.setReturnValue(cir.getReturnValue() + hitbox.expansion());
        }
    }
}
