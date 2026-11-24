package net.evadeclient.mods;

import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mod icons as textures, loaded in the background: {@link #get} never blocks. The first call for a key starts the
 * download and decode on a worker; the texture is uploaded on the client thread once it is ready, and later frames
 * draw it. Decodes PNG (and JPEG/GIF where the JDK can) and lossless WebP; anything else stays a placeholder.
 * Kept for the session, at most {@link #MAX} textures (the least recently drawn go first).
 */
public final class IconCache {
    /** Every icon is stored at this size (fit inside, centred). */
    public static final int SIZE = 64;
    private static final int MAX = 160;
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Evade-ModBrowser-Icons");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicInteger SEQ = new AtomicInteger();

    private enum State { LOADING, READY, FAILED }

    private static final class Entry {
        State state = State.LOADING;
        Identifier id;
    }

    /** Render thread only; access order, so iteration starts at the least recently used. */
    private final Map<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

    /** The icon for {@code key}, or null while it loads (or if it can't be shown). {@code source} gives its bytes. */
    public Identifier get(String key, Callable<byte[]> source) {
        if (key == null) return null;
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
            Entry entry = e;
            evict();
            LOADER.execute(() -> load(entry, source));
        }
        return e.state == State.READY ? e.id : null;
    }

    /** Draw {@code id} as a {@code size} square at (x, y), honouring {@link Theme#fade}. */
    public static void draw(DrawContext ctx, Identifier id, int x, int y, int size) {
        int c = Theme.fa(0xFFFFFFFF);
        if (c >>> 24 == 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, SIZE, SIZE, SIZE, SIZE, c);
    }

    private void load(Entry e, Callable<byte[]> source) {
        int[] argb;
        try {
            byte[] bytes = source.call();
            argb = bytes == null ? null : decode(bytes);
        } catch (Throwable t) {
            argb = null;
        }
        int[] px = argb;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (px == null) { e.state = State.FAILED; return; }
            if (!entries.containsValue(e)) return; // evicted while loading
            NativeImage img = new NativeImage(SIZE, SIZE, false);
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) img.setColorArgb(x, y, px[y * SIZE + x]);
            Identifier id = Identifier.of("evade", "mod_browser/icon_" + SEQ.incrementAndGet());
            mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "Evade mod icon", img));
            e.id = id;
            e.state = State.READY;
        });
    }

    private void evict() {
        if (entries.size() <= MAX) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (entries.size() > MAX && it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.state == State.LOADING) continue;
            if (e.id != null) mc.getTextureManager().destroyTexture(e.id);
            it.remove();
        }
    }

    /** Free every texture (the screen is never needed again this session, e.g. a resource reload). */
    public void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Entry e : entries.values()) if (e.id != null) mc.getTextureManager().destroyTexture(e.id);
        entries.clear();
    }

    // ------------------------------------------------------------------ decoding (any thread)

    /** {@code bytes} as a {@link #SIZE} x {@link #SIZE} ARGB icon, or null if it isn't an image we can read. */
    static int[] decode(byte[] bytes) {
        int w, h;
        int[] src;
        if (WebpLossless.isWebp(bytes)) {
            WebpLossless.Image im = WebpLossless.decode(bytes);
            w = im.width();
            h = im.height();
            src = im.argb();
        } else if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            try (NativeImage img = NativeImage.read(bytes)) {
                w = img.getWidth();
                h = img.getHeight();
                if (w > 2048 || h > 2048) return null;
                src = img.copyPixelsArgb();
            } catch (Exception e) {
                return null;
            }
        } else {
            try {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (bi == null || bi.getWidth() > 2048 || bi.getHeight() > 2048) return null;
                w = bi.getWidth();
                h = bi.getHeight();
                src = bi.getRGB(0, 0, w, h, null, 0, w);
            } catch (Throwable t) {
                return null;
            }
        }
        return fit(src, w, h);
    }

    /** Scale into a SIZE square, keeping the aspect: area-averaged (premultiplied) down, nearest-neighbour up. */
    static int[] fit(int[] src, int w, int h) {
        int[] out = new int[SIZE * SIZE];
        if (w <= 0 || h <= 0) return out;
        double scale = Math.min(SIZE / (double) w, SIZE / (double) h);
        int dw = Math.max(1, (int) Math.round(w * scale)), dh = Math.max(1, (int) Math.round(h * scale));
        int ox = (SIZE - dw) / 2, oy = (SIZE - dh) / 2;
        for (int y = 0; y < dh; y++) {
            int sy0 = (int) Math.floor(y / scale), sy1 = Math.max(sy0 + 1, Math.min(h, (int) Math.ceil((y + 1) / scale)));
            sy0 = Math.min(sy0, h - 1);
            for (int x = 0; x < dw; x++) {
                int sx0 = (int) Math.floor(x / scale), sx1 = Math.max(sx0 + 1, Math.min(w, (int) Math.ceil((x + 1) / scale)));
                sx0 = Math.min(sx0, w - 1);
                long a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int p = src[sy * w + sx];
                        int pa = p >>> 24;
                        a += pa;
                        r += (long) ((p >> 16) & 0xFF) * pa;
                        g += (long) ((p >> 8) & 0xFF) * pa;
                        b += (long) (p & 0xFF) * pa;
                        n++;
                    }
                }
                int oa = (int) (a / n);
                int argb = a == 0 ? 0 : oa << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                out[(oy + y) * SIZE + ox + x] = argb;
            }
        }
        return out;
    }
}
