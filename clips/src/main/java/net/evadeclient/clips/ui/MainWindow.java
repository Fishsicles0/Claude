package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.CaptureEngine;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sidebar navigation + pages. */
public final class MainWindow extends JFrame {
    private final App app;
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    public final LibraryPanel library;
    public final RecordPanel record;
    public final EditorPanel editor;
    public final SettingsPanel settings;
    private final Sidebar sidebar = new Sidebar();
    private String page = "library";
    private TrayIcon tray;
    /** Saves the window's place and size a moment after it was moved or resized. */
    private final Timer saveBounds;

    public MainWindow(App app) {
        super("Evade Clips");
        this.app = app;
        app.window = this;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        if (Theme.icon != null) setIconImage(Theme.icon);
        placeWindow();
        getContentPane().setBackground(Theme.BG);

        library = new LibraryPanel(app);
        record = new RecordPanel(app);
        editor = new EditorPanel(app);
        settings = new SettingsPanel(app);
        pages.add(library, "library");
        pages.add(record, "record");
        pages.add(editor, "editor");
        pages.add(settings, "settings");
        // Focus moves here when a page opens, so it never lands on a random control (or one hidden with the old page).
        pages.setFocusable(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(sidebar, BorderLayout.WEST);
        pages.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));
        root.add(pages, BorderLayout.CENTER);
        setContentPane(root);
        bindPageKeys();

        app.showWindow = () -> {
            setVisible(true);
            if ((getExtendedState() & ICONIFIED) != 0) setExtendedState(getExtendedState() & ~ICONIFIED);
            setAlwaysOnTop(true);
            toFront();
            requestFocus();
            setAlwaysOnTop(false);
        };
        app.openEditor = c -> SwingUtilities.invokeLater(() -> {
            if (c == null) { editor.close(); show("library"); return; }
            editor.open(c);
            show("editor");
        });

        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { if (page.equals("library")) library.focusGrid(); }
            @Override public void windowClosing(WindowEvent e) {
                rememberBounds();
                if (app.settings.minimizeToTray && tray != null) {
                    setVisible(false);
                    editor.close();
                    library.clearCaches(); // nothing to show while it's in the tray
                    if (!app.settings.shownTrayHint) {
                        tray.displayMessage("Evade Clips is still running", "Instant replay keeps going. Right-click the tray icon to quit.", TrayIcon.MessageType.INFO);
                        app.settings.shownTrayHint = true;
                        app.settings.save();
                    }
                } else quit();
            }
        });
        saveBounds = new Timer(1500, e -> rememberBounds());
        saveBounds.setRepeats(false);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { if (isShowing()) saveBounds.restart(); }
            @Override public void componentResized(ComponentEvent e) { if (isShowing()) saveBounds.restart(); }
        });
        addWindowStateListener(e -> saveBounds.restart());
        installTray();
        app.engine.onStatus(s -> SwingUtilities.invokeLater(sidebar::repaint));
        app.onJobs(() -> SwingUtilities.invokeLater(sidebar::repaint));
        // The recording dot pulses and job bars move; nothing to animate while the window is hidden or minimized.
        new Timer(500, e -> { if (isShowing() && (getExtendedState() & ICONIFIED) == 0) sidebar.repaint(); }).start();
    }

    // ------------------------------------------------------------------ window size and place

    /**
     * Fits the window on the screen it opens on (125-200% scaling leaves much less room than 1380×860), at the
     * place and size it had last time when that's still on a screen.
     */
    private void placeWindow() {
        Rectangle wa = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(900, wa.width), Math.min(600, wa.height)));
        int[] saved = app.settings.windowBounds;
        Rectangle restore = saved == null ? null : onScreen(new Rectangle(saved[0], saved[1], saved[2], saved[3]));
        if (restore != null) {
            setBounds(restore);
            if (app.settings.windowMaximized) setExtendedState(MAXIMIZED_BOTH);
            return;
        }
        int w = Math.min(1380, wa.width - 32), h = Math.min(860, wa.height - 32);
        setBounds(wa.x + (wa.width - w) / 2, wa.y + (wa.height - h) / 2, w, h);
        if (wa.width < 1200 || wa.height < 760 || app.settings.windowMaximized) setExtendedState(MAXIMIZED_BOTH);
    }

    /** {@code r} moved and shrunk into the work area of the screen it's mostly on, or null if it's on none. */
    private static Rectangle onScreen(Rectangle r) {
        if (r.width < 200 || r.height < 150) return null;
        GraphicsDevice best = null;
        long bestArea = 0;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle x = d.getDefaultConfiguration().getBounds().intersection(r);
            long area = x.isEmpty() ? 0 : (long) x.width * x.height;
            if (area > bestArea) { bestArea = area; best = d; }
        }
        if (best == null || bestArea < (long) r.width * r.height / 4) return null;
        GraphicsConfiguration gc = best.getDefaultConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle wa = new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
        int w = Math.min(r.width, wa.width), h = Math.min(r.height, wa.height);
        int x = Math.max(wa.x, Math.min(r.x, wa.x + wa.width - w)), y = Math.max(wa.y, Math.min(r.y, wa.y + wa.height - h));
        return new Rectangle(x, y, w, h);
    }

    private void rememberBounds() {
        if (!isDisplayable()) return;
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        boolean iconified = (getExtendedState() & ICONIFIED) != 0;
        var s = app.settings;
        int[] before = s.windowBounds;
        boolean beforeMax = s.windowMaximized;
        s.windowMaximized = max;
        // While maximized or minimized the frame's bounds aren't its normal size: keep the last normal one.
        if (!max && !iconified && isShowing()) {
            Rectangle r = getBounds();
            s.windowBounds = new int[]{r.x, r.y, r.width, r.height};
        }
        if (beforeMax != max || !java.util.Arrays.equals(before, s.windowBounds)) s.save();
    }

    // ------------------------------------------------------------------ pages

    public void show(String name) {
        if (page.equals("editor") && !name.equals("editor")) editor.close();
        page = name;
        cards.show(pages, name);
        sidebar.repaint();
        switch (name) {
            case "library" -> library.focusGrid();
            case "editor" -> {} // the editor puts focus on its video itself
            default -> pages.requestFocusInWindow();
        }
    }

    public String page() { return page; }

    /** Ctrl+1 … Ctrl+4 open Library, Record, Editor (when a clip is open) and Settings from anywhere in the window. */
    private void bindPageKeys() {
        var im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = getRootPane().getActionMap();
        String[] ids = {"library", "record", "editor", "settings"};
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            int mask = net.evadeclient.clips.core.Ffmpeg.MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, mask), "page-" + id);
            am.put("page-" + id, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { if (!id.equals("editor") || editor.clip() != null) show(id); }
            });
        }
    }

    void quit() {
        rememberBounds();
        setVisible(false);
        new Thread(() -> {
            app.shutdown();
            System.exit(0);
        }, "quit").start();
    }

    private void installTray() {
        if (!SystemTray.isSupported() || Theme.icon == null) return;
        PopupMenu m = new PopupMenu();
        MenuItem open = new MenuItem("Open Evade Clips");
        // Through showWindow, so a minimized window is restored too.
        open.addActionListener(e -> SwingUtilities.invokeLater(app.showWindow));
        MenuItem save = new MenuItem("Save replay");
        save.addActionListener(e -> app.saveClip(0, null, null, null));
        MenuItem rec = new MenuItem("Start / stop recording");
        rec.addActionListener(e -> app.toggleRecording());
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> SwingUtilities.invokeLater(this::quit));
        m.add(open);
        m.addSeparator();
        m.add(save);
        m.add(rec);
        m.addSeparator();
        m.add(quit);
        tray = new TrayIcon(Theme.icon, "Evade Clips", m);
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> SwingUtilities.invokeLater(app.showWindow));
        try { SystemTray.getSystemTray().add(tray); } catch (AWTException e) { tray = null; }
    }

    private static String shortcut(int n) {
        return (net.evadeclient.clips.core.Ffmpeg.MAC ? "⌘" : "Ctrl+") + n;
    }

    /**
     * Left navigation with the logo, pages, background jobs and a live status pill. It can be used from the
     * keyboard: Up/Down pick a page, Enter or Space opens it.
     */
    private final class Sidebar extends JComponent {
        private record Item(String id, String label, Theme.Glyph glyph) {}
        private final List<Item> items = List.of(
                new Item("library", "Library", Theme.Glyph.GRID),
                new Item("record", "Record", Theme.Glyph.CAMERA),
                new Item("editor", "Editor", Theme.Glyph.SCISSORS),
                new Item("settings", "Settings", Theme.Glyph.GEAR));
        private final List<Rectangle> hits = new ArrayList<>();
        private int hover = -1;
        /** The page the keyboard is on (shown with a ring while the sidebar has focus). */
        private int focused;
        private Rectangle statusHit = new Rectangle();
        /** Job cards drawn last time: where each one's × is, and the "+N more" card. */
        private final List<Object[]> cancelHits = new ArrayList<>();
        private Rectangle moreHit = new Rectangle();

        Sidebar() {
            setPreferredSize(new Dimension(220, 100));
            setFocusable(true);
            setToolTipText("");
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int h = -1;
                    for (int i = 0; i < hits.size(); i++) if (hits.get(i).contains(e.getPoint()) && enabled(i)) h = i;
                    if (h != hover) { hover = h; repaint(); }
                    boolean hand = h >= 0 || statusHit.contains(e.getPoint()) || moreHit.contains(e.getPoint()) || cancelAt(e) != null;
                    setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    App.ExportJob j = cancelAt(e);
                    if (j != null) { j.cancel(); repaint(); return; }
                    if (moreHit.contains(e.getPoint())) { showAllJobs(e); return; }
                    for (int i = 0; i < hits.size(); i++) if (hits.get(i).contains(e.getPoint()) && enabled(i)) { focused = i; MainWindow.this.show(items.get(i).id); }
                    if (statusHit.contains(e.getPoint())) MainWindow.this.show("record");
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_UP -> { move(-1); e.consume(); }
                        case KeyEvent.VK_DOWN -> { move(1); e.consume(); }
                        case KeyEvent.VK_HOME -> { focused = 0; repaint(); e.consume(); }
                        case KeyEvent.VK_END -> { focused = items.size() - 1; if (!enabled(focused)) move(-1); repaint(); e.consume(); }
                        case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { if (enabled(focused)) MainWindow.this.show(items.get(focused).id); e.consume(); }
                        default -> {}
                    }
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(page)) focused = i;
                    repaint();
                }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }

        private void move(int d) {
            int i = focused;
            for (int n = 0; n < items.size(); n++) {
                i = Math.floorMod(i + d, items.size());
                if (enabled(i)) break;
            }
            focused = i;
            repaint();
        }

        private App.ExportJob cancelAt(MouseEvent e) {
            for (Object[] h : cancelHits) if (((Rectangle) h[0]).contains(e.getPoint())) return (App.ExportJob) h[1];
            return null;
        }

        @Override public String getToolTipText(MouseEvent e) {
            for (int i = 0; i < hits.size(); i++)
                if (hits.get(i).contains(e.getPoint())) return items.get(i).label + " (" + shortcut(i + 1) + ")"
                        + (enabled(i) ? "" : ": open a clip first");
            if (cancelAt(e) != null) return "Cancel";
            return null;
        }

        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJComponent() {
                    @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PAGE_TAB_LIST; }
                };
                accessibleContext.setAccessibleName("Navigation");
            }
            return accessibleContext;
        }

        private boolean enabled(int i) { return !items.get(i).id.equals("editor") || editor.clip() != null; }

        /** Running jobs first, then queued, then finished; newest first in each. */
        private List<App.ExportJob> orderedJobs() {
            List<App.ExportJob> jobs = new ArrayList<>(app.jobs());
            jobs.sort(Comparator.comparingInt((App.ExportJob j) -> j.done ? 2 : "Queued".equals(j.state) ? 1 : 0)
                    .thenComparing(Comparator.comparingLong((App.ExportJob j) -> j.queuedAt).reversed()));
            return jobs;
        }

        /** Every job, with Cancel for the ones still to finish. */
        private void showAllJobs(MouseEvent e) {
            JPopupMenu m = new JPopupMenu();
            JPanel list = new JPanel();
            list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
            Theme.pad(list, 6, 10, 6, 10);
            for (App.ExportJob j : orderedJobs()) {
                JPanel row = new JPanel(new BorderLayout(12, 0));
                row.setOpaque(false);
                Theme.pad(row, 4, 0, 4, 0);
                String title = j.title();
                JLabel l = Theme.label("<html><b>" + esc(j.label) + "</b>" + (title.isBlank() ? "" : " · " + esc(title))
                        + "&nbsp;&nbsp;<span style='color:#9B9BA6'>" + esc(j.stateText()) + "</span></html>", 12.5f, false, Theme.TEXT);
                l.setPreferredSize(new Dimension(300, 24));
                row.add(l, BorderLayout.CENTER);
                if (!j.done) {
                    JButton cancel = Theme.button("Cancel", null, false);
                    cancel.addActionListener(ev -> { j.cancel(); m.setVisible(false); repaint(); });
                    row.add(cancel, BorderLayout.EAST);
                }
                list.add(row);
            }
            m.add(list);
            m.show(this, e.getX(), e.getY());
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.BG);
            g.fillRect(0, 0, w, h);
            // logo
            if (Theme.mark != null) g.drawImage(Theme.mark, 22, 24, 30, 30, null);
            g.setFont(Theme.chakra.deriveFont(22f));
            g.setColor(Theme.TEXT);
            g.drawString("EVADE", 60, 46);
            int ew = g.getFontMetrics().stringWidth("EVADE ");
            g.setColor(Theme.RED);
            g.drawString("CLIPS", 60 + ew, 46);

            hits.clear();
            int y = 88;
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                Rectangle r = new Rectangle(12, y, w - 24, 42);
                hits.add(r);
                boolean active = page.equals(it.id), en = enabled(i);
                if (active || hover == i) {
                    g.setColor(active ? Theme.CARD_HI : Theme.CARD);
                    g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 12, 12));
                }
                if (active) { g.setColor(Theme.RED); g.fill(new RoundRectangle2D.Double(r.x, r.y + 10, 3, r.height - 20, 3, 3)); }
                if (hasFocus() && focused == i) {
                    g.setColor(new Color(0x8A8A96));
                    g.setStroke(new BasicStroke(1.5f));
                    g.draw(new RoundRectangle2D.Double(r.x + 0.75, r.y + 0.75, r.width - 1.5, r.height - 1.5, 12, 12));
                }
                Color c = !en ? Theme.FAINT : active ? Theme.TEXT : Theme.DIM;
                Theme.icon(it.glyph, 18, active ? Theme.RED : c).paintIcon(this, g, r.x + 16, r.y + 12);
                g.setFont((active ? Theme.interBold : Theme.inter).deriveFont(14f));
                g.setColor(c);
                g.drawString(it.label, r.x + 46, r.y + 27);
                if (it.id.equals("library")) {
                    String n = String.valueOf(app.library.all().size());
                    g.setFont(Theme.interBold.deriveFont(11f));
                    int nw = g.getFontMetrics().stringWidth(n) + 12;
                    g.setColor(Theme.CARD_HI);
                    g.fill(new RoundRectangle2D.Double(r.x + r.width - nw - 10, r.y + 12, nw, 18, 9, 9));
                    g.setColor(Theme.DIM);
                    g.drawString(n, r.x + r.width - nw - 4, r.y + 25);
                }
                y += 48;
            }

            // export jobs: as many as fit between the pages and the status pill, then "+N more"
            cancelHits.clear();
            moreHit = new Rectangle();
            List<App.ExportJob> jobs = orderedJobs();
            int navBottom = 88 + items.size() * 48;
            int maxRows = Math.max(0, Math.min(3, (h - 96 - navBottom - 8) / 46));
            int shownJobs = jobs.size() > maxRows ? Math.max(0, maxRows - 1) : jobs.size();
            int by = h - 96;
            if (jobs.size() > shownJobs && maxRows > 0) {
                by -= 46;
                moreHit = new Rectangle(12, by, w - 24, 40);
                g.setColor(Theme.CARD);
                g.fill(new RoundRectangle2D.Double(12, by, w - 24, 40, 10, 10));
                g.setFont(Theme.interBold.deriveFont(12f));
                g.setColor(Theme.DIM);
                int more = jobs.size() - shownJobs;
                g.drawString("+" + more + " more job" + (more == 1 ? "" : "s"), 24, by + 25);
            }
            for (int k = shownJobs - 1; k >= 0; k--) {
                App.ExportJob j = jobs.get(k);
                by -= 46;
                g.setColor(Theme.CARD);
                g.fill(new RoundRectangle2D.Double(12, by, w - 24, 40, 10, 10));
                boolean canCancel = !j.done;
                int textW = w - 48 - (canCancel ? 22 : 0);
                String state = j.stateText();
                g.setFont(Theme.interBold.deriveFont(11.5f));
                int sw = g.getFontMetrics().stringWidth(state);
                g.setColor(j.done && j.result == null && !"Done".equals(j.state) ? Theme.GOLD : Theme.DIM);
                g.drawString(state, 24 + textW - sw, by + 17);
                g.setFont(Theme.inter.deriveFont(11.5f));
                g.setColor(Theme.TEXT);
                String title = j.title();
                g.drawString(LibraryPanel.ellipsize(g, j.label + (title.isBlank() ? "" : " · " + title), textW - sw - 8), 24, by + 17);
                g.setColor(Theme.CARD_HI);
                g.fill(new RoundRectangle2D.Double(24, by + 26, w - 48, 5, 5, 5));
                if (!"Queued".equals(j.state) || j.done) {
                    g.setColor(j.done && j.result == null ? Theme.GOLD : j.done ? Theme.OK : Theme.RED);
                    g.fill(new RoundRectangle2D.Double(24, by + 26, (w - 48) * (j.done ? 1 : j.progress), 5, 5, 5));
                }
                if (canCancel) {
                    Rectangle x = new Rectangle(w - 40, by + 6, 16, 16);
                    cancelHits.add(new Object[]{x, j});
                    g.setColor(Theme.DIM);
                    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(x.x + 4, x.y + 4, x.x + 12, x.y + 12);
                    g.drawLine(x.x + 12, x.y + 4, x.x + 4, x.y + 12);
                }
            }

            // status pill
            CaptureEngine.Status st = app.status;
            statusHit = new Rectangle(12, h - 84, w - 24, 68);
            g.setColor(Theme.CARD);
            g.fill(new RoundRectangle2D.Double(statusHit.x, statusHit.y, statusHit.width, statusHit.height, 14, 14));
            boolean live = st.state() == CaptureEngine.State.LIVE, err = st.state() == CaptureEngine.State.ERROR;
            Color dot = st.recording() ? Theme.RED : live ? Theme.OK : err ? Theme.GOLD : Theme.FAINT;
            float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 300.0));
            g.setColor(st.recording() ? new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), (int) (255 * pulse)) : dot);
            g.fillOval(28, h - 63, 10, 10);
            g.setFont(Theme.interBold.deriveFont(13f));
            g.setColor(Theme.TEXT);
            g.drawString(st.recording() ? "REC " + Theme.time(st.recordingSeconds()) : live ? "Replay on" : st.state() == CaptureEngine.State.STARTING ? "Starting…"
                    : err ? "Capture error" : "Replay off", 46, h - 54);
            g.setFont(Theme.inter.deriveFont(11.5f));
            g.setColor(Theme.DIM);
            String sub = err ? "Click for details" : app.currentGame != null ? app.currentGame : live ? app.settings.hotkeySaveClip + " saves " + RecordPanel.label(app.settings.replaySeconds)
                    : app.settings.autoReplayForGames ? "Waiting for a game" : "Click to set up capture";
            g.drawString(LibraryPanel.ellipsize(g, sub, w - 56), 28, h - 34);
            g.dispose();
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }
}
