package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/** Hides or resizes the boss health bar at the top of the screen. */
public class BossBar extends Module {
    public final BooleanSetting hide = add(new BooleanSetting("Hide", false));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 1.5, 0.05));

    public BossBar() {
        super("Boss Bar", "Hide or resize the boss health bar.", Category.VISUALS);
    }

    public static BossBar active() {
        if (EvadeClient.modules == null) return null;
        BossBar b = EvadeClient.modules.get(BossBar.class);
        return b != null && b.isEnabled() ? b : null;
    }
}
