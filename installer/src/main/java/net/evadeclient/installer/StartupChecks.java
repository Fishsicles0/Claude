package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Quiet checks a few seconds after the launcher opens, reported in the bell (never a pop-up): updates for the mods,
 * resource packs and shader packs the player added to each installed instance (the same Modrinth lookup the Mods page
 * uses), and, when the launcher doesn't update itself, whether a newer launcher is out. Anything that fails (offline,
 * Modrinth busy) is skipped silently. Also holds the "What's new in the launcher" notes, shown once per version.
 */
final class StartupChecks {
    private StartupChecks() {}

    /** What's new in this launcher version (newest first; shown once after an update). */
    static final String[][] WHATS_NEW = {
            {"Light theme and accent colours", "Settings › Appearance: keep the dark Evade look or switch to light, and pick an accent colour."},
            {"Bigger text, less motion", "Text size 100 / 115 / 130 % and a reduced-motion option that stops slides and animations."},
            {"Keyboard everywhere", "Tab and Shift+Tab move through every page, Enter or Space presses, Escape closes. Ctrl+1…6 switch pages, Ctrl+P plays, F1 lists every shortcut."},
            {"Organise instances", "Groups you can fold away, pin favourites to the top, and sort by name, last played or play time."},
            {"Instances as a .zip", "Export an instance (worlds optional) and import it on another PC. Evade's own files reinstall fresh on the first Play."},
            {"Update checks on start", "The bell tells you when mods you added have updates, without slowing the launcher down."}};

    /** Checks every installed instance's own mods and packs; {@code report(instance, updates)} runs for each with updates. */
    static void modUpdates(List<Launcher.Instance> instances, BiConsumer<Launcher.Instance, Integer> report) {
        for (Launcher.Instance i : instances) {
            if (i.installed == null) continue;
            try {
                Map<Path, Content.Type> files = new LinkedHashMap<>();
                for (Path f : Updates.playerMods(Mods.list(i.dir))) files.put(f, Content.Type.MOD);
                for (Content.Type t : new Content.Type[]{Content.Type.RESOURCEPACK, Content.Type.SHADER}) {
                    try { for (Content.Pack p : Content.packs(i.dir, t, null)) if (!p.folder()) files.put(p.file(), t); } catch (RuntimeException ignored) { }
                }
                if (files.isEmpty()) continue;
                int n = (int) Updates.check(files).values().stream().filter(Updates.Known::hasUpdate).count();
                if (n > 0) report.accept(i, n);
            } catch (Exception e) {
                return; // offline or Modrinth unavailable: try again next start
            }
        }
    }

    /**
     * Whether evadeclient.com has a newer launcher than this file (only asked when self-update is off, as self-update
     * otherwise installs it before the window opens). Returns its version, or null.
     */
    static String newerLauncher() {
        Path self = SelfUpdate.selfPath();
        if (self == null) return null;
        boolean exe = self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(InstallerCore.SITE + "/version.json?t=" + System.currentTimeMillis()))
                    .timeout(Duration.ofSeconds(6)).header("User-Agent", LauncherInfo.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            JsonObject inst = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("installer");
            JsonObject pick = inst == null ? null : inst.getAsJsonObject(exe ? "windows" : "jar");
            if (pick == null || !pick.has("sha256")) return null;
            String want = pick.get("sha256").getAsString().toLowerCase(Locale.ROOT);
            if (want.length() < 10 || want.equals(SelfUpdate.sha256(self))) return null;
            return inst.has("version") ? inst.get("version").getAsString() : "";
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the "What's new" sheet should open for {@code version} (once; not for a first install or a dev build). */
    static boolean showWhatsNew(UiPrefs p, String version, boolean firstRun) {
        if (version == null || version.equals("dev")) return false;
        if (version.equals(p.seenVersion)) return false;
        if (firstRun && p.seenVersion == null) { p.seenVersion = version; p.save(); return false; }
        return true;
    }

    /** A short list "3 mods" etc. for a notice. */
    static String plural(int n, String one) { return n + " " + one + (n == 1 ? "" : "s"); }

    static List<String> lines() {
        List<String> out = new ArrayList<>();
        for (String[] w : WHATS_NEW) out.add(w[0]);
        return out;
    }
}
