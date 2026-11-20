package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.ExplosionImpl;

/** Estimates explosion damage the same way the game does (exposure, distance, armour). */
public final class DamageCalc {
    private DamageCalc() {}

    /** Damage an explosion of {@code power} at {@code center} would deal to {@code e} after armour. */
    public static float explosion(Vec3d center, float power, LivingEntity e) {
        double radius = power * 2.0;
        double dist = Math.sqrt(e.squaredDistanceTo(center)) / radius;
        if (dist > 1.0) return 0;
        double exposure = ExplosionImpl.calculateReceivedDamage(center, e);
        double impact = (1.0 - dist) * exposure;
        float raw = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);
        var src = MinecraftClient.getInstance().world.getDamageSources().explosion(null, null);
        return DamageUtil.getDamageLeft(e, raw, src, e.getArmor(), (float) e.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS));
    }
}
