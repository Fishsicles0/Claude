package net.evadeclient.installer;

import net.evadeclient.clips.ui.App;
import net.evadeclient.clips.ui.ClipsPages;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the launcher window to the model: loads settings and instances, checks for updates and news in the
 * background, and carries out what the player clicks. Long work runs off the UI thread; results come back with
 * invokeLater. Play = bring the instance up to date if needed, put the player's servers in its Multiplayer list,
 * make its profile the most recently used one, then open the official Minecraft Launcher (which does the sign-in
 * and runs the game).
 * <p>
 * Evade Clips runs inside the same program ({@link #clips}): its pages are the Clips section, and when the window is
 * closed while instant replay is on, the program stays in the system tray (Open Evade, Save clip, Quit).
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
    /** The built-in Evade Clips, or null when it couldn't start ({@link LauncherView#clipsNote} says why). */
    private final App clips;
    private ClipsPages clipsPages;
    private JFrame frame;
    private TrayIcon tray;
    private volatile boolean busy;
    private boolean started, ffmpegOffered;

    LauncherApp(App clips, String clipsNote) {
        this.clips = clips;
        view.clips = clips;
        view.clipsNote = clipsNote;
        view.actions = this;
        view.settings = settings;
        view.version = EvadeInstaller.launcherVersion();
        view.setupMemory = settings.defaultMemoryGb;
        view.setJavaText(settings.javaPath);
        view.legacyClips = AppHost.legacyAppInstalled();
        if (clips != null) {
            clips.onNotice((title, body, error) -> SwingUtilities.invokeLater(() -> view.notice(title, body, error)));
            clips.engine.onStatus(st -> SwingUtilities.invokeLater(view::repaint));
        }
    }

    /** Starts the background work (versions, news, player, servers). Call once, on the UI thread. */
    void startBackground() {
        if (started) return;
        started = true;
        reloadInstances();
        view.news = News.cached();
        retryRelease();
        loadNews();
        loadProfile();
        loadServerIcons();
        refreshServers();
        if (view.legacyClips && !settings.legacyClipsHandled)
            view.notice("Evade Clips is built in now", "The separate Evade Clips app isn't needed any more. Remove it in Settings.", false);
    }

    // ------------------------------------------------------------------ window and tray

    /** Shows the window (creating it the first time), restored and in front. */
    void showWindow() {
        if (frame == null) createFrame();
        frame.setVisible(true);
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        frame.setAlwaysOnTop(true);
        frame.toFront();
        frame.requestFocus();
        frame.setAlwaysOnTop(false);
        view.repaint();
    }

    /** Opens the window on the Clips section. */
    void showClips() {
        showWindow();
        view.showPage(LauncherView.Page.CLIPS);
        pageShown(LauncherView.Page.CLIPS);
    }

    private void createFrame() {
        startBackground();
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
        if (clips != null) {
            clipsPages = new ClipsPages(clips);
            clipsPages.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            clipsPages.setVisible(false);
            clipsPages.onPageChanged(p -> { view.clipsPage = p; view.repaint(); });
            view.clipsHost = clipsPages;
            view.add(clipsPages);
            clips.window = frame;
            clips.openEditor = c -> SwingUtilities.invokeLater(() -> {
                if (c == null) { clipsPages.editor.close(); clipsPage("library"); return; }
                showClips();
                clipsPages.editor.open(c);
                clipsPage("editor");
            });
        }
        // Ctrl+1 … Ctrl+4 open Clips' Library, Record, Editor and Settings (as in the Clips app).
        var im = frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = frame.getRootPane().getActionMap();
        String[] ids = {"library", "record", "editor", "settings"};
        int mask = net.evadeclient.clips.core.Ffmpeg.MAC ? java.awt.event.InputEvent.META_DOWN_MASK : java.awt.event.InputEvent.CTRL_DOWN_MASK;
        for (int k = 0; k < ids.length; k++) {
            String id = ids[k];
            im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_1 + k, mask), "clips-" + id);
            am.put("clips-" + id, new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (clipsPages == null || (id.equals("editor") && clipsPages.editor.clip() == null)) return;
                    view.showPage(LauncherView.Page.CLIPS);
                    clipsPage(id);
                }
            });
        }
        view.setUiScale(screenScale());
        frame.pack();
        frame.setResizable(false);
        if (!perPixel) {
            float k = view.uiScale;
            try { frame.setShape(new RoundRectangle2D.Double(0, 0, LauncherView.W * k, LauncherView.H * k, 22 * k, 22 * k)); } catch (Exception ignored) { }
        }
        frame.setIconImages(List.of(LauncherView.image("/evade/icon-128.png")));
        frame.setLocationRelativeTo(null);
    }

    /**
     * How big the window can be drawn: it fits the usable area of the screen (its bounds minus the taskbar), e.g.
     * 1366×768 at 125% leaves about 1092×582. {@code -Devade.uiScale=0.8} forces a scale.
     */
    private static float screenScale() {
        String forced = System.getProperty("evade.uiScale");
        if (forced != null) {
            try { return Math.max(0.5f, Math.min(1f, Float.parseFloat(forced))); } catch (NumberFormatException ignored) { }
        }
        try {
            java.awt.GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            java.awt.Rectangle b = gc.getBounds();
            java.awt.Insets in = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
            return LauncherView.fitScale(b.width - in.left - in.right, b.height - in.top - in.bottom);
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    private static boolean perPixelTranslucency() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The tray icon: Open Evade, Save clip, Quit. Clicking the icon opens the window. */
    void installTray() {
        if (clips == null || !SystemTray.isSupported()) return;
        Image icon = LauncherView.image("/evade/icon-128.png");
        PopupMenu m = new PopupMenu();
        MenuItem open = new MenuItem("Open Evade");
        open.addActionListener(e -> SwingUtilities.invokeLater(this::showWindow));
        MenuItem save = new MenuItem("Save clip");
        save.addActionListener(e -> saveClip());
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> SwingUtilities.invokeLater(this::quit));
        m.add(open);
        m.addSeparator();
        m.add(save);
        m.addSeparator();
        m.add(quit);
        tray = new TrayIcon(icon, "Evade", m);
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> SwingUtilities.invokeLater(this::showWindow));
        try { SystemTray.getSystemTray().add(tray); } catch (AWTException | RuntimeException e) { tray = null; }
    }

    /** Whether closing the window leaves the program running in the tray (instant replay on, tray available). */
    private boolean keepsRunning() {
        return clips != null && tray != null && clips.settings.replayEnabled && clips.settings.minimizeToTray;
    }

    /** Closes the window: to the tray while instant replay is on, else the program quits. */
    private void hideWindow() {
        if (!keepsRunning()) { quit(); return; }
        if (frame != null) frame.setVisible(false);
        if (clipsPages != null) clipsPages.hidden();
        view.clipsPage = "library";
        if (clips != null && !clips.settings.shownTrayHint) {
            tray.displayMessage("Evade is still running", "Instant replay keeps going. Right-click the tray icon to quit.", TrayIcon.MessageType.INFO);
            clips.settings.shownTrayHint = true;
            clips.settings.save();
        }
    }

    /** Quits the whole program (Clips saves what it's recording first). */
    void quit() {
        if (busy) {
            showWindow();
            view.toast("Wait a moment: the update is still running");
            return;
        }
        if (frame != null) frame.setVisible(false);
        if (tray != null) try { SystemTray.getSystemTray().remove(tray); } catch (RuntimeException ignored) { }
        new Thread(() -> {
            if (clips != null) clips.shutdown();
            System.exit(0);
        }, "quit").start();
    }

    // ------------------------------------------------------------------ loading

    private void reloadInstances() {
        List<Launcher.Instance> list = Launcher.listInstances(settings);
        for (Launcher.Instance i : list) Launcher.check(i, view.release);
        view.instances = list;
        if (list.stream().noneMatch(i -> i.id.equals(settings.selected))) settings.selected = Launcher.DEFAULT_ID;
        if (list.stream().noneMatch(i -> i.id.equals(view.focusedId))) view.focusedId = settings.selected;
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
                    for (Launcher.Instance i : view.instances) {
                        Launcher.check(i, r);
                        if (i.installed != null && i.pending != null) view.notice("Update ready for " + i.name, i.pending + ". Press Play to update.", false);
                    }
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
                loadNewsImages(n);
            } catch (Exception e) {
                ui(() -> { view.newsError = e.getMessage(); view.newsLoading = false; });
            }
        });
    }

    /** Pictures named by news items (only the three shown), fetched with a short timeout. */
    private void loadNewsImages(List<News.Entry> n) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(4))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        for (int i = 0; i < Math.min(3, n.size()); i++) {
            String url = n.get(i).image();
            if (url == null) continue;
            try {
                var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(6))
                        .header("user-agent", "EvadeLauncher").build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200 || res.body().length > 4_000_000) continue;
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(res.body()));
                if (img != null) ui(() -> view.newsImages.put(url, img));
            } catch (Exception ignored) { /* the card shows an Evade picture instead */ }
        }
    }

    /** The Minecraft Launcher's signed-in player (name and skin face) for the profile card. */
    private void loadProfile() {
        Path mc = settings.minecraftDir();
        work.submit(() -> {
            Profile.Account a = Profile.read(mc);
            ui(() -> {
                view.playerName = a != null ? a.name() : null;
                view.playerUuid = a != null ? a.uuid() : null;
                view.playerFace = null;
            });
            if (a == null) return;
            BufferedImage face = Profile.face(a.uuid());
            if (face != null) ui(() -> { if (a.uuid().equals(view.playerUuid)) view.playerFace = face; });
        });
    }

    void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> {
            r.run();
            view.repaint();
        });
    }

    private void fail(String what, Exception e) {
        String msg = EvadeInstaller.friendly(e);
        ui(() -> view.toast(what + ": " + msg));
    }

    // ------------------------------------------------------------------ servers

    private static Path iconFile(String address) {
        String name;
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(ServersDat.key(address).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            name = java.util.HexFormat.of().formatHex(d);
        } catch (Exception e) {
            name = Integer.toHexString(ServersDat.key(address).hashCode());
        }
        return Launcher.dataDir().resolve("server-icons").resolve(name + ".png");
    }

    /** Icons saved from earlier pings, so the row has pictures before (or without) a network answer. */
    private void loadServerIcons() {
        List<Launcher.Server> list = List.copyOf(settings.servers);
        work.submit(() -> {
            for (Launcher.Server s : list) {
                try {
                    Path f = iconFile(s.address());
                    if (!Files.exists(f)) continue;
                    BufferedImage img = ImageIO.read(f.toFile());
                    String k = ServersDat.key(s.address());
                    if (img != null) ui(() -> view.serverIcons.putIfAbsent(k, img));
                } catch (Exception ignored) { }
            }
        });
    }

    /** Pings every server (3 s timeout each, all at once) for its icon, players and MOTD. */
    @Override public void refreshServers() {
        for (Launcher.Server s : List.copyOf(settings.servers)) ping(s.address());
    }

    private void ping(String address) {
        String k = ServersDat.key(address);
        if (!view.serverPinging.add(k)) return;
        view.repaint();
        work.submit(() -> {
            try {
                ServerPing.Status st = ServerPing.ping(address);
                BufferedImage icon = null;
                if (st.favicon() != null) {
                    try {
                        icon = ImageIO.read(new java.io.ByteArrayInputStream(st.favicon()));
                        if (icon != null) {
                            Path f = iconFile(address);
                            Files.createDirectories(f.getParent());
                            Files.write(f, st.favicon());
                        }
                    } catch (Exception ignored) { }
                }
                BufferedImage ic = icon;
                ui(() -> {
                    view.serverPinging.remove(k);
                    view.serverDown.remove(k);
                    view.serverStatus.put(k, st);
                    if (ic != null) view.serverIcons.put(k, ic);
                });
            } catch (Exception e) {
                ui(() -> {
                    view.serverPinging.remove(k);
                    view.serverStatus.remove(k);
                    view.serverDown.add(k);
                });
            }
        });
    }

    @Override public void saveServer(int index, String name, String address) {
        String addr = address.strip();
        String k = ServersDat.key(addr);
        for (int i = 0; i < settings.servers.size(); i++) {
            if (i != index && ServersDat.key(settings.servers.get(i).address()).equals(k)) {
                view.toast(settings.servers.get(i).name() + " is already in your servers");
                return;
            }
        }
        Launcher.Server sv = new Launcher.Server(name.strip().isEmpty() ? addr : name.strip(), addr);
        if (index >= 0 && index < settings.servers.size()) settings.servers.set(index, sv);
        else if (settings.servers.size() < Launcher.MAX_SERVERS) settings.servers.add(sv);
        else return;
        Launcher.saveSettings(settings);
        ping(addr);
        syncServersEverywhere();
        view.toast(index >= 0 ? "Saved " + sv.name() : "Added " + sv.name() + ". It's in your Multiplayer list too.");
    }

    @Override public void removeServer(int index) {
        if (index < 0 || index >= settings.servers.size()) return;
        Launcher.Server sv = settings.servers.remove(index);
        if (settings.servers.isEmpty()) view.serversEditing = false;
        Launcher.saveSettings(settings);
        syncServersEverywhere();
        view.toast("Removed " + sv.name());
    }

    /** The launcher's servers as Multiplayer-list entries, with the icons their pings gave. */
    private List<ServersDat.Entry> serverEntries() {
        List<ServersDat.Entry> out = new ArrayList<>();
        for (Launcher.Server s : settings.servers) {
            String icon = null;
            ServerPing.Status st = view.serverStatus.get(ServersDat.key(s.address()));
            try {
                if (st != null && st.favicon() != null) icon = Base64.getEncoder().encodeToString(st.favicon());
                else if (Files.exists(iconFile(s.address()))) icon = Base64.getEncoder().encodeToString(Files.readAllBytes(iconFile(s.address())));
            } catch (Exception ignored) { }
            out.add(new ServersDat.Entry(s.name(), s.address(), icon));
        }
        return out;
    }

    /** Puts the servers in every instance that has been installed (their Multiplayer lists stay in step). */
    private void syncServersEverywhere() {
        List<ServersDat.Entry> entries = serverEntries();
        List<Launcher.Instance> list = List.copyOf(view.instances);
        work.submit(() -> {
            for (Launcher.Instance i : list) {
                if (i.installed == null || !Files.isDirectory(i.dir)) continue;
                try { ServersDat.sync(i.dir, entries); }
                catch (Exception e) { System.err.println("servers.dat for " + i.name + ": " + e.getMessage()); }
            }
        });
    }

    @Override public void copyText(String text) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
            view.toast("Copied " + text);
        } catch (Exception e) {
            view.toast("Couldn't copy: " + text);
        }
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
        runPlay(i, r, update, i.isDefault() && i.installed == null, false);
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
        Launcher.saveSettings(settings);
        view.setup = true;
        runPlay(i, view.release, true, view.setupCopy, true);
    }

    /**
     * Updates the instance if asked (and fetches ffmpeg for Clips the first time), puts the player's servers in its
     * Multiplayer list, writes its profile as the most recently used one, then opens the Minecraft Launcher.
     * {@code setupOnly}: the first install from Home, which ends back on Home instead.
     */
    private void runPlay(Launcher.Instance i, InstallerCore.Release r, boolean update, boolean copySettings, boolean setupOnly) {
        busy = true;
        view.playName = i.name;
        view.progress = 0;
        view.shownProgress = 0;
        view.status = "";
        view.skipped = List.of();
        view.error = "";
        view.play = update ? LauncherView.Play.UPDATING : LauncherView.Play.LAUNCHING;
        view.repaint();
        List<ServersDat.Entry> servers = serverEntries();
        work.submit(() -> {
            List<String> skipped = new ArrayList<>();
            try {
                InstallerCore.Progress p = (f, msg) -> ui(() -> {
                    view.progress = Math.max(view.progress, f);
                    if (msg != null) view.status = msg;
                });
                if (update) {
                    // ffmpeg for the built-in Clips rides along with an install (only fetched once).
                    boolean ffmpeg = settings.clips && clips != null && !net.evadeclient.clips.core.Ffmpeg.available();
                    skipped.addAll(core.install(Launcher.options(settings, i, copySettings, ffmpeg, true), r, p));
                    i.dirty = false;
                    if (ffmpeg && net.evadeclient.clips.core.Ffmpeg.available()) {
                        net.evadeclient.clips.core.Ffmpeg.configure(clips.settings.ffmpegPath);
                        clips.fireFfmpegChanged();
                    }
                }
                // The player's servers at the top of this instance's Multiplayer list.
                if (!servers.isEmpty() || Files.exists(i.dir.resolve(ServersDat.OWNED_FILE))) {
                    try {
                        Files.createDirectories(i.dir);
                        ServersDat.sync(i.dir, servers);
                    } catch (Exception e) {
                        System.err.println("servers.dat: " + e.getMessage());
                        skipped.add("Your servers weren't added to the Multiplayer list");
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
                        view.toast(skipped.isEmpty() ? "Evade Client is installed. Press Play!" : "Installed. " + skipped.size() + " extra" + (skipped.size() == 1 ? " was" : "s were") + " skipped: " + skipped.get(0));
                        view.notice("Evade Client is installed", "Press Play to start it", false);
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
                        javax.swing.Timer t = new javax.swing.Timer(2500, e -> { if (view.play == LauncherView.Play.LAUNCHED) closeAfterPlay(); });
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

    /** "Hide the launcher on Play": to the tray when Clips keeps running there, else the program ends. */
    private void closeAfterPlay() {
        if (keepsRunning()) {
            view.play = LauncherView.Play.IDLE;
            hideWindow();
        } else quit();
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
        loadProfile();
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

    // ------------------------------------------------------------------ clips

    @Override public void clipsPage(String id) {
        if (clipsPages == null) return;
        clipsPages.show(id);
        view.clipsPage = clipsPages.page();
        view.repaint();
    }

    @Override public void pageShown(LauncherView.Page p) {
        if (p != LauncherView.Page.CLIPS || clips == null || frame == null) return;
        if (clipsPages != null) SwingUtilities.invokeLater(() -> { if (clipsPages.isShowing()) clipsPages.focusLibrary(); });
        // Windows without ffmpeg: offer it once (Clips can't record or edit without it).
        if (InstallerCore.WINDOWS && !ffmpegOffered && !clips.ffmpegDownloading && !net.evadeclient.clips.core.Ffmpeg.available()) {
            ffmpegOffered = true;
            SwingUtilities.invokeLater(() -> net.evadeclient.clips.ClipsApp.offerFfmpeg(clips, frame));
        }
    }

    @Override public void setReplay(boolean on) {
        if (clips == null) return;
        clips.toggleReplay(on);
        view.repaint();
    }

    @Override public void saveClip() {
        if (clips != null) clips.saveClip(0, null, null, null);
    }

    @Override public void noticesSeen() { view.repaint(); }

    @Override public void removeLegacyClips() {
        view.toast("Removing the old Evade Clips app…");
        work.submit(() -> {
            String result = AppHost.removeLegacyApp();
            ui(() -> {
                settings.legacyClipsHandled = true;
                Launcher.saveSettings(settings);
                view.legacyClips = AppHost.legacyAppInstalled();
                view.toast(result);
            });
        });
    }

    @Override public void sidebarToggled() {
        Launcher.saveSettings(settings);
    }

    // ------------------------------------------------------------------ close

    @Override public void close() {
        if (busy) {
            view.toast("Wait a moment: the update is still running");
            return;
        }
        hideWindow();
    }
}
