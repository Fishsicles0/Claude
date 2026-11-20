package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.DamageCalc;
import net.evadeclient.util.InvUtil;
import net.evadeclient.util.Targets;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Comparator;

/**
 * Outside the Nether, respawn anchors explode when used. Places an anchor next to an enemy, charges it with
 * glowstone, then detonates it, one step per action. Skips spots that would hurt you too much.
 */
public class AnchorAura extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 6, 0.5));
    private final NumberSetting minDamage = add(new NumberSetting("Min Damage", 6, 0, 36, 0.5));
    private final NumberSetting maxSelf = add(new NumberSetting("Max Self Damage", 6, 0, 36, 0.5));
    private final NumberSetting delay = add(new NumberSetting("Delay Ticks", 2, 0, 10, 1));
    private final Targets targets = new Targets();
    private int wait;

    public AnchorAura() {
        super("AnchorAura", "Blows up respawn anchors next to enemies.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (wait-- > 0 || mc.world.getRegistryKey() == World.NETHER) return;
        PlayerEntity t = mc.world.getPlayers().stream()
                .filter(p -> targets.isValid(p) && mc.player.distanceTo(p) <= range.get() + 2)
                .min(Comparator.comparingDouble(p -> mc.player.distanceTo(p))).orElse(null);
        if (t == null) return;

        // 1. an anchor already next to them? charge or detonate it
        for (BlockPos p : BlockPos.iterate(t.getBlockPos().add(-1, 0, -1), t.getBlockPos().add(1, 1, 1))) {
            if (!mc.world.getBlockState(p).isOf(Blocks.RESPAWN_ANCHOR) || !BlockUtil.withinReach(p)) continue;
            int charges = mc.world.getBlockState(p).get(RespawnAnchorBlock.CHARGES);
            int glow = InvUtil.findHotbar(s -> s.isOf(Items.GLOWSTONE));
            int other = InvUtil.findHotbar(s -> !s.isOf(Items.GLOWSTONE) && !s.isOf(Items.RESPAWN_ANCHOR));
            if (charges == 0) {
                if (glow == -1) return;
                use(p, glow);
            } else {
                if (other == -1) return;
                use(p, other); // using an anchor with anything but glowstone sets it off
            }
            wait = delay.getInt();
            return;
        }

        // 2. otherwise place one in the best spot around their feet/head
        int anchor = InvUtil.findHotbar(s -> s.isOf(Items.RESPAWN_ANCHOR));
        if (anchor == -1) return;
        BlockPos best = null;
        float bestDmg = 0;
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            for (BlockPos base : new BlockPos[]{t.getBlockPos(), t.getBlockPos().up()}) {
                BlockPos p = base.offset(d);
                if (!BlockUtil.withinReach(p) || !BlockUtil.canPlace(p, Blocks.RESPAWN_ANCHOR.getDefaultState())) continue;
                Vec3d c = Vec3d.ofCenter(p);
                float dmg = DamageCalc.explosion(c, 5f, t), self = DamageCalc.explosion(c, 5f, mc.player);
                if (dmg >= minDamage.get() && self <= maxSelf.get() && dmg > bestDmg) { best = p.toImmutable(); bestDmg = dmg; }
            }
        }
        if (best != null && BlockUtil.place(best, anchor, true, false)) wait = delay.getInt();
    }

    private void use(BlockPos p, int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(p), Direction.UP, p, false));
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
