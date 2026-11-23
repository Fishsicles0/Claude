package net.evadeclient.replay;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Renders a replay to video, frame by frame: the game's clock is taken over so every frame is exactly 1/fps of
 * a second of the replay (following speed keyframes), however long each frame takes to draw. Frames are read
 * straight off the GPU and piped to ffmpeg (Evade Clips' copy, or one on the PATH) as H.264 MP4; without ffmpeg
 * the built-in encoder is used. Any resolution: the game renders at the chosen size even if the window is smaller.
 * Game audio is mixed in step with the frames (see {@link ReplayAudio}) and muxed into the MP4.
 */
public final class ReplayRender {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static volatile ReplayRender current;

    private final ReplayPlayer player;
    private final int fps, width, height, from, to, quality;
    private final Path out;
    /** Sound: "sounds" (game sounds), "music" (game sounds and music) or "off". */
    private final String audioMode;
    private boolean audioOn;
    private ReplayAudio.Capture audio;
    private Path videoFile;
    private String ffmpegPath;
    private String warning;
    private int origW, origH;
    private boolean started;
    private int settled;
    private long clockMs, frames;
    private double replayTime;
    private Process ffmpeg;
    private OutputStream pipe;
    private org.jcodec.api.SequenceEncoder jcodec;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6);
    private Thread writer;
    private volatile Throwable writeError;
    private int pendingReadbacks;
    private boolean finishing;
    private String error;
    private final long startedAt = System.currentTimeMillis();

    private ReplayRender(ReplayPlayer player, ReplayProject.Render r, Path out) {
        this.player = player;
        this.fps = Math.max(10, Math.min(240, r.fps));
        int ww = mc.getWindow().getFramebufferWidth(), wh = mc.getWindow().getFramebufferHeight();
        this.width = (r.width > 0 ? r.width : ww) & ~1;
        this.height = (r.height > 0 ? r.height : wh) & ~1;
        ReplayProject pr = player.project;
        pr.sort();
        int a = r.from >= 0 ? r.from : pr.hasPath() ? (int) Math.floor(pr.camera.get(0).tick) : player.startTick();
        int b = r.to >= 0 ? r.to : pr.hasPath() ? (int) Math.ceil(pr.camera.get(pr.camera.size() - 1).tick) : player.endTick();
        this.from = Math.max(player.startTick(), Math.min(a, player.endTick()));
        this.to = Math.max(this.from + 1, Math.min(b, player.endTick()));
        this.quality = Math.max(12, Math.min(35, r.quality));
        this.out = out;
        this.audioMode = r.audio == null ? "sounds" : r.audio;
    }

    public static boolean rendering() { return current != null; }
    public static ReplayRender current() { return current; }

    /** Starts rendering the current replay with its project's render settings. */
    public static Path start() {
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null || current != null) return null;
        Path dir = mc.runDirectory.toPath().resolve("evade").resolve("renders");
        String name = p.meta.name.replaceAll("[\\\\/:*?\"<>|]+", "_") + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH.mm.ss")) + ".mp4";
        ReplayRender r = new ReplayRender(p, p.project.render, dir.resolve(name));
        current = r;
        // the sound engine moves to the loopback device before the seek, so it's settled by the first frame
        if (!r.audioMode.equals("off")) {
            r.audioOn = ReplayAudio.enable();
            if (!r.audioOn) r.warning = "no sound (your audio system can't render offline)";
        }
        mc.setScreen(null);
        p.setPlaying(false);
        p.seek(r.from);
        return r.out;
    }

    public int from() { return from; }
    public int to() { return to; }
    public Path output() { return out; }
    public double progress() { return Math.max(0, Math.min(1, (player.tick() - from) / (double) Math.max(1, to - from))); }
    public long frames() { return frames; }
    public boolean hasAudio() { return audioOn; }

    // ------------------------------------------------------------------ the clock

    /** The time the game's frame loop sees: real time normally, a fixed step per frame while rendering. */
    public static long clock(long real) {
        ReplayRender r = current;
        if (r == null) return real;
        return r.nextClock(real);
    }

    private long nextClock(long real) {
        if (!started) {
            // wait for the seek to the start and for the world to be fully drawn, then take over the clock
            if (player.seeking() || mc.world == null || mc.currentScreen != null || mc.worldRenderer == null
                    || !mc.worldRenderer.isTerrainRenderComplete()) { settled = 0; return real; }
            if (++settled < 20) return real;
            try { begin(); } catch (Exception e) { fail("Couldn't start rendering: " + e.getMessage()); return real; }
            clockMs = real;
            replayTime = player.tick();
            return clockMs;
        }
        // each frame moves the replay on by 1/fps of a second, scaled by the speed keyframes
        double before = replayTime;
        replayTime = player.project.advance(replayTime, 1.0 / fps);
        double ticks = replayTime - before;
        ReplayAudio.setTimeScale(player.project.render.slowSound ? player.project.speedAt(replayTime) : 1);
        clockMs += Math.max(1, Math.round(ticks * 50));
        return clockMs;
    }

    private void begin() throws IOException {
        Files.createDirectories(out.getParent());
        origW = mc.getWindow().getFramebufferWidth();
        origH = mc.getWindow().getFramebufferHeight();
        if (width != origW || height != origH) resize(width, height);
        player.setSpeed(1);
        player.setPlaying(true);
        String ff = findFfmpeg();
        ffmpegPath = ff;
        videoFile = out;
        if (audioOn) {
            // whatever played while seeking to the start would all come out at once on the first frame
            mc.getSoundManager().stopAll();
            String base = out.getFileName().toString().replaceAll("\\.mp4$", "");
            audio = new ReplayAudio.Capture(out.resolveSibling("." + base + ".audio.pcm"));
            if (ff != null) videoFile = out.resolveSibling("." + base + ".video.mp4");
        }
        if (ff != null) {
            List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba",
                    "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-", "-vf", "vflip", "-c:v", "libx264", "-preset", "medium",
                    "-crf", String.valueOf(quality), "-pix_fmt", "yuv420p", "-movflags", "+faststart", videoFile.toString()));
            ffmpeg = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            pipe = ffmpeg.getOutputStream();
        } else {
            jcodec = org.jcodec.api.SequenceEncoder.createSequenceEncoder(out.toFile(), fps);
        }
        writer = new Thread(this::writeLoop, "Evade replay encoder");
        writer.setDaemon(true);
        writer.start();
        started = true;
    }

    // ------------------------------------------------------------------ frames

    /** After each frame is drawn: copy it off the GPU (asynchronously, in order) and queue it for the encoder. */
    public static void onFrameDrawn() {
        ReplayRender r = current;
        if (r == null || r.finishing) return;
        if (ReplayPlayer.current() != r.player) { r.fail("Render stopped: the replay was closed"); return; }
        if (!r.started) return;
        if (GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) { r.fail("Render cancelled"); return; }
        if (r.writeError != null) { r.fail("Encoding failed: " + r.writeError.getMessage()); return; }
        r.capture();
        if (r.audio != null) {
            if (r.audioMode.equals("sounds")) mc.getMusicTracker().stop();
            try { r.audio.advance(1.0 / r.fps); }
            catch (Exception e) { r.fail("Recording the sound failed: " + e.getMessage()); return; }
        }
        if (r.player.tick() >= r.to) r.finish();
        else if (r.frames % 15 == 0) mc.getWindow().setTitle(String.format(Locale.ROOT, "Evade - rendering %d%% (Esc to cancel)", Math.round(r.progress() * 100)));
    }

    private void capture() {
        Framebuffer fb = mc.getFramebuffer();
        GpuTexture tex = fb.getColorAttachment();
        if (tex == null) return;
        int w = fb.textureWidth, h = fb.textureHeight;
        if (w != width || h != height) return; // a resize is still settling
        int px = tex.getFormat().pixelSize();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Evade replay frame", 9, (long) w * h * px);
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        pendingReadbacks++;
        frames++;
        enc.copyTextureToBuffer(tex, buffer, 0L, () -> {
            try (GpuBuffer.MappedView view = enc.mapBuffer(buffer, true, false)) {
                byte[] frame = new byte[w * h * 4];
                if (px == 4) view.data().get(0, frame);
                else for (int i = 0; i < w * h; i++) {
                    int c = view.data().getInt(i * px);
                    frame[i * 4] = (byte) c; frame[i * 4 + 1] = (byte) (c >> 8); frame[i * 4 + 2] = (byte) (c >> 16); frame[i * 4 + 3] = (byte) 255;
                }
                queue.put(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                buffer.close();
                pendingReadbacks--;
            }
        }, 0);
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] f = queue.take();
                if (f.length == 0) break;
                if (pipe != null) pipe.write(f);
                else encodeJcodec(f);
            }
            if (pipe != null) { pipe.close(); ffmpeg.waitFor(); }
            if (jcodec != null) jcodec.finish();
        } catch (Throwable t) {
            writeError = t;
        }
    }

    private void encodeJcodec(byte[] rgba) throws IOException {
        var pic = org.jcodec.common.model.Picture.create(width, height, org.jcodec.common.model.ColorSpace.RGB);
        byte[] d = pic.getPlaneData(0);
        for (int y = 0; y < height; y++) {
            int src = (height - 1 - y) * width * 4, dst = y * width * 3;
            for (int x = 0; x < width; x++, src += 4, dst += 3) {
                d[dst] = (byte) ((rgba[src] & 0xFF) - 128);
                d[dst + 1] = (byte) ((rgba[src + 1] & 0xFF) - 128);
                d[dst + 2] = (byte) ((rgba[src + 2] & 0xFF) - 128);
            }
        }
        jcodec.encodeNativeFrame(pic);
    }

    // ------------------------------------------------------------------ ending

    private void finish() {
        finishing = true;
        Thread t = new Thread(() -> {
            // let the last readbacks arrive, then end the stream
            long wait = System.currentTimeMillis() + 5000;
            while (pendingReadbacks > 0 && System.currentTimeMillis() < wait) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }
            try { queue.put(new byte[0]); writer.join(120_000); } catch (InterruptedException ignored) {}
            String err = writeError == null ? null : "Encoding failed: " + writeError.getMessage();
            if (err == null && audio != null) err = addAudio();
            String result = err;
            mc.execute(() -> done(result));
        }, "Evade replay finish");
        t.setDaemon(true);
        t.start();
    }

    /** Puts the recorded sound into the video (or next to it as a WAV when there's no ffmpeg). Returns an error, or null. */
    private String addAudio() {
        try {
            audio.close();
            if (ffmpegPath == null) {
                audio.toWav(out.resolveSibling(out.getFileName().toString().replaceAll("\\.mp4$", "") + ".wav"));
                warning = "sound saved next to it as a .wav (install ffmpeg to get it inside the video)";
                return null;
            }
            List<String> cmd = List.of(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", videoFile.toString(),
                    "-f", "s16le", "-ar", String.valueOf(ReplayAudio.RATE), "-ac", String.valueOf(ReplayAudio.CHANNELS), "-i", audio.file.toString(),
                    "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
                    "-movflags", "+faststart", out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0 || !Files.isRegularFile(out)) {
                EvadeClient.LOG.warn("Replay audio: muxing failed: {}", log.trim());
                Files.move(videoFile, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                warning = "no sound (couldn't add it to the video)";
            }
            return null;
        } catch (Exception e) {
            return "Adding the sound failed: " + e.getMessage();
        } finally {
            cleanTemp();
        }
    }

    private void cleanTemp() {
        try {
            if (audio != null) { audio.close(); Files.deleteIfExists(audio.file); }
            if (videoFile != null && !videoFile.equals(out)) Files.deleteIfExists(videoFile);
        } catch (IOException ignored) {}
    }

    private void fail(String why) {
        if (finishing) return;
        finishing = true;
        error = why;
        if (writer != null) {
            queue.clear();
            queue.offer(new byte[0]);
            if (ffmpeg != null) ffmpeg.destroy();
        }
        done(why);
    }

    private void done(String err) {
        current = null;
        if (audioOn) ReplayAudio.disable();
        if (started && (width != origW || height != origH)) resize(origW, origH);
        mc.getWindow().setTitle("Minecraft* " + net.minecraft.SharedConstants.getGameVersion().name());
        player.setPlaying(false);
        lastResult = err == null ? out : null;
        lastError = err;
        if (err != null) {
            Notifications.push("Render", err, false);
            if (ffmpeg != null) try { ffmpeg.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            cleanTemp();
            try { Files.deleteIfExists(out); } catch (IOException ignored) {}
            return;
        }
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        Notifications.push("Render done", frames + " frames in " + secs + " s: " + out.getFileName() + (warning != null ? " — " + warning : ""), warning == null);
        lastAudioPeak = audio != null ? audio.peak() : -1;
        EvadeClient.LOG.info("Replay rendered to {} ({} frames, {}x{} at {} fps, sound: {})", out, frames, width, height, fps,
                audio != null ? audio.samples() + " samples, peak " + audio.peak() : warning != null ? warning : "off");
        sendToClips(out);
    }

    public static volatile Path lastResult;
    public static volatile String lastError;
    /** Loudest sample in the last render's sound (-1 = rendered without sound). */
    public static volatile long lastAudioPeak = -1;

    // ------------------------------------------------------------------ helpers

    /** Renders at any size: the game draws into a framebuffer of this size whatever the window is. */
    private static void resize(int w, int h) {
        var acc = (net.evadeclient.mixin.WindowAccessor) (Object) mc.getWindow();
        acc.evade$setFramebufferWidth(w);
        acc.evade$setFramebufferHeight(h);
        mc.onResolutionChanged();
    }

    static String findFfmpeg() {
        String prop = System.getProperty("evade.ffmpeg");
        if (prop != null && Files.isRegularFile(Path.of(prop))) return prop;
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = win ? "ffmpeg.exe" : "ffmpeg";
        Path clips = win && System.getenv("APPDATA") != null ? Path.of(System.getenv("APPDATA"), "EvadeClips")
                : Path.of(System.getProperty("user.home"), ".evade-clips");
        for (Path c : List.of(clips.resolve("ffmpeg").resolve("bin").resolve(exe), clips.resolve("ffmpeg").resolve(exe))) {
            if (Files.isRegularFile(c)) return c.toString();
        }
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(java.io.File.pathSeparator)) {
            Path c = Path.of(d, exe);
            if (Files.isRegularFile(c)) return c.toString();
        }
        return null;
    }

    /** Puts the video in Evade Clips' library when it's running, so it's ready to edit and share. */
    private static void sendToClips(Path file) {
        Thread t = new Thread(() -> {
            try {
                var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build();
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:47821/import?path="
                        + java.net.URLEncoder.encode(file.toAbsolutePath().toString(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&title=" + java.net.URLEncoder.encode(file.getFileName().toString().replace(".mp4", ""), java.nio.charset.StandardCharsets.UTF_8)))
                        .timeout(java.time.Duration.ofSeconds(10)).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
                var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) mc.execute(() -> Notifications.push("Evade Clips", "Render added to your library", true));
            } catch (Exception ignored) {
                // Evade Clips isn't running: the video is in .minecraft/evade/renders
            }
        }, "Evade render to Clips");
        t.setDaemon(true);
        t.start();
    }
}
