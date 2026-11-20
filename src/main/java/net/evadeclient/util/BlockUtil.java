package net.evadeclient.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/** Block placing helpers shared by Scaffold, Surround, AutoTrap, Printer and friends. */
public final class BlockUtil {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private BlockUtil() {}

    public static boolean isReplaceable(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    /** Free space for a full block: replaceable and no entity standing in it. */
    public static boolean canPlace(BlockPos pos, BlockState as) {
        if (!isReplaceable(pos)) return false;
        return mc.world.canPlace(as, pos, ShapeContext.absent());
    }

    /** A neighbouring solid face we can click to place at {@code pos}, or null. */
    public static Direction findSupport(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState s = mc.world.getBlockState(n);
            if (s.isReplaceable() || s.getOutlineShape(mc.world, n).isEmpty()) continue;
            Vec3d hit = Vec3d.ofCenter(n).add(Vec3d.of(d.getOpposite().getVector()).multiply(0.5));
            double dist = eye.squaredDistanceTo(hit);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        return best;
    }

    public static int findBlockInHotbar(Predicate<ItemStack> p) {
        return InvUtil.findHotbar(s -> s.getItem() instanceof BlockItem && p.test(s));
    }

    /**
     * Place a block from hotbar {@code slot} at {@code pos} by clicking a neighbouring face.
     * Optionally faces the click point first (sends a look packet) and restores the selected slot.
     */
    public static boolean place(BlockPos pos, int slot, boolean rotate, boolean swapBack) {
        Direction support = findSupport(pos);
        if (support == null || slot < 0) return false;
        BlockPos against = pos.offset(support);
        Direction face = support.getOpposite();
        Vec3d hit = Vec3d.ofCenter(against).add(Vec3d.of(face.getVector()).multiply(0.5));
        if (mc.player.getEyePos().squaredDistanceTo(hit) > Math.pow(mc.player.getBlockInteractionRange() + 0.5, 2)) return false;

        PlayerInventory inv = mc.player.getInventory();
        int prev = inv.getSelectedSlot();
        inv.setSelectedSlot(slot);
        if (rotate) {
            float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), hit);
            PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], mc.player.isOnGround(), mc.player.horizontalCollision));
        }
        ActionResult res = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
        if (res.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        if (swapBack) inv.setSelectedSlot(prev);
        return res.isAccepted();
    }

    public static boolean withinReach(BlockPos pos) {
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= Math.pow(mc.player.getBlockInteractionRange(), 2);
    }
}
