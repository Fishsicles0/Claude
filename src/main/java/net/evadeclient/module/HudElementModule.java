package net.evadeclient.module;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;

import java.util.Map;

/**
 * A module that draws a box on screen. It can be dragged and scaled in the HUD editor,
 * and its position and scale are saved with the config.
 *
 * <p>Positions are in HUD units (see {@code Hud.scale()}). A negative coordinate is anchored to the right/bottom
 * edge ({@code -1} is flush with it), {@link #CENTER} as x keeps the element centred horizontally, and every
 * position is clamped onto the screen when it is resolved, so no window size or GUI scale can lose an element.
 */
public abstract class HudElementModule extends Module {
    /** x of an element centred horizontally on the screen. {@link #moveTo} never produces it by accident. */
    public static final int CENTER = Integer.MIN_VALUE;

    /** Position in scaled GUI pixels. Negative values are anchored to the right/bottom edge; see {@link #CENTER}. */
    public int x, y;
    /** Where the element sits in a fresh install (what {@link #resetPosition} goes back to). */
    public final int defaultX, defaultY;
    /** Unscaled size of the content, set by {@link #render}. */
    protected int width = 20, height = 10;
    /**
     * Set by {@link #render} when it drew nothing this frame (no effects, no cooldowns, nothing equipped), so
     * elements stacked on this one at their default spots close the gap. Preview renders always count as shown.
     */
    protected boolean empty;

    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 2.5, 0.05)
            .describe("How big the widget is drawn. You can also scroll over it in the HUD editor to resize it."));
    public final BooleanSetting background = add(new BooleanSetting("Background", defaultBackground())
            .describe("Draws a panel behind the widget so it stays readable on bright scenes."));
    public final ModeSetting backgroundStyle =
            add(new ModeSetting("Background Style", "Evade", "Evade", "Solid", "Glass", "Outline")
                    .describe("Look of the panel: Evade (soft rounded), Solid, Glass (frosted) or Outline (border only)."));
    public final NumberSetting opacity = add(new NumberSetting("Background Opacity", 100, 10, 100, 5)
            .describe("How see-through the panel is. Lower lets more of the game show through."));
    public final BooleanSetting hideInMenus = add(new BooleanSetting("Hide In Menus", false)
            .describe("Hides it while a menu or inventory is open. Chat doesn't count."));
    public final BooleanSetting combatOnly = add(new BooleanSetting("Only In Combat", false)
            .describe("Shows it only during a fight: for 8 seconds after you hit something or take damage."));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", false)
            .describe("Draw this widget's text in Evade's smooth font instead of the pixel font."));
    /** Locked in the HUD editor: it can't be dragged, nudged or resized there until unlocked (L). Saved, never shown. */
    public final BooleanSetting locked = add(new BooleanSetting("Locked", false).hidden());
    /**
     * The HUD editor stack this widget belongs to ("" for none): widgets in one stack move together and sit one under
     * another (see {@link net.evadeclient.hud.HudStacks}). Saved, never shown.
     */
    public final net.evadeclient.setting.StringSetting stack =
            add(new net.evadeclient.setting.StringSetting("Stack", "", "").maxLength(16).hidden());
    /** Place in its stack, top first. Saved, never shown. */
    public final NumberSetting stackOrder = add(new NumberSetting("Stack Order", 0, 0, 999, 1).hidden());

    /**
     * The smooth (Inter) HUD font at one fixed raster (4 device pixels per unit). Widgets no longer draw with it:
     * they use {@link #smoothStyle()}, rasterised for the scale they are drawn at. Kept for callers without a matrix.
     */
    public static final Style UI_FONT = Style.EMPTY
            .withFont(new net.minecraft.text.StyleSpriteSource.Font(net.minecraft.util.Identifier.of("evade", "ui")));

    /** The smooth font for the element being drawn (set around {@link #render} by {@link #renderScaled}). */
    private static Style currentSmooth;

    /**
     * Test hook: while non-null, every element records the background style it was drawn with (name to style),
     * right after {@link Theme#hudStyle} is set for it. Null (the default) costs nothing.
     */
    public static Map<String, String> STYLE_PROBE;

    /**
     * Whether Background starts on for this element. Read once, while the setting is created (before subclass
     * fields exist), so overrides must return a constant.
     */
    protected boolean defaultBackground() { return true; }

    /**
     * Raster of the smooth HUD font for text drawn under {@code ctx}'s current matrix: the device pixels one unit
     * covers there (GUI scale x HUD scale x element scale), rounded and clamped to 1..6. Inter in
     * {@code UiFont.style(BODY, g)} is then rasterised 1:1, so a scaled Smooth Font widget stays as sharp as an
     * unscaled one. Render thread only.
     */
    public static int smoothRaster(DrawContext ctx) {
        return Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, Math.round(Theme.deviceScale(ctx))));
    }

    /** The smooth HUD font for text drawn under {@code ctx}'s current matrix (see {@link #smoothRaster}). */
    public static Style hudFont(DrawContext ctx) {
        return UiFont.style(UiFont.Role.BODY, smoothRaster(ctx));
    }

    /** The smooth font for the widget being drawn right now, or {@link #UI_FONT} outside a widget's render. */
    public static Style smoothStyle() {
        return currentSmooth != null ? currentSmooth : UI_FONT;
    }

    protected HudElementModule(String name, String description, int defaultX, int defaultY) {
        this(name, description, Category.HUD, defaultX, defaultY);
    }

    protected HudElementModule(String name, String description, Category category, int defaultX, int defaultY) {
        super(name, description, category);
        this.x = this.defaultX = defaultX;
        this.y = this.defaultY = defaultY;
        setVisible(false);
    }

    /**
     * A subclass that declares a setting the base class already has (e.g. its own "Smooth Font") gets the shared
     * one back, so a module never holds two settings with one name (configs and the menu key them by name).
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <S extends Setting<?>> S add(S setting) {
        for (Setting<?> s : getSettings())
            if (s.getName().equals(setting.getName()) && s.getClass() == setting.getClass()) return (S) s;
        return super.add(setting);
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
        Theme.hudStyle = backgroundStyle.get();
        Theme.hudOpacity = opacity.getFloat() / 100f;
        if (STYLE_PROBE != null) STYLE_PROBE.put(getName(), Theme.hudStyle);
        Style outerSmooth = currentSmooth;
        currentSmooth = smoothFont.get() ? hudFont(ctx) : null;
        empty = false;
        try {
            render(ctx, 0, 0, preview);
        } finally {
            currentSmooth = outerSmooth;
            if (preview) empty = false;
            Theme.hudStyle = "Evade";
            Theme.hudOpacity = 1f;
            ctx.getMatrices().popMatrix();
        }
    }

    /** Visibility rules: hidden while a menu is open (chat excepted), or shown only around a fight. */
    public boolean shouldShow() {
        var screen = mc.currentScreen;
        if (hideInMenus.get() && screen != null && !(screen instanceof net.minecraft.client.gui.screen.ChatScreen)) return false;
        return !combatOnly.get() || net.evadeclient.util.Combat.active();
    }

    /**
     * Whether the element is on screen right now: enabled, allowed by its visibility rules (the HUD editor counts
     * as in game) and not empty the last time it was drawn.
     */
    public boolean isShowing() {
        if (!isEnabled() || empty) return false;
        return mc.currentScreen instanceof net.evadeclient.gui.HudEditorScreen || shouldShow();
    }

    /** Whether the element is still where a fresh install puts it (never dragged, or reset). */
    public boolean atDefaultSpot() { return x == defaultX && y == defaultY; }

    /** Put the element back where a fresh install puts it. */
    public void resetPosition() {
        x = defaultX;
        y = defaultY;
    }

    /** {@link #styled} results kept between frames (created on first use; cleared when the font changes). */
    private java.util.Map<String, net.minecraft.text.MutableText> styledCache;
    private Style styledCacheFont;

    /**
     * Text in this element's font: the smooth HUD font while Smooth Font is on, else the pixel font. The same string
     * gives back the same Text from frame to frame, so the game doesn't lay its characters out again (bidirectional
     * reordering included) every frame for labels that rarely change. Don't modify the result.
     */
    protected net.minecraft.text.MutableText styled(String s) {
        Style font = smoothFont.get() ? smoothStyle() : null;
        if (styledCache == null) styledCache = new java.util.HashMap<>();
        if (!java.util.Objects.equals(font, styledCacheFont) || styledCache.size() >= 64) {
            styledCache.clear();
            styledCacheFont = font;
        }
        net.minecraft.text.MutableText t = styledCache.get(s);
        if (t == null) {
            t = net.evadeclient.hud.HudDraw.styled(s, font != null);
            styledCache.put(s, t);
        }
        return t;
    }

    public int scaledWidth() { return Math.round(width * scale.getFloat()); }
    public int scaledHeight() { return Math.round(height * scale.getFloat()); }

    /** Left edge on a {@code screenW}-wide HUD, kept on screen. */
    public int resolveX(int screenW) {
        int w = scaledWidth();
        int raw = x == CENTER ? (screenW - w) / 2 : x < 0 ? screenW + x + 1 - w : x;
        return clampX(raw, screenW);
    }

    /** Top edge on a {@code screenH}-tall HUD, kept on screen. */
    public int resolveY(int screenH) {
        int raw = y < 0 ? screenH + y + 1 - scaledHeight() : y;
        return clampY(raw, screenH);
    }

    /** {@code left} moved just far enough that the element is on a {@code screenW}-wide screen (left edge wins). */
    protected int clampX(int left, int screenW) {
        return Math.max(0, Math.min(Math.max(0, screenW - scaledWidth()), left));
    }

    /** {@code top} moved just far enough that the element is on a {@code screenH}-tall screen (top edge wins). */
    protected int clampY(int top, int screenH) {
        return Math.max(0, Math.min(Math.max(0, screenH - scaledHeight()), top));
    }

    /**
     * Top edge for this element stacked just above {@code below} (2 units apart), for elements that share a column
     * at their default spots: when {@code below} grows or empties, this one follows. Returns {@link Integer#MIN_VALUE}
     * when {@code below} is off, empty or has been moved, so the caller falls back to its own position.
     */
    protected int stackedAbove(HudElementModule below, int screenH) {
        if (below == null || below == this || !below.atDefaultSpot() || !below.isShowing()) return Integer.MIN_VALUE;
        return clampY(below.resolveY(screenH) - 2 - scaledHeight(), screenH);
    }

    /** Store the position anchored to whichever screen edge it is closest to. */
    public void moveTo(int left, int top, int screenW, int screenH) {
        moveTo(left, top, screenW, screenH, false);
    }

    /**
     * Store the position anchored to whichever screen edge it is closest to; {@code centreX} keeps the element
     * centred horizontally instead (the HUD editor passes it when the element is snapped to the centre line), so
     * it stays centred at every window size.
     */
    public void moveTo(int left, int top, int screenW, int screenH, boolean centreX) {
        int w = scaledWidth(), h = scaledHeight();
        left = Math.max(0, Math.min(screenW - w, left));
        top = Math.max(0, Math.min(screenH - h, top));
        // Right/bottom anchoring is stored as -(gap + 1) so a flush edge (gap 0) is -1, never 0.
        x = centreX ? CENTER : left + w / 2 > screenW / 2 ? -(screenW - left - w) - 1 : left;
        y = top + h / 2 > screenH / 2 ? -(screenH - top - h) - 1 : top;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
