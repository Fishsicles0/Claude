package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Emotes: keyframed poses for the player model, played from the emote wheel. Only your own model moves, on your screen
 * (the camera swings round to the front while one plays). An emote stops as soon as you move, jump, sneak, attack, use
 * an item or take damage, so it never stands between you and the game.
 */
public final class Emotes {
    private static final float DEG = (float) (Math.PI / 180.0);
    private static final float BLEND = 0.2f;

    /** Keyframes per model part: [seconds, pitch, yaw, roll] (degrees), replacing the part's own angles. */
    public static final class Animation {
        public final float length;
        public final boolean loop;
        final Map<String, float[][]> parts = new HashMap<>();

        private Animation(float length, boolean loop) {
            this.length = length;
            this.loop = loop;
        }

        static Animation parse(JsonObject o) {
            Animation a = new Animation(Math.max(0.1f, Math.min(30f, o.has("length") ? o.get("length").getAsFloat() : 1f)),
                    o.has("loop") && o.get("loop").getAsBoolean());
            if (o.has("parts")) for (var e : o.getAsJsonObject("parts").entrySet()) {
                JsonArray keys = e.getValue().getAsJsonArray();
                float[][] k = new float[Math.min(keys.size(), 64)][4];
                for (int i = 0; i < k.length; i++) {
                    JsonArray kf = keys.get(i).getAsJsonArray();
                    for (int j = 0; j < 4 && j < kf.size(); j++) k[i][j] = kf.get(j).getAsFloat();
                }
                if (k.length > 0) a.parts.put(e.getKey(), k);
            }
            return a;
        }

        /** Angles (degrees) of {@code part} at {@code t} seconds, or null if this emote leaves it alone. */
        float[] sample(String part, float t, float[] out) {
            float[][] k = parts.get(part);
            if (k == null) return null;
            if (t <= k[0][0] || k.length == 1) { System.arraycopy(k[0], 1, out, 0, 3); return out; }
            for (int i = 1; i < k.length; i++) {
                if (t <= k[i][0]) {
                    float span = Math.max(1e-4f, k[i][0] - k[i - 1][0]);
                    float f = (t - k[i - 1][0]) / span;
                    f = f * f * (3 - 2 * f);
                    for (int j = 0; j < 3; j++) out[j] = k[i - 1][j + 1] + (k[i][j + 1] - k[i - 1][j + 1]) * f;
                    return out;
                }
            }
            System.arraycopy(k[k.length - 1], 1, out, 0, 3);
            return out;
        }
    }

    /** An emote playing: which, and since when (seconds on the {@link #clock}). */
    public record Playing(CosmeticItem item, Animation anim, double startedAt) {
        /** Seconds into the animation, or -1 once a one-shot emote has finished. */
        public float time(double now) {
            float t = (float) (now - startedAt);
            if (anim.loop) return t % anim.length;
            return t > anim.length ? -1 : t;
        }
    }

    private static Playing current;
    /** Why the last emote ended early (tests). */
    public static String lastStop = "";
    private static Vec3d startPos;
    private static Perspective savedPerspective;
    private static boolean switchedCamera;
    /** Test hook: no camera switch. */
    public static boolean moveCamera = true;

    private Emotes() {}

    public static double clock() { return System.nanoTime() / 1e9; }

    public static Playing current() { return current; }

    /** Starts an owned emote. Render thread. */
    public static boolean play(CosmeticItem item) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (item == null || item.slot != Slot.EMOTE || mc.player == null) return false;
        if (!Wardrobe.owns(item.id)) { Notifications.push("Emotes", "You don't own " + item.name + " yet", false); return false; }
        CosmeticAssets.request(item);
        if (item.emote() == null) { Notifications.push("Emotes", item.name + " is still loading", false); return false; }
        if (!CosmeticsEngine.allowed()) return false;
        stop();
        lastStop = "";
        current = new Playing(item, item.emote(), clock());
        startPos = mc.player.getEntityPos();
        if (moveCamera && mc.options.getPerspective() == Perspective.FIRST_PERSON) {
            savedPerspective = mc.options.getPerspective();
            mc.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            switchedCamera = true;
        }
        return true;
    }

    /** Stops the emote (if any) and puts the camera back. */
    public static void stop() {
        if (current == null) return;
        current = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (switchedCamera && savedPerspective != null && mc.options != null) mc.options.setPerspective(savedPerspective);
        switchedCamera = false;
        savedPerspective = null;
    }

    /** Every tick: stop on movement, input, damage, or at the end of a one-shot emote. */
    public static void tick(MinecraftClient mc) {
        if (current == null) return;
        var p = mc.player;
        if (p == null || !CosmeticsEngine.allowed()) { stop(); return; }
        if (current.time(clock()) < 0) { stop(); return; }
        var in = p.input == null ? null : p.input.playerInput;
        boolean input = in != null && (in.forward() || in.backward() || in.left() || in.right() || in.jump() || in.sneak());
        boolean moved = startPos != null && p.getEntityPos().squaredDistanceTo(startPos) > 0.15 * 0.15;
        String why = input ? "input" : moved ? "moved" : p.hurtTime > 0 ? "damage" : p.isUsingItem() ? "using an item"
                : p.handSwinging ? "swing" : p.isDead() ? "dead" : null;
        if (why != null) { lastStop = why; stop(); }
    }

    /** Called right before you attack: an attack always ends the emote. */
    public static void onAttack() { stop(); }

    private static final float[] TMP = new float[3];

    /** Poses the model for {@code playing} at the current time (after vanilla's angles). Any thread that renders. */
    public static void apply(PlayerEntityModel model, Playing playing) {
        if (playing == null) return;
        float t = playing.time(clock());
        if (t < 0) return;
        float elapsed = (float) (clock() - playing.startedAt());
        float w = Math.min(1f, elapsed / BLEND);
        pose(model.head, playing.anim(), "head", t, w);
        pose(model.body, playing.anim(), "body", t, w);
        pose(model.rightArm, playing.anim(), "rightArm", t, w);
        pose(model.leftArm, playing.anim(), "leftArm", t, w);
        pose(model.rightLeg, playing.anim(), "rightLeg", t, w);
        pose(model.leftLeg, playing.anim(), "leftLeg", t, w);
        // the second skin layer (hat, jacket, sleeves, pants) are child parts, so they follow
    }

    private static void pose(ModelPart part, Animation anim, String name, float t, float w) {
        float[] a = anim.sample(name, t, TMP);
        if (a == null) return;
        part.pitch += (a[0] * DEG - part.pitch) * w;
        part.yaw += (a[1] * DEG - part.yaw) * w;
        part.roll += (a[2] * DEG - part.roll) * w;
    }

    /** The emote to show on a preview model (the Wardrobe), looping from {@code since}. */
    public static Playing preview(CosmeticItem item, double since) {
        if (item == null || item.emote() == null) return null;
        Animation a = item.emote();
        return new Playing(item, a.loop ? a : loopOf(a), since);
    }

    private static final Map<Animation, Animation> LOOPED = new HashMap<>();

    private static Animation loopOf(Animation a) {
        return LOOPED.computeIfAbsent(a, x -> {
            Animation l = new Animation(x.length + 0.6f, true);
            l.parts.putAll(x.parts);
            return l;
        });
    }

}
