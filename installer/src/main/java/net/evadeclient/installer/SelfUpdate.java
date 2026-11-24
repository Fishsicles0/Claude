package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Keeps the installer itself up to date, so a copy downloaded once never has to be downloaded again. On start it
 * asks evadeclient.com which installer is current. If this file differs, it downloads the new one, checks its
 * SHA-256, starts it and quits; the new one then copies itself over the old file, so the same shortcut or download
 * opens the new installer from then on. Any problem (offline, slow, a failed check) just carries on with this copy.
 */
final class SelfUpdate {
    static final String UPDATED = "--self-updated", REPLACE = "--replace";

    private SelfUpdate() {}

    /** Returns true if a newer installer was started (the caller should exit). */
    static boolean run(List<String> args) {
        if (Boolean.getBoolean("evade.noSelfUpdate")) return false;
        if (args.contains(UPDATED)) {
            replaceOld(args);
            return false; // this is the new one: never update twice in a row
        }
        Path self = selfPath();
        if (self == null) return false;
        boolean exe = self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(InstallerCore.SITE + "/version.json?t=" + System.currentTimeMillis()))
                    .timeout(Duration.ofSeconds(6)).header("user-agent", "EvadeInstaller").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return false;
            JsonObject inst = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("installer");
            JsonObject pick = inst == null ? null : inst.getAsJsonObject(exe ? "windows" : "jar");
            if (pick == null || !pick.has("sha256") || !pick.has("file")) return false;
            String want = pick.get("sha256").getAsString().toLowerCase(Locale.ROOT), file = pick.get("file").getAsString();
            if (want.length() < 10 || want.equals(sha256(self))) return false;

            Path dir = Files.createTempDirectory("evade-installer-update");
            Path fresh = dir.resolve(exe ? "Evade-Installer.exe" : "EvadeInstaller.jar");
            HttpResponse<InputStream> dl = http.send(HttpRequest.newBuilder(URI.create(InstallerCore.SITE + "/" + file + "?v=" + want.substring(0, 10)))
                    .timeout(Duration.ofSeconds(60)).header("user-agent", "EvadeInstaller").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (dl.statusCode() != 200) return false;
            try (InputStream in = dl.body()) { Files.copy(in, fresh, StandardCopyOption.REPLACE_EXISTING); }
            if (!want.equals(sha256(fresh))) { Files.deleteIfExists(fresh); return false; }

            List<String> cmd = new ArrayList<>();
            if (exe) cmd.add(fresh.toString());
            else {
                String java = Paths.get(System.getProperty("java.home"), "bin", InstallerCore.WINDOWS ? "javaw.exe" : "java").toString();
                cmd.addAll(List.of(java, "-jar", fresh.toString()));
            }
            cmd.addAll(List.of(UPDATED, REPLACE, self.toString()));
            new ProcessBuilder(cmd).inheritIO().start();
            return true;
        } catch (Exception e) {
            System.err.println("Installer self-update skipped: " + e);
            return false;
        }
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
