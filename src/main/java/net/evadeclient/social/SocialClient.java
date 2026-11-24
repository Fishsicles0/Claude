package net.evadeclient.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.EvadeIcons;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.module.misc.PartyPing;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Friends, parties and party pings: the connection to evadeclient.com's /api/social. Starts once you've used Friends
 * (the Friends page or a .friend/.party command) and Evade Icons has proven your account; then keeps one WebSocket open
 * (reconnecting with backoff), or polls every 15 seconds where a socket can't be kept. Everything on the network
 * happens off the game thread; {@link #state()} is an immutable snapshot that any thread may read.
 *
 * <p>Tells the server which server you're playing on (null in singleplayer), so pings reach party members on the same
 * server and friends see it when "Share my server" is on. The server address is forgotten once you go offline.
 */
public final class SocialClient {
    public static final String API = "https://evadeclient.com/api/social/";
    private static final URI WS = URI.create("wss://evadeclient.com/api/social/ws");
    private static final long POLL_MS = 15_000, KEEPALIVE_MS = 30_000, WS_RETRY_WHILE_POLLING_MS = 5 * 60_000;

    /** Where the connection is. */
    public enum Link { OFF, SIGNING_IN, CONNECTING, LIVE, POLLING, LOOPBACK }

    /** An error evadeclient.com answered with ({@code status} is the HTTP status, 0 for a network problem). */
    public static final class SocialException extends RuntimeException {
        public final int status;
        public final long retryAfterMs;
        public SocialException(String message, int status, long retryAfterMs) {
            super(message, null, false, false);
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static volatile SocialState state = SocialState.EMPTY;
    private static volatile Link link = Link.OFF;
    private static volatile String status = "Off";
    private static volatile WebSocket socket;
    private static volatile boolean connecting, polling, pollInFlight;
    private static volatile Listener current;
    private static volatile long nextConnectAt, nextPollAt, lastKeepAlive;
    private static final AtomicInteger failures = new AtomicInteger();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<Integer, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();
    /** Sends on one socket must not overlap: each waits for the one before. */
    private static CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);
    // Presence as last sent (render thread). sentServer null = send again on the next check.
    private static String sentServer, sentName;
    private static long lastPresenceCheck, lastPresenceSent;
    private static boolean registered;
    private static volatile boolean loopback;

    private SocialClient() {}

    /** Hooks the client tick and shutdown. Safe to call more than once. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SocialClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> stop("Off"));
    }

    public static SocialState state() { return state; }
    public static Link link() { return link; }
    /** One line for the Friends page: "Connected", "Signing in...", "Off", ... */
    public static String status() { return status; }
    public static boolean ready() { return link == Link.LIVE || link == Link.POLLING || link == Link.LOOPBACK; }

    // ------------------------------------------------------------------ connection

    private static void tick(MinecraftClient mc) {
        try {
            tick0(mc);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Friends: tick failed", e);
        }
    }

    private static void tick0(MinecraftClient mc) {
        if (loopback) return;
        Friends f = Friends.get();
        if (f == null || !f.isEnabled() || !f.activated()) {
            if (link != Link.OFF) stop(f != null && f.isEnabled() ? "Not set up yet" : "Off");
            return;
        }
        long now = System.currentTimeMillis();
        String token = EvadeIcons.socialToken();
        if (token == null) {
            // Signing in (again: tokens last a day). An open socket stays signed in meanwhile.
            EvadeIcons.requestSignIn();
            if (socket == null) {
                polling = false;
                link = Link.SIGNING_IN;
                status = EvadeIcons.signInStatus();
                return;
            }
        }
        if (now - lastPresenceCheck >= 1000) {
            lastPresenceCheck = now;
            presence(mc, now);
        }
        WebSocket w = socket;
        if (w != null && link == Link.LIVE && now - lastKeepAlive >= KEEPALIVE_MS) {
            lastKeepAlive = now;
            sendRaw(w, "ping");
        }
        if (token == null) return;
        if (polling && !pollInFlight && now >= nextPollAt) poll(mc, token);
        if (socket == null && !connecting && now >= nextConnectAt) connect(token);
    }

    private static void connect(String token) {
        connecting = true;
        if (!polling) { link = Link.CONNECTING; status = "Connecting..."; }
        Listener listener = new Listener();
        HTTP.newWebSocketBuilder()
                .header("authorization", "Bearer " + token)
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(WS, listener)
                .whenComplete((w, err) -> {
                    connecting = false;
                    if (err != null) {
                        Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                        if (c instanceof WebSocketHandshakeException h && h.getResponse().statusCode() == 401) EvadeIcons.forgetSocialToken();
                        failed("Can't reach evadeclient.com", c);
                        return;
                    }
                    if (w.isInputClosed() || w.isOutputClosed()) { failed("Disconnected", null); return; }
                    socket = w;
                    listener.live = true;
                    current = listener;
                    failures.set(0);
                    polling = false;
                    link = Link.LIVE;
                    status = "Connected";
                    lastKeepAlive = System.currentTimeMillis();
                    synchronized (SocialClient.class) { sendTail = CompletableFuture.completedFuture(null); }
                    MinecraftClient.getInstance().execute(() -> sentServer = null); // say where we are right away
                });
    }

    /** A socket closed or never opened: back off (2 s, 4 s, ... up to a minute), and poll after 3 failures in a row. */
    private static void failed(String why, Throwable err) {
        socket = null;
        failPending(why);
        int n = failures.incrementAndGet();
        long delay = Math.min(60_000L, 2_000L << Math.min(5, n - 1));
        delay = (long) (delay * ThreadLocalRandom.current().nextDouble(0.75, 1.25));
        long now = System.currentTimeMillis();
        if (err != null) EvadeClient.LOG.info("Friends: connection failed ({}): {}", n, err.toString());
        if (n >= 3) {
            if (!polling) { polling = true; nextPollAt = now; }
            link = Link.POLLING;
            status = "Connected (checking every 15 s)";
            nextConnectAt = now + WS_RETRY_WHILE_POLLING_MS;
        } else {
            link = Link.CONNECTING;
            status = why + "; retrying...";
            nextConnectAt = now + delay;
        }
    }

    /** Switches everything off: closes the socket, stops polling and forgets the state. */
    public static void stop(String why) {
        closeSocket(1000, "bye");
        polling = false;
        connecting = false;
        failures.set(0);
        nextConnectAt = 0;
        failPending("Friends are off");
        state = SocialState.EMPTY;
        link = Link.OFF;
        status = why;
        sentServer = null;
    }

    private static void closeSocket(int code, String reason) {
        WebSocket w = socket;
        socket = null;
        Listener l = current;
        current = null;
        if (l != null) l.live = false; // closed on purpose: its close event changes nothing
        if (w != null) {
            try { w.sendClose(code, reason).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> { w.abort(); return null; }); }
            catch (RuntimeException e) { w.abort(); }
        }
    }

    private static void failPending(String why) {
        for (var it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            it.remove();
            e.getValue().completeExceptionally(new SocialException(why, 0, 0));
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        volatile boolean live;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (buf.length() > 1 << 20) buf.setLength(0); // a runaway message: drop it
            if (last) {
                String s = buf.toString();
                buf.setLength(0);
                if (!"pong".equals(s)) {
                    try { handle(s); } catch (RuntimeException e) { EvadeClient.LOG.warn("Friends: bad message", e); }
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed(ws, code, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed(ws, -1, String.valueOf(error));
        }

        private void closed(WebSocket ws, int code, String reason) {
            if (!live) return; // closed on purpose, or never took over
            live = false;
            if (current == this) current = null;
            if (code == 4001) EvadeIcons.forgetSocialToken();
            if (code == 4002) { // our social data was deleted (from this or another game)
                MinecraftClient.getInstance().execute(() -> {
                    Friends f = Friends.get();
                    if (f != null) f.deactivate();
                    stop("Your social data was deleted");
                });
                return;
            }
            failed(code == 4000 ? "Signed in from another game" : "Disconnected", null);
        }
    }

    // ------------------------------------------------------------------ polling fallback

    private static void poll(MinecraftClient mc, String token) {
        pollInFlight = true;
        JsonObject b = presenceBody(mc);
        http("state", b, token).whenComplete((res, err) -> {
            pollInFlight = false;
            long now = System.currentTimeMillis();
            if (err != null) {
                nextPollAt = now + 30_000;
                if (polling) status = "Can't reach evadeclient.com; retrying...";
                return;
            }
            nextPollAt = now + POLL_MS;
            if (polling) { link = Link.POLLING; status = "Connected (checking every 15 s)"; }
            for (JsonElement e : SocialState.arr(res, "pings")) if (e.isJsonObject()) deliverPing(e.getAsJsonObject());
        });
    }

    // ------------------------------------------------------------------ presence

    /** Sends where we're playing when it changes (or every 5 minutes over a socket, as a sign of life). */
    private static void presence(MinecraftClient mc, long now) {
        if (link != Link.LIVE) return; // polling carries it in every /state
        String server = currentServer(mc);
        String name = mc.getSession().getUsername();
        String s = server == null ? "" : server;
        if (s.equals(sentServer) && name.equals(sentName) && now - lastPresenceSent < 5 * 60_000) return;
        sentServer = s;
        sentName = name;
        lastPresenceSent = now;
        call("presence", presenceBody(mc)).exceptionally(e -> { MinecraftClient.getInstance().execute(() -> sentServer = null); return null; });
    }

    private static JsonObject presenceBody(MinecraftClient mc) {
        JsonObject b = new JsonObject();
        b.addProperty("name", mc.getSession().getUsername());
        String server = currentServer(mc);
        if (server == null) b.add("server", JsonNull.INSTANCE); else b.addProperty("server", server);
        return b;
    }

    /**
     * The address of the multiplayer server you're on (lower case, without the default port), or null in singleplayer,
     * on a Realm or in the menus. Render thread.
     */
    public static String currentServer(MinecraftClient mc) {
        if (mc.isInSingleplayer() || mc.getNetworkHandler() == null) return null;
        var entry = mc.getCurrentServerEntry();
        if (entry == null || entry.isRealm()) return null;
        return normalize(entry.address);
    }

    /** Lower case, trimmed, no trailing dot, no ":25565" (the server normalizes again and also drops LAN addresses). */
    public static String normalize(String address) {
        if (address == null) return null;
        String a = address.strip().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a.isEmpty() ? null : a;
    }

    // ------------------------------------------------------------------ requests

    /**
     * Sends one request ({@code op} as in the API, e.g. "friend.request"): over the socket when it's open, else over
     * HTTP. Completes with the answer (any fresh "state" in it is applied first), or exceptionally with a
     * {@link SocialException} saying what went wrong.
     */
    public static CompletableFuture<JsonObject> call(String op, JsonObject args) {
        if (loopback) return Loopback.call(op, args);
        String token = EvadeIcons.socialToken();
        if (token == null) return CompletableFuture.failedFuture(new SocialException("Not signed in yet: " + EvadeIcons.signInStatus(), 0, 0));
        WebSocket w = socket;
        CompletableFuture<JsonObject> f;
        if (w != null && link == Link.LIVE) {
            int id = IDS.incrementAndGet();
            JsonObject m = args == null ? new JsonObject() : args.deepCopy();
            m.addProperty("op", op);
            m.addProperty("id", id);
            CompletableFuture<JsonObject> raw = new CompletableFuture<>();
            PENDING.put(id, raw);
            sendRaw(w, m.toString());
            f = raw.orTimeout(10, TimeUnit.SECONDS).whenComplete((r, e) -> PENDING.remove(id)).thenApply(r -> {
                if (!SocialState.bool(r, "ok")) throw new SocialException(errorText(r), (int) SocialState.lng(r, "status"), SocialState.lng(r, "retryAfter"));
                return r;
            });
        } else {
            f = http(op, args == null ? new JsonObject() : args, token);
        }
        return f.thenApply(r -> {
            if (r.has("state") && r.get("state").isJsonObject()) setState(SocialState.parse(r.getAsJsonObject("state")));
            return r;
        });
    }

    private static CompletableFuture<JsonObject> http(String op, JsonObject args, String token) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + op.replace('.', '/')))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION)
                .header("authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(args.toString())).build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((r, err) -> {
            if (err != null) throw new SocialException("Can't reach evadeclient.com", 0, 0);
            JsonObject body;
            try { body = JsonParser.parseString(r.body()).getAsJsonObject(); } catch (RuntimeException e) { body = new JsonObject(); }
            if (r.statusCode() == 401) EvadeIcons.forgetSocialToken();
            if (r.statusCode() != 200) throw new SocialException(errorText(body), r.statusCode(), SocialState.lng(body, "retryAfter"));
            return body;
        });
    }

    private static String errorText(JsonObject r) {
        String e = SocialState.str(r, "error");
        return e.isEmpty() ? "Something went wrong" : e;
    }

    private static void sendRaw(WebSocket w, String text) {
        synchronized (SocialClient.class) {
            sendTail = sendTail.handle((x, e) -> null).thenCompose(x -> w.sendText(text, true)).exceptionally(e -> null);
        }
    }

    /** The message to show for a failed {@link #call}. */
    public static String message(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        if (t instanceof SocialException s) return s.getMessage();
        if (t instanceof java.util.concurrent.TimeoutException) return "evadeclient.com didn't answer";
        return "Something went wrong";
    }

    /** Asks for a fresh state (the Friends page does when it opens). */
    public static void refresh() {
        if (ready()) call("state", link == Link.POLLING ? presenceBody(MinecraftClient.getInstance()) : new JsonObject()).exceptionally(e -> null);
    }

    // ------------------------------------------------------------------ pings

    /**
     * Sends a party ping at a position in the world you're in, on the server you're on. Render thread. Fails at once
     * when you're not in a party or not on a multiplayer server (no request is made).
     */
    public static CompletableFuture<JsonObject> sendPing(double x, double y, double z, String dimension, String kind) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String server = currentServer(mc);
        if (!loopback) {
            if (!ready()) return CompletableFuture.failedFuture(new SocialException("Friends aren't connected", 0, 0));
            if (!state.inParty()) return CompletableFuture.failedFuture(new SocialException("Join a party to ping", 409, 0));
            if (server == null) return CompletableFuture.failedFuture(new SocialException("Pings work on multiplayer servers", 400, 0));
        }
        JsonObject b = new JsonObject();
        b.addProperty("x", x);
        b.addProperty("y", y);
        b.addProperty("z", z);
        b.addProperty("dimension", dimension);
        b.addProperty("kind", kind == null ? "location" : kind);
        b.addProperty("server", server == null ? "loopback" : server);
        return call("ping", b);
    }

    // ------------------------------------------------------------------ incoming events

    /** One message from the server (socket thread, or the test thread through {@link #injectForTest}). */
    static void handle(String text) {
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        String type = SocialState.str(o, "type");
        Friends f = Friends.get();
        boolean social = f == null || f.requestToasts.get(), online = f == null || f.onlineToasts.get();
        switch (type) {
            case "result" -> {
                CompletableFuture<JsonObject> p = PENDING.remove((int) SocialState.lng(o, "id"));
                if (p != null) p.complete(o);
            }
            case "hello", "state" -> {
                if (o.has("state") && o.get("state").isJsonObject()) setState(SocialState.parse(o.getAsJsonObject("state")));
            }
            case "friend.status" -> {
                UUID u = SocialState.uuid(SocialState.str(o, "uuid"));
                if (u == null) return;
                boolean on = SocialState.bool(o, "online");
                String server = o.has("server") && o.get("server").isJsonPrimitive() ? o.get("server").getAsString() : null;
                SocialState before = state;
                boolean wasOn = before.friends().stream().anyMatch(p -> p.uuid().equals(u) && p.online());
                setState(before.withFriendStatus(u, SocialState.str(o, "name"), on, server));
                if (online && before.isFriend(u) && on != wasOn) {
                    String name = MojangNames.shown(u, SocialState.str(o, "name"));
                    Notifications.push("Friends", name + (on ? " is online" + (server != null ? " on " + server : "") : " went offline"), on);
                }
            }
            case "friend.request" -> {
                if (social) Notifications.push("Friend request", who(SocialState.obj(o, "from")) + " wants to be friends", Notifications.Level.SUCCESS, 6000);
            }
            case "friend.accepted" -> {
                if (social) Notifications.push("Friends", who(SocialState.obj(o, "friend")) + " accepted your friend request", Notifications.Level.SUCCESS, 5000);
            }
            case "party.invite" -> {
                if (social) Notifications.push("Party invite", who(SocialState.obj(o, "from")) + " invited you: .party accept", Notifications.Level.SUCCESS, 8000);
            }
            case "party.joined" -> {
                if (social) Notifications.push("Party", who(SocialState.obj(o, "member")) + " joined the party", true);
            }
            case "party.left" -> {
                if (social) Notifications.push("Party", who(SocialState.obj(o, "member")) + (SocialState.bool(o, "kicked") ? " was removed from the party" : " left the party"), false);
            }
            case "party.kicked" -> {
                if (social) Notifications.push("Party", who(SocialState.obj(o, "by")) + " removed you from the party", Notifications.Level.WARN, 4000);
            }
            case "party.leader" -> {
                if (social) {
                    JsonObject l = SocialState.obj(o, "leader");
                    UUID lu = SocialState.uuid(SocialState.str(l, "uuid"));
                    Notifications.push("Party", lu != null && lu.equals(state.me()) ? "You lead the party now" : who(l) + " leads the party now", true);
                }
            }
            case "ping" -> deliverPing(o);
            default -> { /* newer server: ignore what we don't know */ }
        }
    }

    private static String who(JsonObject p) {
        return MojangNames.shown(SocialState.uuid(SocialState.str(p, "uuid")), SocialState.str(p, "name"));
    }

    private static void deliverPing(JsonObject o) {
        JsonObject from = SocialState.obj(o, "from");
        UUID u = SocialState.uuid(SocialState.str(from, "uuid"));
        if (u == null) return;
        String kind = SocialState.str(o, "kind");
        PartyPing.Ping p = new PartyPing.Ping(u, MojangNames.shown(u, SocialState.str(from, "name")),
                num(o, "x"), num(o, "y"), num(o, "z"), SocialState.str(o, "dimension"), kind.isEmpty() ? "location" : kind,
                System.currentTimeMillis());
        MinecraftClient.getInstance().execute(() -> PartyPing.receive(p));
    }

    private static double num(JsonObject o, String k) {
        try { return o.get(k).getAsDouble(); } catch (RuntimeException e) { return 0; }
    }

    private static void setState(SocialState s) { state = s; }

    // ------------------------------------------------------------------ test support

    /**
     * Loopback mode for tests: nothing touches the network; you're in a party with "Loopback" on the server you're on,
     * every ping you send comes straight back from Loopback, and other requests just succeed.
     */
    public static void setLoopback(boolean on) {
        if (on) {
            stop("Loopback");
            loopback = true;
            MojangNames.offline = true;
            link = Link.LOOPBACK;
            status = "Loopback (offline test mode)";
            state = Loopback.state();
        } else {
            loopback = false;
            stop("Off");
        }
    }

    public static boolean isLoopback() { return loopback; }

    /** Test hook: handle {@code json} as if the server had sent it (e.g. a ping or a friend.request event). */
    public static void injectForTest(String json) { handle(json); }

    /** What loopback mode answers. */
    static final class Loopback {
        static final UUID BUDDY = new UUID(0, 1);

        static SocialState state() {
            MinecraftClient mc = MinecraftClient.getInstance();
            UUID me = mc.getSession().getUuidOrNull();
            if (me == null) me = new UUID(0, 2);
            String server = mc.getNetworkHandler() != null ? currentServer(mc) : null;
            var meP = new SocialState.Person(me, mc.getSession().getUsername(), true, server, 0, true);
            var buddy = new SocialState.Person(BUDDY, "Loopback", true, server, 0, false);
            return new SocialState(true, me, mc.getSession().getUsername(), true, true, List.of(buddy), List.of(), List.of(),
                    List.of(), new SocialState.Party("0000000000000000", me, List.of(meP, buddy), List.of()), List.of());
        }

        static CompletableFuture<JsonObject> call(String op, JsonObject args) {
            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            if (op.equals("ping")) {
                JsonObject ev = args.deepCopy();
                ev.addProperty("type", "ping");
                JsonObject from = new JsonObject();
                from.addProperty("uuid", SocialState.hex(BUDDY));
                from.addProperty("name", "Loopback");
                ev.add("from", from);
                ev.remove("server");
                handle(ev.toString());
            }
            return CompletableFuture.completedFuture(ok);
        }
    }
}
