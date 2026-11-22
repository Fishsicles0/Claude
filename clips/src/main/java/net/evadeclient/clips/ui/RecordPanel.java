package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.CaptureSource;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.Ffmpeg;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Capture: instant replay status and controls, recording, and every capture setting. */
public final class RecordPanel extends JPanel {
    private final App app;
    private Hero hero;
    private final JComboBox<String> source = new JComboBox<>(new String[]{"Entire screen", "Game window", "Region"});
    private final JComboBox<String> monitor = new JComboBox<>();
    private final JTextField window = new JTextField();
    private final JSpinner rx = spin(0, -10000, 20000), ry = spin(0, -10000, 20000), rw = spin(1280, 16, 20000), rh = spin(720, 16, 20000);
    private final JComboBox<String> fps = new JComboBox<>(new String[]{"30", "60", "120", "144"});
    private final JComboBox<String> res = new JComboBox<>(new String[]{"Native", "1440p", "1080p", "720p"});
    private final JComboBox<String> quality = new JComboBox<>(new String[]{"Small files", "Balanced", "Best quality"});
    private final JComboBox<String> encoder = new JComboBox<>();
    private final JComboBox<String> codec = new JComboBox<>(new String[]{"Auto (smallest that's fast)", "H.264 (plays everywhere)", "HEVC (≈40% smaller)"});
    private final JLabel dataRate = Theme.label(" ", 12f, false, Theme.DIM);
    private final JCheckBox cursor = new JCheckBox("Capture mouse cursor");
    private final JCheckBox sysAudio = new JCheckBox("Record game / desktop audio");
    private final JComboBox<String> sysDevice = new JComboBox<>();
    private final JCheckBox mic = new JCheckBox("Record microphone");
    private final JComboBox<String> micDevice = new JComboBox<>();
    private final JSlider micVol = new JSlider(0, 200, 100);
    private final JCheckBox voice = new JCheckBox("Record voice chat on its own track");
    private final JComboBox<String> voiceDevice = new JComboBox<>();
    private final JComboBox<String> replayLen = new JComboBox<>(new String[]{"15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes"});
    private static final int[] REPLAY_LENS = {15, 30, 60, 120, 300, 600};
    private final JComboBox<String> postRoll = new JComboBox<>(new String[]{"+0 s after", "+3 s after", "+5 s after", "+10 s after"});
    private static final int[] POST_ROLLS = {0, 3, 5, 10};
    private final JLabel warn = Theme.label(" ", 12f, false, Theme.GOLD);
    private final JPanel recent = new JPanel();
    private final Timer restartLater;
    private boolean loading;
    private Map<String, String> devices = Map.of();

    public RecordPanel(App app) {
        super(new BorderLayout());
        this.app = app;
        hero = new Hero();
        setBackground(Theme.PANEL);
        restartLater = new Timer(700, e -> new Thread(() -> {
            try { app.engine.restart(); } catch (Exception ex) { app.notify("Capture restart failed", ex.getMessage(), true); }
        }, "capture-restart").start());
        restartLater.setRepeats(false);

        JPanel body = new JPanel();
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

        JPanel cols = new JPanel(new GridLayout(1, 2, 22, 0));
        cols.setOpaque(false);
        cols.setAlignmentX(LEFT_ALIGNMENT);
        cols.add(videoCard());
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(audioCard());
        right.add(Box.createVerticalStrut(18));
        right.add(recentCard());
        right.add(Box.createVerticalGlue());
        cols.add(right);
        body.add(Box.createVerticalStrut(18));
        body.add(cols);

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        add(sp, BorderLayout.CENTER);

        load();
        wire();
        app.engine.onStatus(s -> SwingUtilities.invokeLater(hero::repaint));
        app.library.onChange(() -> SwingUtilities.invokeLater(this::refreshRecent));
        new Thread(this::loadDevices, "audio-devices").start();
        refreshRecent();
    }

    private static JSpinner spin(int v, int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, min, max, 1));
        s.setPreferredSize(new Dimension(80, 30));
        return s;
    }

    // ------------------------------------------------------------------ cards

    private JPanel card(String title) {
        JPanel c = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.hq(g);
                g.setColor(Theme.CARD);
                g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 16, 16));
                g.dispose();
            }
        };
        c.setOpaque(false);
        Theme.pad(c, 16, 18, 18, 18);
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
        card.add(Theme.label(label, 13f, false, Theme.DIM), a);
        GridBagConstraints b = gbc(row++);
        b.gridx = 1;
        b.weightx = 1;
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
        line(c, "Capture", source);
        line(c, "Monitor", monitor);
        window.putClientProperty("JTextField.placeholderText", "Part of the window title, e.g. Minecraft");
        line(c, "Window", window);
        JPanel region = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        region.setOpaque(false);
        region.add(Theme.label("x", 12f, false, Theme.FAINT)); region.add(rx);
        region.add(Theme.label("y", 12f, false, Theme.FAINT)); region.add(ry);
        region.add(Theme.label("w", 12f, false, Theme.FAINT)); region.add(rw);
        region.add(Theme.label("h", 12f, false, Theme.FAINT)); region.add(rh);
        line(c, "Region", region);
        line(c, "Frame rate", fps);
        line(c, "Resolution", res);
        line(c, "Quality", quality);
        line(c, "Codec", codec);
        line(c, "Encoder", encoder);
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
        recent.removeAll();
        List<Clip> list = app.library.query("", "all", null, "newest");
        int n = 0;
        for (Clip c : list) {
            if (c.kind != Clip.Kind.REPLAY && c.kind != Clip.Kind.RECORDING) continue;
            if (n++ >= 5) break;
            JButton b = new JButton("<html><b>" + esc(c.title) + "</b>&nbsp;&nbsp;<span style='color:#9B9BA6'>" + Theme.time(c.duration) + " · " + Theme.ago(c.created) + "</span></html>",
                    Theme.icon(c.kind == Clip.Kind.REPLAY ? Theme.Glyph.CLOCK : Theme.Glyph.RECORD, 16, c.kind == Clip.Kind.REPLAY ? Theme.TEXT : Theme.RED));
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setHorizontalAlignment(JButton.LEFT);
            b.setFocusable(false);
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            b.addActionListener(e -> app.openEditor.accept(c));
            recent.add(b);
        }
        if (n == 0) recent.add(Theme.label("Nothing yet. Press " + app.settings.hotkeySaveClip + " to save a replay.", 12.5f, false, Theme.FAINT));
        recent.revalidate();
        recent.repaint();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }

    // ------------------------------------------------------------------ settings binding

    private void load() {
        loading = true;
        var s = app.settings;
        source.setSelectedIndex(switch (s.source) { case "window" -> 1; case "region" -> 2; default -> 0; });
        List<Rectangle> mons = CaptureSource.monitors();
        monitor.removeAllItems();
        for (int i = 0; i < mons.size(); i++) monitor.addItem("Monitor " + (i + 1) + "  (" + mons.get(i).width + "×" + mons.get(i).height + ")");
        monitor.setSelectedIndex(Math.max(0, Math.min(mons.size() - 1, s.monitor)));
        window.setText(s.windowTitle);
        rx.setValue(s.regionX); ry.setValue(s.regionY); rw.setValue(s.regionW); rh.setValue(s.regionH);
        fps.setSelectedItem(String.valueOf(s.fps));
        res.setSelectedIndex(switch (s.resolution) { case 0 -> 0; case 1440 -> 1; case 720 -> 3; default -> 2; });
        quality.setSelectedIndex(s.quality - 1);
        codec.setSelectedIndex(switch (s.codec) { case "h264" -> 1; case "hevc" -> 2; default -> 0; });
        encoder.removeAllItems();
        encoder.addItem("Automatic (best available)");
        if (Ffmpeg.available()) {
            for (String e : Ffmpeg.workingEncoders("h264")) encoder.addItem(Ffmpeg.encoderLabel(e));
            for (String e : Ffmpeg.workingEncoders("hevc")) encoder.addItem(Ffmpeg.encoderLabel(e));
        }
        if (!s.encoder.isBlank()) encoder.setSelectedItem(Ffmpeg.encoderLabel(s.encoder));
        cursor.setSelected(s.captureCursor);
        sysAudio.setSelected(s.recordSystemAudio);
        mic.setSelected(s.recordMic);
        micVol.setValue((int) Math.round(s.micVolume * 100));
        voice.setSelected(s.recordVoice);
        int li = 1;
        for (int i = 0; i < REPLAY_LENS.length; i++) if (REPLAY_LENS[i] == s.replaySeconds) li = i;
        replayLen.setSelectedIndex(li);
        loading = false;
        updateEnabled();
    }

    private void loadDevices() {
        Map<String, String> d = CaptureSource.audioDevices();
        String defSys = CaptureSource.defaultSystemAudio(), defMic = CaptureSource.defaultMic();
        SwingUtilities.invokeLater(() -> {
            devices = d;
            loading = true;
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
            loading = false;
            if ((Ffmpeg.WINDOWS || Ffmpeg.MAC) && defSys == null && app.settings.systemAudioDevice.isBlank() && app.settings.recordSystemAudio)
                warn.setText(Ffmpeg.WINDOWS
                        ? "No desktop-audio device found. Enable \"Stereo Mix\" in Sound settings → Recording, or install VB-Cable, then pick it above."
                        : "No desktop-audio device found. Install BlackHole to record game audio, then pick it above.");
        });
    }

    private void selectDevice(JComboBox<String> box, String id) {
        if (id == null || id.isBlank()) { box.setSelectedIndex(0); return; }
        for (Map.Entry<String, String> e : devices.entrySet()) if (e.getValue().equals(id)) { box.setSelectedItem(e.getKey()); return; }
        box.setSelectedIndex(0);
    }

    private String deviceId(JComboBox<String> box) {
        Object sel = box.getSelectedItem();
        if (sel == null || box.getSelectedIndex() == 0) return "";
        return devices.getOrDefault(sel.toString(), "");
    }

    private void wire() {
        Runnable apply = () -> {
            if (loading) return;
            var s = app.settings;
            s.source = new String[]{"screen", "window", "region"}[source.getSelectedIndex()];
            s.monitor = Math.max(0, monitor.getSelectedIndex());
            s.windowTitle = window.getText().trim();
            s.regionX = (int) rx.getValue(); s.regionY = (int) ry.getValue(); s.regionW = (int) rw.getValue(); s.regionH = (int) rh.getValue();
            s.fps = Integer.parseInt((String) fps.getSelectedItem());
            s.resolution = new int[]{0, 1440, 1080, 720}[res.getSelectedIndex()];
            s.quality = quality.getSelectedIndex() + 1;
            s.codec = new String[]{"auto", "h264", "hevc"}[codec.getSelectedIndex()];
            s.encoder = "";
            if (encoder.getSelectedIndex() > 0 && Ffmpeg.available()) {
                for (String c2 : List.of("h264", "hevc"))
                    for (String e : Ffmpeg.workingEncoders(c2)) if (Ffmpeg.encoderLabel(e).equals(encoder.getSelectedItem())) { s.encoder = e; s.codec = c2; }
            }
            s.captureCursor = cursor.isSelected();
            s.recordSystemAudio = sysAudio.isSelected();
            s.systemAudioDevice = deviceId(sysDevice);
            s.recordMic = mic.isSelected();
            s.micDevice = deviceId(micDevice);
            s.micVolume = micVol.getValue() / 100.0;
            s.recordVoice = voice.isSelected();
            s.voiceDevice = deviceId(voiceDevice);
            s.save();
            updateEnabled();
            if (app.engine.isLive()) restartLater.restart();
        };
        for (JComboBox<?> b : List.of(source, monitor, fps, res, quality, codec, encoder, sysDevice, micDevice, voiceDevice)) b.addActionListener(e -> apply.run());
        for (JCheckBox b : List.of(cursor, sysAudio, mic, voice)) b.addActionListener(e -> apply.run());
        for (JSpinner sp : List.of(rx, ry, rw, rh)) sp.addChangeListener(e -> apply.run());
        micVol.addChangeListener(e -> { if (!micVol.getValueIsAdjusting()) apply.run(); });
        window.addActionListener(e -> apply.run());
        window.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { apply.run(); } });
        for (int i = 0; i < POST_ROLLS.length; i++) if (POST_ROLLS[i] == app.settings.replayAfterSeconds) postRoll.setSelectedIndex(i);
        postRoll.setToolTipText("Keep capturing this long after you press the key, so the clip shows what happens next");
        postRoll.addActionListener(e -> {
            app.settings.replayAfterSeconds = POST_ROLLS[postRoll.getSelectedIndex()];
            app.settings.save();
        });
        replayLen.addActionListener(e -> {
            if (loading) return;
            app.settings.replaySeconds = REPLAY_LENS[replayLen.getSelectedIndex()];
            app.settings.save();
            hero.repaint();
        });
    }

    /** "Up to 65 MB per minute · 30 s buffer ≈ 33 MB": the worst case, since the bitrate is capped. */
    private void updateDataRate() {
        if (!Ffmpeg.available()) return;
        var s = app.settings;
        String enc = net.evadeclient.clips.core.CaptureEngine.previewEncoder(s);
        var in = CaptureSource.build(s);
        long bits = Ffmpeg.liveCapBits(enc, s.quality, s.fps, in.outW(), in.outH()) + 160_000L * in.audioTracks();
        double mbMin = bits * 60 / 8e6;
        dataRate.setText(String.format("<html>up to <b>%.0f MB</b> per minute (%dx%d, %s)<br>replay buffer: at most %.0f MB on disk</html>",
                mbMin, in.outW(), in.outH(), Ffmpeg.isHevc(enc) ? "HEVC" : "H.264", mbMin * (s.replaySeconds + 3) / 60.0));
    }

    private void updateEnabled() {
        updateDataRate();
        String s = app.settings.source;
        monitor.setEnabled(s.equals("screen"));
        window.setEnabled(s.equals("window"));
        for (JSpinner sp : List.of(rx, ry, rw, rh)) sp.setEnabled(s.equals("region"));
        if (s.equals("window") && !Ffmpeg.WINDOWS) warn.setText("Window capture is Windows-only; the whole monitor is captured instead.");
        sysDevice.setEnabled(sysAudio.isSelected());
        micDevice.setEnabled(mic.isSelected());
        micVol.setEnabled(mic.isSelected());
        voiceDevice.setEnabled(voice.isSelected());
    }

    // ------------------------------------------------------------------ hero

    /** Big status card: instant replay switch + buffer meter, save button, record button. */
    private final class Hero extends JPanel {
        final JButton replayToggle = Theme.button("", null, false);
        final JButton save = Theme.button("", Theme.icon(Theme.Glyph.CLOCK, 18, Color.WHITE), true);
        final JButton record = Theme.button("", Theme.icon(Theme.Glyph.RECORD, 16, Theme.RED), false);
        final JButton marker = Theme.button("Marker", Theme.icon(Theme.Glyph.BOOKMARK, 16, Theme.GOLD), false);

        Hero() {
            setLayout(null);
            setOpaque(false);
            setPreferredSize(new Dimension(900, 172));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 172));
            replayToggle.addActionListener(e -> app.toggleReplay(!app.engine.isLive()));
            save.addActionListener(e -> app.saveClip(0, null, null, null));
            record.addActionListener(e -> app.toggleRecording());
            marker.addActionListener(e -> app.marker());
            marker.setToolTipText("Bookmark this moment (" + app.settings.hotkeyMarker + ")");
            replayLen.setToolTipText("How much the replay buffer keeps");
            add(replayToggle);
            add(save);
            add(record);
            add(marker);
            add(replayLen);
            add(postRoll);
            new Timer(250, e -> repaint()).start();
        }

        @Override public void doLayout() {
            int w = getWidth();
            replayLen.setBounds(24, 116, 130, 34);
            postRoll.setBounds(160, 116, 124, 34);
            replayToggle.setBounds(290, 116, 110, 34);
            int bx = w - 24;
            marker.setBounds(bx -= 110, 112, 110, 40);
            record.setBounds(bx -= 186, 112, 176, 40);
            save.setBounds(bx -= 226, 112, 216, 40);
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
                    : live ? "Instant replay is on" : starting ? "Starting capture…" : err ? "Capture stopped" : "Instant replay is off";
            g.drawString(head, 56, 42);
            g.setFont(Theme.inter.deriveFont(13f));
            g.setColor(Theme.DIM);
            String sub;
            if (!Ffmpeg.available()) sub = "ffmpeg wasn't found. Put ffmpeg next to Evade Clips, add it to PATH, or set it in Settings.";
            else if (err) sub = st.message();
            else if (live && app.currentGame != null) sub = app.currentGame + " detected  ·  " + app.settings.hotkeySaveClip + " saves the last " + label(app.settings.replaySeconds)
                    + "  ·  " + app.settings.hotkeyRecord + " to " + (st.recording() ? "stop" : "start") + " recording";
            else if (live) sub = "Press " + app.settings.hotkeySaveClip + " to save the last " + label(app.settings.replaySeconds) + "  ·  "
                    + app.settings.hotkeyRecord + " to " + (st.recording() ? "stop" : "start") + " recording  ·  " + app.settings.hotkeyMarker + " for a marker"
                    + (st.encoder().isEmpty() ? "" : "  ·  " + Ffmpeg.encoderLabel(st.encoder()));
            else if (app.settings.autoReplayForGames) sub = "Waiting for a game. Instant replay turns on by itself when one starts, or turn it on now.";
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

            replayToggle.setText(live || starting ? "Turn off" : "Turn on");
            save.setText("Save last " + label(app.settings.replaySeconds));
            save.setEnabled(live);
            record.setText(st.recording() ? "Stop  " + Theme.time(st.recordingSeconds()) : "Start recording");
            record.setIcon(Theme.icon(st.recording() ? Theme.Glyph.STOP : Theme.Glyph.RECORD, 16, Theme.RED));
            record.setEnabled(Ffmpeg.available());
            marker.setEnabled(live);
        }
    }

    static String label(int secs) { return secs < 60 ? secs + " s" : secs / 60 + " min"; }
}
