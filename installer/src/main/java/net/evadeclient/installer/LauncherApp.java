package net.evadeclient.installer;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the launcher window to the model: loads settings and instances, checks for updates and news in the
 * background, and carries out what the player clicks. Long work runs off the UI thread; results come back with
 * invokeLater. Play = bring the instance up to date if needed, make its profile the most recently used one, then
 * open the official Minecraft Launcher (which does the sign-in and runs the game).
 */
final class LauncherApp implements LauncherView.Actions {
    private final InstallerCore core = new InstallerCore();
    private final LauncherView view = new LauncherView();
    private final Launcher.Settings settings = Launcher.loadSettings();
    private final ExecutorService work = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "launcher-work");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger searchSeq = new AtomicInteger();
    private JFrame frame;
    private volatile boolean busy;

    static void open() {
        new LauncherApp().start();
    }

    private void start() {
        view.actions = this;
        view.settings = settings;
        view.version = EvadeInstaller.launcherVersion();
        view.setupMemory = settings.defaultMemoryGb;
        view.setJavaText(settings.javaPath);
        reloadInstances();
        view.news = News.cached();

        frame = new JFrame("Evade Launcher");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { close(); }
        });
        frame.setContentPane(view);
        boolean perPixel = perPixelTranslucency();
        if (perPixel) {
            try {
                frame.setBackground(new Color(0, 0, 0, 0));
            } catch (RuntimeException e) {
                perPixel = false;
                frame.setBackground(LauncherView.BG);
            }
        }
        view.setOpaque(!perPixel);
        view.roundCorners = perPixel;
        frame.pack();
        frame.setResizable(false);
        if (!perPixel) {
            try { frame.setShape(new RoundRectangle2D.Double(0, 0, LauncherView.W, LauncherView.H, 22, 22)); } catch (Exception ignored) { }
        }
        frame.setIconImages(List.of(LauncherView.image("/evade/icon-128.png")));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        retryRelease();
        loadNews();
    }

    private static boolean perPixelTranslucency() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ loading

    private void reloadInstances() {
        List<Launcher.Instance> list = Launcher.listInstances(settings);
        for (Launcher.Instance i : list) Launcher.check(i, view.release);
        view.instances = list;
        if (list.stream().noneMatch(i -> i.id.equals(settings.selected))) settings.selected = Launcher.DEFAULT_ID;
        if (list.stream().noneMatch(i -> i.id.equals(view.focusedId))) view.focusedId = settings.selected;
        Path clips = InstallerCore.clipsInstalledApp();
        view.clipsPresent = clips != null;
        String cv = settings.clipsVersion;
        if (cv == null && !list.isEmpty()) {
            com.google.gson.JsonObject m = InstallerCore.readMarker(list.get(0).dir);
            cv = m != null ? InstallerCore.str(m, "clips") : null;
        }
        view.clipsVersion = cv;
        view.repaint();
    }

    @Override public void retryRelease() {
        view.releaseError = null;
        view.repaint();
        work.submit(() -> {
            try {
                InstallerCore.Release r = core.fetchRelease();
                ui(() -> {
                    view.release = r;
                    view.releaseError = null;
                    for (Launcher.Instance i : view.instances) Launcher.check(i, r);
                });
            } catch (Exception e) {
                Throwable c = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                String msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
                ui(() -> view.releaseError = msg);
            }
        });
    }

    private void loadNews() {
        view.newsLoading = true;
        work.submit(() -> {
            try {
                List<News.Entry> n = News.fetch(core);
                ui(() -> { view.news = n; view.newsError = null; view.newsLoading = false; });
            } catch (Exception e) {
                ui(() -> { view.newsError = e.getMessage(); view.newsLoading = false; });
            }
        });
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> {
            r.run();
            view.repaint();
        });
    }

    private void fail(String what, Exception e) {
        String msg = EvadeInstaller.friendly(e);
        ui(() -> view.toast(what + ": " + msg));
    }

    // ------------------------------------------------------------------ play & install

    @Override public void play() {
        Launcher.Instance i = view.selected();
        if (i == null || busy) return;
        InstallerCore.Release r = view.release;
        Launcher.check(i, r);
        String reason = i.pending;
        boolean update = i.installed == null || i.dirty || reason != null;
        if (update && r == null) {
            if (i.installed == null) {
                view.playName = i.name;
                view.error = view.releaseError != null ? EvadeInstaller.sentence(view.releaseError) + " Evade needs to download its files once. Check your connection and try again."
                        : "Still checking for the latest version. Try again in a moment.";
                view.play = LauncherView.Play.ERROR;
                if (view.releaseError != null) retryRelease();
                view.repaint();
                return;
            }
            update = false; // offline: play what's installed
        }
        boolean clipsUpdate = settings.clips && r != null && r.clipsUrl() != null
                && (InstallerCore.clipsInstalledApp() == null || !r.clipsVersion().equals(view.clipsVersion));
        runPlay(i, r, update, clipsUpdate, i.isDefault() && i.installed == null, false);
    }

    @Override public void setupInstall() {
        Launcher.Instance i = view.selected();
        if (i == null || busy || view.release == null) return;
        if (!i.isDefault()) {
            for (Launcher.Instance x : view.instances) if (x.isDefault()) i = x;
            settings.selected = i.id;
        }
        i.memoryGb = view.setupMemory;
        i.perf = view.setupPerf;
        i.voice = view.setupVoice;
        i.shaders = view.setupShaders;
        i.dirty = true;
        settings.defaultMemoryGb = view.setupMemory;
        settings.clips = view.setupClips;
        Launcher.saveSettings(settings);
        view.setup = true;
        runPlay(i, view.release, true, view.setupClips && view.release.clipsUrl() != null, view.setupCopy, true);
    }

    @Override public void installClips() {
        InstallerCore.Release r = view.release;
        if (r == null || r.clipsUrl() == null || busy) {
            view.toast(r == null ? "Still checking for the latest version. Try again in a moment." : "Evade Clips isn't available right now");
            return;
        }
        busy = true;
        settings.clips = true;
        Launcher.saveSettings(settings);
        view.toast("Installing Evade Clips " + r.clipsVersion() + "…");
        work.submit(() -> {
            try {
                core.installClips(r, (f, m) -> { });
                settings.clipsVersion = r.clipsVersion();
                Launcher.saveSettings(settings);
                ui(() -> { reloadInstances(); view.toast("Evade Clips is installed"); });
            } catch (Exception e) {
                fail("Evade Clips wasn't installed", e);
            } finally {
                busy = false;
            }
        });
    }

    /**
     * Updates the instance if asked (and Evade Clips), writes its profile as the most recently used one, then opens
     * the Minecraft Launcher. {@code setupOnly}: the first install from Home, which ends back on Home instead.
     */
    private void runPlay(Launcher.Instance i, InstallerCore.Release r, boolean update, boolean clips, boolean copySettings, boolean setupOnly) {
        busy = true;
        view.playName = i.name;
        view.progress = 0;
        view.shownProgress = 0;
        view.status = "";
        view.skipped = List.of();
        view.error = "";
        view.play = update || clips ? LauncherView.Play.UPDATING : LauncherView.Play.LAUNCHING;
        view.repaint();
        work.submit(() -> {
            List<String> skipped = new ArrayList<>();
            try {
                InstallerCore.Progress p = (f, msg) -> ui(() -> {
                    view.progress = Math.max(view.progress, f);
                    if (msg != null) view.status = msg;
                });
                if (update) {
                    skipped.addAll(core.install(Launcher.options(settings, i, copySettings, clips, true), r, p));
                    i.dirty = false;
                    if (clips && skipped.stream().noneMatch(s -> s.startsWith("Evade Clips"))) settings.clipsVersion = r.clipsVersion();
                } else if (clips) {
                    try {
                        p.update(0.3, "Updating Evade Clips " + r.clipsVersion());
                        core.installClips(r, p);
                        settings.clipsVersion = r.clipsVersion();
                    } catch (InstallerCore.ClipsBusyException e) {
                        skipped.add(InstallerCore.CLIPS_BUSY);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        skipped.add("Evade Clips couldn't be updated");
                    }
                }
                // The profile, last used now, so the Minecraft Launcher preselects this instance.
                InstallerCore.Options o = Launcher.options(settings, i, false, false, true);
                InstallerCore.writeProfiles(o);
                String java = settings.javaPath == null || settings.javaPath.isBlank() ? null : settings.javaPath.strip();
                i.javaDirWritten = java;
                if (!i.isDefault() || update || java != null || Files.exists(i.dir.resolve(Launcher.INSTANCE_FILE))) Launcher.save(i);
                Launcher.saveSettings(settings);
                if (setupOnly) {
                    ui(() -> {
                        busy = false;
                        view.setup = false;
                        view.play = LauncherView.Play.IDLE;
                        view.skipped = List.copyOf(skipped);
                        reloadInstances();
                        view.toast(skipped.isEmpty() ? "Evade Client is installed. Press Play!" : "Installed. " + skipped.size() + " extra" + (skipped.size() == 1 ? " was" : "s were") + " skipped: " + shortSkip(skipped.get(0)));
                    });
                    return;
                }
                ui(() -> {
                    view.play = LauncherView.Play.LAUNCHING;
                    view.skipped = List.copyOf(skipped);
                    reloadInstances();
                });
                boolean ok = EvadeInstaller.openLauncher();
                ui(() -> {
                    busy = false;
                    view.play = ok ? LauncherView.Play.LAUNCHED : LauncherView.Play.NO_LAUNCHER;
                    if (ok && settings.closeOnPlay && skipped.isEmpty()) {
                        Timer t = new Timer(2500, e -> { if (view.play == LauncherView.Play.LAUNCHED) System.exit(0); });
                        t.setRepeats(false);
                        t.start();
                    }
                });
            } catch (Exception e) {
                String msg = EvadeInstaller.friendly(e);
                ui(() -> {
                    busy = false;
                    view.error = msg;
                    view.play = LauncherView.Play.ERROR;
                    reloadInstances();
                });
            }
        });
    }

    private static String shortSkip(String s) {
        return s.equals(InstallerCore.CLIPS_BUSY) ? "Evade Clips (it may be open)" : s;
    }

    @Override public void openLauncherAgain() {
        work.submit(() -> {
            boolean ok = EvadeInstaller.openLauncher();
            if (!ok) ui(() -> view.play = LauncherView.Play.NO_LAUNCHER);
        });
    }

    // ------------------------------------------------------------------ instances

    @Override public void select(Launcher.Instance i) {
        settings.selected = i.id;
        view.focusedId = i.id;
        Launcher.saveSettings(settings);
        if (view.play != LauncherView.Play.UPDATING && view.play != LauncherView.Play.LAUNCHING) view.play = LauncherView.Play.IDLE;
        if (view.page == LauncherView.Page.MODS) showMods(i);
        view.repaint();
    }

    @Override public void createInstance(String name, String copyFrom) {
        List<Launcher.Instance> all = view.instances;
        int mem = settings.defaultMemoryGb;
        work.submit(() -> {
            try {
                Launcher.Instance i = Launcher.create(settings, all, name, copyFrom, mem, true, false, true);
                ui(() -> {
                    reloadInstances();
                    view.focusedId = i.id;
                    view.toast(i.name + " is ready. Press Play to install Evade in it.");
                });
            } catch (Exception e) {
                fail("Couldn't create the instance", e);
            }
        });
    }

    @Override public void rename(Launcher.Instance i, String name) {
        String n = Launcher.uniqueName(view.instances, name, i);
        work.submit(() -> {
            try {
                Launcher.rename(settings, i, n);
                ui(() -> { reloadInstances(); view.toast("Renamed to " + n); });
            } catch (Exception e) {
                fail("Couldn't rename it", e);
            }
        });
    }

    @Override public void duplicate(Launcher.Instance i) {
        view.toast("Copying " + i.name + "…");
        List<Launcher.Instance> all = view.instances;
        work.submit(() -> {
            try {
                Launcher.Instance copy = Launcher.duplicate(settings, all, i);
                // A copy of an installed instance can be played straight away: give it its own profile now.
                if (copy.installed != null) InstallerCore.writeProfiles(Launcher.options(settings, copy, false, false, false));
                ui(() -> { reloadInstances(); view.focusedId = copy.id; view.toast("Made " + copy.name); });
            } catch (Exception e) {
                fail("Couldn't copy it", e);
            }
        });
    }

    @Override public void delete(Launcher.Instance i) {
        if (busy) { view.toast("Wait until the update finishes"); return; }
        work.submit(() -> {
            try {
                Launcher.delete(settings, i);
                ui(() -> {
                    if (settings.selected.equals(i.id)) settings.selected = Launcher.DEFAULT_ID;
                    view.focusedId = settings.selected;
                    Launcher.saveSettings(settings);
                    reloadInstances();
                    view.toast("Deleted " + i.name);
                });
            } catch (Exception e) {
                fail("Couldn't delete it", e);
            }
        });
    }

    @Override public void instanceChanged(Launcher.Instance i) {
        Launcher.check(i, view.release);
        try {
            Launcher.save(i);
        } catch (Exception e) {
            fail("Couldn't save the change", e);
        }
    }

    @Override public void copySettings(Launcher.Instance to, String from) {
        work.submit(() -> {
            try {
                Launcher.copySettings(settings, view.instances, from, to);
                String src = "vanilla".equals(from) ? "Minecraft" : from;
                for (Launcher.Instance x : view.instances) if (x.id.equals(from)) src = x.name;
                String s = src;
                ui(() -> view.toast("Copied settings from " + s + " to " + to.name));
            } catch (Exception e) {
                fail("Couldn't copy the settings", e);
            }
        });
    }

    // ------------------------------------------------------------------ mods

    @Override public void showMods(Launcher.Instance i) {
        if (i == null) return;
        work.submit(() -> {
            List<Mods.Entry> list = Mods.list(i.dir);
            var projects = Mods.installedProjects(i.dir);
            ui(() -> {
                if (view.selected() != i) return;
                view.mods = list;
                view.installedProjects = projects;
                i.modCount = (int) list.stream().filter(Mods.Entry::enabled).count();
            });
        });
    }

    @Override public void toggleMod(Mods.Entry e) {
        Launcher.Instance i = view.selected();
        work.submit(() -> {
            try {
                Mods.setEnabled(e, !e.enabled());
                ui(() -> view.toast(e.name() + (e.enabled() ? " switched off" : " switched on")));
            } catch (Exception ex) {
                fail("Couldn't switch " + e.name(), ex);
            }
            showMods(i);
        });
    }

    @Override public void removeMod(Mods.Entry e) {
        Launcher.Instance i = view.selected();
        work.submit(() -> {
            try {
                Mods.remove(i.dir, e);
                ui(() -> view.toast("Removed " + e.name()));
            } catch (Exception ex) {
                fail("Couldn't remove " + e.name(), ex);
            }
            showMods(i);
        });
    }

    @Override public void search(String query, boolean more) {
        int seq = searchSeq.incrementAndGet();
        String q = query == null ? "" : query.strip();
        int offset = more ? view.hits.size() : 0;
        view.searching = true;
        view.searchError = null;
        if (!more) {
            view.lastQuery = q;
            view.resetScroll("browse");
        }
        view.repaint();
        work.submit(() -> {
            try {
                Mods.Page page = Mods.search(core, q, offset);
                ui(() -> {
                    if (seq != searchSeq.get()) return;
                    view.searching = false;
                    List<Mods.Hit> l = new ArrayList<>(more ? view.hits : List.of());
                    l.addAll(page.hits());
                    view.hits = l;
                    view.hitsTotal = page.total();
                });
            } catch (Exception e) {
                ui(() -> {
                    if (seq != searchSeq.get()) return;
                    view.searching = false;
                    if (!more) view.hits = List.of();
                    view.searchError = e.getMessage() != null ? e.getMessage() : "Couldn't reach Modrinth";
                });
            }
        });
    }

    @Override public void installMod(Mods.Hit h) {
        Launcher.Instance i = view.selected();
        if (i == null) return;
        view.modState.put(h.projectId(), "installing");
        view.modProgress = 0;
        work.submit(() -> {
            try {
                List<String> names = Mods.install(core, i.dir, h, (f, m) -> ui(() -> view.modProgress = f));
                ui(() -> {
                    view.modState.put(h.projectId(), "installed");
                    view.toast("Installed " + h.title() + (names.size() > 1 ? " and " + (names.size() - 1) + " librar" + (names.size() == 2 ? "y" : "ies") : "") + " in " + i.name);
                });
            } catch (Exception e) {
                String msg = EvadeInstaller.friendly(e);
                ui(() -> view.modState.put(h.projectId(), msg));
            }
            showMods(i);
        });
    }

    // ------------------------------------------------------------------ settings & system

    @Override public void settingsChanged() {
        Launcher.saveSettings(settings);
    }

    @Override public void changeMinecraftDir() {
        JFileChooser fc = new JFileChooser(settings.minecraftDir().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose your .minecraft folder");
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        settings.minecraftDir = p.equals(InstallerCore.defaultMinecraftDir()) ? null : p.toString();
        settings.selected = Launcher.DEFAULT_ID;
        Launcher.saveSettings(settings);
        reloadInstances();
        view.toast("Minecraft folder: " + p);
    }

    @Override public void browseJava() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(InstallerCore.WINDOWS ? "Choose javaw.exe" : "Choose the java program");
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        settings.javaPath = fc.getSelectedFile().getAbsolutePath();
        view.setJavaText(settings.javaPath);
        Launcher.saveSettings(settings);
        view.repaint();
    }

    @Override public void openFolder(Path p) {
        work.submit(() -> {
            try {
                Files.createDirectories(p);
            } catch (Exception ignored) { }
            if (!EvadeInstaller.openPath(p.toString())) ui(() -> view.toast("Couldn't open " + p));
        });
    }

    @Override public void openUrl(String url) {
        work.submit(() -> {
            if (!EvadeInstaller.openPath(url)) ui(() -> view.toast("Open " + url + " in your browser"));
        });
    }

    @Override public void openClips() {
        work.submit(() -> {
            if (!InstallerCore.openClips()) ui(() -> view.toast("Couldn't start Evade Clips"));
            else ui(() -> view.toast("Opening Evade Clips…"));
        });
    }

    @Override public void close() {
        if (busy) {
            view.toast("Wait a moment: the update is still running");
            return;
        }
        System.exit(0);
    }
}
