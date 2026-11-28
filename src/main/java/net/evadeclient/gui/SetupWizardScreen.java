package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.config.MenuState;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.gui.kit.Surface;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * First-run setup, in three short steps that can all be skipped: pick a play style (switches on a fitting set of mods
 * and HUD widgets), pick or measure the FPS Mode for this PC, then a few tips about the menu. Shown once by
 * {@link FirstRun}; {@code .setup} opens it again.
 *
 * <p>Layout (spec 7.9): a stepper with a slanted progress fill in the header, a DISPLAY question and a BODY line,
 * large option cards, and one consistent footer (Skip · Back · Continue). Steps slide along a horizontal axis.
 */
public final class SetupWizardScreen extends EvadePanelScreen {
    private int step;
    private FirstRun.Style chosen;
    private String tuneStatus;
    /** When the step last changed and in which direction (+1 forward), for the slide. */
    private long stepAt;
    private int stepDir = 1;

    private static final String[] STEPS = {"Style", "Performance", "Tips"};

    /** Tips: icon, title, text. */
    private static final String[][] TIPS = {
            {"mods", "Open the menu", "Right Shift opens Evade any time (change it under Settings). Ctrl+F jumps to search."},
            {"settings", "Toggle and tune", "Click a mod to switch it on; right-click or the gear opens its settings and keybind."},
            {"star", "Pin favourites", "Click the star next to a mod's name to keep it on the Pinned page."},
            {"keyboard", "Chat commands", "Type .help in chat. Tab completes commands, mod and setting names."},
            {"edit", "Move the HUD", "Edit HUD Layout in the sidebar lets you drag every widget where you want it."},
    };

    /** The FPS Mode cards: name, what it does, gauge needle (0..1). */
    private static final String[][] MODES = {
            {"Max FPS", "Every trick for frame rate. Best for older laptops and busy servers.", "0.92"},
            {"Balanced", "Smooth and still good looking. The right start for most PCs.", "0.55"},
            {"Quality", "Keeps the eye candy. For a strong graphics card.", "0.18"},
    };

    public SetupWizardScreen(Screen parent) {
        super("Welcome to Evade", parent);
    }

    /** Test hooks. */
    public int stepForTest() { return step; }
    public void chooseForTest(String style) { chosen = FirstRun.style(style); }
    public void nextForTest() { next(); }

    @Override protected int prefWidth() { return 780; }
    @Override protected int prefHeight() { return 480; }
    @Override protected String subtitle() { return "Three quick steps · skip any of them, change anything later"; }

    @Override
    public void close() {
        MenuState.setSetupDone(true);
        super.close();
    }

    // ------------------------------------------------------------------ chrome: stepper and footer

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        // ●━━━━○────○ with labels under: the fill glides to the current step and leans with the Evade slant.
        int avail = right - (wx + Surface.PAD + 34 + width(title.getString(), Role.HEADLINE)) - 24;
        if (avail < 220) {
            // Narrow: a compact chip instead of the full stepper.
            String t = "Step " + (step + 1) + " of " + STEPS.length + " · " + STEPS[step];
            int cw0 = width(t, Role.SMALL) + 16;
            Paint.pill(ctx, right - cw0, y + 3, right, y + 17, Tokens.N250);
            kit.textCentredY(t, right - cw0 + 8, y + 10, Tokens.N900, Role.SMALL);
            headerReach(right - cw0);
            return;
        }
        int w = Math.min(300, avail), x1 = right - w, cyy = y + 4;
        int seg = (w - 40) / (STEPS.length - 1);
        float prog = kit.anim("wiz#step", step, Motion.SMOOTH);
        int sx = x1 + 20;
        Paint.pill(ctx, sx, cyy - 1.5f, sx + seg * (STEPS.length - 1), cyy + 1.5f, Tokens.N250);
        float fx = sx + seg * Math.max(0, prog);
        if (fx > sx + 1) {
            Paint.hgradient(ctx, sx, cyy - 1.5f, fx, cyy + 1.5f, 1.5f, Tokens.A700, Tokens.A500);
            Paint.glow(kit, fx - 3, cyy - 1.5f, fx + 1, cyy + 1.5f, 1.5f, 3, Theme.alpha(Tokens.A500, 0.5f));
        }
        for (int i = 0; i < STEPS.length; i++) {
            float px = sx + seg * i;
            boolean done = i < step, cur = i == step;
            if (cur || done) {
                Paint.circle(ctx, px, cyy, 14, cur ? Tokens.A600 : Tokens.A800);
                if (cur && Paint.gpu()) UiShape.shape(px - 7, cyy - 7, px + 7, cyy + 7).radius(7).fill(0x00000000).rim(0.25f).draw(ctx);
            } else {
                Paint.circle(ctx, px, cyy, 14, Tokens.N200);
                Paint.ring(ctx, px - 7, cyy - 7, px + 7, cyy + 7, 7, Tokens.N400, 1);
            }
            if (done) Icons.draw(ctx, "check", Math.round(px - 4), Math.round(cyy - 4), 8, Tokens.ON_ACCENT);
            else {
                String n = String.valueOf(i + 1);
                kit.textCentredY(n, Math.round(px - width(n, Role.TINY) / 2f), cyy, cur ? Tokens.ON_ACCENT : Tokens.N700, Role.TINY);
            }
            String l = STEPS[i];
            kit.textCentredY(l, Math.round(px - width(l, Role.SMALL) / 2f), cyy + 16, cur ? Tokens.N950 : Tokens.N700, Role.SMALL);
        }
        headerReach(x1);
    }

    @Override protected int footerHeight() { return 48; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        int by = y + (48 - Button.Size.LG.h) / 2;
        drawButton("skip", "Skip setup", null, x1 - 6, by, 0, Button.Size.LG, GHOST, true, b -> close());
        String nextLabel = step == STEPS.length - 1 ? "Finish" : "Continue";
        int nw = Button.width(kit, nextLabel, Button.Size.LG, null, true) + 8;
        int nx = x2 - nw;
        Button.draw(kit, "next", nx, by, nw, Button.Size.LG, Button.Variant.PRIMARY, nextLabel, null, true, true, this::next);
        if (step > 0) {
            int bw = Button.width(kit, "Back", Button.Size.LG, "chevron_l", false);
            drawButton("prev", "Back", "chevron_l", nx - Tokens.S3 - bw, by, bw, Button.Size.LG, SECONDARY, true, b -> back());
        }
    }

    private void next() {
        if (step == 0 && chosen != null) FirstRun.apply(chosen);
        if (step >= STEPS.length - 1) { close(); return; }
        go(step + 1);
    }

    private void back() { if (step > 0) go(step - 1); }

    private void go(int s) {
        stepDir = s > step ? 1 : -1;
        step = s;
        stepAt = System.currentTimeMillis();
        scrollTarget = 0;
        restartEntrance();
    }

    // ------------------------------------------------------------------ content

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        // Shared axis: the new step slides in from the side it comes from and fades up.
        float t = Motion.clamp01((System.currentTimeMillis() - stepAt) / (float) Math.max(1, Motion.duration(Motion.DUR_BASE)));
        float e = stepAt == 0 || Motion.level() == Motion.Level.OFF ? 1f : Motion.standard(t);
        float f = Theme.fade;
        Theme.fade = f * e;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            if (Motion.decorative() && e < 1f) m.translate(Theme.snap(ctx, (1 - e) * 24 * stepDir), 0);
            return switch (step) {
                case 0 -> renderStyles(ctx, top, mx, my);
                case 1 -> renderTune(ctx, top, mx, my);
                default -> renderTips(ctx, top);
            };
        } finally {
            m.popMatrix();
            Theme.fade = f;
        }
    }

    /** The DISPLAY question and its BODY line; returns the y below. */
    private int hero(String q, String line, int y) {
        kit.text(q, cx, y + 4, Tokens.N950, Role.DISPLAY);
        kit.text(line, cx, y + 40, Tokens.N800, Role.BODY);
        return y + 64;
    }

    private static String styleIcon(String name) {
        return switch (name) {
            case "PvP" -> "controls";
            case "Survival" -> "map";
            case "Hypixel" -> "server";
            case "Building" -> "schematic";
            default -> "hud";
        };
    }

    private int renderStyles(DrawContext ctx, int top, int mx, int my) {
        int y = hero("How do you play?", "Pick one to switch on a set of mods. You can change anything later.", top);
        int n = FirstRun.STYLES.size();
        int cols = cw >= 640 ? n : cw >= 420 ? 3 : 2, gap = 10;
        int cardW = (cw - gap * (cols - 1)) / cols, cardH = 156;
        int i = 0;
        for (FirstRun.Style s : FirstRun.STYLES) {
            int x = cx + (i % cols) * (cardW + gap), cy0 = y + (i / cols) * (cardH + gap);
            float a = enter(i), fd = Theme.fade;
            Theme.fade = fd * a;
            int yy = cy0 + Math.round((1 - a) * 10);
            boolean sel = s == chosen;
            String id = "style:" + s.name();
            try {
                Surface.card(kit, id, x, yy, x + cardW, yy + cardH, false, true, () -> chosen = (chosen == s ? null : s));
                picked(x, yy, x + cardW, yy + cardH, kit.anim(id + "#pick", sel ? 1f : 0f, Motion.SMOOTH));
                // Icon in a circle, lit from below by the accent when picked.
                float icx = x + 36, icy = yy + 36;
                float lit = kit.anim(id + "#sel", sel ? 1f : 0f, Motion.SMOOTH);
                if (lit > 0.01f) Paint.glow(kit, icx - 22, icy - 22, icx + 22, icy + 22, 22, 9, Theme.alpha(Tokens.A600, 0.32f * lit));
                Paint.circle(ctx, icx, icy, 44, Theme.lerpColor(Tokens.N250, Tokens.A900, lit));
                Paint.ring(ctx, icx - 22, icy - 22, icx + 22, icy + 22, 22, Theme.alpha(lit > 0.5f ? Tokens.A500 : 0xFFFFFFFF, lit > 0.5f ? 0.6f : 0.07f), 1);
                Icons.draw(ctx, styleIcon(s.name()), Math.round(icx - 10), Math.round(icy - 10), 20, Theme.lerpColor(Tokens.N900, Tokens.A300, lit));
                if (sel) {
                    // The check badge, top-right.
                    float bx = x + cardW - 16, by = yy + 16;
                    Paint.circle(ctx, bx, by, 16, Tokens.A600);
                    Icons.draw(ctx, "check", Math.round(bx - 5), Math.round(by - 5), 10, Tokens.ON_ACCENT);
                }
                kit.text(s.name(), x + 14, yy + 66, Tokens.N950, Role.TITLE);
                List<String> lines = kit.wrap(s.blurb(), Role.SMALL, cardW - 28, 6);
                for (int l = 0; l < lines.size(); l++) kit.text(lines.get(l), x + 14, yy + 88 + l * 11, Tokens.N700, Role.SMALL);
            } finally {
                Surface.endCard(kit);
                Theme.fade = fd;
            }
            i++;
        }
        int rows = (n + cols - 1) / cols;
        y += rows * (cardH + gap) + 6;
        String note = chosen == null ? "Nothing picked: Continue keeps your mods as they are." : chosen.name() + " is switched on when you press Continue.";
        Icons.draw(ctx, chosen == null ? "info" : "check", cx, y - 1, 12, chosen == null ? Tokens.N600 : Tokens.OK);
        kit.text(note, cx + 18, y, Tokens.N700, Role.SMALL);
        return y - top + 20;
    }

    private int renderTune(DrawContext ctx, int top, int mx, int my) {
        Performance p = EvadeClient.modules.get(Performance.class);
        String mode = p == null ? "" : p.isEnabled() ? p.fpsMode.get() : "";
        int y = hero("How should it run?", "Pick an FPS Mode, or let Evade measure this PC and pick for you.", top);
        String pick = FirstRun.lastPick();
        int gap = 10, tuneW = 196;
        int cardW = (cw - tuneW - gap * 3) / 3, cardH = 150;
        for (int i = 0; i < MODES.length; i++) {
            String name = MODES[i][0];
            int x = cx + i * (cardW + gap);
            float a = enter(i), fd = Theme.fade;
            Theme.fade = fd * a;
            int yy = y + Math.round((1 - a) * 10);
            boolean sel = name.equals(mode);
            String id = "mode:" + name;
            try {
                Surface.card(kit, id, x, yy, x + cardW, yy + cardH, false, true, () -> pickMode(p, name));
                picked(x, yy, x + cardW, yy + cardH, kit.anim(id + "#pick", sel ? 1f : 0f, Motion.SMOOTH));
                gauge(ctx, x + cardW / 2f, yy + 44, 26, Float.parseFloat(MODES[i][2]), sel);
                kit.text(name, x + 14, yy + 78, Tokens.N950, Role.TITLE);
                if (name.equals(pick)) Badge(x + 14 + width(name, Role.TITLE) + Tokens.S3, yy + 79);
                List<String> lines = kit.wrap(MODES[i][1], Role.SMALL, cardW - 28, 3);
                for (int l = 0; l < lines.size(); l++) kit.text(lines.get(l), x + 14, yy + 98 + l * 11, Tokens.N700, Role.SMALL);
                if (sel) {
                    float bx = x + cardW - 16, by = yy + 16;
                    Paint.circle(ctx, bx, by, 16, Tokens.A600);
                    Icons.draw(ctx, "check", Math.round(bx - 5), Math.round(by - 5), 10, Tokens.ON_ACCENT);
                }
            } finally {
                Surface.endCard(kit);
                Theme.fade = fd;
            }
        }
        // The tune panel: measure for 10 seconds and highlight the card it recommends.
        int tx = cx + 3 * (cardW + gap) + gap, tw = cx + cw - tx;
        Paint.surface(ctx, tx, y, tx + tw, y + cardH, Tokens.R_MD, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        boolean tuning = FirstRun.tuning();
        float rcx = tx + tw / 2f, rcy = y + 40;
        if (tuning) Progress.circle(kit, rcx, rcy, 20, Float.NaN);
        else {
            Paint.circle(ctx, rcx, rcy, 44, Tokens.N200);
            PanelGlyphs.draw(ctx, "gauge", rcx - 11, rcy - 11, 22, pick != null ? Tokens.A400 : Tokens.N800);
        }
        int bw = Button.width(kit, tuning ? "Measuring…" : "Tune for my PC", Button.Size.MD, null, false);
        drawButton("tune", tuning ? "Measuring…" : "Tune for my PC", null, Math.round(rcx - bw / 2f), y + 72, bw, Button.Size.MD, SECONDARY, !tuning, b -> {
            tuneStatus = FirstRun.startTune();
            // In a world, let the run see the game: close the setup.
            if (client != null && client.world != null && FirstRun.tuning()) close();
        });
        String status = tuneStatus != null ? tuneStatus : pick != null ? "Last tune picked " + pick + "." : MenuState.tunePending()
                ? "Waiting for your next world." : "10 seconds · nothing is sent anywhere";
        List<String> sl = kit.wrap(status, Role.SMALL, tw - 20, 3);
        for (int l = 0; l < sl.size(); l++)
            kit.text(sl.get(l), Math.round(rcx - width(sl.get(l), Role.SMALL) / 2f), y + 104 + l * 11, Tokens.N700, Role.SMALL);
        y += cardH + Tokens.S6;
        return y - top + renderChanges(ctx, y, mx, my, p);
    }

    /**
     * The picked look for a tall card (fades with {@code a}): the accent surface entering from the left and a short
     * lit edge with a soft glow (a full-height slant would lean out of a tall card).
     */
    private void picked(int x1, int y1, int x2, int y2, float a) {
        if (a < 0.01f) return;
        Paint.hgradient(kit.ctx, x1, y1, x2, y2, Tokens.R_MD, Theme.alpha(Tokens.A900, 0.5f * a), Theme.alpha(Tokens.A900, 0f));
        Paint.ring(kit.ctx, x1, y1, x2, y2, Tokens.R_MD, Theme.alpha(Tokens.A500, 0.45f * a), 1);
        int m = (y1 + y2) / 2;
        Paint.pill(kit.ctx, x1 + 1, m - 22, x1 + 3, m + 22, Theme.alpha(Tokens.A500, a));
        Paint.glow(kit, x1 + 1, m - 22, x1 + 3, m + 22, 1, 5, Theme.alpha(Tokens.A600, 0.4f * a));
    }

    private void Badge(int x, int y) {
        net.evadeclient.gui.kit.Badge.draw(kit, x, y, "Recommended", net.evadeclient.gui.kit.Badge.Tone.OK);
    }

    private void pickMode(Performance p, String name) {
        if (p == null) return;
        if (!p.isEnabled()) p.setEnabled(true);
        p.fpsMode.set(name);
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /** A small speedometer: a track arc, the filled part to the needle and the needle (0 = left, 1 = right). */
    private void gauge(DrawContext ctx, float gx, float gy, float r, float v, boolean on) {
        if (!Paint.gpu()) { Icons.draw(ctx, "controls", Math.round(gx - 10), Math.round(gy - 10), 20, on ? Tokens.A400 : Tokens.N700); return; }
        float th = 3;
        UiShape.arc(ctx, gx, gy, r, th, 240, 240, Tokens.N250);
        UiShape.arc(ctx, gx, gy, r, th, 240, 240 * v, on ? Tokens.A500 : Tokens.N600);
        double ang = Math.toRadians(240 + 240 * v - 90);
        float nx = gx + (float) Math.cos(ang) * (r - 7), ny = gy + (float) Math.sin(ang) * (r - 7);
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(gx, gy);
            m.rotate((float) Math.atan2(ny - gy, nx - gx));
            float len = (float) Math.hypot(nx - gx, ny - gy);
            Paint.rect(ctx, 0, -1, len, 1, 1, on ? Tokens.N950 : Tokens.N800);
        } finally {
            m.popMatrix();
        }
        Paint.circle(ctx, gx, gy, 6, on ? Tokens.A600 : Tokens.N600);
        for (int k = 0; k <= 4; k++) { // ticks
            double ta = Math.toRadians(240 + 60 * k - 90);
            Paint.circle(ctx, gx + (float) Math.cos(ta) * (r + 4), gy + (float) Math.sin(ta) * (r + 4), 1.6f, Tokens.N500);
        }
    }

    /**
     * What Performance changed in the player's own video settings, so nothing changes silently, with a button that
     * hands them all back (culling and the idle frame caps stay: they change no settings). Returns the height used.
     */
    private int renderChanges(DrawContext ctx, int top, int mx, int my, Performance p) {
        if (p == null || !p.isEnabled()) return 0;
        List<String> changes = p.videoChangesSummary();
        int y = top;
        y = section("YOUR VIDEO SETTINGS", changes.isEmpty() ? null : String.valueOf(changes.size()), cx, y);
        kit.text(changes.isEmpty() ? "Performance hasn't changed any of your video settings."
                : "Performance changed these video settings of yours:", cx, y, Tokens.N900, Role.BODY);
        if (!changes.isEmpty()) {
            int kw = Button.width(kit, "Keep my own settings", Button.Size.SM, "reset", false);
            drawButton("keepvideo", "Keep my own settings", "reset", cx + cw - kw, y - 4, kw, Button.Size.SM, GHOST, true, b -> p.keepMyVideoSettings());
        }
        y += 16;
        for (String c : changes) {
            Paint.circle(ctx, cx + 4, y + 4, 3, Tokens.N600);
            kit.text(c, cx + 12, y, Tokens.N800, Role.SMALL);
            y += 12;
        }
        y += 2;
        for (String line : kit.wrap("It also skips drawing far mobs and some particles, and lowers the frame rate while you're "
                + "tabbed out or AFK. Turn Performance off for none of it.", Role.SMALL, cw, 4)) {
            kit.text(line, cx, y, Tokens.N700, Role.SMALL);
            y += 11;
        }
        return y - top + 6;
    }

    /** Test hook: what the setup lists as changed by Performance. */
    public static List<String> changesForTest() {
        Performance p = EvadeClient.modules.get(Performance.class);
        return p == null ? List.of() : p.videoChangesSummary();
    }

    private int renderTips(DrawContext ctx, int top) {
        int y = hero("You're set.", "A few things worth knowing before you play.", top);
        int cols = cw >= 600 ? 3 : 2, gap = 10;
        int cardW = (cw - gap * (cols - 1)) / cols, cardH = 92;
        for (int i = 0; i < TIPS.length; i++) {
            String[] tip = TIPS[i];
            int x = cx + (i % cols) * (cardW + gap), yy = y + (i / cols) * (cardH + gap);
            float a = enter(i), fd = Theme.fade;
            Theme.fade = fd * a;
            yy += Math.round((1 - a) * 10);
            try {
                Paint.surface(ctx, x, yy, x + cardW, yy + cardH, Tokens.R_MD, Tokens.E2, 0, Float.NaN);
                Paint.rect(ctx, x + 12, yy + 12, x + 40, yy + 40, Tokens.R_SM, Tokens.N250);
                Icons.draw(ctx, tip[0], x + 19, yy + 19, 14, Tokens.N900);
                // Step number, tabular, faint, top-right: the order to read them in.
                String n = (i < 9 ? "0" : "") + (i + 1);
                UiFont.drawTabular(ctx, textRenderer, n, x + cardW - 12 - UiFont.tabularWidth(textRenderer, n, Role.SMALL, smooth), yy + 12, Tokens.N600, Role.SMALL, smooth);
                kit.textCentredY(tip[1], x + 50, yy + 26, Tokens.N950, Role.STRONG);
                List<String> lines = kit.wrap(tip[2], Role.SMALL, cardW - 24, 3);
                for (int l = 0; l < lines.size(); l++) kit.text(lines.get(l), x + 12, yy + 50 + l * 11, Tokens.N700, Role.SMALL);
            } finally {
                Theme.fade = fd;
            }
        }
        int rows = (TIPS.length + cols - 1) / cols;
        return y - top + rows * (cardH + gap);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            String f = kit.focused();
            if (f == null || !(f.equals("skip") || f.equals("prev") || f.equals("tune") || f.equals("keepvideo"))) { next(); return true; }
        }
        return super.keyPressed(input);
    }
}
