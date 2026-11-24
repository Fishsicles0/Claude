package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * The launcher side of the game's state: every 2 seconds it reads each instance's heartbeat and play time (off the UI
 * thread), notices a session that ended badly and works out why ({@link CrashHelper}), follows the open log while the
 * Logs tab shows, keeps the bell's notifications on disk, and carries out what {@link GamePages} asks (stop the game,
 * Join / Continue requests, one-click crash fixes, instance settings, delete with Undo).
 */
final class GameController implements GamePages.Actions {
    private final LauncherView view;
    private final GamePages pages;
    private final Launcher.Settings settings;
    private final ExecutorService work;
    private final Runnable play, reload;
    private final Supplier<TrayIcon> tray;
    private final Supplier<java.awt.Component> window;
    private final Map<String, InstanceExtras> extras = new HashMap<>();
    private InstanceTrash.Pending pendingDelete;
    private Timer pendingTimer;
    private LogTools.Tail tail;
    private Launcher.Instance logInstance;
    private volatile boolean polling;
    private String noticeSig = "";
    private long noticesSeenAt;

    GameController(LauncherView view, Launcher.Settings settings, ExecutorService work, Runnable play, Runnable reload,
                   Supplier<TrayIcon> tray, Supplier<java.awt.Component> window) {
        this.view = view;
        this.pages = view.game;
        this.settings = settings;
        this.work = work;
        this.play = play;
        this.reload = reload;
        this.tray = tray;
        this.window = window;
        pages.actions = this;
        loadPrefs();
    }

    /** Starts watching (call once, on the UI thread). */
    void start() {
        loadNotices();
        work.submit(() -> InstanceTrash.finishLeftovers(settings));
        new Timer(2000, e -> { poll(); saveNoticesIfChanged(); }).start();
        new Timer(1000, e -> tailLog()).start();
        poll();
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> { r.run(); view.repaint(); });
    }

    InstanceExtras extras(Launcher.Instance i) {
        synchronized (extras) {
            return extras.computeIfAbsent(i.dir.toString(), k -> {
                InstanceExtras x = InstanceExtras.load(i.dir);
                if (x.handledReport == 0) {
                    // the first look at this instance: older reports aren't news
                    x.handledReport = System.currentTimeMillis();
                    x.save(i.dir);
                }
                return x;
            });
        }
    }

    // ------------------------------------------------------------------ polling

    private void poll() {
        if (polling) return;
        polling = true;
        List<Launcher.Instance> list = List.copyOf(view.instances);
        work.submit(() -> {
            try {
                long now = System.currentTimeMillis();
                for (Launcher.Instance i : list) {
                    if (!Files.isDirectory(i.dir)) continue;
                    InstanceExtras x = extras(i);
                    GameState.Session s = GameState.readSession(i.dir);
                    GameState.Run run = GameState.classify(s, now, s != null && GameState.alive(s.pid()));
                    GameState.Playtime pt = GameState.readPlaytime(i.dir);
                    String[] world = GameState.lastWorld(i.dir);
                    JsonObject req = GameState.pendingRequest(i.dir);
                    List<Path> reports = reports(i.dir);
                    CrashHelper.Diagnosis crash = null;
                    // a session that ended badly, not dealt with yet
                    if (s != null && run == GameState.Run.ENDED_BADLY && s.started() != x.handledSession) {
                        Path rep = CrashHelper.newestReport(i.dir, s.started() - 1000);
                        crash = CrashHelper.diagnose(i.dir, rep, i.memoryGb, now);
                        x.handledSession = s.started();
                        if (rep != null) x.handledReport = Math.max(x.handledReport, mtime(rep));
                        x.save(i.dir);
                    } else if (s != null && run == GameState.Run.ENDED_CLEAN && s.started() != x.handledSession) {
                        x.handledSession = s.started();
                        x.save(i.dir);
                    } else if (run != GameState.Run.RUNNING && run != GameState.Run.NOT_RESPONDING) {
                        // a game without the heartbeat (an older Evade) still leaves crash reports
                        Path rep = CrashHelper.newestReport(i.dir, x.handledReport);
                        if (rep != null) {
                            crash = CrashHelper.diagnose(i.dir, rep, i.memoryGb, now);
                            x.handledReport = mtime(rep);
                            x.save(i.dir);
                        }
                    }
                    CrashHelper.Diagnosis found = crash;
                    ui(() -> {
                        GamePages.Info in = pages.info(i);
                        in.session = s;
                        in.run = run;
                        in.playtime = pt;
                        in.extras = x;
                        in.lastWorld = world;
                        in.reports = reports;
                        in.requestName = req == null ? null : ("world".equals(InstallerCore.str(req, "type")) ? "Continue " : "Join ")
                                + InstallerCore.str(req, "name");
                        in.requestAt = req == null ? 0 : req.get("created").getAsLong();
                        // The launcher stays open after Play: once the game is up, Home shows it playing (and Play again after).
                        if (in.running() && view.play == LauncherView.Play.LAUNCHED && i.id.equals(settings.selected)) view.play = LauncherView.Play.IDLE;
                        if (found != null) {
                            in.crash = found;
                            view.notice(i.name + " crashed", found.title(), true);
                            notifyOs(i.name + " crashed", found.title() + ". Open the Evade Launcher for a fix.");
                        }
                    });
                }
            } catch (Exception e) {
                System.err.println("game state: " + e);
            } finally {
                polling = false;
            }
        });
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return System.currentTimeMillis(); }
    }

    private static List<Path> reports(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.resolve("crash-reports"), "crash-*.txt")) {
            for (Path p : ds) out.add(p);
        } catch (IOException ignored) { }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "hs_err_pid*.log")) {
            for (Path p : ds) out.add(p);
        } catch (IOException ignored) { }
        out.sort(Comparator.comparingLong(GameController::mtime).reversed());
        return out.size() > 6 ? out.subList(0, 6) : out;
    }

    /** Whether Play (or an update) must wait: the instance's game is running. Tells the player why. */
    boolean blockPlay(Launcher.Instance i, boolean update) {
        if (i == null || !GameState.isRunning(i.dir)) return false;
        if (update) view.toast(i.name + " is running. Close the game before it's updated.");
        else if (GameState.pendingRequest(i.dir) != null) view.toast("Sent to the game: it acts on the title screen within 2 minutes");
        else view.toast(i.name + " is already running");
        return true;
    }

    // ------------------------------------------------------------------ requests and stopping

    @Override public void stopGame(Launcher.Instance i) {
        GameState.Session s = pages.info(i).session;
        work.submit(() -> {
            boolean ok = GameState.stop(s);
            ui(() -> view.toast(ok ? "Stopped " + i.name : "The game had already closed"));
            poll();
        });
    }

    @Override public void joinServer(Launcher.Server sv) {
        Launcher.Instance i = view.selected();
        if (i == null) return;
        if (i.installed == null) { view.toast("Press Play once to install Evade first"); return; }
        request(i, false, sv.address(), sv.name());
    }

    @Override public void continueWorld(Launcher.Instance i) {
        String[] w = pages.info(i).lastWorld;
        if (w == null) { view.toast("No world to continue in " + i.name); return; }
        request(i, true, w[0], w[1]);
    }

    /** Writes the one-shot request, then Play (or, while the game runs, tells the player where it will happen). */
    private void request(Launcher.Instance i, boolean world, String target, String name) {
        try {
            GameState.writeRequest(i.dir, world, target, name);
        } catch (IOException e) {
            view.toast("Couldn't pass that to the game: " + EvadeInstaller.friendly(e));
            return;
        }
        boolean running = GameState.isRunning(i.dir);
        if (!i.id.equals(settings.selected)) view.actions.select(i);
        if (running) view.toast((world ? "Opening " : "Joining ") + name + " when the game is on its title screen (2 minutes)");
        else {
            view.toast((world ? "Continue " : "Join ") + name + ": press Play in the Minecraft Launcher");
            play.run();
        }
        poll();
    }

    @Override public void cancelRequest(Launcher.Instance i) {
        GameState.cancelRequest(i.dir);
        pages.info(i).requestName = null;
        view.toast("Cancelled");
    }

    // ------------------------------------------------------------------ crash fixes

    @Override public void dismissCrash(Launcher.Instance i) { pages.info(i).crash = null; }

    @Override public void openReport(Path p) {
        if (p == null) return;
        Launcher.Instance i = view.page == LauncherView.Page.GAME ? view.focused() : pages.crashInstance;
        if (i == null) i = view.focused();
        view.showPage(LauncherView.Page.GAME);
        pages.tab = "logs";
        openLog(i, p);
    }

    @Override public void fix(Launcher.Instance i, CrashHelper.Diagnosis d, boolean alt) {
        if (GameState.isRunning(i.dir)) { view.toast(i.name + " is running. Close it first."); return; }
        CrashHelper.Fix f = d.fix();
        if (alt) {
            switch (f) {
                case DRIVER -> { i.shaders = false; i.dirty = true; view.actions.instanceChanged(i); pages.info(i).crash = null; view.toast("Shaders are off for " + i.name + ". Press Play."); }
                case FIND_MOD -> disableAndMaybePlay(i, d, false);
                default -> { openReport(d.report() != null ? d.report() : i.dir.resolve("logs").resolve("latest.log")); shareLog(); }
            }
            return;
        }
        switch (f) {
            case DISABLE_MOD -> disableAndMaybePlay(i, d, true);
            case RAISE_MEMORY, LOWER_MEMORY -> {
                int[] opts = InstallerCore.memoryOptions();
                int to = i.memoryGb;
                for (int k = 0; k < opts.length; k++) if (opts[k] == i.memoryGb) to = opts[Math.max(0, Math.min(opts.length - 1, k + (f == CrashHelper.Fix.RAISE_MEMORY ? 1 : -1)))];
                if (to == i.memoryGb) to = f == CrashHelper.Fix.RAISE_MEMORY ? InstallerCore.clampMemory(i.memoryGb + 2) : Math.max(2, i.memoryGb - 2);
                if (to == i.memoryGb) { view.toast("This PC can't give " + i.name + " more memory. Close other programs, or turn shaders off."); return; }
                i.memoryGb = to;
                view.actions.instanceChanged(i);
                pages.info(i).crash = null;
                view.toast(i.name + " now uses " + to + " GB");
                selectAndPlay(i);
            }
            case DRIVER -> view.actions.openUrl(driverUrl(d.detail()));
            case CLEAR_JAVA -> {
                InstanceExtras x = extras(i);
                x.javaDir = "";
                settings.javaPath = "";
                view.setJavaText("");
                Launcher.saveSettings(settings);
                extrasChanged(i, x);
                pages.syncFields(i);
                pages.info(i).crash = null;
                view.toast("Using the Minecraft Launcher's own Java");
                selectAndPlay(i);
            }
            case FIND_MOD -> view.actions.openUrl("https://modrinth.com/mods?q=" + URLEncoder.encode(d.mod() == null ? "" : d.mod(), StandardCharsets.UTF_8)
                    + "&g=categories:fabric&v=" + InstallerCore.MC_VERSION);
            case OPEN_MODS -> view.actions.openFolder(i.dir.resolve("mods"));
            default -> openReport(d.report());
        }
    }

    private void disableAndMaybePlay(Launcher.Instance i, CrashHelper.Diagnosis d, boolean thenPlay) {
        if (d.jar() == null) { view.actions.openFolder(i.dir.resolve("mods")); return; }
        work.submit(() -> {
            try {
                Mods.Entry e = null;
                for (Mods.Entry m : Mods.list(i.dir)) if (m.file().equals(d.jar())) e = m;
                if (e == null) throw new IOException(d.jar().getFileName() + " isn't in the mods folder any more");
                Mods.setEnabled(e, false);
                String nm = e.name();
                ui(() -> {
                    pages.info(i).crash = null;
                    view.toast(nm + " is switched off. Turn it back on in Mods.");
                    if (thenPlay) selectAndPlay(i);
                });
            } catch (Exception ex) {
                ui(() -> view.toast("Couldn't switch it off: " + EvadeInstaller.friendly(ex)));
            }
        });
    }

    private void selectAndPlay(Launcher.Instance i) {
        if (!i.id.equals(settings.selected)) view.actions.select(i);
        play.run();
    }

    static String driverUrl(String detail) {
        String d = detail == null ? "" : detail.toLowerCase(java.util.Locale.ROOT);
        if (d.contains("nvogl") || d.contains("nvd3d") || d.contains("nvwgf") || d.contains("nvidia")) return "https://www.nvidia.com/en-us/drivers/";
        if (d.contains("ati") || d.contains("amd") || d.contains("radeon")) return "https://www.amd.com/en/support/download/drivers.html";
        if (d.contains("ig") || d.contains("intel") || d.contains("iris")) return "https://www.intel.com/content/www/us/en/support/detect.html";
        return "https://www.minecraft.net/en-us/article/updating-your-video-card-drivers";
    }

    // ------------------------------------------------------------------ logs

    @Override public void tabShown(String tab) {
        Launcher.Instance i = view.focused();
        if (tab.equals("logs") && i != null && (logInstance != i || pages.logFile == null)) openLog(i, null);
        if (tab.equals("settings") && i != null) checkJava(i);
    }

    /** Called when the Game page opens or its instance changes. */
    void pageShown() {
        Launcher.Instance i = view.focused();
        if (i == null) return;
        pages.info(i).extras = extras(i);
        pages.syncFields(i);
        if (pages.tab.equals("logs")) openLog(i, logInstance == i ? pages.logFile : null);
        if (pages.tab.equals("settings")) checkJava(i);
    }

    @Override public void openLog(Launcher.Instance i, Path file) {
        if (i == null) return;
        logInstance = i;
        List<Path> files = new ArrayList<>(LogTools.logFiles(i.dir));
        if (file != null && !files.contains(file)) files.add(0, file);
        Path f = file != null ? file : files.isEmpty() ? null : files.get(0);
        pages.logFiles = files;
        pages.logFile = f;
        pages.logLines = List.of();
        pages.logError = null;
        pages.follow = true;
        view.resetScroll("gp-log");
        tail = null;
        if (f == null) return;
        work.submit(() -> {
            try {
                long size = Files.size(f);
                List<LogTools.Line> lines = LogTools.parse(LogTools.readAll(f));
                LogTools.Tail t = f.getFileName().toString().equals("latest.log") ? new LogTools.Tail(f) : null;
                if (t != null) { t.poll(); t.skipTo(size); }
                ui(() -> {
                    if (pages.logFile != f) return;
                    pages.logLines = lines;
                    tail = t;
                });
            } catch (Exception e) {
                ui(() -> { if (pages.logFile == f) pages.logError = "Couldn't read " + f.getFileName() + ": " + EvadeInstaller.friendly(e); });
            }
        });
    }

    private volatile boolean tailing;

    private void tailLog() {
        if (view.page != LauncherView.Page.GAME || !pages.tab.equals("logs") || tail == null || tailing || !view.isShowing()) return;
        Launcher.Instance foc = view.focused();
        if (foc != logInstance) { openLog(foc, null); return; }
        LogTools.Tail t = tail;
        tailing = true;
        work.submit(() -> {
            try {
                List<String> add = t.poll();
                if (add == null) ui(() -> openLog(foc, t.file));
                else if (!add.isEmpty()) {
                    ui(() -> {
                        if (tail != t) return;
                        List<LogTools.Line> l = new ArrayList<>(pages.logLines);
                        LogTools.Level prev = l.isEmpty() ? LogTools.Level.INFO : l.get(l.size() - 1).level();
                        for (String s : add) { prev = LogTools.level(s, prev); l.add(new LogTools.Line(s, prev)); }
                        if (l.size() > LogTools.MAX_LINES) l = new ArrayList<>(l.subList(l.size() - LogTools.MAX_LINES, l.size()));
                        pages.logLines = l;
                    });
                }
            } catch (Exception ignored) {
            } finally {
                tailing = false;
            }
        });
    }

    private String shownLog() {
        return LogTools.maskAll(LogTools.filter(pages.logLines, pages.logFilter, pages.search.getText()), view.playerName);
    }

    @Override public void copyLog() {
        if (pages.logLines.isEmpty()) { view.toast("Nothing to copy yet"); return; }
        if (clip(shownLog())) view.toast("Copied the log (private details hidden)");
    }

    /**
     * Evade has no log upload service, so sharing copies the whole log with private details hidden; the player pastes
     * it where they're asking for help.
     */
    @Override public void shareLog() {
        if (pages.logLines.isEmpty()) { view.toast("Nothing to share yet"); return; }
        String text = "Evade Client log (" + (pages.logFile == null ? "" : pages.logFile.getFileName()) + ", private details hidden)\n"
                + LogTools.maskAll(pages.logLines, view.playerName);
        if (clip(text)) view.toast("Log copied with private details hidden. Paste it in #support on the Evade Discord.");
    }

    private boolean clip(String s) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null);
            return true;
        } catch (Exception e) {
            view.toast("Couldn't use the clipboard");
            return false;
        }
    }

    // ------------------------------------------------------------------ instance settings

    @Override public void extrasChanged(Launcher.Instance i, InstanceExtras x) {
        work.submit(() -> {
            try {
                if (InstallerCore.readProfile(settings.minecraftDir(), i.profileKey()) != null)
                    x.applyToProfile(settings.minecraftDir(), i.profileKey(), settings.javaPath, i.dir);
                else x.save(i.dir);
            } catch (Exception e) {
                ui(() -> view.toast("Couldn't save: " + EvadeInstaller.friendly(e)));
            }
        });
        checkJava(i);
    }

    /** Right after Play writes the profile: the instance's own Java, arguments, window size and icon on top. */
    void applyExtras(Launcher.Instance i) throws IOException {
        extras(i).applyToProfile(settings.minecraftDir(), i.profileKey(), settings.javaPath, i.dir);
    }

    @Override public void browseJava(Launcher.Instance i) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(InstallerCore.WINDOWS ? "Choose javaw.exe for " + i.name : "Choose the java program for " + i.name);
        if (fc.showOpenDialog(window.get()) != JFileChooser.APPROVE_OPTION) return;
        pages.java.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private int javaSeq;

    /** Runs the chosen Java with -version and says what it is (Minecraft 1.21.11 needs 21 or newer). */
    private void checkJava(Launcher.Instance i) {
        String path = extras(i).javaDir.strip();
        int seq = ++javaSeq;
        if (path.isEmpty()) { pages.javaCheck = null; return; }
        work.submit(() -> {
            String msg;
            Path p = Path.of(path);
            if (!Files.isRegularFile(p)) msg = "Not found: this instance will use Settings' Java";
            else {
                Path exe = p;
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (n.equals("javaw.exe") && Files.isRegularFile(p.resolveSibling("java.exe"))) exe = p.resolveSibling("java.exe");
                try {
                    Process pr = new ProcessBuilder(exe.toString(), "-version").redirectErrorStream(true).start();
                    String out = new String(pr.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
                    pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("version \"(\\d+)").matcher(out);
                    int major = m.find() ? Integer.parseInt(m.group(1)) : -1;
                    if (major == 1) major = 8;
                    msg = major < 0 ? "Couldn't tell which Java this is" : major < 21 ? "Java " + major + ": too old, Minecraft needs 21" : "Java " + major + ": good";
                } catch (Exception e) {
                    msg = "Couldn't run it: " + EvadeInstaller.friendly(e);
                }
            }
            String m2 = msg;
            ui(() -> { if (seq == javaSeq) pages.javaCheck = m2; });
        });
    }

    // ------------------------------------------------------------------ delete with Undo

    /** What deleting loses, for the confirm dialog. */
    String deleteSummary(Launcher.Instance i) {
        return InstanceTrash.summarize(i.dir).text(i.name);
    }

    /** Deletes with a 10-second Undo. Returns false (and says why) while the game runs. */
    void delete(Launcher.Instance i, Runnable after) {
        if (GameState.isRunning(i.dir)) { view.toast(i.name + " is running. Close the game first."); return; }
        finishPending();
        work.submit(() -> {
            try {
                InstanceTrash.Pending p = InstanceTrash.hold(settings, i);
                ui(() -> {
                    pendingDelete = p;
                    after.run();
                    pages.undo("Deleted " + i.name);
                    pendingTimer = new Timer((int) InstanceTrash.UNDO_MS + 200, e -> finishPending());
                    pendingTimer.setRepeats(false);
                    pendingTimer.start();
                });
            } catch (Exception e) {
                ui(() -> view.toast("Couldn't delete it: " + EvadeInstaller.friendly(e)));
            }
        });
    }

    @Override public void undoDelete() {
        InstanceTrash.Pending p = pendingDelete;
        if (p == null) return;
        pendingDelete = null;
        if (pendingTimer != null) pendingTimer.stop();
        work.submit(() -> {
            try {
                InstanceTrash.restore(p);
                ui(() -> {
                    reload.run();
                    for (Launcher.Instance i : view.instances) if (i.id.equals(p.id())) {
                        view.focusedId = i.id;
                        try { if (i.installed != null) InstallerCore.writeProfiles(Launcher.options(settings, i, false, false, false)); } catch (IOException ignored) { }
                    }
                    view.toast("Restored " + p.name());
                });
            } catch (Exception e) {
                ui(() -> view.toast("Couldn't undo: " + EvadeInstaller.friendly(e)));
            }
        });
    }

    /** Sends a held instance on to the Recycle Bin / Trash now (Undo time over, another delete, or quitting). */
    void finishPending() {
        InstanceTrash.Pending p = pendingDelete;
        pendingDelete = null;
        if (pendingTimer != null) pendingTimer.stop();
        if (p != null) InstanceTrash.finish(p);
    }

    // ------------------------------------------------------------------ notifications

    private Path noticesFile() { return Launcher.dataDir().resolve("notifications.json"); }

    private void loadNotices() {
        JsonObject o = InstallerCore.readJsonObject(noticesFile());
        try {
            noticesSeenAt = o.has("seen") ? o.get("seen").getAsLong() : 0;
            if (o.has("items") && o.get("items").isJsonArray()) {
                List<LauncherView.Notice> l = new ArrayList<>();
                for (var e : o.getAsJsonArray("items")) {
                    JsonObject n = e.getAsJsonObject();
                    l.add(new LauncherView.Notice(InstallerCore.str(n, "title"), InstallerCore.str(n, "body"), n.get("at").getAsLong(), n.has("error") && n.get("error").getAsBoolean()));
                }
                // notices from this run (already shown) stay first
                for (LauncherView.Notice n : l) if (view.notices.stream().noneMatch(x -> same(x, n))) view.notices.add(n);
                view.notices.sort(Comparator.comparingLong(LauncherView.Notice::at).reversed());
                while (view.notices.size() > 30) view.notices.remove(view.notices.size() - 1);
            }
        } catch (RuntimeException ignored) { }
        recountUnread();
    }

    private static boolean same(LauncherView.Notice a, LauncherView.Notice b) {
        return a.title().equals(b.title()) && a.body().equals(b.body());
    }

    /** Keeps one of each title+body (the newest) and counts unread ones from the last time the bell was opened. */
    private void recountUnread() {
        List<LauncherView.Notice> l = view.notices;
        for (int a = 0; a < l.size(); a++) for (int b = l.size() - 1; b > a; b--) if (same(l.get(a), l.get(b))) l.remove(b);
        int n = 0;
        for (LauncherView.Notice x : l) if (x.at() > noticesSeenAt) n++;
        view.unread = "bell".equals(view.overlay) ? 0 : n;
    }

    void noticesSeen() {
        noticesSeenAt = System.currentTimeMillis();
        noticeSig = "";
        saveNoticesIfChanged();
    }

    private void saveNoticesIfChanged() {
        StringBuilder sig = new StringBuilder().append(noticesSeenAt);
        for (LauncherView.Notice n : view.notices) sig.append('|').append(n.at()).append(n.title());
        if (sig.toString().equals(noticeSig)) return;
        recountUnread();
        noticeSig = sig.toString();
        JsonObject o = new JsonObject();
        o.addProperty("seen", noticesSeenAt);
        JsonArray a = new JsonArray();
        for (LauncherView.Notice n : view.notices) {
            JsonObject x = new JsonObject();
            x.addProperty("title", n.title());
            x.addProperty("body", n.body());
            x.addProperty("at", n.at());
            x.addProperty("error", n.error());
            a.add(x);
        }
        o.add("items", a);
        o.addProperty("osNotify", pages.osNotify);
        work.submit(() -> {
            try {
                Files.createDirectories(Launcher.dataDir());
                InstallerCore.writeAtomic(noticesFile(), InstallerCore.GSON.toJson(o));
            } catch (IOException ignored) { }
        });
    }

    private void loadPrefs() {
        JsonObject o = InstallerCore.readJsonObject(noticesFile());
        pages.osNotify = !o.has("osNotify") || o.get("osNotify").getAsBoolean();
    }

    @Override public void osNotifyChanged(boolean on) {
        noticeSig = "";
        saveNoticesIfChanged();
    }

    /** A desktop notification (the tray's, or a short-lived tray icon of its own), when they're on. */
    void notifyOs(String title, String body) {
        if (!pages.osNotify) return;
        try {
            TrayIcon t = tray.get();
            if (t != null) { t.displayMessage(title, body, TrayIcon.MessageType.WARNING); return; }
            if (!SystemTray.isSupported()) return;
            TrayIcon temp = new TrayIcon(LauncherView.image("/evade/icon-128.png"), "Evade");
            temp.setImageAutoSize(true);
            SystemTray.getSystemTray().add(temp);
            temp.displayMessage(title, body, TrayIcon.MessageType.WARNING);
            Timer gone = new Timer(12_000, e -> { try { SystemTray.getSystemTray().remove(temp); } catch (RuntimeException ignored) { } });
            gone.setRepeats(false);
            gone.start();
        } catch (Exception ignored) { /* no tray here: the bell has it */ }
    }

    /** For update notices raised elsewhere. */
    void notifyUpdate(String title, String body) { notifyOs(title, body); }
}
