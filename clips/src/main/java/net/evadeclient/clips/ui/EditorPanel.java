package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.AutoCaptions;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditPresets;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Highlights;
import net.evadeclient.clips.core.Library;
import net.evadeclient.clips.core.ProjectExport;
import net.evadeclient.clips.core.Sounds;

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
import javax.swing.JTabbedPane;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The clip editor: preview player, timeline (trim, split, delete parts, slow-mo / speed-up per part, freeze
 * frames and rewinds, markers), framing for vertical and square video, audio and music, a title card, and
 * export. Edits are saved with the clip as you go and never touch the original file.
 */
public final class EditorPanel extends JPanel {
    private final App app;
    private Clip clip;
    private EditSpec edit;
    /** One undo step: the edit and the clip's markers (with their names) as they were. */
    private record UndoState(EditSpec edit, List<Double> markers, Map<String, String> labels) {}
    private final Deque<UndoState> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    /** The last undo step pushed, as text, so a step that changed nothing isn't pushed twice. */
    private String undoTopKey;
    private static final com.google.gson.Gson UNDO_GSON = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create();

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
    private final JComboBox<String> speed = new JComboBox<>(new String[]{"0.25×", "0.3×", "0.5×", "0.75×", "1×", "1.5×", "2×", "4×"});
    private final ButtonGroup aspectGroup = new ButtonGroup();
    private final java.util.Map<String, JToggleButton> aspectBtns = new java.util.LinkedHashMap<>();
    private final JComboBox<String> fitMode = new JComboBox<>(new String[]{"Crop to fill", "Blurred background", "Facecam on top"});
    private static final String[] FITS = {"crop", "blur", "split"};
    private final JTabbedPane tabs = new JTabbedPane();
    // edit tab
    private final javax.swing.DefaultListModel<EditPresets.Preset> presetModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<EditPresets.Preset> presetList = new javax.swing.JList<>(presetModel);
    private final JComboBox<String> rampTo = new JComboBox<>(new String[]{"Steady speed", "Ramp to 0.25×", "Ramp to 0.3×", "Ramp to 0.5×", "Ramp to 0.75×", "Ramp to 1×", "Ramp to 1.5×", "Ramp to 2×", "Ramp to 4×"});
    private final JComboBox<String> smoothing = new JComboBox<>(new String[]{"Off (repeat frames)", "Frame blending", "Optical flow (smoothest)"});
    private static final String[] SMOOTH = {"off", "blend", "flow"};
    // frame tab
    private final JComboBox<String> camCorner = new JComboBox<>(new String[]{"Top right", "Top left", "Bottom right", "Bottom left", "Custom (drawn on the video)"});
    private final JSlider camShare = new JSlider(20, 60, 38);
    private final JLabel camFileLabel = Theme.label("", 11.5f, false, Theme.FAINT);
    private final JPanel camBox = new JPanel();
    private final JComboBox<String> zoomTarget = new JComboBox<>(new String[]{"Center / crosshair", "Kill feed (top right)", "Chat (bottom left)", "Minimap (top left)", "Health (bottom center)", "Click on the video..."});
    private static final double[][] TARGETS = {{0.5, 0.5}, {0.86, 0.12}, {0.17, 0.78}, {0.12, 0.15}, {0.5, 0.88}};
    private final JSlider zoomAmount = new JSlider(110, 300, 160);
    private final javax.swing.DefaultListModel<String> zkModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> zkList = new javax.swing.JList<>(zkModel);
    private final javax.swing.DefaultListModel<String> maskModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> maskList = new javax.swing.JList<>(maskModel);
    private final JComboBox<String> maskKind = new JComboBox<>(new String[]{"Blur", "Pixelate", "Black box"});
    private static final String[] MASKS = {"blur", "pixelate", "black"};
    // text tab
    private final JComboBox<AutoCaptions.Source> acSource = new JComboBox<>(AutoCaptions.Source.values());
    private final JComboBox<AutoCaptions.Layout> acLayout = new JComboBox<>(AutoCaptions.Layout.values());
    private final JComboBox<String> acPos = new JComboBox<>(new String[]{"Center", "Bottom", "Top"});
    private final JButton acBtn = Theme.button("Caption my clip", null, true);
    private final JLabel acStatus = Theme.label(" ", 11.5f, false, Theme.DIM);
    // effects tab
    private final JComboBox<String> fxSound = new JComboBox<>();
    private final javax.swing.DefaultListModel<String> fxModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> fxList = new javax.swing.JList<>(fxModel);
    private final JSlider fxStrength = new JSlider(0, 100, 60), fxVolume = new JSlider(0, 200, 100);
    private final JComboBox<String> fxSoundSel = new JComboBox<>();
    // export tab
    private final JCheckBox prores = new JCheckBox("Edit-friendly ProRes copy (big files, smooth scrubbing)");
    private final JCheckBox normalize = new JCheckBox("Normalize loudness", true);
    // frame tab: webcam file timing
    private final javax.swing.JSpinner camOffset = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0.0, -3600.0, 3600.0, 0.1));
    // audio tab: mic clean-up and music
    private final JCheckBox micCleanup = new JCheckBox("Clean up mic (reduce hiss and rumble)");
    private final JLabel musicLabel = Theme.label("No music", 11.5f, false, Theme.FAINT);
    private final JSlider musicVol = new JSlider(0, 200, 50);
    private JComponent musicVolRow, musicStartRow;
    private final JCheckBox musicDuck = new JCheckBox("Duck under game and mic", true);
    private final javax.swing.JSpinner musicStart = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0.0, 0.0, 36000.0, 0.5));
    private final JButton musicRemove = small("Remove", "Take the music out");
    // drawing on the video
    private enum Pick { NONE, CAM, MASK, ZOOM }
    private Pick pick = Pick.NONE;
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
    private JComponent micCleanupRow;
    private final javax.swing.DefaultListModel<String> capModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<String> capList = new javax.swing.JList<>(capModel);
    private final JTextField capText = new JTextField();
    private final JComboBox<String> capStyle = new JComboBox<>(new String[]{"Clean", "Subtitle", "Meme", "Pop", "Highlight"});
    private static final String[] CAP_STYLES = {"clean", "subtitle", "meme", "pop", "highlight"};
    private final JComboBox<String> capPos = new JComboBox<>(new String[]{"Bottom", "Center", "Top"});
    /** Caption pictures for the preview, cropped to the text, most recently used last; at most ~32 MB. */
    private final java.util.LinkedHashMap<String, Exporter.Sprite> capPreview = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private long capPreviewBytes;
    private int capPreviewW, capPreviewH;
    private BufferedImage hitPreview;
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

    /** Set while controls are being filled in from the edit, so their listeners don't count it as an edit. */
    private boolean loading;
    /** Set while an undo step (or a newly opened clip) is being shown, so nothing records an undo step for it. */
    private boolean restoring;
    /** A text field's typing goes in one undo step: set at its first change, cleared when it loses focus. */
    private boolean capTextSnap, titleTextSnap;
    private BufferedImage titlePreview;
    private String titlePreviewKey = "";
    private final Timer saveLater;
    /** Drives the playhead, part skipping in edit preview and the clock; runs only while a clip is open. */
    private final Timer ticker = new Timer(30, e -> tick());
    // what tick() last showed, so it only updates what changed
    private double lastT = -1, lastSegSpeed = Double.NaN, lastSegRamp = Double.NaN;
    private int lastSeg = -2;
    private boolean resumeAfterScrub;
    /** Inspector lists and what Delete does in each. */
    private final Map<javax.swing.JList<?>, Runnable> listRemovers = new HashMap<>();

    public EditorPanel(App app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Theme.PANEL);
        player = new VideoPlayer(new VideoPlayer.Listener() {
            @Override public void frame(BufferedImage img, double time) { view.setFrame(img); }
            @Override public void ended() { SwingUtilities.invokeLater(() -> updatePlayIcon()); }
        });
        // The library gets its own copy: later edits (and a capture finishing in the background) never share it.
        saveLater = new Timer(400, e -> { if (clip != null && edit != null) { clip.edit = edit.copy(); app.library.update(clip); } });
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

        add(inspector(), BorderLayout.EAST);

        timeline.onSeek = t -> { player.seek(t); timeline.setPlayhead(t); updateTime(t); };
        timeline.beforeEdit = this::snapshot;
        timeline.afterEdit = this::edited;
        timeline.extraMenu = this::timelineMenu;
        // Scrubbing or dragging a part's edge while playing: pause for the drag (one still frame per move instead
        // of restarting playback each time), then play on from where it was let go.
        timeline.onScrubStart = () -> {
            resumeAfterScrub = player.isPlaying();
            if (resumeAfterScrub) { player.pause(); updatePlayIcon(); }
        };
        timeline.onScrubEnd = () -> {
            if (resumeAfterScrub && clip != null && edit != null) {
                double t = player.time();
                int i = edit.segmentAt(t);
                player.play(t, previewEdits.isSelected() && i >= 0 ? edit.segments.get(i).speedAt(t) : 1);
                updatePlayIcon();
            }
            resumeAfterScrub = false;
        };
        timeline.onDeleteMarker = this::deleteMarker;
        timeline.onRenameMarker = this::renameMarker;
        timeline.onMoveMarker = this::moveMarker;

        // Inspector controls never keep the keyboard: Space, Home, Delete and the rest stay editor shortcuts.
        for (JComponent c : new JComponent[]{speed, rampTo, smoothing, fitMode, camCorner, zoomTarget, maskKind, acSource, acLayout, acPos, fxSound,
                fxSoundSel, effect, capStyle, capPos, ovPos, titlePos, titleSecs, preset, mute, ovAudio, prores, gpu, watermark, normalize, micCleanup, musicDuck})
            c.setFocusable(false);
        quietList(capList, () -> removeCaption());
        quietList(fxList, () -> { EditSpec.Effect f = selectedEffect(); if (f != null) { snapshot(); edit.effects.remove(f); edited(); refreshEffects(); } });
        quietList(maskList, () -> { EditSpec.Mask m = selectedMask(); if (m != null) { snapshot(); edit.masks.remove(m); edited(); refreshMasks(); } });
        quietList(zkList, () -> { EditSpec.ZoomKey k = selectedZoomKey(); if (k != null) { snapshot(); edit.zooms.remove(k); edited(); refreshZoomKeys(); } });
        quietList(ovList, () -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) { snapshot(); edit.overlays.remove(o); edited(); refreshOverlays(); } });
        quietList(presetList, this::deletePresetAsk);

        // Coming back to the editor (Back, another page, or the window from the tray closed the player): reopen it.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) resume();
        });
        bindKeys();
        app.onJobs(() -> SwingUtilities.invokeLater(this::updateJob));
    }

    /**
     * An inspector list that leaves the editor's keys alone: Space, Home, End and the arrows sideways still play
     * and move the playhead while it has focus (Up and Down still move through it), typing doesn't jump around
     * in it, and Delete removes its selected item.
     */
    private void quietList(javax.swing.JList<?> list, Runnable remove) {
        javax.swing.InputMap im = new javax.swing.InputMap();
        im.setParent(list.getInputMap(WHEN_FOCUSED));
        for (int k : new int[]{KeyEvent.VK_SPACE, KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE})
            for (int mod : new int[]{0, InputEvent.SHIFT_DOWN_MASK}) im.put(KeyStroke.getKeyStroke(k, mod), "none");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "none");
        list.setInputMap(WHEN_FOCUSED, im);
        // its type-to-find (a letter jumps to an item) would fight the one-key shortcuts
        for (java.awt.event.KeyListener kl : list.getKeyListeners()) list.removeKeyListener(kl);
        listRemovers.put(list, remove);
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
            saveLater.stop();
            clip.edit = edit.copy();
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
        JButton cover = Theme.button("Set cover", null, false);
        cover.setFont(Theme.interBold.deriveFont(11.5f));
        cover.setMargin(new java.awt.Insets(2, 8, 2, 8));
        cover.setToolTipText("Use this frame as the clip's picture in the library and on share links");
        cover.addActionListener(e -> setCover(player.time()));
        cover.setVisible(coverSupported());
        left.add(Box.createHorizontalStrut(10));
        left.add(cover);
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

    /** The side panel: one tab per kind of edit, so each stays short. */
    private JComponent inspector() {
        tabs.putClientProperty("JTabbedPane.tabWidthMode", "equal");
        tabs.putClientProperty("JTabbedPane.tabAreaAlignment", "fill");
        tabs.putClientProperty("JTabbedPane.showTabSeparators", true);
        tabs.setFont(Theme.interBold.deriveFont(11.5f));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.tabInsets", new java.awt.Insets(4, 4, 4, 4));
        tabs.putClientProperty("JTabbedPane.tabHeight", 36);
        tabs.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));
        tabs.setPreferredSize(new Dimension(372, 100));
        tabs.setFocusable(false);
        tabs.addTab("Edit", tabScroll(editTab()));
        tabs.addTab("Frame", tabScroll(frameTab()));
        tabs.addTab("Text", tabScroll(textTab()));
        tabs.addTab("Effects", tabScroll(effectsTab()));
        tabs.addTab("Audio", tabScroll(audioTab()));
        tabs.addTab("Export", tabScroll(exportTab()));
        String[] tips = {"Presets, details, speed and speed ramps", "Shape, facecam layout, zoom & pan, blur areas", "Auto-captions, captions and the title",
                "Shake, zoom punch, flash, hitmarker, sounds, color, video overlays", "Volume of the game, your mic and voice chat; music; fades",
                "Export for Discord, TikTok, YouTube...; After Effects / Premiere project"};
        for (int i = 0; i < tips.length; i++) tabs.setToolTipTextAt(i, tips[i]);
        return tabs;
    }

    private static JScrollPane tabScroll(JPanel content) {
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(20);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    private static JPanel tabPanel() {
        JPanel p = new FitWidth();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.PANEL);
        Theme.pad(p, 6, 18, 24, 18);
        return p;
    }

    private static JPanel buttonRow(JButton... bs) {
        JPanel row = new JPanel(new GridLayout(1, bs.length, 4, 0));
        row.setOpaque(false);
        for (JButton b : bs) { b.setFont(Theme.interBold.deriveFont(11.5f)); b.setMargin(new java.awt.Insets(4, 2, 4, 2)); row.add(b); }
        return row;
    }

    private static JButton small(String text, String tip) {
        JButton b = Theme.button(text, null, false);
        if (tip != null) b.setToolTipText(tip);
        return b;
    }

    // ---- Edit: presets, details, speed
    private JPanel editTab() {
        JPanel p = tabPanel();
        section(p, "Presets");
        add(p, hint("One click for a whole style. Ones with marker effects act at every marker (kills, aces, pops, or press M). "
                + "Right-click a marker to delete or rename it."));
        presetList.setVisibleRowCount(5);
        presetList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        presetList.setCellRenderer(new PresetRenderer());
        presetList.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) applySelectedPreset(); } });
        JScrollPane ps = new JScrollPane(presetList);
        ps.setPreferredSize(new Dimension(200, 168));
        add(p, ps);
        add(p, Box.createVerticalStrut(6));
        JButton apply = Theme.button("Apply", null, true);
        apply.addActionListener(e -> applySelectedPreset());
        JButton save = small("Save mine", "Save this clip's look and marker effects as your own preset");
        save.addActionListener(e -> savePreset());
        JButton community = small("Community", "Browse presets other players shared, and share yours");
        community.addActionListener(e -> communityDialog());
        add(p, buttonRow(apply, save, community));
        add(p, Box.createVerticalStrut(4));
        JButton imp = small("Import file", "Open a .evadepreset file"), exp = small("Export file", "Save the selected preset as a file to send to a friend"),
                del = small("Delete", "Delete one of your presets");
        imp.addActionListener(e -> importPreset());
        exp.addActionListener(e -> exportPreset());
        del.addActionListener(e -> deletePreset());
        add(p, buttonRow(imp, exp, del));

        section(p, "Details");
        field(p, "Game", gameField);
        tagsField.putClientProperty("JTextField.placeholderText", "clutch, 1v3, montage");
        field(p, "Tags", tagsField);
        for (JTextField f : new JTextField[]{gameField, tagsField}) {
            f.addActionListener(e -> commitMeta());
            f.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { commitMeta(); } });
        }

        section(p, "Speed");
        add(p, hint("Split around a kill (S), then slow that part down, or ramp: ease from one speed into another."));
        speed.setSelectedItem("1×");
        speed.addActionListener(e -> {
            if (loading || edit == null) return;
            int i = edit.segmentAt(player.time());
            double v = parseSpeed(speed.getSelectedItem());
            if (i < 0 || v <= 0) return;
            if (edit.segments.get(i).speed == v) return;
            snapshot();
            edit.segments.get(i).speed = v;
            edited();
        });
        field(p, "This part", speed);
        rampTo.addActionListener(e -> {
            if (loading || edit == null) return;
            int i = edit.segmentAt(player.time());
            if (i < 0) return;
            double v = Math.max(0, parseSpeed(rampTo.getSelectedItem()));
            EditSpec.Segment seg = edit.segments.get(i);
            if (Math.abs((seg.ramped() ? seg.rampTo : 0) - v) < 1e-3 || (v > 0 && Math.abs(v - seg.speed) < 1e-3 && !seg.ramped())) return;
            snapshot();
            seg.rampTo = v;
            edited();
        });
        field(p, "Ends at", rampTo);
        JButton rampHere = Theme.button("Slow-mo ramp at playhead", null, false);
        rampHere.setToolTipText("Eases into 0.3× slow motion at the playhead and back out (R)");
        rampHere.addActionListener(e -> rampAt(player.time(), 0.3));
        add(p, rampHere);
        add(p, Box.createVerticalStrut(8));
        smoothing.setToolTipText("How slow parts get extra frames. Optical flow invents in-between frames from the motion: smoothest, slower to export.");
        smoothing.addActionListener(e -> { if (!loading && edit != null) { snapshot(); edit.smoothing = SMOOTH[smoothing.getSelectedIndex()]; edited(); } });
        field(p, "Smooth", smoothing);

        JButton reset = Theme.button("Reset all edits", null, false);
        reset.addActionListener(e -> { if (edit != null) { snapshot(); edit = EditSpec.whole(clip.duration); loadControls(); edited(); } });
        add(p, Box.createVerticalStrut(18));
        add(p, reset);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ---- Frame: shape, layout, zoom & pan, blur areas
    private JPanel frameTab() {
        JPanel p = tabPanel();
        section(p, "Shape");
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
        fitMode.addActionListener(e -> {
            if (loading) return;
            snapshot();
            edit.fitMode = FITS[fitMode.getSelectedIndex()];
            if (edit.fitMode.equals("split") && (edit.aspect.equals("original") || edit.aspect.equals("16:9"))) edit.aspect = "9:16";
            loadControls();
            edited();
        });
        field(p, "Layout", fitMode);
        slider(p, "Position", cropX, v -> edit.cropX = v / 100.0);
        slider(p, "Zoom", zoom, v -> edit.zoom = v / 100.0);

        // facecam (split layout only)
        camBox.setLayout(new BoxLayout(camBox, BoxLayout.Y_AXIS));
        camBox.setOpaque(false);
        add(camBox, hint("Your facecam goes on top, the gameplay below: the TikTok / Shorts layout."));
        camCorner.addActionListener(e -> {
            if (loading || edit == null) return;
            int i = camCorner.getSelectedIndex();
            if (i == 4) { startPick(Pick.CAM, "Drag a box around your facecam on the video"); return; }
            snapshot();
            double[][] corners = {{0.72, 0.04}, {0.02, 0.04}, {0.72, 0.66}, {0.02, 0.66}};
            edit.camX = corners[i][0]; edit.camY = corners[i][1]; edit.camW = 0.26; edit.camH = 0.3;
            edited();
        });
        field(camBox, "Facecam", camCorner);
        slider(camBox, "Cam size", camShare, v -> edit.camShare = v / 100.0);
        JButton camFile = small("Webcam file...", "Use a separate webcam recording on top instead of a part of the gameplay");
        camFile.addActionListener(e -> chooseCamFile());
        JButton camClear = small("Use gameplay", "Go back to the facecam area of the gameplay");
        camClear.addActionListener(e -> { if (edit != null) { snapshot(); edit.camFile = ""; loadControls(); edited(); } });
        add(camBox, buttonRow(camFile, camClear));
        add(camBox, camFileLabel);
        camOffset.setToolTipText("Where the webcam recording is when the gameplay starts, in seconds. If the webcam started 2 s after the game, use -2.");
        camOffset.addChangeListener(e -> {
            if (loading || edit == null) return;
            double v = ((Number) camOffset.getValue()).doubleValue();
            if (Math.abs(v - edit.camOffset) < 1e-9) return;
            snapshot();
            edit.camOffset = v;
            edited();
        });
        field(camBox, "Webcam at", camOffset);
        add(p, camBox);

        section(p, "Zoom & pan");
        add(p, hint("Glide in on the kill feed, the crosshair or chat, and back out. Keyframes glide smoothly from one to the next."));
        field(p, "Target", zoomTarget);
        zoomTarget.addActionListener(e -> {
            if (loading || edit == null) return;
            if (zoomTarget.getSelectedIndex() == TARGETS.length) { startPick(Pick.ZOOM, "Click the spot to zoom in on"); return; }
            EditSpec.ZoomKey k = selectedZoomKey();
            if (k != null) { snapshot(); k.cx = TARGETS[zoomTarget.getSelectedIndex()][0]; k.cy = TARGETS[zoomTarget.getSelectedIndex()][1]; edited(); refreshZoomKeys(); }
        });
        slider(p, "Amount", zoomAmount, v -> { EditSpec.ZoomKey k = selectedZoomKey(); if (k != null && k.zoom > 1.001) { k.zoom = v / 100.0; refreshZoomKeys(); } });
        JButton zoomIn = Theme.button("Zoom in at playhead", null, false);
        zoomIn.setToolTipText("Glides in on the target, holds, and glides back out (Z)");
        zoomIn.addActionListener(e -> zoomInAt(player.time()));
        add(p, zoomIn);
        add(p, Box.createVerticalStrut(6));
        zkList.setVisibleRowCount(3);
        zkList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        zkList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadZoomKey(); });
        JScrollPane zs = new JScrollPane(zkList);
        zs.setPreferredSize(new Dimension(200, 64));
        add(p, zs);
        add(p, Box.createVerticalStrut(4));
        JButton zkAdd = small("Add key", "Add a keyframe at the playhead with this target and amount"), zkGo = small("Go to", null), zkDel = small("Remove", null);
        zkAdd.addActionListener(e -> addZoomKey(player.time()));
        zkGo.addActionListener(e -> { EditSpec.ZoomKey k = selectedZoomKey(); if (k != null) seekTo(k.t); });
        zkDel.addActionListener(e -> { EditSpec.ZoomKey k = selectedZoomKey(); if (k != null) { snapshot(); edit.zooms.remove(k); edited(); refreshZoomKeys(); } });
        add(p, buttonRow(zkAdd, zkGo, zkDel));

        section(p, "Blur areas");
        add(p, hint("Hide names, chat or coordinates. Add one, then drag a box over what to hide on the video."));
        maskList.setVisibleRowCount(3);
        maskList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        maskList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadMask(); });
        JScrollPane ms = new JScrollPane(maskList);
        ms.setPreferredSize(new Dimension(200, 64));
        add(p, ms);
        add(p, Box.createVerticalStrut(4));
        JButton mAdd = small("Add", "Hide an area from the playhead on (drag it on the video)"), mDraw = small("Redraw", "Drag a new box on the video"),
                mFrom = small("Start", "Starts at the playhead"), mTo = small("End", "Ends at the playhead"), mDel = small("Remove", null);
        mAdd.addActionListener(e -> addMask());
        mDraw.addActionListener(e -> { if (selectedMask() != null) startPick(Pick.MASK, "Drag a box over what to hide"); });
        mFrom.addActionListener(e -> { EditSpec.Mask m = selectedMask(); if (m != null) { snapshot(); m.start = Math.min(player.time(), m.end - 0.1); edited(); refreshMasks(); } });
        mTo.addActionListener(e -> { EditSpec.Mask m = selectedMask(); if (m != null) { snapshot(); m.end = Math.max(player.time(), m.start + 0.1); edited(); refreshMasks(); } });
        mDel.addActionListener(e -> { EditSpec.Mask m = selectedMask(); if (m != null) { snapshot(); edit.masks.remove(m); edited(); refreshMasks(); } });
        add(p, buttonRow(mAdd, mDraw, mFrom, mTo, mDel));
        add(p, Box.createVerticalStrut(6));
        maskKind.addActionListener(e -> { EditSpec.Mask m = selectedMask(); if (!loading && m != null) { snapshot(); m.kind = MASKS[maskKind.getSelectedIndex()]; edited(); refreshMasks(); } });
        field(p, "Style", maskKind);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ---- Text: auto-captions, captions, title
    private JPanel textTab() {
        JPanel p = tabPanel();
        section(p, "Auto-captions");
        add(p, hint("Turns what's said into captions, on your PC. Check the words afterwards: you can fix any in the list below."));
        field(p, "Whose", acSource);
        field(p, "Style", acLayout);
        field(p, "Position", acPos);
        acBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        acBtn.addActionListener(e -> autoCaption());
        add(p, acBtn);
        add(p, acStatus);

        section(p, "Captions");
        add(p, hint("Text between two moments. Pop is big and bold, one word or phrase at a time; Subtitle sits on a dark box."));
        capList.setVisibleRowCount(5);
        capList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        capList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadCaption(); });
        JScrollPane capScroll = new JScrollPane(capList);
        capScroll.setPreferredSize(new Dimension(200, 104));
        add(p, capScroll);
        add(p, Box.createVerticalStrut(6));
        JButton capAdd = small("Add", "Add a caption at the playhead (C)"), capDel = small("Remove", null),
                capFrom = small("Start", "Caption starts at the playhead"), capTo = small("End", "Caption ends at the playhead"), capClear = small("Clear all", null);
        capAdd.addActionListener(e -> addCaption());
        capDel.addActionListener(e -> removeCaption());
        capFrom.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (c != null) { snapshot(); c.start = Math.min(player.time(), c.end - 0.2); edited(); refreshCaptions(); } });
        capTo.addActionListener(e -> { EditSpec.Caption c = selectedCaption(); if (c != null) { snapshot(); c.end = Math.max(player.time(), c.start + 0.2); edited(); refreshCaptions(); } });
        capClear.addActionListener(e -> { if (edit != null && !edit.captions.isEmpty()) { snapshot(); edit.captions.clear(); edited(); refreshCaptions(); } });
        add(p, buttonRow(capAdd, capDel, capFrom, capTo));
        add(p, Box.createVerticalStrut(6));
        capText.putClientProperty("JTextField.placeholderText", "Caption text");
        capText.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                EditSpec.Caption c = selectedCaption();
                if (loading || c == null) return;
                // one undo step for the whole of the typing
                if (!capTextSnap) { snapshot(); capTextSnap = true; }
                c.text = capText.getText();
                edited();
                updateCaptionRow();
            }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        capText.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { capTextSnap = false; } });
        add(p, capText);
        add(p, Box.createVerticalStrut(6));
        capStyle.addActionListener(e -> {
            EditSpec.Caption c = selectedCaption();
            if (loading || c == null || c.style.equals(CAP_STYLES[capStyle.getSelectedIndex()])) return;
            snapshot();
            c.style = CAP_STYLES[capStyle.getSelectedIndex()];
            edited();
        });
        field(p, "Style", capStyle);
        capPos.addActionListener(e -> {
            EditSpec.Caption c = selectedCaption();
            String pos = ((String) capPos.getSelectedItem()).toLowerCase(java.util.Locale.ROOT);
            if (loading || c == null || c.position.equals(pos)) return;
            snapshot();
            c.position = pos;
            edited();
        });
        field(p, "Position", capPos);
        add(p, capClear);

        section(p, "Title");
        titleText.putClientProperty("JTextField.placeholderText", "e.g. 1v4 clutch");
        titleText.getDocument().addDocumentListener(new DocumentListener() {
            void ch() {
                if (loading || edit == null) return;
                if (!titleTextSnap) { snapshot(); titleTextSnap = true; }
                edit.titleText = titleText.getText();
                edited();
            }
            public void insertUpdate(DocumentEvent e) { ch(); }
            public void removeUpdate(DocumentEvent e) { ch(); }
            public void changedUpdate(DocumentEvent e) { ch(); }
        });
        titleText.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { titleTextSnap = false; } });
        add(p, titleText);
        add(p, Box.createVerticalStrut(6));
        titlePos.addActionListener(e -> {
            String pos = ((String) titlePos.getSelectedItem()).toLowerCase(java.util.Locale.ROOT);
            if (loading || edit == null || pos.equals(edit.titlePosition)) return;
            snapshot();
            edit.titlePosition = pos;
            edited();
        });
        field(p, "Position", titlePos);
        titleSecs.addActionListener(e -> {
            if (loading || edit == null) return;
            double v = new double[]{2, 3, 5, 0}[titleSecs.getSelectedIndex()];
            if (v == edit.titleSeconds) return;
            snapshot();
            edit.titleSeconds = v;
            edited();
        });
        field(p, "Show", titleSecs);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ---- Effects: effects & sounds, look, overlays
    private JPanel effectsTab() {
        JPanel p = tabPanel();
        section(p, "Add at the playhead");
        add(p, hint("Pick a sound (optional), then an effect. Right-click the timeline to add one anywhere."));
        refreshSoundBoxes();
        JButton play = small("▶", "Hear this sound");
        play.addActionListener(e -> previewSound((String) fxSound.getSelectedItem()));
        JPanel sr = new JPanel(new BorderLayout(6, 0));
        sr.setOpaque(false);
        sr.add(fxSound, BorderLayout.CENTER);
        sr.add(play, BorderLayout.EAST);
        field(p, "Sound", sr);
        JButton shake = small("Shake", "Screen shake (1)"), punch = small("Zoom punch", "Quick zoom in and out (2)"), flash = small("Flash", "White flash (3)"),
                hit = small("Hitmarker", "Hitmarker in the center (4)"), sound = small("Sound only", "Just the sound (5)");
        shake.addActionListener(e -> addEffect("shake", player.time()));
        punch.addActionListener(e -> addEffect("punch", player.time()));
        flash.addActionListener(e -> addEffect("flash", player.time()));
        hit.addActionListener(e -> addEffect("hitmarker", player.time()));
        sound.addActionListener(e -> addEffect("sound", player.time()));
        add(p, buttonRow(shake, punch, flash));
        add(p, Box.createVerticalStrut(4));
        add(p, buttonRow(hit, sound));
        add(p, Box.createVerticalStrut(4));
        JButton folder = small("Add your own sounds...", "Opens your sounds folder: drop WAV / MP3 / OGG files in and they show up here");
        folder.addActionListener(e -> {
            try { java.awt.Desktop.getDesktop().open(Sounds.ensureUserDir().toFile()); } catch (Exception ex) { app.notify("Couldn't open the sounds folder", ex.getMessage(), true); }
            new Timer(4000, ev -> { ((Timer) ev.getSource()).stop(); refreshSoundBoxes(); }).start();
        });
        add(p, folder);

        section(p, "Effects in this clip");
        fxList.setVisibleRowCount(4);
        fxList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        fxList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadEffect(); });
        JScrollPane fs = new JScrollPane(fxList);
        fs.setPreferredSize(new Dimension(200, 84));
        add(p, fs);
        add(p, Box.createVerticalStrut(4));
        JButton fGo = small("Go to", null), fHere = small("Move here", "Move it to the playhead"), fDel = small("Remove", null);
        fGo.addActionListener(e -> { EditSpec.Effect f = selectedEffect(); if (f != null) seekTo(f.t); });
        fHere.addActionListener(e -> { EditSpec.Effect f = selectedEffect(); if (f != null) { snapshot(); f.t = player.time(); edited(); refreshEffects(); } });
        fDel.addActionListener(e -> { EditSpec.Effect f = selectedEffect(); if (f != null) { snapshot(); edit.effects.remove(f); edited(); refreshEffects(); } });
        add(p, buttonRow(fGo, fHere, fDel));
        add(p, Box.createVerticalStrut(6));
        slider(p, "Strength", fxStrength, v -> { EditSpec.Effect f = selectedEffect(); if (f != null) f.strength = v / 100.0; });
        slider(p, "Sound vol", fxVolume, v -> { EditSpec.Effect f = selectedEffect(); if (f != null) f.volume = v / 100.0; });
        fxSoundSel.addActionListener(e -> {
            EditSpec.Effect f = selectedEffect();
            if (loading || f == null) return;
            snapshot();
            f.sound = fxSoundSel.getSelectedIndex() <= 0 ? "" : (String) fxSoundSel.getSelectedItem();
            edited();
            refreshEffects();
        });
        field(p, "Sound", fxSoundSel);

        section(p, "Color");
        effect.addActionListener(e -> {
            if (loading || edit == null) return;
            snapshot();
            edit.effect = EFFECTS[effect.getSelectedIndex()];
            edited();
        });
        field(p, "Look", effect);

        section(p, "Video overlays");
        add(p, hint("Put a second video on top: your facecam, a reaction, another angle. It starts at the playhead."));
        ovList.setVisibleRowCount(3);
        ovList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        ovList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadOverlay(); });
        JScrollPane ovScroll = new JScrollPane(ovList);
        ovScroll.setPreferredSize(new Dimension(200, 64));
        add(p, ovScroll);
        add(p, Box.createVerticalStrut(6));
        JButton ovFromLib = small("From library", null), ovFromFile = small("From file", null), ovDel = small("Remove", null);
        ovFromLib.addActionListener(e -> addOverlayFromLibrary());
        ovFromFile.addActionListener(e -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Videos", Library.VIDEO_EXT.toArray(String[]::new)));
            if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) addOverlay(fc.getSelectedFile().toPath());
        });
        ovDel.addActionListener(e -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) { snapshot(); edit.overlays.remove(o); edited(); refreshOverlays(); } });
        add(p, buttonRow(ovFromLib, ovFromFile, ovDel));
        add(p, Box.createVerticalStrut(6));
        ovPos.addActionListener(e -> {
            EditSpec.Overlay o = selectedOverlay();
            if (loading || o == null) return;
            snapshot();
            placeOverlay(o, ovPos.getSelectedIndex());
            edited();
        });
        field(p, "Position", ovPos);
        // resizing keeps it in its corner (or around its centre when it was placed somewhere else)
        slider(p, "Size", ovSize, v -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) { int at = overlayCorner(o); o.scale = v / 100.0; placeOverlay(o, at); } });
        slider(p, "Opacity", ovOpacity, v -> { EditSpec.Overlay o = selectedOverlay(); if (o != null) o.opacity = v / 100.0; });
        ovAudio.setOpaque(false);
        ovAudio.addActionListener(e -> { EditSpec.Overlay o = selectedOverlay(); if (!loading && o != null) { snapshot(); o.audio = ovAudio.isSelected(); edited(); } });
        add(p, ovAudio);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ---- Audio
    private JPanel audioTab() {
        JPanel p = tabPanel();
        section(p, "Levels");
        add(p, hint("Game, your mic and voice chat were recorded separately, so each has its own level."));
        slider(p, "Volume", volume, v -> edit.volume = v / 100.0);
        slider(p, "Your mic", micVol, v -> edit.micVolume = v / 100.0);
        micRow = (JComponent) p.getComponent(p.getComponentCount() - 1);
        micCleanup.setOpaque(false);
        micCleanup.setToolTipText("Cuts low rumble (desk bumps, fans) and evens out steady hiss on your mic track. Only in the export.");
        micCleanup.addActionListener(e -> { if (!loading && edit != null) { snapshot(); edit.micCleanup = micCleanup.isSelected(); edited(); } });
        add(p, micCleanup);
        micCleanupRow = micCleanup;
        slider(p, "Voice chat", voiceVol, v -> edit.voiceVolume = v / 100.0);
        voiceRow = (JComponent) p.getComponent(p.getComponentCount() - 1);
        mute.setOpaque(false);
        mute.addActionListener(e -> { if (!loading) { snapshot(); edit.mute = mute.isSelected(); edited(); } });
        add(p, mute);

        section(p, "Music");
        add(p, hint("A song under the whole clip, faded out at the end. It's only in the export: the preview plays the clip's own sound."));
        JButton addMusic = small("Add music...", "Pick a song (MP3, WAV, OGG, M4A, FLAC or AAC)");
        addMusic.addActionListener(e -> chooseMusic());
        musicRemove.addActionListener(e -> { if (edit != null && edit.music != null) { snapshot(); edit.music = null; loadControls(); edited(); } });
        add(p, buttonRow(addMusic, musicRemove));
        add(p, Box.createVerticalStrut(4));
        add(p, musicLabel);
        add(p, Box.createVerticalStrut(4));
        slider(p, "Music vol", musicVol, v -> { if (edit.music != null) edit.music.volume = v / 100.0; });
        musicVolRow = (JComponent) p.getComponent(p.getComponentCount() - 1);
        musicStart.setToolTipText("Where in the song to start, in seconds");
        musicStart.addChangeListener(e -> {
            if (loading || edit == null || edit.music == null) return;
            double v = ((Number) musicStart.getValue()).doubleValue();
            if (Math.abs(v - edit.music.inPoint) < 1e-9) return;
            snapshot();
            edit.music.inPoint = v;
            edited();
        });
        field(p, "Song from", musicStart);
        musicStartRow = (JComponent) p.getComponent(p.getComponentCount() - 1);
        musicDuck.setOpaque(false);
        musicDuck.setToolTipText("Turns the music down while the game or your mic is loud");
        musicDuck.addActionListener(e -> { if (!loading && edit != null && edit.music != null) { snapshot(); edit.music.duck = musicDuck.isSelected(); edited(); } });
        add(p, musicDuck);

        section(p, "Fades");
        slider(p, "Fade in", fadeIn, v -> edit.fadeIn = v / 10.0);
        slider(p, "Fade out", fadeOut, v -> edit.fadeOut = v / 10.0);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private void chooseMusic() {
        if (edit == null) return;
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Music (MP3, WAV, OGG, M4A, FLAC, AAC)", "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus"));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        snapshot();
        EditSpec.Music m = edit.music != null ? edit.music.copy() : new EditSpec.Music();
        m.file = fc.getSelectedFile().getAbsolutePath();
        m.inPoint = 0;
        edit.music = m;
        loadControls();
        edited();
        app.notify("Music added", fc.getSelectedFile().getName() + " · it plays in the export; the preview keeps the clip's own sound", false);
    }

    // ---- Export
    private JPanel exportTab() {
        JPanel p = tabPanel();
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
        normalize.setOpaque(false);
        normalize.setToolTipText("Evens out the sound to the loudness YouTube, TikTok and Discord play at (-14 LUFS), so quiet clips aren't lost and loud ones don't clip");
        add(p, normalize);
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

        section(p, "After Effects & Premiere");
        add(p, hint("For 3D camera shakes, Twixtor or your own presets: a project folder with your cuts, speed ramps, zooms, captions and markers, "
                + "plus each audio track. Opens in After Effects, Premiere Pro and DaVinci Resolve."));
        prores.setOpaque(false);
        add(p, prores);
        JButton proj = Theme.button("Export project folder", null, false);
        proj.addActionListener(e -> exportProject());
        add(p, proj);
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
        ticker.stop();
        flushSave();
        commitMeta();
        clip = c;
        undo.clear();
        redo.clear();
        undoTopKey = null;
        if (c == null) return;
        if (c.markerLabels == null) c.markerLabels = new HashMap<>();
        edit = c.editOrDefault().copy();
        // A clip that was edited and then trimmed externally could carry stale segments; clamp them.
        for (EditSpec.Segment s : edit.segments) { s.end = Math.min(s.end, c.duration); s.start = Math.min(s.start, s.end); }
        edit.segments.removeIf(s -> s.length() < 0.05);
        if (edit.segments.isEmpty()) edit = EditSpec.whole(c.duration);
        restoring = true;
        try { loadControls(); } finally { restoring = false; }
        Path file = c.path(app.library.root());
        view.setFrame(null);
        view.setSource(c.width, c.height);
        BufferedImage strip = null;
        try { if (Files.exists(app.library.strip(c))) strip = ImageIO.read(app.library.strip(c).toFile()); } catch (Exception ignored) {}
        timeline.setClip(c.duration, edit, c.markers, strip, Library.STRIP_FRAMES);
        timeline.labelOf = c::label;
        timeline.setMusic(musicName());
        timeline.setPlayhead(0);
        player.open(file, c.duration, c.fps, c.hasAudio, c.width, c.height);
        player.setAudioTracks(c.audioTracks);
        applyPreviewAudio();
        updateTime(0);
        updatePlayIcon();
        loadWaveform(c, file);
        loadDenseStrip(c, file);
        exportDone.setVisible(false);
        exportStatus.setText(" ");
        currentJob = null;
        updateJob();
        resetTickCache();
        ticker.start();
        SwingUtilities.invokeLater(view::requestFocusInWindow);
    }

    public void close() {
        flushSave();
        commitMeta();
        player.close();
        ticker.stop();
        updatePlayIcon();
    }

    /** Saves edits that are waiting for the save timer now (before another clip opens or the editor closes). */
    private void flushSave() {
        if (!saveLater.isRunning()) return;
        saveLater.stop();
        if (clip != null && edit != null) { clip.edit = edit.copy(); app.library.update(clip); }
    }

    /**
     * Opens the player again when the editor shows after it was closed (Back or Esc, another page, or the window
     * closed to the tray) while the clip is still loaded, where the timeline's playhead is.
     */
    public void resume() {
        if (clip == null || player.isOpen()) return;
        Clip c = clip;
        // the closed player still knows where it was
        double t = Math.max(0, Math.min(c.duration, player.time()));
        player.open(c.path(app.library.root()), c.duration, c.fps, c.hasAudio, c.width, c.height);
        player.setAudioTracks(c.audioTracks);
        if (edit != null) player.setVideoFilter(Exporter.effectFilter(edit.effect));
        applyPreviewAudio();
        player.seek(t);
        resetTickCache();
        ticker.start();
        updatePlayIcon();
    }

    public Clip clip() { return clip; }

    /** Shows a side-panel tab by name ("Edit", "Frame", "Text", "Effects", "Audio", "Export"). */
    public void showTab(String name) {
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).equalsIgnoreCase(name)) tabs.setSelectedIndex(i);
    }

    /** Moves the playhead (for screenshots and the API). */
    public void seek(double t) { seekTo(t); }

    /** Re-reads the clip's edit into the controls (after it was changed outside the editor). */
    public void reload() {
        if (clip == null) return;
        edit = clip.editOrDefault().copy();
        restoring = true;
        try { loadControls(); } finally { restoring = false; }
        edited();
    }

    /** The waveform, wide enough that zooming in on a long clip still shows the shape (200 px a second, 2400..16000). */
    private void loadWaveform(Clip c, Path file) {
        if (!c.hasAudio) return;
        int w = (int) Math.max(2400, Math.min(16000, c.duration * 200));
        Path wav = app.library.thumbsDir().resolve(c.id + "-wave-" + w + ".png");
        Thread t = new Thread(() -> {
            try {
                if (!Files.exists(wav)) {
                    Files.createDirectories(wav.getParent());
                    Ffmpeg.run(List.of("-i", file.toString(), "-filter_complex",
                            "aformat=channel_layouts=mono,compand=gain=4,showwavespic=s=" + w + "x120:colors=0xE11D2E", "-frames:v", "1", wav.toString()));
                }
                BufferedImage img = ImageIO.read(wav.toFile());
                SwingUtilities.invokeLater(() -> { if (clip == c) timeline.setWave(img); });
            } catch (Exception ignored) {}
        }, "waveform");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A denser filmstrip for the timeline (a frame every 2 s, 10..300 frames), made in the background the first
     * time a clip opens, so zooming in shows real detail instead of ten stretched frames.
     */
    private void loadDenseStrip(Clip c, Path file) {
        if (c.duration <= 0) return;
        int frames = (int) Math.max(10, Math.min(300, Math.ceil(c.duration / 2)));
        if (frames <= Library.STRIP_FRAMES) return;
        Path out = app.library.thumbsDir().resolve(c.id + "-estrip-" + frames + ".jpg");
        Thread t = new Thread(() -> {
            try {
                if (!Files.exists(out)) {
                    Files.createDirectories(out.getParent());
                    Path tmp = out.resolveSibling(c.id + "-estrip-" + frames + ".tmp.jpg");
                    List<String> args = new ArrayList<>();
                    // long recordings: key frames only, which is plenty for a filmstrip and far quicker
                    if (c.duration > 60) args.addAll(List.of("-skip_frame", "nokey"));
                    args.addAll(List.of("-i", file.toString(), "-an", "-vf", "fps=" + Library.fmt(frames / c.duration)
                            + ",scale=96:54:force_original_aspect_ratio=decrease,pad=96:54:(ow-iw)/2:(oh-ih)/2:color=black,tile=" + frames + "x1",
                            "-frames:v", "1", "-q:v", "6", tmp.toString()));
                    Ffmpeg.run(args);
                    Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                BufferedImage img = ImageIO.read(out.toFile());
                if (img != null) SwingUtilities.invokeLater(() -> { if (clip == c) timeline.setStrip(img, frames); });
            } catch (Exception ignored) {}
        }, "dense-strip");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void loadControls() {
        boolean was = loading;
        loading = true;
        try { fillControls(); } finally { loading = was; }
        capTextSnap = false;
        titleTextSnap = false;
        updateControlsEnabled();
        updatePresetInfo();
        updateUndo();
    }

    private void fillControls() {
        titleField.setText(clip.title);
        gameField.setText(clip.game == null ? "" : clip.game);
        tagsField.setText(String.join(", ", clip.tags));
        favBtn.setSelected(clip.favorite);
        JToggleButton ab = aspectBtns.get(edit.aspect);
        if (ab != null) ab.setSelected(true);
        fitMode.setSelectedIndex(Math.max(0, java.util.Arrays.asList(FITS).indexOf(edit.fitMode)));
        camShare.setValue((int) Math.round(edit.camShare * 100));
        double[][] corners = {{0.72, 0.04}, {0.02, 0.04}, {0.72, 0.66}, {0.02, 0.66}};
        int corner = 4;
        for (int i = 0; i < corners.length; i++) if (Math.abs(corners[i][0] - edit.camX) < 0.01 && Math.abs(corners[i][1] - edit.camY) < 0.01) corner = i;
        camCorner.setSelectedIndex(corner);
        camFileLabel.setText(edit.camFile.isBlank() ? "Using the facecam in the gameplay" : "Webcam: " + Path.of(edit.camFile).getFileName());
        camBox.setVisible(edit.fitMode.equals("split"));
        smoothing.setSelectedIndex(Math.max(0, java.util.Arrays.asList(SMOOTH).indexOf(edit.smoothing)));
        refreshZoomKeys();
        refreshMasks();
        refreshEffects();
        refreshPresets();
        cropX.setValue((int) Math.round(edit.cropX * 100));
        zoom.setValue((int) Math.round(edit.zoom * 100));
        volume.setValue((int) Math.round(edit.volume * 100));
        micVol.setValue((int) Math.round(edit.micVolume * 100));
        boolean micTrack = java.util.stream.IntStream.range(0, clip.audioTracks).anyMatch(i -> Exporter.trackName(clip, i).equalsIgnoreCase("Mic"));
        micRow.setVisible(micTrack);
        micCleanup.setSelected(edit.micCleanup);
        micCleanupRow.setVisible(micTrack);
        voiceVol.setValue((int) Math.round(edit.voiceVolume * 100));
        voiceRow.setVisible(java.util.stream.IntStream.range(0, clip.audioTracks).anyMatch(i -> Exporter.trackName(clip, i).equalsIgnoreCase("Voice chat")));
        boolean hasMusic = edit.music != null;
        musicLabel.setText(hasMusic ? "Song: " + musicName() : "No music");
        musicVol.setValue((int) Math.round((hasMusic ? edit.music.volume : 0.5) * 100));
        musicStart.setValue(hasMusic ? edit.music.inPoint : 0.0);
        musicDuck.setSelected(!hasMusic || edit.music.duck);
        for (JComponent c : new JComponent[]{musicVolRow, musicStartRow, musicDuck}) c.setVisible(hasMusic);
        musicRemove.setEnabled(hasMusic);
        camOffset.setValue(edit.camOffset);
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
    }

    /** The music's file name, or null when there's none. */
    private String musicName() {
        if (edit == null || edit.music == null || edit.music.file == null || edit.music.file.isBlank()) return null;
        try { return Path.of(edit.music.file).getFileName().toString(); } catch (RuntimeException e) { return edit.music.file; }
    }

    /** The edit's volume, mute, track levels and fades on the preview's sound, so it sounds like the export. */
    private void applyPreviewAudio() {
        if (clip == null || edit == null) return;
        player.setEditGain((float) edit.volume, edit.mute);
        double[] g = new double[Math.max(0, clip.audioTracks)];
        for (int i = 0; i < g.length; i++) g[i] = Exporter.trackGain(clip, edit, i);
        player.setTrackGains(g);
        if (edit.fadeIn <= 0 && edit.fadeOut <= 0) { player.setGainAt(null); return; }
        // the audio thread reads its own copy, never the edit being changed
        EditSpec e = edit.copy();
        double fi = e.fadeIn, fo = e.fadeOut, total = e.outputDuration();
        player.setGainAt(t -> {
            double o = e.toOutput(t), v = 1;
            if (fi > 0) v = Math.min(v, o / fi);
            if (fo > 0) v = Math.min(v, (total - o) / fo);
            return Math.max(0, Math.min(1, v));
        });
    }

    private void updateControlsEnabled() {
        boolean framed = !edit.aspect.equals("original");
        fitMode.setEnabled(true);
        camBox.setVisible(edit.fitMode.equals("split"));
        camOffset.setEnabled(!edit.camFile.isBlank());
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

    /** The edit and the clip's markers as they are now, as an undo step. */
    private UndoState state() {
        Map<String, String> labels = clip.markerLabels == null ? new HashMap<>() : new HashMap<>(clip.markerLabels);
        return new UndoState(edit.copy(), new ArrayList<>(clip.markers), labels);
    }

    private static String key(UndoState s) { return UNDO_GSON.toJson(s.edit()) + "|" + s.markers() + "|" + new java.util.TreeMap<>(s.labels()); }

    /**
     * Records an undo step before a change. Nothing is recorded while controls are being filled in or an undo
     * step is being shown, nor when nothing changed since the last step.
     */
    private void snapshot() {
        if (edit == null || clip == null || restoring || loading) return;
        pushUndo(state());
    }

    private void pushUndo(UndoState s) {
        String k = key(s);
        if (k.equals(undoTopKey) && !undo.isEmpty()) return;
        undo.push(s);
        undoTopKey = k;
        if (undo.size() > 100) undo.removeLast();
        redo.clear();
        updateUndo();
    }

    private void edited() {
        edit.sort();
        player.setVideoFilter(Exporter.effectFilter(edit.effect));
        timeline.setEdit(edit);
        timeline.setMusic(musicName());
        applyPreviewAudio();
        updateControlsEnabled();
        updatePresetInfo();
        updateUndo();
        resetTickCache();
        view.repaint();
        saveLater.restart();
    }

    private void undo() {
        if (undo.isEmpty() || edit == null) return;
        redo.push(state());
        restore(undo.pop());
        undoTopKey = undo.isEmpty() ? null : key(undo.peek());
    }

    private void redo() {
        if (redo.isEmpty() || edit == null) return;
        UndoState now = state();
        undo.push(now);
        undoTopKey = key(now);
        restore(redo.pop());
    }

    /** Shows an undo step: its edit, and the clip's markers as they were. */
    private void restore(UndoState s) {
        edit = s.edit();
        boolean markersChanged = !s.markers().equals(clip.markers) || !s.labels().equals(clip.markerLabels);
        if (markersChanged) {
            clip.markers.clear();
            clip.markers.addAll(s.markers());
            if (clip.markerLabels == null) clip.markerLabels = new HashMap<>();
            clip.markerLabels.clear();
            clip.markerLabels.putAll(s.labels());
            timeline.setMarkers(clip.markers);
            app.library.update(clip);
        }
        restoring = true;
        try { loadControls(); } finally { restoring = false; }
        edited();
    }

    private void updateUndo() {
        undoBtn.setEnabled(!undo.isEmpty());
        redoBtn.setEnabled(!redo.isEmpty());
    }

    private void split() {
        if (edit == null) return;
        UndoState before = state();
        if (edit.splitAt(player.time())) { pushUndo(before); edited(); }
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
        placeOverlay(o, 0);
        edit.overlays.add(o);
        edited();
        refreshOverlays();
        ovList.setSelectedIndex(edit.overlays.size() - 1);
        loadOverlayFrame(o.file);
    }

    /**
     * Puts an overlay in a corner of the frame (0 top right, 1 top left, 2 bottom right, 3 bottom left) or the
     * centre (4); -1 keeps it where it is.
     */
    private static void placeOverlay(EditSpec.Overlay o, int corner) {
        double m = 0.03, half = o.scale / 2;
        double hh = o.scale / 2; // same aspect as the frame: height fraction = width fraction
        switch (corner) {
            case 0 -> { o.x = 1 - m - half; o.y = m + hh; }
            case 1 -> { o.x = m + half; o.y = m + hh; }
            case 2 -> { o.x = 1 - m - half; o.y = 1 - m - hh; }
            case 3 -> { o.x = m + half; o.y = 1 - m - hh; }
            case 4 -> { o.x = 0.5; o.y = 0.5; }
            default -> {}
        }
    }

    /** Which of {@link #placeOverlay}'s spots an overlay is in, or -1 when it was put somewhere else. */
    private static int overlayCorner(EditSpec.Overlay o) {
        for (int i = 0; i <= 4; i++) {
            EditSpec.Overlay t = o.copy();
            placeOverlay(t, i);
            if (Math.abs(t.x - o.x) < 0.005 && Math.abs(t.y - o.y) < 0.005) return i;
        }
        return -1;
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
        boolean was = loading;
        loading = true;
        try {
            ovModel.clear();
            for (EditSpec.Overlay o : edit.overlays) ovModel.addElement(Theme.timeFine(o.start) + "  " + Path.of(o.file).getFileName());
        } finally { loading = was; }
        if (sel >= 0 && sel < ovModel.size()) ovList.setSelectedIndex(sel);
        loadOverlay();
    }

    private void loadOverlay() {
        EditSpec.Overlay o = selectedOverlay();
        boolean on = o != null;
        boolean was = loading;
        loading = true;
        try {
            ovPos.setEnabled(on); ovSize.setEnabled(on); ovOpacity.setEnabled(on); ovAudio.setEnabled(on);
            if (on) {
                ovSize.setValue((int) Math.round(o.scale * 100));
                ovOpacity.setValue((int) Math.round(o.opacity * 100));
                ovAudio.setSelected(o.audio);
                int at = overlayCorner(o);
                if (at >= 0) ovPos.setSelectedIndex(at);
            }
        } finally { loading = was; }
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
        boolean was = loading;
        loading = true;
        try {
            capModel.clear();
            for (EditSpec.Caption c : edit.captions) capModel.addElement(captionRow(c));
        } finally { loading = was; }
        if (sel >= 0 && sel < capModel.size()) capList.setSelectedIndex(sel);
        loadCaption();
    }

    private void removeCaption() {
        EditSpec.Caption c = selectedCaption();
        if (c == null) return;
        snapshot();
        edit.captions.remove(c);
        edited();
        refreshCaptions();
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
        boolean was = loading;
        loading = true;
        try {
            capText.setEnabled(on); capStyle.setEnabled(on); capPos.setEnabled(on);
            if (!capText.getText().equals(on ? c.text : "")) capText.setText(on ? c.text : "");
            if (on) {
                capStyle.setSelectedIndex(Math.max(0, java.util.Arrays.asList(CAP_STYLES).indexOf(c.style)));
                capPos.setSelectedIndex(switch (c.position) { case "center" -> 1; case "top" -> 2; default -> 0; });
            }
        } finally { loading = was; }
        // typing into another caption is a new undo step
        capTextSnap = false;
    }

    /**
     * Finds the action (game-event markers like kills and aces first, then the loudest moments of the game and
     * your mic), marks it, and cuts the clip down to just those moments.
     */
    private void highlightReel() {
        if (clip == null) return;
        boolean events = clip.markers.stream().anyMatch(m -> Highlights.isEvent(clip.label(m)));
        if (!clip.hasAudio && !events) { app.notify("No audio to scan", "Highlights are found from game events (kills, aces) and the sound of the action", false); return; }
        Clip c = clip;
        app.notify("Finding highlights…", null, false);
        Thread th = new Thread(() -> {
            try {
                var moments = Highlights.detect(c, c.path(app.library.root()), 0, null);
                SwingUtilities.invokeLater(() -> {
                    if (clip != c || edit == null) return;
                    if (moments.isEmpty()) { app.notify("No highlights found", "Nothing stood out from the background", false); return; }
                    // one undo step brings back the markers and the whole clip
                    snapshot();
                    for (var m : moments) if (c.markers.stream().noneMatch(x -> Math.abs(x - m.time()) < 2)) c.markers.add(m.time());
                    c.markers.sort(Double::compare);
                    timeline.setMarkers(c.markers);
                    app.library.update(c);
                    // a kill needs its build-up; a loud moment its aftermath too
                    EditSpec cut = Highlights.cut(moments, c.duration, 5, 3, 6, 2);
                    cut.aspect = edit.aspect; cut.fitMode = edit.fitMode; cut.cropX = edit.cropX; cut.zoom = edit.zoom;
                    cut.volume = edit.volume; cut.micVolume = edit.micVolume; cut.micCleanup = edit.micCleanup; cut.titleText = edit.titleText;
                    cut.titlePosition = edit.titlePosition; cut.titleSeconds = edit.titleSeconds;
                    cut.effect = edit.effect; cut.voiceVolume = edit.voiceVolume; cut.smoothing = edit.smoothing;
                    cut.camX = edit.camX; cut.camY = edit.camY; cut.camW = edit.camW; cut.camH = edit.camH; cut.camShare = edit.camShare;
                    cut.camFile = edit.camFile; cut.camOffset = edit.camOffset; cut.mute = edit.mute;
                    cut.music = edit.music == null ? null : edit.music.copy();
                    for (EditSpec.Caption cc : edit.captions) cut.captions.add(cc.copy());
                    edit = cut;
                    loadControls();
                    edited();
                    seekTo(edit.segments.get(0).start);
                    long kills = moments.stream().filter(Highlights.Moment::event).count();
                    app.notify(moments.size() + " highlight" + (moments.size() == 1 ? "" : "s") + " kept" + (kills > 0 ? " (" + kills + " from game events)" : ""),
                            Theme.timeFine(c.duration) + " → " + Theme.timeFine(edit.outputDuration()) + " · Ctrl+Z to undo", false);
                });
            } catch (Exception ex) { app.notify("Highlight scan failed", ex.getMessage(), true); }
        }, "highlights");
        th.setDaemon(true);
        th.start();
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
        double t = player.time();
        if (clip.markers.stream().anyMatch(x -> Math.abs(x - t) < 0.01)) return;
        snapshot();
        clip.markers.add(t);
        clip.markers.sort(Double::compare);
        markersChanged();
    }

    private void deleteMarker(int i) {
        if (clip == null || i < 0 || i >= clip.markers.size()) return;
        snapshot();
        double t = clip.markers.remove(i);
        clip.markerLabels.remove(Clip.key(t));
        markersChanged();
    }

    private void renameMarker(int i, String name) {
        if (clip == null || i < 0 || i >= clip.markers.size()) return;
        String k = Clip.key(clip.markers.get(i));
        String now = clip.markerLabels.get(k);
        if (name.isBlank() ? now == null : name.equals(now)) return;
        snapshot();
        if (name.isBlank()) clip.markerLabels.remove(k); else clip.markerLabels.put(k, name);
        markersChanged();
    }

    private void moveMarker(int i) {
        if (clip == null || i < 0 || i >= clip.markers.size()) return;
        double to = player.time(), from = clip.markers.get(i);
        if (Math.abs(to - from) < 0.01) return;
        snapshot();
        clip.markers.remove(i);
        String label = clip.markerLabels.remove(Clip.key(from));
        if (clip.markers.stream().noneMatch(x -> Math.abs(x - to) < 0.01)) clip.markers.add(to);
        if (label != null) clip.markerLabels.put(Clip.key(to), label);
        clip.markers.sort(Double::compare);
        markersChanged();
    }

    private void markersChanged() {
        timeline.setMarkers(clip.markers);
        app.library.update(clip);
    }

    // ------------------------------------------------------------------ presets

    private void refreshPresets() {
        EditPresets.Preset sel = presetList.getSelectedValue();
        presetModel.clear();
        for (EditPresets.Preset p : EditPresets.builtIns()) presetModel.addElement(p);
        for (EditPresets.Preset p : EditPresets.mine()) presetModel.addElement(p);
        if (sel != null) for (int i = 0; i < presetModel.size(); i++) if (presetModel.get(i).name.equals(sel.name)) presetList.setSelectedIndex(i);
    }

    /** Preset rows: name, who made it, and what it does. */
    private static class PresetRenderer extends javax.swing.DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean sel, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
            if (value instanceof EditPresets.Preset p) {
                String who = p.builtIn ? "" : p.communityId != null ? " · community" + (p.author.isBlank() ? "" : " · " + esc(p.author)) : " · yours";
                String sub = p.description != null && !p.description.isBlank() ? p.description : EditPresets.summary(p);
                l.setText("<html><b>" + esc(p.name) + "</b><span style='color:#8A8A96'>" + who + "</span><br><span style='color:#8A8A96;font-size:9px'>"
                        + esc(sub) + "</span></html>");
                l.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            }
            return l;
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private void applySelectedPreset() {
        EditPresets.Preset p = presetList.getSelectedValue();
        if (p == null) { app.notify("Pick a preset first", null, false); return; }
        applyPreset(p);
    }

    private void applyPreset(EditPresets.Preset p) {
        if (edit == null) return;
        snapshot();
        int used = EditPresets.apply(p, edit, clip);
        loadControls();
        edited();
        if (p.atMarker != null && used == 0)
            app.notify("\"" + p.name + "\" applied", "It also acts at markers, and this clip has none in the kept parts: press M at the big moments or use Highlights (H)", false);
        else app.notify("\"" + p.name + "\" applied", (used > 0 ? "At " + used + " marker" + (used == 1 ? "" : "s") + " · " : "") + "Ctrl+Z to undo", false);
    }

    private void savePreset() {
        if (edit == null) return;
        String name = javax.swing.JOptionPane.showInputDialog(this, "Name your preset", "Save preset", javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        try {
            EditPresets.Preset p = EditPresets.fromEdit(name.trim(), edit, clip);
            p.description = EditPresets.summary(p);
            EditPresets.save(p);
            refreshPresets();
            app.notify("Preset saved", p.name + " · find it in Edit → Presets", false);
        } catch (Exception ex) { app.notify("Couldn't save the preset", ex.getMessage(), true); }
    }

    private void importPreset() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Evade Clips presets", EditPresets.EXT, "json"));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        try {
            EditPresets.Preset p = EditPresets.read(fc.getSelectedFile().toPath());
            EditPresets.save(p);
            refreshPresets();
            for (int i = 0; i < presetModel.size(); i++) if (presetModel.get(i).name.equals(p.name)) presetList.setSelectedIndex(i);
            app.notify("Preset added", p.name + " · Apply to use it", false);
        } catch (Exception ex) { app.notify("Couldn't import that preset", ex.getMessage(), true); }
    }

    private void exportPreset() {
        EditPresets.Preset p = presetList.getSelectedValue();
        if (p == null) { app.notify("Pick a preset first", null, false); return; }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setSelectedFile(new java.io.File(Library.safe(p.name) + "." + EditPresets.EXT));
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        try {
            Path f = fc.getSelectedFile().toPath();
            if (!f.toString().endsWith("." + EditPresets.EXT)) f = Path.of(f + "." + EditPresets.EXT);
            EditPresets.write(p, f);
            app.notify("Preset saved as a file", f.getFileName() + " · send it to a friend", false);
        } catch (Exception ex) { app.notify("Couldn't save the file", ex.getMessage(), true); }
    }

    private void deletePreset() {
        EditPresets.Preset p = presetList.getSelectedValue();
        if (p == null || p.builtIn) { app.notify(p == null ? "Pick one of your presets" : "Built-in presets can't be deleted", null, false); return; }
        try { EditPresets.delete(p); refreshPresets(); } catch (Exception ex) { app.notify("Couldn't delete it", ex.getMessage(), true); }
    }

    /** Delete in the preset list: one of your presets goes after you say so (it's a file, not part of the edit). */
    private void deletePresetAsk() {
        EditPresets.Preset p = presetList.getSelectedValue();
        if (p == null || p.builtIn) return;
        int r = javax.swing.JOptionPane.showConfirmDialog(this, "Delete your preset \"" + p.name + "\"?", "Delete preset", javax.swing.JOptionPane.OK_CANCEL_OPTION);
        if (r == javax.swing.JOptionPane.OK_OPTION) deletePreset();
    }

    /** Community presets: browse, apply, keep, and share yours. */
    private void communityDialog() {
        javax.swing.JDialog d = new javax.swing.JDialog(SwingUtilities.getWindowAncestor(this), "Community presets", java.awt.Dialog.ModalityType.MODELESS);
        javax.swing.DefaultListModel<EditPresets.Preset> model = new javax.swing.DefaultListModel<>();
        javax.swing.JList<EditPresets.Preset> list = new javax.swing.JList<>(model);
        list.setCellRenderer(new PresetRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof EditPresets.Preset p) c.setText(c.getText().replace("</html>", "<br><span style='color:#5C5C68;font-size:9px'>" + p.downloads + " downloads</span></html>"));
                return c;
            }
        });
        JComboBox<String> sort = new JComboBox<>(new String[]{"Newest", "Most downloaded"});
        JLabel status = Theme.label("Loading...", 12f, false, Theme.DIM);
        Runnable load = () -> {
            status.setText("Loading...");
            String by = sort.getSelectedIndex() == 1 ? "popular" : "new";
            new Thread(() -> {
                try {
                    var got = EditPresets.community(app.cloud, by);
                    SwingUtilities.invokeLater(() -> {
                        model.clear();
                        for (var p : got) model.addElement(p);
                        status.setText(got.isEmpty() ? "No community presets yet. Share the first one." : got.size() + " presets · also at clips.evadeclient.com/presets");
                    });
                } catch (Exception ex) { SwingUtilities.invokeLater(() -> status.setText("Couldn't reach the share server: " + ex.getMessage())); }
            }, "community-presets").start();
        };
        sort.addActionListener(e -> load.run());
        JButton apply = Theme.button("Apply to this clip", null, true), keep = Theme.button("Save to my presets", null, false), share = Theme.button("Share one of mine...", null, false);
        java.util.function.Consumer<java.util.function.Consumer<EditPresets.Preset>> withFull = then -> {
            EditPresets.Preset sel = list.getSelectedValue();
            if (sel == null) { status.setText("Pick a preset first."); return; }
            new Thread(() -> {
                try {
                    EditPresets.Preset full = EditPresets.fetch(app.cloud, sel.communityId);
                    SwingUtilities.invokeLater(() -> then.accept(full));
                } catch (Exception ex) { SwingUtilities.invokeLater(() -> status.setText("Download failed: " + ex.getMessage())); }
            }, "preset-download").start();
        };
        apply.addActionListener(e -> withFull.accept(this::applyPreset));
        keep.addActionListener(e -> withFull.accept(full -> {
            try { EditPresets.save(full); refreshPresets(); status.setText("Saved to your presets: " + full.name); } catch (Exception ex) { status.setText(ex.getMessage()); }
        }));
        share.addActionListener(e -> {
            List<EditPresets.Preset> mine = EditPresets.mine();
            if (mine.isEmpty()) { status.setText("Save one of your own first (Edit → Presets → Save mine)."); return; }
            EditPresets.Preset pick = (EditPresets.Preset) javax.swing.JOptionPane.showInputDialog(d, "Which preset?", "Share a preset", javax.swing.JOptionPane.PLAIN_MESSAGE, null, mine.toArray(), mine.get(0));
            if (pick == null) return;
            String author = javax.swing.JOptionPane.showInputDialog(d, "Your name or channel (shown with it, optional)", app.settings.watermarkText == null ? "" : app.settings.watermarkText);
            if (author == null) return;
            String desc = javax.swing.JOptionPane.showInputDialog(d, "Describe it in a few words", pick.description);
            if (desc == null) return;
            pick.author = author.trim();
            pick.description = desc.trim();
            new Thread(() -> {
                try {
                    EditPresets.publish(app.cloud, pick);
                    SwingUtilities.invokeLater(() -> { status.setText("Shared! Everyone can find \"" + pick.name + "\" now."); sort.setSelectedIndex(0); load.run(); });
                } catch (Exception ex) { SwingUtilities.invokeLater(() -> status.setText("Couldn't share it: " + ex.getMessage())); }
            }, "preset-share").start();
        });
        JPanel root = new JPanel(new BorderLayout(0, 10));
        Theme.pad(root, 16, 16, 16, 16);
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(Theme.heading("Community presets"), BorderLayout.WEST);
        top.add(sort, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(460, 340));
        root.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(status, BorderLayout.NORTH);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        btns.add(share);
        btns.add(keep);
        btns.add(apply);
        bottom.add(btns, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        load.run();
    }

    // ------------------------------------------------------------------ speed ramps, zoom, masks, effects

    /** Right-click on the timeline: effects, ramps, zoom, freeze frames, rewinds and the cover at the clicked time. */
    private void timelineMenu(javax.swing.JPopupMenu m, Double t) {
        m.add(menuItem("Slow-mo ramp here", () -> rampAt(t, 0.3)));
        m.add(menuItem("Zoom in here", () -> zoomInAt(t)));
        javax.swing.JMenu fx = new javax.swing.JMenu("Add effect here");
        for (String[] f : new String[][]{{"shake", "Shake"}, {"punch", "Zoom punch"}, {"flash", "Flash"}, {"hitmarker", "Hitmarker"}, {"sound", "Sound only"}})
            fx.add(menuItem(f[1], () -> addEffect(f[0], t)));
        m.add(fx);
        int i = edit == null ? -1 : edit.segmentAt(t);
        if (i >= 0) {
            EditSpec.Segment s = edit.segments.get(i);
            m.addSeparator();
            m.add(menuItem("Freeze frame here (F)", () -> freezeAt(t)));
            if (s.hold() > 0) {
                javax.swing.JMenu len = new javax.swing.JMenu("Freeze frame length (" + Timeline.fmtSecs(s.hold()) + ")");
                for (double v : new double[]{0.5, 1, 2, 3}) len.add(menuItem(Timeline.fmtSecs(v), () -> setHold(i, v)));
                m.add(len);
                m.add(menuItem("Remove freeze frame", () -> setHold(i, 0)));
            }
            javax.swing.JMenuItem rw = menuItem(s.rewind ? "Stop rewinding this part" : "Rewind this part", () -> toggleRewind(i));
            if (!s.rewind && s.length() > EditSpec.MAX_REWIND) {
                rw.setEnabled(false);
                rw.setToolTipText("Parts up to " + (int) EditSpec.MAX_REWIND + " s can be rewound: split around the moment first (S)");
            }
            m.add(rw);
        }
        m.addSeparator();
        javax.swing.JMenuItem cover = menuItem("Use this frame as cover", () -> setCover(t));
        cover.setEnabled(coverSupported());
        m.add(cover);
    }

    /** Freezes the picture at t: the part is split there and holds its last frame for a second. */
    private void freezeAt(double t) {
        if (edit == null) return;
        int i = edit.segmentAt(t);
        if (i < 0) { app.notify("That's in a cut part", "Freeze frames go on kept parts", false); return; }
        EditSpec.Segment s = edit.segments.get(i);
        UndoState before = state();
        int at;
        if (s.end - t < 0.1) at = i;                       // at the end of the part: hold its last frame
        else if (t - s.start < 0.1) {
            // at its very start: hold the end of the part just before, if it runs straight into this one
            if (i > 0 && Math.abs(edit.segments.get(i - 1).end - s.start) < 0.05) at = i - 1;
            else { app.notify("Move the playhead a little into the part", "A freeze frame holds the picture where the part is split", false); return; }
        } else {
            edit.splitAt(t);
            at = i;
        }
        EditSpec.Segment seg = edit.segments.get(at);
        if (seg.hold() <= 0) seg.holdAfter = 1;
        pushUndo(before);
        edited();
        app.notify("Freeze frame added", "Holds " + Timeline.fmtSecs(seg.hold()) + " in the export (right-click it to change the length). The preview doesn't pause for it.", false);
    }

    private void setHold(int i, double v) {
        if (edit == null || i < 0 || i >= edit.segments.size()) return;
        snapshot();
        edit.segments.get(i).holdAfter = v;
        edited();
    }

    private void toggleRewind(int i) {
        if (edit == null || i < 0 || i >= edit.segments.size()) return;
        EditSpec.Segment s = edit.segments.get(i);
        if (!s.rewind && s.length() > EditSpec.MAX_REWIND) return;
        snapshot();
        s.rewind = !s.rewind;
        edited();
        if (s.rewind) app.notify("Rewind added", "This part plays, rewinds and plays again in the export. The preview plays it once.", false);
    }

    // The cover frame is kept by the library (Library.setCover, and CloudShare.uploadThumb for a shared clip's
    // link preview). They're looked up by name, so the editor works with a library that doesn't have them yet:
    // the cover controls simply don't show there.
    private static final java.lang.reflect.Method SET_COVER = lookup(Library.class, "setCover", Clip.class, double.class);
    private static final java.lang.reflect.Method UPLOAD_THUMB = lookup(net.evadeclient.clips.core.CloudShare.class, "uploadThumb", Clip.class, Library.class);

    private static java.lang.reflect.Method lookup(Class<?> c, String name, Class<?>... params) {
        try { return c.getMethod(name, params); } catch (NoSuchMethodException | RuntimeException e) { return null; }
    }

    private static boolean coverSupported() { return SET_COVER != null; }

    /** Makes the frame at t the clip's cover (library thumbnail and share-link picture). */
    private void setCover(double t) {
        if (clip == null || edit == null || SET_COVER == null) return;
        if (edit.segmentAt(t) < 0) { app.notify("That frame is cut from the clip", "Pick a frame in a part you're keeping", false); return; }
        Clip c = clip;
        try {
            SET_COVER.invoke(app.library, c, t);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Throwable why = ex.getCause() != null ? ex.getCause() : ex;
            app.notify("Couldn't set the cover", why.getMessage(), true);
            return;
        }
        boolean shared = c.shareId != null && UPLOAD_THUMB != null;
        app.notify("Cover set", "The frame at " + Theme.timeFine(t) + (shared ? " · updating your share link's picture" : ""), false);
        if (!shared) return;
        Thread th = new Thread(() -> {
            try { UPLOAD_THUMB.invoke(app.cloud, c, app.library); }
            catch (Exception ex) {
                Throwable why = ex.getCause() != null ? ex.getCause() : ex;
                app.notify("Couldn't update the share link's picture", why.getMessage(), true);
            }
        }, "cover-upload");
        th.setDaemon(true);
        th.start();
    }

    private static javax.swing.JMenuItem menuItem(String label, Runnable r) {
        javax.swing.JMenuItem it = new javax.swing.JMenuItem(label);
        it.addActionListener(e -> r.run());
        return it;
    }

    private void rampAt(double t, double slow) {
        if (edit == null) return;
        UndoState before = state();
        if (edit.rampAround(t, slow, 1.2, 1.0)) {
            pushUndo(before);
            edited();
            app.notify("Slow-mo ramp added", "Eases into " + Timeline.fmtSpeed(slow) + " and back out · smoothing: " + smoothing.getSelectedItem(), false);
        } else app.notify("Can't ramp here", "Put the playhead inside a kept part", false);
    }

    private void startPick(Pick what, String message) {
        pick = what;
        view.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        if (player.isPlaying()) { player.pause(); updatePlayIcon(); }
        app.notify(message, "Esc to cancel", false);
    }

    private void endPick() {
        pick = Pick.NONE;
        view.setCursor(java.awt.Cursor.getDefaultCursor());
        view.repaint();
    }

    private void chooseCamFile() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Videos", Library.VIDEO_EXT.toArray(String[]::new)));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION || edit == null) return;
        snapshot();
        edit.camFile = fc.getSelectedFile().getAbsolutePath();
        edited();
        loadControls();
    }

    private EditSpec.ZoomKey selectedZoomKey() {
        int i = zkList.getSelectedIndex();
        List<EditSpec.ZoomKey> keys = edit == null ? List.of() : edit.sortedZooms();
        return i < 0 || i >= keys.size() ? null : keys.get(i);
    }

    private void refreshZoomKeys() {
        int sel = zkList.getSelectedIndex();
        boolean was = loading;
        loading = true;
        try {
            zkModel.clear();
            for (EditSpec.ZoomKey k : edit.sortedZooms())
                zkModel.addElement(Theme.timeFine(k.t) + "   " + (k.zoom <= 1.001 ? "full frame" : Timeline.fmtSpeed(k.zoom).replace("×", "x zoom") + " · " + targetName(k)));
        } finally { loading = was; }
        if (sel >= 0 && sel < zkModel.size()) zkList.setSelectedIndex(sel);
    }

    private static String targetName(EditSpec.ZoomKey k) {
        String[] names = {"center", "kill feed", "chat", "minimap", "health"};
        for (int i = 0; i < TARGETS.length; i++) if (Math.abs(TARGETS[i][0] - k.cx) < 0.02 && Math.abs(TARGETS[i][1] - k.cy) < 0.02) return names[i];
        return "custom spot";
    }

    private void loadZoomKey() {
        EditSpec.ZoomKey k = selectedZoomKey();
        if (k == null) return;
        boolean was = loading;
        loading = true;
        try {
            if (k.zoom > 1.001) zoomAmount.setValue((int) Math.round(k.zoom * 100));
            for (int i = 0; i < TARGETS.length; i++) if (Math.abs(TARGETS[i][0] - k.cx) < 0.02 && Math.abs(TARGETS[i][1] - k.cy) < 0.02) zoomTarget.setSelectedIndex(i);
        } finally { loading = was; }
    }

    private double[] target() {
        int i = zoomTarget.getSelectedIndex();
        return i >= 0 && i < TARGETS.length ? TARGETS[i] : new double[]{pickedX, pickedY};
    }

    private double pickedX = 0.5, pickedY = 0.5;

    private void addZoomKey(double t) {
        if (edit == null) return;
        snapshot();
        double[] c = target();
        edit.zooms.add(new EditSpec.ZoomKey(t, zoomAmount.getValue() / 100.0, c[0], c[1]));
        edited();
        refreshZoomKeys();
    }

    /** Glide in on the target, hold, glide back out. */
    private void zoomInAt(double t) {
        if (edit == null || clip == null) return;
        snapshot();
        double[] c = target();
        double z = zoomAmount.getValue() / 100.0;
        edit.zooms.add(new EditSpec.ZoomKey(Math.max(0, t - 0.5), 1, 0.5, 0.5));
        edit.zooms.add(new EditSpec.ZoomKey(t, z, c[0], c[1]));
        edit.zooms.add(new EditSpec.ZoomKey(Math.min(clip.duration, t + 1.0), z, c[0], c[1]));
        edit.zooms.add(new EditSpec.ZoomKey(Math.min(clip.duration, t + 1.6), 1, 0.5, 0.5));
        edited();
        refreshZoomKeys();
        app.notify("Zoom added", Timeline.fmtSpeed(z).replace("×", "x") + " on the " + targetName(edit.zooms.get(edit.zooms.size() - 3)) + " · change the target or amount in Frame", false);
    }

    private EditSpec.Mask selectedMask() {
        int i = maskList.getSelectedIndex();
        return edit == null || i < 0 || i >= edit.masks.size() ? null : edit.masks.get(i);
    }

    private void addMask() {
        if (edit == null || clip == null) return;
        snapshot();
        EditSpec.Mask m = new EditSpec.Mask();
        m.start = player.time();
        m.end = clip.duration;
        m.kind = MASKS[maskKind.getSelectedIndex()];
        edit.masks.add(m);
        edited();
        refreshMasks();
        maskList.setSelectedIndex(edit.masks.size() - 1);
        startPick(Pick.MASK, "Drag a box over what to hide");
    }

    private void refreshMasks() {
        int sel = maskList.getSelectedIndex();
        boolean was = loading;
        loading = true;
        try {
            maskModel.clear();
            for (EditSpec.Mask m : edit.masks)
                maskModel.addElement(Theme.timeFine(m.start) + "–" + Theme.timeFine(m.end) + "   " + maskKind.getItemAt(Math.max(0, java.util.Arrays.asList(MASKS).indexOf(m.kind))));
        } finally { loading = was; }
        if (sel >= 0 && sel < maskModel.size()) maskList.setSelectedIndex(sel);
    }

    private void loadMask() {
        EditSpec.Mask m = selectedMask();
        if (m == null) return;
        boolean was = loading;
        loading = true;
        try { maskKind.setSelectedIndex(Math.max(0, java.util.Arrays.asList(MASKS).indexOf(m.kind))); } finally { loading = was; }
    }

    private void refreshSoundBoxes() {
        Object a = fxSound.getSelectedItem(), b = fxSoundSel.getSelectedItem();
        boolean was = loading;
        loading = true;
        try {
            fxSound.removeAllItems();
            fxSoundSel.removeAllItems();
            fxSound.addItem("No sound");
            fxSoundSel.addItem("No sound");
            for (String n : Sounds.list()) { fxSound.addItem(n); fxSoundSel.addItem(n); }
            if (a != null) fxSound.setSelectedItem(a);
            if (b != null) fxSoundSel.setSelectedItem(b);
        } finally { loading = was; }
    }

    private void previewSound(String name) {
        if (name == null || fxSound.getSelectedIndex() <= 0) return;
        new Thread(() -> {
            try {
                Path f = Sounds.file(name);
                if (f == null) return;
                Path wav = f;
                if (!f.toString().toLowerCase().endsWith(".wav")) {
                    wav = Files.createTempFile("evadeclips-sound", ".wav");
                    Ffmpeg.run(List.of("-y", "-i", f.toString(), "-ar", "48000", "-ac", "2", "-sample_fmt", "s16", wav.toString()));
                }
                try (var in = javax.sound.sampled.AudioSystem.getAudioInputStream(wav.toFile())) {
                    javax.sound.sampled.Clip c = javax.sound.sampled.AudioSystem.getClip();
                    c.open(in);
                    c.start();
                    Thread.sleep(Math.min(4000, c.getMicrosecondLength() / 1000 + 100));
                    c.close();
                }
            } catch (Exception ex) { app.notify("Couldn't play that sound", ex.getMessage(), true); }
        }, "sound-preview").start();
    }

    private void addEffect(String type, double t) {
        if (edit == null) return;
        if (edit.segmentAt(t) < 0) { app.notify("That's in a cut part", "Effects go on kept parts", false); return; }
        String sound = fxSound.getSelectedIndex() <= 0 ? "" : (String) fxSound.getSelectedItem();
        if (type.equals("sound") && sound.isEmpty()) { app.notify("Pick a sound first", "Effects → Sound", false); return; }
        snapshot();
        EditSpec.Effect f = new EditSpec.Effect(type, t, sound);
        edit.effects.add(f);
        edited();
        refreshEffects();
        fxList.setSelectedIndex(edit.effects.indexOf(f));
    }

    private EditSpec.Effect selectedEffect() {
        int i = fxList.getSelectedIndex();
        return edit == null || i < 0 || i >= edit.effects.size() ? null : edit.effects.get(i);
    }

    private void refreshEffects() {
        int sel = fxList.getSelectedIndex();
        boolean was = loading;
        loading = true;
        try {
            fxModel.clear();
            for (EditSpec.Effect f : edit.effects) {
                String name = switch (f.type) { case "shake" -> "Shake"; case "punch" -> "Zoom punch"; case "flash" -> "Flash"; case "hitmarker" -> "Hitmarker"; default -> "Sound"; };
                fxModel.addElement(Theme.timeFine(f.t) + "   " + name + (f.sound.isBlank() ? "" : "  ·  sound: " + f.sound));
            }
        } finally { loading = was; }
        if (sel >= 0 && sel < fxModel.size()) fxList.setSelectedIndex(sel);
        loadEffect();
    }

    private void loadEffect() {
        EditSpec.Effect f = selectedEffect();
        boolean on = f != null;
        boolean was = loading;
        loading = true;
        try {
            fxStrength.setEnabled(on && !f.type.equals("sound") && !f.type.equals("hitmarker"));
            fxVolume.setEnabled(on && !f.sound.isBlank());
            fxSoundSel.setEnabled(on);
            if (on) {
                fxStrength.setValue((int) Math.round(f.strength * 100));
                fxVolume.setValue((int) Math.round(f.volume * 100));
                fxSoundSel.setSelectedItem(f.sound.isBlank() ? "No sound" : f.sound);
            }
        } finally { loading = was; }
    }

    // ------------------------------------------------------------------ auto-captions

    private void autoCaption() {
        if (clip == null || edit == null) return;
        if (!clip.hasAudio) { app.notify("This clip has no sound to caption", null, false); return; }
        if (!AutoCaptions.ready()) {
            int ok = javax.swing.JOptionPane.showConfirmDialog(this, "Auto-captions use the offline speech engine (about 70 MB, downloaded once,\n"
                    + "the same one as voice commands). Nothing you say leaves your PC. Download it now?", "Auto-captions", javax.swing.JOptionPane.OK_CANCEL_OPTION);
            if (ok != javax.swing.JOptionPane.OK_OPTION) return;
            acBtn.setEnabled(false);
            new Thread(() -> {
                try {
                    net.evadeclient.clips.core.VoiceCommands.downloadAll(pr -> SwingUtilities.invokeLater(() -> acStatus.setText("Downloading the speech engine · " + Math.round(pr * 100) + "%")));
                    SwingUtilities.invokeLater(() -> { acBtn.setEnabled(true); autoCaption(); });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> { acBtn.setEnabled(true); acStatus.setText("Download failed: " + ex.getMessage()); });
                }
            }, "speech-download").start();
            return;
        }
        // Nothing changes until the words are in: the captions are only replaced (or added to) once listening worked.
        boolean replace = false;
        if (!edit.captions.isEmpty()) {
            int r = javax.swing.JOptionPane.showConfirmDialog(this, "Replace the captions this clip already has?", "Auto-captions", javax.swing.JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == javax.swing.JOptionPane.CANCEL_OPTION || r == javax.swing.JOptionPane.CLOSED_OPTION) return;
            replace = r == javax.swing.JOptionPane.YES_OPTION;
        }
        boolean replaceAll = replace;
        Clip c = clip;
        AutoCaptions.Source src = (AutoCaptions.Source) acSource.getSelectedItem();
        AutoCaptions.Layout layout = (AutoCaptions.Layout) acLayout.getSelectedItem();
        String pos = ((String) acPos.getSelectedItem()).toLowerCase();
        double from = edit.segments.get(0).start, to = edit.segments.get(edit.segments.size() - 1).end;
        acBtn.setEnabled(false);
        acStatus.setText("Listening...");
        new Thread(() -> {
            try {
                var words = AutoCaptions.transcribe(c.path(app.library.root()), c, src, from, to,
                        pr -> SwingUtilities.invokeLater(() -> acStatus.setText("Listening · " + Math.round(pr * 100) + "%")));
                var caps = AutoCaptions.build(words, layout, pos);
                SwingUtilities.invokeLater(() -> {
                    acBtn.setEnabled(true);
                    if (caps.isEmpty()) {
                        if (clip == c) acStatus.setText("No speech found. Try another source above.");
                        return;
                    }
                    if (clip != c || edit == null) {
                        // Another clip was opened while listening: the captions still go on the clip they're for.
                        EditSpec e2 = c.editOrDefault().copy();
                        if (replaceAll) e2.captions.clear();
                        e2.captions.addAll(caps);
                        e2.captions.sort(java.util.Comparator.comparingDouble(x -> x.start));
                        c.edit = e2;
                        app.library.update(c);
                        app.notify("Captions added to " + (c.title == null ? "a clip" : c.title), caps.size() + " captions · open it to check the words", false);
                        return;
                    }
                    snapshot();
                    if (replaceAll) edit.captions.clear();
                    edit.captions.addAll(caps);
                    edit.captions.sort(java.util.Comparator.comparingDouble(x -> x.start));
                    edited();
                    refreshCaptions();
                    acStatus.setText(words.size() + " words → " + caps.size() + " captions. Click one to fix its text.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> { acBtn.setEnabled(true); acStatus.setText("Couldn't caption: " + ex.getMessage()); });
            }
        }, "auto-captions").start();
    }

    // ------------------------------------------------------------------ project export

    private void exportProject() {
        if (clip == null || edit == null) return;
        commitMeta();
        saveLater.stop();
        clip.edit = edit.copy();
        app.library.update(clip);
        Clip c = clip;
        EditSpec e = edit.copy();
        boolean pr = prores.isSelected();
        app.notify("Making the project folder...", pr ? "Making the ProRes copy takes a little while" : null, false);
        new Thread(() -> {
            try {
                Path dir = ProjectExport.export(c.path(app.library.root()), c, e, app.library.folder(Clip.Kind.EXPORT), pr, null, null);
                app.notify("Project folder ready", "Open the .jsx in After Effects or the .xml in Premiere / Resolve", false);
                LibraryPanel.reveal(dir);
            } catch (Exception ex) { app.notify("Project export failed", ex.getMessage(), true); }
        }, "project-export").start();
    }

    // ------------------------------------------------------------------ playback

    private void togglePlay() {
        if (clip == null || edit == null) return;
        if (!player.isOpen()) resume();
        if (player.isPlaying()) player.pause();
        else {
            double t = player.time();
            if (previewEdits.isSelected()) {
                // at (or just before) the end of the last kept part, or in the cut after it: from the top
                double lastEnd = edit.segments.get(edit.segments.size() - 1).end;
                int i = edit.segmentAt(t);
                if (i < 0) t = nextSegmentStart(t);
                if (t >= lastEnd - 0.05 || edit.segmentAt(t) < 0) t = edit.segments.get(0).start;
            } else if (t >= clip.duration - 0.05) t = 0;
            int i = edit.segmentAt(t);
            player.play(t, previewEdits.isSelected() && i >= 0 ? edit.segments.get(i).speedAt(t) : 1);
            timeline.follow();
        }
        updatePlayIcon();
    }

    private double nextSegmentStart(double t) {
        for (EditSpec.Segment s : edit.segments) if (s.start >= t - 1e-6) return s.start;
        return clip.duration;
    }

    private void seekTo(double t) {
        player.seek(t);
        timeline.follow();
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
                else { int j = edit.segmentAt(n); player.play(n, edit.segments.get(j).speedAt(n)); }
            } else {
                EditSpec.Segment s = edit.segments.get(i);
                double want = s.speedAt(t);
                // ramps: follow the curve in steps (each change restarts playback, so not every frame)
                if (Math.abs(player.speed() - want) > (s.ramped() ? 0.12 * want : 1e-6)) player.play(t, want);
                else if (t >= s.end - 0.035) {
                    if (i + 1 < edit.segments.size()) player.play(edit.segments.get(i + 1).start, edit.segments.get(i + 1).speedAt(edit.segments.get(i + 1).start));
                    else { player.pause(); updatePlayIcon(); }
                }
            }
        }
        // Only what changed: an idle editor repaints nothing.
        if (t != lastT) {
            lastT = t;
            timeline.setPlayhead(t);
            updateTime(t);
        }
        if (!player.isPlaying() && playBtnShowsPause) updatePlayIcon();
        // Speed box follows the part under the playhead.
        if (edit != null) {
            int i = edit.segmentAt(t);
            EditSpec.Segment s = i >= 0 ? edit.segments.get(i) : null;
            double sp = s == null ? 0 : s.speed, rt = s != null && s.ramped() ? s.rampTo : 0;
            if (i != lastSeg || sp != lastSegSpeed || rt != lastSegRamp) {
                lastSeg = i;
                lastSegSpeed = sp;
                lastSegRamp = rt;
                boolean was = loading;
                loading = true;
                try {
                    speed.setEnabled(i >= 0);
                    rampTo.setEnabled(i >= 0);
                    if (i >= 0) {
                        selectSpeed(speed, sp, "", "");
                        if (rt <= 0) rampTo.setSelectedIndex(0);
                        else selectSpeed(rampTo, rt, "Ramp to ", "");
                    }
                } finally { loading = was; }
            }
        }
    }

    private void resetTickCache() {
        lastT = -1;
        lastSeg = -2;
        lastSegSpeed = Double.NaN;
        lastSegRamp = Double.NaN;
    }

    /** A speed from a combo entry ("0.5×", "Ramp to 2×"); 0 for "Steady speed", -1 if it isn't one. */
    private static double parseSpeed(Object item) {
        if (item == null) return -1;
        String s = item.toString();
        if (s.startsWith("Steady")) return 0;
        s = s.replace("Ramp to", "").replace("×", "").trim();
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Selects a speed in a combo, adding it in order when the list doesn't have it (an R ramp's 0.3×, or a speed
     * from a preset), so the box always shows the part's real speed.
     */
    private static void selectSpeed(JComboBox<String> box, double v, String prefix, String suffix) {
        javax.swing.DefaultComboBoxModel<String> m = (javax.swing.DefaultComboBoxModel<String>) box.getModel();
        int at = m.getSize();
        for (int k = 0; k < m.getSize(); k++) {
            double x = parseSpeed(m.getElementAt(k));
            if (x <= 0) continue;
            if (Math.abs(x - v) < 1e-3) { box.setSelectedIndex(k); return; }
            if (x > v && at == m.getSize()) at = k;
        }
        String label = prefix + Timeline.fmtSpeed(v) + suffix;
        m.insertElementAt(label, at);
        box.setSelectedIndex(at);
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
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "del", this::deleteKey);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "del2", this::deleteKey);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), "in", this::setIn);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_O, 0), "out", this::setOut);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "mark", this::addMarker);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "hl", this::highlightReel);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "shot", this::frameGrab);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_C, 0), "caption", this::addCaption);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "ramp", () -> rampAt(player.time(), 0.3));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Z, 0), "zoomin", () -> zoomInAt(player.time()));
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), "freeze", () -> freezeAt(player.time()));
        String[] fxKeys = {"shake", "punch", "flash", "hitmarker", "sound"};
        for (int i = 0; i < fxKeys.length; i++) {
            String type = fxKeys[i];
            key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, 0), "fx" + i, () -> addEffect(type, player.time()));
        }
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo", this::undo);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo", this::redo);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redo2", this::redo);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "export", this::exportOrCancel);
        key(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc", () -> { if (pick != Pick.NONE) endPick(); else app.openEditor.accept(null); });
    }

    /** Delete: removes the selected item of the inspector list in use, otherwise cuts out the part under the playhead. */
    private void deleteKey() {
        Component f = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f instanceof javax.swing.JList<?> list && list.getSelectedIndex() >= 0 && listRemovers.containsKey(list)) {
            listRemovers.get(list).run();
            return;
        }
        deletePart();
    }

    /**
     * Bound keys that would otherwise type into text fields are skipped while one has focus; so are undo and redo,
     * so Ctrl+Z in a text box doesn't undo the edit behind it.
     */
    private void key(javax.swing.InputMap im, javax.swing.ActionMap am, KeyStroke ks, String name, Runnable r) {
        im.put(ks, name);
        boolean undoKey = name.equals("undo") || name.equals("redo") || name.equals("redo2");
        am.put(name, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                Component f = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                boolean typing = f instanceof javax.swing.text.JTextComponent;
                if (typing && (ks.getModifiers() == 0 || undoKey) && ks.getKeyCode() != KeyEvent.VK_ESCAPE) return;
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
            boolean simple = edit.segments.stream().allMatch(s -> s.speed == 1 && !s.ramped() && s.hold() == 0 && !s.rewinds()) && edit.aspect.equals("original")
                    && edit.zoom == 1 && edit.zooms.isEmpty() && edit.effects.isEmpty() && edit.masks.isEmpty() && edit.overlays.isEmpty()
                    && edit.titleText.isBlank() && edit.volume == 1 && edit.micVolume == 1 && edit.voiceVolume == 1 && !edit.micCleanup && !edit.mute
                    && edit.fadeIn == 0 && edit.fadeOut == 0 && edit.music == null && edit.captions.isEmpty() && "none".equals(edit.effect);
            if (!simple) info += " Only cuts are applied: speed, freeze frames, rewinds, framing, zoom, audio, music, captions, effects and title need another preset.";
        } else {
            int[] sz = Exporter.outputSize(clip, edit, o);
            info += "<br><span style='color:#F4F4F6'>" + sz[0] + "×" + sz[1] + " · " + Theme.timeFine(edit.outputDuration()) + "</span>";
        }
        presetInfo.setText("<html><body style='width:250px'>" + info + "</body></html>");
        gpu.setVisible(p != Exporter.Preset.QUICK_TRIM && p != Exporter.Preset.GIF && p.targetMb == 0);
        normalize.setVisible(p != Exporter.Preset.QUICK_TRIM && p != Exporter.Preset.GIF);
    }

    private Exporter.Options options() {
        Exporter.Options o = new Exporter.Options();
        o.preset = (Exporter.Preset) preset.getSelectedItem();
        o.gpu = gpu.isSelected();
        o.normalize = normalize.isSelected() && o.preset != Exporter.Preset.QUICK_TRIM && o.preset != Exporter.Preset.GIF;
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
        saveLater.stop();
        clip.edit = edit.copy();
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

        // where the frame and the export's area were last drawn (for drawing boxes on the video)
        private double fx, fy, fw = 1, fh = 1;
        private Rectangle2D area = new Rectangle2D.Double();
        private java.awt.Point dragFrom, dragTo;
        /** The current press started a pick (a spot or box on the video), so its click doesn't also play. */
        private boolean pickPress;

        VideoView() {
            setFocusable(true);
            setPreferredSize(new Dimension(640, 360));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    pickPress = pick != Pick.NONE;
                    if (pick != Pick.NONE) { dragFrom = e.getPoint(); dragTo = e.getPoint(); }
                }
                @Override public void mouseDragged(MouseEvent e) { if (dragFrom != null) { dragTo = e.getPoint(); repaint(); } }
                @Override public void mouseReleased(MouseEvent e) {
                    if (pick == Pick.NONE || dragFrom == null) return;
                    picked(dragFrom, e.getPoint());
                    dragFrom = dragTo = null;
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 1 && pick == Pick.NONE && !pickPress && javax.swing.SwingUtilities.isLeftMouseButton(e)) togglePlay();
                    pickPress = false;
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        /** A box dragged (or a spot clicked) on the video, for the facecam, a blur area or a zoom target. */
        private void picked(java.awt.Point a, java.awt.Point b) {
            if (edit == null) { endPick(); return; }
            double ax = (Math.min(a.x, b.x) - fx) / fw, ay = (Math.min(a.y, b.y) - fy) / fh;
            double bx = (Math.max(a.x, b.x) - fx) / fw, by = (Math.max(a.y, b.y) - fy) / fh;
            ax = Exporter.clamp01(ax); ay = Exporter.clamp01(ay); bx = Exporter.clamp01(bx); by = Exporter.clamp01(by);
            boolean click = Math.abs(a.x - b.x) < 6 && Math.abs(a.y - b.y) < 6;
            switch (pick) {
                case CAM -> {
                    if (click || bx - ax < 0.03 || by - ay < 0.03) return;
                    snapshot();
                    edit.camX = ax; edit.camY = ay; edit.camW = bx - ax; edit.camH = by - ay;
                    edited();
                    loadControls();
                }
                case MASK -> {
                    EditSpec.Mask m = selectedMask();
                    if (m == null || click) return;
                    // masks are placed on the export's frame: convert from the source to the export area
                    double rx = (Math.min(a.x, b.x) - area.getX()) / area.getWidth(), ry = (Math.min(a.y, b.y) - area.getY()) / area.getHeight();
                    double rw = Math.abs(a.x - b.x) / area.getWidth(), rh = Math.abs(a.y - b.y) / area.getHeight();
                    snapshot();
                    m.x = Exporter.clamp01(rx); m.y = Exporter.clamp01(ry); m.w = Math.max(0.02, Math.min(1 - m.x, rw)); m.h = Math.max(0.02, Math.min(1 - m.y, rh));
                    edited();
                }
                case ZOOM -> {
                    double cx = click ? ax : (ax + bx) / 2, cy = click ? ay : (ay + by) / 2;
                    pickedX = cx; pickedY = cy;
                    EditSpec.ZoomKey k = selectedZoomKey();
                    if (k != null && k.zoom > 1.001) { snapshot(); k.cx = cx; k.cy = cy; edited(); refreshZoomKeys(); }
                    app.notify("Zoom target set", "Zoom in at playhead (Z) uses this spot", false);
                }
                default -> {}
            }
            endPick();
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
            fx = x; fy = y; fw = dw; fh = dh;
            BufferedImage f = frame;
            double[] zz = previewEdits.isSelected() && edit != null && pick == Pick.NONE ? zoomAt(player.time()) : null;
            if (f != null && zz != null && zz[0] > 1.001) {
                // zoom keyframes, previewed: show the part of the frame the export will
                double z = zz[0];
                int sw = (int) (f.getWidth() / z), sh = (int) (f.getHeight() / z);
                int sx = (int) Math.max(0, Math.min(f.getWidth() - sw, zz[1] * f.getWidth() - sw / 2.0));
                int sy = (int) Math.max(0, Math.min(f.getHeight() - sh, zz[2] * f.getHeight() - sh / 2.0));
                g.drawImage(f, x, y, x + dw, y + dh, sx, sy, sx + sw, sy + sh, null);
            } else if (f != null) g.drawImage(f, x, y, dw, dh, null);
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
                    Exporter.Sprite sp = captionPreview(c, tw, th);
                    g.drawImage(sp.image(), (int) area2.getX() + sp.x(), (int) area2.getY() + sp.y(), null);
                }
                area = area2;
                // facecam area (split layout)
                if ("split".equals(edit.fitMode) && edit.camFile.isBlank()) {
                    Rectangle2D cam = new Rectangle2D.Double(x + dw * edit.camX, y + dh * edit.camY, dw * edit.camW, dh * edit.camH);
                    g.setColor(new Color(0xFF9F1C));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(cam);
                    g.setFont(Theme.interBold.deriveFont(11f));
                    g.drawString("FACECAM (goes on top)", (int) cam.getX() + 4, (int) cam.getY() - 5);
                }
                // blur areas
                for (EditSpec.Mask m : edit.masks) {
                    if (now < m.start || now > m.end) continue;
                    Rectangle2D r = new Rectangle2D.Double(area2.getX() + area2.getWidth() * m.x, area2.getY() + area2.getHeight() * m.y,
                            area2.getWidth() * m.w, area2.getHeight() * m.h);
                    g.setColor(m.kind.equals("black") ? Color.BLACK : new Color(40, 40, 52, 215));
                    g.fill(r);
                    g.setColor(new Color(255, 255, 255, 150));
                    g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 4}, 0));
                    g.draw(r);
                    g.setFont(Theme.interBold.deriveFont(10.5f));
                    g.drawString(m.kind.toUpperCase(), (int) r.getX() + 5, (int) r.getY() + 14);
                }
                // hitmarkers and flashes right now
                for (EditSpec.Effect fxe : edit.effects) {
                    if (now < fxe.t || now > fxe.t + Math.max(0.12, fxe.length)) continue;
                    if (fxe.type.equals("hitmarker")) {
                        int hw = (int) area2.getWidth(), hh = (int) area2.getHeight();
                        if (hw < 4 || hh < 4) continue;
                        if (hitPreview == null || hitPreview.getWidth() != hw || hitPreview.getHeight() != hh) hitPreview = Exporter.renderHitmarker(hw, hh);
                        g.drawImage(hitPreview, (int) area2.getX(), (int) area2.getY(), null);
                    } else if (fxe.type.equals("flash")) {
                        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (0.6 * (1 - (now - fxe.t) / Math.max(0.1, fxe.length)))));
                        g.setColor(Color.WHITE);
                        g.fill(area2);
                        g.setComposite(AlphaComposite.SrcOver);
                    }
                }
                // the box being dragged
                if (dragFrom != null && dragTo != null) {
                    g.setColor(pick == Pick.CAM ? new Color(0xFF9F1C) : Theme.BLUE);
                    g.setStroke(new BasicStroke(2f));
                    g.drawRect(Math.min(dragFrom.x, dragTo.x), Math.min(dragFrom.y, dragTo.y), Math.abs(dragFrom.x - dragTo.x), Math.abs(dragFrom.y - dragTo.y));
                }
                if (pick != Pick.NONE) {
                    String msg = pick == Pick.ZOOM ? "Click the spot to zoom in on" : pick == Pick.CAM ? "Drag a box around your facecam" : "Drag a box over what to hide";
                    g.setFont(Theme.interBold.deriveFont(13f));
                    int mw = g.getFontMetrics().stringWidth(msg) + 20;
                    g.setColor(new Color(0, 0, 0, 200));
                    g.fill(new java.awt.geom.RoundRectangle2D.Double((W - mw) / 2.0, 10, mw, 28, 14, 14));
                    g.setColor(Color.WHITE);
                    g.drawString(msg, (W - mw) / 2 + 10, 29);
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

        /** {zoom, cx, cy} from the zoom keyframes at source time t (the same eased glide as the export). */
        private double[] zoomAt(double t) {
            List<EditSpec.ZoomKey> keys = edit.sortedZooms();
            if (keys.isEmpty()) return null;
            if (t <= keys.get(0).t) return new double[]{keys.get(0).zoom, keys.get(0).cx, keys.get(0).cy};
            for (int i = 1; i < keys.size(); i++) {
                EditSpec.ZoomKey a = keys.get(i - 1), b = keys.get(i);
                if (t <= b.t) {
                    double u = Math.max(0, Math.min(1, (t - a.t) / Math.max(0.05, b.t - a.t)));
                    double e = u * u * (3 - 2 * u);
                    return new double[]{a.zoom + (b.zoom - a.zoom) * e, a.cx + (b.cx - a.cx) * e, a.cy + (b.cy - a.cy) * e};
                }
            }
            EditSpec.ZoomKey l = keys.get(keys.size() - 1);
            return new double[]{l.zoom, l.cx, l.cy};
        }

        private double aspect() {
            double r = switch (edit.aspect) {
                case "16:9" -> 16 / 9.0; case "9:16" -> 9 / 16.0; case "1:1" -> 1; case "4:5" -> 0.8; default -> srcW / (double) srcH; };
            // facecam on top: the gameplay fills only the part below it, so its slice is wider
            if ("split".equals(edit.fitMode) && !edit.aspect.equals("original") && !edit.aspect.equals("16:9")) r /= Math.max(0.3, 1 - edit.camShare);
            return r;
        }

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
    private double outputTimeAt(double t) { return edit.toOutput(t); }

    /**
     * A caption's picture for the preview, cropped to the text and cached (most recently used kept, about 32 MB
     * at most; all dropped when the preview changes size).
     */
    private Exporter.Sprite captionPreview(EditSpec.Caption c, int tw, int th) {
        if (tw != capPreviewW || th != capPreviewH) {
            capPreview.clear();
            capPreviewBytes = 0;
            capPreviewW = tw;
            capPreviewH = th;
        }
        String key = c.text + "|" + c.style + "|" + c.position;
        Exporter.Sprite sp = capPreview.get(key);
        if (sp != null) return sp;
        sp = Exporter.captionSprite(c.text, c.position, c.style, tw, th);
        capPreview.put(key, sp);
        capPreviewBytes += (long) sp.width() * sp.height() * 4;
        var it = capPreview.entrySet().iterator();
        while (capPreviewBytes > (32L << 20) && it.hasNext()) {
            var en = it.next();
            if (en.getKey().equals(key)) continue;
            capPreviewBytes -= (long) en.getValue().width() * en.getValue().height() * 4;
            it.remove();
        }
        return sp;
    }
}
