package net.evadeclient.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.HudEditorScreen;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.client.HudToggle;
import net.evadeclient.util.ClickTracker;
import net.evadeclient.util.HudCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Draws Evade's HUD: world-anchored labels (waypoints, TNT timers) and every enabled HUD element.
 * Registered just before vanilla chat, so chat, the tab list and subtitles stay on top of Evade's widgets.
 * Toasts ({@link net.evadeclient.util.Notifications}) are drawn by their own layer above everything.
 */
public final class Hud {
    private Hud() {}

    /** Per-element render time, only collected while a test/profile turns it on (null = off, zero cost). */
    public static java.util.Map<String, Long> PROFILE;

    /**
     * Evade HUD units to GUI units. Minecraft draws everything at the GUI scale, so with a large GUI scale on a
     * small screen (e.g. Auto on a 1080p laptop with Windows scaling) Evade's widgets got huge and covered the
     * server's scoreboard. With Auto HUD Scale on, the HUD is drawn at about 2 screen pixels per unit on a 1080p
     * screen (more on bigger screens), whatever the GUI scale; HUD Size then scales it to taste.
     */
    public static float scale() {
        var mc = MinecraftClient.getInstance();
        var cs = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        float size = cs == null ? 1f : cs.hudSize.getFloat() / 100f;
        if (cs == null || !cs.autoHudScale.get() || mc.getWindow() == null) return size;
        var win = mc.getWindow();
        double gs = Math.max(1, win.getScaleFactor());
        double target = Math.max(2, Math.min(Math.floor(win.getFramebufferHeight() / 540.0), Math.floor(win.getFramebufferWidth() / 960.0)));
        return (float) (Math.min(1.0, target / gs) * size);
    }

    public static void render(DrawContext ctx) {
        long t0 = System.nanoTime();
        try { renderInner(ctx); } finally { net.evadeclient.util.FrameStats.addHudTime(System.nanoTime() - t0); }
    }

    private static void renderInner(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClickTracker.update();
        if (mc.player == null || EvadeClient.modules == null) return;
        // Toggle Evade HUD key, or a screenshot being taken without Evade's HUD: vanilla's HUD stays.
        if (HudToggle.hiddenNow()) return;

        // Labels that follow world positions (waypoints, TNT timers) stay visible with F3 open.
        if (!mc.options.hudHidden) {
            float td = mc.getRenderTickCounter().getTickProgress(true);
            for (var m : EvadeClient.modules.all()) {
                if (!m.isEnabled()) continue;
                try {
                    m.onHud(ctx, td);
                } catch (Exception e) {
                    ModuleManager.crashed(m, e, "HUD");
                }
            }
        }

        // Corner widgets make way for the full F3 screen only. Pinned debug entries (e.g. the built-in
        // Performance profile, or FPS set to Always) don't count: those leave the rest of the HUD in place.
        if (mc.debugHudEntryList.isF3Enabled()) return;
        // The HUD editor draws the elements itself (with drag handles).
        if (mc.currentScreen instanceof HudEditorScreen) return;

        float k = scale();
        int sw = Math.round(ctx.getScaledWindowWidth() / k), sh = Math.round(ctx.getScaledWindowHeight() / k);
        if (PROFILE != null) PROFILE.merge("__frames", 1L, Long::sum);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(k, k);
        try {
            HudStacks.apply(sw, sh, false);
            // HUD Refresh: widgets that don't move every frame come from the HUD cache's texture (see HudCache),
            // drawn under the ones that do.
            if (HudCache.active()) {
                java.util.List<HudElementModule> cached = new java.util.ArrayList<>(), moving = new java.util.ArrayList<>();
                for (HudElementModule el : EvadeClient.modules.hudElements()) {
                    if (!el.isEnabled() || !el.shouldShow()) continue;
                    // hidden in this replay (the replay editor's HUD mods list)
                    if (net.evadeclient.replay.ReplayOverrides.hudHidden(el.getName())) continue;
                    (HudCache.animated(el) ? moving : cached).add(el);
                }
                HudCache.draw(ctx, cached, sw, sh, k, (c, el) -> drawWidget(c, el, sw, sh));
                for (HudElementModule el : moving) drawWidget(ctx, el, sw, sh);
            } else {
                for (HudElementModule el : EvadeClient.modules.hudElements())
                    if (el.isEnabled() && el.shouldShow() && !net.evadeclient.replay.ReplayOverrides.hudHidden(el.getName())) drawWidget(ctx, el, sw, sh);
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** One widget, timed for the profiler, switched off if it throws. */
    private static void drawWidget(DrawContext ctx, HudElementModule el, int sw, int sh) {
        try {
            if (PROFILE != null) {
                long t = System.nanoTime();
                el.renderScaled(ctx, sw, sh, false);
                PROFILE.merge(el.getName(), System.nanoTime() - t, Long::sum);
            } else el.renderScaled(ctx, sw, sh, false);
        } catch (Exception e) {
            ModuleManager.crashed(el, e, "HUD");
        }
    }

    /**
     * Bottom edge, in GUI pixels, of the vanilla toasts (advancements, recipes, system messages) in the
     * top-right corner; 0 when there are none. Each toast slot is 32 GUI pixels tall.
     */
    public static int toastBottom() {
        var tm = MinecraftClient.getInstance().getToastManager();
        if (tm == null) return 0;
        return ((net.evadeclient.mixin.ToastManagerAccessor) tm).evade$occupiedSpaces().length() * 32;
    }

    /** Toasts are 160 GUI pixels wide, flush with the right edge. */
    private static final int TOAST_WIDTH = 160;

    /**
     * Keeps a top-right corner widget clear of vanilla toasts: while a toast covers it, the widget glides down
     * below the toasts, and back up once they have gone. One per element; call {@link #update} once a frame.
     */
    public static final class ToastDodge {
        private float offset;
        private long last;

        /** @return how far down (HUD units) to draw {@code el} this frame */
        public int update(HudElementModule el, int screenW, int screenH) {
            float target = 0;
            if (el.x < 0 && el.y >= 0) {
                int bottomGui = toastBottom();
                if (bottomGui > 0) {
                    float k = scale();
                    float bottom = bottomGui / k;
                    boolean underToasts = el.resolveY(screenH) < bottom
                            && el.resolveX(screenW) + el.scaledWidth() > screenW - TOAST_WIDTH / k;
                    if (underToasts) target = bottom;
                }
            }
            long now = System.nanoTime();
            float dt = last == 0 ? 1f : Math.min(0.1f, (now - last) / 1_000_000_000f);
            last = now;
            var cs = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
            if (cs != null && !cs.animations.get()) offset = target;
            else offset += (target - offset) * (1f - (float) Math.exp(-dt * 14f));
            if (Math.abs(target - offset) < 0.5f) offset = target;
            return Math.round(offset);
        }
    }
}
