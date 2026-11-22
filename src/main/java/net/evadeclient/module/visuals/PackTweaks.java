package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/** Small screen tweaks texture packs usually do: lower fire, and no pumpkin blur. */
public class PackTweaks extends Module {
    public final BooleanSetting lowFire = add(new BooleanSetting("Low Fire", true));
    public final NumberSetting fireHeight = add(new NumberSetting("Fire Lowered By", 0.3, 0.05, 0.6, 0.05));
    public final BooleanSetting pumpkin = add(new BooleanSetting("Hide Pumpkin Blur", true));

    public PackTweaks() {
        super("Pack Tweaks", "Lowers fire on screen and hides pumpkin blur.", Category.VISUALS);
    }

    public static PackTweaks active() {
        if (EvadeClient.modules == null) return null;
        PackTweaks p = EvadeClient.modules.get(PackTweaks.class);
        return p != null && p.isEnabled() ? p : null;
    }
}
