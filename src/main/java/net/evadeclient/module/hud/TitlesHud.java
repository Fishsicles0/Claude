package net.evadeclient.module.hud;

import net.evadeclient.mixin.InGameHudTextAccessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Vanilla's big title and subtitle, movable and scalable in the HUD editor. Off, they stay where vanilla puts them. */
public class TitlesHud extends VanillaTextHud {
    /** Vanilla's title box: from 48 GUI pixels above the screen's middle to 32 below (title at 4x, subtitle at 2x). */
    private static final int ABOVE = 48, HEIGHT = 80;

    public TitlesHud() {
        super("Titles", "Move and resize the big title and subtitle text servers show.", CENTER, 13);
    }

    @Override
    protected int[] guiSize(boolean sample) {
        var tr = mc.textRenderer;
        Text title, sub;
        if (sample) {
            title = Text.literal("Title");
            sub = Text.literal("Subtitle");
        } else {
            if (!(mc.inGameHud instanceof InGameHudTextAccessor a) || a.evade$title() == null || a.evade$titleRemainTicks() <= 0) return null;
            title = a.evade$title();
            sub = a.evade$subtitle();
        }
        int w = (tr.getWidth(title) + 4) * 4;
        if (sub != null) w = Math.max(w, (tr.getWidth(sub) + 4) * 2);
        return new int[]{w, HEIGHT};
    }

    @Override protected float vanillaX(int sw, int sh, int w, int h) { return sw / 2 - w / 2f; }

    @Override protected float vanillaY(int sw, int sh, int w, int h) { return sh / 2 - ABOVE; }

    @Override
    protected void drawSample(DrawContext ctx, int w, int h) {
        var tr = mc.textRenderer;
        var m = ctx.getMatrices();
        int cx = w / 2;
        m.pushMatrix();
        m.translate(cx, ABOVE);
        m.pushMatrix();
        m.scale(4f, 4f);
        int tw = tr.getWidth("Title");
        ctx.drawTextWithBackground(tr, Text.literal("Title"), -tw / 2, -10, tw, 0xFFFFFFFF);
        m.popMatrix();
        m.scale(2f, 2f);
        int sw = tr.getWidth("Subtitle");
        ctx.drawTextWithBackground(tr, Text.literal("Subtitle"), -sw / 2, 5, sw, 0xFFFFFFFF);
        m.popMatrix();
    }
}
