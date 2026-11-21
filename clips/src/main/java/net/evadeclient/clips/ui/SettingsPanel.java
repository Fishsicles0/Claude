package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Settings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/** Storage, hotkeys, behaviour and integrations. */
public final class SettingsPanel extends JPanel {
    private final App app;
    private final JPanel body = new JPanel();

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
        Settings s = app.settings;

        heading("Hotkeys");
        if (!app.hotkeys.active()) note("Global hotkeys couldn't start on this system" + (app.hotkeys.error() == null ? "." : ": " + app.hotkeys.error())
                + " Buttons, the tray icon and Evade Client still work.");
        row("Save replay", hotkey(s.hotkeySaveClip, v -> { s.hotkeySaveClip = v; }));
        row("Start / stop recording", hotkey(s.hotkeyRecord, v -> { s.hotkeyRecord = v; }));
        row("Add marker", hotkey(s.hotkeyMarker, v -> { s.hotkeyMarker = v; }));
        note("Click a box and press the key (with Ctrl / Alt / Shift if you like). Esc cancels.");

        heading("Storage");
        JTextField folder = new JTextField(s.clipsFolder);
        folder.setEditable(false);
        JButton change = Theme.button("Change…", null, false);
        change.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(s.clipsFolder);
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            s.clipsFolder = fc.getSelectedFile().getAbsolutePath();
            folder.setText(s.clipsFolder);
            s.save();
            new Thread(() -> {
                try { app.engine.restart(); } catch (Exception ex) { app.notify("Capture restart failed", ex.getMessage(), true); }
                app.library.load();
            }, "move-library").start();
        });
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
        note("When the library gets bigger than this, the oldest replays are removed first. Favorites and exports are never removed. 0 = no limit.");
        JTextField game = new JTextField(s.defaultGame);
        commit(game, v -> s.defaultGame = v);
        row("Default game name", game);

        heading("Behaviour");
        check("Start instant replay when the app opens", s.replayOnLaunch, v -> s.replayOnLaunch = v);
        check("Play a sound when a clip is saved", s.playSound, v -> s.playSound = v);
        check("Show a pop-up when a clip is saved", s.showToast, v -> s.showToast = v);
        check("Closing the window keeps Evade Clips running in the tray", s.minimizeToTray, v -> s.minimizeToTray = v);

        heading("Evade Client & integrations");
        check("Allow Evade Client and local tools to save clips", s.controlServer, v -> { s.controlServer = v; app.applyServer(); });
        JSpinner port = new JSpinner(new SpinnerNumberModel(s.controlPort, 1024, 65535, 1));
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        port.addChangeListener(e -> { s.controlPort = (int) port.getValue(); s.save(); app.applyServer(); });
        row("Local port", small(port));
        note("Evade Client's \"Clips\" mod uses this to save replays on a key press or automatically on kills, deaths and wins. "
                + "Scripts and stream decks can POST to http://127.0.0.1:" + s.controlPort + "/clip. Only this PC can reach it.");

        heading("ffmpeg");
        JTextField ff = new JTextField(s.ffmpegPath);
        ff.putClientProperty("JTextField.placeholderText", Ffmpeg.ffmpeg() == null ? "Not found — choose ffmpeg" : "Auto: " + Ffmpeg.ffmpeg());
        commit(ff, v -> { s.ffmpegPath = v; Ffmpeg.configure(v); });
        JButton browse = Theme.button("Browse…", null, false);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            ff.setText(fc.getSelectedFile().getAbsolutePath());
            s.ffmpegPath = ff.getText();
            Ffmpeg.configure(s.ffmpegPath);
            s.save();
            JOptionPane.showMessageDialog(this, Ffmpeg.available() ? "ffmpeg found. Restart capture from the Record page." : "That isn't ffmpeg (ffprobe must be next to it).");
        });
        JPanel fr = new JPanel(new BorderLayout(8, 0));
        fr.setOpaque(false);
        fr.add(ff, BorderLayout.CENTER);
        fr.add(browse, BorderLayout.EAST);
        row("Location", fr);
        note(Ffmpeg.available() ? "Using " + Ffmpeg.ffmpeg() + "  ·  encoders: " + String.join(", ", Ffmpeg.workingEncoders().stream().map(Ffmpeg::encoderLabel).toList())
                : "Evade Clips records and exports with ffmpeg. Windows: get a build from gyan.dev and put the ffmpeg folder next to Evade Clips. macOS: brew install ffmpeg. Linux: install it from your package manager.");

        heading("About");
        note("Evade Clips " + version() + "  ·  part of Evade Client  ·  settings in " + Settings.appDir());

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        add(sp, BorderLayout.CENTER);
    }

    static String version() {
        String v = SettingsPanel.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private void heading(String t) {
        JLabel h = Theme.heading(t);
        h.setBorder(BorderFactory.createEmptyBorder(24, 0, 8, 0));
        h.setAlignmentX(LEFT_ALIGNMENT);
        body.add(h);
    }

    private void note(String t) {
        JLabel l = Theme.label("<html><body style='width:640px'>" + t + "</body></html>", 12f, false, Theme.FAINT);
        l.setAlignmentX(LEFT_ALIGNMENT);
        Theme.pad(l, 2, 0, 8, 0);
        body.add(l);
    }

    private void row(String label, JComponent c) {
        JPanel r = new JPanel(new BorderLayout(16, 0));
        r.setOpaque(false);
        JLabel l = Theme.label(label, 13.5f, false, Theme.TEXT);
        l.setPreferredSize(new Dimension(200, 30));
        r.add(l, BorderLayout.WEST);
        r.add(c, BorderLayout.CENTER);
        r.setAlignmentX(LEFT_ALIGNMENT);
        r.setMaximumSize(new Dimension(860, 40));
        Theme.pad(r, 4, 0, 4, 0);
        body.add(r);
    }

    private static JComponent small(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        c.setPreferredSize(new Dimension(110, 30));
        p.add(c);
        return p;
    }

    private void check(String label, boolean value, Consumer<Boolean> set) {
        JCheckBox b = new JCheckBox(label, value);
        b.setOpaque(false);
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.addActionListener(e -> { set.accept(b.isSelected()); app.settings.save(); });
        Theme.pad(b, 4, 0, 4, 0);
        body.add(b);
    }

    private void commit(JTextField f, Consumer<String> set) {
        Runnable r = () -> { set.accept(f.getText().trim()); app.settings.save(); };
        f.addActionListener(e -> r.run());
        f.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { r.run(); } });
    }

    /** A button that shows a hotkey and records a new one when clicked. */
    private JComponent hotkey(String current, Consumer<String> set) {
        String[] cur = {current};
        JButton b = new JButton(current);
        b.setFont(Theme.interBold.deriveFont(13f));
        b.setPreferredSize(new Dimension(170, 32));
        boolean[] listening = {false};
        b.addActionListener(e -> {
            listening[0] = true;
            b.setText("Press a key…");
            b.requestFocusInWindow();
        });
        b.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (!listening[0]) return;
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) return;
                listening[0] = false;
                if (code == KeyEvent.VK_ESCAPE) { b.setText(cur[0]); return; }
                StringBuilder t = new StringBuilder();
                if (e.isControlDown()) t.append("Ctrl+");
                if (e.isAltDown()) t.append("Alt+");
                if (e.isShiftDown()) t.append("Shift+");
                if (e.isMetaDown()) t.append("Win+");
                t.append(KeyEvent.getKeyText(code));
                b.setText(t.toString());
                cur[0] = t.toString();
                set.accept(t.toString());
                app.settings.save();
                app.applyHotkeys();
                e.consume();
            }
        });
        b.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { if (listening[0]) { listening[0] = false; b.setText(cur[0]); } } });
        return small(b);
    }
}
