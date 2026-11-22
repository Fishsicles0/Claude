package net.evadeclient.module;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;

/**
 * A module that draws a box on screen. It can be dragged and scaled in the HUD editor,
 * and its position and scale are saved with the config.
 */
public abstract class HudElementModule extends Module {
    /** Position in scaled GUI pixels. Negative values are anchored to the right/bottom edge. */
    public int x, y;
    /** Unscaled size of the content, set by {@link #render}. */
    protected int width = 20, height = 10;

    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 2.5, 0.05));
    public final BooleanSetting background = add(new BooleanSetting("Background", true));
    public final net.evadeclient.setting.ModeSetting backgroundStyle =
            add(new net.evadeclient.setting.ModeSetting("Background Style", "Evade", "Evade", "Solid", "Glass", "Outline"));
    public final NumberSetting opacity = add(new NumberSetting("Background Opacity", 100, 10, 100, 5));
    public final BooleanSetting hideInMenus = add(new BooleanSetting("Hide In Menus", false));
    public final BooleanSetting combatOnly = add(new BooleanSetting("Only In Combat", false));

    protected HudElementModule(String name, String description, int defaultX, int defaultY) {
        this(name, description, Category.HUD, defaultX, defaultY);
    }

    protected HudElementModule(String name, String description, Category category, int defaultX, int defaultY) {
        super(name, description, category);
        this.x = defaultX;
        this.y = defaultY;
        setVisible(false);
    }

    /**
     * Draw the element with its top-left corner at (left, top), in unscaled units.
     * Set {@link #width}/{@link #height} so dragging and anchoring work.
     *
     * @param preview true in the HUD editor: draw sample content if there's nothing to show.
     */
    public abstract void render(DrawContext ctx, int left, int top, boolean preview);

    /** Renders at the stored position with this element's scale and background style applied. */
    public void renderScaled(DrawContext ctx, int screenW, int screenH, boolean preview) {
        float s = scale.getFloat();
        int left = resolveX(screenW), top = resolveY(screenH);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(left, top);
        ctx.getMatrices().scale(s, s);
        net.evadeclient.util.Theme.hudStyle = backgroundStyle.get();
        net.evadeclient.util.Theme.hudOpacity = opacity.getFloat() / 100f;
        try {
            render(ctx, 0, 0, preview);
        } finally {
            net.evadeclient.util.Theme.hudStyle = "Evade";
            net.evadeclient.util.Theme.hudOpacity = 1f;
            ctx.getMatrices().popMatrix();
        }
    }

    /** Visibility rules: hidden while a menu is open (chat excepted), or shown only around a fight. */
    public boolean shouldShow() {
        var screen = mc.currentScreen;
        if (hideInMenus.get() && screen != null && !(screen instanceof net.minecraft.client.gui.screen.ChatScreen)) return false;
        return !combatOnly.get() || net.evadeclient.util.Combat.active();
    }

    public int scaledWidth() { return Math.round(width * scale.getFloat()); }
    public int scaledHeight() { return Math.round(height * scale.getFloat()); }

    public int resolveX(int screenW) { return x < 0 ? screenW + x + 1 - scaledWidth() : x; }
    public int resolveY(int screenH) { return y < 0 ? screenH + y + 1 - scaledHeight() : y; }

    /** Store the position anchored to whichever screen edge it is closest to. */
    public void moveTo(int left, int top, int screenW, int screenH) {
        int w = scaledWidth(), h = scaledHeight();
        left = Math.max(0, Math.min(screenW - w, left));
        top = Math.max(0, Math.min(screenH - h, top));
        // Right/bottom anchoring is stored as -(gap + 1) so a flush edge (gap 0) is -1, never 0.
        x = left + w / 2 > screenW / 2 ? -(screenW - left - w) - 1 : left;
        y = top + h / 2 > screenH / 2 ? -(screenH - top - h) - 1 : top;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
