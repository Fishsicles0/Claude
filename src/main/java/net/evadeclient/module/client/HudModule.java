package net.evadeclient.module.client;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;

/** Watermark and the list of active modules (top-right). */
public class HudModule extends Module {
    public final BooleanSetting watermark = add(new BooleanSetting("Watermark", true));
    public final BooleanSetting arrayList = add(new BooleanSetting("Module List", true));
    public final ModeSetting colorMode = add(new ModeSetting("List Color", "Red Wave", "Red Wave", "Rainbow", "Static Red", "White"));

    public HudModule() {
        super("Module List", "Watermark and active module list.", Category.HUD);
        setVisible(false);
        setEnabled(true);
    }
}
