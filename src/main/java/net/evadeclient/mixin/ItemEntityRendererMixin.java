package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ItemPhysics;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity, ItemEntityRenderState> {
    /** Vanilla draws a stack's copies side by side (flat items) when the model is at most this thick. */
    private static final float FLAT_MAX_THICKNESS = 0.0625f;
    /** How far vanilla scatters the copies of a block-like stack, each way on every axis. */
    private static final float SCATTER = 0.15f;
    /** Gap between a flat item and the ground, so it doesn't flicker into the block below. */
    private static final float GROUND_GAP = 0.02f;

    @Shadow @Final private Random random;

    protected ItemEntityRendererMixin(EntityRendererFactory.Context ctx) { super(ctx); }

    /**
     * Item Physics: lay flat items on the ground and stand blocks still, with no bob or spin. The item itself is
     * drawn here; the rest of vanilla's entity rendering (name tag, leash) still runs through super.render.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$physics(ItemEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState cam, CallbackInfo ci) {
        if (!ItemPhysics.on() || state.itemRenderState.isEmpty()) return;
        Box box = state.itemRenderState.getModelBoundingBox();
        int copies = Math.max(1, state.renderedAmount);
        float thickness = (float) box.getLengthZ();
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(state.uniqueOffset * 6.2831855f));
        if (thickness <= FLAT_MAX_THICKNESS) {
            // Vanilla stacks the copies along the model's Z, centred, each 1.5 thicknesses apart. Laid down, that
            // pile points up: lift it so the bottom copy rests just above the ground.
            float spacing = thickness * 1.5f;
            matrices.translate(0, GROUND_GAP + spacing * (copies - 1) / 2f + thickness / 2f, 0);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
            // Centre the model on the entity (the ground pose sits it 1/8 block up, which lying down became 1/8 sideways).
            matrices.translate(-(float) ((box.minX + box.maxX) / 2), -(float) ((box.minY + box.maxY) / 2), -(float) ((box.minZ + box.maxZ) / 2));
        } else {
            matrices.translate(0, (float) -box.minY - lowestCopyOffset(state.seed, copies), 0);
        }
        ItemEntityRenderer.render(matrices, queue, state.light, state, random, box);
        matrices.pop();
        super.render(state, matrices, queue, cam);
        ci.cancel();
    }

    /**
     * How far below the first copy vanilla will scatter the lowest extra copy of a block-like stack (0 or negative).
     * Replays vanilla's random offsets: ItemEntityRenderer.render re-seeds with the same seed before drawing.
     */
    private float lowestCopyOffset(long seed, int copies) {
        if (copies <= 1) return 0;
        random.setSeed(seed);
        float lowest = 0;
        for (int i = 1; i < copies; i++) {
            random.nextFloat();
            float dy = (random.nextFloat() * 2.0f - 1.0f) * SCATTER;
            random.nextFloat();
            lowest = Math.min(lowest, dy);
        }
        return lowest;
    }

    /** Performance: draw one item for a dropped stack instead of a pile of copies. */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("RETURN"))
    private void evade$singleItem(ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci) {
        var p = net.evadeclient.module.mods.Performance.active();
        if (p != null && p.singleItems.get()) state.renderedAmount = 1;
    }
}
