package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

import java.lang.ref.WeakReference;

/**
 * Estimates the server's ticks per second from its world time updates (sent about once a second): how far the
 * server's clock moved against how much real time passed. In singleplayer the integrated server's own tick time is
 * used. An estimate only: network jitter moves it a little, and a server that stops sending its time reads as stalled.
 */
public final class TpsEstimator {
    private final int samples;
    /** A server silent for longer than this (it normally sends its time every second) counts as stalled. */
    private static final double STALL_SECONDS = 2.5;
    private final long[] sampleNs, sampleTime;
    private int sampleCount, newest = -1;
    /** The connection and world the samples came from; weak, so a world that was left is never kept alive. */
    private WeakReference<Object> sampleConnection = new WeakReference<>(null), sampleWorld = new WeakReference<>(null);

    /** @param samples how many time updates the estimate spans (more is steadier, fewer reacts faster) */
    public TpsEstimator(int samples) {
        this.samples = Math.max(2, samples);
        this.sampleNs = new long[this.samples];
        this.sampleTime = new long[this.samples];
    }

    /** Record a time update (call from a module's onServerPacket; anything else is ignored). */
    public void onPacket(Object packet) {
        if (!(packet instanceof WorldTimeUpdateS2CPacket p)) return;
        record(p.time(), System.nanoTime());
    }

    /** Record that the server's clock read {@code serverTime} at {@code nowNs} (also the test hook). */
    public void record(long serverTime, long nowNs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Object connection = mc.getNetworkHandler();
        boolean reset = sampleConnection.get() != connection || sampleCount > 0 && serverTime < sampleTime[newest];
        if (!reset && sampleCount > 0 && sampleWorld.get() != mc.world && mc.world != null) {
            // A new world on the same connection: a dimension change keeps the server's clock running, but a proxy
            // moving you to another server makes it jump. Keep the samples unless it jumped.
            double seconds = (nowNs - sampleNs[newest]) / 1e9;
            reset = serverTime - sampleTime[newest] > seconds * mc.world.getTickManager().getTickRate() * 2 + 100;
        }
        if (reset) {
            sampleCount = 0;
            newest = -1;
            sampleConnection = new WeakReference<>(connection);
        }
        sampleWorld = new WeakReference<>(mc.world);
        newest = (newest + 1) % samples;
        sampleNs[newest] = nowNs;
        sampleTime[newest] = serverTime;
        if (sampleCount < samples) sampleCount++;
    }

    /** The server's tick rate target (20 unless /tick rate changed it). */
    public static float targetRate() {
        var world = MinecraftClient.getInstance().world;
        return world == null ? 20f : world.getTickManager().getTickRate();
    }

    /** Whether the server's ticking is frozen (/tick freeze). */
    public static boolean frozen() {
        var world = MinecraftClient.getInstance().world;
        return world != null && world.getTickManager().isFrozen();
    }

    /** The server's ticks per second, or NaN until enough time updates have arrived. */
    public float tps() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var world = mc.world;
        if (world == null) return Float.NaN;
        float rate = world.getTickManager().getTickRate();
        var server = mc.getServer();
        if (server != null) {
            float ms = server.getAverageTickTime();
            return ms <= 0 ? rate : Math.min(rate, 1000f / ms);
        }
        if (sampleCount < 2 || sampleConnection.get() != mc.getNetworkHandler()) return Float.NaN;
        int oldest = (newest - sampleCount + 1 + samples) % samples;
        long now = System.nanoTime();
        double span = (sampleNs[newest] - sampleNs[oldest]) / 1e9;
        // A server that has stopped sending its time is stalled: the silence counts as time without ticks.
        if ((now - sampleNs[newest]) / 1e9 > STALL_SECONDS) span = (now - sampleNs[oldest]) / 1e9;
        if (span < 0.25) return Float.NaN;
        double tps = (sampleTime[newest] - sampleTime[oldest]) / span;
        return (float) Math.max(0, Math.min(rate, tps));
    }

    /** Green at full speed, yellow when the server slows down, red when it lags (frozen reads as yellow). */
    public static int color(float tps) {
        float rate = targetRate();
        if (frozen()) return Theme.WARN;
        return tps >= rate * 0.95f ? Theme.OK : tps >= rate * 0.75f ? Theme.WARN : Theme.DANGER;
    }
}
