package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.block.Block;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** When you break an ore or log by hand, also mines the connected blocks of the same type. */
public class VeinMiner extends Module {
    private final NumberSetting limit = add(new NumberSetting("Max Blocks", 32, 4, 128, 4));
    private final BooleanSetting oresOnly = add(new BooleanSetting("Ores & Logs Only", true));

    private BlockPos watching;
    private Block watchingBlock;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private BlockPos current;

    public VeinMiner() { super("VeinMiner", "Mines whole ore veins and trees.", Category.WORLD); }

    @Override
    public void onTick() {
        // 1. remember what you're breaking by hand
        if (queue.isEmpty() && current == null && mc.options.attackKey.isPressed() && mc.crosshairTarget instanceof BlockHitResult b) {
            var s = mc.world.getBlockState(b.getBlockPos());
            if (!s.isAir() && (!oresOnly.get() || isVeinBlock(s.getBlock()))) { watching = b.getBlockPos(); watchingBlock = s.getBlock(); }
        }
        // 2. when it breaks, collect the vein
        if (watching != null && mc.world.getBlockState(watching).isAir()) {
            collect(watching, watchingBlock);
            watching = null;
        }
        // 3. mine the queue one block at a time
        if (current != null && mc.world.getBlockState(current).isAir()) current = null;
        while (current == null && !queue.isEmpty()) {
            BlockPos p = queue.poll();
            if (mc.world.getBlockState(p).getBlock() == watchingBlock && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= 25) current = p;
        }
        if (current != null) {
            mc.interactionManager.updateBlockBreakingProgress(current, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void collect(BlockPos start, Block block) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty() && queue.size() < limit.getInt()) {
            BlockPos p = open.poll();
            for (BlockPos n : BlockPos.iterate(p.add(-1, -1, -1), p.add(1, 1, 1))) {
                BlockPos im = n.toImmutable();
                if (seen.add(im) && mc.world.getBlockState(im).getBlock() == block) { queue.add(im); open.add(im); }
            }
        }
    }

    private static boolean isVeinBlock(Block b) {
        var s = b.getDefaultState();
        return s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.GOLD_ORES)
                || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES) || s.isIn(BlockTags.LAPIS_ORES)
                || s.isIn(BlockTags.REDSTONE_ORES) || s.isIn(BlockTags.COPPER_ORES) || b == net.minecraft.block.Blocks.NETHER_QUARTZ_ORE
                || b == net.minecraft.block.Blocks.ANCIENT_DEBRIS || b == net.minecraft.block.Blocks.NETHER_GOLD_ORE;
    }

    @Override
    public String getSuffix() { return queue.isEmpty() ? null : String.valueOf(queue.size()); }
}
