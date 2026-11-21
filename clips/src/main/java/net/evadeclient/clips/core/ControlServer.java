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
 *   POST /clip?seconds=30&after=5&title=...&game=...&tag=...
 *   POST /record/start?game=...   POST /record/stop   POST /record/toggle
 *   POST /marker
 * </pre>
 * Requests carrying an Origin header are refused, so web pages can't trigger it.
 */
public final class ControlServer {
    public interface Handler {
        Map<String, Object> status();
        Map<String, Object> clip(int seconds, int after, String title, String game, String tag) throws Exception;
        Map<String, Object> record(String action, String game) throws Exception;
        void marker();
    }

    private static final Gson GSON = new Gson();
    private HttpServer server;

    public synchronized void start(int port, Handler h) throws IOException {
        stop();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "control-api"); t.setDaemon(true); return t; }));
        server.createContext("/", ex -> {
            try {
                if (ex.getRequestHeaders().containsKey("Origin")) { send(ex, 403, Map.of("error", "browsers not allowed")); return; }
                String path = ex.getRequestURI().getPath();
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                boolean post = ex.getRequestMethod().equalsIgnoreCase("POST");
                switch (path) {
                    case "/status" -> send(ex, 200, h.status());
                    case "/clip" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        int secs = parseInt(q.get("seconds"), 0);
                        send(ex, 200, h.clip(secs, parseInt(q.get("after"), 0), q.get("title"), q.get("game"), q.get("tag")));
                    }
                    case "/record/start", "/record/stop", "/record/toggle" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        send(ex, 200, h.record(path.substring(8), q.get("game")));
                    }
                    case "/marker" -> {
                        if (!post) { send(ex, 405, Map.of("error", "use POST")); return; }
                        h.marker();
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
