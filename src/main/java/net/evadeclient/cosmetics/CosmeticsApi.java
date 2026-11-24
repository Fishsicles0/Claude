package net.evadeclient.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.EvadeIcons;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * evadeclient.com's /api/cosmetics (contract in site-src/cosmetics/README.md). The catalog and what nearby players
 * wear are public; your own items, equipping, codes and the shop link use the same Bearer token as Friends (Evade
 * Icons' account proof). All calls are asynchronous; {@link #transport} is swapped for a mock in tests.
 */
public final class CosmeticsApi {
    public static final String BASE = "https://evadeclient.com/api/cosmetics/";
    /** Most UUIDs per players lookup (the server refuses more). */
    public static final int MAX_BATCH = 64;

    /** Something evadeclient.com (or the network) refused; {@code status} 0 for a network error. */
    public static final class ApiException extends RuntimeException {
        public final int status;
        public ApiException(String message, int status) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    /** One HTTP call: GET when body is null. {@code token} null for public calls. */
    @FunctionalInterface
    public interface Transport {
        CompletableFuture<JsonObject> call(String method, String path, JsonObject body, String token);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public static final Transport NETWORK = CosmeticsApi::network;
    public static volatile Transport transport = NETWORK;

    private CosmeticsApi() {}

    public static CompletableFuture<JsonObject> catalog() {
        return transport.call("GET", "catalog", null, null);
    }

    /** What these players wear: {@code {players: {<uuid>: {equipped: {...}}}}}. At most {@link #MAX_BATCH}. */
    public static CompletableFuture<JsonObject> players(Collection<UUID> uuids) {
        String list = uuids.stream().limit(MAX_BATCH).map(u -> u.toString().replace("-", "")).collect(Collectors.joining(","));
        return transport.call("GET", "players?uuids=" + URLEncoder.encode(list, StandardCharsets.UTF_8), null, null);
    }

    public static CompletableFuture<JsonObject> me() { return authed("GET", "me", null); }

    /** Equip {@code id} in {@code slot} (null takes it off); {@code color} RGB or -1. */
    public static CompletableFuture<JsonObject> equip(Slot slot, String id, int color) {
        JsonObject b = new JsonObject();
        b.addProperty("slot", slot.name());
        if (id == null) b.add("id", JsonNull.INSTANCE); else b.addProperty("id", id);
        if (color >= 0) b.addProperty("color", String.format("#%06X", color & 0xFFFFFF));
        return authed("POST", "equip", b);
    }

    public static CompletableFuture<JsonObject> redeem(String code) {
        JsonObject b = new JsonObject();
        b.addProperty("code", code);
        return authed("POST", "redeem", b);
    }

    /** A short code to type on the shop website, linking it to this Minecraft account: {@code {code, expiresAt}}. */
    public static CompletableFuture<JsonObject> linkCode() { return authed("GET", "link-code", null); }

    /** Whether authenticated calls can go out now (signed in with a fresh token). */
    public static boolean signedIn() { return transport != NETWORK || EvadeIcons.socialToken() != null; }

    private static CompletableFuture<JsonObject> authed(String method, String path, JsonObject body) {
        String token = transport == NETWORK ? EvadeIcons.socialToken() : "test";
        if (token == null) {
            EvadeClient.mc().execute(EvadeIcons::requestSignIn);
            return CompletableFuture.failedFuture(new ApiException("Signing in to evadeclient.com... try again in a moment", 401));
        }
        return transport.call(method, path, body, token);
    }

    private static CompletableFuture<JsonObject> network(String method, String path, JsonObject body, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(10))
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION);
        if (token != null) b.header("authorization", "Bearer " + token);
        if (body != null) b.header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        else b.GET();
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 401 && token != null) EvadeIcons.forgetSocialToken();
            JsonObject o = null;
            try {
                JsonElement e = JsonParser.parseString(r.body());
                if (e.isJsonObject()) o = e.getAsJsonObject();
            } catch (RuntimeException ignored) {
                // not JSON
            }
            if (r.statusCode() != 200) {
                String msg = o != null && o.has("error") ? o.get("error").getAsString() : "evadeclient.com answered " + r.statusCode();
                throw new ApiException(msg, r.statusCode());
            }
            if (o == null) throw new ApiException("evadeclient.com sent something unexpected", r.statusCode());
            return o;
        });
    }

    /** A short reason for a failed call, for a notification. */
    public static String reason(Throwable t) {
        while (t.getCause() != null && t != t.getCause() && !(t instanceof ApiException)) t = t.getCause();
        if (t instanceof ApiException a) return a.getMessage();
        return "Can't reach evadeclient.com";
    }
}
