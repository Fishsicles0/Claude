package net.evadeclient.installer;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
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
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** The whole installer UI, painted by hand so it matches the client's black and red look. */
public final class InstallerView extends JPanel {
    public static final int W = 780, H = 560;

    enum State { IDLE, INSTALLING, DONE, ERROR }

    // palette
    static final Color BG = new Color(0x0A0A0C), CARD = new Color(0x111115), CARD_2 = new Color(0x17171C),
            BORDER = new Color(0x24242C), RED = new Color(0xE11D2E), RED_HI = new Color(0xFF4153),
            TEXT = new Color(0xF4F4F6), DIM = new Color(0x9B9BA6), FAINT = new Color(0x5E5E69), OK = new Color(0x3DDC84),
            AMBER = new Color(0xF5A524);

    private final Font inter, interBold, chakra;
    private final Image mark, word;

    // state (touched on the EDT only)
    State state = State.IDLE;
    Path minecraftDir = InstallerCore.defaultMinecraftDir();
    /** This PC's memory facts, read once (the options never change while the installer is open). */
    private final int recommendedGb = InstallerCore.recommendedMemory(), totalGb = InstallerCore.totalMemoryGb();
    private final int[] memoryChoices = InstallerCore.memoryOptions();
    int memoryGb = InstallerCore.clampMemory(recommendedGb);

    boolean copySettings = true;
    boolean voiceChat = true;
    boolean performanceMods = true;
    /** Iris + a shader pack. Off by default: shaders look great but cost a lot of FPS. */
    boolean shaders = false;
    /** Also install Evade Clips (instant replay, clip library and editor). */
    boolean installClips = true;
    InstallerCore.Release release;
    String releaseError;
    InstallerCore.Installed installed;
    double progress, shownProgress;
    String status = "";
    String error = "";
    /** Extras the last install left out (shown on the Done screen). */
    List<String> skipped = List.of();
    /** Set when "Open Minecraft Launcher" couldn't start it; shown above the button. */
    String launcherError;
    /** While the launcher is being started (the button shows that and ignores clicks). */
    boolean openingLauncher;
    final List<String> stepsDone = new ArrayList<>();

    /**
     * Set when the window has per-pixel alpha: the view then paints the rounded window itself, with anti-aliased
     * corners, over a transparent frame. Otherwise the window is cut to the rounded shape (hard-edged corners).
     */
    boolean roundCorners;

    private final Map<String, Rectangle> hits = new HashMap<>();
    private final Map<String, Float> hover = new HashMap<>();
    private String hovered;
    private Point dragFrom;
    private final long start = System.currentTimeMillis();
    /** Set while painting when a hover or toggle animation hasn't settled yet (the timer keeps repainting until it has). */
    private boolean animating;

    /** Static layers, rendered once per device scale: the window background and the card's soft shadow. */
    private BufferedImage bgLayer, shadowLayer;
    private double bgScale, shadowScale;
    private boolean bgRound;
    private int shadowW, shadowH;
    /** Card shadow: box-blur radius (three passes, close to a Gaussian), offset and strength. */
    private static final int SHADOW_BLUR = 5, SHADOW_PAD = 3 * SHADOW_BLUR + 2, SHADOW_DY = 10;
    private static final float SHADOW_ALPHA = 0.45f;

    Runnable onInstall = () -> {};
    /** Runs the version check again after it failed. */
    Runnable onRetryRelease = () -> {};
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
            @Override public void mouseExited(MouseEvent e) { hovered = null; setCursor(Cursor.getDefaultCursor()); repaint(); }
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
        // Smooth hover/progress animation. Repaints only while something moves: an idle window costs nothing
        // (with per-pixel alpha every repaint re-uploads the whole window).
        new Timer(16, e -> {
            shownProgress += (progress - shownProgress) * 0.18;
            if (animating || state == State.INSTALLING || Math.abs(progress - shownProgress) > 1e-4) repaint();
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
        String was = hovered;
        hovered = hit(p);
        if (!Objects.equals(was, hovered)) repaint();
        boolean enabled = hovered != null && !(hovered.equals("install") && release == null && releaseError == null)
                && !(hovered.equals("open") && openingLauncher);
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
            case "shaders" -> shaders = !shaders;
            case "clips" -> installClips = !installClips;
            case "install", "retry" -> {
                if (release != null) onInstall.run();
                else if (releaseError != null) onRetryRelease.run();
            }
            case "open" -> { if (!openingLauncher) onOpenLauncher.run(); }
            case "done-close" -> System.exit(0);
            case "err-back" -> backToOptions();
            default -> {
                if (id.startsWith("mem")) memoryGb = InstallerCore.clampMemory(Integer.parseInt(id.substring(3)));
            }
        }
        repaint();
    }

    /** From the error screen back to the options, as the player left them. */
    void backToOptions() {
        state = State.IDLE;
        progress = 0;
        shownProgress = 0;
        error = "";
        status = "";
        skipped = List.of();
        launcherError = null;
        refreshInstalled();
    }

    /** Whether each extra can be installed with the release we found (all of them while it's still unknown). */
    private boolean voiceAvailable() { return release == null || release.voiceChat() != null; }
    private boolean perfAvailable() { return release == null || (release.performance() != null && !release.performance().isEmpty()); }
    private boolean shadersAvailable() { return release == null || release.iris() != null; }
    private boolean clipsAvailable() { return release == null || release.clipsUrl() != null; }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Greyscale text AA: right on a per-pixel-alpha window (LCD AA needs an opaque background) and no colour fringes.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        hits.clear();
        animating = false;
        paintBackground(g);
        paintTitleBar(g);
        paintLeft(g);
        paintCard(g);
        g.dispose();
    }

    /** The window background (it never changes, so it's rendered once per device scale and then just copied). */
    private void paintBackground(Graphics2D g) {
        double scale = g.getTransform().getScaleX();
        if (bgLayer == null || bgScale != scale || bgRound != roundCorners) {
            bgLayer = deviceImage(W, H, scale);
            Graphics2D b = bgLayer.createGraphics();
            b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            b.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            b.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            b.scale(bgLayer.getWidth() / (double) W, bgLayer.getHeight() / (double) H);
            paintBackgroundLayer(b, roundCorners);
            b.dispose();
            bgScale = scale;
            bgRound = roundCorners;
        }
        g.drawImage(bgLayer, 0, 0, W, H, null);
    }

    /**
     * With {@code round}, the three fills cover an anti-aliased rounded rectangle and the corners stay transparent
     * (the window has per-pixel alpha); otherwise they cover the whole rectangle and the window is cut to shape.
     */
    private static void paintBackgroundLayer(Graphics2D g, boolean round) {
        Shape win = round ? new RoundRectangle2D.Double(0, 0, W, H, 22, 22) : new Rectangle(0, 0, W, H);
        g.setColor(BG);
        g.fill(win);
        g.setPaint(new RadialGradientPaint(560, 90, 420, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, 70), new Color(225, 29, 46, 0)}));
        g.fill(win);
        g.setPaint(new RadialGradientPaint(80, 470, 300, new float[]{0f, 1f},
                new Color[]{new Color(225, 29, 46, 28), new Color(225, 29, 46, 0)}));
        g.fill(win);
        // Everything on top keeps the window's own alpha (SrcAtop), so nothing shows in the transparent corners.
        g.setComposite(AlphaComposite.SrcAtop);
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
        g.draw(new RoundRectangle2D.Double(0.5, 0.5, W - 1, H - 1, 21, 21));
        g.setComposite(AlphaComposite.SrcOver);
    }

    /** An empty image covering {@code w}×{@code h} user units at {@code scale} device pixels per unit. */
    private static BufferedImage deviceImage(int w, int h, double scale) {
        return new BufferedImage(Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)),
                BufferedImage.TYPE_INT_ARGB_PRE);
    }

    /**
     * The card's drop shadow: a black rounded rectangle softened by three passes of a separable box blur, built
     * once per card size and device scale and drawn at {@link #SHADOW_ALPHA}, {@link #SHADOW_DY} px lower.
     */
    private void paintCardShadow(Graphics2D g, int x, int y, int w, int h) {
        double scale = g.getTransform().getScaleX();
        int pad = SHADOW_PAD;
        if (shadowLayer == null || shadowScale != scale || shadowW != w || shadowH != h) {
            BufferedImage img = deviceImage(w + 2 * pad, h + 2 * pad, scale);
            Graphics2D s = img.createGraphics();
            s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            s.scale(img.getWidth() / (double) (w + 2 * pad), img.getHeight() / (double) (h + 2 * pad));
            s.setColor(Color.BLACK);
            s.fill(new RoundRectangle2D.Double(pad, pad, w, h, 18, 18));
            s.dispose();
            int r = Math.max(1, (int) Math.round(SHADOW_BLUR * scale));
            float[] k = new float[2 * r + 1];
            Arrays.fill(k, 1f / k.length);
            ConvolveOp across = new ConvolveOp(new Kernel(k.length, 1, k), ConvolveOp.EDGE_NO_OP, null);
            ConvolveOp down = new ConvolveOp(new Kernel(1, k.length, k), ConvolveOp.EDGE_NO_OP, null);
            BufferedImage tmp = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
            try {
                for (int i = 0; i < 3; i++) {
                    across.filter(img, tmp);
                    down.filter(tmp, img);
                }
            } catch (RuntimeException ignored) {
                // No imaging library: the shadow stays crisp rather than missing.
            }
            shadowLayer = img;
            shadowScale = scale;
            shadowW = w;
            shadowH = h;
        }
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, SHADOW_ALPHA));
        g.drawImage(shadowLayer, x - pad, y + SHADOW_DY - pad, w + 2 * pad, h + 2 * pad, null);
        g.setComposite(was);
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
        int x = 420, y = 60, w = 316, h = H - 104;
        // shadow + card
        paintCardShadow(g, x, y, w, h);
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
        // Only amounts this PC can spare; the chips share the row evenly.
        int[] opts = memoryChoices;
        int gap = 6, sw = (w - (opts.length - 1) * gap) / Math.max(1, opts.length);
        for (int i = 0; i < opts.length; i++) {
            int sx = x + i * (sw + gap);
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
        g.setFont(inter.deriveFont(11f));
        g.setColor(DIM);
        String rec = "Recommended for this PC: " + recommendedGb + " GB";
        g.drawString(rec, x, y + 173);
        if (totalGb > 0) {
            g.setColor(FAINT);
            g.drawString("  \u00b7  " + totalGb + " GB RAM", x + g.getFontMetrics().stringWidth(rec), y + 173);
        }

        // option switches (an extra the release doesn't have right now is greyed out and can't be switched)
        String[][] rows = {
                {"copy", "Copy my settings & servers", ""},
                {"voice", "Voice chat (Simple Voice Chat)", "Voice chat"},
                {"perf", "FPS mods (Sodium & more)", "FPS mods"},
                {"shaders", "Shaders (Iris + Photon)", "Shaders"},
                {"clips", "Evade Clips (record & edit clips)", "Evade Clips"}};
        boolean[] values = {copySettings, voiceChat, performanceMods, shaders, installClips};
        boolean[] available = {true, voiceAvailable(), perfAvailable(), shadersAvailable(), clipsAvailable()};
        int tx = x + w - 38;
        for (int i = 0; i < rows.length; i++) {
            int ry = y + 186 + i * 30;
            float on = anim(rows[i][0] + "-on", values[i]);
            if (available[i]) {
                g.setFont(inter.deriveFont(13f));
                g.setColor(TEXT);
                g.drawString(rows[i][1], x, ry + 13);
                // On: the Install button's RED_HI-to-RED gradient (blended in from the off grey as it switches).
                Color off = new Color(0x2A2A31);
                g.setPaint(new GradientPaint(0, ry, mix(off, RED_HI, on), 0, ry + 20, mix(off, RED, on)));
                g.fill(new RoundRectangle2D.Double(tx, ry, 38, 20, 20, 20));
                // knob, on a soft contact shadow
                double kx = tx + 3 + on * 18;
                g.setColor(new Color(0, 0, 0, 50));
                g.fill(new Ellipse2D.Double(kx - 0.5, ry + 3 + 1 - 0.5, 15, 15));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(kx, ry + 3, 14, 14));
                hits.put(rows[i][0], new Rectangle(x, ry - 4, w, 28));
            } else {
                g.setFont(inter.deriveFont(13f));
                g.setColor(FAINT);
                g.drawString(rows[i][2], x, ry + 13);
                int nx = x + g.getFontMetrics().stringWidth(rows[i][2]) + 8;
                g.setFont(inter.deriveFont(11.5f));
                g.drawString(ellipsizeEnd(g, "not available right now", tx - 8 - nx), nx, ry + 13);
                g.setColor(new Color(0x1C1C22));
                g.fill(new RoundRectangle2D.Double(tx, ry, 38, 20, 20, 20));
                g.setColor(BORDER);
                g.draw(new RoundRectangle2D.Double(tx + .5, ry + .5, 37, 19, 19, 19));
                g.setColor(new Color(0x3A3A44));
                g.fill(new Ellipse2D.Double(tx + 3 + on * 18, ry + 3, 14, 14));
            }
        }

        // status line (two lines when it needs them, e.g. which site couldn't be reached)
        g.setFont(inter.deriveFont(12f));
        String info;
        Color infoColor = DIM;
        if (releaseError != null) { info = EvadeInstaller.sentence(releaseError) + " Check your connection and press Retry."; infoColor = RED_HI; }
        else if (release == null) info = "Checking for the latest version\u2026";
        else if (installed == null) info = "Evade isn't installed in this folder yet.";
        else if (!release.evadeVersion().equals(installed.evadeVersion())) { info = "Update available: v" + installed.evadeVersion() + " \u2192 v" + release.evadeVersion(); infoColor = RED_HI; }
        else info = "v" + installed.evadeVersion() + " is installed and up to date.";
        List<String> infoLines = wrapLines(g, info, w - 14, 2);
        int iy = infoLines.size() > 1 ? y + 341 : y + 353;
        g.setColor(infoColor);
        g.fill(new Ellipse2D.Double(x, iy - 7, 6, 6));
        for (String line : infoLines) {
            g.drawString(line, x + 14, iy);
            iy += 16;
        }

        boolean retry = release == null && releaseError != null;
        String label = retry ? "Retry" : installed == null ? "Install Evade Client"
                : release != null && !release.evadeVersion().equals(installed.evadeVersion()) ? "Update Evade Client" : "Reinstall";
        bigButton(g, "install", x, y + h - 84, w, 50, label, release != null || retry);
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
        g.fill(new RoundRectangle2D.Double(x, y + 114, bw, 6, 6, 6));

        g.setFont(inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsize(g, status, w), x, y + 146);

        boolean clips = installClips && release != null && release.clipsUrl() != null;
        boolean extras = (voiceChat && voiceAvailable()) || (performanceMods && perfAvailable()) || (shaders && shadersAvailable());
        List<String> steps = new ArrayList<>(List.of("Fabric Loader", "Fabric API", "Evade Client"));
        List<Double> at = new ArrayList<>(List.of(0.15, 0.55, 0.75));
        if (extras) { steps.add("Extra mods"); at.add(0.83); }
        if (clips) { steps.add("Evade Clips"); at.add(0.96); }
        steps.add("Launcher profile");
        at.add(1.0);
        int sy = y + 176, gap = steps.size() >= 6 ? 28 : steps.size() == 5 ? 30 : 34;
        for (int i = 0; i < steps.size(); i++) {
            boolean done = progress >= at.get(i);
            boolean active = !done && (i == 0 || progress >= at.get(i - 1));
            stepRow(g, x, sy, steps.get(i), done, active);
            sy += gap;
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

        boolean partial = !skipped.isEmpty();
        // The launcher error sits right above the button; everything else fits around it.
        g.setFont(inter.deriveFont(11.5f));
        List<String> errLines = launcherError != null ? wrapLines(g, launcherError, w, 2) : List.of();
        int errTop = y + h - 96 - 15 * Math.max(0, errLines.size() - 1) - 12;
        int bottom = launcherError != null ? errTop - 8 : y + h - 96;

        g.setFont(interBold.deriveFont(20f));
        g.setColor(TEXT);
        center(g, partial ? "Almost all set" : "You're all set", x, y + 118, w, 26);
        int sy, rowH, rowGap;
        if (!partial) {
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(DIM);
            center(g, "Evade Client v" + (release != null ? release.evadeVersion() : "") + " is installed.", x, y + 144, w, 18);
            sy = y + 182;
            rowH = 30;
            rowGap = 36;
        } else {
            g.setFont(inter.deriveFont(12f));
            g.setColor(DIM);
            center(g, "Installed, but some extras were skipped:", x, y + 142, w, 18);
            // Amber lines (fewer when the launcher error needs the room); a long item wraps once, and whatever
            // doesn't fit is counted.
            int ly = y + 180;
            g.setColor(AMBER);
            for (String[] line : skippedLines(g, w - 10, launcherError != null ? 2 : 3)) {
                if (line[1] != null) g.fill(new Ellipse2D.Double(x + 1, ly - 7, 5, 5));
                g.drawString(line[0], x + 10, ly);
                ly += 16;
            }
            boolean busy = skipped.contains(InstallerCore.CLIPS_BUSY);
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(FAINT);
            String footer = busy ? "Quit Evade Clips in the tray, then run this again." : "Run the installer again later to add them.";
            for (String line : wrapLines(g, footer, w, 2)) {
                g.drawString(line, x, ly + 4);
                ly += 15;
            }
            sy = ly + 12;
            // Compact step rows, tighter still (or left out) when space is short.
            int room = bottom - sy;
            rowGap = room >= 3 * 30 - 4 ? 30 : room >= 3 * 26 - 4 ? 26 : 0;
            rowH = rowGap - 4;
        }

        String[] steps = {"Open the Minecraft Launcher", "Pick \"Evade Client\" next to Play", "Press Right Shift in game"};
        for (int i = 0; i < steps.length && rowGap > 0; i++) {
            g.setColor(CARD_2);
            g.fill(new RoundRectangle2D.Double(x, sy, w, rowH, 9, 9));
            int base = sy + rowH / 2 + 4;
            g.setFont(interBold.deriveFont(11f));
            g.setColor(RED_HI);
            g.drawString("0" + (i + 1), x + 12, base);
            g.setFont(inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(steps[i], x + 38, base);
            sy += rowGap;
        }

        if (!errLines.isEmpty()) {
            g.setFont(inter.deriveFont(11.5f));
            g.setColor(RED_HI);
            int ey = errTop;
            for (String line : errLines) {
                center(g, line, x, ey, w, 14);
                ey += 15;
            }
        }
        bigButton(g, "open", x, y + h - 84, w, 50, openingLauncher ? "Opening the launcher\u2026" : "Open Minecraft Launcher", !openingLauncher);
        g.setFont(inter.deriveFont(12f));
        float t = anim("done-close", "done-close".equals(hovered));
        g.setColor(mix(FAINT, TEXT, t));
        center(g, "Close", x, y + h - 30, w, 14);
        hits.put("done-close", new Rectangle(x + w / 2 - 30, y + h - 32, 60, 18));
    }

    /**
     * The skipped extras as lines of at most {@code width}: each item gets a bullet (marked by a non-null second
     * element) and wraps once if it must; when they don't all fit in {@code budget} lines, the last line counts the rest.
     */
    private List<String[]> skippedLines(Graphics2D g, int width, int budget) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < skipped.size(); i++) {
            String item = skipped.get(i).equals(InstallerCore.CLIPS_BUSY) ? "Evade Clips wasn't updated (it may be open)" : skipped.get(i);
            int rest = skipped.size() - i;
            int room = budget - out.size() - (rest > 1 ? 1 : 0);
            List<String> lines = wrapLines(g, item, width, 2);
            // The first and the last item always show (cut short if need be); others give way to "and N more".
            if (lines.size() > room && room >= 1 && (out.isEmpty() || rest == 1)) lines = wrapLines(g, item, width, room);
            if (lines.size() > room || room <= 0) {
                out.add(new String[]{"and " + rest + " more", "\u2022"});
                break;
            }
            for (int j = 0; j < lines.size(); j++) out.add(new String[]{lines.get(j), j == 0 ? "\u2022" : null});
        }
        return out;
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
        int ey = y + 166;
        for (String line : wrapLines(g, error, w, 8)) {
            g.drawString(line, x, ey);
            ey += 19;
        }
        bigButton(g, "retry", x, y + h - 84, w, 50, "Try again", true);
        g.setFont(inter.deriveFont(12f));
        float t = anim("err-back", "err-back".equals(hovered));
        g.setColor(mix(FAINT, TEXT, t));
        center(g, "Change options", x, y + h - 30, w, 14);
        int lw = g.getFontMetrics().stringWidth("Change options") + 16;
        hits.put("err-back", new Rectangle(x + (w - lw) / 2, y + h - 32, lw, 18));
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

    /** Word-wraps text to at most maxLines lines of width w; the last line ends in "…" if text is left over. */
    static List<String> wrapLines(Graphics2D g, String text, int w, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String test = line.isEmpty() ? words[i] : line + " " + words[i];
            if (fm.stringWidth(test) > w && !line.isEmpty()) {
                if (lines.size() == maxLines - 1) break;
                lines.add(line.toString());
                line = new StringBuilder(words[i]);
            } else line = new StringBuilder(test);
        }
        if (i < words.length) {
            StringBuilder rest = new StringBuilder(line);
            for (; i < words.length; i++) rest.append(' ').append(words[i]);
            lines.add(ellipsizeEnd(g, rest.toString(), w, true));
        } else if (!line.isEmpty()) {
            lines.add(ellipsizeEnd(g, line.toString(), w));
        }
        return lines;
    }

    /** Cuts the end off text that's wider than w, ending it in "…". */
    static String ellipsizeEnd(Graphics2D g, String s, int w) {
        return ellipsizeEnd(g, s, w, false);
    }

    private static String ellipsizeEnd(Graphics2D g, String s, int w, boolean forceDots) {
        FontMetrics fm = g.getFontMetrics();
        if (!forceDots && fm.stringWidth(s) <= w) return s;
        String e = "\u2026";
        for (int i = s.length(); i > 0; i--) {
            String t = s.substring(0, i).stripTrailing() + e;
            if (fm.stringWidth(t) <= w && (forceDots || i < s.length())) return t;
        }
        return e;
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
        float to = target ? 1f : 0f;
        float v = hover.getOrDefault(id, to);
        v += (to - v) * 0.22f;
        // Settle exactly on the target, so the timer can stop repainting.
        if (Math.abs(to - v) < 0.002f) v = to;
        else animating = true;
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
        // The image has alpha, so it shows the window as it looks with per-pixel alpha: smooth, see-through corners.
        boolean was = roundCorners;
        roundCorners = true;
        paint(g);
        roundCorners = was;
        g.dispose();
        return img;
    }
}
