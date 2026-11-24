package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.CloudShare;
import net.evadeclient.clips.core.ControlServer;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Highlights;
import net.evadeclient.clips.core.Hotkeys;
import net.evadeclient.clips.core.Library;
import net.evadeclient.clips.core.Settings;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Wires the pieces together. Every action (UI button, hotkey, tray, Evade Client) goes through here. */
public final class App {
    public final Settings settings;
    public final Library library;
    public final CaptureEngine engine;
    public final Hotkeys hotkeys = new Hotkeys();
    public final ControlServer server = new ControlServer();
    public volatile CaptureEngine.Status status = new CaptureEngine.Status(CaptureEngine.State.OFF, false, 0, 0, "", "");

    /** Background exports, one at a time. */
    private final ExecutorService exports = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "export"); t.setDaemon(true); return t; });
    private final List<ExportJob> jobs = new CopyOnWriteArrayList<>();
    private final List<Runnable> jobListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> hotkeyListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> ffmpegListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> serverListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> captureListeners = new CopyOnWriteArrayList<>();
    public Consumer<Clip> openEditor = c -> {};
    /** Brings the main window forward (set by the window). */
    public Runnable showWindow = () -> {};
    /** Folders of game screenshots the library's Screenshots view shows (the launcher lists each instance's). */
    public volatile java.util.function.Supplier<java.util.List<ShotsGrid.Source>> screenshotSources = ShotsGrid.defaultSources();
    /** The main window, as the parent of dialogs the app asks from background work (null until it exists). */
    public volatile java.awt.Window window;
    /**
     * "Minecraft" while Minecraft (with Evade Client) is running, else null; checked every few seconds. Evade Clips
     * only records Minecraft, so capture runs only while this is set.
     */
    public volatile String currentGame;
    /** What the app says when you try to record or save a clip without Minecraft open. */
    public static final String NEED_MINECRAFT = "Open Minecraft with Evade Client to record.";
    public final net.evadeclient.clips.events.AutoClipper autoClipper;
    public final CloudShare cloud;
    public final net.evadeclient.clips.events.LiveHighlights live;
    /** Instant replay was started because Minecraft started (stopped again when it closes). */
    private volatile boolean autoStarted;
    /** When Evade Client last talked to the control API (it sends client=evade); its own events clip Minecraft. */
    volatile long evadeClientSeenAt;
    /** Id of the last clip this app saved (hotkey, API, voice, auto-clip, recording), for Evade Client's in-game confirmation. */
    private volatile String lastSavedClipId = "";
    /** Why the local control server isn't running (port in use), or null. */
    private volatile String controlError;
    /** Hotkeys that couldn't be bound ("Save replay (Numpad 5)"), shown under the hotkey rows. */
    private volatile List<String> hotkeyProblems = List.of();
    private volatile boolean hotkeysWarned;
    /** Hotkeys have been applied at least once (before that, whether the hook works isn't known yet). */
    private volatile boolean hotkeysTried;
    /** True while the first-run ffmpeg download runs (the Record page says so instead of "wasn't found"). */
    public volatile boolean ffmpegDownloading;
    public volatile double ffmpegProgress;
    public Toast toast;

    public App(Settings settings) {
        this.settings = settings;
        Ffmpeg.configure(settings.ffmpegPath);
        library = new Library(settings);
        library.onNotice(this::notify);
        engine = new CaptureEngine(settings, library);
        engine.onStatus(s -> status = s);
        autoClipper = new net.evadeclient.clips.events.AutoClipper((secs, title, game, tags) -> {
            if (!engine.isLive()) return;
            saveClipNow(secs, 0, title, game, tags);
        });
        cloud = new CloudShare(settings);
        cloud.confirmReplace = this::askReplace;
        autoClipper.onMarker = label -> { if (engine.isLive()) engine.addMarker(label); };
        // The game that's really running: a loud moment is never filed under the default game.
        live = new net.evadeclient.clips.events.LiveHighlights(engine, autoClipper, () -> currentGame, library.metaDir().resolve("live"));
        library.onAdded(c -> {
            boolean sync = switch (settings.cloudSync) {
                case "all" -> true;
                case "favorites" -> c.kind == Clip.Kind.EXPORT || c.favorite;
                default -> false;
            };
            if (sync && cloud.configured()) shareClip(c, false);
        });
        applyEventSettings();
    }

    public void applyEventSettings() {
        autoClipper.before = settings.autoClipBefore;
        autoClipper.after = settings.autoClipAfter;
        if (live != null) live.threshold = net.evadeclient.clips.events.LiveHighlights.thresholdFor(settings.liveSensitivity);
    }

    /** The Record page reloads its controls when a setting it shows was changed elsewhere (e.g. the replay length). */
    public void onCaptureSettingsChanged(Runnable r) { captureListeners.add(r); }
    private void fireCaptureSettingsChanged() { for (Runnable r : captureListeners) SwingUtilities.invokeLater(r); }

    private net.evadeclient.clips.core.VoiceCommands voice;

    /**
     * Starts or stops listening for voice commands to match the settings (the microphone chosen on the Record
     * page). Opening a microphone can take a moment: call it off the UI thread. Returns an error message or null.
     */
    public synchronized String applyVoice() {
        try {
            if (!settings.voiceCommands) {
                if (voice != null) { voice.close(); voice = null; }
                return null;
            }
            if (!net.evadeclient.clips.core.VoiceCommands.ready()) return "The speech engine isn't downloaded yet.";
            if (voice == null) voice = new net.evadeclient.clips.core.VoiceCommands(net.evadeclient.clips.core.VoiceCommands.modelDir());
            voice.start(micDisplayName(settings.micDevice), cmd -> {
                switch (cmd) {
                    case CLIP -> saveClip(0, null, null, "voice");
                    case RECORD_START -> { if (!engine.isRecording()) toggleRecording(); }
                    case RECORD_STOP -> { if (engine.isRecording()) toggleRecording(); }
                    case MARKER -> { engine.addMarker("Voice"); notify("Marker added", null, false); }
                }
            }, msg -> notify(msg, null, true));
            return null;
        } catch (Throwable t) {
            return "Voice commands couldn't start: " + t.getMessage();
        }
    }

    /** The microphone's name as the system shows it (what voice commands match on), from the saved device id. */
    private static String micDisplayName(String id) {
        if (id == null || id.isBlank()) return "";
        for (var e : net.evadeclient.clips.core.CaptureSource.audioDevices().entrySet())
            if (e.getValue().equals(id)) {
                String n = e.getKey();
                return n.startsWith("Mic: ") ? n.substring(5) : n;
            }
        return id; // on Windows the DirectShow id is already the display name
    }

    /** Every few minutes: phone uploads and notices from the share server. */
    private void background() {
        int n = 0;
        while (true) {
            try {
                if (n % 3 == 0 && cloud.configured() && !settings.cloudAccount.isBlank()) {
                    for (var note : cloud.newNotices()) {
                        String title = note.has("title") ? note.get("title").getAsString() : "A clip";
                        String why = note.has("reasonText") ? note.get("reasonText").getAsString() : "breaking the rules";
                        notify("Clip removed: " + title, "Taken down for " + why + (note.has("note") && !note.get("note").getAsString().isBlank() ? ": " + note.get("note").getAsString() : ""), true);
                        for (Clip c : library.all()) if (note.get("clip").getAsString().equals(c.shareId)) { c.shareId = null; c.shareUrl = null; c.shareBase = null; library.update(c); }
                    }
                    if (settings.pullPhoneUploads)
                        for (Clip c : cloud.pullPhoneUploads(library)) notify("From your phone", c.title, false);
                }
            } catch (Exception e) {
                System.err.println("[clips] background sync: " + e.getMessage());
            }
            n++;
            try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * Starts capture, hotkeys and the control API first (so a clip can be saved right away), then scans the
     * clips folder. The library index itself is read before the window opens ({@link Library#loadIndex()}).
     */
    public void start() {
        Thread watcher = new Thread(this::watchGames, "game-watcher");
        watcher.setDaemon(true);
        watcher.start();
        applyHotkeys();
        applyServer();
        CloudShare.cleanPartials(library);
        library.rescan();
        library.sweepOldReplays();
        Thread bg = new Thread(this::background, "background-sync");
        bg.setDaemon(true);
        bg.start();
        String voiceError = applyVoice();
        if (voiceError != null && settings.voiceCommands) notify(voiceError, null, true);
    }

    /**
     * True while Minecraft (with Evade Client) is open: its process is running, or Evade Client talked to the app
     * in the last 15 s. The self-tests' built-in test patterns always count.
     */
    public boolean minecraftRunning() {
        return currentGame != null || evadeClientRecently() || settings.source.startsWith("test");
    }

    private boolean evadeClientRecently() { return System.currentTimeMillis() - evadeClientSeenAt < 15_000; }

    /** Runs instant replay only while Minecraft is open, and stops capture (saving a recording) when it closes. */
    private void watchGames() {
        while (true) {
            try {
                boolean mc = net.evadeclient.clips.core.GameDetector.detect().isPresent() || evadeClientRecently();
                String game = mc ? net.evadeclient.clips.core.GameDetector.MINECRAFT : null;
                String before = currentGame;
                currentGame = game;
                if (game != null && !game.equals(before)) {
                    if (settings.autoReplayForGames && settings.replayEnabled && !engine.isLive() && Ffmpeg.available()) {
                        engine.start();
                        autoStarted = true;
                        notify(game + " detected", "Instant replay is on · " + settings.hotkeySaveClip + " saves a clip", false);
                    } else if (engine.isLive() && !engine.isRecording() && Ffmpeg.WINDOWS && settings.recordSystemAudio
                            && settings.gameAudioSeparate && settings.systemAudioDevice.isBlank()) {
                        // capture was already on: restart it once so the game's sound gets its own track
                        try { engine.restart(); } catch (Exception e) { System.err.println("[clips] " + e.getMessage()); }
                    }
                } else if (game == null && before != null && !settings.source.startsWith("test")) {
                    // Minecraft closed: capture only ever records its window, so it stops (a recording is saved).
                    if (engine.isRecording()) stopRecordingNow();
                    if (engine.isLive() || autoStarted) engine.stop();
                    autoStarted = false;
                }
            } catch (Exception e) {
                System.err.println("[clips] game watcher: " + e.getMessage());
            }
            // Live loud-moment detection: Evade Client's Clips mod sends Minecraft's own events while it's talking
            // to the app; without it (the mod turned off), loud moments are clipped instead.
            boolean wantLive = settings.liveHighlights && engine.isLive() && currentGame != null && !evadeClientRecently();
            if (wantLive && !live.running()) live.start();
            else if (!wantLive && live.running()) live.stop();
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * Applies the "Behavior" switch right away: starts instant replay when Minecraft is running with auto start
     * on. Never stops a replay you turned on yourself or a running recording.
     */
    public void applyReplaySettings() {
        Thread t = new Thread(() -> {
            if (!settings.replayEnabled || !Ffmpeg.available()) return;
            String game = currentGame;
            try {
                if (settings.autoReplayForGames && game != null && !engine.isLive()) {
                    engine.start();
                    autoStarted = true;
                    notify(game + " detected", "Instant replay is on · " + settings.hotkeySaveClip + " saves a clip", false);
                }
            } catch (Exception e) {
                notify("Couldn't start capture", e.getMessage(), true);
            }
        }, "replay-settings");
        t.setDaemon(true);
        t.start();
    }

    /** Game name for a new clip: what the caller said, else Minecraft. */
    String gameFor(String asked) {
        if (asked != null && !asked.isBlank()) return asked;
        return net.evadeclient.clips.core.GameDetector.MINECRAFT;
    }

    public void shutdown() {
        live.stop();
        autoClipper.shutdown();
        if (voice != null) voice.close();
        engine.shutdown();
        hotkeys.stop();
        server.stop();
        library.shutdown();
        for (ExportJob j : jobs) j.job.cancel();
    }

    // ------------------------------------------------------------------ hotkeys, control API, ffmpeg

    /** Called (on the UI thread) after hotkeys were applied, whether the global hook started or not. */
    public void onHotkeysChanged(Runnable r) { hotkeyListeners.add(r); }

    /** Hotkeys that couldn't be bound, e.g. "Save replay (Numpad 5)". */
    public List<String> hotkeyProblems() { return hotkeyProblems; }

    /** True once the global hotkey hook has been tried (so "couldn't start" isn't shown before it was). */
    public boolean hotkeysTried() { return hotkeysTried; }

    public void applyHotkeys() {
        hotkeys.clear();
        List<String> failed = new ArrayList<>();
        if (hotkeys.start()) {
            if (!hotkeys.bind(settings.hotkeySaveClip, () -> saveClip(0, null, null, null))) failed.add("Save replay (" + settings.hotkeySaveClip + ")");
            if (!hotkeys.bind(settings.hotkeyRecord, this::toggleRecording)) failed.add("Start / stop recording (" + settings.hotkeyRecord + ")");
            if (!hotkeys.bind(settings.hotkeyMarker, this::marker)) failed.add("Add marker (" + settings.hotkeyMarker + ")");
        } else if (!hotkeysWarned) {
            hotkeysWarned = true;
            notify("Global hotkeys unavailable", (hotkeys.error() == null || hotkeys.error().isBlank() ? "" : hotkeys.error() + ". ")
                    + "Buttons, the tray icon and Evade Client still work.", true);
        }
        hotkeyProblems = List.copyOf(failed);
        hotkeysTried = true;
        for (Runnable r : hotkeyListeners) SwingUtilities.invokeLater(r);
    }

    /** Called (on the UI thread) after the control server was started or stopped. */
    public void onServerChanged(Runnable r) { serverListeners.add(r); }

    /** Why the local control server isn't running, or null when it is. */
    public String controlError() { return controlError; }

    /**
     * (Re)starts the local control server. It always runs (so a second copy of the app finds this one); it only
     * takes clips from Evade Client, and with "Allow Evade Client to save clips" off it refuses everything else.
     */
    public void applyServer() {
        server.stop();
        controlError = null;
        try {
            server.start(settings.controlPort, new ControlServer.Handler() {
                @Override public Map<String, Object> status() {
                    CaptureEngine.Status s = status;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app", "Evade Clips");
                    // "launcher": Clips inside the Evade Launcher; "app": the old separate app (a new launcher closes that one)
                    m.put("host", net.evadeclient.clips.ClipsApp.hosted ? "launcher" : "app");
                    m.put("state", s.state().name().toLowerCase());
                    m.put("recording", s.recording());
                    m.put("buffered", Math.round(s.bufferedSeconds()));
                    m.put("replaySeconds", settings.replaySeconds);
                    // so Evade Client knows this key already saved a clip and doesn't save a second one
                    m.put("hotkey", hotkeys.active() ? settings.hotkeySaveClip : "");
                    m.put("hotkeysActive", hotkeys.active());
                    m.put("lastClip", lastSavedClipId);
                    return m;
                }
                @Override public boolean quit() {
                    if (status.recording()) return false;
                    // after the answer has gone out: shut down cleanly (buffer, library, hotkeys), then exit
                    Thread t = new Thread(() -> {
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        shutdown();
                        System.exit(0);
                    }, "quit for update");
                    t.start();
                    return true;
                }
                @Override public Map<String, Object> importFile(java.nio.file.Path file, String title) {
                    var added = library.importFiles(List.of(file), false);
                    if (added.isEmpty()) return Map.of("ok", false);
                    Clip c = added.get(0);
                    if (title != null && !title.isBlank()) c.title = title;
                    c.game = "Minecraft";
                    if (!c.tags.contains("replay")) c.tags.add("replay");
                    library.update(c);
                    App.this.notify("Replay render added", c.title, false);
                    return Map.of("ok", true, "id", c.id);
                }
                @Override public Map<String, Object> clip(int seconds, int after, String title, String game, String tag) throws Exception {
                    return clip(new ControlServer.ClipRequest(seconds, after, title, game, tag, false, 0));
                }
                @Override public Map<String, Object> clip(ControlServer.ClipRequest r) throws Exception {
                    int seconds, after;
                    if (r.auto()) {
                        // An automatic clip (kill, win...): the app owns its length, like its own game events.
                        seconds = Math.min(600, settings.autoClipBefore + r.span());
                        after = r.after() >= 0 ? r.after() : settings.autoClipAfter;
                    } else {
                        seconds = r.seconds(); // 0 = the replay length
                        after = r.after() >= 0 ? r.after() : settings.replayAfterSeconds;
                    }
                    Clip c = saveClipNow(seconds, after, r.title(), r.game(), r.tag());
                    return Map.of("ok", true, "id", c.id, "file", c.path(library.root()).toString(), "duration", c.duration, "title", c.title);
                }
                @Override public Map<String, Object> record(String action, String game) throws Exception {
                    boolean rec = engine.isRecording();
                    if (action.equals("start") || (action.equals("toggle") && !rec)) { startRecording(game); return Map.of("ok", true, "recording", true); }
                    Clip c = stopRecordingNow();
                    return c == null ? Map.of("ok", true, "recording", false) : Map.of("ok", true, "recording", false, "id", c.id);
                }
                @Override public void marker() { App.this.marker(); }
                @Override public void marker(String label) { engine.addMarker(label); }
                @Override public boolean open(String id) {
                    SwingUtilities.invokeLater(showWindow);
                    if (id == null || id.isBlank()) return true;
                    Clip c = id.equals("last") ? library.query("", "all", null, "newest").stream().findFirst().orElse(null) : library.get(id);
                    if (c != null) openEditor.accept(c);
                    return c != null;
                }
                @Override public void evadeClientSeen() { evadeClientSeenAt = System.currentTimeMillis(); }
                @Override public boolean apiEnabled() { return settings.controlServer; }
            });
        } catch (Exception e) {
            controlError = "Port " + settings.controlPort + " is in use. Pick another port.";
            System.err.println("[clips] control API unavailable on port " + settings.controlPort + ": " + e.getMessage());
            notify("Couldn't start the local control server", "Port " + settings.controlPort + " is in use. Pick another port in Settings.", true);
        }
        for (Runnable r : serverListeners) SwingUtilities.invokeLater(r);
    }

    /**
     * After the local port changed: restarts the server and tells Evade Client where to find it (location.json).
     * Runs file work, so call it off the UI thread.
     */
    public void applyPortChange() {
        applyServer();
        net.evadeclient.clips.ClipsApp.writeLocation(settings.controlPort);
    }

    /** Called (on the UI thread) when ffmpeg was found, downloaded or changed in Settings. */
    public void onFfmpegChanged(Runnable r) { ffmpegListeners.add(r); }

    /**
     * ffmpeg is now (or no longer) usable: the pages refresh, the library scans for videos it couldn't read, and
     * instant replay starts if it should be on.
     */
    public void fireFfmpegChanged() {
        for (Runnable r : ffmpegListeners) SwingUtilities.invokeLater(r);
        if (!Ffmpeg.available()) return;
        Thread t = new Thread(() -> {
            library.rescan();
            applyReplaySettings();
        }, "ffmpeg-ready");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ capture actions

    public void toggleReplay(boolean on) {
        settings.replayEnabled = on;
        settings.save();
        if (on && !minecraftRunning()) {
            // It turns on by itself as soon as Minecraft starts.
            notify(NEED_MINECRAFT, "Instant replay turns on by itself when Minecraft starts.", false);
            return;
        }
        // Switched by hand: it stays the way you set it (until Minecraft closes).
        autoStarted = false;
        new Thread(() -> {
            try {
                if (on) engine.start();
                else if (!engine.isRecording()) engine.stop();
            } catch (Exception e) { notify("Couldn't start capture", e.getMessage(), true); }
        }, "replay-toggle").start();
    }

    /** Tag on the clip a look-back opens: the whole buffer, discarded on leaving the editor unless it was trimmed. */
    public static final String LOOK_BACK = "look-back";

    /**
     * Look back: the whole replay buffer (everything from the last few minutes) opens in the editor, to find any
     * moment and keep just that. Nothing is kept unless you trim it; see {@link EditorPanel}.
     */
    public void lookBack() {
        if (!minecraftRunning()) { notify(NEED_MINECRAFT, null, false); return; }
        new Thread(() -> {
            try {
                if (!engine.isLive()) throw new Exception("Instant replay is off. Turn it on in Record.");
                int secs = (int) Math.ceil(Math.max(1, status.bufferedSeconds()));
                Clip c = lookBackNow(secs, null);
                notify("Look back", "Your last " + Theme.time(c.duration) + ". Trim it to the moment you want: trimmed, it's kept; untouched, it's discarded when you leave.", false);
                openEditor.accept(c);
            } catch (Exception e) { notify("Couldn't look back", e.getMessage(), true); }
        }, "look-back").start();
    }

    /** The whole buffer (or its last {@code seconds}) as a look-back clip. */
    public Clip lookBackNow(int seconds, String game) throws Exception {
        Clip c = engine.saveReplay(seconds, 0, "Look back", gameFor(game));
        if (!c.tags.contains(LOOK_BACK)) c.tags.add(LOOK_BACK);
        library.update(c);
        return c;
    }

    /**
     * Leaving a look-back clip in the editor: untouched, it's deleted (nothing to keep); trimmed or otherwise edited,
     * it becomes an ordinary clip. Returns whether it was deleted.
     */
    public boolean settleLookBack(Clip c) {
        if (c == null || !c.tags.contains(LOOK_BACK)) return false;
        if (untouched(c)) {
            library.delete(List.of(c));
            return true;
        }
        c.tags.remove(LOOK_BACK);
        library.update(c);
        return false;
    }

    /** Whether a clip has no edits (or only the default "the whole clip"). */
    public static boolean untouched(Clip c) {
        if (c.edit == null) return true;
        com.google.gson.Gson g = new com.google.gson.Gson();
        return g.toJson(c.edit).equals(g.toJson(EditSpec.whole(c.duration)));
    }

    /** Async save from a hotkey/button; reports with a toast. */
    public void saveClip(int seconds, String title, String game, String tag) {
        if (!minecraftRunning()) { notify(NEED_MINECRAFT, null, false); return; }
        new Thread(() -> {
            try { saveClipNow(seconds, settings.replayAfterSeconds, title, game, tag); }
            catch (Exception e) { notify("Clip not saved", e.getMessage(), true); }
        }, "save-clip").start();
    }

    public Clip saveClipNow(int seconds, int after, String title, String game, String tag) throws Exception {
        if (!minecraftRunning()) throw new Exception(NEED_MINECRAFT);
        if (!engine.isLive()) throw new Exception("Instant replay is off. Turn it on in Record.");
        if (after > 0 && settings.showToast && toast != null) SwingUtilities.invokeLater(() -> toast.show("Saving…", "Capturing " + after + " more seconds", false));
        Clip c = engine.saveReplay(seconds > 0 ? seconds : settings.replaySeconds, after, title, gameFor(game));
        if (tag != null && !tag.isBlank()) {
            for (String t : tag.split(",")) if (!t.isBlank() && !c.tags.contains(t.trim())) c.tags.add(t.trim());
            library.update(c);
        }
        lastSavedClipId = c.id;
        notify("Clip saved", (c.title == null ? "" : c.title + " · ") + Theme.time(c.duration), false);
        return c;
    }

    public void toggleRecording() {
        if (!engine.isRecording() && !minecraftRunning()) { notify(NEED_MINECRAFT, null, false); return; }
        new Thread(() -> {
            try {
                if (engine.isRecording()) stopRecordingNow();
                else startRecording(null);
            } catch (Exception e) { notify("Recording failed", e.getMessage(), true); }
        }, "record-toggle").start();
    }

    public void startRecording(String game) throws Exception {
        if (!minecraftRunning()) throw new Exception(NEED_MINECRAFT);
        engine.startRecording(gameFor(game));
        notify("Recording", "Press " + settings.hotkeyRecord + " to stop", false);
    }

    public Clip stopRecordingNow() throws Exception {
        Clip c = engine.stopRecording();
        if (c != null) {
            lastSavedClipId = c.id;
            notify("Recording saved", Theme.time(c.duration) + " · " + Theme.size(c.size), false);
        }
        if (!settings.replayEnabled && !engine.isRecording()) engine.stop();
        // Minecraft closed while recording: instant replay was only on for it.
        if (autoStarted && currentGame == null && !engine.isRecording()) {
            engine.stop();
            autoStarted = false;
        }
        if (c != null && settings.autoHighlights && c.duration >= 45
                && (c.hasAudio || c.markers.stream().anyMatch(t -> Highlights.isEvent(c.label(t))))) findHighlights(c, true);
        return c;
    }

    /** Scans a clip for its best moments (game-event markers, then the loudest game and mic audio) and marks them. */
    public void findHighlights(Clip c, boolean quiet) {
        Thread t = new Thread(() -> {
            try {
                List<Highlights.Moment> m = Highlights.detect(c, c.path(library.root()), 0, null);
                int added = 0;
                for (Highlights.Moment x : m) {
                    boolean dup = false;
                    for (double have : c.markers) if (Math.abs(have - x.time()) < 2) dup = true;
                    if (!dup) { c.markers.add(x.time()); added++; }
                }
                c.markers.sort(Double::compare);
                if (!m.isEmpty() && !c.tags.contains("highlights")) c.tags.add("highlights");
                library.update(c);
                if (!quiet || added > 0) notify(m.isEmpty() ? "No highlights found" : "Found " + m.size() + " highlight" + (m.size() == 1 ? "" : "s"),
                        c.title, false);
            } catch (Exception e) {
                if (!quiet) notify("Highlight scan failed", e.getMessage(), true);
            }
        }, "highlights");
        t.setDaemon(true);
        t.start();
    }

    /** Saves the frame at {@code t} as a full-resolution PNG next to the clips and copies it. */
    public Path screenshot(Clip c, double t) throws Exception {
        Path dir = library.root().resolve("Screenshots");
        Files.createDirectories(dir);
        Path out = Library.unique(dir, Library.safe(c.title == null ? "Frame" : c.title) + " " + String.format("%.2f", t).replace('.', '_') + ".png");
        Ffmpeg.run(List.of("-ss", Library.fmt(t), "-i", c.path(library.root()).toString(), "-frames:v", "1", out.toString()));
        return out;
    }

    public void marker() {
        engine.addMarker();
        notify("Marker added", null, false);
    }

    /** Something the app told the player (a host such as the Evade Launcher lists these under its bell). */
    public interface NoticeListener { void notice(String title, String body, boolean error); }
    private final List<NoticeListener> noticeListeners = new CopyOnWriteArrayList<>();
    public void onNotice(NoticeListener l) { noticeListeners.add(l); }

    public void notify(String title, String body, boolean error) {
        if (error) System.err.println("[clips] " + title + ": " + body);
        for (NoticeListener l : noticeListeners) { try { l.notice(title, body, error); } catch (RuntimeException ignored) {} }
        if (settings.playSound) beep(!error);
        if (settings.showToast && toast != null) SwingUtilities.invokeLater(() -> toast.show(title, body, error));
    }

    /** A short two-tone chirp (no audio files to ship). */
    static void beep(boolean ok) {
        Thread t = new Thread(() -> {
            try {
                AudioFormat f = new AudioFormat(44100, 16, 1, true, false);
                SourceDataLine line = AudioSystem.getSourceDataLine(f);
                line.open(f);
                line.start();
                double[] tones = ok ? new double[]{880, 1320} : new double[]{330, 220};
                byte[] buf = new byte[44100 * 2 * 7 / 100];
                for (double tone : tones) {
                    for (int i = 0; i < buf.length / 2; i++) {
                        double env = Math.min(1, Math.min(i / 200.0, (buf.length / 2.0 - i) / 400.0));
                        short s = (short) (Math.sin(2 * Math.PI * tone * i / 44100) * 6000 * env);
                        buf[2 * i] = (byte) s;
                        buf[2 * i + 1] = (byte) (s >> 8);
                    }
                    line.write(buf, 0, buf.length);
                }
                line.drain();
                line.close();
            } catch (Exception ignored) {}
        }, "beep");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ exports

    public static final class ExportJob {
        public final Clip clip;
        public final Exporter.Preset preset;
        public final Path out;
        public volatile String label;
        public final net.evadeclient.clips.core.Ffmpeg.Job job = new net.evadeclient.clips.core.Ffmpeg.Job();
        public volatile double progress;
        public volatile String state = "Queued";
        public volatile Clip result;
        public volatile boolean done;
        /** When the job was queued (the sidebar lists the newest first). */
        public final long queuedAt = System.currentTimeMillis();

        ExportJob(Clip clip, Exporter.Preset preset, Path out) { this.clip = clip; this.preset = preset; this.out = out; this.label = preset.label; }

        /** What the job works on, for the sidebar ("Discord · Clutch 1v3"); a montage has no single clip. */
        public String title() {
            if ("Montage".equals(label)) return out.getFileName().toString().replaceFirst("\\.mp4$", "");
            return clip == null || clip.title == null ? "" : clip.title;
        }

        /** "Queued", "37%", "Done", "Cancelled" or "Failed: ...". */
        public String stateText() {
            if (done) return state;
            return "Queued".equals(state) ? "Queued" : Math.round(progress * 100) + "%";
        }

        public void cancel() { job.cancel(); }
    }

    public List<ExportJob> jobs() { return new ArrayList<>(jobs); }
    public void onJobs(Runnable r) { jobListeners.add(r); }
    private void jobsChanged() { for (Runnable r : jobListeners) r.run(); }

    public ExportJob export(Clip clip, EditSpec edit, Exporter.Options o, Consumer<ExportJob> onDone) {
        Path out = Exporter.outputFor(library, clip, o.preset);
        EditSpec spec = edit.copy();
        return runJob(new ExportJob(clip, o.preset, out), (j) -> {
            Exporter.export(clip.path(library.root()), clip, spec, o, out, p -> { j.progress = p; jobsChanged(); }, j.job);
            return library.addNew(out, Clip.Kind.EXPORT, (clip.title == null ? "Clip" : clip.title) + " · " + o.preset.label,
                    clip.game, null, clip.id);
        }, onDone);
    }

    /** Joins clips (in the given order, each with its edits) into one montage video, with the transition in Settings. */
    public ExportJob montage(List<Clip> clips, String title, Consumer<ExportJob> onDone) {
        return montage(clips, title, settings.montageTransition, onDone);
    }

    /**
     * Joins clips (in the given order, each with its edits) into one montage video. {@code transition}: "none",
     * "dip", "fade", "slideleft", "zoomin" or "fadewhite".
     */
    public ExportJob montage(List<Clip> clips, String title, String transition, Consumer<ExportJob> onDone) {
        // Reserved, so two montages queued with the same title never share a file.
        Path out = Exporter.reserveOutput(library.folder(Clip.Kind.EXPORT), Library.safe(title).isBlank() ? "Montage.mp4" : Library.safe(title) + ".mp4");
        Exporter.Options o = new Exporter.Options();
        o.preset = Exporter.Preset.YOUTUBE;
        o.transition = transition == null || transition.isBlank() ? "dip" : transition;
        List<Clip> list = new ArrayList<>(clips);
        ExportJob job = new ExportJob(list.get(0), Exporter.Preset.YOUTUBE, out);
        job.label = "Montage";
        return runJob(job, j -> {
            Exporter.montage(list, library.root(), o, out, p -> { j.progress = p; jobsChanged(); }, j.job);
            Clip c = library.addNew(out, Clip.Kind.EXPORT, title, list.get(0).game, null, null);
            if (!c.tags.contains("montage")) { c.tags.add("montage"); library.update(c); }
            return c;
        }, onDone);
    }

    /** Re-encodes clips to HEVC in the background, replacing each file only if it saves 15%+. */
    public void shrink(List<Clip> clips) {
        for (Clip c : clips) {
            Path tmp = library.root().resolve(".evadeclips").resolve("shrink-" + c.id + ".mp4");
            ExportJob job = new ExportJob(c, Exporter.Preset.SOURCE, tmp);
            job.label = "Shrink";
            runJob(job, j -> {
                Path out = Exporter.shrink(c.path(library.root()), c, tmp, p -> { j.progress = p; jobsChanged(); }, j.job);
                if (out == null) { notify("Already compact", c.title + " wouldn't get much smaller", false); return null; }
                long saved = library.replaceFile(c, out);
                notify("Saved " + Theme.size(saved), c.title + " is now " + Theme.size(c.size), false);
                return c;
            }, null);
        }
    }

    /**
     * Uploads a clip and (with {@code copy}) puts its link on the clipboard, ready to paste into Discord. If the
     * clip already has a link and its edits changed, you're asked before the old link is replaced.
     */
    public void shareClip(Clip c, boolean copy) {
        if (!cloud.configured()) {
            notify("Sharing isn't set up", "Add a share server in Settings → Sharing & phone", true);
            return;
        }
        if (c.shareId == null && settings.sharePrivateByDefault) c.sharePrivate = true;
        ExportJob job = new ExportJob(c, Exporter.Preset.SOURCE, library.metaDir().resolve("share-" + c.id + ".mp4"));
        job.label = "Share";
        runJob(job, j -> {
            waitForThumb(c);
            String url = cloud.share(c, library, p -> { j.progress = p; jobsChanged(); }, j.job);
            if (copy) {
                copyText(url);
                notify("Link copied", c.sharePrivate ? "Private link: anyone you send it to can watch" : url, false);
            }
            return null;
        }, null);
    }

    /** Copies text to the clipboard (on the UI thread). */
    public static void copyText(String text) {
        SwingUtilities.invokeLater(() -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null));
    }

    /** Lets the thumbnail finish so the link preview has a picture. */
    private void waitForThumb(Clip c) throws InterruptedException {
        for (int i = 0; i < 50 && !Files.exists(library.thumb(c)); i++) Thread.sleep(200);
    }

    /**
     * Posts a clip's link to a Discord channel. A clip that already has a public link posts that link; otherwise
     * it's shared first. {@code makePublic}: the user agreed that a private clip becomes viewable by anyone with
     * the link (a private key is never posted).
     */
    public void shareToWebhook(Clip c, Settings.Webhook hook, boolean makePublic) {
        ExportJob job = new ExportJob(c, Exporter.Preset.SOURCE, library.metaDir().resolve("share-" + c.id + ".mp4"));
        job.label = "Discord";
        runJob(job, j -> {
            if (c.shareUrl == null || c.sharePrivate) {
                if (!cloud.configured()) throw new Exception("Add a share server in Settings → Sharing & phone first");
                if (c.shareId == null) c.sharePrivate = !makePublic && settings.sharePrivateByDefault;
                waitForThumb(c);
                cloud.share(c, library, p -> { j.progress = p; jobsChanged(); }, j.job);
                if (c.sharePrivate) {
                    if (!makePublic) throw new Exception("The clip is private, so its link wasn't posted");
                    cloud.setVisibility(c, library, false);
                }
            }
            CloudShare.postWebhook(hook.url, c, c.publicShareUrl());
            notify("Posted to " + hook.name, c.title, false);
            return null;
        }, null);
    }

    /** Asks (on the UI thread) whether a clip's existing link should be replaced by a new upload. */
    private CloudShare.Replace askReplace(Clip c) {
        CloudShare.Replace[] out = {CloudShare.Replace.CANCEL};
        Runnable ask = () -> {
            String t = c.title == null ? "This clip" : "\"" + c.title + "\"";
            String msg = cloud.onCurrentServer(c)
                    ? t + " changed since you shared it.\nReplace the existing link? The old link will stop working."
                    : t + " was shared on another server (" + cloud.serverOf(c) + ").\nShare it on " + cloud.base() + " instead? The old link will stop working.";
            Object[] options = {"Replace link", "Keep the old link", "Cancel"};
            int r = JOptionPane.showOptionDialog(window, msg, "Share link", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            out[0] = r == 0 ? CloudShare.Replace.REPLACE : r == 1 ? CloudShare.Replace.KEEP_OLD : CloudShare.Replace.CANCEL;
        };
        if (java.awt.GraphicsEnvironment.isHeadless()) return CloudShare.Replace.REPLACE;
        try {
            if (SwingUtilities.isEventDispatchThread()) ask.run();
            else SwingUtilities.invokeAndWait(ask);
        } catch (Exception e) {
            return CloudShare.Replace.CANCEL;
        }
        return out[0];
    }

    interface JobBody { Clip run(ExportJob j) throws Exception; }

    private ExportJob runJob(ExportJob j, JobBody body, Consumer<ExportJob> onDone) {
        Path out = j.out;
        jobs.add(j);
        jobsChanged();
        exports.submit(() -> {
            if (j.job.cancelled()) {
                // Cancelled while queued: free the file name it was promised.
                j.state = "Cancelled";
                Exporter.release(out);
            } else {
                j.state = "Exporting";
                jobsChanged();
                try {
                    j.result = body.run(j);
                    j.state = "Done";
                    if (!"Shrink".equals(j.label) && !"Share".equals(j.label) && !"Discord".equals(j.label))
                        notify("Export finished", out.getFileName().toString(), false);
                } catch (Exception e) {
                    boolean cancelled = j.job.cancelled() || "Cancelled".equals(e.getMessage());
                    j.state = cancelled ? "Cancelled" : "Failed: " + e.getMessage();
                    try { Files.deleteIfExists(out); } catch (Exception ignored) {}
                    Exporter.release(out);
                    if (!cancelled) notify(j.label + " failed", e.getMessage(), true);
                }
            }
            j.done = true;
            jobsChanged();
            if (onDone != null) SwingUtilities.invokeLater(() -> onDone.accept(j));
            // Forget finished jobs after a while.
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                @Override public void run() { jobs.remove(j); jobsChanged(); }
            }, 15000);
        });
        return j;
    }
}
