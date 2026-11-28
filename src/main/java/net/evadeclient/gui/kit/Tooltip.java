package net.evadeclient.gui.kit;

import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The kit tooltip (spec 6.11): e5, r5, 6 x 8 padding, at most 220 wide, BODY n950 with an optional SMALL n700 second
 * paragraph. Placed above its target by default (below when there is no room), never over the target itself, so it
 * never hides the control it describes. It eases in (y -4 → 0) after the kit's 450 ms delay. Vanilla text tooltips on
 * Evade screens are drawn by this too ({@code mixin/TooltipRendererMixin}).
 */
public final class Tooltip {
    private Tooltip() {}

    public static final int MAX_W = 220, PAD_X = 8, PAD_Y = 6;

    /** Draw for the kit (target rect in GUI px), at opacity {@code a}. */
    static void draw(Kit k, String text, String sub, int x1, int y1, int x2, int y2, float a) {
        draw(k.ctx, k.tr, k.smooth, text, sub, x1, y1, x2, y2, a);
    }

    /**
     * Draw a tooltip for the target (x1, y1)-(x2, y2): centred above it, or below it when it would leave the screen,
     * kept 4 px inside the screen edges.
     */
    public static void draw(DrawContext ctx, TextRenderer tr, boolean smooth, String text, String sub, int x1, int y1,
                            int x2, int y2, float a) {
        List<String> lines = wrap(tr, smooth, text, Role.BODY, MAX_W - 2 * PAD_X);
        List<String> subLines = sub == null || sub.isEmpty() ? List.of() : wrap(tr, smooth, sub, Role.SMALL, MAX_W - 2 * PAD_X);
        int w = 0;
        for (String l : lines) w = Math.max(w, UiFont.width(tr, l, Role.BODY, smooth));
        for (String l : subLines) w = Math.max(w, UiFont.width(tr, l, Role.SMALL, smooth));
        w += 2 * PAD_X;
        int lh = 11, slh = 10;
        int h = 2 * PAD_Y + lines.size() * lh + (subLines.isEmpty() ? 0 : 3 + subLines.size() * slh) - 2;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int x = Math.max(4, Math.min(sw - w - 4, (x1 + x2) / 2 - w / 2));
        int y = y1 - h - 6;
        boolean below = y < 4;
        if (below) y = Math.min(sh - h - 4, y2 + 6);
        float f = Theme.fade;
        Theme.fade = f * Math.max(0, Math.min(1, a));
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(0, Theme.snap(ctx, (1 - a) * (below ? 4 : -4)));
            Paint.surface(ctx, x, y, x + w, y + h, Tokens.R_SM, Tokens.E5.noAmbient(), 0, Float.NaN);
            int ty = y + PAD_Y - 1;
            for (String l : lines) {
                UiFont.draw(ctx, tr, l, x + PAD_X, ty, Tokens.N950, Role.BODY, smooth);
                ty += lh;
            }
            if (!subLines.isEmpty()) ty += 3;
            for (String l : subLines) {
                UiFont.draw(ctx, tr, l, x + PAD_X, ty, Tokens.N700, Role.SMALL, smooth);
                ty += slh;
            }
        } finally {
            m.popMatrix();
            Theme.fade = f;
        }
    }

    /** Draw for a mouse position only (vanilla tooltips): the target is a small box around the cursor. */
    public static void drawAtMouse(DrawContext ctx, List<String> lines, int mx, int my) {
        drawFor(ctx, lines, mx - 6, my - 6, mx + 6, my + 14);
    }

    /** Draw vanilla tooltip lines for the target (x1, y1)-(x2, y2): the first line is the text, the rest the second
     *  paragraph. */
    public static void drawFor(DrawContext ctx, List<String> lines, int x1, int y1, int x2, int y2) {
        if (lines.isEmpty()) return;
        Tokens.refresh();
        var cs = net.evadeclient.EvadeClient.modules == null ? null
                : net.evadeclient.EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        boolean smooth = (cs == null || cs.smoothFont.get()) && !Theme.square;
        // Vanilla has already wrapped one sentence into several lines: join them and re-wrap in the smooth font.
        String first = String.join(" ", lines).replaceAll("\\s+", " ").strip();
        String rest = null;
        draw(ctx, MinecraftClient.getInstance().textRenderer, smooth, first, rest, x1, y1, x2, y2, 1f);
    }

    private static List<String> wrap(TextRenderer tr, boolean smooth, String s, Role r, int max) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = line.isEmpty() ? word : line + " " + word;
                if (UiFont.width(tr, trial, r, smooth) <= max || line.isEmpty()) { line.setLength(0); line.append(trial); continue; }
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }
}
