package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Small toasts that slide in from the right (e.g. "Zoom enabled" when you use a keybind). In game they sit at
 * the middle of the right edge; while a menu is open they stack from the top-right corner, drawn over the menu.
 * Safe to push from any thread.
 */
public final class Notifications {
    private record Toast(String title, String text, boolean good, long start) {}

    private static final List<Toast> toasts = new ArrayList<>();
    private static final long LIFE = 2200;
    private static final int MAX_WIDTH = 220;
    /** Height of one toast; toasts stack 4 px apart. */
    private static final int H = 26;
    private static final String ELLIPSIS = "...";

    private Notifications() {}

    public static void push(String title, String text, boolean good) {
        synchronized (toasts) {
            toasts.add(new Toast(title == null ? "" : title, text == null ? "" : text, good, System.currentTimeMillis()));
            if (toasts.size() > 5) toasts.removeFirst();
        }
    }

    /** Draws the live toasts in HUD units (see {@link net.evadeclient.hud.Hud#scale()}). */
    public static void render(DrawContext ctx) {
        long now = System.currentTimeMillis();
        List<Toast> live;
        synchronized (toasts) {
            toasts.removeIf(t -> now - t.start > LIFE);
            if (toasts.isEmpty()) return;
            live = new ArrayList<>(toasts);
        }
        // The built-in clip recorder films the finished frame: keep toasts out of it unless the player opted in
        // ("Show Overlays In Clips"). Clips shows its own state in the window title meanwhile. Toasts still expire
        // on time above, so none pops up stale once recording stops.
        if (net.evadeclient.module.misc.Clips.hideOverlays()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        float k = net.evadeclient.hud.Hud.scale();
        int sw = Math.round(ctx.getScaledWindowWidth() / k), sh = Math.round(ctx.getScaledWindowHeight() / k);
        int maxW = Math.max(60, Math.min(MAX_WIDTH, sw / 3));
        int y = mc.currentScreen != null ? 6 : sh / 2 - 20;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(k, k);
        try {
            for (Toast t : live) {
                long age = now - t.start;
                float in = Math.min(1, age / 180f), out = Math.min(1, (LIFE - age) / 220f);
                float vis = ease(Math.max(0, Math.min(in, out)));
                int w = Math.min(maxW, Math.max(tr.getWidth(t.title), tr.getWidth(t.text)) + 18);
                String title = fit(tr, t.title, w - 18), text = fit(tr, t.text, w - 18);
                // Slides in device pixels rather than whole GUI pixels, so the motion stays smooth at any scale.
                float xf = sw - (w + 6) * vis;
                m.pushMatrix();
                m.translate(Theme.snap(ctx, xf), y);
                try {
                    Theme.shadow(ctx, Theme.SH_HUD, 0, 0, w, H, Theme.alpha(0xFF000000, 0.5f * vis));
                    Theme.round(ctx, 0, 0, w, H, 0xE60A0A0C, Theme.R);
                    // Side bar: accent for good news, grey otherwise, inset so it clears the rounded corners.
                    Theme.round(ctx, 0, 3, 2, H - 3, t.good ? Theme.RED : Theme.TEXT_FAINT, 1);
                    ctx.drawTextWithShadow(tr, title, 9, 4, Theme.TEXT);
                    ctx.drawTextWithShadow(tr, text, 9, 15, t.good ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
                    // Time bar along the bottom edge, kept between the rounded corners.
                    float remaining = Math.max(0f, 1f - age / (float) LIFE);
                    int bw = Math.round((w - 6) * remaining);
                    Theme.fill(ctx, 3, H - 1, 3 + bw, H, Theme.alpha(Theme.RED, 0.6f));
                } finally {
                    m.popMatrix();
                }
                y += H + 4;
            }
        } finally {
            m.popMatrix();
        }
    }

    /** {@code s} cut to {@code width} pixels, ending in "..." when it had to be shortened. */
    private static String fit(TextRenderer tr, String s, int width) {
        if (tr.getWidth(s) <= width) return s;
        return tr.trimToWidth(s, Math.max(0, width - tr.getWidth(ELLIPSIS))).stripTrailing() + ELLIPSIS;
    }

    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
}
