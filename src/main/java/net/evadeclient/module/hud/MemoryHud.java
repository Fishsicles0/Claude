package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Java memory usage like F3's "Mem" line, in a choice of formats and units, coloured as it fills up, with optional
 * F3-style allocated and allocation-rate lines, garbage-collection activity, a usage bar and a warning when memory
 * stays nearly full even right after a collection (the game needs more RAM).
 */
public class MemoryHud extends TextHudModule {
    private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
    private static final long MB = 1024L * 1024L, GB = MB * 1024L;
    /** Auto switches to GB from this much maximum memory up (where MB values get five digits). */
    private static final long AUTO_GB_FROM = 10 * GB;
    private static final int FLASH_MS = 300;
    private static final long ALERT_COOLDOWN_MS = 120_000, GC_WINDOW_MS = 60_000;

    private final ModeSetting format = add(new ModeSetting("Format", "Percent + Used/Max",
            "Percent + Used/Max", "Percent", "Used/Max", "Used", "Free"));
    private final ModeSetting unit = add(new ModeSetting("Unit", "Auto", "MB", "GB", "Auto")
            .describe("Auto shows GB when the game may use 10 GB or more, MB otherwise."));
    private final ModeSetting label = add(new ModeSetting("Label", "Mem", "Mem", "RAM", "None"));
    private final BooleanSetting showAllocated = add(new BooleanSetting("Show Allocated", false)
            .describe("Also show how much memory Java has reserved from the system so far, like F3's Allocated line."));
    private final NumberSetting refresh = add(new NumberSetting("Refresh Rate", 0.5, 0.05, 2.0, 0.05)
            .describe("Seconds between updates."));
    private final BooleanSetting colorByUsage = add(new BooleanSetting("Color By Usage", true)
            .describe("Turn the usage line to Warning Color, then Critical Color, as memory fills up."));
    private final NumberSetting warnAt = add(new NumberSetting("Warning At", 70, 50, 95, 5)
            .describe("Usage in percent from which the line uses Warning Color."));
    private final NumberSetting critAt = add(new NumberSetting("Critical At", 85, 60, 99, 1)
            .describe("Usage in percent from which the line uses Critical Color."));
    private final ColorSetting warnColor = add(new ColorSetting("Warning Color", 0xFFFFD21F));
    private final ColorSetting critColor = add(new ColorSetting("Critical Color", 0xFFE11D2E));
    private final BooleanSetting alert = add(new BooleanSetting("High Usage Alert", false)
            .describe("Warn you (at most every two minutes) when memory is still above Critical At right after "
                    + "Java has freed what it can: the game needs more RAM."));
    private final BooleanSetting showRate = add(new BooleanSetting("Show Allocation Rate", false)
            .describe("How fast the game takes new memory, like F3's Allocation rate."));
    private final ModeSetting showGc = add(new ModeSetting("Show GC", "Off", "Off", "Count/min", "Last Pause", "Both")
            .describe("Garbage collections (Java freeing memory) in the last minute, and how long the last one "
                    + "paused the game."));
    private final BooleanSetting flashOnGc = add(new BooleanSetting("Flash On GC", false)
            .describe("Briefly flash the text whenever Java frees memory."));
    private final BooleanSetting usageBar = add(new BooleanSetting("Usage Bar", false)
            .describe("A bar under the text that fills as memory is used."));
    private final NumberSetting barHeight = add(new NumberSetting("Bar Height", 2, 1, 4, 1));
    private final BooleanSetting barAllocated = add(new BooleanSetting("Show Allocated On Bar", true)
            .describe("A fainter part of the bar for the memory Java has reserved but isn't using."));

    // ---- the shown lines and the readings behind them
    private List<String> shown;
    private long nextRefresh;
    private int shownKey;
    /** Bytes at the last refresh: in use, the most Java may use, and reserved from the system so far. */
    private long used, max = 1, total;
    private int pct;

    // ---- garbage collection (read every tick while something needs it)
    private long gcCount = -1, gcMillis;
    /** {time, collections} for every tick that saw collections in the last minute. */
    private final ArrayDeque<long[]> gcTimes = new ArrayDeque<>();
    private long lastPauseMs = -1;
    private long flashStart = Long.MIN_VALUE / 4, lastAlert = Long.MIN_VALUE / 4;
    /** Collections in a row after which usage was still at or above Critical At. */
    private int highAfterGc;

    // ---- allocation rate, sampled every 500 ms like F3's (vanilla's MemoryDebugHudEntry.AllocationRateCalculator)
    private boolean rateStarted;
    private long rateSampleMs, rateBytes, rateGcCount, allocationRate;

    public MemoryHud() { super("Memory", "Java memory usage.", 4, 104); }

    @Override
    protected void onEnable() { resetTracking(); }

    @Override
    protected void onDisable() { resetTracking(); }

    private void resetTracking() {
        gcCount = -1;
        gcTimes.clear();
        lastPauseMs = -1;
        highAfterGc = 0;
        rateStarted = false;
        allocationRate = 0;
    }

    /** Whether anything shown needs the collectors read every tick (nothing does by default). */
    private boolean tracksGc() {
        return !showGc.is("Off") || flashOnGc.get() || alert.get() || showRate.get();
    }

    @Override
    public void onTick() {
        if (!tracksGc()) {
            if (gcCount >= 0 || rateStarted) resetTracking();
            return;
        }
        long now = Util.getMeasuringTimeMs();
        long pauses = 0, pauseMillis = 0, all = 0;
        for (GarbageCollectorMXBean gc : COLLECTORS) {
            long count = Math.max(0, gc.getCollectionCount());
            all += count;
            // Concurrent collectors (ZGC, Shenandoah, G1's concurrent cycle) also report work that runs alongside
            // the game; only their pauses stop it.
            String name = gc.getName();
            if (name.contains("Concurrent") || name.contains("Cycles")) continue;
            pauses += count;
            pauseMillis += Math.max(0, gc.getCollectionTime());
        }
        Runtime rt = Runtime.getRuntime();
        long usedNow = rt.totalMemory() - rt.freeMemory();
        sampleAllocationRate(now, usedNow, all);

        if (gcCount >= 0 && pauses > gcCount) {
            long collections = pauses - gcCount;
            gcTimes.addLast(new long[]{now, collections});
            lastPauseMs = Math.round(Math.max(0, pauseMillis - gcMillis) / (double) collections);
            flashStart = now;
            if (alert.get()) checkAlert(now, usedNow);
        }
        gcCount = pauses;
        gcMillis = pauseMillis;
        while (!gcTimes.isEmpty() && now - gcTimes.peekFirst()[0] > GC_WINDOW_MS) gcTimes.removeFirst();
    }

    /** Right after a collection: memory still nearly full twice in a row means the game is short of RAM. */
    private void checkAlert(long now, long usedNow) {
        int p = (int) (usedNow * 100 / maxMemory(Runtime.getRuntime()));
        highAfterGc = p >= critAt.getInt() ? highAfterGc + 1 : 0;
        if (highAfterGc >= 2 && now - lastAlert > ALERT_COOLDOWN_MS) {
            lastAlert = now;
            // Title and text read as one sentence and each fits the toast's width.
            Notifications.push("Memory: " + p + "% still in use", "after GC, allocate more RAM", false);
        }
    }

    private void sampleAllocationRate(long now, long usedBytes, long collections) {
        if (rateStarted && now - rateSampleMs < 500) return;
        // A collection in between frees memory, so that window says nothing about allocation: keep the last rate.
        if (rateStarted && collections == rateGcCount && now > rateSampleMs)
            allocationRate = Math.max(0, Math.round((usedBytes - rateBytes) * (1000.0 / (now - rateSampleMs))));
        rateStarted = true;
        rateSampleMs = now;
        rateBytes = usedBytes;
        rateGcCount = collections;
    }

    private static long maxMemory(Runtime rt) {
        long m = rt.maxMemory();
        // No -Xmx limit reports Long.MAX_VALUE: then what Java has reserved is the best known ceiling.
        return m == Long.MAX_VALUE || m <= 0 ? Math.max(1, rt.totalMemory()) : m;
    }

    // ---------------------------------------------------------------- text

    @Override
    protected List<String> lines(boolean preview) {
        long now = Util.getMeasuringTimeMs();
        long period = Math.round(refresh.get() * 1000);
        int key = settingsKey();
        if (shown == null || now >= nextRefresh || nextRefresh - now > period || key != shownKey) {
            nextRefresh = now + period;
            shownKey = key;
            shown = buildLines();
        }
        return new ArrayList<>(shown);
    }

    /** Settings that change the text, so a change shows at once instead of at the next refresh. */
    private int settingsKey() {
        int k = format.get().hashCode();
        k = 31 * k + unit.get().hashCode();
        k = 31 * k + label.get().hashCode();
        k = 31 * k + showGc.get().hashCode();
        k = 31 * k + (showAllocated.get() ? 1 : 0);
        k = 31 * k + (showRate.get() ? 2 : 0);
        return k;
    }

    private List<String> buildLines() {
        Runtime rt = Runtime.getRuntime();
        total = rt.totalMemory();
        used = total - rt.freeMemory();
        max = maxMemory(rt);
        pct = (int) (used * 100 / max);
        boolean gb = unit.is("GB") || unit.is("Auto") && max >= AUTO_GB_FROM;
        String u = gb ? "GB" : "MB";
        String prefix = label.is("None") ? "" : label.get() + " ";
        String usage = switch (format.get()) {
            case "Percent" -> pct + "%";
            case "Used/Max" -> amount(used, gb) + "/" + amount(max, gb) + u;
            case "Used" -> amount(used, gb) + u;
            case "Free" -> amount(Math.max(0, max - used), gb) + u + " free";
            default -> pct + "% " + amount(used, gb) + "/" + amount(max, gb) + u;
        };
        List<String> out = new ArrayList<>(4);
        out.add(prefix + usage);
        if (showAllocated.get()) out.add("Allocated " + total * 100 / max + "% " + amount(total, gb) + u);
        if (showRate.get()) out.add("Allocating " + allocationRate / MB + "MB/s");
        if (!showGc.is("Off")) out.add(gcLine());
        return out;
    }

    /** "GC 4/min", "GC pause 12ms" or "GC 4/min, last 12ms" ("-" before the first collection is seen). */
    private String gcLine() {
        long perMin = 0;
        for (long[] t : gcTimes) perMin += t[1];
        String pause = lastPauseMs < 0 ? "-" : lastPauseMs + "ms";
        return switch (showGc.get()) {
            case "Count/min" -> "GC " + perMin + "/min";
            case "Last Pause" -> "GC pause " + pause;
            default -> "GC " + perMin + "/min, last " + pause;
        };
    }

    /** Whole megabytes like F3, or gigabytes with one decimal. */
    private static String amount(long bytes, boolean gb) {
        return gb ? String.format(Locale.ROOT, "%.1f", bytes / (double) GB) : Long.toString(bytes / MB);
    }

    // ---------------------------------------------------------------- colour and bar

    /** The usage line's colour by how full memory is; {@code base} below Warning At or with Color By Usage off. */
    private int usageColor(int base) {
        if (!colorByUsage.get()) return base;
        return pct >= critAt.getInt() ? critColor.get() : pct >= warnAt.getInt() ? warnColor.get() : base;
    }

    @Override
    protected int lineColor(int index, int base) {
        int c = index == 0 ? usageColor(base) : base;
        if (flashOnGc.get()) {
            long left = FLASH_MS - (Util.getMeasuringTimeMs() - flashStart);
            if (left > 0) c = Theme.lerpColor(c, flashColor(c), left / (float) FLASH_MS);
        }
        return c;
    }

    /** Flash to white, or to the accent colour for text that is already (nearly) white so the flash still shows. */
    private static int flashColor(int c) {
        return Theme.relLum(c) > 0.7f ? Theme.RED_BRIGHT : 0xFFFFFFFF;
    }

    @Override
    protected int extraHeight() {
        return usageBar.get() ? barHeight.getInt() + 2 : 0;
    }

    /** The usage bar: used memory in the usage colour over a fainter segment for what Java has reserved. */
    @Override
    protected void drawExtra(DrawContext ctx, int left, int y, int width) {
        if (!usageBar.get()) return;
        int x1 = left + 4, x2 = left + width - 4, span = x2 - x1;
        if (span <= 0) return;
        int top = y + 1, bottom = top + barHeight.getInt();
        int c = usageColor(color.get());
        ctx.fill(x1, top, x2, bottom, Theme.alpha(c, 0.15f));
        if (barAllocated.get()) ctx.fill(x1, top, x1 + part(total, span), bottom, Theme.alpha(c, 0.4f));
        ctx.fill(x1, top, x1 + part(used, span), bottom, c);
    }

    /** {@code bytes} of the maximum as a share of {@code span} pixels. */
    private int part(long bytes, int span) {
        return (int) Math.round(span * Math.max(0, Math.min(1, bytes / (double) max)));
    }
}
