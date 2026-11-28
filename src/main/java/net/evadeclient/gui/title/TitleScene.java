package net.evadeclient.gui.title;

import net.evadeclient.gui.Motion;
import net.evadeclient.mixin.RotatingCubeMapAccessor;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.RotatingCubeMapRenderer;

/**
 * The living part of the main menu's backdrop: the game's panorama drawn with a gentle mouse parallax (it keeps the
 * game's own rotation, so other menus pick up where it left off), and soft embers in the accent colour drifting up
 * through three depth layers. Everything is computed from the clock (no per-particle state), so it costs a few dozen
 * textured quads a frame and looks the same at any frame rate. With motion reduced it all holds still.
 */
public final class TitleScene {
    /** Embers on screen at once (spread over three depth layers). */
    static final int EMBERS = 28;
    /** Embers in the light variant (each a single small quad). */
    static final int EMBERS_LIGHT = 12;
    /** Most the panorama turns and tilts with the mouse, in degrees. */
    private static final float YAW_RANGE = 3.2f, PITCH_RANGE = 1.8f;

    private final Motion.Spring mx = new Motion.Spring(0f), my = new Motion.Spring(0f);
    private long last = System.nanoTime();

    /** Follows the mouse: offsets ease toward its position (-1..1 from the centre). */
    public void update(double mouseX, double mouseY, int w, int h, boolean parallax, boolean motion) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        float tx = 0f, ty = 0f;
        if (parallax && motion && w > 0 && h > 0) {
            tx = (float) Math.max(-1, Math.min(1, (mouseX - w / 2.0) / (w / 2.0)));
            ty = (float) Math.max(-1, Math.min(1, (mouseY - h / 2.0) / (h / 2.0)));
        }
        if (motion) {
            mx.step(tx, 3.2f, 1f, dt);
            my.step(ty, 3.2f, 1f, dt);
        } else {
            mx.set(tx);
            my.set(ty);
        }
        ox = mx.x;
        oy = my.x;
    }

    private static float ox, oy;

    /**
     * The parallax shift of a depth plane, in design px: {@code depth} px at the screen's edge, against the cursor
     * (nearer planes move more; a negative depth floats with it). 0 while the scene is still.
     */
    public static float planeX(float depth) { return -ox * depth; }

    public static float planeY(float depth) { return -oy * depth * 0.6f; }

    /** The parallax offset, -1..1. */
    public float offX() { return mx.x; }
    public float offY() { return my.x; }

    /**
     * The panorama with parallax: the game's cube map at its current turn plus the mouse offset, then its overlay.
     * Advances the game's rotation like vanilla does when {@code rotate}. Falls back to the vanilla call if the
     * accessor isn't there (another mod replaced the renderer).
     */
    public void panorama(DrawContext ctx, int w, int h, boolean rotate, boolean overlay) {
        MinecraftClient mc = MinecraftClient.getInstance();
        RotatingCubeMapRenderer r = mc.gameRenderer.getRotatingPanoramaRenderer();
        if (!(r instanceof RotatingCubeMapAccessor a) || a.evade$cubeMap() == null) {
            r.render(ctx, w, h, rotate);
            return;
        }
        float turn = a.evade$turn();
        if (rotate) {
            float step = (float) (mc.getRenderTickCounter().getFixedDeltaTicks() * mc.options.getPanoramaSpeed().getValue());
            turn += step * 0.1f;
            if (turn > 360f) turn -= 360f;
            a.evade$setTurn(turn);
        }
        a.evade$cubeMap().draw(mc, 10f + my.x * PITCH_RANGE, -turn - mx.x * YAW_RANGE);
        // The game's soft overlay; the light variant leaves it out (its own veil covers the same ground in one pass).
        if (overlay) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, RotatingCubeMapRenderer.OVERLAY_TEXTURE, 0, 0, 0f, 0f, w, h, 16, 128, 16, 128);
    }

    /**
     * Embers drifting up: each has a lane, speed, size and sway derived from its index; its height is the clock
     * modulo its period. Near ones are bigger, faster, brighter and shift more with the parallax. They fade in at
     * the bottom and out near the top. {@code alpha} scales them all (the intro).
     */
    public void embers(DrawContext ctx, int w, int h, float alpha, boolean motion, boolean light) {
        if (alpha <= 0.01f || w <= 0 || h <= 0) return;
        int count = light ? EMBERS_LIGHT : EMBERS;
        double t = motion ? Intro.now() / 1000.0 : 40.0;
        float scale = Math.max(0.7f, Math.min(1.3f, h / 360f));
        for (int i = 0; i < count; i++) {
            int layer = i % 3; // 0 far, 1 mid, 2 near
            float depth = 0.45f + 0.35f * layer;
            float r1 = hash(i * 3 + 1), r2 = hash(i * 7 + 2), r3 = hash(i * 11 + 3);
            double period = (26.0 - layer * 6.0) * (0.8 + 0.4 * r2);
            double phase = (t / period + r1) % 1.0;
            float y = (float) (h * 1.08 - phase * h * 1.2);
            float sway = (float) Math.sin(t * (0.25 + 0.2 * r3) + i) * 10f * depth;
            float x = r3 * (w + 40) - 20 + sway - mx.x * 14f * depth;
            y -= my.x * 8f * depth;
            float life = (float) phase;
            float fade = Math.min(1f, life / 0.15f) * Math.min(1f, (1f - life) / 0.35f);
            float twinkle = motion ? 0.75f + 0.25f * (float) Math.sin(t * (1.3 + r2) + i * 1.7) : 1f;
            float a = alpha * fade * twinkle * (0.20f + 0.22f * layer);
            if (a <= 0.01f) continue;
            int size = Math.max(2, Math.round((2f + 1.5f * layer + 2f * r1) * scale));
            int color = layer == 2 ? Theme.RED_BRIGHT : Theme.RED;
            int cx = Math.round(x), cy = Math.round(y);
            // A soft halo and a small hot core (light: one quad).
            if (!light) TitleArt.glow(ctx, cx, cy, size * 5, size * 5, Theme.alpha(color, a * 0.45f));
            else TitleArt.glow(ctx, cx, cy, size * 3, size * 3, Theme.alpha(color, a * 0.6f));
            if (!light) TitleArt.glow(ctx, cx, cy, size, size, Theme.alpha(Theme.lerpColor(color, 0xFFFFFFFF, 0.35f), Math.min(1f, a * 1.6f)));
        }
    }

    /** A stable pseudo-random 0..1 for {@code n}. */
    static float hash(int n) {
        int x = n * 0x45d9f3b;
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        x = (x >>> 16) ^ x;
        return (x & 0xFFFF) / 65535f;
    }
}
