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

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        List<String> lines = lines(preview);
        if (lines.isEmpty()) { width = 40; height = 13; return; }
        var tr = mc.textRenderer;
        int w = 0;
        for (int i = 0; i < lines.size(); i++) {
            String l = brackets.get() ? "[" + lines.get(i) + "]" : lines.get(i);
            lines.set(i, l);
            w = Math.max(w, tr.getWidth(l));
        }
        width = Math.max(40, w + 8);
        height = lines.size() * 10 + 4;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int y = top + 3;
        for (String l : lines) {
            int tx = left + (width - tr.getWidth(l)) / 2;
            ctx.drawTextWithShadow(tr, l, tx, y, color.get());
            y += 10;
        }
    }
}
