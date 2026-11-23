package net.evadeclient.mixin;

import net.evadeclient.module.visuals.OldAnimations;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Shadow protected abstract void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress);

    private static final String RENDER_FIRST_PERSON =
            "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V";

    /**
     * 1.7 item position: hold items a little lower, like older versions. Applied right after vanilla's own push, so
     * it only moves this hand's item (the main and off hand share the matrix stack) and is undone by vanilla's pop.
     * Maps keep their own position: they're drawn big and centred, and lowering them hides the bottom of the map.
     */
    @Inject(method = RENDER_FIRST_PERSON, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/math/MatrixStack;push()V", ordinal = 0, shift = At.Shift.AFTER))
    private void evade$oldPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress,
                                   ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                   CallbackInfo ci) {
        OldAnimations o = OldAnimations.active();
        if (o == null || !o.oldPosition.get() || item.isEmpty() || item.contains(DataComponentTypes.MAP_ID)) return;
        matrices.translate(0, -o.lower.getFloat(), 0);
    }

    /**
     * 1.7 swing while eating/drinking: the swing's rotations on top of the eat/drink pose. In 1.7 they came after the
     * hand's equip offset; vanilla applies that offset right after this call, so the swing is wrapped in the same
     * offset and its inverse (equip, swing, un-equip, then vanilla's equip) to pivot around the hand, not the camera.
     */
    @Redirect(method = RENDER_FIRST_PERSON, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;applyEatOrDrinkTransformation(Lnet/minecraft/client/util/math/MatrixStack;FLnet/minecraft/util/Arm;Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/PlayerEntity;)V"))
    private void evade$swingWhileEating(HeldItemRenderer self, MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack, PlayerEntity p,
                                        AbstractClientPlayerEntity player, float td, float pitch, Hand hand, float swingProgress,
                                        ItemStack item, float equipProgress, MatrixStack m2, OrderedRenderCommandQueue queue, int light) {
        ((HeldItemRendererInvoker) self).evade$applyEatOrDrink(matrices, tickDelta, arm, stack, p);
        OldAnimations o = OldAnimations.active();
        if (o == null || !o.swingWhileUsing.get() || swingProgress <= 0) return;
        // Same numbers as HeldItemRenderer.applyEquipOffset.
        float side = arm == Arm.RIGHT ? 1 : -1;
        float x = side * 0.56f, y = -0.52f + equipProgress * -0.6f, z = -0.72f;
        matrices.translate(x, y, z);
        applySwingOffset(matrices, arm, swingProgress);
        matrices.translate(-x, -y, -z);
    }
}
