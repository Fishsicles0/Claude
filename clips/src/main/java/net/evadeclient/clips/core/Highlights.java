package net.evadeclient.clips.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds the action in a video. Game events marked while recording (kills, aces, wins, clutches) come first;
 * then the sound: fights are loud. The audio (the game, and your mic when it was recorded on its
 * own track) is measured in quarter-second windows; moments that jump well above the surrounding level (shots,
 * hits, explosions, shouting into the mic) are scored by how far they stand out and how long the burst lasts.
 * The best ones become markers, and {@link #cut} turns them into an edit that keeps only the action.
 */
public final class Highlights {
    /** A highlight: when, how good, and the game event behind it (null for one found from the sound). */
    public record Moment(double time, double score, String label) {
        public Moment(double time, double score) { this(time, score, null); }
        /** Whether it comes from a game event marker rather than the sound. */
        public boolean event() { return label != null; }
    }

    private static final double WINDOW = 0.25;
    /** Marker names that are highlights in themselves. */
    private static final Pattern EVENT = Pattern.compile("kill|ace|win|victory|multi|double|triple|quadra|penta|clutch", Pattern.CASE_INSENSITIVE);

    private Highlights() {}

    /** Loudness (dB RMS) per 0.25 s window of the first audio track. */
    public static double[] loudness(Path file, Ffmpeg.Job job) throws IOException { return loudness(file, "0:a:0", job); }

    /** Loudness (dB RMS) per 0.25 s window of one audio track ({@code map}, e.g. "0:a:1" for the mic). */
    public static double[] loudness(Path file, String map, Ffmpeg.Job job) throws IOException {
        List<String> cmd = List.of("-nostats", "-loglevel", "error", "-i", file.toString(), "-map", map,
                "-af", "aformat=channel_layouts=mono,aresample=8000,asetnsamples=n=" + (int) (8000 * WINDOW)
                        + ":p=0,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-",
                "-f", "null", "-");
        Process p = Ffmpeg.builder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        List<Double> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (job != null && job.cancelled()) { p.destroyForcibly(); throw new IOException("Cancelled"); }
                int eq = line.indexOf("RMS_level=");
                if (eq < 0) continue;
                String v = line.substring(eq + 10).trim();
                double d;
                try { d = v.equals("-inf") ? -90 : Double.parseDouble(v); } catch (NumberFormatException e) { d = -90; }
                out.add(Math.max(-90, d));
            }
        }
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        double[] a = new double[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Detects highlight moments from the sound; at most {@code max} (0 = scale with length: ~1 per 30 s, at least 3). */
    public static List<Moment> detect(Path file, double duration, int max, Ffmpeg.Job job) throws IOException {
        return detect(loudness(file, job), duration, max);
    }

    /**
     * Detects a clip's highlights: its game-event markers (kills, aces, wins...) first, then the loudest moments
     * of the game and your mic (a mic track counts 3 dB less, so talking doesn't outrank the action). Loud
     * moments within 3 s of an event are the event itself and are dropped. At most {@code max} moments (0 =
     * ~1 per 30 s, at least 3), at least 6 s apart; events that close together count as one and are all kept.
     */
    public static List<Moment> detect(Clip c, Path file, int max, Ffmpeg.Job job) throws IOException {
        double[] db = new double[0];
        if (c.hasAudio) {
            db = loudness(file, "0:a:0", job);
            int mic = micTrack(c);
            if (mic > 0) {
                double[] m = loudness(file, "0:a:" + mic, job);
                for (int i = 0; i < db.length; i++) db[i] = Math.max(db[i], i < m.length ? m[i] - 3 : -90);
            }
        }
        List<Moment> events = new ArrayList<>();
        for (double t : c.markers) {
            String l = c.label(t);
            if (isEvent(l)) events.add(new Moment(t, eventScore(l), l));
        }
        events.sort((x, y) -> Double.compare(x.time, y.time));
        List<Moment> loud = candidates(db);
        loud.removeIf(x -> events.stream().anyMatch(ev -> Math.abs(ev.time - x.time) < 3));

        int limit = max > 0 ? max : Math.max(3, (int) Math.ceil(c.duration / 30));
        // Events within 6 s of each other are one moment of play (a multi-kill): grouped, best group first.
        List<List<Moment>> groups = new ArrayList<>();
        for (Moment ev : events) {
            List<Moment> last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && ev.time - last.get(last.size() - 1).time < 6) last.add(ev);
            else { List<Moment> g = new ArrayList<>(); g.add(ev); groups.add(g); }
        }
        groups.sort((x, y) -> Double.compare(best(y), best(x)));
        List<Moment> keep = new ArrayList<>();
        int used = 0;
        for (List<Moment> g : groups) {
            if (used >= limit) break;
            keep.addAll(g);
            used++;
        }
        for (Moment m : loud) {
            if (used >= limit) break;
            boolean near = false;
            for (Moment k : keep) if (Math.abs(k.time - m.time) < 6) { near = true; break; }
            if (near) continue;
            keep.add(m);
            used++;
        }
        keep.sort((x, y) -> Double.compare(x.time, y.time));
        return keep;
    }

    /** The mic's track: one named "Mic", else the second track of a clip with separate tracks; -1 for none. */
    private static int micTrack(Clip c) {
        for (int i = 1; i < c.audioTracks; i++) if (Exporter.trackName(c, i).equalsIgnoreCase("Mic")) return i;
        return -1;
    }

    /** Whether a marker name is a game event that is a highlight in itself (a kill, an ace, a win, a clutch...). */
    public static boolean isEvent(String label) { return label != null && EVENT.matcher(label).find(); }

    private static double eventScore(String label) {
        String l = label.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("ace") || l.contains("victory") || l.contains("win") || l.contains("penta")) return 130;
        if (l.contains("triple") || l.contains("quadra") || l.contains("multi") || l.contains("clutch")) return 120;
        if (l.contains("double")) return 110;
        return 100;
    }

    private static double best(List<Moment> g) {
        double b = 0;
        for (Moment m : g) b = Math.max(b, m.score);
        return b + g.size(); // a bigger multi-kill ranks a little higher
    }

    static List<Moment> detect(double[] db, double duration, int max) {
        List<Moment> found = candidates(db);
        if (found.isEmpty()) return found;
        int limit = max > 0 ? max : Math.max(3, (int) Math.ceil(duration / 30));
        // Keep the best, at least 6 s apart.
        List<Moment> keep = new ArrayList<>();
        for (Moment m : found) {
            if (keep.size() >= limit) break;
            boolean near = false;
            for (Moment k : keep) if (Math.abs(k.time - m.time) < 6) { near = true; break; }
            if (!near) keep.add(m);
        }
        keep.sort((x, y) -> Double.compare(x.time, y.time));
        return keep;
    }

    /** Every loud burst, best first. */
    private static List<Moment> candidates(double[] db) {
        int n = db.length;
        List<Moment> found = new ArrayList<>();
        if (n < 8) return found;
        // Baseline: median of the surrounding ~12 s, so a loud game doesn't flag everything.
        int half = (int) (6 / WINDOW);
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - half), b = Math.min(n, i + half);
            double[] win = Arrays.copyOfRange(db, a, b);
            Arrays.sort(win);
            double median = win[win.length / 2];
            double rise = db[i] - median;
            score[i] = db[i] > -42 && rise > 6 ? rise : 0;
        }
        // Group consecutive loud windows into bursts; score = peak rise + sustained energy.
        for (int i = 0; i < n; ) {
            if (score[i] <= 0) { i++; continue; }
            int j = i;
            double peak = 0, sum = 0;
            int peakAt = i;
            while (j < n && (score[j] > 0 || (j + 1 < n && score[j + 1] > 0))) {
                if (score[j] > peak) { peak = score[j]; peakAt = j; }
                sum += score[j];
                j++;
            }
            double len = (j - i) * WINDOW;
            double s = peak + Math.min(12, sum * WINDOW) + (len > 0.75 ? 3 : 0);
            found.add(new Moment(peakAt * WINDOW, s));
            i = j;
        }
        found.sort((x, y) -> Double.compare(y.score, x.score));
        return found;
    }

    /**
     * An edit that keeps {@code before}/{@code after} seconds around each moment (overlaps merge), for a
     * highlight reel of a long recording.
     */
    public static EditSpec cut(List<Moment> moments, double duration, double before, double after) {
        return cut(moments, duration, before, after, before, after);
    }

    /**
     * Like {@link #cut(List, double, double, double)}, with its own window for game events (a kill needs the
     * build-up before it more than the moments after).
     */
    public static EditSpec cut(List<Moment> moments, double duration, double before, double after, double eventBefore, double eventAfter) {
        EditSpec e = new EditSpec();
        List<Moment> sorted = new ArrayList<>(moments);
        sorted.sort((x, y) -> Double.compare(x.time, y.time));
        for (Moment m : sorted) {
            double b = m.event() ? eventBefore : before, a = m.event() ? eventAfter : after;
            double s = Math.max(0, m.time - b), t = Math.min(duration, m.time + a);
            EditSpec.Segment last = e.segments.isEmpty() ? null : e.segments.get(e.segments.size() - 1);
            if (last != null && s <= last.end + 0.5) {
                last.start = Math.min(last.start, s);
                last.end = Math.max(last.end, t);
            } else {
                e.segments.add(new EditSpec.Segment(s, t, 1));
            }
        }
        // windows of different lengths can reach back over an earlier part: merge any that touch
        for (int i = e.segments.size() - 1; i > 0; i--) {
            EditSpec.Segment a = e.segments.get(i - 1), b = e.segments.get(i);
            if (b.start <= a.end + 0.5) { a.start = Math.min(a.start, b.start); a.end = Math.max(a.end, b.end); e.segments.remove(i); }
        }
        if (e.segments.isEmpty()) e.segments.add(new EditSpec.Segment(0, duration, 1));
        e.fadeIn = 0.3;
        e.fadeOut = 0.5;
        return e;
    }
}
