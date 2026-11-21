package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Locks your field of view to a fixed value so speed, sprinting and status effects can't distort it. */
public class FovChanger extends Module {
    public final NumberSetting fov = add(new NumberSetting("FOV", 70, 30, 120, 1));

    public FovChanger() {
        super("FOV Changer", "Locks your field of view so speed effects don't distort your aim.", Category.MODS);
    }

    /** The locked FOV angle, or null when disabled. Applied in GameRendererMixin before Zoom. */
    public static Float lockedFov() {
        if (EvadeClient.modules == null) return null;
        FovChanger m = EvadeClient.modules.get(FovChanger.class);
        return m != null && m.isEnabled() ? m.fov.get().floatValue() : null;
    }
}
