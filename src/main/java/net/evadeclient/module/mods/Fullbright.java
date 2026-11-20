package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Brightens dark areas by overriding the gamma the lightmap uses. */
public class Fullbright extends Module {
    private final NumberSetting gamma = add(new NumberSetting("Brightness", 15, 1, 15, 0.5));

    public Fullbright() { super("Fullbright", "See clearly in the dark.", Category.MODS); }

    public double gamma() { return gamma.get(); }
}
