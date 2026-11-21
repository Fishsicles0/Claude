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
    private final NumberSetting before = add(new NumberSetting("Seconds Before", 20, 5, 120, 5));
    private final NumberSetting after = add(new NumberSetting("Seconds After", 4, 0, 15, 1));
    private final NumberSetting port = add(new NumberSetting("App Port", 47821, 1024, 65535, 1));

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
        if (dead && !wasDead && deaths.get()) event("Death");
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

    /** From chat: the end-of-game messages AutoGG already recognises. */
    public static void onMessage(String text) {
        Clips c = get();
        if (c == null || !c.wins.get() || EvadeClient.mc().player == null || EvadeClient.mc().player.isDead()) return;
        if (AutoGG.matches(text)) c.event("Win");
    }

    private static Clips get() {
        if (EvadeClient.modules == null) return null;
        Clips c = EvadeClient.modules.get(Clips.class);
        return c != null && c.isEnabled() ? c : null;
    }

    private void event(String what) {
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
                Notifications.push("Clip saved", title, true);
            } else {
                Notifications.push("Clip not saved", res.body().replaceAll(".*\"error\":\"([^\"]*)\".*", "$1"), false);
            }
        }));
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
