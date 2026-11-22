package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Library;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The clip editor: preview player, timeline (trim, split, delete parts, slow-mo / speed-up per part, markers),
 * framing for vertical and square video, audio, a title card, and export. Edits are saved with the clip as you
 * go and never touch the original file.
 */
public final class EditorPanel extends JPanel {
    private final App app;
    private Clip clip;
    private EditSpec edit;
    private final Deque<EditSpec> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    private final VideoView view = new VideoView();
    private final VideoPlayer player;
    private final Timeline timeline = new Timeline();
    private final JLabel timeLabel = Theme.label("0:00.00 / 0:00.00", 12.5f, true, Theme.TEXT);
    private final JLabel outLabel = Theme.label("", 12.5f, false, Theme.DIM);
    private final JButton playBtn = Theme.iconButton(Theme.icon(Theme.Glyph.PLAY, 22, Theme.TEXT), "Play / pause (Space)");
    private final JToggleButton previewEdits = new JToggleButton("Preview edits", true);
    private final JTextField titleField = new JTextField();
    private final JTextField gameField = new JTextField();
    private final JTextField tagsField = new JTextField();
    private final JToggleButton favBtn = new JToggleButton();
    private final JButton undoBtn = Theme.iconButton(Theme.icon(Theme.Glyph.UNDO, 18, Theme.TEXT), "Undo (Ctrl+Z)");
    private final JButton redoBtn = Theme.iconButton(Theme.icon(Theme.Glyph.REDO, 18, Theme.TEXT), "Redo (Ctrl+Y)");

    // inspector controls
    private final JComboBox<String> speed = new JComboBox<>(new String[]{"0.25×", "0.5×", "0.75×", "1×", "1.5×", "2×", "4×"});
    private final ButtonGroup aspectGroup = new ButtonGroup();
    private final java.util.Map<String, JToggleButton> aspectBtns = new java.util.LinkedHashMap<>();
    private final JComboBox<String> fitMode = new JComboBox<>(new String[]{"Crop", "Blurred background"});
    private final JSlider cropX = new JSlider(0, 100, 50);
    private final JSlider zoom = new JSlider(100, 300, 100);
    private final JSlider volume = new JSlider(0, 200, 100);
    private final JCheckBox mute = new JCheckBox("Mute");
    private final JSlider micVol = new JSlider(0, 200, 100);
    private JComponent micRow;
    private final JSlider voiceVol = new JSlider(0, 200, 100);
    private JComponent voiceRow;
    private static final String[] EFFECTS = {"none", "vibrant", "cinematic", "bw", "retro", "warm", "cool"};
    private final JComboBox<String> effect = new JComboBox<>(new String[]{"None", "Vibrant", "Cinematic", "Black & white", "Retro", "Warm", "Cool"});
    private final javax.swing.DefaultListModel<String> capModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> capList = new javax.swing.JList<>(capModel);
    private final JTextField capText = new JTextField();
    private final JComboBox<String> capStyle = new JComboBox<>(new String[]{"Clean", "Subtitle", "Meme"});
    private final JComboBox<String> capPos = new JComboBox<>(new String[]{"Bottom", "Center", "Top"});
    private final java.util.Map<String, BufferedImage> capPreview = new java.util.HashMap<>();
    private final javax.swing.DefaultListModel<String> ovModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> ovList = new javax.swing.JList<>(ovModel);
    private final JComboBox<String> ovPos = new JComboBox<>(new String[]{"Top right", "Top left", "Bottom right", "Bottom left", "Center"});
    private final JSlider ovSize = new JSlider(10, 100, 30), ovOpacity = new JSlider(10, 100, 100);
    private final JCheckBox ovAudio = new JCheckBox("Use its sound");
    private final java.util.Map<String, BufferedImage> ovFrames = new java.util.concurrent.ConcurrentHashMap<>();
    private final JSlider fadeIn = new JSlider(0, 30, 0), fadeOut = new JSlider(0, 30, 0);
    private final JTextField titleText = new JTextField();
    private final JComboBox<String> titlePos = new JComboBox<>(new String[]{"Bottom", "Center", "Top"});
    private final JComboBox<String> titleSecs = new JComboBox<>(new String[]{"First 2 s", "First 3 s", "First 5 s", "Whole clip"});
    private final JComboBox<Exporter.Preset> preset = new JComboBox<>(Exporter.Preset.values());
    private final JLabel presetInfo = Theme.label("", 12f, false, Theme.DIM);
    private final JCheckBox gpu = new JCheckBox("Use GPU encoder when possible", true);
    private final JCheckBox watermark = new JCheckBox("Add my watermark");
    private final JButton exportBtn = Theme.button("Export", Theme.icon(Theme.Glyph.EXPORT, 16, Color.WHITE), true);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel exportStatus = Theme.label(" ", 12f, false, Theme.DIM);
    private final JPanel exportDone = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private App.ExportJob currentJob;

    private boolean loading;
    private BufferedImage titlePreview;
    private String titlePreviewKey = "";
    private final Timer saveLater;

    public EditorPanel(App app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Theme.PANEL);
        player = new VideoPlayer(new VideoPlayer.Listener() {
            @Override public void frame(BufferedImage img, double time) { view.setFrame(img); }
            @Override public void ended() { SwingUtilities.invokeLater(() -> updatePlayIcon()); }
        });
        saveLater = new Timer(400, e -> { if (clip != null) { clip.edit = edit; app.library.update(clip); } });
        saveLater.setRepeats(false);

        add(topBar(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        Theme.pad(center, 0, 20, 0, 0);
        center.add(view, BorderLayout.CENTER);
        center.add(transport(), BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(Theme.BG);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        bottom.add(timelineBar(), BorderLayout.NORTH);
        timeline.setFocusable(true);
        bottom.add(timeline, BorderLayout.CENTER);

        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false);
        main.add(center, BorderLayout.CENTER);
        main.add(bottom, BorderLayout.SOUTH);
        add(main, BorderLayout.CENTER);

        JScrollPane insp = new JScrollPane(inspector());
        insp.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));
        insp.setPreferredSize(new Dimension(340, 100));
        insp.getVerticalScrollBar().setUnitIncrement(20);
        insp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(insp, BorderLayout.EAST);

        timeline.onSeek = t -> { player.seek(t); timeline.setPlayhead(t); updateTime(t); };
        timeline.beforeEdit = this::snapshot;
        timeline.afterEdit = this::edited;

        // Drives playhead, segment skipping during edit preview, and the clock label.
        new Timer(30, e -> tick()).start();
        bindKeys();
        app.onJobs(() -> SwingUtilities.invokeLater(this::updateJob));
    }

    // ------------------------------------------------------------------ layout pieces

    private JComponent topBar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setOpaque(false);
        Theme.pad(p, 14, 16, 12, 20);
        JButton back = Theme.iconButton(Theme.icon(Theme.Glyph.BACK, 20, Theme.TEXT), "Back to library (Esc)");
        back.addActionListener(e -> app.openEditor.accept(null));
        p.add(back);
        p.add(Box.createHorizontalStrut(8));
        titleField.setFont(Theme.interBold.deriveFont(20f));
        titleField.putClientProperty("JComponent.outline", null);
        titleField.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        titleField.setBackground(Theme.PANEL);
        titleField.setMaximumSize(new Dimension(520, 38));
        titleField.setToolTipText("Click to rename");
        titleField.addActionListener(e -> { commitMeta(); view.requestFocusInWindow(); });
        titleField.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { commitMeta(); } });
        p.add(titleField);
        favBtn.setIcon(Theme.icon(Theme.Glyph.STAR, 20, Theme.DIM));
        favBtn.setSelectedIcon(Theme.icon(Theme.Glyph.STAR_FILLED, 20, Theme.GOLD));
        favBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        favBtn.setFocusable(false);
        favBtn.setToolTipText("Favorite (favorites are never auto-deleted)");
        favBtn.addActionListener(e -> { if (clip != null) { clip.favorite = favBtn.isSelected(); app.library.update(clip); } });
        p.add(favBtn);
        p.add(Box.createHorizontalGlue());
        JButton copy = Theme.button("Copy", Theme.icon(Theme.Glyph.COPY, 16, Theme.TEXT), false);
        copy.setToolTipText("Copy the original file to paste into Discord, a browser or a folder");
        copy.addActionListener(e -> {
            if (clip == null) return;
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(LibraryPanel.files(List.of(clip), app.library.root()), null);
            app.notify("Copied — paste it anywhere", null, false);
        });
        p.add(copy);
        p.add(Box.createHorizontalStrut(8));
        JButton show = Theme.button("Show file", Theme.icon(Theme.Glyph.FOLDER, 16, Theme.TEXT), false);
        show.addActionListener(e -> { if (clip != null) LibraryPanel.reveal(clip.path(app.library.root())); });
        p.add(show);
        p.add(Box.createHorizontalStrut(8));
        JButton vertical = Theme.button("Make vertical", null, false);
        vertical.setToolTipText("One click: 9:16 for TikTok, Shorts and Reels (blurred background keeps the whole frame), ready to export");
        vertical.addActionListener(e -> makeVertical());
        p.add(vertical);
        p.add(Box.createHorizontalStrut(8));
        JButton share = Theme.button("Share link", Theme.icon(Theme.Glyph.EXPORT, 16, Color.WHITE), true);
        share.setToolTipText("Upload with your edits and copy a link that plays right in Discord");
        share.addActionListener(e -> {
            if (clip == null) return;
            commitMeta();
            clip.edit = edit;
            app.library.update(clip);
            app.shareClip(clip, true);
        });
        p.add(share);
        return p;
    }

    private JComponent transport() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        Theme.pad(p, 8, 0, 8, 0);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        JButton prev = Theme.iconButton(Theme.icon(Theme.Glyph.FRAME_PREV, 18, Theme.TEXT), "Previous frame (←)");
        prev.addActionListener(e -> step(-1));
        JButton next = Theme.iconButton(Theme.icon(Theme.Glyph.FRAME_NEXT, 18, Theme.TEXT), "Next frame (→)");
        next.addActionListener(e -> step(1));
        playBtn.addActionListener(e -> togglePlay());
        left.add(prev);
        left.add(playBtn);
        left.add(next);
        left.add(Box.createHorizontalStrut(10));
        left.add(timeLabel);
        p.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(outLabel);
        right.add(Box.createHorizontalStrut(14));
        JToggleButton muteBtn = new JToggleButton(Theme.icon(Theme.Glyph.VOLUME, 18, Theme.TEXT));
        muteBtn.setSelectedIcon(Theme.icon(Theme.Glyph.MUTE, 18, Theme.DIM));
        muteBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        muteBtn.setFocusable(false);
        muteBtn.setToolTipText("Mute preview");
        muteBtn.addActionListener(e -> player.setMuted(muteBtn.isSelected()));
        right.add(muteBtn);
        JSlider pv = new JSlider(0, 100, 80);
        pv.setPreferredSize(new Dimension(90, 20));
        pv.setFocusable(false);
        pv.addChangeListener(e -> player.setVolume(pv.getValue() / 100f));
        player.setVolume(0.8f);
        right.add(pv);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JComponent timelineBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        Theme.pad(p, 8, 10, 0, 14);
        JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        l.setOpaque(false);
        JButton split = Theme.button("Split", Theme.icon(Theme.Glyph.SPLIT, 16, Theme.TEXT), false);
        split.setToolTipText("Split at the playhead (S)");
        split.addActionListener(e -> split());
        JButton del = Theme.button("Delete", Theme.icon(Theme.Glyph.TRASH, 16, Theme.TEXT), false);
        del.setToolTipText("Cut out the part under the playhead (Delete)");
        del.addActionListener(e -> deletePart());
        JButton in = Theme.button("Set start", null, false);
        in.setToolTipText("Start this part at the playhead (I)");
        in.addActionListener(e -> setIn());
        JButton out = Theme.button("Set end", null, false);
        out.setToolTipText("End this part at the playhead (O)");
        out.addActionListener(e -> setOut());
        JButton mark = Theme.iconButton(Theme.icon(Theme.Glyph.BOOKMARK, 18, Theme.GOLD), "Add marker at playhead (M)");
        mark.addActionListener(e -> addMarker());
        JButton hl = Theme.button("Highlights", Theme.icon(Theme.Glyph.STAR, 16, Theme.GOLD), false);
        hl.setToolTipText("Find the action and keep only it (H). Undo brings everything back.");
        hl.addActionListener(e -> highlightReel());
        JButton shot = Theme.iconButton(Theme.icon(Theme.Glyph.CAMERA, 18, Theme.TEXT), "Save this frame as a PNG (P)");
        shot.addActionListener(e -> frameGrab());
        undoBtn.addActionListener(e -> undo());
        redoBtn.addActionListener(e -> redo());
        for (Component c : new Component[]{split, del, in, out, hl, mark, shot, undoBtn, redoBtn}) l.add(c);
        p.add(l, BorderLayout.WEST);

        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        r.setOpaque(false);
        previewEdits.setFocusable(false);
        previewEdits.setToolTipText("Skip cut parts and play speed changes while previewing");
        r.add(previewEdits);
        JButton zin = Theme.button("+", null, false), zout = Theme.button("−", null, false);
        zin.setToolTipText("Zoom in (Ctrl+scroll)");
        zout.setToolTipText("Zoom out");
        zin.addActionListener(e -> timeline.zoomBy(1.6));
        zout.addActionListener(e -> timeline.zoomBy(1 / 1.6));
        r.add(zout);
        r.add(zin);
        p.add(r, BorderLayout.EAST);
        return p;
    }

    /** A panel that always fits the scroll pane's width (so nothing is clipped on the right). */
    static final class FitWidth extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private JComponent inspector() {
        JPanel p = new FitWidth();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.PANEL);
        Theme.pad(p, 6, 18, 24, 18);

        section(p, "Details");
        field(p, "Game", gameField);
        tagsField.putClientProperty("JTextField.placeholderText", "clutch, 1v3, montage");
        field(p, "Tags", tagsField);
        for (JTextField f : new JTextField[]{gameField, tagsField}) {
            f.addActionListener(e -> commitMeta());
            f.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { commitMeta(); } });
        }

        section(p, "Speed of this part");
        speed.setSelectedItem("1×");
        speed.addActionListener(e -> {
            if (loading || edit == null) return;
            int i = edit.segmentAt(player.time());
            if (i < 0) return;
            double v = Double.parseDouble(((String) speed.getSelectedItem()).replace("×", ""));
            if (edit.segments.get(i).speed == v) return;
            snapshot();
            edit.segments.get(i).speed = v;
            edited();
        });
        add(p, hint("Split around a kill, then slow it down. Right-click the timeline for more."));
        add(p, speed);

        section(p, "Frame");
        JPanel aspects = new JPanel(new GridLayout(1, 5, 4, 0));
        aspects.setOpaque(false);
        for (String[] a : new String[][]{{"original", "Original"}, {"16:9", "16:9"}, {"9:16", "9:16"}, {"1:1", "1:1"}, {"4:5", "4:5"}}) {
            JToggleButton b = new JToggleButton(a[1]);
            b.setFont(Theme.interBold.deriveFont(11.5f));
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(4, 2, 4, 2));
            b.addActionListener(e -> { if (!loading) { snapshot(); edit.aspect = a[0]; edited(); } });
            aspectGroup.add(b);
            aspectBtns.put(a[0], b);
            aspects.add(b);
        }
        add(p, aspects);
        add(p, Box.createVerticalStrut(8));
        fitMode.addActionListener(e -> { if (!loading) { snapshot(); edit.fitMode = fitMode.getSelectedIndex() == 0 ? "crop" : "blur"; edited(); } });
        field(p, "Fit", fitMode);
        slider(p, "Position", cropX, v -> edit.cropX = v / 100.0);
        slider(p, "Zoom", zoom, v -> edit.zoom = v / 100.0);
        effect.addActionListener(e -> {
            if (loading || edit == null) return;
            snapshot();
            edit.effect = EFFECTS[effect.getSelectedIndex()];
            edited();
        });
        field(p, "Look", effect);

        section(p, "Audio");
        slider(p, "Volume", volume, v -> edit.volume = v / 100.0);
        slider(p, "Your mic", micVol, v -> edit.micVolume = v / 100.0);
        micRow = (JComponent) p.getComponent(p.getComponentCount() - 1);
        slider(p, "Voice chat", voiceVol, v -> edit.voiceVolume = v / 100.0);
        voiceRow = (JComponent) p.getComponent(p.getComponentCount() - 1);
        mute.setOpaque(false);
        mute.addActionListener(e -> { if (!loading) { snapshot(); edit.mute = mute.isSelected(); edited(); } });
        add(p, mute);
        slider(p, "Fade in", fadeIn, v -> edit.fadeIn = v / 10.0);
        slider(p, "Fade out", fadeOut, v -> edit.fadeOut = v / 10.0);

        section(p, "Video overlays");
        add(p, hint("Put a second video on top: your facecam, a reaction, another angle. It starts at the playhead."));
        ovList.setVisibleRowCount(3);
        ovList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        ovList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadOverlay(); });
        JScrollPane ovScroll = new JScrollPane(ovList);
        ovScroll.setPreferredSize(new Dimension(200, 64));
        add(p, ovScroll);
        add(p, Box.createVerticalStrut(6));
        JPanel ovBtns = new JPanel(new GridLayout(1, 3, 4, 0));
        ovBtns.setOpaque(false);
        JButton ovFromLib = Theme.button("From library", null, false), ovFromFile = Theme.button("From file", null, false), ovDel = Theme.button("Remove", null, false);
        ovFromLib.addActionListener(e -> addOverlayFromLibrary());
        ovFromFile.addActionListener(e -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Videos", Library.VIDEO_EXT.toArray(String[]::new)));
            if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) addOverlay(fc.getSelectedFile().toPath());
        });
        ovDel.addActionListener(e -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) { snapshot(); edit.overlays.remove(o); edited(); refreshOverlays(); } });
        for (JButton b : new JButton[]{ovFromLib, ovFromFile, ovDel}) { b.setFont(Theme.interBold.deriveFont(11.5f)); b.setMargin(new java.awt.Insets(4, 2, 4, 2)); ovBtns.add(b); }
        add(p, ovBtns);
        add(p, Box.createVerticalStrut(6));
        ovPos.addActionListener(e -> {
            EditSpec.Overlay o = selectedOverlay();
            if (loading || o == null) return;
            snapshot();
            double m = 0.03, half = o.scale / 2;
            double hh = o.scale / 2; // same aspect as the frame: height fraction = width fraction
            switch (ovPos.getSelectedIndex()) {
                case 0 -> { o.x = 1 - m - half; o.y = m + hh; }
                case 1 -> { o.x = m + half; o.y = m + hh; }
                case 2 -> { o.x = 1 - m - half; o.y = 1 - m - hh; }
                case 3 -> { o.x = m + half; o.y = 1 - m - hh; }
                default -> { o.x = 0.5; o.y = 0.5; }
            }
            edited();
        });
        field(p, "Position", ovPos);
        slider(p, "Size", ovSize, v -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) { o.scale = v / 100.0; ovPos.setSelectedIndex(ovPos.getSelectedIndex()); } });
        slider(p, "Opacity", ovOpacity, v -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) o.opacity = v / 100.0; });
        ovAudio.setOpaque(false);
        ovAudio.addActionListener(e -> { EditSpec.Overlay o = selectedOverlay(); if (!loading && o != null) { snapshot(); o.audio = ovAudio.isSelected(); edited(); } });
        add(p, ovAudio);

        section(p, "Captions");
        add(p, hint("Text that shows between two moments. Meme style is big and loud; Subtitle sits on a dark box."));
        capList.setVisibleRowCount(4);
        capList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        capList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadCaption(); });
        JScrollPane capScroll = new JScrollPane(capList);
        capScroll.setPreferredSize(new Dimension(200, 84));
        add(p, capScroll);
        add(p, Box.createVerticalStrut(6));
        JPanel capBtns = new JPanel(new GridLayout(1, 4, 4, 0));
        capBtns.setOpaque(false);
        JButton capAdd = Theme.button("Add", null, false), capDel = Theme.button("Remove", null, false),
                capFrom = Theme.button("Start", null, false), capTo = Theme.button("End", null, false);
        capAdd.setToolTipText("Add a caption at the playhead (C)");
        capFrom.setToolTipText("Caption starts at the playhead");
        capTo.setToolTipText("Caption ends at the playhead");
        capAdd.addActionListener(e -> addCaption());
        capDel.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (c != null) { snapshot(); edit.captions.remove(c); edited(); refreshCaptions(); } });
        capFrom.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (c != null) { snapshot(); c.start = Math.min(player.time(), c.end - 0.2); edited(); refreshCaptions(); } });
        capTo.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (c != null) { snapshot(); c.end = Math.max(player.time(), c.start + 0.2); edited(); refreshCaptions(); } });
        for (JButton b : new JButton[]{capAdd, capDel, capFrom, capTo}) { b.setFont(Theme.interBold.deriveFont(11.5f)); b.setMargin(new java.awt.Insets(4, 2, 4, 2)); capBtns.add(b); }
        add(p, capBtns);
        add(p, Box.createVerticalStrut(6));
        capText.putClientProperty("JTextField.placeholderText", "Caption text");
        capText.getDocument().addDocumentListener(new DocumentListener() {
            void ch() { EditSpec.Caption c = selectedCaption(); if (!loading && c != null) { c.text = capText.getText(); edited(); updateCaptionRow(); } }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        add(p, capText);
        add(p, Box.createVerticalStrut(6));
        capStyle.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (!loading && c != null) { c.style = ((String) capStyle.getSelectedItem()).toLowerCase(); edited(); } });
        field(p, "Style", capStyle);
        capPos.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (!loading && c != null) { c.position = ((String) capPos.getSelectedItem()).toLowerCase(); edited(); } });
        field(p, "Position", capPos);

        section(p, "Title");
        titleText.putClientProperty("JTextField.placeholderText", "e.g. 1v4 clutch");
        titleText.getDocument().addDocumentListener(new DocumentListener() {
            void ch() { if (!loading) { edit.titleText = titleText.getText(); edited(); } }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        add(p, titleText);
        add(p, Box.createVerticalStrut(6));
        titlePos.addActionListener(e -> { if (!loading) { edit.titlePosition = ((String) titlePos.getSelectedItem()).toLowerCase(); edited(); } });
        field(p, "Position", titlePos);
        titleSecs.addActionListener(e -> {
            if (loading) return;
            edit.titleSeconds = new double[]{2, 3, 5, 0}[titleSecs.getSelectedIndex()];
            edited();
        });
        field(p, "Show", titleSecs);

        section(p, "Export");
        preset.setSelectedItem(Exporter.Preset.SOURCE);
        preset.addActionListener(e -> updatePresetInfo());
        add(p, preset);
        add(p, Box.createVerticalStrut(6));
        presetInfo.setVerticalAlignment(JLabel.TOP);
        add(p, presetInfo);
        gpu.setOpaque(false);
        gpu.setToolTipText("Much faster. Size-targeted presets always use x264 for an exact file size.");
        add(p, gpu);
        watermark.setOpaque(false);
        watermark.setSelected(app.settings.watermarkEnabled);
        watermark.setToolTipText("Your own text or logo, set up in Settings. Evade Clips never adds a watermark of its own.");
        add(p, watermark);
        add(p, Box.createVerticalStrut(10));
        exportBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        exportBtn.addActionListener(e -> exportOrCancel());
        add(p, exportBtn);
        add(p, Box.createVerticalStrut(8));
        progress.setVisible(false);
        add(p, progress);
        add(p, exportStatus);
        exportDone.setOpaque(false);
        exportDone.setVisible(false);
        add(p, exportDone);

        JButton reset = Theme.button("Reset all edits", null, false);
        reset.addActionListener(e -> { if (edit != null) { snapshot(); edit = EditSpec.whole(clip.duration); edited(); loadControls(); } });
        add(p, Box.createVerticalStrut(18));
        add(p, reset);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private static void add(JPanel p, JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (!(c instanceof JLabel) && !(c instanceof JCheckBox)) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        p.add(c);
    }

    private static void add(JPanel p, Component c) {
        if (c instanceof JComponent jc) add(p, jc); else p.add(c);
    }

    private static void section(JPanel p, String title) {
        JLabel h = Theme.heading(title);
        add(p, h);
    }

    private static JLabel hint(String text) {
        JLabel l = Theme.label("<html><body style='width:250px'>" + text + "</body></html>", 11.5f, false, Theme.FAINT);
        Theme.pad(l, 0, 0, 6, 0);
        return l;
    }

    private static void field(JPanel p, String label, JComponent c) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel l = Theme.label(label, 12.5f, false, Theme.DIM);
        l.setPreferredSize(new Dimension(70, 20));
        row.add(l, BorderLayout.WEST);
        row.add(c, BorderLayout.CENTER);
        Theme.pad(row, 0, 0, 6, 0);
        add(p, row);
    }

    private void slider(JPanel p, String label, JSlider s, java.util.function.IntConsumer apply) {
        s.setOpaque(false);
        s.setFocusable(false);
        boolean[] dragging = {false};
        s.addChangeListener(e -> {
            if (loading || edit == null) return;
            if (s.getValueIsAdjusting() && !dragging[0]) { snapshot(); dragging[0] = true; }
            if (!s.getValueIsAdjusting()) {
                if (!dragging[0]) snapshot();
                dragging[0] = false;
            }
            apply.accept(s.getValue());
            edited();
        });
        field(p, label, s);
    }

    // ------------------------------------------------------------------ opening a clip

    public void open(Clip c) {
        player.close();
        commitMeta();
        clip = c;
        undo.clear();
        redo.clear();
        if (c == null) return;
        edit = c.editOrDefault().copy();
        // A clip that was edited and then trimmed externally could carry stale segments; clamp them.
        for (EditSpec.Segment s : edit.segments) { s.end = Math.min(s.end, c.duration); s.start = Math.min(s.start, s.end); }
        edit.segments.removeIf(s -> s.length() < 0.05);
        if (edit.segments.isEmpty()) edit = EditSpec.whole(c.duration);
        loadControls();
        Path file = c.path(app.library.root());
        view.setFrame(null);
        view.setSource(c.width, c.height);
        BufferedImage strip = null;
        try { if (Files.exists(app.library.strip(c))) strip = ImageIO.read(app.library.strip(c).toFile()); } catch (Exception ignored) {}
        timeline.setClip(c.duration, edit, c.markers, strip, Library.STRIP_FRAMES);
        timeline.labelOf = c::label;
        timeline.setPlayhead(0);
        player.open(file, c.duration, c.fps, c.hasAudio, c.width, c.height);
        player.setAudioTracks(c.audioTracks);
        updateTime(0);
        updatePlayIcon();
        loadWaveform(c, file);
        exportDone.setVisible(false);
        exportStatus.setText(" ");
        currentJob = null;
        updateJob();
        SwingUtilities.invokeLater(view::requestFocusInWindow);
    }

    public void close() {
        commitMeta();
        player.close();
    }

    public Clip clip() { return clip; }

    private void loadWaveform(Clip c, Path file) {
        if (!c.hasAudio) return;
        Path wav = app.library.thumbsDir().resolve(c.id + "-wave.png");
        new Thread(() -> {
            try {
                if (!Files.exists(wav)) {
                    Files.createDirectories(wav.getParent());
                    Ffmpeg.run(List.of("-i", file.toString(), "-filter_complex",
                            "aformat=channel_layouts=mono,compand=gain=4,showwavespic=s=2400x120:colors=0xE11D2E", "-frames:v", "1", wav.toString()));
                }
                BufferedImage img = ImageIO.read(wav.toFile());
                SwingUtilities.invokeLater(() -> { if (clip == c) timeline.setWave(img); });
            } catch (Exception ignored) {}
        }, "waveform").start();
    }

    private void loadControls() {
        loading = true;
        titleField.setText(clip.title);
        gameField.setText(clip.game == null ? "" : clip.game);
        tagsField.setText(String.join(", ", clip.tags));
        favBtn.setSelected(clip.favorite);
        JToggleButton ab = aspectBtns.get(edit.aspect);
        if (ab != null) ab.setSelected(true);
        fitMode.setSelectedIndex(edit.fitMode.equals("blur") ? 1 : 0);
        cropX.setValue((int) Math.round(edit.cropX * 100));
        zoom.setValue((int) Math.round(edit.zoom * 100));
        volume.setValue((int) Math.round(edit.volume * 100));
        micVol.setValue((int) Math.round(edit.micVolume * 100));
        micRow.setVisible(clip.audioTracks >= 2);
        voiceVol.setValue((int) Math.round(edit.voiceVolume * 100));
        voiceRow.setVisible(clip.trackNames.contains("Voice chat"));
        effect.setSelectedIndex(Math.max(0, java.util.Arrays.asList(EFFECTS).indexOf(edit.effect)));
        player.setVideoFilter(Exporter.effectFilter(edit.effect));
        refreshCaptions();
        refreshOverlays();
        for (EditSpec.Overlay o : edit.overlays) loadOverlayFrame(o.file);
        mute.setSelected(edit.mute);
        fadeIn.setValue((int) Math.round(edit.fadeIn * 10));
        fadeOut.setValue((int) Math.round(edit.fadeOut * 10));
        titleText.setText(edit.titleText);
        titlePos.setSelectedIndex(switch (edit.titlePosition) { case "center" -> 1; case "top" -> 2; default -> 0; });
        titleSecs.setSelectedIndex(edit.titleSeconds == 2 ? 0 : edit.titleSeconds == 5 ? 2 : edit.titleSeconds == 0 ? 3 : 1);
        loading = false;
        updateControlsEnabled();
        updatePresetInfo();
        updateUndo();
    }

    private void updateControlsEnabled() {
        boolean framed = !edit.aspect.equals("original");
        fitMode.setEnabled(framed);
        cropX.setEnabled((framed && edit.fitMode.equals("crop")) || edit.zoom > 1.001);
    }

    private void commitMeta() {
        if (clip == null) return;
        String t = titleField.getText().trim();
        String g = gameField.getText().trim();
        List<String> tags = new java.util.ArrayList<>();
        for (String s : tagsField.getText().split(",")) if (!s.isBlank() && !tags.contains(s.trim())) tags.add(s.trim());
        boolean changed = !t.equals(clip.title) || !g.equals(clip.game == null ? "" : clip.game) || !tags.equals(clip.tags);
        if (!changed) return;
        if (!t.isEmpty()) clip.title = t;
        clip.game = g;
        clip.tags = tags;
        app.library.update(clip);
    }

    // ------------------------------------------------------------------ editing

    private void snapshot() {
        if (edit == null) return;
        undo.push(edit.copy());
        if (undo.size() > 100) undo.removeLast();
        redo.clear();
        updateUndo();
    }

    private void edited() {
        edit.sort();
        player.setVideoFilter(Exporter.effectFilter(edit.effect));
        timeline.setEdit(edit);
        updateControlsEnabled();
        updatePresetInfo();
        updateUndo();
        view.repaint();
        saveLater.restart();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(edit.copy());
        edit = undo.pop();
        loadControls();
        edited();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(edit.copy());
        edit = redo.pop();
        loadControls();
        edited();
    }

    private void updateUndo() {
        undoBtn.setEnabled(!undo.isEmpty());
        redoBtn.setEnabled(!redo.isEmpty());
    }

    private void split() {
        if (edit == null) return;
        EditSpec before = edit.copy();
        if (edit.splitAt(player.time())) { undo.push(before); redo.clear(); edited(); }
    }

    private void deletePart() {
        if (edit == null) return;
        int i = edit.segmentAt(player.time());
        if (i < 0 || edit.segments.size() <= 1) {
            if (edit.segments.size() <= 1) app.notify("Split first", "Press S at both ends of the part you want to cut", false);
            return;
        }
        snapshot();
        double nextStart = i + 1 < edit.segments.size() ? edit.segments.get(i + 1).start : edit.segments.get(i - 1).start;
        edit.segments.remove(i);
        edited();
        seekTo(nextStart);
    }

    private void setIn() {
        int i = edit == null ? -1 : edit.segmentAt(player.time());
        if (i < 0) return;
        snapshot();
        edit.segments.get(i).start = player.time();
        edited();
    }

    private void setOut() {
        if (edit == null) return;
        double t = player.time();
        int i = edit.segmentAt(t);
        if (i < 0) return;
        snapshot();
        edit.segments.get(i).end = Math.max(edit.segments.get(i).start + 0.1, t);
        edited();
    }

    /** 9:16 with a blurred background (nothing gets cut off), and the Shorts preset picked for export. */
    private void makeVertical() {
        if (edit == null) return;
        snapshot();
        edit.aspect = "9:16";
        edit.fitMode = "blur";
        edit.cropX = 0.5;
        loadControls();
        edited();
        preset.setSelectedItem(Exporter.Preset.SHORTS);
        app.notify("Vertical 9:16", "Switch Fit to Crop to fill the screen instead · Export when ready", false);
    }

    private EditSpec.Overlay selectedOverlay() {
        int i = ovList.getSelectedIndex();
        return edit == null || i < 0 || i >= edit.overlays.size() ? null : edit.overlays.get(i);
    }

    private void addOverlayFromLibrary() {
        List<Clip> clips = app.library.query("", "all", null, "newest");
        clips.removeIf(c -> c == clip);
        if (clips.isEmpty()) { app.notify("No other clips yet", null, false); return; }
        Clip pick = (Clip) javax.swing.JOptionPane.showInputDialog(this, "Clip to overlay", "Video overlay", javax.swing.JOptionPane.PLAIN_MESSAGE,
                null, clips.toArray(), clips.get(0));
        if (pick != null) addOverlay(pick.path(app.library.root()));
    }

    private void addOverlay(Path file) {
        if (edit == null) return;
        snapshot();
        EditSpec.Overlay o = new EditSpec.Overlay();
        o.file = file.toAbsolutePath().toString();
        o.start = player.time();
        try { o.length = Ffmpeg.probe(file).duration(); } catch (Exception ignored) {}
        edit.overlays.add(o);
        edited();
        refreshOverlays();
        ovList.setSelectedIndex(edit.overlays.size() - 1);
        ovPos.setSelectedIndex(0);
        loadOverlayFrame(o.file);
    }

    /** Grabs a frame of an overlay video for the preview (once per file). */
    private void loadOverlayFrame(String file) {
        if (ovFrames.containsKey(file)) return;
        new Thread(() -> {
            try {
                Path png = java.nio.file.Files.createTempFile("evadeclips-ov", ".png");
                Ffmpeg.run(List.of("-ss", "0.5", "-i", file, "-frames:v", "1", "-vf", "scale=320:-2", png.toString()));
                ovFrames.put(file, ImageIO.read(png.toFile()));
                java.nio.file.Files.deleteIfExists(png);
                view.repaint();
            } catch (Exception ignored) {}
        }, "overlay-frame").start();
    }

    private void refreshOverlays() {
        int sel = ovList.getSelectedIndex();
        loading = true;
        ovModel.clear();
        for (EditSpec.Overlay o : edit.overlays) ovModel.addElement(Theme.timeFine(o.start) + "  " + Path.of(o.file).getFileName());
        loading = false;
        if (sel >= 0 && sel < ovModel.size()) ovList.setSelectedIndex(sel);
        loadOverlay();
    }

    private void loadOverlay() {
        EditSpec.Overlay o = selectedOverlay();
        boolean on = o != null;
        loading = true;
        ovPos.setEnabled(on); ovSize.setEnabled(on); ovOpacity.setEnabled(on); ovAudio.setEnabled(on);
        if (on) {
            ovSize.setValue((int) Math.round(o.scale * 100));
            ovOpacity.setValue((int) Math.round(o.opacity * 100));
            ovAudio.setSelected(o.audio);
        }
        loading = false;
    }

    private EditSpec.Caption selectedCaption() {
        int i = capList.getSelectedIndex();
        return edit == null || i < 0 || i >= edit.captions.size() ? null : edit.captions.get(i);
    }

    private void addCaption() {
        if (edit == null) return;
        snapshot();
        double t = player.time();
        EditSpec.Caption c = new EditSpec.Caption("", t, Math.min(clip.duration, t + 3), "bottom", "clean");
        edit.captions.add(c);
        edit.captions.sort(java.util.Comparator.comparingDouble(x -> x.start));
        edited();
        refreshCaptions();
        capList.setSelectedIndex(edit.captions.indexOf(c));
        capText.requestFocusInWindow();
    }

    private void refreshCaptions() {
        int sel = capList.getSelectedIndex();
        loading = true;
        capModel.clear();
        for (EditSpec.Caption c : edit.captions) capModel.addElement(captionRow(c));
        loading = false;
        if (sel >= 0 && sel < capModel.size()) capList.setSelectedIndex(sel);
        loadCaption();
    }

    private static String captionRow(EditSpec.Caption c) {
        return Theme.timeFine(c.start) + "–" + Theme.timeFine(c.end) + "  " + (c.text.isBlank() ? "(empty)" : c.text);
    }

    private void updateCaptionRow() {
        int i = capList.getSelectedIndex();
        if (i >= 0 && i < capModel.size()) capModel.set(i, captionRow(edit.captions.get(i)));
    }

    private void loadCaption() {
        EditSpec.Caption c = selectedCaption();
        boolean on = c != null;
        loading = true;
        capText.setEnabled(on); capStyle.setEnabled(on); capPos.setEnabled(on);
        capText.setText(on ? c.text : "");
        if (on) {
            capStyle.setSelectedIndex(switch (c.style) { case "subtitle" -> 1; case "meme" -> 2; default -> 0; });
            capPos.setSelectedIndex(switch (c.position) { case "center" -> 1; case "top" -> 2; default -> 0; });
        }
        loading = false;
    }

    /** Scans the audio for the action, marks it, and cuts the clip down to just those moments. */
    private void highlightReel() {
        if (clip == null || !clip.hasAudio) { app.notify("No audio to scan", "Highlights are found from the sound of the action", false); return; }
        Clip c = clip;
        app.notify("Finding highlights…", null, false);
        new Thread(() -> {
            try {
                var moments = net.evadeclient.clips.core.Highlights.detect(c.path(app.library.root()), c.duration, 0, null);
                SwingUtilities.invokeLater(() -> {
                    if (clip != c) return;
                    if (moments.isEmpty()) { app.notify("No highlights found", "Nothing stood out from the background", false); return; }
                    for (var m : moments) if (c.markers.stream().noneMatch(x -> Math.abs(x - m.time()) < 2)) c.markers.add(m.time());
                    c.markers.sort(Double::compare);
                    timeline.setMarkers(c.markers);
                    snapshot();
                    EditSpec cut = net.evadeclient.clips.core.Highlights.cut(moments, c.duration, 5, 3);
                    cut.aspect = edit.aspect; cut.fitMode = edit.fitMode; cut.cropX = edit.cropX; cut.zoom = edit.zoom;
                    cut.volume = edit.volume; cut.micVolume = edit.micVolume; cut.titleText = edit.titleText;
                    cut.titlePosition = edit.titlePosition; cut.titleSeconds = edit.titleSeconds;
                    cut.effect = edit.effect; cut.voiceVolume = edit.voiceVolume;
                    for (EditSpec.Caption cc : edit.captions) cut.captions.add(cc.copy());
                    edit = cut;
                    loadControls();
                    edited();
                    seekTo(edit.segments.get(0).start);
                    app.notify(moments.size() + " highlight" + (moments.size() == 1 ? "" : "s") + " kept",
                            Theme.timeFine(c.duration) + " → " + Theme.timeFine(edit.outputDuration()) + " · Ctrl+Z to undo", false);
                });
            } catch (Exception ex) { app.notify("Highlight scan failed", ex.getMessage(), true); }
        }, "highlights").start();
    }

    private void frameGrab() {
        if (clip == null) return;
        Clip c = clip;
        double t = player.time();
        new Thread(() -> {
            try {
                Path png = app.screenshot(c, t);
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.Transferable() {
                    public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.javaFileListFlavor}; }
                    public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return f.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor); }
                    public Object getTransferData(java.awt.datatransfer.DataFlavor f) { return List.of(png.toFile()); }
                }, null);
                app.notify("Frame saved and copied", png.getFileName().toString(), false);
            } catch (Exception ex) { app.notify("Couldn't save the frame", ex.getMessage(), true); }
        }, "frame-grab").start();
    }

    private void addMarker() {
        if (clip == null) return;
        clip.markers.add(player.time());
        clip.markers.sort(Double::compare);
        timeline.setMarkers(clip.markers);
        app.library.update(clip);
    }

    // ------------------------------------------------------------------ playback

    private void togglePlay() {
        if (clip == null) return;
        if (player.isPlaying()) player.pause();
        else {
            double t = player.time();
            if (previewEdits.isSelected()) {
                int i = edit.segmentAt(t);
                if (i < 0) t = nextSegmentStart(t);
                if (t >= clip.duration - 0.05 || (edit.segmentAt(t) < 0)) t = edit.segments.get(0).start;
            } else if (t >= clip.duration - 0.05) t = 0;
            int i = edit.segmentAt(t);
            player.play(t, previewEdits.isSelected() && i >= 0 ? edit.segments.get(i).speed : 1);
        }
        updatePlayIcon();
    }

    private double nextSegmentStart(double t) {
        for (EditSpec.Segment s : edit.segments) if (s.start >= t - 1e-6) return s.start;
        return clip.duration;
    }

    private void seekTo(double t) {
        player.seek(t);
        timeline.setPlayhead(t);
        updateTime(t);
    }

    private void step(int frames) {
        if (clip == null) return;
        if (player.isPlaying()) player.pause();
        double fps = clip.fps > 0 ? Math.min(60, clip.fps) : 30;
        seekTo(Math.max(0, Math.min(clip.duration, player.time() + frames / fps)));
        updatePlayIcon();
    }

    private void tick() {
        if (clip == null) return;
        double t = player.time();
        if (player.isPlaying() && previewEdits.isSelected() && edit != null) {
            int i = edit.segmentAt(t);
            if (i < 0) {
                double n = nextSegmentStart(t);
                if (n >= clip.duration - 0.01) { player.pause(); updatePlayIcon(); }
                else { int j = edit.segmentAt(n); player.play(n, edit.segments.get(j).speed); }
            } else {
                EditSpec.Segment s = edit.segments.get(i);
                if (Math.abs(player.speed() - s.speed) > 1e-6) player.play(t, s.speed);
                else if (t >= s.end - 0.035) {
                    if (i + 1 < edit.segments.size()) player.play(edit.segments.get(i + 1).start, edit.segments.get(i + 1).speed);
                    else { player.pause(); updatePlayIcon(); }
                }
            }
        }
        timeline.setPlayhead(t);
        updateTime(t);
        if (!player.isPlaying() && playBtnShowsPause) updatePlayIcon();
        // Speed box follows the part under the playhead.
        if (edit != null) {
            int i = edit.segmentAt(t);
            loading = true;
            speed.setEnabled(i >= 0);
            if (i >= 0) speed.setSelectedItem(Timeline.fmtSpeed(edit.segments.get(i).speed));
            loading = false;
        }
    }

    private boolean playBtnShowsPause;

    private void updatePlayIcon() {
        playBtnShowsPause = player.isPlaying();
        playBtn.setIcon(Theme.icon(playBtnShowsPause ? Theme.Glyph.PAUSE : Theme.Glyph.PLAY, 22, Theme.TEXT));
    }

    private void updateTime(double t) {
        if (clip == null) return;
        timeLabel.setText(Theme.timeFine(t) + "  /  " + Theme.timeFine(clip.duration));
        if (edit != null) {
            double out = edit.outputDuration();
            outLabel.setText(edit.segments.size() + (edit.segments.size() == 1 ? " part" : " parts") + "  ·  final " + Theme.timeFine(out));
        }
    }

    private void bindKeys() {
        var im = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        var am = getActionMap();
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "play", this::togglePlay);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_K, 0), "playk", this::togglePlay);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prev", () -> step(-1));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next", () -> step(1));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK), "back5", () -> seekTo(Math.max(0, player.time() - 5)));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK), "fwd5", () -> seekTo(Math.min(clip.duration, player.time() + 5)));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_J, 0), "j", () -> seekTo(Math.max(0, player.time() - 2)));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_L, 0), "l", () -> seekTo(Math.min(clip.duration, player.time() + 2)));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "home", () -> seekTo(0));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "end", () -> seekTo(clip.duration));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "split", this::split);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "del", this::deletePart);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "del2", this::deletePart);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), "in", this::setIn);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_O, 0), "out", this::setOut);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "mark", this::addMarker);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "hl", this::highlightReel);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "shot", this::frameGrab);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "caption", this::addCaption);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo", this::undo);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo", this::redo);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redo2", this::redo);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "export", this::exportOrCancel);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc", () -> app.openEditor.accept(null));
    }

    /** Bound keys that would otherwise type into text fields are skipped while one has focus. */
    private void key(javax.swing.InputMap im, javax.swing.ActionMap am, KeyStroke ks, String name, Runnable r) {
        im.put(ks, name);
        am.put(name, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                Component f = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                boolean typing = f instanceof javax.swing.text.JTextComponent;
                if (typing && ks.getModifiers() == 0 && ks.getKeyCode() != KeyEvent.VK_ESCAPE) return;
                if (typing && ks.getKeyCode() == KeyEvent.VK_ESCAPE) { view.requestFocusInWindow(); return; }
                if (clip != null) r.run();
            }
        });
    }

    // ------------------------------------------------------------------ export

    private void updatePresetInfo() {
        Exporter.Preset p = (Exporter.Preset) preset.getSelectedItem();
        if (p == null || clip == null || edit == null) return;
        Exporter.Options o = options();
        String info = p.description;
        if (p == Exporter.Preset.QUICK_TRIM) {
            boolean simple = edit.segments.stream().allMatch(s -> s.speed == 1) && edit.aspect.equals("original") && edit.zoom == 1
                    && edit.titleText.isBlank() && edit.volume == 1 && !edit.mute && edit.fadeIn == 0 && edit.fadeOut == 0
                    && edit.captions.isEmpty() && "none".equals(edit.effect);
            if (!simple) info += " Only cuts are applied: speed, framing, audio, captions, effects and title need another preset.";
        } else {
            int[] sz = Exporter.outputSize(clip, edit, o);
            info += "<br><span style='color:#F4F4F6'>" + sz[0] + "×" + sz[1] + " · " + Theme.timeFine(edit.outputDuration()) + "</span>";
        }
        presetInfo.setText("<html><body style='width:250px'>" + info + "</body></html>");
        gpu.setVisible(p != Exporter.Preset.QUICK_TRIM && p != Exporter.Preset.GIF && p.targetMb == 0);
    }

    private Exporter.Options options() {
        Exporter.Options o = new Exporter.Options();
        o.preset = (Exporter.Preset) preset.getSelectedItem();
        o.gpu = gpu.isSelected();
        if (watermark.isSelected()) {
            boolean was = app.settings.watermarkEnabled;
            app.settings.watermarkEnabled = true;
            o.watermark = Exporter.Watermark.from(app.settings);
            app.settings.watermarkEnabled = was;
        }
        return o;
    }

    private void exportOrCancel() {
        if (clip == null) return;
        if (currentJob != null && !currentJob.done) { currentJob.job.cancel(); return; }
        commitMeta();
        clip.edit = edit;
        app.library.update(clip);
        currentJob = app.export(clip, edit, options(), j -> updateJob());
        exportDone.setVisible(false);
        updateJob();
    }

    private void updateJob() {
        App.ExportJob j = currentJob;
        boolean running = j != null && !j.done;
        progress.setVisible(running);
        exportBtn.setText(running ? "Cancel export" : "Export");
        if (j == null) return;
        progress.setValue((int) (j.progress * 1000));
        if (running) exportStatus.setText(j.state + " · " + Math.round(j.progress * 100) + "%");
        else if (j.result != null) {
            exportStatus.setText("Saved " + Theme.size(j.result.size) + " · " + j.out.getFileName());
            exportDone.removeAll();
            JButton copy = Theme.button("Copy file", Theme.icon(Theme.Glyph.COPY, 14, Theme.TEXT), false);
            copy.addActionListener(e -> {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(LibraryPanel.files(List.of(j.result), app.library.root()), null);
                app.notify("Copied — paste it into Discord", null, false);
            });
            JButton show = Theme.button("Show", Theme.icon(Theme.Glyph.FOLDER, 14, Theme.TEXT), false);
            show.addActionListener(e -> LibraryPanel.reveal(j.out));
            exportDone.add(copy);
            exportDone.add(Box.createHorizontalStrut(6));
            exportDone.add(show);
            exportDone.setVisible(true);
            exportDone.revalidate();
        } else exportStatus.setText(j.state);
    }

    // ------------------------------------------------------------------ preview surface

    /** Paints the current frame letterboxed, with the crop window and title drawn on top. */
    private final class VideoView extends JComponent {
        private volatile BufferedImage frame;
        private int srcW = 16, srcH = 9;

        VideoView() {
            setFocusable(true);
            setPreferredSize(new Dimension(640, 360));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); }
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 1) togglePlay(); }
            });
        }

        void setSource(int w, int h) { srcW = Math.max(1, w); srcH = Math.max(1, h); }
        void setFrame(BufferedImage img) { frame = img; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.hq(g);
            int W = getWidth(), H = getHeight() - 4;
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, W, H);
            double k = Math.min(W / (double) srcW, H / (double) srcH);
            int dw = (int) (srcW * k), dh = (int) (srcH * k);
            int x = (W - dw) / 2, y = (H - dh) / 2;
            BufferedImage f = frame;
            if (f != null) g.drawImage(f, x, y, dw, dh, null);
            if (edit != null && clip != null) {
                boolean framed = !edit.aspect.equals("original") || edit.zoom > 1.001;
                if (framed) {
                    Rectangle2D crop = cropRect(x, y, dw, dh);
                    if (edit.fitMode.equals("blur") && !edit.aspect.equals("original")) {
                        // show the output canvas outline around the frame
                        double r = aspect();
                        double cw, ch;
                        if (r < dw / (double) dh) { ch = dh; cw = dh * r; } else { cw = dw; ch = dw / r; }
                        g.setColor(new Color(255, 255, 255, 120));
                        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 5}, 0));
                        g.draw(new Rectangle2D.Double(x + (dw - cw) / 2, y + (dh - ch) / 2, cw, ch));
                    } else {
                        Area dim = new Area(new Rectangle2D.Double(x, y, dw, dh));
                        dim.subtract(new Area(crop));
                        g.setColor(new Color(0, 0, 0, 170));
                        g.fill(dim);
                        g.setColor(Theme.RED);
                        g.setStroke(new BasicStroke(2f));
                        g.draw(crop);
                    }
                }
                if (!edit.titleText.isBlank()) {
                    Rectangle2D area = edit.fitMode.equals("blur") || edit.aspect.equals("original") ? new Rectangle2D.Double(x, y, dw, dh) : cropRect(x, y, dw, dh);
                    int tw = (int) area.getWidth(), th = (int) area.getHeight();
                    String key = edit.titleText + "|" + edit.titlePosition + "|" + tw + "x" + th;
                    if (!key.equals(titlePreviewKey) && tw > 4 && th > 4) {
                        titlePreview = Exporter.renderTitle(edit.titleText, edit.titlePosition, tw, th);
                        titlePreviewKey = key;
                    }
                    double t = player.time();
                    boolean visible = edit.titleSeconds <= 0 || outputTimeAt(t) <= edit.titleSeconds;
                    if (titlePreview != null && visible) g.drawImage(titlePreview, (int) area.getX(), (int) area.getY(), null);
                }
                double now = player.time();
                // video overlays (their first frame, as a stand-in, where and when they'll show)
                for (EditSpec.Overlay ov : edit.overlays) {
                    double end = ov.start + (ov.length > 0 ? ov.length : clip.duration);
                    if (now < ov.start || now > end) continue;
                    double ow2 = dw * ov.scale;
                    BufferedImage fr = ovFrames.get(ov.file);
                    double oh2 = fr != null ? ow2 * fr.getHeight() / fr.getWidth() : ow2 * 9 / 16;
                    int ox = (int) (x + dw * ov.x - ow2 / 2), oy = (int) (y + dh * ov.y - oh2 / 2);
                    Graphics2D go = (Graphics2D) g.create();
                    go.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) ov.opacity));
                    if (fr != null) go.drawImage(fr, ox, oy, (int) ow2, (int) oh2, null);
                    else { go.setColor(new Color(40, 40, 50)); go.fillRect(ox, oy, (int) ow2, (int) oh2); }
                    go.setComposite(AlphaComposite.SrcOver);
                    go.setColor(Theme.BLUE);
                    go.setStroke(new BasicStroke(1.5f));
                    go.drawRect(ox, oy, (int) ow2, (int) oh2);
                    go.dispose();
                }
                // captions on screen right now
                Rectangle2D area2 = edit.fitMode.equals("blur") || edit.aspect.equals("original") ? new Rectangle2D.Double(x, y, dw, dh) : cropRect(x, y, dw, dh);
                for (EditSpec.Caption c : edit.captions) {
                    if (c.text.isBlank() || now < c.start || now > c.end) continue;
                    int tw = (int) area2.getWidth(), th = (int) area2.getHeight();
                    if (tw < 4 || th < 4) continue;
                    String key = c.text + "|" + c.style + "|" + c.position + "|" + tw + "x" + th;
                    BufferedImage im = capPreview.computeIfAbsent(key, kk -> Exporter.renderCaption(c.text, c.position, c.style, tw, th));
                    if (capPreview.size() > 64) capPreview.clear();
                    g.drawImage(im, (int) area2.getX(), (int) area2.getY(), null);
                }
                // dim when the playhead sits in a cut-out part
                if (edit.segmentAt(player.time()) < 0) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                    g.setColor(Color.BLACK);
                    g.fillRect(x, y, dw, dh);
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setFont(Theme.interBold.deriveFont(13f));
                    g.setColor(Theme.TEXT);
                    String s = "CUT — not in the export";
                    g.drawString(s, x + (dw - g.getFontMetrics().stringWidth(s)) / 2, y + dh / 2);
                }
            }
            g.dispose();
        }

        private double aspect() { return switch (edit.aspect) {
            case "16:9" -> 16 / 9.0; case "9:16" -> 9 / 16.0; case "1:1" -> 1; case "4:5" -> 0.8; default -> srcW / (double) srcH; }; }

        private Rectangle2D cropRect(int x, int y, int dw, int dh) {
            double r = aspect(), sr = dw / (double) dh;
            double cw, ch;
            if (r < sr) { ch = dh; cw = dh * r; } else { cw = dw; ch = dw / r; }
            cw /= Math.max(1, edit.zoom);
            ch /= Math.max(1, edit.zoom);
            return new Rectangle2D.Double(x + (dw - cw) * edit.cropX, y + (dh - ch) / 2, cw, ch);
        }
    }

    /** Where source time t lands in the exported clip. */
    private double outputTimeAt(double t) {
        double out = 0;
        for (EditSpec.Segment s : edit.segments) {
            if (t >= s.end) out += s.outputLength();
            else if (t >= s.start) { out += (t - s.start) / s.speed; break; }
            else break;
        }
        return out;
    }
}
