package net.evadeclient.gui.title;

import net.evadeclient.gui.Motion;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

import java.util.function.Supplier;

/**
 * An Evade main menu button, drawn to the kit's button spec (DESIGN 6.2) but kept a vanilla widget, so keyboard and
 * controller navigation and narration work like any vanilla button. Styles:
 * <ul>
 *   <li>{@link Style#PRIMARY}: the one red call to action of the screen: a red500 to red700 gradient with a soft top
 *   sheen, a specular rim that follows the cursor, and an additive red glow rising under it on hover.</li>
 *   <li>{@link Style#SECONDARY}: raised dark glass (e3) with a hairline, the rim and a slanted red edge light that
 *   grows in from the left on hover.</li>
 *   <li>{@link Style#TILE}: a secondary in tile form: the icon over a short label (the row of utilities).</li>
 *   <li>{@link Style#GHOST}: text only until hovered (Quit: red text on hover).</li>
 *   <li>{@link Style#ICON}: a square ghost icon button; {@link Style#LINK}: a small text link.</li>
 * </ul>
 * Hover runs on the kit's snappy spring (Reduced motion: critically damped; Off: instant), a press dips the button
 * half a device pixel for 90 ms, and keyboard focus draws the kit's red focus ring. Its tooltip text ({@link #tip})
 * is drawn by the screen above the button and read by narration as a hint.
 */
public class TitleButton extends PressableWidget implements TitleTip {
    public enum Style { PRIMARY, SECONDARY, TILE, GHOST, ICON, LINK }

    private static final int PRESS_MS = Motion.DUR_FAST + 40;

    private final Style style;
    private final String icon;
    private final Runnable action;
    private Intro intro;
    private double delayMs;
    private float slideX, slideY;
    private boolean center;
    private boolean danger;
    private String tip;
    /** Parallax depth plane (design px of shift at the screen's edge; see {@link TitleScene#planeX}). */
    private float plane;
    /** Optional: a favicon drawn in the icon slot instead of the icon. */
    private Supplier<Identifier> favicon;
    /** Optional: a short right-aligned note (e.g. players online) that gives way to the chevron on hover. */
    private Supplier<String> trailing;
    /** Optional: the colour of a status dot drawn before the trailing note (0 = none). */
    private java.util.function.IntSupplier trailingDot;
    private String prefix;

    private final Motion.Spring hover = new Motion.Spring(0f);
    private long lastFrame = System.nanoTime();
    private double pressAt = -1e9;

    private String label;
    private String shown;
    private int shownFor = -1;

    public TitleButton(int x, int y, int w, int h, Text message, Style style, String icon, Runnable action) {
        super(x, y, w, h, message);
        this.style = style;
        this.icon = icon;
        this.action = action;
        this.label = message.getString();
    }

    /** Arrives {@code delayMs} into the intro, sliding in from (dx, dy) (only with Full motion). */
    public TitleButton intro(Intro intro, double delayMs, float dx, float dy) {
        this.intro = intro;
        this.delayMs = delayMs;
        this.slideX = dx;
        this.slideY = dy;
        return this;
    }

    /** Centre the icon and label instead of left-aligning them. */
    public TitleButton centered(boolean c) { this.center = c; return this; }

    /** Moves with parallax plane {@code depth} (see {@link TitleScene#planeX}). */
    public TitleButton plane(float depth) { this.plane = depth; return this; }

    /** Hover turns the label red (Quit). */
    public TitleButton danger() { this.danger = true; return this; }

    /** The tooltip (drawn by the screen above the button; narrated as a hint). */
    public TitleButton tip(String t) { this.tip = t; return this; }

    public TitleButton tip(Text t) { this.tip = t == null ? null : t.getString(); return this; }

    @Override public String tip() { return tip; }

    public TitleButton favicon(Supplier<Identifier> f) { this.favicon = f; return this; }

    public TitleButton trailing(Supplier<String> t, java.util.function.IntSupplier dot) {
        this.trailing = t;
        this.trailingDot = dot;
        return this;
    }

    /** A dim word drawn before the label ("Rejoin" before the server's name); the message keeps the full text. */
    public TitleButton prefix(String p, String label) {
        this.prefix = p;
        this.label = label;
        this.shownFor = -1;
        return this;
    }

    public Style style() { return style; }

    @Override
    public void onPress(AbstractInput input) {
        pressAt = Intro.now();
        action.run();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
        if (tip != null) builder.put(NarrationPart.HINT, Text.literal(tip));
    }

    private float radius() {
        return switch (style) {
            case LINK -> 0f;
            case TILE -> Tokens.R_MD;
            case ICON -> Tokens.R_SM;
            default -> getHeight() >= 32 ? Tokens.R_MD : Tokens.R_SM;
        };
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        float in = intro == null ? 1f : intro.p(delayMs, 360);
        if (in <= 0.001f) return;
        boolean moves = intro == null || intro.moves();

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean keyboardFocus = isFocused() && mc.getNavigationType().isKeyboard();
        boolean hot = active && (isHovered() || keyboardFocus);
        float h = Math.max(0f, Math.min(1f, Motion.spring(hover, hot ? 1f : 0f, Motion.SNAPPY, dt)));
        double since = Intro.now() - pressAt;
        boolean press = active && since >= 0 && since < PRESS_MS;

        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = base * in * this.alpha;
            float dip = press ? 0.5f / Theme.deviceScale(ctx) : 0f;
            if (plane != 0) m.translate(Theme.snap(ctx, TitleScene.planeX(plane)), Theme.snap(ctx, TitleScene.planeY(plane)));
            if (moves) m.translate(Theme.snap(ctx, slideX * (1f - in)), Theme.snap(ctx, slideY * (1f - in)) + dip);
            else m.translate(0, dip);
            float x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            switch (style) {
                case PRIMARY -> drawPrimary(ctx, x1, y1, x2, y2, h, press, mouseX);
                case SECONDARY, TILE -> drawSecondary(ctx, x1, y1, x2, y2, h, press, mouseX);
                case GHOST, ICON -> drawGhost(ctx, x1, y1, x2, y2, h, press);
                case LINK -> { }
            }
            drawContent(ctx, getX(), getY(), getX() + getWidth(), getY() + getHeight(), h);
            if (keyboardFocus && style != Style.LINK) focusRing(ctx, x1, y1, x2, y2, radius(), danger && style == Style.GHOST);
            else if (keyboardFocus) Theme.fill(ctx, (int) x1, (int) y2, (int) x2, (int) y2 + 1, Tokens.FOCUS_RING);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    // ------------------------------------------------------------------ surfaces

    private static boolean gpu() { return UiShape.available(); }

    /** The kit focus ring: 2 device px of red400, 2 device px outside, with a soft glow. */
    static void focusRing(DrawContext ctx, float x1, float y1, float x2, float y2, float r, boolean danger) {
        int color = danger ? Tokens.DANGER : Tokens.FOCUS_RING;
        float k = Theme.deviceScale(ctx);
        float o = 4f / k;
        if (gpu()) {
            if (Tokens.glows()) UiShape.glow(ctx, x1 - o, y1 - o, x2 + o, y2 + o, r + o, 1.5f, Theme.alpha(Tokens.A600, 0.40f));
            UiShape.shape(x1 - o, y1 - o, x2 + o, y2 + o).radius(r + o).border(color, 2).draw(ctx);
            return;
        }
        Theme.stroke(ctx, Math.round(x1 - 2), Math.round(y1 - 2), Math.round(x2 + 2), Math.round(y2 + 2), color, Math.round(r + 2));
    }

    private void drawPrimary(DrawContext ctx, float x1, float y1, float x2, float y2, float h, boolean press, int mouseX) {
        float r = radius();
        if (!active) {
            paintRect(ctx, x1, y1, x2, y2, r, Tokens.N400);
            return;
        }
        if (!gpu()) {
            Theme.shadow(ctx, Theme.SH_CARD, Math.round(x1), Math.round(y1 + 2), Math.round(x2), Math.round(y2 + 2), Theme.alpha(0xFF000000, 0.5f));
            int fill = press ? Tokens.A700 : Theme.lerpColor(Tokens.A600, Tokens.A500, h);
            Theme.round(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), fill, Math.round(r));
            Theme.fadeLine(ctx, Math.round(x1 + 2), Math.round(y1), Math.round(x2 - 2), Theme.alpha(0xFFFFFFFF, 0.30f + 0.15f * h));
            return;
        }
        // Light, not paint: an additive red bloom rising under the button as it is hovered, and a soft red ambient
        // shadow below even at rest (the emissive CTA lights the glass it sits on).
        if (Tokens.glows()) {
            if (h > 0.01f) UiShape.glow(ctx, x1 + 2, y1 + 4, x2 - 2, y2 + 4, r, 10f, Theme.alpha(Tokens.A600, 0.34f * h));
            UiShape.shape(x1 + 8, y2 - 6, x2 - 8, y2).radius(3).fill(Theme.alpha(Tokens.A700, 0.30f)).asGlow(6f, true).draw(ctx);
        }
        UiShape.shape(x1, y1, x2, y2).radius(r).fill(Theme.alpha(0xFF000000, press ? 0.30f : 0.45f))
                .asShadow(press ? 1f : 2f, press ? 0.5f : 2f, true).draw(ctx);
        int top = press ? Tokens.A700 : Theme.lerpColor(Tokens.A500, Theme.lerpColor(Tokens.A500, 0xFFFFFFFF, 0.08f), h);
        int bottom = press ? Tokens.A700 : Theme.lerpColor(Tokens.A700, Tokens.A600, h);
        UiShape.shape(x1, y1, x2, y2).radius(r).vertical(top, bottom).border(Theme.alpha(Tokens.A300, 0.35f + 0.25f * h), 1).draw(ctx);
        if (!press) {
            UiShape.shape(x1, y1, x2, y2).radius(r).linear(Theme.alpha(0xFFFFFFFF, 0.16f + 0.04f * h), 0x00FFFFFF, 0, y2 - y1, 0.5f)
                    .rim(0.20f + 0.10f * h).rimAt(h > 0.01f ? mouseX - x1 : (x2 - x1) * 0.35f).draw(ctx);
        }
    }

    private void drawSecondary(DrawContext ctx, float x1, float y1, float x2, float y2, float h, boolean press, int mouseX) {
        float r = radius();
        if (!active) {
            paintRect(ctx, x1, y1, x2, y2, r, Theme.alpha(Tokens.N150, 0.70f));
            return;
        }
        // Glass over the panorama (reads as e3, translucent enough that the world glows through).
        int fill = press ? Theme.alpha(Tokens.N200, 0.92f) : Theme.lerpColor(Theme.alpha(Tokens.N200, 0.80f), Theme.alpha(Tokens.N300, 0.90f), h);
        if (!gpu()) {
            Theme.shadow(ctx, Theme.SH_CARD, Math.round(x1), Math.round(y1 + 2), Math.round(x2), Math.round(y2 + 2), Theme.alpha(0xFF000000, 0.4f));
            Theme.round(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), fill, Math.round(r));
            Theme.hairline(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2),
                    Theme.lerpColor(0x1FFFFFFF, Theme.alpha(Tokens.A400, 0.7f), h), Math.round(r));
            return;
        }
        UiShape.shape(x1, y1, x2, y2).radius(r).fill(Theme.alpha(0xFF000000, 0.40f)).asShadow(press ? 1f : 2f + h, press ? 0.5f : 2f + h, true).draw(ctx);
        UiShape.shape(x1, y1, x2, y2).radius(r).fill(fill)
                .border(Theme.alpha(0xFFFFFFFF, 0.08f + 0.05f * h), 1)
                .rim(0.09f + 0.07f * h).rimAt(h > 0.01f ? mouseX - x1 : (x2 - x1) * 0.4f).draw(ctx);
        // The Evade slant: a red edge light leaning in from the left edge as the button is hovered.
        if (h > 0.01f && style == Style.SECONDARY && !center) {
            float bh = (y2 - y1 - 12) * h, cy = (y1 + y2) / 2f;
            slant(ctx, x1 + 5, cy - bh / 2, x1 + 7, cy + bh / 2, Theme.alpha(Tokens.A500, h));
        }
    }

    private void drawGhost(DrawContext ctx, float x1, float y1, float x2, float y2, float h, boolean press) {
        if (!active || (h <= 0.01f && !press)) return;
        int fill = press ? Tokens.N200 : Theme.alpha(Tokens.N250, (style == Style.ICON ? 0.75f : 0.6f) * h);
        if (danger) fill = Theme.alpha(Tokens.DANGER, press ? 0.22f : 0.12f * h);
        paintRect(ctx, x1, y1, x2, y2, radius(), fill);
    }

    private static void paintRect(DrawContext ctx, float x1, float y1, float x2, float y2, float r, int color) {
        if (gpu()) UiShape.shape(x1, y1, x2, y2).radius(r).fill(color).draw(ctx);
        else Theme.round(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), color, Math.round(r));
    }

    /** A bar from (x1, y1) to (x2, y2) leaning 12 degrees forward (the Evade slant). */
    static void slant(DrawContext ctx, float x1, float y1, float x2, float y2, int color) {
        if (!gpu()) { Theme.fill(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), color); return; }
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            float cy = (y1 + y2) / 2f, t = (float) Math.tan(Math.toRadians(Tokens.SLANT_DEG));
            m.translate(0, cy);
            m.mul(new Matrix3x2f(1, 0, -t, 1, 0, 0));
            UiShape.shape(x1, y1 - cy, x2, y2 - cy).radius(0.5f).fill(color).draw(ctx);
        } finally {
            m.popMatrix();
        }
    }

    // ------------------------------------------------------------------ content

    private Role role() {
        return switch (style) {
            case LINK -> Role.SMALL;
            case TILE -> Role.LABEL;
            default -> getHeight() >= 32 ? Role.TITLE : Role.STRONG;
        };
    }

    private int iconSize() {
        int bh = getHeight();
        return switch (style) {
            case LINK -> 7;
            case ICON -> Math.min(12, bh - 6);
            case TILE -> bh >= 40 ? 16 : 13;
            default -> bh >= 32 ? 15 : bh >= 24 ? 12 : 11;
        };
    }

    private void drawContent(DrawContext ctx, int x1, int y1, int x2, int y2, float h) {
        boolean primary = style == Style.PRIMARY;
        int textColor, iconColor;
        if (!active) {
            textColor = iconColor = primary ? Tokens.N600 : Theme.alpha(Tokens.N700, 0.8f);
        } else if (primary) {
            textColor = iconColor = Tokens.ON_ACCENT;
        } else if (style == Style.LINK) {
            textColor = iconColor = Theme.lerpColor(Tokens.N700, Tokens.A400, h);
        } else if (style == Style.GHOST || style == Style.ICON) {
            textColor = iconColor = Theme.lerpColor(Tokens.N800, danger ? Tokens.DANGER : Tokens.N950, h);
        } else {
            textColor = Theme.lerpColor(Tokens.N900, Tokens.N950, h);
            iconColor = Theme.lerpColor(Tokens.N800, danger ? Tokens.DANGER : Tokens.A400, h);
        }
        int bh = y2 - y1;
        float cy = y1 + bh / 2f;
        int is = iconSize();
        Role role = role();

        if (style == Style.ICON) {
            TitleArt.icon(ctx, icon, x1 + (x2 - x1 - is) / 2, Math.round(cy - is / 2f), is, iconColor);
            return;
        }
        if (style == Style.TILE) {
            String s = ellipsized(role, x2 - x1 - 8);
            int tw = TitleArt.width(s, role);
            int gap = 5, block = is + gap + Math.round(role.capHeight(TitleArt.smooth()));
            int iy = Math.round(cy - block / 2f);
            TitleArt.icon(ctx, icon, x1 + (x2 - x1 - is) / 2, iy, is, iconColor);
            TitleArt.textMid(ctx, s, x1 + (x2 - x1 - tw) / 2, iy + is + gap + role.capHeight(TitleArt.smooth()) / 2f, textColor, role);
            return;
        }
        if (style == Style.LINK) {
            int tw = TitleArt.width(label, role);
            TitleArt.textMid(ctx, label, x1, cy, textColor, role);
            if (icon != null) TitleArt.icon(ctx, icon, x1 + tw + 3 + Math.round(h), Math.round(cy - is / 2f) - Math.round(h), is, textColor);
            return;
        }

        int pad = Math.round(bh * (center ? 0.5f : 0.45f));
        int gap = bh >= 32 ? 10 : 7;
        boolean hasIcon = icon != null || favicon != null;
        boolean chevron = !center && bh >= 24;
        int prefixW = prefix == null ? 0 : TitleArt.width(prefix, role) + 4;
        int right = x2 - pad - (chevron ? 12 : 0);
        int room = right - (x1 + pad + (hasIcon ? is + gap : 0)) - prefixW;
        String s = ellipsized(role, room);
        int tw = TitleArt.width(s, role) + prefixW;
        int x = center ? x1 + (x2 - x1 - (hasIcon ? is + gap : 0) - tw) / 2 : x1 + pad;
        if (hasIcon) {
            Identifier fav = favicon == null ? null : favicon.get();
            if (fav != null) {
                int fs = is + 2;
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, fav, x - 1, Math.round(cy - fs / 2f), 0, 0, fs, fs, 64, 64, 64, 64,
                        Theme.fa(active ? 0xFFFFFFFF : 0x80FFFFFF));
            } else if (icon != null) {
                TitleArt.icon(ctx, icon, x, Math.round(cy - is / 2f), is, iconColor);
            }
            x += is + gap;
        }
        if (prefix != null) {
            TitleArt.textMid(ctx, prefix, x, cy, active ? Tokens.N700 : Tokens.N600, role);
            x += prefixW;
        }
        TitleArt.textMid(ctx, s, x, cy, textColor, role);

        if (center || !active) return;
        // Right slot: the trailing note (with its status dot) fades out as the slanted chevron slides in.
        float base = Theme.fade;
        if (trailing != null) {
            String t = trailing.get();
            float a = 1f - h;
            if (t != null && !t.isEmpty() && a > 0.01f) {
                Theme.fade = base * a;
                int w = TitleArt.width(t, Role.SMALL);
                int tx = x2 - pad - w;
                TitleArt.textMid(ctx, t, tx, cy, primary ? Theme.alpha(Tokens.ON_ACCENT, 0.85f) : Tokens.N700, Role.SMALL);
                int dot = trailingDot == null ? 0 : trailingDot.getAsInt();
                if (dot != 0) dot(ctx, tx - 6, cy, 4, dot);
                Theme.fade = base;
            }
        }
        if (chevron) {
            boolean noted = trailing != null && trailing.get() != null && !trailing.get().isEmpty();
            float a = noted ? h : (primary ? 0.65f + 0.35f * h : 0.35f + 0.65f * h);
            if (a > 0.01f) {
                Theme.fade = base * a;
                int cs = 8;
                float ax = x2 - pad - cs + (1f - h) * -3f;
                var m = ctx.getMatrices();
                m.pushMatrix();
                try {
                    m.translate(Theme.snap(ctx, ax + cs / 2f), Theme.snap(ctx, cy));
                    m.mul(new Matrix3x2f(1, 0, -(float) Math.tan(Math.toRadians(Tokens.SLANT_DEG)), 1, 0, 0));
                    TitleArt.icon(ctx, "chevron_r", -cs / 2, -cs / 2, cs, primary ? Tokens.ON_ACCENT : Tokens.N950);
                } finally {
                    m.popMatrix();
                }
                Theme.fade = base;
            }
        }
    }

    private String ellipsized(Role role, int room) {
        if (shownFor != room) {
            shown = TitleArt.ellipsize(label, Math.max(10, room), role);
            shownFor = room;
        }
        return shown;
    }

    /** A small status dot centred on (cx, cy). */
    static void dot(DrawContext ctx, float cx, float cy, float d, int color) {
        if (gpu()) UiShape.shape(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2).radius(d / 2).fill(color).draw(ctx);
        else Theme.disc(ctx, Math.round(cx - d / 2), Math.round(cy - d / 2), Math.round(d), color);
    }
}
