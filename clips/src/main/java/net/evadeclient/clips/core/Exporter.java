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
        /** Your own watermark (null = none). Evade Clips never adds one of its own. */
        public Watermark watermark;
    }

    /** A creator's own watermark: text or an image, anywhere on the frame, at any size and opacity. */
    public record Watermark(String text, String image, String position, double size, double opacity, double margin) {
        public static Watermark from(Settings s) {
            if (!s.watermarkEnabled) return null;
            if ((s.watermarkText == null || s.watermarkText.isBlank()) && (s.watermarkImage == null || s.watermarkImage.isBlank())) return null;
            return new Watermark(s.watermarkText, s.watermarkImage, s.watermarkPosition, s.watermarkSize, s.watermarkOpacity, s.watermarkMargin);
        }
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

    // ------------------------------------------------------------------ the ffmpeg command

    static List<String> buildArgs(Path src, Clip clip, EditSpec e, Options o, Path out, double vKbps, int aKbps, String[] pass) throws IOException {
        e.fill();
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
        int workFps = fps > 0 ? fps : srcFps;

        List<String> a = new ArrayList<>(List.of("-i", src.toString()));
        int input = 1;
        boolean split = isSplit(e, p);
        int camInput = -1;
        if (split && e.camFile != null && !e.camFile.isBlank() && Files.exists(Path.of(e.camFile))) {
            a.addAll(List.of("-stream_loop", "-1", "-i", e.camFile));
            camInput = input++;
        }
        boolean hasTitle = !e.titleText.isBlank() && p != Preset.GIF;
        int titleInput = -1;
        if (hasTitle) {
            a.addAll(loopPng(renderTitle(e.titleText, e.titlePosition, ow, oh), dur));
            titleInput = input++;
        }
        // Timed captions: one transparent frame each, overlaid only while it's on screen.
        List<double[]> capTimes = new ArrayList<>();
        List<String> capStyles = new ArrayList<>();
        int capInput = input;
        for (EditSpec.Caption c : e.captions) {
            if (c.text == null || c.text.isBlank()) continue;
            double os = e.toOutput(c.start), oe = e.toOutput(c.end);
            if (oe - os < 0.04) continue; // entirely inside a cut part
            a.addAll(loopPng(renderCaption(c.text, c.position, c.style, ow, oh), dur));
            capTimes.add(new double[]{os, oe});
            capStyles.add(c.style);
            input++;
        }
        // Video overlays: each its own input, trimmed to the part that shows.
        List<double[]> ovTimes = new ArrayList<>();
        List<EditSpec.Overlay> ovs = new ArrayList<>();
        List<Boolean> ovAudio = new ArrayList<>();
        int ovInput = input;
        for (EditSpec.Overlay ov : e.overlays) {
            if (ov.file == null || ov.file.isBlank() || !Files.exists(Path.of(ov.file))) continue;
            double os = e.toOutput(ov.start);
            double len = Math.min(ov.length > 0 ? ov.length : dur, dur - os);
            if (len < 0.05) continue;
            boolean ovHasAudio = false;
            try { ovHasAudio = Ffmpeg.probe(Path.of(ov.file)).hasAudio(); } catch (IOException ignored) {}
            a.addAll(List.of("-ss", Library.fmt(ov.inPoint), "-t", Library.fmt(len), "-i", ov.file));
            ovTimes.add(new double[]{os, os + len});
            ovs.add(ov);
            ovAudio.add(ovHasAudio && ov.audio && audio);
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
        if (fx.stream().anyMatch(f -> f.type.equals("hitmarker")) && p != Preset.GIF) {
            a.addAll(loopPng(renderHitmarker(ow, oh), dur));
            hitInput = input++;
        }
        int wmInput = -1;
        if (o.watermark != null) {
            a.addAll(loopPng(renderWatermark(o.watermark, ow, oh), dur));
            wmInput = input++;
        }
        // Sounds for effects.
        List<String> sfxLabels = new ArrayList<>();
        List<String> sfxChains = new ArrayList<>();
        if (p != Preset.GIF) {
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

        StringBuilder fc = new StringBuilder();
        List<EditSpec.Piece> pieces = e.pieces();
        int n = pieces.size();
        // Audio source per piece: the single track, or game + mic (+ voice chat) mixed at the chosen levels.
        String[] aSrc = new String[n];
        if (audio && clip.audioTracks >= 2) {
            fc.append(trackMix(clip, e, 0, "mixed"));
            if (n > 1) {
                fc.append(",asplit=").append(n);
                for (int i = 0; i < n; i++) { fc.append("[as").append(i).append("]"); aSrc[i] = "[as" + i + "]"; }
            } else { fc.append("[as0]"); aSrc[0] = "[as0]"; }
            fc.append(";");
        } else if (audio && n > 1) {
            fc.append("[0:a:0]asplit=").append(n);
            for (int i = 0; i < n; i++) { fc.append("[as").append(i).append("]"); aSrc[i] = "[as" + i + "]"; }
            fc.append(";");
        } else {
            java.util.Arrays.fill(aSrc, "[0:a:0]");
        }
        if (n > 1) {
            fc.append("[0:v]split=").append(n);
            for (int i = 0; i < n; i++) fc.append("[vs").append(i).append("]");
            fc.append(";");
        }
        for (int i = 0; i < n; i++) {
            EditSpec.Piece s = pieces.get(i);
            fc.append(n > 1 ? "[vs" + i + "]" : "[0:v]").append("trim=start=").append(Library.fmt(s.start())).append(":end=").append(Library.fmt(s.end()))
                    .append(",setpts=(PTS-STARTPTS)/").append(Library.fmt(s.speed()));
            // Slow parts get in-between frames, so slow motion stays smooth instead of stuttering.
            if (s.speed() < 0.97 && p != Preset.GIF) {
                if ("flow".equals(e.smoothing)) fc.append(",minterpolate=fps=").append(workFps).append(":mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1");
                else if ("blend".equals(e.smoothing)) fc.append(",framerate=fps=").append(workFps);
            }
            fc.append(",fps=").append(workFps).append(",setsar=1[v").append(i).append("];");
            if (audio) {
                fc.append(aSrc[i]).append("atrim=start=").append(Library.fmt(s.start())).append(":end=").append(Library.fmt(s.end()))
                        .append(",asetpts=PTS-STARTPTS").append(atempo(s.speed())).append("[a").append(i).append("];");
            }
        }
        for (int i = 0; i < n; i++) fc.append("[v").append(i).append("]").append(audio ? "[a" + i + "]" : "");
        fc.append("concat=n=").append(n).append(":v=1:a=").append(audio ? 1 : 0).append("[vc]").append(audio ? "[ac]" : "").append(";");

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
            String cam = camInput >= 0 ? "[" + camInput + ":v]" : "[cam0]";
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
            int mx = Math.max(0, Math.min(ow - mw, (int) Math.round(ow * m.x))), my = Math.max(0, Math.min(oh - mh, (int) Math.round(oh * m.y)));
            String en = "enable='between(t," + Library.fmt(ms) + "," + Library.fmt(me) + ")'";
            if (m.kind.equals("black")) {
                fc.append(";").append(cur).append("drawbox=x=").append(mx).append(":y=").append(my).append(":w=").append(mw).append(":h=").append(mh)
                        .append(":color=black:t=fill:").append(en).append("[mk").append(mi).append("]");
            } else {
                String look2 = m.kind.equals("pixelate")
                        ? "scale=" + Math.max(2, mw / 14) + ":" + Math.max(2, mh / 14) + ",scale=" + mw + ":" + mh + ":flags=neighbor"
                        : "boxblur=" + Math.max(2, Math.min(mw, mh) / 5 - 1) + ":4";
                fc.append(";").append(cur).append("split[mka").append(mi).append("][mkb").append(mi).append("];[mkb").append(mi).append("]crop=")
                        .append(mw).append(":").append(mh).append(":").append(mx).append(":").append(my).append(",").append(look2).append("[mkc").append(mi)
                        .append("];[mka").append(mi).append("][mkc").append(mi).append("]overlay=").append(mx).append(":").append(my).append(":").append(en)
                        .append("[mk").append(mi).append("]");
            }
            cur = "[mk" + mi + "]";
            mi++;
        }
        if (hasTitle) {
            fc.append(";[").append(titleInput).append(":v]format=rgba");
            double ts = e.titleSeconds > 0 ? Math.min(e.titleSeconds, dur) : dur;
            if (e.titleSeconds > 0) fc.append(",fade=t=out:st=").append(Library.fmt(Math.max(0, ts - 0.4))).append(":d=0.4:alpha=1");
            fc.append(",fade=t=in:st=0:d=0.3:alpha=1[title];").append(cur).append("[title]overlay=0:0:eof_action=pass[b0]");
            cur = "[b0]";
        }
        for (int k = 0; k < capTimes.size(); k++) {
            double[] t = capTimes.get(k);
            boolean pop = "pop".equals(capStyles.get(k)) || "highlight".equals(capStyles.get(k));
            fc.append(";[").append(capInput + k).append(":v]format=rgba");
            // pop: fades in while sliding up a little, like the caption bounces onto the screen
            if (pop) fc.append(",fade=t=in:st=").append(Library.fmt(t[0])).append(":d=0.07:alpha=1");
            fc.append("[cap").append(k).append("];").append(cur).append("[cap").append(k).append("]overlay=0:");
            if (pop) fc.append("'").append(Library.fmt(oh * 0.025)).append("*max(0,1-(t-").append(Library.fmt(t[0])).append(")/0.1)'");
            else fc.append("0");
            fc.append(":eof_action=pass:enable='between(t,").append(Library.fmt(t[0])).append(",").append(Library.fmt(t[1])).append(")'[c").append(k).append("]");
            cur = "[c" + k + "]";
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
                fc.append(";[").append(in).append(":a]aresample=48000,aformat=channel_layouts=stereo,volume=").append(Library.fmt(ov.volume))
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
            fc.append(";[").append(hitInput).append(":v]format=rgba[hit];").append(cur).append("[hit]overlay=0:0:eof_action=pass:enable='")
                    .append(en).append("'[hm]");
            cur = "[hm]";
        }
        if (wmInput >= 0) {
            fc.append(";[").append(wmInput).append(":v]format=rgba[wm];").append(cur).append("[wm]overlay=0:0:eof_action=pass[wmo]");
            cur = "[wmo]";
        }
        fc.append(";").append(cur);
        if (p == Preset.GIF) {
            fc.append("split[g0][g1];[g0]palettegen=stats_mode=diff:max_colors=200[pal];[g1][pal]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle[vout]");
        } else {
            fc.append("format=yuv420p[vout]");
        }

        // Audio: the clip's sound (or silence, when there are effect sounds but no clip sound), then overlays and effect sounds on top.
        boolean anyAudio = audio || !sfxLabels.isEmpty() || !ovAudioLabels.isEmpty();
        if (anyAudio) {
            if (audio) {
                fc.append(";[ac]volume=").append(Library.fmt(e.volume));
                if (e.fadeIn > 0) fc.append(",afade=t=in:st=0:d=").append(Library.fmt(e.fadeIn));
                if (e.fadeOut > 0) fc.append(",afade=t=out:st=").append(Library.fmt(Math.max(0, dur - e.fadeOut))).append(":d=").append(Library.fmt(e.fadeOut));
                fc.append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo");
            } else {
                fc.append(";anullsrc=r=48000:cl=stereo,atrim=duration=").append(Library.fmt(dur)).append(",aformat=sample_fmts=fltp:channel_layouts=stereo");
            }
            List<String> extra = new ArrayList<>(ovAudioLabels);
            extra.addAll(sfxLabels);
            if (extra.isEmpty()) fc.append("[aout]");
            else {
                fc.append("[amain]");
                for (String c : sfxChains) fc.append(";").append(c);
                fc.append(";[amain]").append(String.join("", extra)).append("amix=inputs=").append(1 + extra.size())
                        .append(":duration=first:normalize=0,alimiter=limit=0.97[aout]");
            }
        }

        a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[vout]"));
        if (anyAudio) a.addAll(List.of("-map", "[aout]"));

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
            a.addAll(qualityArgs(enc, high ? 18 : 21, (long) (ow * (double) oh * workFps * (high ? 0.15 : 0.10))));
        }
        a.addAll(List.of("-pix_fmt", "yuv420p"));
        if (enc.equals("libx264") || enc.equals("h264_nvenc")) a.addAll(List.of("-profile:v", "high"));
        if (anyAudio) a.addAll(List.of("-c:a", "aac", "-b:a", (aKbps > 0 ? aKbps : 192) + "k", "-ar", "48000"));
        if (pass != null && pass[0].equals("1")) a.addAll(List.of("-f", "mp4"));
        a.addAll(List.of("-movflags", "+faststart", out.toString()));
        return a;
    }

    /** A transparent frame-sized PNG as a looping input for the whole export. */
    private static List<String> loopPng(BufferedImage img, double dur) throws IOException {
        Path png = Files.createTempFile("evadeclips-layer", ".png");
        png.toFile().deleteOnExit();
        ImageIO.write(img, "png", png.toFile());
        return List.of("-loop", "1", "-framerate", "30", "-t", Library.fmt(dur), "-i", png.toString());
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
     */
    static String zoomFilter(EditSpec e, List<EditSpec.Effect> fx, List<Double> fxAt, int w, int h, int fps) {
        List<EditSpec.ZoomKey> keys = new ArrayList<>();
        for (EditSpec.ZoomKey k : e.sortedZooms()) if (e.segmentAt(k.t) >= 0) keys.add(k);
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

    /** Eased piecewise curve through keyframe values, as an ffmpeg expression in output time (it). */
    private static String keyExpr(List<EditSpec.ZoomKey> keys, double[] ts, java.util.function.ToDoubleFunction<EditSpec.ZoomKey> v, double none) {
        if (keys.isEmpty()) return Library.fmt(none);
        StringBuilder sb = new StringBuilder("(").append(Library.fmt(v.applyAsDouble(keys.get(0))));
        for (int i = 1; i < keys.size(); i++) {
            double dv = v.applyAsDouble(keys.get(i)) - v.applyAsDouble(keys.get(i - 1));
            if (Math.abs(dv) < 1e-6) continue;
            double t0 = ts[i - 1], d = Math.max(0.05, ts[i] - t0);
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

    /**
     * Mixes a clip's audio tracks (game, mic, voice chat) at the levels in the edit; ends with the chain open so
     * the caller can append (",asplit=..." or a label).
     */
    static String trackMix(Clip clip, EditSpec e, int input, String tag) {
        StringBuilder sb = new StringBuilder();
        StringBuilder ins = new StringBuilder();
        for (int i = 0; i < clip.audioTracks; i++) {
            String name = clip.trackNames != null && i < clip.trackNames.size() ? clip.trackNames.get(i) : (i == 0 ? "Game" : i == 1 ? "Mic" : "Voice chat");
            double v = name.equalsIgnoreCase("Mic") ? e.micVolume : name.equalsIgnoreCase("Voice chat") ? e.voiceVolume : 1;
            String lbl = "[" + tag + "t" + i + "]";
            sb.append("[").append(input).append(":a:").append(i).append("]volume=").append(Library.fmt(v)).append(lbl).append(";");
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
     * Joins several clips (each with its own edits: cuts, speed, mic level) into one video. Clips of different
     * sizes are fitted to one canvas; clips without sound get silence so the audio stays in sync. A short dip to
     * black between clips keeps the cuts clean.
     */
    public static Path montage(List<Clip> clips, Path root, Options o, Path out, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        if (clips.size() < 1) throw new IOException("Pick at least one clip");
        Files.createDirectories(out.getParent());
        int maxH = 0, fps = 30;
        for (Clip c : clips) { maxH = Math.max(maxH, c.height); fps = Math.max(fps, snapFps(c.fps)); }
        int H = Math.min(o.maxHeight > 0 ? o.maxHeight : 1080, maxH > 0 ? maxH : 1080);
        if (H % 2 == 1) H--;
        int W = even(H * 16 / 9.0);
        fps = Math.min(o.fps > 0 ? o.fps : 60, fps);

        List<String> a = new ArrayList<>();
        StringBuilder fc = new StringBuilder();
        List<String> parts = new ArrayList<>();
        double total = 0;
        for (int ci = 0; ci < clips.size(); ci++) {
            Clip c = clips.get(ci);
            a.addAll(List.of("-i", c.path(root).toString()));
            EditSpec e = c.edit != null ? c.edit : EditSpec.whole(c.duration);
            boolean audio = c.hasAudio && !e.mute;
            double clipLen = e.outputDuration();
            List<EditSpec.Piece> segs = e.fill().pieces();
            int segN = segs.size();
            String[] aSrc = new String[segN];
            if (audio && c.audioTracks >= 2) {
                fc.append("[").append(ci).append(":a:1]volume=").append(Library.fmt(e.micVolume)).append("[mv").append(ci).append("];[")
                        .append(ci).append(":a:0][mv").append(ci).append("]amix=inputs=2:duration=longest:normalize=0,asplit=").append(segN + 1);
                for (int k = 0; k < segN; k++) { fc.append("[am").append(ci).append("_").append(k).append("]"); aSrc[k] = "[am" + ci + "_" + k + "]"; }
                fc.append(";");
            } else java.util.Arrays.fill(aSrc, "[" + ci + ":a:0]");
            for (int k = 0; k < segN; k++) {
                EditSpec.Piece s = segs.get(k);
                String v = "[v" + ci + "_" + k + "]", au = "[a" + ci + "_" + k + "]";
                fc.append("[").append(ci).append(":v]trim=start=").append(Library.fmt(s.start())).append(":end=").append(Library.fmt(s.end()))
                        .append(",setpts=(PTS-STARTPTS)/").append(Library.fmt(s.speed()))
                        .append(",scale=").append(W).append(":").append(H).append(":force_original_aspect_ratio=decrease:flags=lanczos")
                        .append(",pad=").append(W).append(":").append(H).append(":(ow-iw)/2:(oh-ih)/2:color=black,fps=").append(fps).append(",setsar=1,format=yuv420p");
                // dip to black at the start and end of every clip
                if (k == 0) fc.append(",fade=t=in:st=0:d=0.2");
                if (k == segN - 1) fc.append(",fade=t=out:st=").append(Library.fmt(Math.max(0, s.outputLength() - 0.2))).append(":d=0.2");
                fc.append(v).append(";");
                if (audio) {
                    fc.append(aSrc[k]).append("atrim=start=").append(Library.fmt(s.start())).append(":end=").append(Library.fmt(s.end()))
                            .append(",asetpts=PTS-STARTPTS").append(atempo(s.speed())).append(",volume=").append(Library.fmt(e.volume))
                            .append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo");
                } else {
                    fc.append("anullsrc=r=48000:cl=stereo,atrim=duration=").append(Library.fmt(s.outputLength())).append(",aformat=sample_fmts=fltp:channel_layouts=stereo");
                }
                fc.append(au).append(";");
                parts.add(v + au);
            }
            total += clipLen;
        }
        fc.append(String.join("", parts)).append("concat=n=").append(parts.size()).append(":v=1:a=1[vout][aout]");
        a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[vout]", "-map", "[aout]"));
        String enc = pickEncoder(o, false);
        a.addAll(List.of("-c:v", enc));
        a.addAll(qualityArgs(enc, 19, (long) (W * (double) H * fps * 0.15)));
        a.addAll(List.of("-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
        Ffmpeg.run(a, total, progress, job);
        return out;
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
            try { logo = ImageIO.read(new java.io.File(wm.image())); } catch (IOException ignored) {}
        }
        if (logo != null) {
            bw = Math.max(8, (int) Math.round(w * wm.size() / 100.0));
            bh = (int) Math.round(bw * logo.getHeight() / (double) logo.getWidth());
        } else {
            f = titleFont().deriveFont((float) Math.max(8, h * wm.size() / 100.0 * 0.6));
            FontMetrics fm = g.getFontMetrics(f);
            bw = fm.stringWidth(wm.text() == null ? "" : wm.text());
            bh = fm.getAscent() + fm.getDescent();
        }
        String pos = wm.position() == null ? "bottom-right" : wm.position();
        int x = pos.endsWith("left") ? margin : pos.endsWith("right") ? w - bw - margin : (w - bw) / 2;
        int y = pos.startsWith("top") ? margin : pos.startsWith("bottom") ? h - bh - margin : (h - bh) / 2;
        if (logo != null) {
            g.drawImage(logo, x, y, bw, bh, null);
        } else {
            FontMetrics fm = g.getFontMetrics(f);
            java.awt.Shape shape = new TextLayout(wm.text(), f, g.getFontRenderContext()).getOutline(AffineTransform.getTranslateInstance(x, y + fm.getAscent()));
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
        if ("meme".equals(style)) return renderText(text.toUpperCase(java.util.Locale.ROOT), position, w, h, 0.10f, 0.16f, false);
        if ("pop".equals(style)) return renderText(text.toUpperCase(java.util.Locale.ROOT), position, w, h, 0.085f, 0.2f, false, Color.WHITE);
        if ("highlight".equals(style)) return renderText(text.toUpperCase(java.util.Locale.ROOT), position, w, h, 0.085f, 0.2f, false, new Color(0xFFD23F));
        if ("subtitle".equals(style)) return renderText(text, position, w, h, 0.05f, 0.0f, true);
        return renderTitle(text, position, w, h);
    }

    private static BufferedImage renderText(String text, String position, int w, int h, float rel, float stroke, boolean box) {
        return renderText(text, position, w, h, rel, stroke, box, Color.WHITE);
    }

    private static BufferedImage renderText(String text, String position, int w, int h, float rel, float stroke, boolean box, Color fill) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float size = Math.min(w, h) * rel;
        Font f = titleFont().deriveFont(size);
        FontMetrics fm = g.getFontMetrics(f);
        while (fm.stringWidth(text) > w * 0.92 && size > 10) { size *= 0.92f; f = titleFont().deriveFont(size); fm = g.getFontMetrics(f); }
        int tw = fm.stringWidth(text);
        int x = (w - tw) / 2;
        int y = switch (position == null ? "bottom" : position) {
            case "top" -> (int) (h * 0.07) + fm.getAscent();
            case "center" -> (h - fm.getHeight()) / 2 + fm.getAscent();
            default -> (int) (h * (box ? 0.9 : 0.93)) - fm.getDescent();
        };
        if (box) {
            int pad = (int) (size * 0.45);
            g.setColor(new Color(0, 0, 0, 170));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(x - pad, y - fm.getAscent() - pad * 0.6, tw + pad * 2, fm.getAscent() + fm.getDescent() + pad * 1.2, pad, pad));
            g.setColor(Color.WHITE);
            g.setFont(f);
            g.drawString(text, x, y);
        } else {
            java.awt.Shape shape = new TextLayout(text, f, g.getFontRenderContext()).getOutline(AffineTransform.getTranslateInstance(x, y));
            g.setStroke(new BasicStroke(size * stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            g.draw(shape);
            g.setColor(fill);
            g.fill(shape);
        }
        g.dispose();
        return img;
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
    public static double clamp01(double v) { return clamp(v); }

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
