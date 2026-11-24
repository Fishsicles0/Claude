package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.CaptureSource;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.Ffmpeg;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Capture: instant replay status and controls, recording, and every capture setting. */
public final class RecordPanel extends JPanel {
    static final Icon REC_ICON = Theme.icon(Theme.Glyph.RECORD, 16, Theme.RED), STOP_ICON = Theme.icon(Theme.Glyph.STOP, 16, Theme.RED);
    private static final String AUTO_ENCODER = "Automatic (best available)", DETECTING = "Detecting encoders…";

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
    private final JCheckBox sysAudio = new JCheckBox("Record game audio");
    private final JComboBox<String> sysDevice = new JComboBox<>();
    private final JCheckBox mic = new JCheckBox("Record microphone");
    private final JComboBox<String> micDevice = new JComboBox<>();
    private final JSlider micVol = new JSlider(0, 200, 100);
    private final JCheckBox voice = new JCheckBox("Record voice chat on its own track");
    private final JComboBox<String> voiceDevice = new JComboBox<>();
    /** Replay lengths offered (a length set elsewhere is added to the list). */
    private static final int[] REPLAY_LENS = {15, 30, 60, 120, 150, 300, 600};
    private final List<Integer> lenValues = new ArrayList<>();
    private final JComboBox<String> replayLen = new JComboBox<>();
    private final JComboBox<String> postRoll = new JComboBox<>(new String[]{"+0 s after", "+3 s after", "+5 s after", "+10 s after"});
    private static final int[] POST_ROLLS = {0, 3, 5, 10};
    private final JLabel warn = Theme.label(" ", 12f, false, Theme.GOLD);
    /** "New capture settings apply when this recording stops". */
    private final JLabel restartNote = Theme.label(" ", 12f, false, Theme.DIM);
    private boolean pendingNote;
    private final JPanel recent = new JPanel();
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
    private JPanel videoCard, rightColumn;
    private final JPanel cols = new JPanel();
    private boolean twoColumns;

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
        Theme.pad(body, 22, 26, 30, 26);
        JLabel title = Theme.label("Record", 26f, true, Theme.TEXT);
        title.setAlignmentX(LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(16));
        hero.setAlignmentX(LEFT_ALIGNMENT);
        body.add(hero);
        warn.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(warn, 10, 2, 0, 0);
        body.add(warn);
        restartNote.setAlignmentX(LEFT_ALIGNMENT);
        restartNote.setVisible(false);
        Theme.pad(restartNote, 4, 2, 0, 0);
        body.add(restartNote);

        cols.setOpaque(false);
        cols.setAlignmentX(LEFT_ALIGNMENT);
        videoCard = videoCard();
        rightColumn = new JPanel();
        rightColumn.setOpaque(false);
        rightColumn.setLayout(new BoxLayout(rightColumn, BoxLayout.Y_AXIS));
        rightColumn.add(audioCard());
        rightColumn.add(Box.createVerticalStrut(18));
        rightColumn.add(recentCard());
        rightColumn.add(Box.createVerticalGlue());
        setColumns(true);
        body.add(Box.createVerticalStrut(18));
        body.add(cols);

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        add(sp, BorderLayout.CENTER);
        // Two columns when there's room, one below the other when there isn't (no sideways scrolling).
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

    /** The page body: as wide as the window, scrolling only up and down. */
    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(20, r.height - 40); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    private void relayout(int viewportWidth) {
        boolean two = viewportWidth >= 1100;
        if (two != twoColumns) setColumns(two);
        hero.setCompact(viewportWidth - 52 < 960);
    }

    private void setColumns(boolean two) {
        twoColumns = two;
        cols.removeAll();
        if (two) {
            cols.setLayout(new GridLayout(1, 2, 22, 0));
            cols.add(videoCard);
            cols.add(rightColumn);
        } else {
            cols.setLayout(new BoxLayout(cols, BoxLayout.Y_AXIS));
            videoCard.setAlignmentX(LEFT_ALIGNMENT);
            rightColumn.setAlignmentX(LEFT_ALIGNMENT);
            cols.add(videoCard);
            cols.add(Box.createVerticalStrut(18));
            cols.add(rightColumn);
        }
        cols.revalidate();
        cols.repaint();
    }

    // ------------------------------------------------------------------ cards

    private JPanel card(String title) {
        JPanel c = Theme.roundCard(new GridBagLayout());
        c.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints g = gbc(0);
        g.gridwidth = 2;
        c.add(Theme.label(title, 15f, true, Theme.TEXT), g);
        return c;
    }

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
        // Combos may be narrower than their longest item rather than push the card wider than the window.
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

    /** Pushes a card's rows to the top. */
    private void end(JPanel card) {
        GridBagConstraints a = gbc(row++);
        a.gridx = 0;
        a.gridwidth = 2;
        a.weighty = 1;
        card.add(Box.createVerticalGlue(), a);
    }

    private JPanel videoCard() {
        row = 1;
        JPanel c = card("Video");
        JLabel captured = Theme.label("Minecraft (Evade Client)", 13f, false, Theme.TEXT);
        captured.setToolTipText("Evade Clips records the Minecraft window only, while Minecraft with Evade Client is open");
        line(c, "Captures", captured);
        line(c, "Frame rate", fps);
        line(c, "Resolution", res);
        line(c, "Quality", quality);
        line(c, "Codec", codec);
        line(c, "Encoder", encoder);
        line(c, "Replay bitrate", replayRate);
        recordRate.setToolTipText("A different bitrate for full recordings runs a second encode while recording");
        line(c, "Recording bitrate", recordRate);
        line(c, "Data use", dataRate);
        cursor.setOpaque(false);
        full(c, cursor);
        JLabel note = Theme.label("<html><body style='width:330px'>Replays cost nothing until you save: the buffer is already encoded, so saving is instant. "
                + "A GPU encoder keeps your FPS up while you play.</body></html>", 11.5f, false, Theme.FAINT);
        full(c, note);
        end(c);
        return c;
    }

    private JPanel audioCard() {
        row = 1;
        JPanel c = card("Audio");
        sysAudio.setOpaque(false);
        full(c, sysAudio);
        line(c, "Device", sysDevice);
        mic.setOpaque(false);
        full(c, mic);
        line(c, "Device", micDevice);
        micVol.setOpaque(false);
        line(c, "Mic volume", micVol);
        voice.setOpaque(false);
        voice.setToolTipText("Set your chat app's output to a virtual cable (e.g. VB-Cable) and pick it here, so friends' voices can be muted per clip");
        full(c, voice);
        line(c, "Device", voiceDevice);
        end(c);
        return c;
    }

    private JPanel recentCard() {
        row = 1;
        JPanel c = card("Latest captures");
        recent.setOpaque(false);
        recent.setLayout(new BoxLayout(recent, BoxLayout.Y_AXIS));
        full(c, recent);
        end(c);
        return c;
    }

    private void refreshRecent() {
        List<Clip> list = new ArrayList<>();
        for (Clip c : app.library.query("", "all", null, "newest")) {
            if (c.kind != Clip.Kind.REPLAY && c.kind != Clip.Kind.RECORDING) continue;
            list.add(c);
            if (list.size() >= 5) break;
        }
        // The library changes often (every new thumbnail): rebuild only when what's listed changed.
        StringBuilder key = new StringBuilder(app.settings.hotkeySaveClip).append(app.minecraftRunning());
        for (Clip c : list) key.append('|').append(c.id).append(c.title).append(c.duration).append(Theme.ago(c.created));
        if (key.toString().equals(recentKey)) return;
        recentKey = key.toString();
        recent.removeAll();
        for (Clip c : list) {
            JButton b = new JButton("<html><b>" + esc(c.title) + "</b>&nbsp;&nbsp;<span style='color:#9B9BA6'>" + Theme.time(c.duration) + " · " + Theme.ago(c.created) + "</span></html>",
                    Theme.icon(c.kind == Clip.Kind.REPLAY ? Theme.Glyph.CLOCK : Theme.Glyph.RECORD, 16, c.kind == Clip.Kind.REPLAY ? Theme.TEXT : Theme.RED));
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setHorizontalAlignment(JButton.LEFT);
            b.setRequestFocusEnabled(false); // Tab reaches it; a click doesn't take focus
            b.getAccessibleContext().setAccessibleName("Open " + (c.title == null ? "clip" : c.title) + " in the editor");
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            b.addActionListener(e -> app.openEditor.accept(c));
            recent.add(b);
        }
        if (list.isEmpty()) recent.add(Theme.label(app.minecraftRunning() ? "Nothing yet. Press " + app.settings.hotkeySaveClip + " in Minecraft to save a replay."
                : App.NEED_MINECRAFT, 12.5f, false, Theme.FAINT));
        recent.revalidate();
        recent.repaint();
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
            mic.setSelected(s.recordMic);
            micVol.setValue((int) Math.round(s.micVolume * 100));
            voice.setSelected(s.recordVoice);
            loadReplayLengths(s.replaySeconds);
            for (int i = 0; i < POST_ROLLS.length; i++) if (POST_ROLLS[i] == s.replayAfterSeconds) postRoll.setSelectedIndex(i);
        } finally {
            loading = false;
        }
        refreshEncoders();
        updateEnabled();
        if (hero != null) { hero.refreshControls(); hero.repaint(); }
    }

    /** The replay-length list, with {@code current} added if it isn't one of the usual lengths. */
    private void loadReplayLengths(int current) {
        lenValues.clear();
        for (int v : REPLAY_LENS) lenValues.add(v);
        if (!lenValues.contains(current)) { lenValues.add(current); lenValues.sort(null); }
        DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
        for (int v : lenValues) m.addElement(lengthName(v));
        replayLen.setModel(m);
        replayLen.setSelectedIndex(lenValues.indexOf(current));
    }

    /** "30 seconds", "2.5 minutes", "1 minute", or "1 min 45 s" for an unusual length. */
    static String lengthName(int secs) {
        if (secs < 60) return secs + " seconds";
        if (secs % 60 == 0) return secs / 60 + (secs == 60 ? " minute" : " minutes");
        if (secs % 30 == 0) return String.format(java.util.Locale.ROOT, "%.1f minutes", secs / 60.0);
        return label(secs);
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
        SwingUtilities.invokeLater(() -> {
            devices = d;
            loading = true;
            try {
                sysDevice.removeAllItems();
                micDevice.removeAllItems();
                voiceDevice.removeAllItems();
                voiceDevice.addItem("Choose a device");
                sysDevice.addItem("Default" + (defSys == null ? " (none found)" : ""));
                micDevice.addItem("Default" + (defMic == null ? " (none found)" : ""));
                for (String name : d.keySet()) { sysDevice.addItem(name); micDevice.addItem(name); voiceDevice.addItem(name); }
                selectDevice(sysDevice, app.settings.systemAudioDevice);
                selectDevice(micDevice, app.settings.micDevice);
                selectDevice(voiceDevice, app.settings.voiceDevice);
            } finally {
                loading = false;
            }
            noDefaultSys = defSys == null;
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

    private void wire() {
        Runnable apply = () -> {
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
            if (devicesLoaded) s.systemAudioDevice = deviceId(sysDevice);
            s.recordMic = mic.isSelected();
            if (devicesLoaded) s.micDevice = deviceId(micDevice);
            s.micVolume = micVol.getValue() / 100.0;
            s.recordVoice = voice.isSelected();
            if (devicesLoaded) s.voiceDevice = deviceId(voiceDevice);
            s.save();
            updateEnabled();
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
        for (JCheckBox b : List.of(cursor, sysAudio, mic, voice)) b.addActionListener(e -> apply.run());
        micVol.addChangeListener(e -> { if (!micVol.getValueIsAdjusting()) apply.run(); });
        postRoll.setToolTipText("Keep capturing this long after you press the key, so the clip shows what happens next");
        postRoll.addActionListener(e -> {
            if (loading) return;
            app.settings.replayAfterSeconds = POST_ROLLS[postRoll.getSelectedIndex()];
            app.settings.save();
        });
        replayLen.addActionListener(e -> {
            if (loading || replayLen.getSelectedIndex() < 0) return;
            int v = lenValues.get(replayLen.getSelectedIndex());
            app.settings.replaySeconds = v;
            app.settings.save();
            updateDataRate();
            hero.refreshControls();
            hero.repaint();
        });
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

    /**
     * "Up to 65 MB per minute · 30 s buffer ≈ 33 MB": the worst case, since the bitrate is capped. Worked out in
     * the background (picking the encoder may wait for the encoder tests).
     */
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
            String text = String.format("<html>up to <b>%.0f MB</b> per minute (%dx%d, %s)<br>replay buffer: at most %.0f MB on disk</html>",
                    mbMin, out.width, out.height, Ffmpeg.isHevc(enc) ? "HEVC" : "H.264", mbMin * (replaySecs + 3) / 60.0);
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
        micDevice.setEnabled(mic.isSelected());
        micVol.setEnabled(mic.isSelected());
        voiceDevice.setEnabled(voice.isSelected());
        // The warning always matches the settings now (it clears once the cause is fixed).
        String w = " ";
        if (!s.startsWith("test") && CaptureSource.linuxWayland())
            w = "Screen capture on Linux needs an X11 session; on Wayland clips will be black. Choose the 'on Xorg' session at login.";
        else if ((Ffmpeg.WINDOWS || Ffmpeg.MAC) && noDefaultSys && st.recordSystemAudio && st.systemAudioDevice.isBlank())
            w = Ffmpeg.WINDOWS
                    ? "No game-audio device found. Enable \"Stereo Mix\" in Sound settings → Recording, or install VB-Cable, then pick it above."
                    : "No game-audio device found. Install BlackHole to record game audio, then pick it above.";
        warn.setText(w);
        warn.setVisible(!w.isBlank());
    }

    // ------------------------------------------------------------------ hero

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

    /** Big status card: instant replay switch + buffer meter, save button, record button. */
    private final class Hero extends JPanel {
        final JButton replayToggle = Theme.button("", null, false);
        final JButton save = Theme.button("", Theme.icon(Theme.Glyph.CLOCK, 18, Color.WHITE), true);
        final JButton record = Theme.button("", REC_ICON, false);
        final JButton marker = Theme.button("Marker", Theme.icon(Theme.Glyph.BOOKMARK, 16, Theme.GOLD), false);
        /** Narrow windows: the save / record / marker buttons go on a second row. */
        private boolean compact;
        private Rectangle detailsHit = new Rectangle();

        Hero() {
            setLayout(null);
            setOpaque(false);
            setHeights();
            replayToggle.addActionListener(e -> app.toggleReplay(!app.engine.isLive()));
            save.addActionListener(e -> app.saveClip(0, null, null, null));
            record.addActionListener(e -> app.toggleRecording());
            marker.addActionListener(e -> app.marker());
            marker.setToolTipText("Bookmark this moment (" + app.settings.hotkeyMarker + ")");
            replayLen.setToolTipText("How much the replay buffer keeps");
            replayLen.getAccessibleContext().setAccessibleName("Replay length");
            postRoll.getAccessibleContext().setAccessibleName("Seconds kept after the key press");
            add(replayToggle);
            add(save);
            add(record);
            add(marker);
            add(replayLen);
            add(postRoll);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (detailsHit.contains(e.getPoint())) showLog(); }
                @Override public void mouseMoved(MouseEvent e) {
                    setCursor(detailsHit.contains(e.getPoint()) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            // Only the pulsing dot and the buffer meter move; nothing runs while the page isn't on screen.
            new Timer(250, e -> {
                if (!isShowing()) return;
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w instanceof Frame f && (f.getExtendedState() & Frame.ICONIFIED) != 0) return;
                refreshControls();
                repaint();
            }).start();
            // The empty "Latest captures" list says whether Minecraft is open.
            new Timer(3000, e -> { if (isShowing()) refreshRecent(); }).start();
        }

        private void setHeights() {
            int h = compact ? 220 : 172;
            setPreferredSize(new Dimension(600, h));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            setMinimumSize(new Dimension(300, h));
        }

        void setCompact(boolean c) {
            if (c == compact) return;
            compact = c;
            setHeights();
            revalidate();
            repaint();
        }

        /** Brings the buttons up to date with the engine. Changes only what differs (never from paint). */
        void refreshControls() {
            CaptureEngine.Status st = app.status;
            boolean live = st.state() == CaptureEngine.State.LIVE, starting = st.state() == CaptureEngine.State.STARTING;
            text(replayToggle, live || starting ? "Turn off" : "Turn on");
            text(save, "Save last " + label(app.settings.replaySeconds));
            if (save.isEnabled() != live) save.setEnabled(live);
            text(record, st.recording() ? "Stop  " + Theme.time(st.recordingSeconds()) : "Start recording");
            Icon icon = st.recording() ? STOP_ICON : REC_ICON;
            if (record.getIcon() != icon) record.setIcon(icon);
            if (record.isEnabled() != ffmpegOk) record.setEnabled(ffmpegOk);
            if (marker.isEnabled() != live) marker.setEnabled(live);
            if (pendingNote && !st.recording()) { pendingNote = false; restartNote.setText(" "); restartNote.setVisible(false); }
        }

        private void text(JButton b, String t) { if (!t.equals(b.getText())) b.setText(t); }

        @Override public void doLayout() {
            int w = getWidth();
            replayLen.setBounds(24, 116, 130, 34);
            postRoll.setBounds(160, 116, 124, 34);
            replayToggle.setBounds(290, 116, 110, 34);
            int by = compact ? 164 : 112, bx = w - 24;
            marker.setBounds(bx -= 110, by, 110, 40);
            record.setBounds(bx -= 186, by, 176, 40);
            save.setBounds(bx -= 226, by, 216, 40);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            CaptureEngine.Status st = app.status;
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.CARD);
            g.fill(new RoundRectangle2D.Double(0, 0, w, h, 18, 18));
            boolean live = st.state() == CaptureEngine.State.LIVE, starting = st.state() == CaptureEngine.State.STARTING;
            boolean err = st.state() == CaptureEngine.State.ERROR;

            // status dot
            long t = System.currentTimeMillis();
            Color dot = err ? Theme.GOLD : live ? (st.recording() ? Theme.RED : Theme.OK) : starting ? Theme.GOLD : Theme.FAINT;
            float pulse = (float) (0.5 + 0.5 * Math.sin(t / 300.0));
            if (live || st.recording()) {
                g.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), (int) (60 * pulse)));
                g.fillOval(18, 20, 28, 28);
            }
            g.setColor(dot);
            g.fillOval(25, 27, 14, 14);

            g.setFont(Theme.interBold.deriveFont(20f));
            g.setColor(Theme.TEXT);
            String head = st.recording() ? "Recording  " + Theme.time(st.recordingSeconds())
                    : live ? "Instant replay is on" : starting ? "Starting capture…" : err ? "Capture stopped"
                    : !app.minecraftRunning() ? "Waiting for Minecraft" : "Instant replay is off";
            g.drawString(head, 56, 42);
            g.setFont(Theme.inter.deriveFont(13f));
            g.setColor(Theme.DIM);
            detailsHit = new Rectangle();
            if (err && ffmpegOk) {
                // The readable reason (two lines at most), and the full log behind "Details…".
                String msg = st.message() == null || st.message().isBlank() ? "Capture stopped unexpectedly." : st.message();
                List<String> lines = Toast.wrap(g.getFontMetrics(), msg, w - 80);
                if (lines.size() > 2) lines = lines.subList(0, 2);
                for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), 56, 64 + i * 18);
                g.setFont(Theme.interBold.deriveFont(12.5f));
                g.setColor(Theme.RED_HI);
                int dy = 64 + lines.size() * 18;
                String details = "Details…";
                int dw = g.getFontMetrics().stringWidth(details);
                g.drawString(details, 56, dy);
                detailsHit = new Rectangle(52, dy - 14, dw + 8, 20);
                g.dispose();
                return;
            }
            String sub;
            if (!ffmpegOk) sub = app.ffmpegDownloading ? "Downloading ffmpeg… " + Math.round(app.ffmpegProgress * 100) + "%. Recording starts working as soon as it's done."
                    : "ffmpeg wasn't found. Put ffmpeg next to Evade Clips, add it to PATH, or set it in Settings.";
            else if (live && app.currentGame != null) sub = "Minecraft detected  ·  " + app.settings.hotkeySaveClip + " saves the last " + label(app.settings.replaySeconds)
                    + "  ·  " + app.settings.hotkeyRecord + " to " + (st.recording() ? "stop" : "start") + " recording";
            else if (live) sub = "Press " + app.settings.hotkeySaveClip + " to save the last " + label(app.settings.replaySeconds) + "  ·  "
                    + app.settings.hotkeyRecord + " to " + (st.recording() ? "stop" : "start") + " recording  ·  " + app.settings.hotkeyMarker + " for a marker"
                    + (st.encoder().isEmpty() ? "" : "  ·  " + Ffmpeg.encoderLabel(st.encoder()));
            else if (!app.minecraftRunning()) sub = App.NEED_MINECRAFT + (app.settings.autoReplayForGames && app.settings.replayEnabled
                    ? " Instant replay turns on by itself when it starts." : "");
            else sub = "Turn it on to keep the last " + label(app.settings.replaySeconds) + " ready to save at any moment.";
            g.drawString(LibraryPanel.ellipsize(g, sub, w - 80), 56, 64);

            // buffer meter
            double frac = Math.min(1, st.bufferedSeconds() / Math.max(1, app.settings.replaySeconds));
            int mx = 24, my = 84, mw = w - 48;
            g.setColor(Theme.CARD_HI);
            g.fill(new RoundRectangle2D.Double(mx, my, mw, 8, 8, 8));
            if (live) {
                g.setColor(st.recording() ? Theme.RED : Theme.OK);
                g.fill(new RoundRectangle2D.Double(mx, my, Math.max(8, mw * frac), 8, 8, 8));
            }
            g.setFont(Theme.inter.deriveFont(11.5f));
            g.setColor(Theme.FAINT);
            String buf = live ? Math.round(st.bufferedSeconds()) + " s buffered" : "";
            g.drawString(buf, mx + mw - g.getFontMetrics().stringWidth(buf), my + 24);
            g.dispose();
        }
    }

    /** "30 s", "2 min", "2 min 30 s". */
    static String label(int secs) {
        if (secs < 60) return secs + " s";
        return secs % 60 == 0 ? secs / 60 + " min" : secs / 60 + " min " + secs % 60 + " s";
    }
}
