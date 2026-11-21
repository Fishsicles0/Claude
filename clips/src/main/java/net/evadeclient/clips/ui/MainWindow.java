package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.Clip;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
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

    public MainWindow(App app) {
        super("Evade Clips");
        this.app = app;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        if (Theme.icon != null) setIconImage(Theme.icon);
        setMinimumSize(new Dimension(1100, 720));
        setSize(1380, 860);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);

        library = new LibraryPanel(app);
        record = new RecordPanel(app);
        editor = new EditorPanel(app);
        settings = new SettingsPanel(app);
        pages.add(library, "library");
        pages.add(record, "record");
        pages.add(editor, "editor");
        pages.add(settings, "settings");

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(sidebar, BorderLayout.WEST);
        pages.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));
        root.add(pages, BorderLayout.CENTER);
        setContentPane(root);

        app.openEditor = c -> SwingUtilities.invokeLater(() -> {
            if (c == null) { editor.close(); show(editor.clip() != null && page.equals("editor") ? "library" : "library"); return; }
            editor.open(c);
            show("editor");
        });

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (app.settings.minimizeToTray && tray != null) {
                    setVisible(false);
                    editor.close();
                    if (!shownTrayHint) {
                        tray.displayMessage("Evade Clips is still running", "Instant replay keeps going. Right-click the tray icon to quit.", TrayIcon.MessageType.INFO);
                        shownTrayHint = true;
                    }
                } else quit();
            }
        });
        installTray();
        app.engine.onStatus(s -> SwingUtilities.invokeLater(sidebar::repaint));
        app.onJobs(() -> SwingUtilities.invokeLater(sidebar::repaint));
        new Timer(500, e -> sidebar.repaint()).start();
    }

    private boolean shownTrayHint;

    public void show(String name) {
        if (page.equals("editor") && !name.equals("editor")) editor.close();
        page = name;
        cards.show(pages, name);
        sidebar.repaint();
    }

    public String page() { return page; }

    void quit() {
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
        open.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); toFront(); }));
        MenuItem save = new MenuItem("Save replay");
        save.addActionListener(e -> app.saveClip(0, null, null, null));
        MenuItem rec = new MenuItem("Start / stop recording");
        rec.addActionListener(e -> app.toggleRecording());
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> quit());
        m.add(open);
        m.addSeparator();
        m.add(save);
        m.add(rec);
        m.addSeparator();
        m.add(quit);
        tray = new TrayIcon(Theme.icon, "Evade Clips", m);
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); toFront(); }));
        try { SystemTray.getSystemTray().add(tray); } catch (AWTException e) { tray = null; }
    }

    /** Left navigation with the logo, pages and a live status pill. */
    private final class Sidebar extends JComponent {
        private record Item(String id, String label, Theme.Glyph glyph) {}
        private final List<Item> items = List.of(
                new Item("library", "Library", Theme.Glyph.GRID),
                new Item("record", "Record", Theme.Glyph.CAMERA),
                new Item("editor", "Editor", Theme.Glyph.SCISSORS),
                new Item("settings", "Settings", Theme.Glyph.GEAR));
        private final List<Rectangle> hits = new ArrayList<>();
        private int hover = -1;
        private Rectangle statusHit = new Rectangle();

        Sidebar() {
            setPreferredSize(new Dimension(220, 100));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int h = -1;
                    for (int i = 0; i < hits.size(); i++) if (hits.get(i).contains(e.getPoint()) && enabled(i)) h = i;
                    if (h != hover) { hover = h; repaint(); }
                    setCursor(h >= 0 || statusHit.contains(e.getPoint()) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    for (int i = 0; i < hits.size(); i++) if (hits.get(i).contains(e.getPoint()) && enabled(i)) MainWindow.this.show(items.get(i).id);
                    if (statusHit.contains(e.getPoint())) MainWindow.this.show("record");
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private boolean enabled(int i) { return !items.get(i).id.equals("editor") || editor.clip() != null; }

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

            // export jobs
            int by = h - 96;
            for (App.ExportJob j : app.jobs()) {
                by -= 46;
                g.setColor(Theme.CARD);
                g.fill(new RoundRectangle2D.Double(12, by, w - 24, 40, 10, 10));
                g.setFont(Theme.inter.deriveFont(11.5f));
                g.setColor(Theme.DIM);
                g.drawString(LibraryPanel.ellipsize(g, j.label + " · " + (j.done ? j.state : Math.round(j.progress * 100) + "%"), w - 48), 24, by + 17);
                g.setColor(Theme.CARD_HI);
                g.fill(new RoundRectangle2D.Double(24, by + 26, w - 48, 5, 5, 5));
                g.setColor(j.done && j.result == null ? Theme.GOLD : j.done ? Theme.OK : Theme.RED);
                g.fill(new RoundRectangle2D.Double(24, by + 26, (w - 48) * (j.done ? 1 : j.progress), 5, 5, 5));
            }

            // status pill
            CaptureEngine.Status st = app.status;
            statusHit = new Rectangle(12, h - 84, w - 24, 68);
            g.setColor(Theme.CARD);
            g.fill(new RoundRectangle2D.Double(statusHit.x, statusHit.y, statusHit.width, statusHit.height, 14, 14));
            boolean live = st.state() == CaptureEngine.State.LIVE;
            Color dot = st.recording() ? Theme.RED : live ? Theme.OK : st.state() == CaptureEngine.State.ERROR ? Theme.GOLD : Theme.FAINT;
            float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 300.0));
            g.setColor(st.recording() ? new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), (int) (255 * pulse)) : dot);
            g.fillOval(28, h - 63, 10, 10);
            g.setFont(Theme.interBold.deriveFont(13f));
            g.setColor(Theme.TEXT);
            g.drawString(st.recording() ? "REC " + Theme.time(st.recordingSeconds()) : live ? "Replay on" : st.state() == CaptureEngine.State.STARTING ? "Starting…" : "Replay off", 46, h - 54);
            g.setFont(Theme.inter.deriveFont(11.5f));
            g.setColor(Theme.DIM);
            g.drawString(live ? app.settings.hotkeySaveClip + " saves " + RecordPanel.label(app.settings.replaySeconds) : "Click to set up capture", 28, h - 34);
            g.dispose();
        }
    }
}
