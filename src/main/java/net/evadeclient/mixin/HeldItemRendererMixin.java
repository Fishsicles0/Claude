package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.evadeclient.module.visuals.ItemView;
import net.evadeclient.module.visuals.OldAnimations;
import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Shadow protected abstract void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress);

    private static final String RENDER_FIRST_PERSON =
            "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V";

    /**
     * Applied right after vanilla's own push, so each only moves this hand's item (the main and off hand share the
     * matrix stack) and is undone by vanilla's pop.
     * <ul>
     *   <li>1.7 item position: hold items a little lower, like older versions. Maps keep their own position: they're
     *       drawn big and centred, and lowering them hides the bottom of the map.</li>
     *   <li>Pack Tweaks Low Shield: a shield (either hand, idle or blocking) sits lower, so it covers less of the view.</li>
     * </ul>
     */
    @Inject(method = RENDER_FIRST_PERSON, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/math/MatrixStack;push()V", ordinal = 0, shift = At.Shift.AFTER))
    private void evade$oldPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress,
                                   ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                   CallbackInfo ci) {
        OldAnimations o = OldAnimations.active();
        if (o != null && o.oldPosition.get() && !item.isEmpty() && !item.contains(DataComponentTypes.MAP_ID))
            matrices.translate(0, -o.lower.getFloat(), 0);
        PackTweaks pt = PackTweaks.active();
        if (pt != null && pt.lowShield.get() && item.getItem() instanceof ShieldItem)
            matrices.translate(0, -pt.shieldLower.getFloat(), 0);
        ItemView iv = ItemView.active();
        if (iv != null && !item.contains(DataComponentTypes.MAP_ID)) iv.applyOffset(matrices, hand, evade$rightArm(player, hand));
    }

    private static boolean evade$rightArm(AbstractClientPlayerEntity player, Hand hand) {
        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        return arm == Arm.RIGHT;
    }

    /**
     * Skips a hand's item before anything is pushed: Item View's Shield (Hide / Hide When Idle), and the off-hand shield
     * while 1.7 Sword Block draws the sword blocking instead. Drawing only; the shield still blocks.
     */
    @Inject(method = RENDER_FIRST_PERSON, at = @At("HEAD"), cancellable = true)
    private void evade$hideHand(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress,
                                ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                CallbackInfo ci) {
        if (item.isEmpty() || !(player instanceof ClientPlayerEntity cp)) return;
        ItemView iv = ItemView.active();
        if (iv != null && iv.hides(cp, hand, item)) { ci.cancel(); return; }
        if (hand == Hand.OFF_HAND && item.getItem() instanceof ShieldItem && OldAnimations.swordBlocking(cp)) ci.cancel();
    }

    /** Item View Equip Animation off: held items never dip (switching items or after an attack). Drawing only. */
    @ModifyVariable(method = RENDER_FIRST_PERSON, at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private float evade$noEquipDip(float equipProgress) {
        ItemView iv = ItemView.active();
        return iv != null && !iv.equipAnimation.get() ? 0f : equipProgress;
    }

    /** Item View Swing Speed: the first-person swing drawn on its own clock; the game's swing is left alone. */
    @ModifyExpressionValue(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getHandSwingProgress(F)F"))
    private float evade$visualSwing(float real, float tickProgress, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                    ClientPlayerEntity player, int light) {
        ItemView iv = ItemView.active();
        if (iv == null) return real;
        return iv.visualSwing(real, ((LivingEntitySwingInvoker) player).evade$swingDuration());
    }

    /**
     * Right before the item is drawn (after every pose and swing transform; vanilla's pop undoes all of it): the 1.7
     * sword-block, rod and bow poses, Item View's rotation and size, then Pack Tweaks Off-Hand Totem Size % (a totem
     * in the off hand drawn smaller, scaled around the hand).
     */
    @Inject(method = RENDER_FIRST_PERSON, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"))
    private void evade$smallOffhandTotem(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress,
                                         ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                         CallbackInfo ci) {
        boolean right = evade$rightArm(player, hand);
        net.evadeclient.module.visuals.OldPoses.apply(matrices, player, hand, item, right, tickDelta);
        ItemView iv = ItemView.active();
        if (iv != null) iv.applyItemTransform(matrices, hand, right);
        if (hand != Hand.OFF_HAND || !item.isOf(Items.TOTEM_OF_UNDYING)) return;
        PackTweaks pt = PackTweaks.active();
        if (pt == null) return;
        float k = pt.offhandTotem.getFloat() / 100f;
        if (k < 0.999f) matrices.scale(k, k, k);
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
