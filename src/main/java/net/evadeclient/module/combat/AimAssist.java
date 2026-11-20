package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.Targets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Smoothly pulls your crosshair onto the nearest target. Runs every frame with exponential
 * easing, aims at the nearest point of the hitbox (not the centre), and quantises movement to your
 * mouse sensitivity so it moves like a real mouse would.
 */
public class AimAssist extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 8, 0.1));
    private final NumberSetting fov = add(new NumberSetting("FOV", 90, 10, 360, 5));
    private final NumberSetting speed = add(new NumberSetting("Speed", 6, 1, 20, 0.5));
    private final ModeSetting priority = add(new ModeSetting("Priority", "Angle", "Angle", "Distance", "Health"));
    private final BooleanSetting requireClick = add(new BooleanSetting("Require Click", true));
    private final BooleanSetting weaponOnly = add(new BooleanSetting("Weapon Only", false));
    private final BooleanSetting vertical = add(new BooleanSetting("Vertical", true));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls", false));
    private final BooleanSetting stopOnTarget = add(new BooleanSetting("Stop On Target", true));
    private final BooleanSetting sticky = add(new BooleanSetting("Sticky", true));
    private final Targets targets = new Targets();

    private LivingEntity locked;

    public AimAssist() {
        super("AimAssist", "Smoothly helps aim at nearby targets.", Category.COMBAT);
        targets.addTo(this::add);
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (mc.currentScreen != null) return;
        if (requireClick.get() && !mc.options.attackKey.isPressed()) { locked = null; return; }
        if (weaponOnly.get() && !Targets.isWeapon(mc.player.getMainHandStack())) return;

        Vec3d eye = mc.player.getCameraPosVec(ctx.tickDelta);
        Vec3d look = mc.player.getRotationVec(ctx.tickDelta);

        LivingEntity target = pickTarget(eye, look, ctx.tickDelta);
        locked = target;
        if (target == null) return;

        Box box = lerpedBox(target, ctx.tickDelta).contract(0.05);
        if (stopOnTarget.get() && box.raycast(eye, eye.add(look.multiply(range.get() + 1))).isPresent()) return;

        Vec3d aim = RotationUtil.closestPointToRay(box, eye, look);
        float[] want = RotationUtil.rotationsTo(eye, aim);
        double dYaw = MathHelper.wrapDegrees(want[0] - mc.player.getYaw());
        double dPitch = vertical.get() ? want[1] - mc.player.getPitch() : 0;

        // Exponential ease: frame-rate independent, fast when far, gentle when close.
        double k = 1 - Math.exp(-speed.get() * ctx.frameTime);
        double jitter = 1 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.25;
        double moveYaw = RotationUtil.gcd(dYaw * k * jitter);
        double movePitch = RotationUtil.gcd(dPitch * k * jitter);
        if (moveYaw == 0 && movePitch == 0) return;

        // changeLookDirection takes mouse units (degrees / 0.15) and keeps prevYaw consistent, so no stutter.
        mc.player.changeLookDirection(moveYaw / 0.15, movePitch / 0.15);
    }

    private LivingEntity pickTarget(Vec3d eye, Vec3d look, float delta) {
        double r = range.get();
        double halfFov = fov.get() / 2;

        if (sticky.get() && locked != null && valid(locked, eye, look, r, halfFov, delta)) return locked;

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity l) || !valid(l, eye, look, r, halfFov, delta)) continue;
            double score = switch (priority.get()) {
                case "Distance" -> mc.player.squaredDistanceTo(l);
                case "Health" -> l.getHealth() + l.getAbsorptionAmount();
                default -> RotationUtil.angleTo(eye, look, lerpedBox(l, delta).getCenter());
            };
            if (score < bestScore) { bestScore = score; best = l; }
        }
        return best;
    }

    private boolean valid(LivingEntity e, Vec3d eye, Vec3d look, double r, double halfFov, float delta) {
        if (!targets.isValid(e)) return false;
        Box box = lerpedBox(e, delta);
        Vec3d nearest = RotationUtil.closestPointToRay(box, eye, look);
        if (eye.squaredDistanceTo(nearest) > r * r) return false;
        if (halfFov < 180 && RotationUtil.angleTo(eye, look, box.getCenter()) > halfFov) return false;
        return throughWalls.get() || mc.player.canSee(e);
    }

    private static Box lerpedBox(Entity e, float delta) {
        Vec3d pos = e.getLerpedPos(delta);
        return e.getBoundingBox().offset(pos.subtract(e.getEntityPos()));
    }
}
