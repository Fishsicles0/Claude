package net.evadeclient.clips;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Exporter;
import net.evadeclient.clips.core.Ffmpeg;
import net.evadeclient.clips.core.Hotkeys;
import net.evadeclient.clips.core.Library;
import net.evadeclient.clips.core.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end check without a screen: captures ffmpeg's test pattern + tone through the real capture engine,
 * saves instant replays, records a session and runs every export preset, then verifies each file with ffprobe.
 * Regression checks for fixed bugs (capture recovery, hotkeys, Minecraft detection, the library index and storage
 * limit) and the Evade-Client-only rules run alongside; checks for features a build doesn't have yet print SKIP.
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
        s.recordVoice = true;
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
        check(replay != null && replay.audioTracks == 3 && replay.trackNames.equals(List.of("Game", "Mic", "Voice chat")),
                "game, mic and voice chat are separate, named audio tracks (" + (replay == null ? "-" : replay.trackNames) + ")");
        check(replay != null && replay.markers.size() == 1 && Math.abs(replay.markers.get(0) - (replay.duration - 2.5)) < 1.2,
                "marker pressed 2 s before saving lands ~2 s from the end (" + (replay == null ? "-" : replay.markers) + ")");

        engine.addMarker("Kill Steve");
        Clip named = engine.saveReplay(3, null, null);
        check(named.markers.stream().anyMatch(m -> "Kill Steve".equals(named.label(m))), "event marker keeps its name in the saved replay (" + named.markerLabels + ")");
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
            public java.util.Map<String, Object> importFile(Path f, String title) {
                var added = lib.importFiles(List.of(f), false);
                if (!added.isEmpty() && title != null) { added.get(0).title = title; lib.update(added.get(0)); }
                return java.util.Map.of("ok", !added.isEmpty(), "id", added.isEmpty() ? "" : added.get(0).id);
            }
        });
        var http = java.net.http.HttpClient.newHttpClient();
        java.util.function.Function<String, java.net.http.HttpResponse<String>> callApi = path -> {
            try {
                return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        var res = callApi.apply("/clip?seconds=3&title=Kill%20Steve&tag=kill&client=evade");
        check(res.statusCode() == 200 && res.body().contains("\"ok\":true"), "API /clip from Evade Client saves a titled clip (" + res.statusCode() + ")");
        // Evade Clips only works with Evade Client: other programs can't save, record, mark or import.
        List<String> refused = new ArrayList<>();
        for (String path : List.of("/clip?seconds=3", "/clip?seconds=3&client=other", "/record/start", "/record/toggle", "/marker?label=x", "/import?path=x"))
            if (callApi.apply(path).statusCode() != 403 || !callApi.apply(path).body().contains("Evade Client")) refused.add(path);
        var status = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/status")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        check(refused.isEmpty() && status.statusCode() == 200,
                "API refuses /clip, /record, /marker and /import without client=evade (403), /status stays open"
                        + (refused.isEmpty() ? "" : " (not refused: " + refused + ")"));
        var blocked = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/clip"))
                .header("Origin", "https://evil.example").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(blocked.statusCode() == 403, "API refuses requests from web pages");
        // a replay render from Evade Client goes into the library; anything that isn't a video is refused
        Path render = dir.resolve("replay-render.mp4");
        Ffmpeg.run(List.of("-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=2", "-c:v", "libx264", "-preset", "ultrafast", render.toString()));
        java.util.function.Function<String, java.net.http.HttpResponse<String>> imp = path -> {
            try {
                return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/import?path="
                        + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8) + "&title=Replay%20render&client=evade"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        var imported = imp.apply(render.toString());
        var bad = imp.apply(dir.resolve("settings.json").toString());
        check(imported.statusCode() == 200 && imported.body().contains("\"ok\":true") && lib.all().stream().anyMatch(c -> "Replay render".equals(c.title)) && bad.statusCode() == 400,
                "API /import adds a replay render to the library and refuses non-videos (" + imported.statusCode() + "/" + bad.statusCode() + ")");
        api.stop();

        eventTests();
        voiceTests(dir);

        engine.startRecording("Minecraft");
        Thread.sleep(1500);
        engine.addMarker("ACE");
        Thread.sleep(2500);
        Clip rec = engine.stopRecording();
        // Recordings start and end on 1 s segment boundaries (crash-safe, no re-encode): 4 s of recording is 4-6 s.
        check(rec != null && rec.kind == Clip.Kind.RECORDING && rec.duration >= 3.8 && rec.duration <= 6.3,
                "recording saved as MP4, 4 s pressed → 4-6 s on 1 s boundaries (got " + (rec == null ? "-" : String.format("%.2f", rec.duration)) + ")");
        check(rec != null && rec.markers.size() == 1 && "ACE".equals(rec.label(rec.markers.get(0))),
                "recording kept its named marker (" + (rec == null ? "-" : rec.markerLabels) + ")");
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
        check(qi.audioTracks() == 1 && Math.abs(qi.duration() - src.duration) < 0.4, "export mixes game + mic + voice chat (mic muted) into one track");

        // Captions + effect: compare a frame with and without them.
        EditSpec plain = EditSpec.whole(src.duration);
        plain.segments.get(0).end = 6;
        EditSpec styled = plain.copy();
        styled.effect = "bw";
        styled.voiceVolume = 0;
        styled.captions.add(new EditSpec.Caption("when the clutch hits", 1, 3, "top", "meme"));
        styled.captions.add(new EditSpec.Caption("subtitle text", 4, 5.5, "bottom", "subtitle"));
        Path pa = Library.unique(lib.folder(Clip.Kind.EXPORT), "plain.mp4"), pb = Library.unique(lib.folder(Clip.Kind.EXPORT), "styled.mp4");
        Exporter.export(src.path(lib.root()), src, plain, so, pa, null, null);
        Exporter.export(src.path(lib.root()), src, styled, so, pb, null, null);
        java.awt.image.BufferedImage fa = frame(pa, 2.0, dir), fb = frame(pb, 2.0, dir), fb2 = frame(pb, 3.5, dir);
        int h = fa.getHeight(), w = fa.getWidth();
        double capNow = whiteShare(fb, 0, (int) (h * 0.2)), capOff = whiteShare(fb2, 0, (int) (h * 0.2)), capPlain = whiteShare(fa, 0, (int) (h * 0.2));
        check(capNow > capPlain + 0.03 && capOff < capNow - 0.03,
                String.format("meme caption is on screen from 1-3 s only (white in top band: %.0f%% at 2 s vs %.0f%% at 3.5 s)", capNow * 100, capOff * 100));
        int colored = 0, total = 0;
        for (int y = (int) (h * 0.3); y < h * 0.7; y += 4) for (int x = 0; x < w; x += 4) {
            int rgb = fb.getRGB(x, y), r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
            total++;
            if (Math.max(Math.abs(r - g), Math.max(Math.abs(g - b), Math.abs(r - b))) > 12) colored++;
        }
        check(colored < total * 0.02, "black & white effect removes color (" + colored + "/" + total + " colored samples)");

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
        // Exactly one audio stream, the default one, as long as the video: an unused mixer output must not end up in
        // the file as a stray track that players pick instead of the real mix.
        var mstreams = RegressionSupport.audioStreams(mout);
        boolean oneMix = mi.audioTracks() == 1 && mstreams.size() == 1 && mstreams.get(0).isDefault()
                && Math.abs(mstreams.get(0).duration() - want) < 0.6;
        check(mi.width() == 1280 && mi.height() == 720 && Math.abs(mi.duration() - want) < 0.6 && oneMix,
                "montage of 3 clips: " + mi.width() + "x" + mi.height() + " " + String.format("%.2f", mi.duration()) + " s, "
                        + mstreams.size() + " audio stream" + (mstreams.size() == 1 ? "" : "s") + " (want 1, default, "
                        + String.format("%.2f", want) + " s; got " + mstreams + ")");
        upgradeTests(lib, dir, src);
        shareTests(lib, dir);
        regressionTests(dir);
        // the editor's creative tools (ramps, layouts, zoom, effects, captions, presets, project export)
        System.setProperty("clips.editTestOwnAppDir", "true");
        int editFailures = EditTests.run(dir.resolve("edit"));
        check(editFailures == 0, "editor tools: " + (editFailures == 0 ? "all edit checks passed" : editFailures + " edit checks failed"));
        lib.shutdown();
        return finish();
    }

    /** Minecraft events → clips, and Minecraft-only game detection. */
    private void eventTests() throws Exception {
        List<String> saved = new java.util.concurrent.CopyOnWriteArrayList<>();
        var clipper = new net.evadeclient.clips.events.AutoClipper((sec, title, game, tags) -> saved.add(game + "|" + title + "|" + tags + "|" + sec));
        List<String> marks = new java.util.concurrent.CopyOnWriteArrayList<>();
        clipper.onMarker = marks::add;
        // Events close together become one clip, titled after the most important one.
        clipper.after = 1;
        clipper.event("Minecraft", "Kill Steve", "kill", 1);
        clipper.event("Minecraft", "Double kill", "multikill", 2);
        clipper.event("Minecraft", "Totem pop", "totem", 0);
        Thread.sleep(1600);
        check(saved.size() == 1 && saved.get(0).startsWith("Minecraft|Double kill|kill,multikill,totem|"),
                "Minecraft events close together become one clip named after the best one (" + saved + ")");
        check(marks.equals(List.of("Kill Steve", "Double kill", "Totem pop")), "every event drops a named marker (" + marks + ")");
        saved.clear();
        marks.clear();
        clipper.mark("Death");
        Thread.sleep(300);
        check(saved.isEmpty() && marks.equals(List.of("Death")), "a death is a timeline marker, not a clip (" + marks + ")");
        clipper.shutdown();

        // Game detection: Minecraft clients only; every other program (other games included) is ignored.
        String[][] cases = {
                {"C:\\Program Files\\Java\\bin\\javaw.exe", "-cp x net.fabricmc.loader.impl.launch.knot.KnotClient --gameDir C:\\evade", "Minecraft"},
                {"/usr/lib/jvm/java-21/bin/java", "-cp x net.minecraft.client.main.Main --assetsDir a", "Minecraft"},
                {"C:\\Users\\me\\AppData\\Roaming\\.minecraft\\runtime\\java-runtime-delta\\windows-x64\\java-runtime-delta\\bin\\javaw.exe", "", "Minecraft"},
                {"C:\\Program Files (x86)\\Steam\\steamapps\\common\\Some Shooter\\bin\\win64\\shooter.exe", "", null},
                {"D:\\SteamLibrary\\steamapps\\common\\Indie Game\\Ship\\Indie.exe", "", null},
                {"C:\\Program Files\\Epic Games\\Other Game\\Binaries\\Win64\\OtherGame-Win64-Shipping.exe", "", null},
                {"C:\\Program Files\\WindowsApps\\Microsoft.MinecraftUWP_1.21.0.0_x64__8wekyb3d8bbwe\\Minecraft.Windows.exe", "", null},
                {"C:\\Program Files (x86)\\Steam\\steam.exe", "", null},
                {"C:\\Program Files\\Java\\bin\\java.exe", "-jar idea.jar", null},
                {"/usr/lib/jvm/java-21-openjdk/bin/java", "-Xmx4G -Xms1G -jar minecraft_server.1.21.1.jar nogui", null},
                {"/usr/bin/firefox", "", null}};
        List<String> wrong = new ArrayList<>();
        for (String[] c : cases) {
            String got = net.evadeclient.clips.core.GameDetector.identify(c[0], c[1]);
            if (!java.util.Objects.equals(got, c[2])) wrong.add(Path.of(c[0].replace('\\', '/')).getFileName() + " → " + got);
        }
        check(wrong.isEmpty(), "game detection finds Minecraft clients only; other games, servers and apps are ignored ("
                + (cases.length - wrong.size()) + "/" + cases.length + (wrong.isEmpty() ? ")" : "; wrong: " + wrong + ")"));

        // Live: a Minecraft client process is found among the running processes; a game from a Steam library isn't.
        Path fake = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "evadeclips-detect", "steamapps", "common", "Test Arena"));
        Path bin = fake.resolve("TestArena");
        Files.copy(Path.of("/bin/sleep"), bin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        bin.toFile().setExecutable(true);
        Process other = new ProcessBuilder(bin.toString(), "20").start();
        Thread.sleep(500);
        var none = net.evadeclient.clips.core.GameDetector.detect();
        Path javaBin = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "evadeclips-detect", "jre", "bin")).resolve("java");
        Files.copy(Path.of("/bin/bash"), javaBin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        javaBin.toFile().setExecutable(true);
        // "sleep 20; true" keeps the shell itself running (a lone command would replace it).
        Process mc = new ProcessBuilder(javaBin.toString(), "-c", "sleep 20; true", "net.fabricmc.loader.impl.launch.knot.KnotClient", "--gameDir", "evadeclient").start();
        Thread.sleep(500);
        var found = net.evadeclient.clips.core.GameDetector.detect();
        mc.descendants().forEach(ProcessHandle::destroyForcibly);
        mc.destroyForcibly();
        other.destroyForcibly();
        check(none.isEmpty() && found.isPresent() && found.get().name().equals("Minecraft") && found.get().pid() == mc.pid(),
                "game detection finds a running Minecraft client and ignores other games (other game: " + none.map(Object::toString).orElse("none")
                        + ", Minecraft: " + found.map(Object::toString).orElse("none") + ")");
    }

    /**
     * Voice commands through the real recogniser, with speech synthesised by espeak-ng: commands must be
     * recognised (also over loud game noise and in another accent), ordinary talking must not trigger anything.
     */
    private void voiceTests(Path dir) throws Exception {
        boolean espeak = new java.io.File("/usr/bin/espeak-ng").canExecute();
        // The engine libraries come from Maven Central exactly as the app downloads them.
        if (net.evadeclient.clips.core.VoiceCommands.modelInstalled() && espeak && !net.evadeclient.clips.core.VoiceCommands.libsInstalled()) {
            try { net.evadeclient.clips.core.VoiceCommands.downloadLibs(null); }
            catch (Exception ex) { check(false, "speech engine download: " + ex.getMessage()); return; }
            check(net.evadeclient.clips.core.VoiceCommands.libsInstalled(), "speech engine downloads from Maven Central and passes its checksum");
        }
        if (!net.evadeclient.clips.core.VoiceCommands.modelInstalled() || !espeak) {
            System.out.println("  SKIP  voice commands (needs the speech model via -Dclips.voskModel and espeak-ng)");
            return;
        }
        String[][] cases = {
                {"clip that", "en-us", "", "CLIP"}, {"save clip", "en-us", "", "CLIP"}, {"start recording", "en-us", "", "RECORD_START"},
                {"stop recording", "en-us", "", "RECORD_STOP"}, {"mark that", "en-us", "", "MARKER"},
                {"clip that", "en-gb", "", "CLIP"}, {"clip that", "en-us", "noise", "CLIP"}, {"save clip", "en-us", "noise", "CLIP"},
                {"I went to the store to buy some bread", "en-us", "", ""}, {"nice shot, he is one HP, push B", "en-us", "", ""},
                {"can you clip my nails later", "en-us", "", ""}, {"", "en-us", "noise", ""},
                // the other accepted phrases
                {"clip it", "en-us", "", "CLIP"}, {"save that", "en-us", "", "CLIP"},
                // a lone word, or one that sounds like a command, is ordinary talk
                {"mark", "en-us", "", ""}, {"clip", "en-us", "", ""}, {"marker", "en-us", "", ""}, {"click", "en-us", "", ""},
                {"nice clip", "en-us", "", ""}};
        int ok = 0;
        List<String> bad = new ArrayList<>();
        try (var vc = new net.evadeclient.clips.core.VoiceCommands(net.evadeclient.clips.core.VoiceCommands.modelDir())) {
            for (String[] c : cases) {
                Path raw = dir.resolve("voice.wav"), pcm = dir.resolve("voice.pcm");
                if (c[0].isEmpty()) {
                    Ffmpeg.run(List.of("-f", "lavfi", "-i", "anoisesrc=a=0.3:d=3:c=pink", "-ar", "16000", "-ac", "1", "-f", "s16le", pcm.toString()));
                } else {
                    new ProcessBuilder("espeak-ng", "-v", c[1], "-s", "150", "-w", raw.toString(), c[0]).start().waitFor();
                    // pad with silence (and optionally loud game-like noise) the way a real mic would hear it
                    String af = c[2].equals("noise")
                            ? "[0:a]aresample=16000,apad=pad_dur=1[s];anoisesrc=a=0.06:c=brown:r=16000:d=6[n];[s][n]amix=inputs=2:duration=first:normalize=0"
                            : "[0:a]aresample=16000,apad=pad_dur=1";
                    Ffmpeg.run(List.of("-i", raw.toString(), "-filter_complex", af, "-ac", "1", "-f", "s16le", pcm.toString()));
                }
                List<net.evadeclient.clips.core.VoiceCommands.Command> got = new ArrayList<>();
                try (var in = Files.newInputStream(pcm)) { vc.recognize(in, got::add); }
                String want = c[3];
                boolean pass = want.isEmpty() ? got.isEmpty() : got.size() == 1 && got.get(0).name().equals(want);
                if (pass) ok++; else bad.add("\"" + c[0] + "\"" + (c[2].isEmpty() ? "" : "+noise") + " → " + got);
            }
        }
        check(ok == cases.length, "voice commands: " + ok + "/" + cases.length + " right (commands heard, also over noise and in a UK voice; "
                + "normal talking and noise ignored)" + (bad.isEmpty() ? "" : " wrong: " + bad));
    }

    /** Favourites lock, watermark, overlays, separate bitrates, live highlights. */
    private void upgradeTests(Library lib, Path dir, Clip src) throws Exception {
        // Favourites are locked against deletion (bulk delete skips them).
        Settings fs = new Settings();
        fs.clipsFolder = lib.root().toString();
        Files.createDirectories(lib.folder(Clip.Kind.IMPORT));
        Path a = Library.unique(lib.folder(Clip.Kind.IMPORT), "fav-a.mp4"), b = Library.unique(lib.folder(Clip.Kind.IMPORT), "fav-b.mp4");
        Files.copy(src.path(lib.root()), a);
        Files.copy(src.path(lib.root()), b);
        Clip ca = lib.addExisting(a, Clip.Kind.IMPORT, "keep me", null), cb = lib.addExisting(b, Clip.Kind.IMPORT, "delete me", null);
        ca.favorite = true;
        int removed = lib.delete(List.of(ca, cb));
        check(removed == 1 && Files.exists(a) && !Files.exists(b) && lib.get(ca.id) != null, "bulk delete keeps a locked favorite and deletes the rest");

        // Watermark (text, bottom-right) and a red picture-in-picture overlay from 1 s to 3 s, top-right.
        Path red = dir.resolve("red.mp4");
        Ffmpeg.run(List.of("-f", "lavfi", "-i", "color=c=red:s=640x360:r=30:d=2", "-c:v", "libx264", "-preset", "ultrafast", red.toString()));
        EditSpec e = EditSpec.whole(src.duration);
        e.segments.get(0).end = 5;
        EditSpec.Overlay ov = new EditSpec.Overlay();
        ov.file = red.toString(); ov.start = 1; ov.length = 2; ov.x = 0.8; ov.y = 0.2; ov.scale = 0.3;
        e.overlays.add(ov);
        Exporter.Options o = new Exporter.Options();
        o.preset = Exporter.Preset.SOURCE;
        o.watermark = new Exporter.Watermark("@evadeclips", "", "bottom-right", 8, 1.0, 3);
        Path out = Library.unique(lib.folder(Clip.Kind.EXPORT), "overlay-watermark.mp4");
        Exporter.export(src.path(lib.root()), src, e, o, out, null, null);
        var f2 = frame(out, 2.0, dir);
        var f4 = frame(out, 4.0, dir);
        int w = f2.getWidth(), h = f2.getHeight();
        double redIn = share(f2, (int) (w * 0.7), (int) (h * 0.12), (int) (w * 0.9), (int) (h * 0.28), 0xff0000);
        double redOut = share(f4, (int) (w * 0.7), (int) (h * 0.12), (int) (w * 0.9), (int) (h * 0.28), 0xff0000);
        check(redIn > 0.8 && redOut < 0.2, String.format("video overlay shows top-right only during 1-3 s (red: %.0f%% at 2 s, %.0f%% at 4 s)", redIn * 100, redOut * 100));
        double wmBR = whiteShareBox(f4, (int) (w * 0.6), (int) (h * 0.85), w, h), wmTL = whiteShareBox(f4, 0, 0, (int) (w * 0.4), (int) (h * 0.15));
        check(wmBR > 0.02 && wmBR > wmTL * 3, String.format("watermark is drawn bottom-right (white: %.1f%% there, %.1f%% top-left)", wmBR * 100, wmTL * 100));

        // Separate bitrates: replays at 2 Mbit/s, recordings at 10 Mbit/s, on busy footage.
        Settings bs = new Settings();
        bs.clipsFolder = dir.resolve("bitrate").resolve("clips").toString();
        bs.source = "test-noise"; bs.fps = 30; bs.resolution = 720; bs.recordMic = false;
        bs.replayBitrateMbps = 2; bs.recordBitrateMbps = 10; bs.codec = "h264";
        Library blib = new Library(bs);
        blib.load();
        CaptureEngine be = new CaptureEngine(bs, blib);
        be.start();
        long t0 = System.currentTimeMillis();
        while (!be.isLive() && System.currentTimeMillis() - t0 < 20000) Thread.sleep(200);
        Thread.sleep(3000);
        be.startRecording("Test");
        Thread.sleep(8000);
        Clip brec = be.stopRecording();
        Clip brep = be.saveReplay(6, null, null);
        be.shutdown();
        double recMbps = brec.size * 8 / brec.duration / 1e6, repMbps = brep.size * 8 / brep.duration / 1e6;
        check(recMbps > repMbps * 2.5 && repMbps < 2.6 && recMbps < 12,
                String.format("recordings and replays use their own bitrates (recording %.1f Mbit/s, replay %.1f Mbit/s)", recMbps, repMbps));
        blib.shutdown();

        // Live loud-moment auto-clips (Minecraft without the Clips mod's events): bursts every 8 s become clips.
        Settings ls = new Settings();
        ls.clipsFolder = dir.resolve("live").resolve("clips").toString();
        ls.source = "test-bursts"; ls.fps = 15; ls.resolution = 720; ls.recordMic = false; ls.replaySeconds = 30;
        Library llib = new Library(ls);
        llib.load();
        CaptureEngine le = new CaptureEngine(ls, llib);
        List<String> hits = new java.util.concurrent.CopyOnWriteArrayList<>();
        var lclip = new net.evadeclient.clips.events.AutoClipper((sec, title, game, tags) -> hits.add(title + "@" + System.currentTimeMillis()));
        lclip.after = 1;
        var lh = new net.evadeclient.clips.events.LiveHighlights(le, lclip, () -> "Minecraft", dir.resolve("live-work"));
        le.start();
        long l0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - l0 < 34000) { lh.step(); Thread.sleep(1500); }
        lh.step();
        Thread.sleep(1500);
        le.shutdown();
        lclip.shutdown();
        llib.shutdown();
        check(hits.size() >= 2 && hits.size() <= 4 && hits.stream().allMatch(x -> x.startsWith("Highlight")),
                "live auto-clips catch the loud bursts when no game events arrive (" + hits.size() + " clips in 34 s of play, bursts every 8 s)");

    }

    // ------------------------------------------------------------------ regression checks for fixed bugs

    /** Checks for bugs that were found and fixed, so they stay fixed. One failing group doesn't stop the others. */
    private void regressionTests(Path dir) {
        guard("capture recovery", () -> captureRecoveryTests(dir));
        guard("global hotkeys", this::hotkeyTests);
        guard("library", () -> libraryTests(dir));
        guard("Discord webhook", this::webhookTest);
        guard("Minecraft only", () -> minecraftOnlyTests(dir));
    }

    /**
     * Evade Clips works only with Minecraft running Evade Client: old screen / window / region capture settings
     * become Minecraft capture, nothing records or saves a clip without Minecraft open, and the Minecraft window is
     * what Linux capture grabs.
     */
    private void minecraftOnlyTests(Path dir) throws Exception {
        Path appdir = Files.createDirectories(dir.resolve("mc-only-appdata"));
        Files.writeString(appdir.resolve("settings.json"), "{\"source\":\"screen\",\"monitor\":1,\"cs2Events\":true,\"watchFolders\":[\"E:\\\\\"],\"defaultGame\":\"Other\"}");
        Settings.overrideAppDir(appdir);
        Settings old;
        try { old = Settings.load(); } finally { Settings.overrideAppDir(dir.resolve("appdata")); }
        check("minecraft".equals(old.source) && "Minecraft".equals(old.defaultGame),
                "settings from older versions capture Minecraft only (source " + old.source + ", game " + old.defaultGame + ")");

        Settings s = new Settings();
        s.clipsFolder = dir.resolve("mc-only").resolve("clips").toString();
        s.playSound = false;
        s.showToast = false;
        net.evadeclient.clips.ui.App app = new net.evadeclient.clips.ui.App(s);
        try {
            String clipErr = null, recErr = null;
            try { app.saveClipNow(0, 0, null, null, null); } catch (Exception e) { clipErr = e.getMessage(); }
            try { app.startRecording(null); } catch (Exception e) { recErr = e.getMessage(); }
            check(!app.minecraftRunning() && net.evadeclient.clips.ui.App.NEED_MINECRAFT.equals(clipErr) && net.evadeclient.clips.ui.App.NEED_MINECRAFT.equals(recErr)
                            && !app.engine.isLive() && !app.engine.isRecording(),
                    "without Minecraft, saving a clip or recording says \"" + net.evadeclient.clips.ui.App.NEED_MINECRAFT + "\" and captures nothing ("
                            + clipErr + " / " + recErr + ")");
        } finally {
            app.shutdown();
        }

        // Linux: the Minecraft window's area of the screen is what's captured.
        if (Ffmpeg.WINDOWS || Ffmpeg.MAC || java.awt.GraphicsEnvironment.isHeadless() || !new java.io.File("/usr/bin/xwininfo").canExecute()) {
            System.out.println("  SKIP  Minecraft window capture area (needs X11 and xwininfo)");
            return;
        }
        java.awt.Rectangle[] found = new java.awt.Rectangle[2];
        javax.swing.JFrame[] frame = new javax.swing.JFrame[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            frame[0] = new javax.swing.JFrame("Minecraft 1.21.4 - Singleplayer");
            frame[0].setUndecorated(true);
            frame[0].setBounds(120, 80, 640, 360);
            frame[0].setVisible(true);
        });
        try {
            for (int i = 0; i < 20 && found[0] == null; i++) { Thread.sleep(250); found[0] = net.evadeclient.clips.core.CaptureSource.linuxMinecraftWindow(); }
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
        Thread.sleep(500);
        found[1] = net.evadeclient.clips.core.CaptureSource.linuxMinecraftWindow();
        check(found[0] != null && found[0].equals(new java.awt.Rectangle(120, 80, 640, 360)) && found[1] == null,
                "Linux capture grabs the Minecraft window's area (" + found[0] + "), and finds nothing once it's closed (" + found[1] + ")");
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

    private static List<String> sorted(List<String> l) {
        List<String> s = new ArrayList<>(l);
        s.sort(null);
        return s;
    }

    /** Global hotkeys: modifiers held in game don't block the key, holding it saves once, and Ctrl+F8 differs from F8. */
    private void hotkeyTests() throws Exception {
        int f8 = NativeKeyEvent.VC_F8;
        AtomicInteger saves = new AtomicInteger();
        Hotkeys h = new Hotkeys();
        h.bind("F8", saves::incrementAndGet);
        // Ctrl (crouch) or Shift (sprint) held while pressing F8.
        key(h, true, f8, NativeInputEvent.CTRL_L_MASK);
        key(h, false, f8, NativeInputEvent.CTRL_L_MASK);
        key(h, true, f8, NativeInputEvent.SHIFT_L_MASK);
        key(h, false, f8, NativeInputEvent.SHIFT_L_MASK);
        Thread.sleep(400);
        int withMods = saves.getAndSet(0);
        check(withMods == 2, "F8 saves a clip while Ctrl or Shift is held (crouching, sprinting): " + withMods + " of 2 saved");
        // Held down, the keyboard repeats the key press: one clip. After letting go, the next press saves again.
        for (int i = 0; i < 3; i++) key(h, true, f8, 0);
        Thread.sleep(400);
        int held = saves.getAndSet(0);
        key(h, false, f8, 0);
        key(h, true, f8, 0);
        key(h, false, f8, 0);
        Thread.sleep(400);
        int next = saves.get();
        check(held == 1 && next == 1, "holding F8 down saves one clip, the next press another (" + held + " while held, " + next + " after letting go)");
        // A binding with modifiers wins over the plain key, so both can be used.
        AtomicInteger plain = new AtomicInteger(), ctrl = new AtomicInteger();
        Hotkeys both = new Hotkeys();
        both.bind("F8", plain::incrementAndGet);
        both.bind("Ctrl+F8", ctrl::incrementAndGet);
        key(both, true, f8, NativeInputEvent.CTRL_L_MASK);
        key(both, false, f8, NativeInputEvent.CTRL_L_MASK);
        key(both, true, f8, 0);
        key(both, false, f8, 0);
        Thread.sleep(400);
        check(plain.get() == 1 && ctrl.get() == 1, "F8 and Ctrl+F8 can be bound to different actions (one press each: F8 ran "
                + plain.get() + "x, Ctrl+F8 " + ctrl.get() + "x)");
    }

    private static void key(Hotkeys h, boolean down, int code, int mods) {
        NativeKeyEvent e = new NativeKeyEvent(down ? NativeKeyEvent.NATIVE_KEY_PRESSED : NativeKeyEvent.NATIVE_KEY_RELEASED, mods, 0, code,
                NativeKeyEvent.CHAR_UNDEFINED, NativeKeyEvent.KEY_LOCATION_STANDARD);
        if (down) h.nativeKeyPressed(e); else h.nativeKeyReleased(e);
    }

    /**
     * The capture's ffmpeg dying mid-recording, the recording's own encoder dying, and a post-roll as long as the
     * replay. Uses its own small captures (360p).
     */
    private void captureRecoveryTests(Path dir) throws Exception {
        Settings s = captureSettings(dir.resolve("recovery"));
        s.replaySeconds = 6;
        Library lib = new Library(s);
        lib.load();
        CaptureEngine e = new CaptureEngine(s, lib);
        List<String> messages = new CopyOnWriteArrayList<>();
        AtomicReference<CaptureEngine.Status> last = new AtomicReference<>();
        e.onStatus(st -> { last.set(st); remember(messages, st.message()); });
        try {
            long t0 = System.currentTimeMillis();
            e.start();
            boolean live = waitFor(() -> isLive(last.get()), 20_000);
            // The engine restarts a capture that dies after running for more than 5 s.
            Thread.sleep(Math.max(0, 7000 - (System.currentTimeMillis() - t0)));
            long pressed = System.currentTimeMillis();
            e.startRecording("Test");
            Thread.sleep(3000);
            Path pidFile = lib.bufferDir().resolve("ffmpeg.pid");
            long dead = pid(pidFile);
            if (dead > 0) ProcessHandle.of(dead).ifPresent(ProcessHandle::destroyForcibly);
            boolean back = dead > 0 && waitFor(() -> pid(pidFile) > 0 && pid(pidFile) != dead && isLive(last.get()), 20_000);
            Thread.sleep(3000);
            double wall = (System.currentTimeMillis() - pressed) / 1000.0;
            Clip rec = e.stopRecording();
            boolean told = messages.stream().anyMatch(m -> m.contains("recording continues"));
            check(live && back && rec != null && Math.abs(rec.duration - wall) < 2 && told,
                    String.format("a recording keeps going when the capture restarts mid-way: %.1f s clip for %.1f s pressed (capture %s, notice %s)",
                            rec == null ? 0 : rec.duration, wall, back ? "restarted" : "didn't come back", told ? "shown" : "missing"));

            // A post-roll as long as the replay: the start of the clip must still be in the buffer when it's saved.
            Thread.sleep(4000);
            Clip post = e.saveReplay(6, 6, "Post-roll", null);
            check(Math.abs(post.duration - 12) < 1.2,
                    String.format("post-roll as long as the replay buffer: 6 s before + 6 s after = %.2f s (want ~12)", post.duration));
        } finally {
            e.shutdown();
            lib.shutdown();
        }

        // Recordings with their own bitrate run a second encoder; if it dies, the recording carries on from the buffer.
        Settings rs = captureSettings(dir.resolve("recovery-encoder"));
        rs.codec = "h264";
        rs.replayBitrateMbps = 2;
        rs.recordBitrateMbps = 6;
        Library rlib = new Library(rs);
        rlib.load();
        CaptureEngine re = new CaptureEngine(rs, rlib);
        List<String> notes = new CopyOnWriteArrayList<>();
        AtomicReference<CaptureEngine.Status> rlast = new AtomicReference<>();
        re.onStatus(st -> { rlast.set(st); remember(notes, st.message()); });
        try {
            re.start();
            boolean live = waitFor(() -> isLive(rlast.get()), 20_000);
            Thread.sleep(2000);
            long pressed = System.currentTimeMillis();
            re.startRecording("Test");
            Thread.sleep(3000);
            long encoder = pid(rlib.bufferDir().resolve("ffmpeg-rec.pid"));
            if (encoder > 0) ProcessHandle.of(encoder).ifPresent(ProcessHandle::destroyForcibly);
            Thread.sleep(4000);
            double wall = (System.currentTimeMillis() - pressed) / 1000.0;
            Clip rec = re.stopRecording();
            boolean told = notes.stream().anyMatch(m -> m.startsWith("Recording encoder stopped"));
            long recordings = rlib.all().stream().filter(c -> c.kind == Clip.Kind.RECORDING).count();
            check(live && encoder > 0 && rec != null && recordings == 1 && rec.duration >= wall - 1 && rec.duration <= wall + 2.2 && told,
                    String.format("a recording keeps going when its own encoder dies: one %.1f s clip for %.1f s pressed (error %s)",
                            rec == null ? 0 : rec.duration, wall, told ? "shown" : "not shown"));
        } finally {
            re.shutdown();
            rlib.shutdown();
        }
    }

    private static Settings captureSettings(Path root) {
        Settings s = new Settings();
        s.clipsFolder = root.resolve("clips").toString();
        s.source = "test";
        s.fps = 30;
        s.resolution = 360;
        s.recordSystemAudio = true;
        s.recordMic = false;
        s.storageLimitGb = 0;
        return s;
    }

    private static boolean isLive(CaptureEngine.Status st) { return st != null && st.state() == CaptureEngine.State.LIVE; }

    private static void remember(List<String> messages, String m) {
        if (m != null && !m.isBlank() && (messages.isEmpty() || !messages.get(messages.size() - 1).equals(m))) messages.add(m);
    }

    /** The pid at the start of one of the engine's pid files, or -1. */
    private static long pid(Path file) {
        try { return Long.parseLong(Files.readString(file).trim().split("\\s+")[0]); } catch (Exception e) { return -1; }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier ok, long ms) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() - t0 > ms) return false;
            Thread.sleep(100);
        }
        return true;
    }

    /** The library index, the storage limit, cleaning up old replays and the cover frame, each in its own folder. */
    private void libraryTests(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("library-checks"));
        // One small busy clip, copied for every entry: all the same size, so the storage sums are exact.
        Path sample = root.resolve("sample.mp4");
        Ffmpeg.run(List.of("-f", "lavfi", "-i", "testsrc2=s=320x180:r=15:d=2,noise=alls=20:allf=t", "-f", "lavfi", "-i", "sine=f=440:d=2",
                "-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", sample.toString()));
        guard("library index", () -> indexTests(root.resolve("index"), sample));
        guard("storage limit", () -> storageLimitTests(root.resolve("limit"), sample));
        guard("old replay clean-up", () -> ageCleanTests(root.resolve("age"), sample));
        guard("cover frame", () -> coverTests(root.resolve("cover")));
    }

    private static Settings librarySettings(Path root) {
        Settings s = new Settings();
        s.clipsFolder = root.resolve("clips").toString();
        s.storageLimitGb = 0;
        return s;
    }

    private void indexTests(Path root, Path sample) throws Exception {
        Library lib = new Library(librarySettings(root));
        try {
            Path replays = Files.createDirectories(lib.folder(Clip.Kind.REPLAY));
            for (String n : List.of("a", "b", "c")) Files.copy(sample, replays.resolve(n + ".mp4"), StandardCopyOption.REPLACE_EXISTING);
            Path index = Files.createDirectories(lib.metaDir()).resolve("library.json");
            String good = "{\"id\":\"clip-%s\",\"file\":\"Replays/%s.mp4\",\"title\":\"%s\",\"kind\":\"REPLAY\",\"favorite\":true,\"duration\":2.0,\"tags\":[\"keep\"]}";
            // The middle entry has a field of the wrong type (as a newer or damaged version could write).
            Files.writeString(index, "[" + String.format(good, "a", "a", "Alpha") + ","
                    + "{\"id\":\"clip-b\",\"file\":\"Replays/b.mp4\",\"title\":\"Beta\",\"kind\":\"REPLAY\",\"markers\":{\"not\":\"a list\"}},"
                    + String.format(good, "c", "c", "Gamma") + "]");
            lib.load();
            Clip a = lib.get("clip-a"), c = lib.get("clip-c");
            check(a != null && c != null && "Alpha".equals(a.title) && "Gamma".equals(c.title) && a.favorite && c.favorite && c.tags.contains("keep")
                            && lib.all().size() == 3,
                    "one broken entry in library.json doesn't wipe the other clips' titles, tags and favourites (" + titles(lib) + ")");
            // Cut off mid-write: the clips are found again, and the unreadable index is kept instead of overwritten.
            Files.writeString(index, "[{\"id\":\"clip-a\",\"file\":\"Repl");
            lib.load();
            boolean backup;
            try (var files = Files.list(lib.metaDir())) {
                backup = files.anyMatch(p -> p.getFileName().toString().startsWith("library.json.broken"));
            }
            check(backup && lib.all().size() == 3, "an unreadable library.json is kept as a backup and the clips are found again ("
                    + lib.all().size() + " clips, backup " + (backup ? "kept" : "missing") + ")");
        } finally {
            lib.shutdown();
        }
    }

    private void storageLimitTests(Path root, Path sample) throws Exception {
        Settings s = librarySettings(root);
        Library lib = new Library(s);
        try {
            lib.load();
            long size = Files.size(sample);
            double gb = 1024.0 * 1024 * 1024;
            Clip ex1 = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.EXPORT), "export.mp4"), Clip.Kind.EXPORT, "Export 1", null, null, null);
            Clip ex2 = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.EXPORT), "export.mp4"), Clip.Kind.EXPORT, "Export 2", null, null, null);
            Clip imp = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.IMPORT), "import.mp4"), Clip.Kind.IMPORT, "Import", null, null, null);
            Clip old = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.REPLAY), "old.mp4"), Clip.Kind.REPLAY, "Old replay", null, null, null);
            old.created = System.currentTimeMillis() - 3_600_000;
            // The exports alone are over the limit: they're never deleted, and nothing else is deleted to make room.
            s.storageLimitGb = size * 1.5 / gb;
            Clip fresh = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.REPLAY), "fresh.mp4"), Clip.Kind.REPLAY, "New replay", null, null, null);
            check(present(lib, ex1, ex2, imp, old, fresh),
                    "storage limit: when exports alone fill it, no export, import or replay is deleted (left: " + titles(lib) + ")");
            // Room for exports, the import and 1.5 replays: the oldest replay goes, the rest stay.
            s.storageLimitGb = size * 5.5 / gb;
            Clip newest = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.REPLAY), "newest.mp4"), Clip.Kind.REPLAY, "Newest replay", null, null, null);
            boolean oldGone = lib.get(old.id) == null && !Files.exists(old.path(lib.root()));
            check(oldGone && present(lib, ex1, ex2, imp, fresh, newest),
                    "storage limit: the oldest replay makes room; exports, imports and newer replays stay (left: " + titles(lib) + ")");
        } finally {
            lib.shutdown();
        }
    }

    private void ageCleanTests(Path root, Path sample) throws Exception {
        java.lang.reflect.Method sweep = RegressionSupport.method(Library.class, "sweepOldReplays");
        if (sweep == null || !RegressionSupport.hasField(Settings.class, "replayMaxAgeDays")) {
            RegressionSupport.skip("auto-clean of old replays", "Settings.replayMaxAgeDays / Library.sweepOldReplays() aren't in this build");
            return;
        }
        Settings s = librarySettings(root);
        Library lib = new Library(s);
        try {
            lib.load();
            long day = 86_400_000L, now = System.currentTimeMillis();
            Clip oldPlain = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.REPLAY), "old.mp4"), Clip.Kind.REPLAY, "Old replay", null, null, null);
            Clip oldFav = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.REPLAY), "old-fav.mp4"), Clip.Kind.REPLAY, "Old favourite", null, null, null);
            Clip recent = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.REPLAY), "recent.mp4"), Clip.Kind.REPLAY, "Recent replay", null, null, null);
            Clip oldRec = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.RECORDING), "old-rec.mp4"), Clip.Kind.RECORDING, "Old recording", null, null, null);
            Clip oldExport = lib.addNew(copyInto(sample, lib.folder(Clip.Kind.EXPORT), "old-export.mp4"), Clip.Kind.EXPORT, "Old export", null, null, null);
            oldPlain.created = oldFav.created = oldRec.created = oldExport.created = now - 40 * day;
            recent.created = now - day;
            oldFav.favorite = true;
            RegressionSupport.set(s, "replayMaxAgeDays", 30);
            Object freed = RegressionSupport.invoke(sweep, lib);
            boolean gone = lib.get(oldPlain.id) == null && !Files.exists(oldPlain.path(lib.root()));
            boolean bytes = !(freed instanceof Number n) || n.longValue() >= Files.size(sample);
            check(gone && bytes && present(lib, oldFav, recent, oldRec, oldExport),
                    "replays older than 30 days are cleaned up; favourites, recent replays, recordings and exports stay (left: " + titles(lib)
                            + (freed instanceof Number n ? ", freed " + n.longValue() + " bytes" : "") + ")");
        } finally {
            lib.shutdown();
        }
    }

    private void coverTests(Path root) throws Exception {
        java.lang.reflect.Method setCover = RegressionSupport.method(Library.class, "setCover", Clip.class, double.class);
        if (setCover == null || !RegressionSupport.hasField(Clip.class, "coverAt")) {
            RegressionSupport.skip("cover frame", "Clip.coverAt / Library.setCover(Clip, double) aren't in this build");
            return;
        }
        Library lib = new Library(librarySettings(root));
        try {
            // Red for the first second, blue after it: the default cover (further in) is blue, a cover at 0.2 s is red.
            Path video = Files.createDirectories(lib.folder(Clip.Kind.IMPORT)).resolve("red-then-blue.mp4");
            Ffmpeg.run(List.of("-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=1", "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=30:d=3",
                    "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", video.toString()));
            lib.load();
            Clip c = lib.all().get(0);
            Path thumb = lib.thumb(c);
            boolean blue = waitFor(() -> near(centre(thumb), 0x0000ff), 20_000);
            long before = Files.exists(thumb) ? Files.getLastModifiedTime(thumb).toMillis() : 0;
            Thread.sleep(50);
            RegressionSupport.invoke(setCover, lib, c, 0.2);
            boolean red = waitFor(() -> near(centre(thumb), 0xff0000), 20_000);
            long after = Files.exists(thumb) ? Files.getLastModifiedTime(thumb).toMillis() : 0;
            Object at = RegressionSupport.get(c, "coverAt");
            check(blue && red && after > before && at instanceof Number n && Math.abs(n.doubleValue() - 0.2) < 1e-9,
                    "choosing the frame at 0.2 s as the cover redraws the library thumbnail from it (default cover " + (blue ? "blue" : "not found")
                            + ", after choosing " + (red ? "red" : "unchanged") + ", coverAt " + at + ")");
        } finally {
            lib.shutdown();
        }
    }

    /** Posting a shared clip to a Discord webhook (a local server stands in for Discord). */
    private void webhookTest() throws Exception {
        java.lang.reflect.Method post = RegressionSupport.methodNamed(net.evadeclient.clips.core.CloudShare.class, "postWebhook", 3);
        if (post == null) {
            RegressionSupport.skip("posting to a Discord webhook", "CloudShare.postWebhook(url, clip, link) isn't in this build");
            return;
        }
        Class<?>[] types = post.getParameterTypes();
        if (types[0] != String.class || types[1] != Clip.class || types[2] != String.class) {
            RegressionSupport.skip("posting to a Discord webhook", "postWebhook takes " + java.util.Arrays.toString(types) + ", not (url, clip, link)");
            return;
        }
        List<String> got = new CopyOnWriteArrayList<>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            got.add(ex.getRequestMethod());
            got.add(String.valueOf(ex.getRequestURI().getRawQuery()));
            got.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] reply = "{\"id\":\"1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        server.start();
        try {
            Clip c = new Clip();
            c.id = "hook";
            c.title = "Clutch 1v3";
            c.game = "Minecraft";
            c.shareId = "abc123";
            c.shareUrl = "https://clips.evadeclient.com/c/abc123?k=k3yS3cret";
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/123/token";
            Object cloud = java.lang.reflect.Modifier.isStatic(post.getModifiers()) ? null : new net.evadeclient.clips.core.CloudShare(new Settings());
            RegressionSupport.invoke(post, cloud, url, c, c.shareUrl);
            String method = got.size() > 0 ? got.get(0) : "", query = got.size() > 1 ? got.get(1) : "", body = got.size() > 2 ? got.get(2) : "";
            String content = "";
            boolean noPings = false;
            try {
                var j = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                content = j.has("content") ? j.get("content").getAsString() : "";
                noPings = j.has("allowed_mentions") && j.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty();
            } catch (RuntimeException ignored) {}
            check("POST".equals(method) && query.contains("wait=true") && content.contains("Clutch 1v3") && content.contains("/c/abc123")
                            && !body.contains("k3yS3cret") && noPings,
                    "a shared clip posts to a Discord webhook with its title and link, no private key and no pings (\"" + content.replace("\n", " / ") + "\")");
        } finally {
            server.stop(0);
        }
    }

    private static Path copyInto(Path file, Path dir, String name) throws java.io.IOException {
        Files.createDirectories(dir);
        Path p = Library.unique(dir, name);
        Files.copy(file, p);
        return p;
    }

    private static boolean present(Library lib, Clip... clips) {
        for (Clip c : clips) if (lib.get(c.id) == null || !Files.exists(c.path(lib.root()))) return false;
        return true;
    }

    private static String titles(Library lib) {
        return lib.all().stream().map(c -> c.title).sorted().toList().toString();
    }

    /** The colour at the centre of an image file (-1 while it's missing or half-written). */
    private static int centre(Path img) {
        try {
            var im = javax.imageio.ImageIO.read(img.toFile());
            return im == null ? -1 : im.getRGB(im.getWidth() / 2, im.getHeight() / 2) & 0xffffff;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean near(int rgb, int want) {
        return rgb >= 0 && Math.abs((rgb >> 16 & 255) - (want >> 16 & 255)) < 60 && Math.abs((rgb >> 8 & 255) - (want >> 8 & 255)) < 60
                && Math.abs((rgb & 255) - (want & 255)) < 60;
    }

    private static int code(java.net.http.HttpClient http, String url) throws Exception {
        return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Share of pixels close to {@code rgb} in a box. */
    private static double share(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1, int rgb) {
        int hit = 0, total = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            int p = img.getRGB(x, y);
            total++;
            if (Math.abs((p >> 16 & 255) - (rgb >> 16 & 255)) < 40 && Math.abs((p >> 8 & 255) - (rgb >> 8 & 255)) < 40 && Math.abs((p & 255) - (rgb & 255)) < 40) hit++;
        }
        return hit / (double) Math.max(1, total);
    }

    private static double whiteShareBox(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1) {
        return share(img, x0, y0, Math.min(x1, img.getWidth()), Math.min(y1, img.getHeight()), 0xffffff);
    }

    /** Share links + phone gallery against a running Evade Clips Share server (CLIPS_SHARE_URL). */
    private void shareTests(Library lib, Path dir) throws Exception {
        String base = System.getProperty("clips.shareUrl");
        if (base == null || base.isBlank()) { System.out.println("  SKIP  share links (set CLIPS_SHARE_URL to a running clips-share server)"); return; }
        Settings s = new Settings();
        s.cloudUrl = base;
        var cloud = new net.evadeclient.clips.core.CloudShare(s);
        cloud.partSizeOverride = 5 * 1024 * 1024;
        // A busy 3-track clip, big enough to need several upload parts.
        Path big = dir.resolve("big.mp4");
        Ffmpeg.run(List.of("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=8,noise=alls=30:allf=t", "-f", "lavfi", "-i", "sine=f=440:d=8",
                "-f", "lavfi", "-i", "sine=f=220:d=8", "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "12M",
                "-c:a", "aac", "-metadata:s:a:0", "handler_name=Game", "-metadata:s:a:1", "handler_name=Mic", big.toString()));
        Clip c = lib.importFiles(List.of(big), true).get(0);
        for (int i = 0; i < 100 && !Files.exists(lib.thumb(c)); i++) Thread.sleep(100);
        c.edit = EditSpec.whole(c.duration);
        c.edit.captions.add(new EditSpec.Caption("shared!", 0, 2, "top", "meme"));
        String url = cloud.share(c, lib, null, null);
        check(url.startsWith(base + "/c/"), "share link created (" + url + ")");
        var http = java.net.http.HttpClient.newHttpClient();
        var pageRes = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        String pg = pageRes.body();
        String videoUrl = base + "/v/" + c.shareId + ".mp4";
        check(pageRes.statusCode() == 200 && pg.contains("og:video:type\" content=\"video/mp4") && pg.contains("/v/" + c.shareId + ".mp4\"")
                && pg.contains("og:image") && pg.contains("twitter:player:stream"), "share page has Discord/Twitter video embed tags");
        var full = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(videoUrl)).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        Path got = dir.resolve("downloaded.mp4");
        Files.write(got, full.body());
        Ffmpeg.MediaInfo gi = Ffmpeg.probe(got);
        check(full.statusCode() == 200 && full.headers().firstValue("content-type").orElse("").equals("video/mp4")
                        && gi.audioTracks() == 1 && Math.abs(gi.duration() - c.duration) < 0.3 && "h264".equals(Ffmpeg.capture(List.of(Ffmpeg.ffprobe(), "-v", "error", "-select_streams", "v:0",
                        "-show_entries", "stream=codec_name", "-of", "csv=p=0", got.toString()), 10).trim()),
                String.format("edited 2-track clip is shared as a version that plays anywhere: %.1f MB, H.264, %d mixed audio track, %.2f s", full.body().length / 1e6, gi.audioTracks(), gi.duration()));
        var part = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(videoUrl)).header("Range", "bytes=100-199").build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        var tail = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(videoUrl)).header("Range", "bytes=-50").build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        byte[] fb = full.body();
        check(part.statusCode() == 206 && java.util.Arrays.equals(part.body(), java.util.Arrays.copyOfRange(fb, 100, 200))
                        && part.headers().firstValue("content-range").orElse("").equals("bytes 100-199/" + fb.length)
                        && tail.statusCode() == 206 && java.util.Arrays.equals(tail.body(), java.util.Arrays.copyOfRange(fb, fb.length - 50, fb.length)),
                "video is served in byte ranges (seeking in Discord, browsers and phones)");
        var th = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/t/" + c.shareId + ".jpg")).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        check(th.statusCode() == 200 && th.body().length > 1000, "thumbnail for the link preview");
        String gallery = cloud.galleryUrl();
        var g1 = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(gallery)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        var g2 = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(gallery.replaceAll("t=.*", "t=wrong"))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(g1.statusCode() == 200 && g1.body().contains("/c/" + c.shareId) && g2.statusCode() == 404, "phone gallery lists the clip; a wrong link shows nothing");
        String again = cloud.share(c, lib, null, null);
        check(again.equals(url), "sharing an unchanged clip reuses its link");
        var bad = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/clips")).header("Authorization", "Bearer " + s.cloudAccount + ".nope").build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var over = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/uploads")).header("Authorization", "Bearer " + s.cloudAccount + "." + s.cloudKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"size\":60000000}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(bad.statusCode() == 401 && over.statusCode() == 413, "wrong key refused (401); upload past the storage quota refused (413)");
        // A big unedited H.264 clip is uploaded as-is, in several parts, and comes back byte-for-byte.
        Path raw = dir.resolve("raw.mp4");
        Ffmpeg.run(List.of("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=8,noise=alls=30:allf=t", "-f", "lavfi", "-i", "sine=f=440:d=8",
                "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "14M", "-c:a", "aac", "-movflags", "+faststart", raw.toString()));
        Clip rc = lib.importFiles(List.of(raw), true).get(0);
        for (int i = 0; i < 100 && !Files.exists(lib.thumb(rc)); i++) Thread.sleep(100);
        cloud.share(rc, lib, null, null);
        byte[] orig = Files.readAllBytes(rc.path(lib.root()));
        var back = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v/" + rc.shareId + ".mp4")).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        check(cloud.lastParts >= 2 && java.util.Arrays.equals(orig, back.body()),
                String.format("a %.1f MB clip uploads in %d parts and downloads byte-for-byte identical", orig.length / 1e6, cloud.lastParts));
        cloud.unshare(rc, lib);

        // Privacy: a private clip's page and video need the owner's gallery token.
        Path raw2 = rc.path(lib.root());
        Clip pc = lib.importFiles(List.of(Files.copy(raw2, dir.resolve("private.mp4"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)), false).get(0);
        for (int i = 0; i < 100 && !Files.exists(lib.thumb(pc)); i++) Thread.sleep(100);
        pc.sharePrivate = true;
        String purl = cloud.share(pc, lib, null, null);
        int anon = code(http, base + "/c/" + pc.shareId), anonV = code(http, base + "/v/" + pc.shareId + ".mp4"), owner = code(http, purl);
        cloud.setVisibility(pc, lib, false);
        int afterPublic = code(http, base + "/c/" + pc.shareId);
        check(anon == 404 && anonV == 404 && owner == 200 && afterPublic == 200 && pc.shareUrl.equals(base + "/c/" + pc.shareId),
                "private clips need the owner's link; making it public opens it (" + anon + "/" + anonV + "/" + owner + "/" + afterPublic + ")");

        // Reports and takedowns: the owner is told which clip and why; the link explains it.
        var rep = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/report")).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"clip\":\"" + pc.shareId + "\",\"reason\":\"copyright\",\"details\":\"my song\"}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        String adminTok = System.getProperty("clips.shareAdmin", "test-admin");
        var reports = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/admin/reports")).header("Authorization", "Admin " + adminTok).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var rm = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/admin/remove")).header("Authorization", "Admin " + adminTok)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"clip\":\"" + pc.shareId + "\",\"reason\":\"copyright\",\"note\":\"Music claim by Label X\"}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var gonePage = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/c/" + pc.shareId)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        var notes = cloud.newNotices();
        var notesAgain = cloud.newNotices();
        check(rep.statusCode() == 200 && reports.body().contains("my song") && rm.statusCode() == 200 && gonePage.statusCode() == 410
                        && gonePage.body().contains("copyright") && notes.size() == 1 && notes.get(0).get("title").getAsString().equals(pc.title)
                        && notes.get(0).get("note").getAsString().contains("Label X") && notesAgain.isEmpty(),
                "report → takedown: the link says why, and the owner gets one notice naming the clip and reason");

        // Phone upload (gallery token) is pulled into the library.
        String acct = s.cloudAccount;
        String gauth = "Gallery " + acct + "." + s.cloudViewToken;
        byte[] phoneBytes = Files.readAllBytes(raw2);
        var st = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/uploads")).header("Authorization", gauth)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"title\":\"Phone clip\",\"game\":\"Minecraft\",\"size\":" + phoneBytes.length + "}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var sj = com.google.gson.JsonParser.parseString(st.body()).getAsJsonObject();
        String pid = sj.get("clip").getAsString(), up = sj.get("uploadId").getAsString();
        var part1 = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/uploads/" + pid + "/1?uploadId=" + java.net.URLEncoder.encode(up, "UTF-8")))
                .header("Authorization", gauth).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(phoneBytes)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        String etag = com.google.gson.JsonParser.parseString(part1.body()).getAsJsonObject().get("etag").getAsString();
        http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/uploads/" + pid + "/complete")).header("Authorization", gauth)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"uploadId\":\"" + up + "\",\"parts\":[{\"partNumber\":1,\"etag\":\"" + etag + "\"}]}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var galleryDelete = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/clips/" + pid)).header("Authorization", gauth).DELETE().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        List<Clip> pulled = cloud.pullPhoneUploads(lib);
        List<Clip> pulledAgain = cloud.pullPhoneUploads(lib);
        check(galleryDelete.statusCode() == 401 && pulled.size() == 1 && pulled.get(0).title.equals("Phone clip") && pulled.get(0).game.equals("Minecraft")
                        && pulled.get(0).tags.contains("phone") && java.util.Arrays.equals(Files.readAllBytes(pulled.get(0).path(lib.root())), phoneBytes) && pulledAgain.isEmpty(),
                "a phone upload lands in the library once, byte-for-byte; the gallery link can upload but not delete");

        // The subdomain's landing page offers the downloads.
        var home = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        String dlPath = home.body().replaceAll("(?s).*href=\"(/downloads/[^\"]+\\.exe)\".*", "$1");
        int dl = dlPath.startsWith("/downloads/") ? code(http, base + dlPath) : -1;
        check(home.statusCode() == 200 && home.body().contains("Download for Windows") && dl == 200, "landing page has working downloads (" + dlPath + " → " + dl + ")");

        cloud.unshare(pulled.get(0), lib);
        String id = c.shareId;
        cloud.unshare(c, lib);
        var gone = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/c/" + id)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        var goneV = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v/" + id + ".mp4")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(gone.statusCode() == 404 && goneV.statusCode() == 404 && cloud.list().getAsJsonArray("clips").isEmpty() && cloud.list().get("used").getAsLong() == 0,
                "stop sharing removes the page, the video and the storage use");
    }

    private static java.awt.image.BufferedImage frame(Path video, double t, Path dir) throws Exception {
        Path png = dir.resolve("frame-" + video.getFileName() + "-" + t + ".png");
        Ffmpeg.run(List.of("-ss", Library.fmt(t), "-i", video.toString(), "-frames:v", "1", png.toString()));
        return javax.imageio.ImageIO.read(png.toFile());
    }

    /** Share of near-white pixels in rows y0..y1. */
    private static double whiteShare(java.awt.image.BufferedImage img, int y0, int y1) {
        int white = 0, total = 0;
        for (int y = y0; y < y1; y += 2) for (int x = 0; x < img.getWidth(); x += 2) {
            int rgb = img.getRGB(x, y);
            total++;
            if ((rgb >> 16 & 255) > 235 && (rgb >> 8 & 255) > 235 && (rgb & 255) > 235) white++;
        }
        return white / (double) Math.max(1, total);
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
