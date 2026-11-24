package net.evadeclient.clips.ui;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Small always-on-top notification in the corner of the screen (shows over windowed/borderless games). Errors
 * stay up longer, nothing fades while the pointer is on it, and a click dismisses it.
 */
public final class Toast {
    private static final int W = 340, TEXT_X = 54, LINE = 17, MAX_LINES = 3;
    private static final long HOLD_MS = 2600, ERROR_HOLD_MS = 8000;
    private JWindow window;
    private String title = "";
    private List<String> lines = List.of();
    private boolean error;
    private Timer hide;
    private float alpha;
    private volatile boolean hovered;
    private long shownAt, holdMs;

    private static final boolean OPAQUE = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    public void show(String title, String body, boolean error) {
        if (GraphicsEnvironment.isHeadless()) return;
        this.error = error;
        if (window == null) {
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            // Windows: per-pixel see-through windows garble on HiDPI scaling (tiled garbage that stays on screen),
            // so the toast is a normal opaque window cut to a rounded shape there; elsewhere it stays see-through.
            if (OPAQUE) window.setBackground(new Color(0x111115));
            else window.setBackground(new Color(0, 0, 0, 0));
            JComponent content = new JComponent() {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    // A see-through window starts from whatever was in its buffer: wipe it first, or some Windows
                    // drivers show leftover pixels around and behind the toast.
                    g.setComposite(OPAQUE ? java.awt.AlphaComposite.Src : java.awt.AlphaComposite.Clear);
                    g.setColor(new Color(0x111115));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setComposite(java.awt.AlphaComposite.SrcOver);
                    paintToast(g, getWidth(), getHeight());
                    g.dispose();
                }
            };
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; alpha = 1; setOpacity(1); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; shownAt = System.currentTimeMillis(); }
                @Override public void mouseClicked(MouseEvent e) { dismiss(); }
            };
            content.addMouseListener(ma);
            window.setContentPane(content);
        }
        FontMetrics tf = window.getFontMetrics(Theme.interBold.deriveFont(14f));
        FontMetrics bf = window.getFontMetrics(Theme.inter.deriveFont(12f));
        int textW = W - TEXT_X - 18;
        this.title = ellipsize(tf, title == null ? "" : title, textW);
        this.lines = wrap(bf, body, textW);
        int h = lines.isEmpty() ? 54 : 72 + (lines.size() - 1) * LINE;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setSize(new Dimension(W, h));
        if (OPAQUE) {
            try { window.setShape(new RoundRectangle2D.Double(0, 0, W, h, 14, 14)); } catch (Exception ignored) {} // no shaped windows: square corners
        }
        window.setLocation(screen.x + screen.width - W - 24, screen.y + 24);
        alpha = 1;
        setOpacity(1f);
        window.setVisible(true);
        window.repaint();
        if (hide != null) hide.stop();
        shownAt = System.currentTimeMillis();
        holdMs = error ? ERROR_HOLD_MS : HOLD_MS;
        hide = new Timer(30, e -> {
            if (hovered) return;
            if (System.currentTimeMillis() - shownAt > holdMs) {
                alpha -= 0.08f;
                if (alpha <= 0) { dismiss(); return; }
                setOpacity(Math.max(0, alpha));
            }
        });
        hide.start();
    }

    private void dismiss() {
        if (hide != null) hide.stop();
        if (window != null) {
            // Dispose (not just hide): a hidden see-through window can leave its last frame on screen on Windows.
            window.setVisible(false);
            window.dispose();
            window = null;
        }
        hovered = false;
    }

    private void setOpacity(float a) {
        try { window.setOpacity(a); } catch (Exception ignored) {} // no translucency on this system
    }

    /** Word-wraps {@code text} to {@code max} px, at most {@link #MAX_LINES} lines (the last one ellipsized). */
    static List<String> wrap(FontMetrics fm, String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] words = text.strip().split("\\s+");
        StringBuilder line = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String w = words[i];
            String next = line.isEmpty() ? w : line + " " + w;
            if (fm.stringWidth(next) <= max) { line.setLength(0); line.append(next); continue; }
            if (line.isEmpty()) {
                out.add(ellipsize(fm, w, max)); // one word wider than the toast
                if (out.size() == MAX_LINES) { i++; break; }
                continue;
            }
            out.add(line.toString());
            line.setLength(0);
            line.append(w);
            if (out.size() == MAX_LINES) break; // w isn't placed yet
        }
        if (i >= words.length && !line.isEmpty() && out.size() < MAX_LINES) out.add(line.toString());
        if (i < words.length && !out.isEmpty()) {
            // More text than fits: the last line ends with an ellipsis.
            StringBuilder rest = new StringBuilder(out.get(out.size() - 1));
            for (int j = i; j < words.length; j++) rest.append(' ').append(words[j]);
            out.set(out.size() - 1, ellipsize(fm, rest.toString(), max));
        }
        return out;
    }

    static String ellipsize(FontMetrics fm, String s, int max) {
        if (fm.stringWidth(s) <= max) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s.stripTrailing() + "…";
    }

    private void paintToast(Graphics2D g, int w, int h) {
        Theme.hq(g);
        g.setColor(new Color(0x111115));
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 14, 14));
        g.setColor(Theme.BORDER);
        g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 14, 14));
        g.setColor(error ? Theme.GOLD : Theme.RED);
        g.fill(new RoundRectangle2D.Double(0, 0, 5, h, 4, 4));
        if (Theme.mark != null) g.drawImage(Theme.mark, 16, lines.isEmpty() ? (h - 26) / 2 : 17, 26, 26, null);
        Font tf = Theme.interBold.deriveFont(14f);
        g.setFont(tf);
        g.setColor(Theme.TEXT);
        g.drawString(title, TEXT_X, lines.isEmpty() ? 32 : 30);
        if (!lines.isEmpty()) {
            g.setFont(Theme.inter.deriveFont(12f));
            g.setColor(Theme.DIM);
            for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), TEXT_X, 50 + i * LINE);
        }
    }
}
