package net.evadeclient.gametest;

import com.google.gson.JsonElement;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.ModBrowserScreen;
import net.evadeclient.gui.SetupWizardScreen;
import net.evadeclient.module.Module;
import net.evadeclient.module.hud.KeystrokesHud;
import net.evadeclient.module.hud.PlaytimeHud;
import net.evadeclient.module.misc.AutoTip;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.TntTimer;
import net.evadeclient.module.visuals.WorldMap;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.util.KeyClashNotice;
import net.evadeclient.util.SafeStart;
import net.evadeclient.util.SodiumBridge;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixes from the v2.7.0 external review (mods, performance, settings and HUD): every setting described, Sodium
 * option paths still found, Performance's first-run list of changed video settings and its "keep my settings",
 * key clash notices, the Playtime widget, Keystrokes' Mouse Movement pad, AutoTip's fixed command, the TNT Timer
 * opacity default on its slider grid, the test hook only answering to the gametest property, and the Mod Browser
 * shortcut from FPS Mods Installed.
 */
public final class ReviewFixesV27Test {
    private ReviewFixesV27Test() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        // 1. Every setting Evade shows (menu, search, .set and the site) has a one-line description.
        List<String> bare = ctx.computeOnClient(c -> {
            List<String> out = new ArrayList<>();
            for (Module m : EvadeClient.modules.all()) {
                if (!m.getClass().getName().startsWith("net.evadeclient.module.")) continue; // addons describe their own
                for (Setting<?> s : m.getSettings()) {
                    if (!s.isHidden() && s.getDescription().isBlank()) out.add(m.getName() + " > " + s.getName());
                }
            }
            return out;
        });
        check(bare.isEmpty(), "every setting has a description (missing: " + bare + ")");

        // 2. The Sodium and Sodium Extra options FPS Mode tunes are still where Evade looks for them.
        List<String> missing = ctx.computeOnClient(c -> SodiumBridge.checkPaths());
        check(missing.isEmpty(), "Sodium bridge: every option path is found in the installed Sodium (" + missing + ")");

        // 3. Test hook: only the gametest property in a development run counts, never a mod id.
        check(ctx.computeOnClient(c -> SafeStart.testing()), "test hook: answers to -Dfabric.client.gametest in a dev run");

        // 4. Performance lists what it changed in your video settings, and "Keep my own settings" hands them back.
        performanceChanges(ctx);

        // 5. Key clashes: World Map no longer sits on Simple Voice Chat's M, and a clash with another mod is found.
        keyClashes(ctx);

        // 6. Playtime: session time counts in a world, and the formats read right.
        check(PlaytimeHud.text(3725, "1h 05m").equals("1h 02m") && PlaytimeHud.text(3725, "1:05:00").equals("1:02:05")
                && PlaytimeHud.text(540, "1h 05m").equals("9m"), "Playtime: time formats");
        ctx.runOnClient(c -> EvadeClient.modules.get(PlaytimeHud.class).setEnabled(true));
        ctx.waitTicks(25);
        long[] play = ctx.computeOnClient(c -> {
            PlaytimeHud p = EvadeClient.modules.get(PlaytimeHud.class);
            return new long[]{p.sessionSeconds(), p.todaySecondsPlayed()};
        });
        check(play[0] >= 0 && play[1] >= 1, "Playtime: counts this session and today (" + play[0] + " s, " + play[1] + " s today)");
        ctx.runOnClient(c -> EvadeClient.modules.get(PlaytimeHud.class).setEnabled(false));

        // 7. Keystrokes' Mouse Movement pad follows the camera turning.
        mouseMovement(ctx);

        // 8. AutoTip's command is a fixed read-only line, not a one-choice mode; TNT Timer's default is on its grid.
        String tip = ctx.computeOnClient(c -> {
            for (Setting<?> s : EvadeClient.modules.get(AutoTip.class).getSettings()) {
                if (s.getName().equals("Command")) return (s instanceof StatusSetting ? "status:" : s instanceof ModeSetting ? "mode:" : "other:") + s.get();
            }
            return "none";
        });
        check(tip.equals("status:/tip all"), "AutoTip: Command is a read-only /tip all line (" + tip + ")");
        double tnt = ctx.computeOnClient(c -> {
            for (Setting<?> s : EvadeClient.modules.get(TntTimer.class).getSettings()) {
                if (s.getName().equals("Background Opacity") && s instanceof NumberSetting n) return n.getDefault();
            }
            return -1.0;
        });
        check(tnt == 55.0, "TNT Timer: Background Opacity defaults to 55, on its 5-step slider (" + tnt + ")");

        // 9. FPS Mods Installed opens the Mod Browser on optimization mods.
        ctx.runOnClient(c -> ModBrowserScreen.openOptimization());
        ctx.waitTicks(3);
        boolean browser = ctx.computeOnClient(c -> c.currentScreen instanceof ModBrowserScreen);
        ctx.runOnClient(c -> c.setScreen(null));
        ctx.waitTicks(2);
        check(browser, "Performance: FPS Mods Installed opens the Mod Browser");
    }

    private static void performanceChanges(ClientGameTestContext ctx) {
        // Snapshot Performance (settings and on/off) and the game's Max Framerate, so later tests see them unchanged.
        Map<String, JsonElement> saved = ctx.computeOnClient(c -> {
            Map<String, JsonElement> m = new LinkedHashMap<>();
            for (Setting<?> s : EvadeClient.modules.get(Performance.class).getSettings()) m.put(s.getName(), s.toJson());
            return m;
        });
        boolean wasOn = ctx.computeOnClient(c -> EvadeClient.modules.get(Performance.class).isEnabled());
        int[] mine = ctx.computeOnClient(c -> new int[]{c.options.getMaxFps().getValue(), c.options.getEnableVsync().getValue() ? 1 : 0});
        try {
            ctx.runOnClient(c -> {
                EvadeClient.modules.get(Performance.class).setEnabled(false);
                c.options.getMaxFps().setValue(120);
            });
            ctx.waitTicks(2);
            ctx.runOnClient(c -> {
                Performance p = EvadeClient.modules.get(Performance.class);
                p.unlockFps.set(true);
                p.setEnabled(true);
            });
            ctx.waitTicks(5);
            List<String> listed = ctx.computeOnClient(c -> SetupWizardScreen.changesForTest());
            check(listed.stream().anyMatch(s -> s.startsWith("Max Framerate: 120 →")),
                    "Performance: the first-run setup lists the video settings it changed (" + listed + ")");
            ctx.runOnClient(c -> EvadeClient.modules.get(Performance.class).keepMyVideoSettings());
            ctx.waitTicks(5);
            int fps = ctx.computeOnClient(c -> c.options.getMaxFps().getValue());
            List<String> after = ctx.computeOnClient(c -> SetupWizardScreen.changesForTest());
            check(fps == 120 && after.isEmpty(), "Performance: Keep my own settings puts them back (Max Framerate " + fps + ", " + after + ")");
        } finally {
            ctx.runOnClient(c -> {
                Performance p = EvadeClient.modules.get(Performance.class);
                p.setEnabled(false);
                for (Setting<?> s : p.getSettings()) if (saved.containsKey(s.getName())) s.loadJson(saved.get(s.getName()));
                c.options.getMaxFps().setValue(mine[0]);
                c.options.getEnableVsync().setValue(mine[1] == 1);
                if (wasOn) p.setEnabled(true);
            });
            ctx.waitTicks(2);
        }
    }

    private static void keyClashes(ClientGameTestContext ctx) {
        int def = ctx.computeOnClient(c -> EvadeClient.modules.get(WorldMap.class).openKey.getDefault());
        check(def == GLFW.GLFW_KEY_J, "World Map: Open Key defaults to J, off Simple Voice Chat's M");
        if (!FabricLoader.getInstance().isModLoaded("voicechat")) return;
        List<String> clean = ctx.computeOnClient(c -> KeyClashNotice.check(false));
        check(clean.stream().noneMatch(s -> s.startsWith("World Map")), "key clashes: none for World Map by default (" + clean + ")");
        KeySetting key = ctx.computeOnClient(c -> EvadeClient.modules.get(WorldMap.class).openKey);
        int was = ctx.computeOnClient(c -> key.get());
        try {
            ctx.runOnClient(c -> key.set(GLFW.GLFW_KEY_M));
            List<String> clash = ctx.computeOnClient(c -> KeyClashNotice.check(false));
            check(clash.stream().anyMatch(s -> s.startsWith("World Map Open Key|")),
                    "key clashes: World Map on M is found against Simple Voice Chat (" + clash + ")");
        } finally {
            ctx.runOnClient(c -> key.set(was));
        }
    }

    @SuppressWarnings("unchecked")
    private static void mouseMovement(ClientGameTestContext ctx) {
        boolean wasOn = ctx.computeOnClient(c -> EvadeClient.modules.get(KeystrokesHud.class).isEnabled());
        ctx.runOnClient(c -> {
            KeystrokesHud k = EvadeClient.modules.get(KeystrokesHud.class);
            k.setEnabled(true);
            for (Setting<?> s : k.getSettings()) if (s.getName().equals("Mouse Movement")) ((Setting<Boolean>) s).set(true);
        });
        float most = 0f;
        for (int i = 0; i < 12; i++) {
            ctx.runOnClient(c -> c.player.setYaw(c.player.getYaw() + 12f));
            ctx.waitTicks(1);
            float dot = ctx.computeOnClient(c -> EvadeClient.modules.get(KeystrokesHud.class).mouseDotForTest()[0]);
            most = Math.max(most, Math.abs(dot));
        }
        final float moved = most;
        ctx.runOnClient(c -> {
            KeystrokesHud k = EvadeClient.modules.get(KeystrokesHud.class);
            for (Setting<?> s : k.getSettings()) if (s.getName().equals("Mouse Movement")) s.reset();
            k.setEnabled(wasOn);
        });
        check(moved > 0.01f, "Keystrokes: the Mouse Movement dot follows the camera turning (" + moved + ")");
    }
}
