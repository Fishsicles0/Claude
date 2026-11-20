package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks spots where hostile mobs can spawn (block light 0 on a solid top face).
 * Red = can spawn any time, yellow = only at night (open sky).
 */
public class LightOverlay extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 12, 4, 32, 1));
    private final List<BlockPos> always = new ArrayList<>(), night = new ArrayList<>();
    private int refresh;

    public LightOverlay() {
        super("LightOverlay", "Shows where mobs can spawn.", Category.VISUALS);
    }

    @Override
    public void onTick() {
        if (refresh-- > 0) return;
        refresh = 10;
        always.clear();
        night.clear();
        int r = range.getInt();
        BlockPos c = mc.player.getBlockPos();
        for (BlockPos p : BlockPos.iterate(c.add(-r, -6, -r), c.add(r, 6, r))) {
            if (!spawnable(p)) continue;
            (mc.world.getLightLevel(LightType.SKY, p) > 0 ? night : always).add(p.toImmutable());
        }
    }

    public boolean spawnable(BlockPos p) {
        BlockState at = mc.world.getBlockState(p);
        if (!at.getCollisionShape(mc.world, p).isEmpty() || !at.getFluidState().isEmpty()) return false;
        if (!mc.world.getBlockState(p.up()).getCollisionShape(mc.world, p.up()).isEmpty()) return false;
        BlockPos down = p.down();
        BlockState below = mc.world.getBlockState(down);
        if (!below.isSideSolidFullSquare(mc.world, down, Direction.UP)) return false;
        if (below.isOf(net.minecraft.block.Blocks.BEDROCK) || below.isIn(BlockTags.ICE)) return false;
        return mc.world.getLightLevel(LightType.BLOCK, p) == 0;
    }

    @Override
    public void onRender(RenderContext ctx) {
        for (BlockPos p : always) cross(ctx, p, 0xFFFF3B4C);
        for (BlockPos p : night) cross(ctx, p, 0xFFFFB020);
    }

    private void cross(RenderContext ctx, BlockPos p, int c) {
        double y = p.getY() + 0.02;
        ctx.line(new net.minecraft.util.math.Vec3d(p.getX() + 0.15, y, p.getZ() + 0.15), new net.minecraft.util.math.Vec3d(p.getX() + 0.85, y, p.getZ() + 0.85), c, 1.5f);
        ctx.line(new net.minecraft.util.math.Vec3d(p.getX() + 0.85, y, p.getZ() + 0.15), new net.minecraft.util.math.Vec3d(p.getX() + 0.15, y, p.getZ() + 0.85), c, 1.5f);
    }

    public int count() { return always.size() + night.size(); }
}
