package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.lwjgl.glfw.GLFW;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instant replay. While on, Evade keeps the last N seconds of your screen in memory (JPEG-compressed, so
 * 30 s of 720p is roughly 90 MB). Press the clip key and those seconds are written to an MP4 (H.264) in
 * .minecraft/evade/clips, encoded in the background so the game doesn't stutter.
 */
public class Clips extends Module {
    public final KeySetting saveKey = add(new KeySetting("Save Clip Key", GLFW.GLFW_KEY_F8));
    public final NumberSetting seconds = add(new NumberSetting("Clip Length (s)", 30, 10, 120, 5));
    public final NumberSetting fps = add(new NumberSetting("Clip FPS", 30, 15, 60, 15));
    public final ModeSetting quality = add(new ModeSetting("Quality", "720p", "480p", "720p", "1080p"));
    public final BooleanSetting indicator = add(new BooleanSetting("Recording Dot", true));

    private static final long MAX_BYTES = 600L * 1024 * 1024;
    private record Frame(long nanos, byte[] jpeg, int w, int h) {}

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private long bufferedBytes;
    private final AtomicInteger pending = new AtomicInteger();
    private long lastCapture;
    private boolean keyWasDown;
    private volatile boolean saving;
    private volatile String status;
    private volatile long statusUntil;

    private final ExecutorService jpegPool = Executors.newSingleThreadExecutor(r -> daemon(r, "Evade Clips capture"));
    private final ExecutorService mp4Pool = Executors.newSingleThreadExecutor(r -> daemon(r, "Evade Clips encoder"));

    public Clips() {
        super("Clips", "Instant replay: press a key to save the last seconds as an MP4.", Category.MISC);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    }

    public static Clips active() {
        if (EvadeClient.modules == null) return null;
        Clips c = EvadeClient.modules.get(Clips.class);
        return c != null && c.isEnabled() ? c : null;
    }

    public File folder() { return new File(mc.runDirectory, "clips"); }

    /** Frames currently held in the replay buffer. */
    public int bufferedFrames() { synchronized (frames) { return frames.size(); } }

    @Override
    protected void onDisable() {
        synchronized (frames) { frames.clear(); bufferedBytes = 0; }
    }

    @Override
    public void onTick() {
        boolean down = saveKey.isDown() && mc.currentScreen == null;
        if (down && !keyWasDown) save();
        keyWasDown = down;
    }

    // ------------------------------------------------------------------ capture

    /** Called after every frame is fully drawn (world, HUD and any open screen). */
    public static void onFrameEnd() {
        Clips c = active();
        if (c != null) c.capture();
    }

    private void capture() {
        long now = System.nanoTime();
        if (now - lastCapture < (long) (1e9 / fps.get()) || pending.get() >= 3) return;
        var fb = mc.getFramebuffer();
        if (fb == null || fb.textureWidth <= 0 || fb.textureHeight <= 0) return;
        lastCapture = now;
        int target = switch (quality.get()) { case "480p" -> 480; case "1080p" -> 1080; default -> 720; };
        int outH = Math.min(target, fb.textureHeight) & ~1;
        int outW = (int) Math.round(outH * (double) fb.textureWidth / fb.textureHeight) & ~1;
        pending.incrementAndGet();
        try {
            ScreenshotRecorder.takeScreenshot(fb, img -> {
                boolean handedOff = false;
                try (img) {
                    NativeImage small = new NativeImage(outW, outH, false);
                    img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), small);
                    int[] argb = small.copyPixelsArgb();
                    small.close();
                    jpegPool.execute(() -> {
                        try { store(now, toJpeg(argb, outW, outH), outW, outH); }
                        catch (Exception e) { EvadeClient.LOG.warn("Clips: frame encode failed", e); }
                        finally { pending.decrementAndGet(); }
                    });
                    handedOff = true;
                } catch (Exception e) {
                    EvadeClient.LOG.warn("Clips: capture failed", e);
                } finally {
                    if (!handedOff) pending.decrementAndGet();
                }
            });
        } catch (Exception e) {
            pending.decrementAndGet();
        }
    }

    private void store(long nanos, byte[] jpeg, int w, int h) {
        synchronized (frames) {
            frames.addLast(new Frame(nanos, jpeg, w, h));
            bufferedBytes += jpeg.length;
            long keep = (long) ((seconds.get() + 1) * 1e9);
            while (!frames.isEmpty() && (nanos - frames.peekFirst().nanos > keep || bufferedBytes > MAX_BYTES)) {
                bufferedBytes -= frames.removeFirst().jpeg.length;
            }
        }
    }

    private static byte[] toJpeg(int[] argb, int w, int h) throws Exception {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.85f);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(bi, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ save

    /** Write the buffered seconds to an MP4 in the background. Completes with the file, or exceptionally. */
    public CompletableFuture<File> save() {
        if (saving) { CommandManager.print("§7Still saving the last clip..."); return CompletableFuture.failedFuture(new IllegalStateException("busy")); }
        List<Frame> snap;
        synchronized (frames) { snap = new ArrayList<>(frames); }
        // only the trailing run of same-size frames (the window may have been resized)
        if (!snap.isEmpty()) {
            Frame last = snap.get(snap.size() - 1);
            int i = snap.size() - 1;
            while (i > 0 && snap.get(i - 1).w == last.w && snap.get(i - 1).h == last.h) i--;
            snap = snap.subList(i, snap.size());
        }
        if (snap.size() < 2) {
            CommandManager.print("§7Nothing recorded yet. Keep §cClips§7 on for a few seconds first.");
            return CompletableFuture.failedFuture(new IllegalStateException("empty"));
        }
        saving = true;
        setStatus("Saving clip...", 600_000);
        folder().mkdirs();
        File file = new File(folder(), "clip-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".mp4");
        int outFps = fps.getInt();
        List<Frame> clip = snap;
        CompletableFuture<File> done = new CompletableFuture<>();
        mp4Pool.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                encode(clip, file, outFps);
                double secs = (clip.get(clip.size() - 1).nanos - clip.get(0).nanos) / 1e9;
                EvadeClient.LOG.info("Clips: wrote {} ({} frames, {}s, {} KB) in {} ms", file.getName(), clip.size(),
                        String.format("%.1f", secs), file.length() / 1024, System.currentTimeMillis() - t0);
                mc.execute(() -> {
                    CommandManager.print("§aClip saved§7: " + file.getName() + " (" + String.format("%.0f", secs) + "s). §7Type §d.clips§7 to open the folder.");
                    Notifications.push("Clip saved", String.format("%.0fs · %d MB", secs, Math.max(1, file.length() >> 20)), true);
                });
                setStatus("Clip saved", 3000);
                done.complete(file);
            } catch (Exception e) {
                EvadeClient.LOG.error("Clips: saving failed", e);
                mc.execute(() -> CommandManager.print("§cCouldn't save the clip: " + e.getMessage()));
                setStatus("Clip failed", 3000);
                done.completeExceptionally(e);
            } finally {
                saving = false;
            }
        });
        return done;
    }

    /** Lay the captured frames on a constant-rate timeline (so playback speed is right) and encode H.264. */
    private static void encode(List<Frame> clip, File file, int outFps) throws Exception {
        SequenceEncoder enc = SequenceEncoder.createSequenceEncoder(file, outFps);
        long start = clip.get(0).nanos, end = clip.get(clip.size() - 1).nanos, step = (long) (1e9 / outFps);
        int idx = 0, shown = -1;
        Picture pic = null;
        for (long t = start; t <= end; t += step) {
            while (idx + 1 < clip.size() && clip.get(idx + 1).nanos <= t) idx++;
            if (idx != shown) { pic = toPicture(clip.get(idx)); shown = idx; }
            enc.encodeNativeFrame(pic);
        }
        enc.finish();
    }

    private static Picture toPicture(Frame f) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(f.jpeg));
        int w = img.getWidth(), h = img.getHeight();
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        Picture pic = Picture.create(w, h, ColorSpace.RGB);
        byte[] d = pic.getPlaneData(0);
        for (int i = 0, o = 0; i < rgb.length; i++) {
            int p = rgb[i];
            d[o++] = (byte) (((p >> 16) & 255) - 128);
            d[o++] = (byte) (((p >> 8) & 255) - 128);
            d[o++] = (byte) ((p & 255) - 128);
        }
        return pic;
    }

    // ------------------------------------------------------------------ HUD

    private void setStatus(String s, long ms) { status = s; statusUntil = System.currentTimeMillis() + ms; }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        var tr = mc.textRenderer;
        if (indicator.get()) {
            boolean blink = (System.currentTimeMillis() / 600) % 2 == 0 || saving;
            ctx.fill(4, 4, 8, 8, blink ? 0xFFE11D2E : 0x80E11D2E);
        }
        String s = status;
        if (s != null && System.currentTimeMillis() < statusUntil) {
            int w = tr.getWidth(s);
            int x = (ctx.getScaledWindowWidth() - w) / 2;
            ctx.fill(x - 6, 22, x + w + 6, 36, 0xC0000000);
            ctx.fill(x - 6, 22, x + w + 6, 23, 0xFFE11D2E);
            ctx.drawTextWithShadow(tr, s, x, 26, 0xFFFFFFFF);
        }
    }
}
