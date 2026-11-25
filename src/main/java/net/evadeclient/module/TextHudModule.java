package net.evadeclient.module;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
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
    public final ModeSetting textAlign = add(new ModeSetting("Text Align", "Auto", "Auto", "Left", "Center", "Right")
            .describe("Auto lines text up with the screen edge the widget is anchored to, so changing numbers don't jitter."));
    public final BooleanSetting splitColors = add(new BooleanSetting("Label/Value Colors", false)
            .describe("In lines like \"Ping: 42 ms\", draw the label in Text Color and the value in Value Color."));
    public final ColorSetting valueColor = add(new ColorSetting("Value Color", 0xFFFFD21F)
            .describe("Colour of the value after the label, with Label/Value Colors on."));

    protected TextHudModule(String name, String description, int x, int y) {
        super(name, description, x, y);
        color.chroma(chroma);
    }

    protected TextHudModule(String name, String description, Category category, int x, int y) {
        super(name, description, category, x, y);
        color.chroma(chroma);
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

    /** Minimum element height in pixels (e.g. room for an item icon); the text is centred vertically in it. */
    protected int minHeight() { return 0; }

    /** Test hook: the plain lines this element shows right now (not preview). */
    public List<String> textForTest() { return lines(false); }

    /** The alignment in effect: Text Align, or for Auto the edge the element is anchored to. */
    private int align() {
        return switch (textAlign.get()) {
            case "Left" -> -1;
            case "Center" -> 0;
            case "Right" -> 1;
            default -> x == CENTER ? 0 : x < 0 ? 1 : -1;
        };
    }

    // The text is read once per client tick (20/s) and the styled lines and their widths are rebuilt only when it
    // changed (most widgets show the same text for many ticks), or at once when the smooth font's raster changes (GUI
    // scale, HUD scale or element scale), since glyph advances differ per raster. Nothing is allocated otherwise.
    private List<Text> cachedLines;
    private int[] cachedWidths;
    private int cachedLead;
    private long cachedTick = -1;
    /** Raster the cached lines were built with: 0 = the vanilla font, 1..6 = the smooth font's raster. */
    private int cachedFont = -1;
    /** What the cached lines were built from (a copy of this tick's text) and with which text settings. */
    private List<?> cachedSource;
    private int cachedSettings;
    /** This tick's text: styled lines, or plain ones when the widget has none (read at most once a tick). */
    private List<Text> tickStyled;
    private List<String> tickRaw;
    private long tickAt = -1;
    /** Test hook: rebuild text every frame like before (to measure what the per-tick cache saves). */
    public static boolean NO_CACHE = false;

    private void readText() {
        long tick = net.evadeclient.EvadeClient.ticks;
        if (tickAt == tick && !NO_CACHE) return;
        tickAt = tick;
        tickStyled = styledLines(false);
        tickRaw = tickStyled == null ? lines(false) : null;
    }

    /**
     * This tick's text (styled or plain lines), read at most once a tick and shared with drawing: what the HUD cache
     * compares to notice a change.
     */
    public List<?> contentKey() {
        readText();
        return tickStyled != null ? tickStyled : tickRaw;
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        long tick = net.evadeclient.EvadeClient.ticks;
        int fontKey = smoothFont.get() ? smoothRaster(ctx) : 0;
        if (preview) {
            List<Text> styled = styledLines(true);
            List<Text> lines = build(styled, styled == null ? lines(true) : null, fontKey);
            int[] widths = widths(lines);
            int lead = measure(lines, widths);
            draw(ctx, left, top, lines, widths, lead);
            return;
        }
        if (NO_CACHE || cachedLines == null || cachedTick != tick || cachedFont != fontKey) {
            readText();
            List<?> source = tickStyled != null ? tickStyled : tickRaw;
            int settings = (brackets.get() ? 1 : 0) | (splitColors.get() ? 2 : 0) | (valueColor.get() & 0xFFFFFF) << 2;
            if (NO_CACHE || cachedLines == null || cachedFont != fontKey || settings != cachedSettings
                    || source == null || !source.equals(cachedSource)) {
                cachedLines = build(tickStyled, tickRaw, fontKey);
                cachedWidths = widths(cachedLines);
                cachedSource = source == null ? null : List.copyOf(source);
                cachedSettings = settings;
                cachedFont = fontKey;
            }
            cachedLead = measure(cachedLines, cachedWidths);
            cachedTick = tick;
        }
        draw(ctx, left, top, cachedLines, cachedWidths, cachedLead);
    }

    /** The lines as drawn: Brackets, Label/Value Colors and the smooth font applied to {@code styled} or {@code raw}. */
    private List<Text> build(List<Text> styled, List<String> raw, int fontKey) {
        boolean smooth = fontKey > 0, br = brackets.get();
        net.minecraft.text.Style font = smooth ? UiFont.style(UiFont.Role.BODY, fontKey) : null;
        List<Text> lines;
        if (styled != null) {
            lines = new java.util.ArrayList<>(styled.size());
            for (Text s : styled) {
                // Children that only set a colour inherit the parent's font.
                MutableText t = Text.empty();
                if (smooth) t.setStyle(font);
                if (br) t.append(Text.literal("["));
                t.append(s);
                if (br) t.append(Text.literal("]"));
                lines.add(t);
            }
        } else {
            if (raw == null) raw = List.of();
            lines = new java.util.ArrayList<>(raw.size());
            boolean split = splitColors.get();
            int vc = valueColor.get() & 0xFFFFFF;
            for (String r : raw) {
                int at = split ? labelEnd(r) : -1;
                MutableText t;
                if (at > 0) {
                    // "Label: " in the line's colour, the value in Value Color.
                    t = Text.literal(br ? "[" + r.substring(0, at) : r.substring(0, at));
                    t.append(Text.literal(r.substring(at)).withColor(vc));
                    if (br) t.append(Text.literal("]"));
                } else {
                    t = Text.literal(br ? "[" + r + "]" : r);
                }
                if (smooth) t.setStyle(font);
                lines.add(t);
            }
        }
        return lines;
    }

    private int[] widths(List<Text> lines) {
        var tr = mc.textRenderer;
        int[] widths = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) widths[i] = tr.getWidth(lines.get(i));
        return widths;
    }

    /** Sets {@link #width} and {@link #height} for these lines (the hooks may change per tick); returns the leading width. */
    private int measure(List<Text> lines, int[] widths) {
        int w = 0;
        for (int v : widths) w = Math.max(w, v);
        int lead = lines.isEmpty() ? 0 : Math.max(0, leadingWidth());
        width = lines.isEmpty() ? 40 : Math.max(Math.max(40, w + 8 + lead), minWidth());
        height = lines.isEmpty() ? 13 : Math.max(lines.size() * 10 + 4 + Math.max(0, extraHeight()), minHeight());
        return lead;
    }

    /**
     * Where the value starts in a "Label: value" line (just after ": "), or -1 when the line has no label: no ": ",
     * or the part before it is long or holds digits (then it is a value itself, like a time "12:30").
     */
    public static int labelEnd(String line) {
        int at = line.indexOf(": ");
        if (at <= 0 || at > 24) return -1;
        for (int i = 0; i < at; i++) if (Character.isDigit(line.charAt(i))) return -1;
        return at + 2;
    }

    private void draw(DrawContext ctx, int left, int top, List<Text> lines, int[] widths, int lead) {
        if (lines.isEmpty()) { empty = true; return; }
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        drawLeading(ctx, left + 3, top + 2, height - 4);
        int extra = Math.max(0, extraHeight());
        int textH = lines.size() * 10 + 4 + extra;
        int y = top + 3 + Math.max(0, (height - textH) / 2);
        int c = color.get();
        int align = align();
        boolean sh = shadow.get(), rainbow = chroma.get();
        for (int i = 0; i < lines.size(); i++) {
            int x = align < 0 ? left + lead + 4
                    : align > 0 ? left + width - 4 - widths[i]
                    : left + lead + (width - lead - widths[i]) / 2;
            if (rainbow) drawChroma(ctx, tr, lines.get(i), x, y, sh);
            else ctx.drawText(tr, lines.get(i), x, y, lineColor(i, c), sh);
            y += 10;
        }
        drawExtra(ctx, left, y, width);
    }

    /**
     * Rainbow text: each character's hue drifts with time and its position, so the colour flows across. Letters
     * advance by their exact (fractional) widths, so Smooth Font chroma text keeps the spacing and width of the
     * plain line it stands in for instead of spreading by up to a pixel per letter.
     */
    protected void drawChroma(DrawContext ctx, net.minecraft.client.font.TextRenderer tr, Text line, int x, int y, boolean sh) {
        String str = line.getString();
        var style = line.getStyle();
        var handler = tr.getTextHandler();
        var m = ctx.getMatrices();
        double t = (System.currentTimeMillis() % 4000L) / 4000.0;
        float pen = 0;
        for (int i = 0, n = 0; i < str.length(); n++) {
            int cp = str.codePointAt(i);
            i += Character.charCount(cp);
            var ch = Text.literal(new String(Character.toChars(cp))).setStyle(style);
            float hue = (float) ((t - (x + pen + n * 6) / 400.0) % 1.0 + 1.0) % 1.0f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 1f);
            m.pushMatrix();
            try {
                m.translate(pen, 0);
                ctx.drawText(tr, ch, x, y, 0xFF000000 | rgb, sh);
            } finally {
                m.popMatrix();
            }
            pen += handler.getWidth(ch);
        }
    }
}
