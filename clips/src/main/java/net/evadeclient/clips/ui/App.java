package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.Clip;
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
import javax.swing.SwingUtilities;
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
    public Consumer<Clip> openEditor = c -> {};
    /** Brings the main window forward (set by the window). */
    public Runnable showWindow = () -> {};
    /** The game running right now (null if none), checked every few seconds. */
    public volatile String currentGame;
    public final net.evadeclient.clips.events.AutoClipper autoClipper;
    public final net.evadeclient.clips.events.Cs2Integration cs2;
    public final net.evadeclient.clips.events.LeagueIntegration league;
    public final net.evadeclient.clips.core.CloudShare cloud;
    private volatile boolean autoStarted;
    public Toast toast;

    public App(Settings settings) {
        this.settings = settings;
        Ffmpeg.configure(settings.ffmpegPath);
        library = new Library(settings);
        engine = new CaptureEngine(settings, library);
        engine.onStatus(s -> status = s);
        autoClipper = new net.evadeclient.clips.events.AutoClipper((secs, title, game, tags) -> {
            if (!engine.isLive()) return;
            saveClipNow(secs, 0, title, game, tags);
        });
        cs2 = new net.evadeclient.clips.events.Cs2Integration(autoClipper, settings.gsiToken);
        cloud = new net.evadeclient.clips.core.CloudShare(settings);
        autoClipper.onMarker = label -> { if (engine.isLive()) engine.addMarker(label); };
        library.onAdded(c -> {
            boolean sync = switch (settings.cloudSync) {
                case "all" -> true;
                case "favorites" -> c.kind == Clip.Kind.EXPORT || c.favorite;
                default -> false;
            };
            if (sync && cloud.configured()) shareClip(c, false);
        });
        league = new net.evadeclient.clips.events.LeagueIntegration(autoClipper);
        applyEventSettings();
    }

    public void applyEventSettings() {
        autoClipper.before = settings.autoClipBefore;
        autoClipper.after = settings.autoClipAfter;
        cs2.headshotsOnly = settings.cs2HeadshotsOnly;
        cs2.deaths = settings.clipDeaths;
        league.deaths = settings.clipDeaths;
    }

    private net.evadeclient.clips.core.VoiceCommands voice;

    /** Starts or stops listening for voice commands to match the setting. Returns an error message or null. */
    public synchronized String applyVoice() {
        try {
            if (!settings.voiceCommands) {
                if (voice != null) { voice.close(); voice = null; }
                return null;
            }
            if (!net.evadeclient.clips.core.VoiceCommands.ready()) return "The speech engine isn't downloaded yet.";
            if (voice == null) voice = new net.evadeclient.clips.core.VoiceCommands(net.evadeclient.clips.core.VoiceCommands.modelDir());
            voice.start(settings.micDevice, cmd -> {
                switch (cmd) {
                    case CLIP -> saveClip(0, null, null, "voice");
                    case RECORD_START -> { if (!engine.isRecording()) toggleRecording(); }
                    case RECORD_STOP -> { if (engine.isRecording()) toggleRecording(); }
                    case MARKER -> { engine.addMarker("Voice"); notify("Marker added", null, false); }
                }
            });
            return null;
        } catch (Throwable t) {
            return "Voice commands couldn't start: " + t.getMessage();
        }
    }

    public void start() {
        library.load();
        String voiceError = applyVoice();
        if (voiceError != null && settings.voiceCommands) System.err.println("[clips] " + voiceError);
        Thread watcher = new Thread(this::watchGames, "game-watcher");
        watcher.setDaemon(true);
        watcher.start();
        applyHotkeys();
        applyServer();
        if (settings.replayEnabled && settings.replayOnLaunch && Ffmpeg.available()) {
            try { engine.start(); } catch (Exception e) { notify("Couldn't start capture", e.getMessage(), true); }
        }
    }

    /** Names clips after the running game and runs instant replay only while one is open. */
    private void watchGames() {
        while (true) {
            try {
                String game = net.evadeclient.clips.core.GameDetector.detect().map(net.evadeclient.clips.core.GameDetector.Game::name).orElse(null);
                String before = currentGame;
                currentGame = game;
                if (game != null && !game.equals(before)) {
                    if (settings.autoReplayForGames && settings.replayEnabled && !engine.isLive() && Ffmpeg.available()) {
                        engine.start();
                        autoStarted = true;
                        notify(game + " detected", "Instant replay is on · " + settings.hotkeySaveClip + " saves a clip", false);
                    }
                } else if (game == null && before != null && autoStarted && !engine.isRecording() && !settings.replayOnLaunch) {
                    engine.stop();
                    autoStarted = false;
                }
            } catch (Exception e) {
                System.err.println("[clips] game watcher: " + e.getMessage());
            }
            // League's Live Client API is only up during a match; poll it while League is open.
            for (int i = 0; i < 3; i++) {
                if (settings.leagueEvents && net.evadeclient.clips.events.LeagueIntegration.GAME.equals(currentGame)) league.poll();
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            if (!net.evadeclient.clips.events.LeagueIntegration.GAME.equals(currentGame)) league.reset();
        }
    }

    /** Game name for a new clip: what the caller said, else the running game, else the default. */
    String gameFor(String asked) {
        if (asked != null && !asked.isBlank()) return asked;
        return currentGame != null ? currentGame : settings.defaultGame;
    }

    public void shutdown() {
        autoClipper.shutdown();
        if (voice != null) voice.close();
        engine.shutdown();
        hotkeys.stop();
        server.stop();
        library.shutdown();
        for (ExportJob j : jobs) j.job.cancel();
    }

    public void applyHotkeys() {
        hotkeys.clear();
        if (!hotkeys.start()) return;
        hotkeys.bind(settings.hotkeySaveClip, () -> saveClip(0, null, null, null));
        hotkeys.bind(settings.hotkeyRecord, this::toggleRecording);
        hotkeys.bind(settings.hotkeyMarker, this::marker);
    }

    public void applyServer() {
        server.stop();
        if (!settings.controlServer) return;
        try {
            server.start(settings.controlPort, new ControlServer.Handler() {
                @Override public Map<String, Object> status() {
                    CaptureEngine.Status s = status;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("app", "Evade Clips");
                    m.put("state", s.state().name().toLowerCase());
                    m.put("recording", s.recording());
                    m.put("buffered", Math.round(s.bufferedSeconds()));
                    m.put("replaySeconds", settings.replaySeconds);
                    return m;
                }
                @Override public Map<String, Object> clip(int seconds, int after, String title, String game, String tag) throws Exception {
                    Clip c = saveClipNow(seconds, after, title, game, tag);
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
                @Override public boolean gameState(String game, String body) {
                    return !settings.cs2Events || cs2.handle(body);
                }
            });
        } catch (Exception e) {
            System.err.println("[clips] control API unavailable on port " + settings.controlPort + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ capture actions

    public void toggleReplay(boolean on) {
        settings.replayEnabled = on;
        settings.save();
        new Thread(() -> {
            try {
                if (on) engine.start();
                else if (!engine.isRecording()) engine.stop();
            } catch (Exception e) { notify("Couldn't start capture", e.getMessage(), true); }
        }, "replay-toggle").start();
    }

    /** Async save from a hotkey/button; reports with a toast. */
    public void saveClip(int seconds, String title, String game, String tag) {
        new Thread(() -> {
            try { saveClipNow(seconds, settings.replayAfterSeconds, title, game, tag); }
            catch (Exception e) { notify("Clip not saved", e.getMessage(), true); }
        }, "save-clip").start();
    }

    public Clip saveClipNow(int seconds, int after, String title, String game, String tag) throws Exception {
        if (!engine.isLive()) throw new Exception("Instant replay is off. Turn it on in Record.");
        if (after > 0 && settings.showToast && toast != null) SwingUtilities.invokeLater(() -> toast.show("Saving…", "Capturing " + after + " more seconds", false));
        Clip c = engine.saveReplay(seconds > 0 ? seconds : settings.replaySeconds, after, title, gameFor(game));
        if (tag != null && !tag.isBlank()) {
            for (String t : tag.split(",")) if (!t.isBlank() && !c.tags.contains(t.trim())) c.tags.add(t.trim());
            library.update(c);
        }
        notify("Clip saved", (c.title == null ? "" : c.title + " · ") + Theme.time(c.duration), false);
        return c;
    }

    public void toggleRecording() {
        new Thread(() -> {
            try {
                if (engine.isRecording()) stopRecordingNow();
                else startRecording(null);
            } catch (Exception e) { notify("Recording failed", e.getMessage(), true); }
        }, "record-toggle").start();
    }

    public void startRecording(String game) throws Exception {
        engine.startRecording(gameFor(game));
        notify("Recording", "Press " + settings.hotkeyRecord + " to stop", false);
    }

    public Clip stopRecordingNow() throws Exception {
        Clip c = engine.stopRecording();
        if (c != null) notify("Recording saved", Theme.time(c.duration) + " · " + Theme.size(c.size), false);
        if (!settings.replayEnabled && !engine.isRecording()) engine.stop();
        if (c != null && settings.autoHighlights && c.duration >= 45 && c.hasAudio) findHighlights(c, true);
        return c;
    }

    /** Scans a clip for its loudest moments and adds them as markers (in the background). */
    public void findHighlights(Clip c, boolean quiet) {
        Thread t = new Thread(() -> {
            try {
                List<Highlights.Moment> m = Highlights.detect(c.path(library.root()), c.duration, 0, null);
                for (Highlights.Moment x : m) {
                    boolean dup = false;
                    for (double have : c.markers) if (Math.abs(have - x.time()) < 2) dup = true;
                    if (!dup) c.markers.add(x.time());
                }
                c.markers.sort(Double::compare);
                if (!m.isEmpty() && !c.tags.contains("highlights")) c.tags.add("highlights");
                library.update(c);
                if (!quiet || !m.isEmpty()) notify(m.isEmpty() ? "No highlights found" : "Found " + m.size() + " highlight" + (m.size() == 1 ? "" : "s"),
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
        java.nio.file.Files.createDirectories(dir);
        Path out = Library.unique(dir, Library.safe(c.title == null ? "Frame" : c.title) + " " + String.format("%.2f", t).replace('.', '_') + ".png");
        Ffmpeg.run(List.of("-ss", Library.fmt(t), "-i", c.path(library.root()).toString(), "-frames:v", "1", out.toString()));
        return out;
    }

    public void marker() {
        engine.addMarker();
        notify("Marker added", null, false);
    }

    public void notify(String title, String body, boolean error) {
        if (error) System.err.println("[clips] " + title + ": " + body);
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

        ExportJob(Clip clip, Exporter.Preset preset, Path out) { this.clip = clip; this.preset = preset; this.out = out; this.label = preset.label; }
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

    /** Joins clips (in the given order, each with its edits) into one montage video. */
    public ExportJob montage(List<Clip> clips, String title, Consumer<ExportJob> onDone) {
        Path out = Library.unique(library.folder(Clip.Kind.EXPORT), Library.safe(title) + ".mp4");
        Exporter.Options o = new Exporter.Options();
        o.preset = Exporter.Preset.YOUTUBE;
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
     * Uploads a clip and (with {@code copy}) puts its link on the clipboard, ready to paste into Discord.
     */
    public void shareClip(Clip c, boolean copy) {
        if (!cloud.configured()) {
            notify("Sharing isn't set up", "Add a share server in Settings → Sharing", true);
            return;
        }
        ExportJob job = new ExportJob(c, Exporter.Preset.SOURCE, library.metaDir().resolve("share-" + c.id + ".mp4"));
        job.label = "Share";
        runJob(job, j -> {
            // Let the thumbnail finish so the link preview has a picture.
            for (int i = 0; i < 50 && !java.nio.file.Files.exists(library.thumb(c)); i++) Thread.sleep(200);
            String url = cloud.share(c, library, p -> { j.progress = p; jobsChanged(); }, j.job);
            if (copy) {
                SwingUtilities.invokeLater(() -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(url), null));
                notify("Link copied", url, false);
            }
            return null;
        }, null);
    }

    interface JobBody { Clip run(ExportJob j) throws Exception; }

    private ExportJob runJob(ExportJob j, JobBody body, Consumer<ExportJob> onDone) {
        Path out = j.out;
        jobs.add(j);
        jobsChanged();
        exports.submit(() -> {
            j.state = "Exporting";
            jobsChanged();
            try {
                j.result = body.run(j);
                j.state = "Done";
                if (!"Shrink".equals(j.label) && !"Share".equals(j.label)) notify("Export finished", out.getFileName().toString(), false);
            } catch (Exception e) {
                j.state = j.job.cancelled() ? "Cancelled" : "Failed: " + e.getMessage();
                try { java.nio.file.Files.deleteIfExists(out); } catch (Exception ignored) {}
                if (!j.job.cancelled()) notify(j.label + " failed", e.getMessage(), true);
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
