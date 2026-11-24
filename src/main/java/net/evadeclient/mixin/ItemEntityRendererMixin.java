package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.ItemPhysics;
import net.evadeclient.util.EntityVisualsDuck;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.render.entity.state.ItemStackEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics' drawing of dropped items, and Performance's "one item per stack".
 *
 * Only the item model itself is re-posed: vanilla's render(...) still runs from start to end (push, the item, pop,
 * then name tag and leash through EntityRenderer.render), so labels and other mods' hooks keep working.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    /** How far vanilla scatters the copies of a block-like stack, each way on every axis. */
    @Unique private static final float SCATTER = 0.15f;
    /** Gap between a flat item and the ground, so it doesn't flicker into the block below. */
    @Unique private static final float GROUND_GAP = 0.02f;

    @Shadow @Final private Random random;

    /** Scratch rotation (render thread only). */
    @Unique private final Quaternionf evade$rotation = new Quaternionf();

    /**
     * Item Physics: the item model is drawn in our pose instead of vanilla's bobbing, spinning one. Vanilla pushed a
     * matrix and moved it before this call; going back one level and pushing again gives a clean copy of the
     * entity's own frame (vanilla's pop right after still matches).
     */
    @WrapOperation(method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/ItemEntityRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/ItemStackEntityRenderState;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/Box;)V"))
    private void evade$physics(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, ItemStackEntityRenderState stackState,
                               Random rng, Box box, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
        ItemPhysics physics = ItemPhysics.active();
        if (physics == null) {
            original.call(matrices, queue, light, stackState, rng, box);
            return;
        }
        matrices.pop();
        matrices.push();

        ItemPhysics.Motion motion = state instanceof EntityVisualsDuck duck && duck.evade$itemMotion() instanceof ItemPhysics.Motion m ? m : null;
        float size = physics.size();
        float yaw = physics.yaw(state);
        int copies = Math.max(1, state.renderedAmount);
        float thickness = (float) box.getLengthZ();
        boolean flat = thickness <= ItemPhysics.FLAT_MAX_THICKNESS;

        // Half extents of the model in its resting frame (where the tumble and the yaw apply). A flat item lies down:
        // its pile of copies (vanilla stacks them along Z, centred, 1.5 thicknesses apart) becomes its height.
        float hx = (float) box.getLengthX() / 2f, hy, hz;
        float base;
        if (flat) {
            hy = thickness * 1.5f * (copies - 1) / 2f + thickness / 2f;
            hz = (float) box.getLengthY() / 2f;
            base = GROUND_GAP;
        } else {
            hy = (float) box.getLengthY() / 2f;
            hz = thickness / 2f;
            base = -lowestCopyOffset(state.seed, copies);
        }

        // Lift the model so its lowest point (as it is turned right now) rests on the ground, scaled with it.
        Quaternionf full = evade$rotation.rotationY(yaw);
        boolean tumbling = motion != null && Math.abs(motion.orientation.w) < 0.9999999f;
        if (tumbling) full.premul(motion.orientation);
        matrices.translate(0f, (base + verticalHalfExtent(full, hx, hy, hz)) * size, 0f);

        if (motion != null && motion.floating) {
            float t = state.age, off = state.uniqueOffset;
            matrices.translate(0f, MathHelper.sin(t / 10f + off) * 0.04f, 0f);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.sin(t / 40f + off) * 10f));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(t / 30f) * 6f));
        }
        if (tumbling) matrices.multiply(motion.orientation);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yaw));
        matrices.scale(size, size, size);
        if (flat) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
        // Turn about the model's centre (the ground pose sits a flat item 1/8 block up, which lying down is sideways).
        matrices.translate(-(float) ((box.minX + box.maxX) / 2), -(float) ((box.minY + box.maxY) / 2), -(float) ((box.minZ + box.maxZ) / 2));
        original.call(matrices, queue, light, stackState, rng, box);
    }

    /** Half the height of a box with these half extents, turned by {@code q} (unit): row Y of its rotation matrix. */
    @Unique
    private static float verticalHalfExtent(Quaternionf q, float hx, float hy, float hz) {
        float r10 = 2f * (q.x * q.y + q.w * q.z);
        float r11 = 1f - 2f * (q.x * q.x + q.z * q.z);
        float r12 = 2f * (q.y * q.z - q.w * q.x);
        return Math.abs(r10) * hx + Math.abs(r11) * hy + Math.abs(r12) * hz;
    }

    /**
     * How far below the first copy vanilla will scatter the lowest extra copy of a block-like stack (0 or negative).
     * Replays vanilla's random offsets: ItemEntityRenderer.render re-seeds with the same seed before drawing.
     */
    @Unique
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

    /** Item Physics' per-frame motion, and Performance: draw one item for a dropped stack instead of a pile of copies. */
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("RETURN"))
    private void evade$singleItem(ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci) {
        var p = Performance.active();
        if (p != null && p.singleItems.get()) state.renderedAmount = 1;
        ItemPhysics.update(entity, state, tickDelta);
    }
}
