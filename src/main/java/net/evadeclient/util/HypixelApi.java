package net.evadeclient.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A small, polite client for Hypixel's public API ({@code api.hypixel.net/v2/player}) using the player's own API key.
 *
 * <ul>
 *   <li>The key is sent only to {@code https://api.hypixel.net}, in the {@code API-Key} header, and is never logged.</li>
 *   <li>Results (found or not) are cached for 10 minutes per UUID.</li>
 *   <li>At most one request every 0.5 s, at most {@value #WINDOW_MAX} per 5 minutes (Hypixel allows far more for a
 *       personal key), one request in flight at a time, and a back-off when the API says to slow down.</li>
 *   <li>Requests are asynchronous: {@link #lookup} never blocks; it returns what is cached and queues the rest.</li>
 *   <li>Only real (version 4) account UUIDs are looked up, so nicked players and NPCs are never queried.</li>
 * </ul>
 *
 * {@link #lookup} and {@link #tick} run on the client thread; responses are handled on the HTTP client's threads.
 */
public final class HypixelApi {
    private HypixelApi() {}

    /** What the tab list can show for one player. {@code found} is false for a UUID Hypixel has no player for. */
    public record Stats(boolean found, int networkLevel, int bedwarsStar, String skywarsLevel, int duelsWins) {
        static final Stats NONE = new Stats(false, 0, 0, "", 0);
    }

    private record Cached(Stats stats, long at) {}

    private static final String ENDPOINT = "https://api.hypixel.net/v2/player?uuid=";
    private static final Pattern KEY = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final long TTL_MS = 10 * 60_000L, FAIL_TTL_MS = 2 * 60_000L;
    private static final long MIN_GAP_MS = 500, WINDOW_MS = 5 * 60_000L;
    private static final int WINDOW_MAX = 100, QUEUE_MAX = 200, CACHE_MAX = 3000;

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    /** Client thread only. */
    private static final LinkedHashSet<UUID> QUEUE = new LinkedHashSet<>();
    /** Send times of recent requests (client thread only). */
    private static final ArrayDeque<Long> SENT = new ArrayDeque<>();
    private static volatile boolean inFlight;
    private static volatile long backoffUntil;
    private static volatile boolean keyRejected;
    private static volatile String lastError = "";
    private static long lastSent;
    /** The key the rejection and cache belong to (a new key starts over). */
    private static volatile String keyInUse = "";
    private static HttpClient http;

    /** Whether {@code key} looks like a Hypixel API key (a UUID). */
    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key.strip()).matches();
    }

    /** Cached stats for {@code id}, queueing a lookup when there are none or they are older than 10 minutes. */
    public static Stats lookup(UUID id) {
        if (id == null || id.version() != 4) return null;
        Cached c = CACHE.get(id);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at < (c.stats.found() || c.stats == Stats.NONE ? TTL_MS : FAIL_TTL_MS)) return c.stats;
        if (!keyRejected && QUEUE.size() < QUEUE_MAX) QUEUE.add(id);
        return c == null ? null : c.stats;
    }

    /** Sends the next queued request when the rate limits allow. Client thread, every tick. */
    public static void tick(String key) {
        if (key == null) key = "";
        key = key.strip();
        if (!key.equals(keyInUse)) {
            keyInUse = key;
            keyRejected = false;
            lastError = "";
            backoffUntil = 0;
        }
        if (QUEUE.isEmpty() || inFlight || keyRejected || !validKey(key)) return;
        long now = System.currentTimeMillis();
        if (now < backoffUntil || now - lastSent < MIN_GAP_MS) return;
        while (!SENT.isEmpty() && now - SENT.peekFirst() > WINDOW_MS) SENT.pollFirst();
        if (SENT.size() >= WINDOW_MAX) return;

        UUID id = QUEUE.removeFirst();
        Cached c = CACHE.get(id);
        if (c != null && now - c.at < TTL_MS && c.stats.found()) return; // fresh again (queued twice)
        lastSent = now;
        SENT.addLast(now);
        inFlight = true;
        if (CACHE.size() > CACHE_MAX) CACHE.entrySet().removeIf(e -> now - e.getValue().at > TTL_MS);
        send(id, key);
    }

    private static void send(UUID id, String key) {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT + id))
                .timeout(Duration.ofSeconds(10))
                .header("API-Key", key)
                .header("User-Agent", "EvadeClient")
                .header("Accept", "application/json")
                .GET().build();
        final String sentWith = key;
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            try {
                if (err != null) {
                    lastError = "Network error";
                    fail(id);
                    return;
                }
                handle(id, res, sentWith);
            } catch (RuntimeException e) {
                lastError = "Unreadable reply";
                fail(id);
                // Only the exception type: never the request (it carries the key).
                EvadeClient.LOG.debug("Hypixel API reply couldn't be read ({})", e.getClass().getSimpleName());
            } finally {
                inFlight = false;
            }
        });
    }

    private static void handle(UUID id, HttpResponse<String> res, String sentWith) {
        int code = res.statusCode();
        long now = System.currentTimeMillis();
        res.headers().firstValue("RateLimit-Remaining").ifPresent(r -> {
            if (parse(r, 1) <= 0) {
                long reset = res.headers().firstValue("RateLimit-Reset").map(s -> parse(s, 60)).orElse(60L);
                backoffUntil = Math.max(backoffUntil, now + Math.max(5, Math.min(300, reset)) * 1000);
            }
        });
        switch (code) {
            case 200 -> {
                JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
                JsonElement p = root.get("player");
                Stats s = p == null || !p.isJsonObject() ? Stats.NONE : parsePlayer(p.getAsJsonObject());
                CACHE.put(id, new Cached(s, now));
                lastError = "";
            }
            case 403 -> {
                // Invalid or revoked key: stop until the key changes.
                if (sentWith.equals(keyInUse) && !keyRejected) {
                    keyRejected = true;
                    lastError = "Key rejected";
                    Notifications.push("Hypixel Stats", "Hypixel rejected your API key", false);
                }
            }
            case 429 -> {
                long retry = res.headers().firstValue("Retry-After").map(s -> parse(s, 60)).orElse(60L);
                backoffUntil = Math.max(backoffUntil, now + Math.max(10, Math.min(600, retry)) * 1000);
                lastError = "Rate limited, waiting";
                requeue(id);
            }
            default -> {
                lastError = "HTTP " + code;
                fail(id);
                // Server trouble: give it a minute.
                if (code >= 500) backoffUntil = Math.max(backoffUntil, now + 60_000);
            }
        }
    }

    /** Remember a failed lookup briefly so the same player isn't retried at once. */
    private static void fail(UUID id) {
        CACHE.putIfAbsent(id, new Cached(new Stats(false, -1, 0, "", 0), System.currentTimeMillis()));
    }

    private static void requeue(UUID id) {
        net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
            if (QUEUE.size() < QUEUE_MAX) QUEUE.add(id);
        });
    }

    private static long parse(String s, long fallback) {
        try {
            return Long.parseLong(s.strip());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The fields the tab list uses from a {@code player} object. */
    static Stats parsePlayer(JsonObject p) {
        double exp = num(p, "networkExp");
        int level = (int) Math.floor(networkLevel(exp));
        int star = (int) num(obj(p, "achievements"), "bedwars_level");
        JsonObject stats = obj(p, "stats");
        JsonObject sw = obj(stats, "SkyWars");
        String swLevel = sw != null && sw.has("levelFormatted") && sw.get("levelFormatted").isJsonPrimitive()
                ? Hypixel.strip(sw.get("levelFormatted").getAsString()) : "";
        int duelsWins = (int) num(obj(stats, "Duels"), "wins");
        return new Stats(true, level, star, swLevel, duelsWins);
    }

    /** Hypixel's network level for {@code exp} network experience (level 1 at 0 exp, 10 000 exp more per level each time). */
    public static double networkLevel(double exp) {
        if (!(exp > 0)) return 1;
        return 1 + (-8750.0 + Math.sqrt(8750.0 * 8750.0 + 5000.0 * exp)) / 2500.0;
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static double num(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ status

    /** Drops queued lookups (e.g. after leaving Hypixel). The cache is kept. Client thread. */
    public static void clearQueue() { QUEUE.clear(); }

    /** Forgets every cached result (e.g. after the key changed). Client thread. */
    public static void clearCache() { CACHE.clear(); }

    public static boolean keyRejected() { return keyRejected; }

    /** One line for the settings: what the lookups are doing right now. */
    public static String status(String key) {
        if (key == null || key.isBlank()) return "No API key";
        if (!validKey(key)) return "Key doesn't look right";
        if (keyRejected && key.strip().equals(keyInUse)) return "Key rejected by Hypixel";
        long now = System.currentTimeMillis();
        if (now < backoffUntil) return "Waiting " + Math.max(1, (backoffUntil - now) / 1000) + " s (rate limit)";
        String s = CACHE.size() + " cached, " + QUEUE.size() + " queued";
        return lastError.isEmpty() ? s : s + " (" + lastError + ")";
    }
}
