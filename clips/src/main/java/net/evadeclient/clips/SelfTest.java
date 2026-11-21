package net.evadeclient.clips;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Library;
import net.evadeclient.clips.core.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end check without a screen: captures ffmpeg's test pattern + tone through the real capture engine,
 * saves instant replays, records a session and runs every export preset, then verifies each file with ffprobe.
 */
final class SelfTest {
    private final List<String> failures = new ArrayList<>();
    private int passed;

    static int run(Path dir) throws Exception {
        return new SelfTest().go(dir);
    }

    private void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++; else failures.add(what);
    }

    private int go(Path dir) throws Exception {
        if (Files.exists(dir)) try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(dir);
        Settings.overrideAppDir(dir.resolve("appdata"));
        Settings s = new Settings();
        s.clipsFolder = dir.resolve("clips").toString();
        s.source = "test";
        s.fps = 30;
        s.resolution = 720;
        s.replaySeconds = 10;
        s.recordSystemAudio = true;
        s.recordMic = true;
        Ffmpeg.configure(s.ffmpegPath);
        check(Ffmpeg.available(), "ffmpeg + ffprobe found (" + Ffmpeg.ffmpeg() + ")");
        if (!Ffmpeg.available()) return finish();
        System.out.println("  encoders: " + Ffmpeg.workingEncoders());

        Library lib = new Library(s);
        lib.load();
        CaptureEngine engine = new CaptureEngine(s, lib);
        engine.start();
        long t0 = System.currentTimeMillis();
        while (!engine.isLive() || System.currentTimeMillis() - t0 < 7000) Thread.sleep(200);
        engine.addMarker();
        Thread.sleep(2000);

        Clip replay = engine.saveReplay(5, "Test replay", "Minecraft");
        check(replay != null && Math.abs(replay.duration - 5) < 1.6, "instant replay saved, ~5 s (got " + (replay == null ? "-" : String.format("%.2f", replay.duration)) + ")");
        check(replay != null && replay.hasAudio && replay.height == 720, "replay has audio and is 720p");
        check(replay != null && replay.audioTracks == 2, "game and mic are separate audio tracks (" + (replay == null ? 0 : replay.audioTracks) + ")");
        check(replay != null && replay.markers.size() == 1 && Math.abs(replay.markers.get(0) - (replay.duration - 2.5)) < 1.2,
                "marker pressed 2 s before saving lands ~2 s from the end (" + (replay == null ? "-" : replay.markers) + ")");

        Clip longer = engine.saveReplay(60, null, null);
        check(longer.duration >= 9 && longer.duration <= 12.5, "replay is capped at the buffer length (" + String.format("%.2f", longer.duration) + " s)");

        long tp = System.currentTimeMillis();
        Clip post = engine.saveReplay(4, 2, "Post-roll", null);
        check(Math.abs(post.duration - 6) < 1.6 && System.currentTimeMillis() - tp >= 2000,
                "post-roll: 4 s before + 2 s after the key = ~6 s (got " + String.format("%.2f", post.duration) + ")");

        // Local API (what Evade Client calls).
        net.evadeclient.clips.core.ControlServer api = new net.evadeclient.clips.core.ControlServer();
        int port = 47000 + (int) (Math.random() * 900);
        api.start(port, new net.evadeclient.clips.core.ControlServer.Handler() {
            public java.util.Map<String, Object> status() { return java.util.Map.of("state", "live"); }
            public java.util.Map<String, Object> clip(int sec, int after, String title, String game, String tag) throws Exception {
                Clip c = engine.saveReplay(sec, after, title, game);
                if (tag != null) c.tags.add(tag);
                return java.util.Map.of("ok", true, "id", c.id);
            }
            public java.util.Map<String, Object> record(String a, String g) { return java.util.Map.of(); }
            public void marker() {}
        });
        var http = java.net.http.HttpClient.newHttpClient();
        var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/clip?seconds=3&title=Kill%20Steve&tag=kill"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(res.statusCode() == 200 && res.body().contains("\"ok\":true"), "API /clip saves a titled clip (" + res.statusCode() + ")");
        var blocked = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/clip"))
                .header("Origin", "https://evil.example").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(blocked.statusCode() == 403, "API refuses requests from web pages");
        api.stop();

        engine.startRecording("Minecraft");
        Thread.sleep(1500);
        engine.addMarker();
        Thread.sleep(2500);
        Clip rec = engine.stopRecording();
        check(rec != null && rec.kind == Clip.Kind.RECORDING && Math.abs(rec.duration - 4) < 1.6,
                "recording saved as MP4, ~4 s (got " + (rec == null ? "-" : String.format("%.2f", rec.duration)) + ")");
        check(rec != null && rec.markers.size() == 1, "recording kept its marker");
        Clip afterRec = engine.saveReplay(3, null, null);
        check(afterRec != null, "replay buffer kept running during and after the recording");
        engine.shutdown();

        // Thumbnails are made in the background.
        long tw = System.currentTimeMillis();
        while (System.currentTimeMillis() - tw < 20000 && !(Files.exists(lib.thumb(replay)) && Files.exists(lib.strip(replay)))) Thread.sleep(200);
        check(Files.exists(lib.thumb(replay)) && Files.exists(lib.strip(replay)), "thumbnail + hover strip generated");

        // Library persistence.
        Library again = new Library(s);
        again.load();
        check(again.all().size() == lib.all().size() && again.get(replay.id) != null, "library index reloads (" + again.all().size() + " clips)");

        // Exports.
        Clip src = longer;
        EditSpec e = EditSpec.whole(src.duration);
        e.splitAt(3);
        e.splitAt(5);
        e.segments.remove(1);           // cut 3..5
        e.segments.get(1).speed = 0.5;  // slow-mo the rest
        e.titleText = "Clutch 1v3";
        e.fadeIn = 0.3;
        e.fadeOut = 0.5;
        double expected = e.outputDuration();
        for (Exporter.Preset p : Exporter.Preset.values()) {
            EditSpec spec = p == Exporter.Preset.QUICK_TRIM ? simpleTrim(src) : e;
            Exporter.Options o = new Exporter.Options();
            o.preset = p;
            Path out = Exporter.outputFor(lib, src, p);
            long t = System.currentTimeMillis();
            try {
                Exporter.export(src.path(lib.root()), src, spec, o, out, null, null);
                Ffmpeg.MediaInfo info = Ffmpeg.probe(out);
                double want = spec.outputDuration();
                String sz = String.format("%dx%d %.2fs %.1f MB in %.1fs", info.width(), info.height(), info.duration(),
                        Files.size(out) / 1e6, (System.currentTimeMillis() - t) / 1000.0);
                boolean ok = Math.abs(info.duration() - want) < (p == Exporter.Preset.QUICK_TRIM ? 1.2 : 0.4);
                if (p == Exporter.Preset.SHORTS) ok &= info.width() == 1080 && info.height() == 1920;
                if (p == Exporter.Preset.DISCORD) ok &= Files.size(out) <= 10_000_000;
                if (p == Exporter.Preset.GIF) ok &= info.width() == 480;
                if (p != Exporter.Preset.GIF) ok &= info.hasAudio();
                check(ok, "export " + p.label + ": " + sz + " (want " + String.format("%.2f", want) + " s)");
                if (p == Exporter.Preset.SOURCE) lib.addNew(out, Clip.Kind.EXPORT, null, null, null, src.id);
            } catch (Exception ex) {
                check(false, "export " + p.label + ": " + ex.getMessage());
            }
        }
        // Vertical crop (not blurred) keeps the chosen part of the frame.
        EditSpec v = EditSpec.whole(src.duration);
        v.aspect = "9:16";
        v.fitMode = "crop";
        v.cropX = 0;
        Exporter.Options o = new Exporter.Options();
        o.preset = Exporter.Preset.YOUTUBE;
        Path vout = Library.unique(lib.folder(Clip.Kind.EXPORT), "vertical-crop.mp4");
        Exporter.export(src.path(lib.root()), src, v, o, vout, null, null);
        Ffmpeg.MediaInfo vi = Ffmpeg.probe(vout);
        check(vi.width() == 404 && vi.height() == 720, "9:16 crop of a 720p clip is 404x720 (got " + vi.width() + "x" + vi.height() + ")");
        System.out.println("  expected edited duration " + String.format("%.2f", expected));

        // Mic muted in the edit: the export keeps one mixed track.
        EditSpec quiet = EditSpec.whole(src.duration);
        quiet.micVolume = 0;
        Exporter.Options so = new Exporter.Options();
        so.preset = Exporter.Preset.SOURCE;
        Path qout = Library.unique(lib.folder(Clip.Kind.EXPORT), "mic-muted.mp4");
        Exporter.export(src.path(lib.root()), src, quiet, so, qout, null, null);
        Ffmpeg.MediaInfo qi = Ffmpeg.probe(qout);
        check(qi.audioTracks() == 1 && Math.abs(qi.duration() - src.duration) < 0.4, "export mixes game + mic (mic muted) into one track");

        // Highlights: quiet noise with three loud bursts.
        Path noisy = dir.resolve("noisy.mp4");
        Ffmpeg.run(List.of("-f", "lavfi", "-i", "color=c=gray:s=320x180:r=15:d=60", "-f", "lavfi", "-i",
                "anoisesrc=a=0.02:d=60:r=48000", "-af", "volume='if(between(t,10,10.8)+between(t,30,31)+between(t,47,47.6),25,1)':eval=frame",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", noisy.toString()));
        var moments = net.evadeclient.clips.core.Highlights.detect(noisy, 60, 0, null);
        List<Double> times = moments.stream().map(net.evadeclient.clips.core.Highlights.Moment::time).toList();
        boolean hl = moments.size() == 3 && Math.abs(times.get(0) - 10.4) < 1.2 && Math.abs(times.get(1) - 30.5) < 1.2 && Math.abs(times.get(2) - 47.3) < 1.2;
        check(hl, "highlight detection finds the 3 loud moments at ~10, ~30, ~47 s (got " + times + ")");
        EditSpec reel = net.evadeclient.clips.core.Highlights.cut(moments, 60, 5, 3);
        check(reel.segments.size() == 3 && Math.abs(reel.outputDuration() - 24) < 1, "highlight reel keeps 8 s around each (" + String.format("%.1f", reel.outputDuration()) + " s)");

        // Montage of a 2-track 720p clip and a different-sized vertical clip.
        Clip vert = lib.addNew(vout, Clip.Kind.EXPORT, "vertical", null, null, null);
        List<Clip> mclips = List.of(replay, vert, rec);
        double want = 0;
        for (Clip c : mclips) want += (c.edit != null ? c.edit : EditSpec.whole(c.duration)).outputDuration();
        Path mout = Library.unique(lib.folder(Clip.Kind.EXPORT), "montage.mp4");
        Exporter.Options mo = new Exporter.Options();
        mo.preset = Exporter.Preset.YOUTUBE;
        Exporter.montage(mclips, lib.root(), mo, mout, null, null);
        Ffmpeg.MediaInfo mi = Ffmpeg.probe(mout);
        check(mi.width() == 1280 && mi.height() == 720 && Math.abs(mi.duration() - want) < 0.6 && mi.hasAudio(),
                "montage of 3 clips: " + mi.width() + "x" + mi.height() + " " + String.format("%.2f", mi.duration()) + " s (want " + String.format("%.2f", want) + ")");
        lib.shutdown();
        return finish();
    }

    private static EditSpec simpleTrim(Clip c) {
        EditSpec t = EditSpec.whole(c.duration);
        t.segments.get(0).start = 2;
        t.segments.get(0).end = 6;
        return t;
    }

    private int finish() {
        System.out.println();
        System.out.println(failures.isEmpty() ? "ALL " + passed + " CHECKS PASSED" : failures.size() + " FAILED, " + passed + " passed");
        return failures.isEmpty() ? 0 : 1;
    }
}
