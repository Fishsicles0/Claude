package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Modpacks and instance share codes: what is accepted, where files may go, and that codes survive a round trip. */
class MrpackTest {
    private static JsonObject index(String mc, String loader) {
        return JsonParser.parseString("{\"formatVersion\":1,\"game\":\"minecraft\",\"name\":\" Pack \",\"versionId\":\"1.0\","
                + "\"dependencies\":{\"minecraft\":\"" + mc + "\",\"" + loader + "\":\"0.17.0\"},\"files\":[{},{}]}").getAsJsonObject();
    }

    @Test
    void acceptsOnlyFabricOnThisVersion() {
        Mrpack.Info ok = Mrpack.info(index(InstallerCore.MC_VERSION, "fabric-loader"));
        assertTrue(ok.ok(), ok.problem());
        assertEquals("Pack", ok.name());
        assertEquals(2, ok.files());
        assertFalse(Mrpack.info(index("1.20.1", "fabric-loader")).ok());
        assertTrue(Mrpack.info(index(InstallerCore.MC_VERSION, "neoforge")).problem().contains("NeoForge"));
        JsonObject bedrock = index(InstallerCore.MC_VERSION, "fabric-loader");
        bedrock.addProperty("game", "bedrock");
        assertFalse(Mrpack.info(bedrock).ok());
    }

    @Test
    void safePathKeepsFilesInsideTheInstance(@TempDir Path root) {
        assertEquals(root.resolve("mods/a.jar"), Mrpack.safePath(root, "mods/a.jar"));
        for (String bad : new String[]{"../evil.jar", "/etc/passwd", "C:/x", "mods\\a.jar", "mods/../../x", "a//b", ".", "",
                "evade-install.json", ".evade/session.json", "evade-staging/x"}) {
            assertNull(Mrpack.safePath(root, bad), bad);
        }
    }

    @Test
    void projectOfUrl() {
        assertEquals("AANobbMI", Mrpack.projectOfUrl("https://cdn.modrinth.com/data/AANobbMI/versions/abc/sodium.jar"));
        assertNull(Mrpack.projectOfUrl("https://example.com/data/AANobbMI/versions/abc/sodium.jar"));
        assertNull(Mrpack.projectOfUrl(null));
    }

    @Test
    void privateFilesAreNeverExported() {
        assertTrue(Mrpack.privateName("config/MyAuthCache.json"));
        assertTrue(Mrpack.privateName("session_tokens.txt"));
        assertFalse(Mrpack.privateName("config/sodium-options.json"));
    }

    private static ContentApi.Version version(String id, String project) {
        return new ContentApi.Version(id, project, "1.0", "v", "release", "", "https://cdn.modrinth.com/x", "f.jar", "aa", "bb", 1,
                List.of(), List.of("fabric"), List.of(InstallerCore.MC_VERSION), "");
    }

    @Test
    void shareCodeRoundTrip(@TempDir Path dir) throws IOException {
        Launcher.Instance inst = new Launcher.Instance("x", dir, "PvP pack");
        inst.perf = true;
        inst.shaders = false;
        inst.voice = true;
        Map<Path, Updates.Known> known = new LinkedHashMap<>();
        known.put(dir.resolve("mods/a.jar"), new Updates.Known(dir.resolve("mods/a.jar"), Content.Type.MOD, "aa", version("v1", "AAAAAAAA"), null));
        known.put(dir.resolve("mods/b.jar.disabled"), new Updates.Known(dir.resolve("mods/b.jar.disabled"), Content.Type.MOD, "aa", version("v2", "BBBBBBBB"), null));
        known.put(dir.resolve("resourcepacks/p.zip"), new Updates.Known(dir.resolve("resourcepacks/p.zip"), Content.Type.RESOURCEPACK, "aa", version("v3", "CCCCCCCC"), null));
        String code = Mrpack.shareCode(inst, known);
        assertTrue(code.startsWith(Mrpack.CODE_PREFIX), code);
        Mrpack.Share s = Mrpack.readCode("  " + code.substring(0, 10) + "\n" + code.substring(10) + " ");
        assertEquals("PvP pack", s.name());
        assertEquals(List.of("AAAAAAAA"), s.mods()); // switched-off mods aren't shared
        assertEquals(List.of("CCCCCCCC"), s.resourcePacks());
        assertTrue(s.perf());
        assertFalse(s.shaderOption());
        assertTrue(s.voice());
        // codes from before the rename still read
        Mrpack.Share old = Mrpack.readCode(Mrpack.OLD_CODE_PREFIX + code.substring(Mrpack.CODE_PREFIX.length()));
        assertEquals(s.mods(), old.mods());
    }

    @Test
    void wrongCodesSayWhy() {
        IOException profile = assertThrows(IOException.class, () -> Mrpack.readCode(Mrpack.PROFILE_CODE_PREFIX + "abc"));
        assertTrue(profile.getMessage().contains("profile code"), profile.getMessage());
        assertThrows(IOException.class, () -> Mrpack.readCode("hello"));
        assertThrows(IOException.class, () -> Mrpack.readCode(Mrpack.CODE_PREFIX + "!!!not base64!!!"));
    }
}
