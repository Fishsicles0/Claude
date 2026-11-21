package net.evadeclient.clips.core;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

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
    }

    private Exporter() {}

    /** Output frame size for a source and an edit, before preset scaling. */
    record Region(int w, int h, int x, int y) {}

    // ------------------------------------------------------------------ public entry

    public static Path export(Path src, Clip clip, EditSpec edit, Options o, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        Files.createDirectories(out.getParent());
        if (o.preset == Preset.QUICK_TRIM) {
            quickTrim(src, edit, out, progress, job);
            return out;
        }
        double dur = edit.outputDuration();
        if (dur <= 0.05) throw new IOException("Nothing to export: every part of the clip was cut out.");
        double target = o.targetMb > 0 ? o.targetMb : o.preset.targetMb;
        if (target <= 0 || o.preset == Preset.GIF) {
            Ffmpeg.run(buildArgs(src, clip, edit, o, out, -1, 0, null), dur, progress, job);
            return out;
        }

        // Size-targeted: aim a little under, re-try lower if the encoder overshoots.
        long limit = (long) (target * 1000 * 1000); // Discord counts in MB (10^6) on the client
        double factor = 0.93;
        for (int attempt = 0; attempt < 4; attempt++) {
            double audioKbps = clip.hasAudio && !edit.mute ? (target <= 10 ? 96 : 128) : 0;
            double totalKbps = limit * 8 * factor / dur / 1000;
            double videoKbps = Math.max(80, totalKbps - audioKbps);
            final int tries = attempt;
            DoubleConsumer prog = progress == null ? null : p -> progress.accept(Math.min(0.99, (tries * 0.1) + p * (1 - tries * 0.1)));
            twoPass(src, clip, edit, o, out, videoKbps, (int) audioKbps, dur, prog, job);
            if (Files.size(out) <= limit) {
                if (progress != null) progress.accept(1);
                return out;
            }
            factor *= 0.88 * limit / (double) Files.size(out);
        }
        return out; // best effort
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
                        "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-avoid_negative_ts", "make_zero", part.toString()), -1, null, job);
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
                                DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        boolean x264 = Ffmpeg.workingEncoders().contains("libx264");
        if (!x264) {
            Ffmpeg.run(buildArgs(src, clip, edit, o, out, vKbps, aKbps, null), dur, progress, job);
            return;
        }
        Path tmpDir = Files.createTempDirectory("evadeclips-2pass");
        try {
            String log = tmpDir.resolve("pass").toString();
            List<String> p1 = buildArgs(src, clip, edit, o, Path.of(Ffmpeg.WINDOWS ? "NUL" : "/dev/null"), vKbps, aKbps, new String[]{"1", log});
            Ffmpeg.run(p1, dur, progress == null ? null : p -> progress.accept(p * 0.35), job);
            List<String> p2 = buildArgs(src, clip, edit, o, out, vKbps, aKbps, new String[]{"2", log});
            Ffmpeg.run(p2, dur, progress == null ? null : p -> progress.accept(0.35 + p * 0.65), job);
        } finally {
            deleteTree(tmpDir);
        }
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
        boolean blur = e.fitMode.equals("blur") && !e.aspect.equals("original") || (p == Preset.SHORTS && e.aspect.equals("original"));
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

    // ------------------------------------------------------------------ the ffmpeg command

    static List<String> buildArgs(Path src, Clip clip, EditSpec e, Options o, Path out, double vKbps, int aKbps, String[] pass) throws IOException {
        int w = clip.width > 0 ? clip.width : 1920, h = clip.height > 0 ? clip.height : 1080;
        Preset p = o.preset;
        // Both passes must see the exact same filter graph (audio included) or x264's stats won't line up.
        boolean audio = clip.hasAudio && !e.mute && p != Preset.GIF;
        double dur = e.outputDuration();
        int[] size = outputSize(clip, e, o);
        int ow = size[0], oh = size[1];

        // Fit the bitrate budget: if it's tiny, drop resolution / frame rate before it turns to mush.
        int srcFps = snapFps(clip.fps);
        int fps = o.fps > 0 ? o.fps : (p.fps > 0 ? Math.min(p.fps, srcFps) : 0);
        if (vKbps > 0) {
            int outFps = fps > 0 ? fps : srcFps;
            double bpp = vKbps * 1000 / (ow * (double) oh * outFps);
            while (bpp < 0.045 && Math.min(ow, oh) > 360) {
                double k = (Math.min(ow, oh) > 720 ? 720.0 : Math.min(ow, oh) > 540 ? 540.0 : 360.0) / Math.min(ow, oh);
                ow = even(ow * k); oh = even(oh * k);
                bpp = vKbps * 1000 / (ow * (double) oh * outFps);
            }
            if (bpp < 0.045 && outFps > 30) fps = 30;
        }

        List<String> a = new ArrayList<>(List.of("-i", src.toString()));
        boolean hasTitle = !e.titleText.isBlank() && p != Preset.GIF;
        if (hasTitle) {
            Path png = Files.createTempFile("evadeclips-title", ".png");
            png.toFile().deleteOnExit();
            ImageIO.write(renderTitle(e.titleText, e.titlePosition, ow, oh), "png", png.toFile());
            a.addAll(List.of("-loop", "1", "-framerate", "30", "-t", Library.fmt(dur), "-i", png.toString()));
        }

        StringBuilder fc = new StringBuilder();
        int n = e.segments.size();
        for (int i = 0; i < n; i++) {
            EditSpec.Segment s = e.segments.get(i);
            fc.append("[0:v]trim=start=").append(Library.fmt(s.start)).append(":end=").append(Library.fmt(s.end))
                    .append(",setpts=(PTS-STARTPTS)/").append(Library.fmt(s.speed)).append("[v").append(i).append("];");
            if (audio) {
                fc.append("[0:a]atrim=start=").append(Library.fmt(s.start)).append(":end=").append(Library.fmt(s.end))
                        .append(",asetpts=PTS-STARTPTS").append(atempo(s.speed)).append("[a").append(i).append("];");
            }
        }
        for (int i = 0; i < n; i++) fc.append("[v").append(i).append("]").append(audio ? "[a" + i + "]" : "");
        fc.append("concat=n=").append(n).append(":v=1:a=").append(audio ? 1 : 0).append("[vc]").append(audio ? "[ac]" : "").append(";");

        // Framing.
        boolean blur = (e.fitMode.equals("blur") && !e.aspect.equals("original")) || (p == Preset.SHORTS && e.aspect.equals("original"));
        String zoomCrop = zoomCrop(w, h, e);
        if (blur) {
            fc.append("[vc]").append(zoomCrop.isEmpty() ? "null" : zoomCrop).append(",split[fg0][bg0];")
                    .append("[bg0]scale=").append(ow).append(":").append(oh).append(":force_original_aspect_ratio=increase,crop=")
                    .append(ow).append(":").append(oh).append(",boxblur=20:2,eq=brightness=-0.08[bg];")
                    .append("[fg0]scale=").append(ow).append(":").append(oh).append(":force_original_aspect_ratio=decrease[fg];")
                    .append("[bg][fg]overlay=(W-w)/2:(H-h)/2");
        } else {
            Region r = region(w, h, e);
            fc.append("[vc]");
            if (r.w != w || r.h != h) fc.append("crop=").append(r.w).append(":").append(r.h).append(":").append(r.x).append(":").append(r.y).append(",");
            fc.append("scale=").append(ow).append(":").append(oh).append(":flags=lanczos");
        }
        if (fps > 0) fc.append(",fps=").append(fps);
        fc.append(",setsar=1");
        if (e.fadeIn > 0) fc.append(",fade=t=in:st=0:d=").append(Library.fmt(e.fadeIn));
        if (e.fadeOut > 0) fc.append(",fade=t=out:st=").append(Library.fmt(Math.max(0, dur - e.fadeOut))).append(":d=").append(Library.fmt(e.fadeOut));

        if (p == Preset.GIF) {
            fc.append(",split[g0][g1];[g0]palettegen=stats_mode=diff:max_colors=200[pal];[g1][pal]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle[vout]");
        } else if (hasTitle) {
            fc.append("[base];[1:v]format=rgba");
            double ts = e.titleSeconds > 0 ? Math.min(e.titleSeconds, dur) : dur;
            if (e.titleSeconds > 0) fc.append(",fade=t=out:st=").append(Library.fmt(Math.max(0, ts - 0.4))).append(":d=0.4:alpha=1");
            fc.append(",fade=t=in:st=0:d=0.3:alpha=1[title];[base][title]overlay=0:0:eof_action=pass,format=yuv420p[vout]");
        } else {
            fc.append(",format=yuv420p[vout]");
        }

        if (audio) {
            fc.append(";[ac]volume=").append(Library.fmt(e.volume));
            if (e.fadeIn > 0) fc.append(",afade=t=in:st=0:d=").append(Library.fmt(e.fadeIn));
            if (e.fadeOut > 0) fc.append(",afade=t=out:st=").append(Library.fmt(Math.max(0, dur - e.fadeOut))).append(":d=").append(Library.fmt(e.fadeOut));
            fc.append("[aout]");
        }

        a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[vout]"));
        if (audio) a.addAll(List.of("-map", "[aout]"));

        if (p == Preset.GIF) {
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
            switch (enc) {
                case "libx264" -> a.addAll(List.of("-preset", "medium", "-crf", high ? "18" : "21"));
                case "h264_nvenc" -> a.addAll(List.of("-preset", "p6", "-rc", "vbr", "-cq", high ? "19" : "23", "-b:v", "0"));
                case "h264_qsv" -> a.addAll(List.of("-preset", "slow", "-global_quality", high ? "19" : "23"));
                case "h264_amf" -> a.addAll(List.of("-quality", "quality", "-rc", "cqp", "-qp_i", high ? "19" : "23", "-qp_p", high ? "21" : "25"));
                case "h264_videotoolbox" -> a.addAll(List.of("-q:v", high ? "70" : "60"));
                default -> a.addAll(List.of("-q:v", "3"));
            }
        }
        a.addAll(List.of("-pix_fmt", "yuv420p"));
        if (enc.equals("libx264") || enc.equals("h264_nvenc")) a.addAll(List.of("-profile:v", "high"));
        if (audio) a.addAll(List.of("-c:a", "aac", "-b:a", (aKbps > 0 ? aKbps : 192) + "k", "-ar", "48000"));
        if (pass != null && pass[0].equals("1")) a.addAll(List.of("-f", "mp4"));
        a.addAll(List.of("-movflags", "+faststart", out.toString()));
        return a;
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

    // ------------------------------------------------------------------ title card

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

    /** Draws the title text (white, dark outline and shadow) on a transparent frame-sized image. */
    public static BufferedImage renderTitle(String text, String position, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        float size = Math.min(w, h) * 0.075f;
        Font f = titleFont().deriveFont(size);
        FontMetrics fm = g.getFontMetrics(f);
        while (fm.stringWidth(text) > w * 0.9 && size > 10) {
            size *= 0.92f;
            f = titleFont().deriveFont(size);
            fm = g.getFontMetrics(f);
        }
        int tw = fm.stringWidth(text);
        int x = (w - tw) / 2;
        int y = switch (position == null ? "bottom" : position.toLowerCase(Locale.ROOT)) {
            case "top" -> (int) (h * 0.08) + fm.getAscent();
            case "center" -> (h - fm.getHeight()) / 2 + fm.getAscent();
            default -> (int) (h * 0.92) - fm.getDescent();
        };
        TextLayout tl = new TextLayout(text, f, g.getFontRenderContext());
        java.awt.Shape shape = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
        // soft shadow
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(Color.BLACK);
        g.translate(size * 0.05, size * 0.07);
        g.fill(shape);
        g.translate(-size * 0.05, -size * 0.07);
        g.setComposite(AlphaComposite.SrcOver);
        g.setStroke(new BasicStroke(size * 0.12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 220));
        g.draw(shape);
        g.setColor(Color.WHITE);
        g.fill(shape);
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

    static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /** Suggested output file for an export. */
    public static Path outputFor(Library lib, Clip c, Preset p) {
        String base = Library.safe(c.title == null || c.title.isBlank() ? "Clip" : c.title);
        String suffix = switch (p) {
            case DISCORD, DISCORD_NITRO -> " (Discord)";
            case SHORTS -> " (Vertical)";
            case YOUTUBE -> " (YouTube)";
            default -> "";
        };
        return Library.unique(lib.folder(Clip.Kind.EXPORT), base + suffix + "." + p.ext);
    }
}
