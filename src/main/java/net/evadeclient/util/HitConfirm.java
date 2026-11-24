package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Hits the server confirmed were yours: it sends a damage event for every hurt entity naming who caused it, and
 * when that is you, it's a confirmed hit (melee, or your arrow, trident or other projectile). Only entities you hurt
 * are ever noted, and never their health. Feeds the crosshair hit marker and Damage Numbers.
 */
public final class HitConfirm {
    private HitConfirm() {}

    /** One confirmed hit: where the entity was, when, whether it was a projectile, and your swing's damage if known. */
    public record Hit(int entityId, Vec3d pos, long at, boolean projectile, float damage, boolean crit) {}

    /** Your last melee swing at an entity, waiting for the server to confirm it (or not). */
    private record Pending(int entityId, float damage, boolean crit, long at) {}

    private static final long CONFIRM_WINDOW_MS = 1500;
    private static final int KEEP = 32;
    private static final List<Pending> pending = new ArrayList<>();
    private static final List<Hit> hits = new ArrayList<>();
    private static volatile long lastHitMs;

    /** Damage Numbers: you're about to attack {@code target}; {@code damage} is your swing's damage as the client knows it. */
    public static void swing(Entity target, float damage, boolean crit) {
        long now = System.currentTimeMillis();
        pending.removeIf(p -> now - p.at > CONFIRM_WINDOW_MS || p.entityId == target.getId());
        pending.add(new Pending(target.getId(), damage, crit, now));
    }

    /** A damage event from the server (client thread). Records it when you caused it. */
    public static void onDamage(EntityDamageS2CPacket packet) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || packet.sourceCauseId() != mc.player.getId()) return;
        if (packet.entityId() == mc.player.getId()) return;
        Entity e = mc.world.getEntityById(packet.entityId());
        if (e == null) return;
        boolean projectile = packet.sourceDirectId() != mc.player.getId();
        float dmg = -1;
        boolean crit = false;
        long now = System.currentTimeMillis();
        if (!projectile) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                Pending p = pending.get(i);
                if (p.entityId == e.getId() && now - p.at <= CONFIRM_WINDOW_MS) {
                    dmg = p.damage;
                    crit = p.crit;
                    pending.remove(i);
                    break;
                }
            }
        }
        add(new Hit(e.getId(), e.getEntityPos().add(0, e.getHeight(), 0), now, projectile, dmg, crit));
    }

    /** Records a hit (also the test hook). */
    public static void add(Hit hit) {
        hits.add(hit);
        while (hits.size() > KEEP) hits.removeFirst();
        lastHitMs = hit.at();
    }

    /** Hits newer than {@code ms} milliseconds, oldest first. */
    public static List<Hit> recent(long ms) {
        long now = System.currentTimeMillis();
        hits.removeIf(h -> now - h.at() > 10_000);
        List<Hit> out = new ArrayList<>();
        for (Hit h : hits) if (now - h.at() <= ms) out.add(h);
        return out;
    }

    /** Milliseconds since your last confirmed hit (Long.MAX_VALUE if none). */
    public static long sinceLastHit() {
        long l = lastHitMs;
        return l == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - l;
    }

    /** Forget everything (tests, leaving a world). */
    public static void clear() {
        pending.clear();
        hits.clear();
        lastHitMs = 0;
    }
}
