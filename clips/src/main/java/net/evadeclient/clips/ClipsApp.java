package net.evadeclient.clips;

import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Settings;
import net.evadeclient.clips.ui.App;
import net.evadeclient.clips.ui.MainWindow;
import net.evadeclient.clips.ui.Screenshots;
import net.evadeclient.clips.ui.Theme;
import net.evadeclient.clips.ui.Toast;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Evade Clips: records gameplay (always-on instant replay + full recordings), keeps a searchable clip library
 * and edits/exports clips. Flags: {@code --selftest DIR}, {@code --screenshots DIR}, {@code --minimized}.
 */
public final class ClipsApp {
    /** Held for as long as this copy runs, so a second copy knows it isn't the first. */
    private static FileChannel instanceChannel;
    private static FileLock instanceLock;
    /**
     * Set when Evade Clips runs inside the Evade Launcher: location.json then names the launcher's own file (the .exe,
     * or the jar; set explicitly so a just-updated copy running from a temporary folder records its real place), and
     * a pending update left by the old separate app is dropped.
     */
    public static volatile boolean hosted;
    public static volatile Path hostExe, hostJar;

    /** Written by a second copy that couldn't reach this one over the control port: "show your window". */
    static final String SHOW_REQUEST = "show.request";

    /** True if another Evade Clips answers on the control port; asks it to show its window unless minimized. */
    public static boolean alreadyRunning(int port, boolean minimized) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(700)).build();
            var status = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/status"))
                    .timeout(java.time.Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (status.statusCode() != 200 || !status.body().contains("Evade Clips")) return false;
            if (!minimized) http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/open"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Takes the single-instance lock ({@code appdir/instance.lock}). False if another copy holds it. A lock that
     * can't be taken for another reason (a read-only folder) doesn't stop the app.
     */
    /** Lets go of the single-instance lock (used to test whether it's free). */
    public static void releaseInstanceLock() {
        try { if (instanceLock != null) instanceLock.release(); } catch (IOException ignored) { }
        try { if (instanceChannel != null) instanceChannel.close(); } catch (IOException ignored) { }
        instanceLock = null;
        instanceChannel = null;
    }

    public static boolean acquireInstanceLock() {
        try {
            Path f = Settings.appDir().resolve("instance.lock");
            Files.createDirectories(f.getParent());
            instanceChannel = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = instanceChannel.tryLock();
            return instanceLock != null;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            System.err.println("[clips] couldn't take the single-instance lock: " + e.getMessage());
            return true;
        }
    }

    /** Asks the copy that's already running to show its window, through a file it watches. */
    public static void requestShow() {
        try { Files.writeString(Settings.appDir().resolve(SHOW_REQUEST), String.valueOf(System.currentTimeMillis())); }
        catch (IOException ignored) {}
    }

    /** Brings the window forward whenever another copy asks for it (see {@link #requestShow()}). */
    public static void watchShowRequests(App app) {
        Path f = Settings.appDir().resolve(SHOW_REQUEST);
        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (Files.exists(f)) {
                        Files.deleteIfExists(f);
                        SwingUtilities.invokeLater(app.showWindow);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {}
            }
        }, "show-requests");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Records where this copy is installed and which local port it answers on (appdir/location.json), so Evade
     * Client can start it when Minecraft launches and reach it. A pending update left by Evade Installer is kept.
     */
    public static void writeLocation(int port) {
        try {
            String exe = hostExe != null ? hostExe.toString() : System.getProperty("launch4j.exefile");
            Path jar = hostJar != null ? hostJar : Path.of(ClipsApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String javaBin = Path.of(System.getProperty("java.home"), "bin", Ffmpeg.WINDOWS ? "javaw.exe" : "java").toString();
            var o = new com.google.gson.JsonObject();
            if (exe != null) o.addProperty("exe", exe);
            o.addProperty("jar", jar.toString());
            o.addProperty("java", javaBin);
            o.addProperty("port", port);
            Path f = Settings.appDir().resolve("location.json");
            // Evade Installer leaves a newer build next to the running one; keep pointing at it while it's there.
            // (Not when the Evade Launcher hosts Clips: a leftover update of the old separate app must not be started.)
            if (!hosted) try {
                if (Files.exists(f)) {
                    var old = com.google.gson.JsonParser.parseString(Files.readString(f));
                    if (old.isJsonObject() && old.getAsJsonObject().has("pendingUpdate")) {
                        String pending = old.getAsJsonObject().get("pendingUpdate").getAsString();
                        if (Files.isRegularFile(Path.of(pending))) o.addProperty("pendingUpdate", pending);
                    }
                }
            } catch (Exception ignored) {}
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("location.json.tmp");
            Files.writeString(tmp, o.toString());
            try { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            System.err.println("[clips] couldn't record install location: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ log file

    /**
     * Copies everything printed to the console into {@code appdir/logs/clips.log} (with the time on each line),
     * starting over in clips.1.log past 1 MB, so problems can be looked at after the fact.
     */
    public static void installLog() {
        try {
            Path dir = Settings.appDir().resolve("logs");
            Files.createDirectories(dir);
            LogFile log = new LogFile(dir);
            System.setOut(new PrintStream(new Tee(System.out, log), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(System.err, log), true, StandardCharsets.UTF_8));
            String v = ClipsApp.class.getPackage().getImplementationVersion();
            System.out.println("[clips] Evade Clips " + (v == null ? "dev" : v) + " started · " + System.getProperty("os.name")
                    + " · Java " + System.getProperty("java.version"));
        } catch (Exception e) {
            System.err.println("[clips] no log file: " + e.getMessage());
        }
    }

    /** The log file: a time stamp at the start of every line, rotated to clips.1.log past 1 MB. */
    private static final class LogFile extends OutputStream {
        private static final long MAX = 1_000_000;
        private final Path file, old;
        private OutputStream out;
        private long size;
        private boolean lineStart = true;

        LogFile(Path dir) throws IOException {
            file = dir.resolve("clips.log");
            old = dir.resolve("clips.1.log");
            if (Files.exists(file) && Files.size(file) > MAX) Files.move(file, old, StandardCopyOption.REPLACE_EXISTING);
            open();
        }

        private void open() throws IOException {
            out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size = Files.size(file);
        }

        @Override public synchronized void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (out == null) return;
            int start = off, end = off + len;
            for (int i = off; i < end; i++) {
                if (lineStart) {
                    if (i > start) { out.write(b, start, i - start); size += i - start; }
                    start = i;
                    byte[] stamp = (new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss ").format(new java.util.Date())).getBytes(StandardCharsets.UTF_8);
                    out.write(stamp);
                    size += stamp.length;
                    lineStart = false;
                }
                if (b[i] == '\n') lineStart = true;
            }
            if (end > start) { out.write(b, start, end - start); size += end - start; }
            if (lineStart) {
                out.flush();
                if (size > MAX) {
                    out.close();
                    try { Files.move(file, old, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
                    open();
                }
            }
        }

        @Override public synchronized void flush() throws IOException { if (out != null) out.flush(); }
    }

    /** Writes to the console and the log file; a failing log file never breaks the console. */
    private static final class Tee extends OutputStream {
        private final OutputStream a, b;
        Tee(OutputStream a, OutputStream b) { this.a = a; this.b = b; }
        @Override public void write(int x) throws IOException { a.write(x); try { b.write(x); } catch (IOException ignored) {} }
        @Override public void write(byte[] x, int off, int len) throws IOException { a.write(x, off, len); try { b.write(x, off, len); } catch (IOException ignored) {} }
        @Override public void flush() throws IOException { a.flush(); try { b.flush(); } catch (IOException ignored) {} }
    }

    // ------------------------------------------------------------------ first-run ffmpeg

    /** Offers to download ffmpeg (Windows, first run). Call on the UI thread. */
    public static void offerFfmpeg(App app, Window owner) {
        int ok = JOptionPane.showConfirmDialog(owner,
                "Evade Clips records and edits video with ffmpeg, which isn't on this PC yet.\nDownload it now? (about 90 MB, one time)",
                "Evade Clips", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) downloadFfmpeg(app, owner);
    }

    /**
     * Downloads ffmpeg with a progress window (it doesn't block the app). When it's ready the pages update and
     * instant replay starts if it should; on failure the window offers Retry. Call on the UI thread.
     */
    public static void downloadFfmpeg(App app, Window owner) {
        if (app.ffmpegDownloading) return;
        JDialog d = new JDialog(owner, "Setting up ffmpeg", java.awt.Dialog.ModalityType.MODELESS);
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setPreferredSize(new java.awt.Dimension(380, 10));
        JLabel text = Theme.label("Downloading ffmpeg… (about 90 MB, one time)", 13f, false, Theme.TEXT);
        JButton retry = Theme.button("Retry", null, true), close = Theme.button("Close", null, false);
        retry.setVisible(false);
        JPanel p = new JPanel(new BorderLayout(0, 12));
        Theme.pad(p, 18, 20, 16, 20);
        p.add(text, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(retry);
        buttons.add(close);
        p.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(owner);
        close.addActionListener(e -> d.dispose()); // the download carries on in the background
        Runnable[] start = new Runnable[1];
        start[0] = () -> {
            retry.setVisible(false);
            bar.setValue(0);
            bar.setIndeterminate(true);
            text.setForeground(Theme.TEXT);
            text.setText("Downloading ffmpeg… (about 90 MB, one time)");
            app.ffmpegDownloading = true;
            app.ffmpegProgress = 0;
            Thread t = new Thread(() -> {
                try {
                    Ffmpeg.downloadForWindows(f -> {
                        app.ffmpegProgress = f;
                        SwingUtilities.invokeLater(() -> {
                            bar.setIndeterminate(false);
                            bar.setValue((int) Math.round(f * 1000));
                            text.setText(String.format("Downloading ffmpeg… %d MB of about 90 MB", Math.round(f * 90)));
                        });
                    });
                    app.ffmpegDownloading = false;
                    SwingUtilities.invokeLater(d::dispose);
                    app.notify("Ready to record", "ffmpeg is set up", false);
                    app.fireFfmpegChanged();
                } catch (Exception e) {
                    app.ffmpegDownloading = false;
                    String why = e.getMessage() == null ? e.toString() : e.getMessage();
                    app.notify("ffmpeg download failed", why, true);
                    app.fireFfmpegChanged();
                    SwingUtilities.invokeLater(() -> {
                        bar.setIndeterminate(false);
                        text.setForeground(Theme.GOLD);
                        text.setText("<html><body style='width:360px'>The download failed: " + why.replace("<", "&lt;") + "</body></html>");
                        retry.setVisible(true);
                        d.pack();
                        if (!d.isVisible()) d.setVisible(true);
                    });
                }
            }, "ffmpeg-setup");
            t.setDaemon(true);
            t.start();
        };
        retry.addActionListener(e -> start[0].run());
        d.setVisible(true);
        start[0].run();
    }

    // ------------------------------------------------------------------ start

    /**
     * Everything but the window: the log file, location.json, the app (capture, library index) and its shutdown
     * hook, and the encoder probe. Call after taking the single-instance lock; then build the UI and call
     * {@code new Thread(app::start).start()}.
     */
    public static App startCore(Settings settings) {
        installLog();
        writeLocation(settings.controlPort);
        App app = new App(settings);
        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown, "shutdown"));
        // Encoder tests take a few seconds: run them now, so the Record page has them by the time it's opened.
        Thread probe = new Thread(() -> {
            if (!Ffmpeg.available()) return;
            Ffmpeg.captureEncoders("h264");
            Ffmpeg.captureEncoders("hevc");
        }, "encoder-probe");
        probe.setDaemon(true);
        probe.start();
        // Just the index (no probing), so the library shows its clips as soon as the window opens.
        app.library.loadIndex();
        return app;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            System.exit(SelfTest.run(Path.of(args.length > 1 ? args[1] : "build/selftest")));
        }
        if (args.length > 0 && args[0].equals("--edittest")) {
            System.exit(EditTests.run(Path.of(args.length > 1 ? args[1] : "build/edittest")) == 0 ? 0 : 1);
        }
        if (args.length > 0 && args[0].equals("--sizetest")) {
            System.exit(SizeTest.run(Path.of(args.length > 1 ? args[1] : "build/sizetest")));
        }
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        // Direct3D corrupts see-through windows (the clip toast) on some Windows graphics drivers; plain GDI is reliable.
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) System.setProperty("sun.java2d.d3d", "false");
        if (args.length > 0 && args[0].equals("--screenshots")) {
            Theme.install();
            Screenshots.run(Path.of(args.length > 1 ? args[1] : "build/screenshots"));
            System.exit(0);
        }
        boolean minimized = java.util.Arrays.asList(args).contains("--minimized");
        Settings settings = Settings.load();
        // One copy at a time (two would capture twice and save every clip twice): bring the running one forward.
        if (!acquireInstanceLock()) {
            if (!alreadyRunning(settings.controlPort, minimized) && !minimized) requestShow();
            System.exit(0);
        }
        // An older version (without the lock) may still be running.
        if (alreadyRunning(settings.controlPort, minimized)) System.exit(0);
        App app = startCore(settings);
        MainWindow[] window = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            app.toast = new Toast();
            window[0] = new MainWindow(app);
            if (!minimized) window[0].setVisible(true);
        });
        watchShowRequests(app);
        new Thread(app::start, "startup").start();
        // Standalone install on Windows: offer to fetch the video engine on first launch.
        if (Ffmpeg.WINDOWS && !Ffmpeg.available() && !minimized) SwingUtilities.invokeLater(() -> offerFfmpeg(app, window[0]));
    }
}
