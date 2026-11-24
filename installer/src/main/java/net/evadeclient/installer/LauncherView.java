package net.evadeclient.installer;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Evade Launcher's window, painted by hand in the client's black and red look: a sidebar with four pages (Home,
 * Instances, Mods, Settings). All state lives in plain fields that {@link LauncherApp} fills in and the actions it
 * implements; the screenshot mode fills them with sample data instead.
 */
public final class LauncherView extends JPanel {
    public static final int W = 1180, H = 720;
    /** Sidebar width, expanded and collapsed to icons. */
    static final int SIDE_OPEN = 212, SIDE_SHUT = 72;
    /** The sidebar's width as drawn this frame (it slides between the two), and the content area right of it. */
    int SIDE = SIDE_OPEN, CX = SIDE_OPEN + 26, CW = W - CX - 26;

    enum Page { HOME, INSTANCES, MODS, CLIPS, SETTINGS }

    /** Something the launcher told the player (the bell lists them). */
    record Notice(String title, String body, long at, boolean error) {}

    /** Where Play is: updating the instance, opening the Minecraft Launcher, waiting for the player there, or stuck. */
    enum Play { IDLE, UPDATING, LAUNCHING, LAUNCHED, NO_LAUNCHER, ERROR }

    // palette
    static final Color BG = new Color(0x0A0A0C), CARD = new Color(0x111115), CARD_2 = new Color(0x17171C),
            BORDER = new Color(0x24242C), RED = new Color(0xE11D2E), RED_HI = new Color(0xFF4153),
            TEXT = new Color(0xF4F4F6), DIM = new Color(0x9B9BA6), FAINT = new Color(0x5E5E69), OK = new Color(0x3DDC84),
            AMBER = new Color(0xF5A524), FIELD = new Color(0x0C0C0F);

    /** What the launcher does when the player clicks; {@link LauncherApp} implements these. */
    interface Actions {
        default void play() {}
        default void setupInstall() {}
        default void retryRelease() {}
        default void select(Launcher.Instance i) {}
        default void createInstance(String name, String copyFrom) {}
        default void rename(Launcher.Instance i, String name) {}
        default void duplicate(Launcher.Instance i) {}
        default void delete(Launcher.Instance i) {}
        default void instanceChanged(Launcher.Instance i) {}
        default void copySettings(Launcher.Instance to, String from) {}
        default void showMods(Launcher.Instance i) {}
        default void toggleMod(Mods.Entry e) {}
        default void removeMod(Mods.Entry e) {}
        default void search(String query, boolean more) {}
        default void installMod(Mods.Hit h) {}
        default void settingsChanged() {}
        default void changeMinecraftDir() {}
        default void browseJava() {}
        default void openFolder(Path p) {}
        default void openUrl(String url) {}
        default void openClips() {}
        default void installClips() {}
        default void openLauncherAgain() {}
        default void close() { System.exit(0); }
        default void sidebarToggled() {}
        default void saveServer(int index, String name, String address) {}
        default void removeServer(int index) {}
        default void refreshServers() {}
        default void copyText(String text) {}
        default void clipsPage(String id) {}
        default void setReplay(boolean on) {}
        default void saveClip() {}
        default void noticesSeen() {}
        default void removeLegacyClips() {}
        default void pageShown(Page p) {}
    }

    // ------------------------------------------------------------------ links (empty = not shown)
    static final String DISCORD = "", YOUTUBE = "", TIKTOK = "";

    private final Font inter, interBold;
    private final Image mark, word;

    // ------------------------------------------------------------------ state (EDT only)
    Actions actions = new Actions() {};
    Page page = Page.HOME;
    Launcher.Settings settings = new Launcher.Settings();
    List<Launcher.Instance> instances = new ArrayList<>();
    /** The instance shown on the Instances page. */
    String focusedId = Launcher.DEFAULT_ID;
    InstallerCore.Release release;
    String releaseError;
    List<News.Entry> news = List.of();
    String newsError;
    boolean newsLoading = true;
    /** The built-in Evade Clips (null when it couldn't start, e.g. the old separate app holds its folder). */
    net.evadeclient.clips.ui.App clips;
    /** The Clips pages, shown inside the Clips section (null until the window exists). */
    javax.swing.JComponent clipsHost;
    /** Which Clips page is open ("library", "record", "editor", "settings"). */
    String clipsPage = "library";
    /** Why Clips isn't available (shown on its page), or null. */
    String clipsNote;
    /** The old separate Evade Clips app is still installed (Settings offers to remove it). */
    boolean legacyClips;
    String version = "2.0.0";

    // home: player, servers, notices, slides
    String playerName;
    String playerUuid;
    Image playerFace;
    final Map<String, ServerPing.Status> serverStatus = new HashMap<>();
    final Map<String, Image> serverIcons = new HashMap<>();
    /** Addresses being pinged now / that didn't answer. */
    final Set<String> serverPinging = new HashSet<>(), serverDown = new HashSet<>();
    boolean serversEditing;
    final List<Notice> notices = new ArrayList<>();
    int unread;
    /** News pictures fetched from a news item's "image" (url -> picture). */
    final Map<String, Image> newsImages = new HashMap<>();
    private final String[][] slides = {
            {"menu", "EVADE CLIENT", "Your game, sharper", "A clean menu with every module one click away: search, profiles and keybinds in one place."},
            {"hud", "HUD", "A HUD you place yourself", "Drag every element where you want it. FPS, CPS, armour, cooldowns and more, server-safe."},
            {"styles", "STYLES", "Pick the look you like", "Cards, list or compact menus, with themes from classic red to OLED black."},
            {"clips", "EVADE CLIPS · BUILT IN", "Every clutch, saved", "Instant replay runs while you play. Save the last 30 seconds with one key, then edit and share."}};
    /** Slide pictures by name (loaded in the background). */
    final Map<String, Image> heroImages = new java.util.concurrent.ConcurrentHashMap<>();
    private int slide;
    private long slideAt = System.currentTimeMillis();
    private int slideFrom = -1;
    /** Open over the page: "bell", "server:<index>", or null. */
    String overlay;

    Play play = Play.IDLE;
    /** True while the first-run install runs from the Home page's setup card. */
    boolean setup;
    double progress, shownProgress;
    String status = "", error = "";
    List<String> skipped = List.of();
    /** Which instance Play is working on (its name, for the messages). */
    String playName = "";

    // first-run options
    private final int recommendedGb = InstallerCore.recommendedMemory(), totalGb = InstallerCore.totalMemoryGb();
    private final int[] memoryChoices = InstallerCore.memoryOptions();
    int setupMemory = InstallerCore.clampMemory(recommendedGb);
    boolean setupCopy = true, setupVoice = true, setupPerf = true, setupShaders = false;

    // mods page
    boolean browse;
    List<Mods.Entry> mods = List.of();
    List<Mods.Hit> hits = List.of();
    int hitsTotal;
    boolean searching;
    String searchError;
    String lastQuery = "";
    Set<String> installedProjects = new HashSet<>();
    /** Modrinth project id -> "installing", "installed" or an error message. */
    final Map<String, String> modState = new HashMap<>();
    double modProgress;

    // overlays
    private String toast;
    private long toastAt;
    private Popup popup;
    private Modal modal;

    record Popup(String id, int x, int y, int w, List<String[]> items) {}
    record Modal(String kind, String title, String body, String confirm, Launcher.Instance target, Mods.Entry mod, int index) {
        Modal(String kind, String title, String body, String confirm, Launcher.Instance target, Mods.Entry mod) {
            this(kind, title, body, confirm, target, mod, -1);
        }
    }
    private String modalCopyFrom = null;

    /** Set when the window has per-pixel alpha (rounded, anti-aliased corners). */
    boolean roundCorners;

    // input
    private final LinkedHashMap<String, Rectangle> hitMap = new LinkedHashMap<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final Map<String, float[]> scroll = new HashMap<>(); // id -> {target, shown}
    private final Map<String, int[]> scrollAreas = new LinkedHashMap<>(); // id -> {x, y, w, h, contentH}
    private Rectangle clip;
    private String hovered;
    private Point dragFrom;
    private final long start = System.currentTimeMillis();
    private boolean animating;

    final JTextField searchField, modalField, modalField2, javaField;
    private boolean syncingFields;

    // cached layers
    private BufferedImage bgLayer;
    private double bgScale;
    private boolean bgRound;
    private final Map<String, BufferedImage> shadows = new HashMap<>();
    private static final int SHADOW_BLUR = 5, SHADOW_PAD = 3 * SHADOW_BLUR + 2, SHADOW_DY = 10;

    public LauncherView() {
        inter = font("/evade/inter-medium.ttf");
        interBold = font("/evade/inter-bold.ttf");
        mark = image("/evade/mark.png");
        word = image("/evade/word.png");
        setPreferredSize(new java.awt.Dimension(W, H));
        setLayout(null);
        setOpaque(true);

        searchField = field("Search Modrinth for Fabric mods");
        modalField = field("Instance name");
        modalField2 = field("Address");
        javaField = field("");
        add(searchField);
        add(modalField);
        add(modalField2);
        add(javaField);
        modalField2.addActionListener(e -> confirmModal());
        Thread loader = new Thread(() -> {
            for (String n : new String[]{"menu", "hud", "styles", "clips", "settings", "theme"}) {
                Image img = image("/evade/hero/" + n + ".jpg");
                if (img.getWidth(null) > 1) heroImages.put(n, img);
            }
            SwingUtilities.invokeLater(this::repaint);
        }, "hero-images");
        loader.setDaemon(true);
        loader.start();
        searchField.addActionListener(e -> actions.search(searchField.getText(), false));
        Timer debounce = new Timer(450, e -> {
            if (!searchField.getText().strip().equals(lastQuery.strip())) actions.search(searchField.getText(), false);
        });
        debounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(onChange(() -> { if (!syncingFields) debounce.restart(); }));
        modalField.addActionListener(e -> confirmModal());
        javaField.getDocument().addDocumentListener(onChange(() -> {
            if (syncingFields) return;
            settings.javaPath = javaField.getText().strip();
            actions.settingsChanged();
        }));

        AbstractAction escape = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (popup != null) popup = null;
                else if (overlay != null) overlay = null;
                else if (modal != null) { modal = null; requestFocusInWindow(); }
                repaint();
            }
        };
        setFocusable(true);
        for (JComponent c : new JComponent[]{this, searchField, modalField, modalField2, javaField}) {
            c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
            c.getActionMap().put("escape", escape);
        }

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateHover(e.getPoint()); }
            @Override public void mouseExited(MouseEvent e) { hovered = null; setCursor(Cursor.getDefaultCursor()); repaint(); }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.getY() < 44 && hit(e.getPoint()) == null) dragFrom = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { dragFrom = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null) return;
                Window w = SwingUtilities.getWindowAncestor(LauncherView.this);
                if (w != null) w.setLocation(e.getXOnScreen() - dragFrom.x, e.getYOnScreen() - dragFrom.y);
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) click(hit(e.getPoint()));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { wheel(e.getPoint(), e.getPreciseWheelRotation()); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        new Timer(16, e -> {
            shownProgress += (progress - shownProgress) * 0.18;
            boolean scrolling = false;
            for (float[] s : scroll.values()) {
                if (Math.abs(s[0] - s[1]) > 0.5f) { s[1] += (s[0] - s[1]) * 0.3f; scrolling = true; }
                else s[1] = s[0];
            }
            boolean busy = play == Play.UPDATING || play == Play.LAUNCHING || searching || modState.containsValue("installing");
            boolean showing = isShowing();
            if (showing && page == Page.HOME && !firstRun() && System.currentTimeMillis() - slideAt > 8000 && modal == null) nextSlide(1);
            boolean fading = slideFrom >= 0 && System.currentTimeMillis() - slideAt < 700;
            if (!fading) slideFrom = -1;
            if (showing && (animating || scrolling || busy || fading || toast != null || Math.abs(progress - shownProgress) > 1e-4)) repaint();
        }).start();
    }

    private JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.setOpaque(false);
        f.setBorder(new EmptyBorder(0, 0, 0, 0));
        f.setForeground(TEXT);
        f.setCaretColor(RED_HI);
        f.setSelectionColor(new Color(225, 29, 46, 110));
        f.setSelectedTextColor(Color.WHITE);
        f.setFont(inter.deriveFont(13f));
        f.putClientProperty("placeholder", placeholder);
        f.setVisible(false);
        return f;
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    // ------------------------------------------------------------------ model helpers

    Launcher.Instance selected() {
        for (Launcher.Instance i : instances) if (i.id.equals(settings.selected)) return i;
        return instances.isEmpty() ? null : instances.get(0);
    }

    Launcher.Instance focused() {
        for (Launcher.Instance i : instances) if (i.id.equals(focusedId)) return i;
        return selected();
    }

    /** Nothing is installed anywhere yet: Home shows the guided first install. */
    boolean firstRun() {
        if (setup && play != Play.IDLE) return true;
        for (Launcher.Instance i : instances) if (i.installed != null) return false;
        return instances.size() <= 1;
    }

    void toast(String s) {
        toast = s;
        toastAt = System.currentTimeMillis();
        repaint();
    }

    void showPage(Page p) {
        page = p;
        popup = null;
        overlay = null;
        if (p == Page.MODS && selected() != null) actions.showMods(selected());
        actions.pageShown(p);
        repaint();
    }

    // ------------------------------------------------------------------ input

    private String hit(Point p) {
        List<Map.Entry<String, Rectangle>> l = new ArrayList<>(hitMap.entrySet());
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).getValue().contains(p)) return l.get(i).getKey();
        return null;
    }

    private void updateHover(Point p) {
        String was = hovered;
        hovered = hit(p);
        if (!Objects.equals(was, hovered)) repaint();
        boolean hand = hovered != null && !hovered.startsWith("x-") && !hovered.equals("dismiss");
        setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    }

    private void wheel(Point p, double rot) {
        List<Map.Entry<String, int[]>> l = new ArrayList<>(scrollAreas.entrySet());
        for (int i = l.size() - 1; i >= 0; i--) {
            int[] a = l.get(i).getValue();
            if (!new Rectangle(a[0], a[1], a[2], a[3]).contains(p)) continue;
            float[] s = scroll.computeIfAbsent(l.get(i).getKey(), k -> new float[2]);
            s[0] = (float) Math.max(0, Math.min(Math.max(0, a[4] - a[3]), s[0] + rot * 48));
            repaint();
            return;
        }
    }

    private float scrollOf(String id) {
        float[] s = scroll.get(id);
        return s == null ? 0 : s[1];
    }

    void resetScroll(String id) { scroll.remove(id); }

    private void click(String id) {
        if (id == null) return;
        Window w = SwingUtilities.getWindowAncestor(this);
        Launcher.Instance sel = selected(), foc = focused();
        if (popup != null && !id.startsWith("pop:")) {
            popup = null;
            if (id.equals("dismiss")) { repaint(); return; }
        }
        if (overlay != null && !id.startsWith("ov-") && !id.equals("close") && !id.equals("min")) {
            overlay = null;
            if (id.equals("dismiss") || id.equals("bell")) { repaint(); return; }
        }
        switch (id) {
            case "close" -> { if (play != Play.UPDATING) actions.close(); }
            case "min" -> { if (w instanceof java.awt.Frame f) f.setState(java.awt.Frame.ICONIFIED); }
            case "nav-home" -> showPage(Page.HOME);
            case "nav-instances" -> showPage(Page.INSTANCES);
            case "nav-mods" -> showPage(Page.MODS);
            case "nav-clips" -> showPage(Page.CLIPS);
            case "nav-collapse" -> { settings.sidebarCollapsed = !settings.sidebarCollapsed; actions.sidebarToggled(); }
            case "bell" -> { overlay = "bell"; unread = 0; actions.noticesSeen(); }
            case "ov-clear" -> { notices.clear(); unread = 0; overlay = null; actions.noticesSeen(); }
            case "servers-edit" -> serversEditing = !serversEditing;
            case "servers-refresh" -> actions.refreshServers();
            case "ov-copy" -> { Launcher.Server sv = overlayServer(); if (sv != null) { actions.copyText(sv.address()); overlay = null; } }
            case "ov-edit" -> { int i = overlayIndex(); overlay = null; if (i >= 0) openServerModal(i); }
            case "ov-remove" -> { int i = overlayIndex(); overlay = null; if (i >= 0) actions.removeServer(i); }
            case "clips-replay" -> { if (clips != null) actions.setReplay(!clips.settings.replayEnabled); }
            case "clips-save" -> actions.saveClip();
            case "clips-go" -> { showPage(Page.CLIPS); actions.clipsPage("library"); }
            case "clips-legacy" -> actions.removeLegacyClips();
            case "slide-prev" -> nextSlide(-1);
            case "slide-next" -> nextSlide(1);
            case "link-site" -> actions.openUrl(InstallerCore.SITE);
            case "link-docs" -> actions.openUrl(InstallerCore.SITE + "/docs");
            case "link-changelog" -> actions.openUrl(InstallerCore.SITE + "/changelog");
            case "link-discord" -> actions.openUrl(DISCORD);
            case "link-youtube" -> actions.openUrl(YOUTUBE);
            case "link-tiktok" -> actions.openUrl(TIKTOK);
            case "nav-settings" -> showPage(Page.SETTINGS);
            case "side-instance", "home-switch", "mods-instance" -> {
                List<String[]> items = new ArrayList<>();
                for (Launcher.Instance i : instances) items.add(new String[]{"pop:sel:" + i.id, i.name, i.id.equals(settings.selected) ? "✓" : ""});
                Rectangle r = hitMap.get(id);
                int pw = 240, ph = items.size() * 38 + 12;
                int px = id.equals("side-instance") ? r.x : Math.min(W - 28 - pw, r.x);
                int py = id.equals("side-instance") ? r.y - 12 - items.size() * 38 : r.y + r.height + 6;
                if (py + ph > H - 12) py = r.y - 6 - ph;
                popup = new Popup(id, px, Math.max(12, py), pw, items);
            }
            case "play" -> { if (play == Play.IDLE || play == Play.ERROR || play == Play.NO_LAUNCHER) actions.play(); }
            case "play-again" -> actions.openLauncherAgain();
            case "play-done" -> { play = Play.IDLE; skipped = List.of(); }
            case "get-launcher" -> actions.openUrl(Launcher.GET_LAUNCHER);
            case "setup-install" -> {
                if (release != null) actions.setupInstall();
                else if (releaseError != null) actions.retryRelease();
            }
            case "setup-retry" -> actions.setupInstall();
            case "setup-back" -> { play = Play.IDLE; setup = false; error = ""; }
            case "retry-release" -> actions.retryRelease();
            case "setup-folder", "set-folder" -> actions.changeMinecraftDir();
            case "s-copy" -> setupCopy = !setupCopy;
            case "s-voice" -> setupVoice = !setupVoice;
            case "s-perf" -> setupPerf = !setupPerf;
            case "s-shaders" -> setupShaders = !setupShaders;
            case "news-all" -> actions.openUrl(InstallerCore.SITE + "/changelog");
            // instances
            case "inst-new" -> openModal(new Modal("create", "New instance", "A separate game folder with its own mods, settings and worlds, and its own profile in the Minecraft Launcher.", "Create", null, null), "");
            case "inst-use" -> { if (foc != null) actions.select(foc); }
            case "inst-rename" -> { if (foc != null) openModal(new Modal("rename", "Rename instance", "The launcher profile is renamed too.", "Rename", foc, null), foc.name); }
            case "inst-dup" -> { if (foc != null) actions.duplicate(foc); }
            case "inst-del" -> {
                if (foc != null && !foc.isDefault()) openModal(new Modal("delete", "Delete " + foc.name + "?",
                        "Its folder, with its mods, settings and worlds, goes to the " + (InstallerCore.WINDOWS ? "Recycle Bin" : "Trash")
                                + ", and its profile leaves the Minecraft Launcher.", "Delete", foc, null), null);
            }
            case "inst-folder" -> { if (foc != null) actions.openFolder(foc.dir); }
            case "inst-mods" -> { if (foc != null) { actions.select(foc); showPage(Page.MODS); } }
            case "inst-copy" -> {
                if (foc == null) break;
                List<String[]> items = new ArrayList<>();
                items.add(new String[]{"pop:copy:vanilla", "Minecraft (your normal game)", ""});
                for (Launcher.Instance i : instances) if (i != foc) items.add(new String[]{"pop:copy:" + i.id, i.name, ""});
                Rectangle r = hitMap.get(id);
                popup = new Popup(id, r.x, r.y + r.height + 6, Math.max(r.width, 260), items);
            }
            case "inst-perf" -> { if (foc != null) { foc.perf = !foc.perf; foc.dirty = true; actions.instanceChanged(foc); } }
            case "inst-shaders" -> { if (foc != null) { foc.shaders = !foc.shaders; foc.dirty = true; actions.instanceChanged(foc); } }
            case "inst-voice" -> { if (foc != null) { foc.voice = !foc.voice; foc.dirty = true; actions.instanceChanged(foc); } }
            // mods
            case "tab-installed" -> { browse = false; actions.showMods(sel); }
            case "tab-browse" -> {
                browse = true;
                if (hits.isEmpty() && !searching) actions.search(searchField.getText(), false);
            }
            case "mods-folder" -> { if (sel != null) actions.openFolder(sel.dir.resolve("mods")); }
            case "mods-more" -> { if (!searching) actions.search(lastQuery, true); }
            case "search-retry" -> actions.search(searchField.getText(), false);
            // settings
            case "set-close" -> { settings.closeOnPlay = !settings.closeOnPlay; actions.settingsChanged(); }
            case "set-updates" -> { settings.checkUpdates = !settings.checkUpdates; actions.settingsChanged(); }
            case "set-replay" -> { if (clips != null) actions.setReplay(!clips.settings.replayEnabled); }
            case "set-tray" -> { if (clips != null) { clips.settings.minimizeToTray = !clips.settings.minimizeToTray; clips.settings.save(); } }
            case "java-browse" -> actions.browseJava();
            case "java-clear" -> { setJavaText(""); settings.javaPath = ""; actions.settingsChanged(); }
            case "open-mc" -> actions.openFolder(settings.minecraftDir());
            case "open-inst" -> { if (sel != null) actions.openFolder(sel.dir); }
            case "open-clips" -> actions.openFolder(clips != null ? clips.library.root() : InstallerCore.clipsDataDir());
            case "open-data" -> actions.openFolder(Launcher.dataDir());
            case "site" -> actions.openUrl(InstallerCore.SITE);
            // modal
            case "modal-ok" -> confirmModal();
            case "modal-cancel" -> modal = null;
            case "modal-copy" -> {
                List<String[]> items = new ArrayList<>();
                items.add(new String[]{"pop:mcopy:none", "Nothing (fresh settings)", modalCopyFrom == null ? "✓" : ""});
                items.add(new String[]{"pop:mcopy:vanilla", "Minecraft (your normal game)", "vanilla".equals(modalCopyFrom) ? "✓" : ""});
                for (Launcher.Instance i : instances) items.add(new String[]{"pop:mcopy:" + i.id, i.name, i.id.equals(modalCopyFrom) ? "✓" : ""});
                Rectangle r = hitMap.get(id);
                popup = new Popup(id, r.x, r.y + r.height + 6, r.width, items);
            }
            default -> {
                if (id.startsWith("pop:")) popupClick(id.substring(4));
                else if (id.startsWith("mem:")) {
                    int gb = InstallerCore.clampMemory(Integer.parseInt(id.substring(4)));
                    if (page == Page.HOME) setupMemory = gb;
                    else if (page == Page.SETTINGS) { settings.defaultMemoryGb = gb; actions.settingsChanged(); }
                    else if (foc != null && foc.memoryGb != gb) { foc.memoryGb = gb; actions.instanceChanged(foc); }
                } else if (id.startsWith("inst:")) {
                    focusedId = id.substring(5);
                } else if (id.startsWith("mod-toggle:")) {
                    Mods.Entry e = modAt(id);
                    if (e != null) actions.toggleMod(e);
                } else if (id.startsWith("mod-remove:")) {
                    Mods.Entry e = modAt(id);
                    if (e != null) openModal(new Modal("remove-mod", "Remove " + e.name() + "?", e.fileName() + " is deleted from this instance's mods folder.", "Remove", null, e), null);
                } else if (id.startsWith("hit-install:")) {
                    int n = Integer.parseInt(id.substring(12));
                    if (n < hits.size()) {
                        Mods.Hit h = hits.get(n);
                        String st = modState.get(h.projectId());
                        if (!"installing".equals(st) && !"installed".equals(st) && !installedProjects.contains(h.projectId())
                                && InstallerCore.optionForSlug(h.slug()) == null) actions.installMod(h);
                    }
                } else if (id.startsWith("server:")) {
                    int n = Integer.parseInt(id.substring(7));
                    if (serversEditing) openServerModal(n);
                    else overlay = "server:" + n;
                } else if (id.startsWith("server-add")) {
                    if (settings.servers.size() < Launcher.MAX_SERVERS) openServerModal(-1);
                } else if (id.startsWith("server-x:")) {
                    actions.removeServer(Integer.parseInt(id.substring(9)));
                } else if (id.startsWith("ctab:")) {
                    String t = id.substring(5);
                    if (!t.equals("editor") || clipsEditorOpen()) actions.clipsPage(t);
                } else if (id.startsWith("dot:")) {
                    int n = Integer.parseInt(id.substring(4));
                    if (n != slide) { slideFrom = slide; slide = n; slideAt = System.currentTimeMillis(); }
                } else if (id.startsWith("news:")) {
                    int n = Integer.parseInt(id.substring(5));
                    if (n < news.size() && news.get(n).url() != null) actions.openUrl(news.get(n).url());
                }
            }
        }
        repaint();
    }

    private Mods.Entry modAt(String id) {
        int n = Integer.parseInt(id.substring(id.indexOf(':') + 1));
        return n < mods.size() ? mods.get(n) : null;
    }

    private void popupClick(String what) {
        Popup p = popup;
        popup = null;
        if (what.startsWith("sel:")) {
            for (Launcher.Instance i : instances) if (i.id.equals(what.substring(4))) actions.select(i);
        } else if (what.startsWith("copy:")) {
            Launcher.Instance foc = focused();
            if (foc != null) actions.copySettings(foc, what.substring(5));
        } else if (what.startsWith("mcopy:")) {
            String v = what.substring(6);
            modalCopyFrom = v.equals("none") ? null : v;
        }
        if (p != null) repaint();
    }

    private int overlayIndex() {
        if (overlay == null || !overlay.startsWith("server:")) return -1;
        int i = Integer.parseInt(overlay.substring(7));
        return i < settings.servers.size() ? i : -1;
    }

    private Launcher.Server overlayServer() {
        int i = overlayIndex();
        return i < 0 ? null : settings.servers.get(i);
    }

    private boolean clipsEditorOpen() {
        return clipsHost instanceof net.evadeclient.clips.ui.ClipsPages p && p.editor.clip() != null;
    }

    void nextSlide(int d) {
        slideFrom = slide;
        slide = Math.floorMod(slide + d, slides.length);
        slideAt = System.currentTimeMillis();
        repaint();
    }

    /** Add (index -1) or edit a server: a name and an address. */
    void openServerModal(int index) {
        Launcher.Server sv = index >= 0 && index < settings.servers.size() ? settings.servers.get(index) : null;
        openModal(new Modal("server", sv == null ? "Add a server" : "Edit server",
                "It shows in your Servers row and at the top of the Multiplayer list in game.", sv == null ? "Add" : "Save", null, null, index),
                sv == null ? "" : sv.name());
        syncingFields = true;
        modalField2.setText(sv == null ? "" : sv.address());
        syncingFields = false;
    }

    void notice(String title, String body, boolean error) {
        notices.add(0, new Notice(title, body == null ? "" : body, System.currentTimeMillis(), error));
        while (notices.size() > 30) notices.remove(notices.size() - 1);
        if (!"bell".equals(overlay)) unread++;
        repaint();
    }

    private void openModal(Modal m, String text) {
        modal = m;
        modalCopyFrom = m.kind().equals("create") ? Launcher.DEFAULT_ID : null;
        if (text != null) {
            syncingFields = true;
            modalField.setText(text);
            syncingFields = false;
            modalField.selectAll();
            SwingUtilities.invokeLater(modalField::requestFocusInWindow);
        }
    }

    private void confirmModal() {
        Modal m = modal;
        if (m == null) return;
        String text = modalField.getText().strip();
        switch (m.kind()) {
            case "create" -> {
                if (text.isEmpty()) return;
                actions.createInstance(text, modalCopyFrom);
            }
            case "rename" -> {
                if (text.isEmpty()) return;
                actions.rename(m.target(), text);
            }
            case "delete" -> actions.delete(m.target());
            case "server" -> {
                String addr = modalField2.getText().strip();
                if (addr.isEmpty() || addr.contains(" ")) { modalField2.requestFocusInWindow(); return; }
                actions.saveServer(m.index(), text.isEmpty() ? addr : text, addr);
            }
            case "remove-mod" -> actions.removeMod(m.mod());
            default -> { }
        }
        modal = null;
        requestFocusInWindow();
        repaint();
    }

    void setJavaText(String s) {
        syncingFields = true;
        javaField.setText(s == null ? "" : s);
        syncingFields = false;
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        hitMap.clear();
        scrollAreas.clear();
        animating = false;
        // The sidebar slides between its two widths; everything right of it follows.
        float open = anim("side-open", !settings.sidebarCollapsed);
        SIDE = Math.round(SIDE_SHUT + (SIDE_OPEN - SIDE_SHUT) * open);
        CX = SIDE + 26;
        CW = W - CX - 26;
        Rectangle search = null, java = null, modalBox = null, clipsBox = null;
        paintBackground(g);
        paintSidebar(g, open);
        switch (page) {
            case HOME -> { if (firstRun()) paintSetup(g); else paintHome(g); }
            case INSTANCES -> paintInstances(g);
            case MODS -> search = paintMods(g);
            case CLIPS -> clipsBox = paintClips(g);
            case SETTINGS -> java = paintSettings(g);
        }
        paintBell(g, W - 114, 12);
        button(g, "min", W - 74, 12, 28, 26, false);
        button(g, "close", W - 42, 12, 28, 26, true);
        if (modal != null) {
            hitMap.clear();
            modalBox = paintModal(g);
        }
        if (overlay != null && modal == null) {
            Rectangle close = hitMap.get("close"), min = hitMap.get("min"), bell = hitMap.get("bell");
            hitMap.clear();
            hitMap.put("dismiss", new Rectangle(0, 0, W, H));
            if (close != null) hitMap.put("close", close);
            if (min != null) hitMap.put("min", min);
            if (bell != null) hitMap.put("bell", bell);
            if (overlay.equals("bell")) paintNotices(g);
            else if (overlayServer() != null) paintServerCard(g, overlayIndex());
            else overlay = null;
        }
        if (popup != null) {
            if (modal == null) {
                // Clicks outside the menu close it (the window buttons still work).
                Rectangle close = hitMap.get("close"), min = hitMap.get("min");
                hitMap.clear();
                hitMap.put("dismiss", new Rectangle(0, 0, W, H));
                if (close != null) hitMap.put("close", close);
                if (min != null) hitMap.put("min", min);
            } else {
                hitMap.put("dismiss", new Rectangle(0, 0, W, H));
            }
            paintPopup(g);
        }
        // Real text fields sit where the painted boxes are; any covered by a menu or dialog hide meanwhile.
        Rectangle pop = popup != null ? new Rectangle(popup.x(), popup.y(), popup.w(), popup.items().size() * 38 + 12) : null;
        place(searchField, modal == null ? search : null, pop);
        place(javaField, modal == null ? java : null, pop);
        place(modalField, modalBox, pop);
        place(modalField2, modalBox == null || !modal.kind().equals("server") ? null
                : new Rectangle(modalBox.x, modalBox.y + 78, modalBox.width, modalBox.height), pop);
        if (clipsHost != null) {
            boolean show = clipsBox != null && modal == null && popup == null && overlay == null;
            if (show && !clipsBox.equals(clipsHost.getBounds())) { clipsHost.setBounds(clipsBox); clipsHost.revalidate(); }
            if (clipsHost.isVisible() != show) clipsHost.setVisible(show);
        }
        g.dispose();
    }

    /** The toast goes over everything, the embedded Clips pages included. */
    @Override protected void paintChildren(Graphics g0) {
        super.paintChildren(g0);
        if (toast == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        paintToast(g);
        g.dispose();
    }

    private void place(JTextField f, Rectangle r, Rectangle covered) {
        boolean show = r != null && (covered == null || !covered.intersects(r));
        if (show && !r.equals(f.getBounds())) f.setBounds(r);
        if (f.isVisible() != show) f.setVisible(show);
    }

    private void paintBackground(Graphics2D g) {
        double scale = g.getTransform().getScaleX();
        if (bgLayer == null || bgScale != scale || bgRound != roundCorners) {
            bgLayer = deviceImage(W, H, scale);
            Graphics2D b = bgLayer.createGraphics();
            b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            b.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            b.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            b.scale(bgLayer.getWidth() / (double) W, bgLayer.getHeight() / (double) H);
            Shape win = roundCorners ? new RoundRectangle2D.Double(0, 0, W, H, 22, 22) : new Rectangle(0, 0, W, H);
            b.setColor(BG);
            b.fill(win);
            b.setPaint(new RadialGradientPaint(760, 70, 480, new float[]{0f, 1f}, new Color[]{new Color(225, 29, 46, 62), new Color(225, 29, 46, 0)}));
            b.fill(win);
            b.setPaint(new RadialGradientPaint(120, 600, 320, new float[]{0f, 1f}, new Color[]{new Color(225, 29, 46, 26), new Color(225, 29, 46, 0)}));
            b.fill(win);
            b.setComposite(AlphaComposite.SrcAtop);
            for (int x = 0; x < W; x += 40) {
                b.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 9), 0, H, new Color(255, 255, 255, 0)));
                b.fillRect(x, 0, 1, H);
            }
            for (int y = 0; y < H; y += 40) {
                b.setColor(new Color(255, 255, 255, Math.max(0, 9 - y / 40)));
                b.fillRect(0, y, W, 1);
            }
            b.setColor(new Color(255, 255, 255, 18));
            b.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, 21, 21));
            b.dispose();
            bgScale = scale;
            bgRound = roundCorners;
        }
        g.drawImage(bgLayer, 0, 0, W, H, null);
        // sidebar panel (its width moves, so it isn't part of the cached layer)
        Shape was = g.getClip();
        if (roundCorners) g.clip(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(0, 0, SIDE, H);
        g.setColor(new Color(255, 255, 255, 14));
        g.fillRect(SIDE, 0, 1, H);
        g.setClip(was);
    }

    private static BufferedImage deviceImage(int w, int h, double scale) {
        return new BufferedImage(Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)), BufferedImage.TYPE_INT_ARGB_PRE);
    }

    /** A soft drop shadow under a card (blurred once per size and scale, then reused). */
    private void shadow(Graphics2D g, int x, int y, int w, int h) {
        double scale = g.getTransform().getScaleX();
        String key = w + "x" + h + "@" + scale;
        BufferedImage img = shadows.get(key);
        int pad = SHADOW_PAD;
        if (img == null) {
            img = deviceImage(w + 2 * pad, h + 2 * pad, scale);
            Graphics2D s = img.createGraphics();
            s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            s.scale(img.getWidth() / (double) (w + 2 * pad), img.getHeight() / (double) (h + 2 * pad));
            s.setColor(Color.BLACK);
            s.fill(new RoundRectangle2D.Double(pad, pad, w, h, 18, 18));
            s.dispose();
            int r = Math.max(1, (int) Math.round(SHADOW_BLUR * scale));
            float[] k = new float[2 * r + 1];
            Arrays.fill(k, 1f / k.length);
            ConvolveOp across = new ConvolveOp(new Kernel(k.length, 1, k), ConvolveOp.EDGE_NO_OP, null);
            ConvolveOp down = new ConvolveOp(new Kernel(1, k.length, k), ConvolveOp.EDGE_NO_OP, null);
            BufferedImage tmp = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
            try {
                for (int i = 0; i < 3; i++) { across.filter(img, tmp); down.filter(tmp, img); }
            } catch (RuntimeException ignored) { }
            if (shadows.size() > 40) shadows.clear();
            shadows.put(key, img);
        }
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.42f));
        g.drawImage(img, x - pad, y + SHADOW_DY - pad, w + 2 * pad, h + 2 * pad, null);
        g.setComposite(was);
    }

    private void card(Graphics2D g, int x, int y, int w, int h, boolean accent) {
        shadow(g, x, y, w, h);
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 18, 18));
        if (accent) {
            g.setColor(RED);
            g.fill(new RoundRectangle2D.Double(x + 24, y, 46, 2, 2, 2));
        }
    }

    // ------------------------------------------------------------------ sidebar

    private void paintSidebar(Graphics2D g, float open) {
        // logo: the mark always, the word mark fading in as the sidebar opens
        int mx = Math.round(17 + 9 * open);
        g.drawImage(mark, mx, 30, 38, 38, null);
        if (open > 0.05f) {
            Composite c0 = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, open * open)));
            Shape was = g.getClip();
            g.clipRect(0, 0, SIDE - 4, H);
            int wh = 44, ww = Math.round(wh * 320f / 96f);
            g.drawImage(word, mx + 42, 30 + (38 - wh) / 2 + 1, ww, wh, null);
            g.setFont(interBold.deriveFont(8.5f));
            g.setColor(FAINT);
            spaced(g, "LAUNCHER", mx + 45, 80, 3.0f);
            g.setClip(was);
            g.setComposite(c0);
        }

        String[][] nav = {{"nav-home", "Home", "home"}, {"nav-instances", "Instances", "layers"}, {"nav-mods", "Mods", "cube"},
                {"nav-clips", "Clips", "clip"}, {"nav-settings", "Settings", "gear"}};
        Page[] pages = {Page.HOME, Page.INSTANCES, Page.MODS, Page.CLIPS, Page.SETTINGS};
        int iw = SIDE - 32;
        String tip = null;
        int tipY = 0;
        for (int i = 0; i < nav.length; i++) {
            int y = 112 + i * 46;
            boolean on = page == pages[i];
            float t = anim(nav[i][0], nav[i][0].equals(hovered));
            float s = anim(nav[i][0] + "-on", on);
            if (s > 0) {
                g.setPaint(new GradientPaint(16, 0, new Color(225, 29, 46, (int) (56 * s)), 16 + iw, 0, new Color(225, 29, 46, (int) (8 * s))));
                g.fill(new RoundRectangle2D.Double(16, y, iw, 40, 11, 11));
                g.setColor(new Color(255, 65, 83, (int) (255 * s)));
                g.fill(new RoundRectangle2D.Double(16, y + 11, 3, 18, 3, 3));
            }
            if (t > 0 && !on) {
                g.setColor(new Color(255, 255, 255, (int) (12 * t)));
                g.fill(new RoundRectangle2D.Double(16, y, iw, 40, 11, 11));
            }
            Color c = mix(mix(DIM, TEXT, t), TEXT, s);
            int ix = Math.round(36 + 8 * open);
            icon(g, nav[i][2], ix, y + 20, 18, on ? RED_HI : c);
            if (pages[i] == Page.CLIPS && clips != null) {
                // live dot: red while recording, green while instant replay runs
                var st = clips.status;
                boolean rec = st.recording(), live = st.state() == net.evadeclient.clips.core.CaptureEngine.State.LIVE;
                if (rec || live) {
                    g.setColor(BG);
                    g.fill(new Ellipse2D.Double(ix + 4, y + 8, 10, 10));
                    g.setColor(rec ? RED_HI : OK);
                    g.fill(new Ellipse2D.Double(ix + 6, y + 10, 6, 6));
                }
            }
            if (open > 0.4f) {
                g.setFont((on ? interBold : inter).deriveFont(13.5f));
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.min(1f, (open - 0.4f) / 0.6f))));
                g.drawString(nav[i][1], ix + 22, y + 25);
            }
            hit(nav[i][0], new Rectangle(16, y, iw, 40));
            if (open < 0.5f && nav[i][0].equals(hovered)) { tip = nav[i][1]; tipY = y + 20; }
        }

        // collapse / expand
        int cy = H - 150;
        float tc = anim("nav-collapse", "nav-collapse".equals(hovered));
        if (tc > 0) {
            g.setColor(new Color(255, 255, 255, (int) (12 * tc)));
            g.fill(new RoundRectangle2D.Double(16, cy, iw, 34, 10, 10));
        }
        int cix = Math.round(36 + 8 * open);
        Graphics2D q = (Graphics2D) g.create();
        q.rotate(open > 0.5f ? Math.PI / 2 : -Math.PI / 2, cix, cy + 17);
        icon(q, "chevron-down", cix, cy + 17, 10, mix(FAINT, TEXT, tc));
        q.dispose();
        if (open > 0.4f) {
            g.setFont(inter.deriveFont(12f));
            g.setColor(mix(FAINT, DIM, tc));
            g.drawString("Collapse", cix + 22, cy + 21);
        }
        hit("nav-collapse", new Rectangle(16, cy, iw, 34));
        if (open < 0.5f && "nav-collapse".equals(hovered)) { tip = "Expand"; tipY = cy + 17; }

        // The instance Play starts, switchable from here.
        Launcher.Instance sel = selected();
        int bx = 16, by = H - 106, bw = iw, bh = 62;
        float t = anim("side-instance", "side-instance".equals(hovered));
        g.setColor(mix(new Color(0x101014), new Color(0x17171D), t));
        g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 13, 13));
        g.setColor(mix(BORDER, new Color(0x34343E), t));
        g.draw(new RoundRectangle2D.Double(bx + .5, by + .5, bw - 1, bh - 1, 13, 13));
        if (open > 0.6f) {
            g.setFont(interBold.deriveFont(8.5f));
            g.setColor(FAINT);
            spaced(g, "PLAYING", bx + 14, by + 21, 1.6f);
            g.setFont(interBold.deriveFont(13.5f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, sel != null ? sel.name : "—", bw - 50), bx + 14, by + 43);
            statusDot(g, bx + bw - 44, by + 38, sel);
            icon(g, "chevron-up", bx + bw - 20, by + bh / 2, 10, DIM);
        } else {
            avatar(g, bx + (bw - 30) / 2, by + 10, 30, sel != null ? sel.name : "?", sel != null && sel.isDefault() ? RED.getRGB() : hashColor(sel != null ? sel.name : ""));
            statusDot(g, bx + bw - 14, by + 50, sel);
        }
        hit("side-instance", new Rectangle(bx, by, bw, bh));
        if (open < 0.5f && "side-instance".equals(hovered)) { tip = "Playing: " + (sel != null ? sel.name : "—"); tipY = by + bh / 2; }

        if (open > 0.6f) {
            g.setFont(inter.deriveFont(11f));
            float ts = anim("site", "site".equals(hovered));
            g.setColor(mix(FAINT, DIM, ts));
            g.drawString("evadeclient.com", 26, H - 22);
            int sw = g.getFontMetrics().stringWidth("evadeclient.com");
            hit("site", new Rectangle(22, H - 36, sw + 8, 20));
            g.setColor(new Color(0x45454F));
            String v = "v" + version;
            g.drawString(v, SIDE - 18 - g.getFontMetrics().stringWidth(v), H - 22);
        }
        if (tip != null) {
            // collapsed: the label as a small bubble next to the icon
            g.setFont(interBold.deriveFont(12f));
            int tw = g.getFontMetrics().stringWidth(tip) + 20;
            int tx = SIDE + 8;
            g.setColor(new Color(0x1C1C22));
            g.fill(new RoundRectangle2D.Double(tx, tipY - 14, tw, 28, 10, 10));
            g.setColor(new Color(255, 255, 255, 26));
            g.draw(new RoundRectangle2D.Double(tx + .5, tipY - 13.5, tw - 1, 27, 10, 10));
            g.setColor(TEXT);
            center(g, tip, tx, tipY - 14, tw, 28);
        }
    }

    /** A small dot: green when the instance is up to date, amber when Play will update it first, grey before the check. */
    private void statusDot(Graphics2D g, int x, int y, Launcher.Instance i) {
        Color c = FAINT;
        if (i != null) {
            if (i.installed == null || i.dirty || i.pending != null) c = AMBER;
            else if (release != null) c = OK;
        }
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
        g.fill(new Ellipse2D.Double(x - 3, y - 7, 12, 12));
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x, y - 4, 6, 6));
    }

    private void pageTitle(Graphics2D g, String title, String sub) {
        g.setFont(interBold.deriveFont(24f));
        g.setColor(TEXT);
        g.drawString(title, CX, 58);
        if (sub != null) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(DIM);
            g.drawString(ellipsizeEnd(g, sub, CW - 260), CX, 80);
        }
    }

    // ------------------------------------------------------------------ home

    private void paintHome(Graphics2D g) {
        Launcher.Instance sel = selected();
        int rw = 248, rx = W - 26 - rw;
        int x = CX, w = rx - 20 - CX;
        // greeting
        g.setFont(interBold.deriveFont(20f));
        g.setColor(TEXT);
        String hello = greeting() + (playerName != null ? ", " + playerName : "");
        g.drawString(ellipsizeEnd(g, hello, w - 120), x, 38);

        paintHero(g, sel, x, 52, w, 300);
        paintServers(g, x, 372, w);
        paintNewsCards(g, x, 502, w, H - 24 - 502);

        paintProfile(g, rx, 52, rw, 136);
        paintClipsCard(g, rx, 204, rw, 168);
        paintVersions(g, sel, rx, 388, rw, 132);
        paintLinks(g, rx, 536, rw, H - 24 - 536);
    }

    private static String greeting() {
        int h = java.time.LocalTime.now().getHour();
        return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
    }

    /** Draws {@code img} filling the box (cropped, not stretched), aligned by {@code ax} (0 left … 1 right). */
    private static void cover(Graphics2D g, Image img, int x, int y, int w, int h, double ax) {
        int iw = img.getWidth(null), ih = img.getHeight(null);
        if (iw <= 0 || ih <= 0) return;
        double s = Math.max(w / (double) iw, h / (double) ih);
        int dw = (int) Math.ceil(iw * s), dh = (int) Math.ceil(ih * s);
        g.drawImage(img, x - (int) Math.round((dw - w) * ax), y - (dh - h) / 2, dw, dh, null);
    }

    private void paintHero(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        shadow(g, x, y, w, h);
        Shape box = new RoundRectangle2D.Double(x, y, w, h, 20, 20);
        g.setPaint(new GradientPaint(x, y, new Color(0x1A0D10), x + w, y + h, new Color(0x0F0F13)));
        g.fill(box);
        Shape was = g.getClip();
        g.clip(box);
        // the slide picture, cross-fading from the previous one
        long age = System.currentTimeMillis() - slideAt;
        float fade = slideFrom >= 0 ? Math.min(1f, age / 600f) : 1f;
        if (slideFrom >= 0 && fade < 1f) {
            Image from = heroImages.get(slides[slideFrom][0]);
            if (from != null) cover(g, from, x, y, w, h, 0.7);
        }
        Image img = heroImages.get(slides[slide][0]);
        if (img != null) {
            Composite c0 = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
            cover(g, img, x, y, w, h, 0.7);
            g.setComposite(c0);
        } else {
            g.setPaint(new RadialGradientPaint(x + w - 150, y + 40, 330, new float[]{0f, 1f}, new Color[]{new Color(225, 29, 46, 85), new Color(225, 29, 46, 0)}));
            g.fill(box);
        }
        // scrims: dark on the left for the text, and along the bottom for the Play bar
        g.setPaint(new GradientPaint(x, 0, new Color(8, 8, 10, 235), x + w * 0.72f, 0, new Color(8, 8, 10, 40)));
        g.fill(box);
        g.setPaint(new GradientPaint(0, y + h - 130, new Color(8, 8, 10, 0), 0, y + h, new Color(8, 8, 10, 235)));
        g.fill(box);
        g.setPaint(new RadialGradientPaint(x + 60, y + h, 360, new float[]{0f, 1f}, new Color[]{new Color(225, 29, 46, 60), new Color(225, 29, 46, 0)}));
        g.fill(box);
        boolean idle = play == Play.IDLE;
        if (!idle) {
            g.setColor(new Color(8, 8, 10, 200));
            g.fill(box);
        }
        g.setClip(was);
        g.setColor(new Color(255, 255, 255, 22));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 20, 20));

        int ix = x + 30;
        switch (play) {
            case UPDATING, LAUNCHING -> paintHeroProgress(g, ix, y, w - 60, h);
            case LAUNCHED -> paintHeroLaunched(g, ix, y, w - 60, h);
            case NO_LAUNCHER -> paintHeroNoLauncher(g, ix, y, w - 60, h);
            case ERROR -> paintHeroError(g, ix, y, w - 60, h);
            default -> paintHeroIdle(g, sel, ix, y, w - 60, h);
        }
    }

    private void paintHeroIdle(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        String[] sl = slides[slide];
        float a = slideFrom >= 0 ? Math.min(1f, (System.currentTimeMillis() - slideAt) / 500f) : 1f;
        Composite c0 = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
        g.setFont(interBold.deriveFont(9.5f));
        g.setColor(RED_HI);
        spaced(g, sl[1], x, y + 40, 2.0f);
        g.setFont(interBold.deriveFont(30f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, sl[2], w - 60), x - 1, y + 78);
        g.setFont(inter.deriveFont(13f));
        g.setColor(new Color(0xC9C9D1));
        int ly = y + 102;
        for (String line : wrapLines(g, sl[3], Math.min(380, w - 60), 2)) { g.drawString(line, x, ly); ly += 19; }
        g.setComposite(c0);

        // slide dots, top right
        int dx = x + w;
        for (int i = slides.length - 1; i >= 0; i--) {
            boolean on = i == slide;
            int dw = on ? 22 : 8;
            dx -= dw;
            g.setColor(on ? RED_HI : new Color(255, 255, 255, 70));
            g.fill(new RoundRectangle2D.Double(dx, y + 30, dw, 8, 8, 8));
            hit("dot:" + i, new Rectangle(dx - 3, y + 24, dw + 6, 20));
            dx -= 6;
        }

        // instance picker
        int by = y + h - 92;
        float t = anim("home-switch", "home-switch".equals(hovered));
        int sw = Math.min(300, w - 250);
        g.setColor(mix(new Color(12, 12, 15, 200), new Color(28, 28, 34, 220), t));
        g.fill(new RoundRectangle2D.Double(x, by, sw, 56, 14, 14));
        g.setColor(mix(new Color(255, 255, 255, 26), new Color(255, 255, 255, 50), t));
        g.draw(new RoundRectangle2D.Double(x + .5, by + .5, sw - 1, 55, 14, 14));
        avatar(g, x + 10, by + 10, 36, sel != null ? sel.name : "?", sel != null && sel.isDefault() ? RED.getRGB() : hashColor(sel != null ? sel.name : ""));
        g.setFont(interBold.deriveFont(8.5f));
        g.setColor(FAINT);
        spaced(g, "INSTANCE", x + 58, by + 22, 1.6f);
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, sel != null ? sel.name : "—", sw - 100), x + 58, by + 42);
        icon(g, "chevron-down", x + sw - 22, by + 28, 10, mix(DIM, TEXT, t));
        hit("home-switch", new Rectangle(x, by, sw, 56));

        // status line under it
        String reason = sel != null ? sel.pending : null;
        String pill;
        Color pc;
        if (sel == null) { pill = "No instance"; pc = FAINT; }
        else if (sel.installed == null) { pill = "Installs when you press Play"; pc = AMBER; }
        else if (release == null && releaseError == null) { pill = "Checking for updates…"; pc = DIM; }
        else if (release == null) { pill = "Couldn't check for updates"; pc = DIM; }
        else if (reason != null) { pill = "Update ready: " + reason; pc = AMBER; }
        else if (sel.dirty) { pill = "Options changed: applied when you press Play"; pc = AMBER; }
        else { pill = "Up to date"; pc = OK; }
        String meta = sel != null && sel.installed != null ? "  ·  Evade v" + sel.installed.evadeVersion() + "  ·  Minecraft " + InstallerCore.MC_VERSION
                + "  ·  " + sel.memoryGb + " GB" : "  ·  Minecraft " + InstallerCore.MC_VERSION;
        g.setFont(inter.deriveFont(11.5f));
        int sy = y + h - 18;
        g.setColor(pc);
        g.fill(new Ellipse2D.Double(x + 1, sy - 7, 6, 6));
        String ps = ellipsizeEnd(g, pill, w - 250);
        g.drawString(ps, x + 13, sy);
        int pw = g.getFontMetrics().stringWidth(ps);
        if (release == null && releaseError != null) {
            float tr = anim("retry-release", "retry-release".equals(hovered));
            g.setColor(mix(DIM, TEXT, tr));
            g.drawString("Retry", x + 13 + pw + 10, sy);
            hit("retry-release", new Rectangle(x + 13 + pw + 4, sy - 15, 46, 22));
        } else {
            g.setColor(FAINT);
            g.drawString(ellipsizeEnd(g, meta, Math.max(0, w - 250 - pw)), x + 13 + pw, sy);
        }

        boolean ready = release != null || (releaseError != null && sel != null && sel.installed != null);
        String label = release == null && releaseError == null ? "Checking…" : sel != null && (sel.installed == null || reason != null || sel.dirty) && release != null ? "Update & Play" : "Play";
        playButton(g, "play", x + w - 210, by - 2, 210, 60, label, ready && sel != null);
    }

    // ------------------------------------------------------------------ home: servers, news, right rail

    private void paintServers(Graphics2D g, int x, int y, int w) {
        label(g, "SERVERS", x, y + 4);
        if (!settings.servers.isEmpty()) {
            textLink(g, "servers-edit", serversEditing ? "Done" : "Edit", x + w, y + 5, 1);
            g.setFont(inter.deriveFont(12f));
            int ew = g.getFontMetrics().stringWidth(serversEditing ? "Done" : "Edit");
            textLink(g, "servers-refresh", "Refresh", x + w - ew - 18, y + 5, 1);
        }
        int n = Launcher.MAX_SERVERS, gap = 10, tw = (w - gap * (n - 1)) / n, ty = y + 16, th = 92;
        boolean addShown = false;
        for (int i = 0; i < n; i++) {
            int tx = x + i * (tw + gap);
            if (i < settings.servers.size()) {
                Launcher.Server sv = settings.servers.get(i);
                String id = "server:" + i;
                float t = anim(id, id.equals(hovered) || id.equals(overlay));
                g.setColor(mix(new Color(0x111115), new Color(0x1A1A20), t));
                g.fill(new RoundRectangle2D.Double(tx, ty, tw, th, 14, 14));
                g.setColor(mix(BORDER, new Color(225, 29, 46, 120), t));
                g.draw(new RoundRectangle2D.Double(tx + .5, ty + .5, tw - 1, th - 1, 14, 14));
                int is = Math.min(46, tw - 20), ix = tx + (tw - is) / 2, iy = ty + 10;
                Image icon = serverIcons.get(ServersDat.key(sv.address()));
                if (icon != null) {
                    Shape was = g.getClip();
                    g.clip(new RoundRectangle2D.Double(ix, iy, is, is, 10, 10));
                    Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(icon, ix, iy, is, is, null);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
                    g.setClip(was);
                } else avatar(g, ix, iy, is, sv.name(), hashColor(sv.address()));
                // online dot
                String k = ServersDat.key(sv.address());
                Color dc = serverStatus.containsKey(k) ? OK : serverDown.contains(k) ? RED_HI : FAINT;
                g.setColor(new Color(0x111115));
                g.fill(new Ellipse2D.Double(ix + is - 9, iy + is - 9, 14, 14));
                g.setColor(dc);
                g.fill(new Ellipse2D.Double(ix + is - 6, iy + is - 6, 8, 8));
                g.setFont(interBold.deriveFont(11f));
                g.setColor(TEXT);
                String nm = ellipsizeEnd(g, sv.name(), tw - 10);
                center(g, nm, tx, ty + th - 30, tw, 14);
                g.setFont(inter.deriveFont(10f));
                g.setColor(FAINT);
                ServerPing.Status st = serverStatus.get(k);
                String sub = st != null ? Mods.count(st.online()) + " online" : serverPinging.contains(k) ? "…" : serverDown.contains(k) ? "offline" : "";
                center(g, ellipsizeEnd(g, sub, tw - 8), tx, ty + th - 16, tw, 12);
                hit(id, new Rectangle(tx, ty, tw, th));
                if (serversEditing) {
                    int bx = tx + tw - 22, by = ty + 4;
                    String xid = "server-x:" + i;
                    float tx2 = anim(xid, xid.equals(hovered));
                    g.setColor(mix(new Color(0x2A2A31), RED, tx2));
                    g.fill(new Ellipse2D.Double(bx, by, 18, 18));
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(bx + 6, by + 6, bx + 12, by + 12));
                    g.draw(new Line2D.Double(bx + 12, by + 6, bx + 6, by + 12));
                    g.setStroke(new BasicStroke(1f));
                    hit(xid, new Rectangle(bx - 2, by - 2, 22, 22));
                }
            } else {
                String id = "server-add:" + i;
                float t = anim(id, id.equals(hovered));
                boolean first = !addShown;
                addShown = true;
                g.setColor(new Color(255, 255, 255, first ? (int) (6 + 10 * t) : (int) (3 + 8 * t)));
                g.fill(new RoundRectangle2D.Double(tx, ty, tw, th, 14, 14));
                g.setColor(mix(new Color(255, 255, 255, first ? 40 : 20), new Color(225, 29, 46, 150), t));
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
                g.draw(new RoundRectangle2D.Double(tx + .5, ty + .5, tw - 1, th - 1, 14, 14));
                g.setStroke(new BasicStroke(1f));
                icon(g, "plus", tx + tw / 2, ty + th / 2 - (first ? 8 : 0), 14, mix(first ? DIM : FAINT, TEXT, t));
                if (first) {
                    g.setFont(inter.deriveFont(10.5f));
                    g.setColor(mix(FAINT, DIM, t));
                    center(g, "Add server", tx, ty + th - 30, tw, 14);
                }
                hit(id, new Rectangle(tx, ty, tw, th));
            }
        }
    }

    /** The card for a server in the Servers row: what its status ping says, and its address to copy. */
    private void paintServerCard(Graphics2D g, int index) {
        Launcher.Server sv = settings.servers.get(index);
        int n = Launcher.MAX_SERVERS, gap = 10;
        int rx = W - 26 - 248, w = rx - 20 - CX, tw = (w - gap * (n - 1)) / n;
        int cw = 300, ch = 196;
        int tx = CX + index * (tw + gap);
        int x = Math.max(CX, Math.min(W - 26 - cw, tx + tw / 2 - cw / 2)), y = 388 - ch - 10;
        card(g, x, y, cw, ch, true);
        String k = ServersDat.key(sv.address());
        Image icon = serverIcons.get(k);
        if (icon != null) {
            Shape was = g.getClip();
            g.clip(new RoundRectangle2D.Double(x + 20, y + 22, 44, 44, 10, 10));
            g.drawImage(icon, x + 20, y + 22, 44, 44, null);
            g.setClip(was);
        } else avatar(g, x + 20, y + 22, 44, sv.name(), hashColor(sv.address()));
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, sv.name(), cw - 100), x + 76, y + 40);
        g.setFont(inter.deriveFont(12f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, sv.address(), cw - 100), x + 76, y + 59);
        ServerPing.Status st = serverStatus.get(k);
        g.setFont(inter.deriveFont(12f));
        int ly = y + 92;
        if (st != null) {
            g.setColor(OK);
            g.fill(new Ellipse2D.Double(x + 20, ly - 8, 7, 7));
            g.setColor(TEXT);
            String line = String.format(java.util.Locale.ROOT, "%,d / %,d online", st.online(), st.max()) + "  ·  " + st.pingMs() + " ms";
            g.drawString(ellipsizeEnd(g, line, cw - 52), x + 33, ly);
            g.setColor(FAINT);
            g.setFont(inter.deriveFont(11f));
            if (!st.version().isBlank()) g.drawString(ellipsizeEnd(g, st.version(), cw - 40), x + 20, ly + 17);
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(DIM);
            int my = ly + 36;
            for (String line2 : wrapLines(g, st.motd().replace('\n', ' '), cw - 40, 2)) { g.drawString(line2, x + 20, my); my += 16; }
        } else {
            boolean down = serverDown.contains(k);
            g.setColor(down ? RED_HI : FAINT);
            g.fill(new Ellipse2D.Double(x + 20, ly - 8, 7, 7));
            g.setColor(DIM);
            g.drawString(serverPinging.contains(k) ? "Checking…" : down ? "Didn't answer (offline, or the address is wrong)" : "Not checked yet", x + 33, ly);
        }
        int by = y + ch - 50;
        smallPrimary(g, "ov-copy", x + 20, by, 112, 32, "Copy address", null);
        ghostButton(g, "ov-edit", x + 140, by, 60, 32, "Edit", false);
        ghostButton(g, "ov-remove", x + 208, by, 72, 32, "Remove", true);
        hitMap.put("ov-card", new Rectangle(x, y, cw, ch));
        // the card's own buttons win over the click-away area
        for (String id : new String[]{"ov-copy", "ov-edit", "ov-remove"}) { Rectangle r = hitMap.remove(id); if (r != null) hitMap.put(id, r); }
    }

    private void paintNewsCards(Graphics2D g, int x, int y, int w, int h) {
        label(g, "NEWS", x, y + 4);
        textLink(g, "news-all", "View all", x + w, y + 5, 1);
        int top = y + 16, ch = h - 16;
        if (news.isEmpty()) {
            card(g, x, top, w, ch, false);
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(newsError != null ? FAINT : DIM);
            g.drawString(ellipsizeEnd(g, newsLoading ? "Loading the latest news…" : newsError != null ? "News couldn't be loaded. " + EvadeInstaller.sentence(newsError) : "No news yet.", w - 48), x + 24, top + 34);
            return;
        }
        int n = Math.min(3, news.size()), gap = 14, cw = (w - gap * (n - 1)) / n;
        String[] fallback = {"menu", "hud", "styles", "clips", "settings", "theme"};
        for (int i = 0; i < n; i++) {
            News.Entry e = news.get(i);
            int cx = x + i * (cw + gap);
            String id = "news:" + i;
            float t = anim(id, id.equals(hovered));
            int lift = Math.round(t * 2);
            shadow(g, cx, top - lift, cw, ch);
            Shape box = new RoundRectangle2D.Double(cx, top - lift, cw, ch, 16, 16);
            g.setColor(CARD);
            g.fill(box);
            int ih = Math.max(60, ch - 66);
            Image img = e.image() != null ? newsImages.get(e.image()) : null;
            if (img == null) img = heroImages.get(fallback[i % fallback.length]);
            Shape was = g.getClip();
            g.clip(box);
            g.clipRect(cx, top - lift, cw, ih);
            if (img != null) cover(g, img, cx, top - lift, cw, ih, 0.5);
            g.setPaint(new GradientPaint(0, top - lift + ih - 40, new Color(17, 17, 21, 0), 0, top - lift + ih, new Color(17, 17, 21, 200)));
            g.fillRect(cx, top - lift, cw, ih);
            g.setClip(was);
            if (i == 0) {
                g.setFont(interBold.deriveFont(9f));
                int pw = g.getFontMetrics().stringWidth("LATEST") + 14;
                g.setColor(RED);
                g.fill(new RoundRectangle2D.Double(cx + 12, top - lift + 12, pw, 18, 18, 18));
                g.setColor(Color.WHITE);
                center(g, "LATEST", cx + 12, top - lift + 12, pw, 18);
            }
            g.setColor(mix(BORDER, new Color(0x3A3A44), t));
            g.draw(new RoundRectangle2D.Double(cx + .5, top - lift + .5, cw - 1, ch - 1, 16, 16));
            int ty = top - lift + ih + 22;
            g.setFont(interBold.deriveFont(13.5f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, e.title(), cw - 28), cx + 14, ty);
            g.setFont(inter.deriveFont(11f));
            g.setColor(FAINT);
            g.drawString(e.date(), cx + 14, ty + 18);
            int dw = g.getFontMetrics().stringWidth(e.date());
            if (!e.summary().isEmpty()) {
                g.setColor(DIM);
                g.drawString(ellipsizeEnd(g, "  ·  " + e.summary(), cw - 28 - dw), cx + 14 + dw, ty + 18);
            }
            hit(id, new Rectangle(cx, top, cw, ch));
        }
    }

    private void paintProfile(Graphics2D g, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        int fs = 56, fx = x + 18, fy = y + 20;
        g.setColor(new Color(0x0C0C0F));
        g.fill(new RoundRectangle2D.Double(fx - 3, fy - 3, fs + 6, fs + 6, 14, 14));
        if (playerFace != null) {
            Shape was = g.getClip();
            g.clip(new RoundRectangle2D.Double(fx, fy, fs, fs, 10, 10));
            Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(playerFace, fx, fy, fs, fs, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            g.setClip(was);
        } else defaultHead(g, fx, fy, fs);
        g.setColor(new Color(225, 29, 46, 140));
        g.draw(new RoundRectangle2D.Double(fx - 2.5, fy - 2.5, fs + 5, fs + 5, 13, 13));
        int tx = fx + fs + 16;
        g.setFont(interBold.deriveFont(8.5f));
        g.setColor(FAINT);
        spaced(g, "PLAYER", tx, fy + 12, 1.6f);
        g.setFont(interBold.deriveFont(16f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, playerName != null ? playerName : "Not signed in", x + w - 18 - tx), tx, fy + 34);
        g.setFont(inter.deriveFont(11f));
        g.setColor(playerName != null ? OK : DIM);
        String sub = playerName != null ? "Signed in to the Minecraft Launcher" : "Sign in once in the Minecraft Launcher";
        List<String> lines = wrapLines(g, sub, x + w - 18 - tx, 2);
        int ly = fy + 51;
        for (String l : lines) { g.drawString(l, tx, ly); ly += 14; }
        g.setColor(BORDER);
        g.fillRect(x + 18, y + h - 38, w - 36, 1);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(DIM);
        Launcher.Instance sel = selected();
        g.drawString(ellipsizeEnd(g, "Playing  " + (sel != null ? sel.name : "—") + "  ·  " + settings.servers.size() + " server" + (settings.servers.size() == 1 ? "" : "s"), w - 36), x + 18, y + h - 15);
    }

    /** No skin yet: a plain Evade-red head with a simple face (no game art). */
    private void defaultHead(Graphics2D g, int x, int y, int s) {
        g.setPaint(new GradientPaint(x, y, new Color(0x3A1218), x + s, y + s, new Color(0x1A0A0D)));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 10, 10));
        int u = s / 8;
        g.setColor(new Color(255, 255, 255, 200));
        g.fillRect(x + u, y + 4 * u, u * 2, u);
        g.fillRect(x + 5 * u, y + 4 * u, u * 2, u);
        g.setColor(new Color(225, 29, 46, 220));
        g.fillRect(x + 3 * u, y + 6 * u, u * 2, u / 2 + 1);
    }

    private void paintClipsCard(Graphics2D g, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        label(g, "EVADE CLIPS", x + 18, y + 28);
        if (clips == null) {
            g.setFont(inter.deriveFont(12f));
            g.setColor(DIM);
            int ly = y + 54;
            for (String l : wrapLines(g, clipsNote != null ? clipsNote : "Starting…", w - 36, 4)) { g.drawString(l, x + 18, ly); ly += 17; }
            return;
        }
        var st = clips.status;
        boolean rec = st.recording(), live = st.state() == net.evadeclient.clips.core.CaptureEngine.State.LIVE;
        boolean err = st.state() == net.evadeclient.clips.core.CaptureEngine.State.ERROR;
        boolean on = clips.settings.replayEnabled;
        // record icon
        int ix = x + 18, iy = y + 42;
        g.setPaint(new GradientPaint(ix, iy, new Color(0x2A1116), ix + 40, iy + 40, new Color(0x16090C)));
        g.fill(new RoundRectangle2D.Double(ix, iy, 40, 40, 12, 12));
        g.setColor(new Color(225, 29, 46, 60));
        g.draw(new RoundRectangle2D.Double(ix + .5, iy + .5, 39, 39, 12, 12));
        float pulse = rec || live ? (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 300.0)) : 1f;
        if (rec || live) animating = true;
        Color dot = rec ? RED_HI : live ? OK : err ? AMBER : FAINT;
        g.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), Math.round(255 * pulse)));
        g.fill(new Ellipse2D.Double(ix + 13, iy + 13, 14, 14));
        g.setFont(interBold.deriveFont(13.5f));
        g.setColor(TEXT);
        String title = rec ? "Recording " + net.evadeclient.clips.ui.Theme.time(st.recordingSeconds()) : live ? "Instant replay on"
                : err ? "Capture error" : on ? "Replay waits for Minecraft" : "Instant replay off";
        g.drawString(ellipsizeEnd(g, title, w - 130), ix + 52, iy + 17);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(DIM);
        String sub = live ? clips.settings.hotkeySaveClip + " saves the last " + clips.settings.replaySeconds + " s"
                : clips.library.all().size() + " clip" + (clips.library.all().size() == 1 ? "" : "s") + " in your library";
        g.drawString(ellipsizeEnd(g, sub, w - 90), ix + 52, iy + 35);
        toggle(g, "clips-replay-sw", x + w - 18 - 38, iy + 8, on, true);
        hit("clips-replay", new Rectangle(x + w - 18 - 44, iy + 2, 50, 32));
        int bw = (w - 36 - 10) / 2, by = y + h - 50;
        smallPrimary(g, "clips-save", x + 18, by, bw, 34, "Save clip", null);
        ghostButton(g, "clips-go", x + 18 + bw + 10, by, bw, 34, "Open Clips", false);
    }

    private void paintLinks(Graphics2D g, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        label(g, "EVADE", x + 18, y + 28);
        List<String[]> links = new ArrayList<>();
        links.add(new String[]{"link-site", "Website", "globe"});
        links.add(new String[]{"link-docs", "Guides", "book"});
        links.add(new String[]{"link-changelog", "Changelog", "list"});
        if (!DISCORD.isBlank()) links.add(new String[]{"link-discord", "Discord", "chat"});
        if (!YOUTUBE.isBlank()) links.add(new String[]{"link-youtube", "YouTube", "video"});
        if (!TIKTOK.isBlank()) links.add(new String[]{"link-tiktok", "TikTok", "music"});
        int cols = 2, gap = 8, bw = (w - 36 - gap) / cols, bh = 34;
        for (int i = 0; i < links.size(); i++) {
            int bx = x + 18 + (i % cols) * (bw + gap), by = y + 42 + (i / cols) * (bh + gap);
            if (by + bh > y + h - 10) break;
            ghostButton(g, links.get(i)[0], bx, by, bw, bh, links.get(i)[1], false, links.get(i)[2]);
        }
    }

    // ------------------------------------------------------------------ bell

    private void paintBell(Graphics2D g, int x, int y) {
        float t = anim("bell", "bell".equals(hovered) || "bell".equals(overlay));
        g.setColor(mix(new Color(255, 255, 255, 0), new Color(255, 255, 255, 30), t));
        g.fill(new RoundRectangle2D.Double(x, y, 30, 26, 8, 8));
        icon(g, "bell", x + 15, y + 13, 15, mix(DIM, TEXT, t));
        if (unread > 0) {
            String n = unread > 9 ? "9+" : String.valueOf(unread);
            g.setFont(interBold.deriveFont(8.5f));
            int bw = Math.max(14, g.getFontMetrics().stringWidth(n) + 8);
            g.setColor(RED);
            g.fill(new RoundRectangle2D.Double(x + 30 - bw + 4, y - 2, bw, 14, 14, 14));
            g.setColor(Color.WHITE);
            center(g, n, x + 30 - bw + 4, y - 2, bw, 14);
        }
        hitMap.put("bell", new Rectangle(x, y, 30, 26));
    }

    private void paintNotices(Graphics2D g) {
        int w = 340, x = W - 26 - w, y = 46;
        int rows = Math.min(6, notices.size());
        int h = 58 + Math.max(1, rows) * 54 + (notices.isEmpty() ? 0 : 4);
        card(g, x, y, w, h, true);
        g.setFont(interBold.deriveFont(14f));
        g.setColor(TEXT);
        g.drawString("Notifications", x + 20, y + 34);
        if (!notices.isEmpty()) textLink(g, "ov-clear", "Clear", x + w - 20, y + 34, 1);
        if (notices.isEmpty()) {
            g.setFont(inter.deriveFont(12f));
            g.setColor(DIM);
            g.drawString("Nothing new. Updates and saved clips show up here.", x + 20, y + 80);
        }
        int ry = y + 50;
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows; i++) {
            Notice n = notices.get(i);
            g.setColor(new Color(255, 255, 255, 6));
            g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 48, 10, 10));
            g.setColor(n.error() ? AMBER : RED_HI);
            g.fill(new Ellipse2D.Double(x + 22, ry + 14, 7, 7));
            g.setFont(interBold.deriveFont(12.5f));
            g.setColor(TEXT);
            String ago = ago(now - n.at());
            g.drawString(ellipsizeEnd(g, n.title(), w - 90 - 40), x + 38, ry + 20);
            g.setFont(inter.deriveFont(10.5f));
            g.setColor(FAINT);
            g.drawString(ago, x + w - 22 - g.getFontMetrics().stringWidth(ago), ry + 20);
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(DIM);
            g.drawString(ellipsizeEnd(g, n.body(), w - 60), x + 38, ry + 38);
            ry += 54;
        }
        hitMap.put("ov-panel", new Rectangle(x, y, w, h));
        Rectangle clear = hitMap.remove("ov-clear");
        if (clear != null) hitMap.put("ov-clear", clear);
    }

    private static String ago(long ms) {
        long m = ms / 60_000;
        if (m < 1) return "now";
        if (m < 60) return m + " min";
        if (m < 24 * 60) return (m / 60) + " h";
        return (m / 1440) + " d";
    }

    // ------------------------------------------------------------------ clips

    private Rectangle paintClips(Graphics2D g) {
        pageTitle(g, "Clips", null);
        int ty = 64;
        String[][] tabs = {{"library", "Library"}, {"record", "Record"}, {"editor", "Editor"}, {"settings", "Settings"}};
        int tx = CX + 96;
        for (String[] t : tabs) {
            boolean en = !t[0].equals("editor") || clipsEditorOpen();
            boolean on = clipsPage.equals(t[0]);
            int count = t[0].equals("library") && clips != null ? clips.library.all().size() : -1;
            if (clips == null) { en = false; on = false; }
            String id = "ctab:" + t[0];
            float a = en ? anim(id, id.equals(hovered)) : 0;
            g.setFont((on ? interBold : inter).deriveFont(13.5f));
            g.setColor(on ? TEXT : en ? mix(DIM, TEXT, a) : FAINT);
            g.drawString(t[1], tx, ty - 14);
            int lw = g.getFontMetrics().stringWidth(t[1]);
            if (count >= 0) {
                g.setFont(interBold.deriveFont(10f));
                String c = String.valueOf(count);
                int cw = g.getFontMetrics().stringWidth(c) + 12;
                g.setColor(on ? new Color(225, 29, 46, 50) : new Color(255, 255, 255, 14));
                g.fill(new RoundRectangle2D.Double(tx + lw + 7, ty - 27, cw, 16, 16, 16));
                g.setColor(on ? RED_HI : DIM);
                center(g, c, tx + lw + 7, ty - 27, cw, 16);
                lw += cw + 7;
            }
            if (on) {
                g.setColor(RED);
                g.fill(new RoundRectangle2D.Double(tx, ty - 4, lw, 3, 3, 3));
            }
            if (en) hit(id, new Rectangle(tx - 6, ty - 34, lw + 12, 34));
            tx += lw + 26;
        }
        int rx = CX + CW - 118; // keep clear of the window buttons
        if (clips != null) {
            var st = clips.status;
            boolean rec = st.recording(), live = st.state() == net.evadeclient.clips.core.CaptureEngine.State.LIVE;
            smallPrimary(g, "clips-save", rx - 104, ty - 36, 104, 30, "Save clip", null);
            g.setFont(inter.deriveFont(12f));
            String rs = rec ? "Recording" : live ? "Replay on" : clips.settings.replayEnabled ? "Replay waits for Minecraft" : "Replay off";
            int rw2 = g.getFontMetrics().stringWidth(rs);
            int sx = rx - 104 - 16 - 38;
            toggle(g, "clips-replay-sw", sx, ty - 31, clips.settings.replayEnabled, true);
            hit("clips-replay", new Rectangle(sx - 4, ty - 36, 46, 30));
            g.setColor(rec ? RED_HI : live ? OK : DIM);
            g.drawString(rs, sx - 10 - rw2, ty - 16);
        }
        g.setColor(BORDER);
        g.fillRect(CX, ty, CW, 1);
        Rectangle box = new Rectangle(CX, ty + 14, CW, H - ty - 14 - 24);
        if (clips == null || clipsHost == null) {
            card(g, box.x, box.y, box.width, box.height, false);
            g.setFont(interBold.deriveFont(15f));
            g.setColor(TEXT);
            center(g, clips == null ? "Evade Clips isn't running" : "Opening Evade Clips…", box.x, box.y + box.height / 2 - 50, box.width, 20);
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(DIM);
            int ly = box.y + box.height / 2 - 14;
            for (String l : wrapLines(g, clipsNote != null ? clipsNote : "One moment.", Math.min(520, box.width - 80), 4)) {
                center(g, l, box.x, ly - 13, box.width, 18);
                ly += 19;
            }
            return null;
        }
        // a thin frame around the embedded pages
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(box.x - .5, box.y - .5, box.width, box.height, 6, 6));
        return box;
    }

    private void playButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean enabled) {
        float t = enabled ? anim(id, id.equals(hovered)) : 0;
        if (enabled) {
            glow(g, x + w / 2f, y + h * 0.75f, w * 0.62f, h * 0.9f, (int) (80 + 70 * t));
            g.setPaint(new GradientPaint(0, y, mix(RED_HI, new Color(0xFF6070), t), 0, y + h, mix(RED, RED_HI, t)));
        } else {
            g.setColor(new Color(0x2A1418));
        }
        double dy = -t * 1.5;
        g.fill(new RoundRectangle2D.Double(x, y + dy, w, h, 14, 14));
        if (enabled) {
            g.setColor(new Color(255, 255, 255, 55));
            g.fill(new java.awt.geom.Rectangle2D.Double(x + 10, y + dy + 1, w - 20, 1));
        }
        g.setFont(interBold.deriveFont(label.length() > 8 ? 16f : 19f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label), iw = 14, gap = 12;
        int tx = x + (w - tw - iw - gap) / 2;
        int cy = (int) (y + dy + h / 2.0);
        Color fg = enabled ? Color.WHITE : FAINT;
        icon(g, "play", tx + iw / 2, cy, iw, fg);
        g.setColor(fg);
        g.drawString(label, tx + iw + gap, cy + fm.getAscent() / 2f - 2);
        hit(id, new Rectangle(x, y, w, h));
    }

    private void paintHeroProgress(Graphics2D g, int x, int y, int w, int h) {
        boolean launching = play == Play.LAUNCHING;
        label(g, launching ? "STARTING" : "UPDATING " + playName.toUpperCase(), x, y + 40);
        g.setFont(interBold.deriveFont(30f));
        g.setColor(TEXT);
        g.drawString(launching ? "Opening the Minecraft Launcher…" : Math.round(shownProgress * 100) + "%", x - 1, y + 82);
        int by = y + 108;
        g.setColor(new Color(0x24242B));
        g.fill(new RoundRectangle2D.Double(x, by, w, 6, 6, 6));
        double f = launching ? 1 : shownProgress;
        int bw = (int) Math.max(6, w * f);
        g.setPaint(new GradientPaint(x, 0, new Color(0x9B0D1A), x + bw, 0, RED_HI));
        g.fill(new RoundRectangle2D.Double(x, by, bw, 6, 6, 6));
        float s = ((System.currentTimeMillis() - start) % 1400) / 1400f;
        g.setPaint(new GradientPaint(x + bw * s - 30, 0, new Color(255, 255, 255, 0), x + bw * s, 0, new Color(255, 255, 255, 90), true));
        g.fill(new RoundRectangle2D.Double(x, by, bw, 6, 6, 6));
        g.setFont(inter.deriveFont(13f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, launching ? "Making “" + playName + "” the profile it selects" : status, w), x, by + 34);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString(launching ? "" : "Every file is checked before it is used. Your worlds and settings stay as they are.", x, y + h - 28);
    }

    private void paintHeroLaunched(Graphics2D g, int x, int y, int w, int h) {
        // check badge
        int cx = x + 26, cy = y + 60;
        g.setColor(new Color(61, 220, 132, 34));
        g.fill(new Ellipse2D.Double(cx - 26, cy - 26, 52, 52));
        g.setColor(OK);
        g.fill(new Ellipse2D.Double(cx - 18, cy - 18, 36, 36));
        g.setColor(new Color(0x07140C));
        g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D check = new Path2D.Double();
        check.moveTo(cx - 8, cy + 1);
        check.lineTo(cx - 2, cy + 7);
        check.lineTo(cx + 9, cy - 6);
        g.draw(check);
        g.setStroke(new BasicStroke(1f));
        g.setFont(interBold.deriveFont(26f));
        g.setColor(TEXT);
        g.drawString("Press Play in the Minecraft Launcher", x + 70, y + 58);
        g.setFont(inter.deriveFont(13f));
        g.setColor(DIM);
        g.drawString("“" + profileOf(playName) + "” is selected there. Sign in once if it asks.", x + 70, y + 82);

        String[] steps = {"Press the green Play button there", "Right Shift opens the Evade menu"};
        int sy = y + 108;
        for (int i = 0; i < steps.length; i++) {
            int sx = x + i * ((w - 240) / 2 + 6);
            int sw = (w - 240) / 2 - 6;
            g.setColor(new Color(255, 255, 255, 10));
            g.fill(new RoundRectangle2D.Double(sx, sy, sw, 34, 10, 10));
            g.setFont(interBold.deriveFont(11f));
            g.setColor(RED_HI);
            g.drawString("0" + (i + 1), sx + 12, sy + 21);
            g.setFont(inter.deriveFont(12f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, steps[i], sw - 48), sx + 38, sy + 21);
        }
        if (!skipped.isEmpty()) {
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(AMBER);
            String s = skipped.size() == 1 ? "Skipped: " + skippedText(skipped.get(0)) : skipped.size() + " extras were skipped: " + skippedText(skipped.get(0)) + ", …";
            g.drawString(ellipsizeEnd(g, s, w - 250), x, y + h - 28);
        } else {
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(FAINT);
            g.drawString("If the Minecraft Launcher was already open, pick “" + profileOf(playName) + "” from the list next to Play.", x, y + h - 28);
        }
        ghostButton(g, "play-again", x + w - 220, y + h - 96, 220, 36, "Open the launcher again", false);
        textLink(g, "play-done", "Done", x + w - 110, y + h - 44, 0);
    }

    private String profileOf(String name) {
        for (Launcher.Instance i : instances) if (i.name.equals(name)) return i.profileName();
        return InstallerCore.PROFILE_NAME;
    }

    private static String skippedText(String s) {
        return s;
    }

    private void paintHeroNoLauncher(Graphics2D g, int x, int y, int w, int h) {
        badge(g, x + 26, y + 60, "!");
        g.setFont(interBold.deriveFont(24f));
        g.setColor(TEXT);
        g.drawString("Get the Minecraft Launcher first", x + 70, y + 58);
        g.setFont(inter.deriveFont(13f));
        g.setColor(DIM);
        int ly = y + 84;
        String os = InstallerCore.WINDOWS ? "On Windows it's also in the Microsoft Store as “Minecraft Launcher”. "
                : System.getProperty("os.name", "").toLowerCase().contains("mac") ? "Drag it into Applications. " : "";
        for (String line : wrapLines(g, "Evade runs through the official Minecraft Launcher, which signs you in with your Microsoft account. "
                + "Download it from minecraft.net. " + os + "Open it once and sign in, then press Play here again.", w - 330, 4)) {
            g.drawString(line, x + 70, ly);
            ly += 19;
        }
        playButton(g, "get-launcher", x + w - 240, y + h - 132, 240, 50, "Get it at minecraft.net", true);
        ghostButton(g, "play", x + w - 240, y + h - 70, 240, 38, "I've installed it: try again", false);
    }

    private void paintHeroError(Graphics2D g, int x, int y, int w, int h) {
        badge(g, x + 26, y + 60, "!");
        g.setFont(interBold.deriveFont(24f));
        g.setColor(TEXT);
        g.drawString("Couldn't get " + (playName.isEmpty() ? "the game" : playName) + " ready", x + 70, y + 58);
        g.setFont(inter.deriveFont(13f));
        g.setColor(DIM);
        int ly = y + 84;
        for (String line : wrapLines(g, error, w - 330, 5)) {
            g.drawString(line, x + 70, ly);
            ly += 19;
        }
        playButton(g, "play", x + w - 220, y + h - 88, 220, 60, "Try again", true);
    }

    private void badge(Graphics2D g, int cx, int cy, String s) {
        g.setColor(new Color(225, 29, 46, 40));
        g.fill(new Ellipse2D.Double(cx - 26, cy - 26, 52, 52));
        g.setColor(RED);
        g.fill(new Ellipse2D.Double(cx - 18, cy - 18, 36, 36));
        g.setFont(interBold.deriveFont(20f));
        g.setColor(Color.WHITE);
        center(g, s, cx - 18, cy - 18, 36, 36);
    }

    private void paintVersions(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        label(g, "VERSIONS", x + 18, y + 28);
        g.setFont(inter.deriveFont(10f));
        g.setColor(FAINT);
        String cols = "INSTALLED → LATEST";
        g.drawString(cols, x + w - 18 - g.getFontMetrics().stringWidth(cols), y + 28);
        InstallerCore.Installed in = sel != null ? sel.installed : null;
        String[][] rows = {
                {"Evade Client", in != null ? "v" + in.evadeVersion() : "—", release != null ? "v" + release.evadeVersion() : "…"},
                {"Fabric Loader", in != null ? in.loaderVersion() : "—", release != null ? release.loaderVersion() : "…"},
                {"Fabric API", in != null ? InstallerCore.shortVersion(in.fabricApiVersion()) : "—", release != null ? InstallerCore.shortVersion(release.fabricApiVersion()) : "…"}};
        int ry = y + 42;
        for (String[] r : rows) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(r[0], x + 18, ry + 14);
            boolean same = r[1].equals(r[2]) || release == null;
            g.setFont(interBold.deriveFont(12f));
            FontMetrics fm = g.getFontMetrics();
            int lx = x + w - 18 - fm.stringWidth(r[2]);
            g.setColor(same ? DIM : RED_HI);
            g.drawString(r[2], lx, ry + 14);
            if (!same) {
                g.setFont(inter.deriveFont(12f));
                String a = r[1] + "  →  ";
                g.setColor(FAINT);
                g.drawString(a, lx - g.getFontMetrics().stringWidth(a), ry + 14);
            } else {
                icon(g, "check", lx - 14, ry + 9, 10, release == null ? FAINT : OK);
            }
            ry += 27;
        }
    }

    // ------------------------------------------------------------------ first run

    private void paintSetup(Graphics2D g) {
        int x = CX + 6;
        g.setFont(interBold.deriveFont(10f));
        g.setColor(RED_HI);
        spaced(g, "WELCOME", x, 84, 2.4f);
        g.setFont(interBold.deriveFont(34f));
        g.setColor(TEXT);
        g.drawString("Let's get Evade", x - 1, 132);
        g.setColor(RED);
        g.drawString("ready to play.", x - 1, 172);
        g.setFont(inter.deriveFont(13.5f));
        g.setColor(DIM);
        String ver = release != null ? "v" + release.evadeVersion() : "the latest version";
        wrap(g, "One install sets up Fabric, Fabric API and Evade Client " + ver + " in their own folder, and adds an Evade Client "
                + "profile to your Minecraft Launcher. After that, Play keeps it up to date.", x, 206, 330, 21);

        String[][] steps = {{"Install", "Pick your options and press Install"}, {"Play", "Opens the Minecraft Launcher, Evade selected"},
                {"Right Shift", "Opens the Evade menu in game"}};
        int sy = 296;
        for (int i = 0; i < steps.length; i++) {
            g.setColor(new Color(255, 255, 255, 8));
            g.fill(new RoundRectangle2D.Double(x, sy, 340, 50, 12, 12));
            g.setColor(BORDER);
            g.draw(new RoundRectangle2D.Double(x + .5, sy + .5, 339, 49, 12, 12));
            g.setColor(new Color(225, 29, 46, 36));
            g.fill(new Ellipse2D.Double(x + 12, sy + 11, 28, 28));
            g.setFont(interBold.deriveFont(12f));
            g.setColor(RED_HI);
            center(g, String.valueOf(i + 1), x + 12, sy + 11, 28, 28);
            g.setFont(interBold.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString(steps[i][0], x + 52, sy + 22);
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(DIM);
            g.drawString(steps[i][1], x + 52, sy + 39);
            sy += 58;
        }
        // version chips
        int cx = x;
        String[] chips = release != null
                ? new String[]{"Minecraft " + InstallerCore.MC_VERSION, "Fabric " + release.loaderVersion(), "Fabric API " + InstallerCore.shortVersion(release.fabricApiVersion())}
                : new String[]{"Minecraft " + InstallerCore.MC_VERSION, releaseError != null ? "Offline" : "Checking versions…"};
        g.setFont(inter.deriveFont(11.5f));
        for (String c : chips) {
            int w = g.getFontMetrics().stringWidth(c) + 22;
            g.setColor(new Color(255, 255, 255, 10));
            g.fill(new RoundRectangle2D.Double(cx, H - 70, w, 26, 26, 26));
            g.setColor(BORDER);
            g.draw(new RoundRectangle2D.Double(cx + .5, H - 69.5, w - 1, 25, 25, 25));
            g.setColor(DIM);
            center(g, c, cx, H - 70, w, 26);
            cx += w + 8;
        }

        int cardX = CX + CW - 340, cardY = 56, cw = 340, ch = H - 56 - 30;
        card(g, cardX, cardY, cw, ch, true);
        int ix = cardX + 24, iw = cw - 48;
        switch (play) {
            case UPDATING -> paintSetupProgress(g, ix, cardY, iw, ch);
            case ERROR -> paintSetupError(g, ix, cardY, iw, ch);
            default -> paintSetupOptions(g, ix, cardY, iw, ch);
        }
    }

    private void paintSetupOptions(Graphics2D g, int x, int y, int w, int h) {
        label(g, "MINECRAFT FOLDER", x, y + 34);
        box(g, x, y + 44, w, 38);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(ellipsize(g, settings.minecraftDir().toString(), w - 92), x + 12, y + 67);
        smallButton(g, "setup-folder", x + w - 72, y + 50, 64, 26, "Change");

        label(g, "MEMORY", x, y + 110);
        memoryChips(g, x, y + 120, w, setupMemory);
        g.setFont(inter.deriveFont(11f));
        g.setColor(DIM);
        String rec = "Recommended for this PC: " + recommendedGb + " GB";
        g.drawString(rec, x, y + 171);
        if (totalGb > 0) {
            g.setColor(FAINT);
            g.drawString("  ·  " + totalGb + " GB RAM", x + g.getFontMetrics().stringWidth(rec), y + 171);
        }
        String[][] rows = {{"s-copy", "Copy my settings & servers", ""}, {"s-voice", "Voice chat (Simple Voice Chat)", "Voice chat"},
                {"s-perf", "FPS mods (Sodium & more)", "FPS mods"}, {"s-shaders", "Shaders (Iris + Photon)", "Shaders"}};
        boolean[] values = {setupCopy, setupVoice, setupPerf, setupShaders};
        boolean[] avail = {true, release == null || release.voiceChat() != null, release == null || !release.performance().isEmpty(),
                release == null || release.iris() != null};
        for (int i = 0; i < rows.length; i++) {
            int ry = y + 186 + i * 31;
            switchRow(g, rows[i][0], x, ry, w, avail[i] ? rows[i][1] : rows[i][2], values[i], avail[i]);
        }
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString(ellipsizeEnd(g, "Evade Clips is built in: instant replay, clip library & editor.", w), x, y + 186 + rows.length * 31 + 8);
        g.setFont(inter.deriveFont(12f));
        String info;
        Color ic = DIM;
        if (releaseError != null) { info = EvadeInstaller.sentence(releaseError) + " Check your connection and press Retry."; ic = RED_HI; }
        else if (release == null) info = "Checking for the latest version…";
        else info = "Evade Client v" + release.evadeVersion() + " for Minecraft " + InstallerCore.MC_VERSION;
        List<String> lines = wrapLines(g, info, w - 14, 2);
        int iy = y + h - 108 - 16 * (lines.size() - 1);
        g.setColor(ic);
        g.fill(new Ellipse2D.Double(x, iy - 7, 6, 6));
        for (String line : lines) { g.drawString(line, x + 14, iy); iy += 16; }
        boolean retry = release == null && releaseError != null;
        bigButton(g, "setup-install", x, y + h - 84, w, 50, retry ? "Retry" : "Install Evade Client", release != null || retry);
        g.setFont(inter.deriveFont(11f));
        g.setColor(FAINT);
        center(g, "Requires a Minecraft Java Edition account", x, y + h - 30, w, 14);
    }

    private void paintSetupProgress(Graphics2D g, int x, int y, int w, int h) {
        label(g, "INSTALLING", x, y + 34);
        g.setFont(interBold.deriveFont(46f));
        g.setColor(TEXT);
        g.drawString(Math.round(shownProgress * 100) + "%", x - 2, y + 96);
        g.setColor(new Color(0x24242B));
        g.fill(new RoundRectangle2D.Double(x, y + 114, w, 6, 6, 6));
        int bw = (int) Math.max(6, w * shownProgress);
        g.setPaint(new GradientPaint(x, 0, new Color(0x9B0D1A), x + bw, 0, RED_HI));
        g.fill(new RoundRectangle2D.Double(x, y + 114, bw, 6, 6, 6));
        float s = ((System.currentTimeMillis() - start) % 1400) / 1400f;
        g.setPaint(new GradientPaint(x + bw * s - 30, 0, new Color(255, 255, 255, 0), x + bw * s, 0, new Color(255, 255, 255, 90), true));
        g.fill(new RoundRectangle2D.Double(x, y + 114, bw, 6, 6, 6));
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, status, w), x, y + 146);
        boolean clips = InstallerCore.WINDOWS && release != null && release.ffmpegUrl() != null && !InstallerCore.ffmpegPresent();
        boolean extras = setupVoice || setupPerf || setupShaders;
        List<String> steps = new ArrayList<>(List.of("Fabric Loader", "Fabric API", "Evade Client"));
        List<Double> at = new ArrayList<>(List.of(0.15, 0.55, 0.75));
        if (extras) { steps.add("Extra mods"); at.add(0.83); }
        if (clips) { steps.add("Video engine for Clips"); at.add(0.96); }
        steps.add("Launcher profile");
        at.add(1.0);
        int sy = y + 180;
        for (int i = 0; i < steps.size(); i++) {
            boolean done = progress >= at.get(i);
            boolean active = !done && (i == 0 || progress >= at.get(i - 1));
            stepRow(g, x, sy, steps.get(i), done, active);
            sy += 36;
        }
    }

    private void paintSetupError(Graphics2D g, int x, int y, int w, int h) {
        badge(g, x + w / 2, y + 70, "!");
        g.setFont(interBold.deriveFont(19f));
        g.setColor(TEXT);
        center(g, "Install didn't finish", x, y + 112, w, 26);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        int ey = y + 160;
        for (String line : wrapLines(g, error, w, 9)) { g.drawString(line, x, ey); ey += 19; }
        bigButton(g, "setup-retry", x, y + h - 84, w, 50, "Try again", true);
        textLink(g, "setup-back", "Change options", x + w / 2, y + h - 26, 2);
    }

    // ------------------------------------------------------------------ instances

    private void paintInstances(Graphics2D g) {
        pageTitle(g, "Instances", "Each instance has its own mods, settings, worlds and Minecraft Launcher profile.");
        smallPrimary(g, "inst-new", CX + CW - 150, 44, 150, 36, "New instance", "plus");

        int lx = CX, ly = 100, lw = 300, lh = H - ly - 26;
        card(g, lx, ly, lw, lh, false);
        Rectangle area = new Rectangle(lx + 1, ly + 8, lw - 2, lh - 16);
        float off = scrollOf("instances");
        Shape was = g.getClip();
        g.clip(area);
        clip = area;
        int y = ly + 10 - Math.round(off);
        Launcher.Instance foc = focused();
        for (Launcher.Instance i : instances) {
            String id = "inst:" + i.id;
            boolean on = i == foc;
            float t = anim(id, id.equals(hovered));
            if (on) {
                g.setPaint(new GradientPaint(lx + 10, 0, new Color(225, 29, 46, 46), lx + lw, 0, new Color(225, 29, 46, 8)));
                g.fill(new RoundRectangle2D.Double(lx + 10, y, lw - 20, 62, 12, 12));
                g.setColor(new Color(225, 29, 46, 90));
                g.draw(new RoundRectangle2D.Double(lx + 10.5, y + .5, lw - 21, 61, 12, 12));
            } else if (t > 0) {
                g.setColor(new Color(255, 255, 255, (int) (10 * t)));
                g.fill(new RoundRectangle2D.Double(lx + 10, y, lw - 20, 62, 12, 12));
            }
            avatar(g, lx + 22, y + 13, 36, i.name, i.isDefault() ? RED.getRGB() : hashColor(i.name));
            g.setFont(interBold.deriveFont(13.5f));
            g.setColor(TEXT);
            boolean playing = i.id.equals(settings.selected);
            int maxName = lw - 110 - (playing ? 64 : 0);
            String nm = ellipsizeEnd(g, i.name, maxName);
            g.drawString(nm, lx + 70, y + 28);
            if (playing) {
                int px = lx + 70 + g.getFontMetrics().stringWidth(nm) + 8;
                g.setFont(interBold.deriveFont(8.5f));
                int pw = g.getFontMetrics().stringWidth("PLAYING") + 12;
                g.setColor(new Color(225, 29, 46, 44));
                g.fill(new RoundRectangle2D.Double(px, y + 16, pw, 15, 15, 15));
                g.setColor(RED_HI);
                center(g, "PLAYING", px, y + 16, pw, 15);
            }
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(DIM);
            String sub = (i.installed != null ? "v" + i.installed.evadeVersion() : "Not installed yet") + "  ·  " + i.modCount + " mods  ·  " + i.memoryGb + " GB";
            g.drawString(ellipsizeEnd(g, sub, lw - 110), lx + 70, y + 46);
            statusDot(g, lx + lw - 34, y + 33, i);
            hit(id, new Rectangle(lx + 10, y, lw - 20, 62));
            y += 68;
        }
        int content = y + Math.round(off) - (ly + 10);
        g.setClip(was);
        clip = null;
        scrollArea(g, "instances", area, content);

        if (foc != null) paintInstanceDetail(g, foc, lx + lw + 18, ly, CW - lw - 18, lh);
    }

    private void paintInstanceDetail(Graphics2D g, Launcher.Instance i, int x, int y, int w, int h) {
        card(g, x, y, w, h, true);
        int ix = x + 26, iw = w - 52;
        g.setFont(interBold.deriveFont(22f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, i.name, iw - 110), ix, y + 44);
        g.setFont(inter.deriveFont(12f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, "Launcher profile: " + i.profileName(), iw), ix, y + 66);
        boolean playing = i.id.equals(settings.selected);
        if (playing) {
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(OK);
            String s = "Play starts this one";
            int sw = g.getFontMetrics().stringWidth(s);
            g.fill(new Ellipse2D.Double(ix + iw - sw - 12, y + 32, 6, 6));
            g.drawString(s, ix + iw - sw, y + 39);
        } else {
            smallPrimary(g, "inst-use", ix + iw - 120, y + 22, 120, 32, "Play this", "play");
        }

        label(g, "FOLDER", ix, y + 100);
        box(g, ix, y + 110, iw, 38);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(ellipsize(g, i.dir.toString(), iw - 100), ix + 12, y + 134);
        smallButton(g, "inst-folder", ix + iw - 72, y + 116, 64, 26, "Open");

        label(g, "MEMORY", ix, y + 178);
        memoryChips(g, ix, y + 188, iw, i.memoryGb);

        label(g, "EXTRAS", ix, y + 252);
        boolean voice = release == null || release.voiceChat() != null, perf = release == null || !release.performance().isEmpty(), sh = release == null || release.iris() != null;
        switchRow(g, "inst-perf", ix, y + 264, iw, "FPS mods (Sodium & more)", i.perf, perf);
        switchRow(g, "inst-shaders", ix, y + 296, iw, "Shaders (Iris + Photon)", i.shaders, sh);
        switchRow(g, "inst-voice", ix, y + 328, iw, "Voice chat (Simple Voice Chat)", i.voice, voice);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(i.dirty ? AMBER : FAINT);
        g.drawString(i.dirty ? "Changed: applied the next time you press Play with this instance." : "Changes apply the next time you press Play.", ix, y + 376);

        int by = y + h - 58, bw = (iw - 3 * 10) / 4;
        ghostButton(g, "inst-copy", ix, by, bw, 36, "Copy from", false);
        ghostButton(g, "inst-rename", ix + (bw + 10), by, bw, 36, "Rename", false);
        ghostButton(g, "inst-dup", ix + 2 * (bw + 10), by, bw, 36, "Duplicate", false);
        if (!i.isDefault()) ghostButton(g, "inst-del", ix + 3 * (bw + 10), by, bw, 36, "Delete", true);
        else {
            g.setFont(inter.deriveFont(11f));
            g.setColor(FAINT);
            center(g, "Main can't be deleted", ix + 3 * (bw + 10), by, bw, 36);
        }
    }

    // ------------------------------------------------------------------ mods

    private Rectangle paintMods(Graphics2D g) {
        Launcher.Instance sel = selected();
        pageTitle(g, "Mods", "Mods for one instance. Switching one off keeps it, so you can turn it back on later.");
        // instance picker
        g.setFont(interBold.deriveFont(12.5f));
        String nm = sel != null ? sel.name : "—";
        int pw = Math.min(220, g.getFontMetrics().stringWidth(nm) + 74);
        int px = CX + CW - pw;
        float t = anim("mods-instance", "mods-instance".equals(hovered));
        g.setColor(mix(CARD_2, new Color(0x202028), t));
        g.fill(new RoundRectangle2D.Double(px, 44, pw, 36, 11, 11));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(px + .5, 44.5, pw - 1, 35, 11, 11));
        icon(g, "layers", px + 20, 62, 14, DIM);
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, nm, pw - 70), px + 36, 67);
        icon(g, "chevron-down", px + pw - 18, 62, 9, DIM);
        hit("mods-instance", new Rectangle(px, 44, pw, 36));

        // tabs
        int ty = 100;
        tab(g, "tab-installed", CX, ty, "Installed", mods.size(), !browse);
        tab(g, "tab-browse", CX + 140, ty, "Browse Modrinth", -1, browse);
        g.setColor(BORDER);
        g.fillRect(CX, ty + 36, CW, 1);

        int y = ty + 50, h = H - y - 26;
        if (!browse) { paintInstalledMods(g, sel, CX, y, CW, h); return null; }
        return paintBrowse(g, CX, y, CW, h);
    }

    private void tab(Graphics2D g, String id, int x, int y, String label, int count, boolean on) {
        float t = anim(id, id.equals(hovered));
        g.setFont((on ? interBold : inter).deriveFont(13.5f));
        g.setColor(on ? TEXT : mix(DIM, TEXT, t));
        g.drawString(label, x, y + 22);
        int w = g.getFontMetrics().stringWidth(label);
        if (count >= 0) {
            g.setFont(interBold.deriveFont(10.5f));
            String c = String.valueOf(count);
            int cw = g.getFontMetrics().stringWidth(c) + 12;
            g.setColor(on ? new Color(225, 29, 46, 50) : new Color(255, 255, 255, 14));
            g.fill(new RoundRectangle2D.Double(x + w + 8, y + 9, cw, 17, 17, 17));
            g.setColor(on ? RED_HI : DIM);
            center(g, c, x + w + 8, y + 9, cw, 17);
            w += cw + 8;
        }
        if (on) {
            g.setColor(RED);
            g.fill(new RoundRectangle2D.Double(x, y + 34, w, 3, 3, 3));
        }
        hit(id, new Rectangle(x - 6, y, w + 12, 36));
    }

    private void paintInstalledMods(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        if (mods.isEmpty()) {
            g.setFont(interBold.deriveFont(15f));
            g.setColor(TEXT);
            center(g, sel != null && sel.installed == null ? "Nothing installed yet" : "No mods here", x, y + h / 2 - 40, w, 20);
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(DIM);
            center(g, sel != null && sel.installed == null ? "Press Play once to install Evade Client in this instance." : "Find mods under Browse Modrinth.", x, y + h / 2 - 12, w, 18);
            ghostButton(g, "tab-browse", x + w / 2 - 90, y + h / 2 + 16, 180, 36, "Browse Modrinth", false);
            return;
        }
        Rectangle area = new Rectangle(x + 1, y + 8, w - 2, h - 58);
        float off = scrollOf("mods");
        Shape was = g.getClip();
        g.clip(area);
        clip = area;
        int ry = y + 10 - Math.round(off);
        for (int n = 0; n < mods.size(); n++) {
            Mods.Entry e = mods.get(n);
            if (ry + 58 >= area.y && ry <= area.y + area.height) {
                if (n % 2 == 1) {
                    g.setColor(new Color(255, 255, 255, 5));
                    g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 56, 10, 10));
                }
                Color fade = e.enabled() ? TEXT : FAINT;
                avatar(g, x + 22, ry + 11, 34, e.name(), e.enabled() ? (e.option() != null && e.option().equals("Evade") ? RED.getRGB() : hashColor(e.name())) : 0x2A2A31);
                g.setFont(interBold.deriveFont(13f));
                g.setColor(fade);
                String name = ellipsizeEnd(g, e.name(), 300);
                g.drawString(name, x + 68, ry + 25);
                int vx = x + 68 + g.getFontMetrics().stringWidth(name) + 8;
                g.setFont(inter.deriveFont(11.5f));
                g.setColor(FAINT);
                if (!e.version().isEmpty()) g.drawString(ellipsizeEnd(g, e.version(), 140), vx, ry + 25);
                g.setColor(e.enabled() ? DIM : FAINT);
                String desc = !e.description().isEmpty() ? e.description() : e.fileName();
                g.drawString(ellipsizeEnd(g, desc, w - 330), x + 68, ry + 43);

                int rx = x + w - 30;
                if (e.managed()) {
                    String tag = e.option().equals("Evade") ? "Required" : e.option();
                    g.setFont(interBold.deriveFont(10f));
                    int tw = g.getFontMetrics().stringWidth(tag) + 18;
                    boolean ev = e.option().equals("Evade");
                    g.setColor(ev ? new Color(225, 29, 46, 36) : new Color(255, 255, 255, 12));
                    g.fill(new RoundRectangle2D.Double(rx - tw, ry + 17, tw, 22, 22, 22));
                    g.setColor(ev ? RED_HI : DIM);
                    center(g, tag, rx - tw, ry + 17, tw, 22);
                    if (!ev) {
                        g.setFont(inter.deriveFont(10.5f));
                        g.setColor(FAINT);
                        String m = "set in Instances";
                        g.drawString(m, rx - tw - 10 - g.getFontMetrics().stringWidth(m), ry + 32);
                    }
                } else {
                    iconButton(g, "mod-remove:" + n, rx - 32, ry + 12, 32, "trash", true);
                    toggle(g, "mod-toggle:" + n, rx - 32 - 56, ry + 18, e.enabled(), true);
                    if (!e.enabled()) {
                        g.setFont(inter.deriveFont(11f));
                        g.setColor(FAINT);
                        g.drawString("Off", rx - 32 - 56 - 28, ry + 32);
                    }
                }
            }
            ry += 58;
        }
        int content = ry + Math.round(off) - (y + 10);
        g.setClip(was);
        clip = null;
        scrollArea(g, "mods", area, content);
        g.setColor(BORDER);
        g.fillRect(x + 1, y + h - 50, w - 2, 1);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        long off2 = mods.stream().filter(m -> !m.enabled()).count();
        g.drawString(mods.size() + " mods" + (off2 > 0 ? "  ·  " + off2 + " switched off" : "") + "  ·  Evade and its extras are kept up to date by Play", x + 24, y + h - 20);
        ghostButton(g, "mods-folder", x + w - 24 - 170, y + h - 41, 170, 32, "Open mods folder", false);
    }

    private Rectangle paintBrowse(Graphics2D g, int x, int y, int w, int h) {
        // search box
        box(g, x, y, w, 44);
        icon(g, "search", x + 22, y + 22, 14, DIM);
        Rectangle field = new Rectangle(x + 42, y + 8, w - 160, 28);
        if (searchField.getText().isEmpty()) {
            g.setFont(inter.deriveFont(13f));
            g.setColor(FAINT);
            g.drawString((String) searchField.getClientProperty("placeholder"), field.x + 1, y + 27);
        }
        g.setFont(inter.deriveFont(11f));
        g.setColor(FAINT);
        String facet = "Fabric · " + InstallerCore.MC_VERSION;
        g.drawString(facet, x + w - 18 - g.getFontMetrics().stringWidth(facet), y + 26);

        int ly = y + 58, lh = h - 58;
        card(g, x, ly, w, lh, false);
        if (hits.isEmpty()) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(searchError != null ? RED_HI : DIM);
            String msg = searching ? "Searching Modrinth…" : searchError != null ? EvadeInstaller.sentence(searchError) : "No mods found for “" + lastQuery + "”.";
            center(g, msg, x, ly + lh / 2 - 20, w, 18);
            if (searchError != null && !searching) ghostButton(g, "search-retry", x + w / 2 - 70, ly + lh / 2 + 6, 140, 34, "Try again", false);
            return field;
        }
        Rectangle area = new Rectangle(x + 1, ly + 8, w - 2, lh - 16);
        float off = scrollOf("browse");
        Shape was = g.getClip();
        g.clip(area);
        clip = area;
        int ry = ly + 10 - Math.round(off);
        for (int n = 0; n < hits.size(); n++) {
            Mods.Hit hh = hits.get(n);
            if (ry + 70 >= area.y && ry <= area.y + area.height) {
                if (n % 2 == 1) {
                    g.setColor(new Color(255, 255, 255, 5));
                    g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 68, 10, 10));
                }
                avatar(g, x + 22, ry + 13, 42, hh.title(), hh.color());
                g.setFont(interBold.deriveFont(13.5f));
                g.setColor(TEXT);
                String title = ellipsizeEnd(g, hh.title(), 280);
                g.drawString(title, x + 78, ry + 26);
                int titleW = g.getFontMetrics().stringWidth(title);
                g.setFont(inter.deriveFont(11.5f));
                g.setColor(FAINT);
                g.drawString("by " + hh.author() + "  ·  " + Mods.count(hh.downloads()) + " downloads", x + 78 + titleW + 10, ry + 26);
                g.setColor(DIM);
                g.setFont(inter.deriveFont(12f));
                g.drawString(ellipsizeEnd(g, hh.description(), w - 260), x + 78, ry + 47);

                String st = modState.get(hh.projectId());
                String option = InstallerCore.optionForSlug(hh.slug());
                int bx = x + w - 30 - 130;
                if (option != null) {
                    g.setFont(inter.deriveFont(11f));
                    g.setColor(FAINT);
                    center(g, "Part of " + option, bx, ry + 18, 130, 32);
                } else if ("installing".equals(st)) {
                    g.setColor(new Color(0x2A1418));
                    g.fill(new RoundRectangle2D.Double(bx, ry + 18, 130, 32, 10, 10));
                    int pw2 = (int) (130 * Math.max(0.06, modProgress));
                    g.setColor(new Color(225, 29, 46, 140));
                    g.fill(new RoundRectangle2D.Double(bx, ry + 18, pw2, 32, 10, 10));
                    g.setFont(interBold.deriveFont(12f));
                    g.setColor(TEXT);
                    center(g, "Installing…", bx, ry + 18, 130, 32);
                } else if ("installed".equals(st) || installedProjects.contains(hh.projectId())) {
                    g.setColor(new Color(61, 220, 132, 22));
                    g.fill(new RoundRectangle2D.Double(bx, ry + 18, 130, 32, 10, 10));
                    icon(g, "check", bx + 34, ry + 34, 10, OK);
                    g.setFont(interBold.deriveFont(12f));
                    g.setColor(OK);
                    g.drawString("Installed", bx + 46, ry + 38);
                } else {
                    smallPrimary(g, "hit-install:" + n, bx, ry + 18, 130, 32, "Install", "download");
                    if (st != null) {
                        g.setFont(inter.deriveFont(10.5f));
                        g.setColor(RED_HI);
                        String m = ellipsizeEnd(g, st, w - 260);
                        g.drawString(m, x + 78, ry + 64);
                    }
                }
            }
            ry += 74;
        }
        if (hits.size() < hitsTotal) {
            ghostButton(g, "mods-more", x + w / 2 - 80, ry + 6, 160, 34, searching ? "Loading…" : "Load more", false);
            ry += 50;
        }
        int content = ry + Math.round(off) - (ly + 10);
        g.setClip(was);
        clip = null;
        scrollArea(g, "browse", area, content);
        return field;
    }

    // ------------------------------------------------------------------ settings

    private Rectangle paintSettings(Graphics2D g) {
        pageTitle(g, "Settings", null);
        int lx = CX, y = 92, lw = 440, h = H - y - 26;
        card(g, lx, y, lw, h, true);
        int ix = lx + 26, iw = lw - 52;
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Game", ix, y + 38);

        label(g, "MINECRAFT FOLDER", ix, y + 72);
        box(g, ix, y + 82, iw, 38);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(ellipsize(g, settings.minecraftDir().toString(), iw - 100), ix + 12, y + 106);
        smallButton(g, "set-folder", ix + iw - 72, y + 88, 64, 26, "Change");

        label(g, "MEMORY FOR NEW INSTANCES", ix, y + 150);
        memoryChips(g, ix, y + 160, iw, settings.defaultMemoryGb);
        g.setFont(inter.deriveFont(11f));
        g.setColor(DIM);
        g.drawString("Recommended for this PC: " + recommendedGb + " GB" + (totalGb > 0 ? "  ·  " + totalGb + " GB RAM" : ""), ix, y + 211);

        label(g, "JAVA (OPTIONAL)", ix, y + 246);
        box(g, ix, y + 256, iw, 38);
        Rectangle field = new Rectangle(ix + 12, y + 261, iw - 170, 28);
        if (javaField.getText().isEmpty()) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString("The Minecraft Launcher's own Java", ix + 13, y + 280);
        }
        smallButton(g, "java-browse", ix + iw - 146, y + 262, 72, 26, "Browse");
        smallButton(g, "java-clear", ix + iw - 70, y + 262, 62, 26, "Clear");
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        int hy = y + 316;
        for (String line : wrapLines(g, "Leave empty unless you know you need it: the Minecraft Launcher picks the right Java for "
                + InstallerCore.MC_VERSION + ". A path set here is used by every Evade instance (javaw.exe on Windows).", iw, 3)) {
            g.drawString(line, ix, hy);
            hy += 16;
        }

        // about, at the foot of the Game card
        g.setColor(BORDER);
        g.fillRect(lx + 1, y + h - 74, lw - 2, 1);
        g.drawImage(mark, ix, y + h - 56, 30, 30, null);
        g.setFont(interBold.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString("Evade Launcher v" + version, ix + 42, y + h - 41);
        g.setFont(inter.deriveFont(11f));
        g.setColor(FAINT);
        g.drawString("Sign-in and the game itself run in the official Minecraft Launcher.", ix + 42, y + h - 24);

        int rx = lx + lw + 18, rw = CW - lw - 18, rh = 278;
        card(g, rx, y, rw, rh, false);
        int jx = rx + 24, jw = rw - 48;
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Launcher", jx, y + 38);
        boolean tray = clips != null && clips.settings.minimizeToTray;
        settingRow(g, "set-close", jx, y + 60, jw, "Hide the launcher on Play", tray ? "To the tray, so Evade Clips keeps recording" : "After the Minecraft Launcher opens", settings.closeOnPlay);
        settingRow(g, "set-updates", jx, y + 112, jw, "Check for updates on start", "Updates this launcher itself", settings.checkUpdates);
        if (clips != null) {
            settingRow(g, "set-replay", jx, y + 164, jw, "Instant replay (Evade Clips)", "Records the last moments while Minecraft runs", clips.settings.replayEnabled);
            settingRow(g, "set-tray", jx, y + 216, jw, "Keep running in the tray", "Closing the window keeps instant replay going", tray);
        } else {
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(FAINT);
            int ly = y + 182;
            for (String l : wrapLines(g, clipsNote != null ? clipsNote : "Evade Clips isn't running.", jw, 3)) { g.drawString(l, jx, ly); ly += 16; }
        }

        int fy = y + rh + 16, fh = h - rh - 16;
        card(g, rx, fy, rw, fh, false);
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Folders", jx, fy + 38);
        String[][] f = {{"open-mc", "Minecraft"}, {"open-inst", "This instance"}, {"open-clips", "Clips"}, {"open-data", "Launcher"}};
        int bw = (jw - 10) / 2;
        for (int i = 0; i < f.length; i++) {
            int bx = jx + (i % 2) * (bw + 10), by = fy + 56 + (i / 2) * 44;
            ghostButton(g, f[i][0], bx, by, bw, 36, f[i][1], false, "folder");
        }
        if (legacyClips && fy + 56 + 2 * 44 + 36 < fy + fh - 10) {
            ghostButton(g, "clips-legacy", jx, fy + 56 + 2 * 44, jw, 36, "Remove the old separate Evade Clips app", true, "trash");
        }
        g.setFont(inter.deriveFont(11f));
        g.setColor(FAINT);
        return field;
    }

    private void settingRow(Graphics2D g, String id, int x, int y, int w, String title, String sub, boolean on) {
        g.setFont(inter.deriveFont(13f));
        g.setColor(TEXT);
        g.drawString(title, x, y + 15);
        g.setFont(inter.deriveFont(11f));
        g.setColor(FAINT);
        g.drawString(sub, x, y + 32);
        toggle(g, id + "-sw", x + w - 38, y + 6, on, true);
        hit(id, new Rectangle(x, y - 2, w, 40));
    }

    // ------------------------------------------------------------------ overlays

    private void paintToast(Graphics2D g) {
        if (toast == null) return;
        long age = System.currentTimeMillis() - toastAt;
        if (age > 3600) { toast = null; return; }
        float a = age < 200 ? age / 200f : age > 3200 ? (3600 - age) / 400f : 1f;
        g.setFont(interBold.deriveFont(12.5f));
        int tw = g.getFontMetrics().stringWidth(toast) + 48;
        int x = CX + (CW - tw) / 2, y = H - 64 + Math.round((1 - a) * 10);
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, a))));
        shadow(g, x, y, tw, 38);
        g.setColor(new Color(0x1C1C22));
        g.fill(new RoundRectangle2D.Double(x, y, tw, 38, 38, 38));
        g.setColor(new Color(255, 255, 255, 24));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, tw - 1, 37, 37, 37));
        g.setColor(RED_HI);
        g.fill(new Ellipse2D.Double(x + 16, y + 16, 6, 6));
        g.setColor(TEXT);
        g.drawString(toast, x + 30, y + 24);
        g.setComposite(was);
    }

    private void paintPopup(Graphics2D g) {
        Popup p = popup;
        int h = p.items().size() * 38 + 12;
        shadow(g, p.x(), p.y(), p.w(), h);
        g.setColor(new Color(0x18181E));
        g.fill(new RoundRectangle2D.Double(p.x(), p.y(), p.w(), h, 14, 14));
        g.setColor(new Color(0x2E2E38));
        g.draw(new RoundRectangle2D.Double(p.x() + .5, p.y() + .5, p.w() - 1, h - 1, 14, 14));
        int y = p.y() + 6;
        for (String[] it : p.items()) {
            float t = anim(it[0], it[0].equals(hovered));
            if (t > 0) {
                g.setColor(new Color(255, 255, 255, (int) (14 * t)));
                g.fill(new RoundRectangle2D.Double(p.x() + 6, y, p.w() - 12, 38, 9, 9));
            }
            g.setFont(inter.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, it[1], p.w() - 56), p.x() + 18, y + 24);
            if (!it[2].isEmpty()) icon(g, "check", p.x() + p.w() - 26, y + 19, 10, RED_HI);
            hitMap.put(it[0], new Rectangle(p.x() + 6, y, p.w() - 12, 38));
            y += 38;
        }
    }

    private Rectangle paintModal(Graphics2D g) {
        Modal m = modal;
        g.setColor(new Color(4, 4, 6, 170));
        if (roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        else g.fillRect(0, 0, W, H);
        boolean server = m.kind().equals("server");
        boolean text = m.kind().equals("create") || m.kind().equals("rename") || server;
        boolean create = m.kind().equals("create");
        int w = 440, h = text ? (create || server ? 346 : 256) : 200;
        int x = (W - w) / 2, y = (H - h) / 2;
        card(g, x, y, w, h, true);
        int ix = x + 28, iw = w - 56;
        g.setFont(interBold.deriveFont(19f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, m.title(), iw), ix, y + 46);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        int ly = y + 72;
        for (String line : wrapLines(g, m.body(), iw, 3)) { g.drawString(line, ix, ly); ly += 18; }
        Rectangle field = null;
        if (text) {
            int fy = ly + 8;
            label(g, "NAME", ix, fy + 6);
            box(g, ix, fy + 16, iw, 40);
            field = new Rectangle(ix + 12, fy + 22, iw - 24, 28);
            if (modalField.getText().isEmpty()) {
                g.setFont(inter.deriveFont(13f));
                g.setColor(FAINT);
                g.drawString(server ? "Optional: shown under the icon" : "PvP, Survival, Builds…", ix + 13, fy + 41);
            }
            if (server) {
                label(g, "ADDRESS", ix, fy + 84);
                box(g, ix, fy + 94, iw, 40);
                if (modalField2.getText().isEmpty()) {
                    g.setFont(inter.deriveFont(13f));
                    g.setColor(FAINT);
                    g.drawString("play.example.net or 1.2.3.4:25565", ix + 13, fy + 119);
                }
            }
            if (create) {
                label(g, "COPY GAME SETTINGS FROM", ix, fy + 84);
                String from = "Nothing (fresh settings)";
                if ("vanilla".equals(modalCopyFrom)) from = "Minecraft (your normal game)";
                for (Launcher.Instance i : instances) if (i.id.equals(modalCopyFrom)) from = i.name;
                float t = anim("modal-copy", "modal-copy".equals(hovered));
                g.setColor(mix(FIELD, new Color(0x15151A), t));
                g.fill(new RoundRectangle2D.Double(ix, fy + 94, iw, 40, 10, 10));
                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Double(ix + .5, fy + 94.5, iw - 1, 39, 10, 10));
                g.setFont(inter.deriveFont(13f));
                g.setColor(TEXT);
                g.drawString(from, ix + 13, fy + 119);
                icon(g, "chevron-down", ix + iw - 20, fy + 114, 9, DIM);
                hitMap.put("modal-copy", new Rectangle(ix, fy + 94, iw, 40));
            }
        }
        boolean danger = m.kind().equals("delete") || m.kind().equals("remove-mod");
        int bw = 130;
        ghostButton(g, "modal-cancel", x + w - 28 - 2 * bw - 10, y + h - 64, bw, 40, "Cancel", false);
        if (danger) ghostButton(g, "modal-ok", x + w - 28 - bw, y + h - 64, bw, 40, m.confirm(), true, null, true);
        else smallPrimary(g, "modal-ok", x + w - 28 - bw, y + h - 64, bw, 40, m.confirm(), null);
        return field;
    }

    // ------------------------------------------------------------------ widgets

    private void hit(String id, Rectangle r) {
        if (clip != null) r = r.intersection(clip);
        if (!r.isEmpty()) hitMap.put(id, r);
    }

    private void scrollArea(Graphics2D g, String id, Rectangle area, int contentH) {
        scrollAreas.put(id, new int[]{area.x, area.y, area.width, area.height, contentH});
        float[] s = scroll.computeIfAbsent(id, k -> new float[2]);
        float max = Math.max(0, contentH - area.height);
        if (s[0] > max) s[0] = max;
        if (s[1] > max) s[1] = max;
        if (contentH <= area.height) return;
        int th = Math.max(30, area.height * area.height / contentH);
        int ty = area.y + Math.round((area.height - th) * (s[1] / max));
        g.setColor(new Color(255, 255, 255, 30));
        g.fill(new RoundRectangle2D.Double(area.x + area.width - 7, ty, 4, th, 4, 4));
        // soft fade at the bottom edge so cut-off rows don't end abruptly
        if (s[1] < max - 1) {
            g.setPaint(new GradientPaint(0, area.y + area.height - 24, new Color(17, 17, 21, 0), 0, area.y + area.height, new Color(17, 17, 21, 230)));
            g.fillRect(area.x, area.y + area.height - 24, area.width - 10, 24);
        }
    }

    private void memoryChips(Graphics2D g, int x, int y, int w, int value) {
        int[] opts = memoryChoices;
        int gap = 6, sw = (w - (opts.length - 1) * gap) / Math.max(1, opts.length);
        for (int i = 0; i < opts.length; i++) {
            int sx = x + i * (sw + gap);
            String id = "mem:" + opts[i];
            boolean on = value == opts[i];
            float t = anim(id, id.equals(hovered));
            g.setColor(on ? RED : mix(CARD_2, new Color(0x22222A), t));
            g.fill(new RoundRectangle2D.Double(sx, y, sw, 34, 9, 9));
            if (!on) {
                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Double(sx + .5, y + .5, sw - 1, 33, 9, 9));
            }
            g.setFont(interBold.deriveFont(12.5f));
            g.setColor(on ? Color.WHITE : DIM);
            center(g, opts[i] + " GB", sx, y, sw, 34);
            hit(id, new Rectangle(sx, y, sw, 34));
        }
    }

    private void switchRow(Graphics2D g, String id, int x, int y, int w, String text, boolean value, boolean available) {
        g.setFont(inter.deriveFont(13f));
        if (available) {
            g.setColor(TEXT);
            g.drawString(text, x, y + 14);
            toggle(g, id + "-sw", x + w - 38, y, value, true);
            hit(id, new Rectangle(x, y - 4, w, 28));
        } else {
            g.setColor(FAINT);
            g.drawString(text, x, y + 14);
            int nx = x + g.getFontMetrics().stringWidth(text) + 8;
            g.setFont(inter.deriveFont(11.5f));
            g.drawString(ellipsizeEnd(g, "not available right now", x + w - 46 - nx), nx, y + 14);
            toggle(g, id + "-sw", x + w - 38, y, value, false);
        }
    }

    private void toggle(Graphics2D g, String id, int tx, int ry, boolean value, boolean enabled) {
        float on = anim(id + "-on", value);
        if (enabled) {
            Color off = new Color(0x2A2A31);
            g.setPaint(new GradientPaint(0, ry, mix(off, RED_HI, on), 0, ry + 20, mix(off, RED, on)));
            g.fill(new RoundRectangle2D.Double(tx, ry, 38, 20, 20, 20));
            double kx = tx + 3 + on * 18;
            g.setColor(new Color(0, 0, 0, 50));
            g.fill(new Ellipse2D.Double(kx - 0.5, ry + 3.5, 15, 15));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(kx, ry + 3, 14, 14));
            if (id.startsWith("mod-toggle:")) hit(id, new Rectangle(tx - 4, ry - 4, 46, 28));
        } else {
            g.setColor(new Color(0x1C1C22));
            g.fill(new RoundRectangle2D.Double(tx, ry, 38, 20, 20, 20));
            g.setColor(BORDER);
            g.draw(new RoundRectangle2D.Double(tx + .5, ry + .5, 37, 19, 19, 19));
            g.setColor(new Color(0x3A3A44));
            g.fill(new Ellipse2D.Double(tx + 3 + on * 18, ry + 3, 14, 14));
        }
    }

    private void button(Graphics2D g, String id, int x, int y, int w, int h, boolean danger) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(255, 255, 255, 0), danger ? RED : new Color(255, 255, 255, 30), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        g.setColor(mix(DIM, TEXT, t));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double cx = x + w / 2.0, cy = y + h / 2.0;
        if (danger) {
            g.draw(new Line2D.Double(cx - 4.5, cy - 4.5, cx + 4.5, cy + 4.5));
            g.draw(new Line2D.Double(cx + 4.5, cy - 4.5, cx - 4.5, cy + 4.5));
        } else {
            g.draw(new Line2D.Double(cx - 5, cy, cx + 5, cy));
        }
        g.setStroke(new BasicStroke(1f));
        hitMap.put(id, new Rectangle(x, y, w, h));
    }

    private void bigButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean enabled) {
        float t = enabled ? anim(id, id.equals(hovered)) : 0;
        if (enabled) {
            glow(g, x + w / 2f, y + h * 0.75f, w * 0.6f, h * 0.9f, (int) (70 + 60 * t));
            g.setPaint(new GradientPaint(0, y, mix(RED_HI, new Color(0xFF6070), t), 0, y + h, mix(RED, RED_HI, t)));
        } else {
            g.setColor(new Color(0x2A1418));
        }
        g.fill(new RoundRectangle2D.Double(x, y - t * 1.5, w, h, 12, 12));
        if (enabled) {
            g.setColor(new Color(255, 255, 255, 50));
            g.fillRect(x + 8, (int) (y - t * 1.5) + 1, w - 16, 1);
        }
        g.setFont(interBold.deriveFont(15f));
        g.setColor(enabled ? Color.WHITE : FAINT);
        center(g, label, x, (int) (y - t * 1.5), w, h);
        hit(id, new Rectangle(x, y, w, h));
    }

    /** A soft red glow: an elliptical radial gradient that fades to nothing before its edges (no visible box). */
    private static void glow(Graphics2D g, float cx, float cy, float rx, float ry, int alpha) {
        Graphics2D q = (Graphics2D) g.create();
        q.translate(cx, cy);
        q.scale(1, ry / rx);
        q.setPaint(new RadialGradientPaint(0, 0, rx, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, Math.min(255, alpha)), new Color(225, 29, 46, 0)}));
        q.fill(new Ellipse2D.Float(-rx, -rx, 2 * rx, 2 * rx));
        q.dispose();
    }

    /** A compact red button, optionally with an icon before the label. */
    private void smallPrimary(Graphics2D g, String id, int x, int y, int w, int h, String label, String iconKind) {
        float t = anim(id, id.equals(hovered));
        g.setPaint(new GradientPaint(0, y, mix(RED_HI, new Color(0xFF6070), t), 0, y + h, mix(RED, RED_HI, t)));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(new Color(255, 255, 255, 45));
        g.fillRect(x + 6, y + 1, w - 12, 1);
        g.setFont(interBold.deriveFont(12.5f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label), iw = iconKind != null ? 12 : 0, gap = iconKind != null ? 8 : 0;
        int tx = x + (w - tw - iw - gap) / 2;
        if (iconKind != null) icon(g, iconKind, tx + iw / 2, y + h / 2, iw, Color.WHITE);
        g.setColor(Color.WHITE);
        g.drawString(label, tx + iw + gap, y + (h - fm.getHeight()) / 2f + fm.getAscent());
        hit(id, new Rectangle(x, y, w, h));
    }

    private void ghostButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger) {
        ghostButton(g, id, x, y, w, h, label, danger, null, false);
    }

    private void ghostButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger, String iconKind) {
        ghostButton(g, id, x, y, w, h, label, danger, iconKind, false);
    }

    private void ghostButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger, String iconKind, boolean solid) {
        float t = anim(id, id.equals(hovered));
        Color base = solid ? new Color(0x3A1218) : new Color(0x18181D);
        g.setColor(danger ? mix(base, new Color(0x4A141B), t) : mix(base, new Color(0x23232A), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(danger ? mix(new Color(0x3A1D22), RED, t) : mix(BORDER, new Color(0x3A3A44), t));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 10, 10));
        g.setFont(interBold.deriveFont(12.5f));
        int room = w - 16 - (iconKind != null ? 20 : 0);
        if (g.getFontMetrics().stringWidth(label) > room) g.setFont(interBold.deriveFont(11.5f));
        FontMetrics fm = g.getFontMetrics();
        String s = ellipsizeEnd(g, label, room);
        int tw = fm.stringWidth(s), iw = iconKind != null ? 14 : 0, gap = iconKind != null ? 8 : 0;
        int tx = x + (w - tw - iw - gap) / 2;
        Color fg = danger ? mix(new Color(0xFF7A86), Color.WHITE, t) : mix(new Color(0xD6D6DC), TEXT, t);
        if (iconKind != null) icon(g, iconKind, tx + iw / 2, y + h / 2, iw, danger ? fg : mix(DIM, TEXT, t));
        g.setColor(fg);
        g.drawString(s, tx + iw + gap, y + (h - fm.getHeight()) / 2f + fm.getAscent());
        hit(id, new Rectangle(x, y, w, h));
    }

    private void smallButton(Graphics2D g, String id, int x, int y, int w, int h, String label) {
        float hc = anim(id, id.equals(hovered));
        g.setColor(mix(CARD_2.brighter(), RED, hc));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        g.setFont(interBold.deriveFont(11.5f));
        g.setColor(TEXT);
        center(g, label, x, y, w, h);
        hit(id, new Rectangle(x, y, w, h));
    }

    private void iconButton(Graphics2D g, String id, int x, int y, int s, String kind, boolean danger) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(255, 255, 255, 0), danger ? new Color(225, 29, 46, 60) : new Color(255, 255, 255, 24), t));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 9, 9));
        icon(g, kind, x + s / 2, y + s / 2, 14, mix(FAINT, danger ? RED_HI : TEXT, t));
        hit(id, new Rectangle(x, y, s, s));
    }

    /** Underline-free text link. align: 0 left, 1 right, 2 centred on x. */
    private void textLink(Graphics2D g, String id, String s, int x, int y, int align) {
        float t = anim(id, id.equals(hovered));
        g.setFont(inter.deriveFont(12f));
        int w = g.getFontMetrics().stringWidth(s);
        int lx = align == 1 ? x - w : align == 2 ? x - w / 2 : x;
        g.setColor(mix(FAINT, TEXT, t));
        g.drawString(s, lx, y);
        hit(id, new Rectangle(lx - 6, y - 15, w + 12, 22));
    }

    /** A rounded tile with the first letter of a name, tinted with {@code rgb}. */
    private void avatar(Graphics2D g, int x, int y, int s, String name, int rgb) {
        Color c = new Color(rgb & 0xFFFFFF);
        // keep tiles in the launcher's dark range: darken bright colours, lift very dark ones
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        Color top = Color.getHSBColor(hsb[0], Math.min(0.85f, hsb[1]), Math.max(0.32f, Math.min(0.78f, hsb[2])));
        Color bottom = Color.getHSBColor(hsb[0], Math.min(0.9f, hsb[1]), Math.max(0.18f, Math.min(0.5f, hsb[2] * 0.6f)));
        g.setPaint(new GradientPaint(x, y, top, x + s, y + s, bottom));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, s * 0.32, s * 0.32));
        g.setColor(new Color(255, 255, 255, 30));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, s - 1, s - 1, s * 0.32, s * 0.32));
        String letter = name == null || name.isBlank() ? "?" : name.strip().substring(0, 1).toUpperCase();
        g.setFont(interBold.deriveFont(s * 0.42f));
        g.setColor(Color.WHITE);
        center(g, letter, x, y, s, s);
    }

    private static int hashColor(String s) {
        int h = s == null ? 0 : s.toLowerCase().hashCode();
        float hue = ((h >>> 8) % 360) / 360f;
        return Color.HSBtoRGB(hue, 0.55f, 0.6f);
    }

    private void stepRow(Graphics2D g, int x, int y, String name, boolean done, boolean active) {
        if (done) {
            g.setColor(RED);
            g.fill(new Ellipse2D.Double(x, y, 20, 20));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D c = new Path2D.Double();
            c.moveTo(x + 5.5, y + 10.5);
            c.lineTo(x + 8.8, y + 13.6);
            c.lineTo(x + 14.5, y + 7);
            g.draw(c);
            g.setStroke(new BasicStroke(1f));
        } else {
            g.setColor(BORDER);
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(x + 1, y + 1, 18, 18));
            if (active) {
                double a = ((System.currentTimeMillis() - start) % 900) / 900.0 * 360;
                g.setColor(RED_HI);
                g.draw(new Arc2D.Double(x + 1, y + 1, 18, 18, -a, 100, Arc2D.OPEN));
            }
            g.setStroke(new BasicStroke(1f));
        }
        g.setFont(inter.deriveFont(13f));
        g.setColor(done || active ? TEXT : FAINT);
        g.drawString(name, x + 32, y + 15);
    }

    private void box(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 10, 10));
    }

    private void label(Graphics2D g, String s, int x, int y) {
        g.setFont(interBold.deriveFont(9.5f));
        g.setColor(FAINT);
        spaced(g, s, x, y, 1.6f);
    }

    /** Line icons, drawn with shapes (fonts don't reliably have these glyphs). */
    static void icon(Graphics2D g, String kind, int cx, int cy, int s, Color c) {
        g.setColor(c);
        Graphics2D q = (Graphics2D) g.create();
        q.translate(cx, cy);
        double k = s / 18.0;
        q.scale(k, k);
        q.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case "home" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, -1); p.lineTo(0, -8); p.lineTo(8, -1);
                q.draw(p);
                Path2D b = new Path2D.Double();
                b.moveTo(-6, -2.5); b.lineTo(-6, 8); b.lineTo(6, 8); b.lineTo(6, -2.5);
                q.draw(b);
                q.draw(new RoundRectangle2D.Double(-2, 2.5, 4, 5.5, 1.5, 1.5));
            }
            case "layers" -> {
                for (int i = 0; i < 3; i++) {
                    Path2D p = new Path2D.Double();
                    double o = -5 + i * 5;
                    p.moveTo(-8, o); p.lineTo(0, o - 4); p.lineTo(8, o); p.lineTo(0, o + 4); p.closePath();
                    if (i == 0) q.draw(p);
                    else {
                        Path2D v = new Path2D.Double();
                        v.moveTo(-8, o); v.lineTo(0, o + 4); v.lineTo(8, o);
                        q.draw(v);
                    }
                }
            }
            case "cube" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(0, -8.5); p.lineTo(7.5, -4.3); p.lineTo(7.5, 4.3); p.lineTo(0, 8.5); p.lineTo(-7.5, 4.3); p.lineTo(-7.5, -4.3); p.closePath();
                q.draw(p);
                Path2D in = new Path2D.Double();
                in.moveTo(-7.5, -4.3); in.lineTo(0, 0); in.lineTo(7.5, -4.3); in.moveTo(0, 0); in.lineTo(0, 8.5);
                q.draw(in);
            }
            case "gear" -> {
                q.draw(new Ellipse2D.Double(-2.8, -2.8, 5.6, 5.6));
                Path2D p = new Path2D.Double();
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a = Math.PI * 2 * i / (teeth * 2) - Math.PI / 2;
                    double r1 = 8.2, r2 = 6.0;
                    double a0 = a - Math.PI / (teeth * 2) * 0.55, a1 = a + Math.PI / (teeth * 2) * 0.55;
                    double r = i % 2 == 0 ? r1 : r2;
                    if (i == 0) p.moveTo(Math.cos(a0) * r, Math.sin(a0) * r);
                    else p.lineTo(Math.cos(a0) * r, Math.sin(a0) * r);
                    p.lineTo(Math.cos(a1) * r, Math.sin(a1) * r);
                }
                p.closePath();
                q.draw(p);
            }
            case "play" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-6, -8); p.lineTo(8, 0); p.lineTo(-6, 8); p.closePath();
                q.fill(p);
            }
            case "chevron-down", "chevron-up" -> {
                double d = kind.equals("chevron-up") ? -1 : 1;
                Path2D p = new Path2D.Double();
                p.moveTo(-7, -3.5 * d); p.lineTo(0, 3.5 * d); p.lineTo(7, -3.5 * d);
                q.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                q.draw(p);
            }
            case "check" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, 0); p.lineTo(-2.5, 6); p.lineTo(8.5, -6);
                q.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                q.draw(p);
            }
            case "plus" -> {
                q.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                q.draw(new Line2D.Double(-7, 0, 7, 0));
                q.draw(new Line2D.Double(0, -7, 0, 7));
            }
            case "search" -> {
                q.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                q.draw(new Ellipse2D.Double(-8, -8, 12.5, 12.5));
                q.draw(new Line2D.Double(3, 3, 8, 8));
            }
            case "trash" -> {
                q.draw(new Line2D.Double(-7.5, -5, 7.5, -5));
                q.draw(new Line2D.Double(-2.5, -8, 2.5, -8));
                Path2D p = new Path2D.Double();
                p.moveTo(-5.5, -5); p.lineTo(-4.5, 8); p.lineTo(4.5, 8); p.lineTo(5.5, -5);
                q.draw(p);
                q.draw(new Line2D.Double(-1.5, -1.5, -1.5, 4.5));
                q.draw(new Line2D.Double(1.5, -1.5, 1.5, 4.5));
            }
            case "clip" -> {
                // a film frame with a record dot
                q.draw(new RoundRectangle2D.Double(-8.5, -6.5, 17, 13, 3.5, 3.5));
                q.fill(new Ellipse2D.Double(-3, -3, 6, 6));
                q.draw(new Line2D.Double(-8.5, -2.5, -6, -2.5));
                q.draw(new Line2D.Double(6, 2.5, 8.5, 2.5));
            }
            case "bell" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-6.5, 4.5); p.lineTo(-6.5, -1.5); p.curveTo(-6.5, -6, -3.5, -8, 0, -8); p.curveTo(3.5, -8, 6.5, -6, 6.5, -1.5);
                p.lineTo(6.5, 4.5); p.lineTo(8, 6); p.lineTo(-8, 6); p.closePath();
                q.draw(p);
                q.draw(new Line2D.Double(-2, 8.5, 2, 8.5));
            }
            case "globe" -> {
                q.draw(new Ellipse2D.Double(-8, -8, 16, 16));
                q.draw(new Ellipse2D.Double(-3.5, -8, 7, 16));
                q.draw(new Line2D.Double(-8, 0, 8, 0));
            }
            case "book" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(0, -5); p.curveTo(-3, -7.5, -6, -7.5, -8.5, -6.5); p.lineTo(-8.5, 6.5); p.curveTo(-6, 5.5, -3, 5.5, 0, 7.5);
                p.curveTo(3, 5.5, 6, 5.5, 8.5, 6.5); p.lineTo(8.5, -6.5); p.curveTo(6, -7.5, 3, -7.5, 0, -5); p.lineTo(0, 7.5);
                q.draw(p);
            }
            case "list" -> {
                for (int i = -1; i <= 1; i++) {
                    q.fill(new Ellipse2D.Double(-8.5, i * 5.5 - 1.2, 2.4, 2.4));
                    q.draw(new Line2D.Double(-3.5, i * 5.5, 8, i * 5.5));
                }
            }
            case "chat" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, -6); p.lineTo(8, -6); p.lineTo(8, 4); p.lineTo(-1, 4); p.lineTo(-5, 8); p.lineTo(-5, 4); p.lineTo(-8, 4); p.closePath();
                q.draw(p);
            }
            case "video" -> {
                q.draw(new RoundRectangle2D.Double(-8.5, -6, 17, 12, 4, 4));
                Path2D p = new Path2D.Double();
                p.moveTo(-2, -3); p.lineTo(3.5, 0); p.lineTo(-2, 3); p.closePath();
                q.fill(p);
            }
            case "music" -> {
                q.draw(new Line2D.Double(2, -8, 2, 4));
                q.draw(new Line2D.Double(2, -8, 7, -5.5));
                q.fill(new Ellipse2D.Double(-4.5, 1.5, 7, 6));
            }
            case "folder" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, 6.5); p.lineTo(-8, -6.5); p.lineTo(-2.5, -6.5); p.lineTo(-0.5, -4); p.lineTo(8, -4); p.lineTo(8, 6.5); p.closePath();
                q.draw(p);
            }
            case "download" -> {
                q.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                q.draw(new Line2D.Double(0, -8, 0, 3));
                Path2D p = new Path2D.Double();
                p.moveTo(-5, -2); p.lineTo(0, 3.5); p.lineTo(5, -2);
                q.draw(p);
                q.draw(new Line2D.Double(-7, 8, 7, 8));
            }
            default -> { }
        }
        q.dispose();
    }

    // ------------------------------------------------------------------ text helpers

    private void spaced(Graphics2D g, String s, int x, int y, float spacing) {
        float cx = x;
        FontMetrics fm = g.getFontMetrics();
        for (char c : s.toCharArray()) {
            g.drawString(String.valueOf(c), cx, y);
            cx += fm.charWidth(c) + spacing;
        }
    }

    private static void center(Graphics2D g, String s, int x, int y, int w, int h) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, x + (w - fm.stringWidth(s)) / 2f, y + (h - fm.getHeight()) / 2f + fm.getAscent());
    }

    private static void wrap(Graphics2D g, String text, int x, int y, int w, int lh) {
        for (String line : wrapLines(g, text, w, 20)) {
            g.drawString(line, x, y);
            y += lh;
        }
    }

    /** Word-wraps text to at most maxLines lines of width w; the last line ends in "…" if text is left over. */
    static List<String> wrapLines(Graphics2D g, String text, int w, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String test = line.isEmpty() ? words[i] : line + " " + words[i];
            if (fm.stringWidth(test) > w && !line.isEmpty()) {
                if (lines.size() == maxLines - 1) break;
                lines.add(line.toString());
                line = new StringBuilder(words[i]);
            } else line = new StringBuilder(test);
        }
        if (i < words.length) {
            StringBuilder rest = new StringBuilder(line);
            for (; i < words.length; i++) rest.append(' ').append(words[i]);
            lines.add(ellipsizeEnd(g, rest.toString(), w, true));
        } else if (!line.isEmpty()) {
            lines.add(ellipsizeEnd(g, line.toString(), w));
        }
        return lines;
    }

    static String ellipsizeEnd(Graphics2D g, String s, int w) {
        return ellipsizeEnd(g, s, w, false);
    }

    private static String ellipsizeEnd(Graphics2D g, String s, int w, boolean forceDots) {
        FontMetrics fm = g.getFontMetrics();
        if (!forceDots && fm.stringWidth(s) <= w) return s;
        String e = "…";
        for (int i = s.length(); i > 0; i--) {
            String t = s.substring(0, i).stripTrailing() + e;
            if (fm.stringWidth(t) <= w && (forceDots || i < s.length())) return t;
        }
        return e;
    }

    private static String ellipsize(Graphics2D g, String s, int w) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) return s;
        String e = "…";
        for (int i = 1; i < s.length(); i++) {
            String t = e + s.substring(i);
            if (fm.stringWidth(t) <= w) return t;
        }
        return e;
    }

    // ------------------------------------------------------------------ misc

    private float anim(String id, boolean target) {
        float to = target ? 1f : 0f;
        float v = hover.getOrDefault(id, to);
        v += (to - v) * 0.22f;
        if (Math.abs(to - v) < 0.002f) v = to;
        else animating = true;
        hover.put(id, v);
        return v;
    }

    static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private static Font font(String path) {
        try (InputStream in = LauncherView.class.getResourceAsStream(path)) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }

    static Image image(String path) {
        try (InputStream in = LauncherView.class.getResourceAsStream(path)) {
            return ImageIO.read(in);
        } catch (Exception e) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
    }

    /** Opens a popup the way a click on {@code id} would (for screenshots). */
    void openPopupFor(String id) {
        snapshot(1f);
        click(id);
    }

    /** Opens a dialog (for screenshots). */
    void openModalForShots(String kind, String text) {
        Launcher.Instance foc = focused();
        if (kind.equals("create")) click("inst-new");
        else if (kind.equals("delete")) click("inst-del");
        else if (kind.equals("rename")) click("inst-rename");
        if (text != null) {
            syncingFields = true;
            modalField.setText(text);
            syncingFields = false;
        }
        if (foc == null) modal = null;
    }

    void closeOverlays() {
        popup = null;
        modal = null;
    }

    /** Renders the current state to an image (screenshots and tests). */
    BufferedImage snapshot(float scale) {
        BufferedImage img = new BufferedImage(Math.round(W * scale), Math.round(H * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        g.setComposite(AlphaComposite.SrcOver);
        setSize(W, H);
        shownProgress = progress;
        for (float[] s : scroll.values()) s[1] = s[0];
        boolean was = roundCorners;
        roundCorners = true;
        // Lay the text fields out first (they're positioned while painting), then paint everything with them.
        paintComponent(img.createGraphics());
        paint(g);
        roundCorners = was;
        g.dispose();
        return img;
    }
}
