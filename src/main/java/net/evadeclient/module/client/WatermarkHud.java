package net.evadeclient.module.client;

import net.evadeclient.hud.Hud;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Logo;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

/** The Evade logo, top-right by default. Drag it anywhere in the HUD editor; it steps aside for vanilla toasts. */
public class WatermarkHud extends HudElementModule {
    /** Same spot as before it became a movable element: 4 px from the right edge, 3 px from the top. */
    public static final int DEFAULT_X = -5, DEFAULT_Y = 3;
    /** Height of the logo mark in HUD units. */
    private static final int MARK = 17;
    /** Space around the logo when the background is on. */
    private static final int PAD = 3;

    private final Hud.ToastDodge dodge = new Hud.ToastDodge();

    public WatermarkHud() {
        super("Watermark", "The Evade logo in the corner of the screen.", DEFAULT_X, DEFAULT_Y);
        setEnabled(true);
    }

    /** The watermark never had a panel behind it, so its Background starts off where elements pick their default. */
    protected boolean defaultBackground() { return false; }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        boolean bg = background.get();
        int pad = bg ? PAD : 0;
        width = Logo.lockupWidth(MARK) + pad * 2;
        height = MARK + pad * 2;
        if (bg) Theme.hudBox(ctx, left, top, width, height);
        Logo.lockup(ctx, left + pad, top + pad, MARK);
    }

    @Override
    public void renderScaled(DrawContext ctx, int screenW, int screenH, boolean preview) {
        int dy = preview ? 0 : toastOffset(screenW, screenH);
        if (dy == 0) { super.renderScaled(ctx, screenW, screenH, preview); return; }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(0, dy);
        try {
            super.renderScaled(ctx, screenW, screenH, preview);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    /** Whether the watermark is in its default top-right spot (the Module List stacks under it there). */
    public boolean atDefaultSpot() { return x == DEFAULT_X && y == DEFAULT_Y; }

    /**
     * How far the watermark is pushed down by toasts this frame (HUD units). Safe to call again in the same
     * frame (the Module List stacked under it does, even while the watermark itself is hidden).
     */
    public int toastOffset(int screenW, int screenH) { return dodge.update(this, screenW, screenH); }

    /**
     * Where a widget stacked under the watermark starts (HUD units, before the toast offset): just below the
     * panel, or tucked under the bare logo, whose mark has 3 px of transparent space at the bottom.
     */
    public int stackY(int screenH) {
        int bottom = resolveY(screenH) + scaledHeight();
        return background.get() ? bottom + 1 : bottom - Math.round(3 * scale.getFloat());
    }
}
