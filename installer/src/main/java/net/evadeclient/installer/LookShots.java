package net.evadeclient.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * --screenshots with -Devade.shots.only=look: the launcher in its themes (dark and light, other accents, larger text),
 * the Appearance settings, keyboard focus, the grouped Instances list, the What's new and Keyboard shortcuts sheets,
 * dialogs, menus, the bell and toasts, at full size and on a small screen. Sample data only (no network or files).
 */
final class LookShots {
    private LookShots() {}

    static void render(Path out) throws Exception {
        Files.createDirectories(out);
        LauncherView v = new LauncherView();
        v.version = "2.4.0";
        Path mc = Paths.get("C:/Users/Player/AppData/Roaming/.minecraft");
        v.settings.minecraftDir = mc.toString();
        v.settings.defaultMemoryGb = 4;
        InstallerCore.ModFile mod = new InstallerCore.ModFile("Sodium", "0.6.13", "", "", "sodium-fabric-0.6.13+mc1.21.11.jar",
                "AANobbMI", List.of(), Map.of());
        v.release = new InstallerCore.Release("2.0.1", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", mod,
                List.of(mod), "1.4.0", "downloads/Evade-Clips.exe", "", null, null, mod, mod, mod, List.of(mod), mod);
        v.news = Screenshots.sampleNews();
        v.newsLoading = false;
        v.content.offline = true;
        InstallerCore.Installed now = new InstallerCore.Installed("2.0.1", "0.18.6", "0.141.6+1.21.11");
        Launcher.Instance main = Screenshots.instance("default", mc.resolve("evade"), "Main", now, 4, 16);
        Launcher.Instance pvp = Screenshots.instance("pvp", mc.resolve("evade-instances/pvp"), "PvP", now, 2, 9);
        Launcher.Instance bw = Screenshots.instance("bedwars", mc.resolve("evade-instances/bedwars"), "Bedwars", now, 3, 11);
        Launcher.Instance smp = Screenshots.instance("smp", mc.resolve("evade-instances/smp"), "Friends SMP", now, 6, 24);
        Launcher.Instance builds = Screenshots.instance("builds", mc.resolve("evade-instances/builds"), "Builds with shaders", now, 6, 19);
        Launcher.Instance test = Screenshots.instance("test", mc.resolve("evade-instances/test"), "Mod testing", null, 4, 3);
        builds.shaders = true;
        v.instances = new ArrayList<>(List.of(main, pvp, bw, smp, builds, test));
        v.settings.selected = "default";
        long t = System.currentTimeMillis();
        long[][] play = {{41, 0}, {12, 26}, {6, 3}, {19, 1}, {2, 200}, {0, 0}};
        for (int k = 0; k < v.instances.size(); k++) {
            if (play[k][0] == 0) continue;
            v.game.info(v.instances.get(k)).playtime = new GameState.Playtime(play[k][0] * 3_600_000L, t - play[k][1] * 3_600_000L - 60_000, 10, null, null, null);
        }
        Screenshots.sampleHome(v);
        UiPrefs p = v.extras.prefs;
        p.pinned.add("pvp");
        p.groups.put("bedwars", "Minigames");
        p.groups.put("smp", "Survival");
        p.groups.put("builds", "Survival");
        p.groups.put("test", "Testing");
        p.collapsed.add("Testing");
        p.sort = "played";

        for (String look : new String[]{"dark", "light"}) {
            boolean light = look.equals("light");
            String n = light ? "7" : "5";
            set(v, light, 0, 100, false);
            v.page = LauncherView.Page.HOME;
            v.focusedId = "default";
            Screenshots.save(v, out, n + "0-" + look + "-home");
            v.page = LauncherView.Page.INSTANCES;
            v.focusedId = "smp";
            Screenshots.save(v, out, n + "1-" + look + "-instances-groups");
            v.page = LauncherView.Page.SETTINGS;
            Screenshots.save(v, out, n + "2-" + look + "-settings-appearance");
            v.extras.showWhatsNew();
            Screenshots.save(v, out, n + "3-" + look + "-whats-new");
            v.closeOverlays();
            v.overlay = "keys";
            Screenshots.save(v, out, n + "4-" + look + "-shortcuts");
            v.closeOverlays();
            v.page = LauncherView.Page.INSTANCES;
            v.focusedId = "smp";
            v.openPopupFor("inst-group");
            Screenshots.save(v, out, n + "5-" + look + "-group-menu");
            v.closeOverlays();
            v.openModal(new LauncherView.Modal("group", "New group", "Groups keep the Instances list tidy; fold one away with its arrow.", "Create", smp, null), "Ranked");
            Screenshots.save(v, out, n + "6-" + look + "-new-group");
            v.closeOverlays();
            // keyboard focus on the Pin button
            v.snapshot(1f);
            v.extras.alwaysShowFocus = true;
            for (int k = 0; k < 60 && !"inst-pin".equals(v.game.focusId); k++) v.getActionMap().get("gp-next").actionPerformed(null);
            Screenshots.save(v, out, n + "7-" + look + "-keyboard-focus");
            v.extras.alwaysShowFocus = false;
            v.game.focusId = null;
            v.page = LauncherView.Page.HOME;
            v.overlay = "bell";
            v.notice("3 updates for PvP", "Mods or packs you added have newer versions. Open Mods to update them.", false);
            v.overlay = "bell";
            Screenshots.save(v, out, n + "8-" + look + "-bell");
            v.closeOverlays();
            v.page = LauncherView.Page.GAME;
            v.focusedId = "default";
            v.toast("Exported Main: 412 files");
            Screenshots.save(v, out, n + "9-" + look + "-game-toast");
            v.page = LauncherView.Page.MODS;
            Screenshots.save(v, out, n + "9b-" + look + "-mods");
            // small screen (1366×768 at 125%)
            v.setUiScale(LauncherView.fitScale(1092, 582));
            v.page = LauncherView.Page.INSTANCES;
            v.focusedId = "smp";
            Screenshots.save(v, out, n + "9c-" + look + "-small-instances");
            v.page = LauncherView.Page.SETTINGS;
            Screenshots.save(v, out, n + "9d-" + look + "-small-settings");
            v.page = LauncherView.Page.HOME;
            Screenshots.save(v, out, n + "9e-" + look + "-small-home");
            v.setUiScale(1f);
        }
        // accents and text size
        set(v, false, 4, 100, false);
        v.page = LauncherView.Page.HOME;
        Screenshots.save(v, out, "80-dark-blue-home");
        set(v, true, 2, 100, false);
        v.page = LauncherView.Page.INSTANCES;
        Screenshots.save(v, out, "81-light-green-instances");
        set(v, false, 5, 100, false);
        v.page = LauncherView.Page.SETTINGS;
        Screenshots.save(v, out, "82-dark-purple-settings");
        set(v, false, 0, 130, true);
        v.page = LauncherView.Page.SETTINGS;
        Screenshots.save(v, out, "83-text-130-settings");
        v.page = LauncherView.Page.HOME;
        Screenshots.save(v, out, "84-text-130-home");
        v.page = LauncherView.Page.INSTANCES;
        Screenshots.save(v, out, "85-text-130-instances");
        set(v, true, 0, 115, false);
        v.page = LauncherView.Page.GAME;
        Screenshots.save(v, out, "86-light-text-115-game");
        clips(v, out);
        set(v, false, 0, 100, false);
    }

    private static void set(LauncherView v, boolean light, int accent, int text, boolean motion) {
        UiPrefs p = v.extras.prefs;
        p.light = light;
        p.accent = accent;
        p.textSize = text;
        p.reduceMotion = motion;
        v.extras.setPrefs(p);
    }

    /** The embedded Clips pages in both themes (with the self-test's sample library when it's there). */
    private static void clips(LauncherView v, Path out) {
        try {
            Path data = Path.of(System.getProperty("clips.shots.data", "clips/build/selftest"));
            net.evadeclient.clips.core.Settings.overrideAppDir(out.resolve("clips-appdata").toAbsolutePath());
            net.evadeclient.clips.core.Settings s = new net.evadeclient.clips.core.Settings();
            s.clipsFolder = (Files.isDirectory(data.resolve("clips")) ? data.resolve("clips") : out.resolve("clips-sample")).toAbsolutePath().toString();
            s.source = "test";
            s.playSound = false;
            s.showToast = false;
            s.controlServer = false;
            net.evadeclient.clips.ui.Theme.install();
            var app = new net.evadeclient.clips.ui.App(s);
            app.library.load();
            v.clips = app;
            v.clipsNote = null;
            var pages = new net.evadeclient.clips.ui.ClipsPages(app);
            pages.onPageChanged(p -> v.clipsPage = p);
            v.clipsHost = pages;
            v.add(pages);
            v.page = LauncherView.Page.CLIPS;
            Thread.sleep(1500);
            set(v, false, 0, 100, false);
            Screenshots.saveEdt(v, out, "90-dark-clips-library");
            set(v, true, 0, 100, false);
            Screenshots.saveEdt(v, out, "91-light-clips-library");
            javax.swing.SwingUtilities.invokeAndWait(() -> pages.show("settings"));
            Screenshots.saveEdt(v, out, "92-light-clips-settings");
            javax.swing.SwingUtilities.invokeAndWait(() -> pages.show("record"));
            set(v, false, 4, 100, false);
            Screenshots.saveEdt(v, out, "93-dark-blue-clips-record");
            app.shutdown();
            v.remove(pages);
            v.clipsHost = null;
        } catch (Throwable e) {
            System.err.println("Clips pages not rendered: " + e);
        }
    }
}
