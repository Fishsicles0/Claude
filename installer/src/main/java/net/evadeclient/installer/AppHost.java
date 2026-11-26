package net.evadeclient.installer;

import net.evadeclient.clips.ClipsApp;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Settings;
import net.evadeclient.clips.ui.App;
import net.evadeclient.clips.ui.Theme;
import net.evadeclient.clips.ui.Toast;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts the one program: the launcher window and the built-in Evade Clips (capture, library, local control server
 * for Evade Client, tray icon). One copy runs at a time; a second start brings the running one's window forward
 * through Clips' control server ({@code /status}, {@code /open}). {@code --minimized} (how Evade Client starts it
 * with Minecraft, and the login shortcut) starts only the tray; {@code --clips} opens the window on Clips.
 * <p>
 * Older installs had Evade Clips as a separate app. Its settings and library live in the same Clips data folder,
 * so they carry over as they are; this class also hands its shortcuts over, closes it when it would block the
 * built-in one, and can remove it (keeping its ffmpeg).
 */
final class AppHost {
    private AppHost() {}

    static void start(List<String> args) throws Exception {
        boolean minimized = args.contains("--minimized"), clipsFirst = args.contains("--clips");
        // A desktop shortcut's --play <instance>: left as a request that this launcher (or one already open) picks up.
        String playId = InstanceShortcut.playArg(args);
        if (playId != null) InstanceShortcut.request(playId);
        Settings cs = Settings.load();
        // Already running (this program, or an old one without the lock): bring it forward and stop here.
        // Another Evade Launcher (this program) already runs: show it and stop here. Only the old separate app is
        // ever closed; a running launcher is never mistaken for it.
        if (otherLauncherRunning(cs.controlPort) && ClipsApp.alreadyRunning(cs.controlPort, minimized)) { System.exit(0); return; }
        Path legacy = legacyRunningApp();
        if (legacy == null && ClipsApp.alreadyRunning(cs.controlPort, minimized)) { System.exit(0); return; }

        // A just-updated copy puts itself in place of the old file; a normal start clears earlier update temp folders.
        // Updating itself is never silent: LauncherApp asks the player once the window is open (SelfUpdate.check).
        SelfUpdate.finishUpdate(args);

        boolean updated = args.contains(SelfUpdate.UPDATED);
        boolean locked = lock(updated ? 40 : 1);
        if (!locked) {
            legacy = legacyRunningApp();
            if (legacy == null) {
                // Another copy of this program has the lock: ask it to show its window.
                if (!ClipsApp.alreadyRunning(cs.controlPort, minimized) && !minimized) ClipsApp.requestShow();
                System.exit(0);
                return;
            }
        }
        String note = null;
        hostArgs = args;
        if (legacy != null) {
            // The old separate Evade Clips is running: it would record twice and hold the clips folder. Close it.
            closeLegacy(legacy);
            locked = locked || lock(40);
            if (!locked || ClipsApp.alreadyRunning(cs.controlPort, true))
                note = "The old Evade Clips app is still running.";
        }

        App app = note == null ? startClips(cs) : null;
        App clips = app;
        String clipsNote = note;
        LauncherApp[] launcher = new LauncherApp[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            if (clips != null) clips.toast = new Toast();
            launcher[0] = new LauncherApp(clips, clipsNote, clipsNote != null);
            launcher[0].closeAfterQuickPlay = playId != null;
            launcher[0].installTray();
            if (clips != null) clips.showWindow = launcher[0]::showWindow;
            if (minimized && clips != null) launcher[0].startBackground();
            else if (clipsFirst) launcher[0].showClips();
            else launcher[0].showWindow();
        });
        if (clips != null) {
            ClipsApp.watchShowRequests(clips);
            new Thread(clips::start, "clips-startup").start();
        }
        Thread migrate = new Thread(() -> {
            Path self = homePath(args);
            handOverShortcuts(self);
            // Old versions go, so only this one runs: update copies left in the temp folder, and the old separate
            // Evade Clips app (a copy of it starting at login would record instead of the built-in Clips).
            deleteOldUpdateCopies();
            if (legacyAppInstalled() && launcher[0] != null) SwingUtilities.invokeLater(launcher[0]::cleanUpOldVersions);
        }, "clips-shortcuts");
        migrate.setDaemon(true);
        migrate.start();
    }

    private static List<String> hostArgs = List.of();

    /** Where this program lives (see {@link #homePath}), for the self-update that replaces it. */
    static Path homePath() { return homePath(hostArgs); }

    /** Starts the built-in Clips (call with the single-instance lock held). */
    private static App startClips(Settings cs) {
        ClipsApp.hosted = true;
        Path self = homePath(hostArgs);
        if (self != null && self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) ClipsApp.hostExe = self;
        else if (self != null) ClipsApp.hostJar = self;
        // ffmpeg from the old separate app's folder still counts.
        Ffmpeg.addSearchDir(InstallerCore.clipsAppDir().resolve("ffmpeg").resolve("bin"));
        Ffmpeg.addSearchDir(InstallerCore.clipsAppDir().resolve("ffmpeg"));
        return ClipsApp.startCore(cs);
    }

    /**
     * The "Close it now" button: closes the old separate app, takes the lock and starts the built-in Clips.
     * Null when the old app wouldn't close. Runs off the UI thread (closing can take several seconds).
     */
    static synchronized App takeOver() {
        Settings cs = Settings.load();
        // Ask whatever answers on the Clips port to quit cleanly first (the old app, or an older launcher copy that
        // hosts Clips): it saves its buffer and lets go of the lock. Then close the old app by its file, if any.
        askToQuit(cs.controlPort);
        if (!lock(32)) {
            Path legacy = legacyRunningApp();
            if (legacy != null) closeLegacy(legacy);
            // Still held: the old app may run from a file location.json doesn't name (an old download, a copy Evade
            // Client started). Close whatever really holds Clips: the program on its port, any old Clips program.
            if (!lock(8)) forceCloseOldClips(cs.controlPort);
            if (!lock(40)) return null;
        }
        if (ClipsApp.alreadyRunning(cs.controlPort, true)) {
            askToQuit(cs.controlPort);
            for (int i = 0; i < 20 && ClipsApp.alreadyRunning(cs.controlPort, true); i++) {
                try { Thread.sleep(250); } catch (InterruptedException e) { return null; }
            }
            if (ClipsApp.alreadyRunning(cs.controlPort, true)) return null;
        }
        return startClips(cs);
    }

    /** What answers on the Clips port: its /status body, or null when nothing does. */
    private static String clipsStatus(int port) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(700)).build();
            var r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/status"))
                    .timeout(java.time.Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("Evade Clips") ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the Clips on the port is hosted by another Evade Launcher (v2.3+), not the old separate app. */
    static boolean otherLauncherRunning(int port) {
        String st = clipsStatus(port);
        return st != null && st.replace(" ", "").contains("\"host\":\"launcher\"");
    }

    /** Asks the Clips on the port to save and quit (it refuses while a manual recording runs). */
    private static void askToQuit(int port) {
        if (clipsStatus(port) == null || otherLauncherRunning(port)) return;
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(700)).build();
            http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/quit"))
                    .timeout(java.time.Duration.ofSeconds(3)).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) { }
    }

    private static boolean haveLock;

    /** True when nobody holds the Clips lock (taking it for a moment, then letting go). */
    private static boolean lockFree() {
        if (haveLock) return true;
        if (!ClipsApp.acquireInstanceLock()) return false;
        ClipsApp.releaseInstanceLock();
        return true;
    }

    private static boolean lock(int tries) {
        if (haveLock) return true;
        for (int i = 0; i < tries; i++) {
            if (ClipsApp.acquireInstanceLock()) return haveLock = true;
            try { Thread.sleep(250); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    /**
     * Where this program lives: the file a just-updated copy replaces (it runs from a temporary folder until the
     * next start), else this .exe or .jar. Null when run from classes.
     */
    static Path homePath(List<String> args) {
        int i = args.indexOf(SelfUpdate.REPLACE);
        if (args.contains(SelfUpdate.UPDATED) && i >= 0 && i + 1 < args.size()) {
            try { return Paths.get(args.get(i + 1)).toAbsolutePath(); } catch (RuntimeException ignored) { }
        }
        return SelfUpdate.selfPath();
    }

    // ------------------------------------------------------------------ the old separate Evade Clips app

    /** True for the file names the separate app had (Evade-Clips.exe, EvadeClips.jar and their update copies). */
    static boolean legacyName(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.startsWith("evade-clips") || n.startsWith("evadeclips");
    }

    /** The separate app, when location.json says it's what runs (it writes the file when it starts). */
    static Path legacyRunningApp() {
        // location.json stays behind after the old app quits: it only counts while something really holds Clips
        // (the lock or the port) and that something isn't an Evade Launcher.
        int port = Settings.load().controlPort;
        if (otherLauncherRunning(port)) return null;
        if (clipsStatus(port) == null && lockFree()) return null;
        var loc = InstallerCore.readJsonObject(InstallerCore.clipsDataDir().resolve("location.json"));
        for (String k : new String[]{"exe", "jar"}) {
            String v = InstallerCore.str(loc, k);
            if (v == null) continue;
            try {
                Path p = Paths.get(v);
                if (legacyName(p) && Files.isRegularFile(p)) return p;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    /** Whether the separate app's files are still installed. */
    static boolean legacyAppInstalled() {
        Path dir = InstallerCore.clipsAppDir();
        if (!Files.isDirectory(dir)) return false;
        try (var l = Files.list(dir)) {
            return l.anyMatch(AppHost::legacyName);
        } catch (IOException e) {
            return false;
        }
    }

    /** Closes the separate app (politely first) so the built-in Clips can take over. */
    static void closeLegacy(Path app) {
        try {
            if (InstallerCore.WINDOWS) {
                InstallerCore.closeRunningClips(app);
                if (!lockFree()) forceCloseOldClips(Settings.load().controlPort);
                return;
            }
            String needle = app.toString();
            List<ProcessHandle> found = new ArrayList<>();
            ProcessHandle.allProcesses().forEach(ph -> {
                if (ph.pid() == ProcessHandle.current().pid()) return;
                String cmd = ph.info().commandLine().orElse("");
                if (cmd.contains(needle)) found.add(ph);
            });
            for (ProcessHandle ph : found) ph.destroy(); // its shutdown hook saves a recording that's running
            long until = System.currentTimeMillis() + 8000;
            for (ProcessHandle ph : found) {
                while (ph.isAlive() && System.currentTimeMillis() < until) Thread.sleep(200);
                if (ph.isAlive()) ph.destroyForcibly();
            }
        } catch (Exception ignored) { /* the launcher says it's still running */ }
    }

    /**
     * Windows: ends the old separate Evade Clips wherever it runs from. Two ways to find it, neither needing its file:
     * the program listening on Clips' control port (unless that's an Evade Launcher, which is never closed), and any
     * process whose command line names an old Clips program (Evade-Clips.exe, EvadeClips.jar, the "Evade Clips"
     * install folder). This program is never touched. It was already asked to quit politely (/quit) first.
     */
    static void forceCloseOldClips(int port) {
        if (!InstallerCore.WINDOWS) return;
        long self = ProcessHandle.current().pid();
        try {
            if (!otherLauncherRunning(port)) {
                Process ns = new ProcessBuilder("netstat", "-ano", "-p", "TCP").redirectErrorStream(true).start();
                String out = new String(ns.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                ns.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                long pid = listeningPid(out, port);
                if (pid > 0 && pid != self) {
                    ProcessHandle.of(pid).ifPresent(ph -> System.err.println("Closing the old Clips app on port " + port
                            + ": " + ph.info().command().orElse("pid " + pid)));
                    run("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
                }
            }
            String ps = "Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne " + self + " -and $_.CommandLine -and "
                    + "($_.CommandLine -match '(?i)evade[- ]?clips[^\\\\]*[.](exe|jar)' -or $_.CommandLine -match '(?i)[\\\\]Evade Clips[\\\\]') } "
                    + "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
            run("powershell", "-NoProfile", "-NonInteractive", "-Command", ps);
        } catch (Exception e) {
            System.err.println("Couldn't close the old Clips app: " + e.getMessage());
        }
    }

    /** The process id listening on 127.0.0.1 (or any address) at {@code port} in `netstat -ano` output, or -1. */
    static long listeningPid(String netstat, int port) {
        for (String line : netstat.split("\\R")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 5 || !f[0].equalsIgnoreCase("TCP") || !f[3].equalsIgnoreCase("LISTENING")) continue;
            if (!f[1].endsWith(":" + port)) continue;
            try { return Long.parseLong(f[4]); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private static void run(String... cmd) throws Exception {
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        pr.getInputStream().readAllBytes();
        pr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Removes the separate app. Its ffmpeg moves into the Clips data folder first (the built-in Clips uses it);
     * the clips, settings and library stay where they are. Returns what happened, for a toast.
     */
    static String removeLegacyApp() {
        Path dir = InstallerCore.clipsAppDir();
        Path legacy = legacyRunningApp();
        if (legacy != null) closeLegacy(legacy);
        Path ff = dir.resolve("ffmpeg"), keep = InstallerCore.clipsDataDir().resolve("ffmpeg");
        try {
            if (Files.isDirectory(ff) && !Files.exists(keep) && !dir.equals(InstallerCore.clipsDataDir())) {
                Files.createDirectories(keep.getParent());
                try { Files.move(ff, keep, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException e) { copyTree(ff, keep); }
                Ffmpeg.addSearchDir(keep.resolve("bin"));
            }
        } catch (IOException e) {
            System.err.println("Keeping ffmpeg: " + e.getMessage());
        }
        int left = 0;
        try (var l = Files.list(dir)) {
            for (Path p : l.toList()) {
                if (!legacyName(p)) continue;
                try { Files.deleteIfExists(p); } catch (IOException e) { left++; }
            }
        } catch (IOException ignored) { }
        removeOldShortcuts();
        try (var l = Files.list(dir)) {
            if (l.findAny().isEmpty()) Files.deleteIfExists(dir);
        } catch (IOException ignored) { }
        return left == 0 ? "Removed the old Evade Clips app. Your clips and settings are kept." : "Most of the old app was removed; a file in use goes next time.";
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var w = Files.walk(from)) {
            for (Path p : w.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Deletes the launcher copies earlier self-updates downloaded into the temp folder ({@code evade-installer-update*}),
     * except the one running now (a just-updated copy runs from there until it's next started).
     */
    static void deleteOldUpdateCopies() {
        try {
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir", "")).toAbsolutePath();
            Path running = SelfUpdate.selfPath();
            try (var l = Files.list(tmp)) {
                for (Path d : l.toList()) {
                    if (!d.getFileName().toString().startsWith("evade-installer-update") || !Files.isDirectory(d)) continue;
                    if (running != null && running.startsWith(d)) continue;
                    try (var w = Files.walk(d)) {
                        for (Path q : w.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            try { Files.deleteIfExists(q); } catch (IOException inUse) { /* next time */ }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Old launcher copies kept: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ shortcuts

    private static Path startMenu() {
        String appData = System.getenv("APPDATA");
        return appData == null ? null : Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs");
    }

    /**
     * The separate app's "Evade Clips" shortcuts (Start Menu, and start at login in the tray) now open this program:
     * the Start Menu one on the Clips section, the login one in the tray. Only when this program runs from a lasting
     * place (not a temporary folder) and only for shortcuts that point at the separate app.
     */
    static void handOverShortcuts(Path self) {
        if (self == null) return;
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (!tmp.isBlank() && self.startsWith(Paths.get(tmp).toAbsolutePath())) return;
        try {
            if (InstallerCore.WINDOWS) {
                if (!self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) return;
                Path programs = startMenu();
                if (programs == null) return;
                Path menu = programs.resolve("Evade Clips.lnk"), login = programs.resolve("Startup").resolve("Evade Clips.lnk");
                if (!Files.exists(menu) && !Files.exists(login)) return;
                StringBuilder ps = new StringBuilder("$w=New-Object -ComObject WScript.Shell;");
                for (Path lnk : new Path[]{menu, login}) {
                    if (!Files.exists(lnk)) continue;
                    String at = lnk.toString().replace("'", "''");
                    // only shortcuts that still point at the separate app (or at nothing)
                    ps.append("$s=$w.CreateShortcut('").append(at).append("');")
                      .append("if(($s.TargetPath -eq '') -or ((Split-Path $s.TargetPath -Leaf) -like 'Evade-Clips*')){")
                      .append("$s.TargetPath='").append(self.toString().replace("'", "''")).append("';")
                      .append("$s.Arguments='").append(lnk == login ? "--minimized" : "--clips").append("';")
                      .append("$s.WorkingDirectory='").append(self.getParent().toString().replace("'", "''")).append("';$s.Save()};");
                }
                Process pr = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps.toString()).redirectErrorStream(true).start();
                pr.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                pr.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            } else if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                if (!self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return;
                String home = System.getProperty("user.home");
                String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
                String cmd = "\"" + java + "\" -jar \"" + self + "\"";
                Path[] files = {Paths.get(home, ".local", "share", "applications", "evade-clips.desktop"), Paths.get(home, ".config", "autostart", "evade-clips.desktop")};
                for (int i = 0; i < files.length; i++) {
                    Path f = files[i];
                    if (!Files.exists(f)) continue;
                    String text = Files.readString(f);
                    if (!text.contains("EvadeClips") && !text.contains("Evade-Clips")) continue;
                    String entry = "[Desktop Entry]\nType=Application\nName=Evade Clips\nComment=Evade Launcher with Evade Clips\nTerminal=false\nCategories=AudioVideo;Game;\n";
                    Files.writeString(f, entry + "Exec=" + cmd + (i == 0 ? " --clips" : " --minimized") + "\n");
                }
            }
        } catch (Exception e) {
            System.err.println("Shortcut hand-over skipped: " + e.getMessage());
        }
    }

    /** After removing the separate app: its Start Menu entries that still point at it go too. */
    private static void removeOldShortcuts() {
        try {
            if (InstallerCore.WINDOWS) {
                Path programs = startMenu();
                if (programs == null) return;
                StringBuilder ps = new StringBuilder("$w=New-Object -ComObject WScript.Shell;");
                for (Path lnk : new Path[]{programs.resolve("Evade Clips.lnk"), programs.resolve("Startup").resolve("Evade Clips.lnk")}) {
                    if (!Files.exists(lnk)) continue;
                    String at = lnk.toString().replace("'", "''");
                    ps.append("$s=$w.CreateShortcut('").append(at).append("');if((Split-Path $s.TargetPath -Leaf) -like 'Evade-Clips*'){Remove-Item -LiteralPath '")
                      .append(at).append("'};");
                }
                Process pr = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps.toString()).redirectErrorStream(true).start();
                pr.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                pr.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception ignored) { }
    }
}
