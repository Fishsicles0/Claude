package net.evadeclient.installer;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** --screenshots, the game-side views: playing, crash help, Join, the Game page (overview, logs, settings), delete with Undo. */
final class GameShots {
    private GameShots() {}

    static void render(LauncherView v, Path out) throws Exception {
        GamePages gp = v.game;
        Launcher.Instance main = find(v, "default"), pvp = find(v, "pvp");
        if (main == null || pvp == null) return;
        long now = System.currentTimeMillis();
        v.closeOverlays();
        v.unread = 0;
        v.settings.selected = "default";
        v.focusedId = "default";

        // ---- playing
        GamePages.Info m = gp.info(main);
        m.session = new GameState.Session(48211, now - 23 * 60_000, now - 2000, "2.3.0", "multiplayer", "practice.example.net",
                "Evade Practice", null, null, 0, false, null);
        m.run = GameState.Run.RUNNING;
        m.playtime = new GameState.Playtime(41 * 3_600_000L + 12 * 60_000, now, 87, "Survival Island", "Survival Island", "practice.example.net");
        m.lastWorld = new String[]{"Survival Island", "Survival Island"};
        v.page = LauncherView.Page.HOME;
        save(v, out, "30-home-playing");
        v.page = LauncherView.Page.INSTANCES;
        save(v, out, "31-instances-playing");

        // ---- not playing: Continue on Home, then a crash card
        m.run = GameState.Run.ENDED_CLEAN;
        m.session = new GameState.Session(48211, now - 3 * 3_600_000L, now - 2 * 3_600_000L, "2.3.0", "singleplayer", null, null,
                "Survival Island", "Survival Island", now - 2 * 3_600_000L, true, null);
        v.page = LauncherView.Page.HOME;
        save(v, out, "32-home-continue");
        GamePages.Info p = gp.info(pvp);
        p.playtime = new GameState.Playtime(6 * 3_600_000L, now - 26 * 3_600_000L, 14, null, null, "practice.example.net");
        p.run = GameState.Run.ENDED_BADLY;
        p.session = new GameState.Session(51920, now - 40 * 60_000, now - 31 * 60_000, "2.3.0", "multiplayer", "practice.example.net", "Evade Practice", null, null, now - 31 * 60_000, false, null);
        Path jar = pvp.dir.resolve("mods").resolve("fancy-hats-1.4.2+1.21.4.jar");
        List<Mods.Entry> mods = List.of(new Mods.Entry(jar, true, "fancyhats", "Fancy Hats", "1.4.2", "", "", null, 1));
        String report = """
                ---- Minecraft Crash Report ----
                Description: Initializing game

                java.lang.RuntimeException: Mixin transformation of net.minecraft.client.render.entity.PlayerEntityRenderer failed
                Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered
                Caused by: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure: @Inject annotation on renderHat could not find any targets matching 'render' in PlayerEntityRenderer. Using refmap fancyhats-refmap.json [PREINJECT Applicator Phase -> fancyhats.mixins.json:PlayerRendererMixin from mod fancyhats -> Prepare Injections]
                """;
        p.crash = CrashHelper.analyse(report, false, 2, mods, pvp.dir.resolve("crash-reports").resolve("crash-2026-11-24_14.02.11-client.txt"), now);
        p.reports = List.of(pvp.dir.resolve("crash-reports").resolve("crash-2026-11-24_14.02.11-client.txt"),
                pvp.dir.resolve("crash-reports").resolve("crash-2026-11-02_20.41.55-client.txt"));
        v.notices.add(0, new LauncherView.Notice("PvP crashed", p.crash.title(), now - 60_000, true));
        v.unread = 1;
        save(v, out, "33-home-crash");
        v.overlay = "bell";
        save(v, out, "34-home-notifications");
        v.overlay = "server:0";
        save(v, out, "35-home-server-join");
        v.closeOverlays();
        gp.wrongProfile = "Minecraft is running, but not this Evade instance.";
        gp.wrongInstance = main;
        gp.oldJars = List.of(Path.of("C:/Users/Player/AppData/Roaming/.minecraft/mods/evade-client-1.4.0.jar"));
        save(v, out, "35b-home-wrong-profile");
        gp.wrongProfile = null;

        // ---- the Game page
        v.page = LauncherView.Page.GAME;
        gp.tab = "overview";
        m.run = GameState.Run.RUNNING;
        m.session = new GameState.Session(48211, now - 23 * 60_000, now - 2000, "2.3.0", "multiplayer", "practice.example.net",
                "Evade Practice", null, null, 0, false, null);
        m.reports = List.of();
        save(v, out, "36-game-overview-playing");
        v.focusedId = "pvp";
        save(v, out, "37-game-overview-crash");
        gp.ask("Stop Main?", "The game closes right away, like closing it from Task Manager. Anything not saved in the last few seconds is "
                + "lost: singleplayer worlds save every few minutes.", "Stop game", true, () -> { });
        v.focusedId = "default";
        save(v, out, "38-game-stop-confirm");
        gp.confirm = null;

        // logs
        gp.tab = "logs";
        v.playerName = "Player";
        gp.logFile = main.dir.resolve("logs").resolve("latest.log");
        gp.logFiles = List.of(gp.logFile, main.dir.resolve("logs").resolve("2026-11-23-2.log.gz"));
        gp.logLines = LogTools.parse(sampleLog());
        gp.follow = true;
        save(v, out, "39-game-logs");
        gp.logFilter = 1;
        save(v, out, "40-game-logs-warnings");
        gp.logFilter = 0;
        gp.search.setText("sodium");
        save(v, out, "41-game-logs-search");
        gp.search.setText("");

        // settings
        gp.tab = "settings";
        InstanceExtras x = m.extras;
        x.glyph = 2;
        x.color = 3;
        x.width = 1600;
        x.height = 900;
        x.javaDir = "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.5\\bin\\javaw.exe";
        x.javaArgs = "-XX:+AlwaysPreTouch -Xmx12G";
        x.notes = "Practice and duels. Keep it lean.";
        gp.syncFields(main);
        gp.javaCheck = "Java 21: good";
        save(v, out, "42-game-settings");
        v.page = LauncherView.Page.INSTANCES;
        save(v, out, "43-instances-icon");

        // ---- delete: what will be lost, then Undo
        Path tmp = Files.createTempDirectory("evade-shots");
        Path dir = tmp.resolve("survival");
        for (String w : new String[]{"Survival Island", "Creative test"}) {
            Files.createDirectories(dir.resolve("saves").resolve(w));
            Files.write(dir.resolve("saves").resolve(w).resolve("level.dat"), new byte[64 * 1024]);
        }
        Files.createDirectories(dir.resolve("screenshots"));
        for (int k = 0; k < 14; k++) Files.write(dir.resolve("screenshots").resolve("shot-" + k + ".png"), new byte[180 * 1024]);
        Files.createDirectories(dir.resolve("mods"));
        for (int k = 0; k < 9; k++) Files.write(dir.resolve("mods").resolve("mod-" + k + ".jar"), new byte[400 * 1024]);
        Launcher.Instance surv = new Launcher.Instance("survival", dir, "Survival");
        surv.installed = main.installed;
        surv.memoryGb = 4;
        surv.modCount = 9;
        List<Launcher.Instance> list = new ArrayList<>(v.instances);
        list.add(surv);
        v.instances = list;
        v.focusedId = "survival";
        v.openModalForShots("delete", null);
        save(v, out, "44-instances-delete-summary");
        v.closeOverlays();
        list.remove(surv);
        v.focusedId = "default";
        gp.undo("Deleted Survival");
        save(v, out, "45-instances-undo");
        gp.undoText = null;
        v.page = LauncherView.Page.HOME;
    }

    private static List<String> sampleLog() {
        return List.of(
                "[14:01:58] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.18.6",
                "[14:01:58] [main/INFO]: Loading 17 mods:",
                "[14:01:59] [main/INFO]: Launching with --username Player --accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N",
                "[14:01:59] [main/INFO]: Game folder: C:\\Users\\JamieSmith\\AppData\\Roaming\\.minecraft\\evade",
                "[14:02:00] [Render thread/INFO]: Setting user: Player",
                "[14:02:01] [Render thread/INFO]: [Evade] Evade 2.3.0 loaded with 142 modules",
                "[14:02:02] [Render thread/WARN]: [Sodium] The driver for this GPU is older than recommended (31.0.101.4502)",
                "[14:02:02] [Render thread/INFO]: [Sodium] Using the Iris shader pipeline",
                "[14:02:03] [Render thread/INFO]: OpenAL initialized on device OpenAL Soft",
                "[14:02:04] [Render thread/WARN]: Missing sound for event: minecraft:item.goat_horn.play",
                "[14:02:05] [Render thread/INFO]: Connecting to practice.example.net, 25565",
                "[14:02:05] [Netty Client IO #0/INFO]: Resolved practice.example.net to 203.0.113.42",
                "[14:02:06] [Render thread/INFO]: [CHAT] Welcome to Evade Practice, Player!",
                "[14:05:12] [Render thread/ERROR]: Failed to load texture evade:textures/gui/hat_shadow.png",
                "java.io.FileNotFoundException: evade:textures/gui/hat_shadow.png",
                "\tat net.minecraft.resource.ResourceManager.getResourceOrThrow(ResourceManager.java:117)",
                "\tat net.minecraft.client.texture.ResourceTexture.loadContents(ResourceTexture.java:28)",
                "[14:05:13] [Render thread/INFO]: [Evade] Session ID is 7f1c2a9e-0d44-4f6a-9b3c-11f0e3a1b2c4",
                "[14:06:40] [Render thread/WARN]: Received passengers for unknown entity",
                "[14:08:02] [Render thread/INFO]: [CHAT] [Duels] Player won against Rival (5 hits left)",
                "[14:09:30] [Server Connector/INFO]: Pinged 198.51.100.7 in 34 ms",
                "[14:10:01] [Render thread/INFO]: Saved screenshot as 2026-11-24_14.10.01.png");
    }

    private static Launcher.Instance find(LauncherView v, String id) {
        for (Launcher.Instance i : v.instances) if (i.id.equals(id)) return i;
        return null;
    }

    private static void save(LauncherView v, Path dir, String name) throws Exception {
        for (int i = 0; i < 40; i++) v.snapshot(1f);
        ImageIO.write(v.snapshot(Screenshots.SCALE), "png", dir.resolve(name + ".png").toFile());
    }
}
