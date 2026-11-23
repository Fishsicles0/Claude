package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/**
 * Camera Distance: set how far back the third-person camera sits (4 is vanilla). The camera still stops in front of
 * walls exactly as in vanilla, and rides further back on big mounts just like the default camera does.
 */
public class CameraClip extends Module {
    private final NumberSetting distance = add(new NumberSetting("Distance", 4, 1, 8, 0.5)
            .describe("Blocks behind you at normal size; 4 is the vanilla distance."));

    public CameraClip() {
        super("Camera Distance", "Adjust how far the third-person camera sits.", Category.VISUALS);
    }

    /** The chosen distance (vanilla is 4), or -1 when off. CameraMixin scales vanilla's own distance by it. */
    public static float distance() {
        if (EvadeClient.modules == null) return -1;
        CameraClip c = EvadeClient.modules.get(CameraClip.class);
        return c != null && c.isEnabled() ? c.distance.getFloat() : -1;
    }
}
