package net.evadeclient.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * --screenshots with -Devade.shots.only=shaders: the Shader packs chooser in the dark and light themes (the instance
 * page's Shaders row, the sheet on a PC with a graphics card, the sheet on built-in graphics with Shaders off, and the
 * details of BSL and of Evade Shaders). Sample data only, in a throwaway folder.
 */
final class ShaderShots {
    private ShaderShots() {}

    static void render(Path out) throws Exception {
        Files.createDirectories(out);
        Path tmp = Files.createTempDirectory("shadershots");
        LauncherView v = new LauncherView();
        v.version = LauncherInfo.VERSION;
        Path mc = tmp.resolve(".minecraft");
        v.settings.minecraftDir = mc.toString();
        v.settings.defaultMemoryGb = 4;
        InstallerCore.ModFile mod = new InstallerCore.ModFile("Sodium", "0.6.13", "", "", "sodium-fabric-0.6.13+mc1.21.11.jar", "AANobbMI", List.of(), Map.of());
        InstallerCore.ModFile iris = new InstallerCore.ModFile("Iris", "1.9.6+1.21.11-fabric", "", "", "iris-fabric-1.9.6+mc1.21.11.jar", "YL57xq9U", List.of(), Map.of());
        // The versions Modrinth listed for 1.21.11 with Iris when this was written.
        Map<String, InstallerCore.ModFile> packs = new LinkedHashMap<>();
        String[][] v1 = {{"evade-shaders", "1.0", "Evade Shaders v1.0.zip"}, {"photon", "v1.4", "photon_v1.4.zip"}, {"complementary-reimagined", "r5.10", "ComplementaryReimagined_r5.10.zip"},
                {"complementary-unbound", "r5.10", "ComplementaryUnbound_r5.10.zip"}, {"bsl", "10.1.8", "BSL_v10.1.8.zip"},
                {"sildurs-vibrant", "2.02", "Sildur's Vibrant Shaders v2.02 Medium.zip"}};
        for (String[] p : v1) packs.put(p[0], new InstallerCore.ModFile(ShaderPacks.byKey(p[0]).name(), p[1], "", "", p[2], p[0], List.of(), Map.of()));
        Map<String, List<InstallerCore.ModFile>> optional = new LinkedHashMap<>();
        for (OptionalMods.Group gr : OptionalMods.GROUPS) optional.put(gr.key(), List.of(mod));
        v.release = new InstallerCore.Release(LauncherInfo.VERSION, "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", mod, List.of(mod), "1.4.0",
                "downloads/Evade-Clips.exe", "", null, null, iris, mod, packs.get("photon"), List.of(mod), mod, optional, packs);
        v.news = Screenshots.sampleNews();
        v.newsLoading = false;
        v.content.offline = true;
        InstallerCore.Installed now = new InstallerCore.Installed(LauncherInfo.VERSION, "0.18.6", "0.141.6+1.21.11");
        Launcher.Instance main = Screenshots.instance("default", mc.resolve("evade"), "Main", now, 4, 16);
        Launcher.Instance smp = Screenshots.instance("smp", mc.resolve("evade-instances/smp"), "Friends SMP", now, 6, 24);
        Launcher.Instance laptop = Screenshots.instance("laptop", mc.resolve("evade-instances/laptop"), "Laptop", now, 3, 12);
        smp.shaders = true;
        smp.shaderPacks = ShaderPacks.parse("evade-shaders,photon,complementary-reimagined,bsl");
        laptop.shaders = false;
        v.instances = new ArrayList<>(List.of(main, smp, laptop));
        for (Launcher.Instance i : v.instances) Files.createDirectories(i.dir.resolve("mods"));
        v.settings.selected = "smp";
        Screenshots.sampleHome(v);

        for (String look : new String[]{"dark", "light"}) {
            boolean light = look.equals("light");
            String n = light ? "2" : "1";
            set(v, light);
            // A PC with a graphics card.
            v.tools.advice = MemoryAdvice.advise(List.of("NVIDIA GeForce RTX 4060"), 32);
            v.page = LauncherView.Page.INSTANCES;
            v.focusedId = "smp";
            Screenshots.save(v, out, n + "0-" + look + "-instance-shaders-row");
            v.tools.shaderPacks.open();
            Screenshots.save(v, out, n + "1-" + look + "-shader-packs");
            v.game.focusId = "ov-sp:bsl";
            v.click("ov-sp:complementary-unbound");
            Screenshots.save(v, out, n + "2-" + look + "-shader-packs-ticked");
            v.click("ov-sp:complementary-unbound");
            v.click("ov-spinfo:bsl");
            Screenshots.save(v, out, n + "3-" + look + "-shader-pack-details");
            v.click("ov-sp-back");
            v.click("ov-spinfo:evade-shaders");
            Screenshots.save(v, out, n + "3b-" + look + "-evade-shaders-details");
            v.click("ov-sp-back");
            v.closeOverlays();
            // A laptop with built-in graphics, Shaders still off.
            v.tools.advice = MemoryAdvice.advise(List.of("AMD Radeon(TM) 780M Graphics"), 16);
            v.focusedId = "laptop";
            v.tools.shaderPacks.open();
            Screenshots.save(v, out, n + "4-" + look + "-shader-packs-built-in-graphics");
            v.click("ov-sp-light");
            Screenshots.save(v, out, n + "5-" + look + "-shader-packs-light-picked");
            v.click("ov-sp-reset");
            v.closeOverlays();
        }
        set(v, false);
        InstallerCore.deleteTree(tmp);
    }

    private static void set(LauncherView v, boolean light) {
        UiPrefs p = v.extras.prefs;
        p.light = light;
        p.accent = 0;
        p.textSize = 100;
        p.reduceMotion = false;
        v.extras.setPrefs(p);
    }
}
