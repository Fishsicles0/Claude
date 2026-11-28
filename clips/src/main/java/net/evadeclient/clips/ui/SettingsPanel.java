package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Hotkeys;
import net.evadeclient.clips.core.Settings;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Storage, hotkeys, behavior and integrations, one card per section. */
public final class SettingsPanel extends JPanel {
    private static final String VOICE_LABEL = "Listen for \"clip that\", \"clip it\", \"save clip\", \"save that\", \"start recording\", \"stop recording\" and \"mark that\"";
    /**
     * Notes and long check box labels wrap to the card's width: their text is laid out again whenever the page
     * changes width (Swing's HTML needs the width up front).
     */
    private final List<JComponent> fitted = new ArrayList<>();
    private int innerWidth = 760;
    private final App app;
    private final JPanel body = new Body();
    /** The section card rows are added to. */
    private JPanel card;
    /** "Global hotkeys couldn't start…" or the hotkeys that can't be used; hidden when everything works. */
    private final JLabel hotkeyNote = Theme.label(" ", 12f, false, Theme.GOLD);
    /** Why the local control server isn't running (port in use). */
    private final JLabel controlNote = Theme.label(" ", 12f, false, Theme.RED_HI);
    private final List<HotkeyBox> hotkeyBoxes = new ArrayList<>();

    public SettingsPanel(App app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Theme.PANEL);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Theme.PANEL);
        Theme.pad(body, 22, 26, 30, 26);
        JLabel title = Theme.label("Settings", 26f, true, Theme.TEXT);
        title.setAlignmentX(LEFT_ALIGNMENT);
        body.add(title);
        // In the launcher the tab already says "Settings".
        title.setVisible(!net.evadeclient.clips.ClipsApp.hosted);
        Settings s = app.settings;
        fit(hotkeyNote, " ", 0);
        fit(controlNote, " ", 0);

        section("Hotkeys");
        hotkeyNote.setVisible(false);
        place(hotkeyNote, 2, 8);
        row("Save replay", hotkey("Save replay", () -> s.hotkeySaveClip, v -> s.hotkeySaveClip = v));
        row("Start / stop recording", hotkey("Start / stop recording", () -> s.hotkeyRecord, v -> s.hotkeyRecord = v));
        row("Add marker", hotkey("Add marker", () -> s.hotkeyMarker, v -> s.hotkeyMarker = v));
        note("Click a box and press the key (with Ctrl / Alt / Shift if you like). Esc cancels. "
                + "In the app, Ctrl+1 to Ctrl+4 open Library, Record, Editor and Settings.");
        app.onHotkeysChanged(this::refreshHotkeyNote);
        refreshHotkeyNote();

        voiceSection(s);
        storageSection(s);
        watermarkSection(s);

        section("Library");
        check("Protect favorites: they can't be deleted (even in bulk) until you unfavorite them", s.protectFavorites, v -> s.protectFavorites = v);
        check("Preview clips when hovering them in the library", s.hoverPreview, v -> s.hoverPreview = v);

        section("Behavior");
        check("Turn on instant replay when Minecraft starts, off when it closes (no cost while you're not playing)", s.autoReplayForGames,
                v -> { s.autoReplayForGames = v; app.applyReplaySettings(); });
        note("Evade Clips records Minecraft with Evade Client, and only while it's open.");
        check("Find highlights in new recordings automatically (kills and wins from Evade Client, plus the loudest moments)", s.autoHighlights, v -> s.autoHighlights = v);
        check("Play a sound when a clip is saved", s.playSound, v -> s.playSound = v);
        check("Show a pop-up when a clip is saved", s.showToast, v -> s.showToast = v);
        check("Closing the window keeps Evade Clips running in the tray", s.minimizeToTray, v -> s.minimizeToTray = v);

        sharingSection(s);
        eventsSection(s);
        integrationsSection(s);
        ffmpegSection(s);

        section("About");
        note("Evade Clips " + version() + "  ·  part of Evade Client  ·  settings in " + Settings.appDir());
        JButton logs = Theme.button("Open logs folder", Theme.icon(Theme.Glyph.FOLDER, 16, Theme.TEXT), false);
        logs.addActionListener(e -> LibraryPanel.openFolder(Settings.appDir().resolve("logs")));
        row("Logs", small(logs));

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        add(sp, BorderLayout.CENTER);
        sp.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { fitAll(sp.getViewport().getWidth()); }
        });
    }

    /** Lays the wrapping texts out for a page {@code viewportWidth} wide (cards are at most 860 wide). */
    private void fitAll(int viewportWidth) {
        int w = Math.max(280, Math.min(860, viewportWidth - 52) - 36);
        // The cards sit in the middle of a wide window rather than hugging the left edge.
        int side = Math.max(26, (viewportWidth - 860) / 2);
        Theme.pad(body, net.evadeclient.clips.ClipsApp.hosted ? 4 : 22, side, 30, side);
        if (w == innerWidth) return;
        innerWidth = w;
        for (JComponent c : fitted) applyFit(c);
        body.revalidate();
        body.repaint();
    }

    private void applyFit(JComponent c) {
        String raw = (String) c.getClientProperty("fit.text");
        int inset = (Integer) c.getClientProperty("fit.inset");
        // "pt" is one pixel in Swing's HTML ("px" is 1.3).
        String html = raw == null || raw.isBlank() ? " " : "<html><body style='width:" + Math.max(120, innerWidth - inset) + "pt'>" + raw + "</body></html>";
        if (c instanceof JLabel l) l.setText(html);
        else if (c instanceof javax.swing.AbstractButton b) b.setText(html);
    }

    /** Makes {@code c}'s text (HTML-escaped already) wrap to the card, {@code inset} px narrower. */
    private <T extends JComponent> T fit(T c, String raw, int inset) {
        c.putClientProperty("fit.inset", inset);
        c.putClientProperty("fit.text", raw);
        fitted.add(c);
        applyFit(c);
        return c;
    }

    /** New text for a wrapping label or check box. */
    private void refit(JComponent c, String raw) {
        c.putClientProperty("fit.text", raw);
        applyFit(c);
    }

    /** As wide as the window (scrolls only up and down). */
    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(20, r.height - 40); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    // ------------------------------------------------------------------ sections

    private void voiceSection(Settings s) {
        section("Voice commands");
        JCheckBox voice = fit(new JCheckBox("", s.voiceCommands), esc(VOICE_LABEL), 28);
        voice.setOpaque(false);
        voice.setAlignmentX(LEFT_ALIGNMENT);
        voice.addActionListener(e -> {
            s.voiceCommands = voice.isSelected();
            s.save();
            if (s.voiceCommands && !net.evadeclient.clips.core.VoiceCommands.ready()) {
                int ok = JOptionPane.showConfirmDialog(this, "Voice commands need a one-time download of the offline speech engine and model (about 70 MB).\n"
                        + "Speech is recognized on this PC; nothing you say is sent anywhere. Download now?", "Voice commands", JOptionPane.OK_CANCEL_OPTION);
                if (ok != JOptionPane.OK_OPTION) { voice.setSelected(false); s.voiceCommands = false; s.save(); return; }
                voice.setEnabled(false);
                refit(voice, "Downloading the speech engine…");
                new Thread(() -> {
                    String err = null;
                    try { net.evadeclient.clips.core.VoiceCommands.downloadAll(p -> SwingUtilities.invokeLater(
                            () -> refit(voice, "Downloading the speech engine… " + Math.round(p * 100) + "%"))); }
                    catch (Exception ex) { err = ex.getMessage(); }
                    String fail = err != null ? "Download failed: " + err : app.applyVoice();
                    SwingUtilities.invokeLater(() -> {
                        voice.setEnabled(true);
                        refit(voice, esc(VOICE_LABEL));
                        if (fail != null) { JOptionPane.showMessageDialog(this, fail); voice.setSelected(false); s.voiceCommands = false; s.save(); }
                        else app.notify("Voice commands on", "Say \"clip that\"", false);
                    });
                }, "voice-model").start();
                return;
            }
            // Opening the microphone can take a moment: not on the UI thread.
            voice.setEnabled(false);
            new Thread(() -> {
                String err = app.applyVoice();
                SwingUtilities.invokeLater(() -> {
                    voice.setEnabled(true);
                    if (err != null) { JOptionPane.showMessageDialog(this, err); voice.setSelected(false); s.voiceCommands = false; s.save(); }
                });
            }, "voice-toggle").start();
        });
        Theme.pad(voice, 4, 0, 4, 0);
        place(voice, 0, 0);
        note("Uses the microphone chosen on the Record page. Only those exact phrases count, so callouts and chatting don't trigger it.");
    }

    private void storageSection(Settings s) {
        section("Storage");
        JTextField folder = new JTextField(s.clipsFolder);
        folder.setEditable(false);
        JButton change = Theme.button("Change…", null, false);
        change.addActionListener(e -> changeFolder(folder));
        JButton open = Theme.button("Open", null, false);
        open.addActionListener(e -> LibraryPanel.openFolder(app.library.root()));
        JPanel fp = new JPanel(new BorderLayout(8, 0));
        fp.setOpaque(false);
        fp.add(folder, BorderLayout.CENTER);
        JPanel fb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        fb.setOpaque(false);
        fb.add(change);
        fb.add(open);
        fp.add(fb, BorderLayout.EAST);
        row("Clips folder", fp);
        JSpinner limit = new JSpinner(new SpinnerNumberModel(s.storageLimitGb, 0, 10000, 5));
        limit.addChangeListener(e -> { s.storageLimitGb = ((Number) limit.getValue()).doubleValue(); s.save(); });
        row("Storage limit (GB)", small(limit));
        note("When the library gets bigger than this, the oldest replays are removed first, then recordings. "
                + "Favorites, exports, imports and shared clips are never removed. 0 = no limit.");
        int[] ages = {0, 7, 14, 30};
        List<Integer> ageValues = new ArrayList<>();
        for (int a : ages) ageValues.add(a);
        if (!ageValues.contains(s.replayMaxAgeDays)) ageValues.add(s.replayMaxAgeDays);
        JComboBox<String> age = new JComboBox<>();
        for (int a : ageValues) age.addItem(a == 0 ? "Never" : "After " + a + " days");
        age.setSelectedIndex(ageValues.indexOf(s.replayMaxAgeDays));
        age.addActionListener(e -> {
            s.replayMaxAgeDays = ageValues.get(Math.max(0, age.getSelectedIndex()));
            s.save();
            if (s.replayMaxAgeDays > 0) new Thread(app.library::sweepOldReplays, "replay-cleanup").start();
        });
        row("Delete old replays", small(age));
        note("Unfavorited instant replays older than this are deleted. Favorites, shared clips, recordings and exports are kept.");
    }

    private void watermarkSection(Settings s) {
        section("Your watermark");
        note("Your channel name or logo on exports, placed where you want. Free, and Evade Clips never adds a watermark of its own.");
        JLabel wmPreview = new JLabel();
        Runnable wmRefresh = () -> {
            boolean was = s.watermarkEnabled;
            s.watermarkEnabled = true;
            var wm = net.evadeclient.clips.core.Exporter.Watermark.from(s);
            s.watermarkEnabled = was;
            // 16:9, small enough to sit under the controls in the narrowest window.
            int pw = 352, ph = 198;
            java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(pw, ph, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = frame.createGraphics();
            g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(0x2B3A55), pw, ph, new java.awt.Color(0x55302B)));
            g.fillRect(0, 0, pw, ph);
            if (wm != null) g.drawImage(net.evadeclient.clips.core.Exporter.renderWatermark(wm, pw, ph), 0, 0, null);
            g.dispose();
            wmPreview.setIcon(new javax.swing.ImageIcon(frame));
        };
        check("Add my watermark to exports by default", s.watermarkEnabled, v -> s.watermarkEnabled = v);
        JTextField wmText = new JTextField(s.watermarkText);
        wmText.putClientProperty("JTextField.placeholderText", "@yourchannel");
        commit(wmText, v -> { s.watermarkText = v; wmRefresh.run(); });
        row("Text", wmText);
        JTextField wmImg = new JTextField(s.watermarkImage);
        wmImg.setEditable(false);
        wmImg.putClientProperty("JTextField.placeholderText", "Optional PNG logo (used instead of the text)");
        JButton pick = Theme.button("Choose…", null, false), clear = Theme.button("Clear", null, false);
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            s.watermarkImage = fc.getSelectedFile().getAbsolutePath();
            wmImg.setText(s.watermarkImage);
            s.save();
            wmRefresh.run();
        });
        clear.addActionListener(e -> { s.watermarkImage = ""; wmImg.setText(""); s.save(); wmRefresh.run(); });
        JPanel ip = new JPanel(new BorderLayout(8, 0));
        ip.setOpaque(false);
        ip.add(wmImg, BorderLayout.CENTER);
        JPanel ib = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        ib.setOpaque(false);
        ib.add(pick);
        ib.add(clear);
        ip.add(ib, BorderLayout.EAST);
        row("Logo", ip);
        String[] posIds = {"top-left", "top", "top-right", "left", "center", "right", "bottom-left", "bottom", "bottom-right"};
        JComboBox<String> wmPos = new JComboBox<>(new String[]{"Top left", "Top", "Top right", "Left", "Center", "Right", "Bottom left", "Bottom", "Bottom right"});
        wmPos.setSelectedIndex(Math.max(0, java.util.Arrays.asList(posIds).indexOf(s.watermarkPosition)));
        wmPos.addActionListener(e -> { s.watermarkPosition = posIds[wmPos.getSelectedIndex()]; s.save(); wmRefresh.run(); });
        row("Position", small(wmPos));
        for (Object[] sl : new Object[][]{{"Size", 2, 30, (int) s.watermarkSize}, {"Opacity", 10, 100, (int) Math.round(s.watermarkOpacity * 100)},
                {"Distance from edge", 0, 15, (int) s.watermarkMargin}}) {
            javax.swing.JSlider js = new javax.swing.JSlider((int) sl[1], (int) sl[2], (int) sl[3]);
            js.setOpaque(false);
            js.getAccessibleContext().setAccessibleName("Watermark " + ((String) sl[0]).toLowerCase());
            js.addChangeListener(e -> {
                switch ((String) sl[0]) {
                    case "Size" -> s.watermarkSize = js.getValue();
                    case "Opacity" -> s.watermarkOpacity = js.getValue() / 100.0;
                    default -> s.watermarkMargin = js.getValue();
                }
                wmRefresh.run();
                if (!js.getValueIsAdjusting()) s.save();
            });
            row((String) sl[0], js);
        }
        wmRefresh.run();
        Theme.pad(wmPreview, 6, 216, 6, 0);
        place(wmPreview, 0, 0);
    }

    private void sharingSection(Settings s) {
        section("Sharing & phone");
        note("Share links play right inside Discord, and every shared clip also appears in a private gallery on your phone. "
                + "No watermark is added and links don't expire. Clips go to clips.evadeclient.com by default. "
                + "Only change the share server if you run one yourself or trust whoever does: they hold every clip you share there. "
                + "Never paste an address a stranger or a guide sends you.");
        JTextField server = new JTextField(s.cloudUrl);
        server.putClientProperty("JTextField.placeholderText", net.evadeclient.clips.core.CloudShare.DEFAULT_SERVER);
        boolean[] asking = {false};
        Runnable setServer = () -> {
            String v = server.getText().trim();
            if (asking[0] || v.equals(s.cloudUrl == null ? "" : s.cloudUrl.trim())) return;
            asking[0] = true;
            try { applyServer(server, v, s); } finally { asking[0] = false; }
        };
        server.addActionListener(e -> setServer.run());
        server.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { if (!e.isTemporary()) setServer.run(); } });

        row("Share server", server);
        JTextField secret = new JTextField(s.cloudRegisterSecret);
        secret.putClientProperty("JTextField.placeholderText", "Only for private servers");
        commit(secret, v -> s.cloudRegisterSecret = v);
        row("Server invite code", secret);
        JComboBox<String> sync = new JComboBox<>(new String[]{"Only clips I share", "Favorites and exports", "Every new clip"});
        sync.setSelectedIndex(switch (s.cloudSync) { case "favorites" -> 1; case "all" -> 2; default -> 0; });
        sync.addActionListener(e -> { s.cloudSync = new String[]{"off", "favorites", "all"}[sync.getSelectedIndex()]; s.save(); });
        row("Send to phone", small(sync));
        check("New share links are private (only you, and anyone you send the link to)", s.sharePrivateByDefault, v -> s.sharePrivateByDefault = v);
        check("Bring clips I upload from my phone into the library", s.pullPhoneUploads, v -> s.pullPhoneUploads = v);
        JButton phone = Theme.button("Show phone QR code", null, false);
        phone.addActionListener(e -> showPhoneQr());
        JButton rotate = Theme.button("Reset phone link", null, false);
        rotate.setToolTipText("Make a new phone-gallery link; the old one stops working");
        rotate.addActionListener(e -> resetPhoneLink());
        row("Phone gallery", buttons(phone, rotate));
        JButton onServer = Theme.button("Clips on the server…", null, false);
        onServer.addActionListener(e -> showServerClips());
        JButton reset = Theme.button("Reset sharing", null, false);
        reset.addActionListener(e -> resetSharing());
        JButton wipe = Theme.button("Delete my shared data", null, false);
        wipe.addActionListener(e -> deleteSharedData());
        row("Your shared clips", buttons(onServer, reset, wipe));
        note("Deleting a clip here removes its share link too, unless you untick that when you delete it. "
                + "Shared clips that are no longer in your library can be deleted from Clips on the server.");
        webhookRows(s);
    }

    /** Discord channels to post shared clips to (Library → right-click → Post to Discord). */
    private void webhookRows(Settings s) {
        DefaultListModel<Settings.Webhook> model = new DefaultListModel<>();
        for (Settings.Webhook w : s.discordWebhooks) model.addElement(w);
        JList<Settings.Webhook> list = new JList<>(model);
        list.setVisibleRowCount(3);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Discord channels");
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                Settings.Webhook w = (Settings.Webhook) v;
                return super.getListCellRendererComponent(l, "<html><b>" + esc(w.name) + "</b>&nbsp;&nbsp;<span style='color:#5E5E69'>" + esc(w.masked()) + "</span></html>", i, sel, focus);
            }
        });
        JButton add = Theme.button("Add…", null, false), remove = Theme.button("Remove", null, false), test = Theme.button("Test", null, false);
        add.addActionListener(e -> {
            JTextField name = new JTextField("Clips", 20), url = new JTextField(30);
            url.putClientProperty("JTextField.placeholderText", "https://discord.com/api/webhooks/…");
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
            p.add(new JLabel("Name (only shown here)"));
            p.add(name);
            p.add(new JLabel("Webhook link (Discord: channel settings → Integrations → Webhooks → Copy Webhook URL)"));
            p.add(url);
            while (true) {
                if (JOptionPane.showConfirmDialog(this, p, "Add a Discord channel", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
                String u = url.getText().trim();
                if (Settings.validWebhook(u)) {
                    Settings.Webhook w = new Settings.Webhook(name.getText().isBlank() ? "Discord" : name.getText().trim(), u);
                    s.discordWebhooks.add(w);
                    model.addElement(w);
                    s.save();
                    return;
                }
                JOptionPane.showMessageDialog(this, "That isn't a Discord webhook link. It starts with https://discord.com/api/webhooks/", "Add a Discord channel", JOptionPane.WARNING_MESSAGE);
            }
        });
        remove.addActionListener(e -> {
            Settings.Webhook w = list.getSelectedValue();
            if (w == null) return;
            s.discordWebhooks.remove(w);
            model.removeElement(w);
            s.save();
        });
        test.addActionListener(e -> {
            Settings.Webhook w = list.getSelectedValue();
            if (w == null) { JOptionPane.showMessageDialog(this, "Pick a channel in the list first."); return; }
            new Thread(() -> {
                try {
                    Clip probe = new Clip();
                    probe.title = "Evade Clips is connected: clips you post will show up here";
                    net.evadeclient.clips.core.CloudShare.postWebhook(w.url, probe, "");
                    app.notify("Posted a test to " + w.name, null, false);
                } catch (Exception ex) {
                    app.notify("Couldn't post to " + w.name, ex.getMessage(), true);
                }
            }, "webhook-test").start();
        });
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(260, 64));
        p.add(sp, BorderLayout.CENTER);
        JPanel b = new JPanel(new GridLayout(3, 1, 0, 4));
        b.setOpaque(false);
        b.add(add); b.add(remove); b.add(test);
        p.add(b, BorderLayout.EAST);
        row("Discord channels", p, 116);
        note("Right-click a clip → Post to Discord sends its title, game and link to that channel. Webhook links stay on this PC. Nothing is posted automatically.");
    }

    private void eventsSection(Settings s) {
        section("Automatic clips");
        note("The Clips mod in Evade Client tells Evade Clips what happens in Minecraft (kills, totem pops, deaths, wins, "
                + "advancements) and it saves a clip. Nothing reads game memory. Events close together become one clip.");
        JSpinner before = new JSpinner(new SpinnerNumberModel(s.autoClipBefore, 5, 120, 5));
        before.addChangeListener(e -> { s.autoClipBefore = (int) before.getValue(); s.save(); app.applyEventSettings(); });
        row("Seconds before the event", small(before));
        JSpinner after = new JSpinner(new SpinnerNumberModel(s.autoClipAfter, 0, 15, 1));
        after.addChangeListener(e -> { s.autoClipAfter = (int) after.getValue(); s.save(); app.applyEventSettings(); });
        row("Seconds after the event", small(after));
        check("When Evade Client's Clips mod is off, auto-clip loud moments like fights and explosions",
                s.liveHighlights, v -> s.liveHighlights = v);
        JComboBox<String> sens = new JComboBox<>(new String[]{"Low (only big moments)", "Medium", "High (more clips)"});
        sens.setSelectedIndex(switch (s.liveSensitivity) { case "low" -> 0; case "high" -> 2; default -> 1; });
        sens.addActionListener(e -> { s.liveSensitivity = new String[]{"low", "medium", "high"}[sens.getSelectedIndex()]; s.save(); app.applyEventSettings(); });
        row("Loud-moment sensitivity", small(sens));
        note("Every event also drops a named marker (\"Kill Steve\", \"Totem pop\", \"Death\") on the timeline of recordings and replays. "
                + "Which events save a clip is set in Evade Client's Clips mod.");
    }

    private void integrationsSection(Settings s) {
        section("Evade Client");
        check("Allow Evade Client to save clips", s.controlServer, v -> s.controlServer = v);
        controlNote.setVisible(false);
        place(controlNote, 0, 6);
        JSpinner port = new JSpinner(new SpinnerNumberModel(s.controlPort, 1024, 65535, 1));
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        JLabel portNote = noteLabel(portText(s.controlPort));
        // Typing a port changes it one digit at a time: apply it once it has settled.
        Timer applyPort = new Timer(800, e -> {
            int v = (int) port.getValue();
            if (v == s.controlPort) return;
            s.controlPort = v;
            s.save();
            refit(portNote, portText(v));
            new Thread(app::applyPortChange, "port-change").start();
        });
        applyPort.setRepeats(false);
        port.addChangeListener(e -> applyPort.restart());
        row("Local port", small(port));
        place(portNote, 2, 8);
        app.onServerChanged(this::refreshControlNote);
        refreshControlNote();
    }

    private static String portText(int port) {
        return "Evade Client's \"Clips\" mod uses this to save replays on a key press or automatically on kills, deaths and wins, "
                + "and to send replay renders to your library (it finds the port by itself). Only Evade Client on this PC can use it "
                + "(port " + port + "). With saving turned off, opening Evade Clips from Evade Client still works.";
    }

    private void refreshControlNote() {
        String err = app.controlError();
        controlNote.setVisible(err != null);
        refit(controlNote, err == null ? " " : "The local control server isn't running: " + esc(err));
        body.revalidate();
    }

    private void ffmpegSection(Settings s) {
        section("ffmpeg");
        JTextField ff = new JTextField(s.ffmpegPath);
        Runnable placeholder = () -> ff.putClientProperty("JTextField.placeholderText", Ffmpeg.ffmpeg() == null ? "Not found — choose ffmpeg" : "Auto: " + Ffmpeg.ffmpeg());
        placeholder.run();
        commit(ff, v -> {
            if (v.equals(s.ffmpegPath)) return;
            s.ffmpegPath = v;
            Ffmpeg.configure(v);
            app.fireFfmpegChanged();
        });
        JButton browse = Theme.button("Browse…", null, false);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            ff.setText(fc.getSelectedFile().getAbsolutePath());
            s.ffmpegPath = ff.getText();
            Ffmpeg.configure(s.ffmpegPath);
            s.save();
            app.fireFfmpegChanged();
            JOptionPane.showMessageDialog(this, Ffmpeg.available() ? "ffmpeg found. Instant replay and exports are ready." : "That isn't ffmpeg (ffprobe must be next to it).");
        });
        JButton download = Theme.button("Download", null, false);
        download.setToolTipText("Get ffmpeg for Windows (about 90 MB, one time)");
        download.addActionListener(e -> net.evadeclient.clips.ClipsApp.downloadFfmpeg(app, SwingUtilities.getWindowAncestor(this)));
        download.setVisible(Ffmpeg.WINDOWS && !Ffmpeg.available());
        JPanel fr = new JPanel(new BorderLayout(8, 0));
        fr.setOpaque(false);
        fr.add(ff, BorderLayout.CENTER);
        JPanel fb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        fb.setOpaque(false);
        fb.add(download);
        fb.add(browse);
        fr.add(fb, BorderLayout.EAST);
        row("Location", fr);
        JLabel info = noteLabel(" ");
        place(info, 2, 8);
        Runnable refresh = () -> {
            placeholder.run();
            ff.repaint();
            download.setVisible(Ffmpeg.WINDOWS && !Ffmpeg.available());
            if (!Ffmpeg.available()) {
                refit(info, esc("Evade Clips records and exports with ffmpeg. Windows: Download above, or get a build from gyan.dev and put the ffmpeg folder next to Evade Clips. "
                        + "macOS: brew install ffmpeg. Linux: install it from your package manager."));
                return;
            }
            refit(info, esc("Using " + Ffmpeg.ffmpeg() + "  ·  encoders: detecting…"));
            new Thread(() -> {
                String list = String.join(", ", Ffmpeg.workingEncoders().stream().map(Ffmpeg::encoderLabel).toList());
                SwingUtilities.invokeLater(() -> refit(info, esc("Using " + Ffmpeg.ffmpeg() + "  ·  encoders: " + list)));
            }, "encoder-note").start();
        };
        app.onFfmpegChanged(refresh);
        refresh.run();
    }

    // ------------------------------------------------------------------ hotkeys

    /** Shows why global hotkeys don't work, or which ones can't be used; hidden when they all work. */
    void refreshHotkeyNote() {
        String text = null;
        if (app.hotkeysTried() && !app.hotkeys.active()) {
            String err = app.hotkeys.error();
            text = "Global hotkeys couldn't start on this system" + (err == null || err.isBlank() ? "." : ": " + err + ".")
                    + " Buttons, the tray icon and Evade Client still work.";
        } else if (!app.hotkeyProblems().isEmpty()) {
            text = "These hotkeys can't be used: " + String.join(", ", app.hotkeyProblems()) + ". Pick another key.";
        }
        hotkeyNote.setVisible(text != null);
        refit(hotkeyNote, text == null ? " " : esc(text));
        for (HotkeyBox b : hotkeyBoxes) b.sync();
        body.revalidate();
        body.repaint();
    }

    /** A button that shows a hotkey and records a new one when clicked. */
    private final class HotkeyBox extends JButton {
        final String action;
        final Supplier<String> get;
        final Consumer<String> set;
        boolean listening;
        long capturedAt;

        HotkeyBox(String action, Supplier<String> get, Consumer<String> set) {
            super(get.get());
            this.action = action;
            this.get = get;
            this.set = set;
            setFont(Theme.interBold.deriveFont(13f));
            setPreferredSize(new Dimension(170, 32));
            getAccessibleContext().setAccessibleName(action + " hotkey");
            addActionListener(e -> start());
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    // While listening (and just after), keys belong to the capture, not to this button.
                    if (listening || System.currentTimeMillis() - capturedAt < 400) {
                        if (listening && !app.hotkeys.active()) awtKey(e);
                        e.consume();
                    }
                }
                @Override public void keyReleased(KeyEvent e) { if (listening || System.currentTimeMillis() - capturedAt < 400) e.consume(); }
                @Override public void keyTyped(KeyEvent e) { if (listening || System.currentTimeMillis() - capturedAt < 400) e.consume(); }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { if (listening) cancel(); }
            });
        }

        void sync() { if (!listening) setText(get.get()); }

        private void start() {
            if (listening) return;
            listening = true;
            setText("Press a key…");
            requestFocusInWindow();
            if (app.hotkeys.active()) {
                // The global hook reads the key exactly as it will match it later, and nothing fires meanwhile.
                app.hotkeys.captureNext(name -> SwingUtilities.invokeLater(() -> {
                    if (!listening) return;
                    if (name == null) cancel(); else apply(name);
                }));
            }
        }

        private void cancel() {
            listening = false;
            capturedAt = System.currentTimeMillis();
            app.hotkeys.captureNext(null);
            setText(get.get());
        }

        /** Without the global hook: the key pressed in this window, if the hook would understand it. */
        private void awtKey(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) return;
            if (code == KeyEvent.VK_ESCAPE) { cancel(); return; }
            StringBuilder t = new StringBuilder();
            if (e.isControlDown()) t.append("Ctrl+");
            if (e.isAltDown()) t.append("Alt+");
            if (e.isShiftDown()) t.append("Shift+");
            if (e.isMetaDown()) t.append("Win+");
            t.append(KeyEvent.getKeyText(code));
            apply(t.toString());
        }

        private void apply(String name) {
            listening = false;
            capturedAt = System.currentTimeMillis();
            if (!Hotkeys.valid(name)) {
                setText(get.get());
                JOptionPane.showMessageDialog(SettingsPanel.this, "That key can't be used as a global hotkey. Try a function key (F1 to F12) or a letter with Ctrl, Alt or Shift.",
                        "Hotkeys", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            for (HotkeyBox other : hotkeyBoxes) {
                if (other == this || !java.util.Arrays.equals(Hotkeys.parse(other.get.get()), Hotkeys.parse(name))) continue;
                setText(get.get());
                JOptionPane.showMessageDialog(SettingsPanel.this, name + " is already the hotkey for \"" + other.action + "\".", "Hotkeys", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            set.accept(name);
            setText(name);
            app.settings.save();
            new Thread(app::applyHotkeys, "hotkeys").start();
        }
    }

    private JComponent hotkey(String action, Supplier<String> get, Consumer<String> set) {
        HotkeyBox b = new HotkeyBox(action, get, set);
        hotkeyBoxes.add(b);
        return small(b);
    }

    // ------------------------------------------------------------------ storage folder

    /**
     * Picks a new clips folder. A folder that already has an Evade Clips library is switched to; otherwise the
     * clips can be moved there, or the new folder starts empty. The old folder is kept until that worked.
     */
    private void changeFolder(JTextField folderField) {
        Settings s = app.settings;
        JFileChooser fc = new JFileChooser(s.clipsFolder);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dest = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        Path old = app.library.root().toAbsolutePath().normalize();
        if (dest.equals(old)) return;
        if (dest.startsWith(old)) {
            JOptionPane.showMessageDialog(this, "Pick a folder outside the current clips folder.", "Clips folder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (Files.exists(dest.resolve(".evadeclips").resolve("library.json"))) {
            switchFolder(folderField, dest, "Switched to the library in " + dest.getFileName(), "Your current clips stay in " + old);
            return;
        }
        List<Clip> all = app.library.all();
        if (all.isEmpty()) { switchFolder(folderField, dest, "Library now in " + dest.getFileName(), null); return; }
        long bytes = app.library.totalBytes();
        Object[] options = {"Move " + all.size() + " clip" + (all.size() == 1 ? "" : "s") + " (" + Theme.size(bytes) + ")", "Start fresh here", "Cancel"};
        int r = JOptionPane.showOptionDialog(this, "Move your clips to\n" + dest + "?\n\nOr start an empty library there (your clips stay in " + old + ").",
                "Clips folder", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (r == 1) switchFolder(folderField, dest, "Library now in " + dest.getFileName(), "Your old clips are still in " + old);
        else if (r == 0) moveFolder(folderField, dest);
    }

    private void switchFolder(JTextField folderField, Path dest, String title, String body) {
        Settings s = app.settings;
        s.clipsFolder = dest.toString();
        s.save();
        folderField.setText(s.clipsFolder);
        new Thread(() -> {
            app.library.load();
            restartForFolder();
            app.notify(title, body, false);
        }, "switch-library").start();
    }

    private void moveFolder(JTextField folderField, Path dest) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Moving your clips", java.awt.Dialog.ModalityType.MODELESS);
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setPreferredSize(new Dimension(380, 10));
        JPanel p = new JPanel(new BorderLayout(0, 12));
        Theme.pad(p, 18, 20, 18, 20);
        p.add(Theme.label("Moving your clips to " + dest + "…", 13f, false, Theme.TEXT), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        d.setContentPane(p);
        d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        new Thread(() -> {
            try {
                app.library.moveTo(dest, f -> SwingUtilities.invokeLater(() -> bar.setValue((int) Math.round(f * 1000))));
                SwingUtilities.invokeLater(() -> { d.dispose(); folderField.setText(app.settings.clipsFolder); });
                app.library.load();
                restartForFolder();
                app.notify("Library now in " + dest.getFileName(), dest.toString(), false);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(d::dispose);
                app.notify("Clips weren't moved", (ex.getMessage() == null ? ex.toString() : ex.getMessage()) + " Your clips are still in the old folder.", true);
            }
        }, "move-library").start();
    }

    /** Capture keeps its replay buffer in the clips folder: start it again there (after a running recording). */
    private void restartForFolder() {
        try {
            if (app.engine.isRecording()) {
                app.engine.restart(); // applied when the recording stops
                app.notify("New capture settings apply when this recording stops", null, false);
            } else if (app.engine.isLive()) {
                app.engine.restart();
            }
        } catch (Exception ex) {
            app.notify("Capture restart failed", ex.getMessage(), true);
        }
    }

    // ------------------------------------------------------------------ sharing

    /** Opens the phone gallery link as a QR code (and copies it). */
    private void showPhoneQr() {
        new Thread(() -> {
            try {
                String url = app.cloud.galleryUrl();
                var stats = app.cloud.list();
                java.awt.image.BufferedImage qr = qr(url, 280);
                SwingUtilities.invokeLater(() -> {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(url), null);
                    JLabel img = new JLabel(new javax.swing.ImageIcon(qr));
                    JLabel txt = Theme.label("<html><body style='width:280px'>Scan with your phone's camera to see every clip you've shared or synced. "
                            + "Add it to your home screen for one-tap access. The link is copied too.<br><br>"
                            + "<b>This code is a key.</b> Anyone who scans it or gets the link sees all your shared clips, private ones too, "
                            + "and can upload videos into your library. Don't show it on stream or send it to anyone. "
                            + "If it got out, close this and press Reset phone link: the old one stops working.<br><br>"
                            + Theme.size(stats.get("used").getAsLong()) + " of " + Theme.size(stats.get("quota").getAsLong()) + " used</body></html>", 12.5f, false, Theme.DIM);
                    JPanel p = new JPanel(new BorderLayout(0, 12));
                    p.add(img, BorderLayout.CENTER);
                    p.add(txt, BorderLayout.SOUTH);
                    JOptionPane.showMessageDialog(this, p, "Your clips on your phone", JOptionPane.PLAIN_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ex.getMessage(), "Phone gallery", JOptionPane.WARNING_MESSAGE));
            }
        }, "phone-qr").start();
    }

    private void resetPhoneLink() {
        if (!app.cloud.configured()) { JOptionPane.showMessageDialog(this, "Set a share server first."); return; }
        if (JOptionPane.showConfirmDialog(this, "Make a new phone gallery link?\nThe old link (and the QR code you scanned) stops working; scan the new one on your phone.",
                "Reset phone link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        new Thread(() -> {
            try {
                app.cloud.rotateGallery(app.library);
                app.notify("New phone link ready", "The old one no longer works", false);
                SwingUtilities.invokeLater(this::showPhoneQr);
            } catch (Exception ex) {
                app.notify("Couldn't reset the phone link", ex.getMessage(), true);
            }
        }, "rotate-gallery").start();
    }

    /** "Reset sharing": optionally deletes what this PC shared on the server, then forgets its account there. */
    private void resetSharing() {
        if (!app.cloud.configured()) { JOptionPane.showMessageDialog(this, "Set a share server first."); return; }
        Object[] options = {"Delete them and reset", "Keep them online and reset", "Cancel"};
        int r = JOptionPane.showOptionDialog(this, "Start over with a new account on " + app.cloud.base() + "?\n\n"
                        + "Delete the clips you've shared there too? If you keep them, their links keep working,\nbut this PC can't change or remove them any more.",
                "Reset sharing", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (r == 1) {
            app.cloud.resetSharing();
            app.notify("Sharing reset", "The next clip you share creates a new account", false);
        } else if (r == 0) {
            deleteAccountNow("Sharing reset");
        }
    }

    private void deleteSharedData() {
        if (!app.cloud.configured()) { JOptionPane.showMessageDialog(this, "Set a share server first."); return; }
        if (JOptionPane.showConfirmDialog(this, "Delete every clip and preset you've shared from " + app.cloud.base() + ", and your phone gallery?\nClips on this PC stay.",
                "Delete my shared data", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        deleteAccountNow("Shared data deleted");
    }

    private void deleteAccountNow(String doneTitle) {
        new Thread(() -> {
            try {
                int n = app.cloud.deleteAccount(app.library);
                app.notify(doneTitle, n + " clip" + (n == 1 ? "" : "s") + " removed from the server", false);
            } catch (Exception ex) {
                app.notify("Couldn't delete your shared data", ex.getMessage(), true);
            }
        }, "delete-account").start();
    }

    /** This account's clips on the server, with Delete (for uploads that are no longer in the library). */
    private void showServerClips() {
        if (!app.cloud.configured()) { JOptionPane.showMessageDialog(this, "Set a share server first."); return; }
        record Row(String id, String title, String detail, boolean inLibrary) {
            @Override public String toString() {
                return "<html><b>" + esc(title) + "</b>&nbsp;&nbsp;<span style='color:#9B9BA6'>" + esc(detail)
                        + (inLibrary ? "" : " · not in your library") + "</span></html>";
            }
        }
        DefaultListModel<Row> model = new DefaultListModel<>();
        JList<Row> list = new JList<>(model);
        list.setVisibleRowCount(12);
        list.getAccessibleContext().setAccessibleName("Clips on the server");
        JLabel status = Theme.label("Loading…", 12f, false, Theme.DIM);
        JButton delete = Theme.button("Delete", Theme.icon(Theme.Glyph.TRASH, 16, Theme.TEXT), false);
        JButton close = Theme.button("Close", null, false);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Clips on " + app.cloud.base(), java.awt.Dialog.ModalityType.MODELESS);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        Theme.pad(p, 14, 14, 14, 14);
        p.add(status, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(560, 320));
        p.add(sp, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        b.add(delete);
        b.add(close);
        p.add(b, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(this);
        close.addActionListener(e -> d.dispose());
        Runnable load = () -> new Thread(() -> {
            try {
                var r = app.cloud.list();
                java.util.Set<String> mine = new java.util.HashSet<>();
                for (Clip c : app.library.all()) if (c.shareId != null && app.cloud.onCurrentServer(c)) mine.add(c.shareId);
                List<Row> rows = new ArrayList<>();
                var arr = r.getAsJsonArray("clips");
                if (arr != null) for (var el : arr) {
                    var o = el.getAsJsonObject();
                    String id = o.get("id").getAsString();
                    String title = o.has("title") ? o.get("title").getAsString() : id;
                    long size = o.has("size") ? o.get("size").getAsLong() : 0;
                    long created = o.has("created") ? o.get("created").getAsLong() : 0;
                    boolean priv = o.has("visibility") && "private".equals(o.get("visibility").getAsString());
                    String origin = o.has("origin") && "phone".equals(o.get("origin").getAsString()) ? " · from phone" : "";
                    rows.add(new Row(id, title, Theme.size(size) + (created > 0 ? " · " + Theme.ago(created) : "") + (priv ? " · private" : "") + origin, mine.contains(id)));
                }
                long used = r.has("used") ? r.get("used").getAsLong() : 0, quota = r.has("quota") ? r.get("quota").getAsLong() : 0;
                SwingUtilities.invokeLater(() -> {
                    model.clear();
                    for (Row x : rows) model.addElement(x);
                    status.setText(rows.size() + " clip" + (rows.size() == 1 ? "" : "s") + " · " + Theme.size(used) + (quota > 0 ? " of " + Theme.size(quota) : "") + " used");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> status.setText("Couldn't load the list: " + ex.getMessage()));
            }
        }, "server-clips").start();
        delete.addActionListener(e -> {
            List<Row> sel = list.getSelectedValuesList();
            if (sel.isEmpty()) return;
            long linked = sel.stream().filter(Row::inLibrary).count();
            String msg = "Delete " + sel.size() + " clip" + (sel.size() == 1 ? "" : "s") + " from the server? The link" + (sel.size() == 1 ? " stops" : "s stop") + " working."
                    + (linked > 0 ? "\n" + linked + " of them " + (linked == 1 ? "is" : "are") + " in your library; the video" + (linked == 1 ? "" : "s") + " on this PC stay." : "");
            if (JOptionPane.showConfirmDialog(d, msg, "Delete from the server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            new Thread(() -> {
                int failed = 0;
                String why = null;
                for (Row x : sel) {
                    try { app.cloud.deleteRemote(x.id(), app.library); } catch (Exception ex) { failed++; why = ex.getMessage(); }
                }
                if (failed > 0) app.notify("Couldn't delete " + failed + " clip" + (failed == 1 ? "" : "s"), why, true);
                else app.notify("Deleted from the server", sel.size() + " clip" + (sel.size() == 1 ? "" : "s"), false);
                load.run();
            }, "server-delete").start();
        });
        d.setVisible(true);
        load.run();
    }

    static java.awt.image.BufferedImage qr(String text, int size) throws Exception {
        var m = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                java.util.Map.of(com.google.zxing.EncodeHintType.MARGIN, 2));
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(m.getWidth(), m.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
        return img;
    }

    static String version() {
        String v = SettingsPanel.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    // ------------------------------------------------------------------ building blocks

    /** Starts a new section: a heading and a card the following rows go into. */
    private void section(String t) {
        JPanel c = new JPanel() {
            @Override protected void paintComponent(java.awt.Graphics g) { Theme.paintCard(g, getWidth(), getHeight()); }
            @Override public Dimension getMaximumSize() { return new Dimension(860, getPreferredSize().height); }
        };
        c.setOpaque(false);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        Theme.pad(c, 16, 18, 18, 18);
        c.setAlignmentX(LEFT_ALIGNMENT);
        JLabel h = Theme.label(t, 15f, true, Theme.TEXT);
        h.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(h, 0, 0, 8, 0);
        c.add(h);
        body.add(Box.createVerticalStrut(body.getComponentCount() <= 1 ? 18 : 14));
        body.add(c);
        card = c;
    }

    /** Adds a component to the current card, padded above and below. */
    private void place(JComponent c, int top, int bottom) {
        if (top > 0 || bottom > 0) {
            var b = c.getBorder();
            c.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createEmptyBorder(top, 0, bottom, 0), b));
        }
        c.setAlignmentX(LEFT_ALIGNMENT);
        card.add(c);
    }

    /** A wrapping note; {@code raw} is HTML-escaped text. */
    private JLabel noteLabel(String raw) {
        return fit(Theme.label(" ", 12f, false, Theme.FAINT), raw, 0);
    }

    private void note(String t) {
        JLabel l = noteLabel(esc(t));
        Theme.pad(l, 2, 0, 8, 0);
        place(l, 0, 0);
    }

    private void row(String label, JComponent c) { row(label, c, 0); }

    /** A labelled row; {@code minHeight} for rows holding a list. Rows are as tall as their content (buttons may wrap). */
    private void row(String label, JComponent c, int minHeight) {
        JPanel r = new JPanel(new BorderLayout(16, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        r.setOpaque(false);
        JLabel l = Theme.label(label, 13.5f, false, Theme.TEXT);
        l.setPreferredSize(new Dimension(200, 30));
        l.setVerticalAlignment(JLabel.TOP);
        Theme.pad(l, 5, 0, 0, 0);
        l.setLabelFor(c instanceof JPanel p && p.getComponentCount() == 1 && p.getComponent(0) instanceof JComponent only ? only : c);
        r.add(l, BorderLayout.WEST);
        r.add(c, BorderLayout.CENTER);
        if (minHeight > 0) r.setMinimumSize(new Dimension(0, minHeight));
        Theme.pad(r, 4, 0, 4, 0);
        place(r, 0, 0);
    }

    /** Lays buttons out left to right, going on to a new line when the card is too narrow for all of them. */
    static final class WrapLayout implements java.awt.LayoutManager {
        private static final int GAP = 6;
        @Override public void addLayoutComponent(String name, Component comp) {}
        @Override public void removeLayoutComponent(Component comp) {}
        @Override public Dimension minimumLayoutSize(java.awt.Container p) {
            int w = 0, h = 0;
            for (Component c : p.getComponents()) if (c.isVisible()) { w = Math.max(w, c.getPreferredSize().width); h = Math.max(h, c.getPreferredSize().height); }
            return new Dimension(w, h);
        }
        @Override public Dimension preferredLayoutSize(java.awt.Container p) { return place(p, false); }
        @Override public void layoutContainer(java.awt.Container p) { place(p, true); }

        private Dimension place(java.awt.Container p, boolean apply) {
            int max = p.getWidth() > 0 ? p.getWidth() : Integer.MAX_VALUE;
            int x = 0, y = 0, lineH = 0, width = 0;
            for (Component c : p.getComponents()) {
                if (!c.isVisible()) continue;
                Dimension d = c.getPreferredSize();
                if (x > 0 && x + d.width > max) { x = 0; y += lineH + GAP; lineH = 0; }
                if (apply) c.setBounds(x, y, d.width, d.height);
                x += d.width + GAP;
                width = Math.max(width, x - GAP);
                lineH = Math.max(lineH, d.height);
            }
            return new Dimension(width, y + lineH);
        }
    }

    /** Keeps a control at its natural width (at least 110 px), left-aligned in its row. */
    private static JComponent small(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        Dimension d = c.getPreferredSize();
        int w = Math.max(110, d.width);
        if (c instanceof JComboBox<?>) w = Math.min(w, 260); // a very long item mustn't make the row run off the card
        c.setPreferredSize(new Dimension(w, Math.max(30, d.height)));
        p.add(c);
        return p;
    }

    private static JComponent buttons(JComponent... bs) {
        JPanel p = new JPanel(new WrapLayout());
        p.setOpaque(false);
        for (JComponent b : bs) p.add(b);
        return p;
    }

    /** An on/off setting: its description on the left, a switch on the right. */
    private void check(String label, boolean value, Consumer<Boolean> set) {
        RecordPanel.Switch b = new RecordPanel.Switch(label);
        b.setSelected(value);
        b.addActionListener(e -> { set.accept(b.isSelected()); app.settings.save(); });
        JLabel l = fit(Theme.label(" ", 13.5f, false, Theme.TEXT), esc(label), 70);
        l.setLabelFor(b);
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (b.isEnabled()) b.doClick(); }
        });
        JPanel r = new JPanel(new BorderLayout(16, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        r.setOpaque(false);
        r.add(l, BorderLayout.CENTER);
        JPanel sw = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        sw.setOpaque(false);
        sw.add(b);
        r.add(sw, BorderLayout.EAST);
        Theme.pad(r, 7, 0, 7, 0);
        place(r, 0, 0);
    }

    /** Sets the share server after checking it: https only (or this computer), and a warning for anything but the default. */
    private void applyServer(JTextField server, String v, Settings s) {
        String bad = net.evadeclient.clips.core.CloudShare.serverProblem(v);
        if (bad == null && !v.isEmpty() && !net.evadeclient.clips.core.CloudShare.isDefaultServer(v)
                && JOptionPane.showConfirmDialog(this, "Send shared clips to " + v + " instead of clips.evadeclient.com?\n\n"
                        + "That server is run by someone else. They can see, keep and delete every clip you share there,\n"
                        + "and Evade can't remove them for you. Only use a server you run yourself or fully trust.",
                "Use another share server?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
            bad = "";
        if (bad != null) {
            if (!bad.isEmpty()) JOptionPane.showMessageDialog(this, bad, "Share server", JOptionPane.WARNING_MESSAGE);
            server.setText(s.cloudUrl);
            return;
        }
        s.cloudUrl = v;
        s.save();
    }

    private void commit(JTextField f, Consumer<String> set) {
        Runnable r = () -> { set.accept(f.getText().trim()); app.settings.save(); };
        f.addActionListener(e -> r.run());
        f.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { r.run(); } });
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
