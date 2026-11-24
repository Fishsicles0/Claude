package net.evadeclient.module.hud;

import net.evadeclient.mixin.InGameHudTextAccessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Vanilla's action bar message (the line above the hotbar: "Now playing", server messages), movable and scalable in
 * the HUD editor. Off, it stays where vanilla puts it.
 */
public class ActionBarHud extends VanillaTextHud {
    /** Vanilla centres the message on 68 GUI pixels above the bottom. */
    private static final int FROM_BOTTOM = 68, HEIGHT = 16;
    private static final String SAMPLE = "Action bar message";

    public ActionBarHud() {
        super("Action Bar", "Move and resize the action bar message above the hotbar.", CENTER, 13);
    }

    @Override
    protected int[] guiSize(boolean sample) {
        Text t;
        if (sample) {
            t = Text.literal(SAMPLE);
        } else {
            if (!(mc.inGameHud instanceof InGameHudTextAccessor a) || a.evade$overlayMessage() == null || a.evade$overlayRemaining() <= 0) return null;
            t = a.evade$overlayMessage();
        }
        return new int[]{mc.textRenderer.getWidth(t) + 8, HEIGHT};
    }

    @Override protected float vanillaX(int sw, int sh, int w, int h) { return sw / 2 - w / 2f; }

    @Override protected float vanillaY(int sw, int sh, int w, int h) { return sh - FROM_BOTTOM - HEIGHT / 2f; }

    @Override
    protected void drawSample(DrawContext ctx, int w, int h) {
        var tr = mc.textRenderer;
        int tw = tr.getWidth(SAMPLE);
        ctx.drawTextWithBackground(tr, Text.literal(SAMPLE), (w - tw) / 2, h / 2 - 4, tw, 0xFFFFFFFF);
    }
}
