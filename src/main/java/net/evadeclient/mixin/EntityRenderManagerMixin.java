package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.evadeclient.util.Guard;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {
    /**
     * Performance: skip entities beyond the culling distance. Asked only about entities the game was about to draw
     * (in range and on screen), so it can only ever shorten vanilla's own limit, and its counter only counts real
     * savings.
     */
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void evade$cull(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        Guard.run(Performance.class, "entity culling", () -> {
            if (!cir.getReturnValueZ()) return;
            Performance p = Performance.active();
            if (p != null && p.cullEntity(entity, x, y, z)) cir.setReturnValue(false);
        });
    }

    /** Performance "Hide Fire On Mobs": burning mobs skip their fire layer (players still show theirs). */
    @Inject(method = "render", at = @At("HEAD"))
    private void evade$mobFire(net.minecraft.client.render.entity.state.EntityRenderState state,
                               net.minecraft.client.render.state.CameraRenderState camera, double x, double y, double z,
                               net.minecraft.client.util.math.MatrixStack matrices,
                               net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                               org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        Guard.run(Performance.class, "mob fire", () -> {
            if (!state.onFire || state instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState) return;
            Performance p = Performance.active();
            if (p != null && p.hideMobFire()) state.onFire = false;
        });
    }
}
