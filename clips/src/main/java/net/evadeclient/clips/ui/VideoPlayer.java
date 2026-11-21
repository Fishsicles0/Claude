package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Library;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a video by streaming decoded frames and PCM audio out of ffmpeg. Audio drives the clock (so A/V stay in
 * sync); without audio the wall clock does. Playback speed is applied to both, so slow-motion segments can be
 * previewed as they'll export.
 */
public final class VideoPlayer {
    public interface Listener { void frame(BufferedImage img, double time); default void ended() {} }

    private static final int RATE = 48000;

    private Path file;
    private double duration, fps = 30;
    private boolean hasAudio;
    private int w = 960, h = 540;
    private final Listener listener;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean playing;
    private volatile double time;
    private volatile double speed = 1;
    private volatile float volume = 1;
    private volatile boolean muted;
    private final List<Process> procs = new ArrayList<>();

    // still-frame requests while paused (only the newest one runs)
    private final Object stillLock = new Object();
    private Double pendingStill;
    private boolean stillBusy;

    public VideoPlayer(Listener listener) { this.listener = listener; }

    public void open(Path file, double duration, double fps, boolean hasAudio, int srcW, int srcH) {
        stop();
        this.file = file;
        this.duration = duration;
        this.fps = Math.min(60, fps > 0 ? fps : 30);
        this.hasAudio = hasAudio;
        // Decode at preview size: fit in 1280x720, keep aspect, even dimensions.
        double k = Math.min(1, Math.min(1280.0 / Math.max(1, srcW), 720.0 / Math.max(1, srcH)));
        w = Math.max(2, (int) (srcW * k) & ~1);
        h = Math.max(2, (int) (srcH * k) & ~1);
        time = 0;
        showFrame(0);
    }

    public boolean isPlaying() { return playing; }
    public double time() { return time; }
    public double duration() { return duration; }
    public double speed() { return speed; }
    public void setVolume(float v) { volume = v; }
    public void setMuted(boolean m) { muted = m; }

    /** Starts playing from {@code from} at {@code speed} (0.25 .. 4). */
    public void play(double from, double speed) {
        if (file == null) return;
        stop();
        this.speed = speed;
        time = Math.max(0, Math.min(from, duration - 0.02));
        playing = true;
        int gen = generation.incrementAndGet();
        double start = time;
        long wallStart = System.nanoTime();
        SourceDataLine[] line = new SourceDataLine[1];

        // Audio.
        if (hasAudio) {
            Thread at = new Thread(() -> runAudio(gen, start, speed, line), "player-audio");
            at.setDaemon(true);
            at.start();
        }
        Thread vt = new Thread(() -> runVideo(gen, start, speed, wallStart, line), "player-video");
        vt.setDaemon(true);
        vt.start();
    }

    public void pause() {
        stop();
    }

    private void stop() {
        playing = false;
        generation.incrementAndGet();
        synchronized (procs) {
            for (Process p : procs) p.destroyForcibly();
            procs.clear();
        }
    }

    public void close() { stop(); file = null; }

    /** Seeks; keeps playing if it was playing, otherwise shows the frame at {@code t}. */
    public void seek(double t) {
        t = Math.max(0, Math.min(t, Math.max(0, duration - 0.02)));
        if (playing) play(t, speed);
        else { time = t; showFrame(t); }
    }

    // ------------------------------------------------------------------ decoding

    private Process spawn(List<String> args) throws IOException {
        Process p = Ffmpeg.builder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        synchronized (procs) { procs.add(p); }
        return p;
    }

    private void runVideo(int gen, double start, double speed, long wallStart, SourceDataLine[] line) {
        Process p = null;
        try {
            p = spawn(List.of("-loglevel", "error", "-ss", Library.fmt(start), "-i", file.toString(), "-an",
                    "-vf", "fps=" + Library.fmt(fps) + ",scale=" + w + ":" + h + ":flags=fast_bilinear",
                    "-f", "rawvideo", "-pix_fmt", "bgr24", "-"));
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(p.getInputStream(), 1 << 20));
            int frameBytes = w * h * 3;
            ArrayDeque<BufferedImage> pool = new ArrayDeque<>();
            long n = 0;
            while (generation.get() == gen) {
                BufferedImage img = pool.isEmpty() ? new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR) : pool.poll();
                byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                try { in.readFully(dst, 0, frameBytes); } catch (EOFException eof) { break; }
                double ft = start + n / fps;
                n++;
                // Wait until the clock reaches this frame.
                while (generation.get() == gen) {
                    double clock = clock(start, speed, wallStart, line[0]);
                    double ahead = (ft - clock) / speed;
                    if (ahead <= 0.002) break;
                    Thread.sleep(Math.max(1, Math.min(20, (long) (ahead * 1000))));
                }
                if (generation.get() != gen) break;
                double clock = clock(start, speed, wallStart, line[0]);
                if (clock - ft > 0.25) continue; // way behind: drop the frame
                time = ft;
                listener.frame(img, ft);
                // Recycle two frames back so the UI never paints a frame being overwritten.
                if (pool.size() < 3) pool.add(new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR));
            }
            if (generation.get() == gen) {
                // Let the audio drain, then report the end.
                playing = false;
                time = Math.min(duration, time);
                listener.ended();
            }
        } catch (Exception e) {
            if (generation.get() == gen) playing = false;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    private double clock(double start, double speed, long wallStart, SourceDataLine line) {
        if (line != null) {
            return start + line.getMicrosecondPosition() / 1e6 * speed;
        }
        return start + (System.nanoTime() - wallStart) / 1e9 * speed;
    }

    private void runAudio(int gen, double start, double speed, SourceDataLine[] lineOut) {
        Process p = null;
        SourceDataLine line = null;
        try {
            StringBuilder af = new StringBuilder("aresample=" + RATE);
            double s = speed;
            while (s < 0.5) { af.append(",atempo=0.5"); s /= 0.5; }
            if (Math.abs(s - 1) > 1e-6) af.append(",atempo=").append(Library.fmt(s));
            p = spawn(List.of("-loglevel", "error", "-ss", Library.fmt(start), "-i", file.toString(), "-vn",
                    "-af", af.toString(), "-f", "s16le", "-ac", "2", "-ar", String.valueOf(RATE), "-"));
            AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, RATE / 5 * 4); // ~200 ms buffer
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            boolean started = false;
            int r;
            while (generation.get() == gen && (r = in.readNBytes(buf, 0, buf.length)) > 0) {
                r -= r % 4;
                float v = muted ? 0 : volume;
                if (v != 1f) {
                    for (int i = 0; i + 1 < r; i += 2) {
                        int sample = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                        sample = Math.max(-32768, Math.min(32767, Math.round(sample * v)));
                        buf[i] = (byte) sample;
                        buf[i + 1] = (byte) (sample >> 8);
                    }
                }
                line.write(buf, 0, r);
                if (!started) {
                    line.start();
                    lineOut[0] = line;
                    started = true;
                }
            }
            if (generation.get() == gen) line.drain();
        } catch (Exception e) {
            // No audio device (e.g. headless): the video falls back to the wall clock.
        } finally {
            lineOut[0] = null;
            if (line != null) { line.stop(); line.close(); }
            if (p != null) p.destroyForcibly();
        }
    }

    /** Decodes a single frame (paused / scrubbing). Rapid requests collapse to the newest. */
    public void showFrame(double t) {
        if (file == null) return;
        synchronized (stillLock) {
            pendingStill = t;
            if (stillBusy) return;
            stillBusy = true;
        }
        Thread th = new Thread(() -> {
            while (true) {
                double want;
                synchronized (stillLock) {
                    if (pendingStill == null) { stillBusy = false; return; }
                    want = pendingStill;
                    pendingStill = null;
                }
                if (playing) continue;
                BufferedImage img = decodeStill(want);
                if (img != null && !playing) listener.frame(img, want);
            }
        }, "player-still");
        th.setDaemon(true);
        th.start();
    }

    private BufferedImage decodeStill(double t) {
        Process p = null;
        try {
            double at = Math.max(0, Math.min(t, duration - 0.05));
            p = Ffmpeg.builder(List.of("-loglevel", "error", "-ss", Library.fmt(at), "-i", file.toString(), "-an", "-frames:v", "1",
                    "-vf", "scale=" + w + ":" + h + ":flags=fast_bilinear", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            new DataInputStream(p.getInputStream()).readFully(dst);
            return img;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }
}
