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
        });
        var http = java.net.http.HttpClient.newHttpClient();
        var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/clip?seconds=3&title=Kill%20Steve&tag=kill"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(res.statusCode() == 200 && res.body().contains("\"ok\":true"), "API /clip saves a titled clip (" + res.statusCode() + ")");
        var blocked = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/clip"))
                .header("Origin", "https://evil.example").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        check(blocked.statusCode() == 403, "API refuses requests from web pages");
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
        check(mi.width() == 1280 && mi.height() == 720 && Math.abs(mi.duration() - want) < 0.6 && mi.hasAudio(),
                "montage of 3 clips: " + mi.width() + "x" + mi.height() + " " + String.format("%.2f", mi.duration()) + " s (want " + String.format("%.2f", want) + ")");
        upgradeTests(lib, dir, src);
        shareTests(lib, dir);
        lib.shutdown();
        return finish();
    }

    /** Game events → clips, with recorded-style payloads from each game. */
    private void eventTests() throws Exception {
        List<String> saved = new java.util.concurrent.CopyOnWriteArrayList<>();
        var clipper = new net.evadeclient.clips.events.AutoClipper((sec, title, game, tags) -> saved.add(game + "|" + title + "|" + tags + "|" + sec));
        List<String> marks = new java.util.concurrent.CopyOnWriteArrayList<>();
        clipper.onMarker = marks::add;
        clipper.after = 0;

        // CS2 GSI: three kills in a round (one headshot) → one "3K" clip; a spectated player's kills are ignored.
        var cs2 = new net.evadeclient.clips.events.Cs2Integration(clipper, "tok");
        String me = "76561198000000001";
        java.util.function.BiFunction<Integer, Integer, String> st = (k, hs) -> "{\"auth\":{\"token\":\"tok\"},\"provider\":{\"steamid\":\"" + me
                + "\"},\"map\":{\"round\":4,\"phase\":\"live\"},\"player\":{\"steamid\":\"" + me + "\",\"team\":\"CT\",\"state\":{\"round_kills\":" + k
                + ",\"round_killhs\":" + hs + "},\"match_stats\":{\"mvps\":0}}}";
        check(!cs2.handle(st.apply(0, 0).replace("tok", "bad")), "CS2: updates with the wrong token are rejected");
        cs2.handle(st.apply(0, 0));
        clipper.after = 2;
        cs2.handle(st.apply(1, 1));
        cs2.handle(st.apply(2, 1));
        cs2.handle(st.apply(3, 1));
        cs2.handle(st.apply(3, 1).replace("\"steamid\":\"" + me + "\",\"team", "\"steamid\":\"76561198000000002\",\"team").replace("round_kills\":3", "round_kills\":5"));
        Thread.sleep(2600);
        check(saved.size() == 1 && saved.get(0).startsWith("Counter-Strike 2|3K|") && saved.get(0).contains("headshot"),
                "CS2: 3 kills in a round become one \"3K\" clip, spectated kills ignored (" + saved + ")");
        saved.clear();
        clipper.after = 0;
        String win = "{\"auth\":{\"token\":\"tok\"},\"provider\":{\"steamid\":\"" + me + "\"},\"map\":{\"round\":24,\"phase\":\"gameover\",\"team_ct\":{\"score\":13},\"team_t\":{\"score\":10}},"
                + "\"player\":{\"steamid\":\"" + me + "\",\"team\":\"CT\",\"state\":{\"round_kills\":0,\"round_killhs\":0},\"match_stats\":{\"mvps\":0}}}";
        cs2.handle(win);
        Thread.sleep(300);
        check(saved.size() == 1 && saved.get(0).contains("|Match win|"), "CS2: match win (13-10 on CT) is clipped (" + saved + ")");
        saved.clear();

        // League Live Client Data: events before we joined are ignored; then a kill + triple kill merge.
        var lol = new net.evadeclient.clips.events.LeagueIntegration(clipper);
        String head = "{\"activePlayer\":{\"riotIdGameName\":\"Faker\",\"riotId\":\"Faker#KR1\"},\"events\":{\"Events\":[";
        String old = "{\"EventID\":0,\"EventName\":\"GameStart\"},{\"EventID\":1,\"EventName\":\"ChampionKill\",\"KillerName\":\"Faker\",\"VictimName\":\"Old\"}";
        lol.handle(head + old + "]}}");
        clipper.after = 1;
        lol.handle(head + old + ",{\"EventID\":2,\"EventName\":\"ChampionKill\",\"KillerName\":\"Faker\",\"VictimName\":\"Zed\"},"
                + "{\"EventID\":3,\"EventName\":\"ChampionKill\",\"KillerName\":\"Someone\",\"VictimName\":\"Faker\"},"
                + "{\"EventID\":4,\"EventName\":\"Multikill\",\"KillerName\":\"Faker\",\"KillStreak\":3}]}}");
        Thread.sleep(1500);
        check(saved.size() == 1 && saved.get(0).startsWith("League of Legends|Triple kill|"), "League: kill + triple kill = one \"Triple kill\" clip (" + saved + ")");
        saved.clear();
        clipper.after = 0;
        lol.handle(head + "{\"EventID\":9,\"EventName\":\"BaronKill\",\"KillerName\":\"Faker\",\"Stolen\":\"True\"}]}}");
        Thread.sleep(300);
        check(saved.size() == 1 && saved.get(0).contains("|Baron steal|"), "League: baron steal is clipped (" + saved + ")");
        saved.clear();
        marks.clear();
        lol.handle(head + "{\"EventID\":10,\"EventName\":\"ChampionKill\",\"KillerName\":\"Zed\",\"VictimName\":\"Faker\"}]}}");
        String alive = st.apply(0, 0).replace("\"round_kills\"", "\"health\":100,\"round_kills\"").replace("\"round\":4", "\"round\":5");
        cs2.handle(alive);
        cs2.handle(alive.replace("\"health\":100", "\"health\":0"));
        Thread.sleep(300);
        check(saved.isEmpty() && marks.equals(List.of("Death", "Death")), "deaths in League and CS2 become timeline markers, not clips, by default (" + marks + ")");
        clipper.shutdown();

        // Game detection from process paths.
        String[][] cases = {
                {"C:\\Program Files (x86)\\Steam\\steamapps\\common\\Counter-Strike Global Offensive\\game\\bin\\win64\\cs2.exe", "", "Counter-Strike 2"},
                {"D:\\SteamLibrary\\steamapps\\common\\Hades II\\Ship\\Hades2.exe", "", "Hades II"},
                {"C:\\Program Files\\Epic Games\\Some Indie Game\\Binaries\\Win64\\Game-Win64-Shipping.exe", "", "Some Indie Game"},
                {"C:\\Riot Games\\VALORANT\\live\\ShooterGame\\Binaries\\Win64\\VALORANT-Win64-Shipping.exe", "", "Valorant"},
                {"C:\\Program Files\\Java\\bin\\javaw.exe", "-cp x net.fabricmc.loader.impl.launch.knot.KnotClient", "Minecraft"},
                {"C:\\Program Files (x86)\\Steam\\steam.exe", "", null},
                {"C:\\Program Files (x86)\\Steam\\steamapps\\common\\Hades II\\UnityCrashHandler64.exe", "", null},
                {"C:\\Program Files\\Java\\bin\\java.exe", "-jar idea.jar", null},
                {"/usr/bin/firefox", "", null}};
        int ok = 0;
        for (String[] c : cases) if (java.util.Objects.equals(net.evadeclient.clips.core.GameDetector.identify(c[0], c[1]), c[2])) ok++;
        check(ok == cases.length, "game detection: " + ok + "/" + cases.length + " paths identified correctly (libraries, known games, Minecraft, launchers ignored)");

        // Live: a program running from a Steam library folder is found among the running processes.
        Path fake = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "evadeclips-detect", "steamapps", "common", "Test Arena"));
        Path bin = fake.resolve("TestArena");
        Files.copy(Path.of("/bin/sleep"), bin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        bin.toFile().setExecutable(true);
        Process p = new ProcessBuilder(bin.toString(), "20").start();
        Thread.sleep(500);
        var found = net.evadeclient.clips.core.GameDetector.detect();
        p.destroyForcibly();
        check(found.isPresent() && found.get().name().equals("Test Arena"), "game detection finds a running game (" + found.map(Object::toString).orElse("none") + ")");
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
                {"can you clip my nails later", "en-us", "", ""}, {"", "en-us", "noise", ""}};
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

    /** Favourites lock, watermark, overlays, separate bitrates, live highlights, CS2 full rounds. */
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

        // Live loud-moment auto-clips (any game): bursts every 8 s become clips.
        Settings ls = new Settings();
        ls.clipsFolder = dir.resolve("live").resolve("clips").toString();
        ls.source = "test-bursts"; ls.fps = 15; ls.resolution = 720; ls.recordMic = false; ls.replaySeconds = 30;
        Library llib = new Library(ls);
        llib.load();
        CaptureEngine le = new CaptureEngine(ls, llib);
        List<String> hits = new java.util.concurrent.CopyOnWriteArrayList<>();
        var lclip = new net.evadeclient.clips.events.AutoClipper((sec, title, game, tags) -> hits.add(title + "@" + System.currentTimeMillis()));
        lclip.after = 1;
        var lh = new net.evadeclient.clips.events.LiveHighlights(le, lclip, () -> "The Finals", dir.resolve("live-work"));
        le.start();
        long l0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - l0 < 34000) { lh.step(); Thread.sleep(1500); }
        lh.step();
        Thread.sleep(1500);
        le.shutdown();
        lclip.shutdown();
        llib.shutdown();
        check(hits.size() >= 2 && hits.size() <= 4 && hits.stream().allMatch(x -> x.startsWith("Highlight")),
                "live auto-clips catch the loud bursts in a game with no event API (" + hits.size() + " clips in 34 s of play, bursts every 8 s)");

        // CS2 full round: a 3 s round with a kill becomes one clip covering the round.
        List<String> rs = new java.util.concurrent.CopyOnWriteArrayList<>();
        var rc = new net.evadeclient.clips.events.AutoClipper((sec, title, game, tags) -> rs.add(title + "|" + sec));
        rc.after = 0;
        var cs = new net.evadeclient.clips.events.Cs2Integration(rc, "tok");
        cs.fullRounds = "kills";
        String me = "76561198000000001";
        java.util.function.BiFunction<String, Integer, String> st = (phase, k) -> "{\"auth\":{\"token\":\"tok\"},\"provider\":{\"steamid\":\"" + me
                + "\"},\"map\":{\"round\":7,\"phase\":\"live\"},\"round\":{\"phase\":\"" + phase + "\",\"win_team\":\"CT\"},\"player\":{\"steamid\":\"" + me
                + "\",\"team\":\"CT\",\"state\":{\"health\":100,\"round_kills\":" + k + ",\"round_killhs\":0},\"match_stats\":{\"mvps\":0}}}";
        cs.handle(st.apply("freezetime", 0));
        cs.handle(st.apply("live", 0));
        Thread.sleep(1500);
        cs.handle(st.apply("live", 1));
        Thread.sleep(1500);
        cs.handle(st.apply("over", 1));
        Thread.sleep(500);
        rc.shutdown();
        // Console captures from a USB drive: PS5 and Xbox layouts.
        Path usb = dir.resolve("usb");
        Path ps5 = Files.createDirectories(usb.resolve("PS5").resolve("CREATE").resolve("Video Clips").resolve("Call of Duty® Black Ops 6"));
        Path xbox = Files.createDirectories(usb.resolve("Xbox Captures"));
        Files.copy(red, ps5.resolve("Call of Duty® Black Ops 6_20250131201500.mp4"));
        Files.copy(red, xbox.resolve("Halo Infinite-2025_01_31-20_15_00.mp4"));
        for (Path f : List.of(ps5.resolve("Call of Duty® Black Ops 6_20250131201500.mp4"), xbox.resolve("Halo Infinite-2025_01_31-20_15_00.mp4")))
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Settings ws = new Settings();
        ws.watchFolders.add(usb.toString());
        var imported = net.evadeclient.clips.core.WatchFolders.scan(ws, lib);
        var again = net.evadeclient.clips.core.WatchFolders.scan(ws, lib);
        boolean psOk = imported.stream().anyMatch(x -> x.game.equals("Call of Duty® Black Ops 6") && x.tags.contains("ps5"));
        boolean xbOk = imported.stream().anyMatch(x -> x.game.equals("Halo Infinite") && x.tags.contains("xbox"));
        check(imported.size() == 2 && psOk && xbOk && again.isEmpty(), "console captures from a PS5 / Xbox USB drive import once, named after their game");

        check(rs.size() == 1 && rs.get(0).startsWith("Round 7 · won · 1K|") && Integer.parseInt(rs.get(0).split("\\|")[1]) >= 5
                && Integer.parseInt(rs.get(0).split("\\|")[1]) <= 6, "CS2 full-round clip covers the whole round (" + rs + ")");
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
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"title\":\"PS5 ace\",\"game\":\"Call of Duty\",\"size\":" + phoneBytes.length + "}")).build(),
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
        check(galleryDelete.statusCode() == 401 && pulled.size() == 1 && pulled.get(0).title.equals("PS5 ace") && pulled.get(0).game.equals("Call of Duty")
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
