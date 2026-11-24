package net.evadeclient.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.LauncherJoinScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The small file channel between the game and the Evade Launcher, inside the game folder's {@code .evade/}:
 * <ul>
 *   <li>{@code session.json}: a heartbeat every 5 seconds (process id, start time, last beat, menu / world / server),
 *   marked {@code clean} when the game closes normally. The launcher shows "Playing", can stop a hung game, and
 *   spots a session that ended without closing cleanly (a crash).</li>
 *   <li>{@code playtime.json}: total play time, sessions and last played, kept here so it is counted even while the
 *   launcher is closed.</li>
 *   <li>{@code launch-request.json}: a one-shot request the launcher writes when the player clicks Join (a server) or
 *   Continue (a world) there. It is read only on the title screen, deleted on first read, and ignored when older than
 *   two minutes. The game then shows "Joining X (requested from the launcher)" for three seconds with Cancel; nothing
 *   connects unless the player asked for it in the launcher moments before.</li>
 * </ul>
 * Everything stays on this computer; nothing is sent anywhere.
 */
public final class LauncherBridge {
    private LauncherBridge() {}

    public static final String DIR = ".evade", SESSION = "session.json", PLAYTIME = "playtime.json", REQUEST = "launch-request.json";
    /** A request older than this is ignored (and removed). */
    public static final long REQUEST_TTL_MS = 120_000;
    static final long BEAT_MS = 5_000;

    private static final long STARTED = System.currentTimeMillis();
    private static volatile String state = "menu", server, serverName, world, worldFolder;
    private static volatile boolean clean;
    private static Path dir;
    private static long lastCount = STARTED;
    private static int requestTicks;

    /** One-time setup from the client initializer. */
    public static void register() {
        try {
            dir = MinecraftClient.getInstance().runDirectory.toPath().resolve(DIR);
            Files.createDirectories(dir);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Launcher bridge off: {}", e.toString());
            return;
        }
        countSession();
        Thread beat = new Thread(() -> {
            while (true) {
                try {
                    beat(false);
                    Thread.sleep(BEAT_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(BEAT_MS); } catch (InterruptedException e) { return; }
                }
            }
        }, "evade-launcher-heartbeat");
        beat.setDaemon(true);
        beat.start();
        ClientTickEvents.END_CLIENT_TICK.register(LauncherBridge::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> { clean = true; beat(true); });
        // A crash ends the game with System.exit: the session is marked ended but not clean.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> beat(true), "evade-launcher-bye"));
    }

    /** Once a second on the client thread: what the player is doing, and any launcher request on the title screen. */
    private static void tick(MinecraftClient mc) {
        if (++requestTicks % 20 != 0) return;
        ServerInfo entry = mc.getCurrentServerEntry();
        IntegratedServer sp = mc.getServer();
        if (sp != null) {
            state = "singleplayer";
            server = null;
            serverName = null;
            try {
                world = sp.getSaveProperties().getLevelName();
                Path root = sp.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
                worldFolder = root.getFileName() != null ? root.getFileName().toString() : null;
            } catch (RuntimeException ignored) { }
        } else if (entry != null && mc.getNetworkHandler() != null) {
            state = "multiplayer";
            server = entry.address;
            serverName = entry.name;
        } else {
            state = "menu";
        }
        Screen s = mc.currentScreen;
        if (s instanceof net.minecraft.client.gui.screen.TitleScreen || s instanceof net.evadeclient.gui.EvadeTitleScreen) {
            Request r = takeRequest(dir, System.currentTimeMillis());
            if (r != null) mc.setScreen(new LauncherJoinScreen(s, r));
        }
    }

    // ------------------------------------------------------------------ heartbeat and play time

    private static synchronized void beat(boolean ending) {
        if (dir == null) return;
        long now = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("pid", ProcessHandle.current().pid());
        o.addProperty("started", STARTED);
        o.addProperty("beat", now);
        o.addProperty("version", EvadeClient.VERSION);
        o.addProperty("state", state);
        if (server != null) o.addProperty("server", server);
        if (serverName != null) o.addProperty("serverName", serverName);
        if (world != null) o.addProperty("world", world);
        if (worldFolder != null) o.addProperty("worldFolder", worldFolder);
        o.addProperty("ended", ending ? now : 0);
        o.addProperty("clean", clean);
        write(dir.resolve(SESSION), o.toString());
        // play time: what passed since the last count (a long stall counts at most two beats)
        JsonObject p = read(dir.resolve(PLAYTIME));
        long add = Math.max(0, Math.min(now - lastCount, 2 * BEAT_MS));
        lastCount = now;
        p.addProperty("totalMs", num(p, "totalMs") + add);
        p.addProperty("lastPlayed", now);
        if (worldFolder != null) {
            p.addProperty("lastWorld", worldFolder);
            if (world != null) p.addProperty("lastWorldName", world);
        }
        if (server != null) p.addProperty("lastServer", server);
        write(dir.resolve(PLAYTIME), p.toString());
    }

    private static void countSession() {
        JsonObject p = read(dir.resolve(PLAYTIME));
        p.addProperty("sessions", num(p, "sessions") + 1);
        write(dir.resolve(PLAYTIME), p.toString());
    }

    // ------------------------------------------------------------------ requests

    /** A launcher request: join {@code target} (a server address) or open {@code target} (a world folder). */
    public record Request(String type, String target, String name) {
        public boolean world() { return "world".equals(type); }
    }

    /**
     * Reads and removes the request file. Returns it only when it is well-formed, at most two minutes old (and not
     * from the future), else null. The file is deleted either way, so each request is used once.
     */
    public static Request takeRequest(Path dir, long now) {
        if (dir == null) return null;
        Path f = dir.resolve(REQUEST);
        if (!Files.isRegularFile(f)) return null;
        String text;
        try {
            if (Files.size(f) > 4096) { Files.deleteIfExists(f); return null; }
            text = Files.readString(f, StandardCharsets.UTF_8);
            Files.deleteIfExists(f);
        } catch (Exception e) {
            return null;
        }
        return parseRequest(text, now);
    }

    /** The request in {@code json} if it's valid at {@code now}, else null. */
    public static Request parseRequest(String json, long now) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String type = o.get("type").getAsString(), target = o.get("target").getAsString().strip();
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString().strip() : target;
            long created = o.get("created").getAsLong();
            if (now - created > REQUEST_TTL_MS || created - now > 10_000) return null;
            if (target.isEmpty() || target.length() > 255) return null;
            if (name.isEmpty()) name = target;
            if (name.length() > 64) name = name.substring(0, 64);
            if ("server".equals(type)) {
                if (!target.matches("[A-Za-z0-9.:\\-_\\[\\]]+")) return null;
            } else if ("world".equals(type)) {
                if (target.contains("/") || target.contains("\\") || target.equals("..") || target.equals(".")) return null;
            } else return null;
            return new Request(type.toLowerCase(Locale.ROOT), target, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ files

    private static JsonObject read(Path f) {
        try {
            if (Files.isRegularFile(f)) return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) { }
        return new JsonObject();
    }

    private static long num(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    private static void write(Path f, String text) {
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) { /* the launcher just sees an older beat */ }
    }

    /** The bridge folder (for tests). */
    public static Path dir() { return dir; }
}
