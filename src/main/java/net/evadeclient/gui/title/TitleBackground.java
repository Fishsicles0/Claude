package net.evadeclient.gui.title;

import net.evadeclient.EvadeClient;
import net.evadeclient.util.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The Title Screen's non-panorama backgrounds: Evade's animated backdrop (slow drifting accent light over a moving
 * grid), the plain Evade grid, a solid colour, or your own picture from {@code config/evade/backgrounds/*.png}
 * (cover-fitted, loaded once and reloaded when the file changes).
 */
public final class TitleBackground {
    private TitleBackground() {}

    private static final Identifier IMAGE_ID = Identifier.of("evade", "title_background");
    private static NativeImageBackedTexture texture;
    private static String loadedKey;
    private static int texW, texH;
    private static String failedKey;

    /** The folder custom backgrounds are read from (created on first use). */
    public static Path folder() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("backgrounds");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // Read-only config folder: the list is simply empty.
        }
        return dir;
    }

    /** The .png files in {@link #folder()}, by name. */
    public static List<String> images() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder())) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .map(p -> p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
        } catch (IOException ignored) {
            // No folder: no images.
        }
        return out;
    }

    /** {@code wanted} if it exists in the folder, else the first image there, else null. */
    public static String resolve(String wanted) {
        List<String> all = images();
        if (all.isEmpty()) return null;
        if (wanted != null) for (String n : all) if (n.equalsIgnoreCase(wanted)) return n;
        return all.getFirst();
    }

    /** The plain Evade backdrop: a dark gradient with a faint grid (the Title Screen's old "Panorama off" look). */
    public static void grid(DrawContext ctx, int w, int h, float offset) {
        ctx.fillGradient(0, 0, w, h, 0xFF0D0D11, 0xFF060608);
        int step = 32;
        int o = Math.floorMod(Math.round(offset), step);
        int ox = (w % step) / 2 + o, oy = (h % step) / 2 + o;
        for (int x = ox - step; x < w; x += step) if (x >= 0) ctx.fill(x, 0, x + 1, h, 0x07FFFFFF);
        for (int y = oy - step; y < h; y += step) if (y >= 0) ctx.fill(0, y, w, y + 1, 0x07FFFFFF);
    }

    /** Evade Animated: the grid drifting slowly with two soft accent lights wandering across it. */
    public static void animated(DrawContext ctx, int w, int h) {
        double t = System.currentTimeMillis() / 1000.0;
        boolean anim = TitleArt.anims();
        grid(ctx, w, h, anim ? (float) (t * 4 % 32) : 0);
        double a = anim ? t : 0;
        int x1 = (int) (w * (0.30 + 0.18 * Math.sin(a * 0.11))), y1 = (int) (h * (0.35 + 0.15 * Math.cos(a * 0.13)));
        int x2 = (int) (w * (0.72 + 0.15 * Math.cos(a * 0.09))), y2 = (int) (h * (0.70 + 0.12 * Math.sin(a * 0.12)));
        TitleArt.glow(ctx, x1, y1, Math.round(w * 0.9f), Math.round(h * 0.9f), Theme.alpha(Theme.RED, 0.20f));
        TitleArt.glow(ctx, x2, y2, Math.round(w * 0.7f), Math.round(h * 0.7f), Theme.alpha(Theme.RED_BRIGHT, 0.12f));
    }

    /**
     * Your picture, scaled to cover the screen and centred. Returns false (draw something else) when there is no
     * usable image.
     */
    public static boolean image(DrawContext ctx, int w, int h, String wanted) {
        String name = resolve(wanted);
        if (name == null || !load(name)) return false;
        float s = Math.max(w / (float) texW, h / (float) texH);
        int dw = Math.round(texW * s), dh = Math.round(texH * s);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, IMAGE_ID, (w - dw) / 2, (h - dh) / 2, 0, 0, dw, dh, texW, texH, texW, texH);
        return true;
    }

    /** Loads {@code name} into the background texture unless it is already loaded and unchanged. */
    private static boolean load(String name) {
        Path file = folder().resolve(name);
        String key;
        try {
            key = name + "@" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return false;
        }
        if (key.equals(loadedKey) && texture != null) return true;
        if (key.equals(failedKey)) return false;
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage img = NativeImage.read(in);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (texture != null) mc.getTextureManager().destroyTexture(IMAGE_ID);
            texW = img.getWidth();
            texH = img.getHeight();
            texture = new NativeImageBackedTexture(() -> "evade_title_background", img);
            mc.getTextureManager().registerTexture(IMAGE_ID, texture);
            loadedKey = key;
            return true;
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Title Screen couldn't load background {}", file, e);
            failedKey = key;
            texture = null;
            loadedKey = null;
            return false;
        }
    }

    /** Whether a custom image is loaded right now (for tests). */
    public static boolean imageLoaded() { return texture != null; }
}
