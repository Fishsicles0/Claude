package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;

/**
 * Removes the underwater fog (and optionally the blue water overlay) so you can see clearly when swimming. The edge
 * of your render distance still fades as in vanilla, and Blindness and Darkness still apply. Lava, powder snow and
 * the fog above water are not touched (NoFog handles open air). The fog itself is changed in FogRendererMixin.
 */
public class ClearWater extends Module {
    public final BooleanSetting overlay = add(new BooleanSetting("Hide Water Overlay", true)
            .describe("Also removes the blue tint drawn over the screen while you're underwater."));

    public ClearWater() {
        super("Clear Water", "Removes underwater fog. Blindness and Darkness still apply.", Category.VISUALS);
    }

    public static ClearWater active() {
        if (EvadeClient.modules == null) return null;
        ClearWater c = EvadeClient.modules.get(ClearWater.class);
        return c != null && c.isEnabled() ? c : null;
    }

    /** True while the camera is underwater and the water fog is being removed (never under Blindness or Darkness). */
    public static boolean clearFog() {
        if (active() == null) return false;
        Camera cam = camera();
        return cam != null && cam.getSubmersionType() == CameraSubmersionType.WATER && !NoFog.effectFog(cam.getFocusedEntity());
    }

    /**
     * True when the underwater screen overlay should be skipped: Clear Water and Hide Water Overlay are on and no
     * Blindness or Darkness is active (the overlay follows the fog). Vanilla only draws it underwater anyway.
     */
    public static boolean hideOverlay() {
        ClearWater c = active();
        if (c == null || !c.overlay.get()) return false;
        Camera cam = camera();
        return cam == null || !NoFog.effectFog(cam.getFocusedEntity());
    }

    private static Camera camera() {
        var mc = MinecraftClient.getInstance();
        return mc.gameRenderer == null ? null : mc.gameRenderer.getCamera();
    }
}
