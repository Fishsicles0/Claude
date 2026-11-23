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
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        float k = net.evadeclient.hud.Hud.scale();
        int sw = Math.round(ctx.getScaledWindowWidth() / k), sh = Math.round(ctx.getScaledWindowHeight() / k);
        int maxW = Math.max(60, Math.min(MAX_WIDTH, sw / 3));
        int y = mc.currentScreen != null ? 6 : sh / 2 - 20;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(k, k);
        try {
            for (Toast t : live) {
                long age = now - t.start;
                float in = Math.min(1, age / 180f), out = Math.min(1, (LIFE - age) / 220f);
                float vis = ease(Math.max(0, Math.min(in, out)));
                int w = Math.min(maxW, Math.max(tr.getWidth(t.title), tr.getWidth(t.text)) + 18);
                String title = fit(tr, t.title, w - 18), text = fit(tr, t.text, w - 18);
                int x = sw - Math.round((w + 6) * vis);
                Theme.round(ctx, x, y, x + w, y + 26, 0xE60A0A0C);
                ctx.fill(x, y + 2, x + 2, y + 24, t.good ? Theme.RED : Theme.TEXT_FAINT);
                ctx.drawTextWithShadow(tr, title, x + 9, y + 4, Theme.TEXT);
                ctx.drawTextWithShadow(tr, text, x + 9, y + 15, t.good ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
                // time bar
                int bw = Math.max(0, Math.round((w - 4) * (1 - age / (float) LIFE)));
                ctx.fill(x + 2, y + 25, x + 2 + bw, y + 26, Theme.alpha(Theme.RED, 0.6f));
                y += 30;
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** {@code s} cut to {@code width} pixels, ending in "..." when it had to be shortened. */
    private static String fit(TextRenderer tr, String s, int width) {
        if (tr.getWidth(s) <= width) return s;
        return tr.trimToWidth(s, Math.max(0, width - tr.getWidth(ELLIPSIS))).stripTrailing() + ELLIPSIS;
    }

    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
}
