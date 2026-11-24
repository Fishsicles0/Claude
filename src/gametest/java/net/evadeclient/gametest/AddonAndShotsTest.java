package net.evadeclient.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.addon.AddonModule;
import net.evadeclient.addon.EvadeAddons;
import net.evadeclient.gametest.addon.ExampleEvadeAddon;
import net.evadeclient.gui.HudEditorScreen;
import net.evadeclient.gui.ScreenshotGalleryScreen;
import net.evadeclient.hud.HudLayout;
import net.evadeclient.hud.HudStacks;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.hud.FpsHud;
import net.evadeclient.module.hud.PingHud;
import net.evadeclient.module.misc.StreamerMode;
import net.evadeclient.social.SocialChat;
import net.evadeclient.util.Screenshots;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.texture.NativeImage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * v2.4: the Evade Addon API (with the example addon the test mod registers under the "evade" entrypoint), the
 * screenshot gallery's file operations and screen, Streamer Mode in friend messages and summons, and HUD stacks.
 * Leaves every setting, module and file as it found them.
 */
public final class AddonAndShotsTest {
    private AddonAndShotsTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        addons(ctx);
        streamer(ctx);
        stacks(ctx);
        shots(ctx);
    }

    // ------------------------------------------------------------------ addon API

    private static void addons(ClientGameTestContext ctx) {
        String info = ctx.computeOnClient(c -> {
            var g = EvadeClient.modules.get("Example Greeter");
            return ExampleEvadeAddon.registered + "|" + (g instanceof AddonModule) + "|" + EvadeAddons.ownerName(g)
                    + "|" + g.getSettings().size() + "|" + g.isEnabled() + "|" + EvadeAddons.ownerName(EvadeClient.modules.get("Zoom"));
        });
        check(info.equals("1|true|Evade Client Game Tests|6|false|null"),
                "Addon API: the example addon registered once; its module is tagged with the mod's name, has its 6 settings and starts off (" + info + ")");
        boolean clash = ctx.computeOnClient(c -> EvadeAddons.failures().stream().anyMatch(f -> f.contains("Full Bright") && f.contains("clashes"))
                && EvadeClient.modules.all().stream().filter(m -> m.getName().equals("Full Bright")).count() == 0);
        check(clash, "Addon API: a module whose name clashes with a built-in one is refused");
        boolean widgets = ctx.computeOnClient(c -> EvadeClient.modules.hudElements().stream()
                .filter(e -> EvadeAddons.isAddon(e)).map(HudElementModule::getName).toList().containsAll(List.of("Example Ticks", "Example Bar")));
        check(widgets, "Addon API: addon widgets are HUD elements (menu HUD page and HUD editor)");

        ctx.runOnClient(c -> { ExampleEvadeAddon.greeter.ticks = 0; ExampleEvadeAddon.greeter.setEnabled(true); });
        ctx.waitTicks(10);
        check(ctx.computeOnClient(c -> ExampleEvadeAddon.greeter.ticks) > 0, "Addon API: an enabled addon module gets ticks");

        // server rules: by name, and "@addons" / "@addon:<modid>"
        String rules = ctx.computeOnClient(c -> {
            var g = ExampleEvadeAddon.greeter;
            ServerRules.set(Set.of("@addons"));
            boolean off1 = !g.isEnabled();
            g.setEnabled(true);
            boolean stays = !g.isEnabled();
            ServerRules.clear();
            boolean back = g.isEnabled();
            ServerRules.set(Set.of("@addon:evade-gametest"));
            boolean off2 = !g.isEnabled();
            ServerRules.clear();
            ServerRules.set(Set.of("example greeter"));
            boolean off3 = !g.isEnabled();
            ServerRules.clear();
            return off1 + "|" + stays + "|" + back + "|" + off2 + "|" + off3 + "|" + g.isEnabled();
        });
        check(rules.equals("true|true|true|true|true|true"), "Addon API: server rules switch addon modules off (by name, @addons, @addon:<modid>) and they can't be turned back on (" + rules + ")");

        // config: addon settings saved like Evade's own
        String saved = ctx.computeOnClient(c -> {
            var g = ExampleEvadeAddon.greeter;
            g.times.set(3.0);
            try {
                Path tmp = Files.createTempFile("evade-addon", ".json");
                EvadeClient.config.saveTo(tmp);
                JsonObject root = JsonParser.parseString(Files.readString(tmp)).getAsJsonObject();
                Files.deleteIfExists(tmp);
                return root.getAsJsonObject("Example Greeter").getAsJsonObject("settings").get("Times").getAsString();
            } catch (Exception e) {
                return e.toString();
            } finally {
                g.times.reset();
                g.setEnabled(false);
            }
        });
        check(saved.startsWith("3"), "Addon API: addon settings are saved in Evade's config (" + saved + ")");
    }

    // ------------------------------------------------------------------ Streamer Mode

    private static void streamer(ClientGameTestContext ctx) {
        String lines = ctx.computeOnClient(c -> {
            StreamerMode sm = EvadeClient.modules.get(StreamerMode.class);
            boolean was = sm.isEnabled();
            sm.setEnabled(true);
            try {
                String hidden = SocialChat.chatLine(false, "Dex", "secret plans").getString();
                String server = StreamerMode.friendServer("play.example.net");
                sm.setEnabled(false);
                String shown = SocialChat.chatLine(false, "Dex", "secret plans").getString();
                return hidden + "|" + server + "|" + shown;
            } finally {
                sm.setEnabled(was);
            }
        });
        check(!lines.split("\\|")[0].contains("secret") && lines.split("\\|")[0].contains("hidden")
                        && lines.split("\\|")[1].equals("a server") && lines.split("\\|")[2].contains("secret plans"),
                "Streamer Mode: friend message chat lines and friends' servers are hidden while on, shown when off (" + lines + ")");
    }

    // ------------------------------------------------------------------ HUD stacks

    private static void stacks(ClientGameTestContext ctx) {
        String before = ctx.computeOnClient(c -> HudLayout.snapshot());
        String r = ctx.computeOnClient(c -> {
            HudElementModule fps = EvadeClient.modules.get(FpsHud.class), ping = EvadeClient.modules.get(PingHud.class);
            fps.setEnabled(true);
            ping.setEnabled(true);
            fps.x = 10; fps.y = 40; ping.x = 200; ping.y = 90;
            int w = 640, h = 360;
            String id = HudStacks.group(List.of(ping, fps), w, h);
            HudStacks.apply(w, h, true);
            boolean lined = ping.x == fps.x && ping.resolveY(h) == fps.resolveY(h) + fps.scaledHeight() + HudStacks.GAP;
            boolean inSnap = HudLayout.snapshot().contains("\"g\":\"" + id + "\"");
            // moving the top widget carries the other along
            fps.y = 100;
            HudStacks.apply(w, h, true);
            boolean follows = ping.resolveY(h) == 100 + fps.scaledHeight() + HudStacks.GAP;
            int n = HudStacks.ungroup(List.of(fps));
            boolean clear = !HudStacks.inStack(fps) && !HudStacks.inStack(ping);
            return id + "|" + lined + "|" + inSnap + "|" + follows + "|" + n + "|" + clear;
        });
        check(r.matches("s\\w+\\|true\\|true\\|true\\|2\\|true"), "HUD stacks: grouped widgets line up under each other, follow the top one, are in undo snapshots and ungroup (" + r + ")");

        // the editor: Stack is one undo step
        ctx.runOnClient(c -> HudLayout.restore(before));
        ctx.setScreen(() -> new HudEditorScreen(null));
        ctx.waitTicks(2);
        String ed = ctx.computeOnClient(c -> {
            var s = (HudEditorScreen) c.currentScreen;
            HudElementModule fps = EvadeClient.modules.get(FpsHud.class), ping = EvadeClient.modules.get(PingHud.class);
            fps.setEnabled(true);
            ping.setEnabled(true);
            s.selection().clear();
            s.selection().add(fps);
            s.selection().add(ping);
            int d0 = s.undoDepth();
            s.stackSelection();
            boolean stacked = HudStacks.inStack(fps) && fps.stack.get().equals(ping.stack.get());
            s.undo();
            return (s.undoDepth() - d0) + "|" + stacked + "|" + HudStacks.inStack(fps);
        });
        check(ed.equals("0|true|false"), "HUD editor: Stack groups the selection and Ctrl+Z takes it apart (" + ed + ")");
        ctx.runOnClient(c -> { HudLayout.restore(before); c.setScreen(null); });
    }

    // ------------------------------------------------------------------ screenshots

    private static void shots(ClientGameTestContext ctx) {
        Path dir = ctx.computeOnClient(c -> Screenshots.folder());
        Path a = dir.resolve("evade-gallery-test-a.png"), b = dir.resolve("evade-gallery-test-b.png");
        try {
            Files.createDirectories(dir);
            writePng(a, 0xFFE11D2E);
            writePng(b, 0xFF3D7BFF);
            Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        } catch (Exception e) {
            throw new AssertionError("FAILED: couldn't write test screenshots: " + e);
        }
        try {
            // file operations (on the client thread: the folder comes from the game)
            Path fa = a;
            String ops = ctx.computeOnClient(c -> {
                StringBuilder out = new StringBuilder();
                try {
                    List<Screenshots.Shot> list = Screenshots.list();
                    int ia = indexOf(list, fa), ib = indexOf(list, b);
                    out.append(ia >= 0 && ib >= 0 && ib < ia).append('|');
                    out.append(Screenshots.cleanName("../evil") == null && Screenshots.cleanName("a/b.png") == null
                            && "nice shot.png".equals(Screenshots.cleanName("nice shot"))).append('|');
                    Path renamed = Screenshots.rename(fa, "evade-gallery-test-renamed");
                    out.append(Files.exists(renamed) && !Files.exists(fa) && renamed.getFileName().toString().equals("evade-gallery-test-renamed.png")).append('|');
                    boolean taken;
                    try { Screenshots.rename(renamed, "evade-gallery-test-b"); taken = false; } catch (java.io.IOException e) { taken = true; }
                    out.append(taken && Files.exists(renamed)).append('|');
                    Screenshots.Trashed t = Screenshots.trash(renamed);
                    out.append(!Files.exists(renamed) && Files.exists(t.inTrash()) && t.inTrash().getParent().equals(Screenshots.trashFolder())).append('|');
                    Screenshots.restore(t);
                    out.append(Files.exists(renamed) && !Files.exists(t.inTrash()));
                } catch (Exception e) {
                    out.append("error ").append(e);
                }
                return out.toString();
            });
            String[] r = ops.split("\\|");
            check(r.length == 6, "Screenshots: file operations ran (" + ops + ")");
            check(r[0].equals("true"), "Screenshots: the gallery lists the folder's PNGs, newest first");
            check(r[1].equals("true"), "Screenshots: rename only takes plain file names");
            check(r[2].equals("true"), "Screenshots: rename keeps the file in the folder with .png");
            check(r[3].equals("true"), "Screenshots: rename won't overwrite another screenshot");
            check(r[4].equals("true"), "Screenshots: delete moves the file to screenshots/.trash");
            check(r[5].equals("true"), "Screenshots: undo puts a deleted screenshot back");
            a = dir.resolve("evade-gallery-test-renamed.png");

            // the screen: lists, loads thumbnails and the full view, deletes with undo
            ctx.setScreen(() -> new ScreenshotGalleryScreen(null));
            ctx.waitFor(c -> c.currentScreen instanceof ScreenshotGalleryScreen s && s.loadedForTest(), 200);
            Path fb = b;
            ctx.waitFor(c -> ((ScreenshotGalleryScreen) c.currentScreen).thumbReadyForTest(fb.getFileName().toString()), 200);
            check(true, "Screenshot gallery: opens, lists the folder and loads thumbnails in the background");
            int idx = ctx.computeOnClient(c -> indexOf(((ScreenshotGalleryScreen) c.currentScreen).shotsForTest(), fb));
            ctx.runOnClient(c -> ((ScreenshotGalleryScreen) c.currentScreen).openViewForTest(idx));
            ctx.waitFor(c -> ((ScreenshotGalleryScreen) c.currentScreen).fullReadyForTest(), 200);
            check(true, "Screenshot gallery: the full view loads the image");
            ctx.takeScreenshot("evade-screenshot-gallery");
            ctx.runOnClient(c -> ((ScreenshotGalleryScreen) c.currentScreen).deleteCurrentForTest());
            ctx.waitFor(c -> ((ScreenshotGalleryScreen) c.currentScreen).hasUndoForTest(), 100);
            check(!Files.exists(b), "Screenshot gallery: Delete moves the screenshot to the trash");
            ctx.runOnClient(c -> ((ScreenshotGalleryScreen) c.currentScreen).undo());
            ctx.waitFor(c -> Files.exists(fb), 100);
            check(Files.exists(b), "Screenshot gallery: Undo brings it back");
            ctx.waitTicks(5);
            ctx.setScreen(() -> null);
        } finally {
            try {
                Files.deleteIfExists(a);
                Files.deleteIfExists(b);
                Files.deleteIfExists(dir.resolve("evade-gallery-test-a.png"));
                try (var s = Files.list(dir.resolve(Screenshots.TRASH))) {
                    for (Path p : s.toList()) if (p.getFileName().toString().startsWith("evade-gallery-test")) Files.deleteIfExists(p);
                } catch (Exception ignored) {}
                // the screenshot the test just took stays: it is the test's own output
            } catch (Exception ignored) {}
        }
    }

    private static int indexOf(List<Screenshots.Shot> list, Path p) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).file().equals(p)) return i;
        return -1;
    }

    private static void writePng(Path p, int argb) throws Exception {
        try (NativeImage img = new NativeImage(320, 180, false)) {
            for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++) img.setColorArgb(x, y, (x / 40 + y / 45) % 2 == 0 ? argb : 0xFF101014);
            img.writeTo(p);
        }
    }
}
