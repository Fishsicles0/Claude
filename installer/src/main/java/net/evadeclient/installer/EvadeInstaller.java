package net.evadeclient.installer;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evade Installer.
 * <pre>
 *   java -jar EvadeInstaller.jar                      open the installer window
 *   java -jar EvadeInstaller.jar --cli [--dir PATH] [--memory GB] [--no-copy-settings] [--no-voice-chat] [--no-performance-mods]
 *   java -jar EvadeInstaller.jar --screenshots OUTDIR  render every screen to PNG (no display needed)
 * </pre>
 */
public final class EvadeInstaller {
    private EvadeInstaller() {}

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (a.contains("--cli")) { cli(a); return; }
        if (a.contains("--screenshots")) { screenshots(Paths.get(arg(a, "--screenshots", "screenshots"))); return; }
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
        frame.pack();
        frame.setResizable(false);
        try { frame.setShape(new RoundRectangle2D.Double(0, 0, InstallerView.W, InstallerView.H, 22, 22)); } catch (Exception ignored) {}
        List<java.awt.Image> icons = new ArrayList<>();
        icons.add(InstallerView.image("/evade/icon-128.png"));
        frame.setIconImages(icons);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        Runnable loadRelease = () -> new SwingWorker<InstallerCore.Release, Void>() {
            @Override protected InstallerCore.Release doInBackground() throws Exception { return core.fetchRelease(); }
            @Override protected void done() {
                try { view.release = get(); view.releaseError = null; }
                catch (Exception e) { view.releaseError = rootMessage(e); }
            }
        }.execute();
        loadRelease.run();

        view.onInstall = () -> {
            view.state = InstallerView.State.INSTALLING;
            view.progress = 0;
            view.shownProgress = 0;
            Path mc = view.minecraftDir;
            InstallerCore.Options opts = new InstallerCore.Options(mc, InstallerCore.defaultGameDir(mc), view.memoryGb, view.copySettings, view.voiceChat, view.performanceMods);
            new SwingWorker<Void, Object[]>() {
                @Override protected Void doInBackground() throws Exception {
                    core.install(opts, view.release, (f, msg) -> publish(new Object[]{f, msg}));
                    return null;
                }
                @Override protected void process(List<Object[]> chunks) {
                    for (Object[] c : chunks) {
                        view.progress = Math.max(view.progress, (double) c[0]);
                        if (c[1] != null) view.status = (String) c[1];
                    }
                }
                @Override protected void done() {
                    try {
                        get();
                        view.progress = 1;
                        view.refreshInstalled();
                        view.state = InstallerView.State.DONE;
                    } catch (Exception e) {
                        view.error = friendly(e);
                        view.state = InstallerView.State.ERROR;
                    }
                }
            }.execute();
        };
        view.onOpenLauncher = () -> {
            if (!openLauncher()) {
                view.status = "Couldn't open the launcher automatically. Open it from your Start menu or Applications.";
            }
            System.exit(0);
        };
    }

    /** Best-effort: start the official Minecraft Launcher on each OS. */
    static boolean openLauncher() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<List<String>> attempts = new ArrayList<>();
        if (os.contains("win")) {
            String pf86 = System.getenv("ProgramFiles(x86)");
            if (pf86 != null) attempts.add(List.of(pf86 + "\\Minecraft Launcher\\MinecraftLauncher.exe"));
            attempts.add(List.of("explorer.exe", "shell:AppsFolder\\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"));
        } else if (os.contains("mac")) {
            attempts.add(List.of("open", "-a", "Minecraft"));
        } else {
            attempts.add(List.of("minecraft-launcher"));
            attempts.add(List.of("flatpak", "run", "com.mojang.Minecraft"));
        }
        for (List<String> cmd : attempts) {
            try {
                if (cmd.size() == 1 && !new File(cmd.get(0)).exists()) continue;
                new ProcessBuilder(cmd).start();
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ------------------------------------------------------------------ cli

    private static void cli(List<String> a) throws Exception {
        Path mc = Paths.get(arg(a, "--dir", InstallerCore.defaultMinecraftDir().toString()));
        int mem = Integer.parseInt(arg(a, "--memory", "4"));
        boolean copy = !a.contains("--no-copy-settings");
        boolean voice = !a.contains("--no-voice-chat");
        boolean perf = !a.contains("--no-performance-mods");
        InstallerCore core = new InstallerCore();
        System.out.println("Evade Installer");
        System.out.println("Minecraft folder: " + mc);
        InstallerCore.Release r = core.fetchRelease();
        System.out.printf("Installing Evade v%s, Fabric Loader %s, Fabric API %s%s%n", r.evadeVersion(), r.loaderVersion(), r.fabricApiVersion(),
                voice && r.voiceChat() != null ? ", Simple Voice Chat " + r.voiceChat().version() + " (from Modrinth)" : "");
        if (perf && !r.performance().isEmpty())
            System.out.println("Performance mods (from Modrinth): " + String.join(", ", r.performance().stream().map(m -> m.name() + " " + m.version()).toList()));
        int[] last = {-1};
        core.install(new InstallerCore.Options(mc, InstallerCore.defaultGameDir(mc), mem, copy, voice, perf), r, (f, msg) -> {
            int pct = (int) Math.round(f * 100);
            if (msg != null || pct / 10 != last[0] / 10) {
                System.out.printf("[%3d%%] %s%n", pct, msg != null ? msg : "");
                last[0] = pct;
            }
        });
        System.out.println("Done. Open the Minecraft Launcher and choose \"Evade Client\".");
    }

    // ------------------------------------------------------------------ screenshots

    private static void screenshots(Path out) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Files.createDirectories(out);
        InstallerView v = new InstallerView();
        v.minecraftDir = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft");
        v.release = new InstallerCore.Release("1.8.0", "", "", "0.18.6", "0.141.6+1.21.11", "", "", "", null, java.util.List.of());
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

    private static String friendly(Throwable e) {
        String m = rootMessage(e);
        if (e instanceof java.net.ConnectException || m.contains("ConnectException") || m.contains("UnresolvedAddress")
                || m.contains("timed out")) {
            return "Couldn't download the files. Check your internet connection and try again.";
        }
        if (m.contains("AccessDenied") || m.contains("Permission denied")) {
            return "Couldn't write to your Minecraft folder. Close the Minecraft Launcher and try again.";
        }
        return m;
    }
}
