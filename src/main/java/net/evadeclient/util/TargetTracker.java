package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Tracks who you're fighting: the last entity you hit (kept for a few seconds) or, failing that,
 * the player you're looking at (up to 64 blocks, respecting walls).
 */
public final class TargetTracker {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final long COMBAT_TIMEOUT_MS = 6000;
    private static final double LOOK_RANGE = 64;

    private static LivingEntity combatTarget;
    private static long lastHit;
    private static LivingEntity lookTarget;

    private TargetTracker() {}

    public static void onAttack(Entity e) {
        if (e instanceof LivingEntity l) {
            combatTarget = l;
            lastHit = System.currentTimeMillis();
        }
    }

    public static void tick() {
        if (combatTarget != null && (combatTarget.isRemoved() || !combatTarget.isAlive()
                || System.currentTimeMillis() - lastHit > COMBAT_TIMEOUT_MS
                || mc.player.squaredDistanceTo(combatTarget) > LOOK_RANGE * LOOK_RANGE)) {
            combatTarget = null;
        }
        lookTarget = raycastLook();
    }

    /** The current target, preferring someone you're actively fighting. */
    public static LivingEntity get() {
        if (combatTarget != null) return combatTarget;
        return lookTarget;
    }

    public static boolean inCombat() { return combatTarget != null; }

    private static LivingEntity raycastLook() {
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity l) return l;
        Vec3d eye = mc.player.getCameraPosVec(1f);
        Vec3d look = mc.player.getRotationVec(1f);
        Vec3d end = eye.add(look.multiply(LOOK_RANGE));
        BlockHitResult block = mc.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (block.getType() != HitResult.Type.MISS) end = block.getPos();
        Box area = mc.player.getBoundingBox().stretch(end.subtract(eye)).expand(1);
        EntityHitResult hit = ProjectileUtil.raycast(mc.player, eye, end, area,
                e -> e instanceof PlayerEntity && !e.isSpectator() && e.isAlive(), eye.squaredDistanceTo(end));
        return hit != null && hit.getEntity() instanceof LivingEntity l ? l : null;
    }
}
