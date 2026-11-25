package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * evadeclient.com/version.json, the release list every update comes from (the client jar, Evade Shaders, ffmpeg's
 * pinned checksum and this launcher itself), fetched and checked in one place:
 * <ul>
 *   <li><b>Signature.</b> When {@link #PUBLIC_KEY} is set, version.json must come with {@code version.json.sig}: a
 *   base64 Ed25519 signature over its exact bytes, made with an offline key. A missing or wrong signature rejects
 *   the whole list, so a changed website alone can't push a file to anyone.</li>
 *   <li><b>Never older.</b> The highest Evade Client version a list ever named is remembered per site; a list naming
 *   an older one (a rolled-back or replayed version.json) is rejected. The launcher only ever offers a strictly newer
 *   launcher ({@link #newer}).</li>
 *   <li><b>Plain paths.</b> Every {@code "file"} must be a plain path on the site ({@link #safeFile}): no "..", no
 *   scheme, no leading slash.</li>
 * </ul>
 */
final class ReleaseManifest {
    private ReleaseManifest() {}

    /**
     * The release-signing public key: the raw 32-byte Ed25519 key, base64. Empty: signatures aren't checked yet.
     * <p>
     * TODO(owner): make the key pair OFFLINE (never in this repo, never on the web server) and paste the public half here:
     * <pre>
     *   openssl genpkey -algorithm ed25519 -out evade-release.pem
     *   openssl pkey -in evade-release.pem -pubout -outform DER | tail -c 32 | base64
     * </pre>
     * then publish each release with {@code EVADE_SIGNING_KEY=/path/to/evade-release.pem scripts/build-site.sh}, which
     * writes site/version.json.sig. Once this is set, launchers refuse an unsigned version.json, so sign before you
     * ship a launcher with the key in it.
     */
    static final String PUBLIC_KEY = "";

    /** Name of the detached signature next to version.json. */
    static final String SIG_FILE = "version.json.sig";

    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    /** Fetches, verifies and parses {@code site}/version.json (never a cached copy). */
    static JsonObject fetch(HttpClient http, String site) throws IOException, InterruptedException {
        String bust = "?t=" + System.currentTimeMillis();
        byte[] body = get(http, site + "/version.json" + bust);
        String sig = null;
        if (!PUBLIC_KEY.isBlank()) {
            try {
                sig = new String(get(http, site + "/" + SIG_FILE + bust), StandardCharsets.US_ASCII).strip();
            } catch (IOException e) {
                throw new IOException(InstallerCore.host(site) + " sent a release list without its signature", e);
            }
        }
        JsonObject o = parse(body, sig, PUBLIC_KEY);
        checkFloor(site, InstallerCore.str(o, "version"), floorFile());
        return o;
    }

    /** Checks {@code sig} (when a key is set) and parses the list. */
    static JsonObject parse(byte[] body, String sig, String publicKey) throws IOException {
        if (publicKey != null && !publicKey.isBlank() && !verify(body, sig, publicKey))
            throw new IOException("The release list's signature didn't check out, so nothing from it is installed");
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("The release list couldn't be read", e);
        }
    }

    /** True when {@code sigB64} is a valid Ed25519 signature of {@code data} by {@code publicKeyB64} (raw 32 bytes). */
    static boolean verify(byte[] data, String sigB64, String publicKeyB64) {
        if (data == null || sigB64 == null || sigB64.isBlank() || publicKeyB64 == null) return false;
        try {
            byte[] raw = Base64.getDecoder().decode(publicKeyB64.strip());
            if (raw.length != 32) return false;
            // X.509 SubjectPublicKeyInfo for Ed25519 = this fixed 12-byte header + the raw key.
            byte[] spki = new byte[44];
            System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"), 0, spki, 0, 12);
            System.arraycopy(raw, 0, spki, 12, 32);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getDecoder().decode(sigB64.strip()));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ versions

    /**
     * Compares release versions numerically ("2.10.0" &gt; "2.9.1"); a pre-release ("2.7.0-beta.1") sorts before its
     * release, build metadata ("+1.21.11") is ignored. Non-numeric parts count as 0.
     */
    static int compare(String a, String b) {
        String[] x = core(a), y = core(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        boolean preA = pre(a), preB = pre(b);
        return preA == preB ? 0 : preA ? -1 : 1;
    }

    /** True only when {@code candidate} is strictly newer than {@code current} (both must be present). */
    static boolean newer(String candidate, String current) {
        if (candidate == null || candidate.isBlank() || current == null || current.isBlank()) return false;
        return compare(candidate, current) > 0;
    }

    private static String strip(String v) {
        String s = v == null ? "" : v.strip();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int plus = s.indexOf('+');
        return plus >= 0 ? s.substring(0, plus) : s;
    }

    private static String[] core(String v) {
        String s = strip(v);
        int dash = s.indexOf('-');
        return (dash >= 0 ? s.substring(0, dash) : s).split("\\.");
    }

    private static boolean pre(String v) { return strip(v).indexOf('-') >= 0; }

    private static long num(String s) {
        try { return Long.parseLong(s.strip()); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------------ anti-rollback

    /** Where the highest client version each site listed is kept. */
    static Path floorFile() { return Launcher.dataDir().resolve("release-floor.json"); }

    /**
     * Rejects a list naming an older Evade Client than one this launcher already saw from {@code site}; otherwise
     * remembers the version. A release the owner pulls back is published under a higher number instead.
     */
    static void checkFloor(String site, String version, Path file) throws IOException {
        if (version == null || version.isBlank()) return;
        String key = InstallerCore.host(site).toLowerCase(Locale.ROOT);
        JsonObject o = InstallerCore.readJsonObject(file);
        String seen = InstallerCore.str(o, key);
        if (seen != null && compare(version, seen) < 0)
            throw new IOException("The release list from " + InstallerCore.host(site) + " names Evade Client v" + version
                    + ", older than the v" + seen + " it named before, so nothing from it is installed");
        if (seen == null || compare(version, seen) > 0) {
            o.addProperty(key, version.strip());
            try {
                Files.createDirectories(file.getParent());
                InstallerCore.writeAtomic(file, InstallerCore.GSON.toJson(o));
            } catch (IOException ignored) {
                // read-only data folder: the check still ran for this list
            }
        }
    }

    // ------------------------------------------------------------------ paths and hashes

    /** {@code file} when it's a plain relative path on the site (e.g. "downloads/x.jar"), else null. */
    static String safeFile(String file) {
        if (file == null || file.length() > 200 || file.contains("..") || file.contains("://") || file.contains("\\")) return null;
        return SAFE_FILE.matcher(file).matches() ? file : null;
    }

    /** True for a 64-digit hex SHA-256. */
    static boolean sha256(String s) { return s != null && SHA256.matcher(s.strip()).matches(); }

    private static byte[] get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", LauncherInfo.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + InstallerCore.host(url));
        if (res.body().length > 1 << 20) throw new IOException("The release list is too large");
        return res.body();
    }
}
