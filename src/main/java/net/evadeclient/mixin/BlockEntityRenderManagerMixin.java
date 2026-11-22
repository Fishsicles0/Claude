package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderManager.class)
public abstract class BlockEntityRenderManagerMixin {
    /** Performance: don't build render state for far chests, signs, banners, etc. (vanilla returns null here too). */
    @Inject(method = "getRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void evade$cull(E be, float tickDelta,
            ModelCommandRenderer.CrumblingOverlayCommand crumbling, CallbackInfoReturnable<S> cir) {
        Performance p = Performance.active();
        if (p != null && p.cullBlockEntity(be.getPos())) cir.setReturnValue(null);
    }
}
