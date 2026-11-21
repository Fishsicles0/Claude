package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.EditSpec;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * The editor timeline: a filmstrip and waveform of the whole source, the kept segments (drag their edges to
 * trim), cut-out parts hatched, per-segment speed, markers and the playhead. Ctrl+scroll zooms.
 */
public final class Timeline extends JComponent {
    private static final int RULER = 22, STRIP = 58, WAVE = 34, PAD = 12, EDGE = 7;

    private double duration = 1;
    private EditSpec edit;
    private List<Double> markers = List.of();
    private double playhead;
    private BufferedImage strip, wave;
    private int stripFrames = 10;

    // view
    private double zoom = 1, offset; // offset in seconds at left edge

    // interaction
    private int dragSeg = -1;
    private boolean dragStartEdge, scrubbing;
    private double dragOrigin;

    public DoubleConsumer onSeek = t -> {};
    public Runnable beforeEdit = () -> {};
    public Runnable afterEdit = () -> {};

    private static final TexturePaint HATCH;
    static {
        BufferedImage h = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = h.createGraphics();
        g.setColor(new Color(0, 0, 0, 165));
        g.fillRect(0, 0, 8, 8);
        g.setColor(new Color(255, 255, 255, 22));
        g.setStroke(new BasicStroke(2));
        g.drawLine(0, 8, 8, 0);
        g.dispose();
        HATCH = new TexturePaint(h, new Rectangle2D.Double(0, 0, 8, 8));
    }

    public Timeline() {
        setPreferredSize(new Dimension(800, RULER + STRIP + WAVE + PAD * 2 + 6));
        setOpaque(true);
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (edit == null) return;
                if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e)) { menu(e); return; }
                int[] edge = edgeAt(e.getX());
                if (edge != null) {
                    beforeEdit.run();
                    dragSeg = edge[0];
                    dragStartEdge = edge[1] == 0;
                    dragOrigin = dragStartEdge ? edit.segments.get(dragSeg).start : edit.segments.get(dragSeg).end;
                    return;
                }
                scrubbing = true;
                onSeek.accept(clampT(timeAt(e.getX())));
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (edit == null) return;
                double t = clampT(timeAt(e.getX()));
                if (dragSeg >= 0) {
                    EditSpec.Segment s = edit.segments.get(dragSeg);
                    double lo = dragSeg > 0 ? edit.segments.get(dragSeg - 1).end : 0;
                    double hi = dragSeg < edit.segments.size() - 1 ? edit.segments.get(dragSeg + 1).start : duration;
                    if (dragStartEdge) s.start = Math.max(lo, Math.min(t, s.end - 0.1));
                    else s.end = Math.min(hi, Math.max(t, s.start + 0.1));
                    onSeek.accept(dragStartEdge ? s.start : s.end);
                    repaint();
                } else if (scrubbing) {
                    onSeek.accept(t);
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragSeg >= 0) afterEdit.run();
                dragSeg = -1;
                scrubbing = false;
            }
            @Override public void mouseMoved(MouseEvent e) {
                setCursor(edgeAt(e.getX()) != null && e.getY() > RULER ? Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR) : Cursor.getDefaultCursor());
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) {
                    double at = timeAt(e.getX());
                    zoom = Math.max(1, Math.min(60, zoom * (e.getWheelRotation() < 0 ? 1.25 : 0.8)));
                    double vis = duration / zoom;
                    offset = at - (e.getX() - PAD) / (double) trackW() * vis;
                } else {
                    offset += e.getWheelRotation() * duration / zoom * 0.1;
                }
                clampView();
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    public void setClip(double duration, EditSpec edit, List<Double> markers, BufferedImage strip, int stripFrames) {
        this.duration = Math.max(0.01, duration);
        this.edit = edit;
        this.markers = markers == null ? List.of() : markers;
        this.strip = strip;
        this.stripFrames = stripFrames;
        this.wave = null;
        zoom = 1;
        offset = 0;
        repaint();
    }

    public void setEdit(EditSpec e) { edit = e; repaint(); }
    public void setWave(BufferedImage w) { wave = w; repaint(); }
    public void setMarkers(List<Double> m) { markers = m; repaint(); }

    public void setPlayhead(double t) {
        playhead = t;
        // Keep the playhead in view when zoomed.
        double vis = duration / zoom;
        if (zoom > 1 && (t < offset || t > offset + vis)) { offset = t - vis * 0.1; clampView(); }
        repaint();
    }

    public void zoomBy(double k) { zoom = Math.max(1, Math.min(60, zoom * k)); offset = playhead - duration / zoom / 2; clampView(); repaint(); }

    private void clampView() { offset = Math.max(0, Math.min(offset, duration - duration / zoom)); }

    private int trackW() { return Math.max(1, getWidth() - PAD * 2); }
    private double timeAt(int x) { return offset + (x - PAD) / (double) trackW() * (duration / zoom); }
    private double xAt(double t) { return PAD + (t - offset) / (duration / zoom) * trackW(); }
    private double clampT(double t) { return Math.max(0, Math.min(duration, t)); }

    /** {segment index, 0=start/1=end} of an edge near x, or null. */
    private int[] edgeAt(int x) {
        if (edit == null) return null;
        for (int i = 0; i < edit.segments.size(); i++) {
            EditSpec.Segment s = edit.segments.get(i);
            if (Math.abs(xAt(s.start) - x) <= EDGE) return new int[]{i, 0};
            if (Math.abs(xAt(s.end) - x) <= EDGE) return new int[]{i, 1};
        }
        return null;
    }

    private void menu(MouseEvent e) {
        double t = clampT(timeAt(e.getX()));
        JPopupMenu m = new JPopupMenu();
        int seg = edit.segmentAt(t);
        if (seg >= 0) {
            m.add(item("Split here", () -> { beforeEdit.run(); edit.splitAt(t); afterEdit.run(); }));
            if (edit.segments.size() > 1) m.add(item("Delete this part", () -> { beforeEdit.run(); edit.segments.remove(seg); afterEdit.run(); }));
            m.addSeparator();
            for (double sp : new double[]{0.25, 0.5, 0.75, 1, 1.5, 2, 4}) {
                String label = (sp == 1 ? "Normal speed" : (sp < 1 ? "Slow-mo " : "Speed up ") + fmtSpeed(sp));
                JMenuItem it = item(label, () -> { beforeEdit.run(); edit.segments.get(seg).speed = sp; afterEdit.run(); });
                if (edit.segments.get(seg).speed == sp) it.setIcon(Theme.icon(Theme.Glyph.CHECK, 14, Theme.RED));
                m.add(it);
            }
        } else {
            // In a cut-out gap: bring it back.
            m.add(item("Restore this part", () -> {
                beforeEdit.run();
                double lo = 0, hi = duration;
                for (EditSpec.Segment s : edit.segments) {
                    if (s.end <= t) lo = Math.max(lo, s.end);
                    if (s.start >= t) hi = Math.min(hi, s.start);
                }
                edit.segments.add(new EditSpec.Segment(lo, hi, 1));
                edit.sort();
                afterEdit.run();
            }));
        }
        m.show(this, e.getX(), e.getY());
    }

    private static JMenuItem item(String t, Runnable r) {
        JMenuItem i = new JMenuItem(t);
        i.addActionListener(e -> r.run());
        return i;
    }

    static String fmtSpeed(double s) {
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s)) + "×";
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.hq(g);
        int w = getWidth();
        g.setColor(Theme.BG);
        g.fillRect(0, 0, w, getHeight());
        int tw = trackW();
        int y0 = RULER + PAD - 6;

        // ruler
        g.setFont(Theme.inter.deriveFont(10.5f));
        double vis = duration / zoom;
        double step = niceStep(vis / Math.max(1, tw / 90.0));
        for (double t = Math.ceil(offset / step) * step; t <= offset + vis + 1e-9; t += step) {
            int x = (int) xAt(t);
            g.setColor(Theme.BORDER);
            g.drawLine(x, RULER - 6, x, RULER);
            g.setColor(Theme.FAINT);
            String s = step < 1 ? Theme.timeFine(t) : Theme.time(t);
            g.drawString(s, x + 3, RULER - 8);
        }

        Shape track = new RoundRectangle2D.Double(PAD, y0, tw, STRIP + WAVE, 10, 10);
        g.setClip(track);
        g.setColor(Theme.CARD);
        g.fill(track);
        // filmstrip, stretched across the source duration
        if (strip != null) {
            int n = stripFrames, fw = strip.getWidth() / n;
            double frameDur = duration / n;
            for (int i = 0; i < n; i++) {
                int x1 = (int) xAt(i * frameDur), x2 = (int) Math.ceil(xAt((i + 1) * frameDur));
                if (x2 < PAD || x1 > PAD + tw) continue;
                // keep aspect: tile the frame across the slot
                int tileW = (int) (STRIP * 16 / 9.0);
                for (int x = x1; x < x2; x += tileW) {
                    int dw = Math.min(tileW, x2 - x);
                    g.drawImage(strip, x, y0, x + dw, y0 + STRIP, i * fw, 0, i * fw + (int) (fw * dw / (double) tileW), strip.getHeight(), null);
                }
            }
        }
        // waveform
        g.setColor(new Color(0x0E0E12));
        g.fillRect(PAD, y0 + STRIP, tw, WAVE);
        if (wave != null) {
            int sx1 = (int) (offset / duration * wave.getWidth()), sx2 = (int) ((offset + vis) / duration * wave.getWidth());
            g.drawImage(wave, PAD, y0 + STRIP + 2, PAD + tw, y0 + STRIP + WAVE - 2, sx1, 0, Math.max(sx1 + 1, sx2), wave.getHeight(), null);
        }

        // cut-out parts
        if (edit != null) {
            double prev = 0;
            for (EditSpec.Segment s : edit.segments) {
                hatch(g, prev, s.start, y0);
                prev = s.end;
            }
            hatch(g, prev, duration, y0);
        }
        g.setClip(null);

        // kept segments: outline, handles, speed label
        if (edit != null) {
            for (int i = 0; i < edit.segments.size(); i++) {
                EditSpec.Segment s = edit.segments.get(i);
                double x1 = xAt(s.start), x2 = xAt(s.end);
                g.setColor(Theme.RED);
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Double(x1, y0, Math.max(2, x2 - x1), STRIP + WAVE, 8, 8));
                handle(g, x1, y0, true);
                handle(g, x2, y0, false);
                if (s.speed != 1) {
                    String lbl = fmtSpeed(s.speed);
                    g.setFont(Theme.interBold.deriveFont(11f));
                    int lw = g.getFontMetrics().stringWidth(lbl) + 10;
                    int lx = (int) ((x1 + x2) / 2 - lw / 2.0);
                    g.setColor(s.speed < 1 ? new Color(0x4C8DFF) : Theme.GOLD);
                    g.fill(new RoundRectangle2D.Double(lx, y0 + 4, lw, 17, 8, 8));
                    g.setColor(Color.BLACK);
                    g.drawString(lbl, lx + 5, y0 + 17);
                }
            }
        }

        // markers
        g.setColor(Theme.GOLD);
        for (double m : markers) {
            double x = xAt(m);
            if (x < PAD - 4 || x > PAD + tw + 4) continue;
            Path2D tri = new Path2D.Double();
            tri.moveTo(x - 5, y0 - 8);
            tri.lineTo(x + 5, y0 - 8);
            tri.lineTo(x, y0 - 1);
            tri.closePath();
            g.fill(tri);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3, 3}, 0));
            g.drawLine((int) x, y0, (int) x, y0 + STRIP + WAVE);
        }

        // playhead
        double px = xAt(playhead);
        if (px >= PAD - 1 && px <= PAD + tw + 1) {
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f));
            g.drawLine((int) px, y0 - 4, (int) px, y0 + STRIP + WAVE + 4);
            Path2D head = new Path2D.Double();
            head.moveTo(px - 6, y0 - 12);
            head.lineTo(px + 6, y0 - 12);
            head.lineTo(px + 6, y0 - 6);
            head.lineTo(px, y0 - 1);
            head.lineTo(px - 6, y0 - 6);
            head.closePath();
            g.fill(head);
        }

        // zoom scrollbar hint
        if (zoom > 1) {
            int bw = (int) (tw / zoom), bx = (int) (PAD + offset / duration * tw);
            g.setColor(Theme.BORDER);
            g.fill(new RoundRectangle2D.Double(bx, getHeight() - 6, Math.max(20, bw), 4, 4, 4));
        }
        g.dispose();
    }

    private void hatch(Graphics2D g, double a, double b, int y0) {
        if (b - a <= 1e-6) return;
        double x1 = xAt(a), x2 = xAt(b);
        g.setPaint(HATCH);
        g.fill(new Rectangle2D.Double(x1, y0, x2 - x1, STRIP + WAVE));
    }

    private void handle(Graphics2D g, double x, int y0, boolean left) {
        g.setColor(Theme.RED);
        double hw = 8;
        RoundRectangle2D r = new RoundRectangle2D.Double(left ? x - 1 : x - hw + 1, y0, hw, STRIP + WAVE, 6, 6);
        g.fill(r);
        g.setColor(new Color(255, 255, 255, 200));
        double cx = left ? x + 3 : x - 3;
        g.fillRoundRect((int) cx - 1, y0 + (STRIP + WAVE) / 2 - 8, 2, 16, 2, 2);
    }

    private static double niceStep(double raw) {
        double[] steps = {0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600};
        for (double s : steps) if (s >= raw) return s;
        return 3600;
    }
}
