package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The modal every small Evade window shares (spec 6.13): Open to Friends, player cards, cloud backup, server prompts,
 * the benchmark card, Safe Start, launcher joins, the Hypixel key. An e5 window (r12, 20 padding) over a dithered
 * scrim with the world blurred behind; an optional icon tile and the HEADLINE title; the body the subclass draws
 * ({@link #renderBody}); and the actions right-aligned in the footer (quiet ones first, the one primary last).
 * Everything is kit: buttons, text fields (with an optional mask for secrets), keyboard focus.
 *
 * <p>Esc cancels ({@link #close}); Enter runs the primary action unless a field or a focused control takes it.
 * Motion: a fade, a 0.97 to 1 scale and a 10 px rise (Full); a short fade (Reduced); nothing (Off).
 */
public abstract class EvadeDialog extends Screen {
    protected final Screen parent;
    protected final Kit kit = new Kit();
    /** The window; the content area starts at ({@link #bx}, {@link #by}) and is {@link #bw} wide. */
    protected int px, py, pw, ph, bx, by, bw;
    protected boolean smooth = true;
    private long openedAt;

    public static final int PAD = Tokens.S7, TITLE_H = 40, FOOT_H = 44;

    /** A footer action. */
    public static final class Act {
        String label, icon;
        Button.Variant variant;
        final Runnable run;
        public boolean active = true;
        /** Shown on the left side of the footer (a quiet extra like "Get a key"). */
        boolean left;

        Act(String label, Button.Variant v, Runnable run) { this.label = label; this.variant = v; this.run = run; }

        public void setLabel(String s) { label = s; }
        public Act icon(String i) { icon = i; return this; }
        public Act left() { left = true; return this; }
    }

    private final List<Act> acts = new ArrayList<>();

    protected EvadeDialog(String title, Screen parent) {
        super(Text.literal(title));
        this.parent = parent;
    }

    /** Window size wanted, the title and footer included (clamped to the screen). */
    protected int prefWidth() { return 400; }
    protected abstract int prefHeight();

    /** An icon in a tile before the title (null: none). */
    protected String titleIcon() { return null; }

    /** The title shown (default: the screen's title). */
    protected String heading() { return title.getString(); }

    /** Draw the body inside (bx, by)-(bx + bw, footer). */
    protected void renderBody(DrawContext ctx, int mx, int my) {}

    /** Lays out the window and clears the actions; subclasses call this first in {@link #init} and then add actions. */
    protected void frame() {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        smooth = s == null || s.smoothFont.get();
        if (openedAt == 0) openedAt = System.currentTimeMillis();
        pw = Math.min(prefWidth(), width - 20);
        ph = Math.min(prefHeight(), height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        bx = px + PAD;
        bw = pw - 2 * PAD;
        by = py + PAD + TITLE_H - 6;
        acts.clear();
    }

    @Override
    protected void init() {
        frame();
    }

    /** Adds a footer action (in order: quiet ones first, the primary last). */
    protected Act action(String label, Button.Variant v, Runnable run) {
        Act a = new Act(label, v, run);
        acts.add(a);
        return a;
    }

    /** A secondary footer action (old signature: the position is laid out by the footer now). */
    protected Act button(String label, int x, int y, int w, Runnable run) {
        return action(label, Button.Variant.SECONDARY, run);
    }

    /** The primary action, run by Enter: the last enabled one with the primary variant. */
    private Act primary() {
        for (int i = acts.size() - 1; i >= 0; i--) if (acts.get(i).variant == Button.Variant.PRIMARY && acts.get(i).active) return acts.get(i);
        return null;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        if (s != null) s.applyTheme();
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        Paint.scrim(ctx, width, height, fade());
    }

    private float fade() {
        Motion.Level l = Motion.level();
        if (l == Motion.Level.OFF) return 1f;
        int d = l == Motion.Level.REDUCED ? Motion.DUR_REDUCED_MAX : Motion.DUR_QUICK;
        return Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) d);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        kit.begin(ctx, mx, my);
        float base = Theme.fade;
        float a = fade();
        float rise = Motion.decorative() ? Motion.standard(Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) Motion.DUR_SLOW)) : 1f;
        Theme.fade = base * a;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            if (rise < 1f) {
                float sc = 0.97f + 0.03f * rise;
                m.translate(width / 2f, height / 2f);
                m.scale(sc, sc);
                m.translate(-width / 2f, -height / 2f + Theme.snap(ctx, (1f - rise) * 10f));
            }
            Paint.surface(ctx, px, py, px + pw, py + ph, Tokens.R_LG, Tokens.E5, 0, Float.NaN);
            int tx = px + PAD;
            float tcy = py + PAD + 10;
            String icon = titleIcon();
            if (icon != null) {
                Paint.rect(ctx, tx, tcy - 13, tx + 26, tcy + 13, Tokens.R_SM, Tokens.N250);
                PanelGlyphs.draw(ctx, icon, tx + 6, tcy - 7, 14, Tokens.N900);
                tx += 26 + Tokens.S4;
            }
            kit.textCentredY(kit.ellipsize(heading(), Role.HEADLINE, px + pw - PAD - tx), tx, tcy, Tokens.N950, Role.HEADLINE);
            renderBody(ctx, mx, my);
            drawActions();
            kit.end();
        } finally {
            m.popMatrix();
            Theme.fade = base;
        }
    }

    private void drawActions() {
        if (acts.isEmpty()) return;
        int y = py + ph - PAD - Button.Size.MD.h;
        int right = px + pw - PAD, left = px + PAD;
        for (int i = acts.size() - 1; i >= 0; i--) {
            Act a = acts.get(i);
            if (a.left) continue;
            int w = Button.width(kit, a.label, Button.Size.MD, a.icon, false);
            right -= w;
            Button.draw(kit, "act" + i, right, y, w, Button.Size.MD, a.variant, a.label, a.icon, a.active, a.run);
            right -= Tokens.S3;
        }
        for (int i = 0; i < acts.size(); i++) {
            Act a = acts.get(i);
            if (!a.left) continue;
            int w = Button.width(kit, a.label, Button.Size.MD, a.icon, false);
            Button.draw(kit, "act" + i, left, y, w, Button.Size.MD, a.variant, a.label, a.icon, a.active, a.run);
            left += w + Tokens.S3;
        }
    }

    // ------------------------------------------------------------------ body helpers

    /** Old helper: the title is drawn by the frame now. */
    protected void title(DrawContext ctx, String text) {}

    protected void line(DrawContext ctx, String text, int x, int y, int color) {
        UiFont.draw(ctx, textRenderer, text, x, y, color, Role.SMALL, smooth);
    }

    /** Draws {@code text} (BODY) wrapped to the content width from {@code y}; returns the y below it. */
    protected int para(DrawContext ctx, String text, int y, int color) {
        return para(text, bx, y, bw, color, Role.BODY);
    }

    /** {@code text} wrapped to {@code w} in {@code role} from (x, y); returns the y below it. */
    protected int para(String text, int x, int y, int w, int color, Role role) {
        int lh = role == Role.BODY ? 12 : role == Role.SMALL ? 11 : 12;
        for (String para : text.split("\n")) {
            for (String l : kit.wrap(para, role, w, 20)) {
                kit.text(l, x, y, color, role);
                y += lh;
            }
        }
        return y;
    }

    /** A note line with a status icon in front (never colour alone): {@code icon} info / warn / check / error. */
    protected int note(String icon, int color, String text, int y) {
        Icons.draw(kit.ctx, icon, bx, y, 11, color);
        return para(text, bx + 16, y, bw - 16, Tokens.N800, Role.SMALL);
    }

    protected List<String> wrap(String text, int w) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) out.addAll(kit.wrap(para, Role.SMALL, w, 50));
        return out;
    }

    /**
     * A one-line kit text field (sunken, accent ring when focused). {@code masked} hides the text behind dots
     * (secrets). Returns the text.
     */
    protected String field(String id, int x, int y, int w, String placeholder, boolean masked, int max) {
        String s = SearchField.draw(kit, id, x, y, w, placeholder, null, false);
        if (s.length() > max) { s = s.substring(0, max); SearchField.set(kit, id, s); }
        if (masked && !s.isEmpty()) {
            // Paint over the text with dots (the field's editing stays the kit's).
            float cy = y + SearchField.H / 2f;
            int fill = kit.isFocused(id) ? Tokens.N150 : Theme.lerpColor(Tokens.N150, Tokens.N200, 0.5f * kit.anim(id + "#h", kit.hovered(id) ? 1f : 0f));
            Paint.rect(kit.ctx, x + 4, y + 3, x + w - 22, y + SearchField.H - 3, 2, fill);
            int n = Math.min(s.length(), (w - 40) / 6), dx = x + 10;
            for (int i = 0; i < n; i++) Paint.circle(kit.ctx, dx + i * 6, cy, 4, Tokens.N900);
            if (kit.isFocused(id) && (kit.now % 1000 < 550 || Motion.level() == Motion.Level.OFF))
                Theme.fill(kit.ctx, dx + n * 6 - 1, Math.round(cy - 5), dx + n * 6, Math.round(cy + 5), Tokens.A400);
        }
        return s;
    }

    /** A field's label above it. */
    protected void label(String text, int x, int y) { kit.text(text, x, y, Tokens.N800, Role.LABEL); }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (kit.mouseClicked(click.x(), click.y(), click.button())) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return kit.mouseReleased(click.x(), click.y(), click.button()) || super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        return kit.mouseDragged(click.x(), click.y(), click.button()) || super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        return kit.mouseScrolled(x, y, v) || super.mouseScrolled(x, y, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            String f = kit.focused();
            boolean onButton = f != null && f.startsWith("act");
            if (!onButton) {
                Act p = primary();
                if (p != null) { p.run.run(); return true; }
            }
        }
        if (kit.keyPressed(key, input.modifiers())) return true;
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        return kit.charTyped(input.codepoint()) || super.charTyped(input);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
