package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Boxes in your head with obsidian (and a roof) so nobody can drop crystals or anvils on you. */
public class SelfTrap extends Module {
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 3, 1, 8, 1));
    private final BooleanSetting roof = add(new BooleanSetting("Roof", true));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));

    public SelfTrap() {
        super("SelfTrap", "Surrounds your head with obsidian.", Category.PVP);
    }

    @Override
    public void onTick() {
        int slot = Surround.slot();
        if (slot == -1) return;
        int placed = 0;
        for (BlockPos pos : positions(mc.player.getBlockPos())) {
            if (placed >= perTick.getInt()) break;
            if (!BlockUtil.canPlace(pos, Blocks.OBSIDIAN.getDefaultState())) continue;
            if (BlockUtil.place(pos, slot, rotate.get(), true)) placed++;
        }
    }

    List<BlockPos> positions(BlockPos feet) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos head = feet.up();
        for (Direction d : Direction.Type.HORIZONTAL) {
            out.add(feet.offset(d)); // feet ring first so the head ring has support
            out.add(head.offset(d));
        }
        if (roof.get()) {
            out.add(head.up().offset(Direction.NORTH)); // support for the roof block
            out.add(head.up());
        }
        return out;
    }
}
