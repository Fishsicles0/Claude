package net.evadeclient.gametest;

import com.google.gson.JsonPrimitive;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.gui.Motion;
import net.evadeclient.gui.kit.KitGalleryScreen;
import net.evadeclient.module.client.AnimationsSetting;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GUI 3.0 foundation (WP1): the SDF shape shader is live (and the texture fallback works when forced), the kit
 * gallery draws every component without errors at GUI scale 2, 3 and 4 (and a 4K window when the display allows),
 * the central changes reach the menu, a vanilla-button screen and the toasts (smooth font, de-duplication), the GUI
 * and HUD stay inside their cost budgets (the HUD never draws shader shapes), the Animations levels and their
 * migration from the old on/off switch, and the GUI-scale-1 space fix.
 */
public final class GuiKitTest {
    private GuiKitTest() {}

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

    /** Runs inside a world; leaves the window at 1920 x 1080, GUI scale 2, no screen open, settings as found. */
    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        scale(ctx, 1920, 1080, 2);
        ctx.runOnClient(c -> Notifications.clear());

        // 1. Settings migration (old boolean Animations) and the three motion levels.
        String[] mig = ctx.computeOnClient(c -> {
            AnimationsSetting a = cs().animations;
            String was = a.get();
            String[] out = new String[4];
            a.loadJson(new JsonPrimitive(true)); out[0] = a.get();
            a.loadJson(new JsonPrimitive(false)); out[1] = a.get();
            a.loadJson(new JsonPrimitive("Reduced")); out[2] = a.get();
            a.loadJson(new JsonPrimitive("nonsense")); out[3] = a.get();
            a.set(was);
            return out;
        });
        check(mig[0].equals("Full") && mig[1].equals("Off") && mig[2].equals("Reduced") && mig[3].equals("Full"),
                "Animations: an old 'true' loads as Full, 'false' as Off, and the new levels load as saved (" + String.join(", ", mig) + ")");
        String levels = ctx.computeOnClient(c -> {
            AnimationsSetting a = cs().animations;
            String was = a.get();
            StringBuilder b = new StringBuilder();
            try {
                a.set("Full");
                b.append(Motion.level()).append(' ').append(Motion.decorative()).append(' ').append(Motion.duration(320)).append(';');
                a.set("Reduced");
                Motion.Spring s = new Motion.Spring(0f);
                float half = Motion.spring(s, 1f, Motion.SNAPPY, 0.016f);
                b.append(Motion.level()).append(' ').append(Motion.decorative()).append(' ').append(Motion.duration(320)).append(' ')
                        .append(half > 0f && half < 1f).append(';');
                a.set("Off");
                Motion.Spring s2 = new Motion.Spring(0f);
                b.append(Motion.level()).append(' ').append(Motion.duration(320)).append(' ').append(Motion.spring(s2, 1f, Motion.SNAPPY, 0.016f));
            } finally {
                a.set(was);
            }
            return b.toString();
        });
        check(levels.equals("FULL true 320;REDUCED false 120 true;OFF 0 1.0"),
                "Motion levels: Full keeps decorative motion, Reduced caps transitions at 120 ms and drops decorative motion, Off snaps (" + levels + ")");

        // 2. The shader compiles here and the menu draws through it.
        boolean compiled = ctx.computeOnClient(c -> UiShape.compiled());
        check(compiled, "GUI shapes: the evade:ui_shape SDF pipeline compiles on this driver");
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(6);
        long[] menuShapes = ctx.computeOnClient(c -> new long[]{UiShape.submitted});
        ctx.waitTicks(4);
        long menuPerFrame = ctx.computeOnClient(c -> UiShape.submitted) - menuShapes[0];
        check(menuPerFrame > 0 && ctx.computeOnClient(c -> UiShape.available()),
                "GUI shapes: the Evade menu draws its rounded shapes through the shader (" + menuPerFrame + " shapes in 4 ticks)");

        // GUI frame cost: the menu's CPU record time, shader and fallback, and its shapes per frame.
        double[] menuCost = recordCost(ctx, false);
        double[] menuCostFallback = recordCost(ctx, true);
        EvadeClient.LOG.info("[gametest] GUICOST menu record {} us/frame ({} shapes/frame) shader, {} us/frame fallback",
                String.format(Locale.ROOT, "%.0f", menuCost[0]), String.format(Locale.ROOT, "%.0f", menuCost[1]),
                String.format(Locale.ROOT, "%.0f", menuCostFallback[0]));
        check(menuCost[0] > 0 && menuCost[0] < 6000 && menuCost[1] <= 600,
                String.format(Locale.ROOT, "GUI cost: the Evade menu records in %.0f us per frame with %.0f shapes (budget 600 shapes; fallback %.0f us)",
                        menuCost[0], menuCost[1], menuCostFallback[0]));
        ctx.takeScreenshot("kit-menu-gui2");

        // 3. The gallery at GUI 2, 3 and 4: every component drawn, no crash, through the shader.
        Set<String> want = Set.of("window", "button", "iconButton", "toggle", "slider", "progress", "dropdown", "segmented", "tabs",
                "keyChip", "tooltip", "search", "badge", "scrollbar", "sidebar", "listRow", "card", "colorPicker", "toast", "emptyState", "type");
        for (int gui : new int[]{2, 3, 4}) {
            scale(ctx, 1920, 1080, gui);
            ctx.setScreen(() -> new KitGalleryScreen(null));
            ctx.getInput().setCursorPos(1920 * 0.62, 1080 * 0.5);
            ctx.waitTicks(10);
            List<String> missing = ctx.computeOnClient(c -> {
                if (!(c.currentScreen instanceof KitGalleryScreen g)) return List.of("<gallery not open>");
                List<String> m = new ArrayList<>();
                for (String w : want) if (!g.drawn.contains(w)) m.add(w);
                if (g.frames < 3) m.add("<only " + g.frames + " frames>");
                return m;
            });
            long shapes = ctx.computeOnClient(c -> ((KitGalleryScreen) c.currentScreen).shapesLastFrame);
            check(missing.isEmpty() && shapes > 100,
                    "Kit gallery at GUI scale " + gui + ": every component drawn (" + want.size() + " kinds, " + shapes + " shader shapes a frame)" + (missing.isEmpty() ? "" : " missing " + missing));
            ctx.takeScreenshot("kit-gallery-gui" + gui);
        }
        // The dialog over the gallery (scrim, push-back, focus).
        scale(ctx, 1920, 1080, 2);
        ctx.setScreen(() -> new KitGalleryScreen(null));
        ctx.runOnClient(c -> ((KitGalleryScreen) c.currentScreen).dialog(true));
        ctx.waitTicks(12);
        check(ctx.computeOnClient(c -> c.currentScreen instanceof KitGalleryScreen g && g.drawn.contains("dialog")), "Kit gallery: the dialog draws over the page");
        ctx.takeScreenshot("kit-gallery-dialog");
        // Gallery record cost (the heaviest kit screen: about every component at once).
        double[] galleryCost = recordCost(ctx, false);
        EvadeClient.LOG.info("[gametest] GUICOST gallery record {} us/frame ({} shapes/frame)",
                String.format(Locale.ROOT, "%.0f", galleryCost[0]), String.format(Locale.ROOT, "%.0f", galleryCost[1]));
        check(galleryCost[0] > 0 && galleryCost[0] < 12000,
                String.format(Locale.ROOT, "GUI cost: the kit gallery (every component) records in %.0f us per frame with %.0f shapes", galleryCost[0], galleryCost[1]));

        // 4. A 4K window, when the virtual display lets the window grow that large.
        ctx.getInput().resizeWindow(3840, 2160);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(4); c.onResolutionChanged(); });
        ctx.waitTicks(4);
        int[] fb = ctx.computeOnClient(c -> new int[]{c.getWindow().getFramebufferWidth(), c.getWindow().getFramebufferHeight()});
        if (fb[0] >= 3840 && fb[1] >= 2160) {
            ctx.setScreen(() -> new KitGalleryScreen(null));
            ctx.waitTicks(10);
            ctx.takeScreenshot("kit-gallery-3840x2160-gui4");
            EvadeClient.LOG.info("[gametest] PASS: Kit gallery at 3840 x 2160, GUI scale 4");
        } else {
            EvadeClient.LOG.info("[gametest] NOTE: the virtual display kept the window at {} x {}: no 4K screenshot", fb[0], fb[1]);
        }
        scale(ctx, 1920, 1080, 2);

        // 5. Forced fallback: the texture shapes draw the same screens with no shader shapes.
        ctx.runOnClient(c -> UiShape.forceFallback(true));
        try {
            ctx.setScreen(() -> new KitGalleryScreen(null));
            ctx.waitTicks(8);
            long fallbackShapes = ctx.computeOnClient(c -> ((KitGalleryScreen) c.currentScreen).shapesLastFrame);
            boolean stillOpen = ctx.computeOnClient(c -> c.currentScreen instanceof KitGalleryScreen g && g.drawn.containsAll(want));
            check(fallbackShapes == 0 && stillOpen && !ctx.computeOnClient(c -> UiShape.available()),
                    "GUI shapes: with the fallback forced the gallery draws every component from the texture shapes (0 shader shapes)");
            ctx.takeScreenshot("kit-gallery-fallback");
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(6);
            ctx.takeScreenshot("kit-menu-fallback");
        } finally {
            ctx.runOnClient(c -> UiShape.forceFallback(false));
        }

        // 6. Toasts: smooth font cards, three at most, identical ones merged with a count.
        ctx.setScreen(EvadeMenu::new);
        ctx.runOnClient(c -> {
            Notifications.clear();
            Notifications.push("Server rules", "This server turns Freelook off", Notifications.Level.WARN, 8000);
            Notifications.push("Server rules", "This server turns Freelook off", Notifications.Level.WARN, 8000);
            Notifications.push("Server rules", "This server turns Freelook off", Notifications.Level.WARN, 8000);
            Notifications.push("Zoom enabled", "Hold C to zoom", Notifications.Level.SUCCESS, 8000);
            Notifications.push("World invite", "Dex invited you to \"Island\"", Notifications.Level.INFO, 8000);
            Notifications.push("Backup failed", "Couldn't reach the server", Notifications.Level.ERROR, 8000);
        });
        ctx.waitTicks(8);
        List<String> toasts = ctx.computeOnClient(c -> Notifications.snapshot());
        check(toasts.size() == 4 && toasts.getFirst().equals("Server rules|This server turns Freelook off|3"),
                "Toasts: three identical pushes merge into one toast with a x3 count (" + toasts + ")");
        ctx.takeScreenshot("kit-toasts-menu");
        ctx.setScreen(() -> null);
        ctx.waitTicks(4);
        ctx.takeScreenshot("kit-toasts-ingame");
        ctx.runOnClient(c -> Notifications.clear());

        // 7. A screen with vanilla buttons (Shader Packs): painted as kit buttons on Evade screens.
        ctx.setScreen(() -> new net.evadeclient.gui.ShaderPackScreen(null));
        ctx.getInput().setCursorPos(10, 10);
        ctx.waitTicks(6);
        ctx.takeScreenshot("kit-vanilla-buttons");
        ctx.setScreen(() -> null);

        // 8. GUI scale 1: words keep their spaces in the smallest roles.
        scale(ctx, 1920, 1080, 1);
        int[] gaps = ctx.computeOnClient(c -> {
            var tr = MinecraftClient.getInstance().textRenderer;
            return new int[]{UiFont.width(tr, "Quit Game", UiFont.Role.TINY, true) - UiFont.width(tr, "QuitGame", UiFont.Role.TINY, true),
                    UiFont.width(tr, "Read the changelog", UiFont.Role.SMALL, true) - UiFont.width(tr, "Readthechangelog", UiFont.Role.SMALL, true)};
        });
        check(gaps[0] >= 3 && gaps[1] >= 6, "GUI scale 1: a space is at least 3 px in the tiny and small roles (" + gaps[0] + ", " + gaps[1] / 2 + " px)");
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(6);
        ctx.takeScreenshot("kit-menu-gui1");
        ctx.setScreen(() -> null);
        scale(ctx, 1920, 1080, 2);

        // 9. The HUD keeps its cheap texture path: no shader shapes while only the HUD draws.
        List<String> turnedOn = ctx.computeOnClient(c -> {
            List<String> on = new ArrayList<>();
            for (var el : EvadeClient.modules.hudElements())
                if (!el.isEnabled() && !el.getName().equals("Frame Profiler")) { el.setEnabled(true); on.add(el.getName()); }
            return on;
        });
        ctx.runOnClient(c -> Notifications.clear());
        ctx.waitTicks(20); // let widgets, fonts and the HUD cache settle at this scale (as testHudCost does)
        long hudBefore = ctx.computeOnClient(c -> UiShape.submitted);
        ctx.runOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
        ctx.waitTicks(100);
        double hudUs = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
        for (int retry = 0; retry < 2; retry++) { // best of three windows
            ctx.waitTicks(100);
            double again = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
            if (again > 0) hudUs = hudUs > 0 ? Math.min(hudUs, again) : again;
        }
        long hudShapes = ctx.computeOnClient(c -> UiShape.submitted) - hudBefore;
        ctx.runOnClient(c -> { for (var el : EvadeClient.modules.hudElements()) if (turnedOn.contains(el.getName())) el.setEnabled(false); });
        EvadeClient.LOG.info("[gametest] HUDCOST kit {} us per frame, {} shader shapes", String.format(Locale.ROOT, "%.0f", hudUs), hudShapes);
        // The strict HUD budget is testHudCost's (4000 us, measured early in the run); this one checks the HUD stays on
        // the texture path after the GUI changes, with a loose ceiling so a busy machine can't fail it twice.
        check(hudShapes == 0 && hudUs > 0 && hudUs < 8000,
                String.format(Locale.ROOT, "HUD: every widget on costs %.0f us per frame and draws no shader shapes (cheap texture path)", hudUs));

        // 10. The tokens behind it all (Modern theme, Red accent).
        boolean tokens = ctx.computeOnClient(c -> { Tokens.refresh(); return Tokens.E1 != null && Tokens.A400 == 0xFFFF5566 && Tokens.N200 == 0xFF15171D; });
        check(tokens, "Tokens: the Obsidian neutral and red ramps are loaded (cards #15171D, accent text red 400 #FF5566)");
    }

    /**
     * The current screen's CPU record time per frame in microseconds (Fabric's before/after render events around
     * its render) and its shader shapes per frame, with the texture fallback forced or not.
     */
    private static double[] recordCost(ClientGameTestContext ctx, boolean fallback) {
        ctx.runOnClient(c -> UiShape.forceFallback(fallback));
        long[] acc = new long[3]; // total ns, frames, start
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
        ctx.waitTicks(40);
        double[] best = ctx.computeOnClient(c -> new double[]{acc[1] == 0 ? 0 : acc[0] / 1000.0 / acc[1], acc[1] == 0 ? 0 : shapes[0] / (double) acc[1]});
        ctx.runOnClient(c -> UiShape.forceFallback(false));
        // The listeners stay on that screen instance only; reopen it fresh for the next measurement.
        Screen again = ctx.computeOnClient(c -> c.currentScreen);
        ctx.setScreen(() -> again instanceof KitGalleryScreen ? new KitGalleryScreen(null) : new EvadeMenu());
        ctx.waitTicks(4);
        return best;
    }
}
