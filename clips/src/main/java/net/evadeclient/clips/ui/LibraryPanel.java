package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Library;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The clip library: a searchable, filterable grid with hover-scrub previews. */
public final class LibraryPanel extends JPanel {
    private final App app;
    private final JTextField search = new JTextField();
    private final JComboBox<String> game = new JComboBox<>();
    private final JComboBox<String> sort = new JComboBox<>(new String[]{"Newest", "Oldest", "Longest", "Largest", "Name"});
    private final Map<String, JToggleButton> filters = new java.util.LinkedHashMap<>();
    private String filter = "all";
    private final Grid grid = new Grid();
    private final JLabel count = Theme.label("", 12f, false, Theme.DIM);
    private final JLabel storage = Theme.label("", 12f, false, Theme.DIM);
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Image> thumbCache = new HashMap<>();
    private final Map<String, BufferedImage> stripCache = new HashMap<>();
    private boolean updatingGames;
    private List<Clip> shown = new ArrayList<>();

    public LibraryPanel(App app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Theme.PANEL);

        // Header
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        Theme.pad(header, 22, 26, 10, 26);

        JPanel top = row();
        JLabel title = Theme.label("Library", 26f, true, Theme.TEXT);
        top.add(title);
        top.add(Box.createHorizontalStrut(14));
        top.add(count);
        top.add(Box.createHorizontalGlue());
        top.add(storage);
        top.add(Box.createHorizontalStrut(14));
        JButton importBtn = Theme.button("Import", Theme.icon(Theme.Glyph.IMPORT, 16, Theme.TEXT), false);
        importBtn.addActionListener(e -> importDialog());
        top.add(importBtn);
        top.add(Box.createHorizontalStrut(8));
        JButton folderBtn = Theme.button("Folder", Theme.icon(Theme.Glyph.FOLDER, 16, Theme.TEXT), false);
        folderBtn.addActionListener(e -> openFolder(app.library.root()));
        top.add(folderBtn);
        header.add(top);
        header.add(Box.createVerticalStrut(16));

        JPanel bar = row();
        search.putClientProperty("JTextField.placeholderText", "Search titles, games, tags…");
        search.putClientProperty("JTextField.leadingIcon", Theme.icon(Theme.Glyph.SEARCH, 16, Theme.FAINT));
        search.putClientProperty("JTextField.showClearButton", true);
        search.setMaximumSize(new Dimension(320, 34));
        search.setPreferredSize(new Dimension(320, 34));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        bar.add(search);
        bar.add(Box.createHorizontalStrut(12));
        for (String[] f : new String[][]{{"all", "All"}, {"favorites", "★ Favorites"}, {"replays", "Replays"}, {"recordings", "Recordings"}, {"exports", "Exports"}, {"imports", "Imported"}}) {
            JToggleButton b = new JToggleButton(f[1], f[0].equals("all"));
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setFont(Theme.interBold.deriveFont(12f));
            b.addActionListener(e -> {
                filter = f[0];
                filters.values().forEach(x -> x.setSelected(x == b));
                refresh();
            });
            filters.put(f[0], b);
            bar.add(b);
            bar.add(Box.createHorizontalStrut(6));
        }
        bar.add(Box.createHorizontalGlue());
        game.setMaximumSize(new Dimension(170, 34));
        game.addActionListener(e -> { if (!updatingGames) refresh(); });
        bar.add(game);
        bar.add(Box.createHorizontalStrut(8));
        sort.setMaximumSize(new Dimension(120, 34));
        sort.addActionListener(e -> refresh());
        bar.add(sort);
        header.add(bar);
        add(header, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().setBackground(Theme.PANEL);
        add(scroll, BorderLayout.CENTER);

        // Drop files anywhere to import; drag clips out (to Discord, a browser, Explorer...).
        TransferHandler th = new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> paths = new ArrayList<>();
                    for (File f : files) paths.add(f.toPath());
                    new Thread(() -> {
                        List<Clip> added = app.library.importFiles(paths, false);
                        app.notify("Imported " + added.size() + (added.size() == 1 ? " clip" : " clips"), null, false);
                    }, "import").start();
                    return true;
                } catch (Exception e) { return false; }
            }
            @Override public int getSourceActions(JComponent c) { return COPY; }
            @Override protected Transferable createTransferable(JComponent c) { return files(selectedClips()); }
        };
        setTransferHandler(th);
        grid.setTransferHandler(th);

        app.library.onChange(() -> SwingUtilities.invokeLater(this::refresh));
        bindKeys();
        refresh();
    }

    private static JPanel row() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private void bindKeys() {
        var im = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        var am = getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        am.put("delete", action(() -> { if (!search.hasFocus()) deleteSelected(); }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        am.put("open", action(() -> { List<Clip> s = selectedClips(); if (!s.isEmpty()) app.openEditor.accept(s.get(0)); }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename");
        am.put("rename", action(() -> { List<Clip> s = selectedClips(); if (s.size() == 1) rename(s.get(0)); }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "find");
        am.put("find", action(search::requestFocusInWindow));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "all");
        am.put("all", action(() -> { if (!search.hasFocus()) { shown.forEach(c -> selected.add(c.id)); grid.repaint(); } }));
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");
        am.put("copy", action(() -> { if (!search.hasFocus()) copyFiles(selectedClips()); }));
    }

    private static javax.swing.AbstractAction action(Runnable r) {
        return new javax.swing.AbstractAction() { @Override public void actionPerformed(ActionEvent e) { r.run(); } };
    }

    // ------------------------------------------------------------------ data

    public void refresh() {
        List<String> games = app.library.games();
        updatingGames = true;
        Object cur = game.getSelectedItem();
        game.removeAllItems();
        game.addItem("All games");
        for (String g : games) game.addItem(g);
        game.setSelectedItem(cur != null && (games.contains(cur) || "All games".equals(cur)) ? cur : "All games");
        updatingGames = false;

        String g = game.getSelectedItem() == null || "All games".equals(game.getSelectedItem()) ? null : (String) game.getSelectedItem();
        shown = app.library.query(search.getText(), filter, g, ((String) sort.getSelectedItem()).toLowerCase());
        selected.retainAll(shown.stream().map(c -> c.id).toList());
        int total = app.library.all().size();
        count.setText(shown.size() == total ? total + (total == 1 ? " clip" : " clips") : shown.size() + " of " + total);
        long bytes = app.library.totalBytes();
        storage.setText(Theme.size(bytes) + (app.settings.storageLimitGb > 0 ? " of " + (int) app.settings.storageLimitGb + " GB" : ""));
        grid.rebuild();
    }

    private List<Clip> selectedClips() {
        List<Clip> out = new ArrayList<>();
        for (Clip c : shown) if (selected.contains(c.id)) out.add(c);
        return out;
    }

    private Image thumb(Clip c) {
        return thumbCache.computeIfAbsent(c.id + "@" + fileStamp(app.library.thumb(c)), k -> read(app.library.thumb(c)));
    }

    private BufferedImage strip(Clip c) {
        return stripCache.computeIfAbsent(c.id + "@" + fileStamp(app.library.strip(c)), k -> read(app.library.strip(c)));
    }

    private static long fileStamp(Path p) {
        try { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0; } catch (Exception e) { return 0; }
    }

    private static BufferedImage read(Path p) {
        try { return Files.exists(p) ? ImageIO.read(p.toFile()) : null; } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ actions

    private void importDialog() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Import videos");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Videos", Library.VIDEO_EXT.toArray(String[]::new)));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        List<Path> paths = new ArrayList<>();
        for (File f : fc.getSelectedFiles()) paths.add(f.toPath());
        new Thread(() -> {
            List<Clip> added = app.library.importFiles(paths, false);
            app.notify("Imported " + added.size() + (added.size() == 1 ? " clip" : " clips"), null, false);
        }, "import").start();
    }

    private void rename(Clip c) {
        String t = (String) JOptionPane.showInputDialog(this, "Title", "Rename clip", JOptionPane.PLAIN_MESSAGE, null, null, c.title);
        if (t != null) app.library.rename(c, t);
    }

    private void setGame(List<Clip> list) {
        String t = (String) JOptionPane.showInputDialog(this, "Game", "Set game", JOptionPane.PLAIN_MESSAGE, null, null,
                list.get(0).game == null ? "" : list.get(0).game);
        if (t == null) return;
        for (Clip c : list) c.game = t.trim();
        app.library.update(list.get(0));
    }

    private void editTags(List<Clip> list) {
        String current = String.join(", ", list.get(0).tags);
        String t = (String) JOptionPane.showInputDialog(this, "Tags, separated by commas (e.g. clutch, 1v3, montage)",
                "Tags", JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (t == null) return;
        List<String> tags = new ArrayList<>();
        for (String s : t.split(",")) if (!s.isBlank() && !tags.contains(s.trim())) tags.add(s.trim());
        for (Clip c : list) c.tags = new ArrayList<>(tags);
        app.library.update(list.get(0));
    }

    private void deleteSelected() {
        List<Clip> list = selectedClips();
        if (list.isEmpty()) return;
        long favs = list.stream().filter(c -> c.favorite).count();
        String msg = list.size() == 1 ? "Delete \"" + list.get(0).title + "\"?" : "Delete " + list.size() + " clips?";
        if (favs > 0) msg += "\n" + favs + " of them " + (favs == 1 ? "is a favourite." : "are favourites.");
        msg += "\nThey go to the recycle bin where your system has one.";
        if (JOptionPane.showConfirmDialog(this, msg, "Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        app.library.delete(list);
        selected.clear();
    }

    private void toggleFavorite(List<Clip> list) {
        boolean to = !list.stream().allMatch(c -> c.favorite);
        for (Clip c : list) c.favorite = to;
        app.library.update(list.get(0));
    }

    static Transferable files(List<Clip> clips, Path root) {
        List<File> files = new ArrayList<>();
        for (Clip c : clips) files.add(c.path(root).toFile());
        return new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor f) { return f.equals(DataFlavor.javaFileListFlavor); }
            @Override public Object getTransferData(DataFlavor f) { return files; }
        };
    }

    private Transferable files(List<Clip> clips) { return files(clips, app.library.root()); }

    /** Copies the files so they can be pasted straight into Discord, a browser upload or Explorer. */
    private void copyFiles(List<Clip> list) {
        if (list.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(files(list), null);
        app.notify(list.size() == 1 ? "Copied — paste it anywhere" : "Copied " + list.size() + " clips", null, false);
    }

    static void openFolder(Path p) {
        try {
            Files.createDirectories(p);
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, p.toString(), "Folder", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static void reveal(Path file) {
        try {
            if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) { Desktop.getDesktop().browseFileDirectory(file.toFile()); return; }
            if (net.evadeclient.clips.core.Ffmpeg.WINDOWS) { new ProcessBuilder("explorer.exe", "/select,", file.toString()).start(); return; }
        } catch (Exception ignored) {}
        openFolder(file.getParent());
    }

    private void showMenu(MouseEvent e, Clip c) {
        if (!selected.contains(c.id)) { selected.clear(); selected.add(c.id); grid.repaint(); }
        List<Clip> list = selectedClips();
        JPopupMenu m = new JPopupMenu();
        if (list.size() == 1) {
            m.add(item("Open in editor", Theme.Glyph.EDIT, () -> app.openEditor.accept(c)));
            m.add(item("Play in system player", Theme.Glyph.PLAY, () -> { try { Desktop.getDesktop().open(c.path(app.library.root()).toFile()); } catch (Exception ignored) {} }));
            m.addSeparator();
            m.add(item("Rename…", null, () -> rename(c)));
        }
        m.add(item(list.stream().allMatch(x -> x.favorite) ? "Remove from favorites" : "Add to favorites", Theme.Glyph.STAR, () -> toggleFavorite(list)));
        m.add(item("Tags…", null, () -> editTags(list)));
        if (list.size() >= 2) {
            m.addSeparator();
            m.add(item("Make montage from " + list.size() + " clips", Theme.Glyph.FILM, () -> {
                List<Clip> ordered = new ArrayList<>(list);
                ordered.sort(java.util.Comparator.comparingLong(x -> x.created));
                String t = (String) JOptionPane.showInputDialog(this, "Montage title (clips are joined oldest first, with their edits)",
                        "Make montage", JOptionPane.PLAIN_MESSAGE, null, null, "Montage");
                if (t != null) app.montage(ordered, t.isBlank() ? "Montage" : t, null);
            }));
        }
        if (list.size() == 1) {
            m.add(item("Find highlights", Theme.Glyph.BOOKMARK, () -> app.findHighlights(c, false)));
        }
        m.add(item("Set game…", null, () -> setGame(list)));
        m.addSeparator();
        m.add(item("Copy file" + (list.size() > 1 ? "s" : ""), Theme.Glyph.COPY, () -> copyFiles(list)));
        long total = list.stream().mapToLong(x -> x.size).sum();
        m.add(item("Shrink to save space (" + Theme.size(total) + ")", Theme.Glyph.IMPORT, () -> app.shrink(list)));
        JMenu export = new JMenu("Quick export");
        export.setIcon(Theme.icon(Theme.Glyph.EXPORT, 16, Theme.DIM));
        for (Exporter.Preset p : Exporter.Preset.values()) {
            export.add(item(p.label, null, () -> {
                for (Clip x : list) {
                    Exporter.Options o = new Exporter.Options();
                    o.preset = p;
                    o.gpu = true;
                    EditSpec spec = x.edit != null ? x.edit : EditSpec.whole(x.duration);
                    app.export(x, spec, o, null);
                }
            }));
        }
        m.add(export);
        if (list.size() == 1) m.add(item("Show in folder", Theme.Glyph.FOLDER, () -> reveal(c.path(app.library.root()))));
        m.addSeparator();
        m.add(item("Delete" + (list.size() > 1 ? " " + list.size() + " clips" : ""), Theme.Glyph.TRASH, this::deleteSelected));
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    private static JMenuItem item(String text, Theme.Glyph g, Runnable r) {
        JMenuItem i = new JMenuItem(text, g == null ? null : Theme.icon(g, 16, Theme.DIM));
        i.addActionListener(e -> r.run());
        return i;
    }

    // ------------------------------------------------------------------ grid

    private static final int CARD_W = 300, CARD_H = 236, GAP = 18;

    private final class Grid extends JPanel implements Scrollable {
        Grid() {
            setLayout(new GridFlow());
            setBackground(Theme.PANEL);
            Theme.pad(this, 4, 26, 26, 26);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (getComponentAt(e.getPoint()) == Grid.this) { selected.clear(); repaint(); }
                    requestFocusInWindow();
                }
            });
            setFocusable(true);
        }

        void rebuild() {
            removeAll();
            if (shown.isEmpty()) add(new Empty());
            else for (Clip c : shown) add(new Card(c));
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    /** Wrapping grid whose cards stretch to fill the row. */
    private static final class GridFlow implements LayoutManager {
        public void addLayoutComponent(String n, Component c) {}
        public void removeLayoutComponent(Component c) {}
        public Dimension minimumLayoutSize(Container p) { return new Dimension(CARD_W, CARD_H); }

        private int cols(Container p) {
            Insets in = p.getInsets();
            int w = Math.max(CARD_W, p.getWidth() - in.left - in.right);
            return Math.max(1, (w + GAP) / (CARD_W + GAP));
        }

        public Dimension preferredLayoutSize(Container p) {
            Insets in = p.getInsets();
            int n = p.getComponentCount();
            if (n == 1 && p.getComponent(0) instanceof Empty) return new Dimension(CARD_W, 420);
            int cols = cols(p);
            int rows = (n + cols - 1) / cols;
            int cw = cardW(p, cols);
            int ch = cardH(cw);
            return new Dimension(in.left + in.right + CARD_W, in.top + in.bottom + rows * ch + Math.max(0, rows - 1) * GAP);
        }

        private int cardW(Container p, int cols) {
            Insets in = p.getInsets();
            int w = p.getWidth() - in.left - in.right;
            return Math.min(420, (w - (cols - 1) * GAP) / cols);
        }

        private int cardH(int cw) { return (int) (cw * 9 / 16.0) + 68; }

        public void layoutContainer(Container p) {
            Insets in = p.getInsets();
            if (p.getComponentCount() == 1 && p.getComponent(0) instanceof Empty) {
                p.getComponent(0).setBounds(in.left, in.top, p.getWidth() - in.left - in.right, 420);
                return;
            }
            int cols = cols(p);
            int cw = cardW(p, cols), ch = cardH(cw);
            for (int i = 0; i < p.getComponentCount(); i++) {
                int r = i / cols, c = i % cols;
                p.getComponent(i).setBounds(in.left + c * (cw + GAP), in.top + r * (ch + GAP), cw, ch);
            }
        }
    }

    private final class Empty extends JComponent {
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            Theme.hq(g);
            int cx = getWidth() / 2;
            boolean filtered = !search.getText().isBlank() || !filter.equals("all") || game.getSelectedIndex() > 0;
            Theme.icon(Theme.Glyph.FILM, 56, Theme.FAINT).paintIcon(this, g, cx - 28, 90);
            g.setFont(Theme.interBold.deriveFont(20f));
            g.setColor(Theme.TEXT);
            String a = filtered ? "No clips match" : "No clips yet";
            g.drawString(a, cx - g.getFontMetrics().stringWidth(a) / 2, 190);
            g.setFont(Theme.inter.deriveFont(14f));
            g.setColor(Theme.DIM);
            String b = filtered ? "Try a different search or filter." : "Press " + app.settings.hotkeySaveClip + " in game to save the last " + app.settings.replaySeconds + " seconds,";
            g.drawString(b, cx - g.getFontMetrics().stringWidth(b) / 2, 220);
            if (!filtered) {
                String c = "press " + app.settings.hotkeyRecord + " to record, or drop video files here.";
                g.drawString(c, cx - g.getFontMetrics().stringWidth(c) / 2, 242);
            }
        }
    }

    /** One clip: thumbnail (hover to scrub through it), duration, favourite star, title and details. */
    private final class Card extends JComponent {
        final Clip clip;
        boolean hover;
        double scrub = -1;
        float hoverAnim;
        Timer anim;

        Card(Clip clip) {
            this.clip = clip;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("");
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; animate(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; scrub = -1; animate(); }
                @Override public void mouseMoved(MouseEvent e) {
                    int th = thumbH();
                    scrub = e.getY() < th ? Math.max(0, Math.min(0.999, e.getX() / (double) getWidth())) : -1;
                    repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    grid.requestFocusInWindow();
                    if (e.isPopupTrigger()) { showMenu(e, clip); return; }
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (starHit(e.getX(), e.getY())) {
                            clip.favorite = !clip.favorite;
                            app.library.update(clip);
                            return;
                        }
                        if (e.isControlDown() || e.isMetaDown()) {
                            if (!selected.remove(clip.id)) selected.add(clip.id);
                        } else if (e.isShiftDown() && !selected.isEmpty()) {
                            int a = indexOf(selected.iterator().next()), b = shown.indexOf(clip);
                            for (int i = Math.min(a, b); i <= Math.max(a, b); i++) if (i >= 0) selected.add(shown.get(i).id);
                        } else if (!selected.contains(clip.id) || e.getClickCount() == 1) {
                            selected.clear();
                            selected.add(clip.id);
                        }
                        grid.repaint();
                        if (e.getClickCount() == 2) app.openEditor.accept(clip);
                    }
                }
                @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showMenu(e, clip); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (!selected.contains(clip.id)) { selected.clear(); selected.add(clip.id); }
                    getTransferHandler().exportAsDrag(LibraryPanel.this, e, TransferHandler.COPY);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            setTransferHandler(LibraryPanel.this.getTransferHandler());
        }

        private int indexOf(String id) {
            for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(id)) return i;
            return -1;
        }

        void animate() {
            if (anim != null) anim.stop();
            anim = new Timer(15, e -> {
                hoverAnim += (hover ? 1 - hoverAnim : -hoverAnim) * 0.25f;
                if (Math.abs(hoverAnim - (hover ? 1 : 0)) < 0.01) { hoverAnim = hover ? 1 : 0; ((Timer) e.getSource()).stop(); }
                repaint();
            });
            anim.start();
        }

        int thumbH() { return (int) (getWidth() * 9 / 16.0); }

        boolean starHit(int x, int y) { return x > getWidth() - 40 && y < 40; }

        @Override public String getToolTipText(MouseEvent e) {
            return starHit(e.getX(), e.getY()) ? (clip.favorite ? "Unfavorite" : "Favorite") : null;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int w = getWidth(), th = thumbH();
            boolean sel = selected.contains(clip.id);
            RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, w, getHeight(), 14, 14);
            g.setColor(hoverAnim > 0 ? blend(Theme.CARD, Theme.CARD_HI, hoverAnim) : Theme.CARD);
            g.fill(shape);

            // thumbnail / scrub frame
            g.setClip(new RoundRectangle2D.Double(0, 0, w, th + 14, 14, 14));
            g.clipRect(0, 0, w, th);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, th);
            BufferedImage strip = scrub >= 0 ? strip(clip) : null;
            if (strip != null) {
                int n = Library.STRIP_FRAMES;
                int fw = strip.getWidth() / n;
                int i = (int) (scrub * n);
                g.drawImage(strip, 0, 0, w, th, i * fw, 0, (i + 1) * fw, strip.getHeight(), null);
            } else {
                Image t = thumb(clip);
                if (t != null) {
                    double z = 1 + hoverAnim * 0.03;
                    int dw = (int) (w * z), dh = (int) (th * z);
                    g.drawImage(t, (w - dw) / 2, (th - dh) / 2, dw, dh, null);
                } else {
                    Theme.icon(Theme.Glyph.FILM, 36, Theme.FAINT).paintIcon(this, g, w / 2 - 18, th / 2 - 18);
                }
            }
            g.setPaint(new GradientPaint(0, th - 50, new Color(0, 0, 0, 0), 0, th, new Color(0, 0, 0, 170)));
            g.fillRect(0, th - 50, w, 50);
            if (scrub >= 0) {
                g.setColor(Theme.RED);
                g.fillRect(0, th - 3, (int) (w * scrub), 3);
            }
            g.setClip(null);

            // badges
            g.setFont(Theme.interBold.deriveFont(11.5f));
            String dur = Theme.time(scrub >= 0 ? scrub * clip.duration : clip.duration);
            badge(g, dur, w - 10 - g.getFontMetrics().stringWidth(dur) - 12, th - 30, new Color(0, 0, 0, 190), Theme.TEXT);
            String kind = switch (clip.kind) { case REPLAY -> "REPLAY"; case RECORDING -> "REC"; case EXPORT -> "EXPORT"; default -> null; };
            int bx = 10;
            if (kind != null) bx = badge(g, kind, bx, 10, clip.kind == Clip.Kind.EXPORT ? new Color(0x2A, 0x4E, 0x9A, 220) : new Color(0xE1, 0x1D, 0x2E, 215), Color.WHITE) + 6;
            String res = clip.resolution();
            if (!res.isEmpty() && hoverAnim > 0.05) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, hoverAnim));
                badge(g2, res + (clip.fps > 0 ? " · " + Math.round(clip.fps) + "fps" : ""), bx, 10, new Color(0, 0, 0, 170), Theme.TEXT);
                g2.dispose();
            }
            if (!clip.markers.isEmpty()) {
                g.setColor(Theme.GOLD);
                for (double m : clip.markers) {
                    int mx = (int) (m / Math.max(0.01, clip.duration) * w);
                    g.fillRect(mx - 1, th - 6, 3, 6);
                }
            }
            // favourite star
            if (clip.favorite || hoverAnim > 0.05) {
                Graphics2D g2 = (Graphics2D) g.create();
                if (!clip.favorite) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, hoverAnim));
                g2.setColor(new Color(0, 0, 0, 150));
                g2.fillOval(w - 38, 8, 30, 30);
                Theme.icon(clip.favorite ? Theme.Glyph.STAR_FILLED : Theme.Glyph.STAR, 18, clip.favorite ? Theme.GOLD : Color.WHITE).paintIcon(this, g2, w - 32, 13);
                g2.dispose();
            }

            // text
            g.setFont(Theme.interBold.deriveFont(14f));
            g.setColor(Theme.TEXT);
            g.drawString(ellipsize(g, clip.title == null ? "" : clip.title, w - 28), 14, th + 26);
            g.setFont(Theme.inter.deriveFont(12f));
            g.setColor(Theme.DIM);
            StringBuilder meta = new StringBuilder();
            if (clip.game != null && !clip.game.isBlank()) meta.append(clip.game).append("  ·  ");
            meta.append(Theme.ago(clip.created)).append("  ·  ").append(Theme.size(clip.size));
            g.drawString(ellipsize(g, meta.toString(), w - 28), 14, th + 46);
            if (!clip.tags.isEmpty()) {
                g.setFont(Theme.inter.deriveFont(11f));
                g.setColor(Theme.RED_HI);
                g.drawString(ellipsize(g, "#" + String.join("  #", clip.tags), w - 28), 14, th + 62);
            }

            if (sel) {
                g.setColor(Theme.RED);
                g.setStroke(new java.awt.BasicStroke(2.5f));
                g.draw(new RoundRectangle2D.Double(1.25, 1.25, w - 2.5, getHeight() - 2.5, 14, 14));
            }
            g.dispose();
        }

        private int badge(Graphics2D g, String text, int x, int y, Color bg, Color fg) {
            int tw = g.getFontMetrics().stringWidth(text);
            g.setColor(bg);
            g.fill(new RoundRectangle2D.Double(x, y, tw + 12, 20, 8, 8));
            g.setColor(fg);
            g.drawString(text, x + 6, y + 14);
            return x + tw + 12;
        }
    }

    static String ellipsize(Graphics2D g, String s, int max) {
        if (g.getFontMetrics().stringWidth(s) <= max) return s;
        while (s.length() > 1 && g.getFontMetrics().stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static Color blend(Color a, Color b, float t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
