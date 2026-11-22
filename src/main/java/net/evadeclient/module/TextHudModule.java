package net.evadeclient.module;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/** A HUD element that shows one or more lines of text, e.g. FPS, CPS, ping, coordinates. */
public abstract class TextHudModule extends HudElementModule {
    public final ColorSetting color = add(new ColorSetting("Text Color", 0xFFFFFFFF));
    public final BooleanSetting brackets = add(new BooleanSetting("Brackets", false));
    public final BooleanSetting shadow = add(new BooleanSetting("Text Shadow", true));
    public final BooleanSetting chroma = add(new BooleanSetting("Chroma", false));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", false));

    private static final net.minecraft.text.Style UI_FONT = net.minecraft.text.Style.EMPTY
            .withFont(new net.minecraft.text.StyleSpriteSource.Font(net.minecraft.util.Identifier.of("evade", "ui")));

    protected TextHudModule(String name, String description, int x, int y) {
        super(name, description, x, y);
    }

    protected TextHudModule(String name, String description, Category category, int x, int y) {
        super(name, description, category, x, y);
    }

    /** Lines to show; return an empty list to hide the element. */
    protected abstract List<String> lines(boolean preview);

    // Text, widths and size are rebuilt once per client tick (20/s) instead of every frame.
    private List<net.minecraft.text.Text> cachedLines;
    private int[] cachedWidths;
    private long cachedTick = -1;
    /** Test hook: rebuild text every frame like before (to measure what the per-tick cache saves). */
    public static boolean NO_CACHE = false;

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        long tick = net.evadeclient.EvadeClient.ticks;
        if (preview || NO_CACHE || cachedLines == null || cachedTick != tick) {
            List<String> raw = lines(preview);
            List<net.minecraft.text.Text> lines = new java.util.ArrayList<>(raw.size());
            int[] widths = new int[raw.size()];
            int w = 0;
            boolean smooth = smoothFont.get();
            for (int i = 0; i < raw.size(); i++) {
                String l = brackets.get() ? "[" + raw.get(i) + "]" : raw.get(i);
                var t = net.minecraft.text.Text.literal(l);
                if (smooth) t.setStyle(UI_FONT);
                lines.add(t);
                widths[i] = tr.getWidth(t);
                w = Math.max(w, widths[i]);
            }
            width = lines.isEmpty() ? 40 : Math.max(40, w + 8);
            height = lines.isEmpty() ? 13 : lines.size() * 10 + 4;
            if (preview) { draw(ctx, left, top, lines, widths); return; }
            cachedLines = lines; cachedWidths = widths; cachedTick = tick;
        }
        draw(ctx, left, top, cachedLines, cachedWidths);
    }

    private void draw(DrawContext ctx, int left, int top, List<net.minecraft.text.Text> lines, int[] widths) {
        if (lines.isEmpty()) return;
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int y = top + 3;
        int c = color.get();
        boolean sh = shadow.get();
        for (int i = 0; i < lines.size(); i++) {
            int x = left + (width - widths[i]) / 2;
            if (chroma.get()) drawChroma(ctx, tr, lines.get(i), x, y, sh);
            else ctx.drawText(tr, lines.get(i), x, y, c, sh);
            y += 10;
        }
    }

    /** Rainbow text: each character's hue drifts with time and its position, so the colour flows across. */
    private void drawChroma(DrawContext ctx, net.minecraft.client.font.TextRenderer tr, net.minecraft.text.Text line, int x, int y, boolean sh) {
        String str = line.getString();
        var style = line.getStyle();
        double t = (System.currentTimeMillis() % 4000L) / 4000.0;
        for (int i = 0; i < str.length(); i++) {
            var ch = net.minecraft.text.Text.literal(String.valueOf(str.charAt(i))).setStyle(style);
            float hue = (float) ((t - (x + i * 6) / 400.0) % 1.0 + 1.0) % 1.0f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 1f);
            ctx.drawText(tr, ch, x, y, 0xFF000000 | rgb, sh);
            x += tr.getWidth(ch);
        }
    }
}
