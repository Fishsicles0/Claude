package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.ModuleManager;
import net.minecraft.client.gui.DrawContext;

/**
 * A HUD element that moves and scales text vanilla draws itself (titles, the action bar, sound subtitles). Off by
 * default, which leaves vanilla's placement alone. On, vanilla's own drawing is moved as a whole onto the element's
 * box ({@link #begin}, called by the placement mixins), so fades, colours and the text background stay vanilla's.
 *
 * <p>Subclasses describe vanilla's box: its size in GUI pixels right now ({@link #guiSize}) and where vanilla puts its
 * top-left corner ({@link #vanillaX}/{@link #vanillaY}). The element's own render only measures (and draws a sample
 * in the HUD editor when nothing is showing).
 */
public abstract class VanillaTextHud extends HudElementModule {
    /** Test hook: the transform the last {@link #begin} applied, as {translateX, translateY, scale}, or null. */
    public float[] lastTransform;

    protected VanillaTextHud(String name, String description, int x, int y) {
        super(name, description, x, y);
    }

    @Override protected boolean defaultBackground() { return false; }

    /**
     * Vanilla's box for what is showing right now, {w, h} in GUI pixels, or null when nothing is. With
     * {@code sample}, the size of the HUD editor's sample instead.
     */
    protected abstract int[] guiSize(boolean sample);

    /** Left edge of vanilla's own box on a {@code sw} x {@code sh} GUI screen. */
    protected abstract float vanillaX(int sw, int sh, int w, int h);

    /** Top edge of vanilla's own box on a {@code sw} x {@code sh} GUI screen. */
    protected abstract float vanillaY(int sw, int sh, int w, int h);

    /** Draws the HUD editor's sample at GUI size with the box's top-left at (0, 0). */
    protected abstract void drawSample(DrawContext ctx, int w, int h);

    /** Sets {@link #width}/{@link #height} (HUD units) from a GUI-pixel size. */
    private void size(int[] gui) {
        float k = Hud.scale();
        width = Math.max(1, Math.round(gui[0] / k));
        height = Math.max(1, Math.round(gui[1] / k));
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        int[] live = guiSize(false);
        if (live != null) {
            size(live); // vanilla draws it there (begin), nothing to draw here
            return;
        }
        if (!preview) {
            int[] s = guiSize(true);
            size(s);
            empty = true;
            return;
        }
        int[] s = guiSize(true);
        size(s);
        float k = Hud.scale();
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(left, top);
            m.scale(1f / k, 1f / k);
            drawSample(ctx, s[0], s[1]);
        } finally {
            m.popMatrix();
        }
    }

    /**
     * Wraps vanilla's drawing of {@code type}'s text: 0 leaves it alone (the element is off), 1 pushed a matrix that
     * moves it onto the element (the caller pops it after vanilla drew), -1 means hide it (Hide In Menus, Only In
     * Combat). Render thread.
     */
    public static int begin(Class<? extends VanillaTextHud> type, DrawContext ctx) {
        if (EvadeClient.modules == null) return 0;
        VanillaTextHud el = EvadeClient.modules.get(type);
        if (el == null || !el.isEnabled()) return 0;
        try {
            if (!el.shouldShow()) return -1;
            int[] gui = el.guiSize(false);
            if (gui == null) return 0;
            el.size(gui);
            float k = Hud.scale();
            int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
            int hudW = Math.round(sw / k), hudH = Math.round(sh / k);
            float s = el.scale.getFloat();
            float left = el.resolveX(hudW) * k, top = el.resolveY(hudH) * k;
            float vx = el.vanillaX(sw, sh, gui[0], gui[1]), vy = el.vanillaY(sw, sh, gui[0], gui[1]);
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate(left, top);
            m.scale(s, s);
            m.translate(-vx, -vy);
            el.lastTransform = new float[]{left - vx * s, top - vy * s, s};
            return 1;
        } catch (RuntimeException e) {
            ModuleManager.crashed(el, e, "HUD");
            return 0;
        }
    }

    // ---- a default spot that matches vanilla's ---------------------------------------------------------

    /**
     * Top edge (HUD units) that puts the box where vanilla has it, used while the element is at its default spot
     * (so turning it on changes nothing until you move it).
     */
    protected int vanillaTop(int screenH) {
        float k = Hud.scale();
        int shGui = Math.round(screenH * k);
        int swGui = Math.round(hudScreenWidth() * k);
        int[] gui = lastGui();
        float vy = vanillaY(swGui, shGui, gui[0], gui[1]);
        return clampY(Math.round(vy / k), screenH);
    }

    private static int hudScreenWidth() { return Math.max(1, net.evadeclient.hud.HudDraw.screenWidth()); }

    private int[] lastGui() {
        int[] live = guiSize(false);
        return live != null ? live : guiSize(true);
    }

    @Override
    public int resolveY(int screenH) {
        if (atDefaultSpot()) return vanillaTop(screenH);
        return super.resolveY(screenH);
    }
}
