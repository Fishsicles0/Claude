package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.Targets;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;

/** Drops an anvil onto the nearest enemy: places it a few blocks above their head so it falls on them. */
public class AutoAnvil extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 5, 2, 6, 0.5));
    private final NumberSetting height = add(new NumberSetting("Height", 4, 1, 8, 1));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final NumberSetting delay = add(new NumberSetting("Delay", 4, 0, 20, 1));
    private final Targets targets = new Targets();
    private int wait;

    public AutoAnvil() {
        super("AutoAnvil", "Drops anvils onto the nearest enemy.", Category.COMBAT);
        targets.addTo(this::add);
    }

    @Override
    public void onTick() {
        if (wait-- > 0) return;
        int slot = BlockUtil.findBlockInHotbar(s -> s.isOf(Items.ANVIL) || s.isOf(Items.CHIPPED_ANVIL) || s.isOf(Items.DAMAGED_ANVIL));
        if (slot == -1) return;
        net.minecraft.entity.LivingEntity t = mc.world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class,
                mc.player.getBoundingBox().expand(range.get() + height.get()), targets::isValid).stream()
                .min(Comparator.comparingDouble(mc.player::distanceTo)).orElse(null);
        if (t == null) return;
        // Find the lowest clear spot above their head, up to the chosen height, that we can reach.
        for (int h = 1; h <= height.getInt(); h++) {
            BlockPos pos = t.getBlockPos().up(1 + h);
            if (!BlockUtil.canPlace(pos, Blocks.ANVIL.getDefaultState()) || !BlockUtil.withinReach(pos)) continue;
            if (BlockUtil.place(pos, slot, rotate.get(), true)) { wait = delay.getInt(); return; }
        }
    }
}
