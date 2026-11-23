package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Removes the open-air distance fog (terrain, rain and dimension fog) and the fade at the edge of your render
 * distance, so you can see as far as your chunks load. Fog that is a gameplay effect always stays: Blindness,
 * Darkness, lava and powder snow. The work happens in FogRendererMixin.
 */
public class NoFog extends Module {
    /**
     * The fog distances the last world frame was drawn with, after NoFog and Clear Water had their say. Written by
     * FogRendererMixin every frame whether the modules are on or not; read by the in-game tests. NaN until the first
     * frame. Render thread only.
     */
    public static float LAST_ENV_START = Float.NaN, LAST_ENV_END = Float.NaN,
            LAST_RENDER_START = Float.NaN, LAST_RENDER_END = Float.NaN;

    public NoFog() {
        super("NoFog", "Removes distance fog. Blindness, Darkness, lava and snow fog stay.", Category.VISUALS);
    }

    /** True while NoFog is switched on. */
    public static boolean on() {
        if (EvadeClient.modules == null) return false;
        NoFog nf = EvadeClient.modules.get(NoFog.class);
        return nf != null && nf.isEnabled();
    }

    /**
     * True when the camera entity has Blindness or Darkness. Vanilla then uses that effect's fog instead of the
     * water or open-air fog (the same check as its StatusEffectFogModifier), and neither NoFog nor Clear Water may
     * lift it.
     */
    public static boolean effectFog(Entity camera) {
        return camera instanceof LivingEntity e
                && (e.hasStatusEffect(StatusEffects.BLINDNESS) || e.hasStatusEffect(StatusEffects.DARKNESS));
    }
}
