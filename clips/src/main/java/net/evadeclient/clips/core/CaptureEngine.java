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

        encoder = settings.encoder.isBlank() || !Ffmpeg.workingEncoders().contains(settings.encoder)
                ? Ffmpeg.bestEncoder() : settings.encoder;
        CaptureSource.Inputs in = CaptureSource.build(settings);
        List<String> args = new ArrayList<>(List.of("-loglevel", "warning", "-thread_queue_size", "1024"));
        // -thread_queue_size applies to the next input; repeat it for every input so audio never stalls video.
        List<String> inputArgs = new ArrayList<>();
        for (int i = 0; i < in.args().size(); i++) {
            String v = in.args().get(i);
            if (v.equals("-f") && i > 0) inputArgs.addAll(List.of("-thread_queue_size", "1024"));
            inputArgs.add(v);
        }
        args.addAll(inputArgs);
        args.addAll(List.of("-filter_complex", in.filterComplex(), "-map", "[v]"));
        if (in.hasAudio()) args.addAll(List.of("-map", "[a]"));
        if (in.micTrack()) args.addAll(List.of("-map", "[m]"));
        args.addAll(Ffmpeg.liveEncoderArgs(encoder, settings.quality, settings.fps));
        args.addAll(List.of("-r", String.valueOf(settings.fps)));
        if (in.hasAudio()) args.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"));
        if (in.micTrack()) args.addAll(List.of("-metadata:s:a:0", "title=Game", "-metadata:s:a:1", "title=Mic"));
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
                if (recording) appendToRecording(false);
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
                if (fromEnd >= 0 && fromEnd <= total) markers.add(Math.max(0, total - fromEnd));
            }
        }
        String g = game == null || game.isBlank() ? settings.defaultGame : game;
        Path dest = Library.unique(library.folder(Clip.Kind.REPLAY), Library.stampName(g, "mp4"));
        try {
            remux(joined, dest);
        } finally {
            Files.deleteIfExists(joined);
        }
        return library.addNew(dest, Clip.Kind.REPLAY, title, g, markers, null);
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

    private static void remux(Path ts, Path mp4) throws IOException {
        Files.createDirectories(mp4.getParent());
        Ffmpeg.run(List.of("-fflags", "+genpts+discardcorrupt", "-i", ts.toString(), "-map", "0", "-c", "copy",
                "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", mp4.toString()));
    }

    // ------------------------------------------------------------------ recording

    public synchronized void startRecording(String game) throws IOException {
        if (recording) return;
        if (process == null) start();
        recordingGame = game == null || game.isBlank() ? settings.defaultGame : game;
        Path dir = library.folder(Clip.Kind.RECORDING);
        Files.createDirectories(dir);
        recordingTs = Library.unique(dir, Library.stampName(recordingGame, "ts"));
        Files.createFile(recordingTs);
        // Start with the segment being written right now.
        recordNextIndex = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).index + 1;
        recordingStartWall = System.currentTimeMillis();
        recordingMarkers.clear();
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
        String game;
        synchronized (this) {
            if (!recording) return null;
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
            game = recordingGame;
        }
        fire();
        Path mp4 = Library.unique(ts.getParent(), ts.getFileName().toString().replaceAll("\\.ts$", ".mp4"));
        try {
            remux(ts, mp4);
            Files.deleteIfExists(ts);
        } catch (IOException e) {
            // Keep the .ts; it's still playable and the library will pick it up.
            Files.deleteIfExists(mp4);
            return library.addNew(ts, Clip.Kind.RECORDING, null, game, markers, null);
        }
        return library.addNew(mp4, Clip.Kind.RECORDING, null, game, markers, null);
    }

    /** Drops a bookmark at "now": shows up on the recording's (and any saved replay's) timeline. */
    public synchronized void addMarker() {
        long now = System.currentTimeMillis();
        markerWalls.add(now);
        if (recording) recordingMarkers.add((now - recordingStartWall) / 1000.0);
    }

    public synchronized String lastLog() { return Ffmpeg.tail(log.toString()); }
}
