package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The shader pack chooser: keys, the CLI list, and installing, updating and removing packs (from a local server). */
class ShaderPacksTest {
    private HttpServer server;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = served.get(ex.getRequestURI().getPath().substring(1));
            ex.sendResponseHeaders(b == null ? 404 : 200, b == null ? -1 : b.length);
            if (b != null) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void keysAndDefaults() {
        assertEquals(List.of("evade-shaders", "photon", "complementary-reimagined"), new ArrayList<>(ShaderPacks.defaults()));
        assertEquals(ShaderPacks.EVADE, ShaderPacks.ALL[0]); // first: the pack Iris is pointed at by default
        assertEquals(List.of("evade-shaders", "photon", "complementary-reimagined", "complementary-unbound", "bsl", "sildurs-vibrant"),
                new ArrayList<>(ShaderPacks.parse("sildurs-vibrant,bsl,complementary-unbound,complementary-reimagined,photon,evade-shaders")));
        assertEquals(Set.of("evade-shaders", "bsl"), ShaderPacks.parse("evade-shaders,bsl"));
        assertEquals(Set.of("bsl", "sildurs-vibrant"), ShaderPacks.parse("bsl-shaders, sildurs"));
        assertTrue(ShaderPacks.parse("none").isEmpty());
        assertEquals(6, ShaderPacks.parse("all").size());
        assertThrows(IllegalArgumentException.class, () -> ShaderPacks.parse("photon,seus-renewed"));
        assertEquals(ShaderPacks.defaults(), ShaderPacks.clean(null));
        assertEquals("Evade Shaders, Photon and Complementary Reimagined", ShaderPacks.names(ShaderPacks.defaults()));
        assertEquals(ShaderPacks.EVADE, ShaderPacks.byFile("Evade Shaders v1.0.zip"));
        assertEquals(ShaderPacks.EVADE, ShaderPacks.byFile("EvadeShaders.zip"));
        assertNull(ShaderPacks.bySlug("evade-shaders")); // not a Modrinth project
        assertTrue(ShaderPacks.EVADE.ours() && ShaderPacks.EVADE.defaultOn() && ShaderPacks.EVADE.light());
        assertTrue(ShaderPacks.EVADE.url().endsWith("/download#evade-shaders"));
        assertEquals(Set.of("evade-shaders", "complementary-reimagined"), InstanceTemplates.LAPTOP.shaderPacks());
        assertEquals(ShaderPacks.SILDURS, ShaderPacks.byFile("Sildur's Vibrant Shaders v2.02 Medium.zip"));
        assertEquals(ShaderPacks.BSL, ShaderPacks.byFile("BSL_v10.1.8.zip"));
        assertEquals(ShaderPacks.UNBOUND, ShaderPacks.byFile("ComplementaryUnbound_r5.10.zip"));
    }

    @Test
    void sildursEdition() {
        JsonObject medium = JsonParser.parseString("{\"name\":\"Sildur's Vibrant Shaders 2.02 Medium\",\"files\":[{\"filename\":\"Sildur's Vibrant Shaders v2.02 Medium.zip\"}]}").getAsJsonObject();
        JsonObject vl = JsonParser.parseString("{\"name\":\"Sildur's Vibrant Shaders 2.02 Extreme Volumetric Lighting\",\"files\":[{\"filename\":\"Sildur's Vibrant Shaders v2.02 Extreme-VL.zip\"}]}").getAsJsonObject();
        assertTrue(InstallerCore.isEdition(medium, "Medium"));
        assertFalse(InstallerCore.isEdition(vl, "Medium"));
    }

    @Test
    void shareCodeCarriesThePacks(@TempDir Path dir) throws IOException {
        Launcher.Instance inst = new Launcher.Instance("x", dir, "Shaders");
        inst.shaders = true;
        inst.shaderPacks = ShaderPacks.parse("bsl,sildurs-vibrant");
        Mrpack.Share s = Mrpack.readCode(Mrpack.shareCode(inst, new LinkedHashMap<>()));
        assertTrue(s.shaderOption());
        assertEquals(Set.of("bsl", "sildurs-vibrant"), s.shaderPacks());
        inst.shaderPacks = ShaderPacks.defaults();
        assertEquals(ShaderPacks.defaults(), Mrpack.readCode(Mrpack.shareCode(inst, new LinkedHashMap<>())).shaderPacks());
    }

    @Test
    void instanceSettingsKeepThePacks(@TempDir Path dir) throws IOException {
        Launcher.Instance inst = new Launcher.Instance("x", dir, "Shaders");
        inst.shaderPacks = ShaderPacks.parse("complementary-unbound");
        Launcher.save(inst);
        JsonObject o = InstallerCore.readJsonObject(dir.resolve(Launcher.INSTANCE_FILE));
        assertEquals(Set.of("complementary-unbound"), Launcher.readShaderPacks(o));
        assertEquals(Set.of("complementary-unbound"), Launcher.readShaderPacks(InstanceZip.manifest(inst, false)));
        assertEquals(ShaderPacks.defaults(), Launcher.readShaderPacks(new JsonObject())); // older settings: the defaults
    }

    @Test
    void evadeShadersIsTickedForSettingsFromBeforeIt(@TempDir Path dir) throws IOException {
        // Saved by a launcher before Evade Shaders: it's added to whatever was ticked.
        JsonObject old = JsonParser.parseString("{\"shaderPacks\":[\"bsl\"]}").getAsJsonObject();
        assertEquals(List.of("evade-shaders", "bsl"), new ArrayList<>(Launcher.readShaderPacks(old)));
        JsonObject oldDefaults = JsonParser.parseString("{\"shaderPacks\":[\"photon\",\"complementary-reimagined\"]}").getAsJsonObject();
        assertEquals(ShaderPacks.defaults(), Launcher.readShaderPacks(oldDefaults));
        // Unticked by the player since: stays unticked.
        Launcher.Instance inst = new Launcher.Instance("x", dir, "Shaders");
        inst.shaderPacks = ShaderPacks.parse("photon");
        Launcher.save(inst);
        assertEquals(Set.of("photon"), Launcher.readShaderPacks(InstallerCore.readJsonObject(dir.resolve(Launcher.INSTANCE_FILE))));
        assertEquals(Set.of("photon"), Launcher.readShaderPacks(InstanceZip.manifest(inst, false)));
        // None ticked stays none.
        inst.shaderPacks = ShaderPacks.parse("none");
        Launcher.save(inst);
        assertTrue(Launcher.readShaderPacks(InstallerCore.readJsonObject(dir.resolve(Launcher.INSTANCE_FILE))).isEmpty());
    }

    @Test
    void versionJsonEntry() {
        String sha = "7b74a205975fdb2a66ef225971821de3c963e52caa6a19720313b01ba22b6d90";
        JsonObject site = JsonParser.parseString("{\"shaders\":{\"evade\":{\"file\":\"downloads/EvadeShaders.zip\",\"version\":\"1.0\",\"sha256\":\"" + sha + "\"}}}").getAsJsonObject();
        InstallerCore.ModFile f = InstallerCore.evadeShaders(site);
        assertNotNull(f);
        assertEquals("Evade Shaders", f.name());
        assertEquals("1.0", f.version());
        assertEquals("Evade Shaders v1.0.zip", f.filename());
        assertEquals(InstallerCore.SITE + "/downloads/EvadeShaders.zip?v=" + sha.substring(0, 10), f.url());
        assertEquals("SHA-256", InstallerCore.hashAlgo(f.sha512()));
        assertEquals("SHA-512", InstallerCore.hashAlgo("ab".repeat(64)));
        // Missing, incomplete or odd entries: no pack (an older site).
        assertNull(InstallerCore.evadeShaders(new JsonObject()));
        assertNull(InstallerCore.evadeShaders(JsonParser.parseString("{\"shaders\":{\"evade\":{\"file\":\"downloads/EvadeShaders.zip\",\"version\":\"1.0\"}}}").getAsJsonObject()));
        assertNull(InstallerCore.evadeShaders(JsonParser.parseString("{\"shaders\":{\"evade\":{\"file\":\"downloads/EvadeShaders.zip\",\"version\":\"1.0\",\"sha256\":\"abc\"}}}").getAsJsonObject()));
        assertNull(InstallerCore.evadeShaders(JsonParser.parseString("{\"shaders\":{\"evade\":{\"file\":\"https://elsewhere.example/x.zip\",\"version\":\"1.0\",\"sha256\":\"" + sha + "\"}}}").getAsJsonObject()));
        assertNull(InstallerCore.evadeShaders(JsonParser.parseString("{\"shaders\":{\"evade\":{\"file\":\"downloads/EvadeShaders.zip\",\"version\":\"1/../x\",\"sha256\":\"" + sha + "\"}}}").getAsJsonObject()));
    }

    @Test
    void evadeShadersInstallUpdateAndRemove(@TempDir Path game) throws Exception {
        InstallerCore core = new InstallerCore();
        Path packs = game.resolve("shaderpacks"), iris = game.resolve("config/iris.properties");
        Set<String> want = ShaderPacks.defaults();

        // New install with the defaults: Evade Shaders is added (SHA-256 checked) and is the pack Iris starts on.
        Map<String, InstallerCore.ModFile> v1 = release("1");
        v1.put("evade-shaders", evade("1.0", "build one"));
        JsonObject m1 = run(core, game, want, v1, null, true);
        assertEquals(List.of("evade-shaders", "photon", "complementary-reimagined"), new ArrayList<>(m1.keySet()));
        assertEquals("build one", Files.readString(packs.resolve("Evade Shaders v1.0.zip")));
        assertEquals(v1.get("evade-shaders").sha512(), m1.getAsJsonObject("evade-shaders").get("sha").getAsString());
        assertTrue(Files.readString(iris, StandardCharsets.ISO_8859_1).contains("shaderPack=Evade Shaders v1.0.zip"));
        assertNull(updateReason(game, m1, v1));

        // A new build under the same version: told apart by its checksum, downloaded again over Evade's own copy.
        Files.writeString(packs.resolve("Evade Shaders v1.0.zip.txt"), "SHADOWS=false\n"); // the player's pack settings
        Map<String, InstallerCore.ModFile> v1b = new LinkedHashMap<>(v1);
        v1b.put("evade-shaders", evade("1.0", "build two"));
        assertEquals("A new build of Evade Shaders 1.0 is out", updateReason(game, m1, v1b));
        JsonObject m2 = run(core, game, want, v1b, marker(m1), true);
        assertEquals("build two", Files.readString(packs.resolve("Evade Shaders v1.0.zip")));
        assertNull(updateReason(game, m2, v1b));

        // Version 1.1: the old file goes, its settings come along, and Iris follows to the new file.
        Map<String, InstallerCore.ModFile> v2 = new LinkedHashMap<>(v1b);
        v2.put("evade-shaders", evade("1.1", "version two"));
        assertEquals("Evade Shaders 1.1 is out", updateReason(game, m2, v2));
        JsonObject m3 = run(core, game, want, v2, marker(m2), true);
        assertFalse(Files.exists(packs.resolve("Evade Shaders v1.0.zip")));
        assertEquals("version two", Files.readString(packs.resolve("Evade Shaders v1.1.zip")));
        assertEquals("SHADOWS=false\n", Files.readString(packs.resolve("Evade Shaders v1.1.zip.txt")));
        assertTrue(Files.readString(iris, StandardCharsets.ISO_8859_1).contains("shaderPack=Evade Shaders v1.1.zip"));

        // A download that fails its check is not installed (and the old copy is kept).
        Map<String, InstallerCore.ModFile> bad = new LinkedHashMap<>(v2);
        InstallerCore.ModFile e2 = evade("1.2", "version three");
        bad.put("evade-shaders", new InstallerCore.ModFile(e2.name(), e2.version(), e2.url(), "0".repeat(64), e2.filename(), null, List.of(), Map.of()));
        List<String> skipped = new ArrayList<>();
        JsonObject m4 = runSkipping(core, game, want, bad, marker(m3), skipped);
        assertEquals(List.of("Evade Shaders wasn't updated (kept the old one)"), skipped);
        assertFalse(Files.exists(packs.resolve("Evade Shaders v1.2.zip")));
        assertEquals("Evade Shaders v1.1.zip", m4.getAsJsonObject("evade-shaders").get("file").getAsString());

        // Unticked: removed (its settings file stays, it's the player's); Iris moves on to the next ticked pack.
        JsonObject m5 = run(core, game, Set.of("photon"), v2, marker(m4), true);
        assertFalse(m5.has("evade-shaders"));
        assertFalse(Files.exists(packs.resolve("Evade Shaders v1.1.zip")));
        assertTrue(Files.exists(packs.resolve("Evade Shaders v1.1.zip.txt")));
        assertTrue(Files.readString(iris, StandardCharsets.ISO_8859_1).contains("shaderPack=photon_v1.zip"));
    }

    @Test
    void existingPlayersKeepTheirIrisChoice(@TempDir Path game) throws Exception {
        InstallerCore core = new InstallerCore();
        Path iris = game.resolve("config/iris.properties");
        Files.createDirectories(iris.getParent());
        Map<String, InstallerCore.ModFile> v1 = release("1");
        v1.put("evade-shaders", evade("1.0", "build one"));
        // A pack chosen in Iris already (an update from before Evade Shaders): left as it was.
        Files.writeString(iris, "enableShaders=true\nshaderPack=ComplementaryReimagined_r0.zip\n", StandardCharsets.ISO_8859_1);
        JsonObject m1 = run(core, game, ShaderPacks.defaults(), v1, null, true);
        assertTrue(m1.has("evade-shaders"));
        assertTrue(Files.readString(iris, StandardCharsets.ISO_8859_1).contains("shaderPack=ComplementaryReimagined_r0.zip"));
        // Iris installed but no pack ever chosen: Evade Shaders becomes it, and shaders stay as the player set them.
        Files.writeString(iris, "enableShaders=false\nshaderPack=\n", StandardCharsets.ISO_8859_1);
        run(core, game, ShaderPacks.defaults(), v1, marker(m1), true);
        String now = Files.readString(iris, StandardCharsets.ISO_8859_1);
        assertTrue(now.contains("shaderPack=Evade Shaders v1.0.zip"), now);
        assertTrue(now.contains("enableShaders=false"), now);
        assertEquals(1, now.split("shaderPack").length - 1, now);
    }

    @Test
    void installUpdateAndRemove(@TempDir Path game) throws Exception {
        InstallerCore core = new InstallerCore();
        Path packs = game.resolve("shaderpacks");
        Files.createDirectories(packs);
        Files.writeString(packs.resolve("MyOwnPack.zip"), "mine"); // the player's own: never touched

        // First install: all five.
        Map<String, InstallerCore.ModFile> v1 = release("1");
        JsonObject m1 = run(core, game, Set.of("photon", "complementary-reimagined", "complementary-unbound", "bsl", "sildurs-vibrant"), v1, null, true);
        for (InstallerCore.ModFile f : v1.values()) assertTrue(Files.isRegularFile(packs.resolve(f.filename())), f.filename());
        assertEquals(5, m1.size());
        assertTrue(Files.readString(game.resolve("config/iris.properties"), StandardCharsets.ISO_8859_1).contains("shaderPack=photon_v1.zip"));

        // Only BSL, and newer versions: the other four go, BSL is updated, Iris follows Photon's removal to BSL.
        Map<String, InstallerCore.ModFile> v2 = release("2");
        JsonObject m2 = run(core, game, Set.of("bsl"), v2, marker(m1), true);
        assertEquals(Set.of("bsl"), m2.keySet());
        assertTrue(Files.isRegularFile(packs.resolve("BSL_v2.zip")));
        assertFalse(Files.exists(packs.resolve("BSL_v1.zip")));
        for (String gone : List.of("photon_v1.zip", "ComplementaryReimagined_r1.zip", "ComplementaryUnbound_r1.zip", "Sildur's Vibrant Shaders v1 Medium.zip"))
            assertFalse(Files.exists(packs.resolve(gone)), gone);
        assertTrue(Files.isRegularFile(packs.resolve("MyOwnPack.zip")));
        assertTrue(Files.readString(game.resolve("config/iris.properties"), StandardCharsets.ISO_8859_1).contains("shaderPack=BSL_v2.zip"));

        // A pack the player picked in Iris is left selected.
        Files.writeString(game.resolve("config/iris.properties"), "enableShaders=true\nshaderPack=MyOwnPack.zip\n", StandardCharsets.ISO_8859_1);
        JsonObject m3 = run(core, game, Set.of(), v2, marker(m2), true);
        assertTrue(m3.isEmpty());
        assertFalse(Files.exists(packs.resolve("BSL_v2.zip")));
        assertTrue(Files.readString(game.resolve("config/iris.properties"), StandardCharsets.ISO_8859_1).contains("shaderPack=MyOwnPack.zip"));

        // The player's own copy of the same file is used but never removed.
        Files.write(packs.resolve("BSL_v2.zip"), served.get("BSL_v2.zip"));
        JsonObject m4 = run(core, game, Set.of("bsl"), v2, marker(m3), true);
        assertTrue(m4.getAsJsonObject("bsl").get("adopted").getAsBoolean());
        run(core, game, Set.of(), v2, marker(m4), true);
        assertTrue(Files.isRegularFile(packs.resolve("BSL_v2.zip")));
    }

    @Test
    void olderMarkersAreMigrated(@TempDir Path game) throws Exception {
        Path packs = game.resolve("shaderpacks");
        Files.createDirectories(packs);
        Files.writeString(packs.resolve("photon_v1.zip"), "old");
        Files.writeString(packs.resolve("ComplementaryReimagined_r1.zip"), "old");
        JsonObject prev = new JsonObject();
        prev.addProperty("shaders", "v1");
        prev.addProperty("shaderPackFile", "photon_v1.zip");
        prev.addProperty("lightPackFile", "ComplementaryReimagined_r1.zip");
        Map<String, String[]> was = InstallerCore.previousPacks(prev, packs, null);
        assertEquals("photon_v1.zip", was.get("photon")[0]);
        assertEquals("ComplementaryReimagined_r1.zip", was.get("complementary-reimagined")[0]);
        // Shaders switched off: both go.
        run(new InstallerCore(), game, Set.of("photon"), release("2"), prev, false);
        assertFalse(Files.exists(packs.resolve("photon_v1.zip")));
        assertFalse(Files.exists(packs.resolve("ComplementaryReimagined_r1.zip")));
    }

    @Test
    void updateReasonNamesThePack(@TempDir Path game) throws Exception {
        JsonObject marker = new JsonObject();
        marker.addProperty("evade", "1");
        marker.addProperty("loader", "1");
        marker.addProperty("fabricApi", "1");
        JsonObject rec = new JsonObject();
        JsonObject bsl = new JsonObject();
        bsl.addProperty("file", "BSL_v1.zip");
        rec.add("bsl", bsl);
        marker.add("shaderPacks", rec);
        Files.writeString(game.resolve("evade-install.json"), marker.toString());
        InstallerCore.ModFile iris = new InstallerCore.ModFile("Iris", "1", "", "", "iris.jar", "YL57xq9U", List.of(), Map.of());
        InstallerCore.Release r = new InstallerCore.Release("1", "", "", "1", "1", "", "", "", null, List.of(), null, null, null, null, null,
                iris, iris, null, List.of(), null, Map.of(), release("2"));
        assertEquals("BSL 2 is out", InstallerCore.updateReason(game, r, false, false, true, false, false, Set.of(), Set.of("bsl")));
        assertEquals("Sildur's Vibrant 2 isn't added yet", InstallerCore.updateReason(game, r, false, false, true, false, false, Set.of(), Set.of("sildurs-vibrant")));
        assertNull(InstallerCore.updateReason(game, r, false, false, false, false, false, Set.of(), Set.of("bsl")));
    }

    // ------------------------------------------------------------------ helpers

    private JsonObject run(InstallerCore core, Path game, Set<String> packs, Map<String, InstallerCore.ModFile> files, JsonObject prev, boolean shaders) throws Exception {
        List<String> skipped = new ArrayList<>();
        JsonObject out = runSkipping(core, game, packs, files, prev, skipped, shaders);
        assertTrue(skipped.isEmpty(), skipped.toString());
        return out;
    }

    private JsonObject runSkipping(InstallerCore core, Path game, Set<String> packs, Map<String, InstallerCore.ModFile> files, JsonObject prev, List<String> skipped) throws Exception {
        return runSkipping(core, game, packs, files, prev, skipped, true);
    }

    private JsonObject runSkipping(InstallerCore core, Path game, Set<String> packs, Map<String, InstallerCore.ModFile> files, JsonObject prev,
                                   List<String> skipped, boolean shaders) throws Exception {
        InstallerCore.Options o = new InstallerCore.Options(game, game, 2, false, false, false, false, shaders, "k", "n", null, false, false, Set.of(), packs);
        InstallerCore.ModFile iris = new InstallerCore.ModFile("Iris", "1", "", "", "iris.jar", "YL57xq9U", List.of(), Map.of());
        InstallerCore.Release r = new InstallerCore.Release("1", "", "", "1", "1", "", "", "", null, List.of(), null, null, null, null, null,
                iris, iris, files.get("photon"), List.of(), null, Map.of(), files);
        Path staging = game.resolve(".evade-staging");
        Files.createDirectories(staging);
        JsonObject out = core.installShaderPacks(o, r, game, staging, prev, shaders, shaders, skipped, 0, 5, (f, m) -> { });
        InstallerCore.deleteTree(staging);
        return out;
    }

    /** Writes the marker an install with {@code packs} leaves and asks why Play would update. */
    private static String updateReason(Path game, JsonObject packs, Map<String, InstallerCore.ModFile> files) throws IOException {
        JsonObject m = marker(packs);
        m.addProperty("evade", "1");
        m.addProperty("loader", "1");
        m.addProperty("fabricApi", "1");
        Files.writeString(game.resolve("evade-install.json"), m.toString());
        InstallerCore.ModFile iris = new InstallerCore.ModFile("Iris", "1", "", "", "iris.jar", "YL57xq9U", List.of(), Map.of());
        InstallerCore.Release r = new InstallerCore.Release("1", "", "", "1", "1", "", "", "", null, List.of(), null, null, null, null, null,
                iris, iris, null, List.of(), null, Map.of(), files);
        return InstallerCore.updateReason(game, r, false, false, true, false, false, Set.of(), packs.keySet());
    }

    /** Evade Shaders {@code version} with these contents, served by the local server under its stable name (SHA-256). */
    private InstallerCore.ModFile evade(String version, String contents) throws Exception {
        byte[] body = contents.getBytes(StandardCharsets.UTF_8);
        served.put("downloads/EvadeShaders.zip", body);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/downloads/EvadeShaders.zip";
        return new InstallerCore.ModFile("Evade Shaders", version, url, sha, "Evade Shaders v" + version + ".zip", null, List.of(), Map.of());
    }

    private static JsonObject marker(JsonObject packs) {
        JsonObject m = new JsonObject();
        m.add("shaderPacks", packs);
        return m;
    }

    /** Fake packs at version {@code v}, served by the local server. */
    private Map<String, InstallerCore.ModFile> release(String v) throws Exception {
        Map<String, InstallerCore.ModFile> out = new LinkedHashMap<>();
        String[][] files = {{"photon", "photon_v" + v + ".zip"}, {"complementary-reimagined", "ComplementaryReimagined_r" + v + ".zip"},
                {"complementary-unbound", "ComplementaryUnbound_r" + v + ".zip"}, {"bsl", "BSL_v" + v + ".zip"},
                {"sildurs-vibrant", "Sildur's Vibrant Shaders v" + v + " Medium.zip"}};
        for (String[] f : files) {
            byte[] body = (f[1] + " contents").getBytes(StandardCharsets.UTF_8);
            served.put(f[1], body);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(body));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + java.net.URLEncoder.encode(f[1], StandardCharsets.UTF_8).replace("+", "%20");
            out.put(f[0], new InstallerCore.ModFile(ShaderPacks.byKey(f[0]).name(), v, url, sha, f[1], "p" + f[0], List.of(), Map.of()));
        }
        return out;
    }
}
