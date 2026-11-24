package net.evadeclient.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.LauncherBridge;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Evade Launcher bridge: the heartbeat file names this process and the open world, play time is counted, and
 * launcher requests are used once, only while fresh and well-formed. Nothing here connects anywhere: requests are
 * parsed and taken, never acted on.
 */
public final class LauncherBridgeTest {
    private LauncherBridgeTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Path dir = ctx.computeOnClient(c -> LauncherBridge.dir());
        check(dir != null && Files.isDirectory(dir), "Launcher bridge folder exists (" + dir + ")");
        // the tick notes the world once a second; the beat writes it every 5 s
        ctx.waitTicks(20 * 7);
        JsonObject s = read(dir.resolve(LauncherBridge.SESSION));
        long pid = ProcessHandle.current().pid();
        check(s.has("pid") && s.get("pid").getAsLong() == pid, "Heartbeat names this game's process id");
        check(System.currentTimeMillis() - s.get("beat").getAsLong() < 15_000, "Heartbeat is fresh");
        check(s.get("version").getAsString().equals(EvadeClient.VERSION)
                && java.nio.file.Path.of(s.get("gameDir").getAsString()).equals(dir.getParent().toAbsolutePath().normalize()),
                "Heartbeat names the Evade version and the game folder");
        check("singleplayer".equals(s.get("state").getAsString()) && s.has("worldFolder"), "Heartbeat says a world is open: " + s);
        check(!s.get("clean").getAsBoolean() && s.get("ended").getAsLong() == 0, "Running session isn't marked ended");
        JsonObject p = read(dir.resolve(LauncherBridge.PLAYTIME));
        check(p.has("totalMs") && p.get("totalMs").getAsLong() > 0 && p.get("sessions").getAsLong() >= 1, "Play time is counted: " + p);

        long now = System.currentTimeMillis();
        String ok = "{\"type\":\"server\",\"target\":\"play.example.net\",\"name\":\"Example\",\"created\":" + (now - 5000) + "}";
        LauncherBridge.Request r = LauncherBridge.parseRequest(ok, now);
        check(r != null && !r.world() && r.target().equals("play.example.net") && r.name().equals("Example"), "A fresh join request is accepted");
        check(LauncherBridge.parseRequest(ok.replace(String.valueOf(now - 5000), String.valueOf(now - 121_000)), now) == null,
                "A request older than two minutes is ignored");
        check(LauncherBridge.parseRequest(ok.replace("play.example.net", "evil host; rm"), now) == null, "A malformed address is ignored");
        check(LauncherBridge.parseRequest("{\"type\":\"world\",\"target\":\"../x\",\"created\":" + now + "}", now) == null, "A world path outside saves is ignored");
        check(LauncherBridge.parseRequest("{\"type\":\"exec\",\"target\":\"x\",\"created\":" + now + "}", now) == null, "Unknown request types are ignored");
        check(LauncherBridge.parseRequest("not json", now) == null, "Broken request files are ignored");

        // one use: taking it deletes the file, a second take finds nothing
        try {
            Path tmp = Files.createTempDirectory("evade-bridge");
            Files.writeString(tmp.resolve(LauncherBridge.REQUEST), ok);
            check(LauncherBridge.takeRequest(tmp, now) != null, "A request file is read");
            check(!Files.exists(tmp.resolve(LauncherBridge.REQUEST)) && LauncherBridge.takeRequest(tmp, now) == null, "A request is used only once");
            Files.writeString(tmp.resolve(LauncherBridge.REQUEST), ok.replace(String.valueOf(now - 5000), "1"));
            check(LauncherBridge.takeRequest(tmp, now) == null && !Files.exists(tmp.resolve(LauncherBridge.REQUEST)), "An expired request file is removed unused");
        } catch (java.io.IOException e) {
            throw new AssertionError("FAILED: temp folder: " + e);
        }
    }

    private static JsonObject read(Path f) {
        try {
            return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("FAILED: couldn't read " + f + ": " + e);
        }
    }
}
