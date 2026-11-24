package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Evade's loading screen, drawn over the game's own at the end of every loading frame: Evade's dark background, the
 * Evade mark and wordmark, and a red progress bar, fading out exactly when the game's screen does.
 *
 * <p>The logo images are read straight from Evade's jar, because during the first load the game's resources (and
 * so Evade's textures) aren't available yet; the game does the same for its own logo.
 */
public final class EvadeSplash {
    private static final Identifier MARK = Identifier.of("evade", "splash/mark");
    private static final Identifier WORD = Identifier.of("evade", "splash/word");
    private static final int BG = 0x0B0B10, ACCENT = 0xE11D2E, TRACK = 0x26262E;
    private static boolean loaded, failed;
    private static int markW, markH, wordW, wordH;
    private static float shown;

    private EvadeSplash() {}

    /**
     * Called at the end of the game's loading-screen render. {@code progress} is the game's smoothed progress,
     * {@code completeTime}/{@code startTime} its fade timestamps (-1 when unset), {@code reloading} true for a
     * resource-pack reload (which fades in over the current screen).
     */
    public static void render(DrawContext ctx, float progress, long completeTime, long startTime, boolean reloading) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        float done = completeTime > -1L ? (now - completeTime) / 1000f : -1f;
        float start = startTime > -1L ? (now - startTime) / 500f : -1f;
        float alpha;
        if (done >= 1f) alpha = 1f - MathHelper.clamp(done - 1f, 0f, 1f);
        else if (reloading) alpha = MathHelper.clamp(start, 0f, 1f);
        else alpha = 1f;
        if (alpha <= 0f) return;
        int a = Math.round(alpha * 255);

        ctx.fill(0, 0, w, h, (a << 24) | BG);
        // a soft red glow behind the logo
        int glowR = Math.min(w, h) / 2;
        for (int i = 6; i >= 1; i--) {
            int r = glowR * i / 6, ga = Math.round(a * 0.035f);
            ctx.fill(w / 2 - r, h / 2 - r * 2 / 3, w / 2 + r, h / 2 + r * 2 / 3, (ga << 24) | ACCENT);
        }

        load(mc);
        int logoH = Math.max(16, Math.min(h / 7, w / 14));
        int cy = h / 2 - logoH / 2 - 6;
        if (!failed) {
            int mw = Math.round(logoH * markW / (float) markH);
            int ww = Math.round(logoH * 0.62f * wordW / (float) wordH), wh = Math.round(logoH * 0.62f);
            int total = mw + logoH / 3 + ww, x = w / 2 - total / 2;
            int color = (a << 24) | 0xFFFFFF;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, MARK, x, cy, 0, 0, mw, logoH, markW, markH, markW, markH, color);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, WORD, x + mw + logoH / 3, cy + (logoH - wh) / 2, 0, 0, ww, wh, wordW, wordH, wordW, wordH, color);
        }

        // progress: eased so it never jumps backwards
        shown = Math.max(shown * (done > -1f ? 0f : 1f), MathHelper.clamp(progress, 0f, 1f));
        int barW = Math.min(w * 2 / 5, 240), barH = Math.max(2, logoH / 14);
        int bx = w / 2 - barW / 2, by = cy + logoH + Math.max(10, logoH / 2);
        ctx.fill(bx, by, bx + barW, by + barH, (Math.round(a * 0.9f) << 24) | TRACK);
        int fill = Math.round(barW * (done > -1f ? 1f : shown));
        if (fill > 0) ctx.fill(bx, by, bx + fill, by + barH, (a << 24) | ACCENT);
    }

    /** Reads the logo images from Evade's jar once and registers them as textures. */
    private static void load(MinecraftClient mc) {
        if (loaded || failed) return;
        try {
            NativeImage mark = read("assets/evade/textures/gui/logo_mark_brand.png");
            NativeImage word = read("assets/evade/textures/gui/logo_word.png");
            markW = mark.getWidth(); markH = mark.getHeight();
            wordW = word.getWidth(); wordH = word.getHeight();
            mc.getTextureManager().registerTexture(MARK, new NativeImageBackedTexture(() -> "evade splash mark", mark));
            mc.getTextureManager().registerTexture(WORD, new NativeImageBackedTexture(() -> "evade splash word", word));
            loaded = true;
        } catch (Exception e) {
            failed = true;
            EvadeClient.LOG.warn("Evade loading screen: couldn't load the logo", e);
        }
    }

    private static NativeImage read(String path) throws Exception {
        Optional<Path> p = FabricLoader.getInstance().getModContainer("evade").flatMap(m -> m.findPath(path));
        if (p.isEmpty()) throw new IllegalStateException("missing " + path);
        try (InputStream in = Files.newInputStream(p.get())) {
            return NativeImage.read(in);
        }
    }
}
