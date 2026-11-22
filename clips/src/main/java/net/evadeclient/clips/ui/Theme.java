package net.evadeclient.clips.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Evade's black and red look, on top of FlatLaf. */
public final class Theme {
    public static final Color BG = new Color(0x0A0A0C), PANEL = new Color(0x101014), CARD = new Color(0x15151A),
            CARD_HI = new Color(0x1C1C23), BORDER = new Color(0x26262E), RED = new Color(0xE11D2E),
            RED_HI = new Color(0xFF4153), RED_DIM = new Color(0x5A1017), TEXT = new Color(0xF4F4F6),
            DIM = new Color(0x9B9BA6), FAINT = new Color(0x5E5E69), OK = new Color(0x3DDC84), GOLD = new Color(0xFFC53D),
            BLUE = new Color(0x4C8DFF);

    public static Font inter, interBold, chakra;
    public static Image mark, icon;

    private Theme() {}

    public static void install() {
        inter = font("/clips/inter-medium.ttf", Font.PLAIN);
        interBold = font("/clips/inter-bold.ttf", Font.BOLD);
        chakra = font("/clips/chakra-bolditalic.ttf", Font.BOLD);
        mark = image("/clips/mark.png");
        icon = image("/clips/icon-128.png");

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("@accentColor", "#E11D2E");
        extra.put("@background", "#0E0E11");
        extra.put("@foreground", "#F4F4F6");
        FlatLaf.setGlobalExtraDefaults(extra);
        FlatDarkLaf.setup();

        UIManager.put("defaultFont", inter.deriveFont(13f));
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("ScrollPane.background", PANEL);
        UIManager.put("Viewport.background", PANEL);
        UIManager.put("TextField.background", CARD);
        UIManager.put("ComboBox.background", CARD);
        UIManager.put("Spinner.background", CARD);
        UIManager.put("Button.background", CARD_HI);
        UIManager.put("Button.default.background", RED);
        UIManager.put("Button.default.foreground", Color.WHITE);
        UIManager.put("Button.default.hoverBackground", RED_HI);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Separator.foreground", BORDER);
        UIManager.put("PopupMenu.background", CARD);
        UIManager.put("MenuItem.selectionBackground", RED_DIM);
        UIManager.put("List.selectionBackground", RED_DIM);
        UIManager.put("Slider.thumbColor", RED);
        UIManager.put("Slider.trackValueColor", RED);
        UIManager.put("ProgressBar.foreground", RED);
        UIManager.put("ProgressBar.background", CARD_HI);
        UIManager.put("ToolTip.background", CARD_HI);
        UIManager.put("TitlePane.background", BG);
        UIManager.put("TitlePane.inactiveBackground", BG);
        UIManager.put("RootPane.background", BG);
        UIManager.put("TabbedPane.selectedBackground", CARD);
    }

    private static Font font(String path, int fallbackStyle) {
        try (InputStream in = Theme.class.getResourceAsStream(path)) {
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            return f.deriveFont(13f);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, fallbackStyle, 13);
        }
    }

    private static Image image(String path) {
        try (InputStream in = Theme.class.getResourceAsStream(path)) { return ImageIO.read(in); }
        catch (Exception e) { return null; }
    }

    public static void hq(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static JLabel label(String text, float size, boolean bold, Color c) {
        JLabel l = new JLabel(text);
        l.setFont((bold ? interBold : inter).deriveFont(size));
        l.setForeground(c);
        return l;
    }

    public static JLabel heading(String text) {
        JLabel l = label(text.toUpperCase(), 11f, true, FAINT);
        l.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));
        return l;
    }

    public static JButton button(String text, Icon icon, boolean primary) {
        JButton b = new JButton(text, icon);
        b.setFont(interBold.deriveFont(13f));
        b.setFocusable(false);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        if (primary) {
            b.putClientProperty("JButton.buttonType", null);
            b.setBackground(RED);
            b.setForeground(Color.WHITE);
        }
        b.setIconTextGap(8);
        return b;
    }

    public static JButton iconButton(Icon icon, String tooltip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    public static void pad(JComponent c, int t, int l, int b, int r) { c.setBorder(BorderFactory.createEmptyBorder(t, l, b, r)); }

    // ------------------------------------------------------------------ formatting

    public static String time(double sec) {
        if (sec < 0 || Double.isNaN(sec)) sec = 0;
        int s = (int) sec;
        int h = s / 3600, m = (s / 60) % 60, ss = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%d:%02d", m, ss);
    }

    public static String timeFine(double sec) {
        if (sec < 0 || Double.isNaN(sec)) sec = 0;
        int cs = (int) Math.round(sec * 100);
        int m = cs / 6000, s = (cs / 100) % 60, c = cs % 100;
        return String.format("%d:%02d.%02d", m, s, c);
    }

    public static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    public static String ago(long when) {
        long d = (System.currentTimeMillis() - when) / 1000;
        if (d < 60) return "just now";
        if (d < 3600) return (d / 60) + " min ago";
        if (d < 86400) return (d / 3600) + " h ago";
        if (d < 86400 * 7) return (d / 86400) + (d / 86400 == 1 ? " day ago" : " days ago");
        return new java.text.SimpleDateFormat("d MMM yyyy").format(new java.util.Date(when));
    }

    // ------------------------------------------------------------------ vector icons (no image assets needed)

    public enum Glyph { PLAY, PAUSE, RECORD, STOP, SCISSORS, TRASH, STAR, STAR_FILLED, FOLDER, IMPORT, EXPORT, GRID, CAMERA,
        GEAR, BOOKMARK, BACK, FRAME_PREV, FRAME_NEXT, SPLIT, UNDO, REDO, VOLUME, MUTE, COPY, EDIT, CLOCK, CHECK, SEARCH, FILM }

    public static Icon icon(Glyph g, int size, Color color) { return new GlyphIcon(g, size, color); }

    private record GlyphIcon(Glyph glyph, int size, Color color) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            hq(g);
            g.translate(x, y);
            double s = size / 24.0;
            g.scale(s, s);
            Color col = c != null && !c.isEnabled() ? FAINT : color;
            g.setColor(col);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (glyph) {
                case PLAY -> g.fill(poly(7, 4, 20, 12, 7, 20));
                case PAUSE -> { g.fill(new RoundRectangle2D.Double(6, 4, 4, 16, 2, 2)); g.fill(new RoundRectangle2D.Double(14, 4, 4, 16, 2, 2)); }
                case RECORD -> g.fillOval(5, 5, 14, 14);
                case STOP -> g.fill(new RoundRectangle2D.Double(6, 6, 12, 12, 3, 3));
                case SCISSORS, SPLIT -> {
                    g.drawLine(12, 3, 12, 21);
                    g.draw(poly(7, 8, 3, 12, 7, 16));
                    g.draw(poly(17, 8, 21, 12, 17, 16));
                }
                case TRASH -> {
                    g.drawLine(4, 7, 20, 7);
                    g.drawLine(9, 7, 10, 4); g.drawLine(10, 4, 14, 4); g.drawLine(14, 4, 15, 7);
                    g.draw(poly(6, 7, 7, 20, 17, 20, 18, 7));
                    g.drawLine(10, 11, 10, 16); g.drawLine(14, 11, 14, 16);
                }
                case STAR, STAR_FILLED -> {
                    Path2D p = new Path2D.Double();
                    for (int i = 0; i < 10; i++) {
                        double a = Math.PI / 2 + i * Math.PI / 5;
                        double r = i % 2 == 0 ? 9.5 : 4;
                        double px = 12 + Math.cos(a) * r, py = 12.5 - Math.sin(a) * r;
                        if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                    }
                    p.closePath();
                    if (glyph == Glyph.STAR_FILLED) g.fill(p); else g.draw(p);
                }
                case FOLDER -> g.draw(poly(3, 6, 9, 6, 11, 8, 21, 8, 21, 19, 3, 19));
                case IMPORT -> { g.drawLine(12, 3, 12, 15); g.draw(open(7, 10, 12, 15, 17, 10)); g.draw(open(4, 15, 4, 20, 20, 20, 20, 15)); }
                case EXPORT -> { g.drawLine(12, 15, 12, 3); g.draw(open(7, 8, 12, 3, 17, 8)); g.draw(open(4, 15, 4, 20, 20, 20, 20, 15)); }
                case GRID -> { for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) g.draw(new RoundRectangle2D.Double(4 + i * 9, 4 + j * 9, 7, 7, 2, 2)); }
                case CAMERA -> { g.draw(new RoundRectangle2D.Double(3, 6, 13, 12, 3, 3)); g.draw(poly(16, 10, 21, 7, 21, 17, 16, 14)); }
                case FILM -> { g.draw(new RoundRectangle2D.Double(3, 4, 18, 16, 3, 3)); g.drawLine(7, 4, 7, 20); g.drawLine(17, 4, 17, 20); g.drawLine(3, 12, 21, 12); }
                case GEAR -> {
                    g.drawOval(9, 9, 6, 6);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        g.drawLine((int) (12 + Math.cos(a) * 6), (int) (12 + Math.sin(a) * 6), (int) (12 + Math.cos(a) * 9), (int) (12 + Math.sin(a) * 9));
                    }
                }
                case BOOKMARK -> g.draw(poly(6, 3, 18, 3, 18, 21, 12, 16, 6, 21));
                case BACK -> g.draw(open(15, 4, 7, 12, 15, 20));
                case FRAME_PREV -> { g.fill(poly(16, 5, 8, 12, 16, 19)); g.fillRect(5, 5, 2, 14); }
                case FRAME_NEXT -> { g.fill(poly(8, 5, 16, 12, 8, 19)); g.fillRect(17, 5, 2, 14); }
                case UNDO -> { g.draw(open(8, 5, 4, 9, 8, 13)); g.draw(new java.awt.geom.Arc2D.Double(4, 9, 16, 12, 90, -180, java.awt.geom.Arc2D.OPEN)); }
                case REDO -> { g.draw(open(16, 5, 20, 9, 16, 13)); g.draw(new java.awt.geom.Arc2D.Double(4, 9, 16, 12, 90, 180, java.awt.geom.Arc2D.OPEN)); }
                case VOLUME, MUTE -> {
                    g.fill(poly(3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15));
                    if (glyph == Glyph.VOLUME) { g.draw(new java.awt.geom.Arc2D.Double(10, 7, 8, 10, -60, 120, java.awt.geom.Arc2D.OPEN)); g.draw(new java.awt.geom.Arc2D.Double(10, 4, 12, 16, -60, 120, java.awt.geom.Arc2D.OPEN)); }
                    else { g.drawLine(15, 9, 21, 15); g.drawLine(21, 9, 15, 15); }
                }
                case COPY -> { g.draw(new RoundRectangle2D.Double(8, 8, 12, 12, 3, 3)); g.draw(open(4, 15, 4, 4, 15, 4)); }
                case EDIT -> { g.draw(poly(4, 20, 5, 15, 16, 4, 20, 8, 9, 19)); }
                case CLOCK -> { g.drawOval(3, 3, 18, 18); g.draw(open(12, 7, 12, 12, 16, 14)); }
                case CHECK -> g.draw(open(4, 12, 10, 18, 20, 6));
                case SEARCH -> { g.drawOval(4, 4, 12, 12); g.drawLine(14, 14, 20, 20); }
            }
            g.dispose();
        }

        private static Path2D poly(double... xy) {
            Path2D p = open(xy);
            p.closePath();
            return p;
        }

        private static Path2D open(double... xy) {
            Path2D p = new Path2D.Double();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            return p;
        }
    }
}
