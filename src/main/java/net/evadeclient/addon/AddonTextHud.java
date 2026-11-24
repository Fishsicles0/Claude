package net.evadeclient.addon;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;

import java.util.List;

/**
 * A HUD widget that shows lines of text, styled like Evade's own (Text Color, Shadow, Chroma, Brackets, Text Align,
 * Label/Value Colors and the widget settings). Implement {@link #lines(boolean)}: it is called once per client tick
 * (20 times a second), not every frame. Return an empty list to hide the widget for now.
 */
public abstract class AddonTextHud extends TextHudModule {
    protected AddonTextHud(String name, String description, int defaultX, int defaultY) {
        super(name, description, defaultX, defaultY);
    }

    /** The lines to show; {@code preview} is true in the HUD editor (show sample text when there's nothing to show). */
    @Override
    protected abstract List<String> lines(boolean preview);

    /** See {@link AddonModule#setEnabled}. */
    @Override
    public final void setEnabled(boolean enabled) { super.setEnabled(enabled); }

    /** The name of the mod that added this widget, or null before it is registered. */
    public final String addonName() { return EvadeAddons.ownerName(this); }

    protected final BooleanSetting toggle(String name, boolean value, String description) { return add(AddonSettings.toggle(name, value, description)); }
    protected final NumberSetting slider(String name, double value, double min, double max, double step, String description) { return add(AddonSettings.slider(name, value, min, max, step, description)); }
    protected final ModeSetting mode(String name, String value, String description, String... modes) { return add(AddonSettings.mode(name, value, description, modes)); }
    protected final ColorSetting color(String name, int argb, String description) { return add(AddonSettings.color(name, argb, description)); }
    protected final KeySetting key(String name, int glfwKey, String description) { return add(AddonSettings.key(name, glfwKey, description)); }
    protected final StringSetting text(String name, String value, int maxLength, String description) { return add(AddonSettings.text(name, value, maxLength, description)); }
}
