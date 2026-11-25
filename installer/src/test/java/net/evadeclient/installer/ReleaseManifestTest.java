package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The release list's safety checks: Ed25519 signatures (with a throwaway key made in the test, never stored), version
 * ordering and anti-rollback, plain file paths, and that the launcher only ever offers a strictly newer launcher.
 */
class ReleaseManifestTest {
    private static final String SHA = "0f9121b8f3535f1a85b656a43030d1d209f06a6a1e894dfdef019d74c01a47dc";

    @Test
    void versionOrder() {
        assertTrue(ReleaseManifest.compare("2.10.0", "2.9.9") > 0);
        assertTrue(ReleaseManifest.compare("2.6.0", "2.5.0") > 0);
        assertEquals(0, ReleaseManifest.compare("2.5", "2.5.0"));
        assertEquals(0, ReleaseManifest.compare("v2.5.0", "2.5.0+build.3"));
        assertTrue(ReleaseManifest.compare("2.7.0-beta.1", "2.7.0") < 0, "a pre-release sorts before its release");
        assertTrue(ReleaseManifest.compare("2.7.0-beta.1", "2.6.9") > 0);
        assertTrue(ReleaseManifest.newer("2.6.0", "2.5.0"));
        assertFalse(ReleaseManifest.newer("2.5.0", "2.5.0"), "same version is not an update");
        assertFalse(ReleaseManifest.newer("2.4.9", "2.5.0"), "never a downgrade");
        assertFalse(ReleaseManifest.newer(null, "2.5.0"), "no version listed: no update");
        assertFalse(ReleaseManifest.newer("", "2.5.0"));
    }

    @Test
    void safePaths() {
        assertEquals("downloads/Evade-Installer.exe", ReleaseManifest.safeFile("downloads/Evade-Installer.exe"));
        assertEquals("downloads/evade-client-2.7.1.jar", ReleaseManifest.safeFile("downloads/evade-client-2.7.1.jar"));
        for (String bad : new String[]{null, "", "/etc/passwd", "../x.jar", "downloads/../../x", "https://evil.test/x.exe",
                "downloads\\x.exe", "downloads//x", "C:/x.exe", "downloads/x.exe?y", " downloads/x"})
            assertNull(ReleaseManifest.safeFile(bad), String.valueOf(bad));
        assertTrue(ReleaseManifest.sha256(SHA));
        assertFalse(ReleaseManifest.sha256(SHA.substring(1)));
        assertFalse(ReleaseManifest.sha256(null));
        assertFalse(ReleaseManifest.sha256(""));
    }

    @Test
    void signatures() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = kp.getPublic().getEncoded();
        String pub = Base64.getEncoder().encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
        byte[] body = "{\"version\":\"2.7.1\"}".getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(body);
        String sig = Base64.getEncoder().encodeToString(s.sign());

        assertTrue(ReleaseManifest.verify(body, sig, pub));
        assertEquals("2.7.1", InstallerCore.str(ReleaseManifest.parse(body, sig, pub), "version"));
        byte[] changed = "{\"version\":\"2.7.2\"}".getBytes(StandardCharsets.UTF_8);
        assertFalse(ReleaseManifest.verify(changed, sig, pub), "a changed list fails");
        assertThrows(IOException.class, () -> ReleaseManifest.parse(changed, sig, pub));
        assertThrows(IOException.class, () -> ReleaseManifest.parse(body, null, pub), "a missing signature fails once a key is set");
        assertThrows(IOException.class, () -> ReleaseManifest.parse(body, "not base64!", pub));
        assertNotNull(ReleaseManifest.parse(body, null, ""), "no key yet: unsigned lists are read");
        // The key the launcher ships with is either unset or a valid 32-byte Ed25519 key.
        if (!ReleaseManifest.PUBLIC_KEY.isBlank()) assertEquals(32, Base64.getDecoder().decode(ReleaseManifest.PUBLIC_KEY).length);
    }

    @Test
    void antiRollback(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("floor.json");
        ReleaseManifest.checkFloor("https://evadeclient.com", "2.7.1", f);
        ReleaseManifest.checkFloor("https://evadeclient.com", "2.7.1", f); // the same list again is fine
        assertThrows(IOException.class, () -> ReleaseManifest.checkFloor("https://evadeclient.com", "2.7.0", f), "rolled-back list");
        ReleaseManifest.checkFloor("https://evadeclient.com", "2.8.0", f);
        assertThrows(IOException.class, () -> ReleaseManifest.checkFloor("https://evadeclient.com", "2.7.1", f));
        ReleaseManifest.checkFloor("http://localhost:8080", "1.0.0", f); // another site has its own floor
        assertTrue(Files.readString(f).contains("2.8.0"));
    }

    @Test
    void selfUpdateOnlyOffersNewer() {
        JsonObject site = JsonParser.parseString("{\"installer\":{\"version\":\"2.6.0\","
                + "\"windows\":{\"file\":\"downloads/Evade-Installer.exe\",\"sha256\":\"" + SHA + "\"},"
                + "\"jar\":{\"file\":\"downloads/EvadeInstaller.jar\",\"sha256\":\"" + SHA + "\"}}}").getAsJsonObject();
        SelfUpdate.Update u = SelfUpdate.pick(site, true, "2.5.0");
        assertNotNull(u);
        assertEquals("2.6.0", u.version());
        assertTrue(u.url().startsWith(InstallerCore.SITE + "/downloads/Evade-Installer.exe?v="));
        assertNull(SelfUpdate.pick(site, true, "2.6.0"), "same version");
        assertNull(SelfUpdate.pick(site, false, "2.7.0"), "older: never a downgrade");

        JsonObject noVersion = site.deepCopy();
        noVersion.getAsJsonObject("installer").remove("version");
        assertNull(SelfUpdate.pick(noVersion, true, "2.5.0"), "no version listed: no update");
        JsonObject badPath = site.deepCopy();
        badPath.getAsJsonObject("installer").getAsJsonObject("windows").addProperty("file", "https://evil.test/x.exe");
        assertNull(SelfUpdate.pick(badPath, true, "2.5.0"), "a URL instead of a site path");
        JsonObject noHash = site.deepCopy();
        noHash.getAsJsonObject("installer").getAsJsonObject("jar").remove("sha256");
        assertNull(SelfUpdate.pick(noHash, false, "2.5.0"), "no SHA-256: no update");
    }

    @Test
    void tempCleanup(@TempDir Path tmp) throws Exception {
        Path old = Files.createDirectories(tmp.resolve(SelfUpdate.TEMP_PREFIX + "123"));
        Files.writeString(old.resolve("Evade-Installer.exe"), "x");
        Path running = Files.createDirectories(tmp.resolve(SelfUpdate.TEMP_PREFIX + "456"));
        Path self = running.resolve("Evade-Installer.exe");
        Files.writeString(self, "x");
        Path other = Files.createDirectories(tmp.resolve("something-else"));
        SelfUpdate.cleanTemp(tmp, self);
        assertFalse(Files.exists(old), "an earlier update's folder is removed");
        assertTrue(Files.exists(self), "the folder this copy runs from stays");
        assertTrue(Files.exists(other), "other folders are never touched");
    }

    @Test
    void downloadsNeedAllowedHosts() {
        assertTrue(InstallerCore.allowedDownload(InstallerCore.SITE + "/downloads/evade-client-latest.jar"));
        assertTrue(InstallerCore.allowedDownload("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"));
        assertTrue(InstallerCore.allowedDownload("https://github.com/GyanD/codexffmpeg/releases/download/7.1.1/ffmpeg-7.1.1-essentials_build.zip"));
        assertFalse(InstallerCore.allowedDownload("http://cdn.modrinth.com/x.jar"), "not https");
        assertFalse(InstallerCore.allowedDownload("https://evil.test/x.jar"));
        assertFalse(InstallerCore.allowedDownload(InstallerCore.SITE + ".evil.test/x.jar"));
        assertFalse(InstallerCore.allowedDownload(null));
    }

    @Test
    void versionIsStamped() {
        assertFalse(LauncherInfo.VERSION.isBlank());
        assertNotEquals("dev", LauncherInfo.VERSION, "launcher.properties is stamped from installer/build.gradle");
        assertTrue(LauncherInfo.USER_AGENT.contains(LauncherInfo.VERSION));
    }
}
