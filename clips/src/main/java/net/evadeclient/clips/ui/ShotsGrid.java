package net.evadeclient.clips.ui;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The library's Screenshots view: the game's screenshots (F2) from every folder {@link App#screenshotSources} names
 * (the launcher passes each instance's screenshots/ folder), newest first, as a grid of thumbnails. Double-click
 * opens one; right-click copies it, shows it in its folder or deletes it.
 */
public final class ShotsGrid extends JPanel implements Scrollable {
    /** A folder of screenshots and the name shown with its pictures (an instance's name). */
    public record Source(String name, Path folder) {}

    /** A screenshot on disk. */
    public record Shot(Path file, String source, long time, long size) {}

    /** Where screenshots come from when nobody says otherwise: the normal Minecraft folder's screenshots/. */
    public static Supplier<List<Source>> defaultSources() {
        return () -> {
            String appdata = System.getenv("APPDATA");
            String home = System.getProperty("user.home");
            Path mc = appdata != null ? Path.of(appdata, ".minecraft")
                    : System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? Path.of(home, "Library", "Application Support", "minecraft")
                    : Path.of(home, ".minecraft");
            return List.of(new Source("Minecraft", mc.resolve("screenshots")));
        };
    }

    private static final int GAP = 16, MIN_W = 220, INFO = 46;
    private final App app;
    private List<Shot> shots = new ArrayList<>();
    private final Set<Path> selected = new LinkedHashSet<>();
    private final Map<Path, Image> thumbs = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, Image> e) { return size() > 300; }
    };
    private final Set<Path> loading = new LinkedHashSet<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clips-shot-thumbs");
        t.setDaemon(true);
        return t;
    });
    private int hover = -1;

    public ShotsGrid(App app) {
        this.app = app;
        setBackground(Theme.PANEL);
        setFocusable(true);
        getAccessibleContext().setAccessibleName("Screenshots");
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int i = at(e.getX(), e.getY());
                if (i != hover) { hover = i; repaint(); }
            }
            @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int i = at(e.getX(), e.getY());
                if (e.isPopupTrigger()) { menu(e, i); return; }
                if (i < 0) { selected.clear(); repaint(); return; }
                Path f = shots.get(i).file();
                if (e.isControlDown() || e.isMetaDown()) { if (!selected.remove(f)) selected.add(f); }
                else { selected.clear(); selected.add(f); }
                if (e.getClickCount() == 2) open(f);
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu(e, at(e.getX(), e.getY())); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    /** Lists the screenshots again (search matches the file or instance name; sort: "newest", "oldest", "largest", "name"). */
    public void reload(String search, String sort) {
        String q = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        List<Shot> out = new ArrayList<>();
        List<Source> sources;
        try { sources = app.screenshotSources.get(); } catch (RuntimeException e) { sources = List.of(); }
        for (Source s : sources) {
            if (s.folder() == null || !Files.isDirectory(s.folder())) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(s.folder())) {
                for (Path f : ds) {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) || !Files.isRegularFile(f)) continue;
                    if (!q.isEmpty() && !n.contains(q) && !s.name().toLowerCase(Locale.ROOT).contains(q)) continue;
                    try { out.add(new Shot(f, s.name(), Files.getLastModifiedTime(f).toMillis(), Files.size(f))); } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        String so = sort == null ? "newest" : sort.toLowerCase(Locale.ROOT);
        Comparator<Shot> c = switch (so) {
            case "oldest" -> Comparator.comparingLong(Shot::time);
            case "largest" -> Comparator.comparingLong(Shot::size).reversed();
            case "name" -> Comparator.comparing((Shot s) -> s.file().getFileName().toString().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingLong(Shot::time).reversed();
        };
        out.sort(c);
        shots = out;
        Set<Path> keep = new LinkedHashSet<>();
        for (Shot s : out) keep.add(s.file());
        selected.retainAll(keep);
        revalidate();
        repaint();
    }

    public List<Shot> shots() { return shots; }

    public String countText() { return shots.size() + (shots.size() == 1 ? " screenshot" : " screenshots"); }

    // ------------------------------------------------------------------ layout

    private int cols() { return Math.max(1, (getWidth() - 52 + GAP) / (MIN_W + GAP)); }

    private int cardW() { int c = cols(); return Math.max(MIN_W, (getWidth() - 52 - (c - 1) * GAP) / c); }

    private int cardH() { return cardW() * 9 / 16 + INFO; }

    private Rectangle cell(int i) {
        int c = cols(), cw = cardW(), ch = cardH();
        return new Rectangle(26 + (i % c) * (cw + GAP), 4 + (i / c) * (ch + GAP), cw, ch);
    }

    private int at(int x, int y) {
        for (int i = 0; i < shots.size(); i++) if (cell(i).contains(x, y)) return i;
        return -1;
    }

    @Override public Dimension getPreferredSize() {
        int w = getParent() != null ? getParent().getWidth() : 800;
        if (shots.isEmpty()) return new Dimension(w, 420);
        int c = Math.max(1, (w - 52 + GAP) / (MIN_W + GAP));
        int cw = Math.max(MIN_W, (w - 52 - (c - 1) * GAP) / c);
        int rows = (shots.size() + c - 1) / c;
        return new Dimension(w, 4 + rows * (cw * 9 / 16 + INFO) + Math.max(0, rows - 1) * GAP + 26);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }

    // ------------------------------------------------------------------ painting

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        Theme.hq(g);
        if (shots.isEmpty()) {
            g.setFont(Theme.interBold.deriveFont(16f));
            g.setColor(Theme.TEXT);
            String a = "No screenshots yet";
            g.drawString(a, (getWidth() - g.getFontMetrics().stringWidth(a)) / 2, 180);
            g.setFont(Theme.inter.deriveFont(13f));
            g.setColor(Theme.DIM);
            String b = "Press F2 in game. Screenshots from every instance show up here.";
            g.drawString(b, (getWidth() - g.getFontMetrics().stringWidth(b)) / 2, 206);
            g.dispose();
            return;
        }
        Rectangle vis = g.getClipBounds();
        int th = cardW() * 9 / 16;
        for (int i = 0; i < shots.size(); i++) {
            Rectangle r = cell(i);
            if (vis != null && !vis.intersects(r)) continue;
            Shot s = shots.get(i);
            boolean sel = selected.contains(s.file());
            RoundRectangle2D shape = new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 14, 14);
            g.setColor(i == hover ? Theme.CARD_HI : Theme.CARD);
            g.fill(shape);
            Image t = thumb(s.file());
            Graphics2D q = (Graphics2D) g.create();
            q.clip(new RoundRectangle2D.Double(r.x, r.y, r.width, th + 14, 14, 14));
            q.clipRect(r.x, r.y, r.width, th);
            q.setColor(new Color(0x0C0C10));
            q.fillRect(r.x, r.y, r.width, th);
            if (t != null) {
                double k = Math.max(r.width / (double) t.getWidth(null), th / (double) t.getHeight(null));
                int dw = (int) Math.round(t.getWidth(null) * k), dh = (int) Math.round(t.getHeight(null) * k);
                q.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                q.drawImage(t, r.x + (r.width - dw) / 2, r.y + (th - dh) / 2, dw, dh, null);
            }
            q.dispose();
            g.setFont(Theme.interBold.deriveFont(12.5f));
            g.setColor(Theme.TEXT);
            g.drawString(clip(g, s.source(), r.width - 24), r.x + 12, r.y + th + 20);
            g.setFont(Theme.inter.deriveFont(11.5f));
            g.setColor(Theme.DIM);
            g.drawString(clip(g, Theme.ago(s.time()) + "  ·  " + Theme.size(s.size()), r.width - 24), r.x + 12, r.y + th + 37);
            if (sel) {
                g.setColor(Theme.RED);
                g.setStroke(new java.awt.BasicStroke(2f));
                g.draw(new RoundRectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 14, 14));
            }
        }
        g.dispose();
    }

    private static String clip(Graphics2D g, String s, int w) {
        if (g.getFontMetrics().stringWidth(s) <= w) return s;
        while (s.length() > 1 && g.getFontMetrics().stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** The thumbnail, or null while it's read (in the background, subsampled so big PNGs stay cheap). */
    private Image thumb(Path f) {
        Image t = thumbs.get(f);
        if (t != null || !loading.add(f)) return t;
        loader.submit(() -> {
            BufferedImage img = null;
            try (ImageInputStream in = ImageIO.createImageInputStream(f.toFile())) {
                Iterator<ImageReader> it = in == null ? null : ImageIO.getImageReaders(in);
                if (it != null && it.hasNext()) {
                    ImageReader r = it.next();
                    try {
                        r.setInput(in);
                        javax.imageio.ImageReadParam p = r.getDefaultReadParam();
                        int sub = Math.max(1, r.getWidth(0) / 640);
                        p.setSourceSubsampling(sub, sub, 0, 0);
                        img = r.read(0, p);
                    } finally {
                        r.dispose();
                    }
                }
            } catch (Exception ignored) { }
            BufferedImage done = img;
            SwingUtilities.invokeLater(() -> {
                loading.remove(f);
                if (done != null) thumbs.put(f, done);
                repaint();
            });
        });
        return null;
    }

    // ------------------------------------------------------------------ actions

    private void open(Path f) {
        new Thread(() -> {
            try { Desktop.getDesktop().open(f.toFile()); } catch (Exception e) { LibraryPanel.reveal(f); }
        }, "open-shot").start();
    }

    private List<Path> targets(int i) {
        List<Path> l = new ArrayList<>(selected);
        if (i >= 0 && !selected.contains(shots.get(i).file())) { l.clear(); l.add(shots.get(i).file()); }
        return l;
    }

    private void menu(MouseEvent e, int i) {
        if (i < 0) return;
        if (!selected.contains(shots.get(i).file())) { selected.clear(); selected.add(shots.get(i).file()); repaint(); }
        List<Path> l = targets(i);
        JPopupMenu m = new JPopupMenu();
        m.add(item("Open", () -> open(l.get(0))));
        m.add(item("Copy picture", () -> copyImage(l.get(0))));
        m.add(item("Copy file" + (l.size() > 1 ? "s" : ""), () -> copyFiles(l)));
        m.add(item("Show in folder", () -> LibraryPanel.reveal(l.get(0))));
        m.addSeparator();
        m.add(item("Delete" + (l.size() > 1 ? " " + l.size() + " screenshots" : ""), () -> delete(l)));
        m.show(this, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem it = new JMenuItem(text);
        it.addActionListener(e -> r.run());
        return it;
    }

    private void copyImage(Path f) {
        new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(f.toFile());
                if (img == null) return;
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                    @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
                    @Override public boolean isDataFlavorSupported(DataFlavor fl) { return DataFlavor.imageFlavor.equals(fl); }
                    @Override public Object getTransferData(DataFlavor fl) { return img; }
                }, null);
                app.notify("Screenshot copied", null, false);
            } catch (Exception ignored) { }
        }, "copy-shot").start();
    }

    private void copyFiles(List<Path> l) {
        List<java.io.File> files = new ArrayList<>();
        for (Path p : l) files.add(p.toFile());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor fl) { return DataFlavor.javaFileListFlavor.equals(fl); }
            @Override public Object getTransferData(DataFlavor fl) { return files; }
        }, null);
    }

    private void delete(List<Path> l) {
        int n = 0;
        for (Path p : l) {
            try {
                boolean trashed = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                        && Desktop.getDesktop().moveToTrash(p.toFile());
                if (!trashed) Files.deleteIfExists(p);
                n++;
            } catch (Exception ignored) { }
        }
        selected.clear();
        shots.removeIf(s -> !Files.exists(s.file()));
        revalidate();
        repaint();
        app.notify("Deleted " + n + (n == 1 ? " screenshot" : " screenshots"), null, false);
    }
}
