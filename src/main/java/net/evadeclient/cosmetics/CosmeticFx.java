package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.module.misc.Clips;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.util.Combat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cosmetic particles, all spawned on your own client only: auras and trails on players you can actually see (never on
 * someone behind a wall, sneaking, invisible or a spectator, so a particle never gives anyone away), and your own kill
 * and win effects. Kill effects play only for kills you made (a player you hit in the last 10 s dies), win effects
 * only when a game-end line says you won. Particle Amount scales everything, with a hard cap per tick.
 */
public final class CosmeticFx {
    private static final int MAX_PER_TICK = 40;
    private static final long KILL_WINDOW_MS = 10_000;

    private static final Map<UUID, Long> hitAt = new HashMap<>();
    private static final Map<UUID, Boolean> wasAlive = new HashMap<>();
    private static final Map<UUID, Float> carry = new HashMap<>();
    private static final Map<UUID, Vec3d> lastPos = new HashMap<>();
    private static final Map<UUID, long[]> sight = new HashMap<>();
    private static final List<Effect> effects = new ArrayList<>();
    private static long lastWin;
    private static int budget;
    /** Particles spawned since the last take (tests). */
    private static int spawned;
    /** Kill and win effects started since launch (tests). */
    public static int killEffects, winEffects;

    private record Effect(CosmeticItem item, Vec3d at, int[] colors, float size, int length, int[] age) {}

    private CosmeticFx() {}

    public static int takeSpawned() { int s = spawned; spawned = 0; return s; }

    public static void clear() {
        hitAt.clear(); wasAlive.clear(); carry.clear(); lastPos.clear(); sight.clear(); effects.clear();
    }

    // ------------------------------------------------------------------ kills and wins

    public static void onAttack(Entity target) {
        if (target instanceof PlayerEntity p) hitAt.put(p.getUuid(), System.currentTimeMillis());
    }

    public static void onServerPacket(Packet<?> packet) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || !(packet instanceof EntityDamageS2CPacket d)) return;
        if (d.sourceCauseId() != mc.player.getId()) return;
        if (mc.world.getEntityById(d.entityId()) instanceof PlayerEntity p && p != mc.player) hitAt.put(p.getUuid(), System.currentTimeMillis());
    }

    /** A system chat line: a win for you plays your win effect (once per game). */
    public static void onMessage(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || text == null || !CosmeticsEngine.allowed()) return;
        if ("Win".equals(Clips.endOfGame(text, mc.player.getName().getString()))) win();
    }

    /** Plays your win effect around you, if you wear one. */
    public static boolean win() {
        long now = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || now - lastWin < 20_000) return false;
        CosmeticItem item = worn(Slot.WIN_EFFECT);
        if (item == null) return false;
        lastWin = now;
        start(item, mc.player.getEntityPos());
        winEffects++;
        return true;
    }

    /** Plays your kill effect where {@code victim} fell, if you wear one. */
    public static boolean kill(Vec3d at) {
        CosmeticItem item = worn(Slot.KILL_EFFECT);
        if (item == null) return false;
        start(item, at);
        killEffects++;
        return true;
    }

    private static CosmeticItem worn(Slot slot) {
        Loadout.Worn w = Wardrobe.renderLoadout().get(slot);
        CosmeticItem item = w == null ? null : CosmeticsCatalog.get(w.id());
        if (item == null || item.slot != slot) return null;
        CosmeticAssets.request(item);
        return item.fx() == null ? null : item;
    }

    private static void start(CosmeticItem item, Vec3d at) {
        JsonObject fx = item.fx();
        int len = "spiral".equals(str(fx, "type")) ? 40 : 6;
        effects.add(new Effect(item, at, colors(fx), num(fx, "size", 1f), len, new int[1]));
        if (effects.size() > 8) effects.remove(0);
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(MinecraftClient mc, Cosmetics m) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        float amount = (float) (m.particles.get() / 100.0);
        budget = Math.round(MAX_PER_TICK * amount);
        // kills: a player you hit recently just died
        if (!hitAt.isEmpty() || !wasAlive.isEmpty()) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                boolean alive = p.isAlive() && !p.isDead();
                Boolean prev = wasAlive.put(p.getUuid(), alive);
                Long hit = hitAt.get(p.getUuid());
                if (Boolean.TRUE.equals(prev) && !alive && hit != null && now - hit < KILL_WINDOW_MS) {
                    hitAt.remove(p.getUuid());
                    kill(p.getEntityPos());
                }
            }
            hitAt.values().removeIf(t -> now - t > KILL_WINDOW_MS * 2);
            if (hitAt.isEmpty()) wasAlive.clear();
        }
        tickEffects(mc);
        if (budget <= 0 || (m.hideInCombat.get() && Combat.active())) return;
        double range = m.distance.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            boolean self = p == mc.player;
            if (self ? !m.showMine.get() : !m.showOthers.get()) continue;
            Loadout l = self ? Wardrobe.renderLoadout() : PlayerCosmetics.get(p.getUuid());
            if (l == null) continue;
            Loadout.Worn aura = l.get(Slot.AURA), trail = l.get(Slot.TRAIL);
            if (aura == null && trail == null) continue;
            if (p.isInvisible() || p.isSpectator() || p.isSneaking() || p.isDead()) continue;
            if (!self && (p.squaredDistanceTo(mc.player) > range * range || !seen(mc, p, now))) continue;
            Vec3d pos = p.getEntityPos(), prev = lastPos.put(p.getUuid(), pos);
            if (aura != null) emit(p, CosmeticsCatalog.get(aura.id()), amount, false, pos, prev);
            if (trail != null) emit(p, CosmeticsCatalog.get(trail.id()), amount, true, pos, prev);
        }
        if (lastPos.size() > 256) lastPos.clear();
    }

    /** Line of sight from you to them, checked at most twice a second per player. */
    private static boolean seen(MinecraftClient mc, PlayerEntity p, long now) {
        long[] s = sight.computeIfAbsent(p.getUuid(), u -> new long[]{0, 0});
        if (now - s[0] > 500) {
            s[0] = now;
            s[1] = mc.player.canSee(p) ? 1 : 0;
        }
        if (sight.size() > 256) sight.clear();
        return s[1] == 1;
    }

    private static void emit(PlayerEntity p, CosmeticItem item, float amount, boolean trail, Vec3d pos, Vec3d prev) {
        if (item == null) return;
        CosmeticAssets.request(item);
        JsonObject fx = item.fx();
        if (fx == null) return;
        if (trail && (prev == null || prev.squaredDistanceTo(pos) < 0.0025)) return;   // trails only while moving
        float rate = num(fx, "rate", 4) / 20f * amount;
        UUID key = new UUID(p.getUuid().getMostSignificantBits(), p.getUuid().getLeastSignificantBits() ^ (trail ? 1 : 2));
        float c = carry.getOrDefault(key, 0f) + rate;
        int n = (int) c;
        carry.put(key, c - n);
        if (carry.size() > 512) carry.clear();
        int[] cols = colors(fx);
        float size = num(fx, "size", 1f);
        String particle = str(fx, "particle");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n && budget > 0; i++) {
            double x, y, z, vx = 0, vy, vz = 0;
            if (trail) {
                x = pos.x + r.nextGaussian() * 0.15;
                y = pos.y + 0.05 + r.nextDouble() * 0.2;
                z = pos.z + r.nextGaussian() * 0.15;
                vy = 0.01;
            } else {
                double a = r.nextDouble() * Math.PI * 2, rad = 0.35 + r.nextDouble() * 0.25;
                x = pos.x + Math.cos(a) * rad;
                y = pos.y + 0.1 + r.nextDouble() * 1.4;
                z = pos.z + Math.sin(a) * rad;
                vy = 0.02;
            }
            spawn(particle, cols[r.nextInt(cols.length)], size, x, y, z, vx, vy, vz);
        }
    }

    private static void tickEffects(MinecraftClient mc) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Iterator<Effect> it = effects.iterator(); it.hasNext(); ) {
            Effect e = it.next();
            int age = e.age()[0]++;
            if (age >= e.length()) { it.remove(); continue; }
            JsonObject fx = e.item().fx();
            String type = str(fx, "type");
            int count = (int) num(fx, "count", 30);
            // effects you earned get their own allowance (scaled by Particle Amount, never more than the cap)
            int allowance = Math.max(0, Math.round(Math.min(count, MAX_PER_TICK * 2) * (budget / (float) MAX_PER_TICK)));
            if ("spiral".equals(type)) {
                double h = num(fx, "height", 3f);
                int per = Math.max(1, allowance / Math.max(1, e.length() / 2));
                for (int i = 0; i < per; i++) {
                    float t = (age + i / (float) per) / e.length();
                    for (int arm = 0; arm < 2; arm++) {
                        double a = t * Math.PI * 2 * 2.2 + arm * Math.PI, rad = 0.9 * (1 - t * 0.3);
                        spawn(str(fx, "particle"), e.colors()[(age + arm) % e.colors().length], e.size(),
                                e.at().x + Math.cos(a) * rad, e.at().y + t * h, e.at().z + Math.sin(a) * rad, 0, 0.01, 0);
                    }
                }
                if (age == e.length() - 1) burst(e, allowance, e.at().add(0, h, 0), r);
            } else if (age == 0) {
                burst(e, allowance, e.at().add(0, 1.0, 0), r);
            }
        }
    }

    private static void burst(Effect e, int n, Vec3d at, ThreadLocalRandom r) {
        JsonObject fx = e.item().fx();
        boolean spark = fx.has("spark") && fx.get("spark").getAsBoolean();
        for (int i = 0; i < n; i++) {
            double vx = r.nextGaussian() * 0.18, vy = r.nextDouble() * 0.25, vz = r.nextGaussian() * 0.18;
            spawn(str(fx, "particle"), e.colors()[i % e.colors().length], e.size(), at.x, at.y, at.z, vx, vy, vz);
            if (spark && i % 4 == 0) raw(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, vx * 2, vy * 2, vz * 2);
        }
    }

    private static void spawn(String particle, int rgb, float size, double x, double y, double z, double vx, double vy, double vz) {
        ParticleEffect p = switch (particle == null ? "dust" : particle) {
            case "snow" -> ParticleTypes.SNOWFLAKE;
            case "end_rod" -> ParticleTypes.END_ROD;
            case "spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "glow" -> ParticleTypes.GLOW;
            default -> new DustParticleEffect(rgb, Math.max(0.1f, Math.min(2f, size)));
        };
        raw(p, x, y, z, vx, vy, vz);
    }

    private static void raw(ParticleEffect p, double x, double y, double z, double vx, double vy, double vz) {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        world.addParticleClient(p, x, y, z, vx, vy, vz);
        spawned++;
        budget--;
    }

    private static int[] colors(JsonObject fx) {
        JsonArray a = fx != null && fx.has("colors") && fx.get("colors").isJsonArray() ? fx.getAsJsonArray("colors") : null;
        if (a == null || a.isEmpty()) return new int[]{0xFFFFFF};
        int[] out = new int[Math.min(8, a.size())];
        for (int i = 0; i < out.length; i++) {
            JsonElement e = a.get(i);
            int c = Loadout.parseColor(e);
            out[i] = c < 0 ? 0xFFFFFF : c;
        }
        return out;
    }

    private static float num(JsonObject o, String k, float def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : def;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
