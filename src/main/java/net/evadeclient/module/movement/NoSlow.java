package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;

/** Walk at full speed while eating, drinking, blocking or drawing a bow. */
public class NoSlow extends Module {
    public final BooleanSetting items = add(new BooleanSetting("Using Items", true));
    public final BooleanSetting sprint = add(new BooleanSetting("Keep Sprint", true));

    public NoSlow() {
        super("NoSlow", "No slowdown while eating, blocking or using a bow.", Category.MOVEMENT);
    }
}
