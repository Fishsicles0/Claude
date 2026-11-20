package net.evadeclient.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Finds 1x1 "safe holes": an air gap two blocks tall, walled in by blast-proof blocks on all sides and below. */
public final class HoleUtil {
    public enum Type { BEDROCK, OBSIDIAN, NONE }

    private HoleUtil() {}

    public static boolean blastProof(BlockState s) {
        return s.isOf(Blocks.BEDROCK) || s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.CRYING_OBSIDIAN)
                || s.isOf(Blocks.ENDER_CHEST) || s.isOf(Blocks.RESPAWN_ANCHOR) || s.isOf(Blocks.NETHERITE_BLOCK)
                || s.isOf(Blocks.ANCIENT_DEBRIS) || s.isOf(Blocks.REINFORCED_DEEPSLATE);
    }

    public static Type type(BlockPos pos) {
        var w = MinecraftClient.getInstance().world;
        if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) return Type.NONE;
        boolean allBedrock = true;
        List<BlockPos> walls = new ArrayList<>();
        walls.add(pos.down());
        for (Direction d : Direction.Type.HORIZONTAL) walls.add(pos.offset(d));
        for (BlockPos b : walls) {
            BlockState s = w.getBlockState(b);
            if (!blastProof(s)) return Type.NONE;
            if (!s.isOf(Blocks.BEDROCK)) allBedrock = false;
        }
        return allBedrock ? Type.BEDROCK : Type.OBSIDIAN;
    }

    public static List<BlockPos> near(BlockPos center, int h, int v) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(center.add(-h, -v, -h), center.add(h, v, h)))
            if (type(p) != Type.NONE) out.add(p.toImmutable());
        return out;
    }
}
