package net.evadeclient.module.visuals;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;

/**
 * 1.7 Animations' held-item poses, applied by HeldItemRendererMixin right before a first-person item is drawn (after
 * vanilla's equip offset and swing). Matrix changes only: nothing about the item, the player or the server changes.
 */
public final class OldPoses {
    private OldPoses() {}

    public static void apply(MatrixStack m, AbstractClientPlayerEntity player, Hand hand, ItemStack item, boolean rightArm, float tickDelta) {
        OldAnimations o = OldAnimations.active();
        if (o == null || item.isEmpty()) return;
        float side = rightArm ? 1 : -1;
        // Sword block: the pose vanilla still has for items that block, on top of the swing, so clicking while
        // blocking plays the old block-hit. Only while your off-hand shield is the thing actually blocking.
        if (hand == Hand.MAIN_HAND && OldAnimations.swordBlocking(player)) {
            m.translate(side * -0.14142136f, 0.08f, 0.14142136f);
            m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-102.25f));
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * 13.365f));
            m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * 78.05f));
            return;
        }
        // Rods: turned around their own centre, tip toward the middle of the screen, as 1.7 drew them.
        if (o.oldRod.get() && (item.isOf(Items.FISHING_ROD) || item.isOf(Items.CARROT_ON_A_STICK) || item.isOf(Items.WARPED_FUNGUS_ON_A_STICK))) {
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
            return;
        }
        // Bow being drawn: tipped toward the crosshair and leaned in, closer to 1.7's pull pose.
        if (o.oldBow.get() && item.getItem() instanceof BowItem && player.isUsingItem() && player.getActiveHand() == hand) {
            m.translate(side * -0.06f, 0.02f, 0f);
            m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * -12f));
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * -8f));
        }
    }
}
