package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.HoleUtil;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;

/** Fills nearby safe holes with obsidian (or webs) so enemies can't hide in them. Never fills the hole you're in. */
public class HoleFiller extends Module {
    private final ModeSetting near = add(new ModeSetting("Near", "Enemies", "Enemies", "Me"));
    private final NumberSetting enemyRange = add(new NumberSetting("Enemy Range", 4, 1, 8, 0.5));
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 2, 1, 6, 1));
    private final BooleanSetting webs = add(new BooleanSetting("Use Webs", false));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));

    public HoleFiller() {
        super("HoleFiller", "Fills safe holes near enemies so they can't use them.", Category.PVP);
    }

    @Override
    public void onTick() {
        int slot = webs.get() ? BlockUtil.findBlockInHotbar(s -> s.isOf(Items.COBWEB)) : Surround.slot();
        if (slot == -1) return;
        BlockPos me = mc.player.getBlockPos();
        List<BlockPos> holes = HoleUtil.near(me, 5, 3);
        holes.removeIf(h -> h.equals(me) || !BlockUtil.withinReach(h) || !wanted(h));
        holes.sort(Comparator.comparingDouble(h -> h.getSquaredDistance(mc.player.getEntityPos())));
        int placed = 0;
        for (BlockPos h : holes) {
            if (placed >= perTick.getInt()) break;
            if (!BlockUtil.canPlace(h, Blocks.OBSIDIAN.getDefaultState())) continue;
            if (BlockUtil.place(h, slot, rotate.get(), true)) placed++;
        }
    }

    private boolean wanted(BlockPos hole) {
        if (near.is("Me")) return true;
        double r = enemyRange.get() * enemyRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || mc.player.isTeammate(p) || AntiBot.isBot(p) || p.isSpectator()) continue;
            if (p.getBlockPos().equals(hole)) continue; // already inside: filling would just fail
            if (p.squaredDistanceTo(hole.toCenterPos()) <= r) return true;
        }
        return false;
    }
}
