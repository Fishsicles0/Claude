package net.evadeclient.installer;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What the Evade mod tells the launcher through files in an instance's {@code .evade/} folder (written by the mod's
 * LauncherBridge): whether the game runs right now, its play time, and one-shot Join / Continue requests. The
 * launcher never owns the game process (the Minecraft Launcher starts it), so the heartbeat's process id is the only
 * handle it has, used for "Stop game" after the player confirms.
 */
final class GameState {
    private GameState() {}

    static final String DIR = ".evade", SESSION = "session.json", PLAYTIME = "playtime.json", REQUEST = "launch-request.json";
    /** The mod beats every 5 s: older than this and the game is called "not responding". */
    static final long STALE_MS = 20_000;
    /** Older than this (and the process gone) the session is over whatever its file says. */
    static final long GONE_MS = 120_000;
    static final long REQUEST_TTL_MS = 120_000;

    /** One game session as its heartbeat file describes it. */
    record Session(long pid, long started, long beat, String version, String state, String server, String serverName,
                   String world, String worldFolder, long ended, boolean clean, String gameDir) {
        boolean endedCleanly() { return ended > 0 && clean; }
    }

    /** Where a session stands now. */
    enum Run { NONE, RUNNING, NOT_RESPONDING, ENDED_CLEAN, ENDED_BADLY }

    /** Play time from playtime.json. */
    record Playtime(long totalMs, long lastPlayed, int sessions, String lastWorld, String lastWorldName, String lastServer) {
        static final Playtime NONE = new Playtime(0, 0, 0, null, null, null);
    }

    static Path dir(Path instance) { return instance.resolve(DIR); }

    static Session readSession(Path instance) {
        JsonObject o = InstallerCore.readJsonObject(dir(instance).resolve(SESSION));
        if (!o.has("pid") || !o.has("beat")) return null;
        try {
            return new Session(o.get("pid").getAsLong(), num(o, "started"), num(o, "beat"), InstallerCore.str(o, "version"),
                    InstallerCore.str(o, "state"), InstallerCore.str(o, "server"), InstallerCore.str(o, "serverName"),
                    InstallerCore.str(o, "world"), InstallerCore.str(o, "worldFolder"), num(o, "ended"),
                    o.has("clean") && o.get("clean").getAsBoolean(), InstallerCore.str(o, "gameDir"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Playtime readPlaytime(Path instance) {
        JsonObject o = InstallerCore.readJsonObject(dir(instance).resolve(PLAYTIME));
        if (o.size() == 0) return Playtime.NONE;
        return new Playtime(num(o, "totalMs"), num(o, "lastPlayed"), (int) num(o, "sessions"), InstallerCore.str(o, "lastWorld"),
                InstallerCore.str(o, "lastWorldName"), InstallerCore.str(o, "lastServer"));
    }

    /** Where {@code s} stands at {@code now}; {@code alive} says whether its process still exists. */
    static Run classify(Session s, long now, boolean alive) {
        if (s == null) return Run.NONE;
        if (s.ended() > 0) return s.clean() ? Run.ENDED_CLEAN : Run.ENDED_BADLY;
        long age = now - s.beat();
        if (alive && age < STALE_MS) return Run.RUNNING;
        if (alive && age < GONE_MS) return Run.NOT_RESPONDING;
        // no clean goodbye: killed, or the JVM died before its shutdown hook ran
        return Run.ENDED_BADLY;
    }

    static boolean alive(long pid) {
        try {
            Optional<ProcessHandle> h = ProcessHandle.of(pid);
            return h.isPresent() && h.get().isAlive();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Run run(Path instance, long now) {
        Session s = readSession(instance);
        return classify(s, now, s != null && alive(s.pid()));
    }

    static boolean isRunning(Path instance) {
        Run r = run(instance, System.currentTimeMillis());
        return r == Run.RUNNING || r == Run.NOT_RESPONDING;
    }

    /**
     * Ends the game process named by the heartbeat: a normal close first, then a forced one after 5 seconds. Returns
     * false when there was no such process.
     */
    static boolean stop(Session s) {
        if (s == null) return false;
        Optional<ProcessHandle> h = ProcessHandle.of(s.pid());
        if (h.isEmpty() || !h.get().isAlive()) return false;
        ProcessHandle p = h.get();
        // only a Java process: a reused process id must never be killed
        String cmd = p.info().command().orElse("").toLowerCase(java.util.Locale.ROOT);
        if (!cmd.isEmpty() && !cmd.contains("java")) return false;
        p.destroy();
        try {
            p.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            p.destroyForcibly();
        }
        return true;
    }

    /** Writes a one-shot request for the Evade mod: join a server ({@code world} false) or open a world folder. */
    static void writeRequest(Path instance, boolean world, String target, String name) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("type", world ? "world" : "server");
        o.addProperty("target", target);
        o.addProperty("name", name == null || name.isBlank() ? target : name);
        long now = System.currentTimeMillis();
        o.addProperty("created", now);
        o.addProperty("expires", now + REQUEST_TTL_MS);
        o.addProperty("from", "launcher");
        Files.createDirectories(dir(instance));
        InstallerCore.writeAtomic(dir(instance).resolve(REQUEST), InstallerCore.GSON.toJson(o));
    }

    /** A request still waiting for the game (not yet taken and not expired), or null. */
    static JsonObject pendingRequest(Path instance) {
        JsonObject o = InstallerCore.readJsonObject(dir(instance).resolve(REQUEST));
        if (o.size() == 0) return null;
        return System.currentTimeMillis() - num(o, "created") < REQUEST_TTL_MS ? o : null;
    }

    static void cancelRequest(Path instance) {
        try { Files.deleteIfExists(dir(instance).resolve(REQUEST)); } catch (IOException ignored) { }
    }

    /** The world to continue: the last one the mod saw, else the most recently played one in saves/. Folder name and display name. */
    static String[] lastWorld(Path instance) {
        Playtime p = readPlaytime(instance);
        if (p.lastWorld() != null && Files.isRegularFile(instance.resolve("saves").resolve(p.lastWorld()).resolve("level.dat")))
            return new String[]{p.lastWorld(), p.lastWorldName() != null ? p.lastWorldName() : p.lastWorld()};
        Path best = null;
        long bestT = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instance.resolve("saves"))) {
            for (Path d : ds) {
                Path lvl = d.resolve("level.dat");
                if (!Files.isRegularFile(lvl)) continue;
                long t = Files.getLastModifiedTime(lvl).toMillis();
                if (t > bestT) { bestT = t; best = d; }
            }
        } catch (IOException ignored) { }
        return best == null ? null : new String[]{best.getFileName().toString(), best.getFileName().toString()};
    }

    private static long num(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    /** "41 h 12 min", "12 min", "under a minute". */
    static String duration(long ms) {
        long m = ms / 60_000;
        if (m < 1) return "under a minute";
        if (m < 60) return m + " min";
        long h = m / 60;
        return h + " h" + (m % 60 == 0 || h >= 10 ? "" : " " + (m % 60) + " min");
    }

    /** "today", "yesterday", "3 days ago", "never". */
    static String when(long at, long now) {
        if (at <= 0) return "never";
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(at).atZone(z).toLocalDate(),
                java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate());
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 30) return days + " days ago";
        return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(java.time.Instant.ofEpochMilli(at).atZone(z));
    }
}
