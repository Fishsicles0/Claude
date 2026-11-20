package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.minecraft.block.Block;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

/** Covers dark spots where mobs could spawn with slabs, buttons, pressure plates or carpet from your hotbar. */
public class SpawnProofer extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4, 1, 6, 0.5));
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 2, 1, 6, 1));

    public SpawnProofer() { super("SpawnProofer", "Stops mobs spawning around you.", Category.WORLD); }

    @Override
    public void onTick() {
        int slot = BlockUtil.findBlockInHotbar(s -> {
            Block b = ((BlockItem) s.getItem()).getBlock();
            return b instanceof SlabBlock || b instanceof ButtonBlock || b instanceof PressurePlateBlock || b instanceof CarpetBlock;
        });
        if (slot == -1) return;
        int r = (int) Math.ceil(range.get()), done = 0;
        BlockPos c = mc.player.getBlockPos();
        for (BlockPos p : BlockPos.iterate(c.add(-r, -2, -r), c.add(r, 2, r))) {
            if (done >= perTick.getInt()) return;
            BlockPos up = p.up();
            var floor = mc.world.getBlockState(p);
            if (!floor.isSideSolidFullSquare(mc.world, p, Direction.UP)) continue;
            if (!mc.world.getBlockState(up).isAir() || !mc.world.getBlockState(up.up()).isAir()) continue;
            if (mc.world.getLightLevel(LightType.BLOCK, up) > 0) continue;
            if (!BlockUtil.withinReach(up.toImmutable())) continue;
            if (BlockUtil.place(up.toImmutable(), slot, true, true)) done++;
        }
    }
}
