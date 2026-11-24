package net.evadeclient.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Replay render queue: queue several replays (each with its own saved camera path and render settings), then render
 * them one after another, e.g. overnight. Each one opens, renders, goes to Evade Clips' library (as every render
 * does) and closes before the next starts. With "share" on, Evade Clips makes a clips-share link for it and the link
 * is copied when it's ready. The queue is kept in .minecraft/evade/renders/queue.json until it's done.
 */
public final class RenderQueue {
    /** One queued render: the replay file and whether to make a share link afterwards. */
    public record Job(Path replay, boolean share) {}

    private enum Phase { IDLE, OPENING, RENDERING, CLOSING }

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final List<Job> JOBS = new ArrayList<>();
    private static boolean running, loaded;
    private static Phase phase = Phase.IDLE;
    private static Job current;
    private static Path currentOut;
    private static long phaseAt;
    private static int done, failed;
    private static volatile String lastShareUrl;
    /** Whether new queue entries also get a share link (the browser's "Share links" switch). */
    public static boolean shareNew;

    private RenderQueue() {}

    private static Path file() { return mc.runDirectory.toPath().resolve("evade").resolve("renders").resolve("queue.json"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            for (JsonElement e : JsonParser.parseString(Files.readString(f)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                Path p = Path.of(o.get("replay").getAsString());
                if (Files.isRegularFile(p)) JOBS.add(new Job(p, o.has("share") && o.get("share").getAsBoolean()));
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Replay render queue: couldn't read the queue", e);
        }
    }

    private static void save() {
        try {
            JsonArray a = new JsonArray();
            for (Job j : JOBS) {
                JsonObject o = new JsonObject();
                o.addProperty("replay", j.replay().toAbsolutePath().toString());
                o.addProperty("share", j.share());
                a.add(o);
            }
            Files.createDirectories(file().getParent());
            Files.writeString(file(), a.toString());
        } catch (Exception e) {
            EvadeClient.LOG.warn("Replay render queue: couldn't save the queue", e);
        }
    }

    /** The queued renders (not the one rendering now). */
    public static synchronized List<Job> jobs() { load(); return List.copyOf(JOBS); }

    /** Queues {@code replay} (its saved project's camera path and render settings). Replaces an earlier entry for it. */
    public static synchronized void add(Path replay, boolean share) {
        load();
        JOBS.removeIf(j -> j.replay().equals(replay));
        JOBS.add(new Job(replay, share));
        save();
    }

    public static synchronized void remove(Path replay) {
        load();
        JOBS.removeIf(j -> j.replay().equals(replay));
        save();
    }

    public static synchronized void clear() { load(); JOBS.clear(); save(); }

    public static boolean running() { return running; }

    /** "Rendering 2 of 5", "3 queued", "" */
    public static synchronized String status() {
        load();
        if (running) return "Rendering " + (done + failed + 1) + " of " + (done + failed + 1 + JOBS.size());
        return JOBS.isEmpty() ? "" : JOBS.size() + " queued";
    }

    /** Starts rendering the queue (from the menus: leaves nothing open behind). Render thread. */
    public static synchronized void start() {
        load();
        if (running || JOBS.isEmpty()) return;
        if (ReplayRender.rendering()) { Notifications.push("Render queue", "Wait for the current render to finish", false); return; }
        running = true;
        done = failed = 0;
        phase = Phase.IDLE;
        Notifications.push("Render queue", "Rendering " + JOBS.size() + (JOBS.size() == 1 ? " replay" : " replays") + ". Leave the game running", true);
    }

    /** Stops after the current render. */
    public static synchronized void stop() {
        running = false;
    }

    /** Client tick: moves the queue along. */
    static void tick() {
        if (!running) return;
        try { tick0(); }
        catch (RuntimeException e) {
            EvadeClient.LOG.warn("Replay render queue failed", e);
            running = false;
            phase = Phase.IDLE;
        }
    }

    private static synchronized void tick0() {
        long now = System.currentTimeMillis();
        switch (phase) {
            case IDLE -> {
                if (JOBS.isEmpty()) { finish(); return; }
                if (ReplayRender.rendering() || ReplayPlayer.active()) return; // let a running replay end first
                current = JOBS.remove(0);
                save();
                try {
                    ReplayPlayer.open(current.replay(), new TitleScreen());
                    phase = Phase.OPENING;
                    phaseAt = now;
                } catch (Exception e) {
                    failed++;
                    Notifications.push("Render queue", "Couldn't open " + current.replay().getFileName() + ": " + e.getMessage(), false);
                }
            }
            case OPENING -> {
                ReplayPlayer p = ReplayPlayer.current();
                if (p == null) { failed++; phase = Phase.IDLE; return; }
                if (now - phaseAt > 5 * 60_000) { failed++; p.close(); phase = Phase.CLOSING; return; }
                if (mc.world == null || p.seeking() || mc.currentScreen instanceof ReplayLoadingScreen) return;
                if (now - phaseAt < 2000) return; // let chunks settle
                currentOut = ReplayRender.start();
                if (currentOut == null) { failed++; p.close(); phase = Phase.CLOSING; return; }
                phase = Phase.RENDERING;
                phaseAt = now;
            }
            case RENDERING -> {
                if (ReplayRender.rendering()) return;
                if (currentOut.equals(ReplayRender.lastResult)) done++; else failed++;
                ReplayPlayer p = ReplayPlayer.current();
                if (p != null) p.close();
                phase = Phase.CLOSING;
                phaseAt = now;
            }
            case CLOSING -> {
                if (mc.world != null && now - phaseAt < 15_000) return;
                phase = Phase.IDLE;
            }
        }
    }

    private static void finish() {
        running = false;
        phase = Phase.IDLE;
        Notifications.push("Render queue", done + " rendered" + (failed > 0 ? ", " + failed + " failed" : "") + ". They're in Evade Clips (or .minecraft/evade/renders)", failed == 0);
    }

    // ------------------------------------------------------------------ share links (through Evade Clips)

    /** ReplayRender handed a finished video to Evade Clips ({@code id} null when Clips isn't running). Any thread. */
    static void imported(Path file, String id) {
        boolean share;
        synchronized (RenderQueue.class) { share = current != null && current.share() && file.equals(currentOut); }
        if (!share) return;
        if (id == null) {
            mc.execute(() -> Notifications.push("Render queue", "Open Evade Clips to get share links for renders", false));
            return;
        }
        share(id);
    }

    /**
     * Asks Evade Clips to upload a clip to its share server (clips-share) and copies the link. Uses the share server
     * set up in Evade Clips; nothing is uploaded when it has none.
     */
    public static void share(String clipId) {
        Thread t = new Thread(() -> {
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:47821/share?clip="
                                + URLEncoder.encode(clipId, StandardCharsets.UTF_8) + "&client=evade"))
                        .timeout(Duration.ofMinutes(15)).POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                if (res.statusCode() == 200 && o.has("url")) {
                    String url = o.get("url").getAsString();
                    lastShareUrl = url;
                    mc.execute(() -> {
                        mc.keyboard.setClipboard(url);
                        Notifications.push("Render shared", "Link copied: " + url, true);
                    });
                } else {
                    String err = o.has("error") ? o.get("error").getAsString() : "Evade Clips couldn't share it";
                    mc.execute(() -> Notifications.push("Render queue", err, false));
                }
            } catch (Exception e) {
                mc.execute(() -> Notifications.push("Render queue", "Evade Clips didn't answer; share it from its library", false));
            }
        }, "Evade render share");
        t.setDaemon(true);
        t.start();
    }

    /** The last share link made for a render, or null. */
    public static String lastShareUrl() { return lastShareUrl; }
}
