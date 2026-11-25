package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeTitleScreen;
import net.evadeclient.gui.title.Intro;
import net.evadeclient.gui.title.TitleArt;
import net.evadeclient.module.misc.TitleScreenModule;
import net.evadeclient.social.SocialClient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Evade main menu (Title Screen module), before any world: it replaces the vanilla title screen on every path
 * back to it (setScreen(TitleScreen), setScreen(null) with no world, closing the multiplayer, disconnect, language,
 * options, setup and Safe Start screens, after a resource reload), leaves other mods' TitleScreen subclasses alone,
 * switches itself on once for configs from before it was the default, lays out without overlaps from 320x240 GUI px
 * up to 1920x1080 at GUI scale 1 (and a tall phone-like window), keyboard navigation, Reduce Motion, the friends
 * strip and Wardrobe card, and its frame cost. Screenshots: title-*.
 */
public final class TitleScreenTest {
    private TitleScreenTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static String current(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> c.currentScreen == null ? "none" : c.currentScreen.getClass().getName());
    }

    private static boolean evade(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> c.currentScreen instanceof EvadeTitleScreen);
    }

    /** Opens {@code s}, then closes it the way its own Back/Done does, and checks the Evade menu is what shows. */
    private static void backTo(ClientGameTestContext ctx, String what, java.util.function.Function<MinecraftClient, Screen> s) {
        ctx.runOnClient(c -> c.setScreen(s.apply(c)));
        ctx.waitTicks(2);
        ctx.runOnClient(c -> { if (c.currentScreen != null) c.currentScreen.close(); });
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: back from " + what + " shows the Evade menu (" + current(ctx) + ")");
    }

    /** Presses the screen's last vanilla button (a disconnect screen's only one). */
    private static void pressLastButton(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            net.minecraft.client.gui.widget.ButtonWidget last = null;
            for (var child : c.currentScreen.children()) if (child instanceof net.minecraft.client.gui.widget.ButtonWidget b) last = b;
            if (last != null) last.onPress(new net.minecraft.client.input.KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
        });
    }

    public static void run(ClientGameTestContext ctx) {
        // Screenshots show the Full effects (Auto would pick Light on the test's software renderer); cost() measures both.
        ctx.runOnClient(c -> { var t = EvadeClient.modules.get(TitleScreenModule.class); t.setEnabled(true); t.effects.set("Full"); });
        replacement(ctx);
        oldConfig(ctx);
        keyboard(ctx);
        layouts(ctx);
        extras(ctx);
        cost(ctx);
        ctx.runOnClient(c -> c.setScreen(new TitleScreen()));
        ctx.waitTicks(2);
    }

    // ------------------------------------------------------------------ (1) every path shows the Evade menu

    private static void replacement(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> c.setScreen(new TitleScreen()));
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: setScreen(new TitleScreen()) opens the Evade menu");
        ctx.runOnClient(c -> c.setScreen(new TitleScreen(true, null)));
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: the start-up TitleScreen(fade, logo) opens the Evade menu");
        ctx.runOnClient(c -> c.setScreen(new OptionsScreen(null, c.options)));
        ctx.waitTicks(2);
        ctx.runOnClient(c -> c.setScreen(null));
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: setScreen(null) with no world (a parentless screen closing) opens the Evade menu ("
                + current(ctx) + ")");

        // Leaving a server: vanilla's disconnect paths build these with a plain TitleScreen under them.
        backTo(ctx, "the server list", c -> new MultiplayerScreen(new TitleScreen()));
        // A disconnect screen's button (back to the server list, then Back), as when a server kicks you.
        ctx.runOnClient(c -> c.setScreen(new DisconnectedScreen(new MultiplayerScreen(new TitleScreen()),
                Text.literal("Connection lost"), Text.literal("Test"))));
        ctx.waitTicks(2);
        pressLastButton(ctx);
        ctx.waitTicks(2);
        ctx.runOnClient(c -> c.currentScreen.close());
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: disconnect screen, then Back from the server list, shows the Evade menu (" + current(ctx) + ")");
        // A disconnect screen straight over the title (its button is "Back to title" when multiplayer is off).
        ctx.runOnClient(c -> c.setScreen(new DisconnectedScreen(new TitleScreen(), Text.literal("Kicked"), Text.literal("Test"))));
        ctx.waitTicks(2);
        pressLastButton(ctx);
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: a disconnect screen's button back to the title shows the Evade menu (" + current(ctx) + ")");
        // Options, language (which reloads resources when changed), the setup wizard and Safe Start come back to it.
        backTo(ctx, "Options", c -> new OptionsScreen(c.currentScreen, c.options));
        backTo(ctx, "Language", c -> new LanguageOptionsScreen(c.currentScreen, c.options, c.getLanguageManager()));
        backTo(ctx, "the setup wizard", c -> new net.evadeclient.gui.SetupWizardScreen(c.currentScreen));
        backTo(ctx, "Safe Start", c -> new net.evadeclient.gui.SafeStartScreen(c.currentScreen));

        // A resource reload (language change, pack change) keeps it.
        ctx.runOnClient(MinecraftClient::reloadResources);
        ctx.waitTicks(4);
        ctx.waitFor(c -> c.getOverlay() == null, 20 * 60);
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: still the Evade menu after a resource reload (" + current(ctx) + ")");

        // Another mod's own title screen (a TitleScreen subclass) is left alone.
        ctx.runOnClient(c -> c.setScreen(new TitleScreen() { }));
        ctx.waitTicks(2);
        boolean sub = ctx.computeOnClient(c -> c.currentScreen instanceof TitleScreen && c.currentScreen.getClass() != TitleScreen.class);
        check(sub, "Title Screen: another mod's TitleScreen subclass is left alone");

        // Off: vanilla, untouched; the open Evade menu hands over to vanilla on its next tick.
        ctx.runOnClient(c -> c.setScreen(new TitleScreen()));
        ctx.waitTicks(2);
        ctx.runOnClient(c -> EvadeClient.modules.get(TitleScreenModule.class).setEnabled(false));
        ctx.waitTicks(3);
        check(ctx.computeOnClient(c -> c.currentScreen != null && c.currentScreen.getClass() == TitleScreen.class),
                "Title Screen: turned off, the vanilla title screen takes over (" + current(ctx) + ")");
        ctx.runOnClient(c -> c.setScreen(null));
        ctx.waitTicks(2);
        check(ctx.computeOnClient(c -> c.currentScreen != null && c.currentScreen.getClass() == TitleScreen.class),
                "Title Screen: off, setScreen(null) is vanilla's title screen as before");
        ctx.runOnClient(c -> { EvadeClient.modules.get(TitleScreenModule.class).setEnabled(true); c.setScreen(new TitleScreen()); });
        ctx.waitTicks(2);
        check(evade(ctx), "Title Screen: on again, the Evade menu is back");
    }

    // ------------------------------------------------------------------ old configs

    private static void oldConfig(ClientGameTestContext ctx) {
        // A config saved before the Evade menu was the default (no Background, no marker) switches it on once.
        boolean on = ctx.computeOnClient(c -> {
            TitleScreenModule t = EvadeClient.modules.get(TitleScreenModule.class);
            com.google.gson.JsonObject old = new com.google.gson.JsonObject();
            old.addProperty("Last Server Button", true);
            t.onConfigLoaded(old);
            t.setEnabled(false); // what the old config's "enabled": false does right after
            c.setScreen(new TitleScreen());
            return t.isEnabled() && c.currentScreen instanceof EvadeTitleScreen;
        });
        check(on, "Title Screen: a config from before it was the default start screen gets the Evade menu");
        // A config saved since (it has Background) keeps a deliberate "off".
        boolean kept = ctx.computeOnClient(c -> {
            TitleScreenModule t = EvadeClient.modules.get(TitleScreenModule.class);
            com.google.gson.JsonObject recent = new com.google.gson.JsonObject();
            recent.addProperty("Background", "Panorama");
            t.onConfigLoaded(recent);
            t.setEnabled(false);
            c.setScreen(new TitleScreen());
            boolean off = !t.isEnabled() && c.currentScreen.getClass() == TitleScreen.class;
            t.setEnabled(true);
            c.setScreen(new TitleScreen());
            return off;
        });
        check(kept, "Title Screen: a recent config that turned it off stays off");
    }

    // ------------------------------------------------------------------ keyboard

    private static void keyboard(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); c.setScreen(new TitleScreen()); });
        ctx.waitTicks(10);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_TAB);
        ctx.waitTicks(2);
        String first = focused(ctx);
        check(first.equals(Text.translatable("menu.singleplayer").getString()), "Title Screen: Tab focuses Singleplayer first (" + first + ")");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
        ctx.waitTicks(2);
        String second = focused(ctx);
        check(second.equals(Text.translatable("menu.multiplayer").getString()), "Title Screen: Down moves to Multiplayer (" + second + ")");
        ctx.takeScreenshot("title-keyboard-focus");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_UP);
        ctx.waitTicks(2);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        ctx.waitTicks(3);
        String opened = current(ctx);
        // With no worlds yet the world list goes straight on to Create New World.
        check(opened.endsWith("SelectWorldScreen") || opened.endsWith("CreateWorldScreen"),
                "Title Screen: Enter on Singleplayer opens the world list (" + opened + ")");
        ctx.runOnClient(c -> c.currentScreen.close());
        ctx.waitTicks(3);
        check(evade(ctx), "Title Screen: back from Singleplayer shows the Evade menu (" + current(ctx) + ")");

        // A replayed entrance can be skipped.
        boolean skipped = ctx.computeOnClient(c -> {
            Intro i = new Intro();
            i.begin(true);
            return i.skip() && i.done() && i.p(0, 500) >= 0.999f;
        });
        check(skipped, "Title Screen: the entrance can be skipped");
    }

    private static String focused(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> c.currentScreen != null && c.currentScreen.getFocused() instanceof net.minecraft.client.gui.widget.ClickableWidget w
                ? w.getMessage().getString() : "nothing");
    }

    // ------------------------------------------------------------------ (2/3) layout at every size

    private static void layouts(ClientGameTestContext ctx) {
        // window w, h, GUI scale (0 = auto), screenshot?
        int[][] sizes = {
                {1920, 1080, 1, 1}, {1920, 1080, 2, 1}, {1920, 1080, 3, 1}, {1920, 1080, 4, 1},
                {1280, 720, 1, 0}, {1280, 720, 2, 1}, {1280, 720, 3, 0},
                {1366, 768, 0, 0}, {1600, 900, 3, 0},
                {854, 480, 1, 1}, {854, 480, 2, 1}, {640, 480, 2, 1},
                {480, 854, 1, 1}, {720, 1280, 2, 0},
        };
        ctx.getInput().setCursorPos(2, 2); // no hover tooltips in the shots
        for (int[] sz : sizes) {
            ctx.getInput().resizeWindow(sz[0], sz[1]);
            ctx.runOnClient(c -> { c.options.getGuiScale().setValue(sz[2]); c.onResolutionChanged(); c.setScreen(new TitleScreen()); });
            ctx.waitTicks(12);
            String tag = sz[0] + "x" + sz[1] + " gui" + sz[2];
            String problem = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeTitleScreen t ? overlaps(t) : "not the Evade menu");
            String parts = ctx.computeOnClient(c -> c.currentScreen instanceof EvadeTitleScreen t
                    ? t.width + "x" + t.height + " " + t.shownParts() : "");
            check(problem.isEmpty(), "Title Screen: nothing overlaps or leaves the screen at " + tag + " (" + parts + ")" + (problem.isEmpty() ? "" : ": " + problem));
            if (sz[3] == 1) {
                ctx.runOnClient(c -> c.getToastManager().clear());
                ctx.takeScreenshot("title-" + sz[0] + "x" + sz[1] + "-gui" + sz[2]);
            }
        }
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); c.setScreen(new TitleScreen()); });
        ctx.waitTicks(6);
        String parts = ctx.computeOnClient(c -> ((EvadeTitleScreen) c.currentScreen).shownParts().toString());
        check(parts.contains("wide") && parts.contains("teaser") && parts.contains("news"),
                "Title Screen: at 1080p GUI 2 the rails show the account, what's new and the Wardrobe card (" + parts + ")");
    }

    /** "" when every box is on screen and nothing collides (a widget may sit inside a card); else what's wrong. */
    static String overlaps(EvadeTitleScreen t) {
        Map<String, int[]> boxes = t.boxes();
        List<String> issues = new ArrayList<>();
        List<Map.Entry<String, int[]>> list = new ArrayList<>(boxes.entrySet());
        for (var e : list) {
            int[] b = e.getValue();
            if (b[0] < 0 || b[1] < 0 || b[2] > t.width || b[3] > t.height || b[2] <= b[0] || b[3] <= b[1]) {
                issues.add(e.getKey() + " off screen " + java.util.Arrays.toString(b));
            }
        }
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                String a = list.get(i).getKey(), b = list.get(j).getKey();
                int[] p = list.get(i).getValue(), q = list.get(j).getValue();
                if (!(p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3])) continue;
                boolean aCard = a.startsWith("card:"), bCard = b.startsWith("card:");
                if (aCard != bCard) {
                    int[] card = aCard ? p : q, w = aCard ? q : p;
                    if (w[0] >= card[0] && w[1] >= card[1] && w[2] <= card[2] && w[3] <= card[3]) continue; // inside its card
                }
                issues.add(a + " " + java.util.Arrays.toString(p) + " x " + b + " " + java.util.Arrays.toString(q));
            }
        }
        return String.join("; ", issues);
    }

    // ------------------------------------------------------------------ friends, wardrobe, reduce motion

    private static void extras(ClientGameTestContext ctx) {
        // Friends online (loopback: one friend, "Loopback", online).
        ctx.runOnClient(c -> { SocialClient.setLoopback(true); c.setScreen(new TitleScreen()); });
        ctx.waitTicks(10);
        String parts = ctx.computeOnClient(c -> ((EvadeTitleScreen) c.currentScreen).shownParts().toString());
        check(parts.contains("friends"), "Title Screen: the account card shows friends online when Friends is on (" + parts + ")");
        ctx.getInput().setCursorPos(1920 * 0.75, 1080 * 0.3); // parallax off-centre
        ctx.waitTicks(20);
        ctx.takeScreenshot("title-friends-wardrobe");
        boolean noFriends = ctx.computeOnClient(c -> {
            TitleScreenModule t = EvadeClient.modules.get(TitleScreenModule.class);
            t.friendsOnline.set(false);
            c.setScreen(new TitleScreen());
            boolean hidden = !((EvadeTitleScreen) c.currentScreen).shownParts().contains("friends");
            t.friendsOnline.reset();
            return hidden;
        });
        check(noFriends, "Title Screen: Friends Online off hides the strip");
        ctx.runOnClient(c -> { SocialClient.setLoopback(false); c.setScreen(new TitleScreen()); });
        ctx.waitTicks(2);

        // The Wardrobe button opens the Wardrobe; closing it comes back.
        ctx.runOnClient(c -> {
            for (var child : c.currentScreen.children()) {
                if (child instanceof net.minecraft.client.gui.widget.PressableWidget w && w.getMessage().getString().equals("Wardrobe")) {
                    w.onPress(new net.minecraft.client.input.KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
                    break;
                }
            }
        });
        ctx.waitTicks(4);
        String wardrobe = current(ctx);
        check(wardrobe.endsWith("WardrobeScreen"), "Title Screen: the Wardrobe button opens the Wardrobe (" + wardrobe + ")");
        ctx.runOnClient(c -> c.currentScreen.close());
        ctx.waitTicks(4);
        check(evade(ctx), "Title Screen: back from the Wardrobe shows the Evade menu (" + current(ctx) + ")");

        // Reduce Motion: everything still.
        boolean still = ctx.computeOnClient(c -> {
            EvadeClient.modules.get(TitleScreenModule.class).reduceMotion.set(true);
            c.setScreen(new TitleScreen());
            return !TitleArt.anims();
        });
        ctx.waitTicks(4);
        ctx.takeScreenshot("title-reduce-motion");
        ctx.runOnClient(c -> EvadeClient.modules.get(TitleScreenModule.class).reduceMotion.reset());
        check(still, "Title Screen: Reduce Motion stops the menu's animations");

        // Each background still draws.
        for (String mode : new String[]{"Evade Animated", "Evade Grid"}) {
            ctx.runOnClient(c -> { EvadeClient.modules.get(TitleScreenModule.class).background.set(mode); c.setScreen(new TitleScreen()); });
            ctx.waitTicks(6);
            ctx.takeScreenshot("title-bg-" + mode.toLowerCase(Locale.ROOT).replace(' ', '-'));
        }
        ctx.runOnClient(c -> { EvadeClient.modules.get(TitleScreenModule.class).background.reset(); c.setScreen(new TitleScreen()); });
        ctx.waitTicks(4);
    }

    // ------------------------------------------------------------------ frame cost

    private static double fps(ClientGameTestContext ctx) {
        ctx.waitTicks(60);
        return ctx.computeOnClient(c -> {
            float[] w = net.evadeclient.util.FrameStats.framesWithin(2500);
            return 1000.0 * w[0] / Math.max(1, w[1]);
        });
    }

    /**
     * Frame cost against the vanilla title screen under the same conditions (window, GUI scale, panorama): the
     * default (Menu Effects Auto, which is Light on this software renderer) must stay within 1.5x vanilla's frame
     * time. The Full effects and each layer left out are measured and logged, to show where the time goes.
     */
    private static void cost(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); });
        // Vanilla and the Evade default in turn, three times each (the test machine is shared, so one sample can be
        // off): the medians are compared.
        double[] v = new double[3], e = new double[3];
        for (int i = 0; i < 3; i++) {
            ctx.runOnClient(c -> { EvadeClient.modules.get(TitleScreenModule.class).setEnabled(false); c.setScreen(new TitleScreen()); });
            v[i] = fps(ctx);
            ctx.runOnClient(c -> { var t = EvadeClient.modules.get(TitleScreenModule.class); t.setEnabled(true); t.effects.reset();
                c.setScreen(new TitleScreen()); });
            e[i] = fps(ctx);
        }
        java.util.Arrays.sort(v);
        java.util.Arrays.sort(e);
        double vanilla = v[1], auto = e[1];
        double cpu = ctx.computeOnClient(c -> EvadeTitleScreen.frameCostMicros());
        boolean light = ctx.computeOnClient(c -> TitleScreenModule.lightEffects());
        // A still figure (Light) is rendered once and then reused.
        int r0 = ctx.computeOnClient(c -> net.evadeclient.gui.title.TeaserFigure.renders());
        ctx.waitTicks(20);
        int r1 = ctx.computeOnClient(c -> net.evadeclient.gui.title.TeaserFigure.renders());
        if (light) check(r1 == r0, "Title Screen: the still Wardrobe figure isn't re-rendered every frame (" + (r1 - r0) + " renders in 20 ticks)");
        StringBuilder lightProfile = new StringBuilder();
        String[] names = {"panorama", "veils", "embers", "vignette", "glows", "cards", "preview"};
        for (int i = 0; i < names.length; i++) {
            int bit = 1 << i;
            ctx.runOnClient(c -> EvadeTitleScreen.skipLayersForTest = bit);
            lightProfile.append(String.format(Locale.ROOT, " -%s %.0f", names[i], fps(ctx)));
        }
        ctx.runOnClient(c -> EvadeTitleScreen.skipLayersForTest = 0);
        ctx.runOnClient(c -> EvadeClient.modules.get(TitleScreenModule.class).effects.set("Full"));
        double full = fps(ctx);
        int f0 = ctx.computeOnClient(c -> net.evadeclient.gui.title.TeaserFigure.renders());
        ctx.waitTicks(20);
        int f1 = ctx.computeOnClient(c -> net.evadeclient.gui.title.TeaserFigure.renders());
        StringBuilder profile = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            int bit = 1 << i;
            ctx.runOnClient(c -> EvadeTitleScreen.skipLayersForTest = bit);
            profile.append(String.format(Locale.ROOT, " -%s %.0f", names[i], fps(ctx)));
        }
        ctx.runOnClient(c -> { EvadeTitleScreen.skipLayersForTest = 0; EvadeClient.modules.get(TitleScreenModule.class).effects.reset();
            c.setScreen(new TitleScreen()); });
        ctx.waitTicks(4);
        String msg = String.format(Locale.ROOT, "median vanilla %.1f fps, Evade default (%s) %.1f fps (without:%s), Full %.1f fps"
                + " (without:%s; turning figure %d renders/s); menu CPU %.0f us/frame", vanilla, light ? "Light" : "Full", auto,
                lightProfile, full, profile, (f1 - f0), cpu);
        msg += " samples vanilla " + java.util.Arrays.toString(v) + " evade " + java.util.Arrays.toString(e);
        EvadeClient.LOG.info("[gametest] Title Screen cost: {}", msg);
        // Frame time = 1/fps: the default must be within 1.5x of vanilla's (the game caps menus at 60 fps).
        check(auto >= Math.min(55, vanilla / 1.5) && cpu < 4000, "Title Screen: frame cost within 1.5x of vanilla (" + msg + ")");
    }
}
