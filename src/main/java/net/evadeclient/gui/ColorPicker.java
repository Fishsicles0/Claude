package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.ColorHistory;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/**
 * Evade's colour picker: a pop-over for any {@link ColorSetting} with a saturation/value square, a hue strip, an
 * alpha strip, a hex field, the palette swatches, your recent colours and, where the colour has one, its Chroma
 * toggle. Changes apply live; clicking outside or Escape closes it (the colour stays as picked) and remembers the
 * colour in {@link ColorHistory}.
 *
 * <p>The host screen draws it last and hands it input first, in GUI coordinates (the menu does this in all three
 * of its layouts). It scales itself down on a small window.
 */
public final class ColorPicker {
    private static final int PAD = 8, SV_W = 132, STRIP = 10, GAP = 6, SV_H = 100, SW = 9, SW_GAP = 3;
    private static final int PW = PAD + SV_W + GAP + STRIP + GAP + STRIP + PAD;
    private static final int TOP = 24;
    private static final int ROW_Y = TOP + SV_H + 8;           // preview, hex and Chroma
    private static final int PALETTE_Y = ROW_Y + 22;           // swatches
    private static final int RECENT_LABEL_Y = PALETTE_Y + SW + 7;
    private static final int RECENT_Y = RECENT_LABEL_Y + 10;
    private static final int PH = RECENT_Y + SW + PAD;
    private static final int HEX_X = PAD + 34, HEX_W = 64;

    private enum Part { NONE, SV, HUE, ALPHA }

    private ColorSetting target;
    private int initial;
    private float hue, sat, val, alpha = 1f;
    private String hex = "";
    private boolean hexFocused;
    private Part dragging = Part.NONE;
    /** Panel's top-left corner (GUI pixels) and scale. */
    private int px, py;
    private float k = 1f;
    private double anchorX, anchorY;

    /** Whether the picker is showing. */
    public boolean isOpen() { return target != null; }

    /** The colour being edited, or null. */
    public ColorSetting target() { return target; }

    /** Open for {@code setting}, next to (anchorX, anchorY) in GUI pixels. */
    public void open(ColorSetting setting, double anchorX, double anchorY) {
        this.target = setting;
        this.initial = setting.get();
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.dragging = Part.NONE;
        this.hexFocused = false;
        load(setting.get());
    }

    /** Close, keeping the colour as picked; a changed colour joins the recent colours and the config is saved. */
    public void close() {
        if (target == null) return;
        int now = target.get();
        if (now != initial) {
            if (now >>> 24 != 0) ColorHistory.add(now);
            if (EvadeClient.config != null) EvadeClient.config.save();
        }
        target = null;
        dragging = Part.NONE;
        hexFocused = false;
    }

    /** Close and put the colour back to what it was when the picker opened. */
    public void cancel() {
        if (target != null) target.set(initial);
        target = null;
        dragging = Part.NONE;
        hexFocused = false;
    }

    // ---- colour state --------------------------------------------------------------------------------

    private void load(int argb) {
        boolean themed = target.followsTheme() && argb >>> 24 == 0;
        int rgb = themed ? Theme.RED & 0xFFFFFF : argb & 0xFFFFFF;
        float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
        // Greys have no hue: keep the one the strip is on, so the square doesn't jump to red.
        if (hsb[1] > 0.001f && hsb[2] > 0.001f) hue = hsb[0];
        sat = hsb[1];
        val = hsb[2];
        alpha = themed || !target.hasAlpha() ? 1f : (argb >>> 24) / 255f;
        hex = hexOf(current());
    }

    /** The colour the controls describe right now (ARGB). */
    int current() {
        int rgb = Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF;
        int a = target != null && !target.hasAlpha() ? 255 : Math.round(alpha * 255);
        if (target != null && target.followsTheme()) a = Math.max(1, a); // 0 alpha means "follow the theme" there
        return a << 24 | rgb;
    }

    private void apply() {
        if (target == null) return;
        target.set(current());
        if (!hexFocused) hex = hexOf(target.get());
    }

    private static String hexOf(int argb) {
        return (argb >>> 24) == 0xFF ? String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF)
                : String.format(Locale.ROOT, "%08X", argb);
    }

    /** {@code text} as a colour: RGB, RRGGBB or AARRGGBB (a # is optional). Null when it isn't one. */
    public static Integer parseHex(String text) {
        String h = text.strip();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        if (h.length() != 6 && h.length() != 8) return null;
        try {
            long v = Long.parseLong(h, 16);
            return h.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Set the colour from outside the controls (a swatch, a recent colour, the hex field). */
    private void set(int argb) {
        if (target == null) return;
        if (!target.hasAlpha()) argb |= 0xFF000000;
        target.set(argb);
        load(argb);
    }

    // ---- layout --------------------------------------------------------------------------------------

    private void layout(int screenW, int screenH) {
        k = Math.min(1f, Math.min((screenW - 12) / (float) PW, (screenH - 12) / (float) PH));
        k = Math.max(0.4f, k);
        int w = Math.round(PW * k), h = Math.round(PH * k);
        int x = (int) Math.round(anchorX) - w - 6;
        if (x < 6) x = (int) Math.round(anchorX) + 14;
        int y = (int) Math.round(anchorY) - h / 2;
        px = Math.max(6, Math.min(screenW - w - 6, x));
        py = Math.max(6, Math.min(screenH - h - 6, y));
    }

    /** Mouse x/y in panel units. */
    private double lx(double mx) { return (mx - px) / k; }
    private double ly(double my) { return (my - py) / k; }

    private static boolean in(double x, double y, int x1, int y1, int x2, int y2) {
        return x >= x1 && x < x2 && y >= y1 && y < y2;
    }

    private int svW() { return target != null && target.hasAlpha() ? SV_W : SV_W + STRIP + GAP; }
    private int hueX() { return PAD + svW() + GAP; }
    private int alphaX() { return hueX() + STRIP + GAP; }

    // ---- drawing -------------------------------------------------------------------------------------

    private static boolean smooth() {
        var cs = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        return cs == null || cs.smoothFont.get();
    }

    private static void text(DrawContext ctx, String s, int x, int y, int color, Role role) {
        UiFont.draw(ctx, MinecraftClient.getInstance().textRenderer, s, x, y, color, role, smooth());
    }

    private static int width(String s, Role role) {
        return UiFont.width(MinecraftClient.getInstance().textRenderer, s, role, smooth());
    }

    public void render(DrawContext ctx, int mouseX, int mouseY, int screenW, int screenH) {
        if (target == null) return;
        layout(screenW, screenH);
        ctx.createNewRootLayer();
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(px, py);
            m.scale(k, k);
            draw(ctx, lx(mouseX), ly(mouseY));
        } finally {
            m.popMatrix();
        }
    }

    private void draw(DrawContext ctx, double mx, double my) {
        Theme.shadow(ctx, Theme.SH_CARD, 0, 0, PW, PH, Theme.alpha(0xFF000000, 0.6f));
        Theme.round(ctx, 0, 0, PW, PH, Theme.BG | 0xFF000000, Theme.R_WIN);
        Theme.stroke(ctx, 0, 0, PW, PH, Theme.BORDER, Theme.R_WIN);

        // Title and close cross.
        String title = target.getName();
        text(ctx, title, PAD, 9, Theme.TEXT, Role.STRONG);
        boolean hx = in(mx, my, PW - 18, 5, PW - 5, 18);
        text(ctx, "×", PW - 14, 8, hx ? Theme.RED_BRIGHT : Theme.TEXT_DIM, Role.BODY);

        int rgbHue = Color.HSBtoRGB(hue, 1f, 1f) | 0xFF000000;
        // Saturation (left to right) and value (top to bottom), one column at a time.
        int sw = svW();
        for (int i = 0; i < sw; i++) {
            float s = i / (float) (sw - 1);
            int top = Color.HSBtoRGB(hue, s, 1f) | 0xFF000000;
            ctx.fillGradient(PAD + i, TOP, PAD + i + 1, TOP + SV_H, top, 0xFF000000);
        }
        Theme.stroke(ctx, PAD - 1, TOP - 1, PAD + sw + 1, TOP + SV_H + 1, Theme.BORDER, 0);
        int cx = PAD + Math.round(sat * (sw - 1)), cy = TOP + Math.round((1 - val) * (SV_H - 1));
        Theme.stroke(ctx, cx - 3, cy - 3, cx + 4, cy + 4, 0xFF000000, 0);
        Theme.stroke(ctx, cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF, 0);

        // Hue strip, top to bottom.
        int hxs = hueX();
        for (int j = 0; j < SV_H; j++)
            ctx.fill(hxs, TOP + j, hxs + STRIP, TOP + j + 1, Color.HSBtoRGB(j / (float) (SV_H - 1), 1f, 1f) | 0xFF000000);
        marker(ctx, hxs, TOP + Math.round(hue * (SV_H - 1)));

        // Alpha strip: opaque at the top, over a checkerboard.
        if (target.hasAlpha()) {
            int ax = alphaX();
            checker(ctx, ax, TOP, ax + STRIP, TOP + SV_H);
            int rgb = current() & 0xFFFFFF;
            ctx.fillGradient(ax, TOP, ax + STRIP, TOP + SV_H, 0xFF000000 | rgb, rgb);
            marker(ctx, ax, TOP + Math.round((1 - alpha) * (SV_H - 1)));
        }

        // Before | after preview.
        checker(ctx, PAD, ROW_Y, PAD + 28, ROW_Y + 14);
        int before = target.followsTheme() && initial >>> 24 == 0 ? Theme.RED : initial;
        ctx.fill(PAD, ROW_Y, PAD + 14, ROW_Y + 14, before);
        ctx.fill(PAD + 14, ROW_Y, PAD + 28, ROW_Y + 14, target.followsTheme() && target.get() >>> 24 == 0 ? Theme.RED : target.get());
        Theme.stroke(ctx, PAD - 1, ROW_Y - 1, PAD + 29, ROW_Y + 15, Theme.BORDER, 0);

        // Hex field.
        boolean hh = in(mx, my, HEX_X, ROW_Y, HEX_X + HEX_W, ROW_Y + 14);
        Theme.roundBordered(ctx, HEX_X, ROW_Y, HEX_X + HEX_W, ROW_Y + 14, Theme.INPUT,
                hexFocused ? Theme.RED : hh ? Theme.BORDER_HOVER : Theme.BORDER);
        boolean caret = hexFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        text(ctx, "#" + hex + (caret ? "_" : ""), HEX_X + 4, ROW_Y + 3, parseHex(hex) == null ? Theme.DANGER : Theme.TEXT, Role.BODY);

        // Right of the hex field: Chroma (when linked) and Theme (for colours that can follow it).
        int rx = PW - PAD;
        BooleanSetting chroma = target.chromaSetting();
        if (chroma != null) {
            Theme.toggle(ctx, rx - 20, ROW_Y + 2, chroma.get() ? 1f : 0f);
            int lw = width("Chroma", Role.SMALL);
            text(ctx, "Chroma", rx - 24 - lw, ROW_Y + 4, Theme.TEXT_SOFT, Role.SMALL);
            rx -= 24 + lw + 6;
        }
        if (target.followsTheme()) {
            int tw = width("Theme", Role.SMALL) + 10;
            boolean on = target.get() >>> 24 == 0;
            boolean ht = in(mx, my, rx - tw, ROW_Y, rx, ROW_Y + 14);
            Theme.roundBordered(ctx, rx - tw, ROW_Y + 1, rx, ROW_Y + 13, on ? Theme.RED : ht ? Theme.CONTROL_HOVER : Theme.CONTROL,
                    on ? Theme.RED : Theme.BORDER);
            text(ctx, "Theme", rx - tw + 5, ROW_Y + 4, on ? Theme.ON_ACCENT : Theme.TEXT_SOFT, Role.SMALL);
        }

        // Palette swatches.
        int[] pal = ColorSetting.PALETTE;
        for (int i = 0; i < pal.length; i++) swatch(ctx, PAD + i * (SW + SW_GAP), PALETTE_Y, pal[i], mx, my);

        // Recent colours.
        text(ctx, "Recent", PAD, RECENT_LABEL_Y, Theme.TEXT_FAINT, Role.SMALL);
        List<Integer> recent = ColorHistory.get();
        if (recent.isEmpty()) text(ctx, "Colours you pick show up here", PAD, RECENT_Y + 1, Theme.TEXT_FAINT, Role.SMALL);
        for (int i = 0; i < recent.size(); i++) swatch(ctx, PAD + i * (SW + SW_GAP), RECENT_Y, recent.get(i), mx, my);
    }

    private void swatch(DrawContext ctx, int x, int y, int argb, double mx, double my) {
        boolean h = in(mx, my, x - 1, y - 1, x + SW + 1, y + SW + 1);
        if ((argb >>> 24) != 0xFF) checker(ctx, x, y, x + SW, y + SW);
        int g = h ? 1 : 0;
        ctx.fill(x - g, y - g, x + SW + g, y + SW + g, argb);
        if (target.get() == argb) Theme.stroke(ctx, x - 2, y - 2, x + SW + 2, y + SW + 2, 0xFFFFFFFF, 0);
    }

    private static void marker(DrawContext ctx, int x, int y) {
        ctx.fill(x - 2, y - 2, x + STRIP + 2, y + 2, 0xFF000000);
        ctx.fill(x - 1, y - 1, x + STRIP + 1, y + 1, 0xFFFFFFFF);
    }

    private static void checker(DrawContext ctx, int x1, int y1, int x2, int y2) {
        int c = 5;
        for (int y = y1; y < y2; y += c)
            for (int x = x1; x < x2; x += c)
                ctx.fill(x, y, Math.min(x + c, x2), Math.min(y + c, y2), ((x - x1) / c + (y - y1) / c) % 2 == 0 ? 0xFF9A9AA2 : 0xFF5E5E66);
    }

    // ---- input ---------------------------------------------------------------------------------------

    /** A click anywhere while open: inside it edits, outside it closes (and is swallowed). */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (target == null) return false;
        double mx = lx(mouseX), my = ly(mouseY);
        if (!in(mx, my, 0, 0, PW, PH)) { close(); return true; }
        if (button != 0) return true;
        if (in(mx, my, PW - 18, 5, PW - 5, 18)) { close(); return true; }
        hexFocused = false;
        int sw = svW();
        if (in(mx, my, PAD - 2, TOP - 2, PAD + sw + 2, TOP + SV_H + 2)) { dragging = Part.SV; drag(mx, my); return true; }
        if (in(mx, my, hueX() - 2, TOP - 2, hueX() + STRIP + 2, TOP + SV_H + 2)) { dragging = Part.HUE; drag(mx, my); return true; }
        if (target.hasAlpha() && in(mx, my, alphaX() - 2, TOP - 2, alphaX() + STRIP + 2, TOP + SV_H + 2)) {
            dragging = Part.ALPHA; drag(mx, my); return true;
        }
        if (in(mx, my, HEX_X, ROW_Y, HEX_X + HEX_W, ROW_Y + 14)) { hexFocused = true; return true; }
        if (in(mx, my, PAD, ROW_Y, PAD + 14, ROW_Y + 14)) { set(initial); return true; } // the "before" half: undo
        int rx = PW - PAD;
        BooleanSetting chroma = target.chromaSetting();
        if (chroma != null) {
            int lw = width("Chroma", Role.SMALL);
            if (in(mx, my, rx - 24 - lw, ROW_Y, rx, ROW_Y + 14)) { chroma.set(!chroma.get()); return true; }
            rx -= 24 + lw + 6;
        }
        if (target.followsTheme()) {
            int tw = width("Theme", Role.SMALL) + 10;
            if (in(mx, my, rx - tw, ROW_Y, rx, ROW_Y + 14)) { target.set(target.getDefault()); load(target.get()); return true; }
        }
        int[] pal = ColorSetting.PALETTE;
        for (int i = 0; i < pal.length; i++) {
            int x = PAD + i * (SW + SW_GAP);
            if (in(mx, my, x - 1, PALETTE_Y - 1, x + SW + 2, PALETTE_Y + SW + 1)) {
                // A swatch keeps the transparency you chose.
                set(target.hasAlpha() && alpha < 1f ? (Math.round(alpha * 255) << 24) | (pal[i] & 0xFFFFFF) : pal[i]);
                return true;
            }
        }
        List<Integer> recent = ColorHistory.get();
        for (int i = 0; i < recent.size(); i++) {
            int x = PAD + i * (SW + SW_GAP);
            if (in(mx, my, x - 1, RECENT_Y - 1, x + SW + 2, RECENT_Y + SW + 1)) { set(recent.get(i)); return true; }
        }
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        if (target == null) return false;
        if (dragging != Part.NONE) drag(lx(mouseX), ly(mouseY));
        return true;
    }

    public void mouseReleased() { dragging = Part.NONE; }

    private void drag(double mx, double my) {
        float fy = (float) Math.max(0, Math.min(1, (my - TOP) / (SV_H - 1)));
        switch (dragging) {
            case SV -> {
                sat = (float) Math.max(0, Math.min(1, (mx - PAD) / (svW() - 1)));
                val = 1f - fy;
            }
            case HUE -> hue = Math.min(0.9999f, fy);
            case ALPHA -> alpha = 1f - fy;
            default -> { return; }
        }
        apply();
    }

    public boolean keyPressed(KeyInput input) {
        if (target == null) return false;
        int key = input.key();
        if (hexFocused) {
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE -> { hexFocused = false; hex = hexOf(target.get()); }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    Integer c = parseHex(hex);
                    if (c != null) set(c);
                    hexFocused = false;
                    hex = hexOf(target.get());
                }
                case GLFW.GLFW_KEY_BACKSPACE -> { if (!hex.isEmpty()) hex = hex.substring(0, hex.length() - 1); }
                default -> {
                    if (input.isPaste()) {
                        String clip = MinecraftClient.getInstance().keyboard.getClipboard();
                        Integer c = clip == null ? null : parseHex(clip);
                        if (c != null) { set(c); hex = hexOf(c); }
                    }
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { close(); return true; }
        if (input.isCopy()) { MinecraftClient.getInstance().keyboard.setClipboard("#" + hexOf(target.get())); return true; }
        if (input.isPaste()) {
            String clip = MinecraftClient.getInstance().keyboard.getClipboard();
            Integer c = clip == null ? null : parseHex(clip);
            if (c != null) set(c);
            return true;
        }
        return true;
    }

    public boolean charTyped(CharInput input) {
        if (target == null) return false;
        if (!hexFocused) return true;
        String s = input.asString();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.digit(ch, 16) >= 0 && hex.length() < 8) hex += Character.toUpperCase(ch);
        }
        // Applies as soon as it is a whole colour, so the square follows your typing.
        if (hex.length() == 6 || hex.length() == 8) {
            Integer c = parseHex(hex);
            if (c != null && target != null) {
                int v = target.hasAlpha() ? c : c | 0xFF000000;
                target.set(v);
                String keep = hex;
                load(v);
                hex = keep;
            }
        }
        return true;
    }

    // ---- test hooks ----------------------------------------------------------------------------------

    /** Test hook: put the controls at hue/saturation/value/alpha (0..1) and apply, as dragging would. */
    public void setHsvForTest(float h, float s, float v, float a) {
        hue = h; sat = s; val = v; alpha = a;
        apply();
    }

    /** Test hook: where (GUI pixels) a point of the saturation/value square is drawn after the last render. */
    public double[] svPointForTest(float s, float v) {
        return new double[]{px + (PAD + s * (svW() - 1)) * k, py + (TOP + (1 - v) * (SV_H - 1)) * k};
    }
}
