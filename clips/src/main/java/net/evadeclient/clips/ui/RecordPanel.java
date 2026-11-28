package net.evadeclient.clips.ui;

import net.evadeclient.clips.ClipsApp;
import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.CaptureSource;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.Ffmpeg;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Capture, kept simple: one status card (the replay buffer as a ring, the two big actions with their keys), the
 * replay length, three quality presets, sound switches and the latest captures as thumbnails. Every detailed
 * setting (frame rate, codec, encoder, bitrates, devices) is still here, folded away under "Advanced settings".
 */
public final class RecordPanel extends JPanel {
    static final Icon REC_ICON = Theme.icon(Theme.Glyph.RECORD, 16, Theme.RED), STOP_ICON = Theme.icon(Theme.Glyph.STOP, 16, Theme.RED);
    private static final String AUTO_ENCODER = "Automatic (best available)", DETECTING = "Detecting encoders…";
    private static final int MAX_W = 1120;

    private final App app;
    private Hero hero;
    private final JComboBox<String> fps = new JComboBox<>(new String[]{"30", "60", "120", "144", "165", "240"});
    private final JComboBox<String> res = new JComboBox<>(new String[]{"Native", "2160p (4K)", "1440p", "1080p", "720p"});
    private final JComboBox<String> quality = new JComboBox<>(new String[]{"Small files", "Balanced", "Best quality"});
    private final JComboBox<String> encoder = new JComboBox<>();
    private final JComboBox<String> codec = new JComboBox<>(new String[]{"Auto (smallest that's fast)", "H.264 (plays everywhere)", "HEVC (≈40% smaller)"});
    private final JLabel dataRate = Theme.label(" ", 12f, false, Theme.DIM);
    private static final double[] REPLAY_RATES = {0, 4, 6, 8, 12, 16, 25, 40, 60};
    private static final double[] RECORD_RATES = {0, 6, 8, 12, 16, 25, 40, 60, 80};
    private final JComboBox<String> replayRate = new JComboBox<>(rates(REPLAY_RATES, "Automatic (from quality)"));
    private final JComboBox<String> recordRate = new JComboBox<>(rates(RECORD_RATES, "Same as instant replay"));

    private static String[] rates(double[] r, String zero) {
        String[] out = new String[r.length];
        for (int i = 0; i < r.length; i++) out[i] = r[i] == 0 ? zero : (int) r[i] + " Mbit/s  (≈" + Math.round(r[i] * 60 / 8) + " MB/min)";
        return out;
    }
    private final JCheckBox cursor = new JCheckBox("Capture mouse cursor");
    private final JCheckBox sysAudio = new Switch("Game sound");
    private final JComboBox<String> sysDevice = new JComboBox<>();
    /** Windows: the game's own sound on the Game track, Discord, music and the rest on their own. */
    private final JCheckBox gameOnly = new JCheckBox("Game sound on its own track (Discord and music go on another)");
    private final JCheckBox mic = new Switch("Microphone");
    private final JComboBox<String> micDevice = new JComboBox<>();
    private final JSlider micVol = new JSlider(0, 200, 100);
    private final JCheckBox voice = new Switch("Voice chat");
    private final JComboBox<String> voiceDevice = new JComboBox<>();
    /** Replay lengths offered (a length set elsewhere is added to the list). */
    private static final int[] REPLAY_LENS = {15, 30, 60, 120, 300, 600};
    private final List<Integer> lenValues = new ArrayList<>();
    private final Segmented replayLen = new Segmented("Replay length");
    private static final int[] POST_ROLLS = {0, 3, 5, 10};
    private final Segmented postRoll = new Segmented("Keep recording after the key");
    private final JLabel warn = Theme.label(" ", 12f, false, Theme.GOLD);
    /** "New capture settings apply when this recording stops". */
    private final JLabel restartNote = Theme.label(" ", 12f, false, Theme.DIM);
    private boolean pendingNote;
    private final JPanel recent = new JPanel(new GridLayout(1, 4, 14, 0));
    private String recentKey = "";
    private final Timer restartLater;
    private boolean loading;
    private Map<String, String> devices = Map.of();
    /** No desktop-audio device was found (Windows / macOS), so "Default" records no game sound. */
    private boolean noDefaultSys, noDefaultMic;
    private boolean devicesLoaded;
    /** Cached: checking for ffmpeg looks through folders, too slow for every repaint. */
    private volatile boolean ffmpegOk = Ffmpeg.available();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "record-page");
        t.setDaemon(true);
        return t;
    });
    private int rateGen;
    /** The encoder list has been filled (until then the saved encoder is left alone). */
    private boolean encodersReady;
    private final Body body = new Body();
    private final List<Preset> presets = new ArrayList<>();
    private final JPanel advanced = new JPanel();
    private JButton advancedToggle;

    /** The three quality presets: what most people need, in words. */
    private record PresetSpec(String name, String blurb, int fps, int resolution, int quality) {}
    private static final List<PresetSpec> PRESETS = List.of(
            new PresetSpec("Smooth", "Lightest on your PC and disk", 30, 720, 1),
            new PresetSpec("Balanced", "Sharp 1080p, smooth 60 fps", 60, 1080, 2),
            new PresetSpec("Sharp", "Crisp 1440p for montages", 60, 1440, 3));

    public RecordPanel(App app) {
        super(new BorderLayout());
        this.app = app;
        hero = new Hero();
        setBackground(Theme.PANEL);
        restartLater = new Timer(700, e -> new Thread(() -> {
            try {
                if (app.engine.isRecording()) { app.engine.restart(); return; } // started meanwhile: applied when it stops
                boolean wasLive = app.engine.isLive();
                app.engine.restart();
                if (wasLive && app.settings.showToast && app.toast != null)
                    SwingUtilities.invokeLater(() -> app.toast.show("Capture restarted with new settings", "The replay buffer was cleared", false));
            } catch (Exception ex) { app.notify("Capture restart failed", ex.getMessage(), true); }
        }, "capture-restart").start());
        restartLater.setRepeats(false);

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Theme.PANEL);
        Theme.pad(body, ClipsApp.hosted ? 18 : 22, 26, 30, 26);
        if (!ClipsApp.hosted) {
            // In the Evade Launcher the section's own tabs already say "Record".
            JLabel title = Theme.label("Record", 26f, true, Theme.TEXT);
            title.setAlignmentX(LEFT_ALIGNMENT);
            body.add(title);
            body.add(Box.createVerticalStrut(16));
        }
        hero.setAlignmentX(LEFT_ALIGNMENT);
        body.add(hero);
        warn.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(warn, 10, 4, 0, 0);
        body.add(warn);
        restartNote.setAlignmentX(LEFT_ALIGNMENT);
        restartNote.setVisible(false);
        Theme.pad(restartNote, 4, 4, 0, 0);
        body.add(restartNote);

        body.add(gap(18));
        body.add(replayCard());
        body.add(gap(18));
        body.add(qualityCard());
        body.add(gap(18));
        body.add(soundCard());
        body.add(gap(18));
        body.add(advancedCard());
        body.add(gap(18));
        body.add(recentCard());

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        add(sp, BorderLayout.CENTER);
        sp.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { relayout(sp.getViewport().getWidth()); }
        });

        load();
        wire();
        app.engine.onStatus(s -> SwingUtilities.invokeLater(() -> { hero.refreshControls(); hero.repaint(); }));
        app.library.onChange(() -> SwingUtilities.invokeLater(this::refreshRecent));
        app.onCaptureSettingsChanged(this::load);
        app.onFfmpegChanged(() -> { ffmpegOk = Ffmpeg.available(); refreshEncoders(); new Thread(this::loadDevices, "audio-devices").start(); hero.refreshControls(); hero.repaint(); });
        new Thread(this::loadDevices, "audio-devices").start();
        refreshRecent();
        hero.refreshControls();
    }

    private static java.awt.Component gap(int h) { return Box.createVerticalStrut(h); }

    /** The page body: as wide as the window, scrolling only up and down. */
    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(20, r.height - 40); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    private void relayout(int viewportWidth) {
        // A comfortable reading width on big windows, centred; the full width on small ones.
        int side = Math.max(26, (viewportWidth - MAX_W) / 2);
        Theme.pad(body, ClipsApp.hosted ? 18 : 22, side, 30, side);
        hero.setCompact(viewportWidth - 2 * side < 860);
        recent.setLayout(new GridLayout(1, viewportWidth - 2 * side < 700 ? 2 : 4, 14, 0));
        body.revalidate();
    }

    // ------------------------------------------------------------------ cards

    /** A card with a title and a one-line explanation under it. */
    private JPanel section(String title, String sub) {
        JPanel c = Theme.roundCard(null);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(c, 18, 20, 20, 20);
        JLabel t = Theme.label(title, 16f, true, Theme.TEXT);
        t.setAlignmentX(LEFT_ALIGNMENT);
        c.add(t);
        if (sub != null) {
            JLabel s = Theme.label(sub, 12.5f, false, Theme.DIM);
            s.setAlignmentX(LEFT_ALIGNMENT);
            Theme.pad(s, 3, 0, 0, 0);
            c.add(s);
        }
        c.add(Box.createVerticalStrut(14));
        return c;
    }

    private static <T extends JComponent> T left(T c) { c.setAlignmentX(LEFT_ALIGNMENT); return c; }

    private JPanel replayCard() {
        JPanel c = section("Instant replay", "Always keeping the last moments ready, so you never miss a play. Saving is instant.");
        JPanel row = new JPanel(new SettingsPanel.WrapLayout());
        row.setOpaque(false);
        row.add(labelled("Keep the last", replayLen));
        row.add(Box.createHorizontalStrut(18));
        postRoll.setOptions(List.of("Off", "+3 s", "+5 s", "+10 s"));
        postRoll.setToolTipText("Keep capturing this long after you press the key, so the clip shows what happens next");
        row.add(labelled("Then keep going", postRoll));
        c.add(left(row));
        return c;
    }

    private static JPanel labelled(String label, JComponent c) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = Theme.label(label.toUpperCase(), 10.5f, true, Theme.FAINT);
        l.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(l, 0, 2, 7, 6);
        p.add(l);
        c.setAlignmentX(LEFT_ALIGNMENT);
        p.add(c);
        return p;
    }

    private JPanel qualityCard() {
        JPanel c = section("Quality", "Pick one. You can fine-tune everything under Advanced settings.");
        JPanel grid = new JPanel(new GridLayout(1, 3, 14, 0));
        grid.setOpaque(false);
        for (PresetSpec p : PRESETS) {
            Preset tile = new Preset(p);
            presets.add(tile);
            grid.add(tile);
        }
        c.add(left(grid));
        dataRate.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(dataRate, 12, 2, 0, 0);
        c.add(dataRate);
        return c;
    }

    private JPanel soundCard() {
        JPanel c = section("Sound", "What goes into your clips. Each one is its own track, so you can mute any of them later.");
        c.add(left(soundRow(sysAudio, Theme.Glyph.VOLUME, "Game sound", "Everything you hear in Minecraft")));
        c.add(left(divider()));
        c.add(left(soundRow(mic, Theme.Glyph.MIC, "Microphone", "Your voice, for commentary and callouts")));
        c.add(left(divider()));
        c.add(left(soundRow(voice, Theme.Glyph.HEADSET, "Voice chat", "Friends on Discord, on a track of its own (pick its device under Advanced)")));
        return c;
    }

    private static JComponent divider() {
        JPanel d = new JPanel() {
            @Override protected void paintComponent(Graphics g) { g.setColor(Theme.BORDER); g.fillRect(0, getHeight() / 2, getWidth(), 1); }
        };
        d.setOpaque(false);
        d.setPreferredSize(new Dimension(10, 9));
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 9));
        return d;
    }

    private JPanel soundRow(JCheckBox sw, Theme.Glyph glyph, String title, String sub) {
        JPanel r = new JPanel(new BorderLayout(14, 0));
        r.setOpaque(false);
        Theme.pad(r, 6, 0, 6, 0);
        JLabel icon = new JLabel(new Badge(Theme.icon(glyph, 16, Theme.TEXT)));
        r.add(icon, BorderLayout.WEST);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Theme.label(title, 14f, true, Theme.TEXT));
        text.add(Box.createVerticalStrut(2));
        text.add(Theme.label(sub, 12f, false, Theme.DIM));
        r.add(text, BorderLayout.CENTER);
        sw.getAccessibleContext().setAccessibleName(title);
        JPanel holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        holder.add(sw);
        r.add(holder, BorderLayout.EAST);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        MouseAdapter click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (sw.isEnabled()) sw.doClick(); }
        };
        text.addMouseListener(click);
        icon.addMouseListener(click);
        return r;
    }

    /** A soft rounded square behind a small icon. */
    private record Badge(Icon inner) implements Icon {
        @Override public int getIconWidth() { return 36; }
        @Override public int getIconHeight() { return 36; }
        @Override public void paintIcon(java.awt.Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            g.setColor(Theme.CARD_HI);
            g.fill(new RoundRectangle2D.Double(x, y, 36, 36, 12, 12));
            inner.paintIcon(c, g, x + 10, y + 10);
            g.dispose();
        }
    }

    // advanced: every detailed setting, folded away
    private int row = 1;

    private GridBagConstraints gbc(int y) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = y;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(6, 0, 6, 0);
        return g;
    }

    private void line(JPanel card, String label, JComponent c) {
        GridBagConstraints a = gbc(row);
        a.gridx = 0;
        a.weightx = 0;
        a.insets = new Insets(6, 0, 6, 14);
        JLabel l = Theme.label(label, 13f, false, Theme.DIM);
        l.setLabelFor(c);
        card.add(l, a);
        GridBagConstraints b = gbc(row++);
        b.gridx = 1;
        b.weightx = 1;
        if (c instanceof JComboBox<?>) c.setMinimumSize(new Dimension(80, c.getPreferredSize().height));
        card.add(c, b);
    }

    private void full(JPanel card, JComponent c) {
        GridBagConstraints a = gbc(row++);
        a.gridx = 0;
        a.gridwidth = 2;
        a.weightx = 1;
        card.add(c, a);
    }

    private void heading(JPanel card, String text) {
        GridBagConstraints a = gbc(row++);
        a.gridx = 0;
        a.gridwidth = 2;
        a.insets = new Insets(row > 2 ? 16 : 2, 0, 4, 0);
        card.add(Theme.label(text.toUpperCase(), 10.5f, true, Theme.FAINT), a);
    }

    private JPanel advancedCard() {
        JPanel c = Theme.roundCard(new BorderLayout());
        c.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(c, 8, 12, 8, 12);
        advancedToggle = new JButton();
        advancedToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        advancedToggle.setFont(Theme.interBold.deriveFont(13.5f));
        advancedToggle.setHorizontalAlignment(JButton.LEFT);
        advancedToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        advancedToggle.setRequestFocusEnabled(false);
        advancedToggle.addActionListener(e -> showAdvanced(!advanced.isVisible()));
        c.add(advancedToggle, BorderLayout.NORTH);

        advanced.setOpaque(false);
        advanced.setLayout(new GridBagLayout());
        Theme.pad(advanced, 4, 8, 10, 8);
        row = 1;
        heading(advanced, "Video");
        JLabel captured = Theme.label("Minecraft (Evade Client)", 13f, false, Theme.TEXT);
        captured.setToolTipText("Evade Clips records the Minecraft window only, while Minecraft with Evade Client is open");
        line(advanced, "Captures", captured);
        line(advanced, "Frame rate", fps);
        line(advanced, "Resolution", res);
        line(advanced, "Quality", quality);
        line(advanced, "Codec", codec);
        line(advanced, "Encoder", encoder);
        line(advanced, "Replay bitrate", replayRate);
        recordRate.setToolTipText("A different bitrate for full recordings runs a second encode while recording");
        line(advanced, "Recording bitrate", recordRate);
        cursor.setOpaque(false);
        full(advanced, cursor);
        heading(advanced, "Sound devices");
        line(advanced, "Game sound", sysDevice);
        gameOnly.setOpaque(false);
        gameOnly.setToolTipText("Windows 10 (2022 builds) and 11, with the device on Default: the Game track has only Minecraft's sound, "
                + "everything else is on an \"Other sound\" track you can mute in the editor");
        gameOnly.setVisible(Ffmpeg.WINDOWS);
        full(advanced, gameOnly);
        line(advanced, "Microphone", micDevice);
        micVol.setOpaque(false);
        line(advanced, "Mic volume", micVol);
        voiceDevice.setToolTipText("Set your chat app's output to a virtual cable (e.g. VB-Cable) and pick it here, so friends' voices can be muted per clip");
        line(advanced, "Voice chat", voiceDevice);
        c.add(advanced, BorderLayout.CENTER);
        showAdvanced(false);
        return c;
    }

    private void showAdvanced(boolean show) {
        advanced.setVisible(show);
        advancedToggle.setText("Advanced settings");
        advancedToggle.setIcon(Theme.icon(show ? Theme.Glyph.CHEVRON_DOWN : Theme.Glyph.CHEVRON_RIGHT, 16, Theme.DIM));
        advancedToggle.setToolTipText(show ? "Hide the detailed settings" : "Frame rate, resolution, codec, encoder, bitrates and sound devices");
        body.revalidate();
        body.repaint();
    }

    private JPanel recentCard() {
        JPanel c = section("Latest captures", null);
        recent.setOpaque(false);
        c.add(left(recent));
        return c;
    }

    private void refreshRecent() {
        List<Clip> list = new ArrayList<>();
        for (Clip c : app.library.query("", "all", null, "newest")) {
            if (c.kind != Clip.Kind.REPLAY && c.kind != Clip.Kind.RECORDING) continue;
            list.add(c);
            if (list.size() >= 4) break;
        }
        // The library changes often (every new thumbnail): rebuild only when what's listed changed.
        StringBuilder key = new StringBuilder(app.settings.hotkeySaveClip).append(app.minecraftRunning());
        for (Clip c : list) key.append('|').append(c.id).append(c.title).append(c.duration).append(Theme.ago(c.created));
        if (key.toString().equals(recentKey)) return;
        recentKey = key.toString();
        recent.removeAll();
        for (Clip c : list) recent.add(new Thumb(c));
        if (list.isEmpty()) {
            JLabel empty = Theme.label(app.minecraftRunning() ? "Nothing yet. Press " + app.settings.hotkeySaveClip + " in Minecraft to save your first replay."
                    : App.NEED_MINECRAFT, 12.5f, false, Theme.FAINT);
            recent.add(empty);
        }
        recent.revalidate();
        recent.repaint();
    }

    private final Map<String, Image> thumbs = new HashMap<>();

    /** A latest capture: its picture with the length on it, its name and when; a click opens it in the editor. */
    private final class Thumb extends JPanel {
        final Clip clip;
        boolean hover;

        Thumb(Clip c) {
            setOpaque(false);
            clip = c;
            setPreferredSize(new Dimension(200, 170));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            setToolTipText("Open in the editor");
            getAccessibleContext().setAccessibleName("Open " + (c.title == null ? "clip" : c.title) + " in the editor");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { app.openEditor.accept(clip); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) app.openEditor.accept(clip);
                }
            });
            if (!thumbs.containsKey(c.id)) {
                thumbs.put(c.id, null);
                background.submit(() -> {
                    try {
                        Path p = app.library.thumb(c);
                        BufferedImage img = Files.exists(p) ? ImageIO.read(p.toFile()) : null;
                        if (img != null) SwingUtilities.invokeLater(() -> { thumbs.put(c.id, img); repaint(); });
                        else SwingUtilities.invokeLater(() -> thumbs.remove(c.id));
                    } catch (Exception ignored) { }
                });
            }
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int w = getWidth(), ih = Math.min(getHeight() - 52, (int) (w * 9 / 16.0));
            RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, w, ih, 12, 12);
            g.setColor(Theme.CARD_HI);
            g.fill(shape);
            Image img = thumbs.get(clip.id);
            if (img != null) {
                g.setClip(shape);
                // cover the frame, centred
                double s = Math.max(w / (double) img.getWidth(null), ih / (double) img.getHeight(null));
                int dw = (int) (img.getWidth(null) * s), dh = (int) (img.getHeight(null) * s);
                g.drawImage(img, (w - dw) / 2, (ih - dh) / 2, dw, dh, null);
                g.setPaint(new GradientPaint(0, ih * 0.55f, new Color(0, 0, 0, 0), 0, ih, new Color(0, 0, 0, 150)));
                g.fillRect(0, 0, w, ih);
                g.setClip(null);
            } else {
                Theme.icon(Theme.Glyph.FILM, 28, Theme.FAINT).paintIcon(this, g, w / 2 - 14, ih / 2 - 14);
            }
            if (hover || isFocusOwner()) {
                g.setColor(Theme.RED);
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Double(1, 1, w - 2, ih - 2, 12, 12));
                g.setColor(new Color(0, 0, 0, 120));
                g.fill(new Ellipse2D.Double(w / 2.0 - 22, ih / 2.0 - 22, 44, 44));
                Theme.icon(Theme.Glyph.PLAY, 20, Color.WHITE).paintIcon(this, g, w / 2 - 9, ih / 2 - 10);
            }
            // kind and length chips
            g.setFont(Theme.interBold.deriveFont(11f));
            String kind = clip.kind == Clip.Kind.REPLAY ? "REPLAY" : "REC";
            chip(g, kind, 8, 8, clip.kind == Clip.Kind.REPLAY ? new Color(0, 0, 0, 170) : Theme.RED, Color.WHITE);
            String len = Theme.time(clip.duration);
            int lw = g.getFontMetrics().stringWidth(len) + 12;
            chip(g, len, w - lw - 8, ih - 28, new Color(0, 0, 0, 170), Color.WHITE);
            // name and when
            g.setFont(Theme.interBold.deriveFont(13f));
            g.setColor(Theme.TEXT);
            g.drawString(LibraryPanel.ellipsize(g, clip.title == null ? "Clip" : clip.title, w - 4), 2, ih + 22);
            g.setFont(Theme.inter.deriveFont(12f));
            g.setColor(Theme.DIM);
            g.drawString(Theme.ago(clip.created), 2, ih + 41);
            g.dispose();
        }
    }

    private static void chip(Graphics2D g, String text, int x, int y, Color bg, Color fg) {
        int tw = g.getFontMetrics().stringWidth(text);
        g.setColor(bg);
        g.fill(new RoundRectangle2D.Double(x, y, tw + 12, 20, 8, 8));
        g.setColor(fg);
        g.drawString(text, x + 6, y + 14);
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }

    // ------------------------------------------------------------------ settings binding

    /** Shows the settings as they are now (also called when one was changed elsewhere, e.g. the replay length). */
    void load() {
        loading = true;
        try {
            var s = app.settings;
            fps.setSelectedItem(String.valueOf(s.fps));
            res.setSelectedIndex(switch (s.resolution) { case 0 -> 0; case 2160 -> 1; case 1440 -> 2; case 720 -> 4; default -> 3; });
            quality.setSelectedIndex(s.quality - 1);
            replayRate.setSelectedIndex(Math.max(0, indexOf(REPLAY_RATES, s.replayBitrateMbps)));
            recordRate.setSelectedIndex(Math.max(0, indexOf(RECORD_RATES, s.recordBitrateMbps)));
            codec.setSelectedIndex(switch (s.codec) { case "h264" -> 1; case "hevc" -> 2; default -> 0; });
            cursor.setSelected(s.captureCursor);
            sysAudio.setSelected(s.recordSystemAudio);
            gameOnly.setSelected(s.gameAudioSeparate);
            mic.setSelected(s.recordMic);
            micVol.setValue((int) Math.round(s.micVolume * 100));
            voice.setSelected(s.recordVoice);
            loadReplayLengths(s.replaySeconds);
            for (int i = 0; i < POST_ROLLS.length; i++) if (POST_ROLLS[i] == s.replayAfterSeconds) postRoll.setSelected(i);
        } finally {
            loading = false;
        }
        refreshEncoders();
        updateEnabled();
        refreshPresets();
        if (hero != null) { hero.refreshControls(); hero.repaint(); }
    }

    /** The replay-length choices, with {@code current} added if it isn't one of the usual lengths. */
    private void loadReplayLengths(int current) {
        lenValues.clear();
        for (int v : REPLAY_LENS) lenValues.add(v);
        if (!lenValues.contains(current)) { lenValues.add(current); lenValues.sort(null); }
        List<String> names = new ArrayList<>();
        for (int v : lenValues) names.add(label(v));
        replayLen.setOptions(names);
        replayLen.setSelected(lenValues.indexOf(current));
    }

    /** "30 seconds", "2.5 minutes", "1 minute", or "1 min 45 s" for an unusual length. */
    static String lengthName(int secs) {
        if (secs < 60) return secs + " seconds";
        if (secs % 60 == 0) return secs / 60 + (secs == 60 ? " minute" : " minutes");
        if (secs % 30 == 0) return String.format(java.util.Locale.ROOT, "%.1f minutes", secs / 60.0);
        return label(secs);
    }

    /** Marks the preset the current settings match (none when they've been fine-tuned). */
    private void refreshPresets() {
        var s = app.settings;
        for (Preset p : presets) p.setSelected(s.fps == p.spec.fps() && s.resolution == p.spec.resolution() && s.quality == p.spec.quality());
    }

    /**
     * Fills the encoder list without holding up the window: the GPU encoders are tested in the background (a few
     * seconds the first time), then listed with the saved choice selected.
     */
    void refreshEncoders() {
        encodersReady = false;
        loading = true;
        try {
            encoder.removeAllItems();
            encoder.addItem(AUTO_ENCODER);
            if (ffmpegOk) encoder.addItem(DETECTING);
            encoder.setSelectedIndex(0);
        } finally {
            loading = false;
        }
        if (!ffmpegOk) return;
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                List<String> labels = new ArrayList<>();
                for (String c : List.of("h264", "hevc")) for (String e : Ffmpeg.captureEncoders(c)) labels.add(Ffmpeg.encoderLabel(e));
                return labels;
            }
            @Override protected void done() {
                List<String> labels;
                try { labels = get(); } catch (Exception e) { labels = List.of(); }
                loading = true;
                try {
                    encoder.removeAllItems();
                    encoder.addItem(AUTO_ENCODER);
                    for (String l : labels) encoder.addItem(l);
                    String saved = app.settings.encoder;
                    if (saved != null && !saved.isBlank()) encoder.setSelectedItem(Ffmpeg.encoderLabel(saved));
                } finally {
                    loading = false;
                }
                encodersReady = true;
                updateDataRate();
            }
        }.execute();
    }

    private void loadDevices() {
        Map<String, String> d = CaptureSource.audioDevices(true);
        String defSys = CaptureSource.defaultSystemAudio(), defMic = CaptureSource.defaultMic();
        // Windows records the speakers directly, so Default always has game sound there
        boolean speakers = net.evadeclient.clips.core.LoopbackAudio.windowsAvailable();
        SwingUtilities.invokeLater(() -> {
            devices = d;
            loading = true;
            try {
                sysDevice.removeAllItems();
                micDevice.removeAllItems();
                voiceDevice.removeAllItems();
                voiceDevice.addItem("Choose a device");
                sysDevice.addItem("Default" + (speakers ? " (what you hear)" : defSys == null ? " (none found)" : ""));
                micDevice.addItem("Default" + (defMic == null ? " (none found)" : ""));
                for (String name : d.keySet()) { sysDevice.addItem(name); micDevice.addItem(name); voiceDevice.addItem(name); }
                selectDevice(sysDevice, app.settings.systemAudioDevice);
                selectDevice(micDevice, app.settings.micDevice);
                selectDevice(voiceDevice, app.settings.voiceDevice);
            } finally {
                loading = false;
            }
            noDefaultSys = defSys == null && !speakers;
            noDefaultMic = defMic == null;
            devicesLoaded = true;
            updateEnabled();
        });
    }

    private void selectDevice(JComboBox<String> box, String id) {
        if (id == null || id.isBlank()) { box.setSelectedIndex(0); return; }
        for (Map.Entry<String, String> e : devices.entrySet()) if (e.getValue().equals(id)) { box.setSelectedItem(e.getKey()); return; }
        box.setSelectedIndex(0);
    }

    private String deviceId(JComboBox<String> box) {
        Object sel = box.getSelectedItem();
        if (sel == null || box.getSelectedIndex() <= 0) return "";
        return devices.getOrDefault(sel.toString(), "");
    }

    private Runnable apply;

    private void wire() {
        apply = () -> {
            if (loading) return;
            var s = app.settings;
            String micBefore = s.micDevice;
            s.fps = Integer.parseInt((String) fps.getSelectedItem());
            s.resolution = new int[]{0, 2160, 1440, 1080, 720}[res.getSelectedIndex()];
            s.quality = quality.getSelectedIndex() + 1;
            s.codec = new String[]{"auto", "h264", "hevc"}[codec.getSelectedIndex()];
            s.replayBitrateMbps = REPLAY_RATES[Math.max(0, replayRate.getSelectedIndex())];
            s.recordBitrateMbps = RECORD_RATES[Math.max(0, recordRate.getSelectedIndex())];
            Object enc = encoder.getSelectedItem();
            if (!encodersReady || DETECTING.equals(enc)) {
                // still detecting: keep the saved encoder
            } else if (encoder.getSelectedIndex() <= 0) {
                s.encoder = "";
            } else if (ffmpegOk) {
                // The list is already known (it was tested to fill the box), so this is instant.
                for (String c2 : List.of("h264", "hevc"))
                    for (String e : Ffmpeg.captureEncoders(c2)) if (Ffmpeg.encoderLabel(e).equals(enc)) { s.encoder = e; s.codec = c2; }
            }
            s.captureCursor = cursor.isSelected();
            s.recordSystemAudio = sysAudio.isSelected();
            s.gameAudioSeparate = gameOnly.isSelected();
            if (devicesLoaded) s.systemAudioDevice = deviceId(sysDevice);
            s.recordMic = mic.isSelected();
            if (devicesLoaded) s.micDevice = deviceId(micDevice);
            s.micVolume = micVol.getValue() / 100.0;
            s.recordVoice = voice.isSelected();
            if (devicesLoaded) s.voiceDevice = deviceId(voiceDevice);
            s.save();
            updateEnabled();
            refreshPresets();
            if (app.engine.isRecording()) {
                // A restart now would end the recording: the engine applies it when the recording stops.
                restartLater.stop();
                restartNote.setText("New capture settings apply when this recording stops");
                restartNote.setVisible(true);
                pendingNote = true;
                new Thread(() -> { try { app.engine.restart(); } catch (Exception ignored) {} }, "capture-restart").start();
            } else if (app.engine.isLive()) {
                restartLater.restart();
            }
            // Voice commands listen on the microphone chosen here.
            if (!java.util.Objects.equals(micBefore, s.micDevice) && s.voiceCommands) new Thread(() -> {
                String err = app.applyVoice();
                if (err != null) app.notify(err, null, true);
            }, "voice-mic").start();
        };
        for (JComboBox<?> b : List.of(fps, res, quality, codec, encoder, replayRate, recordRate, sysDevice, micDevice, voiceDevice)) b.addActionListener(e -> apply.run());
        for (JCheckBox b : List.of(cursor, sysAudio, mic, voice, gameOnly)) b.addActionListener(e -> apply.run());
        micVol.addChangeListener(e -> { if (!micVol.getValueIsAdjusting()) apply.run(); });
        postRoll.onPick(i -> {
            if (loading) return;
            app.settings.replayAfterSeconds = POST_ROLLS[i];
            app.settings.save();
        });
        replayLen.onPick(i -> {
            if (loading || i < 0 || i >= lenValues.size()) return;
            app.settings.replaySeconds = lenValues.get(i);
            app.settings.save();
            updateDataRate();
            hero.refreshControls();
            hero.repaint();
        });
    }

    /** Applies a quality preset: frame rate, resolution and quality together; codec, encoder and bitrates go back to automatic. */
    private void applyPreset(PresetSpec p) {
        loading = true;
        try {
            fps.setSelectedItem(String.valueOf(p.fps()));
            res.setSelectedIndex(p.resolution() == 1440 ? 2 : p.resolution() == 720 ? 4 : 3);
            quality.setSelectedIndex(p.quality() - 1);
            codec.setSelectedIndex(0);
            encoder.setSelectedIndex(0);
            replayRate.setSelectedIndex(0);
            recordRate.setSelectedIndex(0);
        } finally {
            loading = false;
        }
        apply.run();
    }

    /** Audio tracks capture will record, from the settings and the devices found (no device scan here). */
    private int audioTracks() {
        var s = app.settings;
        boolean pick = Ffmpeg.WINDOWS || Ffmpeg.MAC;
        if (s.source.startsWith("test")) return (s.recordSystemAudio ? 1 : 0) + (s.recordMic ? 1 : 0) + (s.recordVoice ? 1 : 0);
        int n = 0;
        if (s.recordSystemAudio && (!s.systemAudioDevice.isBlank() || !pick || !noDefaultSys)) n++;
        if (s.recordMic && (!s.micDevice.isBlank() || !pick || !noDefaultMic)) n++;
        if (s.recordVoice && !s.voiceDevice.isBlank()) n++;
        return n;
    }

    /** "About 65 MB a minute · the replay buffer uses at most 33 MB": the worst case, since the bitrate is capped. */
    private void updateDataRate() {
        if (!ffmpegOk) { dataRate.setText(" "); return; }
        var s = app.settings;
        int gen = ++rateGen;
        int tracks = audioTracks();
        Dimension out = CaptureSource.outputSize(s);
        int replaySecs = s.replaySeconds;
        background.submit(() -> {
            String enc = CaptureEngine.previewEncoder(s);
            long video = s.replayBitrateMbps > 0 ? (long) (s.replayBitrateMbps * 1e6) : Ffmpeg.liveCapBits(enc, s.quality, s.fps, out.width, out.height);
            long bits = video + 160_000L * tracks;
            double mbMin = bits * 60 / 8e6;
            String text = String.format("Up to %.0f MB a minute  ·  %d×%d, %d fps, %s  ·  the replay buffer uses at most %.0f MB",
                    mbMin, out.width, out.height, s.fps, Ffmpeg.isHevc(enc) ? "HEVC" : "H.264", mbMin * (replaySecs + 3) / 60.0);
            SwingUtilities.invokeLater(() -> { if (gen == rateGen) dataRate.setText(text); });
        });
    }

    private static int indexOf(double[] a, double v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    private void updateEnabled() {
        updateDataRate();
        var st = app.settings;
        String s = st.source;
        sysDevice.setEnabled(sysAudio.isSelected());
        gameOnly.setEnabled(sysAudio.isSelected() && sysDevice.getSelectedIndex() <= 0);
        micDevice.setEnabled(mic.isSelected());
        micVol.setEnabled(mic.isSelected());
        voiceDevice.setEnabled(voice.isSelected());
        // The warning always matches the settings now (it clears once the cause is fixed).
        String w = " ";
        if (!s.startsWith("test") && CaptureSource.linuxWayland())
            w = "Screen capture on Linux needs an X11 session; on Wayland clips will be black. Choose the 'on Xorg' session at login.";
        else if ((Ffmpeg.WINDOWS || Ffmpeg.MAC) && noDefaultSys && st.recordSystemAudio && st.systemAudioDevice.isBlank())
            w = Ffmpeg.WINDOWS
                    ? "No game-sound device found. Enable \"Stereo Mix\" in Sound settings → Recording, or install VB-Cable, then pick it under Advanced settings."
                    : "No game-sound device found. Install BlackHole to record game sound, then pick it under Advanced settings.";
        else if (st.recordVoice && st.voiceDevice.isBlank())
            w = "Voice chat is on, but no device is picked for it yet: choose one under Advanced settings.";
        warn.setText(w);
        warn.setVisible(!w.isBlank());
    }

    /** Shows the full ffmpeg log of the last capture failure, with a Copy button. */
    private void showLog() {
        String log = app.status.lastLog();
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog d = new JDialog(owner, "Capture error details", java.awt.Dialog.ModalityType.MODELESS);
        JTextArea area = new JTextArea(log == null || log.isBlank() ? "No log was kept for this error." : log, 22, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        JButton copy = Theme.button("Copy", Theme.icon(Theme.Glyph.COPY, 16, Theme.TEXT), false);
        copy.addActionListener(e -> { App.copyText(area.getText()); copy.setText("Copied"); });
        JButton close = Theme.button("Close", null, false);
        close.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(copy);
        buttons.add(close);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        Theme.pad(p, 14, 14, 14, 14);
        p.add(Theme.label(app.status.message() == null ? "" : app.status.message(), 13f, true, Theme.TEXT), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ small controls

    /** An on/off switch (a check box underneath, so keyboard, focus and screen readers work as usual). */
    static final class Switch extends JCheckBox {
        private float pos;
        private final Timer anim = new Timer(15, e -> step());

        Switch(String name) {
            super();
            getAccessibleContext().setAccessibleName(name);
            setOpaque(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setRequestFocusEnabled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(46, 26));
            addItemListener(e -> anim.start());
        }

        private void step() {
            float target = isSelected() ? 1 : 0;
            pos += (target - pos) * 0.35f;
            if (Math.abs(target - pos) < 0.02f) { pos = target; anim.stop(); }
            repaint();
        }

        @Override public void setSelected(boolean b) {
            super.setSelected(b);
            if (!anim.isRunning()) pos = b ? 1 : 0;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int w = 44, h = 24, y = (getHeight() - h) / 2;
            Color off = Theme.CARD_HI.brighter(), on = Theme.RED;
            Color track = LibraryPanel.blend(off, on, pos);
            if (!isEnabled()) track = LibraryPanel.blend(track, Theme.CARD, 0.6f);
            g.setColor(track);
            g.fill(new RoundRectangle2D.Double(1, y, w, h, h, h));
            if (hasFocus()) { g.setColor(Theme.RED_HI); g.draw(new RoundRectangle2D.Double(0, y - 1, w + 2, h + 2, h + 2, h + 2)); }
            double kx = 1 + 3 + pos * (w - h);
            g.setColor(new Color(0, 0, 0, 60));
            g.fill(new Ellipse2D.Double(kx, y + 4, h - 6, h - 6));
            g.setColor(isEnabled() ? Color.WHITE : Theme.DIM);
            g.fill(new Ellipse2D.Double(kx, y + 3, h - 6, h - 6));
            g.dispose();
        }
    }

    /** A row of options, one picked: replay length, seconds after the key. */
    private static final class Segmented extends JPanel {
        private List<String> options = List.of();
        private int selected = -1, hover = -1;
        private IntConsumer onPick = i -> {};
        private final List<Rectangle> hits = new ArrayList<>();

        Segmented(String name) {
            setOpaque(false);
            getAccessibleContext().setAccessibleName(name);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(Theme.interBold.deriveFont(12.5f));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { int i = at(e.getX(), e.getY()); if (i >= 0) pick(i); }
                @Override public void mouseMoved(MouseEvent e) { int i = at(e.getX(), e.getY()); if (i != hover) { hover = i; repaint(); } }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_LEFT && selected > 0) pick(selected - 1);
                    if (e.getKeyCode() == KeyEvent.VK_RIGHT && selected < options.size() - 1) pick(selected + 1);
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
                @Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
        }

        void setOptions(List<String> o) { options = List.copyOf(o); revalidate(); repaint(); }
        void setSelected(int i) { selected = i; repaint(); }
        void onPick(IntConsumer c) { onPick = c; }

        private void pick(int i) {
            if (i == selected) return;
            selected = i;
            repaint();
            onPick.accept(i);
        }

        private int at(int x, int y) {
            for (int i = 0; i < hits.size(); i++) if (hits.get(i).contains(x, y)) return i;
            return -1;
        }

        private int itemWidth(String s) { return getFontMetrics(getFont()).stringWidth(s) + 28; }

        @Override public Dimension getPreferredSize() {
            int w = 8;
            for (String s : options) w += itemWidth(s);
            return new Dimension(w, 38);
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int h = getHeight();
            g.setColor(Theme.BG);
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), h, 12, 12));
            g.setColor(isFocusOwner() ? Theme.RED_DIM : Theme.BORDER);
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, h - 1, 12, 12));
            hits.clear();
            int x = 4;
            g.setFont(getFont());
            for (int i = 0; i < options.size(); i++) {
                String s = options.get(i);
                int iw = itemWidth(s);
                Rectangle r = new Rectangle(x, 4, iw, h - 8);
                hits.add(r);
                if (i == selected) {
                    g.setColor(Theme.RED);
                    g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 9, 9));
                } else if (i == hover) {
                    g.setColor(Theme.CARD_HI);
                    g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 9, 9));
                }
                g.setColor(i == selected ? Color.WHITE : i == hover ? Theme.TEXT : Theme.DIM);
                int tw = g.getFontMetrics().stringWidth(s);
                g.drawString(s, r.x + (r.width - tw) / 2, r.y + r.height / 2 + g.getFontMetrics().getAscent() / 2 - 2);
                x += iw;
            }
            g.dispose();
        }
    }

    /** A quality preset card: a picture of its sharpness, the name, what it's for, its numbers. */
    private final class Preset extends JPanel {
        final PresetSpec spec;
        boolean selected, hover;

        Preset(PresetSpec spec) {
            setOpaque(false);
            this.spec = spec;
            setPreferredSize(new Dimension(220, 118));
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName(spec.name() + " quality: " + spec.resolution() + "p, " + spec.fps() + " fps");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { applyPreset(spec); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) applyPreset(spec);
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
                @Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
        }

        void setSelected(boolean s) { if (s != selected) { selected = s; repaint(); } }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 14, 14);
            g.setColor(selected ? new Color(0x2A, 0x10, 0x14) : hover ? Theme.CARD_HI : Theme.BG);
            g.fill(shape);
            g.setStroke(new BasicStroke(selected ? 2f : 1f));
            g.setColor(selected ? Theme.RED : isFocusOwner() ? Theme.RED_DIM : Theme.BORDER);
            g.draw(shape);
            // a little "pixel grid" showing sharpness: finer for higher quality
            int cells = spec.quality() == 1 ? 3 : spec.quality() == 2 ? 5 : 8, gs = 34, gx = 16, gy = 16;
            double cell = gs / (double) cells;
            for (int yy = 0; yy < cells; yy++)
                for (int xx = 0; xx < cells; xx++) {
                    float t = (float) ((xx + yy) / (2.0 * (cells - 1)));
                    g.setColor(LibraryPanel.blend(selected ? Theme.RED : Theme.FAINT, selected ? Theme.RED_HI : Theme.DIM, t));
                    g.fill(new RoundRectangle2D.Double(gx + xx * cell + 0.6, gy + yy * cell + 0.6, cell - 1.2, cell - 1.2, 2, 2));
                }
            g.setFont(Theme.interBold.deriveFont(15f));
            g.setColor(Theme.TEXT);
            g.drawString(spec.name(), 64, 30);
            g.setFont(Theme.inter.deriveFont(12f));
            g.setColor(Theme.DIM);
            g.drawString(LibraryPanel.ellipsize(g, spec.blurb(), w - 76), 64, 48);
            // the numbers, as chips
            g.setFont(Theme.interBold.deriveFont(11.5f));
            int x = 16;
            for (String chip : List.of(spec.resolution() + "p", spec.fps() + " fps")) {
                int cw = g.getFontMetrics().stringWidth(chip) + 16;
                g.setColor(selected ? Theme.RED_DIM : Theme.CARD_HI);
                g.fill(new RoundRectangle2D.Double(x, h - 36, cw, 22, 8, 8));
                g.setColor(Theme.TEXT);
                g.drawString(chip, x + 8, h - 21);
                x += cw + 6;
            }
            if (selected) {
                Theme.icon(Theme.Glyph.CHECK, 16, Theme.RED_HI).paintIcon(this, g, w - 30, 14);
            }
            g.dispose();
        }
    }

    /** A button that shows its key on the right, like a keycap: "Save last 30 s   [F8]". */
    private static final class KeyButton extends JButton {
        String key = "";
        final boolean primary;

        KeyButton(String text, Icon icon, boolean primary) {
            super(text, icon);
            this.primary = primary;
            JButton proto = Theme.button(text, icon, primary);
            setFont(Theme.interBold.deriveFont(14f));
            setRequestFocusEnabled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setIconTextGap(9);
            setHorizontalAlignment(LEFT);
            if (primary) {
                putClientProperty("JButton.buttonType", null);
                setBackground(proto.getBackground());
                setForeground(proto.getForeground());
            }
            putClientProperty("JButton.arc", 14);
        }

        void setKey(String k) {
            k = k == null ? "" : k;
            if (k.equals(key)) return;
            key = k;
            setMargin(new Insets(0, 16, 0, key.isEmpty() ? 16 : 16 + keyWidth() + 10));
            repaint();
        }

        private int keyWidth() { return getFontMetrics(Theme.interBold.deriveFont(11.5f)).stringWidth(key) + 14; }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (key.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            g.setFont(Theme.interBold.deriveFont(11.5f));
            int kw = keyWidth(), kh = 22, x = getWidth() - kw - 12, y = (getHeight() - kh) / 2;
            boolean dark = primary && isEnabled();
            g.setColor(dark ? new Color(255, 255, 255, 50) : Theme.BG);
            g.fill(new RoundRectangle2D.Double(x, y + 2, kw, kh, 7, 7));
            g.setColor(dark ? new Color(255, 255, 255, 40) : Theme.CARD_HI);
            g.fill(new RoundRectangle2D.Double(x, y, kw, kh, 7, 7));
            g.setColor(isEnabled() ? (dark ? Color.WHITE : Theme.TEXT) : Theme.DIM);
            g.drawString(key, x + 7, y + 15);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ hero

    /**
     * The status card. Left: the replay buffer as a ring that fills up (green while it's ready, red while recording)
     * with the time it holds. Right: what's happening in plain words, and the actions with their keys.
     */
    private final class Hero extends JPanel {
        final Switch replayToggle = new Switch("Instant replay");
        final KeyButton save = new KeyButton("", Theme.icon(Theme.Glyph.CLOCK, 18, Color.WHITE), true);
        final KeyButton record = new KeyButton("", REC_ICON, false);
        final KeyButton marker = new KeyButton("Marker", Theme.icon(Theme.Glyph.BOOKMARK, 16, Theme.GOLD), false);
        /** The whole buffer in the editor, to find any moment of the last few minutes. */
        final JButton lookBack = Theme.button("Look back", Theme.icon(Theme.Glyph.FILM, 16, Theme.TEXT), false);
        private boolean compact;
        private Rectangle detailsHit = new Rectangle();
        private boolean syncing;

        Hero() {
            setLayout(null);
            setOpaque(false);
            setHeights();
            replayToggle.addActionListener(e -> { if (!syncing) app.toggleReplay(replayToggle.isSelected()); });
            replayToggle.setToolTipText("Instant replay: keep the last moments ready to save");
            save.addActionListener(e -> app.saveClip(0, null, null, null));
            record.addActionListener(e -> app.toggleRecording());
            marker.addActionListener(e -> app.marker());
            lookBack.addActionListener(e -> app.lookBack());
            lookBack.setToolTipText("Open everything in the replay buffer and pick any moment to keep");
            marker.setToolTipText("Bookmark this moment, to find it fast in the editor");
            for (JComponent c : List.of(replayToggle, save, record, marker, lookBack)) add(c);
            replayToggle.setVisible(!ClipsApp.hosted);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (detailsHit.contains(e.getPoint())) showLog(); }
                @Override public void mouseMoved(MouseEvent e) {
                    setCursor(detailsHit.contains(e.getPoint()) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            // Only the ring, the pulse and the meters move; nothing runs while the page isn't on screen.
            new Timer(120, e -> {
                if (!isShowing()) return;
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w instanceof Frame f && (f.getExtendedState() & Frame.ICONIFIED) != 0) return;
                refreshControls();
                repaint();
            }).start();
            new Timer(3000, e -> { if (isShowing()) refreshRecent(); }).start();
        }

        private boolean meters;

        private void setHeights() {
            int h = (compact ? 330 : 214) + (meters ? 34 : 0);
            setPreferredSize(new Dimension(600, h));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            setMinimumSize(new Dimension(300, h));
        }

        void setCompact(boolean c) {
            if (c == compact) return;
            compact = c;
            setHeights();
            revalidate();
            if (getParent() != null) getParent().revalidate();
            repaint();
        }

        /** Brings the buttons up to date with the engine. Changes only what differs (never from paint). */
        void refreshControls() {
            CaptureEngine.Status st = app.status;
            boolean live = st.state() == CaptureEngine.State.LIVE, starting = st.state() == CaptureEngine.State.STARTING;
            syncing = true;
            try {
                if (replayToggle.isSelected() != (live || starting)) replayToggle.setSelected(live || starting);
            } finally {
                syncing = false;
            }
            text(save, "Save last " + label(app.settings.replaySeconds));
            save.setKey(app.settings.hotkeySaveClip);
            if (save.isEnabled() != live) save.setEnabled(live);
            text(record, st.recording() ? "Stop  " + Theme.time(st.recordingSeconds()) : "Record");
            record.setKey(app.settings.hotkeyRecord);
            Icon icon = st.recording() ? STOP_ICON : REC_ICON;
            if (record.getIcon() != icon) record.setIcon(icon);
            if (record.isEnabled() != ffmpegOk) record.setEnabled(ffmpegOk);
            marker.setKey(app.settings.hotkeyMarker);
            if (marker.isEnabled() != live) marker.setEnabled(live);
            if (lookBack.isEnabled() != live) lookBack.setEnabled(live);
            if (meters != live) { meters = live; setHeights(); revalidate(); if (getParent() != null) getParent().revalidate(); }
            if (pendingNote && !st.recording()) { pendingNote = false; restartNote.setText(" "); restartNote.setVisible(false); }
        }

        private void text(JButton b, String t) { if (!t.equals(b.getText())) b.setText(t); }

        private static final int RING = 150, PAD = 26;

        @Override public void doLayout() {
            int w = getWidth();
            replayToggle.setBounds(w - PAD - 46, PAD - 2, 46, 28);
            int x0 = compact ? PAD : PAD + RING + 30, y = compact ? 214 : 118, avail = w - x0 - PAD;
            int bw = Math.max(150, Math.min(250, (int) (avail * 0.38)));
            save.setBounds(x0, y, bw, 48);
            int rx = x0 + bw + 10, rw = Math.max(120, Math.min(170, (int) (avail * 0.26)));
            record.setBounds(rx, y, rw, 48);
            int mx = rx + rw + 10, rest = x0 + avail - mx;
            if (rest >= 310) {
                marker.setBounds(mx, y, 164, 48);
                lookBack.setBounds(mx + 174, y, Math.min(140, rest - 174), 48);
            } else {
                marker.setBounds(x0, y + 58, 164, 40);
                lookBack.setBounds(x0 + 174, y + 58, 140, 40);
            }
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            CaptureEngine.Status st = app.status;
            int w = getWidth(), h = getHeight();
            boolean live = st.state() == CaptureEngine.State.LIVE, starting = st.state() == CaptureEngine.State.STARTING;
            boolean err = st.state() == CaptureEngine.State.ERROR, rec = st.recording();
            Color state = err ? Theme.GOLD : rec ? Theme.RED : live ? Theme.OK : starting ? Theme.GOLD : Theme.FAINT;

            // card with a soft glow of the state colour in the corner
            RoundRectangle2D card = new RoundRectangle2D.Double(0, 0, w, h, 20, 20);
            g.setColor(Theme.CARD);
            g.fill(card);
            g.setClip(card);
            float glowA = live || rec ? 0.16f : 0.06f;
            java.awt.RadialGradientPaint glow = new java.awt.RadialGradientPaint(PAD + RING / 2f, PAD + RING / 2f, RING * 1.4f,
                    new float[]{0f, 1f}, new Color[]{new Color(state.getRed(), state.getGreen(), state.getBlue(), (int) (255 * glowA)), new Color(0, 0, 0, 0)});
            g.setPaint(glow);
            g.fillRect(0, 0, w, h);
            g.setClip(null);
            g.setColor(Theme.BORDER);
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 20, 20));

            // the ring: how full the replay buffer is
            long t = System.currentTimeMillis();
            int rx = PAD, ry = compact ? PAD : (h - RING) / 2;
            double frac = live ? Math.min(1, st.bufferedSeconds() / Math.max(1, app.settings.replaySeconds)) : 0;
            g.setStroke(new BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.CARD_HI);
            g.draw(new Ellipse2D.Double(rx + 6, ry + 6, RING - 12, RING - 12));
            if (live && frac > 0) {
                g.setColor(rec ? Theme.RED : Theme.OK);
                g.draw(new Arc2D.Double(rx + 6, ry + 6, RING - 12, RING - 12, 90, -360 * frac, Arc2D.OPEN));
            } else if (starting) {
                g.setColor(Theme.GOLD);
                double a = (t / 4.0) % 360;
                g.draw(new Arc2D.Double(rx + 6, ry + 6, RING - 12, RING - 12, -a, 70, Arc2D.OPEN));
            }
            // centre: seconds ready, or the recording time
            String big = rec ? Theme.time(st.recordingSeconds()) : live ? Math.round(Math.min(st.bufferedSeconds(), app.settings.replaySeconds)) + "s"
                    : starting ? "…" : err ? "!" : "OFF";
            String small = rec ? "RECORDING" : live ? "READY" : starting ? "STARTING" : err ? "STOPPED" : "REPLAY";
            g.setFont(Theme.interBold.deriveFont(big.length() > 4 ? 26f : 32f));
            g.setColor(live || rec ? Theme.TEXT : Theme.DIM);
            int bwid = g.getFontMetrics().stringWidth(big);
            g.drawString(big, rx + (RING - bwid) / 2, ry + RING / 2 + 8);
            g.setFont(Theme.interBold.deriveFont(10.5f));
            g.setColor(state);
            int swid = g.getFontMetrics().stringWidth(small);
            g.drawString(small, rx + (RING - swid) / 2, ry + RING / 2 + 28);
            if (rec) {
                float pulse = (float) (0.5 + 0.5 * Math.sin(t / 260.0));
                g.setColor(new Color(225, 29, 46, (int) (90 + 160 * pulse)));
                g.fill(new Ellipse2D.Double(rx + RING / 2.0 - 5, ry + RING / 2.0 - 40, 10, 10));
            }

            // headline and the line under it
            int x0 = compact ? PAD + RING + 24 : PAD + RING + 30, ty = compact ? PAD + 44 : PAD + 30;
            int textW = w - x0 - PAD - 70;
            g.setFont(Theme.interBold.deriveFont(22f));
            g.setColor(Theme.TEXT);
            String head = rec ? "Recording" : live ? "Instant replay is on" : starting ? "Starting capture…" : err ? "Capture stopped"
                    : !app.minecraftRunning() ? "Waiting for Minecraft" : "Instant replay is off";
            g.drawString(LibraryPanel.ellipsize(g, head, textW), x0, ty);
            g.setFont(Theme.inter.deriveFont(13.5f));
            g.setColor(Theme.DIM);
            detailsHit = new Rectangle();
            if (err && ffmpegOk) {
                String msg = st.message() == null || st.message().isBlank() ? "Capture stopped unexpectedly." : st.message();
                List<String> lines = Toast.wrap(g.getFontMetrics(), msg, textW);
                if (lines.size() > 2) lines = lines.subList(0, 2);
                for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x0, ty + 24 + i * 19);
                g.setFont(Theme.interBold.deriveFont(12.5f));
                g.setColor(Theme.RED_HI);
                int dy = ty + 24 + lines.size() * 19;
                String details = "Show details";
                int dw = g.getFontMetrics().stringWidth(details);
                g.drawString(details, x0, dy);
                detailsHit = new Rectangle(x0 - 4, dy - 14, dw + 8, 20);
            } else {
                String sub;
                if (!ffmpegOk) sub = app.ffmpegDownloading ? "Downloading the video engine… " + Math.round(app.ffmpegProgress * 100) + "%. Recording works as soon as it's done."
                        : "The video engine (ffmpeg) isn't installed yet. Open Clips in the launcher to download it.";
                else if (rec) sub = "Press " + app.settings.hotkeyRecord + " to stop. Your clip appears in the Library right away.";
                else if (live) sub = "Press " + app.settings.hotkeySaveClip + " in game to save the last " + label(app.settings.replaySeconds) + "."
                        + (app.currentGame != null ? " Minecraft is being captured." : "");
                else if (!app.minecraftRunning()) sub = App.NEED_MINECRAFT + (app.settings.autoReplayForGames && app.settings.replayEnabled
                        ? " Instant replay turns on by itself." : "");
                else sub = "Turn it on to keep the last " + label(app.settings.replaySeconds) + " ready to save at any moment.";
                List<String> lines = Toast.wrap(g.getFontMetrics(), sub, textW + 60);
                if (lines.size() > 2) lines = lines.subList(0, 2);
                for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x0, ty + 24 + i * 19);
            }

            // live sound meters, one per track: you can see each source is being picked up
            if (live) {
                g.setFont(Theme.inter.deriveFont(11.5f));
                // under the buttons (or under the ring on narrow windows)
                int lx = x0, ly = (marker.getY() + marker.getHeight()) + 16, limit = w - PAD;
                if (compact) { lx = PAD; ly = PAD + RING + 18; }
                for (var lv : app.engine.levels()) {
                    String name = lv.track();
                    int nw = g.getFontMetrics().stringWidth(name);
                    if (lx + nw + 6 + 80 > limit) break;
                    g.setColor(Theme.FAINT);
                    g.drawString(name, lx, ly + 9);
                    int bx = lx + nw + 8, bw = 80;
                    // 12 little bars, like a real level meter
                    double lf = Double.isInfinite(lv.peakDb()) ? 0 : Math.max(0, Math.min(1, (lv.peakDb() + 60) / 60));
                    int bars = 12, lit = (int) Math.round(lf * bars);
                    for (int i = 0; i < bars; i++) {
                        Color c = i >= 10 ? Theme.RED : i >= 7 ? Theme.GOLD : Theme.OK;
                        g.setColor(i < lit ? c : Theme.CARD_HI);
                        g.fill(new RoundRectangle2D.Double(bx + i * (bw / (double) bars), ly, bw / (double) bars - 2, 10, 2, 2));
                    }
                    lx = bx + bw + 18;
                }
            }
            // replay toggle label (standalone only: the launcher has its own switch in the top bar)
            if (!replayToggle.isVisible()) { g.dispose(); return; }
            g.setFont(Theme.interBold.deriveFont(12f));
            g.setColor(Theme.DIM);
            String tl = "Instant replay";
            g.drawString(tl, w - PAD - 46 - 10 - g.getFontMetrics().stringWidth(tl), PAD + 16);
            g.dispose();
        }
    }

    /** "30 s", "2 min", "2 min 30 s". */
    static String label(int secs) {
        if (secs < 60) return secs + " s";
        return secs % 60 == 0 ? secs / 60 + " min" : secs / 60 + " min " + secs % 60 + " s";
    }
}
