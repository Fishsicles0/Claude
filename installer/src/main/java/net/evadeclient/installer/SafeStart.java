package net.evadeclient.installer;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Start in safe mode": the launcher's half of Safe Start. The launcher writes a one-shot request file into the
 * instance's game folder, then plays as usual; the Evade mod reads it at start and starts with every Evade module
 * off (its Safe Start screen then lets the player turn them back on one at a time).
 * <p>
 * <b>The file contract</b> (shared with the mod; keep both sides in step):
 * <pre>
 *   &lt;gameDir&gt;/.evade/safe-start.json
 *   {"requested": &lt;epoch milliseconds when the player asked&gt;, "by": "launcher"}
 * </pre>
 * <ul>
 *   <li>Written atomically (a temporary file, then a move), UTF-8 JSON. {@code by} names who asked ("launcher"
 *       here; the mod's own crash counter may write "crash-guard").</li>
 *   <li>The mod deletes the file once it has acted on it (one safe start per request), and should ignore a request
 *       older than {@link #TTL_MS} (the player asked for a safe start a while ago and then started normally from
 *       elsewhere).</li>
 *   <li>The launcher never reads it back except to show "safe start requested" and to cancel a request it made.</li>
 * </ul>
 */
final class SafeStart {
    private SafeStart() {}

    static final String FILE = "safe-start.json";
    /** A request older than this is stale (the mod ignores and removes it). */
    static final long TTL_MS = 10 * 60_000L;

    static Path file(Path gameDir) { return GameState.dir(gameDir).resolve(FILE); }

    /** Asks the next start of the game in {@code gameDir} to come up in safe mode. */
    static void request(Path gameDir) throws IOException {
        request(gameDir, System.currentTimeMillis());
    }

    static void request(Path gameDir, long now) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("requested", now);
        o.addProperty("by", "launcher");
        Files.createDirectories(GameState.dir(gameDir));
        InstallerCore.writeAtomic(file(gameDir), InstallerCore.GSON.toJson(o));
    }

    /** Whether a fresh request is waiting for the game (not yet picked up, not stale). */
    static boolean pending(Path gameDir, long now) {
        JsonObject o = InstallerCore.readJsonObject(file(gameDir));
        if (!o.has("requested")) return false;
        try {
            long at = o.get("requested").getAsLong();
            return now - at >= 0 && now - at < TTL_MS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Takes back a request the launcher made (the player changed their mind before playing). */
    static void cancel(Path gameDir) {
        try { Files.deleteIfExists(file(gameDir)); } catch (IOException ignored) { }
    }
}
