/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Partner cosmetics: a partner server grants its own partner items to players through evadeclient.com. Each request is
 * signed with the partner key from config.yml ({@code cosmetics.partner-id} / {@code cosmetics.partner-key}); without
 * them this is off. The key never leaves this server: only an HMAC-SHA256 signature of the request is sent.
 *
 * <pre>
 * POST {api-url}/api/partner/grant   body {"uuid": "...", "item": "...", "nonce": "..."}
 * X-Evade-Partner: partner id
 * X-Evade-Timestamp: unix time in milliseconds
 * X-Evade-Signature: hex HMAC-SHA256(key, partnerId + "\n" + timestamp + "\n" + body)
 * </pre>
 */
final class PartnerCosmetics {
    private static final Pattern PARTNER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private PartnerCosmetics() {}

    /** Thrown (inside the future) when a grant fails; the message is fit to show an admin. */
    static final class GrantException extends RuntimeException {
        final int status;

        GrantException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static boolean configured(PluginConfig c) {
        return c != null && c.partnerId != null && PARTNER_ID.matcher(c.partnerId).matches()
                && c.partnerKey != null && c.partnerKey.length() >= 16;
    }

    /**
     * Grants {@code itemId} to {@code player}. Completes with {@code true} when the player didn't own it yet, {@code false}
     * when they already did; completes exceptionally with a {@link GrantException} otherwise. Runs off the main thread.
     */
    static CompletableFuture<Boolean> grant(PluginConfig c, UUID player, String itemId) {
        if (!configured(c)) {
            return CompletableFuture.failedFuture(new GrantException(0,
                    "Partner cosmetics are off: set cosmetics.partner-id and cosmetics.partner-key in config.yml"));
        }
        String item = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        if (player == null || !ITEM_ID.matcher(item).matches()) {
            return CompletableFuture.failedFuture(new GrantException(0, "Not a valid cosmetic id: " + itemId));
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", player.toString().replace("-", ""));
        body.addProperty("item", item);
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        body.addProperty("nonce", HexFormat.of().formatHex(nonce));
        String raw = body.toString();
        String ts = Long.toString(System.currentTimeMillis());
        String sig;
        try {
            sig = sign(c.partnerKey, c.partnerId + "\n" + ts + "\n" + raw);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new GrantException(0, "Couldn't sign the request: " + e.getMessage()));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.cosmeticsApiUrl + "/api/partner/grant"))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json")
                .header("user-agent", "EvadeServerAPI")
                .header("x-evade-partner", c.partnerId)
                .header("x-evade-timestamp", ts)
                .header("x-evade-signature", sig)
                .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8))
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((res, err) -> {
                    if (err != null) throw new GrantException(0, "Couldn't reach Evade: " + err.getMessage());
                    JsonObject o = parse(res.body());
                    if (res.statusCode() == 200) return o != null && o.has("granted") && o.get("granted").getAsBoolean();
                    String msg = o != null && o.has("error") ? o.get("error").getAsString() : "HTTP " + res.statusCode();
                    throw new GrantException(res.statusCode(), msg);
                });
    }

    static String sign(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject parse(String s) {
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
