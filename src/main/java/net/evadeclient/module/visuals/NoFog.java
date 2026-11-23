package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * Thins or removes the open-air distance fog (terrain, rain and dimension fog), per dimension, and softens the fade
 * at the edge of your render distance, so you can see as far as your chunks load. Only the atmospheric fog of open
 * air is ever touched: fog that is a gameplay effect always stays exactly as in vanilla (water, which Clear Water
 * handles, lava, powder snow, Blindness and Darkness). The work happens in FogRendererMixin, which asks
 * {@link #clears} first and then runs each distance through the helpers below.
 */
public class NoFog extends Module {
    /** "No fog" distances: far past any render distance, but finite so shaders never divide by infinity. */
    private static final float GONE_START = 1e6f, GONE_END = 2e6f;
    /** The sky fog distance used during boss fights that thicken the fog (vanilla pulls it down to ~96 blocks). */
    private static final float BOSS_SKY_MAX = 512f;

    /**
     * The fog distances the last world frame was drawn with, after NoFog and Clear Water had their say. Written by
     * FogRendererMixin every frame whether the modules are on or not; read by the in-game tests. NaN until the first
     * frame. Render thread only.
     */
    public static float LAST_ENV_START = Float.NaN, LAST_ENV_END = Float.NaN,
            LAST_RENDER_START = Float.NaN, LAST_RENDER_END = Float.NaN;

    private final BooleanSetting overworld = add(new BooleanSetting("Overworld", true)
            .describe("Clear the fog in the Overworld, and in dimensions added by mods or data packs."));
    private final BooleanSetting nether = add(new BooleanSetting("Nether", true)
            .describe("Clear the thick Nether fog."));
    private final BooleanSetting end = add(new BooleanSetting("End", true)
            .describe("Clear the fog in the End."));
    private final NumberSetting strength = add(new NumberSetting("Fog Strength %", 0, 0, 100, 5)
            .describe("How much distance fog is left: 0 removes it, 50 pushes it twice as far away, 100 is vanilla."));
    private final NumberSetting edgeFade = add(new NumberSetting("Edge Fade %", 25, 0, 100, 5)
            .describe("How softly the world fades out at the edge of your render distance, as a share of vanilla's "
                    + "fade. 0 is a hard edge, 100 is vanilla."));
    private final BooleanSetting clearSky = add(new BooleanSetting("Clear Sky Fog", false)
            .describe("Also remove the haze where the sky meets the horizon, for a crisp horizon line."));

    public NoFog() {
        super("NoFog", "Clears distance fog. Water, lava, snow, Blindness and Darkness fog stay.", Category.VISUALS);
    }

    /** NoFog while it's switched on, otherwise null. */
    public static NoFog active() {
        if (EvadeClient.modules == null) return null;
        NoFog nf = EvadeClient.modules.get(NoFog.class);
        return nf != null && nf.isEnabled() ? nf : null;
    }

    /** True while NoFog is switched on (whether or not it is clearing anything right now; see {@link #clears}). */
    public static boolean on() {
        return active() != null;
    }

    /** {@link #clears(Camera, World)} for the game's own camera and world. */
    public static boolean clears() {
        if (mc.gameRenderer == null || mc.world == null) return false;
        return clears(mc.gameRenderer.getCamera(), mc.world);
    }

    /**
     * True when NoFog is on and may change this frame's fog: the camera is in open air (not in water, lava or powder
     * snow), has neither Blindness nor Darkness, and NoFog is switched on for the current dimension. In every other
     * case the fog must stay exactly as vanilla draws it.
     */
    public static boolean clears(Camera camera, World world) {
        NoFog nf = active();
        if (nf == null || camera == null || world == null) return false;
        CameraSubmersionType sub = camera.getSubmersionType();
        if (sub != CameraSubmersionType.ATMOSPHERIC && sub != CameraSubmersionType.NONE) return false;
        if (effectFog(camera.getFocusedEntity())) return false;
        if (world.getRegistryKey() == World.NETHER) return nf.nether.get();
        if (world.getRegistryKey() == World.END) return nf.end.get();
        return nf.overworld.get(); // the Overworld and any dimension a mod or data pack adds
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

    /**
     * Applies NoFog to the fog vanilla just worked out for this frame, and returns whether it changed anything (false
     * whenever {@link #clears(Camera, World)} is false: the fog then stays exactly as vanilla made it). The terrain fog
     * follows Fog Strength, the render-distance fade follows Edge Fade (its end, the edge of the loaded world, is
     * never moved), and the sky and cloud fog follow Clear Sky Fog and boss fights. Called by FogRendererMixin.
     */
    public static boolean apply(FogData data, Camera camera, World world, int viewDistance) {
        if (!clears(camera, world)) return false;
        NoFog nf = active();
        data.environmentalStart = scaleEnvUnchecked(nf, data.environmentalStart, false);
        data.environmentalEnd = scaleEnvUnchecked(nf, data.environmentalEnd, true);
        data.renderDistanceStart = edgeStart(data.renderDistanceEnd);
        data.skyEnd = skyEnd(data.skyEnd, viewDistance);
        data.cloudEnd = cloudEnd(data.cloudEnd);
        return true;
    }

    /**
     * An open-air fog distance ({@code end} false for where the fog starts, true for where it is solid) after Fog
     * Strength: the distance divided by the strength, so 50% fog sits twice as far away, and none at all at 0%.
     * Unchanged unless {@link #clears()}.
     */
    public static float scaleEnv(float v, boolean end) {
        if (!clears()) return v;
        return scaleEnvUnchecked(active(), v, end);
    }

    private static float scaleEnvUnchecked(NoFog nf, float v, boolean end) {
        double s = nf.strength.get() / 100.0;
        if (s < 0.001) return end ? GONE_END : GONE_START;
        return (float) Math.min(GONE_START, Math.max(0f, v) / s);
    }

    /**
     * Where the render-distance fade starts, given where it ends ({@code renderEnd}, the edge of the loaded world).
     * Vanilla fades over a tenth of the render distance (4 to 64 blocks); Edge Fade keeps that share of it, so the
     * last chunks stay sharp without a hard line where the world stops.
     */
    public static float edgeStart(float renderEnd) {
        NoFog nf = active();
        double pct = nf == null ? 100 : nf.edgeFade.get();
        float width = MathHelper.clamp(renderEnd / 10f, 4f, 64f) * (float) (pct / 100.0);
        return renderEnd - Math.max(width, 0.01f);
    }

    /**
     * The sky fog distance: gone with Clear Sky Fog; during a boss fight that thickens the fog (vanilla pulls the sky
     * fog down to the terrain fog), opened back up to the render distance (at most 512 blocks) so the sky matches the
     * cleared terrain; otherwise vanilla's.
     */
    public static float skyEnd(float v, int viewDistance) {
        NoFog nf = active();
        if (nf == null) return v;
        if (nf.clearSky.get()) return GONE_END;
        if (bossFog()) return Math.min(viewDistance * 16f, BOSS_SKY_MAX);
        return v;
    }

    /** The cloud fog distance: during a boss fight that thickens the fog, back to your cloud distance; else vanilla's. */
    public static float cloudEnd(float v) {
        if (active() == null || !bossFog() || mc.options == null) return v;
        return mc.options.getCloudRenderDistance().getValue() * 16f;
    }

    private static boolean bossFog() {
        return mc.inGameHud != null && mc.inGameHud.getBossBarHud().shouldThickenFog();
    }

    @Override
    public String getSuffix() {
        int s = strength.getInt();
        return s > 0 ? s + "%" : null;
    }
}
