package net.evadeclient.module.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.StatusSetting;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows an Evade icon next to players in the tab list who are using Evade right now.
 *
 * <p>Can't be faked: you prove your account with Mojang's "join server" handshake (your login token only goes
 * to Mojang), evadeclient.com signs a token for your UUID, and a heartbeat marks you as an active Evade user.
 * Icons are looked up for the players in the tab list and cached for five minutes.
 */
public class EvadeIcons extends Module {
    private static final String API = "https://evadeclient.com/api";
    private static final StyleSpriteSource FONT = new StyleSpriteSource.Font(Identifier.of("evade", "icons"));
    /** The Evade logo (same mark as evadeclient.com), drawn as a font glyph so it lines up with the name. */
    private static final Text ICON = Text.literal("\ue000").setStyle(Style.EMPTY.withFont(FONT));
    private static final long CACHE_MS = 5 * 60_000, PRESENCE_MS = 4 * 60_000, SCAN_MS = 30_000;

    public final BooleanSetting showIcons = add(new BooleanSetting("Show Icons", true)
            .describe("Shows an Evade logo in the tab list next to other players who are using Evade right now."));
    public final BooleanSetting shareMe = add(new BooleanSetting("Show That I Use Evade", true)
            .describe("Lets other Evade players see the icon next to your name. Off stops sharing it."));
    public final StatusSetting status = add(new StatusSetting("Status", () -> statusText, "auto")
            .describe("Whether Evade Icons is signed in and sharing, or why not."));

    private record Entry(boolean evade, long fetchedAt) {}
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
    private static volatile String statusText = "Not signed in";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile String token;
    private volatile long tokenExpiry, nextAuthAttempt, lastPresence, lastScan;
    private volatile boolean authing;

    public EvadeIcons() {
        super("Evade Icons", "Shows an Evade icon in the tab list next to players using Evade.", Category.MISC);
        setVisible(false);
        setEnabled(true);
    }

    public static EvadeIcons active() {
        if (EvadeClient.modules == null) return null;
        EvadeIcons m = EvadeClient.modules.get(EvadeIcons.class);
        return m != null && m.isEnabled() ? m : null;
    }

    // ------------------------------------------------------------------ tab list

    /** Put the player's icon in front of their tab-list name (called from PlayerListHudMixin). */
    public static Text decorate(UUID id, Text name) {
        EvadeIcons m = active();
        if (m == null || !m.showIcons.get() || id == null) return name;
        Entry e = CACHE.get(id);
        if (e == null || !e.evade) return name;
        return Text.empty().append(ICON).append(Text.literal(" ")).append(name);
    }

    /** Test hook: pretend the server said this player has an icon. */
    public static void putForTest(UUID id, boolean evade) { CACHE.put(id, new Entry(evade, System.currentTimeMillis())); }

    // ------------------------------------------------------------------ background work

    @Override
    public void onTick() {
        if (mc.getNetworkHandler() == null) return;
        long now = System.currentTimeMillis();
        if (shareMe.get() && !authing && (token == null || now > tokenExpiry - 60_000) && now >= nextAuthAttempt) signIn();
        if (shareMe.get() && token != null && now - lastPresence > PRESENCE_MS) heartbeat();
        if (!shareMe.get() && token != null && lastPresence != 0) hide();
        if (showIcons.get() && (now - lastScan > SCAN_MS || (mc.options.playerListKey.isPressed() && now - lastScan > 2_000))) {
            lastScan = now;
            lookUpTabList(now);
        }
    }

    /** Ask the API about players in the tab list we don't have a fresh answer for (batched, at most 200). */
    private void lookUpTabList(long now) {
        var handler = mc.getNetworkHandler();
        if (handler == null) return;
        List<UUID> want = new ArrayList<>();
        for (PlayerListEntry p : handler.getPlayerList()) {
            UUID id = p.getProfile().id();
            Entry e = CACHE.get(id);
            if ((e == null || now - e.fetchedAt > CACHE_MS) && PENDING.add(id)) want.add(id);
            if (want.size() >= 200) break;
        }
        if (want.isEmpty()) return;
        JsonArray arr = new JsonArray();
        want.forEach(u -> arr.add(u.toString()));
        JsonObject body = new JsonObject();
        body.add("uuids", arr);
        post("/icons", body, null).whenComplete((res, err) -> {
            long t = System.currentTimeMillis();
            try {
                JsonObject icons = res != null ? res.getAsJsonObject("icons") : null;
                for (UUID u : want) {
                    String key = u.toString().replace("-", "");
                    if (res != null) CACHE.put(u, new Entry(icons != null && icons.has(key), t)); // on a network error, try again next scan
                }
            } finally {
                want.forEach(PENDING::remove);
            }
        });
    }

    /** Prove our account with Mojang, then get an Evade token for it. */
    private void signIn() {
        var session = mc.getSession();
        UUID uuid = session.getUuidOrNull();
        String accessToken = session.getAccessToken(), name = session.getUsername();
        if (uuid == null || accessToken == null || accessToken.length() < 20) {
            statusText = "Offline account: icons need a Microsoft login";
            nextAuthAttempt = Long.MAX_VALUE;
            return;
        }
        authing = true;
        statusText = "Signing in...";
        // Proof without Mojang's join check (Mojang refuses those from our server): the Mojang-signed chat key
        // every Microsoft account has, and our one-time id signed with its private half. No token leaves the game.
        mc.getProfileKeys().fetchKeyPair().thenCombine(post("/auth/challenge", new JsonObject(), null), (keys, ch) -> {
            var pair = keys.orElseThrow(() -> new IllegalStateException("no chat key (chat signing unavailable for this account)"));
            String serverId = ch.get("serverId").getAsString();
            try {
                java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                sig.initSign(pair.privateKey());
                sig.update(("evade-icons:" + serverId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var data = pair.publicKey().data();
                var enc = java.util.Base64.getEncoder();
                JsonObject b = new JsonObject();
                b.addProperty("uuid", uuid.toString());
                b.addProperty("expiresAt", data.expiresAt().toEpochMilli());
                b.addProperty("publicKey", enc.encodeToString(data.key().getEncoded()));
                b.addProperty("keySignature", enc.encodeToString(data.keySignature()));
                b.addProperty("serverId", serverId);
                b.addProperty("signature", enc.encodeToString(sig.sign()));
                return b;
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("couldn't sign", e);
            }
        }).thenCompose(b -> post("/auth/key", b, null)).whenComplete((res, err) -> {
            authing = false;
            if (err != null || res == null || !res.has("token")) {
                statusText = "Couldn't verify your account (trying again later)";
                nextAuthAttempt = System.currentTimeMillis() + 10 * 60_000;
                String why = err != null ? String.valueOf(err.getCause() != null ? err.getCause().getMessage() : err.getMessage()) : String.valueOf(res);
                statusText = "Couldn't verify your account: " + (why.length() > 80 ? why.substring(0, 80) : why);
                EvadeClient.LOG.info("Evade Icons: sign-in failed: {}", why);
                return;
            }
            token = res.get("token").getAsString();
            tokenExpiry = System.currentTimeMillis() + res.get("expiresIn").getAsLong() * 1000;
            statusText = "Signed in as " + name;
            // A sign-in Friends asked for (requestSignIn) never shows the icon by itself: only in a world, with the
            // module and "Show That I Use Evade" on, as before (onTick's heartbeat covers a world joined later).
            if (isEnabled() && shareMe.get() && mc.getNetworkHandler() != null) heartbeat();
        });
    }

    private void heartbeat() {
        String t = token;
        if (t == null) return;
        lastPresence = System.currentTimeMillis();
        post("/presence", new JsonObject(), t).whenComplete((res, err) -> {
            if (err != null && String.valueOf(err.getMessage()).contains("401")) { token = null; statusText = "Signing in again..."; }
            UUID me = mc.getSession().getUuidOrNull();
            if (res != null) statusText = "Signed in as " + mc.getSession().getUsername() + ": your icon is showing";
            if (res != null && me != null) CACHE.remove(me); // show our own icon on the next lookup
        });
    }

    /** "Show That I Use Evade" was switched off: take our icon down now instead of letting it expire. */
    private void hide() {
        lastPresence = 0;
        statusText = "Hidden: others don't see your icon";
        post("/presence/hide", new JsonObject(), token);
    }

    @Override
    protected void onDisable() {
        CACHE.clear();
        PENDING.clear();
        if (token != null && lastPresence != 0) hide();
    }

    // ------------------------------------------------------------------ for Friends (net.evadeclient.social)
    // The same proof of account, shared with the Friends connection: nothing here runs unless Friends asks for it.

    private static EvadeIcons instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(EvadeIcons.class);
    }

    /** The Evade token for the account you're playing on while it has over a minute left, else null. Any thread. */
    public static String socialToken() {
        EvadeIcons m = instance();
        String t = m == null ? null : m.token;
        return t != null && System.currentTimeMillis() < m.tokenExpiry - 60_000 ? t : null;
    }

    /**
     * Starts a sign-in for Friends unless one is running, the token is still good, or the wait after a failed one
     * isn't over. Works with this module off too (Friends is its own opt-in); it never shows the icon by itself.
     * Render thread.
     */
    public static void requestSignIn() {
        EvadeIcons m = instance();
        if (m == null || mc.getSession() == null) return;
        long now = System.currentTimeMillis();
        if (!m.authing && (m.token == null || now > m.tokenExpiry - 60_000) && now >= m.nextAuthAttempt) m.signIn();
    }

    /** Where signing in is: "Signing in...", "Signed in as ...", why it failed. */
    public static String signInStatus() { return statusText; }

    /** evadeclient.com refused the token (HTTP 401): drop it so the next request signs in again. */
    public static void forgetSocialToken() {
        EvadeIcons m = instance();
        if (m != null) m.token = null;
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer != null) b.header("authorization", "Bearer " + bearer);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + " " + r.body());
            return JsonParser.parseString(r.body()).getAsJsonObject();
        });
    }
}
