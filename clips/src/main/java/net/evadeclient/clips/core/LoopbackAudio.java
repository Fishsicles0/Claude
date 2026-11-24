package net.evadeclient.clips.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * "What you hear": game and desktop sound recorded straight from the speakers, with no Stereo Mix or virtual cable.
 *
 * <p>A {@link Source} (on Windows, {@link WasapiLoopback}) hands over whatever the speakers played; this streams it to
 * ffmpeg as 32-bit float stereo over a local connection ({@code -f f32le -i tcp://127.0.0.1:port}), to every capture
 * ffmpeg that connects (the replay buffer, and a recording with its own bitrate). The output always runs at exactly
 * real time: when nothing is playing the speakers deliver nothing, so silence is filled in, and if the device
 * delivers in a burst after a pause the stream never runs more than a fraction of a second ahead. That keeps the
 * sound in step with the video however long the quiet stretches are.
 */
public final class LoopbackAudio {
    /** Somewhere sound comes from. Everything is called on the pump thread. */
    public interface Source {
        int rate();
        /** Copies what has been played since the last call, as interleaved stereo frames; returns frames, or -1 if the device went away. */
        int read(float[] stereo) throws Exception;
        void close();
    }

    /** What ffmpeg needs to read the stream. */
    public record Stream(int port, int rate) {
        public List<String> ffmpegInput() {
            return List.of("-f", "f32le", "-ar", String.valueOf(rate), "-ch_layout", "stereo", "-i", "tcp://127.0.0.1:" + port);
        }
    }

    private static final int MAX_FRAMES = 48_000; // one second per read, at most
    private static LoopbackAudio windows;
    private static long windowsFailedAt;

    private final Supplier<Source> factory;
    private final ServerSocket server;
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private volatile int rate;
    private volatile String error;
    /** Frames sent since the stream started, and when that was (for the real-time pacing). */
    private long sent, startNanos;

    private LoopbackAudio(Supplier<Source> factory) throws IOException {
        this.factory = factory;
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    /**
     * The Windows speakers as a stream, started on first use and kept running (opening the device takes a moment,
     * and capture restarts often). Null when it isn't available: not Windows, or the device couldn't be opened
     * (tried again after a minute).
     */
    public static synchronized Stream windows() {
        if (!Ffmpeg.WINDOWS) return null;
        if (windows != null && windows.running) return windows.stream();
        if (windowsFailedAt != 0 && System.currentTimeMillis() - windowsFailedAt < 60_000) return null;
        try {
            windows = start(() -> {
                try { return WasapiLoopback.open(); } catch (Throwable t) { throw new IllegalStateException(t.getMessage(), t); }
            });
            return windows.stream();
        } catch (Exception e) {
            System.err.println("[clips] speaker recording unavailable: " + e.getMessage());
            windowsFailedAt = System.currentTimeMillis();
            windows = null;
            return null;
        }
    }

    /** Whether "what you hear" works on this PC (opens the device once if it isn't running yet). */
    public static boolean windowsAvailable() { return windows() != null; }

    /** Starts streaming from {@code factory}'s source; throws if the first open fails. */
    public static LoopbackAudio start(Supplier<Source> factory) throws Exception {
        LoopbackAudio l = new LoopbackAudio(factory);
        CountDownLatch opened = new CountDownLatch(1);
        Thread pump = new Thread(() -> l.pump(opened), "speaker audio");
        pump.setDaemon(true);
        pump.start();
        if (!opened.await(5, TimeUnit.SECONDS) || l.rate == 0) {
            l.stop();
            throw new IOException(l.error != null ? l.error : "the speakers didn't open in time");
        }
        Thread accept = new Thread(l::acceptLoop, "speaker audio clients");
        accept.setDaemon(true);
        accept.start();
        return l;
    }

    public Stream stream() { return new Stream(server.getLocalPort(), rate); }

    public void stop() {
        running = false;
        try { server.close(); } catch (IOException ignored) {}
        for (OutputStream o : clients) try { o.close(); } catch (IOException ignored) {}
        clients.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setSendBufferSize(1 << 18);
                clients.add(new BufferedOutputStream(s.getOutputStream(), 1 << 14));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void pump(CountDownLatch opened) {
        Source src = null;
        float[] buf = new float[MAX_FRAMES * 2];
        try {
            while (running) {
                if (src == null) {
                    try {
                        src = factory.get();
                        if (rate == 0) {
                            rate = src.rate();
                            startNanos = System.nanoTime();
                        } else if (src.rate() != rate) {
                            // a different device with another rate: keep the stream's rate (ffmpeg is told it once)
                            System.err.println("[clips] speakers changed to " + src.rate() + " Hz; recording silence until capture restarts");
                            src.close();
                            src = null;
                        }
                    } catch (RuntimeException e) {
                        error = e.getMessage();
                        if (rate == 0) return; // the first open failed: start() reports it
                    } finally {
                        opened.countDown();
                    }
                }
                int n = 0;
                if (src != null) {
                    try {
                        n = src.read(buf);
                    } catch (Exception e) {
                        n = -1;
                    }
                    if (n < 0) { // device unplugged or switched: reopen the default one shortly
                        src.close();
                        src = null;
                        n = 0;
                    }
                }
                pace(buf, n);
                Thread.sleep(10);
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (src != null) src.close();
            opened.countDown();
        }
    }

    /** Sends {@code n} frames, keeping the stream at real time: silence for gaps, nothing past 250 ms ahead. */
    void pace(float[] buf, int n) {
        long expected = (System.nanoTime() - startNanos) * rate / 1_000_000_000L;
        if (n > 0 && sent + n > expected + rate / 4) n = (int) Math.max(0, expected + rate / 4 - sent);
        if (n > 0) send(buf, n);
        long behind = expected - sent;
        if (behind > rate / 20) { // more than 50 ms with nothing played: fill with silence
            long fill = Math.min(behind, MAX_FRAMES);
            java.util.Arrays.fill(buf, 0, (int) fill * 2, 0f);
            send(buf, (int) fill);
        }
    }

    private void send(float[] buf, int frames) {
        sent += frames;
        if (clients.isEmpty()) return;
        ByteBuffer b = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(buf, 0, frames * 2);
        byte[] bytes = b.array();
        for (OutputStream o : clients) {
            try {
                o.write(bytes);
                o.flush();
            } catch (IOException e) {
                clients.remove(o); // that capture stopped
                try { o.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Frames streamed so far (for tests). */
    public long sentFrames() { return sent; }
}
