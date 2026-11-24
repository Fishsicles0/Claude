package net.evadeclient.clips.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleConsumer;

/**
 * Session recap: one video from a session's automatic clips (kills, totem pops and wins that Evade Client's Clips mod
 * saved), oldest first, each with its title as a caption ("Triple kill", "Win"), horizontal (1080p) or vertical
 * (1080×1920 for TikTok, Shorts and Reels). Built from the existing montage and export steps, so every clip's own
 * edits (trims, speed, mutes) are kept.
 */
public final class SessionRecap {
    /** Tags the Clips mod gives automatic clips that belong in a recap. */
    public static final Set<String> TAGS = Set.of("kill", "pop", "win");
    /** At most this many clips go in one recap (the newest ones when there are more). */
    public static final int MAX_CLIPS = 30;
    /** How long each caption stays up, in seconds. */
    static final double CAPTION_SEC = 2.5;

    private SessionRecap() {}

    /** Today's start (local time), the default "session". */
    public static long startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** Whether {@code c} is an automatic event clip (from the Clips mod) that a recap should use. */
    public static boolean eligible(Clip c) {
        if (c == null || c.file == null || c.kind != Clip.Kind.REPLAY || c.duration <= 0) return false;
        if (c.tags == null) return false;
        for (String t : c.tags) if (TAGS.contains(t)) return true;
        return false;
    }

    /** The automatic clips saved since {@code since} (ms), oldest first, at most {@link #MAX_CLIPS} (the newest). */
    public static List<Clip> pick(List<Clip> all, long since) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : all) if (c.created >= since && eligible(c)) out.add(c);
        out.sort(Comparator.comparingLong(c -> c.created));
        if (out.size() > MAX_CLIPS) out = new ArrayList<>(out.subList(out.size() - MAX_CLIPS, out.size()));
        return out;
    }

    /** A clip's caption: its title without the " · server" the Clips mod appends ("Triple kill · play.example.net"). */
    public static String caption(Clip c) {
        String t = c.title == null ? "" : c.title;
        int dot = t.indexOf(" · ");
        if (dot > 0) t = t.substring(0, dot);
        t = t.strip();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    /** "Session recap · 24 Nov" (with "vertical" for the vertical cut). */
    public static String title(boolean vertical) {
        String d = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        return "Session recap · " + d + (vertical ? " (vertical)" : "");
    }

    /**
     * Captions for the montage of {@code clips}: each clip's caption from where it starts, for {@link #CAPTION_SEC}
     * (or the clip's length if shorter). The montage joins clips end to end ("dip" transitions don't overlap).
     */
    public static EditSpec captions(List<Clip> clips, double montageDuration) {
        EditSpec spec = EditSpec.whole(montageDuration);
        double at = 0;
        for (Clip c : clips) {
            double len = (c.edit != null ? c.edit : EditSpec.whole(c.duration)).fill().outputDuration();
            String text = caption(c);
            if (!text.isEmpty() && len > 0.3) {
                double end = Math.min(montageDuration, at + Math.min(CAPTION_SEC, len - 0.1));
                if (end > at) spec.captions.add(new EditSpec.Caption(text, at, end, "top", "pop"));
            }
            at += len;
        }
        return spec;
    }

    /**
     * Makes the recap video at {@code out}: the montage of {@code clips}, then one export pass that adds the captions
     * at 1080p (horizontal) or 1080×1920 (vertical). {@code progress} goes 0..1 over both steps.
     */
    public static Path build(List<Clip> clips, Path root, boolean vertical, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        if (clips.isEmpty()) throw new IOException("No automatic clips (kills, pops, wins) in this session yet");
        DoubleConsumer p = progress == null ? x -> {} : progress;
        Path tmp = Files.createTempFile("evadeclips-recap", ".mp4");
        try {
            Exporter.Options mo = new Exporter.Options();
            mo.preset = Exporter.Preset.YOUTUBE;
            mo.transition = "dip";
            Exporter.montage(clips, root, mo, tmp, x -> p.accept(x * 0.6), job);
            if (job != null && job.cancelled()) throw new IOException("Cancelled");
            Ffmpeg.MediaInfo mi = Ffmpeg.probe(tmp);
            Clip whole = new Clip();
            whole.id = "recap";
            whole.file = tmp.getFileName().toString();
            whole.title = title(vertical);
            whole.duration = mi.duration();
            whole.width = mi.width();
            whole.height = mi.height();
            whole.fps = mi.fps();
            whole.hasAudio = mi.hasAudio();
            whole.audioTracks = mi.audioTracks();
            whole.trackNames = new ArrayList<>(mi.trackNames());
            Exporter.Options eo = new Exporter.Options();
            eo.preset = vertical ? Exporter.Preset.SHORTS : Exporter.Preset.YOUTUBE;
            eo.normalize = false; // the montage is already evened out
            Exporter.export(tmp, whole, captions(clips, mi.duration()), eo, out, x -> p.accept(0.6 + x * 0.4), job);
            return out;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}
