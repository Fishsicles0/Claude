package net.evadeclient.voice.client;

import io.github.jaredmdobson.concentus.OpusDecoder;
import net.evadeclient.EvadeClient;
import net.evadeclient.voice.VoiceCommon;
import net.evadeclient.voice.VoiceDownPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Decodes incoming voice and mixes every speaker into one stereo stream: louder when close, quieter when far,
 * panned left/right by where they stand relative to where you're looking.
 */
public final class VoicePlayback {
    public static final int RATE = 48000, FRAME = 960; // 20 ms mono frames

    private static final class Stream {
        final OpusDecoder decoder;
        final ArrayDeque<short[]> queue = new ArrayDeque<>();
        final boolean positional;
        volatile long lastHeard;
        Stream(boolean positional) throws Exception { this.decoder = new OpusDecoder(RATE, 1); this.positional = positional; }
    }

    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private final DoubleSupplier volume;
    private volatile boolean running;
    private Thread thread;

    // counters for the HUD and tests
    public volatile int framesReceived, framesMixed;
    public volatile short[] lastDecoded;
    private volatile boolean deviceFailed;

    public VoicePlayback(DoubleSupplier volume) { this.volume = volume; }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "Evade Voice playback");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        streams.clear();
    }

    /** True if no speakers are configured on this computer (playback is then silent but still runs). */
    public boolean deviceFailed() { return deviceFailed; }

    /** Players heard in the last half second (for the speaking HUD). */
    public Map<UUID, Long> speaking() {
        Map<UUID, Long> out = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        streams.forEach((k, s) -> { if (now - s.lastHeard < 500 && s.positional) out.put(UUID.fromString(k.substring(2)), s.lastHeard); });
        return out;
    }

    /** Called on the main thread for each frame the server forwards. */
    public void receive(VoiceDownPayload p) {
        try {
            String key = (p.positional() ? "p:" : "t:") + p.speaker();
            Stream s = streams.get(key);
            if (s == null) { s = new Stream(p.positional()); streams.put(key, s); }
            short[] pcm = new short[FRAME];
            int n = s.decoder.decode(p.opus(), 0, p.opus().length, pcm, 0, FRAME, false);
            if (n <= 0) return;
            s.lastHeard = System.currentTimeMillis();
            synchronized (s.queue) {
                s.queue.addLast(pcm);
                while (s.queue.size() > 12) s.queue.removeFirst(); // cap latency at ~240 ms
            }
            framesReceived++;
            lastDecoded = pcm;
        } catch (Exception e) {
            EvadeClient.LOG.debug("Voice: couldn't decode a frame", e);
        }
    }

    private void loop() {
        SourceDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, FRAME * 4 * 6);
            line.start();
        } catch (Exception e) {
            deviceFailed = true;
            EvadeClient.LOG.warn("Voice: no audio output available ({}); voices will be received but not played", e.toString());
        }
        int[] acc = new int[FRAME * 2];
        byte[] out = new byte[FRAME * 4];
        long next = System.nanoTime();
        while (running) {
            java.util.Arrays.fill(acc, 0);
            mixInto(acc);
            for (int i = 0; i < acc.length; i++) {
                int v = Math.max(-32768, Math.min(32767, acc[i]));
                out[i * 2] = (byte) v;
                out[i * 2 + 1] = (byte) (v >> 8);
            }
            if (line != null) {
                line.write(out, 0, out.length); // blocks, which paces the loop at real time
            } else {
                next += 20_000_000L;
                long sleep = next - System.nanoTime();
                if (sleep > 0) try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); } catch (InterruptedException ie) { break; }
            }
        }
        if (line != null) { line.stop(); line.close(); }
    }

    private void mixInto(int[] acc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity me = mc.player;
        double master = volume.getAsDouble();
        for (var entry : streams.entrySet()) {
            Stream s = entry.getValue();
            short[] f;
            synchronized (s.queue) { f = s.queue.pollFirst(); }
            if (f == null) continue;
            double l = master, r = master;
            if (s.positional && me != null && mc.world != null) {
                PlayerEntity who = mc.world.getPlayerByUuid(UUID.fromString(entry.getKey().substring(2)));
                if (who == null) continue;
                double dx = who.getX() - me.getX(), dz = who.getZ() - me.getZ(), dy = who.getY() - me.getY();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double fade = dist <= 6 ? 1 : Math.max(0, 1 - (dist - 6) / (VoiceCommon.RANGE - 6));
                double yaw = Math.toRadians(me.getYaw());
                // listener's right-hand vector in Minecraft's coordinates is (-cos yaw, -sin yaw)
                double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                double pan = dist < 0.5 ? 0 : (dx * rx + dz * rz) / Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                l *= fade * Math.min(1, 1 - pan * 0.6);
                r *= fade * Math.min(1, 1 + pan * 0.6);
            }
            for (int i = 0; i < FRAME; i++) {
                acc[i * 2] += (int) (f[i] * l);
                acc[i * 2 + 1] += (int) (f[i] * r);
            }
            framesMixed++;
        }
        // drop streams that have been silent for a while
        long now = System.currentTimeMillis();
        streams.entrySet().removeIf(e -> now - e.getValue().lastHeard > 10_000);
    }
}
