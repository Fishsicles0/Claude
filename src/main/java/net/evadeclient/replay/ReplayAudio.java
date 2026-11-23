package net.evadeclient.replay;

import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.SoundManagerAccessor;
import net.evadeclient.mixin.SoundSystemAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundExecutor;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Game audio for replay renders. While a render runs, the game's sound engine is restarted on an OpenAL "loopback"
 * device: nothing comes out of your speakers, and instead the render pulls exactly 1/fps of a second of mixed audio
 * after every frame. So the sound lines up with the picture sample for sample, however slowly the frames draw.
 */
public final class ReplayAudio {
    public static final int RATE = 48_000, CHANNELS = 2;
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static volatile boolean wanted;
    private static volatile long device;
    /** Sounds played while rendering whose files are still loading. */
    private static final java.util.Queue<java.util.concurrent.CompletableFuture<?>> loading = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private ReplayAudio() {}

    /** The loopback device while one is open, else 0. */
    public static long device() { return device; }

    // ------------------------------------------------------------------ called from the sound engine (mixins)

    /** The sound engine is opening its device: give it a loopback device if a render asked for one. */
    public static long openLoopback() {
        if (!wanted) return 0L;
        try {
            if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")) {
                EvadeClient.LOG.warn("Replay audio: this OpenAL has no loopback support");
                return 0L;
            }
            long d = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (d == 0L) return 0L;
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(d, RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                ALC10.alcCloseDevice(d);
                return 0L;
            }
            device = d;
            return d;
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Replay audio: couldn't open a loopback device", t);
            return 0L;
        }
    }

    /** The game's context attributes plus the output format a loopback device needs. */
    public static IntBuffer attributes(MemoryStack stack, IntBuffer game) {
        IntBuffer out = stack.callocInt(game.limit() + 8);
        for (int i = 0; i + 1 < game.limit() && game.get(i) != 0; i += 2) out.put(game.get(i)).put(game.get(i + 1));
        out.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
                .put(ALC10.ALC_FREQUENCY).put(RATE)
                .put(0);
        return out.flip();
    }

    /** A sound that will start once its file has loaded. */
    public static void pending(java.util.concurrent.CompletableFuture<?> start) { loading.add(start); }

    /** Waits (up to 2 s) for sounds still loading, so they start now rather than a few frames late. */
    private static void awaitLoading() {
        long until = System.nanoTime() + 2_000_000_000L;
        for (java.util.concurrent.CompletableFuture<?> f; (f = loading.peek()) != null; loading.poll()) {
            long left = until - System.nanoTime();
            if (left <= 0) { loading.clear(); return; }
            try { f.get(left, java.util.concurrent.TimeUnit.NANOSECONDS); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ slow motion

    /** Implemented by every OpenAL source (mixin): re-applies its pitch times {@link #timeScale()}. */
    public interface ScaledSource { void evade$rescale(); }

    private static volatile float timeScale = 1f;
    private static boolean pausedByReplay;

    /** How fast game sound plays: 0.5 in half-speed slow motion (an octave down, twice as long), 1 normally. */
    public static float timeScale() { return timeScale; }

    /** Sets {@link #timeScale()} and updates every sound already playing. */
    static void setTimeScale(double scale) {
        float f = (float) Math.max(0.05, Math.min(8, scale));
        if (Math.abs(f - timeScale) < 1e-4) return;
        timeScale = f;
        try {
            SoundSystemAccessor sys = system();
            if (sys.evade$started()) sys.evade$channel().execute(sources -> sources.forEach(s -> ((ScaledSource) s).evade$rescale()));
        } catch (Throwable t) {
            EvadeClient.LOG.debug("Replay audio: couldn't rescale sounds: {}", t.toString());
        }
    }

    /** Every client tick: replay speed and pause for all sound, and the voices. */
    static void onTick(ReplayPlayer p) {
        if (p == null) {
            setTimeScale(1);
            setPaused(false);
            return;
        }
        boolean rendering = ReplayRender.rendering();
        boolean paused = !rendering && !p.playing() && !p.seeking();
        // while rendering, the render sets the scale frame by frame from the speed keyframes
        if (!rendering) setTimeScale(p.project.render.slowSound ? p.speed() : 1);
        setPaused(paused);
        ReplayVoice.tick(paused);
    }

    /** Paused replay, paused sound (what was playing carries on when you press play). */
    static void setPaused(boolean paused) {
        if (paused == pausedByReplay) return;
        pausedByReplay = paused;
        if (paused) mc.getSoundManager().pauseAllExcept();
        else mc.getSoundManager().resumeAll();
    }

    // ------------------------------------------------------------------ switching

    private static SoundSystemAccessor system() {
        return (SoundSystemAccessor) ((SoundManagerAccessor) mc.getSoundManager()).evade$soundSystem();
    }

    /** Moves the game's sound onto the loopback device. Returns false (sound left as it was) if that isn't possible. */
    static boolean enable() {
        if (device != 0L) return true;
        wanted = true;
        try {
            mc.getSoundManager().reloadSounds();
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Replay audio: restarting the sound engine failed", t);
        }
        if (device != 0L && system().evade$started()) return true;
        EvadeClient.LOG.warn("Replay audio: not available, rendering without sound");
        disable();
        return false;
    }

    /** Back to your speakers. */
    static void disable() {
        boolean was = wanted || device != 0L;
        wanted = false;
        device = 0L;
        loading.clear();
        if (was) {
            try { mc.getSoundManager().reloadSounds(); }
            catch (Throwable t) { EvadeClient.LOG.warn("Replay audio: restarting the sound engine failed", t); }
        }
    }

    // ------------------------------------------------------------------ capture

    /** Raw 16-bit stereo PCM, written as the frames render. */
    static final class Capture implements AutoCloseable {
        final Path file;
        private final OutputStream out;
        private double owed;
        private long samples, peak;

        Capture(Path file) throws IOException {
            this.file = file;
            this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        }

        /** Mixes the next {@code seconds} of game audio (on the sound thread, after everything it's been asked to play). */
        void advance(double seconds) throws IOException {
            long d = device;
            owed += seconds * RATE;
            int n = (int) owed;
            if (n <= 0) return;
            owed -= n;
            short[] buf = new short[n * CHANNELS];
            SoundExecutor exec = d != 0L ? system().evade$taskQueue() : null;
            // the loaded sounds queue their start on the sound thread; mixing after that queue keeps them on this frame
            if (exec != null) awaitLoading();
            if (exec != null) exec.submit(() -> SOFTLoopback.alcRenderSamplesSOFT(d, buf, n)).join();
            byte[] bytes = new byte[buf.length * 2];
            for (int i = 0; i < buf.length; i++) {
                short s = buf[i];
                bytes[i * 2] = (byte) s;
                bytes[i * 2 + 1] = (byte) (s >> 8);
                peak = Math.max(peak, Math.abs((int) s));
            }
            out.write(bytes);
            samples += n;
        }

        long samples() { return samples; }
        /** Loudest sample so far (0 = silence). */
        long peak() { return peak; }

        @Override public void close() throws IOException { out.close(); }

        /** Turns the raw PCM into a WAV file (for when there's no ffmpeg to put it in the video). */
        void toWav(Path wav) throws IOException {
            long data = Files.size(file);
            try (RandomAccessFile w = new RandomAccessFile(wav.toFile(), "rw"); var in = Files.newInputStream(file)) {
                w.setLength(0);
                w.write(new byte[]{'R', 'I', 'F', 'F'}); le32(w, 36 + data); w.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
                le32(w, 16); le16(w, 1); le16(w, CHANNELS); le32(w, RATE); le32(w, (long) RATE * CHANNELS * 2); le16(w, CHANNELS * 2); le16(w, 16);
                w.write(new byte[]{'d', 'a', 't', 'a'}); le32(w, data);
                byte[] b = new byte[1 << 16];
                for (int r; (r = in.read(b)) > 0; ) w.write(b, 0, r);
            }
        }

        private static void le32(RandomAccessFile w, long v) throws IOException { w.write(new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}); }
        private static void le16(RandomAccessFile w, int v) throws IOException { w.write(new byte[]{(byte) v, (byte) (v >> 8)}); }
    }
}
