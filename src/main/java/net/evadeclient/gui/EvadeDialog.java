package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A small centred window in the menu's colours (title, wrapped text, vanilla buttons and fields), shared by the
 * v2.5 social and media dialogs: Open to Friends, player cards, cloud backup, Discover and server prompts.
 */
public abstract class EvadeDialog extends Screen {
    protected final Screen parent;
    protected int px, py, pw, ph;
    protected boolean smooth = true;

    protected EvadeDialog(String title, Screen parent) {
        super(Text.literal(title));
        this.parent = parent;
    }

    /** Window size wanted (clamped to the screen). */
    protected int prefWidth() { return 340; }
    protected abstract int prefHeight();

    /** Lays out the window; subclasses call this first in {@link #init} and then add their widgets. */
    protected void frame() {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        smooth = s == null || s.smoothFont.get();
        pw = Math.min(prefWidth(), width - 20);
        ph = Math.min(prefHeight(), height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    protected ButtonWidget button(String label, int x, int y, int w, ButtonWidget.PressAction action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        if (client != null && client.world == null && (s == null || s.blur.get())) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, 0xD0000000);
        Theme.shadow(ctx, Theme.SH_WINDOW, px, py, px + pw, py + ph, 0x90000000);
        Theme.round(ctx, px, py, px + pw, py + ph, Theme.BG, Theme.R_WIN);
        Theme.stroke(ctx, px, py, px + pw, py + ph, Theme.BORDER, Theme.R_WIN);
    }

    protected void title(DrawContext ctx, String text) {
        UiFont.draw(ctx, textRenderer, text, px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
    }

    protected void line(DrawContext ctx, String text, int x, int y, int color) {
        UiFont.draw(ctx, textRenderer, text, x, y, color, Role.SMALL, smooth);
    }

    /** Draws {@code text} wrapped to the window's inner width from {@code y}; returns the y below it. */
    protected int para(DrawContext ctx, String text, int y, int color) {
        for (String l : wrap(text, pw - 28)) {
            line(ctx, l, px + 14, y, color);
            y += 11;
        }
        return y;
    }

    protected List<String> wrap(String text, int w) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String next = cur.isEmpty() ? word : cur + " " + word;
                if (!cur.isEmpty() && UiFont.width(textRenderer, next, Role.SMALL, smooth) > w) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    cur.append(word);
                } else {
                    cur.setLength(0);
                    cur.append(next);
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
