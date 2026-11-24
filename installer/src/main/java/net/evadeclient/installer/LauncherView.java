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
    public static final int W = 1040, H = 660;
    static final int SIDE = 228, CX = 256, CW = W - CX - 28;

    enum Page { HOME, INSTANCES, MODS, SETTINGS }

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
    }

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
    /** Evade Clips: the installed app (null if none) and its version. */
    boolean clipsPresent;
    String clipsVersion;
    String version = "2.0.0";

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
    boolean setupCopy = true, setupVoice = true, setupPerf = true, setupShaders = false, setupClips = true;

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
    record Modal(String kind, String title, String body, String confirm, Launcher.Instance target, Mods.Entry mod) {}
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

    final JTextField searchField, modalField, javaField;
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
        javaField = field("");
        add(searchField);
        add(modalField);
        add(javaField);
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

        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
        getActionMap().put("escape", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (popup != null) popup = null;
                else if (modal != null) modal = null;
                repaint();
            }
        });

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
            if (animating || scrolling || busy || toast != null || Math.abs(progress - shownProgress) > 1e-4) repaint();
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
        if (p == Page.MODS && selected() != null) actions.showMods(selected());
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
        switch (id) {
            case "close" -> { if (play != Play.UPDATING) actions.close(); }
            case "min" -> { if (w instanceof java.awt.Frame f) f.setState(java.awt.Frame.ICONIFIED); }
            case "nav-home" -> showPage(Page.HOME);
            case "nav-instances" -> showPage(Page.INSTANCES);
            case "nav-mods" -> showPage(Page.MODS);
            case "nav-settings" -> showPage(Page.SETTINGS);
            case "side-instance", "home-switch", "mods-instance" -> {
                List<String[]> items = new ArrayList<>();
                for (Launcher.Instance i : instances) items.add(new String[]{"pop:sel:" + i.id, i.name, i.id.equals(settings.selected) ? "✓" : ""});
                Rectangle r = hitMap.get(id);
                int pw = 240;
                int px = id.equals("side-instance") ? r.x : Math.min(W - 28 - pw, r.x);
                int py = id.equals("side-instance") ? r.y - 12 - items.size() * 38 : r.y + r.height + 6;
                popup = new Popup(id, px, py, pw, items);
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
            case "s-clips" -> setupClips = !setupClips;
            case "clips-open" -> actions.openClips();
            case "clips-install" -> actions.installClips();
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
            case "set-clips" -> { settings.clips = !settings.clips; actions.settingsChanged(); }
            case "java-browse" -> actions.browseJava();
            case "java-clear" -> { setJavaText(""); settings.javaPath = ""; actions.settingsChanged(); }
            case "open-mc" -> actions.openFolder(settings.minecraftDir());
            case "open-inst" -> { if (sel != null) actions.openFolder(sel.dir); }
            case "open-clips" -> actions.openFolder(InstallerCore.clipsAppDir());
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
        Rectangle search = null, java = null, modalBox = null;
        paintBackground(g);
        paintSidebar(g);
        switch (page) {
            case HOME -> { if (firstRun()) paintSetup(g); else paintHome(g); }
            case INSTANCES -> paintInstances(g);
            case MODS -> search = paintMods(g);
            case SETTINGS -> java = paintSettings(g);
        }
        button(g, "min", W - 74, 12, 28, 26, false);
        button(g, "close", W - 42, 12, 28, 26, true);
        paintToast(g);
        if (modal != null) {
            hitMap.clear();
            modalBox = paintModal(g);
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
            // sidebar panel
            b.setColor(new Color(0, 0, 0, 90));
            b.fillRect(0, 0, SIDE, H);
            b.setColor(new Color(255, 255, 255, 14));
            b.fillRect(SIDE, 0, 1, H);
            b.setColor(new Color(255, 255, 255, 18));
            b.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, 21, 21));
            b.dispose();
            bgScale = scale;
            bgRound = roundCorners;
        }
        g.drawImage(bgLayer, 0, 0, W, H, null);
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

    private void paintSidebar(Graphics2D g) {
        g.drawImage(mark, 26, 30, 38, 38, null);
        int wh = 44, ww = Math.round(wh * 320f / 96f);
        g.drawImage(word, 68, 30 + (38 - wh) / 2 + 1, ww, wh, null);
        g.setFont(interBold.deriveFont(8.5f));
        g.setColor(FAINT);
        spaced(g, "LAUNCHER", 71, 80, 3.0f);

        String[][] nav = {{"nav-home", "Home", "home"}, {"nav-instances", "Instances", "layers"}, {"nav-mods", "Mods", "cube"}, {"nav-settings", "Settings", "gear"}};
        Page[] pages = {Page.HOME, Page.INSTANCES, Page.MODS, Page.SETTINGS};
        for (int i = 0; i < nav.length; i++) {
            int y = 116 + i * 46;
            boolean on = page == pages[i];
            float t = anim(nav[i][0], nav[i][0].equals(hovered));
            float s = anim(nav[i][0] + "-on", on);
            if (s > 0) {
                g.setPaint(new GradientPaint(18, 0, new Color(225, 29, 46, (int) (56 * s)), 210, 0, new Color(225, 29, 46, (int) (8 * s))));
                g.fill(new RoundRectangle2D.Double(16, y, 196, 40, 11, 11));
                g.setColor(new Color(255, 65, 83, (int) (255 * s)));
                g.fill(new RoundRectangle2D.Double(16, y + 11, 3, 18, 3, 3));
            }
            if (t > 0 && !on) {
                g.setColor(new Color(255, 255, 255, (int) (12 * t)));
                g.fill(new RoundRectangle2D.Double(16, y, 196, 40, 11, 11));
            }
            Color c = mix(mix(DIM, TEXT, t), TEXT, s);
            icon(g, nav[i][2], 44, y + 20, 18, on ? RED_HI : c);
            g.setFont((on ? interBold : inter).deriveFont(13.5f));
            g.setColor(c);
            g.drawString(nav[i][1], 66, y + 25);
            hit(nav[i][0], new Rectangle(16, y, 196, 40));
        }

        // The instance Play starts, switchable from here.
        Launcher.Instance sel = selected();
        int bx = 16, by = H - 128, bw = 196, bh = 62;
        float t = anim("side-instance", "side-instance".equals(hovered));
        g.setColor(mix(new Color(0x101014), new Color(0x17171D), t));
        g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 13, 13));
        g.setColor(mix(BORDER, new Color(0x34343E), t));
        g.draw(new RoundRectangle2D.Double(bx + .5, by + .5, bw - 1, bh - 1, 13, 13));
        g.setFont(interBold.deriveFont(8.5f));
        g.setColor(FAINT);
        spaced(g, "PLAYING", bx + 14, by + 21, 1.6f);
        g.setFont(interBold.deriveFont(13.5f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, sel != null ? sel.name : "—", bw - 50), bx + 14, by + 43);
        statusDot(g, bx + bw - 44, by + 38, sel);
        icon(g, "chevron-up", bx + bw - 20, by + bh / 2, 10, DIM);
        hit("side-instance", new Rectangle(bx, by, bw, bh));

        g.setFont(inter.deriveFont(11f));
        float ts = anim("site", "site".equals(hovered));
        g.setColor(mix(FAINT, DIM, ts));
        g.drawString("evadeclient.com", 26, H - 34);
        int sw = g.getFontMetrics().stringWidth("evadeclient.com");
        hit("site", new Rectangle(22, H - 48, sw + 8, 20));
        g.setColor(new Color(0x45454F));
        String v = "v" + version;
        g.drawString(v, SIDE - 24 - g.getFontMetrics().stringWidth(v), H - 34);
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
        pageTitle(g, "Home", null);
        int x = CX, y = 92, w = CW, h = 218;
        paintHero(g, sel, x, y, w, h);

        int ny = y + h + 22, nh = H - ny - 26, nw = 468;
        paintNews(g, x, ny, nw, nh);
        int rx = x + nw + 18, rw = w - nw - 18;
        int vh = 150;
        paintVersions(g, sel, rx, ny, rw, vh);
        paintClipsCard(g, rx, ny + vh + 16, rw, nh - vh - 16);
    }

    private void paintHero(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        shadow(g, x, y, w, h);
        Shape box = new RoundRectangle2D.Double(x, y, w, h, 20, 20);
        g.setPaint(new GradientPaint(x, y, new Color(0x1A0D10), x + w, y + h, new Color(0x0F0F13)));
        g.fill(box);
        Shape was = g.getClip();
        g.clip(box);
        g.setPaint(new RadialGradientPaint(x + w - 150, y + 40, 330, new float[]{0f, 1f}, new Color[]{new Color(225, 29, 46, 85), new Color(225, 29, 46, 0)}));
        g.fill(box);
        // a big faint mark on the right
        Composite c0 = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.07f));
        g.drawImage(mark, x + w - 250, y - 20, 290, 290, null);
        g.setComposite(c0);
        // diagonal speed lines
        for (int i = 0; i < 5; i++) {
            Path2D band = new Path2D.Double();
            double bx = x + w - 470 + i * 52;
            band.moveTo(bx + 60, y); band.lineTo(bx + 76, y); band.lineTo(bx + 16, y + h); band.lineTo(bx, y + h); band.closePath();
            g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 7), 0, y + h, new Color(255, 255, 255, 0)));
            g.fill(band);
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
        label(g, "INSTANCE", x, y + 40);
        g.setFont(interBold.deriveFont(34f));
        g.setColor(TEXT);
        String name = sel != null ? sel.name : "—";
        String shown = ellipsizeEnd(g, name, w - 300);
        g.drawString(shown, x - 1, y + 82);
        int nw = g.getFontMetrics().stringWidth(shown);
        // switch instance
        float t = anim("home-switch", "home-switch".equals(hovered));
        int sx = x + nw + 14, sy = y + 58;
        g.setColor(mix(new Color(255, 255, 255, 14), new Color(255, 255, 255, 30), t));
        g.fill(new RoundRectangle2D.Double(sx, sy, 30, 30, 9, 9));
        icon(g, "chevron-down", sx + 15, sy + 15, 11, mix(DIM, TEXT, t));
        hit("home-switch", new Rectangle(sx, sy, 30, 30));

        g.setFont(inter.deriveFont(13f));
        g.setColor(DIM);
        String ver = sel != null && sel.installed != null ? "Evade Client v" + sel.installed.evadeVersion() : "Evade Client";
        g.drawString(ver + "  ·  Minecraft " + InstallerCore.MC_VERSION + "  ·  " + (sel != null ? sel.memoryGb : 0) + " GB"
                + (sel != null ? "  ·  " + sel.modCount + " mods" : ""), x, y + 110);

        // status pill
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
        g.setFont(inter.deriveFont(12f));
        pill = ellipsizeEnd(g, pill, w - 300);
        int pw = g.getFontMetrics().stringWidth(pill) + 34;
        g.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), 24));
        g.fill(new RoundRectangle2D.Double(x, y + 128, pw, 28, 28, 28));
        g.setColor(new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), 70));
        g.draw(new RoundRectangle2D.Double(x + .5, y + 128.5, pw - 1, 27, 27, 27));
        g.setColor(pc);
        g.fill(new Ellipse2D.Double(x + 13, y + 139, 6, 6));
        g.drawString(pill, x + 25, y + 147);
        if (release == null && releaseError != null) {
            float tr = anim("retry-release", "retry-release".equals(hovered));
            g.setColor(mix(DIM, TEXT, tr));
            g.drawString("Retry", x + pw + 12, y + 147);
            hit("retry-release", new Rectangle(x + pw + 6, y + 130, 50, 24));
        }

        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString(ellipsizeEnd(g, "Opens the Minecraft Launcher with \u201c" + (sel != null ? sel.profileName() : "Evade Client") + "\u201d selected. Sign-in stays there.", w - 250), x, y + h - 28);

        boolean ready = release != null || (releaseError != null && sel != null && sel.installed != null);
        String label = release == null && releaseError == null ? "Checking…" : sel != null && (sel.installed == null || reason != null || sel.dirty) && release != null ? "Update & Play" : "Play";
        playButton(g, "play", x + w - 220, y + h - 88, 220, 60, label, ready && sel != null);
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
        return s.equals(InstallerCore.CLIPS_BUSY) ? "Evade Clips wasn't updated (it may be open)" : s;
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

    private void paintNews(Graphics2D g, int x, int y, int w, int h) {
        card(g, x, y, w, h, true);
        label(g, "NEWS", x + 24, y + 32);
        textLink(g, "news-all", "Full changelog", x + w - 24, y + 32, 1);
        int top = y + 46, ah = h - 46 - 14;
        if (news.isEmpty()) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(newsError != null ? FAINT : DIM);
            g.drawString(newsLoading ? "Loading the latest news…" : newsError != null ? "News couldn't be loaded. " + EvadeInstaller.sentence(newsError) : "No news yet.", x + 24, top + 22);
            return;
        }
        Rectangle area = new Rectangle(x + 1, top, w - 2, ah);
        float off = scrollOf("news");
        Shape was = g.getClip();
        g.clip(area);
        clip = area;
        int cy = top + 8 - Math.round(off);
        for (int n = 0; n < news.size(); n++) {
            News.Entry e = news.get(n);
            int start = cy;
            boolean latest = n == 0;
            g.setFont(interBold.deriveFont(15f));
            g.setColor(TEXT);
            g.drawString(e.title(), x + 24, cy + 16);
            int tx = x + 24 + g.getFontMetrics().stringWidth(e.title()) + 10;
            if (latest) {
                g.setFont(interBold.deriveFont(9f));
                int pw = g.getFontMetrics().stringWidth("LATEST") + 14;
                g.setColor(new Color(225, 29, 46, 40));
                g.fill(new RoundRectangle2D.Double(tx, cy + 3, pw, 17, 17, 17));
                g.setColor(RED_HI);
                center(g, "LATEST", tx, cy + 3, pw, 17);
            }
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(FAINT);
            g.drawString(e.date(), x + w - 24 - g.getFontMetrics().stringWidth(e.date()), cy + 15);
            cy += 26;
            if (!e.summary().isEmpty()) {
                g.setFont(inter.deriveFont(12.5f));
                g.setColor(DIM);
                for (String line : wrapLines(g, e.summary(), w - 48, 2)) { g.drawString(line, x + 24, cy + 12); cy += 18; }
                cy += 4;
            }
            int shown = 0;
            for (String item : e.items()) {
                if (shown == (latest ? 5 : 3)) break;
                shown++;
                cy = bullet(g, item, x + 24, cy, w - 48);
            }
            if (e.items().size() > shown) {
                g.setFont(inter.deriveFont(11.5f));
                g.setColor(FAINT);
                g.drawString("and " + (e.items().size() - shown) + " more", x + 36, cy + 12);
                cy += 18;
            }
            hit("news:" + n, new Rectangle(x + 12, start - 4, w - 24, cy - start + 4));
            cy += 12;
            if (n < news.size() - 1) {
                g.setColor(BORDER);
                g.fillRect(x + 24, cy - 6, w - 48, 1);
                cy += 10;
            }
        }
        int content = cy + Math.round(off) - top;
        g.setClip(was);
        clip = null;
        scrollArea(g, "news", area, content);
    }

    /** One changelog bullet; a bold "Lead:" stays bold. Returns the y below it. */
    private int bullet(Graphics2D g, String item, int x, int y, int w) {
        g.setFont(inter.deriveFont(12f));
        int colon = item.indexOf(": ");
        String lead = colon > 0 && colon < 42 ? item.substring(0, colon + 1) : null;
        g.setColor(RED_HI);
        g.fill(new Ellipse2D.Double(x + 1, y + 8, 4, 4));
        List<String> lines = wrapLines(g, item, w - 14, 2);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lx = x + 14, ly = y + 13;
            if (i == 0 && lead != null && line.startsWith(lead)) {
                g.setFont(interBold.deriveFont(12f));
                g.setColor(TEXT);
                g.drawString(lead, lx, ly);
                int lw = g.getFontMetrics().stringWidth(lead);
                g.setFont(inter.deriveFont(12f));
                g.setColor(DIM);
                g.drawString(line.substring(lead.length()), lx + lw, ly);
            } else {
                g.setFont(inter.deriveFont(12f));
                g.setColor(DIM);
                g.drawString(line, lx, ly);
            }
            y += 17;
        }
        return y + 3;
    }

    private void paintVersions(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        label(g, "VERSIONS", x + 20, y + 30);
        g.setFont(inter.deriveFont(10f));
        g.setColor(FAINT);
        String cols = "INSTALLED → LATEST";
        g.drawString(cols, x + w - 20 - g.getFontMetrics().stringWidth(cols), y + 30);
        InstallerCore.Installed in = sel != null ? sel.installed : null;
        String[][] rows = {
                {"Evade Client", in != null ? "v" + in.evadeVersion() : "—", release != null ? "v" + release.evadeVersion() : "…"},
                {"Fabric Loader", in != null ? in.loaderVersion() : "—", release != null ? release.loaderVersion() : "…"},
                {"Fabric API", in != null ? InstallerCore.shortVersion(in.fabricApiVersion()) : "—", release != null ? InstallerCore.shortVersion(release.fabricApiVersion()) : "…"}};
        int ry = y + 56;
        for (String[] r : rows) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(r[0], x + 20, ry + 14);
            boolean same = r[1].equals(r[2]) || release == null;
            g.setFont(interBold.deriveFont(12f));
            FontMetrics fm = g.getFontMetrics();
            int lx = x + w - 20 - fm.stringWidth(r[2]);
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
            ry += 30;
        }
    }

    private void paintClipsCard(Graphics2D g, int x, int y, int w, int h) {
        card(g, x, y, w, h, false);
        label(g, "EVADE CLIPS", x + 20, y + 30);
        // record icon
        int ix = x + 20, iy = y + 46;
        g.setPaint(new GradientPaint(ix, iy, new Color(0x2A1116), ix + 40, iy + 40, new Color(0x16090C)));
        g.fill(new RoundRectangle2D.Double(ix, iy, 40, 40, 12, 12));
        g.setColor(new Color(225, 29, 46, 60));
        g.draw(new RoundRectangle2D.Double(ix + .5, iy + .5, 39, 39, 12, 12));
        g.setColor(RED_HI);
        g.fill(new Ellipse2D.Double(ix + 13, iy + 13, 14, 14));
        g.setColor(new Color(255, 65, 83, 70));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new Ellipse2D.Double(ix + 8, iy + 8, 24, 24));
        g.setStroke(new BasicStroke(1f));

        String latest = release != null ? release.clipsVersion() : null;
        boolean outdated = clipsPresent && latest != null && clipsVersion != null && !latest.equals(clipsVersion);
        g.setFont(interBold.deriveFont(13.5f));
        g.setColor(TEXT);
        g.drawString(clipsPresent ? "Installed" + (clipsVersion != null ? "  v" + clipsVersion : "") : "Not installed", ix + 52, iy + 17);
        g.setFont(inter.deriveFont(11.5f));
        g.setColor(outdated ? AMBER : DIM);
        g.drawString(ellipsizeEnd(g, outdated ? "v" + latest + " is out: updates with Play" : clipsPresent ? "Instant replay, clip library & editor" : "Record kills, pops & wins automatically", w - 92), ix + 52, iy + 35);
        int by = y + h - 52;
        if (by < iy + 48) by = iy + 48;
        if (clipsPresent) ghostButton(g, "clips-open", x + 20, by, w - 40, 34, "Open Evade Clips", false);
        else ghostButton(g, "clips-install", x + 20, by, w - 40, 34, release != null && release.clipsUrl() == null ? "Not available right now" : "Install Evade Clips", false);
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
                {"s-perf", "FPS mods (Sodium & more)", "FPS mods"}, {"s-shaders", "Shaders (Iris + Photon)", "Shaders"},
                {"s-clips", "Evade Clips (record & edit clips)", "Evade Clips"}};
        boolean[] values = {setupCopy, setupVoice, setupPerf, setupShaders, setupClips};
        boolean[] avail = {true, release == null || release.voiceChat() != null, release == null || !release.performance().isEmpty(),
                release == null || release.iris() != null, release == null || release.clipsUrl() != null};
        for (int i = 0; i < rows.length; i++) {
            int ry = y + 186 + i * 31;
            switchRow(g, rows[i][0], x, ry, w, avail[i] ? rows[i][1] : rows[i][2], values[i], avail[i]);
        }
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
        boolean clips = setupClips && release != null && release.clipsUrl() != null;
        boolean extras = setupVoice || setupPerf || setupShaders;
        List<String> steps = new ArrayList<>(List.of("Fabric Loader", "Fabric API", "Evade Client"));
        List<Double> at = new ArrayList<>(List.of(0.15, 0.55, 0.75));
        if (extras) { steps.add("Extra mods"); at.add(0.83); }
        if (clips) { steps.add("Evade Clips"); at.add(0.96); }
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

        int rx = lx + lw + 18, rw = CW - lw - 18, rh = 226;
        card(g, rx, y, rw, rh, false);
        int jx = rx + 24, jw = rw - 48;
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Launcher", jx, y + 38);
        settingRow(g, "set-close", jx, y + 60, jw, "Close the launcher on Play", "After the Minecraft Launcher opens", settings.closeOnPlay);
        settingRow(g, "set-updates", jx, y + 112, jw, "Check for updates on start", "Updates this launcher itself", settings.checkUpdates);
        settingRow(g, "set-clips", jx, y + 164, jw, "Keep Evade Clips up to date", "Updated along with Play", settings.clips);

        int fy = y + rh + 16, fh = h - rh - 16;
        card(g, rx, fy, rw, fh, false);
        g.setFont(interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString("Folders", jx, fy + 38);
        String[][] f = {{"open-mc", "Minecraft"}, {"open-inst", "This instance"}, {"open-clips", "Evade Clips"}, {"open-data", "Launcher"}};
        int bw = (jw - 10) / 2;
        for (int i = 0; i < f.length; i++) {
            int bx = jx + (i % 2) * (bw + 10), by = fy + 56 + (i / 2) * 44;
            ghostButton(g, f[i][0], bx, by, bw, 36, f[i][1], false, "folder");
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
        boolean text = m.kind().equals("create") || m.kind().equals("rename");
        boolean create = m.kind().equals("create");
        int w = 440, h = text ? (create ? 346 : 256) : 200;
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
                g.drawString("PvP, Survival, Builds…", ix + 13, fy + 41);
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
