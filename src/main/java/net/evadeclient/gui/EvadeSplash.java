package net.evadeclient.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.evadeclient.EvadeClient;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.UiFont;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Evade's animated loading screen inside the game's own: {@link SplashScene} draws it, this class hands the scene a
 * {@link SplashScene.Canvas} on the game's {@link DrawContext} (in device pixels, so every line is crisp at any GUI
 * scale), feeds it the game's fade, progress and accessibility options, and gives it text once the font can draw.
 *
 * <p>The textures are read straight from Evade's jar ({@code assets/evade/textures/splash/}, made by
 * scripts/draw-splash.py), because during the first load the game's resources aren't available yet; the game does
 * the same for its own logo. They get a LINEAR, clamped sampler, as they are drawn at many sizes.
 *
 * <p>On the first frame it also gives the window Evade's icon (Windows and X11, where the game sets one itself).
 */
public final class EvadeSplash {
    private static final int BG = 0x0B0B10, BG_MONO = 0x050505;
    /** Texture virtual size for {@code drawTexture}: UVs are passed in 1/4096ths, so sub-texel crops stay exact. */
    private static final int R = 4096;
    private static final int[] ICON_SIZES = {16, 32, 48, 128, 256};

    private static final Identifier[] IDS = new Identifier[SplashScene.TEXTURES.length];
    private static final SplashScene SCENE = new SplashScene();
    private static final SplashScene.Frame FRAME = new SplashScene.Frame();
    private static final McCanvas CANVAS = new McCanvas();
    private static boolean loaded, failed, iconDone;
    private static Object overlay;
    private static long startMs;
    /** The last frame's fade, completion and reload state (the menu under it asks about the hand-over). */
    private static float lastAlpha = 1f;
    private static boolean lastComplete, formed;
    private static final float[] SLOT = new float[5];

    static {
        for (int i = 0; i < IDS.length; i++) IDS[i] = Identifier.of("evade", "splash/" + SplashScene.TEXTURES[i]);
    }

    private EvadeSplash() {}

    /** The loading screen's base colour (opaque ARGB; the game applies its own fade alpha). */
    public static int background() {
        return 0xFF000000 | (mono() ? BG_MONO : BG);
    }

    /**
     * The background effects and the logo, in place of the game's logo. {@code alpha} is the game's fade of the
     * whole screen; {@code progress} the real load progress.
     */
    public static void drawScene(DrawContext ctx, Object owner, float alpha, boolean reloading, boolean complete, float progress) {
        MinecraftClient mc = MinecraftClient.getInstance();
        load(mc);
        prepare(mc, owner, reloading, complete, progress);
        FRAME.alpha = alpha;
        lastAlpha = alpha;
        lastComplete = complete;
        boolean slot = flies() && slot(mc);
        FRAME.slotX = slot ? SLOT[0] : Float.NaN;
        FRAME.slotY = SLOT[1];
        FRAME.slotSize = SLOT[2];
        FRAME.handoff = slot && complete && alpha < 1f ? 1f - alpha : -1f;
        FRAME.handin = reloading && !complete && alpha < 1f ? alpha : -1f;
        if (FRAME.handin >= 0f && slot && FRAME.time < 0.6f) formed = true;
        FRAME.formed = formed;
        if (failed) return;
        draw(ctx, mc, false);
    }

    // ----- the hand-over to the main menu ----------------------------------------------------------------------

    /** The Evade menu's logo slot (device px) into {@link #SLOT}; false when the menu isn't the screen below. */
    private static boolean slot(MinecraftClient mc) {
        return mc.currentScreen instanceof EvadeTitleScreen t && t.logoSlot(SLOT);
    }

    /** Whether the logo flies between this screen and the menu (Full motion, the menu is below). */
    public static boolean flies() {
        try {
            return Motion.decorative() && slot(MinecraftClient.getInstance());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether the loading screen is over the menu now and draws the menu's logo itself (the menu leaves its own out). */
    public static boolean flying() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getOverlay() instanceof net.minecraft.client.gui.screen.SplashOverlay && overlay == mc.getOverlay() && flies();
    }

    /** Whether the loading screen has finished and is fading out over the screen below (the menu starts its entrance). */
    public static boolean handingOver() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getOverlay() instanceof net.minecraft.client.gui.screen.SplashOverlay && overlay == mc.getOverlay()
                && lastComplete && lastAlpha < 1f;
    }

    /** The progress bar and text, in place of the game's bar; {@code opacity} is the game's fade of the bar. */
    public static void drawBar(DrawContext ctx, Object owner, float opacity, boolean reloading, boolean complete, float progress) {
        MinecraftClient mc = MinecraftClient.getInstance();
        load(mc);
        prepare(mc, owner, reloading, complete, progress);
        FRAME.uiAlpha = opacity;
        draw(ctx, mc, true);
    }

    /**
     * Renders the scene as it is {@code seconds} into a loading screen, at the current window size: for tests and
     * screenshots. Consecutive calls must not go back in time (the bar eases from one call to the next).
     */
    public static void renderAt(DrawContext ctx, float seconds, float progress, boolean complete) {
        MinecraftClient mc = MinecraftClient.getInstance();
        load(mc);
        if (overlay != EvadeSplash.class || seconds < FRAME.time) {
            overlay = EvadeSplash.class;
            SCENE.reset(0);
        }
        fill(mc, seconds, true, complete, progress);
        FRAME.slotX = Float.NaN;
        FRAME.handoff = FRAME.handin = -1f;
        FRAME.formed = false;
        FRAME.alpha = 1f;
        FRAME.uiAlpha = 1f;
        ctx.fill(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), background());
        draw(ctx, mc, false);
        draw(ctx, mc, true);
    }

    private static void prepare(MinecraftClient mc, Object owner, boolean reloading, boolean complete, float progress) {
        long now = Util.getMeasuringTimeMs();
        if (owner != overlay) {
            overlay = owner;
            startMs = now;
            formed = false;
            lastComplete = false;
            lastAlpha = 1f;
            SCENE.reset(now / 1000L);
            FRAME.alpha = FRAME.uiAlpha = 1f;
        }
        fill(mc, (now - startMs) / 1000f, reloading, complete, progress);
    }

    private static void fill(MinecraftClient mc, float time, boolean reloading, boolean complete, float progress) {
        FRAME.time = time;
        FRAME.reloading = reloading;
        FRAME.complete = complete;
        FRAME.progress = progress;
        FRAME.mono = mono();
        FRAME.motion = motion();
        FRAME.width = mc.getWindow().getFramebufferWidth();
        FRAME.height = mc.getWindow().getFramebufferHeight();
    }

    private static void draw(DrawContext ctx, MinecraftClient mc, boolean bar) {
        int gs = Math.max(1, mc.getWindow().getScaleFactor());
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.scale(1f / gs, 1f / gs);
            CANVAS.begin(ctx, mc, gs);
            if (bar) SCENE.drawBar(CANVAS, FRAME);
            else SCENE.drawScene(CANVAS, FRAME);
        } finally {
            CANVAS.ctx = null;
            m.popMatrix();
        }
    }

    private static boolean mono() {
        GameOptions o = MinecraftClient.getInstance().options;
        return o != null && Boolean.TRUE.equals(o.getMonochromeLogo().getValue());
    }

    /** The panorama speed accessibility option (1 by default): how much ambient motion the screen may have. */
    private static float motion() {
        GameOptions o = MinecraftClient.getInstance().options;
        if (o == null) return 1f;
        Double v = o.getPanoramaSpeed().getValue();
        return v == null ? 1f : (float) Math.max(0, Math.min(1, v));
    }

    // ----- the canvas on the game's DrawContext -------------------------------------------------------------------

    private static final class McCanvas implements SplashScene.Canvas {
        DrawContext ctx;
        TextRenderer tr;
        int gs;
        private long fontCheckMs = Long.MIN_VALUE;
        private boolean fontReady, smooth;
        private int textGs = -1;
        private boolean textSmooth;
        private final OrderedText[] texts = new OrderedText[SplashScene.TEXT_COUNT];
        private final int[] widths = new int[SplashScene.TEXT_COUNT];

        void begin(DrawContext ctx, MinecraftClient mc, int gs) {
            this.ctx = ctx;
            this.tr = mc.textRenderer;
            this.gs = gs;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            if (x1 > x0 && y1 > y0 && argb >>> 24 != 0) ctx.fill(x0, y0, x1, y1, argb);
        }

        @Override
        public void blit(int texture, int x, int y, int w, int h, float u0, float v0, float u1, float v1, int argb) {
            if (!loaded || w <= 0 || h <= 0 || argb >>> 24 == 0) return;
            int rw = Math.max(1, Math.round((u1 - u0) * R)), rh = Math.max(1, Math.round((v1 - v0) * R));
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, IDS[texture], x, y, u0 * R, v0 * R, w, h, rw, rh, R, R, argb);
        }

        @Override
        public void softGlow(float x, float y, float rx, float ry, int rgb, float alpha) {
            if (alpha <= 0.002f) return;
            if (!UiShape.available()) { SplashScene.Canvas.super.softGlow(x, y, rx, ry, rgb, alpha); return; }
            // Additive light, dithered: an elliptical Gaussian (a small core under a wide sigma).
            // Strength folded into an opaque colour: additive light needs only colour x strength, and the shader's
            // dither then runs at full amplitude over the whole falloff (no rings in the 8-bit output).
            float k = Math.min(1f, alpha * 1.55f);
            int col = 0xFF000000 | Math.round((rgb >> 16 & 255) * k) << 16 | Math.round((rgb >> 8 & 255) * k) << 8 | Math.round((rgb & 255) * k);
            Matrix3x2fStack m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate(x, y);
                if (ry != rx) m.scale(1f, ry / rx);
                float core = rx * 0.06f;
                UiShape.shape(-core, -core, core, core).radius(core).fill(col).raw().asGlow(rx * 0.31f, false).draw(ctx);
            } finally {
                m.popMatrix();
            }
        }

        @Override
        public void vignette(int w, int h, float alpha) {
            if (!UiShape.available()) { SplashScene.Canvas.super.vignette(w, h, alpha); return; }
            float aspect = w / (float) Math.max(1, h) * 0.95f, r = h * 0.80f;
            Matrix3x2fStack m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate(w / 2f, h * 0.46f);
                m.scale(aspect, 1f);
                float x1 = -(w / 2f) / aspect, x2 = (w / 2f) / aspect, y1 = -h * 0.46f, y2 = h * 0.54f;
                UiShape.shape(x1, y1, x2, y2).raw()
                        .radial(SplashScene.argb(0x000000, alpha * 0.06f), SplashScene.argb(0x000000, alpha), -x1, -y1, r).draw(ctx);
            } finally {
                m.popMatrix();
            }
        }

        @Override
        public boolean hasTitleLogo() {
            return SLOT[4] > 0;
        }

        @Override
        public void titleLogo(float x, float y, float markSize, float alpha) {
            int design = Math.round(SLOT[4]);
            if (design <= 0 || alpha <= 0.002f) return;
            Matrix3x2fStack m = ctx.getMatrices();
            float base = net.evadeclient.util.Theme.fade;
            m.pushMatrix();
            try {
                m.translate(x, y);
                // At rest (the end of the flight) this is exactly the menu's own scale, so the hand-off is seamless.
                m.scale(markSize / design, markSize / design);
                net.evadeclient.util.Theme.fade = Math.min(1f, alpha);
                net.evadeclient.gui.title.TitleArt.lockup(ctx, 0, 0, design, net.evadeclient.util.Logo.markTint(), 0xFFF2F2F5);
            } finally {
                net.evadeclient.util.Theme.fade = base;
                m.popMatrix();
            }
        }

        @Override
        public void push() {
            ctx.getMatrices().pushMatrix();
        }

        @Override
        public void pop() {
            ctx.getMatrices().popMatrix();
        }

        @Override
        public void translate(float x, float y) {
            ctx.getMatrices().translate(x, y);
        }

        @Override
        public void scale(float s) {
            ctx.getMatrices().scale(s, s);
        }

        @Override
        public void rotate(float radians) {
            ctx.getMatrices().rotate(radians);
        }

        /**
         * Whether the font has loaded. Before the first load's font reload every glyph is the same "missing" box,
         * so a narrow and a wide letter measure the same; once real fonts are in they differ. Checked twice a
         * second (and Evade's own Inter font the same way).
         */
        @Override
        public boolean textReady() {
            if (tr == null) return false;
            long now = Util.getMeasuringTimeMs();
            if (now - fontCheckMs >= 500) {
                fontCheckMs = now;
                try {
                    fontReady = tr.getWidth("i") != tr.getWidth("W");
                    Style st = UiFont.style(UiFont.Role.LABEL, gs);
                    smooth = fontReady && tr.getWidth(Text.literal("i").setStyle(st)) != tr.getWidth(Text.literal("W").setStyle(st));
                } catch (RuntimeException e) {
                    fontReady = smooth = false;
                }
            }
            return fontReady;
        }

        private OrderedText text(int id) {
            if (textGs != gs || textSmooth != smooth) {
                java.util.Arrays.fill(texts, null);
                textGs = gs;
                textSmooth = smooth;
            }
            OrderedText t = texts[id];
            if (t == null) {
                UiFont.Role role = id >= SplashScene.T_TIP && id < SplashScene.T_PERCENT ? UiFont.Role.LABEL : UiFont.Role.SMALL;
                Style st = smooth ? UiFont.style(role, gs) : Style.EMPTY;
                t = Text.literal(SplashScene.text(id)).setStyle(st).asOrderedText();
                texts[id] = t;
                widths[id] = tr.getWidth(t);
            }
            return t;
        }

        @Override
        public int textWidth(int id) {
            text(id);
            return widths[id] * gs;
        }

        @Override
        public int textHeight() {
            return tr.fontHeight * gs;
        }

        @Override
        public void text(int id, int x, int y, int argb) {
            if (argb >>> 24 < 4) return;
            OrderedText t = text(id);
            Matrix3x2fStack m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate(x, y);
                m.scale(gs, gs);
                ctx.drawText(tr, t, 0, 0, argb, false);
            } finally {
                m.popMatrix();
            }
        }
    }

    // ----- textures and the window icon ---------------------------------------------------------------------------

    /** A jar image as a texture with LINEAR, clamped sampling (the default is NEAREST, repeated). */
    private static final class SmoothTexture extends NativeImageBackedTexture {
        SmoothTexture(String name, NativeImage image) {
            super(() -> name, image);
            sampler = RenderSystem.getSamplerCache().get(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, false);
        }
    }

    /** Reads the textures from Evade's jar once and registers them; sets the window icon once. */
    private static void load(MinecraftClient mc) {
        if (!loaded && !failed) {
            try {
                for (int i = 0; i < IDS.length; i++) {
                    NativeImage img = read(SplashScene.TEXTURES[i]);
                    mc.getTextureManager().registerTexture(IDS[i], new SmoothTexture("evade splash " + SplashScene.TEXTURES[i], img));
                }
                loaded = true;
            } catch (Exception e) {
                failed = true;
                EvadeClient.LOG.warn("Evade loading screen: couldn't load its textures", e);
            }
        }
        if (!iconDone) {
            iconDone = true;
            setWindowIcon(mc);
        }
    }

    /**
     * Gives the window Evade's icon, the way the game sets its own (Window#setIcon): only on Windows and X11 (macOS
     * uses the app bundle's icon, Wayland has no window icons).
     */
    private static void setWindowIcon(MinecraftClient mc) {
        ByteBuffer[] pixels = new ByteBuffer[ICON_SIZES.length];
        try {
            int platform = GLFW.glfwGetPlatform();
            if (platform != GLFW.GLFW_PLATFORM_WIN32 && platform != GLFW.GLFW_PLATFORM_X11) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GLFWImage.Buffer buf = GLFWImage.malloc(ICON_SIZES.length, stack);
                for (int i = 0; i < ICON_SIZES.length; i++) {
                    try (NativeImage img = read("icon_" + ICON_SIZES[i])) {
                        pixels[i] = MemoryUtil.memAlloc(img.getWidth() * img.getHeight() * 4);
                        pixels[i].asIntBuffer().put(img.copyPixelsAbgr());
                        buf.position(i);
                        buf.width(img.getWidth());
                        buf.height(img.getHeight());
                        buf.pixels(pixels[i]);
                    }
                }
                buf.position(0);
                GLFW.glfwSetWindowIcon(mc.getWindow().getHandle(), buf);
            }
        } catch (Throwable e) {
            EvadeClient.LOG.warn("Evade: couldn't set the window icon", e);
        } finally {
            for (ByteBuffer b : pixels) if (b != null) MemoryUtil.memFree(b);
        }
    }

    private static NativeImage read(String name) throws Exception {
        String path = "assets/evade/textures/splash/" + name + ".png";
        Optional<Path> p = FabricLoader.getInstance().getModContainer("evade").flatMap(m -> m.findPath(path));
        if (p.isEmpty()) throw new IllegalStateException("missing " + path);
        try (InputStream in = Files.newInputStream(p.get())) {
            return NativeImage.read(in);
        }
    }
}
