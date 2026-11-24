package net.evadeclient.installer;

import java.io.File;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Evade Launcher (still shipped as EvadeInstaller.jar / Evade-Installer.exe so existing downloads keep updating).
 * <pre>
 *   java -jar EvadeInstaller.jar                      open the launcher window (Evade Clips runs inside it)
 *   java -jar EvadeInstaller.jar --minimized          start in the tray only (how Evade Client starts it with Minecraft)
 *   java -jar EvadeInstaller.jar --clips              open the window on the Clips section
 *   java -jar EvadeInstaller.jar --cli [--dir PATH] [--memory GB] [--no-copy-settings] [--no-voice-chat] [--no-performance-mods] [--no-clips] [--shaders]
 *                     [--no-pack-features] [--dynamic-lights]
 *                     [--fast-start] [--more-fps] [--chunk-speed] [--open-to-friends] [--extras KEY,KEY|all]
 *                     [--template pvp|survival|building|laptop]
 *   java -jar EvadeInstaller.jar --play INSTANCE      update that instance if needed, open the Minecraft Launcher on it, close
 *   java -jar EvadeInstaller.jar --screenshots OUTDIR  render every page to PNG (no display needed)
 * </pre>
 */
public final class EvadeInstaller {
    private EvadeInstaller() {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (a.contains("--cli")) { cli(a); return; }
        if (a.contains("--screenshots")) { screenshots(Paths.get(arg(a, "--screenshots", "screenshots"))); return; }
        // Evade Clips' own test and tool modes still work from this file.
        if (!a.isEmpty() && List.of("--selftest", "--edittest", "--sizetest").contains(a.get(0))) { net.evadeclient.clips.ClipsApp.main(args); return; }
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        // Direct3D corrupts see-through windows (the clip toast) on some Windows graphics drivers; plain GDI is reliable.
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");
        // Self-update, single instance, the built-in Evade Clips and the window.
        AppHost.start(a);
    }

    // ------------------------------------------------------------------ system helpers

    /** This launcher's version (from the jar manifest; "dev" when run from classes). */
    static String launcherVersion() {
        String v = EvadeInstaller.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    /** Opens a folder in the file manager or a link in the browser. False if nothing could open it. */
    static boolean openPath(String target) {
        boolean url = target.startsWith("http://") || target.startsWith("https://");
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (url && d.isSupported(java.awt.Desktop.Action.BROWSE)) { d.browse(java.net.URI.create(target)); return true; }
                if (!url && d.isSupported(java.awt.Desktop.Action.OPEN)) { d.open(new File(target)); return true; }
            }
        } catch (Exception ignored) { /* try the system command below */ }
        List<String> cmd = OS.contains("win") ? List.of("explorer.exe", target) : OS.contains("mac") ? List.of("open", target) : List.of("xdg-open", target);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Best-effort: start the official Minecraft Launcher on each OS. True once one of the attempts started. */
    /**
     * An official Minecraft Launcher that is already open never reads the profile list again, so it would start
     * whatever profile it had selected (often an older Evade or plain Fabric) instead of this instance. Closes it
     * first when no game it started is running. Returns false when a game from it is running (then it's left alone).
     */
    static boolean closeIdleMinecraftLauncher() {
        if (!OS.contains("win")) return true;
        java.util.List<ProcessHandle> open = new java.util.ArrayList<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            String cmd = ph.info().command().orElse("").toLowerCase(java.util.Locale.ROOT);
            if (cmd.endsWith("\\minecraftlauncher.exe")) open.add(ph);
        });
        if (open.isEmpty()) return true;
        for (ProcessHandle ph : open) {
            boolean game = ph.descendants().anyMatch(d -> d.info().command().orElse("").toLowerCase(java.util.Locale.ROOT).matches(".*\\\\javaw?\\.exe"));
            if (game) return false;
        }
        for (ProcessHandle ph : open) ph.destroy();
        long until = System.currentTimeMillis() + 5000;
        for (ProcessHandle ph : open) {
            while (ph.isAlive() && System.currentTimeMillis() < until) {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return true; }
            }
        }
        return true;
    }

    static boolean openLauncher() {
        List<List<String>> attempts = new ArrayList<>();
        if (OS.contains("win")) {
            String local = System.getenv("LOCALAPPDATA"), pf86 = System.getenv("ProgramFiles(x86)");
            if (local != null) attempts.add(List.of(local + "\\Programs\\Minecraft Launcher\\MinecraftLauncher.exe"));
            if (pf86 != null) attempts.add(List.of(pf86 + "\\Minecraft Launcher\\MinecraftLauncher.exe"));
            attempts.add(List.of("explorer.exe", "shell:AppsFolder\\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"));
        } else if (OS.contains("mac")) {
            attempts.add(List.of("open", "-a", "Minecraft"));
        } else {
            attempts.add(List.of("minecraft-launcher"));
            attempts.add(List.of("flatpak", "run", "com.mojang.Minecraft"));
        }
        for (List<String> cmd : attempts) {
            try {
                // A full path that isn't there is skipped; a bare command name is looked up on the PATH.
                if (cmd.size() == 1 && new File(cmd.get(0)).isAbsolute() && !new File(cmd.get(0)).exists()) continue;
                // Output goes nowhere, so the launcher never blocks on (or dies writing to) a pipe nobody reads.
                Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                // "open -a" and "flatpak run" exit at once with an error when the launcher isn't installed.
                if ((cmd.get(0).equals("open") || cmd.get(0).equals("flatpak")) && pr.waitFor(2, TimeUnit.SECONDS) && pr.exitValue() != 0) continue;
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ------------------------------------------------------------------ cli

    private static void cli(List<String> a) throws Exception {
        Path mc = Paths.get(arg(a, "--dir", InstallerCore.defaultMinecraftDir().toString()));
        int total = InstallerCore.totalMemoryGb();
        int mem = InstallerCore.clampMemory(InstallerCore.recommendedMemory());
        String memArg = arg(a, "--memory", null);
        if (memArg != null) {
            try {
                mem = Integer.parseInt(memArg.trim().replaceAll("(?i)\\s*g(b)?$", ""));
            } catch (NumberFormatException e) {
                fail("--memory takes a whole number of GB, for example --memory 4");
            }
            if (mem < 1) fail("--memory must be at least 1 GB");
            if (total > 0 && mem > total) fail("--memory " + mem + " is more than this PC has (" + total + " GB of RAM)");
            if (total > 0 && mem > total - 2) {
                System.out.println("Warning: " + mem + " GB leaves less than 2 GB for everything else on this PC (" + total
                        + " GB of RAM). Minecraft may stutter or close. " + InstallerCore.recommendedMemory() + " GB is recommended.");
            }
        }
        boolean copy = !a.contains("--no-copy-settings");
        boolean voice = !a.contains("--no-voice-chat");
        boolean perf = !a.contains("--no-performance-mods");
        boolean clips = !a.contains("--no-clips"); // ffmpeg for the built-in Evade Clips (Windows)
        boolean shaders = a.contains("--shaders");
        boolean packFeatures = !a.contains("--no-pack-features"); // connected textures, CIT, ETF/EMF, custom skies
        boolean dynamicLights = a.contains("--dynamic-lights");
        // Optional extras (all off unless asked for): --fast-start, --more-fps, --chunk-speed, --open-to-friends.
        java.util.Set<String> optional = new java.util.LinkedHashSet<>(OptionalMods.fromArgs(a));
        String tpl = arg(a, "--template", null);
        if (tpl != null) {
            InstanceTemplates.Template t = InstanceTemplates.byKey(tpl);
            if (t == null) fail("--template takes one of: " + InstanceTemplates.keys());
            // A template sets the options; flags given as well still add to it.
            perf = perf && t.perf(); shaders = shaders || t.shaders(); voice = voice && t.voice(); packFeatures = packFeatures && t.packs();
            dynamicLights = dynamicLights || t.lights();
            optional.addAll(t.optional());
            if (memArg == null) mem = InstallerCore.clampMemory(InstanceTemplates.memoryFor(t, mem));
            System.out.println("Template: " + t.name());
        }
        InstallerCore core = new InstallerCore();
        System.out.println("Evade Installer");
        System.out.println("Minecraft folder: " + mc);
        InstallerCore.Release r;
        try {
            r = core.fetchRelease();
        } catch (java.io.IOException e) {
            fail(sentence(e.getMessage() != null ? e.getMessage() : rootMessage(e)) + " Check your connection and try again.");
            return;
        }
        System.out.printf("Installing Evade v%s, Fabric Loader %s, Fabric API %s%s%n", r.evadeVersion(), r.loaderVersion(), r.fabricApiVersion(),
                voice && r.voiceChat() != null ? ", Simple Voice Chat " + r.voiceChat().version() + " (from Modrinth)" : "");
        if (perf && !r.performance().isEmpty())
            System.out.println("Performance mods (from Modrinth): " + String.join(", ", r.performance().stream().map(m -> m.name() + " " + m.version()).toList()));
        if (packFeatures && !r.packFeatures().isEmpty())
            System.out.println("Resource pack features (from Modrinth): " + String.join(", ", r.packFeatures().stream().map(m -> m.name() + " " + m.version()).toList()));
        if (dynamicLights && r.dynamicLights() != null)
            System.out.println("Dynamic lights (from Modrinth): " + r.dynamicLights().name() + " " + r.dynamicLights().version());
        for (String key : optional) {
            OptionalMods.Group g = OptionalMods.byKey(key);
            java.util.List<InstallerCore.ModFile> files = r.optional().get(key);
            System.out.println(g.label() + " (from Modrinth): " + (files == null ? "not available right now"
                    : String.join(", ", files.stream().map(m -> m.name() + " " + m.version()).toList())));
        }
        System.out.println("Memory: " + mem + " GB");
        int[] last = {-1};
        List<String> skipped;
        try {
            skipped = core.install(new InstallerCore.Options(mc, InstallerCore.defaultGameDir(mc), mem, copy, voice, perf, clips, shaders,
                    InstallerCore.PROFILE_KEY, InstallerCore.PROFILE_NAME, null, packFeatures, dynamicLights, optional), r, (f, msg) -> {
                int pct = (int) Math.round(f * 100);
                if (msg != null || pct / 10 != last[0] / 10) {
                    System.out.printf("[%3d%%] %s%n", pct, msg != null ? msg : "");
                    last[0] = pct;
                }
            });
        } catch (Exception e) {
            fail(friendly(e));
            return;
        }
        if (!skipped.isEmpty()) {
            System.out.println("Installed, but some extras were skipped:");
            for (String s : skipped) System.out.println("  - " + s);
            System.out.println("Run the installer again later to add them.");
        }
        System.out.println("Done. Open the Minecraft Launcher and choose \"Evade Client\".");
    }

    private static void fail(String message) {
        System.err.println("Error: " + message);
        System.exit(2);
    }

    // ------------------------------------------------------------------ screenshots

    private static void screenshots(Path out) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Files.createDirectories(out);
        Screenshots.render(out);
        System.exit(0);
    }

    // ------------------------------------------------------------------ helpers

    private static String arg(List<String> a, String key, String def) {
        int i = a.indexOf(key);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : def;
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** "Couldn't reach X" -> "Couldn't reach X." (no doubled full stop). */
    static String sentence(String s) {
        s = s.trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s + ".";
    }

    /** The message to show for a failed install: which app to close when a file is in use, else what went wrong. */
    static String friendly(Throwable e) {
        // Unwrap the SwingWorker / reflection wrappers: the next exception down carries our message.
        while ((e instanceof ExecutionException || e instanceof java.lang.reflect.InvocationTargetException) && e.getCause() != null) e = e.getCause();

        for (Throwable t = e; t != null; t = t.getCause()) {
            // AccessDeniedException, or the plain FileSystemException Windows gives for "being used by another process".
            if (t instanceof AccessDeniedException || t.getClass() == FileSystemException.class) return inUse((FileSystemException) t);
        }
        String top = e.getMessage() != null ? e.getMessage() : rootMessage(e);
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.nio.channels.UnresolvedAddressException || t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return top.startsWith("Couldn't reach") ? sentence(top) + " Check your internet connection and try again."
                        : "Couldn't download the files. Check your internet connection and try again.";
            }
        }
        return top;
    }

    /** Names the app that's holding the file, from the path the exception reports. */
    private static String inUse(FileSystemException e) {
        String file = e.getFile() != null ? e.getFile() : e.getOtherFile();
        Path p = null;
        try { if (file != null) p = Paths.get(file).toAbsolutePath().normalize(); } catch (Exception ignored) {}
        String name = p != null && p.getFileName() != null ? p.getFileName().toString() : "";
        // Only Windows locks files that are in use; elsewhere "access denied" means the folder's permissions.
        if (e instanceof AccessDeniedException && !OS.contains("win")) {
            return "Couldn't write to " + (p != null ? p.toString() : "your Minecraft folder")
                    + ". Check that your account can write to that folder, then try again.";
        }
        if (p != null && (under(p, InstallerCore.clipsAppDir()) || under(p, InstallerCore.clipsDataDir()))) {
            return "A file Evade Clips uses is busy (" + name + "). Close the old Evade Clips app from its tray icon and try again.";
        }
        if (name.startsWith("launcher_profiles")) {
            return "The Minecraft Launcher is using " + name + ". Close the Minecraft Launcher and try again.";
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar") || (p != null && p.toString().contains(File.separator + "mods"))) {
            return "Minecraft is using " + name + ". Close Minecraft and the Minecraft Launcher, then try again.";
        }
        return "Couldn't write to your Minecraft folder" + (name.isEmpty() ? "" : " (" + name + ")")
                + ". Close Minecraft and the Minecraft Launcher, then try again.";
    }

    private static boolean under(Path p, Path dir) {
        try {
            return p.startsWith(dir.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }
}
