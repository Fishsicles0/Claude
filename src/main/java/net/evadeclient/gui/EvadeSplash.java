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

    private EvadeSplash() {}

    /** The loading screen's background (RGB; the game adds its own fade alpha). */
    public static final int BACKGROUND = BG;

    /** Evade's mark and wordmark, {@code height} tall, centred on {@code centerX}; {@code color} carries the fade. */
    public static void drawLogo(DrawContext ctx, int centerX, int top, int height, int color) {
        load(MinecraftClient.getInstance());
        if (failed || height <= 0) return;
        int mw = Math.round(height * markW / (float) markH);
        int wh = Math.round(height * 0.62f), ww = Math.round(wh * wordW / (float) wordH);
        int total = mw + height / 3 + ww, x = centerX - total / 2;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, MARK, x, top, 0, 0, mw, height, markW, markH, markW, markH, color);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, WORD, x + mw + height / 3, top + (height - wh) / 2, 0, 0, ww, wh, wordW, wordH, wordW, wordH, color);
    }

    /** A slim red bar on a dark track in place of the game's white one; {@code opacity} is the game's fade. */
    public static void drawBar(DrawContext ctx, int minX, int minY, int maxX, int maxY, float opacity, float progress) {
        int a = Math.round(MathHelper.clamp(opacity, 0f, 1f) * 255);
        if (a <= 0) return;
        int midY = (minY + maxY) / 2, half = Math.max(1, (maxY - minY) / 4);
        int y1 = midY - half, y2 = midY + half;
        ctx.fill(minX, y1, maxX, y2, (Math.round(a * 0.9f) << 24) | TRACK);
        int fill = Math.round((maxX - minX) * MathHelper.clamp(progress, 0f, 1f));
        if (fill > 0) ctx.fill(minX, y1, minX + fill, y2, (a << 24) | ACCENT);
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
