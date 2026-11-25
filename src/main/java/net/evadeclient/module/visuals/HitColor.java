package net.evadeclient.module.visuals;

import net.evadeclient.mixin.GameRendererAccessor;
import net.evadeclient.mixin.OverlayTextureAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.EntityVisualsDuck;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import java.awt.Color;

/**
 * Changes the colour mobs and players flash when they're hurt (vanilla is red), optionally fading it out, giving
 * players their own colour, or cycling through every hue.
 *
 * The flash is the game's 16x16 entity overlay texture: entities sample one texel of it, column U and row V, and the
 * entity shader mixes the texel's colour in with weight (1 - its alpha). Vanilla paints rows 0-7 its hurt red and
 * picks row 3 (hurt) or row 10 (not hurt, with U as the creeper's white fuse flash). This module paints rows 0-3 for
 * mobs and rows 4-7 for players, and along each row 16 fade steps: column 0 is the full flash, column 15 a sixteenth
 * of it. A hurt entity then picks its row and the column of its fade (LivingEntityRendererMixin). Vanilla's own
 * direct uses (the dragon asks for column 0 of row 3) get the full mob flash, exactly as before. While hurt, vanilla
 * ignores U anyway (the hurt rows are flat), so the creeper's fuse flash loses nothing.
 */
public class HitColor extends Module {
    private static final int VANILLA = 0xB2FF0000;
    /** Overlay rows: mobs use 0-3 (vanilla's hurt row 3 among them), players 4-7; row 10 (not hurt) is never touched. */
    private static final int MOB_ROW = 3, PLAYER_ROW = 7, NOT_HURT_ROW = 10;
    /** Fade steps along each row (the texture's width). */
    private static final int STEPS = 16;
    /** Vanilla's death animation length, in ticks. */
    private static final float DEATH_TICKS = 20f;

    public final ColorSetting color = add(new ColorSetting("Color", 0xFF3D7BFF)
            .describe("The colour mobs and players flash when they take damage (vanilla is red)."));
    public final NumberSetting opacity = add(new NumberSetting("Opacity %", 70, 10, 100, 5)
            .describe("How much of the entity's own colours shows through the flash: lower is a stronger tint."));
    public final ModeSetting animation = add(new ModeSetting("Animation", "Static", "Static", "Fade Out")
            .describe("Static: the full flash for as long as the entity is hurt. Fade Out: it fades away over the hurt time."));
    public final ModeSetting onDeath = add(new ModeSetting("On Death", "Keep", "Keep", "Fade", "None")
            .describe("The flash while a mob or player dies: kept, faded out over the death animation, or none."));
    public final BooleanSetting separatePlayerColor = add(new BooleanSetting("Separate Player Color", false)
            .describe("Players flash in Player Color, mobs in Color."));
    public final ColorSetting playerColor = add(new ColorSetting("Player Color", 0xFFFF5CC8)
            .describe("The flash colour for players, with Separate Player Color on."));
    public final BooleanSetting tintSelf = add(new BooleanSetting("Tint Self", true)
            .describe("Your own player flashes too (third person). Off: you never do."));
    public final BooleanSetting chroma = add(new BooleanSetting("Chroma", false)
            .describe("The flash cycles through every colour (players half a cycle apart with Separate Player Color)."));
    public final NumberSetting chromaSpeed = add(new NumberSetting("Chroma Speed", 1.0, 0.2, 3.0, 0.1)
            .describe("How fast Chroma cycles: 1 is one full cycle every 4 seconds."));

    private static HitColor instance;

    /** What the texture holds now: mob colour in the high half, player colour in the low half; 0 = untouched. */
    private long applied = 0;

    public HitColor() {
        super("Hit Color", "Customise the flash colour when you hit players or mobs.", Category.VISUALS);
        instance = this;
    }

    @Override protected void onEnable() { applied = 0; }

    @Override
    protected void onDisable() {
        paintVanilla();
        applied = 0;
    }

    @Override
    public void onTick() {
        if (!chroma.get()) refresh();
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (chroma.get()) refresh();
    }

    /** Repaints the overlay if the colours it should hold changed. */
    private void refresh() {
        int alpha = (int) Math.round(opacity.get() * 2.55);
        int mob, player;
        if (chroma.get()) {
            long period = Math.max(1, Math.round(4000 / Math.max(0.05, chromaSpeed.get())));
            float hue = (System.currentTimeMillis() % period) / (float) period;
            mob = Color.HSBtoRGB(hue, 0.9f, 1f);
            player = separatePlayerColor.get() ? Color.HSBtoRGB(hue + 0.5f, 0.9f, 1f) : mob;
        } else {
            mob = color.get();
            player = separatePlayerColor.get() ? playerColor.get() : mob;
        }
        mob = (alpha << 24) | (mob & 0xFFFFFF);
        player = (alpha << 24) | (player & 0xFFFFFF);
        long target = ((long) mob << 32) | (player & 0xFFFFFFFFL);
        if (target != applied && paint(mob, player)) applied = target;
    }

    /** The colour currently painted for mobs at full strength (for tests). */
    public int current() {
        NativeImageBackedTexture tex = texture();
        NativeImage img = tex == null ? null : tex.getImage();
        return img == null ? 0 : img.getColorArgb(0, 0);
    }

    private static NativeImageBackedTexture texture() {
        if (mc.gameRenderer == null) return null;
        OverlayTexture overlay = ((GameRendererAccessor) mc.gameRenderer).evade$overlay();
        return overlay == null ? null : ((OverlayTextureAccessor) overlay).evade$texture();
    }

    /** Rows 0-3 (mobs) and 4-7 (players): column x holds the flash at (16 - x)/16 of its strength. Then re-uploads. */
    private boolean paint(int mob, int player) {
        NativeImageBackedTexture tex = texture();
        NativeImage img = tex == null ? null : tex.getImage();
        if (img == null) return false;
        for (int x = 0; x < STEPS; x++) {
            float strength = (STEPS - x) / (float) STEPS;
            int m = faded(mob, strength), p = faded(player, strength);
            for (int y = 0; y < 4; y++) img.setColorArgb(x, y, m);
            for (int y = 4; y < 8; y++) img.setColorArgb(x, y, p);
        }
        tex.upload();
        return true;
    }

    /** All 8 hurt rows back to vanilla's flat red. */
    private void paintVanilla() {
        NativeImageBackedTexture tex = texture();
        NativeImage img = tex == null ? null : tex.getImage();
        if (img == null) return;
        for (int y = 0; y < 8; y++) for (int x = 0; x < STEPS; x++) img.setColorArgb(x, y, VANILLA);
        tex.upload();
    }

    /**
     * The overlay texel for a flash at {@code strength} (0-1) of {@code argb}: the shader keeps alpha/255 of the
     * entity's colour, so the tint's weight (1 - alpha) is what scales.
     */
    private static int faded(int argb, float strength) {
        int tint = 255 - (argb >>> 24);
        int alpha = 255 - Math.round(tint * strength);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ render hooks (render thread, every entity)

    /** Hurt time left (1 = just hit, 0 = over) for the Fade Out animation, from the entity's hurt timer. */
    public static float hurtFade(int hurtTime, int maxHurtTime, float tickDelta) {
        float f = (hurtTime - tickDelta) / Math.max(1, maxHurtTime);
        return f < 0 ? 0 : Math.min(1, f);
    }

    /** True while the module is on (the cheap check before computing anything per entity). */
    public static boolean active() {
        HitColor h = instance;
        return h != null && h.isEnabled();
    }

    /**
     * The packed overlay UV for a hurt or dying living entity, or -1 for vanilla's. Not hurt: always vanilla (the
     * creeper's fuse flash and every other non-hurt use are left alone).
     */
    public static int overlay(LivingEntityRenderState state, float whiteOverlayProgress) {
        if (!state.hurt) return -1;
        HitColor h = instance;
        if (h == null || !h.isEnabled()) return -1;
        boolean player = state instanceof PlayerEntityRenderState;
        if (player && !h.tintSelf.get() && mc.player != null && ((PlayerEntityRenderState) state).id == mc.player.getId()) {
            return notHurt(whiteOverlayProgress);
        }
        float strength;
        if (state.deathTime > 0) {
            if (h.onDeath.is("None")) return notHurt(whiteOverlayProgress);
            strength = h.onDeath.is("Fade") ? 1f - state.deathTime / DEATH_TICKS : 1f;
        } else {
            strength = h.animation.is("Fade Out") && state instanceof EntityVisualsDuck duck ? duck.evade$hurtFade() : 1f;
        }
        // Column x holds (16 - x)/16 of the flash; below half a step there is nothing left to show.
        int column = Math.round((1f - strength) * STEPS);
        if (column >= STEPS) return notHurt(whiteOverlayProgress);
        return OverlayTexture.packUv(Math.max(0, column), player ? PLAYER_ROW : MOB_ROW);
    }

    /** Vanilla's overlay for an entity that isn't hurt (keeps the creeper's fuse flash). */
    private static int notHurt(float whiteOverlayProgress) {
        return OverlayTexture.packUv(OverlayTexture.getU(whiteOverlayProgress), NOT_HURT_ROW);
    }
}
