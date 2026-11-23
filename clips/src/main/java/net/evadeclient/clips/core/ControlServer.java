package net.evadeclient.clips.core;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local control API on 127.0.0.1, so Evade Client (or a stream deck, script, etc.) can save clips:
 * <pre>
 *   GET  /status
 *   POST /clip?seconds=30&after=5&title=...&game=...&tag=...      (seconds=0: the app's replay length)
 *   POST /clip?auto=1&span=N&...   (an automatic clip: the app picks how much before and after, around N s of action)
 *   POST /record/start?game=...   POST /record/stop   POST /record/toggle
 *   POST /marker?label=Kill
 *   POST /open[?clip=<id>|last]   (bring the app forward / open a clip in the editor)
 *   POST /gsi/cs2                 (Counter-Strike 2 Game State Integration)
 * </pre>
 * Requests carrying an Origin header are refused, so web pages can't trigger it. With the API turned off in
 * Settings the server still answers /status and /open (so a second copy of the app finds this one) and, if
 * enabled, /gsi/cs2; everything else is refused with 403. Requests from Evade Client carry {@code client=evade}.
 */
public final class ControlServer {
    /**
     * A /clip request. {@code seconds} 0 means the app's replay length; {@code after} is -1 when the caller didn't
     * say (the app's own post-roll applies). {@code auto} marks an automatic clip around {@code span} seconds of
     * action, whose length the app decides.
     */
    public record ClipRequest(int seconds, int after, String title, String game, String tag, boolean auto, int span) {}

    public interface Handler {
        Map<String, Object> status();
        Map<String, Object> clip(int seconds, int after, String title, String game, String tag) throws Exception;
        /** A /clip request with everything the caller sent (the default keeps older handlers working). */
        default Map<String, Object> clip(ClipRequest r) throws Exception {
            return clip(r.seconds(), Math.max(0, r.after()), r.title(), r.game(), r.tag());
        }
        Map<String, Object> record(String action, String game) throws Exception;
        void marker();
        default void marker(String label) { marker(); }
        /** Shows the window; with a clip id (or "last"), opens that clip in the editor. */
        default boolean open(String clip) { return false; }
        /** A game's own state push (CS2 Game State Integration). Returns false to reject (bad token). */
        default boolean gameState(String game, String body) { return false; }
        /** A request came from Evade Client (it sends {@code client=evade}). */
        default void evadeClientSeen() {}
        /** False while "Allow Evade Client and local tools to save clips" is off: only /status, /open and /gsi answer. */
        default boolean apiEnabled() { return true; }
        /** False while game events from CS2 are off. */
        default boolean gsiEnabled() { return true; }
    }

    private static final Map<String, Object> API_OFF = Map.of("error",
            "Saving clips from other apps is turned off. Turn on \"Allow Evade Client and local tools to save clips\" in Evade Clips' Settings.");

    private static final Gson GSON = new Gson();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

    public synchronized void start(int port, Handler h) throws IOException {
        stop();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
        // A /clip with a post-roll waits for it, so a few threads keep /status answering meanwhile.
        pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "control-api"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", ex -> {
            try {
                if (ex.getRequestHeaders().containsKey("Origin")) { send(ex, 403, Map.of("error", "browsers not allowed")); return; }
                String path = ex.getRequestURI().getPath();
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                boolean post = ex.getRequestMethod().equalsIgnoreCase("POST");
                if ("evade".equals(q.get("client"))) h.evadeClientSeen();
                boolean open = path.equals("/status") || path.equals("/open") || path.equals("/gsi/cs2");
                if (!open && !h.apiEnabled()) { send(ex, 403, API_OFF); return; }
                switch (path) {
                    case "/status" -> {
                        Map<String, Object> st = new LinkedHashMap<>(h.status());
                        if (!h.apiEnabled()) st.put("api", false);
                        send(ex, 200, st);
                    }
                    case "/clip" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        boolean auto = "1".equals(q.get("auto")) || "true".equalsIgnoreCase(q.get("auto"));
                        send(ex, 200, h.clip(new ClipRequest(Math.max(0, parseInt(q.get("seconds"), 0)), q.containsKey("after") ? Math.max(0, parseInt(q.get("after"), 0)) : -1,
                                q.get("title"), q.get("game"), q.get("tag"), auto, Math.max(0, parseInt(q.get("span"), 0)))));
                    }
                    case "/record/start", "/record/stop", "/record/toggle" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        send(ex, 200, h.record(path.substring(8), q.get("game")));
                    }
                    case "/open" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        send(ex, 200, Map.of("ok", h.open(q.get("clip"))));
                    }
                    case "/gsi/cs2" -> {
                        if (!h.gsiEnabled()) { send(ex, 403, Map.of("error", "Counter-Strike 2 events are turned off in Evade Clips")); return; }
                        String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        send(ex, h.gameState("cs2", body) ? 200 : 403, Map.of());
                    }
                    case "/marker" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        if (q.get("label") != null) h.marker(q.get("label")); else h.marker();
                        send(ex, 200, Map.of("ok", true));
                    }
                    default -> send(ex, 404, Map.of("error", "unknown endpoint"));
                }
            } catch (Exception e) {
                send(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        });
        server.start();
    }

    public synchronized void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (pool != null) { pool.shutdown(); pool = null; }
    }

    public synchronized boolean running() { return server != null; }

    private static void send(HttpExchange ex, int code, Map<String, ?> body) throws IOException {
        byte[] b = GSON.toJson(new LinkedHashMap<>(body)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
}
