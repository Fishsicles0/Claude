package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;

/** Removes underwater fog and the blue water overlay so you can see clearly when swimming. */
public class ClearWater extends Module {
    public final BooleanSetting overlay = add(new BooleanSetting("Hide Water Overlay", true));
    public final BooleanSetting lava = add(new BooleanSetting("Clear Lava Too", false));

    public ClearWater() {
        super("Clear Water", "Removes underwater fog for clear visibility.", Category.VISUALS);
    }

    public static ClearWater active() {
        if (EvadeClient.modules == null) return null;
        ClearWater c = EvadeClient.modules.get(ClearWater.class);
        return c != null && c.isEnabled() ? c : null;
    }

    /** True while the camera is in water (or lava, if enabled) and the fog should be removed. */
    public static boolean clearFog() {
        ClearWater c = active();
        if (c == null) return false;
        var mc = MinecraftClient.getInstance();
        if (mc.gameRenderer == null) return false;
        var type = mc.gameRenderer.getCamera().getSubmersionType();
        return type == CameraSubmersionType.WATER || (c.lava.get() && type == CameraSubmersionType.LAVA);
    }
}
