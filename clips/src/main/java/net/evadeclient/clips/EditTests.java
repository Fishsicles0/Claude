package net.evadeclient.clips;

import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Library;
import net.evadeclient.clips.core.Sounds;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks for the editor's creative tools, by exporting real video through ffmpeg and measuring the result:
 * speed ramps and smooth slow motion, the facecam split layout, zoom keyframes, shake / flash / hitmarker /
 * sounds, blurred areas, pop-in captions, auto-captions, project export for After Effects and Premiere, and
 * presets. Run alone with {@code --edittest DIR}; the full self-test runs them too.
 */
public final class EditTests {
    private int passed;
    private final List<String> failures = new ArrayList<>();
    private final Path dir;

    private EditTests(Path dir) { this.dir = dir; }

    public static int run(Path dir) throws Exception {
        EditTests t = new EditTests(dir);
        t.go();
        System.out.println();
        System.out.println(t.failures.isEmpty() ? "ALL " + t.passed + " EDIT CHECKS PASSED" : t.failures.size() + " FAILED, " + t.passed + " passed");
        return t.failures.size();
    }

    private void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++; else failures.add(what);
    }

    private void go() throws Exception {
        Files.createDirectories(dir);
        if (System.getProperty("clips.editTestOwnAppDir") == null) net.evadeclient.clips.core.Settings.overrideAppDir(dir.resolve("appdata"));
        // 8 s of 720p30 with a moving pattern and a quiet tone (so added sounds are easy to measure).
        Path srcFile = dir.resolve("edit-src.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=8", "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000:d=8",
                "-af", "volume=0.03", "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-g", "15", "-c:a", "aac", srcFile.toString()));
        Clip src = new Clip();
        src.id = "edittest";
        src.duration = 8; src.width = 1280; src.height = 720; src.fps = 30; src.hasAudio = true; src.audioTracks = 1;
        Exporter.Options o = new Exporter.Options();
        o.preset = Exporter.Preset.SOURCE;
        o.gpu = false;

        EditSpec plain = EditSpec.whole(8);
        Path plainOut = export(srcFile, src, plain, o, "plain");

        // ---- speed ramp: 1x easing into 0.25x around 4 s and back out, with blended in-between frames
        EditSpec ramp = EditSpec.whole(8);
        check(ramp.rampAround(4, 0.25, 1.5, 1.5), "speed ramp set up around 4 s");
        double want = ramp.outputDuration();
        Path rampOut = export(srcFile, src, ramp, o, "ramp");
        Ffmpeg.MediaInfo ri = Ffmpeg.probe(rampOut);
        check(want > 9 && Math.abs(ri.duration() - want) < 0.35,
                String.format("speed ramp stretches 8 s to %.2f s as planned (got %.2f s)", want, ri.duration()));
        int frames = frameCount(rampOut);
        check(Math.abs(frames - want * 30) < 8, String.format("ramped export plays at a steady 30 fps (%d frames for %.2f s)", frames, want));
        double outAt4 = ramp.toOutput(4);
        check(outAt4 > 4.2 && outAt4 < want - 1, String.format("the ramp's middle lands at %.2f s of the export", outAt4));
        // blended slow-mo: consecutive frames in the slow part differ (new in-between frames, not repeats)
        BufferedImage s1 = frame(rampOut, outAt4), s2 = frame(rampOut, outAt4 + 1 / 30.0);
        check(meanDiff(s1, s2) > 0.4, String.format("slow motion has blended in-between frames, not repeats (frame-to-frame change %.2f)", meanDiff(s1, s2)));

        // optical-flow smoothing on a short slow part
        EditSpec flow = EditSpec.whole(8);
        flow.segments.get(0).start = 2; flow.segments.get(0).end = 3.5; flow.segments.get(0).speed = 0.5;
        flow.smoothing = "flow";
        Path flowOut = export(srcFile, src, flow, o, "flow");
        Ffmpeg.MediaInfo fi = Ffmpeg.probe(flowOut);
        check(Math.abs(fi.duration() - 3) < 0.25 && Math.abs(frameCount(flowOut) - 90) < 6,
                String.format("optical-flow slow motion: 1.5 s at 0.5x → %.2f s, %d frames", fi.duration(), frameCount(flowOut)));

        // ---- split layout: facecam (top-right of the gameplay) on top, gameplay below, 9:16
        EditSpec split = EditSpec.whole(8);
        split.aspect = "9:16";
        split.fitMode = "split";
        Path splitOut = export(srcFile, src, split, o, "split");
        Ffmpeg.MediaInfo si = Ffmpeg.probe(splitOut);
        check(si.width() == 720 && si.height() == 1280, "facecam split is vertical 720x1280 (got " + si.width() + "x" + si.height() + ")");
        BufferedImage sf = frame(splitOut, 2);
        int camOut = (int) Math.round(1280 * split.camShare) / 2 * 2;
        double divider = luma(sf, 0, camOut - 2, sf.getWidth(), camOut + 1);
        check(divider < 20, String.format("a dark divider separates facecam and gameplay (luma %.0f)", divider));

        // ---- zoom keyframes: no zoom at 0.5 s, 2x into the top-left by 3 s
        EditSpec zoom = EditSpec.whole(8);
        zoom.zooms.add(new EditSpec.ZoomKey(1, 1, 0.5, 0.5));
        zoom.zooms.add(new EditSpec.ZoomKey(3, 2, 0.25, 0.25));
        Path zoomOut = export(srcFile, src, zoom, o, "zoom");
        double early = meanDiff(frame(zoomOut, 0.5), frame(plainOut, 0.5)), late = meanDiff(frame(zoomOut, 4), frame(plainOut, 4));
        check(early < 6 && late > 25, String.format("zoom keyframes: unchanged at 0.5 s (diff %.1f), zoomed by 4 s (diff %.1f)", early, late));
        // the zoomed frame at 4 s matches the top-left quarter of the original, scaled up
        BufferedImage z4 = frame(zoomOut, 4), p4 = frame(plainOut, 4);
        BufferedImage quarter = scale(p4.getSubimage(0, 0, 640, 360), 1280, 720);
        check(meanDiff(z4, quarter) < late * 0.6, String.format("2x zoom shows the top-left quarter (diff to it %.1f vs %.1f)", meanDiff(z4, quarter), late));

        // ---- effects
        EditSpec fx = EditSpec.whole(8);
        EditSpec.Effect flash = new EditSpec.Effect("flash", 2, "");
        flash.strength = 1;
        fx.effects.add(flash);
        fx.effects.add(new EditSpec.Effect("hitmarker", 3, "Hitmarker"));
        fx.effects.add(new EditSpec.Effect("shake", 5, "Boom"));
        fx.effects.add(new EditSpec.Effect("punch", 6.5, "Whoosh"));
        Path fxOut = export(srcFile, src, fx, o, "fx");
        double before = luma(frame(fxOut, 1.8)), during = luma(frame(fxOut, 2.03));
        check(during > before + 40, String.format("flash brightens the frame (luma %.0f → %.0f)", before, during));
        BufferedImage hm = frame(fxOut, 3.06), noHm = frame(fxOut, 2.7);
        double hmWhite = whiteShare(hm, 640 - 60, 360 - 60, 640 + 60, 360 + 60), noWhite = whiteShare(noHm, 640 - 60, 360 - 60, 640 + 60, 360 + 60);
        check(hmWhite > noWhite + 0.02, String.format("hitmarker shows at 3 s (white around the centre %.1f%% vs %.1f%%)", hmWhite * 100, noWhite * 100));
        double shake = meanDiff(frame(fxOut, 5.1), frame(plainOut, 5.1)), calm = meanDiff(frame(fxOut, 4.5), frame(plainOut, 4.5));
        check(shake > calm + 5, String.format("screen shake moves the frame at 5 s (diff %.1f vs %.1f before)", shake, calm));
        double punch = meanDiff(frame(fxOut, 6.72), frame(plainOut, 6.72));
        check(punch > calm + 5, String.format("zoom punch at 6.5 s (diff %.1f)", punch));
        double quietDb = loudness(fxOut, 1.0, 0.5), boomDb = loudness(fxOut, 5.0, 0.5);
        check(boomDb > quietDb + 12, String.format("effect sound mixed in (%.1f dB at the boom vs %.1f dB before)", boomDb, quietDb));
        check(Sounds.list().containsAll(List.of("Hitmarker", "Boom", "Whoosh", "Air horn", "Riser")), "built-in sound library: " + Sounds.list().size() + " sounds");

        // ---- blurred area: the top-left corner loses its detail
        EditSpec mask = EditSpec.whole(8);
        EditSpec.Mask m = new EditSpec.Mask();
        m.start = 0; m.end = 8; m.x = 0; m.y = 0; m.w = 0.3; m.h = 0.3;
        mask.masks.add(m);
        Path maskOut = export(srcFile, src, mask, o, "mask");
        double detailBlur = detail(frame(maskOut, 2), 0, 0, 380, 210), detailPlain = detail(frame(plainOut, 2), 0, 0, 380, 210);
        double restBlur = detail(frame(maskOut, 2), 640, 300, 1200, 700), restPlain = detail(frame(plainOut, 2), 640, 300, 1200, 700);
        check(detailBlur < detailPlain * 0.6 && Math.abs(restBlur - restPlain) < restPlain * 0.15,
                String.format("blur hides the masked area only (detail %.1f → %.1f there, %.1f → %.1f elsewhere)", detailPlain, detailBlur, restPlain, restBlur));

        // ---- pop caption
        EditSpec pop = EditSpec.whole(8);
        pop.captions.add(new EditSpec.Caption("clutch", 1, 1.6, "center", "pop"));
        pop.captions.add(new EditSpec.Caption("time", 1.6, 2.2, "center", "highlight"));
        Path popOut = export(srcFile, src, pop, o, "pop");
        double popOn = whiteShare(frame(popOut, 1.4), 300, 280, 980, 440), popOff = whiteShare(frame(popOut, 3), 300, 280, 980, 440);
        check(popOn > popOff + 0.04, String.format("pop caption on screen while its word is spoken (%.0f%% vs %.0f%% white)", popOn * 100, popOff * 100));

        autoCaptionTests();
        presetTests(srcFile, src, o);
        projectTests(srcFile, src);
    }

    /** Presets: built-ins apply at every marker, files round-trip, junk is refused, community publish and browse. */
    private void presetTests(Path srcFile, Clip src, Exporter.Options o) throws Exception {
        src.markers.clear();
        src.markers.add(2.5);
        src.markers.add(6.0);
        src.markerLabels.put(Clip.key(2.5), "Kill Steve");
        var sniper = net.evadeclient.clips.core.EditPresets.builtIns().stream().filter(p -> p.name.equals("Sniper ramp")).findFirst().orElseThrow();
        EditSpec e = EditSpec.whole(8);
        int used = net.evadeclient.clips.core.EditPresets.apply(sniper, e, src);
        long hits = e.effects.stream().filter(f -> f.type.equals("hitmarker") && f.sound.equals("Sniper shot")).count();
        long ramps = e.segments.stream().filter(EditSpec.Segment::ramped).count();
        check(used == 2 && hits == 2 && ramps == 4 && e.outputDuration() > 10,
                String.format("\"Sniper ramp\" preset: a ramp and a hitmarker at both markers (%d hitmarkers, %d ramps, %.2f s)", hits, ramps, e.outputDuration()));
        Path presetOut = export(srcFile, src, e, o, "preset");
        check(Math.abs(Ffmpeg.probe(presetOut).duration() - e.outputDuration()) < 0.4, "a preset-made edit exports");

        var mine = net.evadeclient.clips.core.EditPresets.fromEdit("My style", e, src);
        mine.description = "test";
        Path f = net.evadeclient.clips.core.EditPresets.save(mine);
        var back = net.evadeclient.clips.core.EditPresets.read(f);
        check(back.name.equals("My style") && back.atMarker != null && back.atMarker.rampSpeed > 0 && !back.atMarker.effects.isEmpty()
                && net.evadeclient.clips.core.EditPresets.mine().size() == 1, "save your edit as a preset and read it back (" + net.evadeclient.clips.core.EditPresets.summary(back) + ")");
        var junk = net.evadeclient.clips.core.EditPresets.parse("{\"name\":\"x\",\"aspect\":\"../../etc\",\"atMarker\":{\"effects\":[{\"type\":\"rm -rf\",\"sound\":\"C:/Windows/x.wav\",\"offset\":999}]}}");
        check(junk.aspect == null && junk.atMarker.effects.get(0).type.equals("sound") && junk.atMarker.effects.get(0).offset == 3,
                "presets from others are cleaned: unknown values dropped, effects limited");
        boolean refused = false;
        try { net.evadeclient.clips.core.EditPresets.parse("[1,2,3]"); } catch (java.io.IOException ex) { refused = true; }
        check(refused, "a file that isn't a preset is refused");

        String url = System.getProperty("clips.shareUrl");
        if (url == null) { System.out.println("  SKIP  community presets (set CLIPS_SHARE_URL to a running clips-share server)"); return; }
        var settings = new net.evadeclient.clips.core.Settings();
        settings.cloudUrl = url;
        var cloud = new net.evadeclient.clips.core.CloudShare(settings);
        mine.author = "tester";
        String id = net.evadeclient.clips.core.EditPresets.publish(cloud, mine);
        var list = net.evadeclient.clips.core.EditPresets.community(cloud, "new");
        boolean listed = list.stream().anyMatch(p -> id.equals(p.communityId) && p.name.equals("My style") && p.author.equals("tester"));
        var fetched = net.evadeclient.clips.core.EditPresets.fetch(cloud, id);
        var popular = net.evadeclient.clips.core.EditPresets.community(cloud, "popular");
        int downloads = popular.stream().filter(p -> id.equals(p.communityId)).mapToInt(p -> p.downloads).findFirst().orElse(-1);
        check(listed && fetched.atMarker != null && fetched.atMarker.rampSpeed > 0 && downloads == 1,
                "community presets: publish, list, download (counted: " + downloads + ")");
        var page = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/presets")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var file = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/p/" + id + ".evadepreset")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        check(page.statusCode() == 200 && page.body().contains("My style") && file.statusCode() == 200
                && net.evadeclient.clips.core.EditPresets.parse(file.body()).name.equals("My style"), "the /presets page lists it and its download imports");
    }

    /** After Effects script + Premiere/Resolve XML + audio stems + captions. */
    private void projectTests(Path srcFile, Clip src) throws Exception {
        EditSpec e = EditSpec.whole(8);
        e.splitAt(3);
        e.splitAt(4);
        e.segments.remove(1);                 // cut 3..4
        e.rampAround(6, 0.5, 1, 1);
        e.captions.add(new EditSpec.Caption("clutch", 1, 2, "center", "pop"));
        e.zooms.add(new EditSpec.ZoomKey(1, 1, 0.5, 0.5));
        e.zooms.add(new EditSpec.ZoomKey(2, 1.5, 0.3, 0.4));
        e.effects.add(new EditSpec.Effect("shake", 2.5, "Boom"));
        src.title = "Project test";
        Path dirOut = net.evadeclient.clips.core.ProjectExport.export(srcFile, src, e, dir, false, null, null);
        Path jsx = dirOut.resolve("Project test - After Effects.jsx"), xml = dirOut.resolve("Project test - Premiere & Resolve.xml");
        String js = Files.readString(jsx);
        long parts = js.lines().filter(l -> l.contains("remap(L")).count();
        check(Files.exists(dirOut.resolve("media/Project test.mp4")) && Files.exists(dirOut.resolve("media/Game.wav")) && Files.exists(dirOut.resolve("media/sfx-Boom.wav")),
                "project folder: footage, each audio track as WAV, and the effect sound");
        check(parts == e.segments.size() && js.contains("addText('CLUTCH')") && js.contains("setValueAtTime") && js.contains("Kill Steve") && js.contains("Shake + Boom"),
                "After Effects script: " + parts + " time-remapped parts, caption text layer, zoom keyframes, markers and effects");
        var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());
        var seq = (org.w3c.dom.Element) doc.getElementsByTagName("sequence").item(0);
        long seqFrames = Long.parseLong(seq.getElementsByTagName("duration").item(0).getTextContent());
        int speedFx = doc.getElementsByTagName("effectid").getLength();
        check(Math.abs(seqFrames - e.outputDuration() * 30) <= e.pieces().size() && speedFx > 0 && doc.getElementsByTagName("marker").getLength() == 2,
                String.format("Premiere/Resolve XML parses: %d frames for %.2f s, %d speed changes, 2 markers", seqFrames, e.outputDuration(), speedFx));
        String srt = Files.readString(dirOut.resolve("captions.srt"));
        check(srt.startsWith("1\n00:00:01,000 --> 00:00:02,000\nclutch"), "captions.srt timed to the export");
        if (Boolean.getBoolean("clips.keepProject")) System.out.println("        project at " + dirOut.toAbsolutePath());
    }

    /** Speech (made with espeak-ng) → words with times → captions. Skipped without the speech engine and model. */
    private void autoCaptionTests() throws Exception {
        if (!net.evadeclient.clips.core.AutoCaptions.ready()) { System.out.println("  SKIP  auto-captions (speech engine or model not installed)"); return; }
        Path wav = dir.resolve("speech.wav");
        String said = "one more kill and we win this round. that was the best clutch ever";
        try {
            Process tts = new ProcessBuilder("espeak-ng", "-s", "150", "-w", wav.toString(), said).redirectErrorStream(true).start();
            tts.getInputStream().readAllBytes();
            if (tts.waitFor() != 0 || !Files.exists(wav)) throw new Exception("espeak-ng failed");
        } catch (Exception e) { System.out.println("  SKIP  auto-captions (no espeak-ng to make test speech)"); return; }
        Path talk = dir.resolve("talk.mp4");
        // 1 s of silence, then the sentence, on a clip's only audio track
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "color=c=gray:s=640x360:r=30:d=8", "-i", wav.toString(), "-filter_complex", "[1:a]adelay=1000|1000,apad[a]",
                "-map", "0:v", "-map", "[a]", "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-ar", "48000", talk.toString()));
        Clip c = new Clip();
        c.duration = 8; c.width = 640; c.height = 360; c.fps = 30; c.hasAudio = true; c.audioTracks = 1;
        long t0 = System.currentTimeMillis();
        var words = net.evadeclient.clips.core.AutoCaptions.transcribe(talk, c, net.evadeclient.clips.core.AutoCaptions.Source.AUTO, 0, 8, null);
        long ms = System.currentTimeMillis() - t0;
        List<String> heard = words.stream().map(net.evadeclient.clips.core.AutoCaptions.Word::text).toList();
        long known = java.util.Arrays.stream(said.replace(".", "").split(" ")).filter(heard::contains).distinct().count();
        check(words.size() >= 8 && known >= 7, "auto-captions hear the speech: " + heard + " (" + known + " of the words, " + ms + " ms)");
        boolean timed = !words.isEmpty() && words.get(0).start() >= 0.8 && words.get(0).start() < 1.6;
        for (int i = 1; i < words.size(); i++) timed &= words.get(i).start() >= words.get(i - 1).start();
        check(timed, String.format("word times line up with the audio (first word at %.2f s)", words.isEmpty() ? -1 : words.get(0).start()));
        var single = net.evadeclient.clips.core.AutoCaptions.build(words, net.evadeclient.clips.core.AutoCaptions.Layout.WORD, "center");
        var subs = net.evadeclient.clips.core.AutoCaptions.build(words, net.evadeclient.clips.core.AutoCaptions.Layout.SUBTITLE, "bottom");
        boolean oneEach = single.size() == words.size() && single.stream().allMatch(x -> !x.text.contains(" ") && x.end > x.start);
        check(oneEach && subs.size() >= 1 && subs.size() <= 4 && subs.stream().allMatch(x -> x.style.equals("subtitle")),
                single.size() + " single-word pop captions and " + subs.size() + " subtitle lines");
    }

    private Path export(Path src, Clip clip, EditSpec e, Exporter.Options o, String name) throws Exception {
        Path out = dir.resolve("edit-" + name + ".mp4");
        Files.deleteIfExists(out);
        long t0 = System.currentTimeMillis();
        try {
            Exporter.export(src, clip, e, o, out, null, null);
        } catch (Exception ex) {
            check(false, name + " export: " + ex.getMessage());
            throw ex;
        }
        System.out.println("        (" + name + " exported in " + (System.currentTimeMillis() - t0) + " ms)");
        return out;
    }

    static int frameCount(Path video) throws Exception {
        Process p = new ProcessBuilder(Ffmpeg.ffprobe(), "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames",
                "-of", "csv=p=0", video.toString()).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        try { return Integer.parseInt(s.split("\\s+")[0]); } catch (Exception e) { return -1; }
    }

    private BufferedImage frame(Path video, double t) throws Exception {
        Path png = dir.resolve("f-" + video.getFileName() + "-" + t + ".png");
        Ffmpeg.run(List.of("-y", "-ss", Library.fmt(t), "-i", video.toString(), "-frames:v", "1", png.toString()));
        return ImageIO.read(png.toFile());
    }

    /** Mean loudness (dB) of a window of the export's audio. */
    private static double loudness(Path video, double from, double len) throws Exception {
        Process p = new ProcessBuilder(Ffmpeg.ffmpeg(), "-hide_banner", "-ss", Library.fmt(from), "-t", Library.fmt(len), "-i", video.toString(),
                "-af", "volumedetect", "-vn", "-f", "null", "-").redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        var m = java.util.regex.Pattern.compile("mean_volume: (-?[0-9.]+) dB").matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : -99;
    }

    private static BufferedImage scale(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static double meanDiff(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
        double sum = 0;
        int n = 0;
        for (int y = 0; y < h; y += 3) for (int x = 0; x < w; x += 3) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            sum += (Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255))) / 3.0;
            n++;
        }
        return sum / Math.max(1, n);
    }

    private static double luma(BufferedImage img) { return luma(img, 0, 0, img.getWidth(), img.getHeight()); }

    private static double luma(BufferedImage img, int x0, int y0, int x1, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y += 2) for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x += 2) {
            int p = img.getRGB(x, y);
            sum += 0.299 * (p >> 16 & 255) + 0.587 * (p >> 8 & 255) + 0.114 * (p & 255);
            n++;
        }
        return sum / Math.max(1, n);
    }

    private static double whiteShare(BufferedImage img, int x0, int y0, int x1, int y1) {
        int white = 0, n = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            int p = img.getRGB(x, y);
            n++;
            if ((p >> 16 & 255) > 230 && (p >> 8 & 255) > 230 && (p & 255) > 230) white++;
        }
        return white / (double) Math.max(1, n);
    }

    /** Average difference between neighbouring pixels: high for sharp detail, low after a blur. */
    private static double detail(BufferedImage img, int x0, int y0, int x1, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1 - 1; y += 2) for (int x = x0; x < x1 - 1; x += 2) {
            int p = img.getRGB(x, y), q = img.getRGB(x + 1, y);
            sum += Math.abs((p >> 8 & 255) - (q >> 8 & 255));
            n++;
        }
        return sum / Math.max(1, n);
    }
}
