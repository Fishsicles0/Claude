package net.evadeclient.module;

import net.minecraft.client.gui.DrawContext;

/**
 * A module that draws a box on screen. Its position can be dragged while the ClickGUI is open
 * and is saved with the config.
 */
public abstract class HudElementModule extends Module {
    /** Position in scaled GUI pixels. Negative values are anchored to the right/bottom edge. */
    public int x, y;
    protected int width = 20, height = 10;

    protected HudElementModule(String name, String description, int defaultX, int defaultY) {
        super(name, description, Category.CLIENT);
        this.x = defaultX;
        this.y = defaultY;
        setVisible(false);
    }

    /**
     * Draw the element with its top-left corner at (left, top).
     * Set {@link #width}/{@link #height} so dragging and anchoring work.
     *
     * @param preview true while the ClickGUI is open: draw sample content if there's nothing to show.
     */
    public abstract void render(DrawContext ctx, int left, int top, boolean preview);

    public int resolveX(int screenW) { return x < 0 ? screenW + x + 1 - width : x; }
    public int resolveY(int screenH) { return y < 0 ? screenH + y + 1 - height : y; }

    /** Store the position anchored to whichever screen edge it is closest to. */
    public void moveTo(int left, int top, int screenW, int screenH) {
        left = Math.max(0, Math.min(screenW - width, left));
        top = Math.max(0, Math.min(screenH - height, top));
        // Right/bottom anchoring is stored as -(gap + 1) so a flush edge (gap 0) is -1, never 0.
        x = left + width / 2 > screenW / 2 ? -(screenW - left - width) - 1 : left;
        y = top + height / 2 > screenH / 2 ? -(screenH - top - height) - 1 : top;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
