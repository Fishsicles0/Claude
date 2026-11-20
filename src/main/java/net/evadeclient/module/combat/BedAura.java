package net.evadeclient.module.combat;

import net.evadeclient.mixin.InteractionManagerAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.Targets;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.evadeclient.util.PacketHooks;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.Comparator;

/**
 * Places a bed next to the nearest enemy and breaks it. Beds explode in the Nether and the End (not the
 * Overworld), so this only tries to detonate there; the suffix tells you when it can't.
 */
public class BedAura extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 6, 0.5));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private int wait;

    private final Targets targets = new Targets();

    public BedAura() {
        super("BedAura", "Places and detonates beds next to enemies (Nether/End).", Category.COMBAT);
        targets.addTo(this::add);
    }

    private boolean explodes() {
        var id = mc.world.getRegistryKey().getValue().getPath();
        return id.equals("the_nether") || id.equals("the_end");
    }

    @Override
    public void onTick() {
        if (wait-- > 0) return;
        int slot = BlockUtil.findBlockInHotbar(s -> s.getItem() instanceof net.minecraft.item.BlockItem bi && bi.getBlock() instanceof BedBlock);
        net.minecraft.entity.LivingEntity t = mc.world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class,
                mc.player.getBoundingBox().expand(range.get() + 1), targets::isValid).stream()
                .min(Comparator.comparingDouble(mc.player::distanceTo)).orElse(null);
        if (t == null) return;
        // Break an existing bed next to the target first (detonates it).
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos bp = t.getBlockPos().offset(d);
            if (mc.world.getBlockState(bp).getBlock() instanceof BedBlock && BlockUtil.withinReach(bp)) {
                if (rotate.get()) {
                    float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), Vec3d.ofCenter(bp));
                    PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], mc.player.isOnGround(), mc.player.horizontalCollision));
                }
                ((InteractionManagerAccessor) mc.interactionManager).evade$setBlockBreakingCooldown(0);
                mc.interactionManager.attackBlock(bp, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
                wait = 1;
                return;
            }
        }
        if (slot == -1) return;
        // Otherwise place a bed on a clear, reachable spot beside the target with a floor under it.
        // The server decides which way the second half extends from the direction we face.
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos bp = t.getBlockPos().offset(d);
            boolean floor = !mc.world.getBlockState(bp.down()).getCollisionShape(mc.world, bp.down()).isEmpty();
            if (floor && mc.world.getBlockState(bp).isReplaceable() && BlockUtil.withinReach(bp)) {
                if (BlockUtil.place(bp, slot, rotate.get(), true)) { wait = 1; return; }
            }
        }
    }

    @Override
    public String getSuffix() { return explodes() ? null : "no boom here"; }
}
