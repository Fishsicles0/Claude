package net.evadeclient.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Names checked with Mojang, from the game (evadeclient.com can't reach Mojang). The names other Evade players report
 * are only what their client says, so: adding a friend by name asks Mojang which account has that name and sends its
 * UUID, and names shown for requests, invites and friends are swapped for the name Mojang has for that UUID once known.
 */
public final class MojangNames {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FAILED = new ConcurrentHashMap<>();
    private static final Set<UUID> LOOKING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    /** Turned off by tests (and loopback mode), so nothing leaves the game. */
    public static volatile boolean offline;

    private MojangNames() {}

    /**
     * The UUID of the Minecraft account called {@code name}: completes with null when Mojang says there is none, and
     * exceptionally when Mojang couldn't be asked (the caller can fall back to the name).
     */
    public static CompletableFuture<UUID> lookup(String name) {
        if (offline) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(8)).header("user-agent", "EvadeClient/" + EvadeClient.VERSION).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 204 || r.statusCode() == 404) return null;
            if (r.statusCode() != 200) throw new IllegalStateException("Mojang answered " + r.statusCode());
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            UUID u = SocialState.uuid(o.get("id").getAsString());
            if (u != null && o.has("name")) NAMES.put(u, o.get("name").getAsString());
            return u;
        });
    }

    /**
     * Mojang's name for {@code uuid} if already known; otherwise starts finding out (two lookups at a time, a failed
     * one retried after ten minutes) and returns {@code reported} meanwhile.
     */
    public static String shown(UUID uuid, String reported) {
        if (uuid == null) return reported;
        String known = NAMES.get(uuid);
        if (known != null) return known;
        fetch(uuid);
        return reported == null || reported.isEmpty() ? uuid.toString().substring(0, 8) : reported;
    }

    /** Whether Mojang confirmed {@code reported} as {@code uuid}'s name (false while unknown). */
    public static boolean confirmed(UUID uuid, String reported) {
        String known = uuid == null ? null : NAMES.get(uuid);
        return known != null && known.equalsIgnoreCase(reported);
    }

    /** Test hook: pretend Mojang said {@code uuid} is called {@code name}. */
    public static void putForTest(UUID uuid, String name) { NAMES.put(uuid, name); }

    private static void fetch(UUID uuid) {
        if (offline || LOOKING.contains(uuid)) return;
        Long failed = FAILED.get(uuid);
        if (failed != null && System.currentTimeMillis() - failed < 10 * 60_000) return;
        if (IN_FLIGHT.get() >= 2 || !LOOKING.add(uuid)) return;
        IN_FLIGHT.incrementAndGet();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + SocialState.hex(uuid)))
                .timeout(Duration.ofSeconds(8)).header("user-agent", "EvadeClient/" + EvadeClient.VERSION).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, err) -> {
            try {
                if (err == null && r.statusCode() == 200) {
                    JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                    NAMES.put(uuid, o.get("name").getAsString());
                    FAILED.remove(uuid);
                } else {
                    FAILED.put(uuid, System.currentTimeMillis());
                }
            } catch (RuntimeException e) {
                FAILED.put(uuid, System.currentTimeMillis());
            } finally {
                LOOKING.remove(uuid);
                IN_FLIGHT.decrementAndGet();
            }
        });
    }
}
