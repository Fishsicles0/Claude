package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A cosmetic texture that may be a vertical strip of animation frames (item.json {@code animation}, see
 * site-src/cosmetics/README.md). The strip stays on the CPU; the GPU only holds one frame-sized texture per item,
 * shared by every wearer, and it is written again only when what is shown changes: a new frame, or (with
 * {@code interpolate}) the next of a few blend steps between two frames. A still texture is uploaded once and never
 * touched again. All items run on one clock, so everyone wearing an item sees the same frame.
 */
public final class AnimatedTexture {
    /** Most frames an animation may have, and the largest frame side / whole strip height in pixels. */
    static final int MAX_FRAMES = 64, MAX_FRAME_SIDE = 1024, MAX_HEIGHT = 4096;

    /** One step of the frame order: which frame and for how many ticks. */
    public record Step(int frame, float ticks) {}

    /** The parsed {@code animation} object (null = a still texture). */
    public record Spec(int frames, float frametime, boolean interpolate, List<Step> order) {
        public static Spec parse(JsonElement e) {
            if (e == null || !e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            int frames = o.has("frames") ? o.get("frames").getAsInt() : 1;
            if (frames < 1 || frames > MAX_FRAMES) throw new IllegalArgumentException("animation frames must be 1.." + MAX_FRAMES);
            float ft = Math.max(0.5f, o.has("frametime") ? o.get("frametime").getAsFloat() : 1f);
            if (!Float.isFinite(ft) || ft > 20 * 60) throw new IllegalArgumentException("bad frametime");
            boolean interp = o.has("interpolate") && o.get("interpolate").getAsBoolean();
            List<Step> order = new ArrayList<>();
            if (o.has("order") && o.get("order").isJsonArray()) {
                JsonArray a = o.getAsJsonArray("order");
                if (a.size() > 256) throw new IllegalArgumentException("animation order too long");
                for (JsonElement s : a) {
                    int idx;
                    float t = ft;
                    if (s.isJsonObject()) {
                        idx = s.getAsJsonObject().get("index").getAsInt();
                        if (s.getAsJsonObject().has("time")) t = Math.max(0.5f, s.getAsJsonObject().get("time").getAsFloat());
                    } else idx = s.getAsInt();
                    if (idx < 0 || idx >= frames) throw new IllegalArgumentException("animation order names a missing frame");
                    order.add(new Step(idx, t));
                }
            }
            if (order.isEmpty()) for (int i = 0; i < frames; i++) order.add(new Step(i, ft));
            return new Spec(frames, ft, interp, List.copyOf(order));
        }

        float cycleTicks() {
            float sum = 0;
            for (Step s : order) sum += s.ticks;
            return sum;
        }
    }

    public final Identifier id;
    public final int frameW, frameH, frames;
    private final Spec spec;
    private final NativeImage strip;            // null for a still texture (the GPU copy is all there is)
    private final NativeImageBackedTexture tex;
    private int shownKey = -1;
    private long lastFrameNo = -1;
    /** Uploads since load (tests and the profiler read this). */
    public int uploads;
    /** The frame drawn at the last update, for tests. */
    public int shownFrame;
    /** Some pixels are partly see-through (glass): bones using it are drawn translucent, sorted. */
    public final boolean partialAlpha;

    /**
     * Reads {@code png}; with a spec its height must be exactly {@code frames} frames of {@code frameW x frameW*aspect}.
     * {@code aspect} is the model's UV height / width (1 for thumbnails).
     */
    AnimatedTexture(Identifier id, byte[] png, Spec spec, float aspect, String name) throws IOException {
        NativeImage img = NativeImage.read(png);
        try {
            int w = img.getWidth(), h = img.getHeight();
            int n = spec == null ? 1 : spec.frames();
            if (h > MAX_HEIGHT || w > MAX_FRAME_SIDE) throw new IOException("texture too large (" + w + "x" + h + ")");
            if (h % n != 0) throw new IOException("texture height " + h + " is not " + n + " equal frames");
            int fh = h / n;
            if (spec != null && n > 1 && aspect > 0 && Math.abs(fh - w * aspect) > 0.51f)
                throw new IOException("frame is " + w + "x" + fh + ", the model's UV space wants " + w + "x" + Math.round(w * aspect));
            boolean partial = false;
            for (int y = 0; y < h && !partial; y++) for (int x = 0; x < w; x++) {
                int al = img.getColorArgb(x, y) >>> 24;
                if (al > 12 && al < 243) { partial = true; break; }
            }
            this.partialAlpha = partial;
            this.id = id;
            this.frameW = w;
            this.frameH = fh;
            this.frames = n;
            this.spec = n > 1 ? spec : null;
            if (this.spec == null) {
                // still: the image itself becomes the texture (one upload, no CPU copy kept beyond the texture's own)
                strip = null;
                tex = new NativeImageBackedTexture(() -> name, img);
                uploads = 1;
            } else {
                strip = img;
                tex = new NativeImageBackedTexture(() -> name, w, fh, false);
                copyFrame(0, 0, 0);
            }
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        } catch (IOException | RuntimeException e) {
            img.close();
            throw e;
        }
    }

    public boolean animated() { return spec != null; }

    /** Ticks on the shared clock (20 per second, fractional). */
    public static double clockTicks() { return Util.getMeasuringTimeNano() / 50_000_000.0; }

    /**
     * Brings the GPU copy up to date for now; costs nothing unless the visible frame (or blend step) changed. Call it
     * before drawing; many calls in one frame are free.
     */
    public void update() {
        if (spec == null) return;
        long frameNo = renderFrame();
        if (frameNo == lastFrameNo) return;
        lastFrameNo = frameNo;
        update(clockTicks());
    }

    /** Same, at an explicit time in ticks (tests, the Wardrobe). */
    public void update(double ticks) {
        if (spec == null) return;
        double cycle = spec.cycleTicks();
        double t = ticks % cycle;
        if (t < 0) t += cycle;
        List<Step> order = spec.order();
        int i = 0;
        while (i < order.size() - 1 && t >= order.get(i).ticks()) { t -= order.get(i).ticks(); i++; }
        Step cur = order.get(i), next = order.get((i + 1) % order.size());
        int a = cur.frame(), b = next.frame(), step = 0, steps = 1;
        if (spec.interpolate() && a != b) {
            // a few blend steps per frame: about 60 changes a second at most, smooth to the eye, cheap to upload
            steps = Math.max(2, Math.min(16, Math.round(cur.ticks() * 3)));
            step = (int) Math.min(steps - 1, Math.floor(t / cur.ticks() * steps));
        }
        int key = (a * MAX_FRAMES + b) * 16 + step;
        if (key == shownKey) return;
        shownKey = key;
        shownFrame = a;
        copyFrame(a, b, step / (float) steps);
    }

    /** Fills the frame texture with frame {@code a} blended toward {@code b} by {@code f}, and uploads it once. */
    private void copyFrame(int a, int b, float f) {
        NativeImage dst = tex.getImage();
        if (dst == null || strip == null) return;
        int ya = a * frameH, yb = b * frameH;
        if (f <= 0f || a == b) {
            strip.copyRect(dst, 0, ya, 0, 0, frameW, frameH, false, false);
        } else {
            int wb = Math.round(f * 256), wa = 256 - wb;
            for (int y = 0; y < frameH; y++) for (int x = 0; x < frameW; x++) {
                int ca = strip.getColorArgb(x, ya + y), cb = strip.getColorArgb(x, yb + y);
                int r = ((ca >>> 24) * wa + (cb >>> 24) * wb) >> 8;
                int g = (((ca >> 16) & 255) * wa + ((cb >> 16) & 255) * wb) >> 8;
                int bl = (((ca >> 8) & 255) * wa + ((cb >> 8) & 255) * wb) >> 8;
                int al = ((ca & 255) * wa + (cb & 255) * wb) >> 8;
                dst.setColorArgb(x, y, r << 24 | g << 16 | bl << 8 | al);
            }
        }
        tex.upload();
        uploads++;
    }

    private static long renderFrame() {
        // one update per rendered frame at most, whichever player or screen asks first
        return Util.getMeasuringTimeNano() / 4_000_000L;   // 4 ms buckets: never more than 250 checks a second
    }

    /** Frame index and blend for GUI drawing of a strip (thumbnails): {frame a, frame b, blend 0..1}. */
    public static float[] frameAt(Spec spec, double ticks) {
        if (spec == null || spec.frames() <= 1) return new float[]{0, 0, 0};
        double cycle = spec.cycleTicks();
        double t = ticks % cycle;
        List<Step> order = spec.order();
        int i = 0;
        while (i < order.size() - 1 && t >= order.get(i).ticks()) { t -= order.get(i).ticks(); i++; }
        Step cur = order.get(i), next = order.get((i + 1) % order.size());
        return new float[]{cur.frame(), next.frame(), spec.interpolate() ? (float) (t / cur.ticks()) : 0f};
    }
}
