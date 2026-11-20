package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.PacketHooks;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.Targets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Attacks valid targets around you when your weapon is charged. Picks targets by distance, health or angle,
 * can face them server-side before each hit, and works with AutoCritical.
 */
public class KillAura extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 3.6, 2, 6, 0.1));
    private final ModeSetting priority = add(new ModeSetting("Priority", "Distance", "Distance", "Health", "Angle"));
    private final NumberSetting fov = add(new NumberSetting("FOV", 360, 30, 360, 10));
    private final NumberSetting charge = add(new NumberSetting("Charge %", 95, 50, 100, 1));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final BooleanSetting walls = add(new BooleanSetting("Through Walls", false));
    private final BooleanSetting weaponOnly = add(new BooleanSetting("Weapon Only", true));
    private final BooleanSetting shieldCheck = add(new BooleanSetting("Shield Check", true));
    private final BooleanSetting pauseEating = add(new BooleanSetting("Pause Eating", true));
    private final NumberSetting maxTargets = add(new NumberSetting("Max Targets", 1, 1, 5, 1));
    private final Targets targets = new Targets();

    private Entity current;

    public KillAura() {
        super("KillAura", "Attacks targets around you automatically.", Category.COMBAT);
        targets.addTo(this::add);
    }

    @Override
    public void onTick() {
        current = null;
        if (mc.currentScreen != null) return;
        if (weaponOnly.get() && !Targets.isWeapon(mc.player.getMainHandStack())) return;
        if (pauseEating.get() && mc.player.isUsingItem()) return;

        List<LivingEntity> picked = pick();
        if (picked.isEmpty()) return;
        current = picked.getFirst();
        float need = (float) (charge.get() / 100.0) + (float) ThreadLocalRandom.current().nextDouble(0, 0.03);
        if (mc.player.getAttackCooldownProgress(0f) < Math.min(1f, need)) return;
        if (!AutoCritical.allow()) return;

        // With Max Targets > 1, hit several in range this swing (the cooldown is shared, so damage is reduced,
        // but it clears a crowd). The first target gets the rotation.
        LivingEntity t = picked.getFirst();
        if (rotate.get()) {
            Vec3d aim = RotationUtil.closestPointToRay(t.getBoundingBox(), mc.player.getEyePos(), mc.player.getRotationVec(1f));
            float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), aim);
            PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], mc.player.isOnGround(), mc.player.horizontalCollision));
        }
        for (LivingEntity e : picked) {
            mc.interactionManager.attackEntity(mc.player, e);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private List<LivingEntity> pick() {
        double r = range.get();
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1f);
        List<LivingEntity> list = mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(r + 1), this::valid);
        Comparator<LivingEntity> order = switch (priority.get()) {
            case "Health" -> Comparator.comparingDouble(e -> e.getHealth() + e.getAbsorptionAmount());
            case "Angle" -> Comparator.comparingDouble(e -> RotationUtil.angleTo(eye, look, e.getBoundingBox().getCenter()));
            default -> Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e));
        };
        return list.stream().filter(e -> {
            Box b = e.getBoundingBox();
            Vec3d p = RotationUtil.closestPointToRay(b, eye, look);
            Vec3d nearest = new Vec3d(Math.max(b.minX, Math.min(eye.x, b.maxX)), Math.max(b.minY, Math.min(eye.y, b.maxY)), Math.max(b.minZ, Math.min(eye.z, b.maxZ)));
            if (eye.squaredDistanceTo(nearest) > r * r) return false;
            if (fov.get() < 360 && RotationUtil.angleTo(eye, look, p) > fov.get() / 2) return false;
            return walls.get() || mc.player.canSee(e);
        }).sorted(order).limit(maxTargets.getInt()).toList();
    }

    private boolean valid(LivingEntity e) {
        if (!targets.isValid(e)) return false;
        return !(shieldCheck.get() && e instanceof PlayerEntity p && p.isBlocking() && !(mc.player.getMainHandStack().getItem() instanceof AxeItem));
    }

    public Entity current() { return isEnabled() ? current : null; }

    @Override
    public String getSuffix() { return priority.get(); }
}
