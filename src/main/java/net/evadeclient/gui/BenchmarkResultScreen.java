package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.BenchmarkScene;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * The benchmark scene's result card: average FPS with the 1% and 0.1% lows, what it ran on (graphics, processor,
 * FPS Mode, render scale, resolution, render distance) and a share code that holds all of it.
 */
public final class BenchmarkResultScreen extends Screen {
    private final Screen parent;
    private final BenchmarkScene.Result r;
    private int px, py, pw, ph;

    public BenchmarkResultScreen(Screen parent, BenchmarkScene.Result result) {
        super(Text.literal("Benchmark Result"));
        this.parent = parent;
        this.r = result;
    }

    public BenchmarkScene.Result result() { return r; }

    @Override
    protected void init() {
        pw = Math.min(360, width - 20);
        ph = 200;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int bw = (pw - 28 - 2 * 4) / 3, by = py + ph - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy code"), b -> {
            if (client != null) client.keyboard.setClipboard(r.shareCode());
            Notifications.push("Benchmark", "Share code copied", true);
        }).dimensions(px + 14, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Run again"), b -> {
            close();
            BenchmarkScene.start(BenchmarkScene.DEFAULT_SECONDS);
        }).dimensions(px + 14 + bw + 4, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(px + 14 + (bw + 4) * 2, by, bw, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, 0xD0000000);
        Theme.shadow(ctx, Theme.SH_WINDOW, px, py, px + pw, py + ph, 0x90000000);
        Theme.round(ctx, px, py, px + pw, py + ph, Theme.BG, Theme.R_WIN);
        Theme.stroke(ctx, px, py, px + pw, py + ph, Theme.BORDER, Theme.R_WIN);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        boolean smooth = s == null || s.smoothFont.get();
        int x = px + 14, y = py + 12;
        UiFont.draw(ctx, textRenderer, "Benchmark Scene", x, y, Theme.TEXT, Role.TITLE, smooth);
        UiFont.draw(ctx, textRenderer, String.format(Locale.ROOT, "%.0f s camera flight, %d frames", r.seconds(), r.frames()),
                x, y + 16, Theme.TEXT_DIM, Role.SMALL, smooth);

        // Three big numbers.
        int colW = (pw - 28) / 3, ny = y + 36;
        stat(ctx, x, ny, colW, r.avgFps(), "average FPS", Theme.RED_BRIGHT, smooth);
        stat(ctx, x + colW, ny, colW, r.low1(), "1% low", Theme.TEXT, smooth);
        stat(ctx, x + colW * 2, ny, colW, r.low01(), "0.1% low", Theme.TEXT, smooth);

        int ly = ny + 40;
        line(ctx, x, ly, "Graphics", r.gpu().isEmpty() ? "Unknown" : r.gpu(), smooth);
        line(ctx, x, ly + 12, "Processor", r.cpu().isEmpty() ? "Unknown" : r.cpu(), smooth);
        line(ctx, x, ly + 24, "Settings", r.mode() + ", Render Scale " + r.renderScale() + ", render distance " + r.renderDistance(), smooth);
        line(ctx, x, ly + 36, "Window", r.resolution() + ", Evade " + r.version(), smooth);
        String code = r.shareCode();
        if (textRenderer.getWidth(code) > pw - 28) code = textRenderer.trimToWidth(code, pw - 28 - textRenderer.getWidth("...")) + "...";
        ctx.drawText(textRenderer, code, x, ly + 52, Theme.TEXT_FAINT, false);
    }

    private void stat(DrawContext ctx, int x, int y, int w, double value, String label, int color, boolean smooth) {
        String v = String.format(Locale.ROOT, "%.0f", value);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(2f, 2f);
        ctx.drawText(textRenderer, v, 0, 0, color, false);
        m.popMatrix();
        UiFont.draw(ctx, textRenderer, label, x, y + 20, Theme.TEXT_DIM, Role.SMALL, smooth);
    }

    private void line(DrawContext ctx, int x, int y, String label, String value, boolean smooth) {
        UiFont.draw(ctx, textRenderer, label, x, y, Theme.TEXT_FAINT, Role.SMALL, smooth);
        int vx = x + 62, room = px + pw - 14 - vx;
        String v = textRenderer.getWidth(value) > room ? textRenderer.trimToWidth(value, room - textRenderer.getWidth("...")) + "..." : value;
        UiFont.draw(ctx, textRenderer, v, vx, y, Theme.TEXT, Role.SMALL, smooth);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
