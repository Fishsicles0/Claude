package net.evadeclient.clips.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One ffmpeg capture process feeds both features:
 * <ul>
 *   <li><b>Instant replay</b>: ffmpeg writes one-second MPEG-TS segments into a buffer folder; old ones are
 *       deleted, so "save the last 30 seconds" is just joining the newest segments. No re-encode, so saving
 *       is instant and costs nothing while you play.</li>
 *   <li><b>Recording</b>: from the moment you press record, each finished segment is appended to the recording
 *       file. MPEG-TS can be joined byte-for-byte, so the file on disk is always playable even if the PC
 *       crashes. On stop it is remuxed to MP4.</li>
 * </ul>
 * Starting a recording never restarts capture, so the replay buffer keeps running and the two never drop frames.
 * Segment numbers keep counting up across capture restarts, so a recording carries on through one.
 */
public final class CaptureEngine {
    public enum State { OFF, STARTING, LIVE, ERROR }

    /**
     * What the engine is doing. {@code lastLog} is the full ffmpeg log of the last capture or recording failure
     * (empty when there was none), for a "details" view next to the short {@code message}.
     */
    public record Status(State state, boolean recording, double bufferedSeconds, double recordingSeconds,
                         String message, String encoder, String lastLog) {
        public Status(State state, boolean recording, double bufferedSeconds, double recordingSeconds, String message, String encoder) {
            this(state, recording, bufferedSeconds, recordingSeconds, message, encoder, "");
        }
    }

    private record Segment(int index, String name, double start, double end) {
        double length() { return end - start; }
    }

    /** A finished piece of a recording whose own encoder stopped; the pieces are joined when it stops. */
    private record Part(Path ts, long startWall) {}

    private static final String CAPTURE_PID = "ffmpeg.pid", RECORD_PID = "ffmpeg-rec.pid";
    static final String WAYLAND_MESSAGE = "Screen capture on Linux needs an X11 session; on Wayland clips will be black. Choose the 'on Xorg' session at login.";
    static final String SLOW_MESSAGE = "Your PC can't keep up: lower quality, fps or resolution";
    static final String PENDING_MESSAGE = "New settings apply when the recording stops";

    private final Settings settings;
    private final Library library;
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "capture-janitor");
        t.setDaemon(true);
        return t;
    });

    private Process process;
    /** Where the running capture writes its segments (normally {@link #bufferRoot}; a subfolder if that was locked). */
    private Path bufferDir;
    /** library.bufferDir() at the last start: holds the pid files. */
    private Path bufferRoot;
    private final List<Segment> segments = new ArrayList<>();
    private int csvLinesRead;
    private long csvBytesRead;
    /** The number ffmpeg gives its next segment. Never goes back, so numbers stay unique across restarts. */
    private int nextSegmentIndex;
    private long lastSegmentWall;
    private volatile long startedWall;
    private String encoder = "";
    private int outW, outH;
    /** Audio track names of the running capture ("Game", "Mic", "Voice chat"); MPEG-TS can't store them. */
    private List<String> trackTitles = List.of();
    /** The Minecraft window being captured (Windows: its monitor, or the window itself as the fallback), or null. */
    private CaptureSource.Window window;
    /** The running capture uses Desktop Duplication (Windows), so if it fails, GDI window capture takes over. */
    private boolean ddagrab;
    /** The running capture grabs a Linux screen under Wayland (black frames). */
    private boolean waylandScreen;

    /**
     * The encoder for live capture: the user's choice if it works, otherwise the best one for the chosen codec.
     * "auto" uses HEVC (about 40% smaller files) when a GPU can encode it, else H.264.
     */
    public static String previewEncoder(Settings s) { return pickEncoder(s); }

    static String pickEncoder(Settings s) {
        String codec = switch (s.codec) {
            case "hevc" -> Ffmpeg.captureEncoders("hevc").isEmpty() ? "h264" : "hevc";
            case "h264" -> "h264";
            default -> Ffmpeg.hardwareHevc(true) ? "hevc" : "h264";
        };
        List<String> usable = Ffmpeg.captureEncoders(codec);
        if (!s.encoder.isBlank() && usable.contains(s.encoder)) return s.encoder;
        return usable.get(0);
    }

    /** Bitrate ceiling currently in use (bits/s), or 0 when capture is off. */
    public synchronized long capBits() { return encoder.isEmpty() ? 0 : Ffmpeg.liveCapBits(encoder, settings.quality, settings.fps, outW, outH); }
    private volatile State state = State.OFF;
    private volatile String message = "";
    /** A message that survives state changes until this time (0 = none). */
    private long noticeUntil;
    /** The last status line the engine chose by itself (Wayland, slow PC, pending settings). */
    private String autoMessage = "";
    /** Full log of the last failure, for {@link Status#lastLog()}. */
    private String failureLog = "";
    private final StringBuilder log = new StringBuilder();
    private boolean stopping;
    private int restarts;
    /** Settings changed during a recording: capture restarts once it stops. */
    private boolean pendingRestart;
    private volatile boolean closed;

    // keeping up: instantaneous speed from ffmpeg's -progress output (guarded by `progress`)
    private int captureGeneration;
    private final ArrayDeque<long[]> progress = new ArrayDeque<>(); // {wall ms, output µs}
    private long slowSince;
    private volatile boolean slow;

    // recording
    private boolean recording;
    /** stopRecording() is finishing up; the janitor leaves the recording alone. */
    private boolean recordingStopping;
    private Path recordingTs;
    private int recordNextIndex;
    /** The segment that was being written when recording started. */
    private int recordStartIndex;
    private long recordingStartWall;
    /** Start of the part being written now (later than recordingStartWall after the recording encoder stopped). */
    private long partStartWall;
    private final List<Part> parts = new ArrayList<>();
    private final List<Double> recordingMarkers = new ArrayList<>();
    private String recordingGame;
    /** Wall-clock marker presses, for markers inside saved replays. */
    private final List<Long> markerWalls = new ArrayList<>();
    /** Label for each marker (by wall time), e.g. "Kill Steve", "ACE", "Voice". */
    private final java.util.Map<Long, String> markerNames = new java.util.HashMap<>();
    private final java.util.Map<Double, String> recordingLabels = new java.util.HashMap<>();
    /** Post-roll lengths of replay saves that are waiting for footage; the buffer keeps enough for the longest. */
    private final List<Integer> postRolls = new ArrayList<>();

    public CaptureEngine(Settings settings, Library library) {
        this.settings = settings;
        this.library = library;
        timer.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    public void onStatus(Consumer<Status> l) { listeners.add(l); }

    public synchronized boolean isLive() { return state == State.LIVE || state == State.STARTING; }
    public synchronized boolean isRecording() { return recording; }

    // ------------------------------------------------------------------ lifecycle

    /** Starts capture (the replay buffer). Safe to call when already running. */
    public void start() throws IOException {
        synchronized (this) {
            if (closed || (process != null && process.isAlive())) return;
        }
        if (!Ffmpeg.available()) throw new IOException("ffmpeg was not found. Install it or set its location in Settings.");
        // The slow parts (encoder probe, audio devices, finding the Minecraft window) run before taking the lock, so
        // the Record page never waits on them.
        String enc = pickEncoder(settings);
        CaptureSource.Window title = Ffmpeg.WINDOWS && !settings.source.startsWith("test") ? CaptureSource.findWindow(CaptureSource.MINECRAFT_WINDOW) : null;
        CaptureSource.Inputs in = CaptureSource.build(settings, enc, title);
        synchronized (this) {
            if (closed || (process != null && process.isAlive())) return;
            launch(enc, title, in);
        }
    }

    private void launch(String enc, CaptureSource.Window title, CaptureSource.Inputs in) throws IOException {
        // A recording made from the buffer keeps everything the previous ffmpeg finished (and the piece it was
        // writing) before the folder is cleared.
        if (recording && recProcess == null && bufferDir != null) {
            try { readSegmentList(); } catch (IOException ignored) {}
            appendToRecording(true);
            nextSegmentIndex = Math.max(nextSegmentIndex, recordNextIndex);
        }
        Path root = library.bufferDir();
        Files.createDirectories(root);
        bufferRoot = root;
        endStaleCaptures(root);
        bufferDir = clearBuffer(root);
        cleanOldReplayTemps();
        segments.clear();
        csvLinesRead = 0;
        csvBytesRead = 0;
        synchronized (log) { log.setLength(0); }
        stopping = false;
        synchronized (progress) {
            captureGeneration++;
            progress.clear();
            slowSince = 0;
            slow = false;
        }

        encoder = enc;
        window = title;
        ddagrab = in.ddagrab();
        outW = in.outW();
        outH = in.outH();
        trackTitles = new ArrayList<>(in.audioTitles());
        List<String> args = captureArgs(in, settings.replayBitrateMbps, true);
        args.addAll(List.of("-f", "segment", "-segment_time", "1", "-segment_format", "mpegts",
                "-segment_list", "segments.csv", "-segment_list_type", "csv", "-segment_list_flags", "+live",
                "-segment_start_number", String.valueOf(nextSegmentIndex),
                "-break_non_keyframes", "0", "seg%06d.ts"));

        ProcessBuilder pb = Ffmpeg.interactiveBuilder(args).directory(bufferDir.toFile());
        process = CaptureProcessGuard.start(pb);
        writePid(CAPTURE_PID, process);
        int generation = captureGeneration;
        Ffmpeg.drain(process.getErrorStream(), log);
        Ffmpeg.drainLines(process.getInputStream(), line -> onProgress(generation, line));
        startedWall = lastSegmentWall = System.currentTimeMillis();
        state = State.STARTING;
        noticeUntil = 0;
        waylandScreen = !settings.source.startsWith("test") && CaptureSource.linuxWayland();
        message = waylandScreen ? WAYLAND_MESSAGE : "Starting capture";
        autoMessage = waylandScreen ? WAYLAND_MESSAGE : "";
        fire();
    }

    /**
     * Inputs, filter graph, encoder (with a bitrate ceiling: {@code mbps} > 0, else from the quality level).
     * {@code withProgress} adds ffmpeg's progress report on stdout, used to tell when the PC can't keep up.
     */
    private List<String> captureArgs(CaptureSource.Inputs in, double mbps, boolean withProgress) {
        List<String> args = new ArrayList<>(List.of("-loglevel", "warning"));
        if (withProgress) args.addAll(List.of("-progress", "pipe:1"));
        // -thread_queue_size applies to the next input. A short queue on the video grabber means a lagging
        // encoder makes it skip frames, instead of buffering seconds (and gigabytes) of raw frames that leave
        // replays ending before the key press. Audio packets are tiny, so audio gets a deep queue.
        // Not for filter-graph inputs (-f lavfi, e.g. Windows ddagrab): newer ffmpeg refuses the option there
        // ("cannot be applied to input url ddagrab=...") and the capture never starts. And not at all with an ffmpeg
        // that refuses it on inputs altogether (checked once per ffmpeg).
        boolean queue = Ffmpeg.inputThreadQueue();
        int input = 0;
        String format = null;
        for (String v : in.args()) {
            if (v.equals("-i")) {
                boolean filterInput = "lavfi".equals(format);
                int n = input++;
                if (queue && !filterInput) args.addAll(List.of("-thread_queue_size", n == 0 ? "16" : "512"));
                format = null;
            }
            if (format == null && !args.isEmpty() && "-f".equals(args.get(args.size() - 1))) format = v;
            args.add(v);
        }
        // The replay buffer also measures each audio track (peak level, ten times a second) for the level meters on
        // the Record page: a copy of each track is split off, measured and dropped; what's recorded is unchanged.
        String fc = in.filterComplex();
        List<String> maps = new ArrayList<>(in.audioLabels());
        if (withProgress) {
            StringBuilder m = new StringBuilder(fc);
            for (int i = 0; i < maps.size(); i++) {
                String l = maps.get(i), out = l.replace("]", "o]"), meter = "[meter" + i + "]";
                m.append(";").append(l).append("asplit=2").append(out).append(meter).append(";").append(meter)
                        .append("asetnsamples=n=4800:p=0,astats=metadata=1:reset=1,")
                        .append("ametadata=mode=print:key=lavfi.astats.Overall.Peak_level:direct=1:file=meter").append(i).append(".txt,anullsink");
                maps.set(i, out);
            }
            fc = m.toString();
            meterTracks = new ArrayList<>(in.audioTitles());
        }
        args.addAll(List.of("-filter_complex", fc, "-map", "[v]"));
        for (String l : maps) args.addAll(List.of("-map", l));
        args.addAll(Ffmpeg.liveEncoderArgs(encoder, settings.quality, settings.fps, in.outW(), in.outH(), (long) (mbps * 1_000_000)));
        args.addAll(List.of("-r", String.valueOf(settings.fps)));
        if (in.hasAudio()) args.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"));
        for (int i = 0; i < in.audioTitles().size(); i++) args.addAll(List.of("-metadata:s:a:" + i, "title=" + in.audioTitles().get(i)));
        return args;
    }

    private volatile List<String> meterTracks = List.of();

    /** One audio track's current level, for the meters: its name and peak in dBFS ({@code -inf} when silent). */
    public record Level(String track, double peakDb) {}

    /**
     * The live level of each audio track being captured, newest reading first per track; empty while capture is
     * off. A track that hasn't reported for over a second and a half (its source stalled) is left out.
     */
    public List<Level> levels() {
        Path dir = bufferDir;
        List<String> tracks = meterTracks;
        if (dir == null || process == null || tracks.isEmpty()) return List.of();
        List<Level> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < tracks.size(); i++) {
            Path f = dir.resolve("meter" + i + ".txt");
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f.toFile(), "r")) {
                if (now - Files.getLastModifiedTime(f).toMillis() > 1500) continue;
                long len = r.length();
                int n = (int) Math.min(len, 512);
                byte[] b = new byte[n];
                r.seek(len - n);
                r.readFully(b);
                String tail = new String(b, java.nio.charset.StandardCharsets.US_ASCII);
                int at = tail.lastIndexOf("Peak_level=");
                if (at < 0) continue;
                String v = tail.substring(at + 11).split("\\s")[0].trim();
                double db = v.startsWith("-inf") || v.isEmpty() ? Double.NEGATIVE_INFINITY : Double.parseDouble(v);
                out.add(new Level(tracks.get(i), db));
            } catch (IOException | NumberFormatException e) {
                // not written yet, or mid-line: next time
            }
        }
        return out;
    }

    /** Separate encode for recordings with their own bitrate (null when recordings share the replay buffer's). */
    private Process recProcess;
    private final StringBuilder recLog = new StringBuilder();
    /** The recording encoder has written data (until then it may still fall back to the buffer). */
    private boolean recVerified;
    private long recStartedWall;

    /** Stops capture. A running recording is finished and saved first. */
    public void stop() {
        synchronized (this) { pendingRestart = false; }
        if (isRecording()) {
            try { stopRecording(); } catch (IOException e) { System.err.println("[clips] " + e.getMessage()); }
        }
        Process p;
        synchronized (this) {
            stopping = true;
            p = process;
            process = null;
            state = State.OFF;
            message = "";
            noticeUntil = 0;
            autoMessage = "";
        }
        quit(p);
        synchronized (this) { if (process == null) deletePid(CAPTURE_PID); }
        fire();
    }

    /**
     * Applies new settings by restarting capture. During a recording nothing is restarted (that would end the
     * recording): the new settings apply as soon as the recording stops.
     */
    public void restart() throws IOException {
        synchronized (this) {
            if (recording) {
                pendingRestart = true;
                // Answer the change right away; afterwards it stays as the status line until the recording stops.
                notice(PENDING_MESSAGE, 5000);
            }
        }
        if (isRecording()) { fire(); return; }
        boolean wasLive = isLive();
        stop();
        if (wasLive) start();
    }

    /** Runs a restart that was put off for a recording (in the background, so saving isn't held up). */
    private void applyPendingRestart() {
        synchronized (this) {
            if (!pendingRestart || closed) return;
            pendingRestart = false;
            if (message.equals(PENDING_MESSAGE)) { message = ""; autoMessage = ""; }
        }
        Thread t = new Thread(() -> {
            try {
                Process p;
                boolean wasLive;
                synchronized (this) {
                    // A new recording started meanwhile: wait for that one to stop.
                    if (recording) { pendingRestart = true; return; }
                    wasLive = isLive();
                    stopping = true;
                    p = process;
                    process = null;
                    state = State.OFF;
                    message = "";
                    noticeUntil = 0;
                }
                quit(p);
                synchronized (this) { if (process == null) deletePid(CAPTURE_PID); }
                fire();
                if (wasLive && settings.replayEnabled) start();
            } catch (IOException e) {
                synchronized (this) {
                    state = State.ERROR;
                    message = e.getMessage();
                }
                fire();
            }
        }, "capture-restart");
        t.setDaemon(true);
        t.start();
    }

    private static void quit(Process p) {
        if (p == null) return;
        try {
            OutputStream o = p.getOutputStream();
            o.write('q');
            o.flush();
            o.close();
        } catch (IOException ignored) {}
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) { p.destroyForcibly(); }
    }

    public void shutdown() {
        closed = true;
        stop();
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ leftovers from a crash

    /** Notes which ffmpeg is capturing, so a later start can end it if the app dies without stopping it. */
    private void writePid(String name, Process p) {
        if (bufferRoot == null) return;
        try {
            long started = p.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
            Files.writeString(bufferRoot.resolve(name), p.pid() + " " + started + "\n");
        } catch (Exception e) {
            System.err.println("[clips] couldn't write " + name + ": " + e.getMessage());
        }
    }

    private void deletePid(String name) {
        if (bufferRoot == null) return;
        try { Files.deleteIfExists(bufferRoot.resolve(name)); } catch (IOException ignored) {}
    }

    private boolean ours(long pid) {
        return (process != null && process.pid() == pid) || (recProcess != null && recProcess.pid() == pid);
    }

    /**
     * Ends an ffmpeg that an earlier run of the app left capturing (it crashed or was killed). Only a process
     * that is still the same ffmpeg (same program, same start time) is touched.
     */
    private void endStaleCaptures(Path root) {
        for (String name : List.of(CAPTURE_PID, RECORD_PID)) {
            Path f = root.resolve(name);
            try {
                if (!Files.isRegularFile(f)) continue;
                String[] p = Files.readString(f).trim().split("\\s+");
                long pid = Long.parseLong(p[0]);
                long started = p.length > 1 ? Long.parseLong(p[1]) : 0;
                if (ours(pid)) continue;
                ProcessHandle.of(pid).ifPresent(h -> {
                    if (!h.isAlive() || started == 0) return;
                    String cmd = h.info().command().orElse("");
                    long st = h.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
                    String ff = Ffmpeg.ffmpeg();
                    boolean isFfmpeg = cmd.toLowerCase(Locale.ROOT).contains("ffmpeg") || (ff != null && cmd.equalsIgnoreCase(ff));
                    if (!isFfmpeg || st == 0 || Math.abs(st - started) > 2000) return;
                    System.err.println("[clips] ending ffmpeg " + pid + " left running by an earlier session");
                    h.destroyForcibly();
                    try { h.onExit().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
                });
                Files.deleteIfExists(f);
            } catch (Exception e) {
                System.err.println("[clips] checking " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * Empties the buffer folder for a new capture. If something still holds a file there (a leftover ffmpeg
     * that couldn't be identified), it tries once more and then captures into a fresh subfolder instead.
     */
    private Path clearBuffer(Path root) throws IOException {
        if (deleteContents(root)) return root;
        endStaleCaptures(root);
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (deleteContents(root)) return root;
        Path fresh = root.resolve("run-" + System.currentTimeMillis());
        Files.createDirectories(fresh);
        System.err.println("[clips] the replay buffer folder is in use; capturing into " + fresh);
        return fresh;
    }

    /** Deletes the buffer's files (pid files stay) and old subfolders; false if a file there couldn't go. */
    private static boolean deleteContents(Path root) {
        boolean ok = true;
        try (var s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".pid")) continue;
                if (Files.isDirectory(p)) { deleteTree(p); continue; } // an old fallback folder; fine if it lingers
                try { Files.deleteIfExists(p); } catch (IOException e) { ok = false; }
            }
        } catch (IOException e) {
            return false;
        }
        return ok;
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Collections.reverseOrder())::iterator) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /** Joined replays waiting to be remuxed live next to the buffer; remove any a crash left behind. */
    private void cleanOldReplayTemps() {
        try (var s = Files.list(library.metaDir())) {
            long cutoff = System.currentTimeMillis() - 10 * 60_000;
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith("replay") && n.endsWith(".ts") && Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ periodic work

    /** Whether an ffmpeg log shows Desktop Duplication (or the copy of its frames) failing. */
    public static boolean ddagrabError(String log) {
        for (String line : log.split("\r?\n")) {
            String l = line.toLowerCase(java.util.Locale.ROOT);
            if ((l.contains("ddagrab") || l.contains("hwdownload") || l.contains("dxgi") || l.contains("duplicat"))
                    && (l.contains("error") || l.contains("fail") || l.contains("invalid") || l.contains("unsupported") || l.contains("not ")))
                return true;
        }
        return false;
    }

    private void tick() {
        try {
            synchronized (this) {
                if (process == null) return;
                readSegmentList();
                long now = System.currentTimeMillis();
                if (!process.isAlive() && !stopping) {
                    boolean neverLive = state == State.STARTING;
                    String full = log.toString();
                    failureLog = full;
                    String why = Ffmpeg.explain(full, trackTitles);
                    deletePid(CAPTURE_PID);
                    process = null;
                    state = State.ERROR;
                    noticeUntil = 0;
                    message = why.isBlank() ? "Capture stopped unexpectedly" : why;
                    System.err.println("[clips] capture died: " + Ffmpeg.tail(full));
                    if (ddagrab && neverLive && ddagrabError(full)) {
                        // Desktop Duplication doesn't work on this PC (it never produced a frame): record the
                        // Minecraft window with GDI instead. A failure later on (e.g. a fullscreen mode switch) just
                        // restarts Desktop Duplication below.
                        CaptureSource.ddagrabFailed();
                        System.err.println("[clips] Desktop Duplication failed; capturing the Minecraft window with GDI instead");
                        try {
                            start();
                            notice("Switched to window capture (screen capture isn't available on this PC)", 10_000);
                        } catch (IOException e) {
                            state = State.ERROR;
                            message = e.getMessage();
                        }
                    } else if (restarts++ < 3 && now - startedWall > 5000) {
                        String reason = message;
                        try {
                            start(); // a recording from the buffer keeps going: start() saves what ffmpeg finished
                            notice(recording ? "Capture restarted; recording continues" : "Capture restarted after an error: " + reason, 10_000);
                        } catch (IOException e) {
                            state = State.ERROR;
                            message = e.getMessage();
                        }
                    }
                } else if (state == State.STARTING && !segments.isEmpty()) {
                    state = State.LIVE;
                    if (now >= noticeUntil) { noticeUntil = 0; message = ""; }
                    restarts = 0;
                } else if (state == State.STARTING && now - startedWall > 15000) {
                    message = "Waiting for the first frames... " + Ffmpeg.tail(log.toString());
                }
                if (recording && !recordingStopping) checkRecordingEncoder(now);
                if (recording && recProcess == null) appendToRecording(false);
                prune();
                updateMessage(now);
            }
        } catch (Exception e) {
            System.err.println("[clips] capture tick: " + e);
        }
        fire();
    }

    /** Shows {@code text} for {@code millis}, whatever the state does meanwhile. */
    private void notice(String text, long millis) {
        message = text;
        noticeUntil = System.currentTimeMillis() + millis;
        autoMessage = "";
    }

    /** While live: expires notices and shows the engine's own hints (Wayland, slow PC, pending settings). */
    private void updateMessage(long now) {
        if (state != State.LIVE) return;
        if (noticeUntil != 0) {
            if (now < noticeUntil) return;
            noticeUntil = 0;
            message = "";
        }
        String auto = waylandScreen ? WAYLAND_MESSAGE : slow ? SLOW_MESSAGE : pendingRestart ? PENDING_MESSAGE : "";
        if (message.isEmpty() || message.equals(autoMessage)) {
            message = auto;
            autoMessage = auto;
        }
    }

    /**
     * Watches the recording's own encoder. If it never gets going, the recording switches to the replay
     * buffer's segments from its start (they're kept until the encoder has written something). If it stops
     * later, what it wrote is kept as its own clip and the recording continues from the buffer into a new file.
     */
    private void checkRecordingEncoder(long now) throws IOException {
        if (recProcess == null) return;
        boolean alive = recProcess.isAlive();
        if (!recVerified) {
            long size = size(recordingTs);
            if (alive && size > 0) { recVerified = true; return; }
            if (alive && now - recStartedWall < 4000) return;
            String full = recLog.toString();
            String why = Ffmpeg.explain(full, trackTitles);
            Process dead = recProcess;
            recProcess = null;
            dead.destroyForcibly();
            try { dead.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            deletePid(RECORD_PID);
            failureLog = full;
            try {
                Files.write(recordingTs, new byte[0]); // nothing usable in it
            } catch (IOException e) {
                recordingTs = Library.unique(recordingTs.getParent(), Library.stampName(recordingGame, "ts"));
                Files.createFile(recordingTs);
            }
            recordNextIndex = recordStartIndex;
            System.err.println("[clips] recording encoder didn't start: " + Ffmpeg.tail(full));
            notice("Recording encoder didn't start" + (why.isBlank() ? "" : ": " + why) + " - recording at replay quality", 15_000);
            return;
        }
        if (alive) return;
        String full = recLog.toString();
        String why = Ffmpeg.explain(full, trackTitles);
        recProcess = null;
        deletePid(RECORD_PID);
        failureLog = full;
        System.err.println("[clips] recording encoder stopped: " + Ffmpeg.tail(full));
        // Keep what it wrote, then carry on from the buffer in a new file, starting early enough that nothing is
        // missing. The encoder died at most a tick (250 ms) ago and may have lost a little unwritten output, so
        // its file reliably reaches ~600 ms back. The segment being written now started by lastSegmentWall (when
        // the previous one was seen to close); if that's before then, it's enough, otherwise take one more.
        Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        boolean currentIsEnough = last == null || lastSegmentWall <= now - 600;
        parts.add(new Part(recordingTs, partStartWall));
        recordingTs = Library.unique(recordingTs.getParent(), Library.stampName(recordingGame, "ts"));
        Files.createFile(recordingTs);
        recordNextIndex = currentIsEnough ? nextSegmentIndex : last.index;
        long resumeWall = last == null ? now : lastSegmentWall - 125 - (currentIsEnough ? 0 : Math.round(last.length() * 1000));
        partStartWall = Math.min(now, resumeWall);
        notice("Recording encoder stopped" + (why.isBlank() ? "" : ": " + why) + " - continuing at replay quality", 15_000);
    }

    private static long size(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    /**
     * Reads ffmpeg's progress report. Its own speed= is an average since the start, so the speed over the last
     * few seconds is worked out from out_time instead; below 0.95x for 10 s means frames are falling behind.
     */
    private void onProgress(int generation, String line) {
        if (!line.startsWith("out_time_us=") && !line.startsWith("out_time_ms=")) return;
        long us;
        try { us = Long.parseLong(line.substring(line.indexOf('=') + 1).trim()); } catch (NumberFormatException e) { return; }
        long now = System.currentTimeMillis();
        synchronized (progress) {
            if (generation != captureGeneration) return; // an old capture's report
            if (now - startedWall < 8000) return; // the first seconds are bursty
            long[] newest = progress.peekLast();
            if (newest != null && now - newest[0] < 900) return;
            progress.addLast(new long[]{now, us});
            while (progress.size() > 2 && now - progress.peekFirst()[0] > 6000) progress.removeFirst();
            long[] oldest = progress.peekFirst();
            long span = now - oldest[0];
            if (span < 4000) return;
            double speed = (us - oldest[1]) / 1000.0 / span;
            if (speed < 0.95) {
                if (slowSince == 0) slowSince = now;
                else if (now - slowSince >= 10_000) slow = true;
            } else {
                slowSince = 0;
                slow = false;
            }
        }
    }

    /** Reads new lines of ffmpeg's segment list ("seg000012.ts,11.000,12.000"). */
    private void readSegmentList() throws IOException {
        Path csv = bufferDir.resolve("segments.csv");
        if (!Files.exists(csv)) return;
        long size = Files.size(csv);
        if (size <= csvBytesRead) return;
        String text;
        try (var ch = Files.newByteChannel(csv, StandardOpenOption.READ)) {
            ch.position(csvBytesRead);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (size - csvBytesRead));
            while (buf.hasRemaining() && ch.read(buf) > 0) {}
            text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        }
        int lastNl = text.lastIndexOf('\n');
        if (lastNl < 0) return;
        csvBytesRead += text.substring(0, lastNl + 1).getBytes(StandardCharsets.UTF_8).length;
        for (String line : text.substring(0, lastNl).split("\n")) {
            String[] p = line.trim().split(",");
            if (p.length < 3) continue;
            try {
                String name = p[0];
                int idx = Integer.parseInt(name.replaceAll("\\D", ""));
                segments.add(new Segment(idx, name, Double.parseDouble(p[1]), Double.parseDouble(p[2])));
                nextSegmentIndex = Math.max(nextSegmentIndex, idx + 1);
                lastSegmentWall = System.currentTimeMillis();
                csvLinesRead++;
            } catch (NumberFormatException ignored) {}
        }
    }

    /** Deletes segments that are older than the replay window and no longer needed by a recording. */
    private void prune() {
        if (segments.isEmpty()) return;
        double keep = (settings.replayEnabled ? settings.replaySeconds : 0) + Math.max(settings.replayAfterSeconds, longestPostRoll()) + 3;
        double newest = segments.get(segments.size() - 1).end;
        // A recording from the buffer needs every segment it hasn't copied yet; one with its own encoder needs
        // them only until that encoder is known to work.
        boolean recordingNeedsBuffer = recording && (recProcess == null || !recVerified);
        while (!segments.isEmpty()) {
            Segment s = segments.get(0);
            if (newest - s.end < keep) break;
            if (recordingNeedsBuffer && s.index >= recordNextIndex) break;
            try { Files.deleteIfExists(bufferDir.resolve(s.name)); } catch (IOException ignored) {}
            segments.remove(0);
        }
        long cutoff = System.currentTimeMillis() - (long) (keep * 1000) - 2000;
        markerWalls.removeIf(t -> t < cutoff);
        markerNames.keySet().removeIf(t -> t < cutoff);
    }

    private int longestPostRoll() {
        int max = 0;
        for (int p : postRolls) max = Math.max(max, p);
        return max;
    }

    private synchronized double buffered() {
        if (segments.isEmpty()) return 0;
        double total = segments.get(segments.size() - 1).end - segments.get(0).start;
        return Math.min(settings.replayEnabled ? settings.replaySeconds : 0, total);
    }

    /** Seconds of gameplay in the replay buffer right now. */
    public synchronized double bufferedSeconds() { return buffered(); }

    private void fire() {
        Status s;
        synchronized (this) {
            s = new Status(state, recording, buffered(),
                    recording ? (System.currentTimeMillis() - recordingStartWall) / 1000.0 : 0, message, encoder, failureLog);
        }
        for (Consumer<Status> l : listeners) {
            try { l.accept(s); } catch (Exception e) { System.err.println("[clips] status listener: " + e); }
        }
    }

    // ------------------------------------------------------------------ instant replay

    /**
     * Saves the last {@code seconds} of gameplay as a new clip. Blocks for the time it takes to copy the
     * segments (well under a second for a 30 s clip).
     */
    public Clip saveReplay(int seconds, String title, String game) throws IOException {
        return saveReplay(seconds, 0, title, game);
    }

    /**
     * Saves the last {@code seconds}, plus {@code afterSeconds} more that are still to come (post-roll), so the
     * clip includes what happens right after you press the key.
     */
    public Clip saveReplay(int seconds, int afterSeconds, String title, String game) throws IOException {
        // never more than the replay length: the buffer keeps a few seconds of slack that belong to no clip
        if (settings.replayEnabled && settings.replaySeconds > 0) seconds = Math.min(seconds, settings.replaySeconds);
        synchronized (this) {
            if (process == null || segments.isEmpty())
                throw new IOException(process == null ? "Instant replay is off" : "Replay buffer is still filling");
            // While we wait for the post-roll, the buffer must not drop the start of this clip.
            if (afterSeconds > 0) postRolls.add(afterSeconds);
        }
        Path joined;
        double total = 0;
        List<Double> markers = new ArrayList<>();
        java.util.Map<Double, String> labels = new java.util.HashMap<>();
        try {
            if (afterSeconds > 0) {
                try { Thread.sleep(afterSeconds * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                seconds += afterSeconds;
            }
            // Let ffmpeg finish the segment it's writing (at most ~1 s) so the clip runs right up to the key press
            // without a half-written frame at the end.
            awaitNextSegment();
            synchronized (this) {
                if (segments.isEmpty()) throw new IOException("Replay buffer is empty");
                List<Segment> pick = new ArrayList<>();
                for (int i = segments.size() - 1; i >= 0 && total < seconds - 0.5; i--) {
                    pick.add(0, segments.get(i));
                    total += segments.get(i).length();
                }
                // Next to the buffer, not in it: a capture restart clears the buffer folder.
                joined = Files.createTempFile(library.metaDir(), "replay", ".ts");
                try (OutputStream out = Files.newOutputStream(joined)) {
                    for (Segment s : pick) Files.copy(bufferDir.resolve(s.name), out);
                } catch (IOException e) {
                    Files.deleteIfExists(joined);
                    throw e;
                }
                // The clip ends at lastSegmentWall (when the newest segment closed).
                for (long w : markerWalls) {
                    double fromEnd = (lastSegmentWall - w) / 1000.0;
                    if (fromEnd >= 0 && fromEnd <= total) {
                        double at = Math.max(0, total - fromEnd);
                        markers.add(at);
                        if (markerNames.containsKey(w)) labels.put(at, markerNames.get(w));
                    }
                }
            }
        } finally {
            if (afterSeconds > 0) synchronized (this) { postRolls.remove(Integer.valueOf(afterSeconds)); }
        }
        String g = game == null || game.isBlank() ? settings.defaultGame : game;
        Path dest = Library.unique(library.folder(Clip.Kind.REPLAY), Library.stampName(g, "mp4"));
        try {
            remux(joined, dest);
        } finally {
            Files.deleteIfExists(joined);
        }
        return library.addNew(dest, Clip.Kind.REPLAY, title, g, markers, labels, null);
    }

    /** Waits (up to 2.5 s) until ffmpeg closes the segment it is currently writing. */
    private void awaitNextSegment() throws IOException {
        int before;
        synchronized (this) {
            before = segments.isEmpty() ? -1 : segments.get(segments.size() - 1).index;
        }
        long until = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < until) {
            synchronized (this) {
                if (process == null || !process.isAlive()) return;
                readSegmentList();
                if (!segments.isEmpty() && segments.get(segments.size() - 1).index > before) return;
            }
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * Reads a segment ffmpeg is still writing, cut to whole 188-byte TS packets so the tail is never a torn
     * packet.
     */
    private static byte[] readStable(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        int len = b.length - (b.length % 188);
        return len == b.length ? b : java.util.Arrays.copyOf(b, len);
    }

    private void remux(Path ts, Path mp4) throws IOException {
        List<String> a = new ArrayList<>(List.of("-fflags", "+genpts+discardcorrupt", "-i", ts.toString(), "-map", "0", "-c", "copy"));
        a.addAll(mp4OutputArgs(mp4));
        Ffmpeg.run(a);
    }

    /** Output options shared by every remux to MP4: HEVC tag, track names, faststart. */
    private List<String> mp4OutputArgs(Path mp4) throws IOException {
        Files.createDirectories(mp4.getParent());
        List<String> a = new ArrayList<>();
        if (Ffmpeg.isHevc(encoder)) a.addAll(List.of("-tag:v", "hvc1")); // plays in QuickTime / iOS / browsers
        for (int i = 0; i < trackTitles.size(); i++) {
            // MP4 keeps a track's name in handler_name ("title" is dropped); write both.
            a.addAll(List.of("-metadata:s:a:" + i, "title=" + trackTitles.get(i), "-metadata:s:a:" + i, "handler_name=" + trackTitles.get(i)));
        }
        a.addAll(List.of("-avoid_negative_ts", "make_zero", "-movflags", "+faststart", mp4.toString()));
        return a;
    }

    // ------------------------------------------------------------------ recording

    public synchronized void startRecording(String game) throws IOException {
        if (recording) return;
        if (process == null) start();
        if (bufferDir != null && process != null) readSegmentList(); // know exactly which segment is being written
        recordingGame = game == null || game.isBlank() ? settings.defaultGame : game;
        Path dir = library.folder(Clip.Kind.RECORDING);
        Files.createDirectories(dir);
        recordingTs = Library.unique(dir, Library.stampName(recordingGame, "ts"));
        parts.clear();
        recVerified = false;
        if (settings.recordBitrateMbps > 0 && settings.recordBitrateMbps != settings.replayBitrateMbps) {
            // Recordings have their own bitrate: a second encode writes the recording directly (crash-safe TS).
            CaptureSource.Inputs in = CaptureSource.build(settings, encoder, window);
            List<String> args = captureArgs(in, settings.recordBitrateMbps, false);
            args.addAll(List.of("-f", "mpegts", recordingTs.toString()));
            synchronized (recLog) { recLog.setLength(0); }
            recProcess = CaptureProcessGuard.start(Ffmpeg.interactiveBuilder(args));
            writePid(RECORD_PID, recProcess);
            Ffmpeg.drain(recProcess.getErrorStream(), recLog);
            Ffmpeg.drain(recProcess.getInputStream(), recLog);
            recStartedWall = System.currentTimeMillis();
        } else {
            Files.createFile(recordingTs);
        }
        // Start with the segment being written right now.
        recordStartIndex = recordNextIndex = nextSegmentIndex;
        recordingStartWall = partStartWall = System.currentTimeMillis();
        recordingMarkers.clear();
        recordingLabels.clear();
        recording = true;
        fire();
    }

    /** Appends finished segments (and with {@code finish}, the one in progress) to the recording file. */
    private void appendToRecording(boolean finish) throws IOException {
        try (OutputStream out = Files.newOutputStream(recordingTs, StandardOpenOption.APPEND)) {
            for (Segment s : segments) {
                if (s.index < recordNextIndex) continue;
                Path f = bufferDir.resolve(s.name);
                if (Files.exists(f)) Files.copy(f, out);
                recordNextIndex = s.index + 1;
            }
            if (finish) {
                Path cur = bufferDir.resolve(String.format("seg%06d.ts", recordNextIndex));
                if (Files.exists(cur)) out.write(readStable(cur));
                recordNextIndex++;
            }
        }
    }

    /** A recording file to finish, with its markers relative to its own start. */
    private record Finished(Path ts, List<Double> markers, java.util.Map<Double, String> labels) {}

    /**
     * Finishes the recording, converts it to MP4 and adds it to the library. If its own encoder stopped partway
     * (the rest continued from the replay buffer in a second file), the parts are joined into one clip; should
     * that fail, each part is saved and the newest is returned.
     */
    public Clip stopRecording() throws IOException {
        Process own;
        synchronized (this) {
            if (!recording || recordingStopping) return null;
            recordingStopping = true;
            own = recProcess;
        }
        List<Finished> files;
        String game;
        try {
            boolean fromBuffer = own == null;
            if (own != null) {
                // recProcess stays set until the end, so nothing copies buffer segments into its file meanwhile.
                quit(own);
                synchronized (this) {
                    deletePid(RECORD_PID);
                    // Stopped before its encoder wrote anything: the buffer still has every segment since the start.
                    if (!recVerified && size(recordingTs) == 0) {
                        fromBuffer = true;
                        if (recProcess == own) recProcess = null;
                        recordNextIndex = recordStartIndex;
                    }
                }
            }
            if (fromBuffer) awaitNextSegment();
            synchronized (this) {
                if (own != null && recProcess == own) recProcess = null;
                if (fromBuffer) {
                    boolean alive = process != null && process.isAlive();
                    if (process != null) readSegmentList();
                    // If capture is still running, everything up to now is in finished segments; if it already
                    // stopped, ffmpeg closed the last file cleanly and it can be taken whole.
                    appendToRecording(!alive);
                }
                files = finishedFiles();
                game = recordingGame;
                recording = false;
                parts.clear();
            }
        } finally {
            synchronized (this) { recordingStopping = false; }
        }
        fire();
        applyPendingRestart();
        if (files.size() > 1) {
            // A part that never got any footage (stopped right after the switch) has nothing to add.
            for (int i = files.size() - 1; i >= 0 && files.size() > 1; i--) {
                Path ts = files.get(i).ts();
                if (size(ts) == 0) {
                    try { Files.deleteIfExists(ts); } catch (IOException ignored) {}
                    files.remove(i);
                }
            }
        }
        if (files.size() > 1) {
            try {
                return joinRecording(files, game);
            } catch (IOException e) {
                // Each part is still a playable recording on its own.
                System.err.println("[clips] couldn't join the recording's parts: " + e.getMessage());
            }
        }
        Clip last = null;
        IOException error = null;
        for (Finished f : files) {
            try { last = finishRecording(f.ts(), f.markers(), f.labels(), game); }
            catch (IOException e) { error = e; System.err.println("[clips] saving " + f.ts().getFileName() + ": " + e.getMessage()); }
        }
        if (last == null && error != null) throw error;
        return last;
    }

    /** The recording's files (earlier parts first), each with the markers that fall inside it. */
    private List<Finished> finishedFiles() {
        List<Finished> out = new ArrayList<>();
        List<Part> all = new ArrayList<>(parts);
        all.add(new Part(recordingTs, partStartWall));
        for (int i = 0; i < all.size(); i++) {
            Part p = all.get(i);
            long until = i + 1 < all.size() ? all.get(i + 1).startWall() : Long.MAX_VALUE;
            List<Double> m = new ArrayList<>();
            java.util.Map<Double, String> l = new java.util.HashMap<>();
            for (double t : recordingMarkers) {
                long wall = recordingStartWall + Math.round(t * 1000);
                if ((i > 0 && wall < p.startWall()) || wall >= until) continue;
                double at = Math.max(0, (wall - p.startWall()) / 1000.0);
                m.add(at);
                if (recordingLabels.containsKey(t)) l.put(at, recordingLabels.get(t));
            }
            out.add(new Finished(p.ts(), m, l));
        }
        return out;
    }

    /**
     * Joins the parts of a recording whose own encoder stopped partway into one MP4. ffmpeg's concat demuxer
     * lines up each part after the one before, so the timeline runs straight through.
     */
    private Clip joinRecording(List<Finished> files, String game) throws IOException {
        Path first = files.get(0).ts();
        Path mp4 = Library.unique(first.getParent(), first.getFileName().toString().replaceAll("\\.ts$", ".mp4"));
        List<Double> markers = new ArrayList<>();
        java.util.Map<Double, String> labels = new java.util.HashMap<>();
        StringBuilder list = new StringBuilder("ffconcat version 1.0\n");
        double offset = 0;
        for (Finished f : files) {
            double length = Ffmpeg.probe(f.ts()).duration();
            list.append("file '").append(f.ts().toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
            for (double m : f.markers()) {
                double at = offset + Math.min(m, length);
                markers.add(at);
                String label = f.labels().get(m);
                if (label != null) labels.put(at, label);
            }
            offset += length;
        }
        Path script = Files.createTempFile(library.metaDir(), "parts", ".ffconcat");
        try {
            Files.writeString(script, list.toString());
            List<String> a = new ArrayList<>(List.of("-f", "concat", "-safe", "0", "-fflags", "+genpts+discardcorrupt",
                    "-i", script.toString(), "-map", "0", "-c", "copy"));
            a.addAll(mp4OutputArgs(mp4));
            Ffmpeg.run(a);
        } catch (IOException e) {
            Files.deleteIfExists(mp4);
            throw e;
        } finally {
            Files.deleteIfExists(script);
        }
        for (Finished f : files) Files.deleteIfExists(f.ts());
        return library.addNew(mp4, Clip.Kind.RECORDING, null, game, markers, labels, null);
    }

    private Clip finishRecording(Path ts, List<Double> markers, java.util.Map<Double, String> labels, String game) throws IOException {
        Path mp4 = Library.unique(ts.getParent(), ts.getFileName().toString().replaceAll("\\.ts$", ".mp4"));
        try {
            remux(ts, mp4);
            Files.deleteIfExists(ts);
        } catch (IOException e) {
            // Keep the .ts; it's still playable and the library will pick it up.
            Files.deleteIfExists(mp4);
            return library.addNew(ts, Clip.Kind.RECORDING, null, game, markers, labels, null);
        }
        return library.addNew(mp4, Clip.Kind.RECORDING, null, game, markers, labels, null);
    }

    /**
     * Joins the finished segments newer than {@code afterIndex} into {@code out} (for live analysis). Returns the
     * newest segment index included, or {@code afterIndex} when there's nothing new.
     */
    public synchronized int copyNewSegments(int afterIndex, Path out) throws IOException {
        if (process == null) return afterIndex;
        readSegmentList();
        int last = afterIndex;
        try (OutputStream o = Files.newOutputStream(out)) {
            for (Segment s : segments) {
                if (s.index <= afterIndex) continue;
                Path f = bufferDir.resolve(s.name);
                if (!Files.exists(f)) continue;
                Files.copy(f, o);
                last = s.index;
            }
        }
        return last;
    }

    /** Drops a bookmark at "now": shows up on the recording's (and any saved replay's) timeline. */
    public synchronized void addMarker() { addMarker(null); }

    /** A named bookmark ("Kill Steve", "ACE", "Death"): shows on the timeline with its label. */
    public synchronized void addMarker(String label) {
        long now = System.currentTimeMillis();
        while (markerNames.containsKey(now) || markerWalls.contains(now)) now++;
        markerWalls.add(now);
        if (label != null && !label.isBlank()) markerNames.put(now, label);
        if (recording) {
            double t = (now - recordingStartWall) / 1000.0;
            recordingMarkers.add(t);
            if (label != null && !label.isBlank()) recordingLabels.put(t, label);
        }
    }

    public synchronized String lastLog() { return Ffmpeg.tail(log.toString()); }
}
