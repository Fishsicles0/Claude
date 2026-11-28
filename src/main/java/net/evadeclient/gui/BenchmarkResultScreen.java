package net.evadeclient.gui;

import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.BenchmarkScene;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.Locale;

/**
 * The benchmark scene's result card: average FPS with the 1% and 0.1% lows as DISPLAY tabular numbers (neutral: a
 * number is not good or bad by colour), a verdict badge (Smooth / Playable / Struggling, icon and word), what it ran
 * on (graphics, processor, FPS Mode, render scale, resolution, render distance) and a share code that holds it all.
 */
public final class BenchmarkResultScreen extends EvadeDialog {
    private final BenchmarkScene.Result r;

    public BenchmarkResultScreen(Screen parent, BenchmarkScene.Result result) {
        super("Benchmark Result", parent);
        this.r = result;
    }

    public BenchmarkScene.Result result() { return r; }

    @Override protected int prefWidth() { return 440; }
    @Override protected int prefHeight() { return 318; }
    @Override protected String titleIcon() { return "gauge"; }
    @Override protected String heading() { return "Benchmark Scene"; }

    @Override
    protected void init() {
        frame();
        action("Run again", Button.Variant.GHOST, () -> {
            close();
            BenchmarkScene.start(BenchmarkScene.DEFAULT_SECONDS);
        }).icon("reset").left();
        action("Copy code", Button.Variant.SECONDARY, () -> {
            if (client != null) client.keyboard.setClipboard(r.shareCode());
            Notifications.push("Benchmark", "Share code copied", true);
        });
        action("Done", Button.Variant.PRIMARY, this::close);
    }

    @Override
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int x = bx, y = by - 4;
        kit.text(String.format(Locale.ROOT, "%.0f s camera flight · %d frames", r.seconds(), r.frames()), x, y, Tokens.N700, Role.SMALL);
        // Verdict, top-right of the body: icon plus word, from the 1% low (what you feel).
        Badge.Tone tone = r.low1() >= 60 ? Badge.Tone.OK : r.low1() >= 30 ? Badge.Tone.WARN : Badge.Tone.DANGER;
        String verdict = tone == Badge.Tone.OK ? "Smooth" : tone == Badge.Tone.WARN ? "Playable" : "Struggling";
        int vw = Badge.width(kit, verdict) + 14;
        int vx = bx + bw - vw;
        Paint.pill(ctx, vx, y - 3, vx + vw, y + 11, Theme.alpha(Badge.color(tone), 0.14f));
        PanelGlyphs.draw(ctx, tone == Badge.Tone.OK ? "check" : tone == Badge.Tone.WARN ? "warn" : "error", vx + 4, y - 1, 10, Badge.color(tone));
        kit.textCentredY(verdict, vx + 17, y + 4, Badge.color(tone), Role.TINY);

        // Three hero numbers in a sunken strip.
        int sy = y + 18, sh = 64;
        Paint.surface(ctx, bx, sy, bx + bw, sy + sh, Tokens.R_MD, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        int colW = bw / 3;
        stat(ctx, bx, sy, colW, r.avgFps(), "average FPS", true);
        Paint.rect(ctx, bx + colW, sy + 12, bx + colW + 1, sy + sh - 12, 0, Theme.alpha(0xFFFFFFFF, 0.06f));
        stat(ctx, bx + colW, sy, colW, r.low1(), "1% low", false);
        Paint.rect(ctx, bx + 2 * colW, sy + 12, bx + 2 * colW + 1, sy + sh - 12, 0, Theme.alpha(0xFFFFFFFF, 0.06f));
        stat(ctx, bx + colW * 2, sy, colW, r.low01(), "0.1% low", false);

        int ly = sy + sh + 12;
        row(x, ly, "Graphics", r.gpu().isEmpty() ? "Unknown" : r.gpu());
        row(x, ly + 14, "Processor", r.cpu().isEmpty() ? "Unknown" : r.cpu());
        row(x, ly + 28, "Settings", r.mode() + " · Render Scale " + r.renderScale() + " · render distance " + r.renderDistance());
        row(x, ly + 42, "Window", r.resolution() + " · Evade " + r.version());
        // The share code, in a chip.
        int cy0 = ly + 60;
        Paint.rect(ctx, bx, cy0, bx + bw, cy0 + 18, Tokens.R_XS, Tokens.N150);
        kit.textCentredY(kit.ellipsize(r.shareCode(), Role.SMALL, bw - 12), bx + 6, cy0 + 9, Tokens.N700, Role.SMALL);
    }

    private void stat(DrawContext ctx, int x, int y, int w, double value, String label, boolean hero) {
        String v = String.format(Locale.ROOT, "%.0f", value);
        Role role = hero ? Role.DISPLAY : Role.HEADLINE;
        int vw = UiFont.tabularWidth(textRenderer, v, role, smooth);
        int top = y + (hero ? 8 : 14);
        UiFont.drawTabular(ctx, textRenderer, v, x + (w - vw) / 2, top, hero ? Tokens.N950 : Tokens.N900, role, smooth);
        kit.text(label, x + (w - kit.width(label, Role.SMALL)) / 2, y + 46, Tokens.N700, Role.SMALL);
    }

    private void row(int x, int y, String label, String value) {
        kit.text(label, x, y, Tokens.N700, Role.SMALL);
        int vx = x + 70;
        kit.text(kit.ellipsize(value, Role.SMALL, bx + bw - vx), vx, y, Tokens.N900, Role.SMALL);
    }
}
