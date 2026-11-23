package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.module.visuals.NoFog;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches the real game, builds a test arena and checks each module actually does its job. Every check either
 * passes ("[gametest] PASS: ...") or stops the run with "FAILED: ...". At the end of the world block the suite
 * asserts that no module crashed (and so switched itself off) at any point during the run.
 *
 * <p>Threading: {@link #enable}, {@link #disable}, {@link #setBool} and friends run on the client through the test
 * context, so they are only ever called from the test thread, never inside a runOnClient/computeOnClient lambda
 * (that would deadlock). Inside those lambdas, modules and settings are changed directly.
 */
public class EvadeClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Boolean.getBoolean("evade.footage")) { Footage.film(ctx); return; } // scripts/film-footage.sh
        if (Boolean.getBoolean("evade.menushot")) { menuShots(ctx); return; }      // ./gradlew runClientGameTest -PmenuShot
        if (Boolean.getBoolean("evade.replaytest")) { ReplayTest.run(ctx); return; } // ./gradlew runClientGameTest -PreplayTest

        // Switched on before any world exists: the enable hooks must wait for the world (checked once in it).
        boolean[] beforeWorld = ctx.computeOnClient(c -> {
            var fb = EvadeClient.modules.get(net.evadeclient.module.visuals.Fullbright.class);
            var perf = EvadeClient.modules.get(net.evadeclient.module.mods.Performance.class);
            fb.setEnabled(true);
            return new boolean[]{fb.isEnabled() && !fb.hooksActive(), !perf.isEnabled() || !perf.hooksActive()};
        });

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            var server = sp.getServer();
            server.runCommand("time set day");
            server.runCommand("gamemode survival @a");
            server.runCommand("difficulty easy");
            server.runCommand("tp @a 0 -60 0 0 0");
            ctx.waitTicks(20);
            testDeferredEnable(ctx, beforeWorld);
            // Performance is on by default; tests start without it (its particle budget would make them random)
            // and turn it on where they test it.
            disable(ctx, net.evadeclient.module.mods.Performance.class);
            String addons = ctx.computeOnClient(c -> net.evadeclient.module.mods.Performance.installedAddons());
            check(addons.startsWith("All") && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("moreculling"),
                    "Performance mods load alongside Evade (" + addons + ", MoreCulling too)");

            testCore(ctx, sp);
            testQualityOfLife(ctx, sp);
            testQolHud(ctx, sp);
            testItemHuds(ctx, sp);
            testHudCost(ctx, sp); // early, before anything triggers a chunk rebuild that would skew the timing
            testWorldAtmosphere(ctx, sp);
            testCameraAndView(ctx, sp);
            testVisualBatch(ctx, sp);
            testRenderBatch(ctx, sp);
            testWaypoints(ctx);
            testPerformance(ctx, sp);
            testFpsAndServerRules(ctx, sp);
            testFrameTime(ctx);
            testClips(ctx, sp);
            testVoice(ctx);
            testMiscModules(ctx);
            testChatAndAlerts(ctx, sp);
            testHud(ctx, sp);
            testArmorAndCooldowns(ctx, sp);
            testModuleList(ctx);
            testHudLayout(ctx);
            testMenu(ctx, sp);
            testProfiles(ctx);
            testIcons(ctx, sp);
            testCustomization(ctx);
            testConfigRobustness(ctx);

            List<String> crashed = ctx.computeOnClient(c -> List.copyOf(ModuleManager.CRASHED));
            check(crashed.isEmpty(), "No module crashed during the run: " + crashed);
        }
        ReplayTest.run(ctx);
    }

    // ---- helpers (test thread only) -------------------------------------------------------------------

    private static <T extends Module> T enable(ClientGameTestContext ctx, Class<T> type) {
        return ctx.computeOnClient(c -> {
            T m = EvadeClient.modules.get(type);
            m.setEnabled(true);
            return m;
        });
    }

    private static void disable(ClientGameTestContext ctx, Class<? extends Module> type) {
        ctx.runOnClient(c -> EvadeClient.modules.get(type).setEnabled(false));
    }

    private static void setBool(ClientGameTestContext ctx, Module m, String name, boolean value) {
        ctx.runOnClient(c -> {
            for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((BooleanSetting) s).set(value);
        });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    /** Vanilla toasts (advancements, recipes, tutorials) are cleared so screenshots show only Evade. */
    private static void clearToasts(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.waitTicks(1);
    }

    // ---- helpers (any thread; they touch settings directly) -------------------------------------------

    /** A module's setting by name; fails the test if there is none. */
    private static Setting<?> setting(Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return s;
        throw new AssertionError("FAILED: no setting \"" + name + "\" on " + m.getName());
    }

    private static void setMode(Module m, String name, String value) {
        ((net.evadeclient.setting.ModeSetting) setting(m, name)).fromJson(new com.google.gson.JsonPrimitive(value));
    }

    private static void setNumber(Module m, String name, double value) {
        ((net.evadeclient.setting.NumberSetting) setting(m, name)).set(value);
    }

    /** Sets a setting by name from a plain value (string, number or boolean), the way a config load would. */
    private static void setJson(Module m, String name, Object value) {
        setting(m, name).loadJson(new com.google.gson.Gson().toJsonTree(value));
    }

    private static String settingText(Module m, String name) {
        return setting(m, name).display();
    }

    private static int liveParticles(net.minecraft.client.MinecraftClient c) {
        var m = java.util.regex.Pattern.compile("\\d+").matcher(c.particleManager.getDebugString());
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }

    // ---- core: deferred enable, chat commands, keybinds, hidden settings, pinned debug entries ---------

    /** Modules switched on before the world existed run their enable hooks once it has loaded. */
    private void testDeferredEnable(ClientGameTestContext ctx, boolean[] beforeWorld) {
        ctx.waitTicks(20);
        boolean[] inWorld = ctx.computeOnClient(c -> {
            var fb = EvadeClient.modules.get(net.evadeclient.module.visuals.Fullbright.class);
            var perf = EvadeClient.modules.get(net.evadeclient.module.mods.Performance.class);
            boolean clouds = !perf.lighterOptions.get()
                    || c.options.getCloudRenderMode().getValue() == net.minecraft.client.option.CloudRenderMode.OFF;
            return new boolean[]{fb.hooksActive(), net.evadeclient.module.visuals.Fullbright.level() > 0.99f,
                    !perf.isEnabled() || (perf.hooksActive() && clouds)};
        });
        disable(ctx, net.evadeclient.module.visuals.Fullbright.class);
        ctx.waitTicks(20);
        check(beforeWorld[0] && inWorld[0] && inWorld[1],
                "Fullbright switched on before the world loaded lights it once it has (" + java.util.Arrays.toString(inWorld) + ")");
        check(beforeWorld[1] && inWorld[2], "Performance waits for the world before applying its video settings");
    }

    /** Chat commands, keybinds (and F3 combinations), hidden settings and pinned F3 entries. */
    private void testCore(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String commands = ctx.computeOnClient(c -> {
            var cm = EvadeClient.commands;
            StringBuilder p = new StringBuilder();
            var zoom = EvadeClient.modules.get(Zoom.class);
            var level = (net.evadeclient.setting.NumberSetting) setting(zoom, "Zoom Level");
            double level0 = level.get();
            if (cm.handleChat(".set Zoom Level 6")) p.append(".set wasn't handled; ");
            if (Math.abs(level.get() - 6) > 1e-9) p.append(".set Zoom Level 6 gave ").append(level.get()).append("; ");
            level.set(level0);

            var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
            boolean nh0 = nh.isEnabled();
            cm.handleChat(".toggle nickhider");
            if (nh.isEnabled() == nh0) p.append(".toggle nickhider didn't toggle; ");
            cm.handleChat(".toggle Nick Hider");
            if (nh.isEnabled() != nh0) p.append(".toggle Nick Hider didn't toggle back; ");

            for (String chat : new String[]{".5 hearts left", "...", ". lol", ".coordz lol"})
                if (!cm.handleChat(chat)) p.append('"').append(chat).append("\" was taken as a command; ");
            if (cm.handleChat(".coords")) p.append(".coords (a module command) wasn't handled; ");

            var list = EvadeClient.modules.get(net.evadeclient.module.client.ModuleListHud.class);
            String color0 = list.colorMode.get();
            cm.handleChat(".set Module List List Color Rainbow");
            if (!list.colorMode.is("Rainbow")) p.append("List Color Rainbow gave ").append(list.colorMode.get()).append("; ");
            cm.handleChat(".set Module List List Color Bogus");
            if (!list.colorMode.is("Rainbow")) p.append("a bogus List Color changed it; ");
            list.colorMode.set(color0);

            var gg = EvadeClient.modules.get(net.evadeclient.module.misc.AutoGG.class);
            var text = (net.evadeclient.setting.StringSetting) setting(gg, "Custom Text");
            cm.handleChat(".set AutoGG Custom Text Good game");
            if (!"Good game".equals(text.get())) p.append("multi-word text gave \"").append(text.get()).append("\"; ");
            cm.handleChat(".set AutoGG Custom Text");
            if (!"".equals(text.get())) p.append("an empty value didn't clear the text; ");
            text.reset();

            // Hidden settings are saved but can't be set from chat.
            var ts = EvadeClient.modules.get(net.evadeclient.module.mods.ToggleSprint.class);
            var last = (BooleanSetting) setting(ts, "Last Sprint");
            boolean last0 = last.get();
            if (!last.isHidden()) p.append("Last Sprint isn't hidden; ");
            cm.handleChat(".set ToggleSprint Last Sprint " + (last0 ? "off" : "on"));
            if (last.get() != last0) p.append(".set changed a hidden setting; ");
            try {
                var tmp = java.nio.file.Files.createTempFile("evade-config", ".json");
                EvadeClient.config.saveTo(tmp);
                var root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(tmp)).getAsJsonObject();
                java.nio.file.Files.deleteIfExists(tmp);
                if (!root.getAsJsonObject(ts.getName()).getAsJsonObject("settings").has("Last Sprint"))
                    p.append("the config doesn't save the hidden Last Sprint; ");
            } catch (Exception e) {
                p.append("saving a config failed: ").append(e).append("; ");
            }
            return p.toString();
        });
        check(commands.isEmpty(), "Chat commands: .set, .toggle, module commands, chat that only starts with a dot (" + commands + ")");

        // A bound key toggles its module in game, but not as part of an F3 combination.
        var borders = net.evadeclient.module.visuals.ChunkBorders.class;
        boolean bordersWas = ctx.computeOnClient(c -> {
            var m = EvadeClient.modules.get(borders);
            m.setKey(GLFW.GLFW_KEY_KP_7);
            return m.isEnabled();
        });
        ctx.getInput().pressKey(GLFW.GLFW_KEY_KP_7);
        ctx.waitTicks(2);
        boolean toggled = ctx.computeOnClient(c -> EvadeClient.modules.get(borders).isEnabled()) != bordersWas;
        boolean before = ctx.computeOnClient(c -> EvadeClient.modules.get(borders).isEnabled());
        ctx.getInput().holdKey(GLFW.GLFW_KEY_F3);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_KP_7);
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_F3);
        ctx.waitTicks(2);
        boolean withF3 = ctx.computeOnClient(c -> EvadeClient.modules.get(borders).isEnabled()) != before;
        ctx.runOnClient(c -> {
            c.debugHudEntryList.setF3Enabled(false);
            var m = EvadeClient.modules.get(borders);
            m.setKey(GLFW.GLFW_KEY_UNKNOWN);
            m.setEnabled(bordersWas);
        });
        check(toggled && !withF3, "Keybinds toggle in game, never as part of F3 + key (" + toggled + ", " + withF3 + ")");

        // A pinned F3 entry (FPS: Always) leaves Evade's HUD in place; only the full F3 screen hides it.
        Object fpsVisibility = ctx.computeOnClient(c -> {
            var was = c.debugHudEntryList.getVisibility(net.minecraft.client.gui.hud.debug.DebugHudEntries.FPS);
            c.debugHudEntryList.setEntryVisibility(net.minecraft.client.gui.hud.debug.DebugHudEntries.FPS,
                    net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility.ALWAYS_ON);
            net.evadeclient.hud.Hud.PROFILE = new java.util.HashMap<>();
            return was;
        });
        ctx.waitTicks(20);
        long frames = ctx.computeOnClient(c -> {
            var p = net.evadeclient.hud.Hud.PROFILE;
            net.evadeclient.hud.Hud.PROFILE = null;
            c.debugHudEntryList.setEntryVisibility(net.minecraft.client.gui.hud.debug.DebugHudEntries.FPS,
                    (net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility) fpsVisibility);
            return p == null ? 0L : p.getOrDefault("__frames", 0L);
        });
        check(frames > 0, "Evade's HUD stays up with a pinned F3 entry (" + frames + " HUD frames)");
    }

    // ---- quality of life: Toggle Sprint, Zoom, Freelook, clicks -----------------------------------------

    private void testQualityOfLife(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("kill @e[type=!player]");
        sp.getServer().runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(7);

        // Toggle Sprint holds sprint through the movement input; it never presses the key binding.
        check(ctx.computeOnClient(c -> c.player.input.playerInput.sprint() && !c.options.sprintKey.isPressed()),
                "ToggleSprint holds sprint through the movement input, without pressing the key binding");
        ctx.getInput().pressKey(o -> o.sprintKey);
        ctx.waitTicks(3);
        check(!ctx.computeOnClient(c -> c.player.input.playerInput.sprint()), "ToggleSprint: a tap on sprint toggles it off");
        ctx.getInput().pressKey(o -> o.sprintKey);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(c -> c.player.input.playerInput.sprint()), "ToggleSprint: a second tap toggles it back on");
        disable(ctx, net.evadeclient.module.mods.ToggleSprint.class);
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(c -> c.player.input.playerInput.sprint() || c.options.sprintKey.isPressed()),
                "ToggleSprint: turning it off releases sprint");
        var ts = enable(ctx, net.evadeclient.module.mods.ToggleSprint.class);
        ctx.runOnClient(c -> c.options.getSprintToggled().setValue(true));
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(c -> c.player.input.playerInput.sprint()),
                "ToggleSprint steps aside while the game's own Sprint: Toggle is on");
        ctx.runOnClient(c -> c.options.getSprintToggled().setValue(false));
        ctx.waitTicks(2);

        // Sprint Mode: Hold never holds sprint for you; Always always does; back to Toggle keeps the toggle.
        ctx.runOnClient(c -> setMode(ts, "Sprint Mode", "Hold"));
        ctx.waitTicks(2);
        boolean hold = ctx.computeOnClient(c -> c.player.input.playerInput.sprint());
        ctx.runOnClient(c -> setMode(ts, "Sprint Mode", "Always"));
        ctx.waitTicks(2);
        boolean always = ctx.computeOnClient(c -> c.player.input.playerInput.sprint());
        ctx.runOnClient(c -> setMode(ts, "Sprint Mode", "Toggle"));
        ctx.waitTicks(2);
        check(!hold && always, "Toggle Sprint modes: Hold lets go, Always holds sprint (" + hold + ", " + always + ")");

        // Release Sneak When Flying: a toggled sneak stands you up the tick you start flying.
        sp.getServer().runCommand("gamemode creative @a");
        setBool(ctx, ts, "Toggle Sneak", true);
        ctx.waitTicks(2);
        ctx.getInput().pressKey(o -> o.sneakKey);
        ctx.waitTicks(3);
        boolean sneakOn = ctx.computeOnClient(c -> ts.sneakingForTest() && c.player.input.playerInput.sneak());
        ctx.runOnClient(c -> c.player.getAbilities().flying = true);
        ctx.waitTicks(3);
        boolean released = ctx.computeOnClient(c -> !ts.sneakingForTest() && !c.player.input.playerInput.sneak());
        ctx.runOnClient(c -> c.player.getAbilities().flying = false);
        sp.getServer().runCommand("gamemode survival @a");
        setBool(ctx, ts, "Toggle Sneak", false);
        ctx.waitTicks(3);
        check(sneakOn && released, "Toggle Sneak releases when you start flying (" + sneakOn + ", " + released + ")");

        // Pause In Menus: a toggled sprint is let go while a screen is open and comes back when it closes.
        ctx.runOnClient(c -> c.setScreen(new net.minecraft.client.gui.screen.ChatScreen("", false)));
        ctx.waitTicks(2);
        boolean inMenu = ctx.computeOnClient(c -> c.player.input.playerInput.sprint());
        ctx.runOnClient(c -> c.setScreen(null));
        ctx.waitTicks(2);
        boolean afterMenu = ctx.computeOnClient(c -> c.player.input.playerInput.sprint());
        check(!inMenu && afterMenu, "Toggle Sprint pauses in menus (" + inMenu + " -> " + afterMenu + ")");

        // Status labels: custom text shows.
        String label = ctx.computeOnClient(c -> {
            ((net.evadeclient.setting.StringSetting) setting(ts, "Sprint Toggled Text")).set("Sprint!");
            var lines = ts.textForTest();
            setting(ts, "Sprint Toggled Text").reset();
            return lines.isEmpty() ? "" : lines.get(0);
        });
        check("Sprint!".equals(label), "Toggle Sprint shows custom status text (" + label + ")");

        // Zoom: holding the key narrows the world FOV and the mouse; scrolling narrows it further; letting go restores.
        float fov0 = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        ctx.getInput().holdKey(GLFW.GLFW_KEY_C);
        ctx.waitTicks(15);
        double zoomMult = ctx.computeOnClient(c -> EvadeClient.modules.get(Zoom.class).fovMultiplier());
        float fovZoomed = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        double sensitivity = ctx.computeOnClient(c -> EvadeClient.modules.get(Zoom.class).sensitivity());
        ctx.takeScreenshot("evade-zoom");
        ctx.getInput().scroll(1);
        ctx.waitTicks(8);
        float fovScrolled = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_C);
        ctx.waitTicks(30);
        float fovBack = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        boolean stillZooming = ctx.computeOnClient(c -> EvadeClient.modules.get(Zoom.class).zooming());
        check(zoomMult < 0.5 && fovZoomed < fov0 * 0.5f && sensitivity < 1 && fovScrolled < fovZoomed
                        && Math.abs(fovBack - fov0) < 0.5f && !stillZooming,
                "Zoom narrows the view while held, scroll zooms further, letting go restores (FOV " + fov0 + " -> "
                        + fovZoomed + " -> " + fovScrolled + " -> " + fovBack + ", sensitivity " + sensitivity + ")");

        // Freelook (off by default): the camera turns, the player doesn't.
        ctx.runOnClient(c -> c.mouse.lockCursor());
        ctx.waitTicks(2);
        float y0 = ctx.computeOnClient(c -> c.player.getYaw());
        ctx.getInput().moveCursor(100, 0);
        ctx.waitTicks(3);
        float y1 = ctx.computeOnClient(c -> c.player.getYaw());
        EvadeClient.LOG.info("[gametest] mouse sanity: yaw {} -> {}", y0, y1);
        enable(ctx, net.evadeclient.module.mods.Freelook.class);
        float yawBefore = ctx.computeOnClient(c -> c.player.getYaw());
        ctx.getInput().holdKey(GLFW.GLFW_KEY_LEFT_ALT);
        ctx.waitTicks(3);
        ctx.getInput().moveCursor(300, 0);
        ctx.waitTicks(3);
        float yawAfter = ctx.computeOnClient(c -> c.player.getYaw());
        float camYaw = ctx.computeOnClient(c -> c.gameRenderer.getCamera().getYaw());
        ctx.takeScreenshot("evade-freelook");
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
        ctx.waitTicks(3);
        check(Math.abs(yawAfter - yawBefore) < 0.01 && Math.abs(camYaw - yawBefore) > 5,
                "Freelook turns the camera but not the player (player " + yawBefore + "->" + yawAfter + ", camera " + camYaw + ")");

        // Freelook's smooth return: aiming is back at once, the camera swings back, then first person returns.
        ctx.runOnClient(c -> {
            var fl = EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class);
            setJson(fl, "Return", "Smooth");
            setJson(fl, "Return Time", 600);
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
        });
        ctx.getInput().holdKey(GLFW.GLFW_KEY_LEFT_ALT);
        ctx.waitTicks(3);
        boolean[] during = ctx.computeOnClient(c -> {
            var fl = EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class);
            return new boolean[]{fl.capturing(), c.options.getPerspective() == net.minecraft.client.option.Perspective.THIRD_PERSON_BACK};
        });
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
        ctx.waitTicks(1);
        boolean[] after = ctx.computeOnClient(c -> {
            var fl = EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class);
            return new boolean[]{fl.capturing(), fl.returning()};
        });
        ctx.waitTicks(30);
        boolean[] done = ctx.computeOnClient(c -> {
            var fl = EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class);
            return new boolean[]{fl.active(), c.options.getPerspective() == net.minecraft.client.option.Perspective.FIRST_PERSON};
        });
        String migrated = ctx.computeOnClient(c -> {
            var fl = EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class);
            setting(fl, "Return").reset();
            setting(fl, "Return Time").reset();
            var old = new com.google.gson.JsonObject();
            old.addProperty("Third Person", false);
            fl.onConfigLoaded(old);
            String r = String.valueOf(setting(fl, "Perspective").get());
            setting(fl, "Perspective").reset();
            fl.setEnabled(false);
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
            return r;
        });
        check(during[0] && during[1] && !after[0] && after[1] && !done[0] && done[1],
                "Freelook smooth return (" + java.util.Arrays.toString(during) + java.util.Arrays.toString(after) + java.util.Arrays.toString(done) + ")");
        check("Keep Current".equals(migrated), "Freelook migrates Third Person off to Perspective Keep Current (" + migrated + ")");
        check(net.evadeclient.module.mods.Freelook.host("Mc.Hypixel.NET.:25565").equals("mc.hypixel.net")
                        && net.evadeclient.module.mods.Freelook.isForbidden("mc.hypixel.net", "")
                        && net.evadeclient.module.mods.Freelook.isForbidden("play.example.net", "example.net")
                        && !net.evadeclient.module.mods.Freelook.isForbidden("nothypixel.net", ""),
                "Freelook stays off on servers that forbid it (host parsing and denylist)");

        // Clicks: every press counts, even several within one frame; the CPS widget formats them.
        ctx.runOnClient(c -> c.mouse.lockCursor());
        ctx.waitTicks(2);
        for (int i = 0; i < 5; i++) ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        int rcps = ctx.computeOnClient(c -> net.evadeclient.util.ClickTracker.rightCps());
        check(rcps >= 5, "ClickTracker counts fast clicks (" + rcps + " right clicks)");
        String cps = ctx.computeOnClient(c -> {
            var hud = EvadeClient.modules.get(net.evadeclient.module.hud.CpsHud.class);
            boolean wasOn = hud.isEnabled();
            hud.setEnabled(false); // re-enabling clears the session peak left by the real clicks above
            hud.setEnabled(true);
            StringBuilder p = new StringBuilder();
            try {
                net.evadeclient.util.ClickTracker.clear();
                for (int i = 0; i < 3; i++) net.evadeclient.util.ClickTracker.press(true);
                for (int i = 0; i < 2; i++) net.evadeclient.util.ClickTracker.press(false);
                if (net.evadeclient.util.ClickTracker.leftCps() != 3 || net.evadeclient.util.ClickTracker.rightCps() != 2
                        || Math.abs(net.evadeclient.util.ClickTracker.cps(true, 500) - 6f) > 0.01f) p.append("tracker counts; ");
                if (!hud.textForTest().equals(List.of("3 | 2 CPS"))) p.append("default ").append(hud.textForTest()).append("; ");
                setJson(hud, "Label", "Prefix");
                if (!hud.textForTest().equals(List.of("CPS: 3 | 2"))) p.append("prefix ").append(hud.textForTest()).append("; ");
                setJson(hud, "Label", "Per Button");
                if (!hud.textForTest().equals(List.of("L 3  R 2"))) p.append("per button ").append(hud.textForTest()).append("; ");
                setJson(hud, "Label", "Suffix");
                setJson(hud, "Layout", "Stacked");
                setJson(hud, "Peak", "Session");
                hud.onTick();
                if (!hud.textForTest().equals(List.of("LMB 3 CPS", "RMB 2 CPS", "Max 3 | 2"))) p.append("stacked ").append(hud.textForTest()).append("; ");
                setJson(hud, "Hide When Idle", true);
                net.evadeclient.util.ClickTracker.clear();
                if (!hud.textForTest().isEmpty()) p.append("hide when idle ").append(hud.textForTest()).append("; ");
            } finally {
                for (String n : new String[]{"Label", "Layout", "Peak", "Hide When Idle"}) setting(hud, n).reset();
                hud.setEnabled(wasOn);
            }
            return p.toString();
        });
        check(cps.isEmpty(), "CPS widget formats, peak and idle hiding (" + cps + ")");
    }

    // ---- world atmosphere: Fullbright, Time Changer, NoFog, Clear Water, Weather Changer ----------------

    private void testWorldAtmosphere(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(3);

        // Fullbright changes the lightmap only: the Brightness option is never written. It fades over Fade Time (0.4 s).
        double g0 = ctx.computeOnClient(c -> c.options.getGamma().getValue());
        enable(ctx, net.evadeclient.module.visuals.Fullbright.class);
        ctx.waitTicks(20);
        float lvlOn = ctx.computeOnClient(c -> net.evadeclient.module.visuals.Fullbright.level());
        double g1 = ctx.computeOnClient(c -> c.options.getGamma().getValue());
        boolean gammaLineKept = ctx.computeOnClient(c -> {
            c.options.write();
            try {
                return java.nio.file.Files.readAllLines(c.runDirectory.toPath().resolve("options.txt")).stream()
                        .anyMatch(l -> l.startsWith("gamma:"));
            } catch (java.io.IOException e) {
                return false;
            }
        });
        disable(ctx, net.evadeclient.module.visuals.Fullbright.class);
        ctx.waitTicks(20);
        float lvlOff = ctx.computeOnClient(c -> net.evadeclient.module.visuals.Fullbright.level());
        check(lvlOn > 0.99f && lvlOff == 0f && Math.abs(g1 - g0) < 1e-9 && gammaLineKept,
                "Fullbright fades in and out and leaves Brightness alone (" + lvlOn + " / " + lvlOff + ", gamma " + g0 + " -> " + g1
                        + ", options.txt keeps gamma " + gammaLineKept + ")");

        // Time Changer eases to its preset over about two seconds and hands the server's time back when off.
        enable(ctx, net.evadeclient.module.mods.TimeChanger.class);
        ctx.waitTicks(60);
        long time = ctx.computeOnClient(c -> Math.floorMod(c.world.getTimeOfDay(), 24000L));
        check(time == 6000, "Time Changer sets client time (" + time + ")");
        disable(ctx, net.evadeclient.module.mods.TimeChanger.class);
        ctx.waitTicks(2);
        long clientTime = ctx.computeOnClient(c -> c.world.getTimeOfDay());
        long serverTime = server.computeOnServer(s -> s.getOverworld().getTimeOfDay());
        check(Math.abs(clientTime - serverTime) <= 40,
                "Time Changer restores the server's time when off (" + clientTime + " vs " + serverTime + ")");

        // NoFog: open-air fog gone (Fog Strength 0), render-distance fade kept at Edge Fade 25% of vanilla's width.
        enable(ctx, NoFog.class);
        ctx.waitTicks(3);
        float envStart = ctx.computeOnClient(c -> NoFog.LAST_ENV_START);
        float rStart = ctx.computeOnClient(c -> NoFog.LAST_RENDER_START);
        float rEnd = ctx.computeOnClient(c -> NoFog.LAST_RENDER_END);
        disable(ctx, NoFog.class);
        float edge = net.minecraft.util.math.MathHelper.clamp(rEnd / 10f, 4f, 64f) * 0.25f;
        check(envStart >= 1e5f && Math.abs((rEnd - rStart) - edge) < 0.05f,
                "NoFog clears open-air fog and keeps a 25% edge fade (env " + envStart + ", edge " + (rEnd - rStart) + " vs " + edge + ")");

        // Clear Water lifts the water fog, but never Blindness: effect fog stays whatever NoFog and Clear Water say.
        server.runCommand("fill 98 -61 98 102 -56 102 glass");
        server.runCommand("fill 99 -60 99 101 -56 101 water");
        server.runCommand("tp @a 100 -59 100 0 0");
        enable(ctx, net.evadeclient.module.visuals.ClearWater.class);
        ctx.waitTicks(10);
        boolean clear = ctx.computeOnClient(c -> net.evadeclient.module.visuals.ClearWater.clearFog());
        float waterEnd = ctx.computeOnClient(c -> NoFog.LAST_ENV_END);
        enable(ctx, NoFog.class);
        server.runCommand("effect give @a minecraft:blindness infinite 0 true");
        ctx.waitTicks(30);
        float blindEnd = ctx.computeOnClient(c -> NoFog.LAST_ENV_END);
        boolean clearBlind = ctx.computeOnClient(c -> net.evadeclient.module.visuals.ClearWater.clearFog());
        server.runCommand("effect clear @a minecraft:blindness");
        disable(ctx, NoFog.class);
        disable(ctx, net.evadeclient.module.visuals.ClearWater.class);
        server.runCommand("tp @a 0 -60 0 0 0");
        check(clear && waterEnd >= 1e6f, "Clear Water clears fog while underwater (fog end " + waterEnd + ")");
        check(blindEnd < 50f && !clearBlind, "Blindness fog stays with NoFog and Clear Water on (fog end " + blindEnd + ")");

        // Weather Changer: shows rain while the server stays clear, then fades back to the real weather when off.
        server.runCommand("weather clear");
        var wc = enable(ctx, net.evadeclient.module.mods.WeatherChanger.class);
        ctx.runOnClient(c -> setMode(wc, "Weather", "Rain"));
        ctx.waitTicks(80); // Transition Seconds 3
        float shownRain = ctx.computeOnClient(c -> net.evadeclient.module.mods.WeatherChanger.shownRain());
        boolean realDry = ctx.computeOnClient(c -> !c.world.isRaining());
        disable(ctx, net.evadeclient.module.mods.WeatherChanger.class);
        ctx.waitTicks(80);
        boolean letGo = ctx.computeOnClient(c -> !net.evadeclient.module.mods.WeatherChanger.overriding());
        ctx.runOnClient(c -> setting(wc, "Weather").reset());
        check(shownRain > 0.99f && realDry && letGo,
                "Weather Changer shows rain client-side and lets go when off (" + shownRain + ", dry " + realDry + ", released " + letGo + ")");
        // ...and clear skies in the rain, while gameplay keeps the server's weather.
        server.runCommand("weather rain");
        enable(ctx, net.evadeclient.module.mods.WeatherChanger.class); // default "Clear"
        ctx.waitTicks(80);
        float shownClear = ctx.computeOnClient(c -> net.evadeclient.module.mods.WeatherChanger.shownRain());
        boolean realRain = ctx.computeOnClient(c -> c.world.isRaining());
        disable(ctx, net.evadeclient.module.mods.WeatherChanger.class);
        ctx.waitTicks(80);
        boolean letGoRain = ctx.computeOnClient(c -> !net.evadeclient.module.mods.WeatherChanger.overriding());
        server.runCommand("weather clear");
        check(shownClear < 0.01f && realRain && letGoRain,
                "Weather Changer shows clear skies in the rain without stopping it (" + shownClear + ", raining " + realRain + ", released " + letGoRain + ")");
    }

    // ---- camera and view: projection, FOV Changer, No Hurt Cam, Camera Distance, Zoom options -----------

    private void testCameraAndView(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(5);

        // Projection: a point straight ahead lands in the middle of the screen, also while zoomed.
        java.util.function.Function<net.minecraft.client.MinecraftClient, double[]> ahead = c -> {
            var cam = c.gameRenderer.getCamera();
            var fwd = net.minecraft.util.math.Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
            double[] p = net.evadeclient.util.Projection.toScreen(cam.getCameraPos().add(fwd.multiply(10)));
            double[] side = net.evadeclient.util.Projection.toScreen(cam.getCameraPos().add(fwd.multiply(10)).add(fwd.rotateY((float) Math.PI / 2)));
            return new double[]{p[0] - c.getWindow().getScaledWidth() / 2.0, p[1] - c.getWindow().getScaledHeight() / 2.0,
                    Math.abs(side[0] - p[0])};
        };
        double[] plain = ctx.computeOnClient(ahead::apply);
        ctx.getInput().holdKey(GLFW.GLFW_KEY_C);
        ctx.waitTicks(15);
        double[] zoomed = ctx.computeOnClient(ahead::apply);
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_C);
        ctx.waitTicks(20);
        check(Math.abs(plain[0]) < 2 && Math.abs(plain[1]) < 2 && zoomed[2] > plain[2] * 2,
                "Projection follows the world view and zoom (offset " + plain[0] + "," + plain[1] + "; side " + plain[2] + " -> " + zoomed[2] + ")");

        // FOV Changer (Lock) keeps the game FOV under Speed.
        server.runCommand("effect give @a minecraft:speed 30 4 true");
        ctx.waitTicks(15);
        float fovSpeed = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        var fovMod = enable(ctx, net.evadeclient.module.mods.FovChanger.class);
        ctx.waitTicks(15);
        float fovLocked = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        int gameFov = ctx.computeOnClient(c -> c.options.getFov().getValue());
        check(fovSpeed > gameFov + 3 && Math.abs(fovLocked - gameFov) < 0.5f,
                "FOV Changer locks out speed FOV (" + fovSpeed + " -> " + fovLocked + ", game " + gameFov + ")");

        // Custom Effects weight the speed FOV; Transition Speed; Alt FOV toggle; Hand FOV.
        ctx.runOnClient(c -> { setMode(fovMod, "Mode", "Custom Effects"); fovMod.speedEffect.set(100.0); });
        ctx.waitTicks(15);
        float fovSpeed100 = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        ctx.runOnClient(c -> fovMod.speedEffect.set(0.0));
        ctx.waitTicks(15);
        float fovSpeed0 = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        check(fovSpeed100 > gameFov + 3 && Math.abs(fovSpeed0 - gameFov) < 0.5f,
                "FOV Changer Speed & Sprint Effect % (100: " + fovSpeed100 + ", 0: " + fovSpeed0 + ", game " + gameFov + ")");
        ctx.runOnClient(c -> { fovMod.transitionSpeed.set(1.0); fovMod.speedEffect.set(100.0); });
        ctx.waitTicks(2);
        float fovFast = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        check(Math.abs(fovFast - fovSpeed100) < 0.5f, "FOV Changer Transition Speed 1 applies effects at once (" + fovFast + ")");
        ctx.runOnClient(c -> {
            fovMod.speedEffect.set(0.0);
            fovMod.altKey.set(GLFW.GLFW_KEY_KP_5);
            setMode(fovMod, "Alt FOV Mode", "Toggle");
            fovMod.altFov.set(100.0);
            fovMod.smoothSwitch.set(false);
        });
        tapKey(ctx, GLFW.GLFW_KEY_KP_5);
        float fovAlt = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        tapKey(ctx, GLFW.GLFW_KEY_KP_5);
        float fovAltBack = ctx.computeOnClient(c -> net.evadeclient.util.Projection.fov);
        check(Math.abs(fovAlt - 100) < 0.5f && Math.abs(fovAltBack - gameFov) < 0.5f,
                "FOV Changer Alt FOV toggles (" + fovAlt + " -> " + fovAltBack + ")");
        boolean handOk = ctx.computeOnClient(c -> {
            fovMod.handFov.set(90.0);
            float ignores = net.evadeclient.module.mods.FovChanger.handFov(70f, 0.25);
            fovMod.handIgnoresZoom.set(false);
            float zoomedHand = net.evadeclient.module.mods.FovChanger.handFov(70f, 0.25);
            return Math.abs(ignores - 90f) < 1e-3 && Math.abs(zoomedHand - 22.5f) < 1e-3;
        });
        check(handOk, "FOV Changer Hand FOV (and Hand FOV Ignores Zoom)");
        ctx.runOnClient(c -> { for (var s : fovMod.getSettings()) s.reset(); });
        disable(ctx, net.evadeclient.module.mods.FovChanger.class);
        server.runCommand("effect clear @a minecraft:speed");

        // No Hurt Cam: per-view shake share and the death tilt switch.
        var nhc = enable(ctx, net.evadeclient.module.mods.NoHurtCam.class);
        boolean[] hurt = ctx.computeOnClient(c -> {
            nhc.deathTilt.set(false);
            boolean deathOff = !net.evadeclient.module.mods.NoHurtCam.deathTilt(true);
            nhc.deathTilt.set(true);
            boolean deathOn = net.evadeclient.module.mods.NoHurtCam.deathTilt(true);
            nhc.worldShake.set(50.0);
            net.evadeclient.module.mods.NoHurtCam.setScale(nhc.shake(false));
            double half = net.evadeclient.module.mods.NoHurtCam.tiltStrength(1.0);
            net.evadeclient.module.mods.NoHurtCam.setScale(nhc.shake(true));
            double none = net.evadeclient.module.mods.NoHurtCam.tiltStrength(1.0);
            net.evadeclient.module.mods.NoHurtCam.setScale(1f);
            nhc.worldShake.set(0.0);
            return new boolean[]{deathOff, deathOn, Math.abs(half - 0.5) < 1e-6, none == 0};
        });
        check(hurt[0] && hurt[1] && hurt[2] && hurt[3], "No Hurt Cam World/Hand Shake % and Death Tilt");
        server.runCommand("damage @p 1"); // a real hit with the module on must not throw
        ctx.waitTicks(5);
        disable(ctx, net.evadeclient.module.mods.NoHurtCam.class);
        server.runCommand("effect give @a minecraft:instant_health 1 5 true");

        // Zoom: Smooth Zoom migration, Toggle + Hide HUD, Max Zoom, sensitivity back to 1, spyglass overlay.
        var zoomMod = ctx.computeOnClient(c -> EvadeClient.modules.get(Zoom.class));
        String migrated = ctx.computeOnClient(c -> {
            var o = new com.google.gson.JsonObject();
            o.addProperty("Smooth Zoom", false);
            zoomMod.onConfigLoaded(o);
            return String.valueOf(setting(zoomMod, "Animation").get());
        });
        check("Instant".equals(migrated), "Zoom: old Smooth Zoom off loads as Animation Instant (" + migrated + ")");
        ctx.runOnClient(c -> {
            for (var s : zoomMod.getSettings()) if (!s.isHidden()) s.reset();
            setMode(zoomMod, "Mode", "Toggle");
            setMode(zoomMod, "Overlay", "Spyglass");
            setNumber(zoomMod, "Zoom Level", 8);
            setNumber(zoomMod, "Max Zoom", 5);
            c.options.hudHidden = false;
        });
        setBool(ctx, zoomMod, "Hide HUD", true);
        tapKey(ctx, GLFW.GLFW_KEY_C);
        ctx.waitTicks(12);
        boolean[] zin = ctx.computeOnClient(c -> new boolean[]{zoomMod.zooming(), c.options.hudHidden,
                Math.abs(zoomMod.fovMultiplier() - 0.2) < 0.01, zoomMod.sensitivity() < 1});
        ctx.takeScreenshot("evade-zoom-spyglass");
        tapKey(ctx, GLFW.GLFW_KEY_C);
        ctx.waitTicks(20);
        boolean[] zout = ctx.computeOnClient(c -> new boolean[]{!zoomMod.zooming(), !c.options.hudHidden,
                zoomMod.fovMultiplier() == 1, zoomMod.sensitivity() == 1});
        ctx.runOnClient(c -> {
            for (var s : zoomMod.getSettings()) if (!s.isHidden()) s.reset();
            c.options.hudHidden = false;
        });
        check(zin[0] && zin[1] && zin[2] && zin[3], "Zoom Toggle + Hide HUD + Max Zoom clamps Zoom Level 8 to 5x (" + java.util.Arrays.toString(zin) + ")");
        check(zout[0] && zout[1] && zout[2] && zout[3], "Zoom toggles off, gives the HUD back, sensitivity exactly 1 (" + java.util.Arrays.toString(zout) + ")");

        // Camera Distance never puts the camera behind a wall, in either Wall Mode outside spectator.
        server.runCommand("tp @a 0 -60 0 0 0");
        server.runCommand("fill -3 -61 -2 3 -56 -2 stone");
        var camDist = enable(ctx, net.evadeclient.module.visuals.CameraClip.class);
        ctx.runOnClient(c -> {
            setNumber(camDist, "Distance", 8.0);
            c.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
        });
        ctx.waitTicks(20);
        double walled = ctx.computeOnClient(c -> c.gameRenderer.getCamera().getCameraPos().distanceTo(c.player.getEyePos()));
        ctx.runOnClient(c -> setJson(camDist, "Wall Mode", "Clip"));
        ctx.waitTicks(20);
        double walledClip = ctx.computeOnClient(c -> c.gameRenderer.getCamera().getCameraPos().distanceTo(c.player.getEyePos()));
        boolean clipThrough = ctx.computeOnClient(c -> camDist.clipThrough());
        server.runCommand("fill -3 -61 -2 3 -56 -2 air");
        ctx.waitTicks(20);
        double open = ctx.computeOnClient(c -> c.gameRenderer.getCamera().getCameraPos().distanceTo(c.player.getEyePos()));
        String scroll = ctx.computeOnClient(c -> {
            setting(camDist, "Wall Mode").reset();
            setting(camDist, "Distance").reset();
            StringBuilder p = new StringBuilder();
            // Front Distance follows Distance in configs saved before it existed.
            var old = new com.google.gson.JsonObject();
            old.addProperty("Distance", 6.0);
            setJson(camDist, "Distance", 6.0);
            camDist.onConfigLoaded(old);
            if (((Number) setting(camDist, "Front Distance").get()).doubleValue() != 6.0) p.append("Front Distance not migrated; ");
            setting(camDist, "Distance").reset();
            setting(camDist, "Front Distance").reset();
            // Scroll To Adjust: only in third person, wheel up brings the camera closer by Scroll Step (0.5).
            setJson(camDist, "Scroll To Adjust", true);
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
            boolean firstPerson = camDist.onScroll(1.0);
            c.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
            boolean thirdPerson = camDist.onScroll(1.0);
            double d = ((Number) setting(camDist, "Distance").get()).doubleValue();
            if (firstPerson || !thirdPerson || Math.abs(d - 3.5) > 1e-6)
                p.append("scroll (first person ").append(firstPerson).append(", third ").append(thirdPerson).append(", ").append(d).append("); ");
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
            for (String n : new String[]{"Distance", "Scroll To Adjust"}) setting(camDist, n).reset();
            return p.toString();
        });
        disable(ctx, net.evadeclient.module.visuals.CameraClip.class);
        check(walled < 2.5 && walledClip < 2.5 && !clipThrough && open > 7.5,
                "Camera Distance moves the camera back but stops at walls (" + walled + " / clip mode " + walledClip + " / open " + open + ")");
        check(scroll.isEmpty(), "Camera Distance Front Distance migration and Scroll To Adjust (" + scroll + ")");

        // Motion Blur runs after the world (and not while a menu is open).
        enable(ctx, net.evadeclient.module.visuals.MotionBlur.class);
        ctx.waitTicks(5);
        boolean blurred = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.MotionBlur.class).ranLastFrame());
        ctx.takeScreenshot("evade-motion-blur");
        disable(ctx, net.evadeclient.module.visuals.MotionBlur.class);
        check(blurred, "Motion Blur blends frames in game");
    }

    /** Taps a key long enough for per-frame key polling (KeySetting.isDown) to see it. */
    private static void tapKey(ClientGameTestContext ctx, int key) {
        ctx.getInput().holdKey(key);
        ctx.waitTicks(2);
        ctx.getInput().releaseKey(key);
        ctx.waitTicks(2);
    }

    // ---- HUD readouts -----------------------------------------------------------------------------------

    /** The Lunar-style HUD/info readouts: Item Counter, Saturation, Stopwatch, Server Address, Pack Display, Combo. */
    private void testQolHud(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        server.runCommand("clear @a");
        server.runCommand("item replace entity @a hotbar.0 with cobblestone 64");
        ctx.waitTicks(5);
        // Saturation is tracked client-side (not synced from the server), so set it on the client.
        ctx.runOnClient(c -> c.player.getHungerManager().setSaturationLevel(7f));

        var counter = enable(ctx, net.evadeclient.module.hud.ItemCounterHud.class);
        ctx.runOnClient(c -> setJson(counter, "Track", "Blocks"));
        enable(ctx, net.evadeclient.module.hud.SaturationHud.class);
        enable(ctx, net.evadeclient.module.hud.StopwatchHud.class);
        ctx.waitTicks(25);
        int count = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.ItemCounterHud.class).count());
        float sat = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.SaturationHud.class).saturation());
        long elapsed = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.StopwatchHud.class).elapsedMs());
        ctx.runOnClient(c -> setting(counter, "Track").reset());
        disable(ctx, net.evadeclient.module.hud.ItemCounterHud.class);
        disable(ctx, net.evadeclient.module.hud.SaturationHud.class);
        disable(ctx, net.evadeclient.module.hud.StopwatchHud.class);
        check(count == 64, "Item Counter counts 64 blocks (" + count + ")");
        check(sat > 6 && sat <= 7.5f, "Saturation reads the current value (" + sat + ")");
        check(elapsed > 500, "Stopwatch counts up while on (" + elapsed + " ms)");

        // Server Address trimming (pure helpers).
        check("play.example.net".equals(net.evadeclient.module.hud.ServerHud.withoutPort("play.example.net:25565"))
                        && "[::1]".equals(net.evadeclient.module.hud.ServerHud.withoutPort("[::1]:25565"))
                        && "2001:db8::1".equals(net.evadeclient.module.hud.ServerHud.withoutPort("2001:db8::1"))
                        && "hypixel.net".equals(net.evadeclient.module.hud.ServerHud.withoutPrefix("mc.hypixel.net"))
                        && "play.net".equals(net.evadeclient.module.hud.ServerHud.withoutPrefix("play.net"))
                        && "192.168.0.1".equals(net.evadeclient.module.hud.ServerHud.withoutPrefix("192.168.0.1")),
                "Server Address drops ports and mc. prefixes, never mangles IPs");

        // Saturation formats: Of Cap shows saturation over the food level that caps it.
        var satHud = enable(ctx, net.evadeclient.module.hud.SaturationHud.class);
        String ofCap = ctx.computeOnClient(c -> {
            setJson(satHud, "Format", "Of Cap");
            setJson(satHud, "Hide In Creative", false);
            setJson(satHud, "Food Preview", "Off");
            setJson(satHud, "Sync Mode", "Server Only");
            var hunger = c.player.getHungerManager();
            int oldFood = hunger.getFoodLevel();
            float oldSat = hunger.getSaturationLevel();
            hunger.setFoodLevel(18);
            hunger.setSaturationLevel(7.5f);
            var lines = satHud.textForTest();
            hunger.setFoodLevel(oldFood);
            hunger.setSaturationLevel(oldSat);
            for (String n : new String[]{"Format", "Hide In Creative", "Food Preview", "Sync Mode"}) setting(satHud, n).reset();
            return lines.isEmpty() ? "" : lines.get(0);
        });
        disable(ctx, net.evadeclient.module.hud.SaturationHud.class);
        check("7.5 / 18".equals(ofCap), "Saturation Of Cap reads \"7.5 / 18\" (" + ofCap + ")");

        // Pack Display: the top-pack line is labelled.
        var packHud = enable(ctx, net.evadeclient.module.hud.PackDisplayHud.class);
        var packLines = ctx.computeOnClient(c -> packHud.textForTest());
        disable(ctx, net.evadeclient.module.hud.PackDisplayHud.class);
        check(!packLines.isEmpty() && packLines.get(0).startsWith("Pack: "), "Pack Display shows a labelled top pack (" + packLines + ")");

        // Combo Counter: only hits the server says you caused count, one per tick; taking damage ends the combo.
        var combo = enable(ctx, net.evadeclient.module.hud.ComboHud.class);
        ctx.runOnClient(c -> {
            int self = c.player.getId();
            var type = c.world.getDamageSources().playerAttack(c.player).getTypeRegistryEntry();
            combo.onServerPacket(new net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket(424242, type, self, self, java.util.Optional.empty()));
            // Same tick: a sweep's second victim, and a hit someone else landed.
            combo.onServerPacket(new net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket(424243, type, self, self, java.util.Optional.empty()));
            combo.onServerPacket(new net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket(424242, type, 999999, 999999, java.util.Optional.empty()));
        });
        ctx.waitTicks(1);
        ctx.runOnClient(c -> {
            int self = c.player.getId();
            var type = c.world.getDamageSources().playerAttack(c.player).getTypeRegistryEntry();
            combo.onServerPacket(new net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket(424242, type, self, self, java.util.Optional.empty()));
        });
        int comboHits = ctx.computeOnClient(c -> combo.combo());
        ctx.runOnClient(c -> {
            var type = c.world.getDamageSources().playerAttack(c.player).getTypeRegistryEntry();
            combo.onServerPacket(new net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket(c.player.getId(), type, -1, -1, java.util.Optional.empty()));
        });
        int comboAfterHurt = ctx.computeOnClient(c -> combo.combo());
        int comboBest = ctx.computeOnClient(c -> combo.best());
        disable(ctx, net.evadeclient.module.hud.ComboHud.class);
        check(comboHits == 2, "Combo Counter counts confirmed hits once per tick (" + comboHits + ")");
        check(comboAfterHurt == 0 && comboBest == 2, "Combo Counter ends when you are hurt and keeps the best (" + comboAfterHurt + ", best " + comboBest + ")");

        // Clock formats and old-config migration; Coordinates layout, decimals and the .coords command.
        String clockCoords = ctx.computeOnClient(c -> {
            var clock = EvadeClient.modules.get(net.evadeclient.module.hud.ClockHud.class);
            var coords = EvadeClient.modules.get(net.evadeclient.module.hud.CoordsHud.class);
            StringBuilder p = new StringBuilder();
            List<String> clockTouched = List.of("Format", "Custom Pattern", "Second Zone", "Game Time", "Game Time Style", "Show Day", "Leading Zero");
            try {
                setJson(clock, "Format", "Custom");
                setJson(clock, "Custom Pattern", "EEE {");
                if (!clock.textForTest().get(0).equals("Invalid pattern")) p.append("bad pattern ").append(clock.textForTest()).append("; ");
                setJson(clock, "Custom Pattern", "'T'HH");
                if (!clock.textForTest().get(0).matches("T\\d\\d")) p.append("custom pattern ").append(clock.textForTest()).append("; ");
                setJson(clock, "Format", "24h");
                setJson(clock, "Second Zone", "UTC");
                if (clock.textForTest().stream().noneMatch(l -> l.matches("UTC \\d{1,2}:\\d\\d"))) p.append("second zone ").append(clock.textForTest()).append("; ");
                setJson(clock, "Game Time", "Replace");
                setJson(clock, "Game Time Style", "Ticks");
                if (!clock.textForTest().get(0).matches("\\d{1,5} ticks.*")) p.append("game ticks ").append(clock.textForTest()).append("; ");
                clockTouched.forEach(n -> setting(clock, n).reset());
                setJson(clock, "Format", "24h");
                var old = new com.google.gson.JsonObject();
                old.addProperty("Format", "24h");
                old.addProperty("Source", "In-game");
                old.addProperty("Show Date", false);
                clock.onConfigLoaded(old);
                if (!"Replace".equals(setting(clock, "Game Time").get())) p.append("Source In-game didn't migrate; ");
                if (!Boolean.FALSE.equals(setting(clock, "Show Day").get())) p.append("Show Date off didn't migrate; ");
                if (!Boolean.TRUE.equals(setting(clock, "Leading Zero").get())) p.append("24h lost its leading zero; ");
            } finally {
                clockTouched.forEach(n -> setting(clock, n).reset());
            }
            try {
                var bp = c.player.getBlockPos();
                // .coords copy: the clipboard may be unavailable on a headless display; check it only when it works.
                EvadeClient.commands.handleChat(".coords copy");
                String clip = c.keyboard.getClipboard();
                if (!clip.isEmpty() && !clip.equals(bp.getX() + " " + bp.getY() + " " + bp.getZ())) p.append(".coords copy gave ").append(clip).append("; ");
                setJson(coords, "Decimals", 0.0);
                setJson(coords, "Layout", "Vertical");
                var lines = coords.textForTest();
                if (!lines.get(0).equals("X " + bp.getX()) || !lines.get(2).equals("Z " + bp.getZ())) p.append("vertical ").append(lines).append("; ");
            } finally {
                for (String n : new String[]{"Copy Format", "Decimals", "Layout"}) setting(coords, n).reset();
            }
            return p.toString();
        });
        check(clockCoords.isEmpty(), "Clock formats and migration, Coordinates layout and .coords (" + clockCoords + ")");
    }

    /** Item Counter rows, stack format and change indicator; Keystrokes bind labels. */
    private void testItemHuds(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("clear @a");
        server.runCommand("item replace entity @a hotbar.0 with arrow 64");
        server.runCommand("item replace entity @a hotbar.1 with arrow 64");
        server.runCommand("item replace entity @a hotbar.2 with arrow 64");
        server.runCommand("item replace entity @a inventory.0 with arrow 8");
        server.runCommand("item replace entity @a hotbar.3 with ender_pearl 16");
        ctx.waitTicks(5);
        var counter = enable(ctx, net.evadeclient.module.hud.ItemCounterHud.class);
        ctx.runOnClient(c -> {
            setJson(counter, "Track", "Arrows");
            setJson(counter, "Track 2", "Pinned");
            setJson(counter, "Track 3", "Off");
            setJson(counter, "Pinned Item", "minecraft:ender_pearl");
            setJson(counter, "Display", "Text");
            setJson(counter, "Count Format", "Stacks + Items");
        });
        ctx.waitTicks(3);
        List<String> stacked = ctx.computeOnClient(c -> counter.textForTest());
        check(stacked.equals(List.of("3s 8 Arrows", "1s Ender Pearl")), "Item Counter rows in stacks " + stacked);

        ctx.runOnClient(c -> setJson(counter, "Count Format", "Items"));
        ctx.waitTicks(3);
        server.runCommand("clear @a arrow 10");
        ctx.waitTicks(5);
        List<String> changed = ctx.computeOnClient(c -> counter.textForTest());
        check(!changed.isEmpty() && changed.get(0).equals("190x Arrows -10"), "Item Counter shows the change " + changed);
        ctx.runOnClient(c -> { for (var s : counter.getSettings()) if (!s.isHidden()) s.reset(); });
        disable(ctx, net.evadeclient.module.hud.ItemCounterHud.class);

        String sneak = ctx.computeOnClient(c -> net.evadeclient.module.hud.KeystrokesHud.bindLabel(c.options.sneakKey));
        String attack = ctx.computeOnClient(c -> net.evadeclient.module.hud.KeystrokesHud.bindLabel(c.options.attackKey));
        String forward = ctx.computeOnClient(c -> net.evadeclient.module.hud.KeystrokesHud.bindLabel(c.options.forwardKey));
        check(sneak.equals("LShift") && attack.equals("LMB") && forward.equals("W"),
                "Keystrokes labels binds (" + sneak + ", " + attack + ", " + forward + ")");
        server.runCommand("clear @a");
    }

    /**
     * Evade's own HUD cost: with every HUD element on, it should stay a small slice of a frame. Then every text
     * element's readout is checked while they are all on, and the Frame Profiler on its own.
     */
    private void testHudCost(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        // Something in hand, so Item Counter (Held Item, Hide When Zero) has a row too.
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with cobblestone 32");
        String profiler = "Frame Profiler";
        // The Frame Profiler reads Evade's HUD time itself, so it stays out of the measurement.
        boolean profilerWas = ctx.computeOnClient(c -> {
            var p = EvadeClient.modules.get(net.evadeclient.module.hud.ProfilerHud.class);
            boolean was = p.isEnabled();
            p.setEnabled(false);
            return was;
        });
        List<String> turnedOn = ctx.computeOnClient(c -> {
            List<String> on = new ArrayList<>();
            for (var el : EvadeClient.modules.hudElements())
                if (!el.isEnabled() && !el.getName().equals(profiler)) { el.setEnabled(true); on.add(el.getName()); }
            return on;
        });
        int all = ctx.computeOnClient(c -> EvadeClient.modules.hudElements().size() - 1);
        ctx.waitTicks(20);
        ctx.runOnClient(c -> { net.evadeclient.util.FrameStats.takeHudMicros(); net.evadeclient.hud.Hud.PROFILE = new java.util.HashMap<>(); });
        ctx.waitTicks(100);
        double us = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
        String top = ctx.computeOnClient(c -> {
            var p = net.evadeclient.hud.Hud.PROFILE; net.evadeclient.hud.Hud.PROFILE = null;
            long total = p.entrySet().stream().filter(e -> !e.getKey().startsWith("__")).mapToLong(java.util.Map.Entry::getValue).sum();
            return p.entrySet().stream().filter(e -> !e.getKey().startsWith("__")).sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                    .map(e -> e.getKey() + " " + (100 * e.getValue() / Math.max(1, total)) + "%").reduce((a, b) -> a + ", " + b).orElse("");
        });
        EvadeClient.LOG.info("[gametest] HUDPROFILE {}", top);

        // Every text readout, read while they are all on.
        String readouts = ctx.computeOnClient(c -> {
            StringBuilder p = new StringBuilder();
            for (var el : EvadeClient.modules.hudElements()) {
                if (!(el instanceof net.evadeclient.module.TextHudModule t) || el instanceof net.evadeclient.module.mods.ToggleSprint
                        || el.getName().equals(profiler)) continue;
                if (t.textForTest().isEmpty()) p.append(el.getName()).append(" is empty; ");
            }
            var pos = c.player.getEntityPos();
            String coords = EvadeClient.modules.get(net.evadeclient.module.hud.CoordsHud.class).textForTest().get(0);
            String want = String.format(Locale.ROOT, "X %.1f  Y %.1f  Z %.1f", pos.x, pos.y, pos.z);
            if (!coords.equals(want)) p.append("Coordinates \"").append(coords).append("\" for \"").append(want).append("\"; ");
            String fps = EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).textForTest().get(0);
            if (!fps.matches("\\d+ FPS") || fps.startsWith("0 ")) p.append("FPS \"").append(fps).append("\"; ");
            String ping = EvadeClient.modules.get(net.evadeclient.module.hud.PingHud.class).textForTest().get(0);
            if (!ping.equals("Local")) p.append("Ping \"").append(ping).append("\"; ");
            String mem = EvadeClient.modules.get(net.evadeclient.module.hud.MemoryHud.class).textForTest().get(0);
            if (!mem.matches("Mem \\d+% (\\d+/\\d+MB|\\d+\\.\\d/\\d+\\.\\dGB)")) p.append("Memory \"").append(mem).append("\"; ");
            String pack = EvadeClient.modules.get(net.evadeclient.module.hud.PackDisplayHud.class).textForTest().get(0);
            if (!pack.startsWith("Pack: ")) p.append("Pack \"").append(pack).append("\"; ");
            return p.toString();
        });

        StringBuilder ab = new StringBuilder();
        for (int round = 0; round < 2; round++) for (boolean noCache : new boolean[]{true, false}) {
            ctx.runOnClient(c -> { net.evadeclient.module.TextHudModule.NO_CACHE = noCache; net.evadeclient.util.FrameStats.takeHudMicros(); });
            ctx.waitTicks(80);
            double t = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
            ab.append(noCache ? " everyFrame=" : " perTick=").append(String.format(Locale.ROOT, "%.0fus", t));
        }
        ctx.runOnClient(c -> net.evadeclient.module.TextHudModule.NO_CACHE = false);
        EvadeClient.LOG.info("[gametest] CACHEAB{}", ab);
        ctx.runOnClient(c -> { for (var el : EvadeClient.modules.hudElements()) if (turnedOn.contains(el.getName())) el.setEnabled(false); });
        EvadeClient.LOG.info("[gametest] HUDCOST {} HUD elements on: {} us per frame", all, String.format(Locale.ROOT, "%.0f", us));
        check(us > 0 && us < 4000, String.format(Locale.ROOT, "Evade's HUD stays light with all %d elements on (%.0f us per frame)", all, us));
        check(readouts.isEmpty(), "Text HUD readouts (" + readouts + ")");

        // The Frame Profiler on its own: FPS and the 1% low over the last ten seconds.
        enable(ctx, net.evadeclient.module.hud.ProfilerHud.class);
        ctx.waitTicks(25);
        String line0 = ctx.computeOnClient(c -> {
            var lines = EvadeClient.modules.get(net.evadeclient.module.hud.ProfilerHud.class).textForTest();
            return lines.isEmpty() ? "" : lines.get(0);
        });
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.ProfilerHud.class).setEnabled(profilerWas));
        check(line0.matches("\\d+ FPS  ·  1% low \\d+"), "Frame Profiler shows FPS and its 1% low (\"" + line0 + "\")");

        // Servers that hide coordinates from F3 hide them here too.
        sp.getServer().runCommand("gamerule reduced_debug_info true");
        ctx.waitTicks(5);
        List<String> hidden = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.CoordsHud.class).textForTest());
        sp.getServer().runCommand("gamerule reduced_debug_info false");
        ctx.waitTicks(5);
        sp.getServer().runCommand("clear @a");
        check(hidden.equals(List.of("Coords hidden by server")), "Coordinates respect the server's reduced debug info (" + hidden + ")");
    }

    // ---- visuals ----------------------------------------------------------------------------------------

    /** Batch 2 visual mods: Particles, TNT Timer, Chunk Borders, Scoreboard, Boss Bar, NoRender, Pack Tweaks, Inventory Blur. */
    private void testVisualBatch(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(5);

        // Particles: crits are kept out of the world while hidden, shown once the mod is off. (The particle object is
        // still handed back to the caller either way; returning nothing crashed fireworks.)
        java.util.function.Supplier<Integer> live = () -> ctx.computeOnClient(EvadeClientGameTest::liveParticles);
        java.util.function.Supplier<Integer> crits = () -> {
            ctx.runOnClient(c -> {
                c.particleManager.clearParticles();
                for (int i = 0; i < 20; i++) c.particleManager.addParticle(net.minecraft.particle.ParticleTypes.CRIT, 0, -59, 2, 0, 0, 0);
            });
            ctx.waitTicks(1);
            return live.get();
        };
        enable(ctx, net.evadeclient.module.visuals.Particles.class);
        boolean returned = ctx.computeOnClient(c -> c.particleManager.addParticle(net.minecraft.particle.ParticleTypes.CRIT, 0, -59, 2, 0, 0, 0) != null);
        int hiddenCount = crits.get();
        disable(ctx, net.evadeclient.module.visuals.Particles.class);
        int shownCount = crits.get();
        check(returned && hiddenCount == 0 && shownCount >= 20,
                "Particles hides crits only while enabled (" + hiddenCount + " shown while hidden, " + shownCount + " after)");

        // Particles: Crit Multiplier adds bursts.
        var parts = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.Particles.class));
        enable(ctx, net.evadeclient.module.visuals.Particles.class);
        setBool(ctx, parts, "Hide Crits", false);
        setBool(ctx, parts, "Only My Hits", false);
        java.util.function.Supplier<Integer> burst = () -> {
            ctx.runOnClient(c -> { c.particleManager.clearParticles(); c.particleManager.addEmitter(c.player, net.minecraft.particle.ParticleTypes.CRIT); });
            ctx.waitTicks(3);
            return live.get();
        };
        int single = burst.get();
        ctx.runOnClient(c -> parts.critMultiplier.set(4.0));
        int quad = burst.get();
        ctx.runOnClient(c -> parts.critMultiplier.set(1.0));
        check(quad > single * 2, "Particles Crit Multiplier adds bursts (" + single + " -> " + quad + ")");
        setBool(ctx, parts, "Only My Hits", true);
        setBool(ctx, parts, "Hide Crits", true);

        // Totem pops (an emitter spawns them through the world).
        setBool(ctx, parts, "Hide Totem Pops", true);
        ctx.runOnClient(c -> { c.particleManager.clearParticles(); c.particleManager.addEmitter(c.player, net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING, 10); });
        ctx.waitTicks(5);
        int totemHidden = live.get();
        setBool(ctx, parts, "Hide Totem Pops", false);
        ctx.runOnClient(c -> { c.particleManager.clearParticles(); c.particleManager.addEmitter(c.player, net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING, 10); });
        ctx.waitTicks(5);
        int totemShown = live.get();
        check(totemHidden == 0 && totemShown > 0, "Particles hides totem pops (" + totemHidden + " vs " + totemShown + ")");
        // Mining cracks.
        java.util.function.Supplier<Integer> mine = () -> {
            ctx.runOnClient(c -> {
                c.particleManager.clearParticles();
                var pos = c.player.getBlockPos().down();
                for (int i = 0; i < 20; i++) c.world.spawnBlockBreakingParticle(pos, net.minecraft.util.math.Direction.UP);
            });
            ctx.waitTicks(1);
            return live.get();
        };
        setBool(ctx, parts, "Hide Mining Particles", true);
        int mineHidden = mine.get();
        setBool(ctx, parts, "Hide Mining Particles", false);
        int mineShown = mine.get();
        check(mineHidden == 0 && mineShown >= 20, "Particles hides mining cracks (" + mineHidden + " vs " + mineShown + ")");
        // Fireworks: sparks and flash hidden, only the silent starter remains.
        setBool(ctx, parts, "Hide Fireworks", true);
        ctx.runOnClient(c -> {
            c.particleManager.clearParticles();
            var p = c.player.getEntityPos();
            c.world.addFireworkParticle(p.x, p.y + 3, p.z + 4, 0, 0, 0, List.of(new net.minecraft.component.type.FireworkExplosionComponent(
                    net.minecraft.component.type.FireworkExplosionComponent.Type.LARGE_BALL, it.unimi.dsi.fastutil.ints.IntList.of(0x00FF00),
                    it.unimi.dsi.fastutil.ints.IntList.of(), true, false)));
        });
        ctx.waitTicks(5);
        int fwHidden = live.get();
        setBool(ctx, parts, "Hide Fireworks", false);
        check(fwHidden <= 1, "Particles hides fireworks without crashing (" + fwHidden + " live)");
        disable(ctx, net.evadeclient.module.visuals.Particles.class);

        // TNT Timer: a primed TNT in front of you gets a countdown, but never through a wall.
        server.runCommand("summon tnt 0 -60 5 {fuse:200}");
        ctx.waitTicks(10);
        int timers = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.TntTimer.class).shown());
        check(timers >= 1, "TNT Timer shows a countdown over primed TNT (" + timers + ")");
        server.runCommand("fill -2 -60 3 2 -56 3 stone");
        ctx.waitTicks(3);
        int walled = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.TntTimer.class).shown());
        server.runCommand("fill -2 -60 3 2 -56 3 air");
        ctx.waitTicks(3);
        int inView = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.TntTimer.class).shown());
        check(walled == 0 && inView >= 1, "TNT Timer hides TNT behind a wall (" + walled + " walled, " + inView + " in view)");
        // Primed TNT minecarts too (a cart is only primed on the client when the server primes it).
        // A redstone block underneath keeps the rail powered (a bare powered=true rail turns itself off on placement).
        server.runCommand("setblock 1 -61 6 redstone_block");
        server.runCommand("setblock 1 -60 6 minecraft:activator_rail");
        server.runCommand("summon tnt_minecart 1.5 -60 6.5");
        ctx.waitTicks(10);
        int cartTimers = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.TntTimer.class).shown());
        server.runCommand("kill @e[type=tnt_minecart]");
        server.runCommand("setblock 1 -60 6 air");
        server.runCommand("setblock 1 -61 6 grass_block");
        check(cartTimers >= 2, "TNT Timer also times primed TNT minecarts (" + cartTimers + " with the TNT)");

        // Chunk Borders draws its grid.
        enable(ctx, net.evadeclient.module.visuals.ChunkBorders.class);
        ctx.waitTicks(3);
        int lines = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.ChunkBorders.class).lines());
        check(lines > 50, "Chunk Borders draws the chunk grid (" + lines + " lines)");

        // Scoreboard (numbers hidden) + boss bar on screen, then a screenshot of it all.
        server.runCommand("scoreboard objectives add evade dummy \"Evade\"");
        server.runCommand("scoreboard objectives setdisplay sidebar evade");
        server.runCommand("scoreboard players set Kills evade 12");
        server.runCommand("scoreboard players set Coins evade 340");
        server.runCommand("bossbar add evade:test \"Ender Dragon\"");
        server.runCommand("bossbar set evade:test players @a");
        enable(ctx, net.evadeclient.module.visuals.Scoreboard.class);
        enable(ctx, net.evadeclient.module.visuals.BossBar.class);
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-batch2");
        disable(ctx, net.evadeclient.module.visuals.ChunkBorders.class);

        // Scoreboard is a HUD element drawn by Evade: sized to the sidebar, Max Lines, Numbers (Auto), Hide Server IP Line.
        var SB = net.evadeclient.module.visuals.Scoreboard.class;
        int[] sb = ctx.computeOnClient(c -> {
            var m = EvadeClient.modules.get(SB);
            return new int[]{m.sidebarShown() ? 1 : 0, m.getWidth(), m.getHeight(), m.shownLines()};
        });
        check(sb[0] == 1 && sb[1] > 10 && sb[2] >= 19 && sb[3] == 2,
                "Scoreboard draws the sidebar itself (shown " + sb[0] + ", " + sb[1] + "x" + sb[2] + ", " + sb[3] + " lines)");
        ctx.runOnClient(c -> { var m = EvadeClient.modules.get(SB); m.x = 4; m.y = 4; });
        ctx.waitTicks(3);
        ctx.takeScreenshot("evade-scoreboard-moved");
        ctx.runOnClient(c -> EvadeClient.modules.get(SB).resetPosition());
        server.runCommand("scoreboard players reset Kills evade");
        server.runCommand("scoreboard players reset Coins evade");
        server.runCommand("scoreboard players set Alpha evade 3");
        server.runCommand("scoreboard players set Beta evade 2");
        server.runCommand("scoreboard players set play.example.net evade 1");
        ctx.runOnClient(c -> EvadeClient.modules.get(SB).numbers.set("Auto"));
        ctx.waitTicks(3);
        boolean autoHides = !ctx.computeOnClient(c -> EvadeClient.modules.get(SB).numbersDrawn());
        server.runCommand("scoreboard players set Beta evade 7");
        ctx.waitTicks(3);
        boolean autoShows = ctx.computeOnClient(c -> EvadeClient.modules.get(SB).numbersDrawn());
        ctx.runOnClient(c -> EvadeClient.modules.get(SB).hideServerIp.set(true));
        ctx.waitTicks(3);
        int withoutIp = ctx.computeOnClient(c -> EvadeClient.modules.get(SB).shownLines());
        ctx.runOnClient(c -> { var m = EvadeClient.modules.get(SB); m.hideServerIp.set(false); m.maxLines.set(1.0); });
        ctx.waitTicks(3);
        int trimmed = ctx.computeOnClient(c -> EvadeClient.modules.get(SB).shownLines());
        ctx.runOnClient(c -> { var m = EvadeClient.modules.get(SB); m.maxLines.reset(); m.numbers.reset(); });
        check(autoHides && autoShows && withoutIp == 2 && trimmed == 1,
                "Scoreboard Numbers Auto, Hide Server IP Line and Max Lines (" + autoHides + ", " + autoShows + ", " + withoutIp + ", " + trimmed + ")");

        // Boss Bar Max Bars: three bars, at most two drawn.
        server.runCommand("bossbar add evade:test2 \"Wither\"");
        server.runCommand("bossbar set evade:test2 players @a");
        server.runCommand("bossbar add evade:test3 \"Elder Guardian\"");
        server.runCommand("bossbar set evade:test3 players @a");
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.BossBar.class).maxBars.set(2.0));
        ctx.waitTicks(3);
        int bars = ctx.computeOnClient(c -> net.evadeclient.module.visuals.BossBar.shownBars(3, c.getWindow().getScaledHeight()));
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.BossBar.class).maxBars.reset());
        server.runCommand("bossbar remove evade:test2");
        server.runCommand("bossbar remove evade:test3");
        check(bars == 2, "Boss Bar Max Bars caps the bars drawn (" + bars + " of 3)");
        server.runCommand("kill @e[type=tnt]");
        server.runCommand("bossbar remove evade:test");
        server.runCommand("scoreboard objectives remove evade");
        disable(ctx, net.evadeclient.module.visuals.Scoreboard.class);
        disable(ctx, net.evadeclient.module.visuals.BossBar.class);

        // NoRender toast filter: tutorial toasts dropped, system toasts kept. Pack Tweaks overlay percentages.
        boolean noRenderWas = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.NoRender.class).isEnabled());
        enable(ctx, net.evadeclient.module.visuals.NoRender.class);
        boolean[] toasts = ctx.computeOnClient(c -> {
            var tm = c.getToastManager();
            tm.add(new net.minecraft.client.toast.TutorialToast(c.textRenderer,
                    net.minecraft.client.toast.TutorialToast.Type.MOVEMENT_KEYS, net.minecraft.text.Text.literal("Move"), null, false));
            net.minecraft.client.toast.SystemToast.add(tm, net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION,
                    net.minecraft.text.Text.literal("Evade"), net.minecraft.text.Text.literal("test"));
            boolean[] r = {
                    tm.getToast(net.minecraft.client.toast.TutorialToast.class, net.minecraft.client.toast.Toast.TYPE) == null,
                    tm.getToast(net.minecraft.client.toast.SystemToast.class, net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION) != null};
            tm.clear();
            return r;
        });
        check(toasts[0] && toasts[1], "NoRender drops tutorial toasts and keeps system toasts");
        enable(ctx, net.evadeclient.module.visuals.PackTweaks.class);
        int pumpkin = ctx.computeOnClient(c -> net.evadeclient.module.visuals.PackTweaks.overlayPercent(
                net.minecraft.util.Identifier.ofVanilla("textures/misc/pumpkinblur.png")));
        int frost = ctx.computeOnClient(c -> net.evadeclient.module.visuals.PackTweaks.overlayPercent(
                net.minecraft.util.Identifier.ofVanilla("textures/misc/powder_snow_outline.png")));
        disable(ctx, net.evadeclient.module.visuals.PackTweaks.class);
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.NoRender.class).setEnabled(noRenderWas));
        check(pumpkin == 0 && frost == 100, "Pack Tweaks overlay % (pumpkin " + pumpkin + ", frost " + frost + ")");

        // Inventory Blur: its own strength in inventories, only for the screen groups it is set to.
        var blur = enable(ctx, net.evadeclient.module.visuals.InventoryBlur.class);
        ctx.runOnClient(c -> blur.strength.set(7.0));
        ctx.setScreen(() -> new net.minecraft.client.gui.screen.ingame.InventoryScreen(net.minecraft.client.MinecraftClient.getInstance().player));
        ctx.waitTicks(5);
        int radius = ctx.computeOnClient(c -> net.evadeclient.module.visuals.InventoryBlur.blurRadius(0));
        ctx.takeScreenshot("evade-inventory-blur");
        ctx.setScreen(() -> null);
        boolean groups = ctx.computeOnClient(c -> {
            var inv = new net.minecraft.client.gui.screen.ingame.InventoryScreen(c.player);
            var book = new net.minecraft.client.gui.screen.ingame.BookScreen(net.minecraft.client.gui.screen.ingame.BookScreen.EMPTY_PROVIDER);
            boolean ok = net.evadeclient.module.visuals.InventoryBlur.appliesTo(inv)
                    && !net.evadeclient.module.visuals.InventoryBlur.appliesTo(book);
            blur.booksAndSigns.set(true);
            ok &= net.evadeclient.module.visuals.InventoryBlur.appliesTo(book);
            blur.playerInventory.set(false);
            ok &= !net.evadeclient.module.visuals.InventoryBlur.appliesTo(inv);
            blur.booksAndSigns.reset();
            blur.playerInventory.reset();
            blur.strength.reset();
            return ok;
        });
        disable(ctx, net.evadeclient.module.visuals.InventoryBlur.class);
        check(radius == 7, "Inventory Blur uses its own strength in inventories (" + radius + ")");
        check(groups, "Inventory Blur blurs only the screen groups it's set to");
    }

    /** Deeper render mods: Nick Hider, Tab Tweaks, Nametags, Hit Color, ShinyPots, EnchantGlint, tooltips, Hitboxes, 1.7 Animations. */
    private void testRenderBatch(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 30");
        ctx.waitTicks(3);

        // Nick Hider: your real name is swapped wherever it's drawn.
        String shown = ctx.computeOnClient(c -> {
            var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
            nh.nick.set("Streamer");
            nh.setEnabled(true);
            StringBuilder b = new StringBuilder();
            net.minecraft.text.Text.literal("<" + c.getSession().getUsername() + "> hi").asOrderedText()
                    .accept((i, style, cp) -> { b.appendCodePoint(cp); return true; });
            return b.toString();
        });
        check(shown.equals("<Streamer> hi"), "Nick Hider replaces your name on screen (" + shown + ")");
        disable(ctx, net.evadeclient.module.visuals.NickHider.class);

        // Nick Hider: a 300-character line wraps around the nickname, short or 16 characters long, without showing the
        // real name, splitting the nickname across lines, touching longer names, or throwing.
        String wrapProblems = ctx.computeOnClient(c -> {
            var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
            String real = c.getSession().getUsername();
            var whole = java.util.regex.Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(real) + "(?![A-Za-z0-9_])");
            StringBuilder raw = new StringBuilder();
            while (raw.length() < 300) raw.append("gg ").append(real).append(" vs ")
                    .append(real.toUpperCase(Locale.ROOT)).append(" and ").append(real).append("_fan ok ");
            String line = raw.substring(0, 300);
            int expected = 0;
            for (var m = whole.matcher(line); m.find(); ) expected++;
            StringBuilder problems = new StringBuilder();
            nh.setEnabled(true);
            for (String nick : new String[]{"You", "ABCDEFGHIJKLMNOP"}) {
                nh.nick.set(nick);
                try {
                    var tr = c.textRenderer;
                    List<net.minecraft.text.OrderedText> wrapped = tr.wrapLines(net.minecraft.text.Text.literal(line), 120);
                    tr.wrapLines(net.minecraft.text.StringVisitable.plain(line), 120);
                    tr.getTextHandler().wrapLines(line, 120, net.minecraft.text.Style.EMPTY);
                    tr.trimToWidth(net.minecraft.text.Text.literal(line), 50);
                    tr.trimToWidth(line, 50);
                    tr.getWidth(line);
                    StringBuilder all = new StringBuilder();
                    for (var l : wrapped) {
                        l.accept((i, st, cp) -> { all.appendCodePoint(cp); return true; });
                        all.append('\n');
                    }
                    String s = all.toString();
                    if (whole.matcher(s).find()) problems.append(nick).append(": real name visible; ");
                    int nicks = 0;
                    for (int at = s.indexOf(nick); at >= 0; at = s.indexOf(nick, at + nick.length())) nicks++;
                    if (nicks != expected) problems.append(nick).append(": ").append(nicks).append(" whole nicknames for ").append(expected).append(" names; ");
                    if (!s.contains(real + "_fan")) problems.append(nick).append(": a longer name was changed; ");
                } catch (RuntimeException e) {
                    problems.append(nick).append(": ").append(e).append("; ");
                }
            }
            nh.nick.set("You");
            nh.setEnabled(false);
            return problems.toString();
        });
        check(wrapProblems.isEmpty(), "Nick Hider wraps long lines around the nickname (" + wrapProblems + ")");

        // Nick Hider skins: Own Skin modes, Hide Cape and Hide Others' Skins on your own tab-list entry.
        String skinProblems = ctx.computeOnClient(c -> {
            var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
            var id = c.player.getUuid();
            var entry = c.getNetworkHandler().getPlayerListEntry(id);
            if (entry == null) return "no tab-list entry for you";
            StringBuilder problems = new StringBuilder();
            nh.setEnabled(true);
            nh.ownSkin.set("Default");
            if (!entry.getSkinTextures().equals(net.minecraft.client.util.DefaultSkinHelper.getSkinTextures(id)))
                problems.append("Default isn't the default skin; ");
            nh.ownSkin.set("Steve");
            var steve = entry.getSkinTextures();
            if (!steve.body().texturePath().getPath().equals("textures/entity/player/wide/steve.png")
                    || steve.model() != net.minecraft.entity.player.PlayerSkinType.WIDE || steve.cape() != null)
                problems.append("Steve is ").append(steve).append("; ");
            nh.ownSkin.set("Alex");
            var alex = entry.getSkinTextures();
            if (!alex.body().texturePath().getPath().equals("textures/entity/player/slim/alex.png")
                    || alex.model() != net.minecraft.entity.player.PlayerSkinType.SLIM)
                problems.append("Alex is ").append(alex).append("; ");
            nh.ownSkin.set("Custom File");
            var custom = entry.getSkinTextures();
            boolean fileThere = java.nio.file.Files.isRegularFile(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("evade").resolve("skin.png"));
            if (!fileThere && !custom.equals(net.minecraft.client.util.DefaultSkinHelper.getSkinTextures(id)))
                problems.append("Custom File without skin.png isn't a default skin; ");
            nh.ownSkin.set("Real");
            nh.hideCape.set(true);
            var capeless = entry.getSkinTextures();
            if (capeless.cape() != null || capeless.elytra() != null) problems.append("Hide Cape kept a cape; ");
            nh.hideCape.set(false);
            nh.hideOthersSkins.set(true);
            nh.setEnabled(false);
            var real = entry.getSkinTextures();
            nh.setEnabled(true);
            if (!entry.getSkinTextures().equals(real)) problems.append("Hide Others' Skins changed your skin; ");
            nh.hideOthersSkins.set(false);
            var old = new com.google.gson.JsonObject();
            old.addProperty("Hide Skin", true);
            nh.onConfigLoaded(old);
            if (!nh.ownSkin.is("Default")) problems.append("Hide Skin on didn't migrate to Default; ");
            old.addProperty("Hide Skin", false);
            nh.onConfigLoaded(old);
            if (!nh.ownSkin.is("Real")) problems.append("Hide Skin off didn't migrate to Real; ");
            nh.ownSkin.reset();
            nh.setEnabled(false);
            return problems.toString();
        });
        check(skinProblems.isEmpty(), "Nick Hider skin options (" + skinProblems + ")");

        // Nick Hider matching rules and other players' aliases (pure: no other players needed).
        String matchProblems = ctx.computeOnClient(c -> {
            java.util.Map<String, String> others = new java.util.LinkedHashMap<>();
            others.put("Steve", "Player 1");
            others.put("Alex_99", "Player 2");
            others.put(".Bedrock", "Player 3");
            String[][] cases = {
                    {"gg notch and NOTCH", "true", "true", "gg You and You"},
                    {"gg notch and Notch", "false", "true", "gg notch and You"},
                    {"Notch_fan xNotch Notch2 Notch", "true", "true", "Notch_fan xNotch Notch2 You"},
                    {"Notchy", "true", "false", "Youy"},
                    {"§aNotch§r: hi", "true", "true", "§aYou§r: hi"},
                    {"Steve killed Alex_99 and Notch", "true", "true", "Player 1 killed Player 2 and You"},
                    {"STEVE Steve_x .Bedrock", "true", "true", "Player 1 Steve_x Player 3"},
                    {"steve Steve", "false", "true", "steve Player 1"},
            };
            StringBuilder problems = new StringBuilder();
            for (String[] t : cases) {
                String got = net.evadeclient.module.visuals.NickHider.swapText(t[0], "Notch", "You",
                        Boolean.parseBoolean(t[1]), Boolean.parseBoolean(t[2]), others);
                if (!got.equals(t[3])) problems.append(t[0]).append(" -> ").append(got).append("; ");
            }
            var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
            nh.hideOthers.set(true);
            nh.nick.set("Streamer");
            nh.setEnabled(true);
            String own = net.evadeclient.module.visuals.NickHider.replace("hi " + c.getSession().getUsername());
            if (!own.equals("hi Streamer")) problems.append("own name with Hide Others: ").append(own).append("; ");
            if (nh.seenNames().contains(c.getSession().getUsername())) problems.append("you were aliased; ");
            nh.hideOthers.set(false);
            nh.nick.set("You");
            nh.setEnabled(false);
            return problems.toString();
        });
        check(matchProblems.isEmpty(), "Nick Hider matching and aliases (" + matchProblems + ")");

        // Tab Tweaks: ping text and colours.
        check(net.evadeclient.module.visuals.TabTweaks.pingText(87).equals("87ms")
                        && net.evadeclient.module.visuals.TabTweaks.pingText(12_345).equals("12s")
                        && net.evadeclient.module.visuals.TabTweaks.pingColor(100) == 0xFF55FF55
                        && net.evadeclient.module.visuals.TabTweaks.pingColor(200) == 0xFFFFFF55
                        && net.evadeclient.module.visuals.TabTweaks.pingColor(450) == 0xFFFFAA00
                        && net.evadeclient.module.visuals.TabTweaks.pingColor(900) == 0xFFFF5555,
                "Tab Tweaks formats and colours ping");

        // Nametags: your own tag shows in third person only, and a third-person frame with it renders fine.
        boolean[] ownTag = ctx.computeOnClient(c -> {
            var nt = EvadeClient.modules.get(net.evadeclient.module.visuals.Nametags.class);
            nt.setEnabled(true);
            c.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
            boolean third = net.evadeclient.module.visuals.Nametags.showOwnTag(c.player);
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
            boolean first = net.evadeclient.module.visuals.Nametags.showOwnTag(c.player);
            c.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
            nt.scale.set(1.5);
            nt.shadow.set(true);
            return new boolean[]{third, first};
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("evade-nametags-own");
        ctx.runOnClient(c -> {
            var nt = EvadeClient.modules.get(net.evadeclient.module.visuals.Nametags.class);
            nt.scale.reset();
            nt.shadow.reset();
            nt.setEnabled(false);
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
        });
        check(ownTag[0] && !ownTag[1], "Nametags shows your own tag in third person only");

        // Hit Color repaints the hurt overlay (palette blue by default), and restores red when turned off.
        enable(ctx, net.evadeclient.module.visuals.HitColor.class);
        ctx.waitTicks(3);
        int painted = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.HitColor.class).current());
        disable(ctx, net.evadeclient.module.visuals.HitColor.class);
        int restored = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.HitColor.class).current());
        check((painted & 0xFFFFFF) == 0x3D7BFF && restored == 0xB2FF0000,
                "Hit Color repaints the hurt flash and restores it (" + Integer.toHexString(painted) + " -> " + Integer.toHexString(restored) + ")");
        // Fade Out picks a fade column, players get their own rows, Tint Self off leaves you untinted.
        int[] uvs = ctx.computeOnClient(c -> {
            var hc = EvadeClient.modules.get(net.evadeclient.module.visuals.HitColor.class);
            hc.setEnabled(true);
            setMode(hc, "Animation", "Fade Out");
            var mob = new net.minecraft.client.render.entity.state.LivingEntityRenderState();
            mob.hurt = true;
            ((net.evadeclient.util.EntityVisualsDuck) mob).evade$setHurtFade(0.5f);
            var self = new net.minecraft.client.render.entity.state.PlayerEntityRenderState();
            self.hurt = true;
            self.id = c.player.getId();
            hc.tintSelf.set(false);
            int a = net.evadeclient.module.visuals.HitColor.overlay(mob, 0f);
            int b = net.evadeclient.module.visuals.HitColor.overlay(self, 0f);
            hc.tintSelf.set(true);
            int d = net.evadeclient.module.visuals.HitColor.overlay(self, 0f);
            setMode(hc, "Animation", "Static");
            hc.setEnabled(false);
            return new int[]{a, b, d};
        });
        check(uvs[0] == (8 | 3 << 16) && uvs[1] == (10 << 16) && (uvs[2] >> 16) == 7,
                "Hit Color fades by column, uses player rows and skips you with Tint Self off (" + java.util.Arrays.toString(uvs) + ")");

        // ShinyPots: potions glint only while it's on, filtered by effect type, level and kind.
        boolean[] glint = ctx.computeOnClient(c -> {
            var pot = net.minecraft.item.Items.POTION.getDefaultStack();
            pot.set(net.minecraft.component.DataComponentTypes.POTION_CONTENTS,
                    new net.minecraft.component.type.PotionContentsComponent(net.minecraft.potion.Potions.STRENGTH));
            boolean before = pot.hasGlint();
            var mod = EvadeClient.modules.get(net.evadeclient.module.visuals.ShinyPots.class);
            mod.setEnabled(true);
            boolean after = pot.hasGlint();
            var strength = net.minecraft.component.type.PotionContentsComponent.createStack(net.minecraft.item.Items.POTION, net.minecraft.potion.Potions.STRENGTH);
            var strong = net.minecraft.component.type.PotionContentsComponent.createStack(net.minecraft.item.Items.SPLASH_POTION, net.minecraft.potion.Potions.STRONG_STRENGTH);
            var poison = net.minecraft.component.type.PotionContentsComponent.createStack(net.minecraft.item.Items.POTION, net.minecraft.potion.Potions.POISON);
            var water = net.minecraft.component.type.PotionContentsComponent.createStack(net.minecraft.item.Items.POTION, net.minecraft.potion.Potions.WATER);
            boolean defaults = strength.hasGlint() && strong.hasGlint() && poison.hasGlint() && !water.hasGlint();
            mod.effectType.set("Beneficial");
            boolean beneficial = strength.hasGlint() && !poison.hasGlint();
            mod.minLevel.set(2.0);
            boolean level = !strength.hasGlint() && strong.hasGlint();
            mod.splash.set(false);
            boolean kind = !strong.hasGlint();
            mod.effectType.reset();
            mod.minLevel.reset();
            mod.splash.reset();
            mod.setEnabled(false);
            return new boolean[]{!before && after, defaults, beneficial, level, kind};
        });
        check(glint[0], "ShinyPots makes potions glint");
        check(glint[1] && glint[2] && glint[3] && glint[4], "ShinyPots filters by effect type, level and kind (" + java.util.Arrays.toString(glint) + ")");

        // BetterTooltips (on by default): shulker contents, durability and food lines; off removes them.
        Object[] tips = ctx.computeOnClient(c -> {
            var mod = EvadeClient.modules.get(net.evadeclient.module.visuals.BetterTooltips.class);
            boolean was = mod.isEnabled();
            mod.setEnabled(true);
            var tctx = net.minecraft.item.Item.TooltipContext.create(c.world);
            java.util.function.Function<ItemStack, List<String>> tip = s -> s.getTooltip(tctx, c.player,
                    net.minecraft.item.tooltip.TooltipType.BASIC).stream().map(net.minecraft.text.Text::getString).toList();
            var box = Items.SHULKER_BOX.getDefaultStack();
            box.set(net.minecraft.component.DataComponentTypes.CONTAINER,
                    net.minecraft.component.type.ContainerComponent.fromStacks(List.of(new ItemStack(Items.DIAMOND, 5))));
            var sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.setDamage(100);
            mod.shulkerDisplay.set("List");
            List<String> b = tip.apply(box);
            mod.shulkerDisplay.reset();
            List<String> s = tip.apply(sword), f = tip.apply(new ItemStack(Items.BREAD));
            mod.setEnabled(false);
            List<String> off = tip.apply(sword);
            mod.setEnabled(was);
            return new Object[]{b, s, f, off};
        });
        @SuppressWarnings("unchecked") List<String> boxTip = (List<String>) tips[0], swordTip = (List<String>) tips[1],
                foodTip = (List<String>) tips[2], offTip = (List<String>) tips[3];
        check(boxTip.stream().anyMatch(l -> l.equals("Contents (1/27 slots):")) && boxTip.stream().anyMatch(l -> l.trim().equals("5x Diamond"))
                        && swordTip.stream().anyMatch(l -> l.equals("Durability: 1461/1561"))
                        && foodTip.stream().anyMatch(l -> l.startsWith("Food: 5"))
                        && offTip.stream().noneMatch(l -> l.startsWith("Durability:")),
                "BetterTooltips adds contents, durability and food, and only while on (" + boxTip + swordTip + foodTip + ")");
        // The List view lists shulker contents once, totalled; the default Grid gives a 27-slot preview.
        Object[] btr = ctx.computeOnClient(c -> {
            var mod = EvadeClient.modules.get(net.evadeclient.module.visuals.BetterTooltips.class);
            var box = Items.SHULKER_BOX.getDefaultStack();
            box.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(
                    List.of(new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 32), new ItemStack(Items.DIRT, 5))));
            mod.shulkerDisplay.set("List");
            List<String> l = box.getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, c.player,
                    net.minecraft.item.tooltip.TooltipType.BASIC).stream().map(net.minecraft.text.Text::getString).toList();
            boolean listHasData = box.getTooltipData().isPresent();
            mod.shulkerDisplay.set("Grid");
            Object grid = box.getTooltipData().orElse(null);
            mod.shulkerDisplay.reset();
            var json = new com.google.gson.JsonObject();
            json.addProperty("Shulker Contents", false);
            json.addProperty("Food Values", false);
            mod.onConfigLoaded(json);
            String migrated = mod.shulkerDisplay.get() + "/" + mod.foodDisplay.get();
            mod.shulkerDisplay.reset();
            mod.foodDisplay.reset();
            return new Object[]{l, listHasData, grid, migrated};
        });
        @SuppressWarnings("unchecked") List<String> listTip = (List<String>) btr[0];
        long headers = listTip.stream().filter(s -> s.startsWith("Contents (3/27 slots)")).count();
        boolean stoneTotal = listTip.stream().anyMatch(s -> s.trim().equals("96x Stone"));
        boolean vanillaList = listTip.stream().anyMatch(s -> s.contains("Stone x"));
        boolean gridOk = btr[2] instanceof net.evadeclient.module.visuals.tooltip.ContainerPreviewData d
                && d.stacks().size() == 27 && d.columns() == 9 && d.slots() && d.tint() != 0;
        check(headers == 1 && stoneTotal && !vanillaList && !(Boolean) btr[1] && gridOk,
                "BetterTooltips lists shulker contents once and previews them as a 27-slot grid (" + listTip + ")");
        check("Off/Off".equals(btr[3]), "BetterTooltips migrates Shulker Contents / Food Values (" + btr[3] + ")");

        // Hitboxes filters entity types and restyles F3+B boxes (pure logic).
        boolean hb = ctx.computeOnClient(c -> {
            var h = EvadeClient.modules.get(net.evadeclient.module.visuals.Hitboxes.class);
            h.items.set(false);
            var item = new net.minecraft.entity.ItemEntity(c.world, 0, -60, 0, Items.STONE.getDefaultStack());
            boolean ok = !h.shows(item) && h.shows(c.player)
                    && h.restyle(net.evadeclient.module.visuals.Hitboxes.VANILLA_BOX).stroke() == h.boxColor.get();
            h.eyeLine.set(false);
            ok &= !h.restyle(net.evadeclient.module.visuals.Hitboxes.VANILLA_EYE).hasStroke();
            h.items.reset();
            h.eyeLine.reset();
            return ok;
        });
        check(hb, "Hitboxes filters entity types and restyles F3+B boxes");

        // EnchantGlint swaps in a recoloured glint texture, and restores the original on disable.
        enable(ctx, net.evadeclient.module.visuals.EnchantGlint.class);
        ctx.waitTicks(3);
        boolean tinted = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.EnchantGlint.class).isApplied());
        float glintMatrix = ctx.computeOnClient(c -> {
            var g = EvadeClient.modules.get(net.evadeclient.module.visuals.EnchantGlint.class);
            g.speed.set(2.0);
            g.scale.set(2.0);
            float on = net.evadeclient.module.visuals.EnchantGlint.speed() * net.evadeclient.module.visuals.EnchantGlint.patternScale();
            g.speed.reset();
            g.scale.reset();
            return on;
        });
        check(glintMatrix == 4f, "EnchantGlint Speed and Scale reach the glint matrix (" + glintMatrix + ")");

        // Item Physics + 1.7 Animations + glint, all on screen at once.
        server.runCommand("summon item 0 -60 2 {Item:{id:\"minecraft:diamond_sword\",count:1},PickupDelay:32767}");
        server.runCommand("summon item 1 -60 2.5 {Item:{id:\"minecraft:golden_apple\",count:1},PickupDelay:32767}");
        server.runCommand("summon item -1 -60 2.5 {Item:{id:\"minecraft:stone\",count:1},PickupDelay:32767}");
        server.runCommand("item replace entity @a weapon.mainhand with diamond_sword[enchantments={sharpness:5}]");
        enable(ctx, net.evadeclient.module.visuals.ItemPhysics.class);
        enable(ctx, net.evadeclient.module.visuals.OldAnimations.class);
        ctx.waitTicks(20);
        ctx.takeScreenshot("evade-render-batch");
        disable(ctx, net.evadeclient.module.visuals.EnchantGlint.class);
        ctx.waitTicks(2);
        boolean restoredGlint = ctx.computeOnClient(c -> !EvadeClient.modules.get(net.evadeclient.module.visuals.EnchantGlint.class).isApplied());
        check(tinted && restoredGlint, "EnchantGlint recolours the glint and restores it");
        disable(ctx, net.evadeclient.module.visuals.ItemPhysics.class);
        disable(ctx, net.evadeclient.module.visuals.OldAnimations.class);
        server.runCommand("kill @e[type=item]");
        server.runCommand("clear @a");

        // 1.7 Animations: clicking while eating swings your hand on your screen only (no HandSwingC2SPacket).
        server.runCommand("item replace entity @a weapon.mainhand with golden_apple 8");
        server.runCommand("effect give @a hunger 5 255 true");
        ctx.waitTicks(20);
        enable(ctx, net.evadeclient.module.visuals.OldAnimations.class);
        ctx.runOnClient(c -> {
            c.options.useKey.setPressed(true);
            c.interactionManager.interactItem(c.player, net.minecraft.util.Hand.MAIN_HAND);
        });
        ctx.waitTicks(3);
        ctx.runOnClient(c -> net.minecraft.client.option.KeyBinding.onKeyPressed(c.options.attackKey.getDefaultKey()));
        ctx.waitTicks(2);
        boolean[] swing = ctx.computeOnClient(c -> new boolean[]{c.player.isUsingItem(), c.player.handSwinging});
        boolean serverSwung = server.computeOnServer(s -> s.getPlayerManager().getPlayerList().get(0).handSwinging);
        ctx.runOnClient(c -> c.options.useKey.setPressed(false));
        disable(ctx, net.evadeclient.module.visuals.OldAnimations.class);
        server.runCommand("clear @a");
        server.runCommand("effect clear @a");
        ctx.waitTicks(5);
        check(swing[0] && swing[1] && !serverSwung,
                "1.7 Animations swings while eating without telling the server (" + swing[0] + "/" + swing[1] + "/" + serverSwung + ")");
    }

    /** Waypoints: add, here, remove and the saved file; the .wp commands; Direction HUD's old-config migration. */
    private void testWaypoints(ClientGameTestContext ctx) {
        String problems = ctx.computeOnClient(c -> {
            var wp = EvadeClient.modules.get(net.evadeclient.module.visuals.Waypoints.class);
            var cm = EvadeClient.commands;
            java.util.function.Function<String, net.evadeclient.module.visuals.Waypoints.Point> point =
                    n -> wp.here().stream().filter(p -> p.name().equals(n)).findFirst().orElse(null);
            boolean wasOn = wp.isEnabled();
            wp.setEnabled(true);
            var bp = c.player.getBlockPos();
            StringBuilder p = new StringBuilder();
            try {
                wp.add("gt", bp);
                if (point.apply("gt") == null) p.append("add/here; ");
                if (!java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("evade").resolve("waypoints.json"))) p.append("waypoints.json wasn't written; ");
                if (!wp.remove("gt") || point.apply("gt") != null) p.append("remove; ");

                // add: quoted name with a space, coordinates relative to you.
                cm.handleChat(".wp add \"Test Base\" ~ ~ ~5");
                var pt = point.apply("Test Base");
                if (pt == null || pt.x() != bp.getX() || pt.y() != bp.getY() || pt.z() != bp.getZ() + 5 || pt.created() <= 0)
                    p.append(".wp add with a quoted name and ~ coordinates gave ").append(pt).append("; ");
                // color by palette name; Waypoints.color is what the world markers and the Direction HUD draw.
                cm.handleChat(".wp color \"Test Base\" blue");
                pt = point.apply("Test Base");
                if (pt == null || net.evadeclient.module.visuals.Waypoints.color(pt) != 0xFF3D7BFF) p.append(".wp color; ");
                cm.handleChat(".wp rename \"Test Base\" \"Test Home\"");
                if (point.apply("Test Base") != null || point.apply("Test Home") == null) p.append(".wp rename; ");
                // Death names are reserved for death points.
                cm.handleChat(".wp rename \"Test Home\" Death 4");
                if (point.apply("Test Home") == null) p.append(".wp rename took a death name; ");
                // hide / show: hidden points stay saved but leave here() and visible().
                cm.handleChat(".wp hide Test Home");
                if (point.apply("Test Home") != null || wp.visible().stream().anyMatch(v -> v.point().name().equals("Test Home")))
                    p.append(".wp hide; ");
                cm.handleChat(".wp show Test Home");
                if (point.apply("Test Home") == null) p.append(".wp show; ");
                if (!wp.update("Test Home", q -> q.withColor(0)) || point.apply("Test Home") == null
                        || point.apply("Test Home").color() != 0) p.append("Waypoints.update; ");
                // target: shown (flagged) in visible(); off clears it.
                cm.handleChat(".wp target Test Home");
                if (wp.target() == null || wp.visible().stream().noneMatch(v -> v.target() && v.point().name().equals("Test Home")))
                    p.append(".wp target; ");
                cm.handleChat(".wp target off");
                if (wp.target() != null) p.append(".wp target off; ");
                cm.handleChat(".wp del Test Home");
                if (point.apply("Test Home") != null) p.append(".wp del; ");
            } finally {
                wp.remove("gt");
                wp.remove("Test Base");
                wp.remove("Test Home");
                wp.setEnabled(wasOn);
            }

            // Direction HUD: old Zoom / Show Degrees / Intercardinals map onto Field Of View / Readout / Points.
            var compass = EvadeClient.modules.get(net.evadeclient.module.hud.CompassHud.class);
            List<String> touched = List.of("Width", "Field Of View", "Readout", "Points");
            touched.forEach(n -> setting(compass, n).reset());
            var old = new com.google.gson.JsonObject();
            old.addProperty("Zoom", 2.0);
            old.addProperty("Show Degrees", false);
            old.addProperty("Intercardinals", false);
            compass.onConfigLoaded(old);
            // 180 px wide at 1.6 px/degree x zoom 2 = 56 degrees, clamped to the 60 minimum.
            if (((Number) setting(compass, "Field Of View").get()).doubleValue() != 60.0) p.append("Zoom didn't become Field Of View 60; ");
            if (!"Off".equals(setting(compass, "Readout").get())) p.append("Show Degrees off didn't become Readout Off; ");
            if (!"4 Points".equals(setting(compass, "Points").get())) p.append("Intercardinals off didn't become 4 Points; ");
            touched.forEach(n -> setting(compass, n).reset());
            return p.toString();
        });
        check(problems.isEmpty(), "Waypoints (add, here, remove, file, .wp commands) and Direction HUD migration (" + problems + ")");
    }

    // ---- performance --------------------------------------------------------------------------------------

    /** Performance: benchmark FPS with distant mobs, culling off vs on, per-kind culling, particles, caps, video settings. */
    private void testPerformance(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        // 40-60 blocks: past the culling distance set below, but inside the game's own ~70 blocks for a cow (culling
        // only counts entities the game would otherwise have drawn).
        for (int x = -20; x < 20; x += 2) for (int z = 40; z < 60; z += 2)
            server.runCommand("summon cow " + x + " -60 " + z + " {NoAI:1b,Silent:1b}");
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        ctx.waitTicks(100);
        int fpsOff = ctx.computeOnClient(c -> c.getCurrentFps());

        var perf = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Performance.class));
        setBool(ctx, perf, "Low FPS When Tabbed Out", false); // the test window may not have focus
        setBool(ctx, perf, "Lighter Video Settings", false);  // measure culling on its own
        double distance = ctx.computeOnClient(c -> perf.entityDistance.get());
        ctx.runOnClient(c -> perf.entityDistance.set(32.0));
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        ctx.runOnClient(c -> perf.takeCulled());
        ctx.waitTicks(100);
        int fpsOn = ctx.computeOnClient(c -> c.getCurrentFps());
        int culled = ctx.computeOnClient(c -> perf.takeCulled());
        ctx.runOnClient(c -> perf.entityDistance.set(distance));
        EvadeClient.LOG.info("[gametest] BENCHMARK 200 cows at 40-60 blocks, culling at 32: {} FPS off -> {} FPS on ({} entity draws skipped)", fpsOff, fpsOn, culled);
        ctx.takeScreenshot("evade-performance");
        check(culled > 0, "Performance culls distant entities (" + fpsOff + " -> " + fpsOn + " FPS)");

        // Information entities are never culled: map art, holograms and name tags.
        server.runCommand("summon item_frame 0 -59 50 {Facing:2b,Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":0}}}");
        server.runCommand("summon text_display 2 -58 50 {text:\"hologram\"}");
        server.runCommand("summon armor_stand 4 -60 50 {CustomName:\"stand\",CustomNameVisible:1b}");
        ctx.waitTicks(10);
        int[] info = ctx.computeOnClient(c -> {
            perf.entityDistance.set(16.0);
            var cam = c.gameRenderer.getCamera().getCameraPos();
            int seen = 0, culledInfo = 0;
            for (var e : c.world.getEntities()) {
                if (!(e instanceof net.minecraft.entity.decoration.ItemFrameEntity
                        || e instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity
                        || e instanceof net.minecraft.entity.decoration.ArmorStandEntity)) continue;
                seen++;
                if (perf.cullEntity(e, cam.x, cam.y, cam.z)) culledInfo++;
            }
            perf.entityDistance.set(distance);
            return new int[]{seen, culledInfo};
        });
        check(info[0] >= 3 && info[1] == 0, "Entity Culling keeps map art, holograms and name tags (" + info[0] + " seen, " + info[1] + " culled)");
        server.runCommand("kill @e[type=cow]");
        server.runCommand("kill @e[type=item_frame]");
        server.runCommand("kill @e[type=text_display]");
        server.runCommand("kill @e[type=armor_stand]");

        // Per-kind distances: a dropped item at 40 blocks is culled at Item Distance 32 while a cow at 40 is kept at
        // Entity Distance 64; an arrow is left alone while Cull Projectiles & XP is off.
        server.runCommand("summon item 0 -60 40 {Item:{id:\"minecraft:stone\",count:1},NoGravity:1b,PickupDelay:32767s,Age:-32768s}");
        server.runCommand("summon cow 4 -60 40 {NoAI:1b,Silent:1b}");
        server.runCommand("summon arrow 8 -58 40 {NoGravity:1b}");
        ctx.waitTicks(10);
        boolean[] kinds = ctx.computeOnClient(c -> {
            var cam = c.gameRenderer.getCamera().getCameraPos();
            boolean item = false, cow = true, arrow = true;
            // Vanilla draws a dropped item up to 16 blocks x this multiplier; the world renderer sets it again next
            // frame, so raising it here only makes sure vanilla would draw the item at 40.
            net.minecraft.entity.Entity.setRenderDistanceMultiplier(5.0);
            for (var e : c.world.getEntities()) {
                if (e instanceof net.minecraft.entity.ItemEntity) item = perf.cullEntity(e, cam.x, cam.y, cam.z);
                if (e instanceof net.minecraft.entity.passive.CowEntity) cow = perf.cullEntity(e, cam.x, cam.y, cam.z);
                if (e instanceof net.minecraft.entity.projectile.PersistentProjectileEntity) arrow = perf.cullEntity(e, cam.x, cam.y, cam.z);
            }
            return new boolean[]{item, cow, arrow};
        });
        check(kinds[0] && !kinds[1] && !kinds[2],
                "Performance culls by kind: far item culled, cow and arrow kept (" + java.util.Arrays.toString(kinds) + ")");
        server.runCommand("kill @e[type=!player]");

        // Adaptive Quality only changes effective values: full pressure pulls them to their floors, settings untouched.
        double[] adapt = ctx.computeOnClient(c -> {
            double entity = perf.entityDistance.get(), particles = perf.particlePercent.get();
            perf.setAdaptivePressureForTest(1.0);
            double[] r = {perf.effEntityDist(), perf.entityDistance.get() - entity, perf.effParticlePercent(), perf.particlePercent.get() - particles};
            perf.setAdaptivePressureForTest(0.0);
            return r;
        });
        check(adapt[0] == 32 && adapt[1] == 0 && adapt[2] == 25 && adapt[3] == 0,
                "Adaptive Quality tightens effective limits only (" + java.util.Arrays.toString(adapt) + ")");

        // Block entities: far ones are skipped, near ones aren't, and beams (vanilla distance over 64) never are.
        boolean be = ctx.computeOnClient(c -> perf.cullBlockEntity(new net.minecraft.util.math.BlockPos(0, -60, 90))
                && !perf.cullBlockEntity(new net.minecraft.util.math.BlockPos(0, -60, 5)));
        check(be, "Performance culls far block entities only");
        boolean beams = ctx.computeOnClient(c -> !perf.cullBlockEntity(new net.minecraft.util.math.BlockPos(0, -60, 90), 256)
                && perf.cullBlockEntity(new net.minecraft.util.math.BlockPos(0, -60, 90), 64));
        check(beams, "Block Entity Culling leaves beacon and end-gateway beams alone");

        // Lighter video settings apply on enable and restore on disable.
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Lighter Video Settings", true);
        ctx.runOnClient(c -> c.options.getCloudRenderMode().setValue(net.minecraft.client.option.CloudRenderMode.FANCY));
        var before = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        var during = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        var after = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        check(before == net.minecraft.client.option.CloudRenderMode.FANCY && during == net.minecraft.client.option.CloudRenderMode.OFF && after == before,
                "Performance's lighter settings apply and restore (" + before + " -> " + during + " -> " + after + ")");
        // ...and follow the setting live, leaving no restore file behind once they are given back.
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Lighter Video Settings", false);
        ctx.waitTicks(2);
        ctx.runOnClient(c -> c.options.getCloudRenderMode().setValue(net.minecraft.client.option.CloudRenderMode.FANCY));
        setBool(ctx, perf, "Lighter Video Settings", true);
        ctx.waitTicks(2);
        var on = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        var restoreFile = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("video-restore.json");
        boolean fileWhileOn = java.nio.file.Files.exists(restoreFile);
        setBool(ctx, perf, "Lighter Video Settings", false);
        ctx.waitTicks(2);
        var off = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        // Unlock FPS keeps its own entries in the file; the clouds entry must be gone.
        boolean fileAfter;
        try { fileAfter = java.nio.file.Files.exists(restoreFile) && java.nio.file.Files.readString(restoreFile).contains("\"clouds\""); }
        catch (java.io.IOException e) { fileAfter = true; }
        setBool(ctx, perf, "Lighter Video Settings", true);
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        check(on == net.minecraft.client.option.CloudRenderMode.OFF && off == net.minecraft.client.option.CloudRenderMode.FANCY
                && fileWhileOn && !fileAfter, "Lighter Video Settings follows its setting and cleans up (" + on + " -> " + off + ")");

        // Low-End PC Mode: applies the heavy video settings and restores them exactly.
        Object[] before2 = ctx.computeOnClient(c -> new Object[]{c.options.getAo().getValue(), c.options.getCutoutLeaves().getValue(), c.options.getBiomeBlendRadius().getValue()});
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Low-End PC Mode", true);
        ctx.waitTicks(3);
        Object[] during2 = ctx.computeOnClient(c -> new Object[]{c.options.getAo().getValue(), c.options.getCutoutLeaves().getValue(), c.options.getBiomeBlendRadius().getValue()});
        ctx.takeScreenshot("evade-lowend");
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Low-End PC Mode", false);
        Object[] after2 = ctx.computeOnClient(c -> new Object[]{c.options.getAo().getValue(), c.options.getCutoutLeaves().getValue(), c.options.getBiomeBlendRadius().getValue()});
        check(Boolean.FALSE.equals(during2[0]) && Boolean.FALSE.equals(during2[1]) && Integer.valueOf(0).equals(during2[2])
                        && java.util.Arrays.equals(before2, after2),
                "Low-End PC Mode applies and restores video settings (" + java.util.Arrays.toString(before2) + " -> "
                        + java.util.Arrays.toString(during2) + " -> " + java.util.Arrays.toString(after2) + ")");

        // Effects: ambient particles and far particles are skipped, normal near ones kept; block dust is cut down.
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        ctx.runOnClient(c -> perf.particlePercent.set(100.0)); // no random budget, so the counts are exact
        boolean[] filt = ctx.computeOnClient(c -> {
            var p = c.player.getEntityPos();
            return new boolean[]{
                    perf.skipParticle(net.minecraft.particle.ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x + 2, p.y, p.z),
                    perf.skipParticle(net.minecraft.particle.ParticleTypes.FLAME, p.x + 2, p.y, p.z),
                    perf.skipParticle(net.minecraft.particle.ParticleTypes.FLAME, p.x + 200, p.y, p.z),
                    perf.skipParticle(net.minecraft.particle.ParticleTypes.RAIN, p.x + 2, p.y, p.z)};
        });
        check(filt[0] && !filt[1] && filt[2] && filt[3],
                "Performance skips ambient, far and rain particles but keeps nearby ones (" + java.util.Arrays.toString(filt) + ")");
        // Gameplay cues survive the budget and filters.
        boolean cues = ctx.computeOnClient(c -> {
            perf.particlePercent.set(10.0);
            var p = c.player.getEntityPos();
            boolean ok = true;
            for (int i = 0; i < 50; i++)
                for (var t : List.of(net.minecraft.particle.ParticleTypes.FISHING, net.minecraft.particle.ParticleTypes.SPLASH,
                        net.minecraft.particle.ParticleTypes.BUBBLE, net.minecraft.particle.ParticleTypes.DRIPPING_LAVA))
                    ok &= !perf.skipParticle(t, p.x + 2, p.y, p.z);
            perf.particlePercent.set(100.0);
            return ok && perf.skipParticle(net.minecraft.particle.ParticleTypes.RAIN, p.x + 2, p.y, p.z);
        });
        check(cues, "Performance never drops fishing bites, splashes or lava drips (rain still hidden)");
        java.util.function.Function<Boolean, Integer> dust = less -> {
            setBool(ctx, perf, "Less Block Break Dust", less);
            ctx.runOnClient(c -> {
                c.particleManager.clearParticles();
                var pos = c.player.getBlockPos().add(2, 0, 0);
                for (int i = 0; i < 4; i++) c.world.addBlockBreakParticles(pos, net.minecraft.block.Blocks.STONE.getDefaultState());
            });
            ctx.waitTicks(1);
            return ctx.computeOnClient(EvadeClientGameTest::liveParticles);
        };
        int dustFull = dust.apply(false), dustLess = dust.apply(true);
        check(dustFull > 200 && dustLess > 0 && dustLess < dustFull / 2,
                "Less Block Break Dust cuts breaking particles (" + dustFull + " -> " + dustLess + " for 4 broken blocks)");
        // Max Particles: 3000 nearby flames with the cap at 500 leave at most about 500 alive.
        ctx.runOnClient(c -> { perf.particlePercent.set(100.0); perf.maxParticles.set(500.0); c.particleManager.clearParticles(); });
        ctx.waitTicks(2);
        ctx.runOnClient(c -> {
            var p = c.player.getEntityPos();
            for (int i = 0; i < 3000; i++) c.world.addParticleClient(net.minecraft.particle.ParticleTypes.FLAME, p.x + 2, p.y + 1, p.z, 0, 0, 0);
        });
        ctx.waitTicks(2);
        int capped = ctx.computeOnClient(EvadeClientGameTest::liveParticles);
        ctx.runOnClient(c -> perf.maxParticles.reset());
        check(capped > 0 && capped <= 520, "Max Particles caps live particles (" + capped + " alive with the cap at 500)");
        // Fireworks with a 10% budget: one 3 blocks away and one 60 blocks away both explode in full (386 sparks each).
        ctx.runOnClient(c -> { perf.particlePercent.set(10.0); c.particleManager.clearParticles(); });
        ctx.runOnClient(c -> {
            var p = c.player.getEntityPos();
            var boom = List.of(new net.minecraft.component.type.FireworkExplosionComponent(
                    net.minecraft.component.type.FireworkExplosionComponent.Type.LARGE_BALL,
                    it.unimi.dsi.fastutil.ints.IntList.of(0xFF0000), it.unimi.dsi.fastutil.ints.IntList.of(), true, true));
            c.world.addFireworkParticle(p.x, p.y + 3, p.z + 3, 0, 0, 0, boom);
            c.world.addFireworkParticle(p.x, p.y + 3, p.z + 60, 0, 0, 0, boom);
        });
        ctx.waitTicks(5);
        int sparks = ctx.computeOnClient(EvadeClientGameTest::liveParticles);
        ctx.waitTicks(60);
        check(sparks >= 700, "Fireworks explode in full near and far with a 10% particle budget (" + sparks + " particles live)");
        ctx.runOnClient(c -> perf.particlePercent.set(100.0));

        // Burning mobs: their fire layer is skipped (screenshot).
        server.runCommand("summon zombie 0 -60 4 {NoAI:1b,Silent:1b,Fire:2000s,Rotation:[180f,0f]}");
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-perf-mobfire");
        ctx.runOnClient(c -> perf.particlePercent.reset());
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        server.runCommand("kill @e[type=zombie]");

        // AFK cap: two minutes without input drops to the AFK frame rate; any input lifts it straight away. It only
        // applies on top of the game's own "Reduce FPS when: AFK", never with "Minimized".
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        int[] afk = ctx.computeOnClient(c -> {
            c.options.getInactivityFpsLimit().setValue(net.minecraft.client.option.InactivityFpsLimit.AFK);
            var lim = c.getInactivityFpsLimiter();
            try { lim.getClass().getMethod("evade$pretendIdle", long.class).invoke(lim, 120_000L); }
            catch (Exception e) { throw new AssertionError(e); }
            int idle = lim.update();
            lim.onInput();
            int awake = lim.update();
            c.options.getInactivityFpsLimit().setValue(net.minecraft.client.option.InactivityFpsLimit.MINIMIZED);
            try { lim.getClass().getMethod("evade$pretendIdle", long.class).invoke(lim, 120_000L); }
            catch (Exception e) { throw new AssertionError(e); }
            int minimized = lim.update();
            lim.onInput();
            c.options.getInactivityFpsLimit().setValue(net.minecraft.client.option.InactivityFpsLimit.AFK);
            return new int[]{idle, awake, minimized, perf.afkFps.getInt()};
        });
        int[] mini = ctx.computeOnClient(c -> new int[]{perf.minimisedCap(true), perf.minimisedCap(false)});
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        check(afk[0] <= afk[3] && afk[1] > afk[3], "Performance idles at the AFK FPS when AFK and recovers on input (" + afk[0] + " -> " + afk[1] + ")");
        check(afk[2] > afk[3], "Performance's AFK cap stays off with Reduce FPS when: Minimized (" + afk[2] + ")");
        check(mini[0] == 4 && mini[1] == 1, "Minimised FPS keeps the tick rate in a world (" + mini[0] + " / " + mini[1] + ")");
        setBool(ctx, perf, "Low FPS When Tabbed Out", true);
    }

    /** Frame Time Graph: frames are being recorded and summarised; header, freeze and styles. */
    /** FPS Mode presets, Unlock FPS, the benchmark, server rules, the minimap and the shader switch. */
    private void testFpsAndServerRules(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var perf = enable(ctx, net.evadeclient.module.mods.Performance.class);
        String mode0 = ctx.computeOnClient(c -> perf.fpsMode.get());
        int view0 = ctx.computeOnClient(c -> c.options.getViewDistance().getValue());
        ctx.runOnClient(c -> c.options.getViewDistance().setValue(16));
        ctx.runOnClient(c -> perf.fpsMode.set("Max FPS"));
        ctx.waitTicks(5);
        check(ctx.computeOnClient(c -> perf.renderCap.get() == 8 && perf.lowEnd.get() && c.options.getViewDistance().getValue() <= 8),
                "FPS Mode Max FPS caps render distance at 8 and turns on Low-End PC Mode");
        ctx.runOnClient(c -> perf.fpsMode.set("Balanced"));
        ctx.waitTicks(5);
        check(ctx.computeOnClient(c -> perf.renderCap.get() == 12 && !perf.lowEnd.get() && c.options.getViewDistance().getValue() == 12),
                "FPS Mode Balanced caps render distance at 12 and keeps smooth lighting");
        check(ctx.computeOnClient(c -> !c.options.getEnableVsync().getValue() && c.options.getMaxFps().getValue() == 260),
                "Unlock FPS turns VSync off and removes the frame cap");
        ctx.runOnClient(c -> perf.unlockFps.set(false));
        ctx.waitTicks(3);
        ctx.runOnClient(c -> perf.unlockFps.set(true));
        ctx.runOnClient(c -> perf.fpsMode.set(mode0));
        ctx.waitTicks(3);
        ctx.runOnClient(c -> perf.renderCap.set(0.0));
        ctx.waitTicks(3);
        ctx.runOnClient(c -> c.options.getViewDistance().setValue(view0));

        // Benchmark: the maths, then a real 5-second run (the camera turns a full circle in singleplayer).
        var r = net.evadeclient.util.Benchmark.compute(new float[]{10, 10, 10, 10, 10, 10, 10, 10, 10, 20}, 1, "test");
        check(Math.abs(r.avgFps() - 1000.0 / 11) < 0.5 && Math.abs(r.low1() - 50) < 0.5, "Benchmark computes average FPS and 1% low");
        check(ctx.computeOnClient(c -> net.evadeclient.util.Benchmark.start(5)), "Benchmark starts in a world");
        ctx.waitTicks(160);
        var res = ctx.computeOnClient(c -> net.evadeclient.util.Benchmark.last());
        check(res != null && res.avgFps() > 0 && res.frames() > 10, "Benchmark finishes and reports " + (res == null ? "nothing" : Math.round(res.avgFps()) + " FPS"));

        // Server rules: a server switches Freelook off; it can't be turned back on until the rule lifts.
        enable(ctx, net.evadeclient.module.mods.Freelook.class);
        ctx.runOnClient(c -> net.evadeclient.util.ServerRules.apply("{\"disable\":[\"freelook\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(!ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class).isEnabled()), "Server rules switch a mod off");
        enable(ctx, net.evadeclient.module.mods.Freelook.class);
        check(!ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class).isEnabled()), "A mod disabled by the server can't be turned back on");
        ctx.runOnClient(c -> net.evadeclient.util.ServerRules.apply("{\"disable\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class).isEnabled()), "Lifting the rule turns the mod back on");
        ctx.runOnClient(c -> net.evadeclient.util.ServerRules.apply("not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        disable(ctx, net.evadeclient.module.mods.Freelook.class);

        // Minimap: paints terrain into its texture and frees it when switched off.
        enable(ctx, net.evadeclient.module.hud.MinimapHud.class);
        ctx.waitTicks(40);
        int tex = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.MinimapHud.class).textureSize());
        int painted = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.MinimapHud.class).paintedChunks());
        check(tex > 0 && tex % 16 == 0, "Minimap texture allocated (" + tex + ")");
        check(painted > 0, "Minimap painted terrain (" + painted + " chunks)");
        clearToasts(ctx);
        ctx.takeScreenshot("evade-minimap");
        disable(ctx, net.evadeclient.module.hud.MinimapHud.class);
        check(ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.MinimapHud.class).textureSize()) == 0, "Minimap frees its texture");

        // Shaders: the switch reports Iris' state and explains a missing pack instead of failing.
        String status = ctx.computeOnClient(c -> net.evadeclient.util.ShaderSwitch.status());
        check(status != null && !status.isBlank(), "Shader switch reports Iris state (" + status + ")");
        if (ctx.computeOnClient(c -> net.evadeclient.util.ShaderSwitch.irisLoaded() && net.evadeclient.util.ShaderSwitch.findPack("photon").isEmpty())) {
            String msg = ctx.computeOnClient(c -> net.evadeclient.util.ShaderSwitch.apply("Photon"));
            check(msg.contains("isn't in your shaderpacks"), "Shader switch explains a missing Photon pack");
        }
    }

    private void testFrameTime(ClientGameTestContext ctx) {
        var ftg = enable(ctx, net.evadeclient.module.hud.FrameTimeHud.class);
        ctx.waitTicks(60);
        int frames = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.count());
        float[] s = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.summary(net.evadeclient.util.FrameStats.SIZE));
        float[] sec = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.framesWithin(1000));
        ctx.takeScreenshot("evade-frametime");
        check(frames > 60 && s[0] > 1 && s[1] > 0 && s[1] <= s[0] + 0.5f && s[2] > 0,
                String.format(Locale.ROOT, "Frame Time Graph tracks frames (%d frames, %.0f FPS avg, %.0f 1%% low, %.1f ms worst)", frames, s[0], s[1], s[2]));
        boolean statsOk = s.length == 7
                && s[3] <= s[1] + 0.5f                          // 0.1% low is never above the 1% low
                && s[6] <= s[5] + 0.01f && s[5] <= s[2] + 0.01f // median <= p99 <= worst
                && Math.abs(s[4] * s[0] - 1000f) < 1f;          // average ms and average FPS agree
        boolean windowOk = sec[0] >= 1 && (sec[1] >= 1000 || (int) sec[0] == frames);
        check(statsOk && windowOk, String.format(Locale.ROOT,
                "FrameStats summary (0.1%% low %.0f, avg %.2f ms, p99 %.1f ms, median %.1f ms) and 1 s window (%d frames, %.0f ms)",
                s[3], s[4], s[5], s[6], (int) sec[0], sec[1]));

        String head = ctx.computeOnClient(c -> ftg.headerForTest());
        ctx.runOnClient(c -> ftg.toggleFrozen());
        ctx.waitTicks(8);
        String frozenHead = ctx.computeOnClient(c -> ftg.headerForTest());
        ctx.runOnClient(c -> ftg.toggleFrozen());
        ctx.waitTicks(8);
        String liveHead = ctx.computeOnClient(c -> ftg.headerForTest());
        boolean liveAgain = ctx.computeOnClient(c -> !ftg.isFrozen());
        // Area and Line styles with the Auto range paint without errors (a crash in render switches the module off).
        ctx.runOnClient(c -> { setMode(ftg, "Graph Style", "Area"); setMode(ftg, "Graph Range", "Auto"); });
        ctx.waitTicks(4);
        ctx.runOnClient(c -> setMode(ftg, "Graph Style", "Line"));
        ctx.waitTicks(4);
        boolean ftgAlive = ctx.computeOnClient(c -> ftg.isEnabled()
                && ModuleManager.CRASHED.stream().noneMatch(e -> e.startsWith(ftg.getName())));
        ctx.runOnClient(c -> { setting(ftg, "Graph Style").reset(); setting(ftg, "Graph Range").reset(); });
        disable(ctx, net.evadeclient.module.hud.FrameTimeHud.class);
        check(head.matches("avg \\d+ FPS  1% low \\d+  max \\d+\\.\\dms") && frozenHead.startsWith("FROZEN  avg ")
                        && !liveHead.startsWith("FROZEN") && liveAgain && ftgAlive,
                "Frame Time Graph header (\"" + head + "\"), Freeze (\"" + frozenHead + "\") and graph styles");

        // FPS widget formats and Show Only Below; Memory widget formats.
        String[] fpsText = ctx.computeOnClient(c -> {
            var fps = EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class);
            String plain = String.join("|", fps.textForTest());
            setJson(fps, "Format", "FPS: 144");
            setJson(fps, "Show 1% Low", true);
            setJson(fps, "Show Frame Time", true);
            setJson(fps, "Show Cap", "Cap");
            String rich = String.join("|", fps.textForTest());
            setJson(fps, "Show Only Below", 10);
            setJson(fps, "Linger", 0);
            boolean hidden = c.getCurrentFps() < 12 || fps.textForTest().isEmpty();
            for (String n : new String[]{"Format", "Show 1% Low", "Show Frame Time", "Show Cap", "Show Only Below", "Linger"}) setting(fps, n).reset();
            return new String[]{plain, rich, String.valueOf(hidden)};
        });
        check(fpsText[0].matches("\\d+ FPS") && fpsText[1].matches("FPS: \\d+ · 1% \\d+ · \\d+\\.\\d ms · cap (\\d+|inf)")
                        && "true".equals(fpsText[2]),
                "FPS widget formats (\"" + fpsText[0] + "\", \"" + fpsText[1] + "\") and Show Only Below hides it");
        String[] memText = ctx.computeOnClient(c -> {
            var mem = EvadeClient.modules.get(net.evadeclient.module.hud.MemoryHud.class);
            String plain = String.join("|", mem.textForTest());
            setJson(mem, "Format", "Free");
            setJson(mem, "Label", "RAM");
            setJson(mem, "Unit", "MB");
            setJson(mem, "Show Allocated", true);
            setJson(mem, "Show GC", "Both");
            String rich = String.join("|", mem.textForTest());
            for (String n : new String[]{"Format", "Label", "Unit", "Show Allocated", "Show GC"}) setting(mem, n).reset();
            return new String[]{plain, rich};
        });
        check(memText[0].matches("Mem \\d+% (\\d+/\\d+MB|\\d+\\.\\d/\\d+\\.\\dGB)")
                        && memText[1].matches("RAM \\d+MB free\\|Allocated \\d+% \\d+MB\\|GC \\d+/min, last (-|\\d+ms)"),
                "Memory widget formats (\"" + memText[0] + "\", \"" + memText[1] + "\")");
    }

    // ---- clips, voice, misc and chat modules ----------------------------------------------------------------

    /** Clips: record a few seconds, save to MP4, then decode the file back to prove it's real video. */
    private void testClips(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        server.runCommand("summon tnt 0 -60 6 {fuse:400}");
        var clips = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.misc.Clips.class));
        ctx.runOnClient(c -> {
            setMode(clips, "Quality", "480p");
            setNumber(clips, "Clip FPS", 15.0);
            clips.setEnabled(true);
        });
        ctx.waitTicks(100); // ~5 seconds of footage
        int buffered = ctx.computeOnClient(c -> clips.bufferedFrames());
        var future = ctx.computeOnClient(c -> clips.save());
        for (int i = 0; i < 120 && !future.isDone(); i++) ctx.waitTicks(20);
        java.io.File file = future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
        String info = "none";
        boolean ok = false;
        if (file != null && file.length() > 10_000) {
            try {
                var frame = org.jcodec.api.FrameGrab.getFrameFromFile(file, 10);
                info = file.length() / 1024 + " KB, frame " + frame.getWidth() + "x" + frame.getHeight();
                ok = frame.getHeight() == 480;
            } catch (Exception e) { info = "decode failed: " + e; }
        }
        check(buffered > 20 && ok, "Clips saves a playable MP4 (" + buffered + " frames buffered, " + info + ")");

        // Overlays stay out of built-in clips, and a second save queues behind the first instead of being refused.
        boolean hidden = ctx.computeOnClient(c -> net.evadeclient.module.misc.Clips.hideOverlays());
        ctx.waitTicks(40);
        var f1 = ctx.computeOnClient(c -> clips.save());
        var f2 = ctx.computeOnClient(c -> clips.save());
        for (int i = 0; i < 120 && !(f1.isDone() && f2.isDone()); i++) ctx.waitTicks(20);
        boolean queued = f1.isDone() && f2.isDone() && !f1.isCompletedExceptionally() && !f2.isCompletedExceptionally()
                && !f1.join().equals(f2.join()) && f1.join().length() > 10_000 && f2.join().length() > 10_000;
        ctx.runOnClient(c -> {
            clips.setEnabled(false);
            setting(clips, "Quality").reset();
            setting(clips, "Clip FPS").reset();
        });
        server.runCommand("kill @e[type=tnt]");
        check(hidden && queued, "Clips hides overlays while recording and queues a second save (" + hidden + ", " + queued + ")");

        String me = ctx.computeOnClient(c -> c.player.getName().getString());
        check(ctx.computeOnClient(c -> "Win".equals(net.evadeclient.module.misc.Clips.endOfGame("You won the game!", me))
                        && "Win".equals(net.evadeclient.module.misc.Clips.endOfGame("Winner: " + me, me))
                        && "Game end".equals(net.evadeclient.module.misc.Clips.endOfGame("Winner: Red Team", me))
                        && "Game end".equals(net.evadeclient.module.misc.Clips.endOfGame("GAME OVER!", me))
                        && net.evadeclient.module.misc.Clips.endOfGame("<Bob> you won lol", me) == null
                        && net.evadeclient.module.misc.Clips.endOfGame("[MVP+] Bob: you won", me) == null
                        && net.evadeclient.module.misc.Clips.endOfGame("You won 50 coins!", me) == null),
                "Clips tells wins from other end-of-game lines and ignores player chat");
    }

    /** Voice: Simple Voice Chat is installed alongside Evade, this server runs it, and Evade's shortcut opens its menu. */
    private void testVoice(ClientGameTestContext ctx) {
        boolean installed = ctx.computeOnClient(c -> net.evadeclient.module.misc.VoiceChat.installed());
        boolean onServer = ctx.computeOnClient(c -> net.evadeclient.module.misc.VoiceChat.serverHasVoice());
        ctx.runOnClient(c -> net.evadeclient.module.misc.VoiceChat.openMenu());
        ctx.waitTicks(5);
        String screen = ctx.computeOnClient(c -> c.currentScreen == null ? "none" : c.currentScreen.getClass().getName());
        ctx.setScreen(() -> null);
        check(installed && onServer && screen.startsWith("de.maxhenkel.voicechat"),
                "Voice chat: Simple Voice Chat runs here and Evade opens its menu (" + screen.substring(screen.lastIndexOf('.') + 1) + ")");
    }

    /** Misc modules: AutoGG, AutoReconnect, AutoTip, screenshot paths, chat stacking, own-only pops, sound sliders, crosshair. */
    private void testMiscModules(ClientGameTestContext ctx) {
        check(net.evadeclient.module.misc.AutoGG.matches("                   1st Killer - Steve - 12")
                        && net.evadeclient.module.misc.AutoGG.matches("Winner: Steve")
                        && net.evadeclient.module.misc.AutoGG.matches("VICTORY!")
                        && net.evadeclient.module.misc.AutoGG.matches("Steve has won the game!")
                        && !net.evadeclient.module.misc.AutoGG.matches("You won 50 coins")
                        && !net.evadeclient.module.misc.AutoGG.matches("game over man")
                        && !net.evadeclient.module.misc.AutoGG.matches("#1 Steve")
                        && net.evadeclient.module.misc.AutoGG.looksLikePlayerChat("[MVP+] Steve: you won")
                        && net.evadeclient.module.misc.AutoGG.looksLikePlayerChat("<Steve> GAME OVER")
                        && !net.evadeclient.module.misc.AutoGG.looksLikePlayerChat("Winner: Steve"),
                "AutoGG answers end-of-game announcements only");
        check(ctx.computeOnClient(c -> {
            var gg = EvadeClient.modules.get(net.evadeclient.module.misc.AutoGG.class);
            boolean generic = gg.activePreset().equals("Generic");
            setMode(gg, "Message", "Custom");
            ((net.evadeclient.setting.StringSetting) setting(gg, "Custom Text")).set("a| b |/cmd||c");
            String prev = null;
            boolean ok = true;
            for (int i = 0; i < 50; i++) {
                String m = gg.nextMessage();
                ok &= (m.equals("a") || m.equals("b") || m.equals("c")) && !m.equals(prev);
                prev = m;
            }
            setting(gg, "Message").reset();
            setting(gg, "Custom Text").reset();
            return generic && ok;
        }), "AutoGG Custom Text pool: trimmed, no commands, no repeats");
        check("banned".equals(net.evadeclient.module.misc.AutoReconnect.blockReason(
                        net.minecraft.text.Text.translatable("multiplayer.disconnect.banned.reason", "test")))
                        && "AFK kick".equals(net.evadeclient.module.misc.AutoReconnect.blockReason(
                        net.minecraft.text.Text.translatable("multiplayer.disconnect.idling")))
                        && "not whitelisted".equals(net.evadeclient.module.misc.AutoReconnect.blockReason(
                        net.minecraft.text.Text.translatable("disconnect.genericReason",
                                net.minecraft.text.Text.translatable("multiplayer.disconnect.not_whitelisted"))))
                        && "AFK kick".equals(net.evadeclient.module.misc.AutoReconnect.blockReason(
                        net.minecraft.text.Text.literal("Kicked for being AFK")))
                        && net.evadeclient.module.misc.AutoReconnect.blockReason(net.minecraft.text.Text.translatable("disconnect.timeout")) == null
                        && net.evadeclient.module.misc.AutoReconnect.blockReason(net.minecraft.text.Text.literal("Server restarting")) == null,
                "AutoReconnect skips bans, whitelist refusals and AFK kicks");
        check(ctx.computeOnClient(c -> net.evadeclient.module.misc.AutoReconnect.target()) == null,
                "AutoReconnect has no server to rejoin in singleplayer");
        check(net.evadeclient.module.misc.AutoTip.isHypixel("mc.hypixel.net:25565")
                        && net.evadeclient.module.misc.AutoTip.isHypixel("Hypixel.net")
                        && !net.evadeclient.module.misc.AutoTip.isHypixel("nothypixel.net")
                        && !net.evadeclient.module.misc.AutoTip.isHypixel("hypixel.net.example.com"),
                "AutoTip only tips on Hypixel");
        check(ctx.computeOnClient(c -> net.evadeclient.module.misc.AutoTip.allow(
                        net.minecraft.text.Text.literal("You tipped 5 players in 5 different games!"), false)),
                "AutoTip leaves tip replies alone when it hasn't just tipped");
        check(ctx.computeOnClient(c -> net.evadeclient.module.misc.ScreenshotTools.resolve("2026-01-01_10.00.00.png") != null
                        && net.evadeclient.module.misc.ScreenshotTools.resolve("../options.txt") == null
                        && net.evadeclient.module.misc.ScreenshotTools.resolve("../x.png") == null
                        && net.evadeclient.module.misc.ScreenshotTools.resolve("sub/x.png") == null
                        && net.evadeclient.module.misc.ScreenshotTools.resolve("..png") == null),
                "Screenshot buttons only touch files inside the screenshots folder");
        // BetterChat: three identical lines stack into one "(x3)"; after another line, the next repeat starts fresh.
        String[] stacked = ctx.computeOnClient(c -> {
            var bc = EvadeClient.modules.get(net.evadeclient.module.misc.BetterChat.class);
            boolean was = bc.isEnabled();
            bc.setEnabled(true);
            var chat = c.inGameHud.getChatHud();
            for (int i = 0; i < 3; i++) chat.addMessage(net.minecraft.text.Text.literal("evade stack test"));
            String first = bc.stackCount() + "|" + bc.lastLineText();
            chat.addMessage(net.minecraft.text.Text.literal("evade other line"));
            chat.addMessage(net.minecraft.text.Text.literal("evade stack test"));
            String second = bc.stackCount() + "|" + bc.lastLineText();
            bc.setEnabled(was);
            return new String[]{first, second};
        });
        check(stacked[0].startsWith("3|") && stacked[0].contains("evade stack test (x3)") && stacked[1].startsWith("1|"),
                "BetterChat stacks repeats onto the newest line only (" + stacked[0] + " / " + stacked[1] + ")");
        // Notifier: someone else's pop is ignored, your own is counted.
        int[] pops = ctx.computeOnClient(c -> {
            var n = EvadeClient.modules.get(net.evadeclient.module.misc.Notifier.class);
            boolean was = n.isEnabled();
            n.setEnabled(true);
            int before = n.pops();
            net.evadeclient.module.misc.Notifier.onPop(new net.minecraft.entity.passive.PigEntity(net.minecraft.entity.EntityType.PIG, c.world));
            int afterOther = n.pops();
            net.evadeclient.module.misc.Notifier.onPop(c.player);
            int afterOwn = n.pops();
            n.setEnabled(was);
            return new int[]{before, afterOther, afterOwn};
        });
        check(pops[1] == pops[0] && pops[2] == pops[0] + 1, "Notifier counts only your own totem pops");
        // Sound Tweaks: sliders scale only their own sounds.
        float[] vol = ctx.computeOnClient(c -> {
            var st = EvadeClient.modules.get(net.evadeclient.module.mods.SoundTweaks.class);
            var rain = (net.evadeclient.setting.NumberSetting) setting(st, "Rain");
            boolean was = st.isEnabled();
            st.setEnabled(false);
            float off = net.evadeclient.module.mods.SoundTweaks.volume(net.minecraft.util.Identifier.ofVanilla("weather.rain"));
            st.setEnabled(true);
            rain.set(50.0);
            float half = net.evadeclient.module.mods.SoundTweaks.volume(net.minecraft.util.Identifier.ofVanilla("weather.rain"));
            float other = net.evadeclient.module.mods.SoundTweaks.volume(net.minecraft.util.Identifier.ofVanilla("entity.pig.ambient"));
            rain.reset();
            st.setEnabled(was);
            return new float[]{off, half, other};
        });
        check(vol[0] == 1f && Math.abs(vol[1] - 0.5f) < 1e-4 && vol[2] == 1f, "Sound Tweaks scales rain only (" + java.util.Arrays.toString(vol) + ")");

        // Crosshair: share codes round-trip, junk is refused, every style draws, the editor opens, old configs migrate.
        var ch = enable(ctx, net.evadeclient.module.mods.Crosshair.class);
        String[] xh = ctx.computeOnClient(c -> {
            var x = net.evadeclient.module.mods.CrosshairPattern.preset("X");
            ch.pattern.set("here you go: " + x.shareCode() + " enjoy");
            boolean same = ch.pattern.pattern().equals(x);
            String refused = "no";
            try { ch.pattern.set("EVXH1:short"); } catch (RuntimeException e) { refused = "yes"; }
            return new String[]{String.valueOf(same), refused, ch.pattern.code()};
        });
        check("true".equals(xh[0]) && "yes".equals(xh[1]) && xh[2].length() == 39,
                "Crosshair share codes round-trip and junk is refused (" + xh[2] + ")");
        for (String st : new String[]{"Cross", "Dot", "Circle", "Cross + Dot", "T", "X", "Square", "Arrow", "Circle + Dot", "Custom"}) {
            ctx.runOnClient(c -> { setMode(ch, "Style", st); setNumber(ch, "Rotation", st.equals("Square") ? 45.0 : 0.0); });
            ctx.waitTicks(2);
            check(ctx.computeOnClient(c -> ch.isEnabled()), "Crosshair style " + st + " draws without crashing");
        }
        ctx.runOnClient(c -> c.setScreen(new net.evadeclient.gui.CrosshairEditorScreen(null, ch)));
        ctx.waitTicks(3);
        ctx.takeScreenshot("evade-crosshair-editor");
        ctx.runOnClient(c -> c.setScreen(null));
        ctx.waitTicks(2);
        ctx.takeScreenshot("evade-crosshair-custom");
        String indicator = ctx.computeOnClient(c -> {
            for (var s : ch.getSettings()) if (!s.isHidden() && !(s instanceof net.evadeclient.setting.KeySetting)) s.reset();
            var old = new com.google.gson.JsonObject();
            old.addProperty("Show Attack Indicator", false);
            ch.onConfigLoaded(old);
            String r = String.valueOf(setting(ch, "Attack Indicator").get());
            setting(ch, "Attack Indicator").reset();
            return r;
        });
        disable(ctx, net.evadeclient.module.mods.Crosshair.class);
        check("Off".equals(indicator), "Crosshair migrates Show Attack Indicator: off (" + indicator + ")");
    }

    /** Chat the client received, collected by a listener registered once (Fabric events can't be unregistered). */
    private static final List<String> RECEIVED_CHAT = java.util.Collections.synchronizedList(new ArrayList<>());
    private static boolean chatListener;

    /** Better Chat: Recent Lines stacking, the filter, timestamps, mentions. AutoGG end to end. Protection stages. */
    private void testChatAndAlerts(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String[] chat = ctx.computeOnClient(c -> {
            var bc = EvadeClient.modules.get(net.evadeclient.module.misc.BetterChat.class);
            var mode = (net.evadeclient.setting.ModeSetting) setting(bc, "Stack Mode");
            var filter = (BooleanSetting) setting(bc, "Filter");
            var words = (net.evadeclient.setting.StringSetting) setting(bc, "Filter Words");
            var action = (net.evadeclient.setting.ModeSetting) setting(bc, "Filter Action");
            var brackets = (net.evadeclient.setting.ModeSetting) setting(bc, "Time Brackets");
            var stamps = (BooleanSetting) setting(bc, "Timestamps");
            boolean was = bc.isEnabled();
            bc.setEnabled(true);
            var hud = c.inGameHud.getChatHud();
            try {
                mode.set("Recent Lines");
                hud.addMessage(net.minecraft.text.Text.literal("evade recent a"));
                hud.addMessage(net.minecraft.text.Text.literal("evade recent b"));
                hud.addMessage(net.minecraft.text.Text.literal("evade recent a"));
                String recent = bc.stackCount() + "|" + bc.lastLineText();
                filter.set(true);
                words.set("spam word|re:^buy \\d+|re:([");
                action.set("Collapse");
                hud.addMessage(net.minecraft.text.Text.literal("this has a SPAM WORD in it"));
                String collapsed = bc.lastLineText();
                boolean regex = bc.filtered("buy 64 diamonds") && !bc.filtered("please buy 64") && !bc.filtered("hello");
                stamps.set(true);
                brackets.set("None");
                hud.addMessage(net.minecraft.text.Text.literal("evade time test"));
                String bare = bc.lastLineText();
                String me = c.getSession().getUsername();
                boolean mentions = bc.mentionsYou("<Alex> hey " + me) && !bc.mentionsYou("<" + me + "> hey " + me)
                        && !bc.mentionsYou(me + " joined the game");
                return new String[]{recent, collapsed, String.valueOf(regex), bare, String.valueOf(mentions)};
            } finally {
                mode.reset();
                filter.reset();
                words.reset();
                action.reset();
                brackets.reset();
                stamps.reset();
                bc.setEnabled(was);
            }
        });
        check(chat[0].startsWith("2|") && chat[0].contains("evade recent a (x2)"),
                "BetterChat Recent Lines stacks a repeat across another line (" + chat[0] + ")");
        check(chat[1].contains("[filtered]") && !chat[1].toLowerCase(Locale.ROOT).contains("spam"),
                "BetterChat Collapse shows [filtered] (" + chat[1] + ")");
        check("true".equals(chat[2]), "BetterChat filter: substrings ignore case, re: patterns work, a broken one is skipped");
        check(chat[3].matches("\\d{2}:\\d{2} evade time test"), "BetterChat Time Brackets None (" + chat[3] + ")");
        check("true".equals(chat[4]), "BetterChat mentions: your name in other players' chat only");

        // AutoGG end to end: a server end-of-game line makes it say gg in chat after its delay (1 s).
        if (!chatListener) {
            chatListener = true;
            net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, at) ->
                    RECEIVED_CHAT.add(message.getString()));
        }
        RECEIVED_CHAT.clear();
        boolean ggWas = ctx.computeOnClient(c -> {
            var gg = EvadeClient.modules.get(net.evadeclient.module.misc.AutoGG.class);
            boolean was = gg.isEnabled();
            gg.setEnabled(true);
            net.evadeclient.module.misc.AutoGG.onGame("Winner: Steve");
            return was;
        });
        ctx.waitTicks(40);
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.misc.AutoGG.class).setEnabled(ggWas));
        List<String> said = List.copyOf(RECEIVED_CHAT);
        check(said.stream().anyMatch(l -> l.endsWith("> gg")), "AutoGG says gg after a game ends (" + said + ")");

        // Protection warns, then warns critically, and re-arms on a new item.
        int[] prot = ctx.computeOnClient(c -> {
            var pr = EvadeClient.modules.get(net.evadeclient.module.misc.Protection.class);
            boolean was = pr.isEnabled();
            ItemStack old = c.player.getEquippedStack(EquipmentSlot.HEAD).copy();
            pr.setEnabled(true);
            try {
                ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
                int max = helmet.getMaxDamage();
                helmet.setDamage(max - max / 10 + 1); // just under 10% left: the first warning
                c.player.equipStack(EquipmentSlot.HEAD, helmet);
                pr.onTick();
                int warned = pr.stage(EquipmentSlot.HEAD);
                ItemStack worn = helmet.copy();
                worn.setDamage(max - 2); // under 3% left: critical
                c.player.equipStack(EquipmentSlot.HEAD, worn);
                pr.onTick();
                int critical = pr.stage(EquipmentSlot.HEAD);
                c.player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                pr.onTick();
                int fresh = pr.stage(EquipmentSlot.HEAD);
                return new int[]{warned, critical, fresh};
            } finally {
                c.player.equipStack(EquipmentSlot.HEAD, old);
                pr.setEnabled(was);
            }
        });
        check(prot[0] == 1 && prot[1] == 2 && prot[2] == 0,
                "Protection warns, then warns critically, and re-arms on a new item (" + java.util.Arrays.toString(prot) + ")");
    }

    // ---- HUD: screenshot, reach and combo, cooldowns, armor, module list, layout -----------------------------

    private void testHud(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 10");
        server.runCommand("setblock 3 -60 6 chest");
        server.runCommand("setblock -3 -60 6 ender_chest");
        server.runCommand("setblock 0 -60 12 red_shulker_box");
        server.runCommand("fill 6 -60 9 7 -60 9 barrel");
        server.runCommand("summon zombie 0 -60 4 {NoAI:1b,PersistenceRequired:1b}");
        server.runCommand("item replace entity @a armor.head with netherite_helmet[damage=200]");
        server.runCommand("item replace entity @a armor.chest with diamond_chestplate[damage=420]");
        server.runCommand("item replace entity @a armor.legs with netherite_leggings");
        server.runCommand("item replace entity @a armor.feet with diamond_boots[damage=100]");
        server.runCommand("item replace entity @a weapon.offhand with totem_of_undying");
        server.runCommand("item replace entity @a hotbar.1 with ender_pearl 16");
        server.runCommand("item replace entity @a hotbar.2 with wind_charge 32");
        server.runCommand("item replace entity @a hotbar.3 with golden_apple 12");
        server.runCommand("item replace entity @e[type=zombie] armor.head with iron_helmet");
        server.runCommand("item replace entity @e[type=zombie] armor.chest with diamond_chestplate[damage=300]");
        server.runCommand("item replace entity @e[type=zombie] weapon.mainhand with iron_spear");
        server.runCommand("effect give @a absorption 120 1");
        ctx.waitTicks(20);

        // Reach Display and Combo Counter on a real hit: the zombie's hitbox is 3.7 blocks from your eyes.
        boolean[] hudWas = ctx.computeOnClient(c -> {
            var reach = EvadeClient.modules.get(net.evadeclient.module.hud.ReachHud.class);
            var combo = EvadeClient.modules.get(net.evadeclient.module.hud.ComboHud.class);
            boolean[] was = {reach.isEnabled(), combo.isEnabled()};
            reach.setEnabled(true);
            combo.setEnabled(true);
            return was;
        });
        ctx.waitTicks(2);
        ctx.runOnClient(c -> {
            for (var e : c.world.getEntities())
                if (e instanceof net.minecraft.entity.mob.ZombieEntity z) { c.interactionManager.attackEntity(c.player, z); break; }
        });
        ctx.waitTicks(5);
        String[] hit = ctx.computeOnClient(c -> new String[]{
                EvadeClient.modules.get(net.evadeclient.module.hud.ReachHud.class).textForTest().get(0),
                String.join("|", EvadeClient.modules.get(net.evadeclient.module.hud.ComboHud.class).textForTest())});

        ctx.runOnClient(c -> {
            c.player.getItemCooldownManager().set(new ItemStack(Items.ENDER_PEARL), 400);
            c.player.getItemCooldownManager().set(new ItemStack(Items.WIND_CHARGE), 200);
        });
        ctx.waitTicks(40);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-hud");
        int[] cds = ctx.computeOnClient(c -> {
            var all = net.evadeclient.util.Cooldowns.active();
            var pearl = all.get(net.minecraft.util.Identifier.ofVanilla("ender_pearl"));
            var wind = all.get(net.minecraft.util.Identifier.ofVanilla("wind_charge"));
            return pearl == null || wind == null ? new int[]{-1, -1, -1, -1}
                    : new int[]{pearl.remaining(), pearl.total(), wind.remaining(), wind.total()};
        });
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(net.evadeclient.module.hud.ReachHud.class).setEnabled(hudWas[0]);
            EvadeClient.modules.get(net.evadeclient.module.hud.ComboHud.class).setEnabled(hudWas[1]);
        });
        double reach = -1;
        try { reach = Double.parseDouble(hit[0].replace(" blocks", "")); } catch (NumberFormatException ignored) {}
        check(reach >= 3.6 && reach <= 3.8, "Reach Display measures a hit (" + hit[0] + ")");
        check(hit[1].startsWith("1 Combo"), "Combo Counter counts a hit the server confirmed (" + hit[1] + ")");
        check(cds[0] > 300 && cds[0] < 400 && cds[1] == 400 && cds[2] > 100 && cds[2] < 200 && cds[3] == 200,
                "Cooldowns reads the pearl and wind charge timers (" + java.util.Arrays.toString(cds) + ")");
    }

    /** ArmorHUD durability text, elytra time, ammo and held totals; Cooldowns formats, sort and old-config migration. */
    private void testArmorAndCooldowns(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("clear @a");
        server.runCommand("effect clear @a");
        server.runCommand("item replace entity @a armor.head with netherite_helmet[damage=400]"); // 7 of 407 left
        server.runCommand("item replace entity @a armor.chest with elytra[damage=372]");          // 59 s of flight
        server.runCommand("item replace entity @a armor.legs with netherite_leggings");
        server.runCommand("item replace entity @a armor.feet with diamond_boots[damage=100]");   // 329 of 429 left
        server.runCommand("item replace entity @a weapon.mainhand with bow");
        server.runCommand("item replace entity @a weapon.offhand with golden_apple 3");
        server.runCommand("item replace entity @a inventory.0 with arrow 20");
        server.runCommand("item replace entity @a inventory.1 with spectral_arrow 5");
        server.runCommand("item replace entity @a inventory.2 with golden_apple 12");
        ctx.waitTicks(5);

        var armor = enable(ctx, net.evadeclient.module.client.ArmorHud.class);
        ctx.runOnClient(c -> {
            for (var s : armor.getSettings()) if (!s.isHidden()) s.reset();
            setJson(armor, "Durability Text", "Percent");
        });
        var slots = ctx.computeOnClient(c -> armor.slotsForTest());
        check(slots.equals(List.of("2|-", "~0:59|-", "100|-", "77|-", "100|25", "-|15")),
                "ArmorHUD: percent, elytra time, bow ammo and held total " + slots);
        ctx.runOnClient(c -> setJson(armor, "Durability Text", "Remaining/Max"));
        slots = ctx.computeOnClient(c -> armor.slotsForTest());
        check(slots.get(0).equals("7/407|-") && slots.get(3).equals("329/429|-"), "ArmorHUD: Remaining/Max " + slots);
        ctx.runOnClient(c -> setJson(armor, "Order", "Boots First"));
        slots = ctx.computeOnClient(c -> armor.slotsForTest());
        check(slots.get(0).equals("329/429|-"), "ArmorHUD: Boots First " + slots);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-armorhud-depth");

        var cds = enable(ctx, net.evadeclient.module.client.CooldownHud.class);
        ctx.runOnClient(c -> {
            for (var s : cds.getSettings()) if (!s.isHidden()) s.reset();
            setJson(cds, "Weapon Charge", false);
            setJson(cds, "Time Format", "Whole");
            setJson(cds, "Hotbar Timer", true);
            c.player.getItemCooldownManager().set(new ItemStack(Items.ENDER_PEARL), 400);
            c.player.getItemCooldownManager().set(new ItemStack(Items.WIND_CHARGE), 200);
        });
        ctx.waitTicks(3);
        var rows = ctx.computeOnClient(c -> cds.rowsForTest());
        check(rows.size() == 2 && rows.get(0).matches("Wind Charge: (10|9)s") && rows.get(1).matches("Ender Pearl: (20|19)s"),
                "Cooldowns: Whole seconds, soonest first " + rows);
        ctx.runOnClient(c -> { setJson(cds, "Sort", "Longest First"); setJson(cds, "Time Format", "Ticks"); });
        rows = ctx.computeOnClient(c -> cds.rowsForTest());
        check(rows.get(0).startsWith("Ender Pearl: ") && rows.get(0).endsWith("t"), "Cooldowns: Longest First in ticks " + rows);
        ctx.takeScreenshot("evade-cooldowns-hotbar-timer");

        boolean migrated = ctx.computeOnClient(c -> {
            var o = new com.google.gson.JsonObject();
            o.addProperty("Ready Sound", false);
            o.addProperty("Effect Timers", true);
            cds.onConfigLoaded(o);
            var a = new com.google.gson.JsonObject();
            a.addProperty("Durability %", false);
            armor.onConfigLoaded(a);
            return settingText(cds, "Ready Sound").equals("Off") && settingText(cds, "Effect Timers").equals("PvP Set")
                    && settingText(armor, "Durability Text").equals("Off");
        });
        check(migrated, "Cooldowns/ArmorHUD migrate old on/off settings");

        ctx.runOnClient(c -> {
            for (var s : cds.getSettings()) if (!s.isHidden()) s.reset();
            for (var s : armor.getSettings()) if (!s.isHidden()) s.reset();
            c.player.getItemCooldownManager().remove(net.minecraft.util.Identifier.ofVanilla("ender_pearl"));
            c.player.getItemCooldownManager().remove(net.minecraft.util.Identifier.ofVanilla("wind_charge"));
        });
        server.runCommand("clear @a");
        server.runCommand("effect clear @a");
        server.runCommand("kill @e[type=!player]");
    }

    /** Whether the Module List shows {@code name} (with or without a suffix such as Fullbright's mode). */
    private static boolean listed(List<String> lines, String name) {
        return lines.stream().anyMatch(l -> l.equals(name) || l.startsWith(name + " "));
    }

    /** Module List filtering, text case and sort; Watermark size and stacking. */
    private void testModuleList(ClientGameTestContext ctx) {
        String problems = ctx.computeOnClient(c -> {
            var list = EvadeClient.modules.get(net.evadeclient.module.client.ModuleListHud.class);
            var zoom = EvadeClient.modules.get(Zoom.class);
            var bright = EvadeClient.modules.get(net.evadeclient.module.visuals.Fullbright.class);
            boolean zoomWas = zoom.isEnabled(), brightWas = bright.isEnabled();
            zoom.setEnabled(true);
            bright.setEnabled(true);
            StringBuilder p = new StringBuilder();
            try {
                if (!listed(list.linesForTest(), "Zoom") || !listed(list.linesForTest(), "Fullbright")) p.append("enabled mods missing ").append(list.linesForTest()).append("; ");
                list.hiddenModules.set("zoom,  Full Bright");
                if (listed(list.linesForTest(), "Zoom") || listed(list.linesForTest(), "Fullbright")) p.append("Hidden Modules ").append(list.linesForTest()).append("; ");
                list.hiddenModules.set("");
                list.listVisuals.set(false);
                if (listed(list.linesForTest(), "Fullbright") || !listed(list.linesForTest(), "Zoom")) p.append("List Visuals off ").append(list.linesForTest()).append("; ");
                list.listVisuals.set(true);
                list.textCase.set("UPPERCASE");
                if (!listed(list.linesForTest(), "ZOOM")) p.append("UPPERCASE ").append(list.linesForTest()).append("; ");
                list.textCase.set("Normal");
                list.sort.set("Alphabetical");
                var lines = list.linesForTest();
                var copy = new ArrayList<>(lines);
                copy.sort(String.CASE_INSENSITIVE_ORDER);
                if (!lines.equals(copy)) p.append("Alphabetical ").append(lines).append("; ");
                list.sort.set("Length");
                var byLen = list.linesForTest().stream().map(s -> c.textRenderer.getWidth(s)).toList();
                for (int i = 1; i < byLen.size(); i++) if (byLen.get(i - 1) < byLen.get(i)) { p.append("Length ").append(byLen).append("; "); break; }
            } finally {
                list.hiddenModules.reset();
                list.listVisuals.reset();
                list.textCase.reset();
                list.sort.reset();
                zoom.setEnabled(zoomWas);
                bright.setEnabled(brightWas);
            }
            return p.toString();
        });
        check(problems.isEmpty(), "Module List filtering, text case and sort (" + problems + ")");
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.client.WatermarkHud.class).size.set(24.0));
        ctx.waitTicks(3);
        String watermark = ctx.computeOnClient(c -> {
            var wm = EvadeClient.modules.get(net.evadeclient.module.client.WatermarkHud.class);
            StringBuilder p = new StringBuilder();
            if (wm.isEnabled() && (wm.background.get() ? wm.getHeight() != 30 : wm.getHeight() != 24)) p.append("height ").append(wm.getHeight()).append("; ");
            int sh = Math.round(c.getWindow().getScaledHeight() / net.evadeclient.hud.Hud.scale());
            var list = EvadeClient.modules.get(net.evadeclient.module.client.ModuleListHud.class);
            if (wm.isEnabled() && wm.atDefaultSpot() && list.x == net.evadeclient.module.client.ModuleListHud.DEFAULT_X
                    && list.y == net.evadeclient.module.client.ModuleListHud.DEFAULT_Y
                    && (list.resolveY(sh) != wm.stackY(sh) || list.resolveY(sh) <= wm.resolveY(sh) + 18))
                p.append("list at ").append(list.resolveY(sh)).append(" under a watermark at ").append(wm.resolveY(sh)).append("; ");
            wm.size.reset();
            return p.toString();
        });
        check(watermark.isEmpty(), "Watermark Size sets its height and the Module List stacks under it (" + watermark + ")");
    }

    /**
     * Default HUD layout on a small screen (854x480 at GUI scale 2, 427x240 HUD units): every element fits, and the
     * elements that are on in a fresh install don't overlap. Sizes come from the HUD editor's preview render.
     */
    private void testHudLayout(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(854, 480);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); });
        List<String> defaults = List.of("FPS", "CPS", "Coordinates", "Direction HUD", "Cooldowns", "Keystrokes", "ArmorHUD", "Potion Effects");
        int[][] saved = ctx.computeOnClient(c -> {
            var els = EvadeClient.modules.hudElements();
            int[][] s = new int[els.size()][3];
            for (int i = 0; i < els.size(); i++) {
                var el = els.get(i);
                s[i] = new int[]{el.x, el.y, el.isEnabled() ? 1 : 0};
                if (defaults.contains(el.getName())) { el.resetPosition(); el.setEnabled(true); }
            }
            return s;
        });
        ctx.setScreen(() -> new net.evadeclient.gui.HudEditorScreen(null));
        ctx.waitTicks(5);
        String problems = ctx.computeOnClient(c -> {
            float k = net.evadeclient.hud.Hud.scale();
            int w = Math.round(c.getWindow().getScaledWidth() / k), h = Math.round(c.getWindow().getScaledHeight() / k);
            StringBuilder p = new StringBuilder();
            List<int[]> rects = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                if (!defaults.contains(el.getName())) continue;
                int x = el.resolveX(w), y = el.resolveY(h), ew = el.scaledWidth(), eh = el.scaledHeight();
                if (x < 0 || y < 0 || x + ew > w || y + eh > h) p.append(el.getName()).append(" off screen; ");
                rects.add(new int[]{x, y, x + ew, y + eh});
                names.add(el.getName());
            }
            for (int i = 0; i < rects.size(); i++) for (int j = i + 1; j < rects.size(); j++) {
                int[] a = rects.get(i), b = rects.get(j);
                if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3])
                    p.append(names.get(i)).append(" overlaps ").append(names.get(j)).append("; ");
            }
            return w + "x" + h + ": " + p;
        });
        ctx.takeScreenshot("evade-hud-layout-427x240");
        ctx.setScreen(() -> null);
        ctx.runOnClient(c -> {
            var els = EvadeClient.modules.hudElements();
            for (int i = 0; i < els.size(); i++) {
                var el = els.get(i);
                el.x = saved[i][0];
                el.y = saved[i][1];
                el.setEnabled(saved[i][2] == 1);
            }
        });
        check(problems.endsWith(": "), "Default HUD layout fits a small screen without overlaps (" + problems + ")");
    }

    // ---- menu ---------------------------------------------------------------------------------------------------

    private void testMenu(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        // Typical 1080p monitor at GUI scale 2 (960x540 GUI pixels).
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(c -> {
            c.options.getGuiScale().setValue(2);
            c.onResolutionChanged();
        });
        ctx.waitTicks(10);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-hud-1080p");
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(10);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-menu");
        // Click the first row (NoFog) in the default List style and check it toggles. First row ~y 144 GUI.
        boolean before = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        ctx.getInput().setCursorPos(400 * 2, 140 * 2);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        boolean after = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        check(before != after, "Clicking a mod row in the menu toggles it");
        // Search narrows the list to matching mods; Escape clears it.
        int full = visibleRowCount(ctx);
        ctx.getInput().typeChars("zoom");
        ctx.waitTicks(5);
        List<String> found = visibleNames(ctx);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-menu-search");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(3);
        int cleared = visibleRowCount(ctx);
        boolean stillOpen = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu);
        check(found.contains("Zoom") && found.size() < full && cleared == full && stillOpen,
                "Menu search finds Zoom (" + found.size() + " of " + full + " mods) and Escape clears it (" + cleared + ")");
        // A search on another page looks through every category: on the HUD page, "zoom" still finds Zoom.
        ctx.getInput().setCursorPos(140 * 2, 262 * 2);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(3);
        ctx.getInput().typeChars("zoom");
        ctx.waitTicks(3);
        List<String> hudSearch = visibleNames(ctx);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.getInput().setCursorPos(140 * 2, 106 * 2);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(3);
        check(hudSearch.contains("Zoom"), "Menu search on the HUD page searches every category (" + hudSearch + ")");

        ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
        ctx.waitTicks(10);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-menu-settings");
        // A module page opened from the HUD editor goes back to the editor on Escape.
        ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class),
                new net.evadeclient.gui.HudEditorScreen(null)));
        ctx.waitTicks(5);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(c -> c.currentScreen instanceof net.evadeclient.gui.HudEditorScreen),
                "Escape on a module page opened from the HUD editor returns to the editor");
        ctx.setScreen(() -> new net.evadeclient.gui.HudEditorScreen(null));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-hud-editor");

        // GUI styles: switch each one, reopen the menu, screenshot.
        var cs = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class));
        for (String st : new String[]{"List", "Compact", "Cards"}) {
            ctx.runOnClient(c -> setMode(cs, "GUI Style", st));
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(6);
            clearToasts(ctx);
            ctx.takeScreenshot("evade-gui-" + st.toLowerCase(Locale.ROOT));
        }
        // In Cards style, click the first card's centre and confirm it toggles (3 columns of 190 x 58 from (266, 119)).
        boolean b1 = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        ctx.getInput().setCursorPos(361 * 2, 148 * 2);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        boolean a1 = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        check(b1 != a1, "Clicking a card in the Cards GUI style toggles a mod");
        // In Compact style, click the first row and confirm it toggles.
        ctx.runOnClient(c -> setMode(cs, "GUI Style", "Compact"));
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(6);
        boolean b2 = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        ctx.getInput().setCursorPos(300 * 2, 125 * 2);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        boolean a2 = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        check(b2 != a2, "Clicking a row in the Compact GUI style toggles a mod");
        testThemesAndClips(ctx, sp, cs);

        // The All / Enabled filter: switch to Enabled, confirm only enabled show.
        ctx.runOnClient(c -> setMode(cs, "GUI Style", "List"));
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(6);
        // Enabled toggle sits in the header's right half (seg at x ~702..852, y ~86..108 GUI).
        int enBefore = visibleRowCount(ctx);
        ctx.getInput().setCursorPos(810 * 2, 97 * 2);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        int enAfter = visibleRowCount(ctx);
        check(enAfter < enBefore && enAfter > 0, "All/Enabled filter narrows the list (" + enBefore + " -> " + enAfter + ")");
        ctx.getInput().setCursorPos(740 * 2, 97 * 2); // back to All
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(3);

        testMenuFade(ctx);

        // Auto GUI scale at common sizes (the scale most players use).
        for (int[] sc : new int[][]{{1920, 1080}, {1280, 720}}) {
            ctx.getInput().resizeWindow(sc[0], sc[1]);
            ctx.runOnClient(c -> { c.options.getGuiScale().setValue(0); c.onResolutionChanged(); });
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(8);
            clearToasts(ctx);
            ctx.takeScreenshot("evade-menu-auto-" + sc[0] + "x" + sc[1]);
        }
        ctx.setScreen(() -> null);
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); });
        ctx.waitTicks(3);
    }

    /**
     * Close fade: Escape fades the window out (120 ms) and only then closes; a resize doesn't replay the intro;
     * coming back from the HUD editor does. Runs on a fixed-step clock, so it doesn't depend on real time.
     */
    private void testMenuFade(ClientGameTestContext ctx) {
        java.util.concurrent.atomic.AtomicLong ft = new java.util.concurrent.atomic.AtomicLong(5_000_000);
        EvadeMenu.clock = ft::get;
        try {
            java.util.function.Supplier<Double> openedAt = () -> ctx.computeOnClient(c -> {
                try {
                    var f = EvadeMenu.class.getDeclaredField("openedAt");
                    f.setAccessible(true);
                    return f.getDouble(c.currentScreen);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            ctx.setScreen(EvadeMenu::new);
            ft.addAndGet(400);
            ctx.waitTicks(2);
            double o1 = openedAt.get();
            ctx.getInput().resizeWindow(1600, 900);
            ctx.waitTicks(2);
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.waitTicks(2);
            check(openedAt.get() == o1, "Resizing the window while the menu is open doesn't replay its intro");

            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            ctx.waitTicks(2); // the clock hasn't moved: the fade is at its start
            boolean stillOpen = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu);
            ft.addAndGet(400); // past the 120 ms fade (and the tick backstop)
            ctx.waitTicks(3);
            boolean closed = ctx.computeOnClient(c -> !(c.currentScreen instanceof EvadeMenu));
            check(stillOpen && closed, "Escape fades the menu out, then closes it (" + stillOpen + ", " + closed + ")");

            EvadeMenu menu = ctx.computeOnClient(c -> { EvadeMenu m = new EvadeMenu(); c.setScreen(m); return m; });
            ft.addAndGet(400);
            ctx.waitTicks(2);
            double o2 = openedAt.get();
            ctx.setScreen(() -> new net.evadeclient.gui.HudEditorScreen(menu));
            ft.addAndGet(400);
            ctx.waitTicks(2);
            ctx.setScreen(() -> menu);
            ctx.waitTicks(2);
            check(openedAt.get() > o2, "Coming back from the HUD editor replays the menu's intro");
        } finally {
            EvadeMenu.clock = EvadeMenu.SYSTEM_CLOCK;
        }
        ctx.setScreen(() -> null);
    }

    private int visibleRowCount(ClientGameTestContext ctx) {
        return visibleNames(ctx).size();
    }

    /** Names of the mods the open menu lists right now (after search, filter and sort). */
    private List<String> visibleNames(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> {
            try {
                var mtd = EvadeMenu.class.getDeclaredMethod("visibleModules");
                mtd.setAccessible(true);
                List<String> out = new ArrayList<>();
                for (Object o : (List<?>) mtd.invoke(c.currentScreen)) out.add(((Module) o).getName());
                return out;
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    /** Evade Icons: a verified Evade user's tab-list name gets the icon glyph, and the tab list shows it. */
    private void testIcons(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String[] names = ctx.computeOnClient(c -> {
            var me = c.player.getUuid();
            var entry = c.getNetworkHandler().getPlayerListEntry(me);
            var hud = c.inGameHud.getPlayerListHud();
            String plain = hud.getPlayerName(entry).getString();
            net.evadeclient.module.misc.EvadeIcons.putForTest(me, true);
            String evade = hud.getPlayerName(entry).getString();
            return new String[]{plain, evade};
        });
        var server = sp.getServer();
        server.runCommand("scoreboard objectives add evadelist dummy");
        server.runCommand("scoreboard objectives setdisplay list evadelist");
        ctx.getInput().holdKey(GLFW.GLFW_KEY_TAB);
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-tab-icons");
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_TAB);
        server.runCommand("scoreboard objectives remove evadelist");
        ctx.runOnClient(c -> net.evadeclient.module.misc.EvadeIcons.putForTest(c.player.getUuid(), false));
        check(!names[0].startsWith("\ue000") && names[1].equals("\ue000 " + names[0]),
                "Evade Icons puts the Evade logo before Evade users' tab names (\"" + names[0] + "\" -> logo + name)");
    }

    /** Quick look at the menu: open it in a few themes and screenshot each (no other tests). */
    private void menuShots(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); });
            var cs = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class));
            for (String style : new String[]{"List", "Cards", "Compact"}) {
                ctx.runOnClient(c -> setMode(cs, "GUI Style", style));
                ctx.setScreen(EvadeMenu::new);
                ctx.getInput().setCursorPos(140 * 2, 200 * 2); // hover a sidebar item
                ctx.waitTicks(8);
                clearToasts(ctx);
                ctx.takeScreenshot("menushot-" + style.toLowerCase(Locale.ROOT));
            }
            ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
            ctx.waitTicks(8);
            ctx.takeScreenshot("menushot-settings");
            ctx.runOnClient(c -> setMode(cs, "GUI Style", "List"));
            ctx.runOnClient(c -> { setMode(cs, "Theme", "Modern"); setMode(cs, "Accent Color", "Red"); });
            // Small / high-scale screens (e.g. a laptop with Windows display scaling and Auto GUI scale).
            int[][] screens = {{1920, 1080, 4}, {1280, 720, 3}, {1366, 768, 4}};
            for (int[] sc : screens) {
                ctx.getInput().resizeWindow(sc[0], sc[1]);
                ctx.runOnClient(c -> { c.options.getGuiScale().setValue(sc[2]); c.onResolutionChanged(); });
                ctx.setScreen(EvadeMenu::new);
                ctx.waitTicks(8);
                clearToasts(ctx);
                ctx.takeScreenshot("menushot-" + sc[0] + "x" + sc[1] + "-gui" + sc[2]);
                ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
                ctx.waitTicks(8);
                ctx.takeScreenshot("menushot-" + sc[0] + "x" + sc[1] + "-gui" + sc[2] + "-settings");
                ctx.setScreen(() -> null);
                ctx.waitTicks(4);
                ctx.takeScreenshot("hudshot-" + sc[0] + "x" + sc[1] + "-gui" + sc[2]);
            }
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); });
        }
    }

    /** Customization: profile share codes, HUD visibility rules, per-widget background styles, Iris loaded. */
    private void testCustomization(ClientGameTestContext ctx) {
        // Share code round trip: copy Default's code, import it, and the copy has the same settings file.
        String[] r = ctx.computeOnClient(c -> {
            var pm = EvadeClient.profiles;
            String code = pm.shareCode(pm.active());
            String name = pm.importCode(code);
            String same;
            try {
                same = String.valueOf(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(pm.file(name)))
                        .equals(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(pm.file(pm.active())))));
            } catch (Exception e) { same = e.toString(); }
            String junk = String.valueOf(pm.importCode("not a code"));
            pm.delete(name);
            return new String[]{code == null ? "null" : code.substring(0, Math.min(12, code.length())), name, same, junk, String.valueOf(pm.exists(name))};
        });
        check(r[0].startsWith("EVADE1:") && "true".equals(r[2]) && "null".equals(r[3]) && "false".equals(r[4]),
                "Profile share codes round-trip and junk is rejected (" + r[0] + "... -> \"" + r[1] + "\", same=" + r[2] + ")");
        // A code survives being wrapped in text; each kind of bad code gets its own reason.
        String codes = ctx.computeOnClient(c -> {
            var pm = EvadeClient.profiles;
            StringBuilder p = new StringBuilder();
            String code = pm.shareCode(pm.active());
            var wrapped = pm.importProfile("my code: " + code.substring(0, code.length() / 2) + "\n" + code.substring(code.length() / 2) + " thanks");
            if (!wrapped.ok()) p.append("wrapped code: ").append(wrapped.error()).append("; ");
            else pm.delete(wrapped.name());
            var E = net.evadeclient.config.ProfileManager.ImportError.class;
            if (pm.importProfile("EVADE1:@@@").error() != Enum.valueOf(E, "INVALID")) p.append("EVADE1:@@@ isn't INVALID; ");
            if (pm.importProfile("hello").error() != Enum.valueOf(E, "NOT_A_CODE")) p.append("hello isn't NOT_A_CODE; ");
            if (pm.importProfile("").error() != Enum.valueOf(E, "EMPTY")) p.append("empty isn't EMPTY; ");
            if (pm.importProfile("EVADE2:abc").error() != Enum.valueOf(E, "NEWER")) p.append("EVADE2 isn't NEWER; ");
            return p.toString();
        });
        check(codes.isEmpty(), "Profile codes: wrapped in text still imports, bad codes say why (" + codes + ")");

        // Visibility rules and background styles on a HUD element.
        boolean[] v = ctx.computeOnClient(c -> {
            var fps = EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class);
            fps.combatOnly.set(true);
            net.evadeclient.util.Combat.reset();
            boolean hiddenIdle = !fps.shouldShow();
            net.evadeclient.util.Combat.hit();
            boolean shownInCombat = fps.shouldShow();
            fps.combatOnly.set(false);
            return new boolean[]{hiddenIdle, shownInCombat};
        });
        var fpsMod = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class));
        boolean fpsWas = ctx.computeOnClient(c -> {
            boolean was = fpsMod.isEnabled();
            fpsMod.setEnabled(true);
            HudElementModule.STYLE_PROBE = new java.util.HashMap<>();
            setMode(fpsMod, "Background Style", "Glass");
            return was;
        });
        ctx.waitTicks(3);
        String[] style = ctx.computeOnClient(c -> {
            String probed = HudElementModule.STYLE_PROBE.get("FPS");
            HudElementModule.STYLE_PROBE = null;
            setMode(fpsMod, "Background Style", "Evade");
            fpsMod.setEnabled(fpsWas);
            return new String[]{String.valueOf(probed), net.evadeclient.util.Theme.hudStyle};
        });
        check(v[0] && v[1], "HUD visibility rules: combat-only hides when idle, shows in a fight");
        check("Glass".equals(style[0]) && "Evade".equals(style[1]),
                "Per-widget background style is drawn and reset after drawing (" + style[0] + ", " + style[1] + ")");

        boolean iris = ctx.computeOnClient(c -> net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris"));
        check(iris, "Iris (the Shaders install option) loads alongside Evade and the FPS mods");
    }

    /** A broken config entry only skips itself; an unreadable file is kept as .corrupt; pre-split configs migrate. */
    private void testConfigRobustness(ClientGameTestContext ctx) {
        String problems = ctx.computeOnClient(c -> {
            var cm = EvadeClient.config;
            StringBuilder p = new StringBuilder();
            cm.save(); // so the live state can be loaded back afterwards
            var borders = EvadeClient.modules.get(net.evadeclient.module.visuals.ChunkBorders.class);
            boolean bordersWas = borders.isEnabled();
            try {
                var dir = java.nio.file.Files.createTempDirectory("evade-config-test");
                var broken = dir.resolve("broken.json");
                borders.setEnabled(false);
                java.nio.file.Files.writeString(broken, "{\"_format\":2,\"NoFog\":{\"key\":\"x\",\"settings\":7},\"Chunk Borders\":{\"enabled\":true}}");
                if (!cm.loadFrom(broken)) p.append("a broken entry failed the whole load; ");
                if (!borders.isEnabled()) p.append("a later module didn't load after a broken one; ");
                var junk = dir.resolve("junk.json");
                java.nio.file.Files.writeString(junk, "{oops");
                if (cm.loadFrom(junk)) p.append("an unreadable file loaded; ");
                if (!java.nio.file.Files.exists(dir.resolve("junk.json.corrupt"))) p.append("no .corrupt copy kept; ");
                var old = dir.resolve("old.json");
                java.nio.file.Files.writeString(old, "{\"Module List\":{\"enabled\":true,\"settings\":"
                        + "{\"Watermark\":false,\"Module List\":true,\"List Color\":\"Static Red\"}}}");
                if (!cm.loadFrom(old)) p.append("a pre-split config didn't load; ");
                var wm = EvadeClient.modules.get(net.evadeclient.module.client.WatermarkHud.class);
                var list = EvadeClient.modules.get(net.evadeclient.module.client.ModuleListHud.class);
                if (wm.isEnabled() || !list.isEnabled() || !list.colorMode.is("Accent"))
                    p.append("pre-split migration gave watermark ").append(wm.isEnabled()).append(", list ").append(list.isEnabled())
                            .append(" ").append(list.colorMode.get()).append("; ");
            } catch (Exception e) {
                p.append(e).append("; ");
            } finally {
                cm.load(); // back to the state saved above
                borders.setEnabled(bordersWas);
            }
            return p.toString();
        });
        check(problems.isEmpty(), "Config loading is robust and migrates old configs (" + problems + ")");
    }

    /**
     * Theme presets and accent colours (screenshots for the site), then two short clips for the website: the menu
     * opening, hovering, toggling and changing theme/style, and the HUD editor dragging an element. Animations run
     * on a fixed 30 fps clock here so the recordings are smooth whatever the test machine's speed.
     */
    private void testThemesAndClips(ClientGameTestContext ctx, TestSingleplayerContext sp, net.evadeclient.module.client.ClientSettings cs) {
        ctx.runOnClient(c -> setMode(cs, "GUI Style", "List"));
        String[][] looks = {{"Midnight", "Red"}, {"OLED", "Purple"}, {"Classic", "Green"}, {"Modern", "Blue"}};
        for (String[] l : looks) {
            ctx.runOnClient(c -> { setMode(cs, "Theme", l[0]); setMode(cs, "Accent Color", l[1]); });
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(6);
            clearToasts(ctx);
            ctx.takeScreenshot("evade-theme-" + l[0].toLowerCase(Locale.ROOT));
        }
        int[] seen = ctx.computeOnClient(c -> {
            setMode(cs, "Theme", "Classic"); setMode(cs, "Accent Color", "Blue"); cs.applyTheme();
            int classicRed = net.evadeclient.util.Theme.RED, classicSq = net.evadeclient.util.Theme.square ? 1 : 0, classicCard = net.evadeclient.util.Theme.CARD;
            setMode(cs, "Theme", "Modern"); setMode(cs, "Accent Color", "Red"); cs.applyTheme();
            return new int[]{classicRed, classicSq, classicCard, net.evadeclient.util.Theme.RED, net.evadeclient.util.Theme.square ? 1 : 0, net.evadeclient.util.Theme.CARD};
        });
        check(seen[0] == 0xFF2F7BFF && seen[1] == 1 && seen[3] == 0xFFE11D2E && seen[4] == 0 && seen[2] != seen[5],
                "Theme presets and accent colours restyle the client (Classic + Blue -> square corners, blue accent; back to Modern + Red)");

        // Keep the camera on the scene: cursor moves during the menu clip would otherwise turn the player.
        float[] look = ctx.computeOnClient(c -> new float[]{c.player.getYaw(), 0f}); // level, like the HUD shot
        Runnable faceScene = () -> ctx.runOnClient(c -> { c.player.setYaw(look[0]); c.player.setPitch(look[1]);
            c.player.lastYaw = look[0]; c.player.lastPitch = look[1]; });

        // --- clip 1: the menu
        clearToasts(ctx);
        java.util.concurrent.atomic.AtomicLong t = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        EvadeMenu.clock = t::get;
        var in = ctx.getInput();
        Runnable frame = () -> { t.addAndGet(33); ctx.takeScreenshot("clip-menu"); };
        try {
            in.setCursorPos(1880, 1040);
            ctx.setScreen(EvadeMenu::new);
            for (int i = 0; i < 14; i++) frame.run();
            for (int i = 0; i <= 30; i++) { in.setCursorPos(800, (140 + i * 4) * 2); frame.run(); }
            in.setCursorPos(800, 140 * 2);
            for (int i = 0; i < 6; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 16; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 14; i++) frame.run();
            // Sidebar: the highlight glides down to HUD while the list fades and rises in, then back up to All Mods.
            in.setCursorPos(280, 524);
            for (int i = 0; i < 4; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 14; i++) frame.run();
            in.setCursorPos(280, 212);
            for (int i = 0; i < 4; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 14; i++) frame.run();
            // A mod's settings slide in from the right (right-click the first row), Back slides the list in from the left.
            in.setCursorPos(800, 280);
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            for (int i = 0; i < 16; i++) frame.run();
            in.setCursorPos(572, 120);
            for (int i = 0; i < 4; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 16; i++) frame.run();
            check(ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu),
                    "Sidebar clicks and a mod page's Back keep the menu open (page fade-through, sliding highlight)");
            // Settings page: hover and press the Menu Key box (it dips and rebounds), let it pulse, then Escape.
            in.setCursorPos(280, 788);
            for (int i = 0; i < 4; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 6; i++) frame.run();
            in.setCursorPos(1680, 258);
            for (int i = 0; i < 6; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 30; i++) frame.run(); // listening: the border breathes, the dots count
            in.pressKey(GLFW.GLFW_KEY_ESCAPE);
            for (int i = 0; i < 10; i++) frame.run();
            // Back to All Mods so the theme/accent steps run where they did.
            in.setCursorPos(280, 212);
            for (int i = 0; i < 4; i++) frame.run();
            in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 10; i++) frame.run();
            check(ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu
                            && EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class).menuKey.get() == GLFW.GLFW_KEY_RIGHT_SHIFT),
                    "Menu Key press/listen/Escape in the clip leaves the key unchanged and the menu open");
            in.setCursorPos(1880, 1040);
            String[][] steps = {{"Theme", "Midnight"}, {"Accent Color", "Purple"}, {"Theme", "Classic"}, {"Accent Color", "Green"},
                    {"Theme", "OLED"}, {"Accent Color", "Blue"}, {"GUI Style", "Cards"}, {"GUI Style", "Compact"},
                    {"Theme", "Modern"}, {"Accent Color", "Red"}, {"GUI Style", "List"}};
            for (String[] st : steps) {
                ctx.runOnClient(c -> setMode(cs, st[0], st[1]));
                for (int i = 0; i < 18; i++) frame.run();
            }
        } finally {
            EvadeMenu.clock = EvadeMenu.SYSTEM_CLOCK;
        }

        // --- clip 2: the HUD editor (drag an element, watch it snap, resize it, put it back)
        // Recording renders one frame at a time, so hide the FPS counter (it would read ~5) and re-apply the
        // absorption effect without particles (its blue swirls fly right past the camera between frames).
        sp.getServer().runCommand("effect give @a absorption 120 1 true");
        boolean fpsWasOn = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).isEnabled());
        ctx.runOnClient(c -> { EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).setEnabled(false); c.particleManager.clearParticles(); });
        ctx.setScreen(() -> new net.evadeclient.gui.HudEditorScreen(null));
        ctx.waitTicks(3);
        faceScene.run();
        int[] el = ctx.computeOnClient(c -> {
            var w = c.getWindow();
            var coords = EvadeClient.modules.get(net.evadeclient.module.hud.CoordsHud.class);
            for (var e : EvadeClient.modules.hudElements()) if (e == coords && e.isEnabled())
                return new int[]{EvadeClient.modules.hudElements().indexOf(e), e.x, e.y,
                        e.resolveX(w.getScaledWidth()) + e.scaledWidth() / 2, e.resolveY(w.getScaledHeight()) + e.scaledHeight() / 2,
                        Math.round(e.scale.getFloat() * 100)};
            return null;
        });
        check(el != null, "Coordinates is on for the HUD editor clip");
        Runnable shot = () -> ctx.takeScreenshot("clip-hud");
        int sx = el[3] * 2, sy = el[4] * 2;
        in.setCursorPos(sx, sy);
        for (int i = 0; i < 10; i++) shot.run();
        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        int[][] path = {{960, 540}, {1500, 300}, {sx, sy}};
        int px = sx, py = sy;
        boolean moved = false;
        for (int[] to : path) {
            for (int i = 1; i <= 30; i++) {
                double k = i / 30.0, e = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
                in.setCursorPos(px + (to[0] - px) * e, py + (to[1] - py) * e);
                shot.run();
            }
            for (int i = 0; i < 8; i++) shot.run();
            if (to == path[0]) moved = ctx.computeOnClient(c -> {
                var e = EvadeClient.modules.hudElements().get(el[0]);
                return e.x != el[1] || e.y != el[2];
            });
            px = to[0]; py = to[1];
        }
        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 0; i < 4; i++) { in.scroll(1); shot.run(); shot.run(); }
        int scaledUp = ctx.computeOnClient(c -> Math.round(EvadeClient.modules.hudElements().get(el[0]).scale.getFloat() * 100));
        for (int i = 0; i < 4; i++) { in.scroll(-1); shot.run(); shot.run(); }
        int scaledBack = ctx.computeOnClient(c -> Math.round(EvadeClient.modules.hudElements().get(el[0]).scale.getFloat() * 100));
        for (int i = 0; i < 10; i++) shot.run();
        ctx.runOnClient(c -> {
            var e = EvadeClient.modules.hudElements().get(el[0]);
            e.x = el[1]; e.y = el[2];
            e.scale.fromJson(new com.google.gson.JsonPrimitive(el[5] / 100.0));
        });
        check(moved, "Dragging a HUD element in the editor moves it");
        check(scaledUp == el[5] + 20 && scaledBack == el[5], "Scrolling over a HUD element resizes it in 5% steps ("
                + el[5] + "% -> " + scaledUp + "% -> " + scaledBack + "%)");

        // Grid snap: with the grid on (and no other element to align to), a drop at an off-grid point lands on the grid.
        List<String> others = ctx.computeOnClient(c -> {
            List<String> off = new ArrayList<>();
            var coords = EvadeClient.modules.hudElements().get(el[0]);
            for (var e : EvadeClient.modules.hudElements()) if (e != coords && e.isEnabled()) { e.setEnabled(false); off.add(e.getName()); }
            EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class).hudGrid.set(true);
            return off;
        });
        ctx.waitTicks(2);
        in.setCursorPos(sx, sy);
        ctx.waitTicks(1);
        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 1; i <= 10; i++) { in.setCursorPos(sx + (403 - sx) * i / 10.0, sy + (317 - sy) * i / 10.0); ctx.waitTicks(1); }
        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(2);
        int[] snapped = ctx.computeOnClient(c -> {
            var e = EvadeClient.modules.hudElements().get(el[0]);
            float k = net.evadeclient.hud.Hud.scale();
            int w = Math.round(c.getWindow().getScaledWidth() / k), h = Math.round(c.getWindow().getScaledHeight() / k);
            int[] r = {e.resolveX(w), e.resolveY(h)};
            e.x = el[1]; e.y = el[2];
            EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class).hudGrid.reset();
            for (var o : EvadeClient.modules.hudElements()) if (others.contains(o.getName())) o.setEnabled(true);
            return r;
        });
        ctx.setScreen(() -> null);
        check(snapped[0] % 8 == 0 && snapped[1] % 8 == 0, "HUD editor grid snaps a dropped element to the grid (" + snapped[0] + ", " + snapped[1] + ")");

        // --- clip 3: in game. Zoom in on the scene, scroll to zoom further, let go, then look around with Freelook.
        boolean[] camWas = ctx.computeOnClient(c -> new boolean[]{
                EvadeClient.modules.get(net.evadeclient.module.mods.Zoom.class).isEnabled(),
                EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class).isEnabled()});
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(net.evadeclient.module.mods.Zoom.class).setEnabled(true);
            EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class).setEnabled(true);
            c.particleManager.clearParticles();
        });
        ctx.waitTicks(2);
        faceScene.run();
        ctx.waitTicks(2);
        clearToasts(ctx);
        t.set(2_000_000);
        EvadeMenu.clock = t::get;
        Runnable play = () -> { t.addAndGet(33); ctx.takeScreenshot("clip-play"); };
        try {
            for (int i = 0; i < 15; i++) play.run();
            in.holdKey(GLFW.GLFW_KEY_C);
            for (int i = 0; i < 30; i++) play.run();
            in.scroll(1);
            for (int i = 0; i < 12; i++) play.run();
            in.scroll(1);
            for (int i = 0; i < 24; i++) play.run();
            in.releaseKey(GLFW.GLFW_KEY_C);
            for (int i = 0; i < 18; i++) play.run();
            in.holdKey(GLFW.GLFW_KEY_LEFT_ALT);
            for (int i = 0; i < 4; i++) play.run();
            for (int i = 0; i < 60; i++) { in.moveCursor(i < 30 ? 14 : -14, i % 30 < 15 ? -2 : 2); play.run(); }
            in.releaseKey(GLFW.GLFW_KEY_LEFT_ALT);
            for (int i = 0; i < 15; i++) play.run();
        } finally {
            EvadeMenu.clock = EvadeMenu.SYSTEM_CLOCK;
        }
        ctx.runOnClient(c -> {
            setMode(cs, "GUI Style", "Compact");
            EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).setEnabled(fpsWasOn);
            EvadeClient.modules.get(net.evadeclient.module.mods.Zoom.class).setEnabled(camWas[0]);
            EvadeClient.modules.get(net.evadeclient.module.mods.Freelook.class).setEnabled(camWas[1]);
            c.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
        });
    }

    // ---- profiles -----------------------------------------------------------------------------------------------

    /** Profiles: each one remembers its own module state, switching restores it, and server links switch for you. */
    private void testProfiles(ClientGameTestContext ctx) {
        boolean ok = ctx.computeOnClient(c -> {
            var P = EvadeClient.profiles;
            NoFog nf = EvadeClient.modules.get(NoFog.class);
            if (!"Default".equals(P.active())) P.switchTo("Default");
            P.delete("GTProfile"); // clean slate if a previous run left one
            nf.setEnabled(true);
            EvadeClient.config.save();          // Default: NoFog ON
            P.create("GTProfile");              // snapshot (ON), now active
            nf.setEnabled(false);
            EvadeClient.config.save();          // GTProfile: NoFog OFF
            P.switchTo("Default");
            boolean defaultOn = nf.isEnabled(); // expect true
            P.switchTo("GTProfile");
            boolean testOff = !nf.isEnabled();  // expect true (NoFog off)
            P.switchTo("Default");
            P.delete("GTProfile");
            return defaultOn && testOff;
        });
        check(ok, "Profiles keep separate module state and restore on switch");

        // Server links: addresses are normalised, joining switches (once), leaving switches back, deleting unlinks.
        String links = ctx.computeOnClient(c -> {
            var P = EvadeClient.profiles;
            StringBuilder p = new StringBuilder();
            if (!"Default".equals(P.active())) P.switchTo("Default");
            P.delete("GTSrv");
            P.create("GTSrv");
            P.switchTo("Default");
            P.toggleServer("GTSrv", " Play.Example.NET ");
            if (!P.serversFor("GTSrv").equals(List.of("play.example.net"))) p.append("servers ").append(P.serversFor("GTSrv")).append("; ");
            if (!P.isLinked("GTSrv", "play.example.net:25565")) p.append("not linked with the default port; ");
            String joined = P.onJoinServer("PLAY.example.net");
            if (!"GTSrv".equals(joined) || !"GTSrv".equals(P.active())) p.append("join switched to ").append(joined).append("; ");
            if (P.onJoinServer("play.example.net") != null) p.append("a second join switched again; ");
            String back = P.onLeaveServer();
            if (!"Default".equals(back) || !"Default".equals(P.active())) p.append("leaving went back to ").append(back).append("; ");
            if (P.list().contains("servers")) p.append("the links file shows as a profile; ");
            if (!"servers profile".equals(net.evadeclient.config.ProfileManager.sanitize("servers"))) p.append("sanitize(servers); ");
            P.delete("GTSrv");
            if (!P.serversFor("GTSrv").isEmpty()) p.append("deleting kept its links; ");
            return p.toString();
        });
        check(links.isEmpty(), "Server-linked profiles switch on join and back on leave (" + links + ")");

        // Create a couple of profiles so the page looks real, then screenshot it.
        ctx.runOnClient(c -> {
            EvadeClient.profiles.create("PvP");
            EvadeClient.profiles.create("SkyBlock");
            EvadeClient.profiles.switchTo("Default");
        });
        ctx.setScreen(EvadeMenu::new);
        ctx.runOnClient(c -> EvadeMenu.openProfilesForTest());
        ctx.waitTicks(5);
        clearToasts(ctx);
        ctx.takeScreenshot("evade-profiles");
        ctx.runOnClient(c -> { EvadeClient.profiles.delete("PvP"); EvadeClient.profiles.delete("SkyBlock"); });
        ctx.setScreen(() -> null);
    }
}
