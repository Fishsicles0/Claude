package net.evadeclient.installer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Quiet checks a few seconds after the launcher opens, reported in the bell (never a pop-up): updates for the mods,
 * resource packs and shader packs the player added to each installed instance (the same Modrinth lookup the Mods page
 * uses). Anything that fails (offline, Modrinth busy) is skipped silently. (A newer launcher is found by
 * {@link SelfUpdate#check}.) Also holds the "What's new in the launcher" notes, shown once per version.
 */
final class StartupChecks {
    private StartupChecks() {}

    /** What's new in this launcher version (newest first; shown once after an update). */
    static final String[][] WHATS_NEW = {
            {"Always up to date", "The launcher now looks for a new Evade and a new launcher every 10 minutes and again right before Play, so leaving it open never keeps you on an old version."},
            {"Add-ons tab", "Mods > Add-ons installs Distant Horizons, Flashback, Litematica, MiniHUD, SkyHanni and Axiom from Modrinth in one click, with each one's licence and what was tested."},
            {"Smarter mod checks", "Version clashes are spotted (e.g. a mod that needs a different Sodium), no more false \"problems will stop the game\" warnings, and an overlap with an Evade mod offers to switch Evade's off."},
            {"Updates ask first", "When a newer Evade Launcher is out, it asks before updating, checks the download's SHA-256, and never installs an older version."}};

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
                int n = ContentPins.unpinnedUpdates(i.dir, Updates.check(files).values()).size(); // pinned ones aren't news
                if (n > 0) report.accept(i, n);
            } catch (Exception e) {
                return; // offline or Modrinth unavailable: try again next start
            }
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
