package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.*;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Screenshots of the GUI 3.0 panels, dialogs and editors (WP4) for visual review: every panel at 1920x1080 GUI 2, and
 * a few at 3840x2160 GUI 4 and 854x480. Checks nothing. Run with
 * {@code JAVA_TOOL_OPTIONS=-Devade.wp4shots=true ./gradlew runClientGameTest}.
 */
final class Wp4Shots {
    private Wp4Shots() {}

    private static final String FILE = "evade-wp4-house.litematic";

    static void run(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("tp @a 0 -60 0 -30 20");
            ctx.waitTicks(20);
            Path dir = ctx.computeOnClient(c -> net.evadeclient.module.misc.Schematics.folder());
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve(FILE), net.evadeclient.schematic.SchematicIO.gzip(
                        net.evadeclient.schematic.SchematicIO.litematicTag(SchematicsTest.house())));
            } catch (Exception e) { throw new AssertionError(e); }
            ctx.runOnClient(c -> {
                var m = EvadeClient.modules.get(net.evadeclient.module.misc.Schematics.class);
                m.setEnabled(true);
                m.load(FILE);
            });
            ctx.waitFor(c -> !EvadeClient.modules.get(net.evadeclient.module.misc.Schematics.class).loading(), 200);

            size(ctx, 1920, 1080, 2);
            all(ctx, "");
            size(ctx, 3840, 2160, 4);
            some(ctx, "-4k");
            size(ctx, 854, 480, 2);
            some(ctx, "-854");
            size(ctx, 1920, 1080, 2);
            ctx.runOnClient(c -> {
                var m = EvadeClient.modules.get(net.evadeclient.module.misc.Schematics.class);
                m.unload();
                m.setEnabled(false);
            });
            try { Files.deleteIfExists(dir.resolve(FILE)); } catch (Exception ignored) {}
        }
    }

    private static void size(ClientGameTestContext ctx, int w, int h, int gui) {
        ctx.getInput().resizeWindow(w, h);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(gui); c.onResolutionChanged(); });
        ctx.waitTicks(5);
    }

    private static void shot(ClientGameTestContext ctx, String name, Function<MinecraftClient, Screen> s) {
        try {
            ctx.runOnClient(c -> c.setScreen(s.apply(c)));
            ctx.getInput().setCursorPos(4, 4);
            ctx.waitTicks(10);
            ctx.runOnClient(c -> { c.getToastManager().clear(); Notifications.clear(); });
            ctx.waitTicks(2);
            ctx.takeScreenshot("wp4-" + name);
        } catch (Throwable t) {
            System.out.println("[wp4shots] " + name + " failed: " + t);
        }
    }

    private static void some(ClientGameTestContext ctx, String sfx) {
        shot(ctx, "keybinds" + sfx, c -> new KeybindsScreen(null));
        shot(ctx, "setup-styles" + sfx, c -> new SetupWizardScreen(null));
        shot(ctx, "schematics" + sfx, c -> new SchematicBrowserScreen(null));
        shot(ctx, "materials" + sfx, c -> new MaterialListScreen(null));
        shot(ctx, "hud-editor" + sfx, c -> new HudEditorScreen(null));
        shot(ctx, "server-prompt" + sfx, c -> new ServerPromptScreen(new net.evadeclient.api.ApiPrompts.Prompt(
                "prompt.input", "isle", "Name your island", "Up to 24 letters", "", 24, null)));
    }

    private static void all(ClientGameTestContext ctx, String sfx) {
        some(ctx, sfx);
        shot(ctx, "setup-tune", c -> { var w = new SetupWizardScreen(null); w.nextForTest(); return w; });
        shot(ctx, "setup-tips", c -> { var w = new SetupWizardScreen(null); w.nextForTest(); w.nextForTest(); return w; });
        shot(ctx, "waypoints", c -> new WaypointManagerScreen(null));
        shot(ctx, "shader-packs", c -> new ShaderPackScreen(null));
        shot(ctx, "pack-features", c -> new PackFeaturesScreen(null));
        shot(ctx, "benchmark", c -> new BenchmarkResultScreen(null, new net.evadeclient.util.BenchmarkScene.Result(
                143.6, 92.4, 61.0, 1436, 10, "Mesa llvmpipe", "Test CPU", "Balanced", "1920x1080", 12, "100%", "2.9.2")));
        shot(ctx, "safe-start", c -> new SafeStartScreen(null));
        shot(ctx, "player-cards", c -> { PlayerCardScreen.openList(null); return c.currentScreen; });
        shot(ctx, "open-to-friends", c -> new OpenToFriendsScreen(null));
        shot(ctx, "quick-play", c -> new QuickPlayScreen(null));
        shot(ctx, "item-picker", c -> new ItemPickerScreen(null, EvadeClient.modules.get(net.evadeclient.module.visuals.ItemHighlight.class).itemsSetting()));
        shot(ctx, "cloud-backup", c -> new CloudBackupScreen(null));
        shot(ctx, "hypixel-key", c -> new HypixelKeyScreen(null, "", k -> {}));
        shot(ctx, "launcher-join", c -> new LauncherJoinScreen(null, new net.evadeclient.util.LauncherBridge.Request("server", "play.example.net", "Example Server")));
        shot(ctx, "text-hotkeys", c -> new TextHotkeysScreen(null));
        shot(ctx, "gallery", c -> new ScreenshotGalleryScreen(null));
        shot(ctx, "crosshair-editor", c -> new CrosshairEditorScreen(null, EvadeClient.modules.get(net.evadeclient.module.mods.Crosshair.class)));
        shot(ctx, "world-map", c -> { WorldMapScreen.open(null); return c.currentScreen; });
        shot(ctx, "replay-browser", c -> new net.evadeclient.replay.ReplayBrowserScreen(null));
        // HUD editor: the add-widget list (right-click on empty space).
        shot(ctx, "hud-editor-select", c -> {
            var s = new HudEditorScreen(null);
            return s;
        });
        try {
            ctx.runOnClient(c -> ((HudEditorScreen) c.currentScreen).selection().add(EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class)));
            ctx.waitTicks(3);
            ctx.takeScreenshot("wp4-hud-editor-selected");
            double[] p = ctx.computeOnClient(c -> {
                float k = net.evadeclient.hud.Hud.scale();
                double f = c.getWindow().getScaleFactor();
                int w = Math.round(c.getWindow().getScaledWidth() / k), h = Math.round(c.getWindow().getScaledHeight() / k);
                for (int yy = h / 4; yy < h / 2; yy += 4) for (int xx = w / 4; xx < w * 3 / 4; xx += 4) {
                    boolean hit = false;
                    for (HudElementModule el : EvadeClient.modules.hudElements()) {
                        if (!el.isEnabled()) continue;
                        int l = el.resolveX(w), t = el.resolveY(h);
                        if (xx >= l - 2 && xx <= l + el.scaledWidth() + 2 && yy >= t - 2 && yy <= t + el.scaledHeight() + 2) { hit = true; break; }
                    }
                    if (!hit) return new double[]{xx * k * f, yy * k * f};
                }
                return new double[]{800, 400};
            });
            ctx.getInput().setCursorPos(p[0], p[1]);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            ctx.waitTicks(4);
            ctx.takeScreenshot("wp4-hud-editor-add");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            ctx.waitTicks(2);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_SLASH);
            ctx.waitTicks(4);
            ctx.takeScreenshot("wp4-hud-editor-help");
        } catch (Throwable t) {
            System.out.println("[wp4shots] hud editor extras failed: " + t);
        }
        ctx.setScreen(() -> null);
    }
}
