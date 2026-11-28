package net.evadeclient.gui.kit;

import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;

/**
 * Buttons (spec 6.2): Primary (the one red call to action per view), Secondary, Ghost and Danger, in four sizes, with
 * an optional leading icon and the slanted Evade chevron. Every state: idle, hover (fill and rim on a snappy spring,
 * the primary's glow), pressed (+0.5 device px, flat, darker), keyboard focus ring, disabled.
 */
public final class Button {
    private Button() {}

    public enum Variant { PRIMARY, SECONDARY, GHOST, DANGER }

    /** Heights: sm 20 (label text), md 24, lg 28, xl 36 (title calls to action). */
    public enum Size {
        SM(Tokens.H_SM, Role.LABEL), MD(Tokens.H_MD, Role.STRONG), LG(Tokens.H_LG, Role.STRONG), XL(Tokens.H_XL, Role.STRONG);

        public final int h;
        public final Role role;

        Size(int h, Role role) { this.h = h; this.role = role; }
    }

    /** The width a button with this label (and optional icon / chevron) takes. */
    public static int width(Kit k, String label, Size size, String icon, boolean chevron) {
        int pad = Math.round(size.h * 0.55f);
        int w = k.width(label, size.role) + 2 * pad;
        if (icon != null) w += iconSize(size) + Tokens.S3;
        if (chevron) w += 6 + Tokens.S2;
        return w;
    }

    private static int iconSize(Size s) { return s == Size.SM ? 12 : s == Size.XL ? 16 : 14; }

    /** A button at (x, y), {@code w} wide (0 = fit the label). Returns its width. */
    public static int draw(Kit k, String id, int x, int y, int w, Size size, Variant v, String label, String icon,
                           boolean enabled, Runnable onClick) {
        return draw(k, id, x, y, w, size, v, label, icon, false, enabled, onClick);
    }

    /** {@link #draw} with the trailing slanted chevron (forward actions: Next, Continue). */
    public static int draw(Kit k, String id, int x, int y, int w, Size size, Variant v, String label, String icon,
                           boolean chevron, boolean enabled, Runnable onClick) {
        if (w <= 0) w = width(k, label, size, icon, chevron);
        int h = size.h;
        int st = k.state(id, x, y, x + w, y + h, enabled);
        boolean dis = (st & Kit.DISABLED) != 0, press = (st & Kit.PRESSED) != 0;
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float dy = press ? 0.5f / Theme.deviceScale(k.ctx) : 0f;
        float x1 = x, y1 = y + dy, x2 = x + w, y2 = y + h + dy, r = Tokens.R_SM;
        int text;
        switch (v) {
            case PRIMARY -> {
                if (dis) {
                    Paint.rect(k.ctx, x1, y1, x2, y2, r, Tokens.N400);
                    text = Tokens.N600;
                } else {
                    if (hov > 0.01f) Paint.glow(k, x1, y1 + 4, x2, y2 + 4, r, 10, Theme.alpha(Tokens.A600, 0.35f * hov));
                    // Soft contact shadow, the red gradient (brighter on hover, flat on press) and its top sheen.
                    if (Paint.gpu()) {
                        net.evadeclient.render.ui.UiShape.shape(x1, y1, x2, y2).radius(r).fill(Theme.alpha(0xFF000000, press ? 0.25f : 0.4f))
                                .asShadow(press ? 1f : 1.5f, press ? 0.5f : 1.5f, false).draw(k.ctx);
                    }
                    int topC = press ? Tokens.A700 : Theme.lerpColor(Tokens.A500, Theme.lerpColor(Tokens.A500, 0xFFFFFFFF, 0.08f), hov);
                    int botC = press ? Tokens.A700 : Theme.lerpColor(Tokens.A700, Tokens.A600, hov);
                    Paint.vgradient(k.ctx, x1, y1, x2, y2, r, topC, botC);
                    if (!press && Paint.gpu())
                        net.evadeclient.render.ui.UiShape.shape(x1, y1, x2, y2).radius(r)
                                .linear(Theme.alpha(0xFFFFFFFF, 0.18f), 0x00FFFFFF, 0, y2 - y1, 0.45f).rim(0.18f + 0.06f * hov)
                                .rimAt(k.mx - x1).draw(k.ctx);
                    text = Tokens.ON_ACCENT;
                }
            }
            case SECONDARY -> {
                if (dis) {
                    float f = Theme.fade;
                    Theme.fade = f * 0.4f;
                    try { Paint.surface(k.ctx, x1, y1, x2, y2, r, Tokens.E3, 0, Float.NaN); } finally { Theme.fade = f; }
                    text = Theme.alpha(Tokens.N950, 0.4f);
                } else {
                    int fill = press ? Tokens.N250 : Theme.lerpColor(Tokens.N250, Tokens.N300, hov);
                    Paint.surface(k.ctx, x1, y1, x2, y2, r, Tokens.E3.noAmbient().withFill(fill), press ? 0 : hov, k.mx - x1);
                    text = Tokens.N950;
                }
            }
            case GHOST -> {
                if (!dis && (hov > 0.01f || press))
                    Paint.rect(k.ctx, x1, y1, x2, y2, r, press ? Tokens.N200 : Theme.alpha(Tokens.N250, 0.6f * hov));
                text = dis ? Tokens.N600 : Theme.lerpColor(Tokens.N800, Tokens.N950, hov);
            }
            default -> { // DANGER
                if (!dis && (hov > 0.01f || press))
                    Paint.rect(k.ctx, x1, y1, x2, y2, r, Theme.alpha(Tokens.DANGER, press ? 0.22f : 0.14f * hov));
                text = dis ? Tokens.N600 : Tokens.DANGER;
            }
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x1, y1, x2, y2, r, v == Variant.DANGER ? Tokens.DANGER : Tokens.FOCUS_RING);

        // Label (and icon / chevron), centred as a group on the button's cap-height centre line.
        int iw = icon != null ? iconSize(size) + Tokens.S3 : 0, cw = chevron ? Tokens.S2 + 6 : 0;
        int lw = k.width(label, size.role);
        int group = iw + lw + cw;
        int gx = Math.round(x1 + (w - group) / 2f);
        var m = k.ctx.getMatrices();
        m.pushMatrix();
        try {
            if (dy != 0) m.translate(0, dy);
            float cy = y + h / 2f;
            if (icon != null) {
                int is = iconSize(size);
                Icons.draw(k.ctx, icon, gx, Math.round(cy - is / 2f), is, text);
            }
            k.textCentredY(label, gx + iw, cy, text, size.role);
            if (chevron) { // the forward chevron, leaning with the Evade slant
                int cs = 8;
                var mm = k.ctx.getMatrices();
                mm.pushMatrix();
                mm.translate(gx + iw + lw + Tokens.S2 + 3, cy);
                mm.mul(new org.joml.Matrix3x2f(1, 0, -(float) Math.tan(Math.toRadians(Tokens.SLANT_DEG)), 1, 0, 0));
                Icons.draw(k.ctx, "chevron_r", -cs / 2, -cs / 2, cs, text);
                mm.popMatrix();
            }
        } finally {
            m.popMatrix();
        }
        if (enabled) k.hit(id, x, y, x + w, y + h, onClick);
        return w;
    }

    /**
     * A square icon button (ghost): 24 or 28 px, icon n800, n950 on hover. Close, back, gear, more.
     * {@code tip} shows as the kit tooltip.
     */
    public static void icon(Kit k, String id, int x, int y, int size, String icon, String tip, boolean enabled, Runnable onClick) {
        int st = k.state(id, x, y, x + size, y + size, enabled);
        boolean dis = (st & Kit.DISABLED) != 0, press = (st & Kit.PRESSED) != 0;
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        if (!dis && (hov > 0.01f || press))
            Paint.rect(k.ctx, x, y, x + size, y + size, Tokens.R_SM, press ? Tokens.N200 : Theme.alpha(Tokens.N250, 0.7f * hov));
        int is = Math.round(size * 0.58f);
        int col = dis ? Tokens.N600 : Theme.lerpColor(Tokens.N800, Tokens.N950, hov);
        Icons.draw(k.ctx, icon, x + (size - is) / 2, y + (size - is) / 2, is, col);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + size, y + size, Tokens.R_SM, Tokens.FOCUS_RING);
        if (enabled) k.hit(id, x, y, x + size, y + size, onClick);
        if (tip != null) k.tooltip(id, tip, null, x, y, x + size, y + size);
    }

    /**
     * A vanilla {@code ButtonWidget} on an Evade screen, painted as a kit Secondary button (no grey stone): used by
     * {@code mixin/ButtonWidgetKitMixin}. {@code label} null keeps vanilla's own label (only the background is
     * drawn). Kept free of a {@link Kit} so it works inside vanilla widgets.
     */
    public static void vanilla(net.minecraft.client.gui.DrawContext ctx, int x, int y, int w, int h, String label, boolean active,
                               boolean hot, float alpha) {
        Tokens.refresh();
        float f = Theme.fade;
        Theme.fade = f * Math.max(0, Math.min(1, alpha)) * (active ? 1f : 0.45f);
        try {
            int fill = active && hot ? Tokens.N300 : Tokens.N250;
            Paint.surface(ctx, x, y, x + w, y + h, Tokens.R_SM, Tokens.E3.noAmbient().withFill(fill), active && hot ? 1f : 0f, Float.NaN);
            if (active && hot && h >= 12) Paint.ring(ctx, x, y, x + w, y + h, Tokens.R_SM, Theme.alpha(Tokens.A400, 0.55f), 1);
            if (label == null) return;
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            var cs = net.evadeclient.EvadeClient.modules == null ? null
                    : net.evadeclient.EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
            boolean smooth = cs == null || cs.smoothFont.get();
            String s = label;
            int max = w - 8;
            if (net.evadeclient.util.UiFont.width(mc.textRenderer, s, Role.STRONG, smooth) > max) {
                while (s.length() > 1 && net.evadeclient.util.UiFont.width(mc.textRenderer, s + "\u2026", Role.STRONG, smooth) > max)
                    s = s.substring(0, s.length() - 1);
                s = s.stripTrailing() + "\u2026";
            }
            int tw = net.evadeclient.util.UiFont.width(mc.textRenderer, s, Role.STRONG, smooth);
            float ty = net.evadeclient.util.UiFont.centredY(Role.STRONG, y + h / 2f, smooth);
            net.evadeclient.util.UiFont.draw(ctx, mc.textRenderer, s, x + (w - tw) / 2, Math.round(ty),
                    active ? Tokens.N950 : Tokens.N600, Role.STRONG, smooth);
        } finally {
            Theme.fade = f;
        }
    }

    /** Whether the screen showing now is one of Evade's own (vanilla widgets on it get the kit look). */
    public static boolean evadeScreen() {
        if (Theme.square) return false;
        var s = net.minecraft.client.MinecraftClient.getInstance().currentScreen;
        return s != null && s.getClass().getName().startsWith("net.evadeclient.");
    }
}
