package net.evadeclient.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * --screenshots with -Devade.shots.only=v25: the v2.5 launcher tools in the dark and light themes (instance page with
 * optional extras and the More menu, the extras sheet, templates in New instance, import from another launcher, the
 * drop veil, the crash card's safe mode, the garbage collector setting, the version list with pins and Undo update,
 * memory advice, What's new) and danger colours with a blue accent. Sample data only, in a throwaway folder.
 */
final class V25Shots {
    private V25Shots() {}

    static void render(Path out) throws Exception {
        Files.createDirectories(out);
        Path tmp = Files.createTempDirectory("v25shots");
        LauncherView v = new LauncherView();
        v.version = LauncherInfo.VERSION;
        Path mc = tmp.resolve(".minecraft");
        v.settings.minecraftDir = mc.toString();
        v.settings.defaultMemoryGb = 4;
        InstallerCore.ModFile mod = new InstallerCore.ModFile("Sodium", "0.6.13", "", "", "sodium-fabric-0.6.13+mc1.21.11.jar", "AANobbMI", List.of(), Map.of());
        Map<String, List<InstallerCore.ModFile>> optional = new LinkedHashMap<>();
        for (OptionalMods.Group gr : OptionalMods.GROUPS) optional.put(gr.key(), List.of(mod));
        v.release = new InstallerCore.Release("2.5.0", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", mod, List.of(mod), "1.4.0",
                "downloads/Evade-Clips.exe", "", null, null, mod, mod, mod, List.of(mod), mod, optional);
        v.news = Screenshots.sampleNews();
        v.newsLoading = false;
        v.content.offline = true;
        InstallerCore.Installed now = new InstallerCore.Installed("2.5.0", "0.18.6", "0.141.6+1.21.11");
        Launcher.Instance main = Screenshots.instance("default", mc.resolve("evade"), "Main", now, 4, 16);
        Launcher.Instance pvp = Screenshots.instance("pvp", mc.resolve("evade-instances/pvp"), "PvP", now, 3, 9);
        Launcher.Instance smp = Screenshots.instance("smp", mc.resolve("evade-instances/smp"), "Friends SMP", now, 8, 24);
        pvp.optional = new java.util.LinkedHashSet<>(Set.of(OptionalMods.FAST_START.key(), OptionalMods.MORE_FPS.key()));
        smp.optional = new java.util.LinkedHashSet<>(List.of(OptionalMods.FAST_START.key(), OptionalMods.FRIENDS.key()));
        smp.shaders = true;
        smp.lights = true;
        v.instances = new ArrayList<>(List.of(main, pvp, smp));
        for (Launcher.Instance i : v.instances) Files.createDirectories(i.dir.resolve("mods"));
        v.settings.selected = "default";
        Screenshots.sampleHome(v);
        // A laptop with a Radeon 780M and 16 GB: Friends SMP asks for 8 GB, over half.
        v.tools.advice = MemoryAdvice.advise(List.of("AMD Radeon(TM) 780M Graphics"), 16);

        for (String look : new String[]{"dark", "light"}) {
            boolean light = look.equals("light");
            String n = light ? "2" : "1";
            set(v, light, 0);
            v.page = LauncherView.Page.INSTANCES;
            v.focusedId = "smp";
            Screenshots.save(v, out, n + "0-" + look + "-instance-extras-advice");
            v.openPopupFor("inst-more");
            Screenshots.save(v, out, n + "1-" + look + "-instance-more-menu");
            v.closeOverlays();
            v.overlay = "extras";
            Screenshots.save(v, out, n + "2-" + look + "-optional-extras");
            v.closeOverlays();
            v.openModal(new LauncherView.Modal("create", "New instance", "A separate game folder with its own mods, settings and worlds, and its own profile in the Minecraft Launcher.", "Create", null, null), "Laptop");
            v.tools.template = InstanceTemplates.LAPTOP;
            Screenshots.save(v, out, n + "3-" + look + "-new-instance-templates");
            v.closeOverlays();
            v.page = LauncherView.Page.INSTANCES;
            v.overlay = "import";
            v.tools.found = sampleFound(tmp);
            v.tools.scanning = false;
            Screenshots.save(v, out, n + "4-" + look + "-import-found");
            v.tools.importTitle = "New instance: Fabulously Fast (37 mods from Modrinth)";
            v.tools.importNotes = List.of("2 mods aren't on Modrinth, so they weren't copied: my-private-mod.jar, test-utils.jar",
                    "Fancy Menu has no version for Fabric 1.21.11");
            Screenshots.save(v, out, n + "5-" + look + "-import-done");
            v.tools.importTitle = null;
            v.tools.importNotes = List.of();
            v.closeOverlays();
            v.page = LauncherView.Page.HOME;
            v.tools.dragging = true;
            Screenshots.save(v, out, n + "6-" + look + "-drop-veil");
            v.tools.dragging = false;
            // crash card with Start in safe mode
            v.game.info(main).crash = new CrashHelper.Diagnosis("A mod made the game crash", "Iris failed while loading the shader pack. Switching it off for this instance usually fixes it.",
                    "java.lang.IllegalStateException: Failed to compile shader composite1", CrashHelper.Fix.DISABLE_MOD, "iris", mc.resolve("evade/mods/iris.jar"), null, System.currentTimeMillis());
            v.page = LauncherView.Page.HOME;
            Screenshots.save(v, out, n + "7-" + look + "-home-crash-safe-mode");
            v.page = LauncherView.Page.GAME;
            v.focusedId = "default";
            v.settings.selected = "default";
            Screenshots.save(v, out, n + "7b-" + look + "-game-crash-safe-mode");
            v.game.info(main).crash = null;
            v.game.tab = "settings";
            v.focusedId = "smp";
            v.settings.selected = "smp";
            v.game.info(smp).extras.zgc = true;
            Screenshots.save(v, out, n + "8-" + look + "-game-settings-gc");
            v.game.tab = "overview";
            v.settings.selected = "default";
            v.page = LauncherView.Page.SETTINGS;
            Screenshots.save(v, out, n + "9-" + look + "-settings-about-advice");
            v.extras.showWhatsNew();
            Screenshots.save(v, out, n + "9b-" + look + "-whats-new");
            v.closeOverlays();
            versions(v, out, tmp, n + "9c-" + look);
            v.page = LauncherView.Page.INSTANCES;
        }
        // danger colours with a blue accent: Delete and the crash card stay red
        set(v, false, 4);
        v.page = LauncherView.Page.INSTANCES;
        v.focusedId = "smp";
        Screenshots.save(v, out, "30-dark-blue-delete-stays-red");
        v.openModal(new LauncherView.Modal("delete", "Delete Friends SMP?", "Its folder (24 mods, 3 worlds) goes to the Recycle Bin.", "Delete", smp, null), null);
        Screenshots.save(v, out, "31-dark-blue-delete-dialog");
        v.closeOverlays();
        v.game.info(main).crash = new CrashHelper.Diagnosis("Minecraft ran out of memory", "The game needed more memory than it was given.", null,
                CrashHelper.Fix.RAISE_MEMORY, null, null, null, System.currentTimeMillis());
        v.page = LauncherView.Page.HOME;
        Screenshots.save(v, out, "32-dark-blue-crash-red");
        set(v, true, 4);
        Screenshots.save(v, out, "33-light-blue-crash-red");
        v.page = LauncherView.Page.INSTANCES;
        Screenshots.save(v, out, "34-light-blue-delete-stays-red");
        v.game.info(main).crash = null;
        set(v, true, 2);
        v.page = LauncherView.Page.INSTANCES;
        Screenshots.save(v, out, "35-light-green-delete-stays-red");
        set(v, false, 0);
        InstallerCore.deleteTree(tmp);
    }

    /** The Mods page with a pinned mod, Undo update, and the version list (real versions when Modrinth answers). */
    private static void versions(LauncherView v, Path out, Path tmp, String prefix) throws Exception {
        Launcher.Instance sel = v.selected();
        Path mods = sel.dir.resolve("mods");
        Files.createDirectories(mods);
        Path zoom = mods.resolve("zoomify-2.14.5+1.21.11.jar"), skin = mods.resolve("appleskin-fabric-mc1.21.11-3.0.7.jar");
        Files.writeString(zoom, "jar");
        Files.writeString(skin, "jar");
        v.mods = List.of(new Mods.Entry(zoom, true, "zoomify", "Zoomify", "2.14.5+1.21.11", "A zoom mod with infinite customizability.", "isxander", null, 3),
                new Mods.Entry(skin, true, "appleskin", "AppleSkin", "3.0.7+mc1.21.11", "Adds food value information to tooltips and HUD.", "squeek502", null, 1));
        // a pin and a kept update, as files on disk (the page reads them)
        ContentPins.setPinned(sel.dir, "EsAfCjCV", true);
        Path old = mods.resolve("appleskin-fabric-mc1.21.11-3.0.6.jar");
        Files.writeString(old, "old");
        ContentPins.Batch b = ContentPins.begin(sel.dir);
        b.keep(old, skin, Content.Type.MOD, "EsAfCjCV", "3.0.6+mc1.21.11", "3.0.7+mc1.21.11", "AppleSkin");
        v.page = LauncherView.Page.MODS;
        v.content.tab = ContentPage.Tab.INSTALLED;
        v.snapshot(1f);
        ContentApi.Version cur = ver("zoom-cur", "w7ThoJFB", "2.14.5+1.21.11", "release", "2026-09-02", "Fixed zoom scrolling with Sodium.");
        ContentApi.Version newer = ver("zoom-new", "w7ThoJFB", "2.14.6+1.21.11", "release", "2026-10-14", "Smoother zoom transitions; secondary zoom key.");
        ContentApi.Version askin = ver("as-cur", "EsAfCjCV", "3.0.7+mc1.21.11", "release", "2026-08-30", "");
        ContentApi.Version askinNew = ver("as-new", "EsAfCjCV", "3.0.8+mc1.21.11", "release", "2026-11-01", "");
        v.content.known.put(zoom, new Updates.Known(zoom, Content.Type.MOD, "a", cur, newer));
        v.content.known.put(skin, new Updates.Known(skin, Content.Type.MOD, "b", askin, askinNew));
        v.content.projects.put("w7ThoJFB", new ContentApi.Project("w7ThoJFB", "zoomify", "Zoomify", "mod", "", "", null, "LGPL-3.0", "required", List.of()));
        v.content.projects.put("EsAfCjCV", new ContentApi.Project("EsAfCjCV", "appleskin", "AppleSkin", "mod", "", "", null, "Unlicense", "optional", List.of()));
        v.content.versions.reload();
        Screenshots.save(v, out, prefix + "-mods-pinned-undo");
        v.content.sheet = "mod:1";
        v.content.bodies.put("EsAfCjCV", "Food and saturation values in tooltips and on the HUD.\n\nLicence: Unlicense");
        Screenshots.save(v, out, prefix + "-mod-sheet-pinned");
        v.content.sheet = "mod:0";
        v.content.bodies.put("w7ThoJFB", "A zoom mod with infinite customizability.\n\nLicence: LGPL-3.0");
        v.snapshot(1f);
        List<ContentApi.Version> list;
        try {
            list = ContentApi.versions("w7ThoJFB", List.of("fabric"));
        } catch (Exception e) {
            list = List.of(newer, cur, ver("zoom-old", "w7ThoJFB", "2.14.4+1.21.11", "beta", "2026-07-21", "Beta: new zoom curve options."));
        }
        v.content.versions.open(zoom, v.content.known.get(zoom), "Zoomify");
        v.content.versions.list = list;
        if (!list.isEmpty() && list.size() > 1) v.content.known.put(zoom, new Updates.Known(zoom, Content.Type.MOD, "a", list.get(1), list.get(0)));
        Screenshots.save(v, out, prefix + "-version-list");
        v.content.sheet = null;
        v.page = LauncherView.Page.INSTANCES;
    }

    private static ContentApi.Version ver(String id, String pid, String number, String type, String date, String log) {
        return new ContentApi.Version(id, pid, number, number, type, date + "T10:00:00Z", "https://cdn.modrinth.com/x.jar", "x.jar", "a", "b".repeat(128), 1,
                List.of(), List.of("fabric"), List.of("1.21.11"), log);
    }

    private static List<LauncherImport.Found> sampleFound(Path tmp) {
        Path root = tmp.resolve("other-launcher/instances");
        return List.of(new LauncherImport.Found("Fabulously Fast", root.resolve("Fabulously Fast/minecraft"), "1.21.4", 39, 2, 3),
                new LauncherImport.Found("Hypixel PvP", root.resolve("Hypixel PvP/.minecraft"), "1.21.1", 12, 0, 5),
                new LauncherImport.Found("Survival with friends", root.resolve("Survival with friends"), "1.21.11", 54, 6, 1));
    }

    private static void set(LauncherView v, boolean light, int accent) {
        UiPrefs p = v.extras.prefs;
        p.light = light;
        p.accent = accent;
        p.textSize = 100;
        p.reduceMotion = false;
        v.extras.setPrefs(p);
    }
}
