package net.evadeclient.module.client;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;

public class HudModule extends Module {
    public final BooleanSetting watermark = add(new BooleanSetting("Watermark", true));
    public final BooleanSetting arrayList = add(new BooleanSetting("ArrayList", true));
    public final BooleanSetting coords = add(new BooleanSetting("Coords", true));
    public final BooleanSetting fps = add(new BooleanSetting("FPS", true));
    public final BooleanSetting rainbow = add(new BooleanSetting("Rainbow", true));

    public HudModule() {
        super("HUD", "On-screen overlay.", Category.CLIENT);
        setVisible(false);
        setEnabled(true);
    }
}
