package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.ChatCompletion;
import net.evadeclient.config.MenuState;
import net.evadeclient.config.ModShare;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.gui.FirstRun;
import net.evadeclient.gui.KeybindsScreen;
import net.evadeclient.gui.SetupWizardScreen;
import net.evadeclient.gui.WaypointManagerScreen;
import net.evadeclient.module.Module;
import net.evadeclient.module.hud.CoordsHud;
import net.evadeclient.module.misc.StreamerMode;
import net.evadeclient.module.mods.NoHurtCam;
import net.evadeclient.module.mods.TimeChanger;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.module.visuals.NickHider;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.ChatCoordinates;
import net.evadeclient.util.Keybinds;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The v2.3 menu upgrades: dot-command Tab completion, .binds/.reset/.profile/.share/.import, Toggle/Hold binds with
 * modifiers and conflict detection, the Keybinds page, pinned mods, per-mod share codes, the Waypoint Manager and
 * waypoint icons/beam/range, clickable chat coordinates, Streamer Mode and the first-run setup (opened by hand: it
 * never opens by itself in tests).
 */
public final class MenuUpgradesTest {
    private MenuUpgradesTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        // Every module's on/off state, put back at the end (the setup's play styles change them).
        Map<String, Boolean> enabled = ctx.computeOnClient(c -> {
            Map<String, Boolean> m = new HashMap<>();
            for (Module mod : EvadeClient.modules.all()) m.put(mod.getName(), mod.isEnabled());
            return m;
        });

        completion(ctx);
        commands(ctx);
        binds(ctx);
        menu(ctx);
        waypoints(ctx);
        chatCoordinates(ctx);
        streamer(ctx);
        setup(ctx);

        ctx.setScreen(() -> null);
        ctx.runOnClient(c -> {
            for (Module mod : EvadeClient.modules.all()) {
                Boolean was = enabled.get(mod.getName());
                if (was != null && mod.isEnabled() != was) mod.setEnabled(was);
            }
            EvadeClient.config.save();
        });
        ctx.waitTicks(2);
    }

    // ---- Tab completion ---------------------------------------------------

    private static void completion(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            StringBuilder p = new StringBuilder();
            var cmd = ChatCompletion.complete(".tog");
            if (!cmd.options().contains("toggle") || !"gle".equals(cmd.ghost())) p.append("command word ").append(cmd).append("; ");
            var mod = ChatCompletion.complete(".toggle Zo");
            if (!mod.options().contains("Zoom") || !"om".equals(mod.ghost())) p.append("mod name ").append(mod).append("; ");
            var spaced = ChatCompletion.complete(".toggle nick h");
            if (!spaced.options().contains("Nick Hider")) p.append("spaced mod name ").append(spaced.options()).append("; ");
            var setting = ChatCompletion.complete(".set Zoom ");
            if (!setting.options().contains("Max Zoom")) p.append("setting names ").append(setting.options()).append("; ");
            var bool = ChatCompletion.complete(".set Zoom Scroll To Zoom ");
            if (bool.options().isEmpty()) p.append("setting values ").append(bool).append("; ");
            var prof = ChatCompletion.complete(".profile load ");
            if (!prof.options().contains("Default")) p.append("profile names ").append(prof.options()).append("; ");
            var wp = ChatCompletion.complete(".wp ");
            if (!wp.options().contains("gui")) p.append("wp subcommands ").append(wp.options()).append("; ");
            if (!ChatCompletion.complete("hello").options().isEmpty() || !ChatCompletion.complete("..help").options().isEmpty())
                p.append("plain chat got options; ");
            return p.toString();
        });
        check(r.isEmpty(), "Tab completion offers commands, mods, settings, values and profiles (" + r + ")");

        // In the real chat box: ghost text, then Tab completes and cycles.
        ctx.setScreen(() -> new ChatScreen(".toggle Zo", false));
        ctx.waitTicks(3);
        ctx.takeScreenshot("evade-chat-completion");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_TAB);
        ctx.waitTicks(2);
        String after = ctx.computeOnClient(c -> field(c.currentScreen).getText());
        ctx.getInput().pressKey(GLFW.GLFW_KEY_TAB);
        ctx.waitTicks(2);
        String cycled = ctx.computeOnClient(c -> field(c.currentScreen).getText());
        ctx.setScreen(() -> null);
        check(after.equals(".toggle Zoom") && cycled.startsWith(".toggle ") && !cycled.equals(".toggle Zo"),
                "Tab in chat completes a dot command and cycles (" + after + " -> " + cycled + ")");
    }

    private static TextFieldWidget field(Object screen) {
        try {
            var f = ChatScreen.class.getDeclaredField("chatField");
            f.setAccessible(true);
            return (TextFieldWidget) f.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("chatField unreadable: " + e);
        }
    }

    // ---- .reset / .share / .import / .profile / .binds ------------------------

    private static void commands(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            StringBuilder p = new StringBuilder();
            Zoom zoom = EvadeClient.modules.get(Zoom.class);
            NumberSetting max = (NumberSetting) setting(zoom, "Max Zoom");
            double def = max.getDefault();
            max.set(max.getMax());
            EvadeClient.commands.handleChat(".reset zoom max zoom");
            if (max.get() != def) p.append(".reset one setting; ");
            max.set(max.getMax());
            // Per-mod code: copy, change, paste back.
            EvadeClient.commands.handleChat(".share zoom");
            String code = c.keyboard.getClipboard();
            if (code == null || !code.startsWith(ModShare.PREFIX)) p.append(".share clipboard ").append(code).append("; ");
            EvadeClient.commands.handleChat(".reset zoom");
            if (max.get() != def) p.append(".reset all; ");
            EvadeClient.commands.handleChat(".import");
            if (max.get() != max.getMax()) p.append(".import didn't restore (").append(max.get()).append("); ");
            var wrong = ModShare.apply(code, EvadeClient.modules.get(NoHurtCam.class));
            if (wrong.ok()) p.append("a Zoom code applied to another mod; ");
            if (ModShare.apply("EVADE1:abc", null).ok() || ModShare.apply("hello", null).ok()) p.append("junk accepted; ");
            max.reset();
            EvadeClient.commands.handleChat(".profile list");
            EvadeClient.commands.handleChat(".binds");
            EvadeClient.commands.handleChat(".binds conflicts");
            return p.toString();
        });
        check(r.isEmpty(), ".reset, .share and .import (EVMD1 codes) work (" + r + ")");
    }

    private static Setting<?> setting(Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return s;
        throw new AssertionError("no setting " + name + " on " + m.getName());
    }

    // ---- Toggle / Hold binds, modifiers, conflicts ---------------------------

    private static void binds(ClientGameTestContext ctx) {
        int hold = GLFW.GLFW_KEY_KP_8, combo = GLFW.GLFW_KEY_KP_9;
        ctx.runOnClient(c -> {
            var nhc = EvadeClient.modules.get(NoHurtCam.class);
            nhc.setEnabled(false);
            nhc.setKey(hold);
            nhc.setHoldBind(true);
            var tc = EvadeClient.modules.get(TimeChanger.class);
            tc.setEnabled(false);
            tc.setKey(combo);
            tc.setBindMods(Keybinds.CTRL);
        });
        ctx.getInput().holdKey(hold);
        ctx.waitTicks(3);
        boolean onWhileHeld = ctx.computeOnClient(c -> EvadeClient.modules.get(NoHurtCam.class).isEnabled());
        ctx.getInput().releaseKey(hold);
        ctx.waitTicks(3);
        boolean offAfter = ctx.computeOnClient(c -> !EvadeClient.modules.get(NoHurtCam.class).isEnabled());
        check(onWhileHeld && offAfter, "Hold bind: on while the key is held, off when released (" + onWhileHeld + ", " + offAfter + ")");

        ctx.getInput().pressKey(combo);
        ctx.waitTicks(3);
        boolean plain = ctx.computeOnClient(c -> EvadeClient.modules.get(TimeChanger.class).isEnabled());
        // Right Ctrl: Left Ctrl is the game's sprint key, which Toggle Sprint would toggle.
        ctx.getInput().holdKey(GLFW.GLFW_KEY_RIGHT_CONTROL);
        ctx.getInput().pressKey(combo);
        ctx.getInput().releaseKey(GLFW.GLFW_KEY_RIGHT_CONTROL);
        ctx.waitTicks(3);
        boolean withCtrl = ctx.computeOnClient(c -> EvadeClient.modules.get(TimeChanger.class).isEnabled());
        check(!plain && withCtrl, "Ctrl + key bind: the key alone does nothing, Ctrl + key toggles (" + plain + ", " + withCtrl + ")");

        String conf = ctx.computeOnClient(c -> {
            StringBuilder p = new StringBuilder();
            var nhc = EvadeClient.modules.get(NoHurtCam.class);
            var tc = EvadeClient.modules.get(TimeChanger.class);
            nhc.setKey(combo);
            if (!Keybinds.conflictNames(combo, 0, nhc).isEmpty()) p.append("plain vs Ctrl combo flagged; ");
            nhc.setBindMods(Keybinds.CTRL);
            if (!Keybinds.conflictNames(combo, Keybinds.CTRL, nhc).contains(tc.getName())) p.append("same combo not flagged; ");
            int forward = Keybinds.gameCode(c.options.forwardKey);
            if (Keybinds.conflictNames(forward, 0, nhc).isEmpty()) p.append("vanilla Forward clash not flagged; ");
            // Saved with the config.
            nhc.setHoldBind(true);
            EvadeClient.config.save();
            try {
                String json = Files.readString(EvadeClient.config.file());
                if (!json.contains("\"bindMode\": \"hold\"") || !json.contains("\"bindMods\": 1")) p.append("not saved; ");
            } catch (Exception e) {
                p.append(e).append("; ");
            }
            for (Module m : List.<Module>of(nhc, tc)) {
                m.setKey(GLFW.GLFW_KEY_UNKNOWN);
                m.setBindMods(0);
                m.setHoldBind(false);
                m.setEnabled(false);
            }
            EvadeClient.config.save();
            return p.toString();
        });
        check(conf.isEmpty(), "Keybind conflicts: same key + modifiers and game controls clash; modes are saved (" + conf + ")");
    }

    // ---- Keybinds page, pinned mods --------------------------------------------

    @SuppressWarnings("unchecked")
    private static void menu(ClientGameTestContext ctx) {
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(3);
        ctx.runOnClient(c -> KeybindsScreen.openFromCurrent());
        ctx.waitTicks(8);
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.takeScreenshot("evade-keybinds");
        boolean open = ctx.computeOnClient(c -> c.currentScreen instanceof KeybindsScreen);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(3);
        boolean back = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu);
        check(open && back, "The Keybinds page opens from the menu and Escape returns to it (" + open + ", " + back + ")");

        // Pin Zoom: the Pinned page lists just it.
        List<String> pinned = ctx.computeOnClient(c -> {
            Zoom zoom = EvadeClient.modules.get(Zoom.class);
            for (Module m : EvadeClient.modules.all()) MenuState.setPinned(m, false);
            MenuState.setPinned(zoom, true);
            try {
                var pageField = EvadeMenu.class.getDeclaredField("page");
                pageField.setAccessible(true);
                Class<?> pageType = pageField.getType();
                Object pinnedPage = null;
                for (Object o : pageType.getEnumConstants()) if (((Enum<?>) o).name().equals("PINNED")) pinnedPage = o;
                pageField.set(null, pinnedPage);
                EvadeMenu menu = new EvadeMenu();
                c.setScreen(menu);
                var mtd = EvadeMenu.class.getDeclaredMethod("visibleModules");
                mtd.setAccessible(true);
                return ((List<Module>) mtd.invoke(menu)).stream().map(Module::getName).toList();
            } catch (ReflectiveOperationException e) {
                return List.of("error: " + e);
            }
        });
        ctx.waitTicks(8);
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.takeScreenshot("evade-menu-pinned");
        ctx.runOnClient(c -> {
            MenuState.setPinned(EvadeClient.modules.get(Zoom.class), false);
            try {
                var pageField = EvadeMenu.class.getDeclaredField("page");
                pageField.setAccessible(true);
                for (Object o : pageField.getType().getEnumConstants()) if (((Enum<?>) o).name().equals("ALL")) pageField.set(null, o);
            } catch (ReflectiveOperationException ignored) {}
        });
        check(pinned.equals(List.of("Zoom")), "The Pinned page shows pinned mods only (" + pinned + ")");

        // A mod page with the Key Mode and Share rows.
        ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
        ctx.waitTicks(8);
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.takeScreenshot("evade-menu-keymode");
        ctx.setScreen(() -> null);
    }

    // ---- Waypoint Manager, icons, beam, range ------------------------------------

    private static void waypoints(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            StringBuilder p = new StringBuilder();
            Waypoints w = EvadeClient.modules.get(Waypoints.class);
            BlockPos me = c.player.getBlockPos();
            var near = w.addAt(w.freeName("Manager Test"), null, me.add(4, 0, 0));
            var far = w.addAt(w.freeName("Far Test"), null, me.add(200, 0, 0));
            if (near == null || far == null) return "couldn't add";
            w.replacePoint(near, near.withIcon("Home").withBeam(Waypoints.Point.BEAM_OFF));
            w.replacePoint(far, far.withRange(64));
            var nearNow = w.allInWorld().stream().filter(x -> x.name().equals(near.name())).findFirst().orElse(null);
            if (nearNow == null || !"Home".equals(nearNow.icon()) || nearNow.beam() != Waypoints.Point.BEAM_OFF) p.append("edit not kept; ");
            boolean farShown = w.visible().stream().anyMatch(s -> s.point().name().equals(far.name()));
            if (farShown) p.append("range ignored; ");
            // Delete with undo.
            int i = w.deletePoint(nearNow);
            if (i < 0 || w.allInWorld().contains(nearNow)) p.append("delete; ");
            w.restorePoint(nearNow, i);
            if (!w.allInWorld().contains(nearNow)) p.append("undo; ");
            if (!Waypoints.shareText(nearNow).contains("X " + nearNow.x())) p.append("share text; ");
            return p.toString();
        });
        check(r.isEmpty(), "Waypoints: icon, beam and range edits are kept, range hides far points, delete has undo (" + r + ")");

        ctx.setScreen(() -> new WaypointManagerScreen(null));
        ctx.waitTicks(8);
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.takeScreenshot("evade-waypoint-manager");
        ctx.setScreen(() -> null);
        ctx.runOnClient(c -> EvadeClient.commands.handleChat(".wp gui"));
        ctx.waitTicks(3);
        boolean gui = ctx.computeOnClient(c -> c.currentScreen instanceof WaypointManagerScreen);
        ctx.setScreen(() -> null);
        check(gui, ".wp gui opens the Waypoint Manager");
        ctx.runOnClient(c -> {
            Waypoints w = EvadeClient.modules.get(Waypoints.class);
            for (var pt : w.allInWorld()) if (pt.name().startsWith("Manager Test") || pt.name().startsWith("Far Test")) w.deletePoint(pt);
        });
    }

    // ---- Clickable chat coordinates ----------------------------------------------

    private static void chatCoordinates(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> c.inGameHud.getChatHud().addMessage(Text.literal("Meet me at 120 64 -300 by the lake")));
        ctx.setScreen(() -> new ChatScreen("", false));
        ctx.waitTicks(3);
        String r = ctx.computeOnClient(c -> {
            int h = c.getWindow().getScaledHeight();
            ChatCoordinates.Hit hit = null;
            int hx = 0, hy = 0;
            for (int y = h - 60; y < h - 10 && hit == null; y += 2)
                for (int x = 2; x < 330 && hit == null; x += 2) { hit = ChatCoordinates.at(c, c.textRenderer, x, y); hx = x; hy = y; }
            if (hit == null) return "no clickable coordinates found";
            if (hit.x() != 120 || hit.y() != 64 || hit.z() != -300) return "wrong values " + hit;
            int before = EvadeClient.modules.get(Waypoints.class).allInWorld().size();
            if (!ChatCoordinates.save(hit)) return "save failed";
            var w = EvadeClient.modules.get(Waypoints.class);
            var saved = w.allInWorld().stream().filter(pt -> pt.x() == 120 && pt.y() == 64 && pt.z() == -300).findFirst().orElse(null);
            if (saved == null || w.allInWorld().size() != before + 1) return "not saved";
            w.deletePoint(saved);
            return "ok@" + hx + "," + hy;
        });
        ctx.setScreen(() -> null);
        check(r.startsWith("ok"), "Coordinates in chat are clickable and save a waypoint (" + r + ")");
    }

    // ---- Streamer Mode ------------------------------------------------------------

    private static void streamer(ClientGameTestContext ctx) {
        boolean[] r = ctx.computeOnClient(c -> {
            var nh = EvadeClient.modules.get(NickHider.class);
            boolean nickBefore = nh.isEnabled();
            var coords = EvadeClient.modules.get(CoordsHud.class);
            boolean coordsWas = coords.isEnabled();
            coords.setEnabled(true);
            var sm = EvadeClient.modules.get(StreamerMode.class);
            sm.setEnabled(true);
            boolean hidden = String.join(" ", coords.textForTest()).contains("Streamer");
            boolean wpHidden = EvadeClient.modules.get(Waypoints.class).coordsHidden();
            boolean nickOn = nh.isEnabled();
            return new boolean[]{hidden, wpHidden, nickOn, nickBefore, coordsWas};
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("evade-streamer-mode");
        boolean restored = ctx.computeOnClient(c -> {
            EvadeClient.modules.get(StreamerMode.class).setEnabled(false);
            EvadeClient.modules.get(CoordsHud.class).setEnabled(r[4]);
            return EvadeClient.modules.get(NickHider.class).isEnabled() == r[3];
        });
        check(r[0] && r[1] && r[2] && restored, "Streamer Mode hides coordinates in Evade's widgets and waypoints, switches Nick Hider on and back ("
                + r[0] + ", " + r[1] + ", " + r[2] + ", " + restored + ")");
    }

    // ---- First-run setup --------------------------------------------------------

    private static void setup(ClientGameTestContext ctx) {
        boolean auto = ctx.computeOnClient(c -> FirstRun.inGameTest());
        ctx.setScreen(() -> FirstRun.wizard(null));
        ctx.waitTicks(8);
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.takeScreenshot("evade-setup-styles");
        String r = ctx.computeOnClient(c -> {
            if (!(c.currentScreen instanceof SetupWizardScreen w)) return "not open";
            w.chooseForTest("Minimal");
            w.nextForTest();
            StringBuilder p = new StringBuilder();
            if (!EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).isEnabled()) p.append("FPS off; ");
            if (EvadeClient.modules.get(net.evadeclient.module.hud.CpsHud.class).isEnabled()) p.append("CPS still on; ");
            if (w.stepForTest() != 1) p.append("step ").append(w.stepForTest()).append("; ");
            if (!FirstRun.pick(240, 120).equals("Quality") || !FirstRun.pick(100, 50).equals("Balanced") || !FirstRun.pick(40, 20).equals("Max FPS"))
                p.append("tune pick; ");
            return p.toString();
        });
        ctx.waitTicks(4);
        ctx.takeScreenshot("evade-setup-tune");
        ctx.runOnClient(c -> { if (c.currentScreen instanceof SetupWizardScreen w) w.nextForTest(); });
        ctx.waitTicks(4);
        ctx.takeScreenshot("evade-setup-tips");
        ctx.setScreen(() -> null);
        check(auto && r.isEmpty(), "First-run setup: never opens by itself in tests; a play style applies; tune thresholds (" + r + ")");
    }
}
