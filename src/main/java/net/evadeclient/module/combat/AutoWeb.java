package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.Targets;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;

/** Places cobwebs on the nearest target's feet (and head) to slow them down. */
public class AutoWeb extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 6, 0.5));
    private final BooleanSetting head = add(new BooleanSetting("Head Too", false));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final Targets targets = new Targets();

    public AutoWeb() {
        super("AutoWeb", "Traps the nearest enemy in cobwebs.", Category.PVP);
        targets.addTo(this::add);
    }

    @Override
    public void onTick() {
        int slot = BlockUtil.findBlockInHotbar(s -> s.isOf(Items.COBWEB));
        if (slot == -1) return;
        double r = range.get() * range.get();
        LivingEntity t = null;
        double best = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!targets.isValid(e)) continue;
            double d = mc.player.squaredDistanceTo(e);
            if (d <= r && d < best) { best = d; t = (LivingEntity) e; }
        }
        if (t == null) return;
        BlockPos feet = t.getBlockPos();
        for (BlockPos p : head.get() ? new BlockPos[]{feet, feet.up()} : new BlockPos[]{feet}) {
            if (!mc.world.getBlockState(p).isReplaceable() || mc.world.getBlockState(p).isOf(Blocks.COBWEB)) continue;
            if (BlockUtil.place(p, slot, rotate.get(), true)) return; // one per tick
        }
    }
}
