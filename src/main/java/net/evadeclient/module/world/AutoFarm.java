package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Harvests fully grown crops around you and replants the farmland with seeds from your hotbar. */
public class AutoFarm extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4, 1, 6, 0.5));
    private final BooleanSetting replant = add(new BooleanSetting("Replant", true));
    private final NumberSetting perTick = add(new NumberSetting("Actions/Tick", 2, 1, 8, 1));

    public AutoFarm() { super("AutoFarm", "Harvests and replants crops.", Category.WORLD); }

    @Override
    public void onTick() {
        int r = (int) Math.ceil(range.get()), done = 0;
        BlockPos c = mc.player.getBlockPos();
        for (BlockPos p : BlockPos.iterate(c.add(-r, -2, -r), c.add(r, 2, r))) {
            if (done >= perTick.getInt()) return;
            if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > range.get() * range.get()) continue;
            var s = mc.world.getBlockState(p);
            boolean mature = (s.getBlock() instanceof CropBlock crop && crop.isMature(s))
                    || (s.getBlock() instanceof NetherWartBlock && s.get(NetherWartBlock.AGE) >= 3);
            if (mature) {
                mc.interactionManager.attackBlock(p.toImmutable(), Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
                done++;
            } else if (replant.get() && s.isAir()) {
                var below = mc.world.getBlockState(p.down());
                int seed = -1;
                if (below.isOf(Blocks.FARMLAND)) seed = InvUtil.findHotbar(st -> st.isOf(Items.WHEAT_SEEDS) || st.isOf(Items.CARROT)
                        || st.isOf(Items.POTATO) || st.isOf(Items.BEETROOT_SEEDS));
                else if (below.isOf(Blocks.SOUL_SAND)) seed = InvUtil.findHotbar(st -> st.isOf(Items.NETHER_WART));
                if (seed == -1) continue;
                int prev = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(seed);
                BlockPos soil = p.down().toImmutable();
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(soil).add(0, 0.5, 0), Direction.UP, soil, false));
                mc.player.getInventory().setSelectedSlot(prev);
                done++;
            }
        }
    }
}
