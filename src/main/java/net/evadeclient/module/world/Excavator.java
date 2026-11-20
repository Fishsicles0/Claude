package net.evadeclient.module.world;

import net.evadeclient.mixin.InteractionManagerAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.RotationUtil;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.evadeclient.util.PacketHooks;

import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/** Digs out a box in front of you: every mineable block in a width x height x depth region within reach. */
public class Excavator extends Module {
    private final NumberSetting width = add(new NumberSetting("Width", 3, 1, 9, 1));
    private final NumberSetting height = add(new NumberSetting("Height", 3, 1, 9, 1));
    private final NumberSetting depth = add(new NumberSetting("Depth", 4, 1, 16, 1));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));

    public Excavator() {
        super("Excavator", "Digs out a box in front of you.", Category.WORLD);
    }

    /** The region in front of the player, based on facing. */
    List<BlockPos> region() {
        Direction f = mc.player.getHorizontalFacing();
        Direction right = f.rotateYClockwise();
        BlockPos feet = mc.player.getBlockPos().offset(f, 1); // one block ahead, so it digs forward not under you
        int w = width.getInt() / 2;
        BlockPos from = feet.offset(right, -w);
        BlockPos a = from, b = from.offset(right, width.getInt() - 1).offset(f, depth.getInt() - 1).up(height.getInt() - 1);
        return StreamSupport.stream(BlockPos.iterate(a, b).spliterator(), false)
                .map(BlockPos::toImmutable)
                .filter(p -> !mc.world.getBlockState(p).isAir() && mc.world.getBlockState(p).getHardness(mc.world, p) >= 0)
                .filter(BlockUtil::withinReach)
                .sorted(Comparator.comparingDouble(p -> p.getSquaredDistance(mc.player.getEyePos())))
                .toList();
    }

    @Override
    public void onTick() {
        if (mc.currentScreen != null) return;
        List<BlockPos> blocks = region();
        if (blocks.isEmpty()) return;
        BlockPos pos = blocks.getFirst();
        Direction side = BlockUtil.findSupport(pos) != null ? BlockUtil.findSupport(pos).getOpposite() : Direction.UP;
        if (rotate.get()) {
            float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), Vec3d.ofCenter(pos));
            PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], mc.player.isOnGround(), mc.player.horizontalCollision));
        }
        ((InteractionManagerAccessor) mc.interactionManager).evade$setBlockBreakingCooldown(0);
        if (mc.player.isCreative()) mc.interactionManager.attackBlock(pos, side);
        else mc.interactionManager.updateBlockBreakingProgress(pos, side);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    @Override
    public void onRender(RenderContext ctx) {
        for (BlockPos p : region()) ctx.boxOutline(new Box(p), 0x80E11D2E, 1f);
    }

    @Override
    public String getSuffix() { return width.getInt() + "x" + height.getInt() + "x" + depth.getInt(); }
}
