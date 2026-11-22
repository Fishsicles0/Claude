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
        lib.shutdown();
        return finish();
    }

    /** Game events → clips, with recorded-style payloads from each game. */
    private void eventTests() throws Exception {
        List<String> saved = new java.util.concurrent.CopyOnWriteArrayList<>();
        var clipper = new net.evadeclient.clips.events.AutoClipper((sec, title, game, tags) -> saved.add(game + "|" + title + "|" + tags + "|" + sec));
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
                + "\"player\":{\"steamid\":\"" + me + "\",\"team\":\"CT\",\"state\":{\"round_kills\":0,\"round_killhs\":0},\"match_stats\":{\"mvps\":1}}}";
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
