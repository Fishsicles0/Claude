package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.MathHelper;

/**
 * Brightens caves and night. Visual only; sends nothing to the server.
 *
 * <p>LightmapTextureManagerMixin feeds the brightness the lightmap is built with through {@link #gamma}, so nothing
 * is written to the game's options: your Brightness setting, the vanilla slider and options.txt are left alone, and
 * Fullbright works from the first frame after launch with no state to set up or undo. The Darkness effect (the
 * Warden, sculk shriekers) still dims the world, as it does in vanilla.
 */
public class Fullbright extends Module {
    /** The brightness at which every light level is drawn fully lit (vanilla's own slider stops at 1). */
    private static final float FULL = 15f;
    /** {@link #builtStrength} when the lightmap was last built without Fullbright. */
    private static final float OFF = -1f;

    private final NumberSetting strength = add(new NumberSetting("Strength", 100, 10, 100, 5)
            .describe("How much brighter dark places get, in percent. 100 lights everything fully."));

    /** The brightness the lightmap was last built with while Fullbright was on; NaN when off (for tests). */
    private float applied = Float.NaN;
    /** The strength (0..1) the lightmap was last built with, or OFF. Render thread only. */
    private float builtStrength = OFF;

    public Fullbright() {
        super("Fullbright", "Lights up caves and night. Leaves your Brightness setting alone.", Category.VISUALS);
    }

    @Override
    public String getSuffix() {
        int s = strength.getInt();
        return s >= 100 ? null : s + "%";
    }

    /** The brightness the lightmap was last built with by Fullbright, or NaN when Fullbright isn't applied. */
    public float appliedGamma() { return applied; }

    private float targetStrength() {
        return isEnabled() ? MathHelper.clamp(strength.getFloat() / 100f, 0f, 1f) : OFF;
    }

    /**
     * True when Fullbright was switched, or its Strength changed, since the lightmap was last built, so it must be
     * rebuilt now rather than whenever the game next decides to. Called by LightmapTextureManagerMixin every frame.
     */
    public static boolean needsLightmapUpdate() {
        if (EvadeClient.modules == null) return false;
        Fullbright fb = EvadeClient.modules.get(Fullbright.class);
        return fb != null && fb.targetStrength() != fb.builtStrength;
    }

    /**
     * The brightness the lightmap uses: {@code vanilla} (the Brightness option) while Fullbright is off, otherwise
     * raised towards full light by Strength. The Darkness effect pulls it back to vanilla as it pulses in.
     * Called by LightmapTextureManagerMixin on the render thread whenever the lightmap is rebuilt.
     */
    public static float gamma(float vanilla, float tickProgress) {
        if (EvadeClient.modules == null) return vanilla;
        Fullbright fb = EvadeClient.modules.get(Fullbright.class);
        if (fb == null) return vanilla;
        float s = fb.targetStrength();
        fb.builtStrength = s;
        if (s == OFF) {
            fb.applied = Float.NaN;
            return vanilla;
        }
        float darkness = 0;
        if (mc.player != null && mc.options != null) {
            darkness = mc.player.getEffectFadeFactor(StatusEffects.DARKNESS, tickProgress)
                    * mc.options.getDarknessEffectScale().getValue().floatValue();
        }
        float g = vanilla + Math.max(0f, FULL - vanilla) * s * (1f - MathHelper.clamp(darkness, 0f, 1f));
        fb.applied = g;
        return g;
    }
}
