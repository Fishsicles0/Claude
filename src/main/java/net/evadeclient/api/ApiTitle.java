package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * A server title in Evade's style ({@code title}): a large title with an accent rule and an optional subtitle,
 * fading in, staying and fading out like vanilla's (times in ticks, 20 a second). A new title replaces the old one;
 * a {@code title} with neither title nor subtitle clears it.
 */
public final class ApiTitle {
    private static final int MAX_FADE = 100, MAX_STAY = 600;
    private static final float TITLE_SCALE = 2.6f, SUB_SCALE = 1.3f;

    private static String title, subtitle;
    private static int color;
    private static long start, fadeIn, stay, fadeOut;

    private ApiTitle() {}

    static boolean handle(JsonObject o) {
        String t = ApiJson.str(o, "title", "", ApiJson.MAX_TEXT);
        String s = ApiJson.str(o, "subtitle", "", ApiJson.MAX_TEXT);
        if (t.isEmpty() && s.isEmpty()) {
            boolean had = title != null;
            clear();
            return had;
        }
        title = t;
        subtitle = s;
        color = ApiJson.color(o, "color", 0);
        fadeIn = ticks(o, "fadeIn", 10, MAX_FADE);
        stay = ticks(o, "stay", 70, MAX_STAY);
        fadeOut = ticks(o, "fadeOut", 20, MAX_FADE);
        start = System.currentTimeMillis();
        return true;
    }

    /** A time field in ticks, as milliseconds. */
    private static long ticks(JsonObject o, String key, int def, int max) {
        double t = ApiJson.num(o, key, def);
        return Math.round(Math.max(0, Math.min(max, t)) * 50);
    }

    /** Whether a title is on screen (for tests). */
    public static boolean showing() {
        return title != null && System.currentTimeMillis() - start < fadeIn + stay + fadeOut;
    }

    static void clear() {
        title = subtitle = null;
    }

    /** Draws the title, centred in the upper third of the screen, in GUI pixels. */
    static void render(DrawContext ctx) {
        if (title == null) return;
        long age = System.currentTimeMillis() - start;
        long total = fadeIn + stay + fadeOut;
        if (age >= total) {
            clear();
            return;
        }
        float a = age < fadeIn ? age / (float) Math.max(1, fadeIn)
                : age > fadeIn + stay ? (total - age) / (float) Math.max(1, fadeOut) : 1f;
        a = Math.max(0f, Math.min(1f, a));
        if (a < 0.03f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        float cx = sw / 2f;
        // Slides up a few pixels as it fades in.
        float rise = (1 - ease(Math.min(1f, age / (float) Math.max(1, fadeIn)))) * 6f;
        float y = sh * 0.3f + rise;
        int textColor = Theme.alpha(color == 0 ? Theme.TEXT : color | 0xFF000000, a);
        var m = ctx.getMatrices();
        int titleW = tr.getWidth(title);
        if (!title.isEmpty()) {
            float k = Math.min(TITLE_SCALE, (sw - 20f) / Math.max(1, titleW));
            m.pushMatrix();
            try {
                m.translate(cx, y - 9 * k);
                m.scale(k, k);
                ctx.drawTextWithShadow(tr, title, -titleW / 2, 0, textColor);
            } finally {
                m.popMatrix();
            }
            // Accent rule under the title, growing out from the middle.
            int half = Math.round(Math.min(titleW * k, sw - 20f) / 2f * ease(a));
            Theme.fill(ctx, Math.round(cx) - half, Math.round(y + 3), Math.round(cx) + half, Math.round(y + 4), Theme.alpha(Theme.RED, a));
        }
        if (!subtitle.isEmpty()) {
            int subW = tr.getWidth(subtitle);
            float k = Math.min(SUB_SCALE, (sw - 20f) / Math.max(1, subW));
            m.pushMatrix();
            try {
                m.translate(cx, y + (title.isEmpty() ? -4 : 9));
                m.scale(k, k);
                ctx.drawTextWithShadow(tr, subtitle, -subW / 2, 0, Theme.alpha(Theme.TEXT_SOFT, a));
            } finally {
                m.popMatrix();
            }
        }
    }

    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
}
