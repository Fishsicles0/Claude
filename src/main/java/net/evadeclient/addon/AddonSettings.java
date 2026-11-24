package net.evadeclient.addon;

import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;

/** Builds the settings the addon base classes hand out, checking what an addon passes in. */
final class AddonSettings {
    private AddonSettings() {}

    static String name(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A setting needs a name");
        String n = name.strip();
        if (n.length() > 40) throw new IllegalArgumentException("Setting names are 40 characters at most: " + n);
        return n;
    }

    static BooleanSetting toggle(String name, boolean value, String description) {
        return new BooleanSetting(name(name), value).describe(description);
    }

    static NumberSetting slider(String name, double value, double min, double max, double step, String description) {
        if (!(min < max) || !Double.isFinite(min) || !Double.isFinite(max) || !(step >= 0))
            throw new IllegalArgumentException("Slider " + name + ": needs min < max and a step of 0 or more");
        return new NumberSetting(name(name), Math.max(min, Math.min(max, value)), min, max, step).describe(description);
    }

    static ModeSetting mode(String name, String value, String description, String... modes) {
        if (modes == null || modes.length == 0) throw new IllegalArgumentException("Mode " + name + ": needs at least one option");
        boolean known = false;
        for (String m : modes) known |= m.equals(value);
        if (!known) throw new IllegalArgumentException("Mode " + name + ": the default " + value + " isn't one of its options");
        return new ModeSetting(name(name), value, modes).describe(description);
    }

    static ColorSetting color(String name, int argb, String description) {
        return new ColorSetting(name(name), argb).describe(description);
    }

    static KeySetting key(String name, int glfwKey, String description) {
        KeySetting k = new KeySetting(name(name), KeySetting.isValidKey(glfwKey) ? glfwKey : org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN);
        return k.describe(description);
    }

    static StringSetting text(String name, String value, int maxLength, String description) {
        return new StringSetting(name(name), value == null ? "" : value, "text").maxLength(Math.max(1, Math.min(256, maxLength)))
                .describe(description);
    }
}
