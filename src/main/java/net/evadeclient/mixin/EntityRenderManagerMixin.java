package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {
    /** Performance: skip entities beyond the culling distance. */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void evade$cull(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        Performance p = Performance.active();
        if (p != null && p.cullEntity(entity, x, y, z)) cir.setReturnValue(false);
    }
}
