package net.evadeclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe Start: a broken setting, a module bug or a clash with another mod can never lock the player out of the game.
 * <ul>
 *   <li>Every start writes {@code .evade/boot-marker.json}; reaching the title screen removes it. A marker that is
 *   still there at the next start means that start never got to the title screen. After {@value #FAILS_TO_TRIGGER}
 *   such starts in a row, the game starts with every Evade module off.</li>
 *   <li>The Evade Launcher's "Start in safe mode" writes {@code .evade/safe-start.json}
 *   ({@code {"requested": epochMillis, "by": "launcher"}}): acted on once, deleted after, ignored (and deleted) when
 *   older than ten minutes. Same result.</li>
 *   <li>The modules that were on are kept in {@code .evade/safe-mode.json} with the suspect read from the last crash
 *   report or log (an Evade mixin or module named in the stack trace). {@link net.evadeclient.gui.SafeStartScreen}
 *   shows them on the title screen to turn back on one at a time, or all at once.</li>
 * </ul>
 * It also measures the start: "Evade ready in X ms" (from JVM start to the first title screen) and the heap in use
 * at that point, for the log and the in-game test's budget. Everything stays on this computer.
 */
public final class SafeStart {
    private SafeStart() {}

    public static final String MARKER = "boot-marker.json", REQUEST = "safe-start.json", STATE = "safe-mode.json";
    /** A launcher request older than this is stale (matches the launcher's SafeStart.TTL_MS). */
    public static final long REQUEST_TTL_MS = 10 * 60_000L;
    /** Starts in a row that never reached the title screen before Safe Start steps in. */
    public static final int FAILS_TO_TRIGGER = 2;

    /** What {@link #begin} decided: whether this start is a safe start, why ("launcher" / "crash"), and the failed starts before it. */
    public record Decision(boolean safe, String reason, int failedStarts) {}

    /** The last crash's suspect: the Evade mixin and modules named in its stack trace (any may be empty), and where it was read. */
    public record Suspect(String mixin, List<String> modules, String source) {
        public static final Suspect NONE = new Suspect(null, List.of(), null);
        public boolean any() { return mixin != null || !modules.isEmpty(); }
    }

    private static Path dir;
    private static Decision decision = new Decision(false, null, 0);
    private static boolean reachedTitle, screenShown;
    private static long readyMs = -1, heapMb = -1;

    // ------------------------------------------------------------------ start-up (client initializer)

    /** Called once from the client initializer, after the config is loaded and before anything runs. */
    public static void init() {
        try {
            dir = FabricLoader.getInstance().getGameDir().resolve(LauncherBridge.DIR);
            Files.createDirectories(dir);
            // In the in-game test a killed run must not turn the next one into a safe start (tests use temp folders).
            decision = begin(dir, System.currentTimeMillis(), !testing());
            if (decision.safe()) enterSafeMode(decision);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Safe Start off: {}", e.toString());
        }
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (isTitle(screen)) onTitle();
        });
        ClientTickEvents.END_CLIENT_TICK.register(SafeStart::tick);
    }

    /**
     * Whether Evade's in-game tests are running: Loom's clientGameTest run sets {@code -Dfabric.client.gametest} in a
     * development environment. A mod can't switch Safe Start or the first-run setup off by picking a mod id, and a
     * normal install ignores the property.
     */
    public static boolean testing() {
        return FabricLoader.getInstance().isDevelopmentEnvironment() && System.getProperty("fabric.client.gametest") != null;
    }

    static boolean isTitle(Screen s) {
        return s instanceof TitleScreen || s instanceof net.evadeclient.gui.EvadeTitleScreen;
    }

    /**
     * The file half of Safe Start, kept apart from the game so the test can run it on a temporary folder: counts this
     * start (marker), takes the launcher's request (deleting it), and decides.
     *
     * @param countCrashes false to ignore the failed-start count (only the launcher's request counts)
     */
    public static Decision begin(Path dir, long now, boolean countCrashes) {
        int failed = 0;
        Path marker = dir.resolve(MARKER);
        if (Files.isRegularFile(marker)) {
            JsonObject m = read(marker);
            failed = (int) Math.min(99, num(m, "failed", 0) + 1);
        }
        JsonObject mark = new JsonObject();
        mark.addProperty("started", now);
        mark.addProperty("failed", failed);
        mark.addProperty("version", EvadeClient.VERSION);
        write(marker, mark);

        boolean launcher = takeRequest(dir, now);
        if (launcher) return new Decision(true, "launcher", failed);
        if (countCrashes && failed >= FAILS_TO_TRIGGER) return new Decision(true, "crash", failed);
        return new Decision(false, null, failed);
    }

    /** Reads and deletes the launcher's request; true when it was fresh (under ten minutes old, not from the future). */
    public static boolean takeRequest(Path dir, long now) {
        Path f = dir.resolve(REQUEST);
        if (!Files.isRegularFile(f)) return false;
        JsonObject o = read(f);
        try { Files.deleteIfExists(f); } catch (IOException ignored) { }
        long at = num(o, "requested", -1);
        if (at <= 0) return false;
        long age = now - at;
        return age < REQUEST_TTL_MS && age > -60_000; // a minute of clock skew either way
    }

    /** The title screen was reached: this start counts as good. */
    public static void markReached(Path dir) {
        try { Files.deleteIfExists(dir.resolve(MARKER)); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ safe mode

    /** Switches every Evade module off and records which were on (added to any list a safe start before this one kept). */
    private static void enterSafeMode(Decision d) {
        List<String> on = new ArrayList<>();
        for (Module m : EvadeClient.modules.all()) {
            if (!m.isEnabled() || m instanceof ClientSettings) continue;
            on.add(m.getName());
            try { m.setEnabled(false); } catch (RuntimeException e) { EvadeClient.LOG.warn("Safe Start couldn't switch {} off", m.getName(), e); }
        }
        Suspect s = Suspect.NONE;
        try { s = findSuspect(dir.getParent(), EvadeClient.modules.all()); } catch (RuntimeException ignored) { }
        writeState(dir, d, on, s, System.currentTimeMillis());
        EvadeClient.LOG.warn("Safe Start ({}): {} Evade modules switched off{}", d.reason(), on.size(),
                s.any() ? ", suspect " + (s.mixin() != null ? s.mixin() : String.join(", ", s.modules())) : "");
        // The off state is saved, so a crash before the title screen keeps them off; the list above brings them back.
        try { EvadeClient.config.save(); } catch (RuntimeException ignored) { }
    }

    /** Writes the state file, merging the modules with the ones a still-open earlier safe start kept. */
    public static void writeState(Path dir, Decision d, List<String> on, Suspect s, long now) {
        Set<String> all = new LinkedHashSet<>(on);
        JsonObject old = read(dir.resolve(STATE));
        if (old.has("modules") && old.get("modules").isJsonArray()) for (JsonElement e : old.getAsJsonArray("modules")) {
            if (e.isJsonPrimitive()) all.add(e.getAsString());
        }
        JsonObject o = new JsonObject();
        o.addProperty("since", now);
        o.addProperty("reason", d.reason());
        o.addProperty("failedStarts", d.failedStarts());
        o.add("modules", array(all));
        if (s.mixin() != null) o.addProperty("suspectMixin", s.mixin());
        o.add("suspectModules", array(s.modules()));
        if (s.source() != null) o.addProperty("source", s.source());
        write(dir.resolve(STATE), o);
    }

    /** What the state file holds, or null when there's none (no safe start waiting for the player). */
    public static JsonObject state() {
        if (dir == null) return null;
        Path f = dir.resolve(STATE);
        return Files.isRegularFile(f) ? read(f) : null;
    }

    /** The modules a safe start switched off that are still off. */
    public static List<String> waitingModules() {
        JsonObject o = state();
        List<String> out = new ArrayList<>();
        if (o == null || !o.has("modules") || !o.get("modules").isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray("modules")) {
            if (!e.isJsonPrimitive()) continue;
            Module m = EvadeClient.modules.get(e.getAsString());
            if (m != null && !m.isEnabled()) out.add(m.getName());
        }
        return out;
    }

    /** Turns one module back on (from the Safe Start screen); clears the state once all are back on. */
    public static void restore(String name) {
        Module m = EvadeClient.modules.get(name);
        if (m != null) m.setEnabled(true);
        if (waitingModules().isEmpty()) finish();
        EvadeClient.config.save();
    }

    /** "Turn all back on". */
    public static void restoreAll() {
        for (String n : waitingModules()) {
            Module m = EvadeClient.modules.get(n);
            if (m != null) m.setEnabled(true);
        }
        finish();
        EvadeClient.config.save();
    }

    /** The player is done with the list (keeps whatever is still off, off). */
    public static void finish() {
        if (dir == null) return;
        try { Files.deleteIfExists(dir.resolve(STATE)); } catch (IOException ignored) { }
    }

    /** This start's decision (for the screen and tests). */
    public static Decision decision() { return decision; }

    // ------------------------------------------------------------------ suspect

    private static final Pattern MIXIN = Pattern.compile("net\\.evadeclient\\.mixin\\.([A-Za-z0-9_]+)|evade\\.mixins\\.json:([A-Za-z0-9_]+)");
    private static final Pattern MODULE = Pattern.compile("net\\.evadeclient\\.module\\.[a-z]+\\.([A-Z][A-Za-z0-9_]*)");

    /**
     * Reads the newest crash report (or, without one, the previous session's log) under {@code gameDir} and names the
     * Evade mixin and modules in it. Module class names become module names using {@code modules}.
     */
    public static Suspect findSuspect(Path gameDir, List<Module> modules) {
        Path src = newest(gameDir.resolve("crash-reports"), "crash-*.txt");
        if (src == null) src = newest(gameDir.resolve("logs"), "*.log.gz");
        if (src == null) return Suspect.NONE;
        String text;
        try {
            text = readText(src, 2_000_000);
        } catch (IOException e) {
            return Suspect.NONE;
        }
        return suspectIn(text, modules, src.getFileName().toString());
    }

    /** The suspect in a crash report or log's text. */
    public static Suspect suspectIn(String text, List<Module> modules, String source) {
        String mixin = null;
        Matcher mx = MIXIN.matcher(text);
        if (mx.find()) mixin = mx.group(1) != null ? mx.group(1) : mx.group(2);
        Set<String> names = new LinkedHashSet<>();
        Matcher md = MODULE.matcher(text);
        while (md.find() && names.size() < 5) {
            String cls = md.group(1);
            for (Module m : modules) if (m.getClass().getSimpleName().equals(cls)) names.add(m.getName());
        }
        return new Suspect(mixin, List.copyOf(names), source);
    }

    private static Path newest(Path folder, String glob) {
        Path best = null;
        long bestT = Long.MIN_VALUE;
        if (!Files.isDirectory(folder)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, glob)) {
            for (Path p : ds) {
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > bestT) { bestT = t; best = p; }
            }
        } catch (IOException ignored) { }
        return best;
    }

    private static String readText(Path f, int max) throws IOException {
        try (InputStream raw = Files.newInputStream(f);
             InputStream in = f.toString().endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw) {
            return new String(in.readNBytes(max), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ title screen

    private static void onTitle() {
        if (reachedTitle) return;
        reachedTitle = true;
        if (dir != null) markReached(dir);
        // How long the start took and how much heap it left in use (in the test, after a collection so it's steady).
        long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        readyMs = System.currentTimeMillis() - start;
        if (testing()) System.gc();
        Runtime rt = Runtime.getRuntime();
        heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        EvadeClient.LOG.info("Evade ready in {} ms (heap {} MB in use of {} MB)", readyMs, heapMb, rt.maxMemory() >> 20);
    }

    /** On the first title screen, the Safe Start screen opens while modules from a safe start are still waiting. */
    private static void tick(MinecraftClient mc) {
        // Started straight into a world (quick play) without a title screen: that start worked too.
        if (!reachedTitle && mc.world != null && mc.getOverlay() == null) onTitle();
        if (screenShown || !reachedTitle || !isTitle(mc.currentScreen)) return;
        screenShown = true;
        if (state() != null && !waitingModules().isEmpty()) mc.setScreen(new net.evadeclient.gui.SafeStartScreen(mc.currentScreen));
        else finish();
    }

    /** Milliseconds from JVM start to the first title screen, or -1 before it. */
    public static long readyMillis() { return readyMs; }

    /** Heap in use (MB) when the first title screen opened, or -1 before it. */
    public static long heapAtTitleMb() { return heapMb; }

    // ------------------------------------------------------------------ files

    private static JsonObject read(Path f) {
        try {
            if (!Files.isRegularFile(f) || Files.size(f) > 64_000) return new JsonObject();
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void write(Path f, JsonObject o) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) { }
        }
    }

    private static long num(JsonObject o, String k, long def) {
        try { return o.has(k) ? o.get(k).getAsLong() : def; } catch (RuntimeException e) { return def; }
    }

    private static JsonArray array(Iterable<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }
}
