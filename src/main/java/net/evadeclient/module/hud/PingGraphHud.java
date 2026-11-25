package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.NetStats;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;

import java.lang.ref.WeakReference;

/**
 * Your ping over time, one bar per sample (newest on the right), coloured green, yellow or red like the Ping widget,
 * with a header of the current, average and worst ping and the jitter. Live measures the round trip itself once a
 * second with the game's own ping query (the one F3's ping chart sends every tick); Tab List graphs the number the
 * server reports there, which most servers only refresh every few seconds.
 */
public class PingGraphHud extends HudElementModule {
    private static final int CAPACITY = 240;
    /** What the HUD editor shows before any ping has been measured. */
    private static final int[] SAMPLE = {42, 44, 41, 47, 52, 45, 43, 40, 61, 88, 54, 46, 44, 43, 45, 49, 42, 41, 44, 46};

    public final ModeSetting source = add(new ModeSetting("Source", "Live", "Live", "Tab List")
            .describe("Live measures the round trip once a second with the game's own ping query. Tab List graphs the ping the server lists."));
    public final NumberSetting samples = add(new NumberSetting("Samples Shown", 60, 20, 240, 10)
            .describe("How many seconds of history the graph shows."));
    public final NumberSetting graphHeight = add(new NumberSetting("Graph Height", 28, 16, 64, 4)
            .describe("Height of the graph in pixels."));
    public final NumberSetting barWidth = add(new NumberSetting("Bar Width", 2, 1, 4, 1)
            .describe("Pixels per sample. Wider bars make a wider graph."));
    public final ModeSetting range = add(new ModeSetting("Graph Range", "Auto", "Auto", "100 ms", "200 ms", "500 ms")
            .describe("The ping that fills the graph's height. Auto fits it to the pings shown."));
    public final BooleanSetting header = add(new BooleanSetting("Header", true)
            .describe("Current, average and worst ping and the jitter above the graph."));
    public final NumberSetting goodBelow = add(new NumberSetting("Good Below", 80, 20, 300, 10)
            .describe("Pings under this many ms are drawn in Good Color."));
    public final NumberSetting badAbove = add(new NumberSetting("Bad Above", 150, 50, 1000, 10)
            .describe("Pings over this many ms are drawn in Bad Color; between the two, Fair Color."));
    public final ColorSetting goodColor = add(new ColorSetting("Good Color", 0xFF22C55E)
            .describe("Colour of pings under Good Below."));
    public final ColorSetting fairColor = add(new ColorSetting("Fair Color", 0xFFEAB308)
            .describe("Colour of pings between Good Below and Bad Above."));
    public final ColorSetting badColor = add(new ColorSetting("Bad Color", 0xFFE11D2E)
            .describe("Colour of pings over Bad Above."));
    public final ColorSetting headerColor = add(new ColorSetting("Header Color", 0xFFFFFFFF)
            .describe("Colour of the stats line above the graph."));

    private final int[] ring = new int[CAPACITY];
    private int count, head;
    private int seenSerial = -1;
    private long lastTabSample;
    private WeakReference<ClientConnection> connection = new WeakReference<>(null);
    private String headerString = "";
    private long headerTick = Long.MIN_VALUE;
    /** The ping that fills the graph height, eased in Auto. */
    private float scaleMs = 100;

    public PingGraphHud() {
        super("Ping Graph", "Your ping over the last minute as a graph, with average, worst and jitter.", -1, 270);
    }

    @Override
    protected void onDisable() {
        count = head = 0;
        seenSerial = -1;
    }

    @Override
    public void onTick() {
        ClientConnection c = NetStats.connection();
        if (c != connection.get()) {
            connection = new WeakReference<>(c);
            count = head = 0;
            NetStats.clearPings();
            seenSerial = NetStats.pingSerial();
        }
        if (c == null || mc.isInSingleplayer()) return;
        if (source.is("Live")) {
            NetStats.queryPing(1000);
            int serial = NetStats.pingSerial();
            if (serial != seenSerial && NetStats.pingCount() > 0) {
                seenSerial = serial;
                push(NetStats.ping(0));
            }
        } else {
            long now = System.currentTimeMillis();
            if (now - lastTabSample < 1000) return;
            lastTabSample = now;
            var entry = mc.getNetworkHandler() == null || mc.player == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) push(entry.getLatency());
        }
    }

    /** Adds a sample to the graph (also the test hook). */
    public void push(int ms) {
        ring[head] = Math.max(0, ms);
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    public int sampleCount() { return count; }

    /** The {@code i}-th newest sample shown (0 = newest). */
    private int sample(int i, boolean demo) {
        if (demo) return SAMPLE[(SAMPLE.length - 1 - i % SAMPLE.length + SAMPLE.length) % SAMPLE.length];
        return ring[(head - 1 - i + CAPACITY * 2) % CAPACITY];
    }

    /** Test hook: the header as last built. */
    public String headerForTest() { return headerString; }

    private int colorFor(int ms) {
        return ms < goodBelow.get() ? goodColor.get() : ms > badAbove.get() ? badColor.get() : fairColor.get();
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        int n = samples.getInt(), bw = barWidth.getInt(), h = graphHeight.getInt();
        boolean demo = count == 0 && preview;
        int shown = demo ? n : Math.min(n, count);
        if (shown == 0 && !preview) {
            // Nothing measured yet (or singleplayer): keep the frame so it can still be placed, but say so.
            headerString = mc.isInSingleplayer() ? "Ping: local" : "Ping: waiting...";
        } else if (EvadeClient.ticks != headerTick) {
            headerTick = EvadeClient.ticks;
            long sum = 0;
            int max = 0, jitterSum = 0;
            for (int i = 0; i < shown; i++) {
                int v = sample(i, demo);
                sum += v;
                max = Math.max(max, v);
                if (i > 0) jitterSum += Math.abs(v - sample(i - 1, demo));
            }
            int avg = shown == 0 ? 0 : Math.round(sum / (float) shown);
            int jitter = shown < 2 ? 0 : Math.round(jitterSum / (float) (shown - 1));
            headerString = "Ping " + sample(0, demo) + "ms  avg " + avg + "  max " + max + "  jitter " + jitter;
            float target = switch (range.get()) {
                case "100 ms" -> 100f;
                case "200 ms" -> 200f;
                case "500 ms" -> 500f;
                default -> Math.max(50f, max * 1.2f);
            };
            scaleMs = range.is("Auto") ? scaleMs + (target - scaleMs) * (target > scaleMs ? 0.4f : 0.08f) : target;
        }
        boolean head = header.get();
        Text headerText = styled(headerString);
        int gw = n * bw;
        int hw = head ? tr.getWidth(headerText) : 0;
        width = Math.max(gw, hw) + 8;
        height = h + (head ? 18 : 8);
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        if (head) ctx.drawText(tr, headerText, left + 4, top + 3, headerColor.get(), true);
        int gx = left + 4 + (width - 8 - gw), gy = top + (head ? 14 : 4);
        int base = gy + h;
        ctx.fill(gx, base, gx + gw, base + 1, 0x30FFFFFF);
        float per = h / Math.max(1f, scaleMs);
        for (int i = 0; i < shown; i++) {
            int v = sample(i, demo);
            int barH = Math.max(1, Math.min(h, Math.round(v * per)));
            int x = gx + gw - (i + 1) * bw;
            ctx.fill(x, base - barH, x + bw, base, colorFor(v));
        }
    }
}
