package net.evadeclient.clips.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the action in a video, for any game, without game-specific hooks: fights are loud. The audio is measured
 * in quarter-second windows; moments that jump well above the surrounding level (shots, hits, explosions,
 * shouting into the mic) are scored by how far they stand out and how long the burst lasts. The best ones
 * become markers, and {@link #cut} turns them into an edit that keeps only the action.
 */
public final class Highlights {
    public record Moment(double time, double score) {}

    private static final double WINDOW = 0.25;

    private Highlights() {}

    /** Loudness (dB RMS) per 0.25 s window. */
    public static double[] loudness(Path file, Ffmpeg.Job job) throws IOException {
        List<String> cmd = List.of("-nostats", "-loglevel", "error", "-i", file.toString(), "-map", "0:a:0",
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

    /** Detects highlight moments; at most {@code max} (0 = scale with length: ~1 per 30 s, at least 3). */
    public static List<Moment> detect(Path file, double duration, int max, Ffmpeg.Job job) throws IOException {
        return detect(loudness(file, job), duration, max);
    }

    static List<Moment> detect(double[] db, double duration, int max) {
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

    /**
     * An edit that keeps {@code before}/{@code after} seconds around each moment (overlaps merge), for a
     * highlight reel of a long recording.
     */
    public static EditSpec cut(List<Moment> moments, double duration, double before, double after) {
        EditSpec e = new EditSpec();
        for (Moment m : moments) {
            double s = Math.max(0, m.time - before), t = Math.min(duration, m.time + after);
            if (!e.segments.isEmpty() && s <= e.segments.get(e.segments.size() - 1).end + 0.5) {
                e.segments.get(e.segments.size() - 1).end = Math.max(e.segments.get(e.segments.size() - 1).end, t);
            } else {
                e.segments.add(new EditSpec.Segment(s, t, 1));
            }
        }
        if (e.segments.isEmpty()) e.segments.add(new EditSpec.Segment(0, duration, 1));
        e.fadeIn = 0.3;
        e.fadeOut = 0.5;
        return e;
    }
}
