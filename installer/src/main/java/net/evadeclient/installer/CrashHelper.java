package net.evadeclient.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After a game session ends badly: finds what it left behind (a {@code crash-reports/crash-*.txt}, a JVM
 * {@code hs_err_pid*.log}, or the end of {@code logs/latest.log}) and names the likely cause in plain words, with the
 * one-click fix that fits: switch the guilty mod off, give the game more (or less) memory, update the graphics
 * driver, use the Minecraft Launcher's own Java, or just open the report.
 */
final class CrashHelper {
    private CrashHelper() {}

    enum Fix { DISABLE_MOD, RAISE_MEMORY, LOWER_MEMORY, DRIVER, CLEAR_JAVA, FIND_MOD, OPEN_MODS, OPEN_LOG }

    /**
     * What went wrong. {@code mod}: the mod's id (or name) to switch off / look for, when the fix needs one;
     * {@code jar}: its file, when it was found in the mods folder.
     */
    record Diagnosis(String title, String cause, String detail, Fix fix, String mod, Path jar, Path report, long at) {}

    /** A report the game left behind after {@code since} (ms), newest first: crash report, then JVM error log. */
    static Path newestReport(Path instance, long since) {
        Path best = null;
        long bestT = since;
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instance.resolve("crash-reports"), "crash-*.txt")) {
            for (Path p : ds) candidates.add(p);
        } catch (IOException ignored) { }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instance, "hs_err_pid*.log")) {
            for (Path p : ds) candidates.add(p);
        } catch (IOException ignored) { }
        for (Path p : candidates) {
            try {
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > bestT) { bestT = t; best = p; }
            } catch (IOException ignored) { }
        }
        return best;
    }

    /**
     * Diagnoses a session that ended badly. {@code report} may be null (then the end of latest.log is read);
     * {@code memoryGb} is the instance's memory setting.
     */
    static Diagnosis diagnose(Path instance, Path report, int memoryGb, long at) {
        String text = "";
        Path src = report;
        try {
            if (report != null) text = read(report, 2_000_000);
            else if (Files.isRegularFile(instance.resolve("logs").resolve("latest.log"))) {
                src = instance.resolve("logs").resolve("latest.log");
                text = tail(src, 400_000);
            }
        } catch (IOException ignored) { }
        List<Mods.Entry> mods = List.of();
        try { mods = Mods.list(instance); } catch (RuntimeException ignored) { }
        Diagnosis d = analyse(text, report != null && report.getFileName().toString().startsWith("hs_err"), memoryGb, mods, src, at);
        return d;
    }

    /** The analysis itself (no files touched besides the mod list given), so it can be tested on sample text. */
    static Diagnosis analyse(String text, boolean jvmLog, int memoryGb, List<Mods.Entry> mods, Path report, long at) {
        String t = text == null ? "" : text;
        String lower = t.toLowerCase(Locale.ROOT);

        // ---- the wrong Java
        if (lower.contains("unsupportedclassversionerror") || lower.contains("compiled by a more recent version of the java runtime")
                || Pattern.compile("requires (?:java|any version of java) ?\\d|minecraft \\S+ requires java").matcher(lower).find()) {
            return new Diagnosis("The wrong Java started the game", "Minecraft " + InstallerCore.MC_VERSION + " needs Java 21. A Java override for this "
                    + "instance (or in Settings) points at an older one.", firstLine(t, "UnsupportedClassVersionError", "requires"),
                    Fix.CLEAR_JAVA, null, null, report, at);
        }
        // ---- out of memory: the heap was too small, or the PC itself ran out
        if (lower.contains("there is insufficient memory for the java runtime") || lower.contains("native memory allocation (malloc) failed")
                || lower.contains("native memory allocation (mmap) failed")) {
            return new Diagnosis("Your PC ran out of memory", "Windows couldn't give the game the memory it asked for. Close other programs, or "
                    + "give this instance less memory (" + memoryGb + " GB now).", firstLine(t, "insufficient memory", "Native memory"),
                    Fix.LOWER_MEMORY, null, null, report, at);
        }
        if (lower.contains("java.lang.outofmemoryerror") || lower.contains("out of memory")) {
            return new Diagnosis("Minecraft ran out of memory", "The game needed more than the " + memoryGb + " GB this instance gives it. "
                    + "Big modpacks and shaders need more.", firstLine(t, "OutOfMemoryError", "Out of memory"),
                    Fix.RAISE_MEMORY, null, null, report, at);
        }
        // ---- a mod is missing something it needs
        Matcher dep = Pattern.compile("(?i)Mod '([^']+)' \\(([a-z0-9_\\-]+)\\)[^\\n]*?requires (?:any version of |version [^ ]+ of |version [^ ]+ or later of )?'?([^',\\n]+?)'?(?: \\(([a-z0-9_\\-]+)\\))?,? which is missing").matcher(t);
        if (dep.find()) {
            String need = dep.group(4) != null ? dep.group(4) : dep.group(3).strip();
            Mods.Entry e = byId(mods, dep.group(2));
            return new Diagnosis(dep.group(1) + " needs " + dep.group(3).strip(), dep.group(1) + " won't start without " + dep.group(3).strip()
                    + ", which isn't installed. Install it from Modrinth, or switch " + dep.group(1) + " off.", dep.group().strip(),
                    Fix.FIND_MOD, need, e != null ? e.file() : null, report, at);
        }
        Matcher dep2 = Pattern.compile("(?i)Install ([^,\\n]+?), any version(?:,| or later)").matcher(t);
        if (lower.contains("incompatible mods found") && dep2.find()) {
            String need = dep2.group(1).strip();
            return new Diagnosis("A mod needs " + need, "A mod won't start without " + need + ", which isn't installed.", dep2.group().strip(),
                    Fix.FIND_MOD, need, null, report, at);
        }
        // ---- two mods that don't work together, or the same mod twice
        if (lower.contains("duplicate mod") || lower.contains("found multiple") || lower.contains("duplicate, conflicting mod")) {
            return new Diagnosis("A mod is installed twice", "Two copies of the same mod are in the mods folder. Remove the older one.",
                    firstLine(t, "uplicate", "multiple"), Fix.OPEN_MODS, null, null, report, at);
        }
        Matcher inc = Pattern.compile("(?i)Mod '([^']+)' \\(([a-z0-9_\\-]+)\\)[^\\n]*?is incompatible with[^\\n]*?'([^']+)'").matcher(t);
        if (inc.find()) {
            Mods.Entry e = byId(mods, inc.group(2));
            return new Diagnosis(inc.group(1) + " doesn't work with " + inc.group(3), "These two mods can't run together. Switch "
                    + inc.group(1) + " off (you can turn it back on later).", inc.group().strip(), Fix.DISABLE_MOD, inc.group(2),
                    e != null ? e.file() : null, report, at);
        }
        // ---- a mixin (a mod's change to the game) failed: name the mod
        if (lower.contains("mixin") && (lower.contains("mixinapplyerror") || lower.contains("mixintransformererror")
                || lower.contains("mixin apply") || lower.contains("injectionerror") || lower.contains("invalidmixinexception"))) {
            String id = null;
            Matcher m = Pattern.compile("(?i)from mod ([a-z0-9_\\-]+)").matcher(t);
            if (m.find()) id = m.group(1);
            if (id == null) {
                Matcher c = Pattern.compile("(?i)([a-z0-9_\\-]+)\\.mixins?\\.json").matcher(t);
                while (c.find()) {
                    String cand = c.group(1);
                    if (byId(mods, cand) != null || byPrefix(mods, cand) != null) { id = cand; break; }
                    if (id == null) id = cand;
                }
            }
            Mods.Entry e = id == null ? null : byId(mods, id) != null ? byId(mods, id) : byPrefix(mods, id);
            String name = e != null ? e.name() : id != null ? id : "A mod";
            if (e == null || !"Evade".equals(e.option())) {
                return new Diagnosis(name + " failed to load", name + " couldn't change the game the way it needs to. It's probably made for "
                        + "another Minecraft version. Switch it off and play without it.", firstLine(t, "Mixin", "mixin"),
                        Fix.DISABLE_MOD, e != null ? e.id() : id, e != null ? e.file() : null, report, at);
            }
        }
        // ---- graphics driver
        if (jvmLog || lower.contains("exception_access_violation")) {
            Matcher f = Pattern.compile("(?im)^#\\s*C\\s+\\[([^+\\]]+)").matcher(t);
            String frame = f.find() ? f.group(1).toLowerCase(Locale.ROOT) : "";
            if (frame.matches(".*(atio6axx|atig6pxx|amdxx|ig\\d+icd|igxelpicd|igc\\d*|nvoglv|nvd3d|nvwgf|libnvidia|radeonsi|iris_dri|libgl|opengl32|vulkan).*")) {
                return new Diagnosis("The graphics driver crashed", "The game stopped inside the graphics driver (" + frame.strip() + "). "
                        + "Updating the graphics driver usually fixes it. If you use shaders, try without them.", "Problematic frame: " + frame,
                        Fix.DRIVER, null, null, report, at);
            }
        }
        if (lower.contains("glfw error 65542") || lower.contains("glfw error 65543") || lower.contains("the driver does not appear to support opengl")
                || lower.contains("pixel format not accelerated")) {
            return new Diagnosis("The graphics driver can't run Minecraft", "The game couldn't open its window with OpenGL. Update the graphics "
                    + "driver from your GPU maker's website.", firstLine(t, "GLFW", "OpenGL"), Fix.DRIVER, null, null, report, at);
        }
        // ---- a mod in the stack trace
        Matcher desc = Pattern.compile("(?m)^Description: (.+)$").matcher(t);
        String what = desc.find() ? desc.group(1).strip() : null;
        Matcher ex = Pattern.compile("(?m)^([a-zA-Z0-9_.$]+(?:Exception|Error)(?::[^\\n]*)?)$").matcher(t);
        String first = ex.find() ? ex.group(1) : null;
        Mods.Entry suspect = suspect(t, mods);
        if (suspect != null && !"Evade".equals(suspect.option())) {
            return new Diagnosis(suspect.name() + " probably crashed the game", "The error came from " + suspect.name() + ". Switch it off and "
                    + "try again; if the game starts, look for an update to it.", first != null ? first : what, Fix.DISABLE_MOD, suspect.id(),
                    suspect.file(), report, at);
        }
        if (report == null && t.isEmpty()) {
            return new Diagnosis("The game closed unexpectedly", "It ended without a crash report. If it was closed from Task Manager, "
                    + "that's all it was; if not, the log may say more.", null, Fix.OPEN_LOG, null, null, null, at);
        }
        return new Diagnosis("The game crashed", what != null ? "Minecraft said: " + what + "." : "The report doesn't point at a mod. "
                + "Share the log with Evade support and we'll take a look.", first, Fix.OPEN_LOG, null, null, report, at);
    }

    /** A mod named in a "Suspected mods" line, or the mod whose package appears first in the stack trace. */
    private static Mods.Entry suspect(String t, List<Mods.Entry> mods) {
        Matcher s = Pattern.compile("(?i)Suspected Mods?:\\s*([^\\n]+)").matcher(t);
        if (s.find()) {
            Matcher id = Pattern.compile("\\(([a-z0-9_\\-]+)\\)").matcher(s.group(1));
            while (id.find()) {
                Mods.Entry e = byId(mods, id.group(1));
                if (e != null) return e;
            }
        }
        Matcher at = Pattern.compile("(?m)^\\s+at ([a-z0-9_]+(?:\\.[a-z0-9_]+){1,3})\\.").matcher(t);
        int n = 0;
        while (at.find() && n++ < 40) {
            String pkg = at.group(1);
            if (pkg.startsWith("java.") || pkg.startsWith("jdk.") || pkg.startsWith("sun.") || pkg.startsWith("net.minecraft")
                    || pkg.startsWith("com.mojang") || pkg.startsWith("net.fabricmc") || pkg.startsWith("org.spongepowered")
                    || pkg.startsWith("org.lwjgl") || pkg.startsWith("com.google") || pkg.startsWith("io.netty")
                    || pkg.startsWith("it.unimi") || pkg.startsWith("org.apache") || pkg.startsWith("org.slf4j")) continue;
            String[] parts = pkg.split("\\.");
            for (String p : parts) {
                if (p.length() < 4 || p.equals("client") || p.equals("mixin") || p.equals("common") || p.equals("fabric")) continue;
                Mods.Entry e = byId(mods, p);
                if (e == null) e = byPrefix(mods, p);
                if (e != null) return e;
            }
        }
        return null;
    }

    private static Mods.Entry byId(List<Mods.Entry> mods, String id) {
        if (id == null) return null;
        for (Mods.Entry e : mods) if (id.equalsIgnoreCase(e.id())) return e;
        return null;
    }

    private static Mods.Entry byPrefix(List<Mods.Entry> mods, String id) {
        if (id == null || id.length() < 4) return null;
        String l = id.toLowerCase(Locale.ROOT);
        for (Mods.Entry e : mods) if (e.id() != null && (e.id().startsWith(l) || l.startsWith(e.id()) && e.id().length() >= 4)) return e;
        return null;
    }

    private static String firstLine(String t, String... keys) {
        for (String line : t.split("\\r?\\n")) for (String k : keys) if (line.contains(k)) return line.strip();
        return null;
    }

    private static String read(Path f, int max) throws IOException {
        byte[] b = Files.readAllBytes(f);
        return new String(b, 0, Math.min(b.length, max), StandardCharsets.UTF_8);
    }

    private static String tail(Path f, int max) throws IOException {
        byte[] b = Files.readAllBytes(f);
        int from = Math.max(0, b.length - max);
        return new String(b, from, b.length - from, StandardCharsets.UTF_8);
    }
}
