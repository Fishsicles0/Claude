package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.Targets;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Boxes the nearest enemy in with obsidian (sides at feet and head height, plus a roof). */
public class AutoTrap extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 6, 0.5));
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 3, 1, 8, 1));
    private final BooleanSetting roof = add(new BooleanSetting("Roof", true));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final Targets targets = new Targets();

    public AutoTrap() {
        super("AutoTrap", "Traps the nearest enemy in obsidian.", Category.PVP);
    }

    @Override
    public void onTick() {
        int slot = Surround.slot();
        if (slot == -1) return;
        PlayerEntity t = mc.world.getPlayers().stream()
                .filter(p -> targets.isValid(p) && mc.player.distanceTo(p) <= range.get())
                .min(Comparator.comparingDouble(p -> mc.player.distanceTo(p))).orElse(null);
        if (t == null) return;
        int placed = 0;
        for (BlockPos pos : positions(t.getBlockPos())) {
            if (placed >= perTick.getInt()) break;
            if (!BlockUtil.withinReach(pos) || !BlockUtil.canPlace(pos, Blocks.OBSIDIAN.getDefaultState())) continue;
            if (BlockUtil.place(pos, slot, rotate.get(), true)) placed++;
        }
    }

    private List<BlockPos> positions(BlockPos feet) {
        List<BlockPos> out = new ArrayList<>();
        for (Direction d : Direction.Type.HORIZONTAL) {
            out.add(feet.offset(d).down());
            out.add(feet.offset(d));
            out.add(feet.offset(d).up());
        }
        if (roof.get()) out.add(feet.up(2));
        return out;
    }
}
