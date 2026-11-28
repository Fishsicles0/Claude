package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.ColorHistory;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
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
 * Evade's colour picker (spec 6.16): an e4 popover 232 px wide for any {@link ColorSetting}. A 200 x 120
 * saturation / value area with an inner hairline and a ring handle that carries the colour, horizontal hue and alpha
 * strips with the same handles, a before | after chip (click "before" to undo), the hex value in tabular digits
 * (click to type), the Chroma switch and Theme button where the colour has them, the 12 theme swatches and your 8
 * recent colours (empty slots dashed). Everything is drawn through the SDF shapes with dithered gradients.
 *
 * <p>The host screen draws it last and hands it input first, in GUI coordinates. It scales itself down on a small
 * window. Changes apply live; clicking outside, Enter or Escape closes it and remembers the colour.
 */
public final class ColorPicker {
    private static final int PW = 232, PAD = 16, SV_W = 200, SV_H = 120, STRIP = 10, SW = 14;
    private static final int TOP = 40, HUE_Y = TOP + SV_H + 12;
    private static final int HEX_W = 92, ROW_H = 22;

    private enum Part { NONE, SV, HUE, ALPHA }

    private ColorSetting target;
    private int initial;
    private float hue, sat, val, alpha = 1f;
    private String hex = "";
    private boolean hexFocused;
    private Part dragging = Part.NONE;
    private int px, py;
    private float k = 1f;
    private double anchorX, anchorY;
    private long openedAt;

    public boolean isOpen() { return target != null; }

    public ColorSetting target() { return target; }

    /** Open for {@code setting}, next to (anchorX, anchorY) in GUI pixels. */
    public void open(ColorSetting setting, double anchorX, double anchorY) {
        this.target = setting;
        this.initial = setting.get();
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.dragging = Part.NONE;
        this.hexFocused = false;
        this.openedAt = System.currentTimeMillis();
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

    // ---- colour state ----------------------------------------------------------------------------

    private void load(int argb) {
        boolean themed = target.followsTheme() && argb >>> 24 == 0;
        int rgb = themed ? Theme.RED & 0xFFFFFF : argb & 0xFFFFFF;
        float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
        if (hsb[1] > 0.001f && hsb[2] > 0.001f) hue = hsb[0];
        sat = hsb[1];
        val = hsb[2];
        alpha = themed || !target.hasAlpha() ? 1f : (argb >>> 24) / 255f;
        hex = hexOf(current());
    }

    int current() {
        int rgb = Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF;
        int a = target != null && !target.hasAlpha() ? 255 : Math.round(alpha * 255);
        if (target != null && target.followsTheme()) a = Math.max(1, a);
        return a << 24 | rgb;
    }

    private void apply() {
        if (target == null) return;
        target.set(current());
        if (!hexFocused) hex = hexOf(target.get());
    }

    private static String hexOf(int argb) {
        return (argb >>> 24) == 0xFF ? String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF) : String.format(Locale.ROOT, "%08X", argb);
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

    private void set(int argb) {
        if (target == null) return;
        if (!target.hasAlpha()) argb |= 0xFF000000;
        target.set(argb);
        load(argb);
    }

    // ---- layout ----------------------------------------------------------------------------------

    private int alphaY() { return HUE_Y + STRIP + 10; }
    private int rowY() { return (target != null && target.hasAlpha() ? alphaY() + STRIP : HUE_Y + STRIP) + 14; }
    private int swatchY() { return rowY() + ROW_H + 26; }
    private int recentY() { return swatchY() + SW + 22; }
    private int height() { return recentY() + SW + PAD; }

    private static float swatchX(int i, int n) { return PAD + i * ((SV_W - SW) / (float) (n - 1)); }

    private void layout(int screenW, int screenH) {
        int ph = height();
        k = Math.min(1f, Math.min((screenW - 12) / (float) PW, (screenH - 12) / (float) ph));
        k = Math.max(0.4f, k);
        int w = Math.round(PW * k), h = Math.round(ph * k);
        int x = (int) Math.round(anchorX) - w - 10;
        if (x < 6) x = (int) Math.round(anchorX) + 16;
        int y = (int) Math.round(anchorY) - h / 2;
        px = Math.max(6, Math.min(screenW - w - 6, x));
        py = Math.max(6, Math.min(screenH - h - 6, y));
    }

    private double lx(double mx) { return (mx - px) / k; }
    private double ly(double my) { return (my - py) / k; }

    private static boolean in(double x, double y, float x1, float y1, float x2, float y2) {
        return x >= x1 && x < x2 && y >= y1 && y < y2;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    private static boolean smooth() {
        var cs = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        return (cs == null || cs.smoothFont.get()) && !Theme.square;
    }

    private static void text(DrawContext ctx, String s, int x, float cy, int color, Role role) {
        UiFont.draw(ctx, MinecraftClient.getInstance().textRenderer, s, x, Math.round(UiFont.centredY(role, cy, smooth())), color, role, smooth());
    }

    private static int width(String s, Role role) {
        return UiFont.width(MinecraftClient.getInstance().textRenderer, s, role, smooth());
    }

    public void render(DrawContext ctx, int mouseX, int mouseY, int screenW, int screenH) {
        if (target == null) return;
        Tokens.refresh();
        layout(screenW, screenH);
        ctx.createNewRootLayer();
        float a0 = Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) Math.max(1, Motion.duration(Motion.DUR_QUICK)));
        if (Motion.level() == Motion.Level.OFF) a0 = 1f;
        // A soft dim behind the popover keeps the eye on it.
        Paint.rect(ctx, 0, 0, screenW, screenH, 0, Theme.alpha(Tokens.N0, 0.30f * a0));
        float a = Motion.standard(Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) Math.max(1, Motion.duration(Motion.DUR_QUICK))));
        if (Motion.level() == Motion.Level.OFF) a = 1f;
        var m = ctx.getMatrices();
        float base = Theme.fade;
        m.pushMatrix();
        try {
            Theme.fade = base * a;
            m.translate(px, py + (Motion.decorative() ? (1 - a) * -4 : 0));
            m.scale(k, k);
            draw(ctx, lx(mouseX), ly(mouseY));
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    private void draw(DrawContext ctx, double mx, double my) {
        int ph = height();
        Paint.surface(ctx, 0, 0, PW, ph, Tokens.R_MD, Tokens.E4, 0, Float.NaN);

        // Title and close.
        text(ctx, target.getName(), PAD, 20, Tokens.N950, Role.STRONG);
        boolean hx = in(mx, my, PW - 32, 8, PW - 8, 32);
        if (hx) Paint.rect(ctx, PW - 32, 8, PW - 8, 32, Tokens.R_SM, Theme.alpha(Tokens.N350, 0.8f));
        Icons.draw(ctx, "close", PW - 26, 14, 12, hx ? Tokens.N950 : Tokens.N700);

        // Saturation (left to right) and value (top to bottom).
        int pure = Color.HSBtoRGB(hue, 1f, 1f) | 0xFF000000;
        float x1 = PAD, y1 = TOP, x2 = PAD + SV_W, y2 = TOP + SV_H;
        if (Paint.gpu()) {
            UiShape.shape(x1, y1, x2, y2).radius(Tokens.R_SM).horizontal(0xFFFFFFFF, pure).draw(ctx);
            UiShape.shape(x1, y1, x2, y2).radius(Tokens.R_SM).vertical(0x00000000, 0xFF000000).draw(ctx);
            UiShape.shape(x1, y1, x2, y2).radius(Tokens.R_SM).border(Theme.alpha(0xFFFFFFFF, 0.08f), 1).shade(-0.4f).draw(ctx);
        } else {
            for (int i = 0; i < SV_W; i++) {
                int top = Color.HSBtoRGB(hue, i / (float) (SV_W - 1), 1f) | 0xFF000000;
                ctx.fillGradient(PAD + i, TOP, PAD + i + 1, TOP + SV_H, Theme.fa(top), Theme.fa(0xFF000000));
            }
        }
        handle(ctx, PAD + sat * SV_W, TOP + (1 - val) * SV_H, current() | 0xFF000000);

        // Hue strip: the six hue segments end to end, rounded at the ends.
        float seg = SV_W / 6f;
        for (int i = 0; i < 6; i++) {
            int c1 = Color.HSBtoRGB(i / 6f, 1f, 1f) | 0xFF000000, c2 = Color.HSBtoRGB((i + 1) / 6f, 1f, 1f) | 0xFF000000;
            float sx = PAD + i * seg;
            if (Paint.gpu()) {
                UiShape.shape(sx - (i > 0 ? 0.5f : 0), HUE_Y, sx + seg + (i < 5 ? 0.5f : 0), HUE_Y + STRIP)
                        .radii(i == 0 ? 5 : 0, i == 5 ? 5 : 0, i == 5 ? 5 : 0, i == 0 ? 5 : 0).horizontal(c1, c2).draw(ctx);
            } else {
                for (int j = 0; j < (int) seg; j++) Theme.fill(ctx, Math.round(sx) + j, HUE_Y, Math.round(sx) + j + 1, HUE_Y + STRIP,
                        Color.HSBtoRGB((i + j / seg) / 6f, 1f, 1f) | 0xFF000000);
            }
        }
        handle(ctx, PAD + hue * SV_W, HUE_Y + STRIP / 2f, pure);

        // Alpha strip over a checkerboard.
        if (target.hasAlpha()) {
            int ay = alphaY();
            checker(ctx, PAD + 5, ay, PAD + SV_W - 5, ay + STRIP);
            int rgb = current() & 0xFFFFFF;
            Paint.hgradient(ctx, PAD, ay, PAD + SV_W, ay + STRIP, STRIP / 2f, 0xFF000000 | rgb, rgb);
            Paint.ring(ctx, PAD, ay, PAD + SV_W, ay + STRIP, STRIP / 2f, Theme.alpha(0xFFFFFFFF, 0.08f), 1);
            handle(ctx, PAD + alpha * SV_W, ay + STRIP / 2f, current());
        }

        // Before | after, the hex value, Chroma and Theme.
        int ry = rowY();
        float rc = ry + ROW_H / 2f;
        int before = target.followsTheme() && initial >>> 24 == 0 ? Theme.RED : initial;
        int after = target.followsTheme() && target.get() >>> 24 == 0 ? Theme.RED : target.get();
        checker(ctx, PAD, ry, PAD + 40, ry + ROW_H);
        if (Paint.gpu()) {
            UiShape.shape(PAD, ry, PAD + 20, ry + ROW_H).radii(Tokens.R_XS, 0, 0, Tokens.R_XS).fill(before).draw(ctx);
            UiShape.shape(PAD + 20, ry, PAD + 40, ry + ROW_H).radii(0, Tokens.R_XS, Tokens.R_XS, 0).fill(after).draw(ctx);
        } else {
            Theme.fill(ctx, PAD, ry, PAD + 20, ry + ROW_H, before);
            Theme.fill(ctx, PAD + 20, ry, PAD + 40, ry + ROW_H, after);
        }
        Paint.ring(ctx, PAD, ry, PAD + 40, ry + ROW_H, Tokens.R_XS, Theme.alpha(0xFFFFFFFF, in(mx, my, PAD, ry, PAD + 20, ry + ROW_H) ? 0.4f : 0.12f), 1);

        int hx1 = PAD + 48;
        boolean hh = in(mx, my, hx1, ry, hx1 + HEX_W, ry + ROW_H);
        Paint.surface(ctx, hx1, ry, hx1 + HEX_W, ry + ROW_H, Tokens.R_SM, Tokens.SUNKEN.withFill(hh ? Tokens.N200 : Tokens.N150), 0, Float.NaN);
        if (hexFocused) Paint.ring(ctx, hx1, ry, hx1 + HEX_W, ry + ROW_H, Tokens.R_SM, Tokens.A400, 1);
        boolean caret = hexFocused && (System.currentTimeMillis() / 530) % 2 == 0;
        var tr = MinecraftClient.getInstance().textRenderer;
        int ty = Math.round(UiFont.centredY(Role.BODY, rc, smooth()));
        UiFont.draw(ctx, tr, "#", hx1 + 7, ty, Tokens.N600, Role.BODY, smooth());
        int dw = UiFont.drawTabular(ctx, tr, hex, hx1 + 7 + width("#", Role.BODY) + 1, ty, parseHex(hex) == null ? Tokens.DANGER : Tokens.N950, Role.BODY, smooth());
        if (caret) {
            int cx = hx1 + 8 + width("#", Role.BODY) + UiFont.tabularWidth(tr, hex, Role.BODY, smooth());
            Theme.fill(ctx, cx, Math.round(rc - 5), cx + 1, Math.round(rc + 5), Tokens.A400);
        }

        int rx = PW - PAD;
        BooleanSetting chroma = target.chromaSetting();
        if (chroma != null) {
            boolean on = chroma.get();
            int tw = 28;
            if (on) Paint.pill(ctx, rx - tw, rc - 7, rx, rc + 7, Tokens.A600);
            else Paint.surface(ctx, rx - tw, rc - 7, rx, rc + 7, 7, Tokens.SUNKEN, 0, Float.NaN);
            Paint.circle(ctx, on ? rx - 7 : rx - tw + 7, rc, 11, on ? 0xFFFFFFFF : Tokens.N800);
            int lw = width("Chroma", Role.SMALL);
            text(ctx, "Chroma", rx - tw - 6 - lw, rc, Tokens.N800, Role.SMALL);
            rx -= tw + 12 + lw;
        }
        if (target.followsTheme()) {
            boolean on = target.get() >>> 24 == 0;
            int tw = width("Theme", Role.LABEL) + 14;
            boolean ht = in(mx, my, rx - tw, ry, rx, ry + ROW_H);
            Paint.surface(ctx, rx - tw, ry, rx, ry + ROW_H, Tokens.R_SM, Tokens.E3.noAmbient().withFill(on ? Tokens.ACCENT_SURFACE : ht ? Tokens.N300 : Tokens.N250), 0, Float.NaN);
            if (on) Paint.ring(ctx, rx - tw, ry, rx, ry + ROW_H, Tokens.R_SM, Theme.alpha(Tokens.A500, 0.7f), 1);
            text(ctx, "Theme", rx - tw + 7, rc, on ? Tokens.N950 : Tokens.N800, Role.LABEL);
        }

        // Theme swatches.
        int sy = swatchY();
        UiFont.caps(ctx, tr, "SWATCHES", PAD, sy - 13, Tokens.N600, Role.CAPS_SM, 1f, smooth());
        int[] pal = ColorSetting.PALETTE;
        for (int i = 0; i < pal.length; i++) swatch(ctx, swatchX(i, pal.length), sy, pal[i], mx, my);

        // Recent colours: eight slots, the empty ones dashed.
        int ryy = recentY();
        UiFont.caps(ctx, tr, "RECENT", PAD, ryy - 13, Tokens.N600, Role.CAPS_SM, 1f, smooth());
        List<Integer> recent = ColorHistory.get();
        for (int i = 0; i < 8; i++) {
            float x = PAD + i * ((SV_W - SW) / 7f);
            if (i < recent.size()) swatch(ctx, x, ryy, recent.get(i), mx, my);
            else Paint.dashed(ctx, Math.round(x), ryy, Math.round(x) + SW, ryy + SW, Theme.alpha(Tokens.N500, 0.8f));
        }
    }

    private void swatch(DrawContext ctx, float x, int y, int argb, double mx, double my) {
        boolean h = in(mx, my, x - 1, y - 1, x + SW + 1, y + SW + 1);
        float g = h ? 1f : 0f;
        if ((argb >>> 24) != 0xFF) checker(ctx, Math.round(x), y, Math.round(x) + SW, y + SW);
        Paint.rect(ctx, x - g, y - g, x + SW + g, y + SW + g, Tokens.R_XS, argb);
        Paint.ring(ctx, x - g, y - g, x + SW + g, y + SW + g, Tokens.R_XS, Theme.alpha(0xFFFFFFFF, 0.10f), 1);
        if (target.get() == argb) Paint.ring(ctx, x - 3, y - 3, x + SW + 3, y + SW + 3, Tokens.R_XS + 2, 0xFFFFFFFF, 2);
    }

    /** A 14 px ring handle centred on (x, y): 2 device px white over a 1 px dark outline, filled with {@code fill}. */
    private static void handle(DrawContext ctx, float x, float y, int fill) {
        float r = 7;
        if (Paint.gpu()) UiShape.shape(x - r, y - r, x + r, y + r).radius(r).fill(Theme.alpha(0xFF000000, 0.45f)).asShadow(1.2f, 0.8f, false).draw(ctx);
        Paint.circle(ctx, x, y, 2 * r, fill);
        Paint.ring(ctx, x - r, y - r, x + r, y + r, r, Theme.alpha(0xFF000000, 0.55f), 1);
        Paint.ring(ctx, x - r + 0.5f, y - r + 0.5f, x + r - 0.5f, y + r - 0.5f, r - 0.5f, 0xFFFFFFFF, 2);
    }

    private static void checker(DrawContext ctx, int x1, int y1, int x2, int y2) {
        int c = 4;
        for (int y = y1; y < y2; y += c)
            for (int x = x1; x < x2; x += c)
                Theme.fill(ctx, x, y, Math.min(x + c, x2), Math.min(y + c, y2), ((x - x1) / c + (y - y1) / c) % 2 == 0 ? 0xFF8A8D96 : 0xFF54575F);
    }

    // ---- input -----------------------------------------------------------------------------------

    /** A click anywhere while open: inside it edits, outside it closes (and is swallowed). */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (target == null) return false;
        double mx = lx(mouseX), my = ly(mouseY);
        if (!in(mx, my, 0, 0, PW, height())) { close(); return true; }
        if (button != 0) return true;
        if (in(mx, my, PW - 32, 8, PW - 8, 32)) { close(); return true; }
        hexFocused = false;
        if (in(mx, my, PAD - 4, TOP - 4, PAD + SV_W + 4, TOP + SV_H + 4)) { dragging = Part.SV; drag(mx, my); return true; }
        if (in(mx, my, PAD - 6, HUE_Y - 4, PAD + SV_W + 6, HUE_Y + STRIP + 4)) { dragging = Part.HUE; drag(mx, my); return true; }
        if (target.hasAlpha() && in(mx, my, PAD - 6, alphaY() - 4, PAD + SV_W + 6, alphaY() + STRIP + 4)) { dragging = Part.ALPHA; drag(mx, my); return true; }
        int ry = rowY();
        if (in(mx, my, PAD + 48, ry, PAD + 48 + HEX_W, ry + ROW_H)) { hexFocused = true; return true; }
        if (in(mx, my, PAD, ry, PAD + 20, ry + ROW_H)) { set(initial); return true; } // the "before" half: undo
        int rx = PW - PAD;
        BooleanSetting chroma = target.chromaSetting();
        if (chroma != null) {
            int lw = width("Chroma", Role.SMALL);
            if (in(mx, my, rx - 28 - 6 - lw, ry, rx, ry + ROW_H)) { chroma.set(!chroma.get()); return true; }
            rx -= 28 + 12 + lw;
        }
        if (target.followsTheme()) {
            int tw = width("Theme", Role.LABEL) + 14;
            if (in(mx, my, rx - tw, ry, rx, ry + ROW_H)) { target.set(target.getDefault()); load(target.get()); return true; }
        }
        int[] pal = ColorSetting.PALETTE;
        int sy = swatchY();
        for (int i = 0; i < pal.length; i++) {
            float x = swatchX(i, pal.length);
            if (in(mx, my, x - 1, sy - 1, x + SW + 1, sy + SW + 1)) {
                set(target.hasAlpha() && alpha < 1f ? (Math.round(alpha * 255) << 24) | (pal[i] & 0xFFFFFF) : pal[i]);
                return true;
            }
        }
        List<Integer> recent = ColorHistory.get();
        int ryy = recentY();
        for (int i = 0; i < Math.min(8, recent.size()); i++) {
            float x = PAD + i * ((SV_W - SW) / 7f);
            if (in(mx, my, x - 1, ryy - 1, x + SW + 1, ryy + SW + 1)) { set(recent.get(i)); return true; }
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
        float fx = (float) Math.max(0, Math.min(1, (mx - PAD) / SV_W));
        switch (dragging) {
            case SV -> {
                sat = fx;
                val = 1f - (float) Math.max(0, Math.min(1, (my - TOP) / SV_H));
            }
            case HUE -> hue = Math.min(0.9999f, fx);
            case ALPHA -> alpha = fx;
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
        // Arrow keys nudge the colour: left / right the hue, up / down the brightness.
        float step = input.hasShift() ? 0.05f : 0.01f;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> { hue = (hue - step + 1f) % 1f; apply(); }
            case GLFW.GLFW_KEY_RIGHT -> { hue = (hue + step) % 1f; apply(); }
            case GLFW.GLFW_KEY_UP -> { val = Math.min(1f, val + step); apply(); }
            case GLFW.GLFW_KEY_DOWN -> { val = Math.max(0f, val - step); apply(); }
            default -> { }
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

    // ---- test hooks ------------------------------------------------------------------------------

    /** Test hook: put the controls at hue/saturation/value/alpha (0..1) and apply, as dragging would. */
    public void setHsvForTest(float h, float s, float v, float a) {
        hue = h; sat = s; val = v; alpha = a;
        apply();
    }

    /** Test hook: where (GUI pixels) a point of the saturation/value area is drawn after the last render. */
    public double[] svPointForTest(float s, float v) {
        return new double[]{px + (PAD + s * SV_W) * k, py + (TOP + (1 - v) * SV_H) * k};
    }
}
