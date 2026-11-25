package net.evadeclient.clips;

import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Highlights;
import net.evadeclient.clips.core.Library;
import net.evadeclient.clips.core.Settings;
import net.evadeclient.clips.core.Sounds;
import net.evadeclient.clips.ui.App;
import net.evadeclient.clips.ui.EditorPanel;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks for the editor's creative tools, by exporting real video through ffmpeg and measuring the result:
 * speed ramps and smooth slow motion, the facecam split layout, zoom keyframes, shake / flash / hitmarker /
 * sounds, blurred areas, pop-in captions, auto-captions, project export for After Effects and Premiere, and
 * presets; then regression checks for editor and export bugs that were fixed (undo, montage and Discord sizes,
 * cuts with zoom and webcams, rotation, captions on the command line, audio). Run alone with
 * {@code --edittest DIR}; the full self-test runs them too.
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
        Exporter.Options raw = new Exporter.Options();
        raw.preset = o.preset;
        raw.gpu = o.gpu;
        raw.normalize = false; // this check compares levels inside the mix (exports are loudness-normalised by default)
        Path fxOut = export(srcFile, src, fx, raw, "fx");
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
        regressionTests(srcFile, o);
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
        // The server counts downloads later (one per viewer per day, folded in hourly), so it's 0 or 1 here.
        check(listed && fetched.atMarker != null && fetched.atMarker.rampSpeed > 0 && (downloads == 0 || downloads == 1),
                "community presets: publish, list, download (listed in popular, counted so far: " + downloads + ")");
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
        check(Files.exists(dirOut.resolve("media/Project test.mp4")) && Files.exists(dirOut.resolve("media/1 - Game.wav")) && Files.exists(dirOut.resolve("media/sfx-Boom.wav")),
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

    // ------------------------------------------------------------------ regression checks for fixed bugs

    /**
     * Checks for editor and export bugs that were found and fixed, so they stay fixed. Each runs on its own, so one
     * failure doesn't hide the rest. Checks for features this build doesn't have yet print SKIP.
     */
    private void regressionTests(Path srcFile, Exporter.Options o) {
        guard("editor undo", () -> undoTests(srcFile));
        guard("muted clip with an overlay's sound", () -> mutedOverlayTest(srcFile, o));
        guard("portrait phone video", () -> rotationTest(o));
        guard("kill cam zoom across a cut", () -> killCamTest(o));
        guard("Discord preset on a long clip", this::discordLongTest);
        guard("freeze frame and rewind", () -> freezeRewindTest(srcFile, o));
        guard("loudness normalisation", () -> loudnessTest(srcFile));
        guard("montage transitions", () -> montageTransitionTest(srcFile));
        guard("highlights from event markers", this::highlightMarkerTest);
        // The rest read the exact ffmpeg command an export ran.
        RegressionSupport.CommandLog installed = null;
        try { installed = RegressionSupport.CommandLog.install(dir, ""); } catch (Exception e) { System.out.println("        (no ffmpeg command log: " + e + ")"); }
        RegressionSupport.CommandLog log = installed;
        try {
            guard("webcam split layout", () -> webcamSplitTest(log, srcFile, o));
            if (log == null) {
                RegressionSupport.skip("export command checks (caption command length, mic clean-up, music)", "needs a POSIX shell to record ffmpeg's command line");
                return;
            }
            guard("caption export command", () -> captionCommandTest(log, o));
            guard("mic clean-up", () -> micCleanupTest(log, o));
            guard("background music", () -> musicTest(log, srcFile, o));
        } finally {
            if (log != null) log.close();
        }
    }

    private interface Step { void run() throws Exception; }

    private void guard(String what, Step step) {
        try {
            step.run();
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            check(false, what + ": " + t);
        }
    }

    /** Undo and redo in the real editor (headless): nothing to undo after opening; every step goes back and forth. */
    private void undoTests(Path srcFile) throws Exception {
        Path root = dir.resolve("editor-library");
        Settings s = new Settings();
        s.clipsFolder = root.toString();
        s.storageLimitGb = 0;
        s.controlServer = false;
        s.playSound = false;
        s.showToast = false;
        Path file = Files.createDirectories(root.resolve("Imported")).resolve("undo.mp4");
        Files.copy(srcFile, file, StandardCopyOption.REPLACE_EXISTING);
        App app = new App(s);
        EditorPanel[] ed = new EditorPanel[1];
        try {
            Clip c = app.library.addExisting(file, Clip.Kind.IMPORT, "Undo test", null);
            SwingUtilities.invokeAndWait(() -> {
                if (net.evadeclient.clips.ui.Theme.inter == null) net.evadeclient.clips.ui.Theme.install(); // as the app does at start
                ed[0] = new EditorPanel(app);
                ed[0].open(c);
            });
            Boolean[] canUndo = new Boolean[1];
            SwingUtilities.invokeAndWait(() -> canUndo[0] = undoAvailable(ed[0]));
            // One key press per event, as a user would: S at 2, 4 and 6 s, then Ctrl+Z three times, then Ctrl+Y three times.
            for (double t : new double[]{2, 4, 6}) SwingUtilities.invokeAndWait(() -> { ed[0].seek(t); act(ed[0], "split"); });
            int split = onEdt(() -> parts(ed[0], c));
            for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(() -> act(ed[0], "undo"));
            int undone = onEdt(() -> parts(ed[0], c));
            for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(() -> act(ed[0], "redo"));
            int redone = onEdt(() -> parts(ed[0], c));
            check(Boolean.FALSE.equals(canUndo[0]), "editor: nothing to undo right after opening a clip"
                    + (canUndo[0] == null ? " (no Undo button found)" : canUndo[0] ? " (Undo was available)" : ""));
            check(split == 4 && undone == 1 && redone == 4, "editor: 3 splits, then 3 undos, then 3 redos give 4, 1 and 4 parts (got "
                    + split + ", " + undone + ", " + redone + ")");
        } finally {
            if (ed[0] != null) SwingUtilities.invokeAndWait(() -> ed[0].close());
            try { app.shutdown(); } catch (RuntimeException ignored) {}
        }
    }

    private static <T> T onEdt(java.util.function.Supplier<T> s) throws Exception {
        Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> out[0] = s.get());
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    /** Runs one of the editor's keyboard actions ("split", "undo", "redo"). */
    private static void act(EditorPanel ed, String name) {
        javax.swing.Action a = ed.getActionMap().get(name);
        if (a == null) throw new IllegalStateException("the editor has no \"" + name + "\" key action");
        a.actionPerformed(new java.awt.event.ActionEvent(ed, java.awt.event.ActionEvent.ACTION_PERFORMED, name));
    }

    /** How many parts the editor's edit has now. */
    private static int parts(EditorPanel ed, Clip c) {
        Object e = RegressionSupport.get(ed, "edit");
        if (e instanceof EditSpec es) return es.segments.size();
        return c.edit == null ? -1 : c.edit.segments.size();
    }

    /** Whether the editor's Undo button is enabled (null if there's no such button). */
    private static Boolean undoAvailable(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.AbstractButton b && b.getToolTipText() != null && b.getToolTipText().startsWith("Undo")) return b.isEnabled();
            if (c instanceof java.awt.Container k) {
                Boolean found = undoAvailable(k);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** A clip with its own audio muted keeps an overlay's sound (it used to come out silent). */
    private void mutedOverlayTest(Path srcFile, Exporter.Options o) throws Exception {
        Path beep = dir.resolve("src-overlay-beep.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=2", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:d=2",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", beep.toString()));
        Clip c = clipOf(srcFile, "muted");
        EditSpec e = EditSpec.whole(c.duration);
        e.mute = true;
        EditSpec.Overlay ov = new EditSpec.Overlay();
        ov.file = beep.toString();
        ov.start = 2;
        ov.length = 2;
        ov.audio = true;
        e.overlays.add(ov);
        Path out = export(srcFile, c, e, o, "muted-overlay");
        Ffmpeg.MediaInfo mi = Ffmpeg.probe(out);
        double with = mi.hasAudio() ? loudness(out, 2.5, 1) : -99, without = mi.hasAudio() ? loudness(out, 0.5, 1) : -99;
        check(mi.hasAudio() && mi.audioTracks() == 1 && with > -30 && with > without + 20,
                String.format("a muted clip keeps an overlay's own sound (%d audio stream%s; %.0f dB during the overlay, %.0f dB before)",
                        mi.audioTracks(), mi.audioTracks() == 1 ? "" : "s", with, without));
    }

    /** A portrait phone video (stored landscape with a 90° rotation) exports portrait, not squashed. */
    private void rotationTest(Exporter.Options o) throws Exception {
        Path raw = dir.resolve("src-phone-raw.mp4"), phone = dir.resolve("src-phone.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=3", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:d=3",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", raw.toString()));
        try {
            Ffmpeg.run(List.of("-y", "-display_rotation", "90", "-i", raw.toString(), "-map", "0", "-c", "copy", phone.toString()));
        } catch (java.io.IOException e) {
            RegressionSupport.skip("portrait phone video", "this ffmpeg can't write a display rotation: " + e.getMessage());
            return;
        }
        Clip c = clipOf(phone, "phone");
        Path out = export(phone, c, EditSpec.whole(c.duration), o, "phone");
        Ffmpeg.MediaInfo oi = Ffmpeg.probe(out);
        // The export shows the picture the way ffmpeg plays the phone video: upright, not squeezed.
        double diff = meanDiff(frame(out, 1.5), frame(phone, 1.5));
        check(c.width == 720 && c.height == 1280 && oi.width() == 720 && oi.height() == 1280 && diff < 10,
                String.format("a portrait phone video (rotation 90) exports portrait: read as %dx%d, exported %dx%d (difference to the upright frame %.1f)",
                        c.width, c.height, oi.width(), oi.height(), diff));
    }

    /** "Kill cam zoom" at a kill just before a cut must not stay zoomed in for the part after the cut. */
    private void killCamTest(Exporter.Options o) throws Exception {
        Path src20 = dir.resolve("src-20s.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=20", "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000:d=20",
                "-af", "volume=0.03", "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-g", "15", "-c:a", "aac", src20.toString()));
        Clip c = clipOf(src20, "killcam");
        c.markers.add(7.5);
        c.markerLabels.put(Clip.key(7.5), "Kill Steve");
        EditSpec cut = new EditSpec();
        cut.segments.add(new EditSpec.Segment(0, 8, 1));
        cut.segments.add(new EditSpec.Segment(12, Math.min(20, c.duration), 1));
        EditSpec kill = cut.copy();
        var preset = net.evadeclient.clips.core.EditPresets.builtIns().stream().filter(p -> p.name.equals("Kill cam zoom")).findFirst().orElseThrow();
        int used = net.evadeclient.clips.core.EditPresets.apply(preset, kill, c);
        Path plain = export(src20, c, cut, o, "killcam-plain"), zoomed = export(src20, c, kill, o, "killcam");
        // Output 7.6 s is just after the kill; output 12 s is source 16 s, well into the part after the cut.
        double atKill = meanDiff(frame(zoomed, 7.6), frame(plain, 7.6)), later = meanDiff(frame(zoomed, 12), frame(plain, 12));
        check(used == 1 && atKill > 12 && later < 6, String.format("kill cam zoom at a kill before a cut: zoomed at the kill (diff %.1f), "
                + "back to normal after the cut (diff %.1f)", atKill, later));
    }

    /** The 10 MB Discord preset on an 8-minute clip: the file fits, or the export says it can't fit instead of going over. */
    private void discordLongTest() throws Exception {
        Path big = dir.resolve("src-8min.mp4");
        // Busy video and noisy sound, so the encoders spend their whole budget.
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=10:d=480,noise=alls=12:allf=t", "-f", "lavfi", "-i", "anoisesrc=a=0.2:r=48000:d=480",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "400k", "-c:a", "aac", "-b:a", "128k", big.toString()));
        Clip c = clipOf(big, "long");
        Exporter.Options d = new Exporter.Options();
        d.preset = Exporter.Preset.DISCORD;
        d.gpu = false;
        Path out = dir.resolve("edit-discord-long.mp4");
        Files.deleteIfExists(out);
        boolean ok;
        String result;
        long t0 = System.currentTimeMillis();
        try {
            Exporter.export(big, c, EditSpec.whole(c.duration), d, out, null, null);
            long size = Files.size(out);
            ok = size <= 10_000_000;
            result = String.format("%.2f MB", size / 1e6);
        } catch (java.io.IOException e) {
            String m = String.valueOf(e.getMessage());
            ok = m.contains("MB") && !Files.exists(out);
            result = "refused: " + m + (Files.exists(out) ? " (and left the file behind)" : "");
        }
        check(ok, String.format("Discord (10 MB) on an 8-minute clip fits or says why not (%s, %.0f s)", result, (System.currentTimeMillis() - t0) / 1000.0));
    }

    /** A freeze frame after a part and a rewound part: lengths, times and the exported file agree to the frame. */
    private void freezeRewindTest(Path srcFile, Exporter.Options o) throws Exception {
        if (!RegressionSupport.hasField(EditSpec.Segment.class, "holdAfter") || !RegressionSupport.hasField(EditSpec.Segment.class, "rewind")) {
            RegressionSupport.skip("freeze frame and rewind", "EditSpec.Segment.holdAfter / rewind aren't in this build");
            return;
        }
        Clip c = clipOf(srcFile, "freeze");
        EditSpec e = new EditSpec();
        EditSpec.Segment hold = new EditSpec.Segment(0, 3, 1), back = new EditSpec.Segment(4, 6, 1);
        RegressionSupport.set(hold, "holdAfter", 1.0);
        RegressionSupport.set(back, "rewind", true);
        e.segments.add(hold);
        e.segments.add(back);
        // 3 s plus a 1 s freeze; then 2 s forward, backward and forward again: 4 + 6 = 10 s.
        double t2 = e.toOutput(2), tCut = e.toOutput(3.5), t5 = e.toOutput(5);
        check(same(hold.outputLength(), 4) && same(back.outputLength(), 6) && same(e.outputDuration(), 10) && same(t2, 2) && same(tCut, 4) && same(t5, 5),
                String.format("freeze frame + rewind: parts of %.2f and %.2f s, %.2f s in all; 2 s, 3.5 s (cut) and 5 s land at %.2f, %.2f and %.2f s "
                        + "(want 4, 6, 10; 2, 4, 5)", hold.outputLength(), back.outputLength(), e.outputDuration(), t2, tCut, t5));
        Path out = export(srcFile, c, e, o, "freeze-rewind");
        int frames = frameCount(out);
        double frozen = meanDiff(frame(out, 3.25), frame(out, 3.75)), moving = meanDiff(frame(out, 1.25), frame(out, 1.75));
        check(Math.abs(frames - e.outputDuration() * 30) <= 1 && frozen < 1.5 && moving > 5,
                String.format("freeze frame + rewind export: %d frames for %.2f s at 30 fps; the freeze holds still (change %.2f, %.2f while playing)",
                        frames, e.outputDuration(), frozen, moving));
    }

    private static boolean same(double a, double b) { return Math.abs(a - b) < 1e-6; }

    /** Quiet and loud clips both export at the -14 LUFS that YouTube, TikTok and Discord play at. */
    private void loudnessTest(Path srcFile) throws Exception {
        if (!RegressionSupport.hasField(Exporter.Options.class, "normalize")) {
            RegressionSupport.skip("loudness normalisation", "Exporter.Options.normalize isn't in this build");
            return;
        }
        Clip c = clipOf(srcFile, "loudness");
        Exporter.Options n = new Exporter.Options();
        n.preset = Exporter.Preset.SOURCE;
        n.gpu = false;
        RegressionSupport.set(n, "normalize", true);
        double[] volumes = {0.2, 1.0}, lufs = new double[2];
        for (int i = 0; i < 2; i++) {
            EditSpec e = EditSpec.whole(c.duration);
            e.volume = volumes[i];
            lufs[i] = RegressionSupport.integratedLoudness(export(srcFile, c, e, n, "loudness-" + i));
        }
        check(Math.abs(lufs[0] + 14) <= 1.5 && Math.abs(lufs[1] + 14) <= 1.5,
                String.format("loudness normalisation: volume 0.2 and 1.0 both export at about -14 LUFS (%.1f and %.1f LUFS)", lufs[0], lufs[1]));
    }

    /** A crossfade montage overlaps each pair of clips by the transition: sum - (n-1)·d. */
    private void montageTransitionTest(Path srcFile) throws Exception {
        if (!RegressionSupport.hasField(Exporter.Options.class, "transition") || !RegressionSupport.hasField(Exporter.Options.class, "transitionSec")) {
            RegressionSupport.skip("montage transitions", "Exporter.Options.transition / transitionSec aren't in this build");
            return;
        }
        Clip a = clipOf(srcFile, "m1"), b = clipOf(srcFile, "m2"), c = clipOf(srcFile, "m3");
        c.edit = EditSpec.whole(c.duration);
        c.edit.segments.get(0).start = 1;
        c.edit.segments.get(0).end = 5;
        Exporter.Options m = new Exporter.Options();
        m.preset = Exporter.Preset.YOUTUBE;
        m.gpu = false;
        RegressionSupport.set(m, "transition", "fade");
        RegressionSupport.set(m, "transitionSec", 0.3);
        Path out = dir.resolve("edit-montage-fade.mp4");
        Files.deleteIfExists(out);
        Exporter.montage(List.of(a, b, c), dir, m, out, null, null);
        double sum = a.duration + b.duration + c.edit.outputDuration(), want = sum - 2 * 0.3, got = Ffmpeg.probe(out).duration();
        check(Math.abs(got - want) <= 0.1, String.format("montage with 0.3 s crossfades: %.2f s from %.2f s of clips (want %.2f)", got, sum, want));
    }

    /** A quiet kill marked by a game event (at 20 s) makes it into the highlight reel next to the loud moments. */
    private void highlightMarkerTest() throws Exception {
        java.lang.reflect.Method detect = RegressionSupport.method(Highlights.class, "detect", Clip.class, Path.class, int.class, Ffmpeg.Job.class);
        if (detect == null) {
            RegressionSupport.skip("highlights from event markers", "Highlights.detect(Clip, Path, int, Job) isn't in this build");
            return;
        }
        Path noisy = dir.resolve("src-noisy.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "color=c=gray:s=320x180:r=15:d=60", "-f", "lavfi", "-i", "anoisesrc=a=0.02:d=60:r=48000",
                "-af", "volume='if(between(t,10,10.8)+between(t,30,31)+between(t,47,47.6),25,1)':eval=frame",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", noisy.toString()));
        Clip c = clipOf(noisy, "highlights");
        c.markers.add(20.0);
        c.markerLabels.put(Clip.key(20), "Kill Steve");
        Object found = RegressionSupport.invoke(detect, null, c, noisy, 0, null);
        List<Highlights.Moment> moments = new ArrayList<>();
        if (found instanceof List<?> l) for (Object x : l) if (x instanceof Highlights.Moment hm) moments.add(hm);
        EditSpec reel = Highlights.cut(moments, c.duration, 6, 2);
        check(reel.segmentAt(20) >= 0 && moments.stream().anyMatch(x -> Math.abs(x.time() - 20) < 0.5),
                "highlights keep the kill marked at 20 s though it's quiet (moments at " + moments.stream().map(x -> String.format("%.1f", x.time())).toList() + ")");
    }

    /** With a separate webcam file, the facecam follows the cuts instead of playing on from its start. */
    private void webcamSplitTest(RegressionSupport.CommandLog log, Path srcFile, Exporter.Options o) throws Exception {
        Path cam = dir.resolve("src-webcam.mp4");
        // The webcam recording is red for its first 4 s and blue after, so the picture tells which moment of it is shown.
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "color=c=red:s=320x240:r=30:d=4", "-f", "lavfi", "-i", "color=c=blue:s=320x240:r=30:d=4",
                "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", cam.toString()));
        Clip c = clipOf(srcFile, "webcam");
        EditSpec e = new EditSpec();
        e.segments.add(new EditSpec.Segment(0, 2, 1));
        e.segments.add(new EditSpec.Segment(5, c.duration, 1)); // 2..5 s cut
        e.aspect = "9:16";
        e.fitMode = "split";
        e.camFile = cam.toString();
        if (log != null) log.clear();
        Path out = export(srcFile, c, e, o, "webcam-split");
        // Output 2.5 s is source 5.5 s: the webcam is blue by then (it showed red when it played on from its start).
        BufferedImage f = frame(out, 2.5);
        int band = (int) (f.getHeight() * e.camShare);
        double blue = colourShare(f, f.getWidth() / 4, band / 5, f.getWidth() * 3 / 4, band * 4 / 5, 0x0000ff);
        check(blue > 0.8, String.format("split layout with a webcam file stays in sync after a cut (webcam area %.0f%% blue at 2.5 s, want the 5.5 s moment)", blue * 100));
        if (log != null) {
            List<String> cmd = log.commandWriting(out);
            String g = RegressionSupport.CommandLog.graph(cmd);
            int trims = count(g, "(?<![a-z])trim=start="), pieces = e.pieces().size();
            boolean looped = cmd != null && cmd.contains("-stream_loop");
            check(cmd != null && trims >= 2 * pieces && !looped, "split layout trims the webcam like the gameplay (" + trims + " video trims for "
                    + pieces + " parts" + (looped ? ", webcam looped from its start" : "") + ")");
        }
    }

    /** 120 timed caption words (auto-captions of about a minute of talking) must not blow the command-line limit. */
    private void captionCommandTest(RegressionSupport.CommandLog log, Exporter.Options o) throws Exception {
        Path small = dir.resolve("src-small.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=30:d=10", "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000:d=10",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", small.toString()));
        Clip c = clipOf(small, "captions");
        EditSpec e = EditSpec.whole(c.duration);
        for (int i = 0; i < 120; i++) e.captions.add(new EditSpec.Caption("word" + i, i * 0.08, (i + 1) * 0.08, "center", "pop"));
        log.clear();
        long t0 = System.currentTimeMillis();
        Path out = export(small, c, e, o, "many-captions");
        long ms = System.currentTimeMillis() - t0;
        List<String> cmd = log.commandWriting(out);
        int len = cmd == null ? -1 : RegressionSupport.CommandLog.windowsLength(cmd);
        boolean script = RegressionSupport.CommandLog.usesScript(cmd);
        double got = Ffmpeg.probe(out).duration();
        // Windows refuses command lines over 32,767 characters.
        check(cmd != null && (len < 30_000 || script) && Math.abs(got - c.duration) < 0.3,
                String.format("120 caption words: the export command fits the Windows limit (%,d characters%s; %.2f s exported in %.1f s)",
                        len, script ? ", graph in a file" : "", got, ms / 1000.0));
    }

    /** "Clean up mic" adds the noise filter to the mic track, and only when it's on. */
    private void micCleanupTest(RegressionSupport.CommandLog log, Exporter.Options o) throws Exception {
        if (!RegressionSupport.hasField(EditSpec.class, "micCleanup")) {
            RegressionSupport.skip("mic clean-up", "EditSpec.micCleanup isn't in this build");
            return;
        }
        Path two = dir.resolve("src-2track.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=4", "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000:d=4",
                "-f", "lavfi", "-i", "anoisesrc=a=0.05:r=48000:d=4", "-map", "0", "-map", "1", "-map", "2", "-shortest",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-metadata:s:a:0", "title=Game", "-metadata:s:a:1", "title=Mic", two.toString()));
        Clip c = clipOf(two, "mic");
        EditSpec clean = EditSpec.whole(c.duration);
        RegressionSupport.set(clean, "micCleanup", true);
        log.clear();
        Path out = export(two, c, clean, o, "mic-cleanup");
        String g = RegressionSupport.CommandLog.graph(log.commandWriting(out));
        Path plainOut = export(two, c, EditSpec.whole(c.duration), o, "mic-plain");
        String pg = RegressionSupport.CommandLog.graph(log.commandWriting(plainOut));
        check(c.audioTracks == 2 && g.contains("afftdn") && g.contains("highpass") && !pg.contains("afftdn") && Ffmpeg.probe(out).audioTracks() == 1,
                "mic clean-up filters the mic track (noise reduction " + (g.contains("afftdn") ? "on" : "missing") + " when ticked, "
                        + (pg.contains("afftdn") ? "on" : "off") + " when not)");
    }

    /** Background music: mixed in and ducked under the clip's sound; on a muted clip it's still there, with nothing to duck under. */
    private void musicTest(RegressionSupport.CommandLog log, Path srcFile, Exporter.Options o) throws Exception {
        Class<?> musicType = RegressionSupport.nested(EditSpec.class, "Music");
        if (musicType == null || !RegressionSupport.hasField(EditSpec.class, "music")) {
            RegressionSupport.skip("background music", "EditSpec.Music / EditSpec.music aren't in this build");
            return;
        }
        Path song = dir.resolve("src-music.wav");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "sine=frequency=523:sample_rate=44100:d=20", song.toString()));
        Clip c = clipOf(srcFile, "music");
        String[] graphs = new String[2];
        int[] inputs = new int[2];
        boolean[] audio = new boolean[2];
        double[] lengths = new double[2];
        for (int k = 0; k < 2; k++) {
            EditSpec e = EditSpec.whole(c.duration);
            e.mute = k == 1;
            var ctor = musicType.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object m = ctor.newInstance();
            RegressionSupport.set(m, "file", song.toString());
            RegressionSupport.set(m, "volume", 0.5);
            RegressionSupport.set(m, "duck", true);
            if (!RegressionSupport.set(e, "music", m)) throw new IllegalStateException("couldn't set EditSpec.music");
            log.clear();
            Path out = export(srcFile, c, e, o, k == 0 ? "music" : "music-muted");
            List<String> cmd = log.commandWriting(out);
            graphs[k] = RegressionSupport.CommandLog.graph(cmd);
            inputs[k] = RegressionSupport.CommandLog.inputIndex(cmd, song);
            Ffmpeg.MediaInfo mi = Ffmpeg.probe(out);
            audio[k] = mi.hasAudio();
            lengths[k] = mi.duration();
        }
        String mixed = graphs[0], muted = graphs[1];
        int mixInputs = lastInt(mixed, "amix=inputs=(\\d+)");
        boolean usesMusic = inputs[0] >= 0 && mixed.contains("[" + inputs[0] + ":a]");
        check(usesMusic && mixed.contains("sidechaincompress") && mixInputs >= 2 && audio[0] && Math.abs(lengths[0] - c.duration) < 0.4,
                "background music is mixed in and ducks under the clip's sound (music " + (usesMusic ? "in" : "missing") + ", ducking "
                        + (mixed.contains("sidechaincompress") ? "on" : "off") + ", final mix of " + mixInputs + ")");
        boolean mutedMusic = inputs[1] >= 0 && muted.contains("[" + inputs[1] + ":a]");
        check(mutedMusic && !muted.contains("sidechaincompress") && audio[1] && Math.abs(lengths[1] - c.duration) < 0.4,
                "a muted clip still gets its music, with nothing to duck under (music " + (mutedMusic ? "in" : "missing") + ", ducking "
                        + (muted.contains("sidechaincompress") ? "on" : "off") + ", audio " + (audio[1] ? "present" : "missing") + ")");
    }

    /** A clip for a test file, as the library would see it (its file name is relative to the test folder). */
    private static Clip clipOf(Path file, String id) throws java.io.IOException {
        Ffmpeg.MediaInfo i = Ffmpeg.probe(file);
        Clip c = new Clip();
        c.id = id;
        c.file = file.getFileName().toString();
        c.title = id;
        c.duration = i.duration();
        c.width = i.width();
        c.height = i.height();
        c.fps = i.fps();
        c.hasAudio = i.hasAudio();
        c.audioTracks = i.audioTracks();
        c.trackNames = new ArrayList<>(i.trackNames());
        return c;
    }

    private static int count(String s, String regex) {
        int n = 0;
        var m = java.util.regex.Pattern.compile(regex).matcher(s);
        while (m.find()) n++;
        return n;
    }

    /** The number in the last match of a one-group pattern, or -1. */
    private static int lastInt(String s, String regex) {
        int v = -1;
        var m = java.util.regex.Pattern.compile(regex).matcher(s);
        while (m.find()) v = Integer.parseInt(m.group(1));
        return v;
    }

    /** Share of pixels close to {@code rgb} in a box. */
    private static double colourShare(BufferedImage img, int x0, int y0, int x1, int y1, int rgb) {
        int hit = 0, n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y += 2) for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x += 2) {
            int p = img.getRGB(x, y);
            n++;
            if (Math.abs((p >> 16 & 255) - (rgb >> 16 & 255)) < 50 && Math.abs((p >> 8 & 255) - (rgb >> 8 & 255)) < 50 && Math.abs((p & 255) - (rgb & 255)) < 50) hit++;
        }
        return hit / (double) Math.max(1, n);
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
