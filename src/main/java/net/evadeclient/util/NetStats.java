package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.util.Util;

/**
 * Your own connection to the server, as the client sees it: when the last packet arrived (for Connection Watch),
 * packets per second in and out (vanilla's own counters), and round-trip pings measured with the vanilla ping query
 * (the packet F3's ping chart sends every tick while it's open; Ping Graph sends one a second at most).
 * Nothing here looks at other players.
 */
public final class NetStats {
    private NetStats() {}

    /** Arrival of the newest packet on the play connection (System.nanoTime), or 0 before any. Set on the network thread. */
    private static volatile long lastReceivedNs;
    /** The connection {@link #lastReceivedNs} belongs to (identity only). */
    private static volatile ClientConnection lastConnection;

    /** Ping history, newest last: round trips in ms. Written on the network thread, read on the render thread. */
    public static final int PING_CAPACITY = 600;
    private static final int[] pings = new int[PING_CAPACITY];
    private static int pingCount, pingHead;
    /** Bumped for every ping result, so a graph knows when to repaint. */
    private static volatile int pingSerial;
    private static long lastQueryMs;

    // ------------------------------------------------------------------ receive time

    /** ClientConnectionWatchMixin: a packet arrived on {@code connection}. Network thread; cheap. */
    public static void onReceive(ClientConnection connection) {
        lastConnection = connection;
        lastReceivedNs = System.nanoTime();
    }

    /** The play connection, or null outside a server/world. */
    public static ClientConnection connection() {
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        return nh == null ? null : nh.getConnection();
    }

    /**
     * Seconds since the last packet arrived on the current play connection, or 0 when there is none (or nothing has
     * arrived on it yet, so a fresh join never reads as silent).
     */
    public static double silentSeconds() {
        ClientConnection c = connection();
        if (c == null || c != lastConnection || lastReceivedNs == 0) return 0;
        return Math.max(0, (System.nanoTime() - lastReceivedNs) / 1e9);
    }

    /** Test hook: pretend the last packet arrived {@code secondsAgo} seconds ago on the current connection. */
    public static void fakeLastReceived(double secondsAgo) {
        lastConnection = connection();
        lastReceivedNs = System.nanoTime() - (long) (secondsAgo * 1e9);
    }

    // ------------------------------------------------------------------ packets per second

    /** Packets received per second on the play connection (vanilla's smoothed counter), or -1 when not connected. */
    public static float packetsIn() {
        ClientConnection c = connection();
        return c == null ? -1 : c.getAveragePacketsReceived();
    }

    /** Packets sent per second on the play connection (vanilla's smoothed counter), or -1 when not connected. */
    public static float packetsOut() {
        ClientConnection c = connection();
        return c == null ? -1 : c.getAveragePacketsSent();
    }

    // ------------------------------------------------------------------ ping

    /**
     * Sends one vanilla ping query when at least {@code intervalMs} have passed since the last one (client thread).
     * The answer comes back through {@link #onPingResult}. Never in singleplayer, where there is no network.
     */
    public static void queryPing(long intervalMs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        var nh = mc.getNetworkHandler();
        if (nh == null || mc.isInSingleplayer()) return;
        long now = Util.getMeasuringTimeMs();
        if (now - lastQueryMs < intervalMs) return;
        lastQueryMs = now;
        nh.sendPacket(new QueryPingC2SPacket(now));
    }

    /** PingResultMixin: a ping query came back (network thread). {@code startMs} is what the query carried. */
    public static void onPingResult(long startMs) {
        long rtt = Util.getMeasuringTimeMs() - startMs;
        if (rtt < 0 || rtt > 60_000) return;
        record((int) rtt);
    }

    /** Adds one ping sample (also the test hook). */
    public static synchronized void record(int ms) {
        pings[pingHead] = ms;
        pingHead = (pingHead + 1) % PING_CAPACITY;
        if (pingCount < PING_CAPACITY) pingCount++;
        pingSerial++;
    }

    /** Forget the ping history (leaving a server). */
    public static synchronized void clearPings() {
        pingCount = 0;
        pingHead = 0;
        pingSerial++;
    }

    public static synchronized int pingCount() { return pingCount; }

    /** The {@code i}-th newest ping (0 = newest), in ms. */
    public static synchronized int ping(int i) {
        if (i < 0 || i >= pingCount) return 0;
        return pings[(pingHead - 1 - i + PING_CAPACITY * 2) % PING_CAPACITY];
    }

    public static int pingSerial() { return pingSerial; }
}
