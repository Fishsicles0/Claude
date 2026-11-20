package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Surrounds your feet with obsidian (crying obsidian / ender chests as backup) to block crystals and pushes. */
public class Surround extends Module {
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 4, 1, 8, 1));
    private final BooleanSetting center = add(new BooleanSetting("Center", true));
    private final BooleanSetting floor = add(new BooleanSetting("Floor", true));
    private final BooleanSetting disableOnJump = add(new BooleanSetting("Disable On Jump", true));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));

    public Surround() {
        super("Surround", "Surrounds your feet with obsidian.", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        if (center.get() && mc.player.isOnGround()) {
            BlockPos p = mc.player.getBlockPos();
            mc.player.setPosition(p.getX() + 0.5, mc.player.getY(), p.getZ() + 0.5);
            mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
        }
    }

    @Override
    public void onTick() {
        if (disableOnJump.get() && !mc.player.isOnGround() && mc.player.getVelocity().y > 0.1) { setEnabled(false); return; }
        int slot = slot();
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
        if (floor.get()) out.add(feet.down());
        for (Direction d : Direction.Type.HORIZONTAL) {
            out.add(feet.offset(d).down()); // support first, so the side block has something to sit on
            out.add(feet.offset(d));
        }
        return out;
    }

    static int slot() {
        int s = BlockUtil.findBlockInHotbar(st -> st.isOf(Items.OBSIDIAN));
        if (s == -1) s = BlockUtil.findBlockInHotbar(st -> st.isOf(Items.CRYING_OBSIDIAN));
        if (s == -1) s = BlockUtil.findBlockInHotbar(st -> st.isOf(Items.ENDER_CHEST));
        return s;
    }
}
