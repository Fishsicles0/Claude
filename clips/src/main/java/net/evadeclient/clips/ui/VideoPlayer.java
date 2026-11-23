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
    private int audioTracks = 1;
    /** Extra video filter for the preview (the clip's color effect), so what you see is what exports. */
    private volatile String videoFilter = "";
    private int w = 960, h = 540;
    private final Listener listener;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean playing;
    private volatile double time;
    private volatile double speed = 1;
    private volatile float volume = 1;
    private volatile boolean muted;
    /** The edit's own sound settings (its Volume and Mute), on top of the preview's monitor volume and mute. */
    private volatile float editGain = 1;
    private volatile boolean editMute;
    /** Level of each audio track (game, mic, voice chat), as the export mixes them; null = all at 1. */
    private volatile double[] trackGains;
    /** Extra gain at a source time (the edit's fades), or null. */
    private volatile java.util.function.DoubleUnaryOperator gainAt;
    private final List<Process> procs = new ArrayList<>();
    /** Restarts playback a moment after rapid changes (track levels, seeks while playing) instead of on every one. */
    private final javax.swing.Timer restart = new javax.swing.Timer(250, e -> resume());
    private final javax.swing.Timer seekLater = new javax.swing.Timer(120, e -> resume());
    private long lastStart;
    /** Where a debounced seek will restart playback (-1 = none). */
    private volatile double pendingSeek = -1;

    // still-frame requests while paused (only the newest one runs)
    private final Object stillLock = new Object();
    private Double pendingStill;
    private boolean stillBusy;

    public VideoPlayer(Listener listener) {
        this.listener = listener;
        restart.setRepeats(false);
        seekLater.setRepeats(false);
    }

    private void resume() {
        double t = pendingSeek >= 0 ? pendingSeek : time;
        if (playing) play(t, speed);
        pendingSeek = -1;
    }

    public void open(Path file, double duration, double fps, boolean hasAudio, int srcW, int srcH) {
        restart.stop();
        seekLater.stop();
        pendingSeek = -1;
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
    /** Whether a file is loaded (false after {@link #close()}). */
    public boolean isOpen() { return file != null; }
    public double time() { double p = pendingSeek; return p >= 0 ? p : time; }
    public double duration() { return duration; }
    public double speed() { return speed; }
    public void setVolume(float v) { volume = v; }
    /** With 2 tracks (game + mic) the preview plays both mixed. */
    public void setAudioTracks(int n) { audioTracks = Math.max(1, n); }

    public void setVideoFilter(String f) {
        String nf = f == null ? "" : f;
        if (nf.equals(videoFilter)) return;
        videoFilter = nf;
        if (playing) play(time, speed); else showFrame(time);
    }
    public void setMuted(boolean m) { muted = m; }

    /** The edit's Volume (1 = as recorded) and Mute, so the preview sounds like the export. */
    public void setEditGain(float master, boolean mute) { editGain = Math.max(0, master); editMute = mute; }

    /** The edit's fades as a gain at each source time (null = none). */
    public void setGainAt(java.util.function.DoubleUnaryOperator f) { gainAt = f; }

    /**
     * Each audio track's level (game, mic, voice chat), as the export mixes them. Changing them while playing
     * restarts the sound a moment later, so dragging a slider doesn't restart it on every step.
     */
    public void setTrackGains(double[] g) {
        double[] old = trackGains;
        if (java.util.Arrays.equals(old, g)) return;
        trackGains = g == null ? null : g.clone();
        if (playing && audioTracks >= 2) restart.restart();
    }

    /** Starts playing from {@code from} at {@code speed} (0.25 .. 4). */
    public void play(double from, double speed) {
        if (file == null) return;
        restart.stop();
        seekLater.stop();
        pendingSeek = -1;
        lastStart = System.nanoTime();
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
        restart.stop();
        seekLater.stop();
        double p = pendingSeek;
        pendingSeek = -1;
        stop();
        if (p >= 0) { time = p; showFrame(p); }
    }

    private void stop() {
        playing = false;
        generation.incrementAndGet();
        synchronized (procs) {
            for (Process p : procs) p.destroyForcibly();
            procs.clear();
        }
    }

    public void close() { restart.stop(); seekLater.stop(); pendingSeek = -1; stop(); file = null; }

    /**
     * Seeks; keeps playing if it was playing, otherwise shows the frame at {@code t}. While playing, seeks that
     * come faster than every 120 ms (dragging) restart playback once they settle instead of each time.
     */
    public void seek(double t) {
        t = Math.max(0, Math.min(t, Math.max(0, duration - 0.02)));
        if (playing) {
            if (System.nanoTime() - lastStart < 120_000_000L) {
                pendingSeek = t;
                seekLater.restart();
            } else play(t, speed);
        }
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
                    "-vf", "fps=" + Library.fmt(fps) + ",scale=" + w + ":" + h + ":flags=fast_bilinear" + (videoFilter.isEmpty() ? "" : "," + videoFilter),
                    "-f", "rawvideo", "-pix_fmt", "bgr24", "-"));
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(p.getInputStream(), 1 << 20));
            int frameBytes = w * h * 3;
            // Three frames, reused in turn: the UI paints one while the next is decoded into another.
            BufferedImage[] ring = new BufferedImage[3];
            for (int i = 0; i < ring.length; i++) ring[i] = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            int slot = 0;
            long n = 0;
            while (generation.get() == gen) {
                BufferedImage img = ring[slot];
                slot = (slot + 1) % ring.length;
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
            List<String> args = new ArrayList<>(List.of("-loglevel", "error", "-ss", Library.fmt(start), "-i", file.toString(), "-vn"));
            if (audioTracks >= 2) {
                // every track at the edit's level for it, mixed the way the export mixes them
                double[] g = trackGains;
                StringBuilder graph = new StringBuilder(), in = new StringBuilder();
                for (int i = 0; i < audioTracks; i++) {
                    double v = g != null && i < g.length ? Math.max(0, g[i]) : 1;
                    graph.append("[0:a:").append(i).append("]volume=").append(Library.fmt(v)).append("[t").append(i).append("];");
                    in.append("[t").append(i).append("]");
                }
                args.addAll(List.of("-filter_complex", graph + in.toString() + "amix=inputs=" + audioTracks + ":normalize=0," + af + "[pa]", "-map", "[pa]"));
            }
            else args.addAll(List.of("-af", af.toString()));
            args.addAll(List.of("-f", "s16le", "-ac", "2", "-ar", String.valueOf(RATE), "-"));
            p = spawn(args);
            AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, RATE / 5 * 4); // ~200 ms buffer
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            boolean started = false;
            long frames = 0;
            int r;
            while (generation.get() == gen && (r = in.readNBytes(buf, 0, buf.length)) > 0) {
                r -= r % 4;
                // monitor volume x the edit's volume, mute and fades (at this buffer's moment of the source)
                float v = muted || editMute ? 0 : volume * editGain;
                java.util.function.DoubleUnaryOperator env = gainAt;
                if (env != null && v > 0) v *= (float) Math.max(0, Math.min(1, env.applyAsDouble(start + frames / (double) RATE * speed)));
                frames += r / 4;
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
                    "-vf", "scale=" + w + ":" + h + ":flags=fast_bilinear" + (videoFilter.isEmpty() ? "" : "," + videoFilter), "-f", "rawvideo", "-pix_fmt", "bgr24", "-"))
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
