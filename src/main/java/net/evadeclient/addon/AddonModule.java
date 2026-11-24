package net.evadeclient.addon;

import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;

/**
 * A module added by another mod. It is listed in the Evade menu like a built-in one, with an "Addon: &lt;mod&gt;"
 * tag, and gets the same hooks: {@link #onTick}, {@link #onRender}, {@link #onHud}, {@link #onEnable},
 * {@link #onDisable}, chat commands ({@link #commandAliases}) and so on. Modules start off.
 *
 * <p>Create settings in field initialisers with the helpers below, e.g.
 * {@code private final BooleanSetting sound = toggle("Sound", true, "Play a sound when it triggers.");}
 * Setting values are saved with Evade's config and profiles, keyed by module and setting name, so keep names stable.
 */
public abstract class AddonModule extends Module {
    /**
     * @param name        shown in the menu and used for config and commands; must not clash with another module
     * @param description one plain-English line saying what it does
     */
    protected AddonModule(String name, String description, AddonCategory category) {
        super(name, description, (category == null ? AddonCategory.MISC : category).category);
    }

    /**
     * Turning a module on always goes through Evade, so a server's {@code evade:rules} can keep it off. Final: an
     * addon can't skip that check.
     */
    @Override
    public final void setEnabled(boolean enabled) { super.setEnabled(enabled); }

    /** The name of the mod that added this module (as in its fabric.mod.json), or null before it is registered. */
    public final String addonName() { return EvadeAddons.ownerName(this); }

    protected final BooleanSetting toggle(String name, boolean value, String description) { return add(AddonSettings.toggle(name, value, description)); }
    protected final NumberSetting slider(String name, double value, double min, double max, double step, String description) { return add(AddonSettings.slider(name, value, min, max, step, description)); }
    protected final ModeSetting mode(String name, String value, String description, String... modes) { return add(AddonSettings.mode(name, value, description, modes)); }
    protected final ColorSetting color(String name, int argb, String description) { return add(AddonSettings.color(name, argb, description)); }
    /** A key the player picks (a GLFW key code, or {@code GLFW_KEY_UNKNOWN} for none); read it with {@code isDown()}. */
    protected final KeySetting key(String name, int glfwKey, String description) { return add(AddonSettings.key(name, glfwKey, description)); }
    /** A short text value, at most {@code maxLength} characters (256 at most). */
    protected final StringSetting text(String name, String value, int maxLength, String description) { return add(AddonSettings.text(name, value, maxLength, description)); }
}
