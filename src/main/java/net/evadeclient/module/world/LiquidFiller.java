package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/** Fills water and lava source blocks in reach with cheap blocks from your hotbar (cobblestone, dirt, netherrack...). */
public class LiquidFiller extends Module {
    private final BooleanSetting water = add(new BooleanSetting("Water", true));
    private final BooleanSetting lava = add(new BooleanSetting("Lava", true));
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 2, 1, 8, 1));

    public LiquidFiller() {
        super("LiquidFiller", "Fills nearby water and lava with blocks.", Category.WORLD);
    }

    static int fillerSlot() {
        return BlockUtil.findBlockInHotbar(s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.DIRT) || s.isOf(Items.NETHERRACK)
                || s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.STONE) || s.isOf(Items.ANDESITE) || s.isOf(Items.DIORITE)
                || s.isOf(Items.GRANITE) || s.isOf(Items.TUFF) || s.isOf(Items.BLACKSTONE) || s.isOf(Items.END_STONE));
    }

    @Override
    public void onTick() {
        int slot = fillerSlot();
        if (slot == -1) return;
        int r = (int) Math.ceil(mc.player.getBlockInteractionRange());
        BlockPos c = mc.player.getBlockPos();
        List<BlockPos> sources = StreamSupport.stream(BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r)).spliterator(), false)
                .map(BlockPos::toImmutable).filter(this::wanted).filter(BlockUtil::withinReach)
                .sorted(Comparator.comparingDouble(p -> p.getSquaredDistance(mc.player.getEyePos()))).toList();
        int placed = 0;
        for (BlockPos p : sources) {
            if (placed >= perTick.getInt()) break;
            if (mc.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(p))) continue;
            if (BlockUtil.place(p, slot, true, true) || airPlace(p, slot)) placed++;
        }
    }

    private boolean wanted(BlockPos p) {
        var f = mc.world.getFluidState(p);
        if (!f.isStill()) return false;
        return (water.get() && f.isIn(FluidTags.WATER)) || (lava.get() && f.isIn(FluidTags.LAVA));
    }

    /** Clicks the liquid block itself when nothing solid is next to it. */
    private boolean airPlace(BlockPos p, int slot) {
        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);
        var res = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(p.toCenterPos(), Direction.UP, p, false));
        mc.player.getInventory().setSelectedSlot(prev);
        return res.isAccepted();
    }
}
