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
 * trim), cut-out parts hatched, per-segment speed, freeze frames and rewinds, markers (right-click one to
 * rename, move or delete it), a music lane and the playhead. Ctrl+scroll zooms; scroll, Shift+scroll or the bar
 * along the bottom pans.
 */
public final class Timeline extends JComponent {
    private static final int RULER = 22, STRIP = 58, WAVE = 34, PAD = 12, EDGE = 7, LANE = 16;

    private double duration = 1;
    private EditSpec edit;
    private List<Double> markers = List.of();
    /** Extra right-click items from the editor (effects, ramps, zoom) for the time clicked. */
    public java.util.function.BiConsumer<JPopupMenu, Double> extraMenu;
    /** Marker names ("Kill Steve", "ACE"), drawn next to the marker and shown on hover. */
    public java.util.function.Function<Double, String> labelOf = t -> null;
    private double playhead;
    private BufferedImage strip, wave;
    private int stripFrames = 10;
    /** The background music's name, drawn in its own lane (null = no music). */
    private String music;

    // view
    private double zoom = 1, offset; // offset in seconds at left edge
    /** Whether the view keeps the playhead in sight; off once you scroll away, on again when you seek or play. */
    private boolean following = true;

    // interaction
    private int dragSeg = -1, pendingSeg = -1;
    private boolean dragStartEdge, pendingStart, scrubbing, barDrag;
    private int pressX;
    private double dragOrigin, grabDelta;

    public DoubleConsumer onSeek = t -> {};
    public Runnable beforeEdit = () -> {};
    public Runnable afterEdit = () -> {};
    /** Around a drag that moves the playhead (scrubbing or dragging a part's edge), so playback can pause for it. */
    public Runnable onScrubStart = () -> {}, onScrubEnd = () -> {};
    /** Marker actions from its right-click menu, by index in the marker list. */
    public java.util.function.IntConsumer onDeleteMarker = i -> {}, onMoveMarker = i -> {};
    public java.util.function.BiConsumer<Integer, String> onRenameMarker = (i, name) -> {};

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
        setPreferredSize(new Dimension(800, baseHeight()));
        setOpaque(true);
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (edit == null) return;
                if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e)) { menu(e); return; }
                if (zoom > 1 && e.getY() >= getHeight() - 10) {
                    // the bar along the bottom: grab it where pressed (or centre it there) and drag the view
                    double vis = duration / zoom, at = (e.getX() - PAD) / (double) trackW() * duration;
                    grabDelta = at >= offset && at <= offset + vis ? at - offset : vis / 2;
                    barDrag = true;
                    following = false;
                    offset = at - grabDelta;
                    clampView();
                    repaint();
                    return;
                }
                // Near a part's edge: a drag trims it; a plain click still just moves the playhead.
                int[] edge = e.getY() > RULER ? edgeAt(e.getX()) : null;
                if (edge != null) {
                    pendingSeg = edge[0];
                    pendingStart = edge[1] == 0;
                    pressX = e.getX();
                    return;
                }
                following = true;
                scrubbing = true;
                onScrubStart.run();
                onSeek.accept(clampT(timeAt(e.getX())));
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (edit == null) return;
                if (barDrag) {
                    offset = (e.getX() - PAD) / (double) trackW() * duration - grabDelta;
                    clampView();
                    repaint();
                    return;
                }
                if (pendingSeg >= 0 && dragSeg < 0 && Math.abs(e.getX() - pressX) >= 3 && pendingSeg < edit.segments.size()) {
                    beforeEdit.run();
                    dragSeg = pendingSeg;
                    dragStartEdge = pendingStart;
                    dragOrigin = dragStartEdge ? edit.segments.get(dragSeg).start : edit.segments.get(dragSeg).end;
                    following = true;
                    onScrubStart.run();
                }
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
                boolean moved = dragSeg >= 0 || scrubbing;
                if (dragSeg >= 0) afterEdit.run();
                else if (pendingSeg >= 0 && edit != null) { following = true; onSeek.accept(clampT(timeAt(e.getX()))); }
                if (moved) onScrubEnd.run();
                dragSeg = -1;
                pendingSeg = -1;
                scrubbing = false;
                barDrag = false;
            }
            @Override public void mouseMoved(MouseEvent e) {
                int mk = markerAt(e.getX(), e.getY());
                String tip = null;
                if (mk >= 0) {
                    double m = markers.get(mk);
                    String l = labelOf.apply(m);
                    tip = (l == null ? "Marker" : l) + " · " + Theme.timeFine(m) + " · right-click to rename, move or delete";
                }
                setToolTipText(tip);
                setCursor(edgeAt(e.getX()) != null && e.getY() > RULER && e.getY() < getHeight() - 10
                        ? Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR) : Cursor.getDefaultCursor());
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) {
                    double at = timeAt(e.getX());
                    zoom = Math.max(1, Math.min(60, zoom * (e.getWheelRotation() < 0 ? 1.25 : 0.8)));
                    double vis = duration / zoom;
                    offset = at - (e.getX() - PAD) / (double) trackW() * vis;
                } else {
                    // Scroll, Shift+scroll and sideways scrolling (reported as Shift+scroll) all pan, and the view
                    // then stays where you put it instead of jumping back to the playhead.
                    offset += e.getPreciseWheelRotation() * duration / zoom * 0.1;
                    following = false;
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
        this.stripFrames = Math.max(1, stripFrames);
        this.wave = null;
        zoom = 1;
        offset = 0;
        following = true;
        repaint();
    }

    public void setEdit(EditSpec e) { edit = e; repaint(); }
    public void setWave(BufferedImage w) { wave = w; repaint(); }
    public void setMarkers(List<Double> m) { markers = m == null ? List.of() : m; repaint(); }
    /** Swaps in a denser filmstrip (more detail when zoomed) without moving the view. */
    public void setStrip(BufferedImage s, int frames) { strip = s; stripFrames = Math.max(1, frames); repaint(); }

    /** Shows the music lane with this name, or hides it (null). */
    public void setMusic(String name) {
        boolean had = music != null;
        music = name == null || name.isBlank() ? null : name;
        if (had != (music != null)) {
            setPreferredSize(new Dimension(getPreferredSize().width, baseHeight()));
            revalidate();
        }
        repaint();
    }

    private int baseHeight() { return RULER + STRIP + WAVE + PAD * 2 + 6 + (music != null ? LANE + 4 : 0); }

    public void setPlayhead(double t) {
        boolean moved = Math.abs(t - playhead) > 1e-6;
        playhead = t;
        // Keep the playhead in view when zoomed, unless you scrolled away from it.
        double vis = duration / zoom;
        if (following && moved && zoom > 1 && (t < offset || t > offset + vis)) { offset = t - vis * 0.1; clampView(); }
        repaint();
    }

    /** Brings the playhead back into view and keeps it there (playback started, or the playhead was moved). */
    public void follow() {
        following = true;
        double vis = duration / zoom;
        if (zoom > 1 && (playhead < offset || playhead > offset + vis)) { offset = playhead - vis * 0.1; clampView(); repaint(); }
    }

    public void zoomBy(double k) { zoom = Math.max(1, Math.min(60, zoom * k)); offset = playhead - duration / zoom / 2; following = true; clampView(); repaint(); }

    private void clampView() { offset = Math.max(0, Math.min(offset, duration - duration / zoom)); }

    private int trackW() { return Math.max(1, getWidth() - PAD * 2); }
    private double timeAt(int x) { return offset + (x - PAD) / (double) trackW() * (duration / zoom); }
    private double xAt(double t) { return PAD + (t - offset) / (duration / zoom) * trackW(); }
    private double clampT(double t) { return Math.max(0, Math.min(duration, t)); }

    /** Index of the marker under (x, y) (its flag above the strip, within 6 px), or -1. */
    int markerAt(int x, int y) {
        if (y >= RULER + PAD + 4) return -1;
        int best = -1;
        double bd = 6;
        for (int i = 0; i < markers.size(); i++) {
            double d = Math.abs(xAt(markers.get(i)) - x);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

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
        int mk = markerAt(e.getX(), e.getY());
        if (mk >= 0) {
            double at = markers.get(mk);
            String name = labelOf.apply(at);
            m.add(item("Rename marker…", () -> {
                Object v = javax.swing.JOptionPane.showInputDialog(this, "Marker name", "Rename marker", javax.swing.JOptionPane.PLAIN_MESSAGE,
                        null, null, name == null ? "" : name);
                if (v != null) onRenameMarker.accept(mk, v.toString().trim());
            }));
            m.add(item("Move marker to playhead", () -> onMoveMarker.accept(mk)));
            m.add(item("Delete marker", () -> onDeleteMarker.accept(mk)));
            m.addSeparator();
        }
        int seg = edit.segmentAt(t);
        if (seg >= 0) {
            m.add(item("Split here", () -> { beforeEdit.run(); edit.splitAt(t); afterEdit.run(); }));
            if (edit.segments.size() > 1) m.add(item("Delete this part", () -> { beforeEdit.run(); edit.segments.remove(seg); afterEdit.run(); }));
            m.addSeparator();
            for (double sp : new double[]{0.25, 0.3, 0.5, 0.75, 1, 1.5, 2, 4}) {
                String label = (sp == 1 ? "Normal speed" : (sp < 1 ? "Slow-mo " : "Speed up ") + fmtSpeed(sp));
                JMenuItem it = item(label, () -> { beforeEdit.run(); edit.segments.get(seg).speed = sp; edit.segments.get(seg).rampTo = 0; afterEdit.run(); });
                if (edit.segments.get(seg).speed == sp) it.setIcon(Theme.icon(Theme.Glyph.CHECK, 14, Theme.RED));
                m.add(it);
            }
            if (extraMenu != null) { m.addSeparator(); extraMenu.accept(m, t); }
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
        double r = Math.round(s * 100) / 100.0;
        return (r == Math.floor(r) ? String.valueOf((int) r) : String.valueOf(r)) + "×";
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
                if (s.speed != 1 || s.ramped()) {
                    String lbl = s.ramped() ? fmtSpeed(s.speed) + "→" + fmtSpeed(s.rampTo) : fmtSpeed(s.speed);
                    g.setFont(Theme.interBold.deriveFont(11f));
                    int lw = g.getFontMetrics().stringWidth(lbl) + 10;
                    int lx = (int) ((x1 + x2) / 2 - lw / 2.0);
                    g.setColor(Math.min(s.speed, s.ramped() ? s.rampTo : s.speed) < 1 ? new Color(0x4C8DFF) : Theme.GOLD);
                    g.fill(new RoundRectangle2D.Double(lx, y0 + 4, lw, 17, 8, 8));
                    g.setColor(Color.BLACK);
                    g.drawString(lbl, lx + 5, y0 + 17);
                }
                // rewind (left) and freeze frame (right) badges along the bottom of the part
                if (s.rewinds()) badge(g, "REWIND", new Color(0xB388FF), x1 + 10, y0 + STRIP + WAVE - 21, false);
                if (s.hold() > 0) badge(g, "FREEZE " + fmtSecs(s.hold()), new Color(0x7FDBFF), x2 - 10, y0 + STRIP + WAVE - 21, true);
            }
        }

        // effects (pink dots along the bottom) and zoom keyframes (blue diamonds along the top)
        if (edit != null) {
            for (EditSpec.Effect f : edit.effects) {
                double x = xAt(f.t);
                if (x < PAD - 4 || x > PAD + tw + 4) continue;
                g.setColor(new Color(0xFF4FA3));
                g.fill(new java.awt.geom.Ellipse2D.Double(x - 4, y0 + STRIP + WAVE - 10, 8, 8));
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1f));
                g.draw(new java.awt.geom.Ellipse2D.Double(x - 4, y0 + STRIP + WAVE - 10, 8, 8));
            }
            for (EditSpec.ZoomKey k : edit.zooms) {
                double x = xAt(k.t);
                if (x < PAD - 4 || x > PAD + tw + 4) continue;
                Path2D d = new Path2D.Double();
                d.moveTo(x, y0 + 3); d.lineTo(x + 5, y0 + 8); d.lineTo(x, y0 + 13); d.lineTo(x - 5, y0 + 8); d.closePath();
                g.setColor(new Color(0x4C8DFF));
                g.fill(d);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1f));
                g.draw(d);
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
            String l = labelOf.apply(m);
            if (l != null) {
                g.setFont(Theme.interBold.deriveFont(10.5f));
                int lw = g.getFontMetrics().stringWidth(l) + 8;
                int lx = (int) Math.min(x + 6, getWidth() - lw - 2);
                g.setColor(new Color(0, 0, 0, 190));
                g.fill(new RoundRectangle2D.Double(lx, y0 + STRIP - 18, lw, 15, 6, 6));
                g.setColor(Theme.GOLD);
                g.drawString(l, lx + 4, y0 + STRIP - 7);
            }
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

        // music lane: the song plays under every kept part
        if (music != null && edit != null) {
            int ly = y0 + STRIP + WAVE + 4;
            Color mc = new Color(0x2EC4B6);
            g.setFont(Theme.interBold.deriveFont(10.5f));
            boolean named = false;
            for (EditSpec.Segment s : edit.segments) {
                double a1 = Math.max(PAD, xAt(s.start)), a2 = Math.min(PAD + tw, xAt(s.end));
                if (a2 - a1 < 1) continue;
                g.setColor(new Color(mc.getRed(), mc.getGreen(), mc.getBlue(), 70));
                g.fill(new RoundRectangle2D.Double(a1, ly, a2 - a1, LANE, 8, 8));
                g.setColor(mc);
                g.setStroke(new BasicStroke(1f));
                g.draw(new RoundRectangle2D.Double(a1, ly, a2 - a1, LANE, 8, 8));
                if (!named && a2 - a1 > 40) {
                    java.awt.Shape clip = g.getClip();
                    g.clip(new Rectangle2D.Double(a1, ly, a2 - a1, LANE));
                    g.drawString("Music · " + music, (int) a1 + 7, ly + 12);
                    g.setClip(clip);
                    named = true;
                }
            }
        }

        // zoom scrollbar: drag it to move the view
        if (zoom > 1) {
            int bw = (int) (tw / zoom), bx = (int) (PAD + offset / duration * tw);
            g.setColor(barDrag ? Theme.FAINT : Theme.BORDER);
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

    /** A small label on a part; {@code right} = its right edge is at x. */
    private static void badge(Graphics2D g, String text, Color c, double x, int y, boolean right) {
        g.setFont(Theme.interBold.deriveFont(9.5f));
        int w = g.getFontMetrics().stringWidth(text) + 8;
        double bx = right ? x - w : x;
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(bx, y, w, 14, 6, 6));
        g.setColor(Color.BLACK);
        g.drawString(text, (int) bx + 4, y + 11);
    }

    static String fmtSecs(double s) {
        double r = Math.round(s * 10) / 10.0;
        return (r == Math.floor(r) ? String.valueOf((int) r) : String.valueOf(r)) + " s";
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
