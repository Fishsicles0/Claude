package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.module.misc.Cosmetics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Particles that go with an emote (the {@code fx} object of an emote's item.json): rune glyphs circling you while you
 * levitate, a ring of fire at your feet, hearts tossed from your hand, puffs while you laugh, a dust ring when you land.
 * Emotes only play on your own screen, so these are spawned on your own client, around you, only while the emote plays,
 * scaled by Particle Amount (0 turns them off) and capped per tick.
 *
 * <p>Fields: {@code shape} ring (round your feet) | orbit (circling you at {@code height}) | rise (drifting up around
 * you) | toss (out of your right hand, forwards and up) | puff (beside your head) | impact (a burst at your feet once,
 * at {@code at} seconds); {@code from} / {@code to} the part of the emote (seconds) that makes particles; {@code rate}
 * per second ({@code count} for impact); {@code radius} and {@code height} in blocks; {@code particle} soft | heart |
 * note | enchant | poof | flame | end_rod | glow | spark | dust, with the same colour and soft-glow fields as auras.
 */
public final class EmoteFx {
    private static final int MAX_PER_TICK = 12;
    private static float carry;
    private static float lastT = -1;
    private static Emotes.Playing last;
    /** Particles spawned since launch (tests). */
    public static int spawnedTotal;

    private EmoteFx() {}

    /** Every tick while an emote plays. */
    public static void tick(MinecraftClient mc, Emotes.Playing playing) {
        ClientPlayerEntity p = mc.player;
        if (playing != last) { last = playing; carry = 0; lastT = -1; }
        if (p == null || mc.world == null || playing == null) return;
        JsonObject fx = playing.item().fx();
        if (fx == null) return;
        Cosmetics m = CosmeticsEngine.module();
        float amount = m == null ? 1f : (float) (m.particles.get() / 100.0);
        if (amount <= 0) return;
        float t = playing.time(Emotes.clock());
        if (t < 0) return;
        String shape = str(fx, "shape");
        int budget = Math.max(1, Math.round(MAX_PER_TICK * amount));
        float prev = lastT;
        lastT = t;
        if ("impact".equals(shape)) {
            float at = num(fx, "at", 0f);
            boolean crossed = prev >= 0 ? (prev <= at && t > at) || (t < prev && t > at) : t > at && t < at + 0.1f;
            if (!crossed) return;
            int n = Math.min(budget * 3, Math.round(num(fx, "count", 20) * amount));
            for (int i = 0; i < n; i++) emit(p, fx, shape, i, n);
            return;
        }
        if (t < num(fx, "from", 0f) || t > num(fx, "to", 1e9f)) return;
        carry += num(fx, "rate", 8) / 20f * amount;
        int n = Math.min(budget, (int) carry);
        carry -= (int) carry;
        for (int i = 0; i < n; i++) emit(p, fx, shape, i, n);
    }

    private static void emit(ClientPlayerEntity p, JsonObject fx, String shape, int i, int n) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double yaw = Math.toRadians(p.bodyYaw);
        // the way you face (the camera swings round to your front while an emote plays) and your right hand side
        double fx_ = -Math.sin(yaw), fz = Math.cos(yaw), rx = -fz, rz = fx_;
        double cx = p.getX(), cy = p.getY(), cz = p.getZ();
        float radius = num(fx, "radius", 0.8f), height = num(fx, "height", 1.0f);
        double x, y, z, vx = 0, vy = 0, vz = 0;
        double a = r.nextDouble() * Math.PI * 2;
        switch (shape) {
            case "ring" -> {
                x = cx + Math.cos(a) * radius; y = cy + 0.05 + r.nextDouble() * 0.15; z = cz + Math.sin(a) * radius;
                vy = 0.02 + r.nextDouble() * 0.03;
            }
            case "orbit" -> {
                double turn = Emotes.clock() * 1.6 + i * Math.PI * 2 / Math.max(1, n);
                x = cx + Math.cos(turn) * radius; y = cy + height + r.nextGaussian() * 0.25; z = cz + Math.sin(turn) * radius;
                vx = -Math.sin(turn) * 0.02; vz = Math.cos(turn) * 0.02; vy = 0.01;
            }
            case "toss" -> {
                x = cx + rx * 0.35 + fx_ * 0.45; y = cy + height; z = cz + rz * 0.35 + fz * 0.45;
                vx = fx_ * 0.12 + r.nextGaussian() * 0.02; vy = 0.12 + r.nextDouble() * 0.05; vz = fz * 0.12 + r.nextGaussian() * 0.02;
            }
            case "puff" -> {
                double side = r.nextBoolean() ? 1 : -1;
                x = cx + rx * 0.45 * side + fx_ * 0.2; y = cy + height + r.nextDouble() * 0.3; z = cz + rz * 0.45 * side + fz * 0.2;
                vx = rx * 0.03 * side; vy = 0.03; vz = rz * 0.03 * side;
            }
            case "impact" -> {
                x = cx + Math.cos(a) * 0.3; y = cy + 0.1; z = cz + Math.sin(a) * 0.3;
                vx = Math.cos(a) * (0.15 + r.nextDouble() * 0.1); vy = 0.02 + r.nextDouble() * 0.04; vz = Math.sin(a) * (0.15 + r.nextDouble() * 0.1);
            }
            default -> { // rise
                double rad = radius * (0.6 + r.nextDouble() * 0.5);
                x = cx + Math.cos(a) * rad; y = cy + r.nextDouble() * height; z = cz + Math.sin(a) * rad;
                vy = 0.03;
            }
        }
        int[] cols = colors(fx);
        int rgb = cols[r.nextInt(cols.length)];
        String particle = str(fx, "particle");
        if ("soft".equals(particle)) {
            int[] grad = cols.length >= 2 ? cols : new int[]{rgb, rgb};
            if (SoftParticle.spawn(x, y, z, vx, vy, vz, grad, Math.max(0.1f, Math.min(4f, num(fx, "size", 0.8f))),
                    Math.max(0f, Math.min(3f, num(fx, "sizeEnd", 0.4f))), Math.max(0.2f, Math.min(4f, num(fx, "life", 1.2f))),
                    num(fx, "fade", 0.25f), Math.max(-2f, Math.min(2f, num(fx, "gravity", -0.15f))))) {
                spawnedTotal++;
                return;
            }
        }
        if ("note".equals(particle)) vx = r.nextInt(25) / 24.0;    // a note's colour comes from its x speed
        ParticleEffect pe = switch (particle) {
            case "heart" -> ParticleTypes.HEART;
            case "note" -> ParticleTypes.NOTE;
            case "enchant" -> ParticleTypes.ENCHANT;
            case "poof" -> ParticleTypes.POOF;
            case "flame" -> ParticleTypes.FLAME;
            case "end_rod" -> ParticleTypes.END_ROD;
            case "glow" -> ParticleTypes.GLOW;
            case "spark" -> ParticleTypes.ELECTRIC_SPARK;
            default -> new DustParticleEffect(rgb, Math.max(0.1f, Math.min(2f, num(fx, "size", 1f))));
        };
        MinecraftClient.getInstance().world.addParticleClient(pe, x, y, z, vx, "note".equals(particle) ? 0 : vy, "note".equals(particle) ? 0 : vz);
        spawnedTotal++;
    }

    private static int[] colors(JsonObject fx) {
        JsonArray a = fx.has("colors") && fx.get("colors").isJsonArray() ? fx.getAsJsonArray("colors") : null;
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
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : def;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
