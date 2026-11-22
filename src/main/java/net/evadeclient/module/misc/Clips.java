package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to the Evade Clips desktop app (http://127.0.0.1:47821) so you never miss a play: saves a clip on a key
 * press, or by itself when you get a kill, pop someone, die or win. Events close together become one clip
 * ("Triple kill"), and it waits a few seconds after the last one so the clip shows the aftermath too.
 */
public class Clips extends Module {
    private final KeySetting saveKey = add(new KeySetting("Save Clip Key", GLFW.GLFW_KEY_UNKNOWN));
    private final BooleanSetting kills = add(new BooleanSetting("Auto Clip Kills", true));
    private final BooleanSetting pops = add(new BooleanSetting("Auto Clip Pops", true));
    private final BooleanSetting deaths = add(new BooleanSetting("Auto Clip Deaths", false));
    private final BooleanSetting wins = add(new BooleanSetting("Auto Clip Wins", true));
    private final BooleanSetting advancements = add(new BooleanSetting("Auto Clip Advancements", true));
    private final NumberSetting before = add(new NumberSetting("Seconds Before", 20, 5, 120, 5));
    private final NumberSetting after = add(new NumberSetting("Seconds After", 4, 0, 15, 1));
    private final NumberSetting port = add(new NumberSetting("App Port", 47821, 1024, 65535, 1));
    private final KeySetting openKey = add(new KeySetting("Open Clips App", GLFW.GLFW_KEY_UNKNOWN));
    private final BooleanSetting launchApp = add(new BooleanSetting("Start Evade Clips With Game", true));
    private boolean openWasDown, launchChecked;
    private long lastClipNotice;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();

    /** Players we hit recently, so we know a death near us was our kill. */
    private final Map<UUID, Long> hitAt = new HashMap<>();
    private final Map<UUID, Boolean> wasAlive = new HashMap<>();
    private boolean wasDead, keyWasDown;
    private long lastWarn;

    // pending clip (events merge while it waits)
    private final List<String> events = new ArrayList<>();
    private long firstEvent, fireAt;
    private int killCount;

    public Clips() {
        super("Clips", "Saves clips in Evade Clips: on a key, or automatically on kills, pops, deaths and wins.", Category.MISC);
        setEnabled(true);
    }

    @Override
    public void onAttack(Entity target) {
        if (target instanceof PlayerEntity p) hitAt.put(p.getUuid(), System.currentTimeMillis());
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        boolean down = saveKey.isDown();
        if (down && !keyWasDown) send(before.getInt(), "Clip", "manual");
        keyWasDown = down;
        boolean open = openKey.isDown();
        if (open && !openWasDown) openApp(null);
        openWasDown = open;
        if (!launchChecked) { launchChecked = true; ensureAppRunning(); }

        // Kills: a player we hit in the last 10 s just died.
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            boolean alive = p.isAlive() && !p.isDead();
            Boolean prev = wasAlive.put(p.getUuid(), alive);
            Long hit = hitAt.get(p.getUuid());
            if (Boolean.TRUE.equals(prev) && !alive && hit != null && now - hit < 10_000 && kills.get()) {
                killCount++;
                event("Kill " + p.getName().getString());
            }
        }
        hitAt.values().removeIf(t -> now - t > 15_000);
        if (wasAlive.size() > 256) wasAlive.clear();

        boolean dead = mc.player.isDead();
        if (dead && !wasDead) { if (deaths.get()) event("Death"); else mark("Death"); }
        wasDead = dead;

        if (!events.isEmpty() && now >= fireAt) flush();
    }

    /** From the network mixin: someone popped a totem. Clip it if it's a player we've been fighting. */
    public static void onPop(Entity e) {
        Clips c = get();
        if (c == null || !c.pops.get() || !(e instanceof PlayerEntity p) || p == EvadeClient.mc().player) return;
        Long hit = c.hitAt.get(p.getUuid());
        if (hit != null && System.currentTimeMillis() - hit < 10_000) c.event("Popped " + p.getName().getString());
    }

    private static final java.util.regex.Pattern ADVANCEMENT = java.util.regex.Pattern.compile(
            "^(\\S+) has (made the advancement|completed the challenge|reached the goal) \\[(.+)]$");

    /** From chat: end-of-game messages (as AutoGG recognises them) and your own advancements. */
    public static void onMessage(String text) {
        Clips c = get();
        if (c == null || EvadeClient.mc().player == null) return;
        var m = ADVANCEMENT.matcher(text.trim());
        if (m.matches()) {
            if (c.advancements.get() && m.group(1).equals(EvadeClient.mc().player.getName().getString())) c.event(m.group(3));
            return;
        }
        if (c.wins.get() && !EvadeClient.mc().player.isDead() && AutoGG.matches(text)) c.event("Win");
    }

    private static Clips get() {
        if (EvadeClient.modules == null) return null;
        Clips c = EvadeClient.modules.get(Clips.class);
        return c != null && c.isEnabled() ? c : null;
    }

    private void event(String what) {
        mark(what);
        long now = System.currentTimeMillis();
        if (events.isEmpty()) firstEvent = now;
        events.add(what);
        fireAt = now + after.getInt() * 1000L;
    }

    private void flush() {
        long span = (System.currentTimeMillis() - firstEvent) / 1000;
        int seconds = (int) Math.min(600, before.getInt() + span);
        String title = killCount >= 2 ? multi(killCount) : events.get(0);
        List<String> tags = new ArrayList<>();
        for (String e : events) {
            String tag = e.split(" ")[0].toLowerCase();
            if (!tags.contains(tag)) tags.add(tag);
        }
        if (killCount >= 2) tags.add("multikill");
        events.clear();
        killCount = 0;
        send(seconds, title, String.join(",", tags));
    }

    private static String multi(int n) {
        return switch (n) { case 2 -> "Double kill"; case 3 -> "Triple kill"; case 4 -> "Quad kill"; default -> n + "-kill streak"; };
    }

    private void send(int seconds, String title, String tags) {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Singleplayer";
        String url = "http://127.0.0.1:" + port.getInt() + "/clip?seconds=" + seconds + "&game=Minecraft"
                + "&title=" + enc(title + " · " + server) + "&tag=" + enc(tags);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> mc.execute(() -> {
            if (err != null) {
                if (System.currentTimeMillis() - lastWarn > 60_000) {
                    lastWarn = System.currentTimeMillis();
                    Notifications.push("Clips", "Open Evade Clips to save clips", false);
                }
            } else if (res.statusCode() == 200) {
                Notifications.push("Clip saved", title + "  ·  .clips last to edit", true);
            } else {
                Notifications.push("Clip not saved", res.body().replaceAll(".*\"error\":\"([^\"]*)\".*", "$1"), false);
            }
        }));
    }

    // ------------------------------------------------------------------ the Evade Clips app

    /** {@code .clips} opens the app, {@code .clips last} opens your newest clip in its editor, {@code .clips save} clips now. */
    public static void command(String[] a) {
        Clips c = EvadeClient.modules == null ? null : EvadeClient.modules.get(Clips.class);
        if (c == null) return;
        String sub = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : "";
        if (sub.equals("save")) c.send(c.before.getInt(), "Clip", "manual");
        else c.openApp(sub.equals("last") ? "last" : null);
    }

    /** Brings Evade Clips forward (starting it if needed), optionally opening a clip in its editor. */
    void openApp(String clip) {
        String url = "http://127.0.0.1:" + port.getInt() + "/open" + (clip == null ? "" : "?clip=" + enc(clip));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> mc.execute(() -> {
            if (err == null) return;
            if (!launch(false)) Notifications.push("Evade Clips", "Not installed: get it from evadeclient.com", false);
        }));
    }

    /** Starts Evade Clips in the tray when the game starts, so instant replay is already running when you play. */
    private void ensureAppRunning() {
        if (!launchApp.get()) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.getInt() + "/status")).timeout(Duration.ofSeconds(2)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            if (err != null || res.statusCode() != 200) mc.execute(() -> launch(true));
        });
    }

    /** Starts the installed app (location recorded by the app / installer). Returns false if it isn't installed. */
    private static boolean launch(boolean minimized) {
        try {
            java.nio.file.Path loc = appDir().resolve("location.json");
            if (!java.nio.file.Files.exists(loc)) return false;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(loc)).getAsJsonObject();
            List<String> cmd = new ArrayList<>();
            if (o.has("exe") && java.nio.file.Files.exists(java.nio.file.Path.of(o.get("exe").getAsString()))) {
                cmd.add(o.get("exe").getAsString());
            } else if (o.has("jar") && java.nio.file.Files.exists(java.nio.file.Path.of(o.get("jar").getAsString()))) {
                String javaBin = o.has("java") && java.nio.file.Files.exists(java.nio.file.Path.of(o.get("java").getAsString()))
                        ? o.get("java").getAsString() : java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
                cmd.add(javaBin);
                cmd.add("-jar");
                cmd.add(o.get("jar").getAsString());
            } else return false;
            if (minimized) cmd.add("--minimized");
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (minimized) Notifications.push("Evade Clips", "Instant replay is starting", true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Same folder Evade Clips uses for its settings. */
    static java.nio.file.Path appDir() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win") && System.getenv("APPDATA") != null) return java.nio.file.Path.of(System.getenv("APPDATA"), "EvadeClips");
        return java.nio.file.Path.of(System.getProperty("user.home"), ".evade-clips");
    }

    /** Drops a named marker in Evade Clips right away (shows on the recording's timeline). */
    private void mark(String label) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.getInt() + "/marker?label=" + enc(label)))
                .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
