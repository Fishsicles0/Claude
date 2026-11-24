package net.evadeclient.clips.core;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders a clip + its {@link EditSpec} into a new file with one of the export presets. */
public final class Exporter {

    public enum Preset {
        QUICK_TRIM("Quick trim", "Instant, no re-encode. Cuts on the nearest second.", 0, 0, 0, "mp4"),
        SOURCE("Source quality", "Full resolution and frame rate, high bitrate MP4.", 0, 0, 0, "mp4"),
        DISCORD("Discord (10 MB)", "Fits Discord's free upload limit. Shrinks resolution if it has to.", 1080, 60, 10, "mp4"),
        DISCORD_NITRO("Discord Nitro (50 MB)", "Fits Nitro Basic's limit at the best quality that fits.", 1080, 60, 50, "mp4"),
        YOUTUBE("YouTube 1080p60", "1080p, 60 fps, high bitrate. Great for uploads.", 1080, 60, 0, "mp4"),
        SHORTS("TikTok / Shorts / Reels", "Vertical 1080×1920, 60 fps. Uses your crop or a blurred background.", 1080, 60, 0, "mp4"),
        GIF("GIF", "480 px wide, 15 fps, optimised palette.", 480, 15, 0, "gif");

        public final String label, description, ext;
        public final int height, fps;
        public final double targetMb;

        Preset(String label, String description, int height, int fps, double targetMb, String ext) {
            this.label = label; this.description = description; this.height = height; this.fps = fps;
            this.targetMb = targetMb; this.ext = ext;
        }

        @Override public String toString() { return label; }
    }

    /** Overrides on top of a preset (0 = use the preset's). */
    public static final class Options {
        public Preset preset = Preset.SOURCE;
        public int maxHeight;
        public int fps;
        public double targetMb;
        public boolean gpu = true;
        /** Your own watermark (null = none). Evade Clips never adds one of its own. */
        public Watermark watermark;
        /**
         * Evens out the loudness to -14 LUFS, what YouTube, TikTok and Discord play at, so quiet clips aren't lost
         * and loud ones don't clip. One pass; never for Quick trim (no re-encode) or GIF (no sound).
         */
        public boolean normalize = true;
        /** Montage only: how clips join: "none", "dip" (to black), "fade", "slideleft", "zoomin" or "fadewhite". */
        public String transition = "dip";
        /** Montage only: how long a transition takes, in seconds. */
        public double transitionSec = 0.3;
        /** Montage only: background music under the whole montage (null = none). */
        public EditSpec.Music music;
    }

    /** A creator's own watermark: text or an image, anywhere on the frame, at any size and opacity. */
    public record Watermark(String text, String image, String position, double size, double opacity, double margin) {
        public static Watermark from(Settings s) {
            if (!s.watermarkEnabled) return null;
            if ((s.watermarkText == null || s.watermarkText.isBlank()) && (s.watermarkImage == null || s.watermarkImage.isBlank())) return null;
            return new Watermark(s.watermarkText, s.watermarkImage, s.watermarkPosition, s.watermarkSize, s.watermarkOpacity, s.watermarkMargin);
        }

        /** Whether there's anything to draw: some text, or an image file that can be read. */
        public boolean usable() {
            if (text != null && !text.isBlank()) return true;
            return imageReadable();
        }

        boolean imageReadable() {
            if (image == null || image.isBlank()) return false;
            try {
                Path p = Path.of(image);
                return Files.isRegularFile(p) && Files.isReadable(p);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** An image and where its top-left corner goes on the frame. */
    public record Sprite(BufferedImage image, int x, int y) {
        public int width() { return image.getWidth(); }
        public int height() { return image.getHeight(); }
    }

    private Exporter() {}

    /** Output frame size for a source and an edit, before preset scaling. */
    record Region(int w, int h, int x, int y) {}

    /** Below this video bitrate a size-targeted export drops to 240p / 24 fps; below {@link #MIN_VIDEO_KBPS} it can't fit. */
    private static final double LOW_VIDEO_KBPS = 80, MIN_VIDEO_KBPS = 48;
    /** Filter graphs longer than this go in a file: Windows caps a whole command line at 32,767 characters. */
    private static final int INLINE_GRAPH = 8000;
    /** Seconds of the source decoded before each piece when an input is opened with a fast seek. */
    private static final double PREROLL = 1;
    /** Pieces further apart than this (seconds) are read through separate, fast-seeked inputs. */
    private static final double SEEK_GAP = 15;
    private static final String LOUDNORM = ",loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000";

    // ------------------------------------------------------------------ output files

    /** Output files promised to exports that are queued or running, so two of them never share a file. */
    private static final Set<Path> RESERVED = ConcurrentHashMap.newKeySet();

    private static Path key(Path p) { return p.toAbsolutePath().normalize(); }

    /**
     * A file name in {@code dir} that doesn't exist yet and isn't promised to another export ("name (2).mp4"
     * style). It stays reserved until the export writing it ends ({@link #export} and {@link #montage} release it).
     */
    public static Path reserveOutput(Path dir, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        synchronized (RESERVED) {
            Path p = dir.resolve(name);
            for (int i = 2; Files.exists(p) || RESERVED.contains(key(p)); i++) p = dir.resolve(base + " (" + i + ")" + ext);
            RESERVED.add(key(p));
            return p;
        }
    }

    /** Frees a file reserved with {@link #reserveOutput} (for a queued export that was dropped before it ran). */
    public static void release(Path out) {
        if (out != null) RESERVED.remove(key(out));
    }

    // ------------------------------------------------------------------ public entry

    public static Path export(Path src, Clip clip, EditSpec edit, Options o, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        Path tmp = null;
        try {
            Files.createDirectories(out.getParent());
            if (o.preset == Preset.QUICK_TRIM) {
                quickTrim(src, edit, out, progress, job);
                return out;
            }
            double dur = edit.outputDuration();
            if (dur <= 0.05) throw new IOException("Nothing to export: every part of the clip was cut out.");
            tmp = Files.createTempDirectory("evadeclips-export");
            double target = o.targetMb > 0 ? o.targetMb : o.preset.targetMb;
            if (target <= 0 || o.preset == Preset.GIF) {
                Ffmpeg.run(buildArgs(src, clip, edit, o, out, -1, 0, null, tmp), dur, progress, job);
                return out;
            }

            // Size-targeted: budget the sound first (the same sound buildArgs will add), then aim a little under
            // and re-try lower if the encoder overshoots.
            long limit = (long) (target * 1000 * 1000); // Discord counts in MB (10^6) on the client
            boolean sound = hasAnyAudio(clip, edit, o);
            int[] steps = !sound ? new int[]{0} : target <= 10 ? new int[]{96, 64, 48} : new int[]{128, 96, 64, 48};
            double budget = limit * 8 * 0.93 / dur / 1000;
            int audioKbps = steps[steps.length - 1];
            for (int a : steps) if (budget - a >= LOW_VIDEO_KBPS) { audioKbps = a; break; }
            if (budget - audioKbps < MIN_VIDEO_KBPS) throw new IOException(tooLong(o, target, dur, limit, audioKbps));

            double factor = 0.93;
            for (int attempt = 0; attempt < 4; attempt++) {
                double totalKbps = limit * 8 * factor / dur / 1000;
                double videoKbps = Math.max(MIN_VIDEO_KBPS, totalKbps - audioKbps);
                final int tries = attempt;
                DoubleConsumer prog = progress == null ? null : p -> progress.accept(Math.min(0.99, (tries * 0.1) + p * (1 - tries * 0.1)));
                twoPass(src, clip, edit, o, out, videoKbps, audioKbps, dur, prog, job, tmp);
                if (Files.size(out) <= limit) {
                    if (progress != null) progress.accept(1);
                    return out;
                }
                factor *= 0.88 * limit / (double) Files.size(out);
            }
            long got = Files.size(out);
            Files.deleteIfExists(out);
            throw new IOException(String.format(Locale.ROOT, "The export came out at %.1f MB, over the %s MB limit. Trim the clip%s.",
                    got / 1e6, mb(target), o.preset == Preset.DISCORD && o.targetMb <= 0 ? " or use Discord Nitro (50 MB)" : " or pick a bigger size"));
        } finally {
            if (tmp != null) deleteTree(tmp);
            release(out);
        }
    }

    /** "This clip is 8:00 long; the 10 MB Discord preset fits about 7 min. Trim it or use Discord Nitro (50 MB)." */
    private static String tooLong(Options o, double target, double dur, long limit, int audioKbps) {
        double fits = limit * 8 * 0.93 / ((audioKbps + MIN_VIDEO_KBPS) * 1000);
        boolean discord = o.targetMb <= 0 && o.preset == Preset.DISCORD;
        String what = o.targetMb > 0 ? "a " + mb(target) + " MB file"
                : discord ? "the 10 MB Discord preset" : o.preset == Preset.DISCORD_NITRO ? "the 50 MB Discord Nitro preset" : "the " + mb(target) + " MB limit";
        String about = fits >= 120 ? (int) Math.floor(fits / 60) + " min" : Math.max(1, (int) Math.floor(fits)) + " s";
        return "This clip is " + clock(dur) + " long; " + what + " fits about " + about + ". Trim it"
                + (discord ? " or use Discord Nitro (50 MB)." : ".");
    }

    private static String mb(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    private static String clock(double s) {
        long t = Math.round(s);
        return t >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }

    /**
     * Whether the export will have sound: the clip's own, an effect's sound, an overlay's sound or music. The
     * size budget and the filter graph agree on this, so a muted clip with music still gets its audio budget.
     */
    static boolean hasAnyAudio(Clip clip, EditSpec e, Options o) throws IOException {
        if (o.preset == Preset.GIF) return false;
        e.fill();
        if (clip.hasAudio && !e.mute) return true;
        if (musicUsable(e.music)) return true;
        for (EditSpec.Effect f : e.effects) if (e.segmentAt(f.t) >= 0 && Sounds.file(f.sound) != null) return true;
        for (EditSpec.Overlay ov : e.overlays) if (ov.audio && overlayFile(ov) != null && overlayHasAudio(ov.file)) return true;
        return false;
    }

    private static Path overlayFile(EditSpec.Overlay ov) {
        if (ov.file == null || ov.file.isBlank()) return null;
        try {
            Path p = Path.of(ov.file);
            return Files.exists(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Map<String, Boolean> HAS_AUDIO = new ConcurrentHashMap<>();

    /** Whether an overlay video has sound (probed once per file version). */
    private static boolean overlayHasAudio(String file) {
        String k;
        try { k = file + "|" + Files.getLastModifiedTime(Path.of(file)).toMillis(); } catch (Exception e) { return false; }
        return HAS_AUDIO.computeIfAbsent(k, kk -> {
            try { return Ffmpeg.probe(Path.of(file)).hasAudio(); } catch (IOException e) { return false; }
        });
    }

    static boolean musicUsable(EditSpec.Music m) {
        if (m == null || m.file == null || m.file.isBlank()) return false;
        try { return Files.isRegularFile(Path.of(m.file)); } catch (RuntimeException e) { return false; }
    }

    /** Stream-copies each kept part and joins them. Instant, no quality loss. */
    private static void quickTrim(Path src, EditSpec edit, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        Path tmpDir = Files.createTempDirectory("evadeclips-trim");
        try {
            List<Path> parts = new ArrayList<>();
            int i = 0;
            for (EditSpec.Segment s : edit.segments) {
                Path part = tmpDir.resolve("part" + (i++) + ".mp4");
                Ffmpeg.run(List.of("-ss", Library.fmt(s.start), "-i", src.toString(), "-t", Library.fmt(s.length()),
                        "-map", "0:v:0?", "-map", "0:a?", "-c", "copy", "-avoid_negative_ts", "make_zero", part.toString()), -1, null, job);
                parts.add(part);
                if (progress != null) progress.accept(i / (double) (edit.segments.size() + 1));
            }
            if (parts.size() == 1) {
                Ffmpeg.run(List.of("-i", parts.get(0).toString(), "-c", "copy", "-movflags", "+faststart", out.toString()), -1, null, job);
            } else {
                Path list = tmpDir.resolve("list.txt");
                StringBuilder sb = new StringBuilder();
                for (Path p : parts) sb.append("file '").append(p.toString().replace("'", "'\\''")).append("'\n");
                Files.writeString(list, sb.toString());
                Ffmpeg.run(List.of("-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", "-movflags", "+faststart", out.toString()), -1, null, job);
            }
            if (progress != null) progress.accept(1);
        } finally {
            deleteTree(tmpDir);
        }
    }

    private static void twoPass(Path src, Clip clip, EditSpec edit, Options o, Path out, double vKbps, int aKbps, double dur,
                                DoubleConsumer progress, Ffmpeg.Job job, Path tmp) throws IOException {
        boolean x264 = Ffmpeg.workingEncoders().contains("libx264");
        if (!x264) {
            Ffmpeg.run(buildArgs(src, clip, edit, o, out, vKbps, aKbps, null, tmp), dur, progress, job);
            return;
        }
        Path passDir = Files.createTempDirectory(tmp, "2pass");
        try {
            String log = passDir.resolve("pass").toString();
            List<String> p1 = buildArgs(src, clip, edit, o, Path.of(Ffmpeg.WINDOWS ? "NUL" : "/dev/null"), vKbps, aKbps, new String[]{"1", log}, tmp);
            Ffmpeg.run(p1, dur, progress == null ? null : p -> progress.accept(p * 0.35), job);
            List<String> p2 = buildArgs(src, clip, edit, o, out, vKbps, aKbps, new String[]{"2", log}, tmp);
            Ffmpeg.run(p2, dur, progress == null ? null : p -> progress.accept(0.35 + p * 0.65), job);
        } finally {
            deleteTree(passDir);
        }
    }

    // ------------------------------------------------------------------ ffmpeg version and long graphs

    private static volatile String versionOf;
    private static volatile int versionMajor = 99;

    /** ffmpeg's major version; 99 for development builds or when it can't be told. */
    static int ffmpegMajor() {
        String exe = Ffmpeg.ffmpeg();
        if (exe == null) return 99;
        if (exe.equals(versionOf)) return versionMajor;
        int v = parseMajor(Ffmpeg.capture(List.of(exe, "-hide_banner", "-version"), 15));
        versionMajor = v;
        versionOf = exe;
        return v;
    }

    static int parseMajor(String versionText) {
        Matcher m = Pattern.compile("ffmpeg version n?(\\d+)\\.(\\d+)").matcher(versionText == null ? "" : versionText);
        if (!m.find()) return 99;
        int v = Integer.parseInt(m.group(1));
        return v > 100 ? 99 : v;
    }

    /** -filter_complex with the graph, or the graph in a script file when it's long. */
    static List<String> graphArgs(String graph, Path tmp) throws IOException {
        if (graph.length() <= INLINE_GRAPH || tmp == null) return List.of("-filter_complex", graph);
        Path f = Files.createTempFile(tmp, "graph", ".txt");
        Files.writeString(f, graph, StandardCharsets.UTF_8);
        // ffmpeg 7 reads any option's value from a file with "-/"; older builds have -filter_complex_script.
        return List.of(ffmpegMajor() >= 7 ? "-/filter_complex" : "-filter_complex_script", f.toString());
    }

    // ------------------------------------------------------------------ geometry

    static double aspectRatio(String aspect, int w, int h) {
        return switch (aspect) {
            case "16:9" -> 16 / 9.0;
            case "9:16" -> 9 / 16.0;
            case "1:1" -> 1;
            case "4:5" -> 4 / 5.0;
            default -> w / (double) h;
        };
    }

    /** The part of the source frame that ends up in the export (crop mode). */
    static Region region(int w, int h, EditSpec e) {
        double r = aspectRatio(e.aspect, w, h);
        double sr = w / (double) h;
        double rw, rh;
        if (r < sr) { rh = h; rw = h * r; } else { rw = w; rh = w / r; }
        rw /= Math.max(1, e.zoom);
        rh /= Math.max(1, e.zoom);
        int cw = even(rw), ch = even(rh);
        int x = (int) Math.round((w - cw) * clamp(e.cropX));
        int y = (int) Math.round((h - ch) / 2.0);
        return new Region(cw, ch, x, y);
    }

    /** Final output size for an edit + preset. */
    public static int[] outputSize(Clip clip, EditSpec e, Options o) {
        int w = clip.width > 0 ? clip.width : 1920, h = clip.height > 0 ? clip.height : 1080;
        Preset p = o.preset;
        double r = aspectRatio(p == Preset.SHORTS && e.aspect.equals("original") ? "9:16" : e.aspect, w, h);
        boolean blur = isBlur(e, p) || isSplit(e, p);
        int ow, oh;
        if (blur) {
            // Canvas: fit the aspect around the source's short side.
            if (r < 1) { ow = Math.min(w, h); oh = (int) (ow / r); } else { oh = Math.min(w, h); ow = (int) (oh * r); }
        } else {
            Region rg = region(w, h, e);
            ow = rg.w; oh = rg.h;
        }
        if (p == Preset.SHORTS) {
            if (r < 1) { ow = 1080; oh = (int) Math.round(1080 / r); } else { oh = 1080; ow = (int) Math.round(1080 * r); }
        } else {
            int cap = o.maxHeight > 0 ? o.maxHeight : p.height;
            if (p == Preset.GIF) {
                // GIF preset is a width.
                if (ow > cap) { oh = (int) Math.round(oh * cap / (double) ow); ow = cap; }
            } else if (cap > 0) {
                // "1080p" means the short side, so vertical video keeps its quality.
                int shortSide = Math.min(ow, oh);
                if (shortSide > cap) {
                    double k = cap / (double) shortSide;
                    ow = (int) Math.round(ow * k);
                    oh = (int) Math.round(oh * k);
                }
            }
        }
        return new int[]{even(ow), even(oh)};
    }

    // ------------------------------------------------------------------ where the pieces are read from

    /**
     * An ffmpeg input reading a stretch of the source and the pieces it feeds. Pieces far apart get their own
     * fast-seeked input, so a highlight reel of a long recording doesn't decode everything in between; the
     * reversed pieces of a rewind get their own input too, so the rest of the export keeps streaming while
     * they're turned around.
     */
    static final class Source {
        final boolean reversed;
        double seek, lo, hi;
        final List<Integer> pieces = new ArrayList<>();

        Source(boolean reversed, double lo, double hi) { this.reversed = reversed; this.lo = lo; this.hi = hi; }
    }

    static List<Source> sources(List<EditSpec.Piece> pieces) {
        List<Source> out = null;
        // Many far-apart moments: read through more of the gaps rather than open dozens of inputs.
        for (double gap = SEEK_GAP; out == null || (out.size() > 24 && gap < 1e6); gap *= 2) out = sources(pieces, gap);
        return out;
    }

    private static List<Source> sources(List<EditSpec.Piece> pieces, double gap) {
        List<Source> out = new ArrayList<>();
        Source cur = null;
        for (int i = 0; i < pieces.size(); i++) {
            EditSpec.Piece p = pieces.get(i);
            boolean join;
            if (cur == null || cur.reversed != p.reverse()) join = false;
            else if (p.reverse()) join = Math.abs(p.end() - cur.lo) < 1e-3;
            else join = p.start() >= cur.hi - 1e-6 && p.start() - cur.hi <= gap;
            if (!join) {
                cur = new Source(p.reverse(), p.start(), p.end());
                out.add(cur);
            }
            cur.lo = Math.min(cur.lo, p.start());
            cur.hi = Math.max(cur.hi, p.end());
            cur.pieces.add(i);
        }
        // Only seek when it skips something worth skipping (the first part usually starts at the beginning).
        for (Source s : out) s.seek = s.lo > PREROLL + 1 ? s.lo - PREROLL : 0;
        return out;
    }

    // ------------------------------------------------------------------ the ffmpeg command

    static List<String> buildArgs(Path src, Clip clip, EditSpec e, Options o, Path out, double vKbps, int aKbps, String[] pass, Path tmp) throws IOException {
        e.fill();
        int w = clip.width > 0 ? clip.width : 1920, h = clip.height > 0 ? clip.height : 1080;
        Preset p = o.preset;
        boolean gif = p == Preset.GIF;
        // Both passes must see the exact same filter graph (audio included) or x264's stats won't line up.
        boolean audio = clip.hasAudio && !e.mute && !gif;
        double dur = e.outputDuration();
        int[] size = outputSize(clip, e, o);
        int ow = size[0], oh = size[1];

        // Fit the bitrate budget: if it's tiny, drop resolution / frame rate before it turns to mush.
        int srcFps = snapFps(clip.fps);
        int fps = o.fps > 0 ? o.fps : (p.fps > 0 ? Math.min(p.fps, srcFps) : 0);
        if (vKbps > 0) {
            int outFps = fps > 0 ? fps : srcFps;
            int floor = vKbps < LOW_VIDEO_KBPS ? 240 : 360;
            double bpp = vKbps * 1000 / (ow * (double) oh * outFps);
            while (bpp < 0.045 && Math.min(ow, oh) > floor) {
                int s = Math.min(ow, oh);
                double k = (s > 720 ? 720.0 : s > 540 ? 540.0 : s > 360 ? 360.0 : 240.0) / s;
                ow = even(ow * k); oh = even(oh * k);
                bpp = vKbps * 1000 / (ow * (double) oh * outFps);
            }
            if (bpp < 0.045 && outFps > 30) { fps = 30; outFps = 30; bpp = vKbps * 1000 / (ow * (double) oh * outFps); }
            if (vKbps < LOW_VIDEO_KBPS && bpp < 0.03 && outFps > 24) fps = 24;
        }
        int workFps = fps > 0 ? fps : srcFps;

        List<String> a = new ArrayList<>();
        List<EditSpec.Piece> pieces = e.pieces();
        int n = pieces.size();
        List<Source> sources = sources(pieces);
        Source[] srcOf = new Source[n];
        for (Source s : sources) {
            for (int i : s.pieces) srcOf[i] = s;
            if (s.seek > 0) a.addAll(List.of("-ss", Library.fmt(s.seek)));
            if (clip.duration > 0 && s.hi < clip.duration - 1) a.addAll(List.of("-t", Library.fmt(s.hi - s.seek + 1)));
            a.addAll(List.of("-i", src.toString()));
        }
        int input = sources.size();

        boolean split = isSplit(e, p);
        int camInput = -1;
        double camDur = Double.POSITIVE_INFINITY;
        if (split && e.camFile != null && !e.camFile.isBlank() && Files.exists(Path.of(e.camFile))) {
            // Read once and cut like the gameplay, so the facecam follows trims, cuts and speed changes.
            a.addAll(List.of("-i", e.camFile));
            camInput = input++;
            try {
                double d = Ffmpeg.probe(Path.of(e.camFile)).duration();
                if (d > 0) camDur = d;
            } catch (IOException ignored) {}
        }
        int titleInput = -1;
        Sprite title = null;
        if (!e.titleText.isBlank() && !gif) {
            title = crop(renderTitle(e.titleText, e.titlePosition, ow, oh));
            if (title != null) {
                double ts = e.titleSeconds > 0 ? Math.min(e.titleSeconds, dur) : dur;
                a.addAll(loopPng(title.image(), ts + 0.05, tmp));
                titleInput = input++;
            }
        }
        // Timed captions: all of them as one image stream (see captionTrack).
        List<CapItem> caps = new ArrayList<>();
        for (EditSpec.Caption c : e.captions) {
            if (c.text == null || c.text.isBlank()) continue;
            double os = e.toOutput(c.start), oe = Math.min(dur, e.toOutput(c.end));
            if (oe - os < 0.04) continue; // entirely inside a cut part
            caps.add(new CapItem(c, os, oe));
        }
        CaptionTrack capTrack = captionTrack(caps, ow, oh, dur, tmp);
        int capInput = -1;
        if (capTrack != null) {
            a.addAll(List.of("-f", "concat", "-safe", "0", "-i", capTrack.list().toString()));
            capInput = input++;
        }
        // Video overlays: each its own input, trimmed to the part that shows.
        List<double[]> ovTimes = new ArrayList<>();
        List<EditSpec.Overlay> ovs = new ArrayList<>();
        List<Boolean> ovAudio = new ArrayList<>();
        int ovInput = input;
        for (EditSpec.Overlay ov : e.overlays) {
            if (overlayFile(ov) == null) continue;
            double os = e.toOutput(ov.start);
            double len = Math.min(ov.length > 0 ? ov.length : dur, dur - os);
            if (len < 0.05) continue;
            a.addAll(List.of("-ss", Library.fmt(ov.inPoint), "-t", Library.fmt(len), "-i", ov.file));
            ovTimes.add(new double[]{os, os + len});
            ovs.add(ov);
            // An overlay's own sound plays even when the clip is muted or has no sound of its own.
            ovAudio.add(ov.audio && !gif && overlayHasAudio(ov.file));
            input++;
        }
        // Effects at their output times (ones inside cut parts are dropped).
        List<EditSpec.Effect> fx = new ArrayList<>();
        List<Double> fxAt = new ArrayList<>();
        for (EditSpec.Effect f : e.effects) {
            if (e.segmentAt(f.t) < 0) continue;
            fx.add(f);
            fxAt.add(e.toOutput(f.t));
        }
        int hitInput = -1;
        Sprite hit = null;
        if (fx.stream().anyMatch(f -> f.type.equals("hitmarker")) && !gif) {
            hit = crop(renderHitmarker(ow, oh));
            double last = 0;
            for (int k = 0; k < fx.size(); k++) if (fx.get(k).type.equals("hitmarker")) last = Math.max(last, fxAt.get(k) + Math.max(0.08, fx.get(k).length));
            if (hit != null) {
                a.addAll(loopPng(hit.image(), Math.min(dur, last) + 0.1, tmp));
                hitInput = input++;
            }
        }
        int wmInput = -1;
        Sprite wm = null;
        if (o.watermark != null) {
            if (!o.watermark.usable()) warnOnce("Watermark image not found: " + o.watermark.image());
            else {
                if (!o.watermark.imageReadable() && o.watermark.image() != null && !o.watermark.image().isBlank())
                    warnOnce("Watermark image not found: " + o.watermark.image() + " (using the text)");
                wm = crop(renderWatermark(o.watermark, ow, oh));
                if (wm != null) {
                    a.addAll(loopPng(wm.image(), dur, tmp));
                    wmInput = input++;
                }
            }
        }
        // Sounds for effects.
        List<String> sfxLabels = new ArrayList<>();
        List<String> sfxChains = new ArrayList<>();
        if (!gif) {
            for (int k = 0; k < fx.size(); k++) {
                EditSpec.Effect f = fx.get(k);
                Path snd = Sounds.file(f.sound);
                if (snd == null) continue;
                long ms = Math.max(0, Math.round(fxAt.get(k) * 1000));
                a.addAll(List.of("-i", snd.toString()));
                sfxChains.add("[" + input + ":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=" + Library.fmt(f.volume)
                        + ",adelay=" + ms + "|" + ms + "[sfx" + k + "]");
                sfxLabels.add("[sfx" + k + "]");
                input++;
            }
        }
        // Background music, looped if the export is longer than the song.
        int musicInput = -1;
        if (!gif && musicUsable(e.music)) {
            a.addAll(List.of("-stream_loop", "-1", "-i", e.music.file));
            musicInput = input++;
        }

        StringBuilder fc = new StringBuilder();
        // Every input's pieces: video split, audio (the single track, or game + mic (+ voice chat) mixed at the
        // chosen levels) split the same way.
        String[] vSrc = new String[n], aSrc = new String[n];
        for (int k = 0; k < sources.size(); k++) {
            List<Integer> idx = sources.get(k).pieces;
            int cnt = idx.size();
            if (cnt == 1) vSrc[idx.get(0)] = "[" + k + ":v]";
            else {
                fc.append("[").append(k).append(":v]split=").append(cnt);
                for (int j = 0; j < cnt; j++) { String l = "[vs" + k + "_" + j + "]"; fc.append(l); vSrc[idx.get(j)] = l; }
                fc.append(";");
            }
            if (!audio) continue;
            if (clip.audioTracks >= 2) {
                fc.append(trackMix(clip, e, k, "m" + k));
                if (cnt > 1) fc.append(",asplit=").append(cnt);
            } else if (cnt > 1) {
                fc.append("[").append(k).append(":a:0]asplit=").append(cnt);
            } else {
                aSrc[idx.get(0)] = "[" + k + ":a:0]";
                continue;
            }
            for (int j = 0; j < cnt; j++) { String l = "[as" + k + "_" + j + "]"; fc.append(l); aSrc[idx.get(j)] = l; }
            fc.append(";");
        }
        for (int i = 0; i < n; i++) {
            EditSpec.Piece s = pieces.get(i);
            double t0 = s.start() - srcOf[i].seek, t1 = s.end() - srcOf[i].seek;
            fc.append(vSrc[i]).append("trim=start=").append(Library.fmt(t0)).append(":end=").append(Library.fmt(t1))
                    .append(",setpts=(PTS-STARTPTS)/").append(Library.fmt(s.speed()));
            // Slow parts get in-between frames, so slow motion stays smooth instead of stuttering.
            if (s.speed() < 0.97 && !gif) {
                if ("flow".equals(e.smoothing)) fc.append(",minterpolate=fps=").append(workFps).append(":mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1");
                else if ("blend".equals(e.smoothing)) fc.append(",framerate=fps=").append(workFps);
            }
            fc.append(",fps=").append(workFps).append(pieceTail(s, w, h, workFps)).append(",setsar=1[v").append(i).append("];");
            if (audio) {
                fc.append(aSrc[i]).append("atrim=start=").append(Library.fmt(t0)).append(":end=").append(Library.fmt(t1))
                        .append(",asetpts=PTS-STARTPTS").append(atempo(s.speed())).append(audioTail(s)).append("[a").append(i).append("];");
            }
        }
        for (int i = 0; i < n; i++) fc.append("[v").append(i).append("]").append(audio ? "[a" + i + "]" : "");
        fc.append("concat=n=").append(n).append(":v=1:a=").append(audio ? 1 : 0).append("[vc]").append(audio ? "[ac]" : "").append(";");

        // A webcam file is cut exactly like the gameplay (the same pieces, shifted by its offset).
        if (camInput >= 0) {
            String[] cSrc = new String[n];
            if (n == 1) cSrc[0] = "[" + camInput + ":v]";
            else {
                fc.append("[").append(camInput).append(":v]split=").append(n);
                for (int i = 0; i < n; i++) { cSrc[i] = "[cs" + i + "]"; fc.append(cSrc[i]); }
                fc.append(";");
            }
            for (int i = 0; i < n; i++) {
                EditSpec.Piece s = pieces.get(i);
                double len = s.end() - s.start(), cs = s.start() + e.camOffset, ce = s.end() + e.camOffset;
                double a0 = Math.max(0, cs), b0 = Math.min(camDur, ce);
                if (b0 - a0 < 0.04) {
                    // Nothing of the webcam at this moment: hold its nearest frame.
                    a0 = cs >= camDur ? Math.max(0, camDur - 0.2) : 0;
                    b0 = Math.min(camDur, a0 + Math.min(0.2, Math.max(0.04, len)));
                }
                double pre = Math.max(0, Math.min(len, a0 - cs)), post = Math.max(0, len - pre - (b0 - a0));
                fc.append(cSrc[i]).append("trim=start=").append(Library.fmt(a0)).append(":end=").append(Library.fmt(b0)).append(",setpts=PTS-STARTPTS");
                if (pre > 0.001 || post > 0.001) {
                    fc.append(",tpad=");
                    if (pre > 0.001) fc.append("start_mode=clone:start_duration=").append(Library.fmt(pre));
                    if (pre > 0.001 && post > 0.001) fc.append(":");
                    if (post > 0.001) fc.append("stop_mode=clone:stop_duration=").append(Library.fmt(post));
                }
                fc.append(",setpts=PTS/").append(Library.fmt(s.speed())).append(",fps=").append(workFps).append(pieceTail(s, 640, 480, workFps))
                        .append(",setsar=1[cp").append(i).append("];");
            }
            for (int i = 0; i < n; i++) fc.append("[cp").append(i).append("]");
            fc.append("concat=n=").append(n).append(":v=1:a=0,tpad=stop_mode=clone:stop=-1[camc];");
        }

        // Zoom keyframes and zoom punches glide over the source frame (in a split layout, the gameplay only).
        String zoomExpr = zoomFilter(e, fx, fxAt, w, h, workFps);

        // Framing.
        boolean blur = isBlur(e, p);
        String zoomCrop = zoomCrop(w, h, e);
        if (split) {
            int camOut = even(oh * Math.max(0.15, Math.min(0.7, e.camShare)));
            int gameOut = oh - camOut;
            fc.append("[vc]");
            if (camInput < 0) fc.append("split[gsrc][csrc];[csrc]crop=").append(even(w * e.camW)).append(":").append(even(h * e.camH)).append(":")
                    .append((int) Math.round(w * clamp(e.camX))).append(":").append((int) Math.round(h * clamp(e.camY))).append("[cam0];[gsrc]");
            if (!zoomExpr.isEmpty()) fc.append(zoomExpr).append(",");
            // gameplay: the widest slice of the frame that fills the bottom part
            double gr = ow / (double) gameOut, sr = w / (double) h;
            int gw = gr < sr ? even(h * gr) : w, gh = gr < sr ? h : even(w / gr);
            fc.append("crop=").append(gw).append(":").append(gh).append(":").append((int) Math.round((w - gw) * clamp(e.cropX))).append(":").append((h - gh) / 2)
                    .append(",scale=").append(ow).append(":").append(gameOut).append(":flags=lanczos,setsar=1[game];");
            String cam = camInput >= 0 ? "[camc]" : "[cam0]";
            fc.append(cam).append("scale=").append(ow).append(":").append(camOut).append(":force_original_aspect_ratio=increase:flags=lanczos,crop=")
                    .append(ow).append(":").append(camOut).append(",fps=").append(workFps).append(",setsar=1[camf];")
                    .append("[camf][game]vstack=inputs=2:shortest=1,drawbox=x=0:y=").append(camOut - 2).append(":w=iw:h=4:color=black@0.9:t=fill");
        } else if (blur) {
            fc.append("[vc]");
            if (!zoomExpr.isEmpty()) fc.append(zoomExpr).append(",");
            fc.append(zoomCrop.isEmpty() ? "null" : zoomCrop).append(",split[fg0][bg0];")
                    .append("[bg0]scale=").append(ow).append(":").append(oh).append(":force_original_aspect_ratio=increase,crop=")
                    .append(ow).append(":").append(oh).append(",boxblur=20:2,eq=brightness=-0.08[bg];")
                    .append("[fg0]scale=").append(ow).append(":").append(oh).append(":force_original_aspect_ratio=decrease[fg];")
                    .append("[bg][fg]overlay=(W-w)/2:(H-h)/2");
        } else {
            Region r = region(w, h, e);
            fc.append("[vc]");
            if (!zoomExpr.isEmpty()) fc.append(zoomExpr).append(",");
            if (r.w != w || r.h != h) fc.append("crop=").append(r.w).append(":").append(r.h).append(":").append(r.x).append(":").append(r.y).append(",");
            fc.append("scale=").append(ow).append(":").append(oh).append(":flags=lanczos");
        }
        if (fps > 0) fc.append(",fps=").append(fps);
        fc.append(",setsar=1");
        if (e.fadeIn > 0) fc.append(",fade=t=in:st=0:d=").append(Library.fmt(e.fadeIn));
        if (e.fadeOut > 0) fc.append(",fade=t=out:st=").append(Library.fmt(Math.max(0, dur - e.fadeOut))).append(":d=").append(Library.fmt(e.fadeOut));
        String look = effectFilter(e.effect);
        if (!look.isEmpty()) fc.append(",").append(look);
        // Flash: a white frame that fades fast.
        for (int k = 0; k < fx.size(); k++) {
            EditSpec.Effect f = fx.get(k);
            if (!f.type.equals("flash")) continue;
            double t0 = fxAt.get(k), len = Math.max(0.08, f.length);
            double peak = 0.35 + 0.55 * clamp(f.strength);
            fc.append(",drawbox=x=0:y=0:w=iw:h=ih:color=white@").append(Library.fmt(peak)).append(":t=fill:enable='between(t,")
                    .append(Library.fmt(t0)).append(",").append(Library.fmt(t0 + len * 0.35)).append(")'")
                    .append(",drawbox=x=0:y=0:w=iw:h=ih:color=white@").append(Library.fmt(peak * 0.4)).append(":t=fill:enable='between(t,")
                    .append(Library.fmt(t0 + len * 0.35)).append(",").append(Library.fmt(t0 + len)).append(")'");
        }
        fc.append("[base]");
        String cur = "[base]";
        // Blurred / pixelated / blacked-out areas (names, chat, coordinates), in output-frame coordinates.
        int mi = 0;
        for (EditSpec.Mask m : e.masks) {
            double ms = e.toOutput(m.start), me = e.toOutput(m.end);
            if (me - ms < 0.04) continue;
            int mw = even(ow * Math.max(0.02, Math.min(1, m.w))), mh = even(oh * Math.max(0.02, Math.min(1, m.h)));
            int mx = (int) Math.round(ow * m.x), my = (int) Math.round(oh * m.y);
            // A sliver too thin to blur grows to 8 px around its centre.
            if (mw < 8) { mx += (mw - Math.min(ow, 8)) / 2; mw = Math.min(ow, 8); }
            if (mh < 8) { my += (mh - Math.min(oh, 8)) / 2; mh = Math.min(oh, 8); }
            mx = Math.max(0, Math.min(ow - mw, mx));
            my = Math.max(0, Math.min(oh - mh, my));
            String en = "enable='between(t," + Library.fmt(ms) + "," + Library.fmt(me) + ")'";
            if (m.kind.equals("black")) {
                fc.append(";").append(cur).append("drawbox=x=").append(mx).append(":y=").append(my).append(":w=").append(mw).append(":h=").append(mh)
                        .append(":color=black:t=fill:").append(en).append("[mk").append(mi).append("]");
            } else {
                String look2 = m.kind.equals("pixelate")
                        ? "scale=" + Math.max(2, mw / 14) + ":" + Math.max(2, mh / 14) + ",scale=" + mw + ":" + mh + ":flags=neighbor"
                        : boxblur(mw, mh);
                fc.append(";").append(cur).append("split[mka").append(mi).append("][mkb").append(mi).append("];[mkb").append(mi).append("]crop=")
                        .append(mw).append(":").append(mh).append(":").append(mx).append(":").append(my).append(",").append(look2).append("[mkc").append(mi)
                        .append("];[mka").append(mi).append("][mkc").append(mi).append("]overlay=").append(mx).append(":").append(my).append(":").append(en)
                        .append("[mk").append(mi).append("]");
            }
            cur = "[mk" + mi + "]";
            mi++;
        }
        if (titleInput >= 0) {
            fc.append(";[").append(titleInput).append(":v]format=rgba");
            double ts = e.titleSeconds > 0 ? Math.min(e.titleSeconds, dur) : dur;
            if (e.titleSeconds > 0) fc.append(",fade=t=out:st=").append(Library.fmt(Math.max(0, ts - 0.4))).append(":d=0.4:alpha=1");
            fc.append(",fade=t=in:st=0:d=0.3:alpha=1[title];").append(cur).append("[title]overlay=").append(title.x()).append(":").append(title.y())
                    .append(":eof_action=pass[b0]");
            cur = "[b0]";
        }
        if (capInput >= 0) {
            fc.append(";[").append(capInput).append(":v]format=rgba[caps];").append(cur).append("[caps]overlay=").append(capTrack.x()).append(":")
                    .append(capTrack.y()).append(":eof_action=pass[cap]");
            cur = "[cap]";
        }
        List<String> ovAudioLabels = new ArrayList<>();
        for (int k = 0; k < ovs.size(); k++) {
            EditSpec.Overlay ov = ovs.get(k);
            double[] t = ovTimes.get(k);
            int in = ovInput + k;
            int vw = even(ow * Math.max(0.05, Math.min(1, ov.scale)));
            fc.append(";[").append(in).append(":v]scale=").append(vw).append(":-2,fps=").append(workFps).append(",format=rgba");
            if (ov.opacity < 0.999) fc.append(",colorchannelmixer=aa=").append(Library.fmt(Math.max(0, ov.opacity)));
            fc.append(",setpts=PTS-STARTPTS+").append(Library.fmt(t[0])).append("/TB[ov").append(k).append("];")
                    .append(cur).append("[ov").append(k).append("]overlay=x='(W*").append(Library.fmt(ov.x)).append(")-w/2':y='(H*")
                    .append(Library.fmt(ov.y)).append(")-h/2':eof_action=pass:enable='between(t,").append(Library.fmt(t[0])).append(",")
                    .append(Library.fmt(t[1])).append(")'[o").append(k).append("]");
            cur = "[o" + k + "]";
            if (ovAudio.get(k)) {
                long ms = Math.round(t[0] * 1000);
                fc.append(";[").append(in).append(":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=").append(Library.fmt(ov.volume))
                        .append(",adelay=").append(ms).append("|").append(ms).append("[oa").append(k).append("]");
                ovAudioLabels.add("[oa" + k + "]");
            }
        }
        if (hitInput >= 0) {
            StringBuilder en = new StringBuilder();
            for (int k = 0; k < fx.size(); k++) {
                if (!fx.get(k).type.equals("hitmarker")) continue;
                if (en.length() > 0) en.append("+");
                double t0 = fxAt.get(k);
                en.append("between(t,").append(Library.fmt(t0)).append(",").append(Library.fmt(t0 + Math.max(0.08, fx.get(k).length))).append(")");
            }
            fc.append(";[").append(hitInput).append(":v]format=rgba[hit];").append(cur).append("[hit]overlay=").append(hit.x()).append(":").append(hit.y())
                    .append(":eof_action=pass:enable='").append(en).append("'[hm]");
            cur = "[hm]";
        }
        if (wmInput >= 0) {
            fc.append(";[").append(wmInput).append(":v]format=rgba[wm];").append(cur).append("[wm]overlay=").append(wm.x()).append(":").append(wm.y())
                    .append(":eof_action=pass[wmo]");
            cur = "[wmo]";
        }
        fc.append(";").append(cur);
        if (gif) {
            fc.append("split[g0][g1];[g0]palettegen=stats_mode=diff:max_colors=200[pal];[g1][pal]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle[vout]");
        } else {
            fc.append("format=yuv420p[vout]");
        }

        // Audio: the clip's sound (or silence, when the only sound is an effect, an overlay or music), then music,
        // overlays and effect sounds on top, limited, and evened out to -14 LUFS.
        boolean anyAudio = audio || !sfxLabels.isEmpty() || !ovAudioLabels.isEmpty() || musicInput >= 0;
        if (anyAudio) {
            if (audio) {
                fc.append(";[ac]volume=").append(Library.fmt(e.volume));
                if (e.fadeIn > 0) fc.append(",afade=t=in:st=0:d=").append(Library.fmt(e.fadeIn));
                if (e.fadeOut > 0) fc.append(",afade=t=out:st=").append(Library.fmt(Math.max(0, dur - e.fadeOut))).append(":d=").append(Library.fmt(e.fadeOut));
                fc.append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo");
            } else {
                fc.append(";anullsrc=r=48000:cl=stereo,atrim=duration=").append(Library.fmt(dur)).append(",aformat=sample_fmts=fltp:channel_layouts=stereo");
            }
            String tail = o.normalize && p != Preset.QUICK_TRIM ? LOUDNORM : "";
            List<String> extra = new ArrayList<>();
            if (musicInput >= 0) {
                // Duck the music under the clip's own sound: the game and your mic push it down while they're loud.
                boolean duck = audio && e.music.duck;
                fc.append(duck ? "[am0];[am0]asplit=2[amain][sc]" : "[amain]");
                fc.append(";").append(musicChain(e.music, musicInput, dur, "mus"));
                if (duck) {
                    fc.append(";[mus][sc]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=300[mduck]");
                    extra.add("[mduck]");
                } else extra.add("[mus]");
            }
            extra.addAll(ovAudioLabels);
            extra.addAll(sfxLabels);
            if (extra.isEmpty()) fc.append(tail).append("[aout]");
            else {
                if (musicInput < 0) fc.append("[amain]");
                for (String c : sfxChains) fc.append(";").append(c);
                fc.append(";[amain]").append(String.join("", extra)).append("amix=inputs=").append(1 + extra.size())
                        .append(":duration=first:normalize=0,alimiter=limit=0.97").append(tail).append("[aout]");
            }
        }

        a.addAll(graphArgs(fc.toString(), tmp));
        a.addAll(List.of("-map", "[vout]"));
        if (anyAudio) a.addAll(List.of("-map", "[aout]"));

        if (gif) {
            a.addAll(List.of("-loop", "0", out.toString()));
            return a;
        }

        String enc = pickEncoder(o, pass != null || vKbps > 0);
        a.addAll(List.of("-c:v", enc));
        if (vKbps > 0) {
            String b = Math.round(vKbps) + "k";
            a.addAll(List.of("-b:v", b, "-maxrate", Math.round(vKbps * 1.5) + "k", "-bufsize", Math.round(vKbps * 2) + "k"));
            if (enc.equals("libx264")) a.addAll(List.of("-preset", "medium"));
            if (pass != null) a.addAll(List.of("-pass", pass[0], "-passlogfile", pass[1]));
        } else {
            boolean high = p == Preset.SOURCE || p == Preset.YOUTUBE;
            a.addAll(qualityArgs(enc, high ? 18 : 21, (long) (ow * (double) oh * workFps * (high ? 0.15 : 0.10))));
        }
        a.addAll(List.of("-pix_fmt", "yuv420p"));
        if (enc.equals("libx264") || enc.equals("h264_nvenc")) a.addAll(List.of("-profile:v", "high"));
        // A size-targeted export always has a budget for its sound; never fall back to 192k there.
        if (anyAudio) a.addAll(List.of("-c:a", "aac", "-b:a", (aKbps > 0 ? aKbps : vKbps > 0 ? 64 : 192) + "k", "-ar", "48000"));
        if (pass != null && pass[0].equals("1")) a.addAll(List.of("-f", "mp4"));
        a.addAll(List.of("-movflags", "+faststart", out.toString()));
        return a;
    }

    /**
     * After a piece's frames are at the output rate: played backwards for a rewind (long high-resolution pieces
     * are turned around at half size, because every frame is held in memory until the piece ends), then its
     * freeze frame.
     */
    private static String pieceTail(EditSpec.Piece s, int w, int h, int fps) {
        StringBuilder sb = new StringBuilder();
        if (s.reverse()) {
            double bytes = s.playLength() * fps * w * (double) h * 1.5;
            if (bytes <= 900e6) sb.append(",reverse");
            else sb.append(",scale=").append(even(w / 2.0)).append(":").append(even(h / 2.0)).append(",reverse,scale=").append(w).append(":").append(h);
        }
        if (s.hold() > 0) sb.append(",tpad=stop_mode=clone:stop_duration=").append(Library.fmt(s.hold()));
        return sb.toString();
    }

    /** The audio side of {@link #pieceTail}: backwards for a rewind, then silence under the freeze frame. */
    private static String audioTail(EditSpec.Piece s) {
        StringBuilder sb = new StringBuilder();
        if (s.reverse()) sb.append(",areverse");
        if (s.hold() > 0) sb.append(",apad=pad_dur=").append(Library.fmt(s.hold()));
        return sb.toString();
    }

    /**
     * boxblur with each plane's radius inside what that plane allows: the chroma planes of 4:2:0 video are half
     * size, so a thin area on a small export (GIF, 360p) would otherwise fail the export.
     */
    static String boxblur(int mw, int mh) {
        int m = Math.min(mw, mh);
        int r = Math.max(1, Math.min(m / 5 - 1, m / 2));
        int c = Math.max(1, Math.min(r, m / 4));
        return "boxblur=lr=" + r + ":lp=4:cr=" + c + ":cp=4";
    }

    /** The music input as a chain: from its start point, cut to the export's length, at its level, faded out. */
    static String musicChain(EditSpec.Music m, int input, double dur, String label) {
        StringBuilder sb = new StringBuilder("[").append(input).append(":a]");
        if (m.inPoint > 0.001) sb.append("atrim=start=").append(Library.fmt(m.inPoint)).append(",asetpts=PTS-STARTPTS,");
        sb.append("atrim=duration=").append(Library.fmt(dur)).append(",volume=").append(Library.fmt(m.volume));
        // starting mid-song: a quick fade in instead of a click
        if (m.inPoint > 0.001) sb.append(",afade=t=in:st=0:d=").append(Library.fmt(Math.min(0.25, dur / 4)));
        double f = Math.min(Math.max(0, m.fadeOut), dur / 2);
        if (f > 0.01) sb.append(",afade=t=out:st=").append(Library.fmt(Math.max(0, dur - f))).append(":d=").append(Library.fmt(f));
        return sb.append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[").append(label).append("]").toString();
    }

    /** A transparent PNG layer as a looping input for {@code len} seconds. */
    private static List<String> loopPng(BufferedImage img, double len, Path tmp) throws IOException {
        Path png = Files.createTempFile(tmp, "layer", ".png");
        writePng(img, png);
        return List.of("-loop", "1", "-framerate", "30", "-t", Library.fmt(Math.max(0.1, len)), "-i", png.toString());
    }

    /** PNG with light compression: layers are mostly transparent, so they stay small, and many write quickly. */
    static void writePng(BufferedImage img, Path f) throws IOException {
        var it = ImageIO.getImageWritersByFormatName("png");
        if (!it.hasNext()) { ImageIO.write(img, "png", f.toFile()); return; }
        ImageWriter w = it.next();
        Files.deleteIfExists(f);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f.toFile())) {
            if (out == null) { ImageIO.write(img, "png", f.toFile()); return; }
            w.setOutput(out);
            ImageWriteParam pr = w.getDefaultWriteParam();
            if (pr.canWriteCompressed()) {
                pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                pr.setCompressionQuality(0.8f);
            }
            w.write(null, new IIOImage(img, null, null), pr);
        } finally {
            w.dispose();
        }
    }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static void warnOnce(String msg) {
        if (WARNED.add(msg)) System.err.println("[Evade Clips] " + msg);
    }

    static boolean isSplit(EditSpec e, Preset p) {
        return "split".equals(e.fitMode) && (!e.aspect.equals("original") || p == Preset.SHORTS) && !e.aspect.equals("16:9");
    }

    static boolean isBlur(EditSpec e, Preset p) {
        return (e.fitMode.equals("blur") && !e.aspect.equals("original")) || (p == Preset.SHORTS && e.aspect.equals("original") && !"split".equals(e.fitMode));
    }

    /** Speed of the export around source time t (for effects that last a moment of output time). */
    private static double speedAt(EditSpec e, double t) {
        int i = e.segmentAt(t);
        return i < 0 ? 1 : Math.max(0.1, e.segments.get(i).speedAt(t));
    }

    /**
     * zoompan for zoom keyframes and zoom punches, or "" when there are none. Keyframes glide with an ease in and
     * out; a punch zooms in quickly and settles back. The frame is upscaled first so slow glides don't jitter.
     * Keyframes inside cut parts count at the cut, so a zoom-out that falls in a cut still happens there.
     */
    static String zoomFilter(EditSpec e, List<EditSpec.Effect> fx, List<Double> fxAt, int w, int h, int fps) {
        List<EditSpec.ZoomKey> keys = e.sortedZooms();
        StringBuilder punch = new StringBuilder();
        for (int k = 0; k < fx.size(); k++) {
            EditSpec.Effect f = fx.get(k);
            if (!f.type.equals("punch")) continue;
            double t0 = fxAt.get(k), len = Math.max(0.15, f.length / speedAt(e, f.t));
            punch.append("+").append(Library.fmt(0.12 + 0.3 * clamp(f.strength))).append("*sin(PI*clip((it-").append(Library.fmt(t0)).append(")/")
                    .append(Library.fmt(len)).append(",0,1))");
        }
        // Screen shake: zoom in a touch (so no edges show) and jitter the view, fading out over the shake.
        StringBuilder shakeZ = new StringBuilder(), jx = new StringBuilder(), jy = new StringBuilder();
        for (int k = 0; k < fx.size(); k++) {
            EditSpec.Effect f = fx.get(k);
            if (!f.type.equals("shake") || f.length <= 0) continue;
            double t0 = fxAt.get(k), len = Math.max(0.1, f.length / speedAt(e, f.t));
            String env = "between(it," + Library.fmt(t0) + "," + Library.fmt(t0 + len) + ")*(1-(it-" + Library.fmt(t0) + ")/" + Library.fmt(len) + ")";
            double st = 0.3 + 0.7 * clamp(f.strength);
            shakeZ.append("+").append(Library.fmt(0.07 * st)).append("*").append(env);
            jx.append("+iw*").append(Library.fmt(0.022 * st)).append("*").append(env).append("*sin(it*71)");
            jy.append("+ih*").append(Library.fmt(0.022 * st)).append("*").append(env).append("*cos(it*53)");
        }
        punch.append(shakeZ);
        if (keys.isEmpty() && punch.length() == 0) return "";
        double[] ts = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) ts[i] = e.toOutput(keys.get(i).t);
        String z = keyExpr(keys, ts, k -> Math.max(1, k.zoom), 1) + punch;
        String cx = keyExpr(keys, ts, k -> clamp(k.cx), 0.5), cy = keyExpr(keys, ts, k -> clamp(k.cy), 0.5);
        return "scale=" + even(w * 2) + ":" + even(h * 2) + ":flags=bicubic,zoompan=z='" + z + "':x='clip(iw*(" + cx + ")-iw/zoom/2" + jx + ",0,iw-iw/zoom)':y='clip(ih*("
                + cy + ")-ih/zoom/2" + jy + ",0,ih-ih/zoom)':d=1:s=" + w + "x" + h + ":fps=" + fps;
    }

    /**
     * Eased piecewise curve through keyframe values, as an ffmpeg expression in output time (it). Keyframes that
     * land on the same moment (both sides of a cut) switch there in one step.
     */
    private static String keyExpr(List<EditSpec.ZoomKey> keys, double[] ts, java.util.function.ToDoubleFunction<EditSpec.ZoomKey> v, double none) {
        if (keys.isEmpty()) return Library.fmt(none);
        StringBuilder sb = new StringBuilder("(").append(Library.fmt(v.applyAsDouble(keys.get(0))));
        for (int i = 1; i < keys.size(); i++) {
            double dv = v.applyAsDouble(keys.get(i)) - v.applyAsDouble(keys.get(i - 1));
            if (Math.abs(dv) < 1e-6) continue;
            double t0 = ts[i - 1], d = ts[i] - t0;
            if (d < 0.002) {
                sb.append("+").append(Library.fmt(dv)).append("*gte(it,").append(Library.fmt(Math.max(0, t0 - 0.001))).append(")");
                continue;
            }
            String u = "clip((it-" + Library.fmt(t0) + ")/" + Library.fmt(d) + ",0,1)";
            sb.append("+").append(Library.fmt(dv)).append("*").append(u).append("*").append(u).append("*(3-2*").append(u).append(")");
        }
        return sb.append(")").toString();
    }

    /**
     * Quality-targeted encoding with a peak ceiling, so a busy clip can't balloon: 1080p60 "Source quality" tops
     * out around 19 Mbit/s (~140 MB a minute), while calm footage comes out far smaller.
     */
    static List<String> qualityArgs(String enc, int crf, long capBits) {
        String max = capBits / 1000 + "k", buf = capBits * 2 / 1000 + "k", avg = capBits * 6 / 10 / 1000 + "k";
        String base = enc.replaceFirst("^(h264|hevc)", "");
        return switch (base) {
            case "_nvenc" -> List.of("-preset", "p6", "-rc", "vbr", "-cq", String.valueOf(crf + 1), "-b:v", avg, "-maxrate", max, "-bufsize", buf);
            case "_qsv" -> List.of("-preset", "slow", "-b:v", avg, "-maxrate", max, "-bufsize", buf);
            case "_amf" -> List.of("-quality", "quality", "-rc", "vbr_peak", "-b:v", avg, "-maxrate", max, "-bufsize", buf);
            case "_videotoolbox" -> List.of("-b:v", avg, "-maxrate", max, "-bufsize", buf);
            default -> enc.equals("libx264") || enc.equals("libx265")
                    ? List.of("-preset", "medium", "-crf", String.valueOf(enc.equals("libx265") ? crf + 5 : crf), "-maxrate", max, "-bufsize", buf)
                    : List.of("-q:v", "3", "-maxrate", max, "-bufsize", buf);
        };
    }

    // ------------------------------------------------------------------ shrink

    /**
     * Re-encodes a clip to HEVC (GPU if possible) to free space: usually 40-60% smaller at the same look. Audio
     * and every track are copied untouched. Returns the new file, or null if it wouldn't save at least 15%.
     */
    public static Path shrink(Path src, Clip clip, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        List<String> hevc = Ffmpeg.workingEncoders("hevc");
        String enc = hevc.isEmpty() ? "libx264" : hevc.get(0);
        int fps = snapFps(clip.fps);
        long cap = (long) (Math.max(1, clip.width) * (double) Math.max(1, clip.height) * fps * (Ffmpeg.isHevc(enc) ? 0.05 : 0.08));
        List<String> a = new ArrayList<>(List.of("-i", src.toString(), "-map", "0:v:0", "-map", "0:a?", "-c:v", enc));
        a.addAll(qualityArgs(enc, Ffmpeg.isHevc(enc) ? 23 : 24, Math.max(1_000_000, cap)));
        a.addAll(List.of("-pix_fmt", "yuv420p", "-c:a", "copy"));
        if (Ffmpeg.isHevc(enc)) a.addAll(List.of("-tag:v", "hvc1"));
        a.addAll(List.of("-movflags", "+faststart", out.toString()));
        Ffmpeg.run(a, clip.duration, progress, job);
        if (Files.size(out) > Files.size(src) * 0.85) {
            Files.deleteIfExists(out);
            return null;
        }
        return out;
    }

    private static String pickEncoder(Options o, boolean sizeTargeted) {
        List<String> enc = Ffmpeg.workingEncoders();
        // x264 is the most accurate at hitting a size; GPU encoders are much faster for everything else.
        if (sizeTargeted && enc.contains("libx264")) return "libx264";
        if (o.gpu) return enc.get(0);
        return enc.contains("libx264") ? "libx264" : enc.get(0);
    }

    private static String zoomCrop(int w, int h, EditSpec e) {
        if (e.zoom <= 1.001) return "";
        int cw = even(w / e.zoom), ch = even(h / e.zoom);
        int x = (int) Math.round((w - cw) * clamp(e.cropX)), y = (h - ch) / 2;
        return "crop=" + cw + ":" + ch + ":" + x + ":" + y;
    }

    /** atempo only accepts 0.5..100 per instance, so chain it for big slow-downs. */
    static String atempo(double speed) {
        if (Math.abs(speed - 1) < 1e-6) return "";
        StringBuilder sb = new StringBuilder();
        double s = speed;
        while (s < 0.5) { sb.append(",atempo=0.5"); s /= 0.5; }
        while (s > 100) { sb.append(",atempo=100"); s /= 100; }
        sb.append(",atempo=").append(Library.fmt(s));
        return sb.toString();
    }

    // ------------------------------------------------------------------ audio tracks

    /** A clip's audio track title, or the usual one by position (Game, Mic, Voice chat) when it has none. */
    public static String trackName(Clip c, int i) {
        if (c.trackNames != null && i >= 0 && i < c.trackNames.size()) {
            String n = c.trackNames.get(i);
            if (n != null && !n.isBlank()) return n.trim();
        }
        return i == 0 ? "Game" : i == 1 ? "Mic" : i == 2 ? "Voice chat" : "Track " + (i + 1);
    }

    /** The edit's level for one of a clip's audio tracks (your mic and voice chat have their own sliders). */
    public static double trackGain(Clip c, EditSpec e, int i) {
        String n = trackName(c, i);
        return n.equalsIgnoreCase("Mic") ? e.micVolume : n.equalsIgnoreCase("Voice chat") ? e.voiceVolume : 1;
    }

    /** The mic track's filters: optional clean-up (rumble and steady hiss), then its level. */
    static String micChain(EditSpec e) {
        return (e.micCleanup ? "highpass=f=90,afftdn=nr=12:nf=-40:tn=1," : "") + "volume=" + Library.fmt(e.micVolume);
    }

    /**
     * Mixes a clip's audio tracks (game, mic, voice chat) at the levels in the edit; ends with the chain open so
     * the caller can append (",asplit=..." or a label).
     */
    static String trackMix(Clip clip, EditSpec e, int input, String tag) {
        StringBuilder sb = new StringBuilder();
        StringBuilder ins = new StringBuilder();
        for (int i = 0; i < clip.audioTracks; i++) {
            String chain = trackName(clip, i).equalsIgnoreCase("Mic") ? micChain(e) : "volume=" + Library.fmt(trackGain(clip, e, i));
            String lbl = "[" + tag + "t" + i + "]";
            sb.append("[").append(input).append(":a:").append(i).append("]").append(chain).append(lbl).append(";");
            ins.append(lbl);
        }
        sb.append(ins).append("amix=inputs=").append(clip.audioTracks).append(":duration=longest:normalize=0");
        return sb.toString();
    }

    /** The ffmpeg filter for a color look ("" for none). */
    public static String effectFilter(String effect) {
        return switch (effect == null ? "none" : effect) {
            case "vibrant" -> "eq=saturation=1.4:contrast=1.07:gamma=0.97";
            case "cinematic" -> "eq=contrast=1.1:saturation=0.85,colorbalance=rs=-0.06:bs=0.08:rh=0.08:bh=-0.06,"
                    + "drawbox=x=0:y=0:w=iw:h=ih*0.11:color=black:t=fill,drawbox=x=0:y=ih*0.89:w=iw:h=ih*0.11:color=black:t=fill";
            case "bw" -> "hue=s=0,eq=contrast=1.15";
            case "retro" -> "curves=preset=vintage,noise=alls=10:allf=t,vignette=PI/4";
            case "warm" -> "colorbalance=rs=0.08:gs=0.02:bs=-0.08:rm=0.05:bm=-0.05";
            case "cool" -> "colorbalance=rs=-0.07:bs=0.09:rm=-0.04:bm=0.06";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ montage

    /**
     * Joins several clips (each with its own edits: cuts, speed, freeze frames, mic level) into one video. Clips
     * of different sizes are fitted to one canvas; clips without sound get silence so the audio stays in sync.
     * Clips join with the chosen transition: a short dip to black (the default), a hard cut, or a crossfade,
     * slide, zoom or flash that overlaps the clips by the transition's length.
     */
    public static Path montage(List<Clip> clips, Path root, Options o, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        if (clips.size() < 1) throw new IOException("Pick at least one clip");
        RESERVED.add(key(out));
        Path tmp = null;
        try {
            Files.createDirectories(out.getParent());
            tmp = Files.createTempDirectory("evadeclips-montage");
            int maxH = 0, fps = 30;
            for (Clip c : clips) { maxH = Math.max(maxH, c.height); fps = Math.max(fps, snapFps(c.fps)); }
            int H = Math.min(o.maxHeight > 0 ? o.maxHeight : 1080, maxH > 0 ? maxH : 1080);
            if (H % 2 == 1) H--;
            int W = even(H * 16 / 9.0);
            fps = Math.min(o.fps > 0 ? o.fps : 60, fps);
            String tr = transition(o.transition);
            boolean overlap = clips.size() > 1 && !tr.equals("none") && !tr.equals("dip");

            List<String> a = new ArrayList<>();
            StringBuilder fc = new StringBuilder();
            String[] cv = new String[clips.size()], ca = new String[clips.size()];
            double[] lens = new double[clips.size()];
            for (int ci = 0; ci < clips.size(); ci++) {
                Clip c = clips.get(ci);
                a.addAll(List.of("-i", c.path(root).toString()));
                EditSpec e = (c.edit != null ? c.edit : EditSpec.whole(c.duration)).fill();
                boolean audio = c.hasAudio && !e.mute;
                List<EditSpec.Piece> segs = e.pieces();
                int segN = segs.size();
                double clipLen = e.outputDuration();
                lens[ci] = clipLen;
                // Each clip's sound: every track mixed by name (game; your mic and voice chat at their levels),
                // split once per piece. Never more split outputs than pieces: a spare one ends up as a stray track.
                String[] aSrc = new String[segN];
                if (audio) {
                    if (c.audioTracks >= 2) {
                        fc.append(trackMix(c, e, ci, "m" + ci));
                        if (segN > 1) fc.append(",asplit=").append(segN);
                    } else if (segN > 1) fc.append("[").append(ci).append(":a:0]asplit=").append(segN);
                    if (c.audioTracks >= 2 || segN > 1) {
                        for (int k = 0; k < segN; k++) { aSrc[k] = "[am" + ci + "_" + k + "]"; fc.append(aSrc[k]); }
                        fc.append(";");
                    } else aSrc[0] = "[" + ci + ":a:0]";
                }
                String[] vSrc = new String[segN];
                if (segN > 1) {
                    fc.append("[").append(ci).append(":v]split=").append(segN);
                    for (int k = 0; k < segN; k++) { vSrc[k] = "[mv" + ci + "_" + k + "]"; fc.append(vSrc[k]); }
                    fc.append(";");
                } else vSrc[0] = "[" + ci + ":v]";
                for (int k = 0; k < segN; k++) {
                    EditSpec.Piece s = segs.get(k);
                    fc.append(vSrc[k]).append("trim=start=").append(Library.fmt(s.start())).append(":end=").append(Library.fmt(s.end()))
                            .append(",setpts=(PTS-STARTPTS)/").append(Library.fmt(s.speed()))
                            .append(",scale=").append(W).append(":").append(H).append(":force_original_aspect_ratio=decrease:flags=lanczos")
                            .append(",pad=").append(W).append(":").append(H).append(":(ow-iw)/2:(oh-ih)/2:color=black,fps=").append(fps)
                            .append(pieceTail(s, W, H, fps)).append(",setsar=1,format=yuv420p[v").append(ci).append("_").append(k).append("];");
                    if (audio) {
                        fc.append(aSrc[k]).append("atrim=start=").append(Library.fmt(s.start())).append(":end=").append(Library.fmt(s.end()))
                                .append(",asetpts=PTS-STARTPTS").append(atempo(s.speed())).append(audioTail(s)).append(",volume=").append(Library.fmt(e.volume))
                                .append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo");
                    } else {
                        fc.append("anullsrc=r=48000:cl=stereo,atrim=duration=").append(Library.fmt(s.outputLength())).append(",aformat=sample_fmts=fltp:channel_layouts=stereo");
                    }
                    fc.append("[a").append(ci).append("_").append(k).append("];");
                }
                for (int k = 0; k < segN; k++) fc.append("[v").append(ci).append("_").append(k).append("][a").append(ci).append("_").append(k).append("]");
                fc.append("concat=n=").append(segN).append(":v=1:a=1[cv").append(ci).append("][ca").append(ci).append("];");
                cv[ci] = "[cv" + ci + "]";
                ca[ci] = "[ca" + ci + "]";
                if (tr.equals("dip")) {
                    // dip to black at the start and end of every clip
                    fc.append(cv[ci]).append("fade=t=in:st=0:d=0.2,fade=t=out:st=").append(Library.fmt(Math.max(0, clipLen - 0.2))).append(":d=0.2[cd").append(ci).append("];");
                    cv[ci] = "[cd" + ci + "]";
                } else if (overlap) {
                    // Overlapping transitions need every clip exactly as long as its edit says, on one time base.
                    fc.append(cv[ci]).append("tpad=stop_mode=clone:stop_duration=0.5,trim=duration=").append(Library.fmt(clipLen)).append(",settb=AVTB[cx").append(ci).append("];");
                    fc.append(ca[ci]).append("apad=pad_dur=0.5,atrim=duration=").append(Library.fmt(clipLen)).append("[cy").append(ci).append("];");
                    cv[ci] = "[cx" + ci + "]";
                    ca[ci] = "[cy" + ci + "]";
                }
            }
            double total;
            String aJoined;
            if (overlap) {
                String v = cv[0], au = ca[0];
                double acc = lens[0];
                for (int i = 1; i < clips.size(); i++) {
                    double d = Math.min(Math.max(1.0 / fps, o.transitionSec), 0.45 * Math.min(lens[i - 1], lens[i]));
                    d = Math.max(0.001, d);
                    double off = Math.max(0, acc - d);
                    fc.append(v).append(cv[i]).append("xfade=transition=").append(tr).append(":duration=").append(Library.fmt(d))
                            .append(":offset=").append(Library.fmt(off)).append("[xv").append(i).append("];");
                    fc.append(au).append(ca[i]).append("acrossfade=d=").append(Library.fmt(d)).append(":c1=tri:c2=tri[xa").append(i).append("];");
                    v = "[xv" + i + "]";
                    au = "[xa" + i + "]";
                    acc += lens[i] - d;
                }
                fc.append(v).append("format=yuv420p[vout];");
                aJoined = au;
                total = acc;
            } else {
                total = 0;
                for (int i = 0; i < clips.size(); i++) { fc.append(cv[i]).append(ca[i]); total += lens[i]; }
                fc.append("concat=n=").append(clips.size()).append(":v=1:a=1[vout][acat];");
                aJoined = "[acat]";
            }
            // Music under the whole montage, ducked under the clips' sound; then the loudness evened out.
            String tail = o.normalize ? LOUDNORM : "";
            if (musicUsable(o.music)) {
                a.addAll(List.of("-stream_loop", "-1", "-i", o.music.file));
                int mi = clips.size();
                fc.append(musicChain(o.music, mi, total, "mus")).append(";");
                if (o.music.duck) {
                    fc.append(aJoined).append("asplit=2[amain][sc];[mus][sc]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=300[mduck];")
                            .append("[amain][mduck]");
                } else fc.append(aJoined).append("[mus]");
                fc.append("amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.97").append(tail).append("[aout]");
            } else {
                fc.append(aJoined).append(tail.isEmpty() ? "anull" : tail.substring(1)).append("[aout]");
            }
            a.addAll(graphArgs(fc.toString(), tmp));
            a.addAll(List.of("-map", "[vout]", "-map", "[aout]"));
            String enc = pickEncoder(o, false);
            a.addAll(List.of("-c:v", enc));
            a.addAll(qualityArgs(enc, 19, (long) (W * (double) H * fps * 0.15)));
            a.addAll(List.of("-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
            Ffmpeg.run(a, total, progress, job);
            return out;
        } finally {
            if (tmp != null) deleteTree(tmp);
            release(out);
        }
    }

    /** A montage transition name ffmpeg's xfade knows ("none" and "dip" are handled here). */
    static String transition(String t) {
        String s = t == null ? "dip" : t.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "none", "cut" -> "none";
            case "fade", "crossfade" -> "fade";
            case "slideleft", "slide" -> "slideleft";
            case "zoomin", "zoom" -> ffmpegMajor() >= 5 ? "zoomin" : "fade";
            case "fadewhite", "flash" -> "fadewhite";
            default -> "dip";
        };
    }

    // ------------------------------------------------------------------ captions as one image stream

    private record CapItem(EditSpec.Caption c, double os, double oe) {}

    /** The ffconcat list of caption pictures and where they go on the frame. */
    record CaptionTrack(Path list, int x, int y) {}

    /** Frames of a pop caption's pop-in (it fades in while sliding up a little). */
    private static final int POP_STEPS = 3;
    private static final double POP_STEP = 1 / 30.0;

    /**
     * Every caption as one image stream, so a clip with hundreds of auto-caption words is one input and one
     * overlay instead of hundreds of full-frame layers: a picture (just the area the captions use) for each
     * stretch of time with a different set of captions on screen, plus the three pop-in frames of each pop
     * caption, listed with their durations in an ffconcat file. Null when no caption shows.
     */
    static CaptionTrack captionTrack(List<CapItem> items, int ow, int oh, double dur, Path dir) throws IOException {
        int n = items.size();
        if (n == 0 || dir == null) return null;
        Sprite[] sp = new Sprite[n];
        boolean[] pop = new boolean[n];
        int slide = (int) Math.round(oh * 0.025);
        Rectangle u = null;
        for (int k = 0; k < n; k++) {
            EditSpec.Caption c = items.get(k).c();
            sp[k] = captionSprite(c.text, c.position, c.style, ow, oh);
            pop[k] = "pop".equals(c.style) || "highlight".equals(c.style);
            Rectangle r = new Rectangle(sp[k].x(), sp[k].y(), sp[k].width(), sp[k].height());
            if (pop[k]) r.add(new Rectangle(sp[k].x(), sp[k].y() + slide, sp[k].width(), sp[k].height()));
            u = u == null ? r : u.union(r);
        }
        int ux = Math.max(0, u.x & ~1), uy = Math.max(0, u.y & ~1);
        int uw = Math.min(ow, u.x + u.width) - ux, uh = Math.min(oh, u.y + u.height) - uy;
        uw = Math.max(2, uw + (uw & 1));
        uh = Math.max(2, uh + (uh & 1));
        if (ux + uw > ow) ux = Math.max(0, ow - uw);
        if (uy + uh > oh) uy = Math.max(0, oh - uh);

        // Old ffmpeg can't give the pictures a fine time base: there everything lands on its 1/25 s grid.
        boolean fine = ffmpegMajor() >= 5;
        double grid = fine ? 0.001 : 0.04;
        double[] s0 = new double[n], s1 = new double[n];
        TreeSet<Double> bounds = new TreeSet<>();
        double end = snap(dur, grid);
        bounds.add(0.0);
        bounds.add(end);
        for (int k = 0; k < n; k++) {
            s0[k] = snap(Math.max(0, Math.min(dur, items.get(k).os())), grid);
            s1[k] = snap(Math.max(0, Math.min(dur, items.get(k).oe())), grid);
            bounds.add(s0[k]);
            bounds.add(s1[k]);
            if (pop[k]) for (int j = 1; j <= POP_STEPS; j++) bounds.add(Math.min(s1[k], snap(s0[k] + j * POP_STEP, grid)));
        }
        List<Double> b = new ArrayList<>(bounds.headSet(end, true));
        List<String> files = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        Map<String, String> fileFor = new HashMap<>();
        String prev = null;
        boolean any = false;
        for (int i = 0; i + 1 < b.size(); i++) {
            double t0 = b.get(i), t1 = b.get(i + 1);
            if (t1 - t0 < grid / 2) continue;
            List<int[]> on = new ArrayList<>();
            StringBuilder keyB = new StringBuilder();
            for (int k = 0; k < n; k++) {
                if (t0 < s0[k] - 1e-9 || t0 >= s1[k] - 1e-9) continue;
                int phase = pop[k] ? (int) Math.min(POP_STEPS, Math.round((t0 - s0[k]) / POP_STEP)) : POP_STEPS;
                on.add(new int[]{k, phase});
                keyB.append(k).append(':').append(phase).append(',');
            }
            String key = keyB.toString();
            if (!on.isEmpty()) any = true;
            if (key.equals(prev)) { lengths.set(lengths.size() - 1, lengths.get(lengths.size() - 1) + (t1 - t0)); continue; }
            String file = fileFor.get(key);
            if (file == null) {
                file = "cap" + fileFor.size() + ".png";
                BufferedImage img = new BufferedImage(uw, uh, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                for (int[] st : on) {
                    Sprite s = sp[st[0]];
                    int dy = 0;
                    float alpha = 1;
                    if (pop[st[0]] && st[1] < POP_STEPS) {
                        double frac = (st[1] + 0.5) / POP_STEPS;
                        dy = (int) Math.round(slide * (1 - frac));
                        alpha = (float) Math.min(1, (st[1] + 0.5) * POP_STEP / 0.07);
                    }
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    g.drawImage(s.image(), s.x() - ux, s.y() - uy + dy, null);
                }
                g.dispose();
                writePng(img, dir.resolve(file));
                fileFor.put(key, file);
            }
            files.add(file);
            lengths.add(t1 - t0);
            prev = key;
        }
        if (!any || files.isEmpty()) return null;
        // Each picture's duration; the last one is listed again, because ffmpeg ignores the last entry's duration.
        Path list = Files.createTempFile(dir, "captions", ".ffconcat");
        StringBuilder l = new StringBuilder("ffconcat version 1.0\n");
        for (int i = 0; i < files.size(); i++) {
            l.append("file '").append(files.get(i)).append("'\n");
            if (fine) l.append("option framerate 1000\n");
            l.append("duration ").append(Library.fmt(lengths.get(i))).append('\n');
        }
        l.append("file '").append(files.get(files.size() - 1)).append("'\n");
        if (fine) l.append("option framerate 1000\n");
        Files.writeString(list, l.toString(), StandardCharsets.UTF_8);
        return new CaptionTrack(list, ux, uy);
    }

    private static double snap(double t, double grid) { return Math.round(t / grid) * grid; }

    // ------------------------------------------------------------------ title card, captions, layers

    private static Font titleFont;

    static synchronized Font titleFont() {
        if (titleFont == null) {
            try (InputStream in = Exporter.class.getResourceAsStream("/clips/inter-bold.ttf")) {
                titleFont = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) {
                titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);
            }
        }
        return titleFont;
    }

    /** The watermark on a transparent frame-sized image (text in the title font, or a scaled image). */
    public static BufferedImage renderWatermark(Watermark wm, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, wm.opacity()))));
        int margin = (int) Math.round(Math.min(w, h) * wm.margin() / 100.0);
        int bw, bh;
        BufferedImage logo = null;
        Font f = null;
        if (wm.image() != null && !wm.image().isBlank()) {
            try { logo = ImageIO.read(new java.io.File(wm.image())); } catch (IOException | RuntimeException ignored) {}
        }
        String text = wm.text() == null ? "" : wm.text();
        // A moved or deleted logo and no text: nothing to draw (an empty text can't be laid out).
        if (logo == null && text.isBlank()) {
            g.dispose();
            return img;
        }
        if (logo != null) {
            bw = Math.max(8, (int) Math.round(w * wm.size() / 100.0));
            bh = (int) Math.round(bw * logo.getHeight() / (double) logo.getWidth());
        } else {
            f = titleFont().deriveFont((float) Math.max(8, h * wm.size() / 100.0 * 0.6));
            FontMetrics fm = g.getFontMetrics(f);
            bw = fm.stringWidth(text);
            bh = fm.getAscent() + fm.getDescent();
        }
        String pos = wm.position() == null ? "bottom-right" : wm.position();
        int x = pos.endsWith("left") ? margin : pos.endsWith("right") ? w - bw - margin : (w - bw) / 2;
        int y = pos.startsWith("top") ? margin : pos.startsWith("bottom") ? h - bh - margin : (h - bh) / 2;
        if (logo != null) {
            g.drawImage(logo, x, y, bw, bh, null);
        } else {
            FontMetrics fm = g.getFontMetrics(f);
            Shape shape = new TextLayout(text, f, g.getFontRenderContext()).getOutline(AffineTransform.getTranslateInstance(x, y + fm.getAscent()));
            g.setStroke(new BasicStroke(f.getSize2D() * 0.12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 200));
            g.draw(shape);
            g.setColor(Color.WHITE);
            g.fill(shape);
        }
        g.dispose();
        return img;
    }

    /**
     * A caption on a transparent frame-sized image. "meme": big uppercase white with a heavy black outline;
     * "subtitle": smaller text on a rounded dark box; "clean": like the title.
     */
    public static BufferedImage renderCaption(String text, String position, String style, int w, int h) {
        return onFrame(captionSprite(text, position, style, w, h), w, h);
    }

    /**
     * A caption drawn on an image just big enough for it, and where that goes on a w×h frame (see
     * {@link #renderCaption} for the styles).
     */
    public static Sprite captionSprite(String text, String position, String style, int w, int h) {
        String t = text == null ? "" : text;
        return switch (style == null ? "clean" : style) {
            case "meme" -> textSprite(t.toUpperCase(Locale.ROOT), position, w, h, 0.10f, 0.16f, false, Color.WHITE);
            case "pop" -> textSprite(t.toUpperCase(Locale.ROOT), position, w, h, 0.085f, 0.2f, false, Color.WHITE);
            case "highlight" -> textSprite(t.toUpperCase(Locale.ROOT), position, w, h, 0.085f, 0.2f, false, new Color(0xFFD23F));
            case "subtitle" -> textSprite(t, position, w, h, 0.05f, 0.0f, true, Color.WHITE);
            default -> titleSprite(t, position, w, h);
        };
    }

    /** Draws the title text (white, dark outline and shadow) on a transparent frame-sized image. */
    public static BufferedImage renderTitle(String text, String position, int w, int h) {
        return onFrame(titleSprite(text == null ? "" : text, position, w, h), w, h);
    }

    private static BufferedImage onFrame(Sprite s, int w, int h) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(s.image(), s.x(), s.y(), null);
        g.dispose();
        return img;
    }

    private static Sprite emptySprite() { return new Sprite(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), 0, 0); }

    /** A graphics context with the text settings layers are drawn with, for measuring. */
    private static Graphics2D measure() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        hints(g);
        return g;
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /** The pixel area (even top-left corner, inside the frame) for drawing bounds plus a little antialiasing room. */
    private static Rectangle area(Rectangle2D b, int w, int h) {
        int x0 = Math.max(0, (int) Math.floor(b.getMinX()) - 2) & ~1, y0 = Math.max(0, (int) Math.floor(b.getMinY()) - 2) & ~1;
        int x1 = Math.min(w, (int) Math.ceil(b.getMaxX()) + 2), y1 = Math.min(h, (int) Math.ceil(b.getMaxY()) + 2);
        return x1 - x0 < 1 || y1 - y0 < 1 ? null : new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    private static Sprite textSprite(String text, String position, int w, int h, float rel, float stroke, boolean box, Color fill) {
        if (text.isBlank() || w < 4 || h < 4) return emptySprite();
        Graphics2D m = measure();
        float size = Math.min(w, h) * rel;
        Font f = titleFont().deriveFont(size);
        FontMetrics fm = m.getFontMetrics(f);
        while (fm.stringWidth(text) > w * 0.92 && size > 10) { size *= 0.92f; f = titleFont().deriveFont(size); fm = m.getFontMetrics(f); }
        int tw = fm.stringWidth(text);
        int x = (w - tw) / 2;
        int y = switch (position == null ? "bottom" : position) {
            case "top" -> (int) (h * 0.07) + fm.getAscent();
            case "center" -> (h - fm.getHeight()) / 2 + fm.getAscent();
            default -> (int) (h * (box ? 0.9 : 0.93)) - fm.getDescent();
        };
        Shape shape = null;
        RoundRectangle2D boxShape = null;
        Rectangle2D bounds;
        if (box) {
            int pad = (int) (size * 0.45);
            boxShape = new RoundRectangle2D.Double(x - pad, y - fm.getAscent() - pad * 0.6, tw + pad * 2, fm.getAscent() + fm.getDescent() + pad * 1.2, pad, pad);
            bounds = boxShape.getBounds2D();
            bounds.add(new Rectangle2D.Double(x, y - fm.getAscent(), tw, fm.getAscent() + fm.getDescent()));
        } else {
            shape = new TextLayout(text, f, m.getFontRenderContext()).getOutline(AffineTransform.getTranslateInstance(x, y));
            Rectangle2D sb = shape.getBounds2D();
            double grow = size * stroke / 2;
            bounds = new Rectangle2D.Double(sb.getX() - grow, sb.getY() - grow, sb.getWidth() + grow * 2, sb.getHeight() + grow * 2);
        }
        m.dispose();
        Rectangle r = area(bounds, w, h);
        if (r == null) return emptySprite();
        BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.translate(-r.x, -r.y);
        if (box) {
            g.setColor(new Color(0, 0, 0, 170));
            g.fill(boxShape);
            g.setColor(Color.WHITE);
            g.setFont(f);
            g.drawString(text, x, y);
        } else {
            g.setStroke(new BasicStroke(size * stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            g.draw(shape);
            g.setColor(fill);
            g.fill(shape);
        }
        g.dispose();
        return new Sprite(img, r.x, r.y);
    }

    private static Sprite titleSprite(String text, String position, int w, int h) {
        if (text.isBlank() || w < 4 || h < 4) return emptySprite();
        Graphics2D m = measure();
        float size = Math.min(w, h) * 0.075f;
        Font f = titleFont().deriveFont(size);
        FontMetrics fm = m.getFontMetrics(f);
        while (fm.stringWidth(text) > w * 0.9 && size > 10) {
            size *= 0.92f;
            f = titleFont().deriveFont(size);
            fm = m.getFontMetrics(f);
        }
        int tw = fm.stringWidth(text);
        int x = (w - tw) / 2;
        int y = switch (position == null ? "bottom" : position.toLowerCase(Locale.ROOT)) {
            case "top" -> (int) (h * 0.08) + fm.getAscent();
            case "center" -> (h - fm.getHeight()) / 2 + fm.getAscent();
            default -> (int) (h * 0.92) - fm.getDescent();
        };
        Shape shape = new TextLayout(text, f, m.getFontRenderContext()).getOutline(AffineTransform.getTranslateInstance(x, y));
        m.dispose();
        double sx = size * 0.05, sy = size * 0.07, grow = size * 0.06;
        Rectangle2D sb = shape.getBounds2D();
        Rectangle2D bounds = new Rectangle2D.Double(sb.getX() - grow, sb.getY() - grow, sb.getWidth() + grow * 2, sb.getHeight() + grow * 2);
        bounds.add(new Rectangle2D.Double(sb.getX() + sx, sb.getY() + sy, sb.getWidth(), sb.getHeight()));
        Rectangle r = area(bounds, w, h);
        if (r == null) return emptySprite();
        BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.translate(-r.x, -r.y);
        // soft shadow
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(Color.BLACK);
        g.translate(sx, sy);
        g.fill(shape);
        g.translate(-sx, -sy);
        g.setComposite(AlphaComposite.SrcOver);
        g.setStroke(new BasicStroke(size * 0.12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 220));
        g.draw(shape);
        g.setColor(Color.WHITE);
        g.fill(shape);
        g.dispose();
        return new Sprite(img, r.x, r.y);
    }

    /** The visible part of a frame-sized layer (even top-left corner), or null when nothing on it shows. */
    static Sprite crop(BufferedImage full) {
        int w = full.getWidth(), h = full.getHeight();
        int[] row = new int[w];
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            full.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) == 0) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                y1 = y;
            }
        }
        if (x1 < 0) return null;
        x0 &= ~1;
        y0 &= ~1;
        BufferedImage img = new BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(full, -x0, -y0, null);
        g.dispose();
        return new Sprite(img, x0, y0);
    }

    /** A hitmarker: four short white strokes around the centre, with a dark edge, on a transparent frame. */
    public static BufferedImage renderHitmarker(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double cx = w / 2.0, cy = h / 2.0, s = Math.min(w, h) * 0.045, gap = s * 0.45;
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
        for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            path.moveTo(cx + d[0] * gap, cy + d[1] * gap);
            path.lineTo(cx + d[0] * (gap + s), cy + d[1] * (gap + s));
        }
        g.setStroke(new BasicStroke((float) (s * 0.42), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(new Color(0, 0, 0, 170));
        g.draw(path);
        g.setStroke(new BasicStroke((float) (s * 0.22), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setColor(Color.WHITE);
        g.draw(path);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ helpers

    /** 29.4 measured fps → 30, 59.9 → 60 etc. */
    static int snapFps(double f) {
        if (f <= 0) return 60;
        for (int std : new int[]{24, 25, 30, 48, 50, 60, 72, 90, 120, 144, 165, 240}) if (Math.abs(f - std) <= std * 0.05) return std;
        return (int) Math.round(f);
    }

    static int even(double v) { int i = (int) Math.round(v); return Math.max(2, i - (i % 2)); }
    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    public static double clamp01(double v) { return clamp(v); }

    static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /** Suggested output file for an export, reserved for it until the export ends. */
    public static Path outputFor(Library lib, Clip c, Preset p) {
        String base = Library.safe(c.title == null || c.title.isBlank() ? "Clip" : c.title);
        String suffix = switch (p) {
            case DISCORD, DISCORD_NITRO -> " (Discord)";
            case SHORTS -> " (Vertical)";
            case YOUTUBE -> " (YouTube)";
            default -> "";
        };
        return reserveOutput(lib.folder(Clip.Kind.EXPORT), base + suffix + "." + p.ext);
    }
}
