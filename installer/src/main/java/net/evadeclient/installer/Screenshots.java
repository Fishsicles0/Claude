package net.evadeclient.installer;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** --screenshots: every launcher page and state, rendered with sample data (no display, network or files needed). */
final class Screenshots {
    private Screenshots() {}

    /** Device pixels per design pixel in the PNGs (-Devade.shots.scale=1, 1.25, 1.5 or 2; default 2). */
    static final float SCALE = Float.parseFloat(System.getProperty("evade.shots.scale", "2"));

    static void render(Path out) throws Exception {
        if ("content".equals(System.getProperty("evade.shots.only"))) { ContentShots.render(out); return; }
        if ("look".equals(System.getProperty("evade.shots.only"))) { LookShots.render(out); return; }
        if ("v25".equals(System.getProperty("evade.shots.only"))) { V25Shots.render(out); return; }
        if ("shaders".equals(System.getProperty("evade.shots.only"))) { ShaderShots.render(out); return; }
        LauncherView v = new LauncherView();
        v.version = EvadeInstaller.launcherVersion();
        Path mc = Paths.get("C:/Users/Player/AppData/Roaming/.minecraft");
        v.settings.minecraftDir = mc.toString();
        v.settings.defaultMemoryGb = 4;
        v.setupMemory = 4;

        InstallerCore.ModFile mod = new InstallerCore.ModFile("Sodium", "0.6.13", "", "", "sodium-fabric-0.6.13+mc1.21.11.jar",
                "AANobbMI", List.of(), Map.of());
        InstallerCore.Release release = new InstallerCore.Release("2.0.1", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", mod,
                List.of(mod), "1.4.0", "downloads/Evade-Clips.exe", "", null, null, mod, mod, mod, List.of(mod), mod);
        v.release = release;
        v.news = sampleNews();
        v.newsLoading = false;

        // ---- first run: nothing installed yet
        Launcher.Instance main = instance("default", mc.resolve("evade"), "Main", null, 4, 0);
        v.instances = new ArrayList<>(List.of(main));
        save(v, out, "01-welcome");
        v.setup = true;
        v.play = LauncherView.Play.UPDATING;
        v.progress = 0.62;
        v.status = "Downloading Evade Client v2.0.1";
        save(v, out, "02-welcome-installing");
        v.play = LauncherView.Play.ERROR;
        v.error = "Couldn't reach evadeclient.com. Check your internet connection and try again.";
        save(v, out, "03-welcome-error");
        v.setup = false;
        v.play = LauncherView.Play.IDLE;

        // ---- installed: three instances
        main = instance("default", mc.resolve("evade"), "Main", new InstallerCore.Installed("2.0.1", "0.18.6", "0.141.6+1.21.11"), 4, 16);
        Launcher.Instance pvp = instance("pvp", mc.resolve("evade-instances").resolve("pvp"), "PvP", new InstallerCore.Installed("2.0.0", "0.18.6", "0.141.6+1.21.11"), 2, 9);
        pvp.pending = "Evade Client v2.0.1 is out";
        pvp.shaders = false;
        pvp.voice = false;
        Launcher.Instance builds = instance("builds", mc.resolve("evade-instances").resolve("builds"), "Builds with shaders", new InstallerCore.Installed("2.0.1", "0.18.6", "0.141.6+1.21.11"), 6, 19);
        builds.shaders = true;
        v.instances = new ArrayList<>(List.of(main, pvp, builds));
        v.settings.selected = "default";
        sampleHome(v);
        save(v, out, "04-home");
        v.overlay = "friends";
        save(v, out, "04b-home-friends");
        v.overlay = "server:1";
        save(v, out, "04c-home-server-card");
        v.overlay = "bell";
        save(v, out, "04d-home-notifications");
        v.overlay = null;
        v.unread = 0;
        v.openServerModal(-1);
        v.modalField.setText("Practice");
        v.modalField2.setText("practice.example.net");
        save(v, out, "04e-home-add-server");
        v.closeOverlays();
        v.nextSlide(1);
        v.nextSlide(1);
        v.nextSlide(1);
        save(v, out, "04f-home-slide-clips");
        v.nextSlide(1);

        v.settings.selected = "pvp";
        save(v, out, "05-home-update");
        v.play = LauncherView.Play.UPDATING;
        v.playName = "PvP";
        v.progress = 0.71;
        v.status = "Downloading Lithium 0.15.0";
        save(v, out, "06-home-updating");
        v.play = LauncherView.Play.LAUNCHED;
        save(v, out, "07-home-press-play");
        v.play = LauncherView.Play.NO_LAUNCHER;
        save(v, out, "08-home-no-launcher");
        v.play = LauncherView.Play.ERROR;
        v.error = "Minecraft is using sodium-fabric-0.6.13+mc1.21.11.jar. Close Minecraft and the Minecraft Launcher, then try again.";
        save(v, out, "09-home-error");
        v.play = LauncherView.Play.IDLE;
        v.settings.selected = "default";

        // ---- instances
        v.page = LauncherView.Page.INSTANCES;
        v.focusedId = "pvp";
        pvp.dirty = true;
        save(v, out, "10-instances");
        pvp.dirty = false;
        v.openModalForShots("create", "Survival");
        save(v, out, "11-instances-new");
        v.closeOverlays();
        v.openModalForShots("delete", null);
        save(v, out, "12-instances-delete");
        v.closeOverlays();

        // ---- mods
        v.page = LauncherView.Page.MODS;
        // With -Devade.shots.instance=<a game folder>, its real mods (and their real icons) are listed.
        String real = System.getProperty("evade.shots.instance");
        if (real != null && Files.isDirectory(Path.of(real, "mods"))) {
            v.mods = Mods.list(Path.of(real));
            for (Mods.Entry e : v.mods) ModIcons.get(e.file(), null);
            for (int i = 0; i < 150 && ModIcons.busy(); i++) Thread.sleep(200);
        } else v.mods = sampleMods(main.dir.resolve("mods"));
        save(v, out, "13-mods");
        v.browse = true;
        v.searchField.setText("");
        v.lastQuery = "";
        v.hits = sampleHits();
        v.hitsTotal = 4210;
        if (Boolean.getBoolean("evade.shots.live")) {
            // the real Modrinth results, with their icons
            try {
                Mods.Page page = Mods.search(new InstallerCore(), "", 0);
                v.hits = page.hits();
                v.hitsTotal = page.total();
                for (Mods.Hit h : v.hits) ModIcons.url(h.iconUrl(), null);
                for (int i = 0; i < 150 && ModIcons.busy(); i++) Thread.sleep(200);
            } catch (Exception e) {
                System.err.println("Live Modrinth results skipped: " + e.getMessage());
            }
        }
        v.installedProjects.add("w7ThoJFB");
        v.modState.put("1IjD5062", "installing");
        v.modProgress = 0.55;
        save(v, out, "14-mods-browse");
        v.modState.clear();
        v.browse = false;

        // ---- settings
        v.page = LauncherView.Page.SETTINGS;
        v.legacyClips = true;
        save(v, out, "15-settings");
        v.legacyClips = false;
        v.clipsBlocked = true;
        save(v, out, "15b-settings-old-clips-running");
        v.page = LauncherView.Page.CLIPS;
        save(v, out, "15c-clips-old-app-running");
        v.clipsBlocked = false;
        v.page = LauncherView.Page.SETTINGS;

        // ---- small screens: 1366×768 at 125% (about 1092×582 usable) and a 1280×720 laptop with a taskbar
        v.page = LauncherView.Page.HOME;
        v.setUiScale(LauncherView.fitScale(1092, 582));
        save(v, out, "21-small-1366x768-125pct");
        v.setUiScale(LauncherView.fitScale(1280, 680));
        save(v, out, "22-small-1280x720");
        v.page = LauncherView.Page.INSTANCES;
        v.setUiScale(LauncherView.fitScale(1092, 582));
        save(v, out, "23-small-instances");
        v.setUiScale(1f);
        v.page = LauncherView.Page.SETTINGS;

        // ---- clips (the real Clips pages, with the self-test's sample library when it's there)
        // ---- game state, logs, crash help, instance settings
        GameShots.render(v, out);
        clipsPages(v, out);
        v.page = LauncherView.Page.HOME;
        v.openPopupFor("home-switch");
        save(v, out, "16-home-switch-instance");
        v.closeOverlays();
        ContentShots.render(out);
    }

    /** Sample player, servers (with drawn icons, no real server art), notices and a Clips stand-in for Home. */
    static void sampleHome(LauncherView v) throws Exception {
        long t0 = System.currentTimeMillis();
        while (v.heroImages.size() < 10 && System.currentTimeMillis() - t0 < 5000) Thread.sleep(50);
        v.playerName = "Player";
        v.settings.servers.clear();
        String[][] servers = {{"Evade Practice", "practice.example.net", "1834", "4000", "41"}, {"Skyblock", "sky.example.org", "612", "1000", "63"},
                {"Friends SMP", "smp.example.com:25570", "4", "20", "18"}, {"Build Comp", "build.example.net", null, null, null}};
        int[] colors = {0xE11D2E, 0x3D8BFF, 0x3DDC84, 0xF5A524};
        for (int i = 0; i < servers.length; i++) {
            String[] sv = servers[i];
            v.settings.servers.add(new Launcher.Server(sv[0], sv[1]));
            String k = ServersDat.key(sv[1]);
            if (sv[2] != null) {
                v.serverStatus.put(k, new ServerPing.Status(i == 0 ? "Evade Practice · duels, FFA and parkour" : i == 1 ? "Season 4 is live: new islands" : "Just us. Be nice.",
                        Integer.parseInt(sv[2]), Integer.parseInt(sv[3]), "1.21.11", null, Long.parseLong(sv[4])));
                v.serverIcons.put(k, icon(colors[i], i));
            } else v.serverDown.add(k);
        }
        long now = System.currentTimeMillis();
        v.notices.add(new LauncherView.Notice("Clip saved", "1v3 clutch at mid · 0:30", now - 4 * 60_000, false));
        v.notices.add(new LauncherView.Notice("Update ready for PvP", "Evade Client v2.0.1 is out. Press Play to update.", now - 26 * 60_000, false));
        v.notices.add(new LauncherView.Notice("Evade Clips is built in now", "The separate Evade Clips app isn't needed any more.", now - 3 * 3_600_000, false));
        v.unread = 2;
        v.clipsNote = "Instant replay, the clip library and the editor run inside the launcher.";
    }

    /** A simple 64×64 server icon: a coloured tile with a pixel letter shape (made here, no real server art). */
    private static java.awt.image.BufferedImage icon(int rgb, int kind) {
        var img = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        java.awt.Color c = new java.awt.Color(rgb);
        g.setPaint(new java.awt.GradientPaint(0, 0, c, 64, 64, c.darker().darker()));
        g.fillRect(0, 0, 64, 64);
        g.setColor(new java.awt.Color(255, 255, 255, 230));
        int[][] shapes = {
                {0b111110, 0b100000, 0b111100, 0b100000, 0b111110},
                {0b011110, 0b100000, 0b011100, 0b000010, 0b111100},
                {0b111110, 0b100000, 0b111100, 0b100000, 0b100000},
                {0b111100, 0b100010, 0b111100, 0b100010, 0b111100}};
        int[] sh = shapes[kind % shapes.length];
        for (int r = 0; r < 5; r++) for (int col = 0; col < 6; col++)
            if ((sh[r] >> (5 - col) & 1) == 1) g.fillRect(14 + col * 6, 17 + r * 6, 6, 6);
        g.dispose();
        return img;
    }

    /** The Clips section with the real Clips pages (headless: laid out by hand). */
    private static void clipsPages(LauncherView v, Path out) {
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
            int n = 0;
            String[] names = {"1v3 clutch at mid", "Crystal PvP final", "Bridge fight", "Pearl escape", "Totem pop chain", "Montage draft"};
            for (var c : app.library.query("", "all", null, "newest")) if (n < names.length && c.kind != net.evadeclient.clips.core.Clip.Kind.EXPORT) c.title = names[n++];
            v.clips = app;
            v.clipsNote = null;
            var pages = new net.evadeclient.clips.ui.ClipsPages(app);
            pages.onPageChanged(p -> v.clipsPage = p);
            v.clipsHost = pages;
            v.add(pages);
            v.page = LauncherView.Page.CLIPS;
            Thread.sleep(1500); // thumbnails
            saveEdt(v, out, "17-clips-library");
            javax.swing.SwingUtilities.invokeAndWait(() -> pages.show("record"));
            saveEdt(v, out, "18-clips-record");
            javax.swing.SwingUtilities.invokeAndWait(() -> pages.show("settings"));
            saveEdt(v, out, "19-clips-settings");
            v.page = LauncherView.Page.HOME;
            saveEdt(v, out, "20-home-with-clips");
            v.page = LauncherView.Page.CLIPS;
            javax.swing.SwingUtilities.invokeAndWait(() -> pages.show("library"));
            v.setUiScale(LauncherView.fitScale(1092, 582));
            saveEdt(v, out, "24-small-clips-library");
            v.setUiScale(1f);
            app.shutdown();
        } catch (Throwable e) {
            System.err.println("Clips pages not rendered: " + e);
            e.printStackTrace();
        }
    }

    /** Like {@link #save}, on the UI thread, letting the Clips pages' background work (thumbnails) land between passes. */
    static void saveEdt(LauncherView v, Path dir, String name) throws Exception {
        for (int i = 0; i < 6; i++) {
            javax.swing.SwingUtilities.invokeAndWait(() -> v.snapshot(1f));
            Thread.sleep(250);
        }
        java.awt.image.BufferedImage[] img = new java.awt.image.BufferedImage[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> { v.snapshot(Screenshots.SCALE); img[0] = v.snapshot(Screenshots.SCALE); });
        ImageIO.write(img[0], "png", dir.resolve(name + ".png").toFile());
    }

    static Launcher.Instance instance(String id, Path dir, String name, InstallerCore.Installed installed, int mem, int mods) {
        Launcher.Instance i = new Launcher.Instance(id, dir, name);
        i.installed = installed;
        i.memoryGb = mem;
        i.modCount = mods;
        return i;
    }

    static List<News.Entry> sampleNews() {
        return List.of(
                new News.Entry("v2.0.1", "v2.0.1", "23 Nov 2026", "Updating is painless, and one mod removed.", List.of(
                        "The installer updates itself: open the copy you already have and it fetches the newest installer from evadeclient.com, checks it and switches to it.",
                        "Evade Clips updates even while it's open: the installer closes it, waits out antivirus scans, or installs the new version beside the running one.",
                        "Downloads always come straight from the latest build, never from an old cached copy.",
                        "Removed Nametags: restyling other players' name tags isn't allowed on many servers.",
                        "Fullbright now tells you when shaders are on, because shader packs do their own lighting and ignore it."), null),
                new News.Entry("v2.0.0", "v2.0.0", "23 Nov 2026", "A new look, a much bigger FPS boost, deeper mods, a minimap, and Evade icons that work.", List.of(
                        "New look: smooth rounded shapes, soft shadows, sharp Inter text at every GUI scale, and animations throughout the menu.",
                        "FPS Mode (Performance): one-click Max FPS, Balanced (default) or Quality.",
                        "Benchmark: type .bench to measure your average FPS and 1% low over 30 seconds.",
                        "Minimap (new HUD element): terrain only, never players, mobs or caves."), null));
    }

    private static List<Mods.Entry> sampleMods(Path dir) {
        Object[][] rows = {
                {"evade-client-2.0.1.jar", true, "Evade Client", "2.0.1", "Server-safe quality-of-life client: HUD, zoom, freelook, waypoints and more.", "Evade"},
                {"fabric-api-0.141.6+1.21.11.jar", true, "Fabric API", "0.141.6+1.21.11", "Core API module providing key hooks and inter-compatibility features.", "Evade"},
                {"sodium-fabric-0.6.13+mc1.21.11.jar", true, "Sodium", "0.6.13+mc1.21.11", "Sodium is a powerful rendering engine for Minecraft which improves frame rates.", "FPS mods"},
                {"lithium-fabric-0.15.0+mc1.21.11.jar", true, "Lithium", "0.15.0+mc1.21.11", "General-purpose optimization mod for Minecraft.", "FPS mods"},
                {"voicechat-fabric-1.21.11-2.6.6.jar", true, "Simple Voice Chat", "1.21.11-2.6.6", "A working voice chat in Minecraft!", "Voice chat"},
                {"zoomify-2.14.6+1.21.11.jar", true, "Zoomify", "2.14.6+1.21.11", "A zoom mod with infinite customizability.", null},
                {"appleskin-fabric-mc1.21.11-3.0.7.jar", true, "AppleSkin", "3.0.7+mc1.21.11", "Adds food value information to tooltips and HUD.", null},
                {"mousetweaks-fabric-mc1.21.11-2.30.jar.disabled", false, "Mouse Tweaks", "2.30", "Enhances inventory management by adding various functions to the mouse buttons.", null},
                {"yet-another-config-lib-3.8.0+1.21.11-fabric.jar", true, "YetAnotherConfigLib", "3.8.0+1.21.11-fabric", "A builder-based configuration library for Minecraft.", null},
        };
        List<Mods.Entry> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new Mods.Entry(dir.resolve((String) r[0]), (boolean) r[1], null, (String) r[2], (String) r[3], (String) r[4], "", (String) r[5], 1));
        }
        return out;
    }

    private static List<Mods.Hit> sampleHits() {
        return List.of(
                new Mods.Hit("w7ThoJFB", "zoomify", "Zoomify (Zoom)", "isxander", "A zoom mod with infinite customizability.", 73_586_418, 4811407),
                new Mods.Hit("1IjD5062", "continuity", "Continuity", "Pepper_Bell", "A Fabric mod that allows for efficient connected textures.", 31_204_551, 0x6C8CBF),
                new Mods.Hit("EsAfCjCV", "appleskin", "AppleSkin", "squeek502", "Food/hunger-related HUD improvements.", 48_002_113, 0xC83C3C),
                new Mods.Hit("AANobbMI", "sodium", "Sodium", "jellysquid3", "The fastest and most compatible rendering optimization mod for Minecraft.", 120_552_800, 0x8EE2A1),
                new Mods.Hit("aC3cM3Vq", "mouse-tweaks", "Mouse Tweaks", "YaLTeR", "Enhances inventory management by adding various functions to the mouse buttons.", 40_120_000, 0x7F6A4E),
                new Mods.Hit("mOgUt4GM", "modmenu", "Mod Menu", "Prospector", "Adds a mod menu to view the list of mods you have installed.", 98_301_559, 0x5A6B7C));
    }

    static void save(LauncherView v, Path dir, String name) throws Exception {
        for (int i = 0; i < 40; i++) v.snapshot(1f);
        ImageIO.write(v.snapshot(Screenshots.SCALE), "png", dir.resolve(name + ".png").toFile());
    }
}
