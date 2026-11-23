package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Small toasts that slide in from the right (e.g. "Zoom enabled" when you use a keybind). */
public final class Notifications {
    private record Toast(String title, String text, boolean good, long start) {}

    private static final List<Toast> toasts = new ArrayList<>();
    private static final long LIFE = 2200;

    private Notifications() {}

    public static void push(String title, String text, boolean good) {
        toasts.add(new Toast(title, text, good, System.currentTimeMillis()));
        if (toasts.size() > 5) toasts.removeFirst();
    }

    public static void render(DrawContext ctx) {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.start > LIFE);
        var tr = MinecraftClient.getInstance().textRenderer;
        float k = net.evadeclient.hud.Hud.scale(); // drawn inside the HUD's scale
        int sw = Math.round(ctx.getScaledWindowWidth() / k);
        int y = Math.round(ctx.getScaledWindowHeight() / k) / 2 - 20;
        for (Toast t : toasts) {
            long age = now - t.start;
            float in = Math.min(1, age / 180f), out = Math.min(1, (LIFE - age) / 220f);
            float vis = ease(Math.min(in, out));
            int w = Math.max(tr.getWidth(t.title), tr.getWidth(t.text)) + 18;
            int x = sw - Math.round((w + 6) * vis);
            Theme.round(ctx, x, y, x + w, y + 26, 0xE60A0A0C);
            ctx.fill(x, y + 2, x + 2, y + 24, t.good ? Theme.RED : Theme.TEXT_FAINT);
            ctx.drawTextWithShadow(tr, t.title, x + 9, y + 4, Theme.TEXT);
            ctx.drawTextWithShadow(tr, t.text, x + 9, y + 15, t.good ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
            // time bar
            int bw = Math.round((w - 4) * (1 - age / (float) LIFE));
            ctx.fill(x + 2, y + 25, x + 2 + bw, y + 26, Theme.alpha(Theme.RED, 0.6f));
            y += 30;
        }
    }

    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
}
