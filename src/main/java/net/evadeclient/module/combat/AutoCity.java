package net.evadeclient.module.combat;

import net.evadeclient.mixin.InteractionManagerAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.HoleUtil;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.Targets;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.evadeclient.util.PacketHooks;

import java.util.Comparator;

/**
 * "Cities" an enemy who is standing in a 1x1 hole: mines the obsidian wall beside them so they're exposed to a
 * crystal. Only mines a wall block that is actually a hole wall, never the floor.
 */
public class AutoCity extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 6, 0.5));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final Targets targets = new Targets();

    public AutoCity() {
        super("AutoCity", "Mines the wall of an enemy's hole so they can be crystalled.", Category.COMBAT);
        targets.addTo(this::add);
    }

    @Override
    public void onTick() {
        net.minecraft.entity.LivingEntity t = mc.world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class,
                mc.player.getBoundingBox().expand(range.get() + 2), targets::isValid).stream()
                .min(Comparator.comparingDouble(mc.player::distanceTo)).orElse(null);
        if (t == null) return;
        BlockPos feet = t.getBlockPos();
        if (HoleUtil.type(feet) == HoleUtil.Type.NONE) return; // only when they're actually in a hole
        // The wall block nearest us is the one to mine.
        BlockPos wall = null;
        double best = Double.MAX_VALUE;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos w = feet.offset(d);
            if (!HoleUtil.blastProof(mc.world.getBlockState(w))) continue;
            double dist = mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(w));
            if (dist <= range.get() * range.get() && dist < best) { best = dist; wall = w; }
        }
        if (wall == null) return;
        Direction face = Direction.getFacing(mc.player.getX() - wall.getX(), 0, mc.player.getZ() - wall.getZ());
        if (rotate.get()) {
            float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), Vec3d.ofCenter(wall));
            PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], mc.player.isOnGround(), mc.player.horizontalCollision));
        }
        ((InteractionManagerAccessor) mc.interactionManager).evade$setBlockBreakingCooldown(0);
        if (mc.player.isCreative()) mc.interactionManager.attackBlock(wall, face);
        else mc.interactionManager.updateBlockBreakingProgress(wall, face);
        mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }
}
