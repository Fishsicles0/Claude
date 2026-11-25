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
import java.awt.geom.Rectangle2D;
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

    enum Page { HOME, INSTANCES, MODS, CLIPS, GAME, SETTINGS }

    /** Something the launcher told the player (the bell lists them). */
    record Notice(String title, String body, long at, boolean error) {}

    /** Where Play is: updating the instance, opening the Minecraft Launcher, waiting for the player there, or stuck. */
    enum Play { IDLE, UPDATING, LAUNCHING, LAUNCHED, NO_LAUNCHER, ERROR }

    // palette
    static final Color BG = new Color(0x0B0B0E), CARD = new Color(0x121216), CARD_2 = new Color(0x18181D),
            BORDER = new Color(0x232329), RED = new Color(0xE11D2E), RED_HI = new Color(0xFF4153),
            TEXT = new Color(0xF4F4F6), DIM = new Color(0x9A9AA5), FAINT = new Color(0x7C7C88) /* 4.5:1 on cards */, OK = new Color(0x3DDC84),
            AMBER = new Color(0xF5A524), FIELD = new Color(0x0E0E12),
            /** the rail's panel, page hairlines, and panels raised over the page */
            RAIL_BG = new Color(0x0E0E11), HAIRLINE = new Color(255, 255, 255, 14), RAISED = new Color(0x18181D);

    /** What the launcher does when the player clicks; {@link LauncherApp} implements these. */
    interface Actions {
        default void play() {}
        default void setupInstall() {}
        default void retryRelease() {}
        /** Download, check and start the newer launcher the player agreed to (see SelfUpdate). */
        default void updateLauncher() {}
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
        default void manageAccount() {}
        default void takeOverClips() {}
        /** F5: reload what the page shows. */
        default void refresh(Page p) {}
        /** An instance was added outside the usual actions (imported): reload the list. */
        default void instancesChanged() {}
    }

    // ------------------------------------------------------------------ links (empty = not shown)
    static final String DISCORD = "https://evadeclient.com/discord", YOUTUBE = "", TIKTOK = ""; // the site page links the official invite

    final Font inter, interBold;
    final Image mark, word;

    // ------------------------------------------------------------------ state (EDT only)
    Actions actions = new Actions() {};
    Page page = Page.HOME;
    /** The Mods page (installed content, Modrinth browser, worlds, modpacks); see {@link ContentPage}. */
    ContentPage content;
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
    String version = LauncherInfo.VERSION;

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
    /** Open over the page: "bell", "friends", "server:<index>", or null. */
    String overlay;
    /** The full-body skin render for the profile card (null: a plain figure is drawn). */
    Image playerBody;
    /** The old separate Evade Clips app holds Clips' folder (a banner offers to close it); while it's closing. */
    boolean clipsBlocked, clipsBusy;

    /** A friend (filled in by the friends system once it exists; empty for now). */
    record Friend(String name, String uuid, String status, boolean online) {}
    List<Friend> friends = new ArrayList<>();

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
    Popup popup;
    Modal modal;
    /** The game's state, logs, crashes and instance settings (GamePages paints them). */
    final GamePages game;
    /** Appearance, keyboard shortcuts, instance groups/pins/sorting, zip export/import, What's new. */
    final ViewExtras extras;
    /** Import from other launchers, drag and drop, templates, optional extras, safe mode, shortcuts, memory advice. */
    final LauncherTools tools;

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
    final LinkedHashMap<String, Rectangle> hitMap = new LinkedHashMap<>();
    private final Map<String, Float> hover = new HashMap<>();
    final Map<String, float[]> scroll = new HashMap<>(); // id -> {target, shown}
    private final Map<String, int[]> scrollAreas = new LinkedHashMap<>(); // id -> {x, y, w, h, contentH}
    Rectangle clip;
    String hovered;
    private Point dragFrom;
    /**
     * The whole window drawn smaller on small screens (1 = full size): everything is laid out in the 1180×720
     * design size and painted through this scale, so it stays sharp (vector shapes and text, no bitmap stretch).
     */
    float uiScale = 1f;
    /** The smallest scale used: below it text gets too small to read. */
    static final float MIN_SCALE = 0.7f;

    /** Picks the scale that fits a usable screen area of {@code w}×{@code h} (from 1 down to {@link #MIN_SCALE}). */
    static float fitScale(int w, int h) {
        float s = Math.min(1f, Math.min((w - 12) / (float) W, (h - 12) / (float) H));
        return Math.max(MIN_SCALE, s);
    }

    void setUiScale(float s) {
        uiScale = s;
        setPreferredSize(new java.awt.Dimension(Math.round(W * s), Math.round(H * s)));
        for (JTextField f : new JTextField[]{searchField, modalField, modalField2, javaField}) f.setFont(inter.deriveFont(13f * s));
        bgLayer = null;
        revalidate();
        repaint();
    }

    /** A mouse position in design coordinates. */
    private Point logical(MouseEvent e) {
        return new Point(Math.round(e.getX() / uiScale), Math.round(e.getY() / uiScale));
    }

    private Rectangle toScreen(Rectangle r) {
        if (r == null || uiScale == 1f) return r;
        int x = Math.round(r.x * uiScale), y = Math.round(r.y * uiScale);
        return new Rectangle(x, y, Math.round((r.x + r.width) * uiScale) - x, Math.round((r.y + r.height) * uiScale) - y);
    }
    private final long start = System.currentTimeMillis();
    boolean animating;

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
            for (String n : new String[]{"menu", "hud", "styles", "clips", "settings", "theme", "bg-world", "bg-hud", "bg-menu", "bg-clips"}) {
                Image img = image("/evade/hero/" + n + ".jpg");
                if (img.getWidth(null) > 1) heroImages.put(n, img);
            }
            SwingUtilities.invokeLater(this::repaint);
        }, "hero-images");
        loader.setDaemon(true);
        loader.start();
        content = new ContentPage(this);
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
                else if (content != null && content.sheet != null) content.sheet = null;
                repaint();
            }
        };
        setFocusable(true);
        for (JComponent c : new JComponent[]{this, searchField, modalField, modalField2, javaField}) {
            c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
            c.getActionMap().put("escape", escape);
        }
        game = new GamePages(this);
        extras = new ViewExtras(this);
        extras.installKeys();
        tools = new LauncherTools(this);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateHover(logical(e)); }
            @Override public void mouseExited(MouseEvent e) { hovered = null; setCursor(Cursor.getDefaultCursor()); repaint(); }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (logical(e).y < 44 && hit(logical(e)) == null) dragFrom = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { dragFrom = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null) return;
                Window w = SwingUtilities.getWindowAncestor(LauncherView.this);
                if (w != null) w.setLocation(e.getXOnScreen() - dragFrom.x, e.getYOnScreen() - dragFrom.y);
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) click(hit(logical(e)));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { wheel(logical(e), e.getPreciseWheelRotation()); }
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
            if (showing && page == Page.HOME && !firstRun() && !LauncherTheme.reduceMotion() && System.currentTimeMillis() - slideAt > 8000 && modal == null) nextSlide(1);
            if (LauncherTheme.reduceMotion()) { slideFrom = -1; for (float[] s : scroll.values()) s[1] = s[0]; scrolling = false; }
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

    void wheel(Point p, double rot) {
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

    float scrollOf(String id) {
        float[] s = scroll.get(id);
        return s == null ? 0 : s[1];
    }

    void resetScroll(String id) { scroll.remove(id); }

    void click(String id) {
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
        if (extras.click(id)) { repaint(); return; }
        if (tools.click(id)) { repaint(); return; }
        if (content.click(id)) { repaint(); return; }
        switch (id) {
            case "close" -> { if (play != Play.UPDATING) actions.close(); }
            case "min" -> { if (w instanceof java.awt.Frame f) f.setState(java.awt.Frame.ICONIFIED); }
            case "nav-home" -> showPage(Page.HOME);
            case "nav-instances" -> showPage(Page.INSTANCES);
            case "nav-mods" -> showPage(Page.MODS);
            case "nav-clips" -> showPage(Page.CLIPS);
            case "nav-game" -> showPage(Page.GAME);
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
            case "clips-takeover" -> { if (!clipsBusy) actions.takeOverClips(); }
            case "status-pill" -> showPage(Page.CLIPS);
            case "manage-account" -> actions.manageAccount();
            case "friends", "friends-add" -> overlay = "friends";
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
                if (foc != null && !foc.isDefault() && game.running(foc)) toast(foc.name + " is running. Close the game first.");
                else if (foc != null && !foc.isDefault()) openModal(new Modal("delete", "Delete " + foc.name + "?",
                        InstanceTrash.summarize(foc.dir).text(foc.name), "Delete", foc, null), null);
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
            case "inst-packs" -> { if (foc != null) { foc.packs = !foc.packs; foc.dirty = true; actions.instanceChanged(foc); } }
            case "inst-lights" -> { if (foc != null) { foc.lights = !foc.lights; foc.dirty = true; actions.instanceChanged(foc); } }
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
            case "set-gpu" -> {
                settings.preferGpu = !settings.preferGpu;
                actions.settingsChanged();
                java.nio.file.Path mc = settings.minecraftDir();
                new Thread(settings.preferGpu ? () -> GpuPreference.apply(mc) : GpuPreference::undo, "gpu-preference").start();
            }
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
                if (id.startsWith("gp-")) game.click(id);
                else if (id.startsWith("pop:")) popupClick(id.substring(4));
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
        if (extras.popup(what)) { repaint(); return; }
        if (tools.popup(what)) { repaint(); return; }
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

    void openModal(Modal m, String text) {
        modal = m;
        modalCopyFrom = m.kind().equals("create") ? Launcher.DEFAULT_ID : null;
        if (m.kind().equals("create")) tools.template = InstanceTemplates.BLANK;
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
        if (m.kind().startsWith("c:")) { modal = null; content.confirm(m, text); requestFocusInWindow(); repaint(); return; }
        switch (m.kind()) {
            case "create" -> {
                if (text.isEmpty()) return;
                if (!tools.createInstance(text, modalCopyFrom)) actions.createInstance(text, modalCopyFrom);
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
            case "self-update" -> actions.updateLauncher();
            case "group" -> {
                if (text.isEmpty()) return;
                extras.setGroup(m.target(), text);
            }
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

    /** Where the Clips pages were placed last frame (design coordinates). */
    private Rectangle clipsBoxShown;

    /** Home's hero backdrops (Evade gameplay, softly blurred), one per slide. */
    private static final String[] BACKDROPS = {"bg-world", "bg-hud", "bg-menu", "bg-clips"};

    /** Width of the icon rail on the left. */
    static final int RAIL = 88;
    /** Window corner radius. */
    static final int RADIUS = 20;

    /** Paints everything (children too) through the theme's colours. */
    @Override public void paint(Graphics g) {
        super.paint(LauncherTheme.wrap(g));
    }

    /**
     * With a theme that changes colours, a child's own repaint (a text field's caret, the Clips pages) goes through
     * this view too, so it's drawn in the theme's colours.
     */
    @Override protected boolean isPaintingOrigin() { return !LauncherTheme.identity(); }

    private int themeGen = -1;

    /** The theme, accent or text size changed: redraw cached layers. */
    void themeChanged() {
        bgLayer = null;
        themeGen = LauncherTheme.generation();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        // A repaint only inside the (opaque) Clips pages doesn't need the whole launcher painted under it.
        Rectangle dirty = g0.getClipBounds();
        if (dirty != null && page == Page.CLIPS && clipsHost != null && clipsHost.isVisible() && clipsHost.isOpaque() && !hitMap.isEmpty()
                && modal == null && overlay == null && popup == null && clipsBoxShown != null && toScreen(clipsBoxShown).contains(dirty)) return;
        if (themeGen != LauncherTheme.generation()) { themeGen = LauncherTheme.generation(); bgLayer = null; }
        Graphics2D g = ThemedGraphics.withText((Graphics2D) g0.create());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (uiScale != 1f) g.scale(uiScale, uiScale);
        hitMap.clear();
        scrollAreas.clear();
        animating = false;
        SIDE = RAIL;
        CX = RAIL + 24;
        CW = W - CX - 24;
        Rectangle search = null, java = null, modalBox = null, clipsBox = null;
        game.frameStart();
        paintBackground(g);
        paintRail(g);
        switch (page) {
            case HOME -> { if (firstRun()) paintSetup(g); else paintHome(g); }
            case INSTANCES -> paintInstances(g);
            case MODS -> search = content.paint(g);
            case CLIPS -> clipsBox = clipsBoxShown = paintClips(g);
            case GAME -> game.paintPage(g);
            case SETTINGS -> java = paintSettings(g);
        }
        if (page != Page.GAME) game.hideFields();
        paintTopRight(g);
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
            else if (overlay.equals("friends")) paintFriends(g);
            else if (overlay.equals("whatsnew")) extras.paintWhatsNew(g);
            else if (overlay.equals("keys")) extras.paintKeys(g);
            else if (tools.paintOverlay(g, overlay)) { /* import, optional extras */ }
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
        content.placeFields(modal != null || overlay != null, pop);
        if (clipsHost != null) {
            boolean show = clipsBox != null && modal == null && popup == null && overlay == null;
            Rectangle cb = toScreen(clipsBox);
            if (show && !cb.equals(clipsHost.getBounds())) { clipsHost.setBounds(cb); clipsHost.revalidate(); }
            if (clipsHost.isVisible() != show) clipsHost.setVisible(show);
        }
        game.paintOverlay(g);
        tools.paintDrag(g);
        g.dispose();
    }

    /** The toast and the rail's labels go over everything, the embedded Clips pages included. */
    @Override protected void paintChildren(Graphics g0) {
        super.paintChildren(g0);
        if (toast == null && sideTip == null) return;
        Graphics2D g = ThemedGraphics.withText((Graphics2D) g0.create());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        if (uiScale != 1f) g.scale(uiScale, uiScale);
        paintSideTip(g);
        if (toast != null) paintToast(g);
        g.dispose();
    }

    void place(JTextField f, Rectangle r, Rectangle covered) {
        boolean show = r != null && (covered == null || !covered.intersects(r));
        r = toScreen(r);
        if (show && !r.equals(f.getBounds())) f.setBounds(r);
        if (f.isVisible() != show) f.setVisible(show);
    }

    /** A calm near-black surface: one faint red glow, the rail's panel, and a hairline round the window. */
    private void paintBackground(Graphics2D g) {
        double scale = g.getTransform().getScaleX();
        if (bgLayer == null || bgScale != scale || bgRound != roundCorners) {
            bgLayer = deviceImage(W, H, scale);
            Graphics2D b = LauncherTheme.wrap(bgLayer.createGraphics());
            b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            b.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            b.scale(bgLayer.getWidth() / (double) W, bgLayer.getHeight() / (double) H);
            Shape win = roundCorners ? new RoundRectangle2D.Double(0, 0, W, H, RADIUS * 2, RADIUS * 2) : new Rectangle(0, 0, W, H);
            b.setColor(BG);
            b.fill(win);
            b.setPaint(new RadialGradientPaint(W * 0.42f, -80, 560, new float[]{0f, 1f}, new Color[]{new Color(225, 29, 46, 26), new Color(225, 29, 46, 0)}));
            b.fill(win);
            Shape was = b.getClip();
            b.clip(win);
            b.setColor(RAIL_BG);
            b.fillRect(0, 0, RAIL, H);
            b.setColor(HAIRLINE);
            b.fillRect(RAIL, 0, 1, H);
            b.setClip(was);
            b.setColor(new Color(255, 255, 255, 16));
            b.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, RADIUS * 2 - 1, RADIUS * 2 - 1));
            b.dispose();
            bgScale = scale;
            bgRound = roundCorners;
        }
        g.drawImage(bgLayer, 0, 0, W, H, null);
    }

    private static BufferedImage deviceImage(int w, int h, double scale) {
        return new BufferedImage(Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)), BufferedImage.TYPE_INT_ARGB_PRE);
    }

    /** A soft drop shadow under something raised above the page (menus, dialogs, toasts). */
    void shadow(Graphics2D g, int x, int y, int w, int h) {
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
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.drawImage(img, x - pad, y + SHADOW_DY - pad, w + 2 * pad, h + 2 * pad, null);
        g.setComposite(was);
    }

    /** A flat card on the page: a slightly lighter surface with a hairline edge, 16px corners. */
    void card(Graphics2D g, int x, int y, int w, int h, boolean accent) {
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 16 * 2, 16 * 2));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 16 * 2, 16 * 2));
    }

    /** A raised panel (menus, dialogs, cards that open over the page): elevated surface and a soft shadow. */
    void raised(Graphics2D g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h);
        g.setColor(RAISED);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 32, 32));
        g.setColor(new Color(255, 255, 255, 18));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 32, 32));
    }

    // ------------------------------------------------------------------ rail and top bar

    private void paintRail(Graphics2D g) {
        g.drawImage(mark, (RAIL - 40) / 2, 26, 40, 40, null);
        String[][] nav = {{"nav-home", "Home", "home"}, {"nav-instances", "Instances", "layers"}, {"nav-mods", "Mods", "cube"},
                {"nav-clips", "Clips", "clip"}, {"nav-game", "Game", "pulse"}};
        Page[] pages = {Page.HOME, Page.INSTANCES, Page.MODS, Page.CLIPS, Page.GAME};
        String tip = null;
        int tipY = 0;
        for (int i = 0; i <= nav.length; i++) {
            boolean settingsItem = i == nav.length;
            String id = settingsItem ? "nav-settings" : nav[i][0];
            Page p = settingsItem ? Page.SETTINGS : pages[i];
            int y = settingsItem ? H - 24 - 52 : 108 + i * 64;
            if (railButton(g, id, y, settingsItem ? "gear" : nav[i][2], page == p)) { tip = settingsItem ? "Settings" : nav[i][1]; tipY = y + 26; }
            if (p == Page.CLIPS && clips != null) {
                var st = clips.status;
                boolean rec = st.recording(), live = st.state() == net.evadeclient.clips.core.CaptureEngine.State.LIVE;
                if (rec || live || clipsBlocked) {
                    int dx = (RAIL - 52) / 2 + 52 - 10, dy = y + 6;
                    g.setColor(page == p ? RED : RAIL_BG);
                    g.fill(new Ellipse2D.Double(dx - 2, dy - 2, 12, 12));
                    g.setColor(clipsBlocked ? AMBER : rec ? RED_HI : OK);
                    g.fill(new Ellipse2D.Double(dx, dy, 8, 8));
                }
            } else if (p == Page.CLIPS && clipsBlocked) {
                int dx = (RAIL - 52) / 2 + 52 - 10, dy = y + 6;
                g.setColor(AMBER);
                g.fill(new Ellipse2D.Double(dx, dy, 8, 8));
            }
        }
        sideTip = tip;
        sideTipY = tipY;
    }

    /** One rail button: a rounded square, filled red when its page is open, with a small tab on the rail's edge. */
    private boolean railButton(Graphics2D g, String id, int y, String iconKind, boolean on) {
        int s = 52, x = (RAIL - s) / 2;
        float t = anim(id, id.equals(hovered));
        float a = anim(id + "-on", on);
        Color fill = mix(mix(new Color(0x141418), new Color(0x1E1E24), t), RED, a);
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 32, 32));
        if (a > 0.01f) {
            g.setColor(new Color(255, 65, 83, Math.round(255 * a)));
            g.fill(new RoundRectangle2D.Double(-4, y + 12, 8, s - 24, 8, 8));
        }
        icon(g, iconKind, x + s / 2, y + s / 2, 20, mix(mix(DIM, TEXT, t), Color.WHITE, a));
        hit(id, new Rectangle(x, y, s, s));
        return id.equals(hovered);
    }

    /** The rail's label bubble (painted after the children, so it shows over the Clips pages). */
    private String sideTip;
    private int sideTipY;

    private void paintSideTip(Graphics2D g) {
        String tip = sideTip;
        if (tip == null) return;
        g.setFont(interBold.deriveFont(12f));
        int tw = g.getFontMetrics().stringWidth(tip) + 20;
        int tx = RAIL + 6, ty = sideTipY - 15;
        g.setColor(new Color(0x1E1E24));
        g.fill(new RoundRectangle2D.Double(tx, ty, tw, 30, 16, 16));
        g.setColor(new Color(255, 255, 255, 22));
        g.draw(new RoundRectangle2D.Double(tx + .5, ty + .5, tw - 1, 29, 16, 16));
        g.setColor(TEXT);
        center(g, tip, tx, ty, tw, 30);
    }

    /** A square top-bar button (bell, minimise, close): a dark tile with a hairline. */
    private void topButton(Graphics2D g, String id, int x, String iconKind, boolean danger) {
        int y = 22, s = 40;
        float t = anim(id, id.equals(hovered) || id.equals(overlay));
        int was = danger ? ThemedGraphics.danger(g) : -1;
        g.setColor(mix(new Color(0x141418), danger ? RED : new Color(0x202026), t));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 24, 24));
        if (was >= 0) ThemedGraphics.mode(g, was);
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, s - 1, s - 1, 24, 24));
        Color c = mix(DIM, TEXT, t);
        if (iconKind.equals("min") || iconKind.equals("close")) {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double cx = x + s / 2.0, cy = y + s / 2.0;
            if (iconKind.equals("close")) {
                g.draw(new Line2D.Double(cx - 5.5, cy - 5.5, cx + 5.5, cy + 5.5));
                g.draw(new Line2D.Double(cx + 5.5, cy - 5.5, cx - 5.5, cy + 5.5));
            } else g.draw(new Line2D.Double(cx - 6, cy, cx + 6, cy));
            g.setStroke(new BasicStroke(1f));
        } else icon(g, iconKind, x + s / 2, y + s / 2, 18, c);
        hitMap.put(id, new Rectangle(x, y, s, s));
    }

    /** Right end of the top bar: Clips status, notifications, minimise and close. */
    private void paintTopRight(Graphics2D g) {
        int cx = W - 24 - 40, mx = cx - 48, bx = mx - 56;
        topButton(g, "close", cx, "close", true);
        topButton(g, "min", mx, "min", false);
        topButton(g, "bell", bx, "bell", false);
        if (unread > 0) {
            String n = unread > 9 ? "9+" : String.valueOf(unread);
            g.setFont(interBold.deriveFont(10f));
            int bw = Math.max(18, g.getFontMetrics().stringWidth(n) + 10);
            g.setColor(BG);
            g.fill(new RoundRectangle2D.Double(bx + 40 - bw / 2.0 - 2, 14, bw + 4, 22, 22, 22));
            g.setColor(RED);
            g.fill(new RoundRectangle2D.Double(bx + 40 - bw / 2.0, 16, bw, 18, 18, 18));
            g.setColor(Color.WHITE);
            center(g, n, (int) Math.round(bx + 40 - bw / 2.0), 16, bw, 18);
        }
        // Clips status pill: what instant replay is doing right now (opens Clips)
        String text;
        Color dot;
        if (clipsBlocked) { text = "Clips needs attention"; dot = AMBER; }
        else if (clips == null) { text = "Clips is off"; dot = FAINT; }
        else {
            var st = clips.status;
            boolean rec = st.recording(), live = st.state() == net.evadeclient.clips.core.CaptureEngine.State.LIVE;
            text = rec ? "Recording " + net.evadeclient.clips.ui.Theme.time(st.recordingSeconds()) : live ? "Instant replay on"
                    : clips.settings.replayEnabled ? "Replay starts with Minecraft" : "Instant replay off";
            dot = rec ? RED_HI : live ? OK : FAINT;
            if (rec || live) animating = true;
        }
        g.setFont(inter.deriveFont(12.5f));
        int pw = g.getFontMetrics().stringWidth(text) + 40, px = bx - 12 - pw;
        float t = anim("status-pill", "status-pill".equals(hovered));
        g.setColor(mix(new Color(0x141418), new Color(0x1C1C22), t));
        g.fill(new RoundRectangle2D.Double(px, 22, pw, 40, 40, 40));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(px + .5, 22.5, pw - 1, 39, 40, 40));
        float pulse = dot == OK || dot == RED_HI ? (float) (0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 320.0)) : 1f;
        g.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), Math.round(255 * pulse)));
        g.fill(new Ellipse2D.Double(px + 16, 38, 8, 8));
        g.setColor(mix(DIM, TEXT, t));
        g.drawString(text, px + 32, 46);
        hit("status-pill", new Rectangle(px, 22, pw, 40));
        topRightStart = px;
    }

    /** Where the top bar's right group starts (the left group stays clear of it). */
    private int topRightStart = W - 400;

    /** A page title in the top bar, with an optional line under it. */
    void pageTitle(Graphics2D g, String title, String sub) {
        g.setFont(interBold.deriveFont(26f));
        g.setColor(TEXT);
        g.drawString(title, CX, 52);
        if (sub != null) {
            g.setFont(inter.deriveFont(13f));
            g.setColor(DIM);
            g.drawString(ellipsizeEnd(g, sub, CW - 420), CX, 80);
        }
    }

    // ------------------------------------------------------------------ home

    private void paintHome(Graphics2D g) {
        Launcher.Instance sel = selected();
        paintLinksPill(g);
        int pw = 296, x = CX, y = 84, hw = CW - 24 - pw, hh = 372;
        paintHero(g, sel, x, y, hw, hh);
        paintProfile(g, x + hw + 24, y, pw, hh);
        if (!game.paintHomeCrash(g, x, y + hh + 24, CW, H - 24 - (y + hh + 24))) paintNewsRow(g, x, y + hh + 24, CW, H - 24 - (y + hh + 24));
    }

    /** Top bar, left: Evade's links, then the player's servers (icons, and + to add one). */
    private void paintLinksPill(Graphics2D g) {
        List<String[]> links = new ArrayList<>();
        links.add(new String[]{"link-site", "globe", "evadeclient.com"});
        links.add(new String[]{"link-docs", "book", "Guides"});
        links.add(new String[]{"link-changelog", "list", "Changelog"});
        if (!DISCORD.isBlank()) links.add(new String[]{"link-discord", "chat", "Discord"});
        if (!YOUTUBE.isBlank()) links.add(new String[]{"link-youtube", "video", "YouTube"});
        if (!TIKTOK.isBlank()) links.add(new String[]{"link-tiktok", "music", "TikTok"});
        int x = CX, y = 22, h = 40;
        int w = links.size() * 36 + 8;
        g.setColor(new Color(0x141418));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 24, 24));
        for (int i = 0; i < links.size(); i++) {
            String id = links.get(i)[0];
            int bx = x + 4 + i * 36;
            float t = anim(id, id.equals(hovered));
            if (t > 0) {
                g.setColor(new Color(255, 255, 255, Math.round(14 * t)));
                g.fill(new RoundRectangle2D.Double(bx, y + 4, 32, 32, 18, 18));
            }
            icon(g, links.get(i)[1], bx + 16, y + 20, 16, mix(DIM, TEXT, t));
            hit(id, new Rectangle(bx, y + 4, 32, 32));
        }
        paintServersPill(g, x + w + 12, y, topRightStart - 12 - (x + w + 12));
    }

    /** The player's servers as icons in the top bar. A click opens the server's card; + adds one. */
    private void paintServersPill(Graphics2D g, int x, int y, int maxW) {
        int n = settings.servers.size();
        boolean add = n < Launcher.MAX_SERVERS;
        int slots = n + (add ? 1 : 0);
        int fit = Math.max(1, (maxW - 8 - 90) / 36);
        slots = Math.min(slots, fit);
        int w = 8 + 92 + slots * 36;
        g.setColor(new Color(0x141418));
        g.fill(new RoundRectangle2D.Double(x, y, w, 40, 24, 24));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, 39, 24, 24));
        g.setFont(interBold.deriveFont(11f));
        g.setColor(FAINT);
        g.drawString("SERVERS", x + 16, y + 24);
        int sx = x + 92;
        for (int i = 0; i < slots; i++) {
            int bx = sx + i * 36, by = y + 4;
            if (i < n && (!add || i < slots - 1 || slots == n)) {
                Launcher.Server sv = settings.servers.get(i);
                String id = "server:" + i;
                float t = anim(id, id.equals(hovered) || id.equals(overlay));
                String k = ServersDat.key(sv.address());
                Image icon = serverIcons.get(k);
                Shape was = g.getClip();
                g.clip(new RoundRectangle2D.Double(bx + 2, by + 2, 28, 28, 14, 14));
                if (icon != null) {
                    Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(icon, bx + 2, by + 2, 28, 28, null);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
                } else tile(g, bx + 2, by + 2, 28, sv.name());
                g.setClip(was);
                if (t > 0.01f) {
                    g.setColor(new Color(255, 65, 83, Math.round(220 * t)));
                    g.setStroke(new BasicStroke(1.75f));
                    g.draw(new RoundRectangle2D.Double(bx + 1, by + 1, 30, 30, 16, 16));
                    g.setStroke(new BasicStroke(1f));
                }
                Color dc = serverStatus.containsKey(k) ? OK : serverDown.contains(k) ? LauncherTheme.DANGER : null;
                if (dc != null) {
                    g.setColor(new Color(0x141418));
                    g.fill(new Ellipse2D.Double(bx + 22, by + 22, 11, 11));
                    g.setColor(dc);
                    g.fill(new Ellipse2D.Double(bx + 24, by + 24, 7, 7));
                }
                hit(id, new Rectangle(bx, by, 32, 32));
            } else {
                String id = "server-add:" + i;
                float t = anim(id, id.equals(hovered));
                g.setColor(new Color(255, 255, 255, Math.round(8 + 14 * t)));
                g.fill(new RoundRectangle2D.Double(bx + 2, by + 2, 28, 28, 14, 14));
                icon(g, "plus", bx + 16, by + 16, 12, mix(DIM, TEXT, t));
                hit(id, new Rectangle(bx, by, 32, 32));
            }
        }
        serversPillX = sx;
    }

    /** Where the first server icon sits (the server card opens under it). */
    private int serversPillX = CX + 200;

    /** Draws {@code img} filling the box (cropped, not stretched), aligned by {@code ax} (0 left … 1 right). */
    private static void cover(Graphics2D g, Image img, int x, int y, int w, int h, double ax) {
        int iw = img.getWidth(null), ih = img.getHeight(null);
        if (iw <= 0 || ih <= 0) return;
        double s = Math.max(w / (double) iw, h / (double) ih);
        int dw = (int) Math.ceil(iw * s), dh = (int) Math.ceil(ih * s);
        g.drawImage(img, x - (int) Math.round((dw - w) * ax), y - (dh - h) / 2, dw, dh, null);
    }

    private void paintHero(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        // The photo-backed hero keeps the dark look in every theme (light text on a dimmed picture).
        int mode = ThemedGraphics.island(g);
        paintHeroCard(g, sel, x, y, w, h);
        ThemedGraphics.mode(g, mode);
    }

    private void paintHeroCard(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        Shape box = new RoundRectangle2D.Double(x, y, w, h, 40, 40);
        g.setColor(CARD);
        g.fill(box);
        Shape was = g.getClip();
        g.clip(box);
        // the artwork, cross-fading from the previous picture
        long age = System.currentTimeMillis() - slideAt;
        float fade = slideFrom >= 0 ? Math.min(1f, age / 700f) : 1f;
        if (slideFrom >= 0 && fade < 1f) {
            Image from = heroImages.get(BACKDROPS[slideFrom % BACKDROPS.length]);
            if (from != null) cover(g, from, x, y, w, h, 0.8);
        }
        Image img = heroImages.get(BACKDROPS[slide % BACKDROPS.length]);
        if (img != null) {
            Composite c0 = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
            cover(g, img, x, y, w, h, 0.8);
            g.setComposite(c0);
        }
        // dim it, then a scrim on the left for the text
        g.setColor(new Color(10, 10, 12, 120));
        g.fill(box);
        g.setPaint(new GradientPaint(x, 0, new Color(10, 10, 12, 245), x + w * 0.75f, 0, new Color(10, 10, 12, 0)));
        g.fill(box);
        g.setPaint(new GradientPaint(0, y + h * 0.55f, new Color(10, 10, 12, 0), 0, y + h, new Color(10, 10, 12, 200)));
        g.fill(box);
        if (play != Play.IDLE) {
            g.setColor(new Color(10, 10, 12, 190));
            g.fill(box);
        }
        g.setClip(was);
        g.setColor(new Color(255, 255, 255, 18));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 40, 40));

        switch (play) {
            case UPDATING, LAUNCHING -> paintHeroProgress(g, x + 40, y + 8, w - 80, h - 16);
            case LAUNCHED -> paintHeroLaunched(g, x + 40, y + 8, w - 80, h - 16);
            case NO_LAUNCHER -> paintHeroNoLauncher(g, x + 40, y + 8, w - 80, h - 16);
            case ERROR -> paintHeroError(g, x + 40, y + 8, w - 80, h - 16);
            default -> paintHeroIdle(g, sel, x + 40, y, w - 80, h);
        }
    }

    private void paintHeroIdle(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        // tag
        String tag = "EVADE CLIENT" + (sel != null && sel.installed != null ? "  v" + sel.installed.evadeVersion() : "");
        g.setFont(interBold.deriveFont(10.5f));
        int tw = (int) (g.getFontMetrics().stringWidth(tag) + tag.length() * 1.2f) + 24;
        g.setColor(new Color(225, 29, 46, 46));
        g.fill(new RoundRectangle2D.Double(x, y + 44, tw, 26, 26, 26));
        g.setColor(RED_HI);
        spaced(g, tag, x + 12, y + 61, 1.2f);
        // the instance, big
        g.setFont(interBold.deriveFont(48f));
        g.setColor(TEXT);
        String name = ellipsizeEnd(g, sel != null ? sel.name : "—", w - 40);
        g.drawString(name, x - 2, y + 128);
        // what it is
        g.setFont(inter.deriveFont(14.5f));
        g.setColor(new Color(0xC6C6CE));
        String meta = "Minecraft " + InstallerCore.MC_VERSION;
        if (sel != null && sel.installed != null) meta += "  ·  Fabric " + sel.installed.loaderVersion();
        if (sel != null) meta += "  ·  " + sel.memoryGb + " GB" + (sel.installed != null ? "  ·  " + sel.modCount + " mods" : "");
        g.drawString(ellipsizeEnd(g, meta, w - 40), x, y + 162);

        String reason = sel != null ? sel.pending : null;
        String status, sub;
        Color sc;
        if (sel == null) { status = "No instance"; sc = FAINT; sub = "PICK AN INSTANCE"; }
        else if (sel.installed == null) { status = "Evade installs when you press Play"; sc = AMBER; sub = "INSTALLS FIRST"; }
        else if (release == null && releaseError == null) { status = "Checking for updates…"; sc = DIM; sub = "READY TO LAUNCH"; }
        else if (release == null) { status = "Couldn't check for updates"; sc = DIM; sub = "READY TO LAUNCH"; }
        else if (reason != null) { status = "Update ready: " + reason; sc = AMBER; sub = "UPDATES FIRST"; }
        else if (sel.dirty) { status = "Options changed: applied when you press Play"; sc = AMBER; sub = "UPDATES FIRST"; }
        else { status = "Up to date"; sc = OK; sub = "READY TO LAUNCH"; }
        String[] running = game.heroStatus(sel);
        if (running != null) { status = running[0]; sc = OK; sub = running[1]; }
        g.setFont(inter.deriveFont(13f));
        g.setColor(sc);
        g.fill(new Ellipse2D.Double(x + 1, y + 182, 8, 8));
        String st = ellipsizeEnd(g, status, w - 120);
        g.drawString(st, x + 18, y + 191);
        if (release == null && releaseError != null) {
            int sw = g.getFontMetrics().stringWidth(st);
            float tr = anim("retry-release", "retry-release".equals(hovered));
            g.setColor(mix(DIM, TEXT, tr));
            g.drawString("Retry", x + 30 + sw, y + 191);
            hit("retry-release", new Rectangle(x + 24 + sw, y + 174, 50, 24));
        }

        // Play, with the instance switch next to it
        boolean ready = release != null || (releaseError != null && sel != null && sel.installed != null);
        boolean checking = release == null && releaseError == null;
        String label = checking ? "CHECKING" : sel != null && (sel.installed == null || reason != null || sel.dirty) && release != null ? "UPDATE & PLAY" : "PLAY";
        if (running != null) label = "PLAYING";
        int by = y + h - 40 - 64;
        launchButton(g, "play", x, by, 232, 64, label, checking ? "ONE MOMENT" : sub, ready && sel != null);
        squareButton(g, "home-switch", x + 232 + 12, by, 64, "layers", "Switch instance");
        game.heroButtons(g, sel, x + 232 + 12 + 64 + 12, by);

        // artwork dots
        int dx = x + w;
        for (int i = slides.length - 1; i >= 0; i--) {
            boolean on = i == slide;
            int dw = on ? 20 : 8;
            dx -= dw;
            g.setColor(on ? new Color(255, 255, 255, 210) : new Color(255, 255, 255, 60));
            g.fill(new RoundRectangle2D.Double(dx, y + h - 40 - 4, dw, 8, 8, 8));
            hit("dot:" + i, new Rectangle(dx - 3, y + h - 54, dw + 6, 28));
            dx -= 8;
        }
    }

    /** The big red Play button: a bold label and a small line under it. */
    private void launchButton(Graphics2D g, String id, int x, int y, int w, int h, String label, String sub, boolean enabled) {
        float t = enabled ? anim(id, id.equals(hovered)) : 0;
        if (enabled) {
            shadowGlow(g, x, y, w, h, t);
            g.setColor(mix(RED, RED_HI, t));
        } else g.setColor(new Color(0x2A1418));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 28, 28));
        if (enabled) {
            g.setColor(new Color(255, 255, 255, 40));
            g.fill(new RoundRectangle2D.Double(x + 12, y + 1, w - 24, 1.2, 1, 1));
        }
        Color fg = enabled ? Color.WHITE : FAINT;
        g.setFont(interBold.deriveFont(19f));
        FontMetrics fm = g.getFontMetrics();
        int iw = 14, gap = 12;
        int lw = (int) (fm.stringWidth(label) + label.length() * 0.6f);
        int tx = x + (w - lw - iw - gap) / 2;
        icon(g, "play", tx + iw / 2, y + 26, iw, fg);
        g.setColor(fg);
        spaced(g, label, tx + iw + gap, y + 33, 0.6f);
        g.setFont(interBold.deriveFont(9.5f));
        g.setColor(enabled ? new Color(255, 255, 255, 190) : FAINT);
        int sw = (int) (g.getFontMetrics().stringWidth(sub) + sub.length() * 1.4f);
        spaced(g, sub, x + (w - sw) / 2, y + 51, 1.4f);
        hit(id, new Rectangle(x, y, w, h));
    }

    /** A soft red light under the Play button (no box edges). */
    private void shadowGlow(Graphics2D g, int x, int y, int w, int h, float t) {
        Graphics2D q = (Graphics2D) g.create();
        float rx = w * 0.6f, ry = h * 0.7f;
        q.translate(x + w / 2f, y + h * 0.8f);
        q.scale(1, ry / rx);
        q.setPaint(new RadialGradientPaint(0, 0, rx, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, Math.round(70 + 50 * t)), new Color(225, 29, 46, 0)}));
        q.fill(new Ellipse2D.Float(-rx, -rx, 2 * rx, 2 * rx));
        q.dispose();
    }

    /** A square icon button (glass tile) beside the Play button. */
    void squareButton(Graphics2D g, String id, int x, int y, int s, String iconKind, String tipText) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(255, 255, 255, 18), new Color(255, 255, 255, 34), t));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 28, 28));
        g.setColor(new Color(255, 255, 255, 26));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, s - 1, s - 1, 28, 28));
        icon(g, iconKind, x + s / 2, y + s / 2, 20, mix(new Color(0xD6D6DC), Color.WHITE, t));
        hit(id, new Rectangle(x, y, s, s));
    }

    /** Right of the hero: the player's skin standing on their card, their name, and account/friends buttons. */
    private void paintProfile(Graphics2D g, int x, int y, int w, int h) {
        int cy = y + 96, ch = h - 96;
        card(g, x, cy, w, ch, false);
        // the skin, standing on the card
        int bh = 176, bw = Math.round(bh * 0.5f);
        int bx = x + (w - bw) / 2, byBottom = cy + 92;
        g.setPaint(new RadialGradientPaint(x + w / 2f, byBottom - 20, 110, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, 46), new Color(225, 29, 46, 0)}));
        g.fill(new Ellipse2D.Double(x + w / 2.0 - 110, byBottom - 130, 220, 220));
        if (playerBody != null) {
            int iw = playerBody.getWidth(null), ih = playerBody.getHeight(null);
            double s = bh / (double) Math.max(1, ih);
            int dw = (int) Math.round(iw * s);
            Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(playerBody, x + (w - dw) / 2, byBottom - bh, dw, bh, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        } else defaultBody(g, bx, byBottom - bh, bw, bh);
        g.setColor(RED);
        g.fill(new RoundRectangle2D.Double(x + w / 2.0 - 60, byBottom + 2, 120, 4, 4, 4));
        // name
        g.setFont(interBold.deriveFont(22f));
        g.setColor(TEXT);
        center(g, ellipsizeEnd(g, playerName != null ? playerName : "Not signed in", w - 40), x, byBottom + 18, w, 30);
        g.setFont(inter.deriveFont(12f));
        g.setColor(playerName != null ? DIM : FAINT);
        center(g, playerName != null ? "Minecraft account" : "Sign in once in the Minecraft Launcher", x, byBottom + 46, w, 18);
        int ix = x + 24, iw = w - 48, ry = byBottom + 78;
        if (playerName != null) {
            accentButton(g, "manage-account", ix, ry, iw - 52, 44, "Manage account");
            squareSmall(g, "friends-add", ix + iw - 44, ry, 44, "user-plus");
            ghostButton(g, "friends", ix, ry + 54, iw, 44, "Your friends", false, "users");
        } else {
            accentButton(g, "play-again", ix, ry, iw, 44, "Open the Minecraft Launcher");
            ghostButton(g, "friends", ix, ry + 54, iw, 44, "Your friends", false, "users");
        }
    }

    /** No skin yet: a plain standing figure in the launcher's colours (simple shapes, no game art). */
    private void defaultBody(Graphics2D g, int x, int y, int w, int h) {
        double u = h / 32.0;
        Color skin = new Color(0x34343C), shirt = new Color(0x2A2A31), legs = new Color(0x1F1F25);
        double cx = x + w / 2.0;
        g.setColor(skin);
        g.fill(new RoundRectangle2D.Double(cx - 4 * u, y, 8 * u, 8 * u, 2 * u, 2 * u));
        g.setColor(shirt);
        g.fill(new RoundRectangle2D.Double(cx - 4 * u, y + 8 * u, 8 * u, 12 * u, u, u));
        g.setColor(new Color(0x26262C));
        g.fill(new RoundRectangle2D.Double(cx - 8 * u, y + 8 * u, 4 * u, 12 * u, u, u));
        g.fill(new RoundRectangle2D.Double(cx + 4 * u, y + 8 * u, 4 * u, 12 * u, u, u));
        g.setColor(legs);
        g.fill(new RoundRectangle2D.Double(cx - 4 * u, y + 20 * u, 4 * u, 12 * u, u, u));
        g.fill(new RoundRectangle2D.Double(cx, y + 20 * u, 4 * u, 12 * u, u, u));
        g.setColor(new Color(255, 255, 255, 170));
        g.fill(new Rectangle2D.Double(cx - 3 * u, y + 4 * u, 2 * u, u));
        g.fill(new Rectangle2D.Double(cx + u, y + 4 * u, 2 * u, u));
    }

    /** A tinted red button (secondary action on dark cards). */
    void accentButton(Graphics2D g, String id, int x, int y, int w, int h, String label) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(225, 29, 46, 44), new Color(225, 29, 46, 80), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        g.setFont(interBold.deriveFont(13.5f));
        g.setColor(mix(new Color(0xFF6B78), Color.WHITE, t));
        center(g, ellipsizeEnd(g, label, w - 20), x, y, w, h);
        hit(id, new Rectangle(x, y, w, h));
    }

    private void squareSmall(Graphics2D g, String id, int x, int y, int s, String iconKind) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(225, 29, 46, 44), new Color(225, 29, 46, 80), t));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 24, 24));
        icon(g, iconKind, x + s / 2, y + s / 2, 18, mix(new Color(0xFF6B78), Color.WHITE, t));
        hit(id, new Rectangle(x, y, s, s));
    }

    /** Bottom of Home: the latest news as artwork cards, and a tile for all of it. */
    private void paintNewsRow(Graphics2D g, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        int pad = 16, gap = 16, allW = 88;
        int top = y + pad, ch = h - 2 * pad;
        if (news.isEmpty()) {
            g.setFont(inter.deriveFont(13f));
            g.setColor(newsError != null ? FAINT : DIM);
            g.drawString(ellipsizeEnd(g, newsLoading ? "Loading the latest news…" : newsError != null ? "News couldn't be loaded. " + EvadeInstaller.sentence(newsError) : "No news yet.", w - 48), x + 24, y + h / 2 + 5);
            return;
        }
        int n = Math.min(3, news.size());
        int cw = (w - 2 * pad - allW - gap * n) / n;
        String[] fallback = {"hud", "clips", "theme", "menu"};
        for (int i = 0; i < n; i++) {
            News.Entry e = news.get(i);
            int cx = x + pad + i * (cw + gap);
            String id = "news:" + i;
            float t = anim(id, id.equals(hovered));
            Shape box = new RoundRectangle2D.Double(cx, top, cw, ch, 24, 24);
            g.setColor(mix(new Color(0x18181D), new Color(0x1D1D23), t));
            g.fill(box);
            int ih = ch - 64;
            Image img = e.image() != null ? newsImages.get(e.image()) : null;
            if (img == null) img = heroImages.get(fallback[i % fallback.length]);
            Shape was = g.getClip();
            g.clip(box);
            g.clipRect(cx, top, cw, ih);
            if (img != null) cover(g, img, cx, top, cw, ih, 0.5);
            g.setColor(new Color(10, 10, 12, Math.round(70 - 40 * t)));
            g.fillRect(cx, top, cw, ih);
            g.setClip(was);
            if (i == 0) {
                g.setFont(interBold.deriveFont(9.5f));
                int pw = g.getFontMetrics().stringWidth("NEW") + 16;
                g.setColor(RED);
                g.fill(new RoundRectangle2D.Double(cx + 10, top + 10, pw, 20, 20, 20));
                g.setColor(Color.WHITE);
                center(g, "NEW", cx + 10, top + 10, pw, 20);
            }
            int ty = top + ih + 24;
            g.setFont(interBold.deriveFont(14.5f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, e.title() + (e.summary().isEmpty() ? "" : ": " + e.summary()), cw - 70), cx + 14, ty);
            g.setFont(inter.deriveFont(12f));
            g.setColor(FAINT);
            g.drawString(e.date(), cx + 14, ty + 20);
            // square open-link button
            int sb = 36, sx = cx + cw - sb - 12, sy = top + ih + 14;
            g.setColor(mix(new Color(225, 29, 46, 40), RED, t));
            g.fill(new RoundRectangle2D.Double(sx, sy, sb, sb, 20, 20));
            icon(g, "external", sx + sb / 2, sy + sb / 2, 15, mix(new Color(0xFF6B78), Color.WHITE, t));
            hit(id, new Rectangle(cx, top, cw, ch));
        }
        // all news
        int ax = x + w - pad - allW;
        float t = anim("news-all", "news-all".equals(hovered));
        g.setColor(mix(new Color(0x18181D), new Color(0x1F1F25), t));
        g.fill(new RoundRectangle2D.Double(ax, top, allW, ch, 24, 24));
        icon(g, "arrow-right", ax + allW / 2, top + ch / 2 - 12, 18, mix(DIM, TEXT, t));
        g.setFont(interBold.deriveFont(12f));
        g.setColor(mix(DIM, TEXT, t));
        center(g, "All news", ax, top + ch / 2 + 6, allW, 20);
        hit("news-all", new Rectangle(ax, top, allW, ch));
    }

    // ------------------------------------------------------------------ overlays: server card, notices, friends

    /** The card for a server: what its status ping says, and its address to copy. */
    private void paintServerCard(Graphics2D g, int index) {
        Launcher.Server sv = settings.servers.get(index);
        int cw = 380, ch = 204;
        int tx = serversPillX + index * 36 + 16;
        int x = Math.max(CX, Math.min(W - 24 - cw, tx - cw / 2)), y = 72;
        raised(g, x, y, cw, ch);
        String k = ServersDat.key(sv.address());
        Image icon = serverIcons.get(k);
        Shape was = g.getClip();
        g.clip(new RoundRectangle2D.Double(x + 20, y + 20, 48, 48, 24, 24));
        if (icon != null) g.drawImage(icon, x + 20, y + 20, 48, 48, null);
        else tile(g, x + 20, y + 20, 48, sv.name());
        g.setClip(was);
        g.setFont(interBold.deriveFont(16f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, sv.name(), cw - 104), x + 82, y + 40);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, sv.address(), cw - 104), x + 82, y + 60);
        ServerPing.Status st = serverStatus.get(k);
        int ly = y + 96;
        if (st != null) {
            g.setColor(OK);
            g.fill(new Ellipse2D.Double(x + 20, ly - 8, 8, 8));
            g.setFont(interBold.deriveFont(12.5f));
            g.setColor(TEXT);
            String line = String.format(java.util.Locale.ROOT, "%,d / %,d online", st.online(), st.max());
            g.drawString(line, x + 36, ly);
            int lw = g.getFontMetrics().stringWidth(line);
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString(ellipsizeEnd(g, "  ·  " + st.pingMs() + " ms" + (st.version().isBlank() ? "" : "  ·  " + st.version()), cw - 56 - lw), x + 36 + lw, ly);
            g.setFont(inter.deriveFont(12f));
            g.setColor(DIM);
            int my = ly + 24;
            for (String line2 : wrapLines(g, st.motd().replace('\n', ' '), cw - 40, 2)) { g.drawString(line2, x + 20, my); my += 17; }
        } else {
            boolean down = serverDown.contains(k);
            g.setColor(down ? RED_HI : FAINT);
            g.fill(new Ellipse2D.Double(x + 20, ly - 8, 8, 8));
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(DIM);
            g.drawString(serverPinging.contains(k) ? "Checking…" : down ? "No answer (offline, or the address is wrong)" : "Not checked yet", x + 36, ly);
        }
        int by = y + ch - 56;
        game.serverJoin(g, x + 20, by, 84, 36, index);
        ghostButton(g, "ov-copy", x + 112, by, 96, 36, "Copy", false, "copy");
        ghostButton(g, "ov-edit", x + 216, by, 64, 36, "Edit", false);
        ghostButton(g, "ov-remove", x + 288, by, 72, 36, "Remove", true);
        hitMap.put("ov-card", new Rectangle(x, y, cw, ch));
        for (String id : new String[]{"gp-join", "ov-copy", "ov-edit", "ov-remove"}) { Rectangle r = hitMap.remove(id); if (r != null) hitMap.put(id, r); }
    }

    private void paintNotices(Graphics2D g) {
        int w = 360, x = W - 24 - w, y = 72;
        int rows = Math.min(6, notices.size());
        int h = 64 + Math.max(1, rows) * 60 + 8;
        raised(g, x, y, w, h);
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Notifications", x + 20, y + 36);
        if (!notices.isEmpty()) textLink(g, "ov-clear", "Clear all", x + w - 20, y + 36, 1);
        if (notices.isEmpty()) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(DIM);
            g.drawString("You're all caught up.", x + 20, y + 90);
        }
        int ry = y + 56;
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows; i++) {
            Notice n = notices.get(i);
            g.setColor(new Color(255, 255, 255, 6));
            g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 54, 20, 20));
            g.setColor(n.error() ? new Color(245, 165, 36, 40) : new Color(225, 29, 46, 36));
            g.fill(new RoundRectangle2D.Double(x + 20, ry + 11, 32, 32, 16, 16));
            icon(g, n.error() ? "alert" : "bell", x + 36, ry + 27, 14, n.error() ? AMBER : RED_HI);
            g.setFont(interBold.deriveFont(13f));
            g.setColor(TEXT);
            String ago = ago(now - n.at());
            g.drawString(ellipsizeEnd(g, n.title(), w - 130), x + 64, ry + 23);
            g.setFont(inter.deriveFont(11f));
            g.setColor(FAINT);
            g.drawString(ago, x + w - 24 - g.getFontMetrics().stringWidth(ago), ry + 23);
            g.setFont(inter.deriveFont(12f));
            g.setColor(DIM);
            g.drawString(ellipsizeEnd(g, n.body(), w - 90), x + 64, ry + 41);
            ry += 60;
        }
        hitMap.put("ov-panel", new Rectangle(x, y, w, h));
        Rectangle clear = hitMap.remove("ov-clear");
        if (clear != null) hitMap.put("ov-clear", clear);
    }

    /**
     * Friends: a hook for the friends system that's being built. With {@link #friends} filled in, it lists them;
     * until then it says they're coming.
     */
    private void paintFriends(Graphics2D g) {
        int w = 320, h = friends.isEmpty() ? 220 : 72 + Math.min(6, friends.size()) * 52 + 12;
        int x = W - 24 - w, y = 84 + 96 - 10;
        raised(g, x, y, w, h);
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Friends", x + 20, y + 36);
        if (friends.isEmpty()) {
            g.setColor(new Color(225, 29, 46, 36));
            g.fill(new RoundRectangle2D.Double(x + w / 2.0 - 28, y + 58, 56, 56, 28, 28));
            icon(g, "users", x + w / 2, y + 86, 24, RED_HI);
            g.setFont(interBold.deriveFont(14f));
            g.setColor(TEXT);
            center(g, "Friends are coming soon", x, y + 126, w, 20);
            g.setFont(inter.deriveFont(12f));
            g.setColor(DIM);
            int ly = y + 160;
            for (String l : wrapLines(g, "See who's online and play together, right from here.", w - 48, 2)) { center(g, l, x, ly - 13, w, 18); ly += 18; }
        } else {
            int ry = y + 56;
            for (int i = 0; i < Math.min(6, friends.size()); i++) {
                Friend f = friends.get(i);
                tile(g, x + 20, ry + 6, 36, f.name());
                g.setFont(interBold.deriveFont(13f));
                g.setColor(TEXT);
                g.drawString(ellipsizeEnd(g, f.name(), w - 100), x + 68, ry + 22);
                g.setFont(inter.deriveFont(12f));
                g.setColor(f.online() ? OK : FAINT);
                g.drawString(ellipsizeEnd(g, f.status(), w - 100), x + 68, ry + 40);
                ry += 52;
            }
        }
        hitMap.put("ov-panel", new Rectangle(x, y, w, h));
    }

    private static String ago(long ms) {
        long m = ms / 60_000;
        if (m < 1) return "now";
        if (m < 60) return m + " min";
        if (m < 24 * 60) return (m / 60) + " h";
        return (m / 1440) + " d";
    }

    // ------------------------------------------------------------------ clips

    /**
     * The old separate Evade Clips app is running and holds Clips' folder: an amber banner that says so, with a
     * button that closes it and starts the built-in Clips.
     */
    private void clipsBanner(Graphics2D g, int x, int y, int w) {
        int h = 76;
        g.setColor(new Color(245, 165, 36, 22));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 28, 28));
        g.setColor(new Color(245, 165, 36, 70));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 28, 28));
        g.setColor(new Color(245, 165, 36, 40));
        g.fill(new RoundRectangle2D.Double(x + 18, y + 18, 40, 40, 20, 20));
        icon(g, "alert", x + 38, y + 38, 18, AMBER);
        g.setFont(interBold.deriveFont(14f));
        g.setColor(TEXT);
        int bw = 168;
        g.drawString(ellipsizeEnd(g, "The old Evade Clips app is still running", w - 110 - bw), x + 72, y + 33);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, clipsBusy ? "Closing it and starting Clips…" : "Clips is built into the launcher now. Close the old app to use it here.", w - 110 - bw), x + 72, y + 53);
        if (clipsBusy) {
            g.setColor(new Color(0x2A2A31));
            g.fill(new RoundRectangle2D.Double(x + w - 18 - bw, y + 18, bw, 40, 24, 24));
            g.setFont(interBold.deriveFont(13f));
            g.setColor(DIM);
            center(g, "Closing…", x + w - 18 - bw, y + 18, bw, 40);
            animating = true;
        } else smallPrimary(g, "clips-takeover", x + w - 18 - bw, y + 18, bw, 40, "Close it now", null);
    }

    private Rectangle paintClips(Graphics2D g) {
        pageTitle(g, "Clips", null);
        // segmented tabs
        int ty = 84;
        String[][] tabs = {{"library", "Library"}, {"record", "Record"}, {"editor", "Editor"}, {"settings", "Settings"}};
        int tx = CX + 4, segX = CX;
        g.setFont(interBold.deriveFont(13f));
        int total = 8;
        int[] widths = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            int count = tabs[i][0].equals("library") && clips != null ? clips.library.all().size() : -1;
            widths[i] = g.getFontMetrics().stringWidth(tabs[i][1]) + 32 + (count >= 0 ? g.getFontMetrics().stringWidth(String.valueOf(count)) + 16 : 0);
            total += widths[i];
        }
        g.setColor(new Color(0x141418));
        g.fill(new RoundRectangle2D.Double(segX, ty, total, 44, 26, 26));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(segX + .5, ty + .5, total - 1, 43, 26, 26));
        for (int i = 0; i < tabs.length; i++) {
            String[] t = tabs[i];
            boolean en = clips != null && (!t[0].equals("editor") || clipsEditorOpen());
            boolean on = clips != null && clipsPage.equals(t[0]);
            String id = "ctab:" + t[0];
            float a = en ? anim(id, id.equals(hovered)) : 0;
            float s = anim(id + "-on", on);
            if (s > 0.01f) {
                g.setColor(new Color(225, 29, 46, Math.round(255 * s)));
                g.fill(new RoundRectangle2D.Double(tx, ty + 4, widths[i], 36, 20, 20));
            } else if (a > 0.01f) {
                g.setColor(new Color(255, 255, 255, Math.round(12 * a)));
                g.fill(new RoundRectangle2D.Double(tx, ty + 4, widths[i], 36, 20, 20));
            }
            g.setFont(interBold.deriveFont(13f));
            g.setColor(on ? Color.WHITE : en ? mix(DIM, TEXT, a) : FAINT);
            g.drawString(t[1], tx + 16, ty + 27);
            if (t[0].equals("library") && clips != null) {
                String c = String.valueOf(clips.library.all().size());
                int lw = g.getFontMetrics().stringWidth(t[1]);
                g.setFont(interBold.deriveFont(10.5f));
                int cw = g.getFontMetrics().stringWidth(c) + 12;
                g.setColor(on ? new Color(255, 255, 255, 50) : new Color(255, 255, 255, 16));
                g.fill(new RoundRectangle2D.Double(tx + 16 + lw + 7, ty + 13, cw, 18, 18, 18));
                g.setColor(on ? Color.WHITE : DIM);
                center(g, c, tx + 16 + lw + 7, ty + 13, cw, 18);
            }
            if (en) hit(id, new Rectangle(tx, ty + 4, widths[i], 36));
            tx += widths[i];
        }
        // replay switch and Save clip on the right
        int rx = CX + CW;
        if (clips != null) {
            smallPrimary(g, "clips-save", rx - 120, ty + 2, 120, 40, "Save clip", "clip");
            g.setFont(inter.deriveFont(13f));
            String rs = "Instant replay";
            int rw2 = g.getFontMetrics().stringWidth(rs);
            int sx = rx - 120 - 20 - 38;
            toggle(g, "clips-replay-sw", sx, ty + 12, clips.settings.replayEnabled, true);
            hit("clips-replay", new Rectangle(sx - 12 - rw2, ty + 2, rw2 + 56, 40));
            g.setColor(DIM);
            g.drawString(rs, sx - 12 - rw2, ty + 27);
        }
        int top = ty + 44 + 16;
        if (clipsBlocked) {
            clipsBanner(g, CX, top, CW);
            top += 76 + 16;
        }
        Rectangle box = new Rectangle(CX, top, CW, H - top - 24);
        if (clips == null || clipsHost == null) {
            card(g, box.x, box.y, box.width, box.height, false);
            g.setColor(new Color(255, 255, 255, 10));
            g.fill(new RoundRectangle2D.Double(box.x + box.width / 2.0 - 32, box.y + box.height / 2.0 - 84, 64, 64, 32, 32));
            icon(g, "clip", box.x + box.width / 2, box.y + box.height / 2 - 52, 26, DIM);
            g.setFont(interBold.deriveFont(17f));
            g.setColor(TEXT);
            center(g, clipsBlocked ? "Clips will open here" : clips == null ? "Evade Clips isn't running" : "Opening Evade Clips…", box.x, box.y + box.height / 2 - 6, box.width, 24);
            g.setFont(inter.deriveFont(13f));
            g.setColor(DIM);
            int ly = box.y + box.height / 2 + 34;
            String msg = clipsBlocked ? "Instant replay, your clip library and the editor, once the old app is closed." : clipsNote != null ? clipsNote : "One moment.";
            for (String l : wrapLines(g, msg, Math.min(520, box.width - 80), 3)) { center(g, l, box.x, ly - 13, box.width, 18); ly += 20; }
            return null;
        }
        return box;
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

    void badge(Graphics2D g, int cx, int cy, String s) {
        int was = ThemedGraphics.danger(g); // "!" means a problem: red whatever the accent
        g.setColor(new Color(225, 29, 46, 40));
        g.fill(new Ellipse2D.Double(cx - 26, cy - 26, 52, 52));
        g.setColor(RED);
        g.fill(new Ellipse2D.Double(cx - 18, cy - 18, 36, 36));
        g.setFont(interBold.deriveFont(20f));
        g.setColor(Color.WHITE);
        center(g, s, cx - 18, cy - 18, 36, 36);
        ThemedGraphics.mode(g, was);
    }

    /** A solid red button with a play icon (the hero's other states: try again, get the launcher). */
    private void playButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean enabled) {
        float t = enabled ? anim(id, id.equals(hovered)) : 0;
        if (enabled) shadowGlow(g, x, y, w, h, t);
        g.setColor(enabled ? mix(RED, RED_HI, t) : new Color(0x2A1418));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 28, 28));
        g.setFont(interBold.deriveFont(label.length() > 12 ? 15f : 17f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label), iw = 13, gap = 10;
        int tx = x + (w - tw - iw - gap) / 2;
        Color fg = enabled ? Color.WHITE : FAINT;
        icon(g, "play", tx + iw / 2, y + h / 2, iw, fg);
        g.setColor(fg);
        g.drawString(label, tx + iw + gap, y + (h - fm.getHeight()) / 2f + fm.getAscent());
        hit(id, new Rectangle(x, y, w, h));
        game.name(id, label);
    }

    /** A small dot: green when the instance is up to date, amber when Play will update it first, grey before the check. */
    void statusDot(Graphics2D g, int x, int y, Launcher.Instance i) {
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

    /** A mod's real icon (rounded), or a neutral tile with its initial while there's none; faded when switched off. */
    void modIcon(Graphics2D g, int x, int y, int s, String name, Image img, boolean enabled) {
        Composite c0 = g.getComposite();
        if (!enabled) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        if (img != null && img.getWidth(null) > 1) {
            Shape was = g.getClip();
            RoundRectangle2D r = new RoundRectangle2D.Double(x, y, s, s, s * 0.45, s * 0.45);
            g.setColor(new Color(0x1C1C22));
            g.fill(r);
            g.clip(r);
            Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            // small pixel-art icons stay crisp; big ones scale smoothly
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, img.getWidth(null) < s ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, x, y, s, s, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            g.setClip(was);
        } else tile(g, x, y, s, name);
        g.setComposite(c0);
    }

    /** A neutral rounded tile with the first letter of a name (when there's no picture). */
    void tile(Graphics2D g, int x, int y, int s, String name) {
        g.setColor(new Color(0x24242B));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, s * 0.6, s * 0.6));
        String letter = name == null || name.isBlank() ? "?" : name.strip().substring(0, 1).toUpperCase();
        g.setFont(interBold.deriveFont(s * 0.42f));
        g.setColor(new Color(0xB4B4BE));
        center(g, letter, x, y, s, s);
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
            int ry = y + 200 + i * 42;
            switchRow(g, rows[i][0], x, ry, w, avail[i] ? rows[i][1] : rows[i][2], values[i], avail[i]);
        }
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.setColor(new Color(225, 29, 46, 26));
        g.fill(new RoundRectangle2D.Double(x, y + 200 + rows.length * 42 + 6, w, 52, 24, 24));
        icon(g, "clip", x + 24, y + 200 + rows.length * 42 + 32, 16, RED_HI);
        g.setFont(interBold.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString("Evade Clips is built in", x + 46, y + 200 + rows.length * 42 + 28);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, "Instant replay, clip library and editor", w - 60), x + 46, y + 200 + rows.length * 42 + 45);
        g.setFont(inter.deriveFont(12f));
        String info;
        Color ic = DIM;
        if (releaseError != null) { info = EvadeInstaller.sentence(releaseError) + " Check your connection and press Retry."; ic = LauncherTheme.DANGER; }
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
        pageTitle(g, "Instances", "Each has its own mods, settings, worlds and Minecraft Launcher profile.");
        int lx = CX, ly = 100, lw = 320, lh = H - ly - 24;
        card(g, lx, ly, lw, lh, false);
        extras.paintInstanceList(g, lx, ly, lw, lh);
        ghostButton(g, "inst-new", lx + 16, ly + lh - 60, lw - 32, 44, "New instance", false, "plus");
        Launcher.Instance foc = focused();
        if (foc != null) paintInstanceDetail(g, foc, lx + lw + 24, ly, CW - lw - 24, lh);
    }

    /** An instance's tile: Evade's mark for Main, else a neutral tile with its initial. */
    void instanceTile(Graphics2D g, int x, int y, int s, Launcher.Instance i) {
        if (game.paintIcon(g, x, y, s, i)) return;
        if (i.isDefault()) {
            g.setColor(new Color(225, 29, 46, 40));
            g.fill(new RoundRectangle2D.Double(x, y, s, s, s * 0.6, s * 0.6));
            g.drawImage(mark, x + s / 5, y + s / 5, s * 3 / 5, s * 3 / 5, null);
        } else tile(g, x, y, s, i.name);
    }

    /** A settings row: the name (and a line under it) on the left, the control on the right, a hairline below. */
    void rowLabel(Graphics2D g, int x, int y, int h, String title, String sub, boolean last, int w) {
        rowLabel(g, x, y, h, title, sub, last, w, w);
    }

    /** As above, with the line under the name kept within {@code subW} (clear of a control on the right). */
    void rowLabel(Graphics2D g, int x, int y, int h, String title, String sub, boolean last, int w, int subW) {
        g.setFont(interBold.deriveFont(13.5f));
        g.setColor(TEXT);
        int ty = sub == null ? y + h / 2 + 5 : y + h / 2 - 3;
        g.drawString(title, x, ty);
        if (sub != null) {
            g.setFont(inter.deriveFont(12f));
            g.setColor(FAINT);
            g.drawString(ellipsizeEnd(g, sub, subW), x, ty + 19);
        }
        if (!last) {
            g.setColor(HAIRLINE);
            g.fillRect(x, y + h, w, 1);
        }
    }

    private void paintInstanceDetail(Graphics2D g, Launcher.Instance i, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        int ix = x + 28, iw = w - 56;
        instanceTile(g, ix, y + 26, 52, i);
        g.setFont(interBold.deriveFont(22f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, i.name, iw - 340), ix + 68, y + 50);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, "Profile “" + i.profileName() + "”", iw - 340), ix + 68, y + 72);
        boolean playing = i.id.equals(settings.selected);
        int toolsRight = ix + iw - 140 - 12;
        if (playing) {
            g.setFont(interBold.deriveFont(12f));
            String s2 = "Play starts this one";
            int sw = g.getFontMetrics().stringWidth(s2) + 36;
            g.setColor(new Color(61, 220, 132, 26));
            g.fill(new RoundRectangle2D.Double(ix + iw - sw, y + 32, sw, 32, 32, 32));
            g.setColor(OK);
            g.fill(new Ellipse2D.Double(ix + iw - sw + 14, y + 45, 7, 7));
            g.drawString(s2, ix + iw - sw + 28, y + 53);
            toolsRight = ix + iw - sw - 12;
        } else smallPrimary(g, "inst-use", ix + iw - 140, y + 30, 140, 40, "Play this", "play");
        extras.paintInstanceTools(g, i, toolsRight, y + 30);

        int ctl = 300, cx = ix + iw - ctl, ry = y + 100, rh = 64;
        rowLabel(g, ix, ry, rh, "Folder", ellipsize(g, i.dir.toString(), iw - 280), false, iw);
        ghostButton(g, "inst-export", ix + iw - 248, ry + 14, 120, 36, "Export", false, "external");
        ghostButton(g, "inst-folder", ix + iw - 120, ry + 14, 120, 36, "Open", false, "folder");
        game.name("inst-folder", "Open the instance folder in the file manager");
        game.name("inst-export", "Export this instance as a zip");
        ry += rh;
        rowLabel(g, ix, ry, rh, "Memory", tools.memoryLine(i.memoryGb, "How much Minecraft may use"), false, iw, iw - ctl - 12);
        if (tools.memoryWarn(i.memoryGb)) { g.setColor(AMBER); g.fill(new Ellipse2D.Double(ix - 12, ry + rh / 2 + 9, 6, 6)); }
        memoryChips(g, cx, ry + 14, ctl, i.memoryGb);
        ry += rh;
        boolean voice = release == null || release.voiceChat() != null, perf = release == null || !release.performance().isEmpty(), sh = release == null || release.iris() != null;
        String[][] rows = {{"inst-perf", "FPS mods", "Sodium, Lithium and friends"}, {"inst-shaders", "Shaders", ShaderPacks.rowText(i.shaderPacks)},
                {"inst-voice", "Voice chat", "Simple Voice Chat"},
                {"inst-packs", "Resource pack features", "Connected textures, custom items, entities and skies"},
                {"inst-lights", "Dynamic lights", "Held torches light up the area"}};
        boolean packs = release == null || !release.packFeatures().isEmpty(), lights = release == null || release.dynamicLights() != null;
        boolean[] val = {i.perf, i.shaders, i.voice, i.packs, i.lights}, av = {perf, sh, voice, packs, lights};
        // Rows tighten on small windows so the note and the buttons below stay clear (the last row is Optional extras).
        int oh = Math.max(40, Math.min(rh - 8, (y + h - 28 - 40 - 34 - ry) / (rows.length + 1)));
        for (int k = 0; k < rows.length; k++) {
            rowLabel(g, ix, ry, oh, rows[k][1], av[k] ? rows[k][2] : "Not available right now", false, iw, k == 1 ? iw - 170 : iw);
            toggle(g, rows[k][0] + "-sw", ix + iw - 44, ry + oh / 2 - 12, val[k], av[k]);
            if (av[k]) hit(rows[k][0], new Rectangle(ix, ry, iw, oh));
            game.name(rows[k][0], rows[k][1] + ", " + (val[k] ? "on" : "off"));
            if (k == 1 && av[k]) tools.shaderPacks.paintRowButton(g, i, ix, ry, oh, iw); // the Shader packs chooser
            ry += oh;
        }
        tools.paintExtrasRow(g, i, ix, ry, oh, iw);
        ry += oh - 6;
        g.setFont(inter.deriveFont(12f));
        g.setColor(i.dirty ? AMBER : FAINT);
        g.drawString(i.dirty ? "Changed: applied the next time you press Play with this instance." : "Changes apply the next time you press Play.", ix, ry + 26);

        int by = y + h - 28 - 40, bw = (iw - 4 * 10) / 5;
        ghostButton(g, "inst-copy", ix, by, bw, 40, "Copy settings", false, "copy");
        ghostButton(g, "inst-rename", ix + (bw + 10), by, bw, 40, "Rename", false);
        ghostButton(g, "inst-dup", ix + 2 * (bw + 10), by, bw, 40, "Duplicate", false, "layers");
        ghostButton(g, "inst-more", ix + 3 * (bw + 10), by, bw, 40, "More", false, "list");
        game.name("inst-more", "More: start in safe mode, desktop shortcut, optional extras");
        if (!i.isDefault()) ghostButton(g, "inst-del", ix + 4 * (bw + 10), by, bw, 40, "Delete", true, "trash");
        else {
            g.setFont(inter.deriveFont(12f));
            g.setColor(FAINT);
            center(g, "Main stays", ix + 4 * (bw + 10), by, bw, 40);
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
        g.fill(new RoundRectangle2D.Double(px, 96, pw, 40, 24, 24));
        icon(g, "layers", px + 22, 116, 15, DIM);
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, nm, pw - 70), px + 40, 121);
        icon(g, "chevron-down", px + pw - 20, 116, 9, DIM);
        hit("mods-instance", new Rectangle(px, 96, pw, 40));

        // tabs
        int ty = 100;
        tab(g, "tab-installed", CX, ty, "Installed", mods.size(), !browse);
        tab(g, "tab-browse", CX + 140, ty, "Browse Modrinth", -1, browse);
        g.setColor(HAIRLINE);
        g.fillRect(CX, ty + 38, CW, 1);

        int y = ty + 52, h = H - y - 24;
        if (!browse) { paintInstalledMods(g, sel, CX, y, CW, h); return null; }
        return paintBrowse(g, CX, y, CW, h);
    }

    void tab(Graphics2D g, String id, int x, int y, String label, int count, boolean on) {
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
        game.name(id, label + " tab" + (count >= 0 ? ", " + count : "") + (on ? ", selected" : ""));
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
                modIcon(g, x + 22, ry + 9, 38, e.name(), "Evade".equals(e.option()) && e.name().startsWith("Evade") ? mark : ModIcons.get(e.file(), this::repaint), e.enabled());
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
            g.setColor(searchError != null ? LauncherTheme.DANGER : DIM);
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
                modIcon(g, x + 22, ry + 13, 42, hh.title(), ModIcons.url(hh.iconUrl(), this::repaint), true);
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
        int y = 92;
        if (clipsBlocked) {
            clipsBanner(g, CX, y, CW);
            y += 76 + 16;
        }
        int lw = 600, rw = CW - lw - 24;
        // Game on top, Appearance under it (one line per row when the window is short of room)
        int full = 56 + 4 * 66 + 44, room = H - 24 - y - 24;
        boolean compactLook = room - full < ViewExtras.appearanceHeight(false);
        int h = Math.min(full, room - ViewExtras.appearanceHeight(compactLook));
        // ---- Game
        int lx = CX;
        card(g, lx, y, lw, h, false);
        int ix = lx + 28, iw = lw - 56, ctl = 300, cx = ix + iw - ctl;
        g.setFont(interBold.deriveFont(17f));
        g.setColor(TEXT);
        g.drawString("Game", ix, y + 40);
        int ry = y + 56, rh = 66;
        rowLabel(g, ix, ry, rh, "Minecraft folder", ellipsize(g, settings.minecraftDir().toString(), iw - 200), false, iw);
        ghostButton(g, "open-mc", ix + iw - 172, ry + 15, 84, 36, "Open", false);
        ghostButton(g, "set-folder", ix + iw - 80, ry + 15, 80, 36, "Change", false);
        ry += rh;
        rowLabel(g, ix, ry, rh, "Memory for new instances", tools.memoryLine(settings.defaultMemoryGb,
                "Recommended here: " + recommendedGb + " GB" + (totalGb > 0 ? " of " + totalGb + " GB" : "")), false, iw, iw - ctl - 12);
        memoryChips(g, cx, ry + 15, ctl, settings.defaultMemoryGb);
        ry += rh;
        rowLabel(g, ix, ry, rh, "Java", "Optional: leave empty", false, iw);
        int fw = ctl - 88;
        box(g, cx, ry + 15, fw, 36);
        Rectangle field = new Rectangle(cx + 12, ry + 19, fw - 44, 28);
        if (javaField.getText().isEmpty()) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString("Minecraft Launcher's own", cx + 13, ry + 38);
        } else {
            float tc = anim("java-clear", "java-clear".equals(hovered));
            icon(g, "close-x", cx + fw - 18, ry + 33, 10, mix(FAINT, TEXT, tc));
            hit("java-clear", new Rectangle(cx + fw - 32, ry + 19, 28, 28));
        }
        ghostButton(g, "java-browse", cx + fw + 8, ry + 15, 80, 36, "Browse", false);
        ry += rh;
        rowLabel(g, ix, ry, rh, "Folders", "Open where things live", true, iw);
        int bw = (ctl - 16) / 3;
        ghostButton(g, "open-inst", cx, ry + 15, bw, 36, "Instance", false);
        ghostButton(g, "open-clips", cx + bw + 8, ry + 15, bw, 36, "Clips", false);
        ghostButton(g, "open-data", cx + 2 * (bw + 8), ry + 15, bw, 36, "Launcher", false);
        g.setFont(inter.deriveFont(12f));
        g.setColor(FAINT);
        int hy = ry + rh + 24;
        String hint = h >= 56 + 4 * 66 + 84 ? "Java: only set one if you know you need it. The Minecraft Launcher picks the right Java for "
                + InstallerCore.MC_VERSION + "; a path set here is used by every Evade instance (javaw.exe on Windows)."
                : "Java: leave it empty unless you need it; Minecraft picks the right one.";
        for (String line : wrapLines(g, hint, iw, 3)) {
            if (hy > y + h - 16) break;
            g.drawString(line, ix, hy);
            hy += 17;
        }
        extras.paintAppearance(g, lx, y + h + 24, lw, H - 24 - (y + h + 24));

        // ---- Launcher
        int rx = lx + lw + 24, jx = rx + 24, jw = rw - 48;
        boolean tray = clips != null && clips.settings.minimizeToTray;
        int lh = (clips != null ? 56 + 4 * 58 + 8 : 56 + 2 * 58 + 8) + (InstallerCore.WINDOWS ? 58 : 0);
        card(g, rx, y, rw, lh, false);
        g.setFont(interBold.deriveFont(17f));
        g.setColor(TEXT);
        g.drawString("Launcher", jx, y + 40);
        int sy = y + 52;
        sy = toggleRow(g, "set-close", jx, sy, jw, "Hide on Play", tray ? "To the tray: Clips keeps recording" : "Once the Minecraft Launcher opens", settings.closeOnPlay, false);
        sy = toggleRow(g, "set-updates", jx, sy, jw, "Launcher updates", "Ask when a newer version is out", settings.checkUpdates, clips == null && !InstallerCore.WINDOWS);
        if (InstallerCore.WINDOWS)
            sy = toggleRow(g, "set-gpu", jx, sy, jw, "Faster graphics card", "Windows graphics setting for Minecraft's Java", settings.preferGpu, clips == null);
        if (clips != null) {
            sy = toggleRow(g, "set-replay", jx, sy, jw, "Instant replay", "Evade Clips records while you play", clips.settings.replayEnabled, false);
            toggleRow(g, "set-tray", jx, sy, jw, "Keep running in the tray", "When the window is closed", tray, true);
        }

        // ---- About
        int ay = y + lh + 24, ah = H - 24 - ay;
        card(g, rx, ay, rw, ah, false);
        g.drawImage(mark, jx, ay + 22, 36, 36, null);
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Evade Launcher", jx + 48, ay + 38);
        g.setFont(inter.deriveFont(12f));
        g.setColor(FAINT);
        g.drawString(ellipsizeEnd(g, "Version " + version + "  ·  Sign-in stays with the Minecraft Launcher", jw - 48), jx + 48, ay + 56);
        int ly = ay + ah - 20 - 36;
        paintVersionRows(g, jx, ay + 76, jw, ly - 4);
        if (legacyClips && !clipsBlocked) {
            ghostButton(g, "clips-legacy", jx, ly, jw, 36, "Remove the old Evade Clips app", true, "trash");
        } else {
            List<String[]> links = new ArrayList<>();
            links.add(new String[]{"link-site", "Website", "globe"});
            links.add(new String[]{"link-docs", "Guides", "book"});
            links.add(new String[]{"link-changelog", "Changelog", "list"});
            if (!DISCORD.isBlank()) links.add(new String[]{"link-discord", "Discord", "chat"});
            int n = links.size(), lw2 = (jw - (n - 1) * 8) / n;
            for (int i = 0; i < n; i++) ghostButton(g, links.get(i)[0], jx + i * (lw2 + 8), ly, lw2, 36, links.get(i)[1], false, links.get(i)[2]);
        }
        return field;
    }

    /** A label with a line under it on the left and a switch on the right. Returns the y of the next row. */
    int toggleRow(Graphics2D g, String id, int x, int y, int w, String title, String sub, boolean on, boolean last) {
        int h = 58;
        rowLabel(g, x, y, h, title, sub, last, w);
        toggle(g, id + "-sw", x + w - 40, y + h / 2 - 10, on, true);
        hit(id, new Rectangle(x, y, w, h));
        game.name(id, title + ", " + (on ? "on" : "off") + (sub != null ? ". " + sub : ""));
        return y + h;
    }

    /** Evade, Fabric Loader and Fabric API: installed (in the selected instance) and newest, each with its icon. */
    /** The version rows from {@code y}; a row that would reach past {@code bottom} (the buttons) is left out. */
    private void paintVersionRows(Graphics2D g, int x, int y, int w, int bottom) {
        Launcher.Instance sel = selected();
        InstallerCore.Installed in = sel != null ? sel.installed : null;
        Path apiJar = null;
        if (sel != null) {
            for (Mods.Entry e : mods) if ("fabric-api".equals(e.id()) || e.fileName().startsWith("fabric-api")) apiJar = e.file();
            if (apiJar == null && in != null) apiJar = sel.dir.resolve("mods").resolve("fabric-api-" + in.fabricApiVersion() + ".jar");
        }
        Image api = apiJar != null && java.nio.file.Files.isRegularFile(apiJar) ? ModIcons.get(apiJar, this::repaint) : null;
        Object[][] rows = {
                {"Evade Client", mark, in != null ? "v" + in.evadeVersion() : "—", release != null ? "v" + release.evadeVersion() : "…"},
                {"Fabric Loader", null, in != null ? in.loaderVersion() : "—", release != null ? release.loaderVersion() : "…"},
                {"Fabric API", api, in != null ? InstallerCore.shortVersion(in.fabricApiVersion()) : "—", release != null ? InstallerCore.shortVersion(release.fabricApiVersion()) : "…"}};
        int ry = y;
        for (Object[] r : rows) {
            if (ry + 40 > bottom) break;
            modIcon(g, x, ry + 6, 32, (String) r[0], (Image) r[1], true);
            g.setFont(interBold.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString((String) r[0], x + 46, ry + 27);
            String have = (String) r[2], latest = (String) r[3];
            boolean same = have.equals(latest) || release == null;
            g.setFont(interBold.deriveFont(12.5f));
            FontMetrics fm = g.getFontMetrics();
            int lx = x + w - fm.stringWidth(latest);
            g.setColor(same ? DIM : RED_HI);
            g.drawString(latest, lx, ry + 27);
            if (!same) {
                g.setFont(inter.deriveFont(12.5f));
                String a = have + "  →  ";
                g.setColor(FAINT);
                g.drawString(a, lx - g.getFontMetrics().stringWidth(a), ry + 27);
            } else icon(g, "check", lx - 16, ry + 22, 10, release == null ? FAINT : OK);
            ry += 44;
        }
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
        float a = LauncherTheme.reduceMotion() ? 1f : age < 200 ? age / 200f : age > 3200 ? (3600 - age) / 400f : 1f;
        g.setFont(interBold.deriveFont(12.5f));
        int tw = g.getFontMetrics().stringWidth(toast) + 48;
        int x = CX + (CW - tw) / 2, y = H - 64 + Math.round((1 - a) * 10);
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, a))));
        shadow(g, x, y, tw, 38);
        g.setColor(RAISED);
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
        g.setColor(RAISED);
        g.fill(new RoundRectangle2D.Double(p.x(), p.y(), p.w(), h, 24, 24));
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
        boolean text = m.kind().equals("create") || m.kind().equals("rename") || m.kind().equals("group") || server || m.kind().startsWith("c:text");
        boolean create = m.kind().equals("create");
        int w = 440, h = text ? (create ? 470 : server ? 346 : 256) : 200;
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
            label(g, m.kind().startsWith("c:") ? content.fieldLabel(m) : "NAME", ix, fy + 6);
            box(g, ix, fy + 16, iw, 40);
            field = new Rectangle(ix + 12, fy + 22, iw - 24, 28);
            if (modalField.getText().isEmpty()) {
                g.setFont(inter.deriveFont(13f));
                g.setColor(FAINT);
                g.drawString(server ? "Optional: shown under the icon" : m.kind().startsWith("c:") ? content.fieldHint(m) : "PvP, Survival, Builds…", ix + 13, fy + 41);
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
                tools.paintTemplates(g, ix, fy + 156, iw);
            }
        }
        boolean danger = m.kind().equals("delete") || m.kind().equals("remove-mod") || m.kind().startsWith("c:del");
        int bw = 130;
        ghostButton(g, "modal-cancel", x + w - 28 - 2 * bw - 10, y + h - 64, bw, 40, "Cancel", false);
        if (danger) ghostButton(g, "modal-ok", x + w - 28 - bw, y + h - 64, bw, 40, m.confirm(), true, null, true);
        else smallPrimary(g, "modal-ok", x + w - 28 - bw, y + h - 64, bw, 40, m.confirm(), null);
        return field;
    }

    // ------------------------------------------------------------------ widgets

    void hit(String id, Rectangle r) {
        if (clip != null) r = r.intersection(clip);
        if (!r.isEmpty()) hitMap.put(id, r);
    }

    void scrollArea(Graphics2D g, String id, Rectangle area, int contentH) {
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

    void memoryChips(Graphics2D g, int x, int y, int w, int value) {
        int[] opts = memoryChoices;
        // one segmented control: a dark track, the chosen size filled red
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(x, y, w, 36, 22, 22));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, 35, 22, 22));
        int sw = (w - 8) / Math.max(1, opts.length);
        for (int i = 0; i < opts.length; i++) {
            int sx = x + 4 + i * sw;
            String id = "mem:" + opts[i];
            boolean on = value == opts[i];
            float t = anim(id, id.equals(hovered));
            if (on) {
                g.setColor(RED);
                g.fill(new RoundRectangle2D.Double(sx, y + 4, sw, 28, 16, 16));
            } else if (t > 0.01f) {
                g.setColor(new Color(255, 255, 255, Math.round(12 * t)));
                g.fill(new RoundRectangle2D.Double(sx, y + 4, sw, 28, 16, 16));
            }
            g.setFont(interBold.deriveFont(12.5f));
            g.setColor(on ? Color.WHITE : mix(DIM, TEXT, t));
            center(g, opts[i] + " GB", sx, y + 4, sw, 28);
            hit(id, new Rectangle(sx, y + 4, sw, 28));
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

    void toggle(Graphics2D g, String id, int tx, int ry, boolean value, boolean enabled) {
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
        int was = danger ? ThemedGraphics.danger(g) : -1;
        g.setColor(mix(new Color(255, 255, 255, 0), danger ? RED : new Color(255, 255, 255, 30), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        if (was >= 0) ThemedGraphics.mode(g, was);
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

    void bigButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean enabled) {
        float t = enabled ? anim(id, id.equals(hovered)) : 0;
        if (enabled) shadowGlow(g, x, y, w, h, t);
        g.setColor(enabled ? mix(RED, RED_HI, t) : new Color(0x2A1418));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        g.setFont(interBold.deriveFont(15f));
        g.setColor(enabled ? Color.WHITE : FAINT);
        center(g, label, x, y, w, h);
        hit(id, new Rectangle(x, y, w, h));
        game.name(id, label + (enabled ? "" : ", not available"));
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
    void smallPrimary(Graphics2D g, String id, int x, int y, int w, int h, String label, String iconKind) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(RED, RED_HI, t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, Math.min(h, 24), Math.min(h, 24)));
        g.setFont(interBold.deriveFont(13f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label), iw = iconKind != null ? 12 : 0, gap = iconKind != null ? 8 : 0;
        int tx = x + (w - tw - iw - gap) / 2;
        if (iconKind != null) icon(g, iconKind, tx + iw / 2, y + h / 2, iw, Color.WHITE);
        g.setColor(Color.WHITE);
        g.drawString(label, tx + iw + gap, y + (h - fm.getHeight()) / 2f + fm.getAscent());
        hit(id, new Rectangle(x, y, w, h));
        game.name(id, label);
    }

    void ghostButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger) {
        ghostButton(g, id, x, y, w, h, label, danger, null, false);
    }

    void ghostButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger, String iconKind) {
        ghostButton(g, id, x, y, w, h, label, danger, iconKind, false);
    }

    void ghostButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger, String iconKind, boolean solid) {
        float t = anim(id, id.equals(hovered));
        int was = danger ? ThemedGraphics.danger(g) : -1; // Delete stays red whatever the accent
        Color base = solid ? new Color(0x3A1218) : new Color(0x1C1C22);
        int r = Math.min(h, 24);
        g.setColor(danger ? mix(base, new Color(0x4A141B), t) : mix(base, new Color(0x26262D), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r, r));
        if (danger) {
            g.setColor(mix(new Color(225, 29, 46, 50), RED, t));
            g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, r, r));
        }
        g.setFont(interBold.deriveFont(13f));
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
        if (was >= 0) ThemedGraphics.mode(g, was);
        hit(id, new Rectangle(x, y, w, h));
        game.name(id, label);
    }

    void smallButton(Graphics2D g, String id, int x, int y, int w, int h, String label) {
        float hc = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(0x24242B), new Color(0x2E2E36), hc));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, Math.min(h, 20), Math.min(h, 20)));
        g.setFont(interBold.deriveFont(12f));
        g.setColor(TEXT);
        center(g, label, x, y, w, h);
        hit(id, new Rectangle(x, y, w, h));
        game.name(id, label);
    }

    void iconButton(Graphics2D g, String id, int x, int y, int s, String kind, boolean danger) {
        float t = anim(id, id.equals(hovered));
        int was = danger ? ThemedGraphics.danger(g) : -1;
        g.setColor(mix(new Color(255, 255, 255, 0), danger ? new Color(225, 29, 46, 60) : new Color(255, 255, 255, 24), t));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 9, 9));
        icon(g, kind, x + s / 2, y + s / 2, 14, mix(FAINT, danger ? RED_HI : TEXT, t));
        if (was >= 0) ThemedGraphics.mode(g, was);
        hit(id, new Rectangle(x, y, s, s));
    }

    /** Underline-free text link. align: 0 left, 1 right, 2 centred on x. */
    void textLink(Graphics2D g, String id, String s, int x, int y, int align) {
        float t = anim(id, id.equals(hovered));
        g.setFont(inter.deriveFont(12f));
        int w = g.getFontMetrics().stringWidth(s);
        int lx = align == 1 ? x - w : align == 2 ? x - w / 2 : x;
        g.setColor(mix(FAINT, TEXT, t));
        g.drawString(s, lx, y);
        hit(id, new Rectangle(lx - 6, y - 15, w + 12, 22));
        game.name(id, s);
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

    void box(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 20, 20));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 20, 20));
    }

    void label(Graphics2D g, String s, int x, int y) {
        g.setFont(interBold.deriveFont(10f));
        g.setColor(FAINT);
        spaced(g, s, x, y, 1.4f);
    }

    /** Line icons, drawn with shapes (fonts don't reliably have these glyphs). */
    static void icon(Graphics2D g, String kind, int cx, int cy, int s, Color c) {
        g.setColor(c);
        Graphics2D q = (Graphics2D) g.create();
        q.translate(cx, cy);
        double k = s / 18.0;
        q.scale(k, k);
        // a 1.75px line at the size drawn (the icon box is 18 units)
        q.setStroke(new BasicStroke((float) (1.75 / k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
            case "user-plus" -> {
                q.draw(new Ellipse2D.Double(-6.5, -8, 7, 7));
                Path2D p = new Path2D.Double();
                p.moveTo(-9, 8); p.curveTo(-9, 2.5, -6, 0.5, -3, 0.5); p.curveTo(0, 0.5, 3, 2.5, 3, 8);
                q.draw(p);
                q.draw(new Line2D.Double(6.5, -4, 6.5, 2));
                q.draw(new Line2D.Double(3.5, -1, 9.5, -1));
            }
            case "users" -> {
                q.draw(new Ellipse2D.Double(-7.5, -8, 7, 7));
                Path2D p = new Path2D.Double();
                p.moveTo(-10, 8); p.curveTo(-10, 2.5, -7, 0.5, -4, 0.5); p.curveTo(-1, 0.5, 2, 2.5, 2, 8);
                q.draw(p);
                q.draw(new Arc2D.Double(1.5, -8, 7, 7, -90, 230, Arc2D.OPEN));
                Path2D p2 = new Path2D.Double();
                p2.moveTo(4.5, 0.6); p2.curveTo(7.5, 0.8, 10, 3, 10, 8);
                q.draw(p2);
            }
            case "external" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-1, -7); p.lineTo(-7, -7); p.lineTo(-7, 7); p.lineTo(7, 7); p.lineTo(7, 1);
                q.draw(p);
                q.draw(new Line2D.Double(0, 0, 7.5, -7.5));
                Path2D h = new Path2D.Double();
                h.moveTo(2.5, -7.5); h.lineTo(7.5, -7.5); h.lineTo(7.5, -2.5);
                q.draw(h);
            }
            case "arrow-right" -> {
                q.draw(new Line2D.Double(-7.5, 0, 7.5, 0));
                Path2D p = new Path2D.Double();
                p.moveTo(2, -5.5); p.lineTo(7.5, 0); p.lineTo(2, 5.5);
                q.draw(p);
            }
            case "alert" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(0, -8); p.lineTo(8.5, 7); p.lineTo(-8.5, 7); p.closePath();
                q.draw(p);
                q.draw(new Line2D.Double(0, -2.5, 0, 2));
                q.fill(new Ellipse2D.Double(-1.1, 3.6, 2.2, 2.2));
            }
            case "close-x" -> {
                q.draw(new Line2D.Double(-6, -6, 6, 6));
                q.draw(new Line2D.Double(6, -6, -6, 6));
            }
            case "copy" -> {
                q.draw(new RoundRectangle2D.Double(-4, -4, 12, 12, 3, 3));
                Path2D p = new Path2D.Double();
                p.moveTo(-8, 4); p.lineTo(-8, -6); p.quadTo(-8, -8, -6, -8); p.lineTo(4, -8);
                q.draw(p);
            }
            case "folder" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, 6.5); p.lineTo(-8, -6.5); p.lineTo(-2.5, -6.5); p.lineTo(-0.5, -4); p.lineTo(8, -4); p.lineTo(8, 6.5); p.closePath();
                q.draw(p);
            }
            case "pulse" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-9, 0); p.lineTo(-4.5, 0); p.lineTo(-2, -6.5); p.lineTo(2, 6.5); p.lineTo(4.5, 0); p.lineTo(9, 0);
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

    void spaced(Graphics2D g, String s, int x, int y, float spacing) {
        float cx = x;
        FontMetrics fm = g.getFontMetrics();
        for (char c : s.toCharArray()) {
            g.drawString(String.valueOf(c), cx, y);
            cx += fm.charWidth(c) + spacing;
        }
    }

    static void center(Graphics2D g, String s, int x, int y, int w, int h) {
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

    static String ellipsize(Graphics2D g, String s, int w) {
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

    float anim(String id, boolean target) {
        float to = target ? 1f : 0f;
        float v = hover.getOrDefault(id, to);
        v += (to - v) * (LauncherTheme.reduceMotion() ? 1f : 0.22f);
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
            BufferedImage img = in == null ? null : ImageIO.read(in);
            if (img == null) System.err.println("[launcher] no picture at " + path);
            return img != null ? argb(img) : new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        } catch (Throwable e) {
            System.err.println("[launcher] couldn't read " + path + ": " + e);
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
    }

    /**
     * The picture as plain 32-bit ARGB. JPEGs decode as 3-byte BGR, which some window surfaces (a translucent,
     * round-cornered window on Linux) silently fail to draw.
     */
    static BufferedImage argb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_INT_ARGB_PRE) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
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
        overlay = null;
    }

    /** Keeps scrolled lists inside their content (headless layout doesn't do it). */
    private static void clampViewports(java.awt.Component c) {
        if (c instanceof javax.swing.JViewport v && v.getView() != null) {
            Point p = v.getViewPosition();
            int maxY = Math.max(0, v.getView().getHeight() - v.getExtentSize().height);
            if (p.y > maxY || p.y < 0) v.setViewPosition(new Point(0, Math.max(0, Math.min(p.y, maxY))));
        }
        if (c instanceof java.awt.Container k) for (java.awt.Component ch : k.getComponents()) clampViewports(ch);
    }

    /** Lays out a component tree without a window (screenshots run headless, where validate() does nothing). */
    private static void layoutTree(java.awt.Component c) {
        if (c instanceof java.awt.Container k) {
            k.doLayout();
            for (java.awt.Component ch : k.getComponents()) layoutTree(ch);
        }
    }

    /** Renders the current state to an image (screenshots and tests). */
    BufferedImage snapshot(float scale) {
        int vw = Math.round(W * uiScale), vh = Math.round(H * uiScale);
        BufferedImage img = new BufferedImage(Math.round(vw * scale), Math.round(vh * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        g.setComposite(AlphaComposite.SrcOver);
        setSize(vw, vh);
        shownProgress = progress;
        for (float[] s : scroll.values()) s[1] = s[0];
        boolean was = roundCorners;
        roundCorners = true;
        // Lay the text fields out first (they're positioned while painting), then paint everything with them.
        paintComponent(img.createGraphics());
        if (clipsHost != null && clipsHost.isVisible()) {
            // twice: a scrolling list sizes itself from its width, which the first pass sets
            layoutTree(clipsHost);
            layoutTree(clipsHost);
            clampViewports(clipsHost);
        }
        paint(g);
        roundCorners = was;
        g.dispose();
        return img;
    }
}
