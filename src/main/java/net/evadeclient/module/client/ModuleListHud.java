package net.evadeclient.module.client;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.Motion;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The list of mods you have on, with an accent bar on the screen-edge side. Top-right under the watermark by
 * default; drag it anywhere in the HUD editor. Rows line up with the edge they are aligned to, a list anchored to
 * the bottom grows upward, and mods that turn on or off slide (or fade) in and out.
 */
public class ModuleListHud extends HudElementModule {
    /** Same spot as before it became a movable element: flush with the right edge, under the watermark. */
    public static final int DEFAULT_X = -1, DEFAULT_Y = 17;
    /** Top of the list at its default spot when the watermark is off or moved away. */
    private static final int TOP_WITHOUT_WATERMARK = 1;
    /** Space between the text and a row's plain side, and between the text and a side with a bar. */
    private static final int PAD_TEXT = 3, PAD_BAR = 4;
    /** Seconds a row takes to open and slide in (or slide out and close) at Animation Speed 1. */
    private static final float ANIM_SECONDS = 0.32f;
    /** Share of a row's animation spent opening (or closing) its line; the rest is the slide or fade. */
    private static final float OPEN_PART = 0.35f;
    /** How quickly rows glide to a new place after a re-sort (per second, at Animation Speed 1). */
    private static final float GLIDE_RATE = 22f;
    /** A pause longer than this (list hidden, loading screen) skips animations: rows just appear as they are. */
    private static final long STALE_NANOS = 500_000_000L;

    // ---- colours ---------------------------------------------------------------------------------------

    /** Saved "Red Wave" / "Static Red" from before the modes followed the accent are mapped by ConfigManager. */
    public final ModeSetting colorMode = add(new ModeSetting("List Color", "Accent Wave",
            "Accent Wave", "Accent", "Rainbow", "Gradient", "Custom", "Category", "White")
            .describe("Accent Wave and Gradient pulse down the list; Category colours each mod by its tab."));
    public final ColorSetting color1 = add(new ColorSetting("Color 1", 0xFFE11D2E)
            .describe("The Custom colour, and where Gradient starts."));
    public final ColorSetting color2 = add(new ColorSetting("Color 2", 0xFF29D3F0)
            .describe("Where Gradient ends."));
    public final NumberSetting waveSpeed = add(new NumberSetting("Wave Speed", 1.0, 0.2, 3.0, 0.1)
            .describe("How fast Accent Wave, Gradient and Rainbow move."));
    public final NumberSetting waveSpread = add(new NumberSetting("Wave Spread", 0.45, 0.05, 1.5, 0.05)
            .describe("How far the colour moves on from one line to the next."));
    public final ColorSetting suffixColor = add(new ColorSetting("Suffix Color", 0xFFB8B8C0)
            .describe("Colour of the mode shown after a mod's name."));

    // ---- layout and line style -------------------------------------------------------------------------

    public final ModeSetting sort = add(new ModeSetting("Sort", "Length", "Length", "Alphabetical", "Category")
            .describe("Length stacks the longest names against the edge; Category groups mods by tab."));
    public final NumberSetting lineSpacing = add(new NumberSetting("Line Spacing", 1, 0, 4, 1)
            .describe("Extra space between lines, in pixels."));
    public final ModeSetting align = add(new ModeSetting("Align", "Auto", "Auto", "Left", "Right"));
    public final ModeSetting sidebar = add(new ModeSetting("Sidebar", "Outer", "Outer", "Inner", "Outline", "None")
            .describe("A coloured bar on the edge side, on the text side, an outline around the list, or none."));
    public final ModeSetting textCase = add(new ModeSetting("Text Case", "Normal", "Normal", "lowercase", "UPPERCASE"));
    public final ModeSetting suffixStyle = add(new ModeSetting("Suffix Style", "Plain", "Plain", "Brackets", "Dash", "Hidden")
            .describe("How a mod's mode is shown after its name: Mod Mode, Mod [Mode], Mod - Mode, or not at all."));
    public final BooleanSetting textShadow = add(new BooleanSetting("Text Shadow", true));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", false));

    // ---- animation -------------------------------------------------------------------------------------

    public final ModeSetting animation = add(new ModeSetting("Animation", "Slide", "Slide", "Fade", "Off")
            .describe("How mods enter and leave the list. Off when Animations is off in Client Settings."));
    public final NumberSetting animationSpeed = add(new NumberSetting("Animation Speed", 1.0, 0.25, 3.0, 0.25));

    // ---- filtering -------------------------------------------------------------------------------------

    public final BooleanSetting listVisuals = add(new BooleanSetting("List Visuals", true)
            .describe("Show mods from the Visuals tab."));
    public final BooleanSetting listControls = add(new BooleanSetting("List Controls", true)
            .describe("Show mods from the Controls tab."));
    public final BooleanSetting listMisc = add(new BooleanSetting("List Misc", true)
            .describe("Show mods from the Misc tab."));
    public final BooleanSetting listServer = add(new BooleanSetting("List Server-facing", true)
            .describe("Show mods from the Server-facing tab."));
    public final StringSetting hiddenModules = add(new StringSetting("Hidden Modules", "", "names, comma-separated")
            .maxLength(160)
            .describe("Mods never shown in the list, separated by commas (e.g. Zoom, Fullbright)."));

    private static final String[][] SAMPLE = {{"Toggle Sprint", "Toggled"}, {"Performance", null}, {"Fullbright", null}, {"Zoom", null}};
    private static final Map<Category, Integer> CATEGORY_COLORS = categoryColors();

    /** One line of the list: its label (kept while the mod animates out) and where it is in its animation. */
    private static final class Row {
        final Module module;
        Text name, suffix;
        int nameW, textW, category;
        String sortName = "";
        /** In the list once animations finish (the mod is on and listed). */
        boolean active;
        /** Animation progress: 0 = gone, 1 = fully shown. */
        float vis;
        /** Distance of the line from the anchored edge, in pixels (glides after a re-sort); NaN until placed. */
        float pos = Float.NaN;

        Row(Module module) { this.module = module; }
    }

    private final Map<Module, Row> rows = new IdentityHashMap<>();
    /** Every row being drawn (listed and leaving) in sort order, rebuilt once per client tick. */
    private final List<Row> sorted = new ArrayList<>();
    private List<Row> sample = List.of();
    private int activeCount, activeTextW;
    private long builtTick = -1, sampleTick = -1;
    private int builtFont = -1;
    private String hiddenRaw;
    private Set<String> hiddenKeys = Set.of();
    private long lastNanos;
    /** Seconds of colour-wave time; advanced by Wave Speed each frame so changing the speed never jumps. */
    private double waveClock;
    private final Hud.ToastDodge dodge = new Hud.ToastDodge();

    // Per-frame scratch for the outline pass: the drawn lines' boxes, from the anchored edge outward.
    private int[] bx0 = new int[16], bx1 = new int[16], by0 = new int[16], bColor = new int[16];
    private int boxCount;

    public ModuleListHud() {
        super("Module List", "The mods you have on, listed in the corner of the screen.", DEFAULT_X, DEFAULT_Y);
        setEnabled(true);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        TextRenderer tr = mc.textRenderer;
        long now = System.nanoTime();
        boolean stale = lastNanos == 0 || now - lastNanos > STALE_NANOS;
        float dt = stale ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        waveClock += dt * waveSpeed.get();

        boolean animate = !preview && animating();
        int font = smoothFont.get() ? Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, Math.round(Theme.deviceScale(ctx)))) : 0;
        if (builtTick != EvadeClient.ticks || builtFont != font || net.evadeclient.module.TextHudModule.NO_CACHE) {
            rebuild(tr, font, animate && !stale);
        }
        if (!preview) advance(dt, animate && !stale);

        int spacing = lineSpacing.getInt();
        int rowH = 9 + spacing, textOff = 1 + spacing / 2;
        String side = sidebar.get();
        boolean outlineBar = side.equals("Outline");
        boolean bgOutline = background.get() && Theme.hudStyle.equals("Outline");
        int edgePad = side.equals("Outer") || outlineBar ? PAD_BAR : PAD_TEXT;
        int innerPad = side.equals("Inner") || outlineBar ? PAD_BAR : PAD_TEXT;
        int closing = outlineBar || bgOutline ? 1 : 0;

        // Size from the finished layout, so the element doesn't jitter while lines animate.
        boolean showSample = preview && activeCount == 0;
        if (showSample && sampleTick != builtTick) buildSample(tr, font);
        List<Row> list = showSample ? sample : sorted;
        int count = showSample ? sample.size() : activeCount;
        int textW = showSample ? sampleTextW() : activeTextW;
        if (count == 0) {
            width = 40;
            height = rowH;
            if (sorted.isEmpty()) return;
        } else {
            width = textW + edgePad + innerPad;
            height = count * rowH + closing;
        }

        boolean right = alignRight();
        boolean upward = y < 0; // anchored to the bottom: the list grows upward from there
        // Rows in order from the anchored edge outward: Length keeps the longest line against the edge.
        boolean reverse = upward && !sort.is("Length");
        int n = list.size();
        if (bx0.length < n) {
            int cap = Math.max(n, bx0.length * 2);
            bx0 = new int[cap]; bx1 = new int[cap]; by0 = new int[cap]; bColor = new int[cap];
        }
        boxCount = 0;

        int edge = right ? left + width : left;
        int bottom = top + height;
        boolean slide = animate && animation.is("Slide"), fade = animate && animation.is("Fade");
        int fill = rowFill();
        int outlineColor = bgOutline && !outlineBar ? Theme.alpha(Theme.RED | 0xFF000000, 0.85f * Theme.hudOpacity) : 0;
        boolean shadow = textShadow.get();
        int suffixC = suffixColor.get() | 0xFF000000;

        float placed = 0;
        for (int k = 0; k < n; k++) {
            Row r = list.get(reverse ? n - 1 - k : k);
            float pos, show;
            if (preview) {
                if (!showSample && !r.active) continue;
                pos = placed;
                placed += rowH;
                show = 1f;
            } else {
                pos = Float.isNaN(r.pos) ? 0 : r.pos;
                show = showOf(r.vis);
            }
            if (show <= 0f) continue;

            int rowW = r.textW + edgePad + innerPad;
            int ry = Math.round(pos);
            int y0 = upward ? bottom - ry - rowH : top + ry;
            int restX0 = right ? edge - rowW : edge, restX1 = restX0 + rowW;
            int dx = slide && show < 1f ? Math.round((1f - show) * (rowW + 2)) : 0;
            int x0 = right ? restX0 + dx : restX0 - dx, x1 = x0 + rowW;
            float a = fade ? show : 1f;
            int color = Theme.alpha(color(r, pos / rowH) | 0xFF000000, a);

            boolean clip = dx != 0;
            if (clip) ctx.enableScissor(restX0, y0, restX1, y0 + rowH);
            try {
                if (fill != 0) Theme.fill(ctx, x0, y0, x1, y0 + rowH, Theme.alpha(fill, a));
                if (outlineColor != 0) rowOutline(ctx, x0, x1, y0, rowH, Theme.alpha(outlineColor, a));
                switch (side) {
                    case "Outer" -> { if (right) Theme.fill(ctx, x1 - 1, y0, x1, y0 + rowH, color); else Theme.fill(ctx, x0, y0, x0 + 1, y0 + rowH, color); }
                    case "Inner" -> { if (right) Theme.fill(ctx, x0, y0, x0 + 1, y0 + rowH, color); else Theme.fill(ctx, x1 - 1, y0, x1, y0 + rowH, color); }
                    case "Outline" -> rowOutline(ctx, x0, x1, y0, rowH, color);
                    default -> {}
                }
                int tx = right ? x0 + innerPad : x0 + edgePad, ty = y0 + textOff;
                ctx.drawText(mc.textRenderer, r.name, tx, ty, color, shadow);
                if (r.suffix != null) ctx.drawText(mc.textRenderer, r.suffix, tx + r.nameW, ty, Theme.alpha(suffixC, a), shadow);
            } finally {
                if (clip) ctx.disableScissor();
            }

            // Kept for the outline's connectors, clamped to the line's resting box like the slide is clipped.
            int i = boxCount++;
            bx0[i] = Math.max(restX0, x0);
            bx1[i] = Math.min(restX1, x1);
            by0[i] = y0;
            bColor[i] = outlineBar ? color : outlineColor;
        }
        if ((outlineBar || outlineColor != 0) && boxCount > 0) outlineConnectors(ctx, right, upward, rowH);
    }

    @Override
    public void renderScaled(DrawContext ctx, int screenW, int screenH, boolean preview) {
        int dy = 0;
        if (!preview) {
            WatermarkHud wm = stackedUnder();
            dy = wm != null ? wm.toastOffset(screenW, screenH) : dodge.update(this, screenW, screenH);
        }
        if (dy == 0) { super.renderScaled(ctx, screenW, screenH, preview); return; }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(0, dy);
        try {
            super.renderScaled(ctx, screenW, screenH, preview);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** At its default spot the list follows the watermark: right under it, or at the top when it is off. */
    @Override
    public int resolveY(int screenH) {
        if (x != DEFAULT_X || y != DEFAULT_Y) return super.resolveY(screenH);
        WatermarkHud wm = stackedUnder();
        return wm != null ? wm.stackY(screenH) : TOP_WITHOUT_WATERMARK;
    }

    /** The watermark this list is stacked under, or null when either has been moved or the watermark is off. */
    private WatermarkHud stackedUnder() {
        if (x != DEFAULT_X || y != DEFAULT_Y || EvadeClient.modules == null) return null;
        WatermarkHud wm = EvadeClient.modules.get(WatermarkHud.class);
        return wm != null && wm.isEnabled() && wm.atDefaultSpot() ? wm : null;
    }

    /** Auto lines rows up with the edge the list is anchored to (right when it sits on the right half). */
    private boolean alignRight() {
        return switch (align.get()) {
            case "Left" -> false;
            case "Right" -> true;
            default -> x < 0;
        };
    }

    /** Whether lines animate: the element's Animation, unless Animations is off in Client Settings. */
    private boolean animating() {
        if (animation.is("Off")) return false;
        var cs = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        return cs == null || cs.animations.get();
    }

    /**
     * Test hook: the lines the list settles on with the current mods and settings, in display order from the top
     * for a top-anchored list (name and suffix as drawn). Builds its own labels; the drawn list is untouched.
     */
    public List<String> linesForTest() {
        parseHidden();
        List<Row> out = new ArrayList<>();
        for (Module m : EvadeClient.modules.all()) {
            if (!listed(m)) continue;
            Row r = new Row(m);
            label(r, mc.textRenderer, null, m.getName(), m.getSuffix());
            r.category = m.getCategory().ordinal();
            out.add(r);
        }
        out.sort(comparator());
        List<String> lines = new ArrayList<>(out.size());
        for (Row r : out) lines.add(r.name.getString() + (r.suffix == null ? "" : r.suffix.getString()));
        return lines;
    }

    // ---- rows (rebuilt once per tick) ------------------------------------------------------------------

    /**
     * Rebuilds labels, filters and the sort order. A mod that turns on starts hidden when {@code animate} (and
     * opens over the next frames); one that turns off keeps its label until it has animated out.
     */
    private void rebuild(TextRenderer tr, int font, boolean animate) {
        builtTick = EvadeClient.ticks;
        builtFont = font;
        Style style = font > 0 ? UiFont.style(UiFont.Role.BODY, font) : null;
        parseHidden();

        for (Row r : rows.values()) r.active = false;
        for (Module m : EvadeClient.modules.all()) {
            if (!listed(m)) continue;
            Row r = rows.get(m);
            if (r == null) {
                r = new Row(m);
                r.vis = animate ? 0f : 1f;
                rows.put(m, r);
            }
            label(r, tr, style, m.getName(), m.getSuffix());
            r.category = m.getCategory().ordinal();
            r.active = true;
        }
        if (!animate) rows.values().removeIf(r -> !r.active);

        sorted.clear();
        sorted.addAll(rows.values());
        sorted.sort(comparator());
        activeCount = 0;
        activeTextW = 0;
        for (Row r : sorted) {
            if (!r.active) continue;
            activeCount++;
            activeTextW = Math.max(activeTextW, r.textW);
        }
    }

    /** Sample lines for the HUD editor while no mod is listed, built with the current settings. */
    private void buildSample(TextRenderer tr, int font) {
        sampleTick = builtTick;
        Style style = font > 0 ? UiFont.style(UiFont.Role.BODY, font) : null;
        List<Row> s = new ArrayList<>(SAMPLE.length);
        for (String[] e : SAMPLE) {
            Row r = new Row(null);
            label(r, tr, style, e[0], e[1]);
            r.category = Category.MODS.ordinal();
            r.active = true;
            r.vis = 1f;
            s.add(r);
        }
        s.sort(comparator());
        sample = s;
    }

    private int sampleTextW() {
        int w = 0;
        for (Row r : sample) w = Math.max(w, r.textW);
        return w;
    }

    private void label(Row r, TextRenderer tr, Style style, String name, String suffix) {
        String shownName = applyCase(name);
        r.name = styled(shownName, style);
        r.nameW = tr.getWidth(r.name);
        String sfx = suffixText(suffix);
        r.suffix = sfx == null ? null : styled(sfx, style);
        r.textW = r.nameW + (r.suffix == null ? 0 : tr.getWidth(r.suffix));
        r.sortName = shownName.toLowerCase(Locale.ROOT);
    }

    private static MutableText styled(String s, Style style) {
        MutableText t = Text.literal(s);
        return style == null ? t : t.setStyle(style);
    }

    private String applyCase(String s) {
        return switch (textCase.get()) {
            case "lowercase" -> s.toLowerCase(Locale.ROOT);
            case "UPPERCASE" -> s.toUpperCase(Locale.ROOT);
            default -> s;
        };
    }

    /** The suffix as drawn after the name (with its leading space), or null when there is none to show. */
    private String suffixText(String suffix) {
        if (suffix == null || suffix.isBlank()) return null;
        String s = applyCase(suffix.strip());
        return switch (suffixStyle.get()) {
            case "Hidden" -> null;
            case "Brackets" -> " [" + s + "]";
            case "Dash" -> " - " + s;
            default -> " " + s;
        };
    }

    private Comparator<Row> comparator() {
        Comparator<Row> byLength = Comparator.comparingInt((Row r) -> -r.textW).thenComparing(r -> r.sortName);
        return switch (sort.get()) {
            case "Alphabetical" -> Comparator.comparing((Row r) -> r.sortName);
            case "Category" -> Comparator.comparingInt((Row r) -> r.category).thenComparing(byLength);
            default -> byLength;
        };
    }

    private boolean listed(Module m) {
        if (!m.isEnabled() || !m.isVisible()) return false;
        boolean tab = switch (m.getCategory()) {
            case VISUALS -> listVisuals.get();
            case MODS -> listControls.get();
            case MISC -> listMisc.get();
            case SERVER -> listServer.get();
            default -> true;
        };
        return tab && (hiddenKeys.isEmpty() || !hiddenKeys.contains(key(m.getName())));
    }

    /** Re-reads Hidden Modules only when its text has changed. */
    private void parseHidden() {
        String raw = hiddenModules.get();
        if (raw == null) raw = "";
        if (raw.equals(hiddenRaw)) return;
        hiddenRaw = raw;
        Set<String> keys = new HashSet<>();
        for (String part : raw.split(",")) {
            String k = key(part);
            if (!k.isEmpty()) keys.add(k);
        }
        hiddenKeys = keys;
    }

    /** A name as Hidden Modules matches it: lower case, without spaces. */
    private static String key(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    // ---- animation (per frame) -------------------------------------------------------------------------

    /**
     * Moves every row's progress toward shown or gone and lays the lines out from the anchored edge. A closing
     * line still takes up its shrinking share of the height, so the lines after it close the gap smoothly.
     */
    private void advance(float dt, boolean animate) {
        float step = animate ? dt * animationSpeed.getFloat() / ANIM_SECONDS : 1f;
        boolean gone = false;
        for (Row r : sorted) {
            float target = r.active ? 1f : 0f;
            if (!animate) r.vis = target;
            else if (r.vis < target) r.vis = Math.min(target, r.vis + step);
            else if (r.vis > target) r.vis = Math.max(target, r.vis - step);
            if (!r.active && r.vis <= 0f) gone = true;
        }
        if (gone) {
            sorted.removeIf(r -> !r.active && r.vis <= 0f);
            rows.values().removeIf(r -> !r.active && r.vis <= 0f);
        }

        int rowH = 9 + lineSpacing.getInt();
        boolean reverse = y < 0 && !sort.is("Length");
        float glide = animate ? 1f - (float) Math.exp(-dt * GLIDE_RATE * animationSpeed.getFloat()) : 1f;
        float acc = 0;
        int n = sorted.size();
        for (int k = 0; k < n; k++) {
            Row r = sorted.get(reverse ? n - 1 - k : k);
            float target = acc;
            acc += rowH * openOf(r.vis);
            if (Float.isNaN(r.pos) || glide >= 1f) r.pos = target;
            else {
                r.pos += (target - r.pos) * glide;
                if (Math.abs(target - r.pos) < 0.05f) r.pos = target;
            }
        }
    }

    /** How open a line's height is: the first part of its progress. */
    private static float openOf(float vis) {
        float t = Motion.clamp01(vis / OPEN_PART);
        return t * t * (3f - 2f * t);
    }

    /** How far a line has slid or faded in: the rest of its progress (eases out entering, eases in leaving). */
    private static float showOf(float vis) {
        return Motion.cubicOut((vis - OPEN_PART) / (1f - OPEN_PART));
    }

    // ---- drawing helpers -------------------------------------------------------------------------------

    /** The 1 px outline pieces of one line: its edge-side bar and its inner side. Connectors are drawn after. */
    private static void rowOutline(DrawContext ctx, int x0, int x1, int y0, int rowH, int color) {
        Theme.fill(ctx, x1 - 1, y0, x1, y0 + rowH, color);
        Theme.fill(ctx, x0, y0, x0 + 1, y0 + rowH, color);
    }

    /**
     * Joins the lines' inner sides into one staircase outline: a 1 px step between neighbouring lines, drawn on
     * the wider line's side, and a closing line across the line farthest from the anchored edge.
     */
    private void outlineConnectors(DrawContext ctx, boolean right, boolean upward, int rowH) {
        for (int i = 0; i + 1 < boxCount; i++) {
            // Boxes were recorded from the anchor outward; for upward lists that is bottom to top.
            int a = upward ? i + 1 : i, b = upward ? i : i + 1; // a above b
            int ay1 = by0[a] + rowH;
            if (by0[b] != ay1) continue; // not touching (a line between them is still opening or closing)
            if (right) {
                if (bx0[a] < bx0[b]) Theme.fill(ctx, bx0[a], ay1, bx0[b] + 1, ay1 + 1, bColor[a]);
                else if (bx0[b] < bx0[a]) Theme.fill(ctx, bx0[b], ay1 - 1, bx0[a] + 1, ay1, bColor[b]);
            } else {
                if (bx1[a] > bx1[b]) Theme.fill(ctx, bx1[b] - 1, ay1, bx1[a], ay1 + 1, bColor[a]);
                else if (bx1[b] > bx1[a]) Theme.fill(ctx, bx1[a] - 1, ay1 - 1, bx1[b], ay1, bColor[b]);
            }
        }
        int far = boxCount - 1;
        int cy = upward ? by0[far] - 1 : by0[far] + rowH;
        Theme.fill(ctx, bx0[far], cy, bx1[far], cy + 1, bColor[far]);
    }

    /** Line backing for the element's Background options; Evade style is kept dark enough to read over sky. */
    private int rowFill() {
        if (!background.get()) return 0;
        return switch (Theme.hudStyle) {
            case "Outline" -> 0;
            case "Solid", "Glass" -> Theme.hudFill();
            default -> Theme.alpha(Theme.HUD_BG | 0xFF000000, 0.82f * Theme.hudOpacity);
        };
    }

    /** The colour of a line {@code index} lines (fractional while gliding) from the anchored edge. */
    private int color(Row r, float index) {
        return switch (colorMode.get()) {
            case "Accent" -> Theme.RED;
            case "Rainbow" -> rainbow(index);
            case "Gradient" -> Theme.lerpColor(color1.get() | 0xFF000000, color2.get() | 0xFF000000, wave(index));
            case "Custom" -> color1.get();
            case "Category" -> CATEGORY_COLORS.getOrDefault(categoryOf(r), 0xFFFFFFFF);
            case "White" -> 0xFFFFFFFF;
            // The accent pulsing towards its bright tint (never towards black, so it stays readable).
            default -> Theme.lerpColor(Theme.RED | 0xFF000000, Theme.RED_BRIGHT | 0xFF000000, wave(index));
        };
    }

    /** 0..1 position in the colour wave for a line: moves with time and shifts by Wave Spread per line. */
    private float wave(float index) {
        return (float) ((Math.sin(waveClock * (1000.0 / 600.0) - index * waveSpread.get()) + 1.0) / 2.0);
    }

    /** A hue cycle every 4 s at Wave Speed 1, a third of a second further on per line at Wave Spread 1. */
    private int rainbow(float index) {
        double ms = waveClock * 1000.0 + index * waveSpread.get() * 333.0;
        float hue = (float) ((ms % 4000.0) / 4000.0);
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.6f, 1f) & 0xFFFFFF);
    }

    private static Category categoryOf(Row r) {
        Category[] all = Category.values();
        return r.category >= 0 && r.category < all.length ? all[r.category] : Category.MISC;
    }

    /** A distinct colour for every tab; any tab added later gets its own hue from its position. */
    private static Map<Category, Integer> categoryColors() {
        Map<Category, Integer> m = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            int color = switch (c.name()) {
                case "SERVER" -> 0xFFFF8A1F;
                case "VISUALS" -> 0xFFA95CFF;
                case "HUD" -> 0xFF29D3F0;
                case "MODS" -> 0xFF3BE37A;
                case "MISC" -> 0xFFFFD21F;
                case "CLIENT" -> 0xFFFF5CC8;
                default -> 0xFF000000 | (java.awt.Color.HSBtoRGB((c.ordinal() * 0.618034f) % 1f, 0.6f, 1f) & 0xFFFFFF);
            };
            m.put(c, color);
        }
        return m;
    }
}
