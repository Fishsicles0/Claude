package net.evadeclient.module;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/** A HUD element that shows one or more lines of text, e.g. FPS, CPS, ping, coordinates. */
public abstract class TextHudModule extends HudElementModule {
    public final ColorSetting color = add(new ColorSetting("Text Color", 0xFFFFFFFF));
    public final BooleanSetting brackets = add(new BooleanSetting("Brackets", false));

    protected TextHudModule(String name, String description, int x, int y) {
        super(name, description, x, y);
    }

    protected TextHudModule(String name, String description, Category category, int x, int y) {
        super(name, description, category, x, y);
    }

    /** Lines to show; return an empty list to hide the element. */
    protected abstract List<String> lines(boolean preview);

    // Text, widths and size are rebuilt once per client tick (20/s) instead of every frame.
    private List<String> cachedLines;
    private int[] cachedWidths;
    private long cachedTick = -1;
    /** Test hook: rebuild text every frame like before (to measure what the per-tick cache saves). */
    public static boolean NO_CACHE = false;

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        long tick = net.evadeclient.EvadeClient.ticks;
        if (preview || NO_CACHE || cachedLines == null || cachedTick != tick) {
            List<String> lines = lines(preview);
            int[] widths = new int[lines.size()];
            int w = 0;
            for (int i = 0; i < lines.size(); i++) {
                String l = brackets.get() ? "[" + lines.get(i) + "]" : lines.get(i);
                lines.set(i, l);
                widths[i] = tr.getWidth(l);
                w = Math.max(w, widths[i]);
            }
            width = lines.isEmpty() ? 40 : Math.max(40, w + 8);
            height = lines.isEmpty() ? 13 : lines.size() * 10 + 4;
            if (preview) { draw(ctx, left, top, lines, widths); return; }
            cachedLines = lines; cachedWidths = widths; cachedTick = tick;
        }
        draw(ctx, left, top, cachedLines, cachedWidths);
    }

    private void draw(DrawContext ctx, int left, int top, List<String> lines, int[] widths) {
        if (lines.isEmpty()) return;
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int y = top + 3;
        int c = color.get();
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(tr, lines.get(i), left + (width - widths[i]) / 2, y, c);
            y += 10;
        }
    }
}
