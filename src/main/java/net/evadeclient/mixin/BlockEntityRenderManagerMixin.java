package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderManager.class)
public abstract class BlockEntityRenderManagerMixin {
    /**
     * Performance: don't build render state for far chests, signs, banners, etc. (vanilla returns null here too
     * for ones out of range). Renderers that draw outside their block or ask to be seen farther than the default
     * 64 blocks (beacon and end-gateway beams, moving pistons, structure outlines) are always left to vanilla.
     */
    @Inject(method = "getRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void evade$cull(E be, float tickDelta,
            ModelCommandRenderer.CrumblingOverlayCommand crumbling, CallbackInfoReturnable<S> cir) {
        Performance p = Performance.active();
        if (p == null || !p.blockEntityCulling.get()) return;
        BlockEntityRenderer<E, S> renderer = ((BlockEntityRenderManager) (Object) this).get(be);
        if (renderer == null || renderer.rendersOutsideBoundingBox()) return;
        if (p.cullBlockEntity(be.getPos(), renderer.getRenderDistance())) cir.setReturnValue(null);
    }
}
