package net.evadeclient.module.hud;

import net.evadeclient.mixin.InGameHudTextAccessor;
import net.evadeclient.mixin.SubtitleEntryAccessor;
import net.evadeclient.mixin.SubtitlesHudAccessor;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Vanilla's sound subtitles (turned on in Accessibility settings), movable and scalable in the HUD editor. Off, they
 * stay in the bottom-right corner where vanilla puts them. Shows exactly what vanilla lists, nothing more.
 */
public class SoundSubtitlesHud extends VanillaTextHud {
    /** Vanilla's list ends 30 GUI pixels above the bottom, 1 from the right; each line is 10 tall. */
    private static final int BOTTOM_GAP = 30, LINE = 10;
    private static final String[] SAMPLE = {"Footsteps", "Door creaks"};

    public SoundSubtitlesHud() {
        super("Subtitles", "Move and resize the sound subtitles (turn them on in Accessibility settings).", -1, -13);
    }

    private int arrows() {
        var tr = mc.textRenderer;
        return tr.getWidth("<") + tr.getWidth(" ") + tr.getWidth(">") + tr.getWidth(" ");
    }

    @Override
    protected int[] guiSize(boolean sample) {
        var tr = mc.textRenderer;
        int max = 0, n;
        if (sample) {
            for (String s : SAMPLE) max = Math.max(max, tr.getWidth(s));
            n = SAMPLE.length;
        } else {
            if (!mc.options.getShowSubtitles().getValue()) return null;
            if (!(mc.inGameHud instanceof InGameHudTextAccessor a)) return null;
            List<Object> entries = ((SubtitlesHudAccessor) a.evade$subtitlesHud()).evade$entries();
            if (entries.isEmpty()) return null;
            n = entries.size();
            for (Object e : entries) max = Math.max(max, tr.getWidth(((SubtitleEntryAccessor) e).evade$text()));
        }
        int half = (max + arrows()) / 2;
        return new int[]{half * 2 + 2, n * LINE};
    }

    @Override protected float vanillaX(int sw, int sh, int w, int h) { return sw - 1 - w; }

    @Override protected float vanillaY(int sw, int sh, int w, int h) { return sh - BOTTOM_GAP - h; }

    @Override
    protected void drawSample(DrawContext ctx, int w, int h) {
        var tr = mc.textRenderer;
        int bg = mc.options.getTextBackgroundColor(0.8F);
        for (int i = 0; i < SAMPLE.length; i++) {
            // Bottom line first, as vanilla stacks them upwards.
            int top = h - (i + 1) * LINE;
            ctx.fill(0, top, w, top + LINE, bg);
            String s = SAMPLE[i];
            ctx.drawTextWithShadow(tr, s, (w - tr.getWidth(s)) / 2, top + 1, 0xFFFFFFFF);
            if (i == 0) ctx.drawTextWithShadow(tr, ">", w - 1 - tr.getWidth(">"), top + 1, 0xFFFFFFFF);
        }
    }
}
