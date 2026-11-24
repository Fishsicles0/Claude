package net.evadeclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.TitleScreen;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Start-up time: from the Java process starting to the first title screen ("Boot"), how much of it Evade's own
 * set-up took ("Evade"), and the memory in use once the title screen is up. Shown in the Frame Profiler (SafeStart
 * logs the "Evade ready in X ms" line), and kept in {@code config/evade/boot-times.json} (the last ten starts) so a test can
 * tell a slower start from noise.
 */
public final class BootTimer {
    private BootTimer() {}

    private static long initStartNanos, initNanos;
    private static long readyMs = -1, heapMb = -1;

    /** First thing in Evade's client initialiser. */
    public static void initStart() { initStartNanos = System.nanoTime(); }

    /** Last thing in Evade's client initialiser: also waits for the first title screen. */
    public static void initEnd() {
        if (initStartNanos != 0) initNanos = System.nanoTime() - initStartNanos;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (readyMs >= 0) return;
            if (!(screen instanceof TitleScreen) && !screen.getClass().getSimpleName().contains("TitleScreen")) return;
            ready();
        });
    }

    private static void ready() {
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        readyMs = Math.max(0, System.currentTimeMillis() - start);
        Runtime rt = Runtime.getRuntime();
        heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        // SafeStart logs "Evade ready in X ms" itself; this adds Evade's own share for the Frame Profiler.
        EvadeClient.LOG.info("Boot timing: Evade's own set-up took {} ms of the {} ms to the title screen", evadeInitMs(), readyMs);
        Thread t = new Thread(BootTimer::save, "Evade boot times");
        t.setDaemon(true);
        t.start();
    }

    /** Process start to the first title screen, ms (-1 until it has shown). */
    public static long readyMs() { return readyMs; }

    /** Evade's own client initialiser, ms. */
    public static long evadeInitMs() { return initNanos / 1_000_000; }

    /** Memory in use at the first title screen, MB (-1 until then). */
    public static long heapMb() { return heapMb; }

    /** "Boot 14.2 s · Evade 0.31 s" for the Frame Profiler, or null before the title screen. */
    public static String profilerLine() {
        if (readyMs < 0) return null;
        return String.format(Locale.ROOT, "Boot %.1f s  ·  Evade %.2f s", readyMs / 1000.0, evadeInitMs() / 1000.0);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("boot-times.json");
    }

    /** Evade's set-up times of earlier starts (newest last), not counting this one. */
    public static synchronized List<Long> history() {
        List<Long> out = new ArrayList<>();
        try {
            Path f = file();
            if (!Files.exists(f)) return out;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (var e : root.getAsJsonArray("evadeMs")) out.add(e.getAsLong());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<Long> previous;

    /** The history as it was before this start was added (read once). */
    public static synchronized List<Long> previousStarts() {
        if (previous == null) previous = history();
        return previous;
    }

    private static synchronized void save() {
        previousStarts();
        List<Long> all = new ArrayList<>(previous);
        all.add(evadeInitMs());
        while (all.size() > 10) all.removeFirst();
        JsonObject root = new JsonObject();
        JsonArray a = new JsonArray();
        for (long v : all) a.add(v);
        root.add("evadeMs", a);
        root.addProperty("lastReadyMs", readyMs);
        root.addProperty("lastHeapMb", heapMb);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), root.toString());
        } catch (Exception e) {
            EvadeClient.LOG.warn("Boot timer: couldn't save {}", file(), e);
        }
    }
}
