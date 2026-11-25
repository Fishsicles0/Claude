package net.evadeclient.cosmetics;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Atlases;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Evade's own cosmetic particle ({@code "particle": "soft"}): a soft round glow sprite blended additively (it brightens
 * what is behind it, never a hard-edged square), full-bright, fading in and out, shrinking over its life and running
 * through the effect's colours (colour over life). No collision, no registry entry: it is added straight to the
 * particle manager, so it works on any server.
 */
public final class SoftParticle extends BillboardParticle {
    static final Identifier SPRITE = Identifier.of("evade", "soft_glow");
    private static final RenderPipeline ADDITIVE = RenderPipeline.builder()
            .withLocation(Identifier.of("evade", "pipeline/soft_particle"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withVertexShader("core/particle")
            .withFragmentShader("core/particle")
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_LIGHT, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.LIGHTNING)
            .withDepthWrite(false)
            .build();
    private static final RenderType TYPE = new RenderType(true, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE, ADDITIVE);

    /** Soft particles spawned since launch (tests). */
    public static int spawnedTotal;

    private final int[] colors;
    private final float size0, size1, fade;

    private SoftParticle(ClientWorld world, double x, double y, double z, double vx, double vy, double vz, Sprite sprite,
                         int[] colors, float size, float sizeEnd, float life, float fade, float gravity) {
        super(world, x, y, z, vx, vy, vz, sprite);
        this.velocityX = vx;
        this.velocityY = vy;
        this.velocityZ = vz;
        this.colors = colors;
        this.size0 = 0.12f * size;
        this.size1 = size0 * sizeEnd;
        this.fade = MathHelper.clamp(fade, 0.02f, 0.5f);
        this.maxAge = Math.max(4, Math.round(life * 20 * (0.85f + random.nextFloat() * 0.3f)));
        this.gravityStrength = gravity / 16f;
        this.velocityMultiplier = 0.94f;
        this.collidesWithWorld = false;
        this.scale = size0;
        setColorAt(0);
    }

    /** Adds one soft particle; false if the sprite isn't there (a resource pack removed it). */
    static boolean spawn(double x, double y, double z, double vx, double vy, double vz, int[] colors, float size, float sizeEnd,
                         float life, float fade, float gravity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        Sprite sprite = sprite();
        if (sprite == null) return false;
        spawnedTotal++;
        mc.particleManager.addParticle(new SoftParticle(mc.world, x, y, z, vx, vy, vz, sprite, colors, size, sizeEnd, life, fade, gravity));
        return true;
    }

    /** The soft glow sprite from the particle atlas (keyed by its definition id, minecraft:particles), or null. */
    static Sprite sprite() {
        try {
            SpriteAtlasTexture atlas = MinecraftClient.getInstance().getAtlasManager().getAtlasTexture(Atlases.PARTICLES);
            Sprite s = atlas.getSprite(SPRITE);
            return s != null && SPRITE.equals(s.getContents().getId()) ? s : null;   // missing: the caller falls back to dust
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void tick() {
        super.tick();
        setColorAt(age / (float) maxAge);
    }

    private void setColorAt(float k) {
        // colour over life (a gradient through the colours), and brightness for the fade in / out: additive blending
        // makes a darker colour a fainter glow, so the alpha stays 1 and never cuts the sprite's soft edge
        float pos = k * (colors.length - 1);
        int i = Math.min(colors.length - 1, (int) pos), j = Math.min(colors.length - 1, i + 1);
        float f = pos - i;
        int a = colors[i], b = colors[j];
        float fadeIn = Math.min(1, k / fade), fadeOut = Math.min(1, (1 - k) / fade);
        float v = Math.max(0, Math.min(fadeIn, fadeOut));
        v = v * v * (3 - 2 * v);
        setColor(lerp(a >> 16, b >> 16, f) * v, lerp(a >> 8, b >> 8, f) * v, lerp(a, b, f) * v);
        this.scale = size0 + (size1 - size0) * k;
    }

    private static float lerp(int a, int b, float f) { return ((a & 255) + ((b & 255) - (a & 255)) * f) / 255f; }

    @Override
    protected RenderType getRenderType() { return TYPE; }

    @Override
    protected int getBrightness(float tint) { return 0xF000F0; }
}
