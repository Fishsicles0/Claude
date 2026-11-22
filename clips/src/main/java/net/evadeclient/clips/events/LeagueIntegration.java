package net.evadeclient.clips.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Locale;

/**
 * League of Legends through Riot's official Live Client Data API, which the game serves on
 * https://127.0.0.1:2999 during a match. Clips your kills, multikills (double … penta), aces, dragon / baron /
 * herald steals and kills, and the victory screen.
 */
public final class LeagueIntegration {
    public static final String GAME = "League of Legends";
    private final AutoClipper clipper;
    public volatile boolean kills = true, multikills = true, objectives = true, wins = true, deaths = false;
    private int lastEventId = -1;
    private HttpClient http;

    public LeagueIntegration(AutoClipper clipper) { this.clipper = clipper; }

    /** Polls once; call every second or so while League is running. Silent when no match is live. */
    public void poll() {
        try {
            if (http == null) http = client();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:2999/liveclientdata/allgamedata"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) handle(r.body());
        } catch (Exception ignored) {
            // Not in a match (or loading): the API isn't up yet.
        }
    }

    /** Processes an {@code allgamedata} document; only events newer than the last one seen count. */
    public synchronized void handle(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject active = root.has("activePlayer") ? root.getAsJsonObject("activePlayer") : new JsonObject();
        String[] me = {str(active, "riotIdGameName"), str(active, "summonerName"), str(active, "riotId")};
        JsonArray events = root.has("events") && root.getAsJsonObject("events").has("Events")
                ? root.getAsJsonObject("events").getAsJsonArray("Events") : new JsonArray();
        boolean firstLook = lastEventId < 0;
        for (JsonElement el : events) {
            JsonObject e = el.getAsJsonObject();
            int id = e.has("EventID") ? e.get("EventID").getAsInt() : 0;
            if (id <= lastEventId) continue;
            lastEventId = id;
            if (firstLook) continue; // joined mid-game: don't clip history
            String name = str(e, "EventName");
            if (name == null) continue;
            switch (name) {
                case "ChampionKill" -> {
                    if (kills && isMe(str(e, "KillerName"), me)) clipper.event(GAME, "Kill " + str(e, "VictimName"), "kill", 1);
                    else if (isMe(str(e, "VictimName"), me)) {
                        if (deaths) clipper.event(GAME, "Death", "death", 0); else clipper.mark("Death");
                    }
                }
                case "Multikill" -> {
                    if (multikills && isMe(str(e, "KillerName"), me)) {
                        int k = e.has("KillStreak") ? e.get("KillStreak").getAsInt() : 2;
                        String[] names = {"", "", "Double kill", "Triple kill", "Quadra kill", "PENTAKILL"};
                        clipper.event(GAME, names[Math.min(5, Math.max(2, k))], k >= 5 ? "pentakill" : "multikill", 2 + k);
                    }
                }
                case "Ace" -> { if (multikills && isMe(str(e, "Acer"), me)) clipper.event(GAME, "Ace", "ace", 6); }
                case "DragonKill", "BaronKill", "HeraldKill", "HordeKill", "AtakhanKill" -> {
                    if (!objectives || !isMe(str(e, "KillerName"), me)) break;
                    boolean stolen = "True".equalsIgnoreCase(str(e, "Stolen"));
                    String what = name.replace("Kill", "").replace("Horde", "Voidgrubs");
                    clipper.event(GAME, (stolen ? what + " steal" : what), stolen ? "steal" : "objective", stolen ? 7 : 2);
                }
                case "GameEnd" -> { if (wins && "Win".equalsIgnoreCase(str(e, "Result"))) clipper.event(GAME, "Victory", "win", 8); }
                default -> {}
            }
        }
    }

    /** Resets between matches. */
    public synchronized void reset() { lastEventId = -1; }

    static boolean isMe(String name, String[] me) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String m : me) {
            if (m == null || m.isBlank()) continue;
            String a = m.toLowerCase(Locale.ROOT);
            if (n.equals(a) || n.equals(a.split("#")[0])) return true;
        }
        return false;
    }

    private static String str(JsonObject o, String k) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }

    /** The game uses a self-signed certificate on localhost; this client only ever talks to 127.0.0.1:2999. */
    private static HttpClient client() throws Exception {
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(1)).proxy(HttpClient.Builder.NO_PROXY).build();
    }
}
