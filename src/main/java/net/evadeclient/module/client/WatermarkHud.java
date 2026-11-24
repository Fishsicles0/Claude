package net.evadeclient.module.client;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Logo;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.joml.Matrix3x2fStack;

import java.time.LocalTime;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Evade logo (or your own text), top-right by default, optionally with FPS, ping and the time beside it.
 * Drag it anywhere in the HUD editor; it steps aside for vanilla toasts.
 */
public class WatermarkHud extends HudElementModule {
    /** Same spot as before it became a movable element: 4 px from the right edge, 3 px from the top. */
    public static final int DEFAULT_X = -5, DEFAULT_Y = 3;
    /** Space around the content when the background is on (more at the sides of text). */
    private static final int PAD = 3, PAD_TEXT_X = 4;
    /** Watermark Size the other proportions are measured against (the logo's original height). */
    private static final float BASE_SIZE = 17f;
    /** The Text style's type size at Watermark Size 17, relative to the HUD font. */
    private static final float TEXT_SCALE_AT_BASE = 1.5f;
    /** At Watermark Size 17 the bare mark has this much transparent space under it. */
    private static final int MARK_BOTTOM_SPACE = 3;
    private static final String SEP = "  \u00B7  ";
    /** Middle of a capital letter below the top of a line of HUD text, in font pixels (both fonts). */
    private static final float CAP_CENTER = 3.75f;
    private static final int INFO_COLOR = 0xFFD6D6DE, SEP_COLOR = 0xFF8A8A96, DIVIDER = 0x50FFFFFF;
    /** The local short time pattern (h:mm a or HH:mm), with plain spaces (some locales use a narrow no-break space). */
    private static final DateTimeFormatter TIME = localTime();

    public final ModeSetting style = add(new ModeSetting("Watermark Style", "Logo", "Logo", "Text", "Logo + Info")
            .describe("The Evade logo, your own text, or the logo with FPS, ping or the time beside it."));
    public final StringSetting text = add(new StringSetting("Watermark Text", "Evade", "used by the Text style")
            .maxLength(24)
            .describe("What the Text style shows; its first letter takes the accent colour."));
    public final ModeSetting info = add(new ModeSetting("Watermark Info", "FPS + Ping", "None", "FPS", "FPS + Ping", "FPS + Ping + Time")
            .describe("What Logo + Info and Text show after the name."));
    public final NumberSetting size = add(new NumberSetting("Watermark Size", 17, 10, 32, 1)
            .describe("Height of the logo; the text and info scale with it."));

    private final Hud.ToastDodge dodge = new Hud.ToastDodge();

    // Text and widths are rebuilt once per client tick (and when the font's raster scale changes).
    private Text nameText, infoText;
    private int nameW, infoW;
    private long builtTick = -1;
    private int builtKey = Integer.MIN_VALUE;

    public WatermarkHud() {
        super("Watermark", "The Evade logo or your own text in the corner, with FPS and ping if you like.", DEFAULT_X, DEFAULT_Y);
        setEnabled(true);
    }

    /** The watermark never had a panel behind it, so its Background starts off where elements pick their default. */
    protected boolean defaultBackground() { return false; }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        boolean bg = background.get();
        int mark = size.getInt();
        float k = mark / BASE_SIZE;
        switch (style.get()) {
            case "Text" -> renderText(ctx, left, top, bg, k);
            case "Logo + Info" -> renderLogoInfo(ctx, left, top, bg, mark, k);
            default -> {
                int pad = bg ? PAD : 0;
                width = Logo.lockupWidth(mark) + pad * 2;
                height = mark + pad * 2;
                if (bg) Theme.hudBox(ctx, left, top, width, height);
                Logo.lockup(ctx, left + pad, top + pad, mark);
            }
        }
    }

    /** The lockup, a hairline divider, then the info line centred on the mark. */
    private void renderLogoInfo(DrawContext ctx, int left, int top, boolean bg, int mark, float k) {
        float infoScale = Math.max(0.75f, Math.min(1.75f, k));
        build(ctx, false, 0f, infoScale);
        int pad = bg ? PAD : 0;
        int lockW = Logo.lockupWidth(mark);
        int gap = Math.max(3, Math.round(mark / 4f));
        int infoPx = Math.round(infoW * infoScale);
        boolean hasInfo = infoText != null && infoW > 0;
        width = lockW + (hasInfo ? gap * 2 + 1 + infoPx : 0) + pad * 2;
        height = mark + pad * 2;
        if (bg) Theme.hudBox(ctx, left, top, width, height);
        int x = left + pad, y = top + pad;
        Logo.lockup(ctx, x, y, mark);
        if (!hasInfo) return;
        int dx = x + lockW + gap;
        int dh = Math.max(6, Math.round(mark * 0.56f));
        int dy = y + (mark - dh) / 2;
        Theme.fill(ctx, dx, dy, dx + 1, dy + dh, DIVIDER);
        // Capitals centred on the mark, level with the wordmark's letters.
        drawScaled(ctx, infoText, dx + 1 + gap, y + mark / 2f - CAP_CENTER * infoScale, infoScale, INFO_COLOR);
    }

    /** Your text in Inter SemiBold (first letter in the accent), then the info line on the same baseline. */
    private void renderText(DrawContext ctx, int left, int top, boolean bg, float k) {
        float nameScale = TEXT_SCALE_AT_BASE * k;
        float infoScale = Math.max(0.75f, nameScale * 0.62f);
        build(ctx, true, nameScale, infoScale);
        int padX = bg ? PAD_TEXT_X : 0, padY = bg ? PAD - 1 : 0;
        int namePx = Math.round(nameW * nameScale);
        boolean hasInfo = infoText != null && infoW > 0;
        int gap = Math.max(3, Math.round(4 * k));
        int infoPx = hasInfo ? Math.round(infoW * infoScale) : 0;
        int lineH = Math.round(9 * nameScale);
        width = namePx + (hasInfo ? gap + infoPx : 0) + padX * 2;
        height = lineH + padY * 2;
        if (bg) Theme.hudBox(ctx, left, top, width, height);
        float x = left + padX, y = top + padY;
        drawScaled(ctx, nameText, x, y, nameScale, 0xFFFFFFFF);
        // Same baseline: the fonts put it about 7/9 of the way down the line.
        if (hasInfo) drawScaled(ctx, infoText, x + namePx + gap, y + 7f * (nameScale - infoScale), infoScale, INFO_COLOR);
    }

    private void drawScaled(DrawContext ctx, Text t, float x, float y, float s, int color) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(Math.round(x), Math.round(y));
            m.scale(s, s);
            ctx.drawText(mc.textRenderer, t, 0, 0, color, true);
        } finally {
            m.popMatrix();
        }
    }

    /**
     * Builds the name and info Texts once per tick, in Inter rasterised for the size they appear at on screen
     * (so scaled text stays crisp). {@code nameScale} 0 skips the name (Logo + Info has no text name).
     */
    private void build(DrawContext ctx, boolean withName, float nameScale, float infoScale) {
        float device = Theme.deviceScale(ctx);
        int gName = withName ? raster(device * nameScale) : 0, gInfo = raster(device * infoScale);
        int key = (withName ? 1 : 0) | gName << 1 | gInfo << 4;
        if (builtTick == EvadeClient.ticks && builtKey == key && !net.evadeclient.module.TextHudModule.NO_CACHE) return;
        builtTick = EvadeClient.ticks;
        builtKey = key;
        TextRenderer tr = mc.textRenderer;

        if (withName) {
            String s = text.get() == null ? "" : text.get().strip();
            if (s.isEmpty()) s = "Evade";
            int split = s.offsetByCodePoints(0, 1);
            MutableText t = Text.empty().setStyle(UiFont.style(UiFont.Role.STRONG, gName));
            t.append(Text.literal(s.substring(0, split)).withColor(Logo.markTint() & 0xFFFFFF));
            t.append(Text.literal(s.substring(split)));
            nameText = t;
            nameW = tr.getWidth(t);
        } else {
            nameText = null;
            nameW = 0;
        }

        List<String> parts = infoParts();
        if (parts.isEmpty()) {
            infoText = null;
            infoW = 0;
        } else {
            var font = UiFont.style(UiFont.Role.BODY, gInfo);
            MutableText t = Text.empty().setStyle(font), widest = Text.empty().setStyle(font);
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    t.append(Text.literal(SEP).withColor(SEP_COLOR & 0xFFFFFF));
                    widest.append(SEP);
                }
                t.append(Text.literal(parts.get(i)));
                widest.append(parts.get(i).replaceAll("[0-9]", "0"));
            }
            infoText = t;
            // Measured with every digit as a 0, so a changing FPS or ping doesn't nudge the logo every tick.
            infoW = Math.max(tr.getWidth(t), tr.getWidth(widest));
        }
    }

    private static int raster(float devicePx) {
        return Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, Math.round(devicePx)));
    }

    /** FPS, ping and the time, as chosen in Watermark Info (empty for None). */
    private List<String> infoParts() {
        String mode = info.get();
        if (mode.equals("None")) return List.of();
        List<String> parts = new ArrayList<>(3);
        parts.add(mc.getCurrentFps() + " FPS");
        if (mode.contains("Ping")) parts.add(ping());
        if (mode.contains("Time")) parts.add(TIME.format(LocalTime.now()).replace('\u202F', ' ').replace('\u00A0', ' '));
        return parts;
    }

    /** Your latency from the player list, or SP in singleplayer (and before the server has sent it). */
    private String ping() {
        if (mc.isInSingleplayer() || mc.player == null) return "SP";
        var nh = mc.getNetworkHandler();
        var entry = nh == null ? null : nh.getPlayerListEntry(mc.player.getUuid());
        return entry == null ? "SP" : entry.getLatency() + " ms";
    }

    private static DateTimeFormatter localTime() {
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            String p = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale);
            if (p.indexOf('a') >= 0 || p.indexOf('h') >= 0 || p.indexOf('K') >= 0) return DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);
        } catch (RuntimeException ignored) {
            // Fall through to 24-hour time.
        }
        return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    }

    @Override
    public void renderScaled(DrawContext ctx, int screenW, int screenH, boolean preview) {
        int dy = preview ? 0 : toastOffset(screenW, screenH);
        if (dy == 0) { super.renderScaled(ctx, screenW, screenH, preview); return; }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(0, dy);
        try {
            super.renderScaled(ctx, screenW, screenH, preview);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** Whether the watermark is in its default top-right spot (the Module List stacks under it there). */
    public boolean atDefaultSpot() { return x == DEFAULT_X && y == DEFAULT_Y; }

    /**
     * How far the watermark is pushed down by toasts this frame (HUD units). Safe to call again in the same
     * frame (the Module List stacked under it does, even while the watermark itself is hidden).
     */
    public int toastOffset(int screenW, int screenH) { return dodge.update(this, screenW, screenH); }

    /**
     * Where a widget stacked under the watermark starts (HUD units, before the toast offset): just below the
     * panel or the text, or tucked under the bare logo, whose mark has transparent space at the bottom
     * (3 px at Watermark Size 17, in proportion at other sizes).
     */
    public int stackY(int screenH) {
        int bottom = resolveY(screenH) + scaledHeight();
        if (background.get() || style.is("Text")) return bottom + 1;
        return bottom - Math.round(MARK_BOTTOM_SPACE * size.getFloat() / BASE_SIZE * scale.getFloat());
    }
}
