package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.DamageCalc;
import net.evadeclient.util.InvUtil;
import net.evadeclient.util.PacketHooks;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.Targets;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;

/**
 * Places end crystals on obsidian/bedrock next to an enemy and breaks them, choosing spots that hurt the target
 * more than you (using the game's own exposure calculation). Won't pick spots that would take you below a safe health.
 */
public class CrystalAura extends Module {
    private final NumberSetting targetRange = add(new NumberSetting("Target Range", 10, 4, 16, 1));
    private final NumberSetting placeRange = add(new NumberSetting("Place Range", 4.5, 2, 6, 0.5));
    private final NumberSetting breakRange = add(new NumberSetting("Break Range", 4.5, 2, 6, 0.5));
    private final NumberSetting minDamage = add(new NumberSetting("Min Damage", 6, 0, 36, 0.5));
    private final NumberSetting maxSelf = add(new NumberSetting("Max Self Damage", 6, 0, 36, 0.5));
    private final NumberSetting safeHealth = add(new NumberSetting("Keep Health Above", 4, 0, 20, 1));
    private final NumberSetting placeDelay = add(new NumberSetting("Place Delay", 1, 0, 10, 1));
    private final NumberSetting breakDelay = add(new NumberSetting("Break Delay", 1, 0, 10, 1));
    private final BooleanSetting autoSwitch = add(new BooleanSetting("Auto Switch", true));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final BooleanSetting render = add(new BooleanSetting("Show Spot", true));

    private int placeWait, breakWait;
    private BlockPos lastSpot;
    private PlayerEntity target;
    private final Targets targets = new Targets();

    public CrystalAura() {
        super("CrystalAura", "Places and breaks end crystals near enemies.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        target = mc.world.getPlayers().stream()
                .filter(p -> targets.isValid(p) && mc.player.distanceTo(p) <= targetRange.get())
                .min(Comparator.comparingDouble(p -> mc.player.distanceTo(p))).orElse(null);
        if (target == null) { lastSpot = null; return; }

        if (breakWait-- <= 0 && breakCrystal()) breakWait = breakDelay.getInt();
        if (placeWait-- <= 0 && placeCrystal()) placeWait = placeDelay.getInt();
    }

    private boolean breakCrystal() {
        double r = breakRange.get();
        EndCrystalEntity best = null;
        float bestDmg = 0;
        for (EndCrystalEntity c : mc.world.getEntitiesByClass(EndCrystalEntity.class, mc.player.getBoundingBox().expand(r), Entity::isAlive)) {
            if (mc.player.getEyePos().distanceTo(c.getEntityPos()) > r) continue;
            float dmg = DamageCalc.explosion(c.getEntityPos(), 6f, target);
            float self = DamageCalc.explosion(c.getEntityPos(), 6f, mc.player);
            if (!safe(self) || dmg < minDamage.get() || dmg <= bestDmg) continue;
            best = c;
            bestDmg = dmg;
        }
        if (best == null) return false;
        face(best.getEntityPos());
        mc.interactionManager.attackEntity(mc.player, best);
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private boolean placeCrystal() {
        Hand hand = mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) ? Hand.OFF_HAND : Hand.MAIN_HAND;
        int slot = -1;
        if (hand == Hand.MAIN_HAND && !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
            if (!autoSwitch.get()) return false;
            slot = InvUtil.findHotbar(s -> s.isOf(Items.END_CRYSTAL));
            if (slot == -1) return false;
        }
        BlockPos spot = bestSpot();
        lastSpot = spot;
        if (spot == null) return false;
        if (slot != -1) mc.player.getInventory().setSelectedSlot(slot);
        Vec3d hit = Vec3d.ofCenter(spot).add(0, 0.5, 0);
        face(hit);
        mc.interactionManager.interactBlock(mc.player, hand, new BlockHitResult(hit, Direction.UP, spot, false));
        mc.player.swingHand(hand);
        return true;
    }

    private BlockPos bestSpot() {
        double r = placeRange.get();
        BlockPos center = mc.player.getBlockPos();
        int ri = (int) Math.ceil(r);
        BlockPos best = null;
        float bestDmg = 0;
        for (BlockPos p : BlockPos.iterate(center.add(-ri, -ri, -ri), center.add(ri, ri, ri))) {
            if (!(mc.world.getBlockState(p).isOf(Blocks.OBSIDIAN) || mc.world.getBlockState(p).isOf(Blocks.BEDROCK))) continue;
            BlockPos up = p.up();
            if (!mc.world.getBlockState(up).isAir()) continue;
            if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(p).add(0, 0.5, 0)) > r) continue;
            if (!mc.world.getOtherEntities(null, new Box(up).stretch(0, 1, 0)).isEmpty()) continue;
            Vec3d boom = Vec3d.ofBottomCenter(up);
            float dmg = DamageCalc.explosion(boom, 6f, target);
            float self = DamageCalc.explosion(boom, 6f, mc.player);
            if (dmg < minDamage.get() || !safe(self) || dmg <= bestDmg) continue;
            best = p.toImmutable();
            bestDmg = dmg;
        }
        return best;
    }

    private boolean safe(float self) {
        return self <= maxSelf.get() && mc.player.getHealth() + mc.player.getAbsorptionAmount() - self > safeHealth.get();
    }

    private void face(Vec3d at) {
        if (!rotate.get()) return;
        float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), at);
        PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (render.get() && lastSpot != null) {
            Box b = new Box(lastSpot);
            ctx.boxFilled(b, ColorUtil.withAlpha(0xE11D2E, 50));
            ctx.boxOutline(b, 0xFFE11D2E, 2f);
        }
    }

    @Override
    public String getSuffix() { return target != null ? target.getName().getString() : null; }
}
