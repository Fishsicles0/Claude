package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ItemPhysics;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @Shadow @Final private Random random;

    /** Item Physics: lay flat items on the ground and stand blocks still, with no bob or spin. */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$physics(ItemEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState cam, CallbackInfo ci) {
        if (!ItemPhysics.on() || state.itemRenderState.isEmpty()) return;
        var box = state.itemRenderState.getModelBoundingBox();
        boolean flat = box.maxZ - box.minZ < 0.1;
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(state.uniqueOffset * 6.2831855f));
        if (flat) {
            matrices.translate(0, 0.02f, 0);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
        } else {
            matrices.translate(0, (float) -box.minY, 0);
        }
        ItemEntityRenderer.render(matrices, queue, state.light, state, random, box);
        matrices.pop();
        ci.cancel();
    }

    /** Performance: draw one item for a dropped stack instead of a pile of copies. */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("RETURN"))
    private void evade$singleItem(net.minecraft.entity.ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci) {
        var p = net.evadeclient.module.mods.Performance.active();
        if (p != null && p.singleItems.get()) state.renderedAmount = 1;
    }
}
