package net.evadeclient.clips.ui;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.RoundRectangle2D;

/** Small always-on-top notification in the corner of the screen (shows over windowed/borderless games). */
public final class Toast {
    private JWindow window;
    private String title = "", body;
    private boolean error;
    private Timer hide;
    private float alpha;

    public void show(String title, String body, boolean error) {
        if (GraphicsEnvironment.isHeadless()) return;
        this.title = title;
        this.body = body;
        this.error = error;
        if (window == null) {
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            window.setBackground(new Color(0, 0, 0, 0));
            window.setContentPane(new JComponent() {
                @Override protected void paintComponent(Graphics g0) { paintToast((Graphics2D) g0, getWidth(), getHeight()); }
            });
        }
        int w = 320, h = body == null || body.isBlank() ? 54 : 72;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setSize(new Dimension(w, h));
        window.setLocation(screen.x + screen.width - w - 24, screen.y + 24);
        alpha = 1;
        try { window.setOpacity(1f); } catch (Exception ignored) {}
        window.setVisible(true);
        window.repaint();
        if (hide != null) hide.stop();
        long shownAt = System.currentTimeMillis();
        hide = new Timer(30, e -> {
            long age = System.currentTimeMillis() - shownAt;
            if (age > 2600) {
                alpha -= 0.08f;
                if (alpha <= 0) { window.setVisible(false); ((Timer) e.getSource()).stop(); return; }
                try { window.setOpacity(Math.max(0, alpha)); } catch (Exception ignored) {}
            }
        });
        hide.start();
    }

    private void paintToast(Graphics2D g, int w, int h) {
        Theme.hq(g);
        g.setColor(new Color(0x111115));
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 14, 14));
        g.setColor(Theme.BORDER);
        g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 14, 14));
        g.setColor(error ? Theme.GOLD : Theme.RED);
        g.fill(new RoundRectangle2D.Double(0, 0, 5, h, 4, 4));
        if (Theme.mark != null) g.drawImage(Theme.mark, 16, (h - 26) / 2, 26, 26, null);
        g.setFont(Theme.interBold.deriveFont(14f));
        g.setColor(Theme.TEXT);
        boolean hasBody = body != null && !body.isBlank();
        g.drawString(title, 54, hasBody ? 30 : 32);
        if (hasBody) {
            g.setFont(Theme.inter.deriveFont(12f));
            g.setColor(Theme.DIM);
            String b = body;
            while (g.getFontMetrics().stringWidth(b) > w - 70 && b.length() > 4) b = b.substring(0, b.length() - 4) + "…";
            g.drawString(b, 54, 50);
        }
    }
}
