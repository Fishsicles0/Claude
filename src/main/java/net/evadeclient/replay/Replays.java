package net.evadeclient.replay;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Wires replays into the game: clocks, keys, the playback bar, and the buttons in the menus. */
public final class Replays {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final boolean[] down = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private Replays() {}

    public static void init() {
        ReplayRecorder.cleanUp();
        ClientTickEvents.START_CLIENT_TICK.register(c -> {
            ReplayPlayer.clientTick();
            // P and E are the replay's play and editor keys: keep Social Interactions and the inventory from opening too
            if (ReplayPlayer.active() && c.currentScreen == null) {
                while (c.options.socialInteractionsKey.wasPressed()) {}
                while (c.options.inventoryKey.wasPressed()) {}
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            ReplayRecorder.tick();
            ReplayAudio.onTick(ReplayPlayer.current());
            if (ReplayPlayer.active()) {
                ReplayOverrides.onTick();
                keys();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ReplayPlayer.onDisconnect(handler.getConnection());
            if (!ReplayPlayer.isReplayConnection(handler.getConnection())) {
                var r = net.evadeclient.EvadeClient.modules.get(net.evadeclient.module.misc.Replay.class);
                ReplayRecorder.end(r != null && r.saveOnLeave());
            }
        });
        HudElementRegistry.addLast(Identifier.of("evade", "replay_bar"), (ctx, tc) -> drawBar(ctx));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Replays"), b -> client.setScreen(new ReplayBrowserScreen(screen)))
                        .dimensions(6, 6, 70, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Watch your 3D replays, make camera paths and render video"))).build());
            }
            if (screen instanceof GameMenuScreen) addPauseButtons(screen, w);
        });
    }

    // ------------------------------------------------------------------ pause menu

    private static void addPauseButtons(net.minecraft.client.gui.screen.Screen screen, int w) {
        var buttons = Screens.getButtons(screen);
        if (ReplayPlayer.active()) {
            buttons.add(ButtonWidget.builder(Text.literal("Replay editor"), b -> mc.setScreen(new ReplayEditorScreen()))
                    .dimensions(6, 6, 100, 20).build());
            return;
        }
        if (!ReplayRecorder.active()) return;
        boolean rec = ReplayRecorder.recording();
        buttons.add(ButtonWidget.builder(Text.literal(rec ? "■ Save replay" : "● Start recording"), b -> {
            net.evadeclient.module.misc.Replay.toggleRecording();
            mc.setScreen(null);
        }).dimensions(6, 6, 110, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(rec
                ? "Save what you've recorded as a 3D replay (Title screen → Replays to watch it)"
                : "Record the world in 3D from now on, to fly through it and render video later"))).build());
        if (rec) {
            buttons.add(ButtonWidget.builder(Text.literal(ReplayRecorder.paused() ? "Resume" : "Pause"), b -> {
                ReplayRecorder.togglePause();
                mc.setScreen(null);
            }).dimensions(120, 6, 60, 20).build());
            buttons.add(ButtonWidget.builder(Text.literal("Marker"), b -> {
                ReplayRecorder.marker("Marker");
                net.evadeclient.util.Notifications.push("Replay", "Marker added", true);
                mc.setScreen(null);
            }).dimensions(184, 6, 60, 20).build());
        }
    }

    // ------------------------------------------------------------------ keys while watching

    private static boolean pressed(int key) {
        boolean now = GLFW.glfwGetKey(mc.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
        boolean edge = now && !down[key];
        down[key] = now;
        return edge;
    }

    private static void keys() {
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null || mc.currentScreen != null || ReplayRender.rendering()) {
            // keep edge state in sync so a key held while a screen closes doesn't fire
            for (int k : new int[]{GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_E})
                down[k] = GLFW.glfwGetKey(mc.getWindow().getHandle(), k) == GLFW.GLFW_PRESS;
            return;
        }
        if (pressed(GLFW.GLFW_KEY_P)) p.togglePlay();
        if (pressed(GLFW.GLFW_KEY_LEFT)) p.seek(p.tick() - 100);
        if (pressed(GLFW.GLFW_KEY_RIGHT)) p.seek(p.tick() + 100);
        if (pressed(GLFW.GLFW_KEY_UP)) p.setSpeed(p.speed() * 2);
        if (pressed(GLFW.GLFW_KEY_DOWN)) p.setSpeed(p.speed() / 2);
        if (pressed(GLFW.GLFW_KEY_K)) {
            ReplayEditorScreen.addKeyframe(p);
            net.evadeclient.util.Notifications.push("Camera", p.project.camera.size() + " keyframes", true);
        }
        if (pressed(GLFW.GLFW_KEY_E)) mc.setScreen(new ReplayEditorScreen());
    }

    // ------------------------------------------------------------------ the playback bar

    static String time(double seconds) {
        int s = (int) Math.floor(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static void drawBar(DrawContext ctx) {
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null || ReplayRender.rendering() || mc.currentScreen instanceof ReplayEditorScreen || mc.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int x0 = 10, x1 = w - 10, y = h - 30;
        ctx.fill(x0 - 4, y - 16, x1 + 4, y + 22, 0x99000000);
        double now = p.seconds(p.tick()), total = p.seconds(p.endTick());
        String state = p.seeking() ? "Loading..." : (p.playing() ? "▶ " : "❚❚ ") + time(now) + " / " + time(total)
                + (Math.abs(p.speed() - 1) > 1e-3 ? String.format(Locale.ROOT, "   %sx", trim(p.speed())) : "");
        ctx.drawTextWithShadow(mc.textRenderer, state, x0, y - 12, 0xFFFFFFFF);
        String hint = "P play · ←/→ 5 s · ↑/↓ speed · K keyframe · E editor";
        ctx.drawTextWithShadow(mc.textRenderer, hint, x1 - mc.textRenderer.getWidth(hint), y - 12, 0xFF9A9AA6);
        ctx.fill(x0, y + 4, x1, y + 8, 0xFF3A3A44);
        int span = Math.max(1, p.endTick() - p.startTick());
        int px = x0 + (int) ((x1 - x0) * (double) (p.tick() - p.startTick()) / span);
        ctx.fill(x0, y + 4, px, y + 8, 0xFFE5484D);
        for (var m : p.meta.markers) {
            int mx = x0 + (int) ((x1 - x0) * (double) (m.tick() - p.startTick()) / span);
            ctx.fill(mx - 1, y, mx + 1, y + 12, 0xFFFFC04D);
        }
        for (var k : p.project.camera) {
            int kx = x0 + (int) ((x1 - x0) * (k.tick - p.startTick()) / span);
            ctx.fill(kx - 2, y + 10, kx + 2, y + 14, 0xFF4C8DFF);
        }
        ctx.fill(px - 1, y + 1, px + 1, y + 11, 0xFFFFFFFF);
    }

    static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
