package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Third-person camera goes through walls instead of zooming in, and you can set how far back it sits. */
public class CameraClip extends Module {
    private final NumberSetting distance = add(new NumberSetting("Distance", 4, 1, 20, 0.5));

    public CameraClip() {
        super("CameraClip", "Third-person camera passes through walls.", Category.VISUALS);
    }

    /** Distance to use instead of vanilla's wall-clipped one, or -1 when off. */
    public static float distance() {
        if (EvadeClient.modules == null) return -1;
        CameraClip c = EvadeClient.modules.get(CameraClip.class);
        return c != null && c.isEnabled() ? c.distance.getFloat() : -1;
    }
}
