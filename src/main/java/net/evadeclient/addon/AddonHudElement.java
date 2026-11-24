package net.evadeclient.addon;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.minecraft.client.gui.DrawContext;

/**
 * A HUD widget added by another mod. It can be dragged, scaled, locked and stacked in the HUD editor, and gets
 * Evade's standard widget settings (Scale, Background, Background Style, Hide In Menus, Only In Combat, Smooth Font)
 * on top of its own.
 *
 * <p>Implement {@link #render(DrawContext, int, int, boolean)}: draw with the top-left corner at (left, top), in
 * unscaled HUD units, and call {@link #size} with what you drew. With {@code preview} true (the HUD editor) draw sample
 * content when there's nothing to show. {@link #background(DrawContext, int, int)} draws the widget's background box in
 * the player's chosen style. For plain lines of text, extend {@link AddonTextHud} instead.
 */
public abstract class AddonHudElement extends HudElementModule {
    /**
     * @param defaultX where it sits in a fresh install, in HUD units; negative values are anchored to the right edge
     *                 ({@code -1} is flush with it), {@link #CENTER} keeps it centred
     * @param defaultY likewise, negative values anchored to the bottom edge
     */
    protected AddonHudElement(String name, String description, int defaultX, int defaultY) {
        super(name, description, defaultX, defaultY);
    }

    /** See {@link AddonModule#setEnabled}. */
    @Override
    public final void setEnabled(boolean enabled) { super.setEnabled(enabled); }

    /** The name of the mod that added this widget, or null before it is registered. */
    public final String addonName() { return EvadeAddons.ownerName(this); }

    /** The size of what {@link #render} drew, in unscaled HUD units (used for dragging, snapping and anchoring). */
    protected final void size(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    /** Draws the background box in the player's chosen style, when Background is on. */
    protected final void background(DrawContext ctx, int left, int top) {
        if (background.get()) net.evadeclient.util.Theme.hudBox(ctx, left, top, width, height);
    }

    /** Text in the widget's font (the smooth font while Smooth Font is on). */
    protected final net.minecraft.text.MutableText label(String s) { return styled(s); }

    protected final BooleanSetting toggle(String name, boolean value, String description) { return add(AddonSettings.toggle(name, value, description)); }
    protected final NumberSetting slider(String name, double value, double min, double max, double step, String description) { return add(AddonSettings.slider(name, value, min, max, step, description)); }
    protected final ModeSetting mode(String name, String value, String description, String... modes) { return add(AddonSettings.mode(name, value, description, modes)); }
    protected final ColorSetting color(String name, int argb, String description) { return add(AddonSettings.color(name, argb, description)); }
    protected final KeySetting key(String name, int glfwKey, String description) { return add(AddonSettings.key(name, glfwKey, description)); }
    protected final StringSetting text(String name, String value, int maxLength, String description) { return add(AddonSettings.text(name, value, maxLength, description)); }
}
