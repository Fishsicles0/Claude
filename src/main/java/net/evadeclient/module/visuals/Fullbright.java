package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * Brightens caves and night. Visual only; sends nothing to the server and writes nothing to the game's options.
 *
 * <p>LightmapTextureManagerMixin changes the values the lightmap shader is built with, so your Brightness setting,
 * the vanilla slider and options.txt are left alone, and there is no state to set up or undo. Three looks:
 * <ul>
 *   <li><b>Gamma</b> raises the lightmap's brightness curve (the Brightness value) towards 15: dark places become
 *       readable while light and shadow still show.</li>
 *   <li><b>Night Vision</b> uses vanilla's own night-vision factor, exactly as the potion would light the world,
 *       without giving you a fake effect.</li>
 *   <li><b>Full Light</b> raises the lightmap's ambient light to its maximum: every block is drawn fully lit.</li>
 * </ul>
 * The Darkness effect (the Warden, sculk shriekers) still dims and pulses the world as in vanilla: Fullbright backs
 * off as it fades in. Blindness is fog and is not touched at all.
 *
 * <p>The level fades in and out over Fade Time (also when Adaptive follows the light around you), and the fade is
 * stepped per frame on the render thread, so it finishes even after the module is switched off.
 */
public class Fullbright extends Module {
    /** The brightness at which every light level is drawn fully lit (vanilla's own slider stops at 1). */
    private static final float FULL_GAMMA = 15f;
    private static final int GAMMA = 0, NIGHT_VISION = 1, FULL_LIGHT = 2;

    private final ModeSetting mode = add(new ModeSetting("Mode", "Gamma", "Gamma", "Night Vision", "Full Light")
            .describe("Gamma: brightens dark places but keeps light and shadow. Night Vision: lights the world like "
                    + "the potion, without the effect. Full Light: every block fully lit, no shading."));
    private final NumberSetting strength = add(new NumberSetting("Strength %", 100, 10, 100, 5)
            .describe("How much brighter dark places get. 100 lights everything fully."));
    private final BooleanSetting adaptive = add(new BooleanSetting("Adaptive", false)
            .describe("Only brighten where it's dark: full strength at or below Full Boost Below Light, fading out "
                    + "towards full light (bright daylight or a torch at your feet)."));
    private final NumberSetting threshold = add(new NumberSetting("Full Boost Below Light", 7, 1, 15, 1)
            .describe("Adaptive: the light level (0-15) at or below which Fullbright works at full strength."));
    private final NumberSetting fadeTime = add(new NumberSetting("Fade Time", 0.4, 0, 2, 0.1)
            .describe("Seconds to fade in or out when switched, or when Adaptive follows the light. 0 is instant."));

    // ---- fade state (render thread only) ----------------------------------------------------------------------
    /** The faded brightness level (0..1, Strength included) and where it is heading. */
    private static float level, target;
    private static long lastNanos;
    /** The level and mode the lightmap was last built with, so a change rebuilds it on the very next frame. */
    private static float builtLevel = -1f;
    private static int builtMode = -1;
    /** This frame's level with the Darkness effect taken out, and the mode it is drawn in (see {@link #frame}). */
    private static float frameLevel;
    private static int frameMode;

    public Fullbright() {
        super("Fullbright", "Lights up caves and night. Leaves your Brightness setting alone.", Category.VISUALS);
    }

    @Override
    public String getSuffix() { return mode.get(); }

    /** Old configs saved the strength as "Strength"; it is now "Strength %". */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement old = settings.get("Strength");
        if (old == null || settings.has(strength.getName())) return;
        try {
            strength.set(old.getAsDouble());
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }

    /** Shader packs light the world themselves and ignore the lightmap Fullbright changes: say so. */
    @Override
    protected void onEnable() {
        if (net.evadeclient.util.ShaderSwitch.inUse())
            net.evadeclient.util.Notifications.push("Fullbright", "Doesn't work while shaders are on. Turn them off in Performance > Shaders.", false);
    }

    /** The current faded level (0..1), for tests. Render thread. */
    public static float level() { return level; }

    private static Fullbright instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(Fullbright.class);
    }

    private int modeIndex() {
        return switch (mode.get()) {
            case "Night Vision" -> NIGHT_VISION;
            case "Full Light" -> FULL_LIGHT;
            default -> GAMMA;
        };
    }

    /**
     * How much Adaptive lets through at the camera (0..1): 1 at or below Full Boost Below Light, falling to 0 at full
     * light. The light counted is the brighter of the block light and the sky light as it looks right now (sky light
     * times vanilla's sky factor, which drops at night and in storms).
     */
    private float adaptiveFactor(World world, Camera camera) {
        BlockPos p = BlockPos.ofFloored(camera.getCameraPos());
        float sky = world.getLightLevel(LightType.SKY, p)
                * camera.getEnvironmentAttributeInterpolator().get(EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL, 1f);
        float light = Math.max(world.getLightLevel(LightType.BLOCK, p), sky);
        float full = threshold.getFloat();
        if (light <= full) return 1f;
        if (full >= 15f) return 0f;
        return MathHelper.clamp((15f - light) / (15f - full), 0f, 1f);
    }

    /**
     * Once per frame, at the start of the lightmap update (render thread): works out where Fullbright is heading,
     * steps the fade towards it, and prepares this frame's values. Returns true when the lightmap must be rebuilt now
     * because Fullbright's part of it changed since it was last built.
     */
    public static boolean frame(float tickProgress) {
        long now = System.nanoTime();
        Fullbright fb = instance();
        World world = mc.world;
        if (fb == null || world == null || mc.player == null || mc.gameRenderer == null) {
            // No world: nothing to fade. The next world starts dark and fades in.
            level = target = 0f;
            lastNanos = now;
            return false;
        }

        // Where to go: off is 0; on is Strength, scaled down by Adaptive where it's already bright.
        float t = 0f;
        if (fb.isEnabled()) {
            t = MathHelper.clamp(fb.strength.getFloat() / 100f, 0f, 1f);
            if (fb.adaptive.get()) t *= fb.adaptiveFactor(world, mc.gameRenderer.getCamera());
        }
        target = t;

        // Step the fade: a full 0..1 swing takes Fade Time.
        float dt = lastNanos == 0 ? 0f : Math.max(0f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float fade = fb.fadeTime.getFloat();
        if (fade <= 0f) {
            level = target;
        } else {
            float step = dt / fade;
            level = Math.abs(target - level) <= step ? target : level + Math.signum(target - level) * step;
        }

        // The Darkness effect pulls Fullbright back to vanilla as it fades in, as it does with the Brightness option.
        float darkness = 0f;
        if (mc.options != null) {
            darkness = mc.player.getEffectFadeFactor(StatusEffects.DARKNESS, tickProgress)
                    * mc.options.getDarknessEffectScale().getValue().floatValue();
        }
        darkness = MathHelper.clamp(darkness, 0f, 1f);
        frameLevel = level * (1f - darkness);
        int m = fb.modeIndex();
        // Full Light raises the ambient light, and vanilla's shader skips the Darkness pulse whenever the ambient
        // light isn't zero. So while Darkness is on at all, Full Light falls back to Gamma, which keeps the pulse.
        frameMode = m == FULL_LIGHT && darkness > 0f ? GAMMA : m;

        return level != builtLevel || (level > 0f && frameMode != builtMode) || level != target;
    }

    /** The lightmap is being rebuilt with this frame's values (called after {@link #frame}). */
    public static void building() {
        builtLevel = level;
        builtMode = frameMode;
    }

    /** Lightmap ambient light factor (Full Light): raised towards 1, where every light level is drawn fully lit. */
    public static float ambientLight(float vanilla) {
        if (frameMode != FULL_LIGHT || frameLevel <= 0f) return vanilla;
        return vanilla + Math.max(0f, 1f - vanilla) * frameLevel;
    }

    /** Lightmap night-vision factor (Night Vision): at least this frame's level, as the potion would set it. */
    public static float nightVision(float vanilla) {
        if (frameMode != NIGHT_VISION || frameLevel <= 0f) return vanilla;
        return Math.max(vanilla, frameLevel);
    }

    /**
     * Lightmap brightness (Gamma): vanilla's value (your Brightness option, already lowered by Darkness) raised
     * towards {@link #FULL_GAMMA}, where every light level is drawn fully lit.
     */
    public static float brightness(float vanilla) {
        if (frameMode != GAMMA || frameLevel <= 0f) return vanilla;
        return vanilla + Math.max(0f, FULL_GAMMA - vanilla) * frameLevel;
    }
}
