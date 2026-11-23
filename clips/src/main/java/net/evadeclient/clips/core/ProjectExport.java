package net.evadeclient.clips.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Hands an edit to After Effects, Premiere Pro or DaVinci Resolve, for work Evade Clips doesn't do (3D camera
 * shakes, Twixtor, third-party presets). Writes a project folder:
 * <ul>
 *   <li>{@code media/} the footage (the original file, or an edit-friendly ProRes copy) and each audio track as WAV</li>
 *   <li>{@code <title> - After Effects.jsx}: run it in After Effects (File → Scripts → Run Script File) and it
 *       builds the comp: one layer per kept part with cuts and speed ramps as time-remap keyframes (ready for
 *       Twixtor), zoom keyframes as scale/position keyframes, captions as text layers, markers, and effect sounds</li>
 *   <li>{@code <title> - Premiere & Resolve.xml}: Final Cut XML that Premiere Pro (File → Import) and DaVinci
 *       Resolve (File → Import Timeline) read: the cuts with their speeds, every audio track, and the markers</li>
 *   <li>{@code captions.srt}, {@code markers.txt} and a how-to</li>
 * </ul>
 * Everything is timed to the export: cut parts are gone and slow parts are stretched.
 */
public final class ProjectExport {
    private ProjectExport() {}

    public static Path export(Path src, Clip clip, EditSpec e, Path parent, boolean prores, DoubleConsumer progress, Ffmpeg.Job job) throws IOException {
        e.fill();
        String title = clip.title == null || clip.title.isBlank() ? "Clip" : clip.title;
        Path dir = Library.unique(parent, Library.safe(title) + " (project)");
        Path media = dir.resolve("media");
        Files.createDirectories(media);
        int fps = Exporter.snapFps(clip.fps);
        int w = clip.width > 0 ? clip.width : 1920, h = clip.height > 0 ? clip.height : 1080;

        // footage
        String base = Library.safe(title);
        Path footage;
        if (prores) {
            footage = media.resolve(base + ".mov");
            Ffmpeg.run(List.of("-y", "-i", src.toString(), "-map", "0:v:0", "-map", "0:a?", "-c:v", "prores_ks", "-profile:v", "2", "-pix_fmt", "yuv422p10le",
                    "-c:a", "pcm_s16le", footage.toString()), clip.duration, progress == null ? null : p -> progress.accept(p * 0.8), job);
        } else {
            footage = media.resolve(base + "." + Library.ext(src));
            Files.copy(src, footage);
        }
        // every audio track on its own, so mic, game and voice chat can be mixed in the editor; numbered, so two
        // tracks with the same (or no usable) name never share a file
        List<String[]> wavs = new ArrayList<>();
        for (int i = 0; i < clip.audioTracks; i++) {
            String name = Exporter.trackName(clip, i);
            String safe = Library.safe(name);
            if (safe.isBlank()) safe = "Track " + (i + 1);
            Path wav = media.resolve((i + 1) + " - " + safe + ".wav");
            Ffmpeg.run(List.of("-y", "-i", src.toString(), "-map", "0:a:" + i, "-c:a", "pcm_s16le", "-ar", "48000", wav.toString()));
            wavs.add(new String[]{name, wav.toString()});
        }
        // effect sounds the edit uses
        List<String[]> sfx = new ArrayList<>();
        for (EditSpec.Effect f : e.effects) {
            if (e.segmentAt(f.t) < 0) continue;
            Path s = Sounds.file(f.sound);
            if (s == null) continue;
            Path copy = media.resolve("sfx-" + Library.safe(f.sound) + "." + Library.ext(s));
            if (!Files.exists(copy)) Files.copy(s, copy);
            sfx.add(new String[]{f.sound, copy.toString(), Library.fmt(e.toOutput(f.t)), Library.fmt(f.volume)});
        }

        Files.writeString(dir.resolve(base + " - After Effects.jsx"), afterEffects(clip, e, title, footage, w, h, fps, sfx), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(base + " - Premiere & Resolve.xml"), fcpXml(clip, e, title, footage, wavs, w, h, fps), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("captions.srt"), srt(e), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("markers.txt"), markers(clip, e), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("HOW TO OPEN.txt"), """
                After Effects
                  File > Scripts > Run Script File... and pick the .jsx file in this folder.
                  You get a comp with every kept part as its own layer. Speed changes, ramps, rewinds and freeze
                  frames are time-remap keyframes (apply Twixtor or Timewarp to a layer for optical-flow slow motion),
                  zooms are scale and position keyframes, captions are text layers, and your markers and effects are
                  comp markers.

                Premiere Pro
                  File > Import... and pick the .xml file. It opens as a sequence with the cuts, speeds, audio
                  tracks (game, mic, voice chat on their own tracks) and markers.

                DaVinci Resolve
                  File > Import > Timeline... and pick the .xml file.

                Keep this folder together: the project files point at the media folder next to them.
                """, StandardCharsets.UTF_8);
        if (progress != null) progress.accept(1);
        return dir;
    }

    // ------------------------------------------------------------------ After Effects

    static String afterEffects(Clip clip, EditSpec e, String title, Path footage, int w, int h, int fps, List<String[]> sfx) {
        double dur = Math.max(0.1, e.outputDuration());
        StringBuilder js = new StringBuilder();
        js.append("// Made by Evade Clips. In After Effects: File > Scripts > Run Script File... and pick this file.\n");
        js.append("(function () {\n");
        js.append("  var here = File($.fileName).parent;\n");
        js.append("  function media(name) { var f = new File(here.fsName + '/media/' + name); if (!f.exists) { alert('Missing ' + f.fsName + '\\nKeep the project folder together.'); return null; } return app.project.importFile(new ImportOptions(f)); }\n");
        js.append("  app.beginUndoGroup('Evade Clips: ").append(jsStr(title)).append("');\n");
        js.append("  var footage = media(").append(jsq(footage.getFileName().toString())).append(");\n");
        js.append("  if (!footage) return;\n");
        js.append("  var comp = app.project.items.addComp(").append(jsq(title)).append(", ").append(w).append(", ").append(h).append(", 1, ")
                .append(num(dur)).append(", ").append(fps).append(");\n");
        js.append("  function remap(layer, keys) {\n");
        js.append("    layer.timeRemapEnabled = true;\n");
        js.append("    var tr = layer.property('ADBE Time Remapping');\n");
        js.append("    for (var i = 0; i < keys.length; i++) tr.setValueAtTime(keys[i][0], keys[i][1]);\n");
        js.append("    for (var k = tr.numKeys; k >= 1; k--) { var t = tr.keyTime(k), keep = false;\n");
        js.append("      for (var j = 0; j < keys.length; j++) if (Math.abs(keys[j][0] - t) < 0.0005) keep = true;\n");
        js.append("      if (!keep) tr.removeKey(k); }\n");
        js.append("    for (var m = 1; m <= tr.numKeys; m++) tr.setInterpolationTypeAtKey(m, KeyframeInterpolationType.LINEAR);\n");
        js.append("  }\n");
        // one layer per kept part, time remapped (cuts are layer edges; speed, ramps, rewinds and freeze frames
        // are the remap curve)
        double out = 0;
        int n = 0;
        for (EditSpec.Segment s : e.segments) {
            double segOut = s.outputLength();
            if (segOut <= 0.01) continue;
            n++;
            String what = s.rewinds() ? " (rewind)" : s.ramped() ? " (ramp)" : s.speed != 1 ? " (" + Library.fmt(s.speed) + "x)" : "";
            if (s.hold() > 0) what += " (freeze frame)";
            js.append("  var L").append(n).append(" = comp.layers.add(footage);\n");
            js.append("  L").append(n).append(".name = ").append(jsq("Part " + n + what)).append(";\n");
            StringBuilder keys = new StringBuilder("[");
            double t = out;
            keys.append("[").append(num(t)).append(",").append(num(s.start)).append("]");
            for (EditSpec.Piece p : s.pieces()) {
                // a reversed piece runs from its end back to its start; a freeze frame holds where it stopped
                double to = p.reverse() ? p.start() : p.end();
                t += p.playLength();
                keys.append(",[").append(num(t)).append(",").append(num(to)).append("]");
                if (p.hold() > 0) {
                    t += p.hold();
                    keys.append(",[").append(num(t)).append(",").append(num(to)).append("]");
                }
            }
            keys.append("]");
            js.append("  remap(L").append(n).append(", ").append(keys).append(");\n");
            js.append("  L").append(n).append(".inPoint = ").append(num(out)).append("; L").append(n).append(".outPoint = ").append(num(out + segOut)).append(";\n");
            out += segOut;
        }
        // zoom keyframes → scale and position on every part
        List<EditSpec.ZoomKey> zk = e.sortedZooms();
        if (!zk.isEmpty()) {
            js.append("  for (var z = 1; z <= ").append(n).append("; z++) { var L = comp.layer(z);\n");
            for (EditSpec.ZoomKey k : zk) {
                // a key inside a cut part counts at the cut (a zoom-out there still happens)
                double t = e.toOutput(k.t), zf = Math.max(1, k.zoom);
                double px = w / 2.0 + (0.5 - k.cx) * w * zf, py = h / 2.0 + (0.5 - k.cy) * h * zf;
                js.append("    L.property('Scale').setValueAtTime(").append(num(t)).append(", [").append(num(zf * 100)).append(", ").append(num(zf * 100)).append("]);\n");
                js.append("    L.property('Position').setValueAtTime(").append(num(t)).append(", [").append(num(px)).append(", ").append(num(py)).append("]);\n");
            }
            js.append("  }\n");
        }
        // captions as text layers
        int c = 0;
        for (EditSpec.Caption cap : e.captions) {
            if (cap.text == null || cap.text.isBlank()) continue;
            double s = e.toOutput(cap.start), en = e.toOutput(cap.end);
            if (en - s < 0.04) continue;
            c++;
            String text = cap.style.equals("meme") || cap.style.equals("pop") || cap.style.equals("highlight") ? cap.text.toUpperCase(Locale.ROOT) : cap.text;
            double y = switch (cap.position) { case "top" -> h * 0.12; case "center" -> h * 0.5; default -> h * 0.88; };
            js.append("  var T").append(c).append(" = comp.layers.addText(").append(jsq(text)).append(");\n");
            js.append("  (function (t) { var p = t.property('Source Text'), d = p.value; d.fontSize = ").append(Math.round(Math.min(w, h) * (cap.style.equals("subtitle") ? 0.05 : 0.085)))
                    .append("; d.fillColor = ").append(cap.style.equals("highlight") ? "[1, 0.82, 0.25]" : "[1, 1, 1]")
                    .append("; d.applyStroke = true; d.strokeColor = [0, 0, 0]; d.strokeWidth = ").append(Math.round(Math.min(w, h) * 0.008))
                    .append("; d.justification = ParagraphJustification.CENTER_JUSTIFY; p.setValue(d);\n");
            js.append("    t.property('Position').setValue([").append(num(w / 2.0)).append(", ").append(num(y)).append("]); t.inPoint = ").append(num(s))
                    .append("; t.outPoint = ").append(num(en)).append("; })(T").append(c).append(");\n");
        }
        // markers and effects as comp markers
        for (int i = 0; i < clip.markers.size(); i++) {
            double m = clip.markers.get(i);
            if (e.segmentAt(m) < 0) continue;
            js.append("  comp.markerProperty.setValueAtTime(").append(num(e.toOutput(m))).append(", new MarkerValue(").append(jsq(label(clip, m))).append("));\n");
        }
        for (EditSpec.Effect f : e.effects) {
            if (e.segmentAt(f.t) < 0) continue;
            String what = switch (f.type) { case "shake" -> "Shake"; case "punch" -> "Zoom punch"; case "flash" -> "Flash"; case "hitmarker" -> "Hitmarker"; default -> "Sound"; };
            js.append("  comp.markerProperty.setValueAtTime(").append(num(e.toOutput(f.t))).append(", new MarkerValue(").append(jsq(what + (f.sound.isBlank() ? "" : " + " + f.sound))).append("));\n");
        }
        for (String[] s : sfx) {
            js.append("  (function () { var a = media(").append(jsq(Path.of(s[1]).getFileName().toString())).append("); if (!a) return; var l = comp.layers.add(a); l.startTime = ")
                    .append(s[2]).append("; l.name = ").append(jsq(s[0])).append("; })();\n");
        }
        js.append("  comp.openInViewer();\n");
        js.append("  app.endUndoGroup();\n");
        js.append("})();\n");
        return js.toString();
    }

    // ------------------------------------------------------------------ Premiere / Resolve (FCP 7 XML)

    /**
     * A clip on the timeline: source frames in..out played over len frames (speed = how fast, reverse =
     * backwards). A freeze frame is its own item: one frame stretched over the hold.
     */
    private record Item(long in, long out, long len, double speed, boolean reverse, boolean still) {}

    private static List<Item> items(EditSpec e, int fps) {
        List<Item> out = new ArrayList<>();
        for (EditSpec.Piece p : e.pieces()) {
            long len = frames(p.playLength(), fps);
            if (len > 0) out.add(new Item(frames(p.start(), fps), frames(p.end(), fps), len, p.speed(), p.reverse(), false));
            long hold = frames(p.hold(), fps);
            if (hold > 0) {
                long last = Math.max(0, frames(p.reverse() ? p.start() : p.end(), fps) - (p.reverse() ? 0 : 1));
                out.add(new Item(last, last + 1, hold, 1.0 / hold, false, true));
            }
        }
        return out;
    }

    static String fcpXml(Clip clip, EditSpec e, String title, Path footage, List<String[]> wavs, int w, int h, int fps) {
        List<Item> pieces = items(e, fps);
        long total = 0;
        for (Item p : pieces) total += p.len();
        long srcFrames = frames(clip.duration, fps);
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"5\">\n");
        x.append(" <sequence id=\"sequence-1\">\n  <name>").append(xml(title)).append("</name>\n  <duration>").append(total).append("</duration>\n");
        x.append(rate(fps, "  "));
        x.append("  <media>\n   <video>\n    <format><samplecharacteristics><width>").append(w).append("</width><height>").append(h)
                .append("</height><pixelaspectratio>square</pixelaspectratio>").append(rate(fps, "")).append("</samplecharacteristics></format>\n    <track>\n");
        long pos = 0;
        int id = 0;
        for (Item p : pieces) {
            long len = p.len();
            id++;
            x.append("     <clipitem id=\"v").append(id).append("\">\n      <name>").append(xml(p.still() ? title + " (freeze frame)" : title)).append("</name>\n");
            x.append("      <duration>").append(srcFrames).append("</duration>\n").append(rate(fps, "      "));
            x.append("      <start>").append(pos).append("</start><end>").append(pos + len).append("</end>\n");
            x.append("      <in>").append(p.in()).append("</in><out>").append(p.out()).append("</out>\n");
            x.append(id == 1 ? file(footage, clip, srcFrames, w, h, fps) : "      <file id=\"file-1\"/>\n");
            if (Math.abs(p.speed() - 1) > 1e-3 || p.reverse()) x.append(speed(p.speed(), p.reverse()));
            x.append("     </clipitem>\n");
            pos += len;
        }
        x.append("    </track>\n   </video>\n   <audio>\n");
        // the clip's own audio (all tracks), then each track separately so they can be balanced
        for (int t = 0; t < Math.max(1, wavs.size()); t++) {
            x.append("    <track>\n");
            pos = 0;
            int aid = 0;
            for (Item p : pieces) {
                long len = p.len();
                // a freeze frame is silent
                if (p.still()) { pos += len; continue; }
                aid++;
                x.append("     <clipitem id=\"a").append(t).append("-").append(aid).append("\">\n      <name>").append(xml(wavs.isEmpty() ? title : wavs.get(t)[0])).append("</name>\n");
                x.append("      <duration>").append(srcFrames).append("</duration>\n").append(rate(fps, "      "));
                x.append("      <start>").append(pos).append("</start><end>").append(pos + len).append("</end>\n");
                x.append("      <in>").append(p.in()).append("</in><out>").append(p.out()).append("</out>\n");
                if (wavs.isEmpty()) x.append("      <file id=\"file-1\"/>\n      <sourcetrack><mediatype>audio</mediatype><trackindex>1</trackindex></sourcetrack>\n");
                else if (aid == 1) x.append(wavFile(t, wavs.get(t), srcFrames, fps));
                else x.append("      <file id=\"wav-").append(t).append("\"/>\n");
                if (Math.abs(p.speed() - 1) > 1e-3 || p.reverse()) x.append(speed(p.speed(), p.reverse()));
                x.append("     </clipitem>\n");
                pos += len;
            }
            x.append("    </track>\n");
        }
        x.append("   </audio>\n  </media>\n");
        for (double m : clip.markers) {
            if (e.segmentAt(m) < 0) continue;
            long f = frames(e.toOutput(m), fps);
            x.append("  <marker><name>").append(xml(label(clip, m))).append("</name><in>").append(f).append("</in><out>-1</out></marker>\n");
        }
        x.append(" </sequence>\n</xmeml>\n");
        return x.toString();
    }

    private static String file(Path footage, Clip clip, long srcFrames, int w, int h, int fps) {
        return "      <file id=\"file-1\">\n       <name>" + xml(footage.getFileName().toString()) + "</name>\n       <pathurl>" + xml(url(footage)) + "</pathurl>\n"
                + rate(fps, "       ") + "       <duration>" + srcFrames + "</duration>\n       <media><video><samplecharacteristics><width>" + w + "</width><height>" + h
                + "</height></samplecharacteristics></video>" + (clip.hasAudio ? "<audio><channelcount>2</channelcount></audio>" : "") + "</media>\n      </file>\n";
    }

    private static String wavFile(int t, String[] wav, long srcFrames, int fps) {
        Path p = Path.of(wav[1]);
        return "      <file id=\"wav-" + t + "\">\n       <name>" + xml(p.getFileName().toString()) + "</name>\n       <pathurl>" + xml(url(p)) + "</pathurl>\n"
                + rate(fps, "       ") + "       <duration>" + srcFrames + "</duration>\n       <media><audio><channelcount>2</channelcount></audio></media>\n      </file>\n";
    }

    private static String speed(double speed, boolean reverse) {
        return "      <filter><effect><name>Time Remap</name><effectid>timeremap</effectid><effectcategory>motion</effectcategory><effecttype>motion</effecttype>"
                + "<mediatype>video</mediatype><parameter><parameterid>speed</parameterid><name>speed</name><value>" + num(speed * 100)
                + "</value></parameter><parameter><parameterid>reverse</parameterid><name>reverse</name><value>" + (reverse ? "TRUE" : "FALSE")
                + "</value></parameter></effect></filter>\n";
    }

    private static String rate(int fps, String indent) {
        return indent + "<rate><timebase>" + fps + "</timebase><ntsc>FALSE</ntsc></rate>\n";
    }

    private static long frames(double seconds, int fps) { return Math.round(seconds * fps); }

    private static String url(Path p) {
        String abs = p.toAbsolutePath().toString().replace('\\', '/');
        if (!abs.startsWith("/")) abs = "/" + abs;
        try {
            return "file://localhost" + new java.net.URI("file", null, abs, null).getRawPath();
        } catch (java.net.URISyntaxException e) {
            return "file://localhost" + abs.replace(" ", "%20");
        }
    }

    // ------------------------------------------------------------------ captions and markers

    static String srt(EditSpec e) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (EditSpec.Caption c : e.captions) {
            if (c.text == null || c.text.isBlank()) continue;
            double s = e.toOutput(c.start), en = e.toOutput(c.end);
            if (en - s < 0.04) continue;
            sb.append(++n).append('\n').append(srtTime(s)).append(" --> ").append(srtTime(en)).append('\n').append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    private static String srtTime(double t) {
        long ms = Math.round(t * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private static String markers(Clip clip, EditSpec e) {
        StringBuilder sb = new StringBuilder("Time in the export\tMarker\n");
        for (double m : clip.markers) if (e.segmentAt(m) >= 0) sb.append(srtTime(e.toOutput(m)).replace(',', '.')).append('\t').append(label(clip, m)).append('\n');
        for (EditSpec.Effect f : e.effects) if (e.segmentAt(f.t) >= 0) sb.append(srtTime(e.toOutput(f.t)).replace(',', '.')).append('\t').append("Effect: ").append(f.type)
                .append(f.sound.isBlank() ? "" : " + " + f.sound).append('\n');
        return sb.toString();
    }

    private static String label(Clip clip, double m) {
        String l = clip.label(m);
        return l == null || l.isBlank() ? "Marker" : l;
    }

    private static String num(double v) { return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private static String jsq(String s) { return "'" + jsStr(s) + "'"; }
    private static String jsStr(String s) { return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", ""); }
    private static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
