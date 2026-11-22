package net.evadeclient.installer;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
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
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The whole installer UI, painted by hand so it matches the client's black and red look. */
public final class InstallerView extends JPanel {
    public static final int W = 780, H = 480;

    enum State { IDLE, INSTALLING, DONE, ERROR }

    // palette
    static final Color BG = new Color(0x0A0A0C), CARD = new Color(0x111115), CARD_2 = new Color(0x17171C),
            BORDER = new Color(0x24242C), RED = new Color(0xE11D2E), RED_HI = new Color(0xFF4153),
            TEXT = new Color(0xF4F4F6), DIM = new Color(0x9B9BA6), FAINT = new Color(0x5E5E69), OK = new Color(0x3DDC84);

    private final Font inter, interBold, chakra;
    private final Image mark, word;

    // state (touched on the EDT only)
    State state = State.IDLE;
    Path minecraftDir = InstallerCore.defaultMinecraftDir();
    int memoryGb = 4;
    boolean copySettings = true;
    boolean voiceChat = true;
    boolean performanceMods = true;
    /** Also install Evade Clips (instant replay, clip library and editor). */
    boolean installClips = true;
    InstallerCore.Release release;
    String releaseError;
    InstallerCore.Installed installed;
    double progress, shownProgress;
    String status = "";
    String error = "";
    final List<String> stepsDone = new ArrayList<>();

    private final Map<String, Rectangle> hits = new HashMap<>();
    private final Map<String, Float> hover = new HashMap<>();
    private String hovered;
    private Point dragFrom;
    private final long start = System.currentTimeMillis();

    Runnable onInstall = () -> {};
    Runnable onOpenLauncher = () -> {};
    Consumer<Path> onFolderChanged = p -> {};

    public InstallerView() {
        inter = font("/evade/inter-medium.ttf");
        interBold = font("/evade/inter-bold.ttf");
        chakra = font("/evade/chakra-bolditalic.ttf");
        mark = image("/evade/mark.png");
        word = image("/evade/word.png");
        setPreferredSize(new java.awt.Dimension(W, H));
        setOpaque(true);
        refreshInstalled();

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateHover(e.getPoint()); }
            @Override public void mouseExited(MouseEvent e) { hovered = null; setCursor(Cursor.getDefaultCursor()); }
            @Override public void mousePressed(MouseEvent e) {
                if (e.getY() < 44 && hit(e.getPoint()) == null) dragFrom = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { dragFrom = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null) return;
                Window w = SwingUtilities.getWindowAncestor(InstallerView.this);
                if (w != null) w.setLocation(e.getXOnScreen() - dragFrom.x, e.getYOnScreen() - dragFrom.y);
            }
            @Override public void mouseClicked(MouseEvent e) { click(hit(e.getPoint())); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        // Smooth hover/progress animation.
        new Timer(16, e -> {
            shownProgress += (progress - shownProgress) * 0.18;
            repaint();
        }).start();
    }

    void refreshInstalled() {
        installed = InstallerCore.readInstalled(InstallerCore.defaultGameDir(minecraftDir));
    }

    // ------------------------------------------------------------------ input

    private String hit(Point p) {
        for (var e : hits.entrySet()) if (e.getValue().contains(p)) return e.getKey();
        return null;
    }

    private void updateHover(Point p) {
        hovered = hit(p);
        boolean enabled = hovered != null && !(hovered.equals("install") && release == null);
        setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    }

    private void click(String id) {
        if (id == null) return;
        Window w = SwingUtilities.getWindowAncestor(this);
        switch (id) {
            case "close" -> { if (state != State.INSTALLING) System.exit(0); }
            case "min" -> { if (w instanceof java.awt.Frame f) f.setState(java.awt.Frame.ICONIFIED); }
            case "change" -> {
                JFileChooser fc = new JFileChooser(minecraftDir.toFile());
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fc.setDialogTitle("Choose your .minecraft folder");
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    minecraftDir = fc.getSelectedFile().toPath();
                    refreshInstalled();
                    onFolderChanged.accept(minecraftDir);
                }
            }
            case "copy" -> copySettings = !copySettings;
            case "voice" -> voiceChat = !voiceChat;
            case "perf" -> performanceMods = !performanceMods;
            case "clips" -> installClips = !installClips;
            case "install", "retry" -> { if (release != null) onInstall.run(); }
            case "open" -> onOpenLauncher.run();
            case "done-close" -> System.exit(0);
            default -> {
                if (id.startsWith("mem")) memoryGb = Integer.parseInt(id.substring(3));
            }
        }
        repaint();
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        hits.clear();
        paintBackground(g);
        paintTitleBar(g);
        paintLeft(g);
        paintCard(g);
        g.dispose();
    }

    private void paintBackground(Graphics2D g) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(560, 90, 420, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, 70), new Color(225, 29, 46, 0)}));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(80, 470, 300, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, 28), new Color(225, 29, 46, 0)}));
        g.fillRect(0, 0, W, H);
        // faint grid, fading out from the top
        for (int x = 0; x < W; x += 40) {
            g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 10), 0, H, new Color(255, 255, 255, 0)));
            g.fillRect(x, 0, 1, H);
        }
        for (int y = 0; y < H; y += 40) {
            g.setColor(new Color(255, 255, 255, Math.max(0, 10 - y / 40)));
            g.fillRect(0, y, W, 1);
        }
        g.setColor(new Color(255, 255, 255, 18));
        g.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, 22, 22));
    }

    private void paintTitleBar(Graphics2D g) {
        button(g, "min", W - 74, 12, 28, 26, "–", false);
        button(g, "close", W - 42, 12, 28, 26, "✕", true);
    }

    private void button(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger) {
        float t = anim(id, id.equals(hovered));
        g.setColor(mix(new Color(255, 255, 255, 0), danger ? RED : new Color(255, 255, 255, 30), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 8, 8));
        // Icons are drawn with lines (fonts don't reliably include the glyphs).
        g.setColor(mix(DIM, TEXT, t));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double cx = x + w / 2.0, cy = y + h / 2.0;
        if (danger) {
            g.draw(new java.awt.geom.Line2D.Double(cx - 4.5, cy - 4.5, cx + 4.5, cy + 4.5));
            g.draw(new java.awt.geom.Line2D.Double(cx + 4.5, cy - 4.5, cx - 4.5, cy + 4.5));
        } else {
            g.draw(new java.awt.geom.Line2D.Double(cx - 5, cy, cx + 5, cy));
        }
        g.setStroke(new BasicStroke(1f));
        hits.put(id, new Rectangle(x, y, w, h));
    }

    private void paintLeft(Graphics2D g) {
        int x = 44;
        // logo lockup
        g.drawImage(mark, x, 64, 54, 54, null);
        int wh = 62, ww = Math.round(wh * 320f / 96f);
        g.drawImage(word, x + 60, 64 + (54 - wh) / 2 + 2, ww, wh, null);
        g.setFont(interBold.deriveFont(9.5f));
        g.setColor(FAINT);
        spaced(g, "INSTALLER", x + 64, 130, 3.2f);

        g.setFont(interBold.deriveFont(33f));
        g.setColor(TEXT);
        g.drawString("Evade Client,", x, 196);
        g.setColor(RED);
        g.drawString("one click away.", x, 236);

        g.setFont(inter.deriveFont(13.5f));
        g.setColor(DIM);
        String ver = release != null ? "v" + release.evadeVersion() : "the latest version";
        wrap(g, "Installs Fabric, Fabric API and Evade Client " + ver + " in their own folder, then adds an "
                + "Evade Client profile to your Minecraft Launcher.", x, 270, 320, 21);

        // version chips
        int cx = x;
        String[] chips = release != null
                ? new String[]{"Minecraft " + InstallerCore.MC_VERSION, "Fabric " + release.loaderVersion(), "Fabric API " + shortApi(release.fabricApiVersion())}
                : new String[]{"Minecraft " + InstallerCore.MC_VERSION, releaseError != null ? "Offline" : "Checking versions…"};
        g.setFont(inter.deriveFont(11.5f));
        for (String c : chips) {
            int w = g.getFontMetrics().stringWidth(c) + 22;
            g.setColor(new Color(255, 255, 255, 10));
            g.fill(new RoundRectangle2D.Double(cx, 352, w, 26, 26, 26));
            g.setColor(BORDER);
            g.draw(new RoundRectangle2D.Double(cx + .5, 352.5, w - 1, 25, 25, 25));
            g.setColor(DIM);
            center(g, c, cx, 352, w, 26);
            cx += w + 8;
        }

        g.setFont(inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString("evadeclient.com", x, H - 34);
    }

    private void paintCard(Graphics2D g) {
        int x = 420, y = 60, w = 316, h = 376;
        // shadow + card
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(0, 0, 0, 22));
            g.fill(new RoundRectangle2D.Double(x - i, y - i + 6, w + 2 * i, h + 2 * i, 22 + i, 22 + i));
        }
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 18, 18));
        g.setColor(RED);
        g.fill(new RoundRectangle2D.Double(x + 24, y, 46, 2, 2, 2));

        int ix = x + 24, iw = w - 48;
        switch (state) {
            case IDLE -> paintIdle(g, ix, y, iw, h);
            case INSTALLING -> paintInstalling(g, ix, y, iw, h);
            case DONE -> paintDone(g, ix, y, iw, h);
            case ERROR -> paintError(g, ix, y, iw, h);
        }
    }

    private void paintIdle(Graphics2D g, int x, int y, int w, int h) {
        label(g, "MINECRAFT FOLDER", x, y + 34);
        box(g, x, y + 44, w, 38);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(ellipsize(g, minecraftDir.toString(), w - 92), x + 12, y + 67);
        float hc = anim("change", "change".equals(hovered));
        g.setColor(mix(CARD_2.brighter(), RED, hc));
        g.fill(new RoundRectangle2D.Double(x + w - 72, y + 50, 64, 26, 8, 8));
        g.setFont(interBold.deriveFont(11.5f));
        g.setColor(TEXT);
        center(g, "Change", x + w - 72, y + 50, 64, 26);
        hits.put("change", new Rectangle(x + w - 72, y + 50, 64, 26));

        label(g, "MEMORY", x, y + 112);
        int[] opts = {2, 4, 6, 8};
        int sw = (w - 3 * 6) / 4;
        for (int i = 0; i < opts.length; i++) {
            int sx = x + i * (sw + 6);
            String id = "mem" + opts[i];
            boolean on = memoryGb == opts[i];
            float t = anim(id, id.equals(hovered));
            g.setColor(on ? RED : mix(CARD_2, new Color(0x22222A), t));
            g.fill(new RoundRectangle2D.Double(sx, y + 122, sw, 34, 9, 9));
            if (!on) {
                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Double(sx + .5, y + 122.5, sw - 1, 33, 9, 9));
            }
            g.setFont(interBold.deriveFont(12.5f));
            g.setColor(on ? Color.WHITE : DIM);
            center(g, opts[i] + " GB", sx, y + 122, sw, 34);
            hits.put(id, new Rectangle(sx, y + 122, sw, 34));
        }

        // settings toggle row
        g.setFont(inter.deriveFont(13f));
        g.setColor(TEXT);
        g.drawString("Copy my settings & servers", x, y + 189);
        float on = anim("copy-on", copySettings);
        int tx = x + w - 38, ty = y + 176;
        g.setColor(mix(new Color(0x2A2A31), RED, on));
        g.fill(new RoundRectangle2D.Double(tx, ty, 38, 20, 20, 20));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(tx + 3 + on * 18, ty + 3, 14, 14));
        hits.put("copy", new Rectangle(x, y + 172, w, 28));

        g.setFont(inter.deriveFont(13f));
        g.setColor(TEXT);
        g.drawString("Voice chat (Simple Voice Chat)", x, y + 219);
        float von = anim("voice-on", voiceChat);
        int vy = y + 206;
        g.setColor(mix(new Color(0x2A2A31), RED, von));
        g.fill(new RoundRectangle2D.Double(tx, vy, 38, 20, 20, 20));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(tx + 3 + von * 18, vy + 3, 14, 14));
        hits.put("voice", new Rectangle(x, y + 202, w, 28));

        g.setFont(inter.deriveFont(13f));
        g.setColor(TEXT);
        g.drawString("FPS mods (Sodium & more)", x, y + 249);
        float pon = anim("perf-on", performanceMods);
        int py = y + 236;
        g.setColor(mix(new Color(0x2A2A31), RED, pon));
        g.fill(new RoundRectangle2D.Double(tx, py, 38, 20, 20, 20));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(tx + 3 + pon * 18, py + 3, 14, 14));
        hits.put("perf", new Rectangle(x, y + 232, w, 28));

        g.setFont(inter.deriveFont(13f));
        g.setColor(TEXT);
        g.drawString("Evade Clips (record & edit clips)", x, y + 279);
        float con = anim("clips-on", installClips);
        int cy = y + 266;
        g.setColor(mix(new Color(0x2A2A31), RED, con));
        g.fill(new RoundRectangle2D.Double(tx, cy, 38, 20, 20, 20));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(tx + 3 + con * 18, cy + 3, 14, 14));
        hits.put("clips", new Rectangle(x, y + 262, w, 28));

        // status line
        g.setFont(inter.deriveFont(12f));
        String info;
        Color infoColor = DIM;
        if (releaseError != null) { info = "Can't reach evadeclient.com. Check your connection."; infoColor = RED_HI; }
        else if (release == null) info = "Checking for the latest version…";
        else if (installed == null) info = "Evade isn't installed in this folder yet.";
        else if (!release.evadeVersion().equals(installed.evadeVersion())) { info = "Update available: v" + installed.evadeVersion() + " → v" + release.evadeVersion(); infoColor = RED_HI; }
        else info = "v" + installed.evadeVersion() + " is installed and up to date.";
        g.setColor(infoColor);
        g.fill(new Ellipse2D.Double(x, y + 298, 6, 6));
        g.drawString(ellipsize(g, info, w - 14), x + 14, y + 305);

        String label = installed == null ? "Install Evade Client"
                : release != null && !release.evadeVersion().equals(installed.evadeVersion()) ? "Update Evade Client" : "Reinstall";
        bigButton(g, "install", x, y + h - 84, w, 50, label, release != null);
        g.setFont(inter.deriveFont(11f));
        g.setColor(FAINT);
        center(g, "Requires a Minecraft Java Edition account", x, y + h - 30, w, 14);
    }

    private void paintInstalling(Graphics2D g, int x, int y, int w, int h) {
        label(g, "INSTALLING", x, y + 34);
        g.setFont(interBold.deriveFont(46f));
        g.setColor(TEXT);
        g.drawString(Math.round(shownProgress * 100) + "%", x - 2, y + 96);
        // bar
        g.setColor(new Color(0x24242B));
        g.fill(new RoundRectangle2D.Double(x, y + 114, w, 6, 6, 6));
        int bw = (int) Math.max(6, w * shownProgress);
        g.setPaint(new GradientPaint(x, 0, new Color(0x9B0D1A), x + bw, 0, RED_HI));
        g.fill(new RoundRectangle2D.Double(x, y + 114, bw, 6, 6, 6));
        // moving shine
        float s = ((System.currentTimeMillis() - start) % 1400) / 1400f;
        g.setPaint(new GradientPaint(x + bw * s - 30, 0, new Color(255, 255, 255, 0), x + bw * s, 0, new Color(255, 255, 255, 90), true));
        g.setClip(new RoundRectangle2D.Double(x, y + 114, bw, 6, 6, 6));
        g.fillRect(x, y + 114, bw, 6);
        g.setClip(null);

        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsize(g, status, w), x, y + 146);

        boolean clips = installClips && release != null && release.clipsUrl() != null;
        String[] steps = clips ? new String[]{"Fabric Loader", "Fabric API", "Evade Client", "Evade Clips", "Launcher profile"}
                : new String[]{"Fabric Loader", "Fabric API", "Evade Client", "Launcher profile"};
        double[] at = clips ? new double[]{0.15, 0.55, 0.8, 0.96, 1.0} : new double[]{0.15, 0.55, 0.8, 1.0};
        int sy = y + 176;
        for (int i = 0; i < steps.length; i++) {
            boolean done = progress >= at[i];
            boolean active = !done && (i == 0 || progress >= at[i - 1]);
            stepRow(g, x, sy, steps[i], done, active);
            sy += clips ? 30 : 34;
        }
    }

    private void paintDone(Graphics2D g, int x, int y, int w, int h) {
        // check badge
        int cx = x + w / 2, cy = y + 70;
        g.setColor(new Color(225, 29, 46, 40));
        g.fill(new Ellipse2D.Double(cx - 34, cy - 34, 68, 68));
        g.setColor(RED);
        g.fill(new Ellipse2D.Double(cx - 24, cy - 24, 48, 48));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D check = new Path2D.Double();
        check.moveTo(cx - 10, cy + 1);
        check.lineTo(cx - 3, cy + 8);
        check.lineTo(cx + 11, cy - 7);
        g.draw(check);
        g.setStroke(new BasicStroke(1f));

        g.setFont(interBold.deriveFont(20f));
        g.setColor(TEXT);
        center(g, "You're all set", x, y + 118, w, 26);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        center(g, "Evade Client v" + (release != null ? release.evadeVersion() : "") + " is installed.", x, y + 144, w, 18);

        String[] steps = {"Open the Minecraft Launcher", "Pick \"Evade Client\" next to Play", "Press Right Shift in game"};
        int sy = y + 182;
        for (int i = 0; i < steps.length; i++) {
            g.setColor(CARD_2);
            g.fill(new RoundRectangle2D.Double(x, sy, w, 30, 9, 9));
            g.setFont(interBold.deriveFont(11f));
            g.setColor(RED_HI);
            g.drawString("0" + (i + 1), x + 12, sy + 19);
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(steps[i], x + 38, sy + 19);
            sy += 36;
        }
        bigButton(g, "open", x, y + h - 84, w, 50, "Open Minecraft Launcher", true);
        g.setFont(inter.deriveFont(12f));
        float t = anim("done-close", "done-close".equals(hovered));
        g.setColor(mix(FAINT, TEXT, t));
        center(g, "Close", x, y + h - 30, w, 14);
        hits.put("done-close", new Rectangle(x + w / 2 - 30, y + h - 32, 60, 18));
    }

    private void paintError(Graphics2D g, int x, int y, int w, int h) {
        int cx = x + w / 2, cy = y + 70;
        g.setColor(new Color(225, 29, 46, 40));
        g.fill(new Ellipse2D.Double(cx - 34, cy - 34, 68, 68));
        g.setColor(RED);
        g.fill(new Ellipse2D.Double(cx - 24, cy - 24, 48, 48));
        g.setFont(interBold.deriveFont(28f));
        g.setColor(Color.WHITE);
        center(g, "!", cx - 24, cy - 24, 48, 48);
        g.setFont(interBold.deriveFont(19f));
        g.setColor(TEXT);
        center(g, "Install didn't finish", x, y + 118, w, 26);
        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        wrap(g, error, x, y + 166, w, 19);
        bigButton(g, "retry", x, y + h - 84, w, 50, "Try again", true);
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
                g.draw(new java.awt.geom.Arc2D.Double(x + 1, y + 1, 18, 18, -a, 100, java.awt.geom.Arc2D.OPEN));
            }
            g.setStroke(new BasicStroke(1f));
        }
        g.setFont(inter.deriveFont(13f));
        g.setColor(done || active ? TEXT : FAINT);
        g.drawString(name, x + 32, y + 15);
    }

    private void bigButton(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean enabled) {
        float t = enabled ? anim(id, id.equals(hovered)) : 0;
        if (enabled) {
            // glow
            g.setPaint(new RadialGradientPaint(x + w / 2f, y + h, w * 0.6f, new float[]{0f, 1f},
                    new Color[]{new Color(225, 29, 46, (int) (70 + 60 * t)), new Color(225, 29, 46, 0)}));
            g.fillRect(x - 40, y - 10, w + 80, h + 40);
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
        hits.put(id, new Rectangle(x, y, w, h));
    }

    private void box(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(0x0C0C0F));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 10, 10));
    }

    private void label(Graphics2D g, String s, int x, int y) {
        g.setFont(interBold.deriveFont(9.5f));
        g.setColor(FAINT);
        spaced(g, s, x, y, 1.6f);
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
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(test) > w && !line.isEmpty()) {
                g.drawString(line.toString(), x, y);
                y += lh;
                line = new StringBuilder(word);
            } else line = new StringBuilder(test);
        }
        if (!line.isEmpty()) g.drawString(line.toString(), x, y);
    }

    private static String ellipsize(Graphics2D g, String s, int w) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) return s;
        // keep the end of paths visible: "...\.minecraft"
        String e = "…";
        for (int i = 1; i < s.length(); i++) {
            String t = e + s.substring(i);
            if (fm.stringWidth(t) <= w) return t;
        }
        return e;
    }

    private static String shortApi(String v) {
        int plus = v.indexOf('+');
        return plus > 0 ? v.substring(0, plus) : v;
    }

    // ------------------------------------------------------------------ misc

    private float anim(String id, boolean target) {
        float v = hover.getOrDefault(id, target ? 1f : 0f);
        v += ((target ? 1f : 0f) - v) * 0.22f;
        hover.put(id, v);
        return v;
    }

    private static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private static Font font(String path) {
        try (InputStream in = InstallerView.class.getResourceAsStream(path)) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }

    static Image image(String path) {
        try (InputStream in = InstallerView.class.getResourceAsStream(path)) {
            return ImageIO.read(in);
        } catch (Exception e) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
    }

    /** Render the current state to an image (used for screenshots and tests). */
    BufferedImage snapshot(float scale) {
        BufferedImage img = new BufferedImage(Math.round(W * scale), Math.round(H * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        g.setComposite(AlphaComposite.SrcOver);
        setSize(W, H);
        shownProgress = progress;
        paint(g);
        g.dispose();
        return img;
    }
}
