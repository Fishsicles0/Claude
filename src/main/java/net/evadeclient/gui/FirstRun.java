package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.config.MenuState;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ArmorHud;
import net.evadeclient.module.hud.*;
import net.evadeclient.module.misc.HypixelStats;
import net.evadeclient.module.misc.QuickPlay;
import net.evadeclient.module.mods.BlockOutline;
import net.evadeclient.module.mods.Crosshair;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.mods.ToggleSprint;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.module.visuals.BetterTooltips;
import net.evadeclient.module.visuals.HitColor;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.util.Benchmark;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import java.util.List;
import java.util.Locale;

/**
 * The first-run setup: shown once, the first time the Evade menu or the title screen opens (never during the
 * automated tests unless a test opens it), plus the play-style presets it applies and the "Tune for my PC" run,
 * which uses the FPS benchmark and waits for the next world when picked from the title screen.
 */
public final class FirstRun {
    private FirstRun() {}

    /** A play style: the mods it switches on and the HUD widgets it shows (other info widgets go off). */
    public record Style(String name, String blurb, List<Class<? extends Module>> mods) {}

    /** The info widgets a style may switch off when it doesn't use them. Other HUD elements are left alone. */
    private static final List<Class<? extends Module>> INFO_WIDGETS = List.of(FpsHud.class, CpsHud.class,
            KeystrokesHud.class, PingHud.class, ArmorHud.class, PotionHud.class, CoordsHud.class, CompassHud.class,
            MinimapHud.class, SaturationHud.class, BedWarsHud.class, ClockHud.class, ServerHud.class, ComboHud.class,
            SpeedHud.class, MemoryHud.class);

    public static final List<Style> STYLES = List.of(
            new Style("PvP", "Keystrokes, CPS, FPS and ping, armour and potions, Toggle Sprint, a clean crosshair and hit colour.",
                    List.of(ToggleSprint.class, KeystrokesHud.class, CpsHud.class, FpsHud.class, PingHud.class, ArmorHud.class,
                            PotionHud.class, Crosshair.class, HitColor.class)),
            new Style("Survival", "Coordinates, direction, minimap, waypoints, armour, saturation and better tooltips.",
                    List.of(ToggleSprint.class, CoordsHud.class, CompassHud.class, MinimapHud.class, Waypoints.class,
                            ArmorHud.class, PotionHud.class, SaturationHud.class, BetterTooltips.class)),
            new Style("Hypixel", "Quick Play, player stats in tab, Bed Wars info, FPS and ping, armour and potions.",
                    List.of(QuickPlay.class, HypixelStats.class, BedWarsHud.class, FpsHud.class, PingHud.class,
                            ArmorHud.class, PotionHud.class, ToggleSprint.class)),
            new Style("Building", "Coordinates and direction, a clear block outline, Zoom, the clock and Toggle Sprint.",
                    List.of(CoordsHud.class, CompassHud.class, BlockOutline.class, Zoom.class, ClockHud.class, ToggleSprint.class)),
            new Style("Minimal", "Just an FPS counter. Turn on more any time from the menu.",
                    List.of(FpsHud.class)));

    /** Switch on the style's mods and show its widgets (other info widgets go off), then save. */
    public static void apply(Style style) {
        if (EvadeClient.modules == null) return;
        for (Class<? extends Module> c : INFO_WIDGETS) {
            Module m = EvadeClient.modules.get(c);
            if (m != null && !style.mods().contains(c)) m.setEnabled(false);
        }
        for (Class<? extends Module> c : style.mods()) {
            Module m = EvadeClient.modules.get(c);
            if (m != null) m.setEnabled(true);
        }
        EvadeClient.config.save();
    }

    public static Style style(String name) {
        for (Style s : STYLES) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }

    // ------------------------------------------------------------------ hooks

    /** Whether the automated in-game tests are running (the setup never opens by itself then). */
    public static boolean inGameTest() {
        return net.evadeclient.util.SafeStart.testing();
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (MenuState.setupDone() || inGameTest()) return;
            boolean menu = screen instanceof EvadeMenu;
            boolean title = screen instanceof TitleScreen || screen instanceof EvadeTitleScreen;
            if (!menu && !title) return;
            MenuState.setSetupDone(true); // shown once, however it is left
            client.send(() -> {
                if (client.currentScreen == screen) client.setScreen(new SetupWizardScreen(screen));
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(FirstRun::tickTune);
        // The graphics are known once the window exists: integrated graphics start on the laptop profile.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                autoProfile(false);
                // Integrated graphics: Evade Shaders (when it's the active pack) starts on its Low level, unless its settings were changed.
                if (!inGameTest()) net.evadeclient.util.ShaderSwitch.laptopProfile();
            } catch (RuntimeException e) {
                net.evadeclient.EvadeClient.LOG.warn("First run: graphics check failed", e);
            }
        });
    }

    // ------------------------------------------------------------------ graphics-aware start

    /**
     * Reads the OpenGL renderer once per GPU (first start, an update to this version, or a new graphics card or
     * driver name) and, for integrated graphics, applies Performance's laptop profile and says so. A PC with a
     * graphics card keeps its settings. Never runs by itself during the automated tests ({@code force} = test hook).
     *
     * @return what was done: "laptop", "kept" (not integrated, or already set for this GPU), or "skipped"
     */
    public static String autoProfile(boolean force) {
        if (EvadeClient.modules == null) return "skipped";
        Performance p = EvadeClient.modules.get(Performance.class);
        String gpu = net.evadeclient.util.GpuInfo.renderer();
        if (p == null || gpu.isEmpty()) return "skipped";
        if (!force && (inGameTest() || gpu.equals(p.tunedGpu.get()))) return "kept";
        boolean upgrade = !force && p.tunedGpu.get().isEmpty() && MenuState.setupDone();
        p.tunedGpu.set(gpu);
        var kind = net.evadeclient.util.GpuInfo.classify(gpu);
        // Someone updating Evade who already picked an FPS Mode other than the default keeps their choice.
        boolean ownChoice = upgrade && !p.fpsMode.get().equals("Balanced");
        if (kind != net.evadeclient.util.GpuInfo.Kind.INTEGRATED || ownChoice) {
            EvadeClient.config.save();
            return "kept";
        }
        p.applyLaptopProfile();
        EvadeClient.config.save();
        Notifications.push("Tuned for integrated graphics",
                "Max FPS, Render Scale " + Performance.LAPTOP_RENDER_SCALE + "% with Evade's upscaler. Measure it: Performance > Benchmark Scene",
                Notifications.Level.SUCCESS, 9000);
        EvadeClient.LOG.info("First run: {} is integrated graphics, laptop profile applied", gpu);
        return "laptop";
    }

    // ------------------------------------------------------------------ Tune for my PC

    private static Benchmark.Result before;
    private static boolean tuning;
    private static int joinedTicks;

    /** True while a tune run is going. */
    public static boolean tuning() { return tuning; }

    /**
     * Start the tune now if in a world (a 10 second benchmark), else when the next world has loaded. Returns a line
     * for the setup screen to show.
     */
    public static String startTune() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (tuning) return "Already measuring...";
        if (mc.player == null || mc.world == null) {
            MenuState.setTunePending(true);
            return "We'll measure for 10 seconds the next time you join a world.";
        }
        return beginRun() ? "Measuring for 10 seconds... keep the game in view." : "Couldn't start: a benchmark is already running.";
    }

    private static boolean beginRun() {
        Performance p = EvadeClient.modules.get(Performance.class);
        if (p != null && !p.isEnabled()) p.setEnabled(true);
        if (p != null) p.fpsMode.set("Balanced"); // measure from the middle preset
        before = Benchmark.last();
        if (!Benchmark.start(10)) return false;
        tuning = true;
        MenuState.setTunePending(false);
        return true;
    }

    private static void tickTune(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) { joinedTicks = 0; return; }
        joinedTicks++;
        // A tune picked outside a world starts a few seconds into the next one, once nothing is open.
        if (!tuning && MenuState.tunePending() && joinedTicks > 100 && mc.currentScreen == null && !Benchmark.running()) {
            Notifications.push("Tune for my PC", "Measuring your FPS for 10 seconds...", true);
            beginRun();
        }
        if (!tuning || Benchmark.running()) return;
        tuning = false;
        Benchmark.Result r = Benchmark.last();
        if (r == null || r == before) return; // cancelled
        String mode = pick(r.avgFps(), r.low1());
        Performance p = EvadeClient.modules.get(Performance.class);
        if (p != null) {
            p.setEnabled(true);
            p.fpsMode.set(mode);
            EvadeClient.config.save();
        }
        lastPick = mode;
        Notifications.push("Tuned for your PC", String.format(Locale.ROOT, "%.0f FPS: FPS Mode set to %s", r.avgFps(), mode), true);
    }

    private static String lastPick;

    /** The FPS Mode the last tune picked this session, or null. */
    public static String lastPick() { return lastPick; }

    /** The FPS Mode for a measured average and 1% low (at Balanced). */
    public static String pick(double avg, double low1) {
        if (avg >= 160 && low1 >= 70) return "Quality";
        if (avg >= 75 && low1 >= 35) return "Balanced";
        return "Max FPS";
    }

    /** Test hook: open the setup over {@code parent}. */
    public static Screen wizard(Screen parent) { return new SetupWizardScreen(parent); }
}
