package net.evadeclient.installer;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** --screenshots: every launcher page and state, rendered with sample data (no display, network or files needed). */
final class Screenshots {
    private Screenshots() {}

    static void render(Path out) throws Exception {
        LauncherView v = new LauncherView();
        v.version = EvadeInstaller.launcherVersion();
        Path mc = Paths.get("C:/Users/Player/AppData/Roaming/.minecraft");
        v.settings.minecraftDir = mc.toString();
        v.settings.defaultMemoryGb = 4;
        v.setupMemory = 4;

        InstallerCore.ModFile mod = new InstallerCore.ModFile("Sodium", "0.6.13", "", "", "sodium-fabric-0.6.13+mc1.21.11.jar",
                "AANobbMI", List.of(), Map.of());
        InstallerCore.Release release = new InstallerCore.Release("2.0.1", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", mod,
                List.of(mod), "1.4.0", "downloads/Evade-Clips.exe", "", null, null, mod, mod, mod);
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
        v.clipsPresent = true;
        v.clipsVersion = "1.4.0";
        save(v, out, "04-home");

        v.settings.selected = "pvp";
        v.clipsVersion = "1.3.2";
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
        v.clipsVersion = "1.4.0";

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
        v.mods = sampleMods(main.dir.resolve("mods"));
        save(v, out, "13-mods");
        v.browse = true;
        v.searchField.setText("");
        v.lastQuery = "";
        v.hits = sampleHits();
        v.hitsTotal = 4210;
        v.installedProjects.add("w7ThoJFB");
        v.modState.put("1IjD5062", "installing");
        v.modProgress = 0.55;
        save(v, out, "14-mods-browse");
        v.modState.clear();
        v.browse = false;

        // ---- settings
        v.page = LauncherView.Page.SETTINGS;
        save(v, out, "15-settings");
        v.page = LauncherView.Page.HOME;
        v.openPopupFor("home-switch");
        save(v, out, "16-home-switch-instance");
        v.closeOverlays();
    }

    private static Launcher.Instance instance(String id, Path dir, String name, InstallerCore.Installed installed, int mem, int mods) {
        Launcher.Instance i = new Launcher.Instance(id, dir, name);
        i.installed = installed;
        i.memoryGb = mem;
        i.modCount = mods;
        return i;
    }

    private static List<News.Entry> sampleNews() {
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

    private static void save(LauncherView v, Path dir, String name) throws Exception {
        for (int i = 0; i < 40; i++) v.snapshot(1f);
        ImageIO.write(v.snapshot(2f), "png", dir.resolve(name + ".png").toFile());
    }
}
