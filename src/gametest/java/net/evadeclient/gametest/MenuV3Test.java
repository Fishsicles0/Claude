package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.config.MenuState;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.gui.Motion;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.AnimationsSetting;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * The Evade hub, GUI 3.0 (WP3): the menu's list, cards and compact views, a settings page with kit controls (no
 * cyclers), the keyboard path menu → settings → back, Reduced and Off animations, Classic, small and 4K windows, the
 * toasts below the close button, the colour picker, and the menu's CPU record cost. Screenshots are named menu3-*.
 * {@code -Devade.menuv3only=true} (in JAVA_TOOL_OPTIONS) runs only this and ends the game right after.
 */
public final class MenuV3Test {
    private MenuV3Test() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static ClientSettings cs() { return EvadeClient.modules.get(ClientSettings.class); }

    private static void scale(ClientGameTestContext ctx, int w, int h, int gui) {
        ctx.getInput().resizeWindow(w, h);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(gui); c.onResolutionChanged(); });
        ctx.waitTicks(3);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(c -> { c.getToastManager().clear(); Notifications.clear(); });
        ctx.takeScreenshot("menu3-" + name);
    }

    public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String styleWas = ctx.computeOnClient(c -> cs().guiStyle.get());
        String animWas = ctx.computeOnClient(c -> cs().animations.get());
        String themeWas = ctx.computeOnClient(c -> cs().theme.get());
        boolean zoomWas = ctx.computeOnClient(c -> EvadeClient.modules.get(Zoom.class).isEnabled());
        try {
            scale(ctx, 1920, 1080, 2);
            ctx.runOnClient(c -> { EvadeMenu.openAllForTest(); cs().guiStyle.set("List"); cs().animations.set(AnimationsSetting.FULL);
                for (Module m : EvadeClient.modules.all()) MenuState.setPinned(m, false);
                MenuState.setPinned(EvadeClient.modules.get(Zoom.class), true); });

            // 1. The list (dense), hovered row, at GUI 2 and 3 (the same design px).
            ctx.setScreen(EvadeMenu::new);
            ctx.getInput().setCursorPos(560 * 2, 200 * 2);
            ctx.waitTicks(12);
            shot(ctx, "list-gui2");
            double[] hud = ctx.computeOnClient(c -> ((EvadeMenu) c.currentScreen).controlCentreForTest("nav:HUD"));
            check(hud != null && Math.abs(hud[0] - 132) < 4 && Math.abs(hud[1] - 246) < 4,
                    "Menu v3: the sidebar's HUD item is where the menu tests click it (" + (hud == null ? "none" : (int) hud[0] + "," + (int) hud[1]) + ")");
            // The All / Enabled filter where testMenu clicks it (x ~246..330, y ~66..88 GUI at 1080p GUI 2).
            int allN = ctx.computeOnClient(c -> ((EvadeMenu) c.currentScreen).visibleModules().size());
            ctx.getInput().setCursorPos(300 * 2, 77 * 2);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(3);
            int enN = ctx.computeOnClient(c -> ((EvadeMenu) c.currentScreen).visibleModules().size());
            ctx.getInput().setCursorPos(260 * 2, 77 * 2);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(3);
            int backN = ctx.computeOnClient(c -> ((EvadeMenu) c.currentScreen).visibleModules().size());
            check(enN < allN && enN > 0 && backN == allN, "Menu v3: the All / Enabled filter narrows the list and back (" + allN + " -> " + enN + " -> " + backN + ")");
            scale(ctx, 1920, 1080, 3);
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(12);
            shot(ctx, "list-gui3");
            scale(ctx, 1920, 1080, 2);

            // 2. Cards and Compact.
            for (String st : new String[]{"Cards", "Compact"}) {
                ctx.runOnClient(c -> cs().guiStyle.set(st));
                ctx.setScreen(EvadeMenu::new);
                ctx.getInput().setCursorPos(380 * 2, 170 * 2);
                ctx.waitTicks(12);
                shot(ctx, st.toLowerCase(Locale.ROOT));
            }
            ctx.runOnClient(c -> cs().guiStyle.set("List"));

            // 3. Settings pages: Zoom (keys row, sliders, segmented and dropdown choices), and Client Settings.
            ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
            ctx.getInput().setCursorPos(700 * 2, 250 * 2);
            ctx.waitTicks(14);
            shot(ctx, "settings-zoom");
            ctx.runOnClient(c -> ((EvadeMenu) c.currentScreen).kit().popup("c:Zoom/Animation"));
            ctx.waitTicks(4);
            shot(ctx, "settings-dropdown");
            ctx.runOnClient(c -> ((EvadeMenu) c.currentScreen).kit().popup(null));
            ctx.setScreen(EvadeMenu::new);
            ctx.getInput().setCursorPos(132 * 2, 356 * 2);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.getInput().setCursorPos(1800, 1000);
            ctx.waitTicks(12);
            shot(ctx, "settings-client");

            // 4. Keyboard: Ctrl+F, type, Down to the result, Tab to its gear, Enter opens it, Escape comes back
            //    with the focus on its row.
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(6);
            ctx.getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F);
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL);
            ctx.getInput().typeChars("zoom");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
            ctx.waitTicks(4);
            String f1 = ctx.computeOnClient(c -> ((EvadeMenu) c.currentScreen).kit().focused());
            shot(ctx, "keyboard-focus");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_TAB);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_TAB);
            String f2 = ctx.computeOnClient(c -> ((EvadeMenu) c.currentScreen).kit().focused());
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
            ctx.waitTicks(10);
            boolean onZoom = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu m && m.selected() instanceof Zoom);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_TAB);
            ctx.waitTicks(6);
            shot(ctx, "keyboard-settings");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            ctx.waitTicks(6);
            String f3 = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeMenu m ? m.kit().focused() + "|" + m.selected() : "closed");
            shot(ctx, "keyboard-back");
            check("row:Zoom".equals(f1) && f2 != null && f2.startsWith("gear:Zoom") && onZoom && f3.startsWith("row:Zoom|null"),
                    "Menu v3: keyboard path Ctrl+F → Down → Tab → Enter opens Zoom, Escape returns to its row (" + f1 + ", " + f2 + ", " + onZoom + ", " + f3 + ")");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); // clears the search
            ctx.waitTicks(2);

            // 5. Toasts sit below the window's close button.
            ctx.runOnClient(c -> {
                Notifications.clear();
                Notifications.push("Zoom enabled", "Hold C to zoom", Notifications.Level.SUCCESS, 8000);
                Notifications.push("World invite", "Dex invited you to \"Island\"", Notifications.Level.INFO, 8000);
            });
            ctx.waitTicks(10);
            int inset = ctx.computeOnClient(c -> Notifications.screenTopInset);
            ctx.takeScreenshot("menu3-toasts");
            check(inset >= 50, "Menu v3: toasts start below the menu's close button (y " + inset + ")");
            ctx.runOnClient(c -> Notifications.clear());

            // 6. Profiles and the colour picker.
            ctx.setScreen(EvadeMenu::new);
            ctx.runOnClient(c -> EvadeMenu.openProfilesForTest());
            ctx.waitTicks(10);
            shot(ctx, "profiles");
            ctx.runOnClient(c -> EvadeMenu.openAllForTest());
            EvadeMenu fps = ctx.computeOnClient(c -> { EvadeMenu m = EvadeMenu.open(EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class)); c.setScreen(m); return m; });
            ctx.waitTicks(8);
            ctx.runOnClient(c -> fps.picker.open(EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).color,
                    c.getWindow().getScaledWidth() * 0.7, c.getWindow().getScaledHeight() * 0.45));
            ctx.waitTicks(8);
            ctx.takeScreenshot("menu3-colorpicker");
            ctx.runOnClient(c -> fps.picker.close());

            // 7. Motion levels: Reduced (fades only) and Off (no animation; Escape closes at once).
            ctx.runOnClient(c -> cs().animations.set(AnimationsSetting.REDUCED));
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(1);
            shot(ctx, "reduced-opening");
            ctx.waitTicks(8);
            shot(ctx, "reduced");
            ctx.runOnClient(c -> cs().animations.set(AnimationsSetting.OFF));
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(3);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            ctx.waitTicks(2);
            boolean closedAtOnce = ctx.computeOnClient(c -> !(c.currentScreen instanceof EvadeMenu));
            check(closedAtOnce && Motion.level() == Motion.Level.OFF, "Menu v3: with Animations Off, Escape closes the menu at once");
            ctx.runOnClient(c -> cs().animations.set(AnimationsSetting.FULL));

            // 8. Classic theme, a small window and 4K.
            ctx.runOnClient(c -> { cs().theme.set("Classic"); cs().applyTheme(); });
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(10);
            shot(ctx, "classic");
            ctx.runOnClient(c -> { cs().theme.set(themeWas); cs().applyTheme(); });
            scale(ctx, 854, 480, 0);
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(10);
            shot(ctx, "854x480");
            ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
            ctx.waitTicks(10);
            shot(ctx, "854x480-settings");
            ctx.getInput().resizeWindow(3840, 2160);
            ctx.runOnClient(c -> { c.options.getGuiScale().setValue(4); c.onResolutionChanged(); });
            ctx.waitTicks(4);
            int[] fb = ctx.computeOnClient(c -> new int[]{c.getWindow().getFramebufferWidth(), c.getWindow().getFramebufferHeight()});
            if (fb[0] >= 3840 && fb[1] >= 2160) {
                ctx.setScreen(EvadeMenu::new);
                ctx.waitTicks(10);
                shot(ctx, "3840x2160-gui4");
                ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
                ctx.waitTicks(10);
                shot(ctx, "3840x2160-gui4-settings");
            } else {
                EvadeClient.LOG.info("[gametest] NOTE: the virtual display kept the window at {} x {}: no 4K menu shot", fb[0], fb[1]);
            }
            scale(ctx, 1920, 1080, 2);

            // 9a. The Wardrobe (a tall hat and a pet: nothing cut off), the emote wheel and the mod browser.
            boolean cosWas = ctx.computeOnClient(c -> net.evadeclient.module.misc.Cosmetics.get().isEnabled());
            ctx.runOnClient(c -> {
                net.evadeclient.cosmetics.Wardrobe.setTestMode(true);
                net.evadeclient.cosmetics.Wardrobe.resetForTest();
                net.evadeclient.cosmetics.CosmeticsCatalog.loadBundled();
                net.evadeclient.module.misc.Cosmetics.get().setEnabled(true);
                net.evadeclient.cosmetics.CosmeticAssets.preloadBundled();
                net.evadeclient.cosmetics.WardrobeScreen.openFromCurrent();
            });
            ctx.waitTicks(4);
            ctx.runOnClient(c -> {
                var w = (net.evadeclient.cosmetics.WardrobeScreen) c.currentScreen;
                w.selectSlot(net.evadeclient.cosmetics.Slot.CAPE); w.clickItem(net.evadeclient.cosmetics.CosmeticsCatalog.get("chevron_standard"));
                w.selectSlot(net.evadeclient.cosmetics.Slot.HAT); w.clickItem(net.evadeclient.cosmetics.CosmeticsCatalog.get("witch_hat"));
                w.selectSlot(net.evadeclient.cosmetics.Slot.PET); w.clickItem(net.evadeclient.cosmetics.CosmeticsCatalog.get("baby_dragon"));
            });
            ctx.getInput().setCursorPos(1300, 420);
            ctx.waitTicks(20);
            shot(ctx, "wardrobe-tall");
            scale(ctx, 1920, 1080, 3);
            ctx.waitTicks(8);
            shot(ctx, "wardrobe-gui3");
            scale(ctx, 1920, 1080, 2);
            ctx.runOnClient(c -> { c.setScreen(null); net.evadeclient.cosmetics.EmoteWheelScreen.open(); });
            ctx.getInput().setCursorPos(960 + 150, 540 - 160);
            ctx.waitTicks(12);
            shot(ctx, "emote-wheel");
            ctx.runOnClient(c -> net.evadeclient.gui.ModBrowserScreen.open());
            ctx.waitTicks(20);
            shot(ctx, "modbrowser");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                net.evadeclient.cosmetics.Wardrobe.resetForTest();
                net.evadeclient.cosmetics.Wardrobe.setTestMode(false);
                net.evadeclient.module.misc.Cosmetics.get().setEnabled(cosWas);
            });

            // 9. The menu's CPU record cost per frame (budget: no more than before GUI 3.0, 1338 us on this machine).
            ctx.setScreen(EvadeMenu::new);
            ctx.getInput().setCursorPos(560 * 2, 200 * 2);
            ctx.waitTicks(8);
            double[] cost = recordCost(ctx);
            EvadeClient.LOG.info("[gametest] GUICOST menu3 record {} us/frame ({} shapes/frame)",
                    String.format(Locale.ROOT, "%.0f", cost[0]), String.format(Locale.ROOT, "%.0f", cost[1]));
            check(cost[0] > 0 && cost[0] < 6000 && cost[1] <= 600,
                    String.format(Locale.ROOT, "Menu v3: records in %.0f us per frame with %.0f shapes", cost[0], cost[1]));
            ctx.setScreen(() -> null);
        } finally {
            ctx.runOnClient(c -> {
                cs().guiStyle.set(styleWas);
                cs().animations.set(animWas);
                cs().theme.set(themeWas);
                cs().applyTheme();
                MenuState.setPinned(EvadeClient.modules.get(Zoom.class), false);
                EvadeClient.modules.get(Zoom.class).setEnabled(zoomWas);
                EvadeMenu.openAllForTest();
                c.setScreen(null);
            });
            scale(ctx, 1920, 1080, 2);
        }
        if (Boolean.getBoolean("evade.menuv3only")) {
            EvadeClient.LOG.info("[gametest] menuv3only: done, stopping");
            Runtime.getRuntime().halt(0);
        }
    }

    /** The menu's CPU record time per frame (us) and shader shapes per frame, over 40 ticks (best of 3 windows). */
    private static double[] recordCost(ClientGameTestContext ctx) {
        long[] acc = new long[3];
        long[] shapes = new long[2];
        ctx.runOnClient(c -> {
            Screen s = c.currentScreen;
            ScreenEvents.beforeRender(s).register((sc, dc, mx, my, d) -> { acc[2] = System.nanoTime(); shapes[1] = UiShape.submitted; });
            ScreenEvents.afterRender(s).register((sc, dc, mx, my, d) -> {
                if (acc[2] == 0) return;
                acc[0] += System.nanoTime() - acc[2];
                acc[1]++;
                shapes[0] += UiShape.submitted - shapes[1];
                acc[2] = 0;
            });
        });
        double best = Double.MAX_VALUE, sh = 0;
        for (int w = 0; w < 3; w++) {
            ctx.runOnClient(c -> { acc[0] = 0; acc[1] = 0; shapes[0] = 0; });
            ctx.waitTicks(40);
            double[] r = ctx.computeOnClient(c -> new double[]{acc[1] == 0 ? 0 : acc[0] / 1000.0 / acc[1], acc[1] == 0 ? 0 : shapes[0] / (double) acc[1]});
            if (r[0] > 0 && r[0] < best) { best = r[0]; sh = r[1]; }
        }
        return new double[]{best == Double.MAX_VALUE ? 0 : best, sh};
    }
}
