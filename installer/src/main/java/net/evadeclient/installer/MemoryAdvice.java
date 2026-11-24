package net.evadeclient.installer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Memory advice for this PC. A laptop whose only graphics are built into the processor (Radeon 780M / 760M / 680M,
 * Vega, Intel UHD / Iris Xe / Arc integrated, Apple M-series) shares its RAM between the game and the graphics, so the
 * usual "more is better" is wrong there: 3–4 GB is the sweet spot, and giving Minecraft more than half the RAM starves
 * the graphics. The graphics cards are read from the operating system (no game needed); this is advice only, the
 * player picks. Also suggests the generational ZGC preset (Game page › Settings) on PCs with RAM to spare.
 */
final class MemoryAdvice {
    private MemoryAdvice() {}

    /** What the advice is based on and what it says. */
    record Advice(List<String> gpus, boolean shared, int totalGb, int recommendedGb) {
        /** True when {@code gb} is more than half this PC's RAM (where the graphics would be starved, or Windows). */
        boolean overHalf(int gb) { return totalGb > 0 && gb * 2 > totalGb; }

        /** One line for under a memory setting, for the amount chosen. */
        String line(int gb) {
            if (overHalf(gb)) return shared
                    ? gb + " GB is over half your " + totalGb + " GB, which the built-in graphics share: " + recommendedGb + " GB is best"
                    : gb + " GB is over half your " + totalGb + " GB of RAM: " + recommendedGb + " GB is recommended";
            if (shared) return "Built-in graphics share your RAM: " + recommendedGb + " GB is best here";
            return "Recommended here: " + recommendedGb + " GB" + (totalGb > 0 ? " of " + totalGb + " GB" : "");
        }

        /** The graphics in words ("Built-in: AMD Radeon 780M Graphics"), or null when unknown. */
        String gpuText() {
            if (gpus.isEmpty()) return null;
            return (shared ? "Built-in graphics: " : "Graphics: ") + String.join(", ", gpus);
        }

        /** Whether the ZGC preset is worth offering: a PC with RAM to spare (it uses a little more memory than G1). */
        boolean zgcSuits() { return totalGb >= 12; }
    }

    // Integrated graphics: AMD APUs ("Radeon(TM) Graphics", 610M-890M, Vega), Intel's built-in, Apple silicon, ARM.
    private static final Pattern INTEGRATED = Pattern.compile("(?i)(radeon\\s*(\\(tm\\)\\s*)?graphics|radeon\\s*\\(tm\\)\\s*\\d{3}m|radeon\\s*\\d{3}m|"
            + "\\bvega\\b|intel.*(uhd|hd graphics|iris|arc\\s*(\\(tm\\)\\s*)?graphics)|apple\\s*m\\d|adreno|mali)");
    // A separate graphics card: NVIDIA, Radeon RX / Pro, Intel Arc A- or B-series.
    private static final Pattern DISCRETE = Pattern.compile("(?i)(nvidia|geforce|rtx|gtx|quadro|radeon\\s*(rx|pro)\\b|arc\\s*(\\(tm\\)\\s*)?[ab]\\d{3})");

    static boolean integrated(String gpu) { return gpu != null && INTEGRATED.matcher(gpu).find() && !DISCRETE.matcher(gpu).find(); }

    static boolean discrete(String gpu) { return gpu != null && DISCRETE.matcher(gpu).find(); }

    /** The advice for these graphics and this much RAM (pure: tested with made-up PCs). */
    static Advice advise(List<String> gpus, int totalGb) {
        boolean anyIntegrated = false, anyDiscrete = false;
        for (String g : gpus) {
            if (discrete(g)) anyDiscrete = true;
            else if (integrated(g)) anyIntegrated = true;
        }
        boolean shared = anyIntegrated && !anyDiscrete;
        int rec;
        if (shared) rec = totalGb >= 16 ? 4 : totalGb >= 8 || totalGb == 0 ? 3 : 2;
        else rec = InstallerCore.recommendedMemory();
        if (totalGb > 0) rec = Math.max(1, Math.min(rec, Math.max(2, totalGb / 2)));
        return new Advice(List.copyOf(gpus), shared, totalGb, rec);
    }

    private static volatile List<String> cached;

    /** This PC's graphics (read once; empty when the system won't say). {@code -Devade.gpus=A;B} overrides it. */
    static List<String> detect() {
        String forced = System.getProperty("evade.gpus");
        if (forced != null) {
            List<String> out = new ArrayList<>();
            for (String s : forced.split(";")) if (!s.isBlank()) out.add(s.strip());
            return out;
        }
        List<String> c = cached;
        if (c != null) return c;
        List<String> out = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                for (String l : run("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name }")) if (!l.isBlank()) out.add(l.strip());
            } else if (os.contains("mac")) {
                for (String l : run("system_profiler", "SPDisplaysDataType")) {
                    String t = l.strip();
                    if (t.startsWith("Chipset Model:")) out.add(t.substring(14).strip());
                }
            } else {
                for (String l : run("lspci")) {
                    String t = l.toLowerCase(Locale.ROOT);
                    if (t.contains("vga compatible") || t.contains("3d controller") || t.contains("display controller")) {
                        int k = l.indexOf(": ");
                        out.add(k >= 0 ? l.substring(k + 2).strip() : l.strip());
                    }
                }
            }
        } catch (Exception ignored) { /* unknown graphics: general advice */ }
        cached = List.copyOf(out);
        return cached;
    }

    /** The advice for this PC (reads the graphics the first time; call off the UI thread). */
    static Advice forPc(List<String> gpus) { return advise(gpus, InstallerCore.totalMemoryGb()); }

    private static List<String> run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor(8, TimeUnit.SECONDS);
        return List.of(new String(out).split("\\R"));
    }

    // ------------------------------------------------------------------ the garbage collector preset

    /** Generational ZGC (Java 21): very short pauses, a little more memory than G1. */
    static final String ZGC_FLAGS = "-XX:+UseZGC -XX:+ZGenerational -XX:+DisableExplicitGC";

    /**
     * The profile's Java arguments with the collector switched: {@code zgc} replaces Evade's G1 settings with
     * {@link #ZGC_FLAGS}, and back. Only the flags Evade wrote are touched; the player's own arguments stay.
     */
    static String withCollector(String args, boolean zgc) {
        String a = args == null ? "" : args.strip();
        String g1 = InstallerCore.G1_FLAGS;
        if (zgc) {
            if (a.contains(ZGC_FLAGS)) return a;
            a = a.contains(g1) ? a.replace(g1, ZGC_FLAGS) : (a + " " + ZGC_FLAGS).strip();
        } else if (a.contains(ZGC_FLAGS)) {
            a = a.replace(ZGC_FLAGS, g1);
        }
        return a.replaceAll("\\s{2,}", " ").strip();
    }
}
