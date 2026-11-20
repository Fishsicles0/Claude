package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.stream.StreamSupport;

/**
 * Breaks blocks around you. "All" mines everything in range, "Selected" only the block type you were looking at
 * when you turned it on, "Flatten" only blocks at or above your feet. Instant in creative, one block at a time in survival.
 */
public class Nuker extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "All", "All", "Selected", "Flatten"));
    private final NumberSetting range = add(new NumberSetting("Range", 4, 1, 6, 0.5));
    private final NumberSetting perTick = add(new NumberSetting("Creative Blocks/Tick", 6, 1, 30, 1));
    private final BooleanSetting showTarget = add(new BooleanSetting("Show Target", true));

    private Block selected;
    private BlockPos breaking;

    public Nuker() { super("Nuker", "Breaks blocks around you.", Category.WORLD); }

    @Override
    protected void onEnable() {
        selected = mc.crosshairTarget instanceof BlockHitResult b ? mc.world.getBlockState(b.getBlockPos()).getBlock() : null;
        breaking = null;
    }

    @Override
    public void onTick() {
        if (breaking != null && (mc.world.getBlockState(breaking).isAir() || !ok(breaking))) breaking = null;
        if (breaking == null) breaking = next();
        if (breaking == null) return;
        if (mc.player.isCreative()) {
            int n = 0;
            var im = (net.evadeclient.mixin.InteractionManagerAccessor) mc.interactionManager;
            while (breaking != null && n++ < perTick.getInt()) {
                im.evade$setBlockBreakingCooldown(0); // creative adds a 5-tick pause after every break
                mc.interactionManager.attackBlock(breaking, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
                breaking = next();
            }
            return;
        }
        mc.interactionManager.updateBlockBreakingProgress(breaking, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private BlockPos next() {
        int r = (int) Math.ceil(range.get());
        BlockPos c = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        return StreamSupport.stream(BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r)).spliterator(), false)
                .map(BlockPos::toImmutable).filter(this::ok)
                .min(Comparator.comparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p)))).orElse(null);
    }

    private boolean ok(BlockPos p) {
        BlockState s = mc.world.getBlockState(p);
        if (s.isAir() || s.getHardness(mc.world, p) < 0 || !s.getFluidState().isEmpty()) return false;
        if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > range.get() * range.get()) return false;
        if (mode.is("Selected") && s.getBlock() != selected) return false;
        return !mode.is("Flatten") || p.getY() >= mc.player.getBlockY();
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (showTarget.get() && breaking != null) ctx.boxOutline(new Box(breaking), 0xFFE11D2E, 2f);
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
