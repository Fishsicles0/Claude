package net.evadeclient.installer;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the launcher itself up to date, and only with the player's say-so. A few seconds after the window opens,
 * {@link #check} asks evadeclient.com's release list ({@link ReleaseManifest}: signature when enabled, never an older
 * list) whether a strictly newer launcher is out. The launcher then asks the player; only on "Update" does
 * {@link #install} download it, check its SHA-256, start it and quit. The new copy then copies itself over the old
 * file, so the same shortcut opens the new launcher from then on, and the next normal start removes the temporary
 * copy. Any problem (offline, a failed check, an older or same version) leaves this copy as it is.
 */
final class SelfUpdate {
    static final String UPDATED = "--self-updated", REPLACE = "--replace";
    /** Prefix of the temporary folders a downloaded launcher runs from until the next start. */
    static final String TEMP_PREFIX = "evade-installer-update";
    /** A launcher download larger than this is refused. */
    static final long MAX_BYTES = 256L << 20;

    private SelfUpdate() {}

    /** A newer launcher on evadeclient.com: its version, where to get it and its SHA-256. */
    record Update(String version, String url, String sha256, boolean exe) {}

    /**
     * In a just-updated copy: put it in place of the old file. In a normal start: remove the temporary copies earlier
     * updates ran from. Never downloads anything.
     */
    static void finishUpdate(List<String> args) {
        if (args.contains(UPDATED)) replaceOld(args);
        else cleanTemp(Paths.get(System.getProperty("java.io.tmpdir", ".")), selfPath());
    }

    /** The newer launcher evadeclient.com offers for this file, or null (none, not newer, offline, or run from classes). */
    static Update check() {
        if (Boolean.getBoolean("evade.noSelfUpdate")) return null;
        Path self = selfPath();
        if (self == null) return null;
        boolean exe = self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
        try {
            JsonObject site = ReleaseManifest.fetch(client(), InstallerCore.SITE);
            Update u = pick(site, exe, LauncherInfo.VERSION);
            if (u == null || u.sha256().equals(sha256(self))) return null;
            return u;
        } catch (Exception e) {
            System.err.println("Launcher update check skipped: " + e.getMessage());
            return null;
        }
    }

    /**
     * The launcher entry in a release list for this kind of file, when it is strictly newer than {@code current},
     * names a plain file on the site and a SHA-256. Null otherwise (an older or equal version is never offered).
     */
    static Update pick(JsonObject site, boolean exe, String current) {
        JsonObject inst = site != null && site.has("installer") && site.get("installer").isJsonObject() ? site.getAsJsonObject("installer") : null;
        if (inst == null) return null;
        JsonObject f = inst.has(exe ? "windows" : "jar") && inst.get(exe ? "windows" : "jar").isJsonObject() ? inst.getAsJsonObject(exe ? "windows" : "jar") : null;
        if (f == null) return null;
        String version = InstallerCore.str(inst, "version"), file = ReleaseManifest.safeFile(InstallerCore.str(f, "file")), sha = InstallerCore.str(f, "sha256");
        if (file == null || !ReleaseManifest.sha256(sha) || !ReleaseManifest.newer(version, current)) return null;
        sha = sha.strip().toLowerCase(Locale.ROOT);
        return new Update(version.strip(), InstallerCore.SITE + "/" + file + "?v=" + sha.substring(0, 10), sha, exe);
    }

    /** Downloads {@code u}, checks it and starts it. True when the new launcher was started (the caller then quits). */
    static boolean install(Update u) {
        Path self = selfPath();
        if (self == null || u == null) return false;
        Path dir = null;
        try {
            dir = Files.createTempDirectory(TEMP_PREFIX);
            Path fresh = dir.resolve(u.exe() ? "Evade-Installer.exe" : "EvadeInstaller.jar");
            HttpResponse<InputStream> dl = client().send(HttpRequest.newBuilder(URI.create(u.url()))
                    .timeout(Duration.ofSeconds(60)).header("User-Agent", LauncherInfo.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = InstallerCore.stallGuard(dl.body(), InstallerCore.STALL_MS); var out = Files.newOutputStream(fresh)) {
                if (dl.statusCode() != 200) throw new IOException("HTTP " + dl.statusCode());
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if ((done += n) > MAX_BYTES) throw new IOException("the download is too large");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (!u.sha256().equalsIgnoreCase(HexFormat.of().formatHex(md.digest()))) throw new IOException("the download failed its SHA-256 check");

            List<String> cmd = new ArrayList<>();
            if (u.exe()) cmd.add(fresh.toString());
            else {
                String java = Paths.get(System.getProperty("java.home"), "bin", InstallerCore.WINDOWS ? "javaw.exe" : "java").toString();
                cmd.addAll(List.of(java, "-jar", fresh.toString()));
            }
            // The file the new copy replaces: where this one lives (a just-updated copy runs from its temp folder).
            Path home = AppHost.homePath();
            cmd.addAll(List.of(UPDATED, REPLACE, (home != null ? home : self).toString()));
            new ProcessBuilder(cmd).inheritIO().start();
            return true;
        } catch (Exception e) {
            System.err.println("Launcher update failed: " + e.getMessage());
            if (dir != null) try { InstallerCore.deleteTree(dir); } catch (Exception ignored) { }
            return false;
        }
    }

    /** Removes the temporary folders earlier self-updates ran from, except the one {@code self} is in. Best effort. */
    static void cleanTemp(Path tmp, Path self) {
        if (tmp == null || !Files.isDirectory(tmp)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp, TEMP_PREFIX + "*")) {
            for (Path d : ds) {
                if (!Files.isDirectory(d) || (self != null && self.startsWith(d))) continue;
                try { InstallerCore.deleteTree(d); } catch (Exception ignored) { /* still running or locked: next time */ }
            }
        } catch (Exception ignored) { }
    }

    /** In the new copy: put this file in place of the old one (once the old one has quit), best effort. */
    private static void replaceOld(List<String> args) {
        int i = args.indexOf(REPLACE);
        Path self = selfPath();
        if (i < 0 || i + 1 >= args.size() || self == null) return;
        Path old = Paths.get(args.get(i + 1));
        if (!Files.isRegularFile(old) || old.equals(self)) return;
        Thread t = new Thread(() -> {
            for (int n = 0; n < 40; n++) {
                try {
                    Files.copy(self, old, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (Exception busy) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }
            }
        }, "installer-replace-old");
        t.setDaemon(true);
        t.start();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** The installer file this program runs from (the .exe on Windows, else the .jar), or null when run from classes. */
    static Path selfPath() {
        try {
            String exe = System.getProperty("launch4j.exefile");
            if (exe != null && !exe.isBlank()) return Paths.get(exe).toAbsolutePath();
            Path p = Paths.get(SelfUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return Files.isRegularFile(p) && (n.endsWith(".jar") || n.endsWith(".exe")) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
