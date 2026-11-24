package net.evadeclient.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.evadeclient.EvadeClient;
import net.evadeclient.addon.AddonHudElement;
import net.evadeclient.mixin.DrawContextStateAccessor;
import net.evadeclient.mixin.GameRendererFogAccessor;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.hud.CompassHud;
import net.evadeclient.module.hud.FrameTimeHud;
import net.evadeclient.module.hud.KeystrokesHud;
import net.evadeclient.module.hud.MinimapHud;
import net.evadeclient.module.hud.PaperDollHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * HUD Refresh (Performance): Evade's corner widgets are drawn into an off-screen texture 60 or 30 times a second (and
 * at once when one changes), and every frame in between shows that texture as a single quad. At hundreds of FPS
 * the HUD then costs almost nothing.
 *
 * <p>Why a texture: in 1.21.11 every widget adds dozens of small render states (quads, text, items) to the frame's
 * GUI state, and the game sorts each one into layers and builds its vertices again every frame; with 20 to 40 widgets
 * that is most of the HUD's cost, whatever the widgets themselves do. Here the widgets are drawn into a GUI state of
 * Evade's own and rendered by a second GUI renderer into a window-sized framebuffer (through the same
 * {@code getFramebuffer} hook Render Scale uses), then composited with premultiplied alpha, so the picture is the
 * same pixel for pixel.
 *
 * <p>The texture is redrawn when it is older than the refresh period, when a widget moves, is resized, turns on or off,
 * or (text widgets, checked once a tick) its text changes, and when the window or HUD scale changes. Widgets that move
 * every frame (minimap, compass, keystrokes, the frame-time graph, the paper doll, Chroma text and other mods'
 * widgets) are drawn live on top as usual. While a screen other than chat is open everything is drawn live (settings
 * may be changing). If anything fails, the cache turns itself off for the session and the HUD is drawn as before.
 * Render thread only.
 */
public final class HudCache {
    private HudCache() {}

    /** Auto: cache only while the game runs faster than this. */
    private static final double AUTO_MIN_FPS = 90;

    private static String mode = "Every Frame";
    private static long periodNanos;
    private static boolean auto;
    /** Test hook: draw every widget every frame regardless of the setting. */
    public static boolean forceOff;
    /** Something failed: drawn live for the rest of the session. */
    private static boolean broken;

    /** Follows HUD Refresh ("Auto", "Every Frame", "60 Hz", "30 Hz"); every client tick. */
    public static void configure(String refresh) {
        if (refresh == null) refresh = "Every Frame";
        if (refresh.equals(mode)) return;
        mode = refresh;
        auto = refresh.equals("Auto");
        periodNanos = switch (refresh) {
            case "30 Hz" -> 1_000_000_000L / 30;
            case "60 Hz", "Auto" -> 1_000_000_000L / 60;
            default -> 0;
        };
        lastSignature = 0;
        if (periodNanos <= 0) release();
    }

    /** Whether the cache is used on this frame (Auto checks the frame rate). */
    public static boolean active() {
        if (periodNanos <= 0 || forceOff || broken) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getOverlay() != null) return false; // a resource reload replaces textures and fonts
        if (mc.currentScreen != null && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) return false;
        return !auto || mc.getCurrentFps() > AUTO_MIN_FPS;
    }

    /** Widgets that move every frame: always drawn live. */
    private static final Set<Class<?>> ANIMATED = Set.of(MinimapHud.class, CompassHud.class, KeystrokesHud.class,
            FrameTimeHud.class, PaperDollHud.class);

    public static boolean animated(HudElementModule el) {
        if (ANIMATED.contains(el.getClass()) || el instanceof AddonHudElement) return true;
        return el instanceof TextHudModule t && t.chroma.get();
    }

    // ------------------------------------------------------------------ the texture

    private static SimpleFramebuffer fb;
    private static GuiRenderer renderer;
    private static GuiRenderState state;
    /** While set, the game's getFramebuffer() returns the HUD texture (the second GUI renderer draws into it). */
    private static Framebuffer redirect;
    private static long lastSignature, drawnAt;
    private static long hits, redraws, live;
    private static int lastWidgets;

    /** {@code MinecraftClient.getFramebuffer()} while the cached HUD is rendered (see {@link RenderScale#redirect}). */
    static Framebuffer redirect() { return redirect; }

    private record Content(long tick, Object text) {}

    private static final Map<HudElementModule, Long> elementSigs = new IdentityHashMap<>();
    private static String lastChanged = "";

    /** The widget whose change caused the latest redraw (tests and tuning). */
    public static String lastChanged() { return lastChanged; }

    private static final Map<HudElementModule, Content> contents = new IdentityHashMap<>();

    /** Text widgets' text, read once a tick (the rate they rebuild it at); null for other widgets. */
    private static Object content(HudElementModule el) {
        if (!(el instanceof TextHudModule t)) return null;
        Content c = contents.get(el);
        if (c == null || c.tick() != EvadeClient.ticks) {
            c = new Content(EvadeClient.ticks, t.textForTest());
            contents.put(el, c);
        }
        return c.text();
    }

    /**
     * Draws {@code widgets} (the cacheable ones on screen this frame) through the texture: redrawn into it when stale,
     * then shown as one quad. {@code draw} renders one widget into a context (with the HUD's scale already applied).
     * Falls back to drawing them live when the cache can't be used.
     */
    public static void draw(DrawContext ctx, List<HudElementModule> widgets, int sw, int sh, float k,
                            java.util.function.BiConsumer<DrawContext, HudElementModule> draw) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (widgets.isEmpty()) return;
        if (!active() || !(ctx instanceof DrawContextStateAccessor acc)) {
            for (HudElementModule el : widgets) { live++; draw.accept(ctx, el); }
            return;
        }
        var win = mc.getWindow();
        int fw = win.getFramebufferWidth(), fh = win.getFramebufferHeight();
        long sig = 17;
        sig = sig * 31 + fw; sig = sig * 31 + fh; sig = sig * 31 + sw; sig = sig * 31 + sh;
        sig = sig * 31 + Float.floatToIntBits(k); sig = sig * 31 + win.getScaleFactor();
        for (HudElementModule el : widgets) {
            long e = System.identityHashCode(el);
            e = e * 31 + el.resolveX(sw); e = e * 31 + el.resolveY(sh);
            e = e * 31 + Float.floatToIntBits(el.scale.getFloat());
            e = e * 31 + el.getWidth() * 7919L + el.getHeight();
            e = e * 31 + Objects.hashCode(content(el));
            Long before = elementSigs.put(el, e);
            if (before != null && before != e) lastChanged = el.getName();
            sig = sig * 31 + e;
        }
        long now = System.nanoTime();
        try {
            if (fb == null || sig != lastSignature || now - drawnAt >= periodNanos) {
                redraw(mc, widgets, fw, fh, k, draw);
                lastSignature = sig;
                drawnAt = now;
                redraws++;
            } else {
                hits++;
            }
            lastWidgets = widgets.size();
            // The texture covers the window: GUI coordinates 0..fw/scale, which can be a fraction past the last whole one.
            double gs = win.getScaleFactor();
            int gw = ctx.getScaledWindowWidth(), gh = ctx.getScaledWindowHeight();
            float u2 = (float) Math.min(1.0, gw * gs / fw), v2 = (float) Math.min(1.0, gh * gs / fh);
            acc.evade$state().addSimpleElement(new TexturedQuadGuiElementRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                    TextureSetup.of(fb.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.NEAREST)),
                    new Matrix3x2f(), 0, 0, gw, gh, 0f, u2, 1f, 1f - v2, 0xFFFFFFFF, null));
        } catch (RuntimeException e) {
            broken = true;
            redirect = null;
            EvadeClient.LOG.error("HUD cache: failed, drawing the HUD every frame from now on", e);
            release();
            for (HudElementModule el : widgets) { live++; draw.accept(ctx, el); }
        }
    }

    private static void redraw(MinecraftClient mc, List<HudElementModule> widgets, int fw, int fh, float k,
                               java.util.function.BiConsumer<DrawContext, HudElementModule> draw) {
        if (fb == null) fb = new SimpleFramebuffer("Evade HUD cache", fw, fh, true);
        else if (fb.textureWidth != fw || fb.textureHeight != fh) fb.resize(fw, fh);
        if (renderer == null) {
            state = new GuiRenderState();
            // Special elements (3D models in the GUI) have no renderer here: widgets that draw them are kept live.
            renderer = new GuiRenderer(state, mc.getBufferBuilders().getEntityVertexConsumers(),
                    mc.gameRenderer.getEntityRenderCommandQueue(), mc.gameRenderer.getEntityRenderDispatcher(), List.of());
        }
        DrawContext hctx = new DrawContext(mc, state, -1, -1);
        hctx.getMatrices().pushMatrix();
        hctx.getMatrices().scale(k, k);
        try {
            for (HudElementModule el : widgets) draw.accept(hctx, el);
        } finally {
            hctx.getMatrices().popMatrix();
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(fb.getColorAttachment(), 0, fb.getDepthAttachment(), 1.0);
        FogRenderer fog = ((GameRendererFogAccessor) mc.gameRenderer).evade$fogRenderer();
        redirect = fb;
        try {
            renderer.render(fog.getFogBuffer(FogRenderer.FogType.NONE));
            renderer.incrementFrame();
        } finally {
            redirect = null;
        }
    }

    /** Frees the texture and the second GUI renderer (HUD Refresh set to Every Frame, or a failure). */
    public static void release() {
        lastSignature = 0;
        contents.clear();
        elementSigs.clear();
        if (fb != null) { fb.delete(); fb = null; }
        if (renderer != null) {
            try { renderer.close(); } catch (RuntimeException ignored) {}
            renderer = null;
            state = null;
        }
    }

    // ------------------------------------------------------------------ status and tests

    /** {frames shown from the texture, redraws of it, widget draws done live} since the last call (resets). */
    public static long[] takeCounts() {
        long[] c = {hits, redraws, live};
        hits = redraws = live = 0;
        return c;
    }

    /** Widgets in the texture at the last frame (tests). */
    public static int lastWidgets() { return lastWidgets; }

    public static boolean broken() { return broken; }

    private static long statusHits, statusRedraws, statusAt;
    private static String statusText = "";

    /** "Redrawn 31 times a second, reused 270 times" for Performance, refreshed once a second. */
    public static String status() {
        if (periodNanos <= 0) return "Off: every widget is drawn every frame";
        if (broken) return "Unavailable: drawing every frame";
        if (auto && MinecraftClient.getInstance().getCurrentFps() <= AUTO_MIN_FPS) return "Waiting: the game runs under " + (int) AUTO_MIN_FPS + " FPS";
        long now = System.currentTimeMillis();
        if (now - statusAt >= 1000) {
            long h = hits - statusHits, r = redraws - statusRedraws;
            double secs = statusAt == 0 ? 1 : (now - statusAt) / 1000.0;
            statusHits = hits;
            statusRedraws = redraws;
            statusAt = now;
            if (h < 0 || r < 0) { h = 0; r = 0; } // counters were taken by a test meanwhile
            statusText = h + r == 0 ? "Nothing cached yet (in a world)"
                    : String.format(Locale.ROOT, "Redrawn %.0f times a second, reused %.0f", r / secs, h / secs);
        }
        return statusText;
    }
}
