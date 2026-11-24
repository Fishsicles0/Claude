package net.evadeclient.gui.title;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

/**
 * An Evade main menu button. Styles: {@link Style#PRIMARY} (accent fill with a glassy top half), {@link Style#SECONDARY}
 * (dark glass with a hairline rim), {@link Style#GHOST} (text only until hovered), {@link Style#ICON} (a square icon
 * button) and {@link Style#LINK} (a small text link). Hover lifts the button a pixel and lights an accent glow (a
 * spring, so it eases in and out), a press dips it, and keyboard or controller focus draws a white ring. The label
 * is the button's message, so narration reads it like any vanilla button.
 */
public class TitleButton extends PressableWidget {
    public enum Style { PRIMARY, SECONDARY, GHOST, ICON, LINK }

    private static final int PRESS_MS = 180;

    private final Style style;
    private final String icon;
    private final Runnable action;
    private Intro intro;
    private double delayMs;
    private float slideX, slideY;
    private boolean center;
    private boolean danger;
    /** Optional: a favicon drawn in the icon slot instead of the icon. */
    private Supplier<Identifier> favicon;
    /** Optional: a short right-aligned note (e.g. players online) that gives way to the arrow on hover. */
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

    /** Arrives {@code delayMs} into the intro, sliding in from (dx, dy). */
    public TitleButton intro(Intro intro, double delayMs, float dx, float dy) {
        this.intro = intro;
        this.delayMs = delayMs;
        this.slideX = dx;
        this.slideY = dy;
        return this;
    }

    /** Centre the icon and label (half-width buttons) instead of left-aligning them. */
    public TitleButton centered(boolean c) { this.center = c; return this; }

    /** Hover turns the label red (Quit). */
    public TitleButton danger() { this.danger = true; return this; }

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

    @Override
    public void onPress(AbstractInput input) {
        pressAt = Intro.now();
        action.run();
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    /** Radius by style: buttons 4, icon squares 4, links none. */
    private int radius() {
        return style == Style.LINK ? 0 : 4;
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        float in = intro == null ? 1f : intro.p(delayMs, 360);
        if (in <= 0.001f) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean keyboardFocus = isFocused() && mc.getNavigationType().isKeyboard();
        boolean hot = active && (isHovered() || keyboardFocus);
        boolean anims = TitleArt.anims();
        float h = anims ? hover.step(hot ? 1f : 0f, 30f, 0.85f, dt) : (hot ? 1f : 0f);
        if (!anims) hover.set(h);
        h = Math.max(0f, Math.min(1.2f, h));
        float hc = Math.min(1f, h);
        double since = Intro.now() - pressAt;
        float press = since < PRESS_MS && active ? 1f - Motion.cubicOut((float) (since / PRESS_MS)) : 0f;

        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = base * in * this.alpha;
            float lift = style == Style.LINK || style == Style.GHOST ? 0f : -1f * hc + 1f * press;
            m.translate(Theme.snap(ctx, slideX * (1f - in)), Theme.snap(ctx, slideY * (1f - in) + lift));
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            switch (style) {
                case PRIMARY -> drawPrimary(ctx, x1, y1, x2, y2, hc, press);
                case SECONDARY, ICON -> drawSecondary(ctx, x1, y1, x2, y2, hc, press);
                case GHOST -> drawGhost(ctx, x1, y1, x2, y2, hc);
                case LINK -> { }
            }
            drawContent(ctx, x1, y1, x2, y2, hc);
            if (keyboardFocus && style != Style.LINK) {
                Theme.stroke(ctx, x1 - 2, y1 - 2, x2 + 2, y2 + 2, 0xE6FFFFFF, radius() + 2);
            } else if (keyboardFocus) {
                Theme.fill(ctx, x1, y2, x2, y2 + 1, 0xE6FFFFFF);
            }
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    private void drawPrimary(DrawContext ctx, int x1, int y1, int x2, int y2, float h, float press) {
        int r = radius();
        if (!active) {
            Theme.round(ctx, x1, y1, x2, y2, 0xB0151519, r);
            Theme.hairline(ctx, x1, y1, x2, y2, 0x18FFFFFF, r);
            return;
        }
        Theme.shadow(ctx, Theme.SH_CARD, x1, y1 + 2, x2, y2 + 2, Theme.alpha(0xFF000000, 0.55f));
        if (h > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x2, y2, Theme.alpha(Theme.RED, 0.85f * h));
        int fill = Theme.lerpColor(Theme.RED | 0xFF000000, Theme.RED_BRIGHT | 0xFF000000, 0.22f * h);
        if (press > 0) fill = Theme.lerpColor(fill, Theme.RED_DARK | 0xFF000000, 0.35f * press);
        Theme.round(ctx, x1, y1, x2, y2, fill, r);
        // A glassy top half and a light catching the top edge.
        Theme.round(ctx, x1, y1, x2, y1 + (y2 - y1) / 2, 0x16FFFFFF, r, Theme.TL | Theme.TR);
        Theme.fadeLine(ctx, x1 + 2, y1, x2 - 2, Theme.alpha(0xFFFFFFFF, 0.42f + 0.2f * h));
    }

    private void drawSecondary(DrawContext ctx, int x1, int y1, int x2, int y2, float h, float press) {
        int r = radius();
        if (!active) {
            Theme.round(ctx, x1, y1, x2, y2, 0x900C0C10, r);
            Theme.hairline(ctx, x1, y1, x2, y2, 0x12FFFFFF, r);
            return;
        }
        Theme.shadow(ctx, Theme.SH_CARD, x1, y1 + 2, x2, y2 + 2, Theme.alpha(0xFF000000, 0.45f));
        if (h > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x2, y2, Theme.alpha(Theme.RED, 0.34f * h));
        int fill = Theme.lerpColor(0xC40F0F13, 0xE61B1B22, h);
        if (press > 0) fill = Theme.lerpColor(fill, 0xF0141418, press);
        Theme.round(ctx, x1, y1, x2, y2, fill, r);
        Theme.fadeLine(ctx, x1 + 2, y1, x2 - 2, Theme.alpha(0xFFFFFFFF, 0.10f + 0.06f * h));
        Theme.hairline(ctx, x1, y1, x2, y2, Theme.lerpColor(0x26FFFFFF, Theme.alpha(Theme.RED_BRIGHT, 0.85f), h), r);
        if (style == Style.SECONDARY && !center && h > 0.01f) {
            int bh = Math.round((y2 - y1 - 8) * h);
            int cy = (y1 + y2) / 2;
            Theme.round(ctx, x1 + 2, cy - bh / 2, x1 + 4, cy - bh / 2 + bh, Theme.RED | 0xFF000000, 1);
        }
    }

    private void drawGhost(DrawContext ctx, int x1, int y1, int x2, int y2, float h) {
        if (h > 0.01f) {
            Theme.round(ctx, x1, y1, x2, y2, Theme.alpha(0xD0141419, h), radius());
            Theme.hairline(ctx, x1, y1, x2, y2, Theme.alpha(danger ? Theme.DANGER : 0xFFFFFFFF, 0.22f * h), radius());
        }
    }

    private void drawContent(DrawContext ctx, int x1, int y1, int x2, int y2, float h) {
        boolean primary = style == Style.PRIMARY;
        int textColor, iconColor;
        if (!active) {
            textColor = Theme.TEXT_FAINT;
            iconColor = Theme.TEXT_FAINT;
        } else if (primary) {
            textColor = Theme.ON_ACCENT;
            iconColor = Theme.ON_ACCENT;
        } else if (style == Style.LINK) {
            textColor = Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, h);
            iconColor = textColor;
        } else if (style == Style.GHOST) {
            textColor = Theme.lerpColor(Theme.TEXT_DIM, danger ? Theme.DANGER : 0xFFFFFFFF, h);
            iconColor = textColor;
        } else {
            textColor = Theme.lerpColor(Theme.TEXT, 0xFFFFFFFF, h);
            iconColor = Theme.lerpColor(Theme.TEXT_SOFT, danger ? Theme.DANGER : Theme.RED_BRIGHT, h);
        }
        int bh = y2 - y1, cy = y1 + bh / 2;

        if (style == Style.ICON) {
            int s = Math.min(12, bh - 6);
            TitleArt.icon(ctx, icon, x1 + (x2 - x1 - s) / 2, cy - s / 2, s, iconColor);
            return;
        }
        Role role = primary ? Role.STRONG : style == Style.LINK ? Role.SMALL : Role.BODY;
        int textY = role == Role.SMALL ? cy - 3 : cy - 4;
        int is = style == Style.LINK ? 7 : primary ? (bh >= 24 ? 12 : 11) : (bh >= 22 ? 11 : 10);
        int pad = style == Style.LINK ? 0 : Math.max(8, bh / 2);
        int gap = style == Style.LINK ? 4 : 7;
        boolean hasIcon = icon != null || favicon != null;

        if (style == Style.LINK) {
            String s = label;
            int tw = TitleArt.width(s, role);
            TitleArt.text(ctx, s, x1, textY, textColor, role);
            if (icon != null) TitleArt.icon(ctx, icon, x1 + tw + 3 + Math.round(h * 1f), cy - is / 2 - Math.round(h), is, textColor);
            return;
        }

        int prefixW = prefix == null ? 0 : TitleArt.width(prefix, role) + 4;
        int right = x2 - pad - (center ? 0 : 10);
        int room = right - (x1 + pad + (hasIcon ? is + gap : 0)) - prefixW;
        if (shownFor != room) {
            shown = TitleArt.ellipsize(label, Math.max(10, room), role);
            shownFor = room;
        }
        int tw = TitleArt.width(shown, role) + prefixW;
        int x = center ? x1 + (x2 - x1 - (hasIcon ? is + gap : 0) - tw) / 2 : x1 + pad;
        if (hasIcon) {
            Identifier fav = favicon == null ? null : favicon.get();
            if (fav != null) {
                int fs = is + 2;
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, fav, x - 1, cy - fs / 2, 0, 0, fs, fs, 64, 64, 64, 64,
                        Theme.fa(active ? 0xFFFFFFFF : 0x80FFFFFF));
            } else if (icon != null) {
                TitleArt.icon(ctx, icon, x, cy - is / 2, is, iconColor);
            }
            x += is + gap;
        }
        if (prefix != null) {
            TitleArt.text(ctx, prefix, x, textY, active ? Theme.TEXT_DIM : Theme.TEXT_FAINT, role);
            x += prefixW;
        }
        TitleArt.text(ctx, shown, x, textY, textColor, role);

        if (center || !active) return;
        // Right slot: the trailing note (with its status dot) fades out as an arrow slides in.
        if (trailing != null) {
            String t = trailing.get();
            float a = 1f - h;
            if (t != null && !t.isEmpty() && a > 0.01f) {
                float base = Theme.fade;
                Theme.fade = base * a;
                int w = TitleArt.width(t, Role.SMALL);
                int tx = x2 - pad - w;
                TitleArt.text(ctx, t, tx, cy - 3, primary ? Theme.ON_ACCENT : Theme.TEXT_DIM, Role.SMALL);
                int dot = trailingDot == null ? 0 : trailingDot.getAsInt();
                if (dot != 0) Theme.disc(ctx, tx - 7, cy - 2, 4, dot);
                Theme.fade = base;
            }
        }
        if (h > 0.01f) {
            float base = Theme.fade;
            Theme.fade = base * h;
            int cs = 8;
            int ax = x2 - pad - cs + Math.round((1f - h) * -4f);
            TitleArt.icon(ctx, "chevron_r", ax, cy - cs / 2, cs, primary ? Theme.ON_ACCENT : 0xFFFFFFFF);
            Theme.fade = base;
        }
    }
}
