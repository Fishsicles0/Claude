package net.evadeclient.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.RenderScaleWorldRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;

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
 * drawn over the real main framebuffer by the {@link Upscaler} (Evade's edge-aware upscaler, bilinear or nearest), and
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

    private static final Supplier<String> LABEL = () -> "Evade render scale upscale";

    /** Wanted scale (1 = off), set every client tick by Performance. */
    private static double scale = 1;
    private static Upscaler.Mode mode = Upscaler.Mode.SMOOTH;
    private static boolean sharpen = true;

    /** True only while the world pass runs into the scaled framebuffer: getFramebuffer() returns it then. */
    private static boolean redirect;
    private static SimpleFramebuffer scaled;
    /** The real main framebuffer of the frame being scaled: only that one is swapped for the scaled one. */
    private static Framebuffer realMain;
    /** The entity-outline framebuffer was resized to the scaled size (and must go back to the window size). */
    private static boolean outlineScaled;
    /** Render Scale can't work in this session (its hooks aren't applied, or the framebuffer couldn't be made). */
    private static boolean broken;
    /** Size of the world pass on the last scaled frame, and of the window it was scaled to (for status and tests). */
    private static int lastW, lastH, lastOutW, lastOutH;
    private static long scaledFrames;

    // ------------------------------------------------------------------ settings (Performance, every client tick)

    /**
     * Sets what the next frames use: {@code scale} in (0, 1], 1 meaning off. Turning it off frees the scaled
     * framebuffer straight away (client tick = render thread).
     */
    public static void configure(double scale, boolean nearest, boolean sharpen) {
        configure(scale, nearest ? Upscaler.Mode.SHARP : Upscaler.Mode.SMOOTH, sharpen);
    }

    /** Like {@link #configure(double, boolean, boolean)} with an {@link Upscaler} mode (Evade, Smooth or Sharp). */
    public static void configure(double scale, Upscaler.Mode mode, boolean sharpen) {
        RenderScale.scale = scale >= 0.999 ? 1 : Math.max(0.25, scale);
        RenderScale.mode = mode == null ? Upscaler.Mode.SMOOTH : mode;
        RenderScale.sharpen = sharpen;
        if (RenderScale.scale >= 1) release();
    }

    /** The upscaler in use (tests and the status line). */
    public static Upscaler.Mode mode() { return mode; }

    /** Whether the world is drawn below full resolution right now (configured below 100%). */
    public static boolean enabled() { return scale < 1; }

    // ------------------------------------------------------------------ frame (GameRenderer mixin)

    /**
     * {@code MinecraftClient.getFramebuffer()} return value hook: the scaled framebuffer while the world pass runs.
     * Only the game's own main framebuffer is swapped: if another hook already handed out a framebuffer of its own
     * (a projection effect drawing the world into its own target, say), that one is left alone.
     */
    public static Framebuffer redirect(Framebuffer main) {
        Framebuffer hud = HudCache.redirect(); // the HUD cache's own GUI pass draws into its texture
        if (hud != null) return hud;
        return redirect && main == realMain ? scaled : main;
    }

    /** Right before {@code GameRenderer.renderWorld} in {@code GameRenderer.render}. */
    public static void beginWorld(MinecraftClient mc) {
        redirect = false;
        if (scale >= 1 || broken) return;
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
            broken = true;
            release();
            return;
        }
        realMain = main;
        redirect = true;
        if (mc.getFramebuffer() != scaled) {
            // The getFramebuffer hook isn't applied (mixin not registered): drawing on would scale an empty image.
            redirect = false;
            realMain = null;
            broken = true;
            EvadeClient.LOG.error("Render Scale: the framebuffer hook isn't active, drawing at full resolution");
            release();
            return;
        }
        lastW = sw;
        lastH = sh;
        lastOutW = w;
        lastOutH = h;
    }

    /**
     * Right after the fog buffer rotates in {@code GameRenderer.render}: the world, the entity outlines, the vanilla
     * post effect and Motion Blur are in the scaled framebuffer. Scale it up over the real one; the GUI follows.
     */
    public static void endWorld(MinecraftClient mc) {
        if (!redirect) return;
        redirect = false;
        Framebuffer main = realMain != null ? realMain : mc.getFramebuffer();
        realMain = null;
        if (scaled == null || scaled.getColorAttachmentView() == null || main.getColorAttachmentView() == null) return;
        GpuDevice device = RenderSystem.getDevice();
        RenderPipeline pipeline = Upscaler.pipeline(device, mode, sharpen);
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(LABEL, main.getColorAttachmentView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", scaled.getColorAttachmentView(),
                    RenderSystem.getSamplerCache().get(Upscaler.filter(mode)));
            pass.draw(0, 3);
        }
        scaledFrames++;
    }

    /** The entity-outline framebuffer follows the world pass's size (the game re-creates it at window size on resize). */
    private static void resizeOutline(MinecraftClient mc, int w, int h) {
        if (!((Object) mc.worldRenderer instanceof RenderScaleWorldRendererAccessor acc)) return;
        Framebuffer outline = acc.evade$entityOutlineFramebuffer();
        if (outline == null) return;
        if (outline.textureWidth != w || outline.textureHeight != h) outline.resize(w, h);
        outlineScaled = true;
    }

    /** Frees the scaled framebuffer and puts the entity-outline framebuffer back to the window size. */
    private static void release() {
        redirect = false;
        realMain = null;
        if (scaled == null && !outlineScaled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (scaled != null) {
            scaled.delete();
            scaled = null;
        }
        if (outlineScaled && mc != null && (Object) mc.worldRenderer instanceof RenderScaleWorldRendererAccessor acc) {
            outlineScaled = false;
            Framebuffer outline = acc.evade$entityOutlineFramebuffer();
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

    /** Render Scale gave up for this session (hooks missing or no framebuffer); the world is drawn at full resolution. */
    public static boolean broken() { return broken; }

    /** Whether the world pass is running into the scaled framebuffer right now. */
    public static boolean inScaledPass() { return redirect; }
}
