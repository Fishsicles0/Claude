package net.evadeclient.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.RenderScaleWorldRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Render Scale (Performance): the 3D world is drawn into a smaller framebuffer and scaled up to the window before the
 * HUD, chat, menus and text are drawn, so those stay sharp at full resolution while the world costs a fraction of the
 * pixels. Laptop graphics are mostly limited by how many pixels they fill, so this is the biggest single FPS win there.
 *
 * <p>How: {@code GameRenderer.render} marks the world section (from {@code renderWorld} up to just after the fog
 * buffer rotates, which is after the entity-outline blit, the vanilla post effect and Motion Blur). While it is marked,
 * {@code MinecraftClient.getFramebuffer()} returns the scaled framebuffer, so everything the world pass draws (terrain,
 * entities, sky, clouds, weather, particles, the hand, the block outline, waypoints and other world-space overlays,
 * the transparency and entity-outline post chains) lands in it at the smaller size: every render pass sets its
 * viewport from its target, and the frame graph sizes its own targets from that framebuffer. Then the scaled image is
 * drawn over the real main framebuffer (bilinear or nearest, optionally with a light contrast-adaptive sharpen), and
 * the rest of the frame (GUI, screens, menu blur, toasts) runs against the full-size framebuffer as usual. So
 * screenshots, Clips, replay renders and anything reading the main framebuffer after the frame see a full window-size
 * image; mouse and GUI coordinates never change.
 *
 * <p>At 100% (or with Performance off, a shader pack on or a replay rendering) nothing is marked: the getFramebuffer
 * hook is one static boolean read and the scaled framebuffer is freed. The framebuffer is created once and only
 * recreated when the window or the scale changes. Render thread only.
 */
public final class RenderScale {
    private RenderScale() {}

    /** Light contrast-adaptive sharpen; samples its four neighbours one source texel away. */
    private static final RenderPipeline SHARPEN = RenderPipeline.builder()
            .withLocation(Identifier.of("evade", "pipeline/render_scale_sharpen"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("evade", "core/render_scale_sharpen"))
            .withSampler("InSampler")
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();
    /** A plain full-screen copy (vanilla's own blit pipeline: no blending, writes colour and alpha). */
    private static final RenderPipeline PLAIN = RenderPipelines.TRACY_BLIT;
    private static final Supplier<String> LABEL = () -> "Evade render scale upscale";

    /** Wanted scale (1 = off), set every client tick by Performance. */
    private static double scale = 1;
    private static boolean nearest, sharpen = true;

    /** True only while the world pass runs into the scaled framebuffer: getFramebuffer() returns it then. */
    private static boolean redirect;
    private static SimpleFramebuffer scaled;
    /** The entity-outline framebuffer was resized to the scaled size (and must go back to the window size). */
    private static boolean outlineScaled;
    private static boolean sharpenBroken;
    /** Size of the world pass on the last scaled frame, and of the window it was scaled to (for status and tests). */
    private static int lastW, lastH, lastOutW, lastOutH;
    private static long scaledFrames;

    // ------------------------------------------------------------------ settings (Performance, every client tick)

    /**
     * Sets what the next frames use: {@code scale} in (0, 1], 1 meaning off. Turning it off frees the scaled
     * framebuffer straight away (client tick = render thread).
     */
    public static void configure(double scale, boolean nearest, boolean sharpen) {
        RenderScale.scale = scale >= 0.999 ? 1 : Math.max(0.25, scale);
        RenderScale.nearest = nearest;
        RenderScale.sharpen = sharpen;
        if (RenderScale.scale >= 1) release();
    }

    /** Whether the world is drawn below full resolution right now (configured below 100%). */
    public static boolean enabled() { return scale < 1; }

    // ------------------------------------------------------------------ frame (GameRenderer mixin)

    /** {@code MinecraftClient.getFramebuffer()} return value hook: the scaled framebuffer while the world pass runs. */
    public static Framebuffer redirect(Framebuffer main) {
        return redirect ? scaled : main;
    }

    /** Right before {@code GameRenderer.renderWorld} in {@code GameRenderer.render}. */
    public static void beginWorld(MinecraftClient mc) {
        redirect = false;
        if (scale >= 1) return;
        if (mc.gameRenderer == null || mc.gameRenderer.isRenderingPanorama()) return;
        Framebuffer main = mc.getFramebuffer();
        int w = main.textureWidth, h = main.textureHeight;
        if (w < 2 || h < 2 || main.getColorAttachment() == null) return;
        int sw = Math.max(1, (int) Math.round(w * scale)), sh = Math.max(1, (int) Math.round(h * scale));
        if (sw >= w && sh >= h) return;
        try {
            if (scaled == null) scaled = new SimpleFramebuffer("Evade Render Scale", sw, sh, true);
            else if (scaled.textureWidth != sw || scaled.textureHeight != sh) scaled.resize(sw, sh);
            resizeOutline(mc, sw, sh);
        } catch (RuntimeException e) {
            EvadeClient.LOG.error("Render Scale: couldn't create the {}x{} world framebuffer, drawing at full resolution", sw, sh, e);
            release();
            scale = 1;
            return;
        }
        lastW = sw;
        lastH = sh;
        lastOutW = w;
        lastOutH = h;
        redirect = true;
    }

    /**
     * Right after the fog buffer rotates in {@code GameRenderer.render}: the world, the entity outlines, the vanilla
     * post effect and Motion Blur are in the scaled framebuffer. Scale it up over the real one; the GUI follows.
     */
    public static void endWorld(MinecraftClient mc) {
        if (!redirect) return;
        redirect = false;
        Framebuffer main = mc.getFramebuffer();
        if (scaled == null || scaled.getColorAttachmentView() == null || main.getColorAttachmentView() == null) return;
        GpuDevice device = RenderSystem.getDevice();
        RenderPipeline pipeline = PLAIN;
        if (sharpen && !sharpenBroken) {
            // Cached after the first frame (one map lookup); invalid only if the shader failed to compile.
            if (device.precompilePipeline(SHARPEN).isValid()) pipeline = SHARPEN;
            else {
                sharpenBroken = true;
                EvadeClient.LOG.warn("Render Scale: the sharpen shader didn't compile, scaling without it");
            }
        }
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(LABEL, main.getColorAttachmentView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", scaled.getColorAttachmentView(),
                    RenderSystem.getSamplerCache().get(nearest ? FilterMode.NEAREST : FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        scaledFrames++;
    }

    /** The entity-outline framebuffer follows the world pass's size (the game re-creates it at window size on resize). */
    private static void resizeOutline(MinecraftClient mc, int w, int h) {
        if (mc.worldRenderer == null) return;
        Framebuffer outline = ((RenderScaleWorldRendererAccessor) mc.worldRenderer).evade$entityOutlineFramebuffer();
        if (outline == null) return;
        if (outline.textureWidth != w || outline.textureHeight != h) outline.resize(w, h);
        outlineScaled = true;
    }

    /** Frees the scaled framebuffer and puts the entity-outline framebuffer back to the window size. */
    private static void release() {
        redirect = false;
        if (scaled == null && !outlineScaled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (scaled != null) {
            scaled.delete();
            scaled = null;
        }
        if (outlineScaled && mc != null && mc.worldRenderer != null && mc.getWindow() != null) {
            outlineScaled = false;
            Framebuffer outline = ((RenderScaleWorldRendererAccessor) mc.worldRenderer).evade$entityOutlineFramebuffer();
            int w = mc.getFramebuffer().textureWidth, h = mc.getFramebuffer().textureHeight;
            if (outline != null && w > 0 && h > 0 && (outline.textureWidth != w || outline.textureHeight != h)) outline.resize(w, h);
        }
    }

    // ------------------------------------------------------------------ status and tests

    /** The world-pass size of the last scaled frame, {width, height, windowWidth, windowHeight}; zeros if none yet. */
    public static int[] lastSize() { return new int[]{lastW, lastH, lastOutW, lastOutH}; }

    /** The scaled framebuffer, or null when Render Scale isn't in use (tests). */
    public static Framebuffer scaledFramebuffer() { return scaled; }

    /** Frames scaled up so far (tests: it grows only while Render Scale is drawing). */
    public static long scaledFrames() { return scaledFrames; }

    /** Whether the world pass is running into the scaled framebuffer right now. */
    public static boolean inScaledPass() { return redirect; }
}
