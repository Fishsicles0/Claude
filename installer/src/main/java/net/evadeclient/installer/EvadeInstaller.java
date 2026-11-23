package net.evadeclient.installer;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Color;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.geom.RoundRectangle2D;
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
 * Evade Installer.
 * <pre>
 *   java -jar EvadeInstaller.jar                      open the installer window
 *   java -jar EvadeInstaller.jar --cli [--dir PATH] [--memory GB] [--no-copy-settings] [--no-voice-chat] [--no-performance-mods] [--no-clips] [--shaders]
 *   java -jar EvadeInstaller.jar --screenshots OUTDIR  render every screen to PNG (no display needed)
 * </pre>
 */
public final class EvadeInstaller {
    private EvadeInstaller() {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (a.contains("--cli")) { cli(a); return; }
        if (a.contains("--screenshots")) { screenshots(Paths.get(arg(a, "--screenshots", "screenshots"))); return; }
        // A newer installer on evadeclient.com takes over (and replaces this file), so one download lasts.
        if (SelfUpdate.run(a)) { System.exit(0); return; }
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        SwingUtilities.invokeLater(EvadeInstaller::openWindow);
    }

    // ------------------------------------------------------------------ window

    private static void openWindow() {
        InstallerCore core = new InstallerCore();
        InstallerView view = new InstallerView();
        JFrame frame = new JFrame("Evade Installer");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(view);
        // Rounded corners: with per-pixel alpha the frame is see-through and the view paints the rounded window
        // itself, anti-aliased. Without it, the window is cut to the rounded shape (hard-edged corners). Set
        // before pack(), while the frame has no native window yet.
        boolean perPixel = perPixelTranslucency();
        if (perPixel) {
            try {
                frame.setBackground(new Color(0, 0, 0, 0));
            } catch (RuntimeException e) {
                perPixel = false;
                frame.setBackground(InstallerView.BG);
            }
        }
        view.setOpaque(!perPixel);
        view.roundCorners = perPixel;
        frame.pack();
        frame.setResizable(false);
        if (!perPixel) {
            try { frame.setShape(new RoundRectangle2D.Double(0, 0, InstallerView.W, InstallerView.H, 22, 22)); } catch (Exception ignored) {}
        }
        List<java.awt.Image> icons = new ArrayList<>();
        icons.add(InstallerView.image("/evade/icon-128.png"));
        frame.setIconImages(icons);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Runnable loadRelease = () -> new SwingWorker<InstallerCore.Release, Void>() {
            @Override protected InstallerCore.Release doInBackground() throws Exception { return core.fetchRelease(); }
            @Override protected void done() {
                try {
                    view.release = get();
                    view.releaseError = null;
                } catch (Exception e) {
                    // fetchRelease names the source it couldn't reach; that's the message to show, not its root cause.
                    Throwable c = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    view.releaseError = c.getMessage() != null ? c.getMessage() : rootMessage(c);
                }
                view.repaint();
            }
        }.execute();
        loadRelease.run();
        view.onRetryRelease = () -> {
            view.releaseError = null;
            view.repaint();
            loadRelease.run();
        };

        view.onInstall = () -> {
            view.state = InstallerView.State.INSTALLING;
            view.progress = 0;
            view.shownProgress = 0;
            view.status = "";
            view.skipped = List.of();
            view.launcherError = null;
            Path mc = view.minecraftDir;
            InstallerCore.Options opts = new InstallerCore.Options(mc, InstallerCore.defaultGameDir(mc), view.memoryGb, view.copySettings, view.voiceChat, view.performanceMods, view.installClips, view.shaders);
            InstallerCore.Release release = view.release;
            new SwingWorker<List<String>, Object[]>() {
                @Override protected List<String> doInBackground() throws Exception {
                    return core.install(opts, release, (f, msg) -> publish(new Object[]{f, msg}));
                }
                @Override protected void process(List<Object[]> chunks) {
                    for (Object[] c : chunks) {
                        view.progress = Math.max(view.progress, (double) c[0]);
                        if (c[1] != null) view.status = (String) c[1];
                    }
                }
                @Override protected void done() {
                    try {
                        view.skipped = List.copyOf(get());
                        view.progress = 1;
                        view.refreshInstalled();
                        view.state = InstallerView.State.DONE;
                    } catch (Exception e) {
                        view.error = friendly(e);
                        view.state = InstallerView.State.ERROR;
                    }
                    view.repaint();
                }
            }.execute();
        };
        view.onOpenLauncher = () -> {
            if (view.openingLauncher) return;
            view.openingLauncher = true;
            view.launcherError = null;
            // Off the EDT: some attempts wait a moment to see whether the launcher actually started.
            new Thread(() -> {
                boolean ok = openLauncher();
                SwingUtilities.invokeLater(() -> {
                    view.openingLauncher = false;
                    if (ok) System.exit(0);
                    view.launcherError = "Couldn't open the Minecraft Launcher. " + (OS.contains("win") ? "Open it from your Start menu."
                            : OS.contains("mac") ? "Open it from your Applications folder." : "Open it from your app menu.");
                    view.repaint();
                });
            }, "open-launcher").start();
        };
    }

    /** Whether the screen can show windows with see-through pixels (smooth, anti-aliased window corners). */
    private static boolean perPixelTranslucency() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Best-effort: start the official Minecraft Launcher on each OS. True once one of the attempts started. */
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
        boolean clips = !a.contains("--no-clips");
        boolean shaders = a.contains("--shaders");
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
        System.out.println("Memory: " + mem + " GB");
        int[] last = {-1};
        List<String> skipped;
        try {
            skipped = core.install(new InstallerCore.Options(mc, InstallerCore.defaultGameDir(mc), mem, copy, voice, perf, clips, shaders), r, (f, msg) -> {
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
        if (clips && r.clipsUrl() != null && skipped.stream().noneMatch(s -> s.startsWith("Evade Clips")))
            System.out.println("Evade Clips " + r.clipsVersion() + " installed: " + InstallerCore.clipsAppDir());
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
        InstallerView v = new InstallerView();
        v.minecraftDir = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft");
        InstallerCore.ModFile mod = new InstallerCore.ModFile("Sodium", "0.6.13", "", "", "sodium-fabric-0.6.13+mc1.21.11.jar",
                "AANobbMI", List.of(), java.util.Map.of());
        InstallerCore.Release full = new InstallerCore.Release("1.8.0", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", mod,
                List.of(mod), "1.0.0", "downloads/Evade-Clips.exe", "", null, null, mod, mod, mod);
        v.release = full;
        v.installed = null;
        save(v, out, "1-ready");
        v.installed = new InstallerCore.Installed("0.3.0", "0.20.1", "0.141.6+1.21.11");
        save(v, out, "2-update");
        v.state = InstallerView.State.INSTALLING;
        v.progress = 0.62;
        v.status = "Downloading Evade Client v0.3.1";
        save(v, out, "3-installing");
        v.state = InstallerView.State.DONE;
        v.progress = 1;
        save(v, out, "4-done");
        v.state = InstallerView.State.ERROR;
        v.error = "Couldn't reach evadeclient.com. Check your internet connection and try again.";
        save(v, out, "5-error");

        v.state = InstallerView.State.DONE;
        v.skipped = List.of("Lithium couldn't be downloaded", "Voice chat isn't available right now");
        save(v, out, "6-done-skipped");
        v.skipped = List.of(InstallerCore.CLIPS_BUSY, "Shaders skipped: Sodium could not be found", "The shader pack couldn't be downloaded",
                "Krypton couldn't be updated (kept the installed version)");
        v.launcherError = "Couldn't open the Minecraft Launcher. Open it from your Start menu.";
        save(v, out, "7-done-busy");
        v.skipped = List.of();
        v.launcherError = null;

        v.state = InstallerView.State.IDLE;
        v.release = null;
        v.releaseError = "Couldn't reach Modrinth (Fabric API)";
        save(v, out, "8-offline");
        v.releaseError = null;
        v.release = new InstallerCore.Release("1.8.0", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", null,
                List.of(), "1.0.0", "downloads/Evade-Clips.exe", "", null, null, null, null, null);
        save(v, out, "9-unavailable");
        v.release = full;
        v.state = InstallerView.State.INSTALLING;
        v.progress = 0.8;
        v.status = "Downloading Lithium 0.15.0";
        save(v, out, "10-installing-extras");
        System.exit(0);
    }

    private static void save(InstallerView v, Path dir, String name) throws Exception {
        // Paint twice so hover/toggle animations settle at their resting values.
        for (int i = 0; i < 30; i++) v.snapshot(1f);
        ImageIO.write(v.snapshot(2f), "png", dir.resolve(name + ".png").toFile());
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
            return "Evade Clips is open, so it couldn't be updated. Close it from the tray (right-click its icon, then Quit) and try again.";
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
