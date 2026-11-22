package net.evadeclient.clips.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counter-Strike 2 through Valve's official Game State Integration: CS2 itself posts its state to Evade Clips
 * (no memory reading, nothing anti-cheat cares about). Clips kills, headshots, 3k/4k/aces, MVPs and match wins,
 * only for your own player (not whoever you're spectating).
 */
public final class Cs2Integration {
    public static final String GAME = "Counter-Strike 2";
    private final AutoClipper clipper;
    private final String token;
    public volatile boolean kills = true, headshotsOnly = false, multikills = true, mvps = true, wins = true, deaths = false;
    private int health = -1;
    /** Save each whole round as one clip: always, or only rounds where you got a kill. */
    public volatile String fullRounds = "off"; // "off", "kills", "all"
    private long roundStartWall;
    private String roundPhase = "";
    private int killsThisRound;

    private int roundKills = -1, roundHs = -1, mvpCount = -1, roundNo = -1;
    private String lastMapPhase = "";

    public Cs2Integration(AutoClipper clipper, String token) {
        this.clipper = clipper;
        this.token = token;
    }

    /** Handles one GSI POST body. Returns false if the auth token doesn't match. */
    public synchronized boolean handle(String body) {
        JsonObject root;
        try { root = JsonParser.parseString(body).getAsJsonObject(); } catch (Exception e) { return false; }
        String tok = str(obj(root, "auth"), "token");
        if (token != null && !token.equals(tok)) return false;
        JsonObject player = obj(root, "player"), provider = obj(root, "provider"), map = obj(root, "map");
        String me = str(provider, "steamid"), who = str(player, "steamid");
        if (me != null && who != null && !me.equals(who)) return true; // spectating someone else
        int round = map != null && map.has("round") ? map.get("round").getAsInt() : roundNo;
        JsonObject state = obj(player, "state"), stats = obj(player, "match_stats");

        if (round != roundNo) { roundNo = round; roundKills = 0; roundHs = 0; }
        JsonObject rnd = obj(root, "round");
        String rp = str(rnd, "phase");
        if (rp != null && !rp.equals(roundPhase)) {
            if (rp.equals("live")) { roundStartWall = System.currentTimeMillis(); killsThisRound = 0; }
            if (rp.equals("over") && roundStartWall > 0 && !"off".equals(fullRounds) && (fullRounds.equals("all") || killsThisRound > 0)) {
                int secs = (int) Math.ceil((System.currentTimeMillis() - roundStartWall) / 1000.0) + 2;
                String team = player != null ? str(player, "team") : null;
                String result = team != null && team.equals(str(rnd, "win_team")) ? "won" : "lost";
                clipper.clipSpan(GAME, "Round " + Math.max(1, roundNo) + " · " + result + (killsThisRound > 0 ? " · " + killsThisRound + "K" : ""),
                        "round", secs);
            }
            roundPhase = rp;
        }
        if (state != null) {
            int k = num(state, "round_kills"), hs = num(state, "round_killhs");
            if (roundKills >= 0 && k > roundKills) killsThisRound += k - roundKills;
            if (roundKills >= 0 && k > roundKills) {
                boolean headshot = hs > roundHs;
                // With full-round clips on, the round clip covers every kill: kills only get timeline markers.
                boolean rounds = !"off".equals(fullRounds);
                if (k >= 5 && multikills) { if (rounds) clipper.mark("ACE"); else clipper.event(GAME, "ACE", "ace", 5); }
                else if (k >= 3 && multikills) { if (rounds) clipper.mark(k + "K"); else clipper.event(GAME, k == 3 ? "3K" : "4K", k + "k", k); }
                else if (kills && (!headshotsOnly || headshot)) {
                    if (rounds) clipper.mark(headshot ? "Headshot" : "Kill");
                    else clipper.event(GAME, headshot ? "Headshot" : "Kill", headshot ? "headshot" : "kill", headshot ? 2 : 1);
                }
            }
            roundKills = k;
            roundHs = hs;
            if (state.has("health")) {
                int hp = num(state, "health");
                if (health > 0 && hp == 0) {
                    if (deaths) clipper.event(GAME, "Death", "death", 0); else clipper.mark("Death");
                }
                health = hp;
            }
        }
        if (stats != null) {
            int m = num(stats, "mvps");
            if (mvpCount >= 0 && m > mvpCount && mvps) { if (!"off".equals(fullRounds)) clipper.mark("Round MVP"); else clipper.event(GAME, "Round MVP", "mvp", 3); }
            mvpCount = m;
        }
        String phase = str(map, "phase");
        if ("gameover".equals(phase) && !"gameover".equals(lastMapPhase) && wins && player != null) {
            String team = str(player, "team");
            int ct = num(obj(map, "team_ct"), "score"), t = num(obj(map, "team_t"), "score");
            boolean won = "CT".equals(team) ? ct > t : "T".equals(team) && t > ct;
            if (won) clipper.event(GAME, "Match win", "win", 6);
        }
        if (phase != null) lastMapPhase = phase;
        return true;
    }

    private static JsonObject obj(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; }
    private static String str(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null; }
    private static int num(JsonObject o, String k) { try { return o != null && o.has(k) ? o.get(k).getAsInt() : 0; } catch (Exception e) { return 0; } }

    // ------------------------------------------------------------------ setup

    /** The config file that tells CS2 where to send its state. */
    public static String config(int port, String token) {
        return "\"Evade Clips\"\n{\n"
                + " \"uri\" \"http://127.0.0.1:" + port + "/gsi/cs2\"\n"
                + " \"timeout\" \"1.1\"\n \"buffer\" \"0.1\"\n \"throttle\" \"0.2\"\n \"heartbeat\" \"30\"\n"
                + " \"auth\" { \"token\" \"" + token + "\" }\n"
                + " \"data\"\n {\n  \"provider\" \"1\"\n  \"map\" \"1\"\n  \"round\" \"1\"\n  \"player_id\" \"1\"\n  \"player_state\" \"1\"\n  \"player_match_stats\" \"1\"\n }\n}\n";
    }

    /** CS2's cfg folders in every Steam library on this PC. */
    public static List<Path> cfgFolders() {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        for (String r : new String[]{"C:/Program Files (x86)/Steam", "C:/Program Files/Steam", home + "/.steam/steam",
                home + "/.local/share/Steam", home + "/Library/Application Support/Steam"}) roots.add(Path.of(r));
        List<Path> libs = new ArrayList<>(roots);
        for (Path r : roots) {
            Path vdf = r.resolve("steamapps/libraryfolders.vdf");
            try {
                if (!Files.exists(vdf)) continue;
                Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf, StandardCharsets.UTF_8));
                while (m.find()) libs.add(Path.of(m.group(1).replace("\\\\", "\\")));
            } catch (IOException ignored) {}
        }
        List<Path> out = new ArrayList<>();
        for (Path lib : libs) {
            Path cfg = lib.resolve("steamapps/common/Counter-Strike Global Offensive/game/csgo/cfg");
            if (Files.isDirectory(cfg) && !out.contains(cfg)) out.add(cfg);
        }
        return out;
    }

    /** Writes the config into every CS2 install found. Returns how many. */
    public static int install(int port, String token) {
        int n = 0;
        for (Path cfg : cfgFolders()) {
            try {
                Files.writeString(cfg.resolve("gamestate_integration_evadeclips.cfg"), config(port, token), StandardCharsets.UTF_8);
                n++;
            } catch (IOException e) {
                System.err.println("[clips] couldn't write CS2 config in " + cfg + ": " + e.getMessage());
            }
        }
        return n;
    }
}
