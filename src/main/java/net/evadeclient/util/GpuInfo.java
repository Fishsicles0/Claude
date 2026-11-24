package net.evadeclient.util;

import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What graphics the game is drawing with, read from the OpenGL renderer string (the GPU that actually runs the game,
 * which on a laptop with two GPUs is not always the one the launcher sees). Used to start integrated graphics on a
 * laptop profile, and named in benchmark results.
 *
 * <p>Examples of renderer strings: "AMD Radeon 780M Graphics" or "AMD Radeon(TM) Graphics" (Windows),
 * "AMD Radeon 780M (radeonsi, gfx1103_r1, LLVM 18.1.8, DRM 3.57)" (Linux), "Mesa Intel(R) Graphics (ADL GT2)",
 * "Intel(R) Iris(R) Xe Graphics", "NVIDIA GeForce RTX 4060 Laptop GPU/PCIe/SSE2", "llvmpipe (LLVM 20.1.2, 256 bits)".
 */
public final class GpuInfo {
    private GpuInfo() {}

    /** The kind of graphics. */
    public enum Kind {
        /** Built into the processor and sharing its memory (AMD APUs, Intel's built-in graphics, Apple silicon). */
        INTEGRATED,
        /** A separate graphics card. */
        DISCRETE,
        /** No GPU at all: the CPU draws everything (a remote desktop, a virtual machine, a missing driver). */
        SOFTWARE,
        UNKNOWN
    }

    // AMD APUs ("Radeon(TM) Graphics", "Radeon 780M", 610M-890M, Vega), Intel's built-in graphics (UHD, HD, Iris,
    // "Intel(R) Graphics", Arc integrated), Apple silicon, phone-class GPUs.
    private static final Pattern INTEGRATED = Pattern.compile("(?i)(radeon\\s*(\\(tm\\)\\s*)?graphics|radeon\\s*(\\(tm\\)\\s*)?\\d{3}m\\b|"
            + "\\bvega\\b|radeon\\s*(\\(tm\\)\\s*)?(r[2-7]|vega)\\b|intel.*(uhd|hd graphics|iris|\\(r\\)\\s*graphics|arc\\s*(\\(tm\\)\\s*)?graphics)|"
            + "apple\\s*m\\d|adreno|mali)");
    // A separate card: NVIDIA, Radeon RX / Pro / W-series, Intel Arc A- or B-series.
    private static final Pattern DISCRETE = Pattern.compile("(?i)(nvidia|geforce|\\brtx\\b|\\bgtx\\b|quadro|radeon\\s*(\\(tm\\)\\s*)?(rx|pro)\\b|"
            + "arc\\s*(\\(tm\\)\\s*)?[ab]\\d{3})");
    private static final Pattern APU_VEGA = Pattern.compile("(?i)vega\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern SOFTWARE = Pattern.compile("(?i)(llvmpipe|softpipe|swiftshader|microsoft basic render|gdi generic|virgl|svga3d)");

    /** The kind of graphics a renderer string names. */
    public static Kind classify(String renderer) {
        if (renderer == null || renderer.isBlank()) return Kind.UNKNOWN;
        if (SOFTWARE.matcher(renderer).find()) return Kind.SOFTWARE;
        // "Radeon RX Vega 8 Graphics" is a Ryzen APU (Vega 3 to 11); the Vega 56 and 64 cards are discrete.
        var vega = APU_VEGA.matcher(renderer);
        if (vega.find() && Integer.parseInt(vega.group(1)) <= 11) return Kind.INTEGRATED;
        if (DISCRETE.matcher(renderer).find()) return Kind.DISCRETE;
        if (INTEGRATED.matcher(renderer).find()) return Kind.INTEGRATED;
        return Kind.UNKNOWN;
    }

    /** Test hook: pretend the game runs on this renderer (null = read the real one). */
    public static String forcedRenderer;

    /** The OpenGL renderer string, or "" before the window exists. Render thread. */
    public static String renderer() {
        if (forcedRenderer != null) return forcedRenderer;
        try {
            String r = RenderSystem.getDevice().getRenderer();
            return r == null ? "" : r.strip();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The OpenGL vendor string, or "". Render thread. */
    public static String vendor() {
        try {
            String v = RenderSystem.getDevice().getVendor();
            return v == null ? "" : v.strip();
        } catch (Throwable t) {
            return "";
        }
    }

    public static Kind kind() { return classify(renderer()); }

    /** The renderer in words for a result card: driver details in brackets and "/PCIe/SSE2" left out. */
    public static String shortName() {
        return shortName(renderer());
    }

    public static String shortName(String renderer) {
        if (renderer == null) return "";
        String s = renderer.replaceAll("/PCIe/SSE2", "").replaceAll("\\s*\\(TM\\)|\\s*\\(R\\)|\\(tm\\)|\\(r\\)", "");
        // "AMD Radeon 780M (radeonsi, gfx1103_r1, LLVM ...)": keep the name, drop the driver details,
        // but keep a bracket that is the whole description ("Mesa Intel(R) Graphics (ADL GT2)").
        int b = s.indexOf(" (");
        if (b > 0 && (s.toLowerCase(Locale.ROOT).contains("radeonsi") || s.toLowerCase(Locale.ROOT).contains("llvm"))) s = s.substring(0, b);
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String cpu;

    /** The processor's name ("AMD Ryzen 7 250 w/ Radeon 780M Graphics"), read once through OSHI; "" if unknown. */
    public static synchronized String cpuName() {
        if (cpu == null) {
            try {
                cpu = new oshi.SystemInfo().getHardware().getProcessor().getProcessorIdentifier().getName().replaceAll("\\s+", " ").strip();
            } catch (Throwable t) {
                cpu = "";
            }
        }
        return cpu;
    }

    private static int physicalCores;

    /** Physical processor cores (OSHI), or half the logical processors when it can't say. At least 1. */
    public static synchronized int physicalCores() {
        if (physicalCores == 0) {
            int n = 0;
            try {
                n = new oshi.SystemInfo().getHardware().getProcessor().getPhysicalProcessorCount();
            } catch (Throwable ignored) {
            }
            if (n <= 0) n = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            physicalCores = n;
        }
        return physicalCores;
    }
}
