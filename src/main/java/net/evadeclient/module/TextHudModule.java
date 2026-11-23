package net.evadeclient.module;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;

/**
 * A HUD element that shows one or more lines of text, e.g. FPS, CPS, ping, coordinates.
 *
 * <p>Subclasses return plain {@link #lines} (or styled ones from {@link #styledLines}); the optional hooks below
 * colour lines per frame, reserve a leading icon area and draw extra content (a bar, a graph) under the text.
 * Every hook defaults to drawing exactly what a plain text element draws.
 */
public abstract class TextHudModule extends HudElementModule {
    public final ColorSetting color = add(new ColorSetting("Text Color", 0xFFFFFFFF));
    public final BooleanSetting brackets = add(new BooleanSetting("Brackets", false));
    public final BooleanSetting shadow = add(new BooleanSetting("Text Shadow", true));
    public final BooleanSetting chroma = add(new BooleanSetting("Chroma", false));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", false));

    /** The smooth (Inter) HUD font, applied to every line while Smooth Font is on. */
    public static final net.minecraft.text.Style UI_FONT = net.minecraft.text.Style.EMPTY
            .withFont(new net.minecraft.text.StyleSpriteSource.Font(net.minecraft.util.Identifier.of("evade", "ui")));

    protected TextHudModule(String name, String description, int x, int y) {
        super(name, description, x, y);
    }

    protected TextHudModule(String name, String description, Category category, int x, int y) {
        super(name, description, category, x, y);
    }

    /** Lines to show; return an empty list to hide the element. */
    protected abstract List<String> lines(boolean preview);

    // ---- optional hooks ------------------------------------------------------------------------------

    /** Styled lines shown instead of {@link #lines} when non-null (rebuilt once per tick); children's colours win over Text Color. */
    protected List<Text> styledLines(boolean preview) { return null; }

    /** Colour of line {@code index} this frame ({@code base} is Text Color); called every frame, Chroma still wins. */
    protected int lineColor(int index, int base) { return base; }

    /** Extra height in pixels reserved under the text for {@link #drawExtra}. */
    protected int extraHeight() { return 0; }

    /** Minimum element width in pixels (e.g. so a bar drawn by {@link #drawExtra} has room). */
    protected int minWidth() { return 0; }

    /** Draws under the text every frame, after the background: element left, top of the extra area, element width. */
    protected void drawExtra(DrawContext ctx, int left, int y, int width) {}

    /** Width in pixels reserved left of the text for {@link #drawLeading} (e.g. an item icon). */
    protected int leadingWidth() { return 0; }

    /** Draws the leading area every frame, after the background: its top-left corner and available height. */
    protected void drawLeading(DrawContext ctx, int x, int y, int h) {}

    // Text, widths and size are rebuilt once per client tick (20/s) instead of every frame.
    private List<Text> cachedLines;
    private int[] cachedWidths;
    private int cachedLead;
    private long cachedTick = -1;
    /** Test hook: rebuild text every frame like before (to measure what the per-tick cache saves). */
    public static boolean NO_CACHE = false;

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        long tick = net.evadeclient.EvadeClient.ticks;
        if (preview || NO_CACHE || cachedLines == null || cachedTick != tick) {
            boolean smooth = smoothFont.get(), br = brackets.get();
            List<Text> styled = styledLines(preview);
            List<Text> lines;
            if (styled != null) {
                lines = new java.util.ArrayList<>(styled.size());
                for (Text s : styled) {
                    // Children that only set a colour inherit the parent's font.
                    MutableText t = Text.empty();
                    if (smooth) t.setStyle(UI_FONT);
                    if (br) t.append(Text.literal("["));
                    t.append(s);
                    if (br) t.append(Text.literal("]"));
                    lines.add(t);
                }
            } else {
                List<String> raw = lines(preview);
                lines = new java.util.ArrayList<>(raw.size());
                for (String r : raw) {
                    var t = Text.literal(br ? "[" + r + "]" : r);
                    if (smooth) t.setStyle(UI_FONT);
                    lines.add(t);
                }
            }
            int[] widths = new int[lines.size()];
            int w = 0;
            for (int i = 0; i < lines.size(); i++) {
                widths[i] = tr.getWidth(lines.get(i));
                w = Math.max(w, widths[i]);
            }
            int lead = lines.isEmpty() ? 0 : Math.max(0, leadingWidth());
            width = lines.isEmpty() ? 40 : Math.max(Math.max(40, w + 8 + lead), minWidth());
            height = lines.isEmpty() ? 13 : lines.size() * 10 + 4 + Math.max(0, extraHeight());
            if (preview) { draw(ctx, left, top, lines, widths, lead); return; }
            cachedLines = lines; cachedWidths = widths; cachedLead = lead; cachedTick = tick;
        }
        draw(ctx, left, top, cachedLines, cachedWidths, cachedLead);
    }

    private void draw(DrawContext ctx, int left, int top, List<Text> lines, int[] widths, int lead) {
        if (lines.isEmpty()) return;
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        drawLeading(ctx, left + 3, top + 2, height - 4);
        int y = top + 3;
        int c = color.get();
        boolean sh = shadow.get(), rainbow = chroma.get();
        for (int i = 0; i < lines.size(); i++) {
            int x = left + lead + (width - lead - widths[i]) / 2;
            if (rainbow) drawChroma(ctx, tr, lines.get(i), x, y, sh);
            else ctx.drawText(tr, lines.get(i), x, y, lineColor(i, c), sh);
            y += 10;
        }
        drawExtra(ctx, left, top + lines.size() * 10 + 3, width);
    }

    /** Rainbow text: each character's hue drifts with time and its position, so the colour flows across. */
    protected void drawChroma(DrawContext ctx, net.minecraft.client.font.TextRenderer tr, Text line, int x, int y, boolean sh) {
        String str = line.getString();
        var style = line.getStyle();
        double t = (System.currentTimeMillis() % 4000L) / 4000.0;
        for (int i = 0; i < str.length(); i++) {
            var ch = Text.literal(String.valueOf(str.charAt(i))).setStyle(style);
            float hue = (float) ((t - (x + i * 6) / 400.0) % 1.0 + 1.0) % 1.0f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 1f);
            ctx.drawText(tr, ch, x, y, 0xFF000000 | rgb, sh);
            x += tr.getWidth(ch);
        }
    }
}
