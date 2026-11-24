package net.evadeclient.social;

import com.google.gson.JsonObject;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.module.misc.StreamerMode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Richer presence: what you're doing ("In menus", "Singleplayer", "On a server", "AFK") and since when, sent with your
 * Friends presence when Share Activity is on, and what your friends are doing, as evadeclient.com last told us.
 * Friends and your party see it only while you're online; evadeclient.com forgets it when you go offline.
 *
 * <p>AFK means no mouse movement, key press, camera turn or step for {@link #AFK_MS}; anything you do ends it.
 * Nothing about where you are in a world is sent: only the kind of place and how long you've been there.
 */
public final class SocialPresence {
    /** How long without any input before you count as AFK. */
    public static final long AFK_MS = 5 * 60_000L;

    /** What a player is doing. {@code wire} is the API value. */
    public enum Activity {
        MENUS("menus", "In menus"), SINGLEPLAYER("singleplayer", "Singleplayer"), SERVER("server", "On a server"), AFK("afk", "AFK");

        public final String wire, label;
        Activity(String wire, String label) { this.wire = wire; this.label = label; }

        public static Activity of(String wire) {
            for (Activity a : values()) if (a.wire.equals(wire)) return a;
            return null;
        }
    }

    /** A friend's activity and since when (ms), as last reported. */
    public record Status(Activity activity, long since) {}

    private static final Map<UUID, Status> FRIENDS = new ConcurrentHashMap<>();
    private static boolean registered;
    // Input tracking (render thread).
    private static long lastInput = System.currentTimeMillis();
    private static double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
    private static float lastYaw, lastPitch;
    private static double lastX, lastZ;
    private static Object lastScreen;
    private static String placeKey = "";
    private static long placeSince = System.currentTimeMillis();
    private static Activity forced;

    private SocialPresence() {}

    /** Hooks the client tick (AFK and place tracking). Safe to call more than once. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SocialPresence::tick);
    }

    private static void tick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        boolean moved = false;
        if (mc.mouse != null) {
            double mx = mc.mouse.getX(), my = mc.mouse.getY();
            if (mx != lastMouseX || my != lastMouseY) moved = !Double.isNaN(lastMouseX);
            lastMouseX = mx;
            lastMouseY = my;
        }
        if (mc.player != null) {
            float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
            double x = mc.player.getX(), z = mc.player.getZ();
            if (yaw != lastYaw || pitch != lastPitch || Math.abs(x - lastX) > 0.01 || Math.abs(z - lastZ) > 0.01) moved = true;
            lastYaw = yaw;
            lastPitch = pitch;
            lastX = x;
            lastZ = z;
            if (mc.player.handSwinging) moved = true;
        }
        if (mc.currentScreen != lastScreen) { lastScreen = mc.currentScreen; moved = true; }
        if (mc.options != null && (mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed() || mc.options.leftKey.isPressed()
                || mc.options.rightKey.isPressed() || mc.options.jumpKey.isPressed() || mc.options.attackKey.isPressed()
                || mc.options.useKey.isPressed() || mc.options.sneakKey.isPressed())) moved = true;
        if (moved) lastInput = now;
        String key = place(mc);
        if (!key.equals(placeKey)) { placeKey = key; placeSince = now; }
    }

    /** "menus", "sp" or the server address: a change starts the "since" clock again. */
    private static String place(MinecraftClient mc) {
        if (mc.world == null) return "menus";
        if (mc.isInSingleplayer()) return "sp";
        String s = SocialClient.currentServer(mc);
        return s == null ? "mp" : "mp:" + s;
    }

    /** What you're doing now. Render thread. */
    public static Activity current(MinecraftClient mc) {
        if (forced != null) return forced;
        if (System.currentTimeMillis() - lastInput >= AFK_MS) return Activity.AFK;
        if (mc.world == null) return Activity.MENUS;
        return mc.isInSingleplayer() ? Activity.SINGLEPLAYER : Activity.SERVER;
    }

    /** When you started on the current world, server or menus (AFK doesn't reset it). */
    public static long since() { return placeSince; }

    /** Whether you count as AFK now. */
    public static boolean afk() { return forced == Activity.AFK || (forced == null && System.currentTimeMillis() - lastInput >= AFK_MS); }

    /** Whether your activity goes out with your presence (Friends → Share Activity). */
    public static boolean sharing() {
        Friends f = Friends.get();
        return f == null || f.shareActivity.get();
    }

    /** Adds "activity" and "since" (or activity: null when not sharing) to a presence body. Render thread. */
    static void addTo(JsonObject body, MinecraftClient mc) {
        if (!sharing()) {
            body.add("activity", com.google.gson.JsonNull.INSTANCE);
            return;
        }
        body.addProperty("activity", current(mc).wire);
        body.addProperty("since", since());
    }

    /** A short key for "did it change": the activity, or "off". */
    static String key(MinecraftClient mc) {
        return sharing() ? current(mc).wire + "@" + since() : "off";
    }

    // ------------------------------------------------------------------ friends' activity

    /** Remembers what {@code o} (a person in state, or a friend.status event) says {@code u} is doing. Any thread. */
    static void read(UUID u, JsonObject o) {
        if (u == null) return;
        Activity a = Activity.of(SocialState.str(o, "activity"));
        if (a == null || !SocialState.bool(o, "online")) FRIENDS.remove(u);
        else FRIENDS.put(u, new Status(a, SocialState.lng(o, "activeSince")));
    }

    /** What {@code u} is doing, or null (offline, not shared, or an older client). */
    public static Status of(UUID u) { return u == null ? null : FRIENDS.get(u); }

    /** Forgets every friend's activity (Friends switched off). */
    static void clear() { FRIENDS.clear(); }

    /**
     * The status line under a friend's name: "Online · play.example.net", or with richer presence "On a server ·
     * play.example.net · 40m", "Singleplayer · 1h 5m", "AFK · 12m", "In menus".
     */
    public static String describe(SocialState.Person p) {
        if (!p.online()) return null;
        Status st = of(p.uuid());
        String server = p.server() != null ? StreamerMode.friendServer(p.server()) : null;
        if (st == null) return server != null ? "Online  ·  " + server : "Online";
        StringBuilder b = new StringBuilder(st.activity().label);
        if (server != null && (st.activity() == Activity.SERVER || st.activity() == Activity.AFK)) b.append("  ·  ").append(server);
        String d = duration(System.currentTimeMillis() - st.since());
        if (st.since() > 0 && d != null && st.activity() != Activity.MENUS) b.append("  ·  ").append(d);
        return b.toString();
    }

    /** "5m", "1h 5m", "3h"; null under a minute or for nonsense. */
    public static String duration(long ms) {
        if (ms < 60_000 || ms > 30L * 24 * 3600_000) return null;
        long m = ms / 60_000, h = m / 60;
        if (h == 0) return m + "m";
        return m % 60 == 0 || h >= 10 ? h + "h" : String.format(Locale.ROOT, "%dh %dm", h, m % 60);
    }

    // ------------------------------------------------------------------ test support

    /** Test hook: pretend to be doing {@code a} (null: back to real tracking). */
    public static void forceForTest(Activity a) { forced = a; }
}
