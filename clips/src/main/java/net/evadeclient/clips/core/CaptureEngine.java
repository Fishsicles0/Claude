package net.evadeclient.clips.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
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
 */
public final class CaptureEngine {
    public enum State { OFF, STARTING, LIVE, ERROR }

    public record Status(State state, boolean recording, double bufferedSeconds, double recordingSeconds,
                         String message, String encoder) {}

    private record Segment(int index, String name, double start, double end) {
        double length() { return end - start; }
    }

    private final Settings settings;
    private final Library library;
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "capture-janitor");
        t.setDaemon(true);
        return t;
    });

    private Process process;
    private Path bufferDir;
    private final List<Segment> segments = new ArrayList<>();
    private int csvLinesRead;
    private long csvBytesRead;
    private long lastSegmentWall;
    private long startedWall;
    private String encoder = "";
    private int outW, outH;
    /** Audio track names of the running capture ("Game", "Mic", "Voice chat"); MPEG-TS can't store them. */
    private List<String> trackTitles = List.of();

    /**
     * The encoder for live capture: the user's choice if it works, otherwise the best one for the chosen codec.
     * "auto" uses HEVC (about 40% smaller files) when a GPU can encode it, else H.264.
     */
    public static String previewEncoder(Settings s) { return pickEncoder(s); }

    static String pickEncoder(Settings s) {
        String codec = switch (s.codec) {
            case "hevc" -> Ffmpeg.workingEncoders("hevc").isEmpty() ? "h264" : "hevc";
            case "h264" -> "h264";
            default -> Ffmpeg.hardwareHevc() ? "hevc" : "h264";
        };
        if (!s.encoder.isBlank() && Ffmpeg.workingEncoders(codec).contains(s.encoder)) return s.encoder;
        return Ffmpeg.workingEncoders(codec).get(0);
    }

    /** Bitrate ceiling currently in use (bits/s), or 0 when capture is off. */
    public synchronized long capBits() { return encoder.isEmpty() ? 0 : Ffmpeg.liveCapBits(encoder, settings.quality, settings.fps, outW, outH); }
    private volatile State state = State.OFF;
    private volatile String message = "";
    private final StringBuilder log = new StringBuilder();
    private boolean stopping;
    private int restarts;

    // recording
    private boolean recording;
    private Path recordingTs;
    private int recordNextIndex;
    private long recordingStartWall;
    private final List<Double> recordingMarkers = new ArrayList<>();
    private String recordingGame;
    /** Wall-clock marker presses, for markers inside saved replays. */
    private final List<Long> markerWalls = new ArrayList<>();
    /** Label for each marker (by wall time), e.g. "Kill Steve", "ACE", "Voice". */
    private final java.util.Map<Long, String> markerNames = new java.util.HashMap<>();
    private final java.util.Map<Double, String> recordingLabels = new java.util.HashMap<>();

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
    public synchronized void start() throws IOException {
        if (process != null && process.isAlive()) return;
        if (!Ffmpeg.available()) throw new IOException("ffmpeg was not found. Install it or set its location in Settings.");
        bufferDir = library.bufferDir();
        Files.createDirectories(bufferDir);
        try (var s = Files.list(bufferDir)) {
            for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p);
        }
        segments.clear();
        csvLinesRead = 0;
        csvBytesRead = 0;
        log.setLength(0);
        stopping = false;

        encoder = pickEncoder(settings);
        CaptureSource.Inputs in = CaptureSource.build(settings);
        outW = in.outW();
        outH = in.outH();
        trackTitles = new ArrayList<>(in.audioTitles());
        List<String> args = captureArgs(in, settings.replayBitrateMbps);
        args.addAll(List.of("-f", "segment", "-segment_time", "1", "-segment_format", "mpegts",
                "-segment_list", "segments.csv", "-segment_list_type", "csv", "-segment_list_flags", "+live",
                "-break_non_keyframes", "0", "seg%06d.ts"));

        ProcessBuilder pb = Ffmpeg.interactiveBuilder(args).directory(bufferDir.toFile());
        process = pb.start();
        Ffmpeg.drain(process.getErrorStream(), log);
        Ffmpeg.drain(process.getInputStream(), log);
        startedWall = lastSegmentWall = System.currentTimeMillis();
        state = State.STARTING;
        message = "Starting capture";
        fire();
    }

    /** Inputs, filter graph, encoder (with a bitrate ceiling: {@code mbps} > 0, else from the quality level). */
    private List<String> captureArgs(CaptureSource.Inputs in, double mbps) {
        List<String> args = new ArrayList<>(List.of("-loglevel", "warning", "-thread_queue_size", "1024"));
        // -thread_queue_size applies to the next input; repeat it for every input so audio never stalls video.
        for (int i = 0; i < in.args().size(); i++) {
            String v = in.args().get(i);
            if (v.equals("-f") && i > 0) args.addAll(List.of("-thread_queue_size", "1024"));
            args.add(v);
        }
        args.addAll(List.of("-filter_complex", in.filterComplex(), "-map", "[v]"));
        for (String l : in.audioLabels()) args.addAll(List.of("-map", l));
        args.addAll(Ffmpeg.liveEncoderArgs(encoder, settings.quality, settings.fps, in.outW(), in.outH(), (long) (mbps * 1_000_000)));
        args.addAll(List.of("-r", String.valueOf(settings.fps)));
        if (in.hasAudio()) args.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"));
        for (int i = 0; i < in.audioTitles().size(); i++) args.addAll(List.of("-metadata:s:a:" + i, "title=" + in.audioTitles().get(i)));
        return args;
    }

    /** Separate encode for recordings with their own bitrate (null when recordings share the replay buffer's). */
    private Process recProcess;
    private final StringBuilder recLog = new StringBuilder();

    /** Stops capture. A running recording is finished and saved first. */
    public void stop() {
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
        }
        quit(p);
        fire();
    }

    /** Applies new settings by restarting capture (keeps a running recording going into a new file). */
    public void restart() throws IOException {
        boolean wasLive = isLive();
        stop();
        if (wasLive) start();
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
        stop();
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ periodic work

    private void tick() {
        try {
            synchronized (this) {
                if (process == null) return;
                readSegmentList();
                if (!process.isAlive() && !stopping) {
                    String why = Ffmpeg.tail(log.toString());
                    process = null;
                    state = State.ERROR;
                    message = why.isBlank() ? "Capture stopped unexpectedly" : why;
                    System.err.println("[clips] capture died: " + message);
                    if (restarts++ < 3 && System.currentTimeMillis() - startedWall > 5000) {
                        try { start(); } catch (IOException e) { message = e.getMessage(); }
                    }
                } else if (state == State.STARTING && !segments.isEmpty()) {
                    state = State.LIVE;
                    message = "";
                    restarts = 0;
                } else if (state == State.STARTING && System.currentTimeMillis() - startedWall > 15000) {
                    message = "Waiting for the first frames... " + Ffmpeg.tail(log.toString());
                }
                if (recording && recProcess == null) appendToRecording(false);
                prune();
            }
        } catch (Exception e) {
            System.err.println("[clips] capture tick: " + e);
        }
        fire();
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
                lastSegmentWall = System.currentTimeMillis();
                csvLinesRead++;
            } catch (NumberFormatException ignored) {}
        }
    }

    /** Deletes segments that are older than the replay window and no longer needed by a recording. */
    private void prune() {
        if (segments.isEmpty()) return;
        double keep = (settings.replayEnabled ? settings.replaySeconds : 0) + 3;
        double newest = segments.get(segments.size() - 1).end;
        while (!segments.isEmpty()) {
            Segment s = segments.get(0);
            if (newest - s.end < keep) break;
            if (recording && s.index >= recordNextIndex) break;
            try { Files.deleteIfExists(bufferDir.resolve(s.name)); } catch (IOException ignored) {}
            segments.remove(0);
        }
        long cutoff = System.currentTimeMillis() - (long) (keep * 1000) - 2000;
        markerWalls.removeIf(t -> t < cutoff);
        markerNames.keySet().removeIf(t -> t < cutoff);
    }

    private synchronized double buffered() {
        if (segments.isEmpty()) return 0;
        double total = segments.get(segments.size() - 1).end - segments.get(0).start;
        return Math.min(settings.replayEnabled ? settings.replaySeconds : 0, total);
    }

    private void fire() {
        Status s;
        synchronized (this) {
            s = new Status(state, recording, buffered(),
                    recording ? (System.currentTimeMillis() - recordingStartWall) / 1000.0 : 0, message, encoder);
        }
        for (Consumer<Status> l : listeners) {
            try { l.accept(s); } catch (Exception e) { System.err.println("[clips] status listener: " + e); }
        }
    }

    // ------------------------------------------------------------------ instant replay

    /** Name of the segment ffmpeg is currently writing (the one after the last finished one). */
    private String currentSegmentName() {
        int next = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).index + 1;
        return String.format("seg%06d.ts", next);
    }

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
        synchronized (this) {
            if (process == null || segments.isEmpty())
                throw new IOException(process == null ? "Instant replay is off" : "Replay buffer is still filling");
        }
        if (afterSeconds > 0) {
            try { Thread.sleep(afterSeconds * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            seconds += afterSeconds;
        }
        // Let ffmpeg finish the segment it's writing (at most ~1 s) so the clip runs right up to the key press
        // without a half-written frame at the end.
        awaitNextSegment();
        Path joined;
        List<Double> markers = new ArrayList<>();
        java.util.Map<Double, String> labels = new java.util.HashMap<>();
        synchronized (this) {
            if (segments.isEmpty()) throw new IOException("Replay buffer is empty");
            List<Segment> pick = new ArrayList<>();
            double total = 0;
            for (int i = segments.size() - 1; i >= 0 && total < seconds - 0.5; i--) {
                pick.add(0, segments.get(i));
                total += segments.get(i).length();
            }
            joined = Files.createTempFile(bufferDir, "replay", ".ts");
            try (OutputStream out = Files.newOutputStream(joined)) {
                for (Segment s : pick) Files.copy(bufferDir.resolve(s.name), out);
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
        Files.createDirectories(mp4.getParent());
        List<String> a = new ArrayList<>(List.of("-fflags", "+genpts+discardcorrupt", "-i", ts.toString(), "-map", "0", "-c", "copy"));
        if (Ffmpeg.isHevc(encoder)) a.addAll(List.of("-tag:v", "hvc1")); // plays in QuickTime / iOS / browsers
        for (int i = 0; i < trackTitles.size(); i++) {
            // MP4 keeps a track's name in handler_name ("title" is dropped); write both.
            a.addAll(List.of("-metadata:s:a:" + i, "title=" + trackTitles.get(i), "-metadata:s:a:" + i, "handler_name=" + trackTitles.get(i)));
        }
        a.addAll(List.of("-avoid_negative_ts", "make_zero", "-movflags", "+faststart", mp4.toString()));
        Ffmpeg.run(a);
    }

    // ------------------------------------------------------------------ recording

    public synchronized void startRecording(String game) throws IOException {
        if (recording) return;
        if (process == null) start();
        recordingGame = game == null || game.isBlank() ? settings.defaultGame : game;
        Path dir = library.folder(Clip.Kind.RECORDING);
        Files.createDirectories(dir);
        recordingTs = Library.unique(dir, Library.stampName(recordingGame, "ts"));
        if (settings.recordBitrateMbps > 0 && settings.recordBitrateMbps != settings.replayBitrateMbps) {
            // Recordings have their own bitrate: a second encode writes the recording directly (crash-safe TS).
            CaptureSource.Inputs in = CaptureSource.build(settings);
            List<String> args = captureArgs(in, settings.recordBitrateMbps);
            args.addAll(List.of("-f", "mpegts", recordingTs.toString()));
            recLog.setLength(0);
            recProcess = Ffmpeg.interactiveBuilder(args).start();
            Ffmpeg.drain(recProcess.getErrorStream(), recLog);
            Ffmpeg.drain(recProcess.getInputStream(), recLog);
        } else {
            Files.createFile(recordingTs);
        }
        // Start with the segment being written right now.
        recordNextIndex = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).index + 1;
        recordingStartWall = System.currentTimeMillis();
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

    /** Finishes the recording, converts it to MP4 and adds it to the library. */
    public Clip stopRecording() throws IOException {
        Path ts;
        List<Double> markers;
        java.util.Map<Double, String> labels;
        String game;
        Process own;
        synchronized (this) {
            if (!recording) return null;
            own = recProcess;
        }
        if (own != null) {
            quit(own);
            synchronized (this) {
                recProcess = null;
                recording = false;
                ts = recordingTs;
                markers = new ArrayList<>(recordingMarkers);
                labels = new java.util.HashMap<>(recordingLabels);
                game = recordingGame;
            }
            fire();
            return finishRecording(ts, markers, labels, game);
        }
        awaitNextSegment();
        synchronized (this) {
            if (!recording) return null;
            boolean alive = process != null && process.isAlive();
            if (process != null) readSegmentList();
            // If capture is still running, everything up to now is in finished segments; if it already stopped,
            // ffmpeg closed the last file cleanly and it can be taken whole.
            appendToRecording(!alive);
            recording = false;
            ts = recordingTs;
            markers = new ArrayList<>(recordingMarkers);
            labels = new java.util.HashMap<>(recordingLabels);
            game = recordingGame;
        }
        fire();
        return finishRecording(ts, markers, labels, game);
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
