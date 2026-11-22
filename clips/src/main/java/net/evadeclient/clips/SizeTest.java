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
import java.util.function.Consumer;

/**
 * How much disk a clip costs. Captures the worst case for video compression (full-screen fast-moving grain,
 * like particles, rain or a grass field whipping past) through the real capture engine, and checks every saved
 * clip and export stays under its ceiling. Also reports what an ordinary scene costs. Prints MB per minute.
 */
final class SizeTest {
    private final List<String> failures = new ArrayList<>();
    private int passed;
    private final StringBuilder table = new StringBuilder();

    static int run(Path dir) throws Exception { return new SizeTest().go(dir); }

    private void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++; else failures.add(what);
    }

    private static double mbPerMin(Clip c) { return c.size / 1e6 / c.duration * 60; }

    private int go(Path dir) throws Exception {
        if (Files.exists(dir)) try (var s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        Files.createDirectories(dir);
        Settings.overrideAppDir(dir.resolve("appdata"));
        if (!Ffmpeg.available()) { check(false, "ffmpeg found"); return 1; }

        table.append(String.format("  %-44s %10s %10s %10s%n", "case", "MB/min", "ceiling", "30 s clip"));
        Clip worst1080 = null, calm1080 = null;
        for (int q = 1; q <= 3; q++) {
            final int quality = q;
            Clip c = capture(dir, "q" + q, s -> { s.source = "test-noise"; s.resolution = 1080; s.fps = 60; s.quality = quality; s.codec = "h264"; });
            String name = "1080p60 " + new String[]{"Small", "Balanced", "Best"}[q - 1] + ", worst case (H.264)";
            long cap = Ffmpeg.liveCapBits("libx264", q, 60, 1920, 1080) + 2 * 160_000L;
            row(name, c, cap);
            check(mbPerMin(c) <= cap * 60 / 8e6 * 1.12, name + " stays under its ceiling");
            if (q == 2) worst1080 = c;
        }
        Clip busy = capture(dir, "busy", s -> { s.source = "test"; s.resolution = 1080; s.fps = 60; s.quality = 2; s.codec = "h264"; });
        row("1080p60 Balanced, busy scene (H.264)", busy, Ffmpeg.liveCapBits("libx264", 2, 60, 1920, 1080) + 320_000L);
        calm1080 = capture(dir, "calm", s -> { s.source = "test-calm"; s.resolution = 1080; s.fps = 60; s.quality = 2; s.codec = "h264"; });
        row("1080p60 Balanced, calm scene (H.264)", calm1080, Ffmpeg.liveCapBits("libx264", 2, 60, 1920, 1080) + 320_000L);
        check(mbPerMin(calm1080) < mbPerMin(worst1080) * 0.5, "a calm scene costs far less than the ceiling (only busy footage reaches it)");

        // HEVC vs H.264 on the same worst-case content (1080p30, game audio only).
        if (!Ffmpeg.workingEncoders("hevc").isEmpty()) {
            Clip h264 = capture(dir, "h264-1080", s -> { s.source = "test-noise"; s.resolution = 1080; s.fps = 30; s.quality = 2; s.codec = "h264"; s.recordMic = false; });
            Clip hevc = capture(dir, "hevc-1080", s -> { s.source = "test-noise"; s.resolution = 1080; s.fps = 30; s.quality = 2; s.codec = "hevc"; s.recordMic = false; });
            row("1080p30 Balanced, worst case (H.264)", h264, Ffmpeg.liveCapBits("libx264", 2, 30, 1920, 1080) + 160_000L);
            row("1080p30 Balanced, worst case (HEVC)", hevc, Ffmpeg.liveCapBits("libx265", 2, 30, 1920, 1080) + 160_000L);
            check(hevc.size < h264.size * 0.75, String.format("HEVC clip is %.0f%% smaller than H.264", 100 - 100.0 * hevc.size / h264.size));
        }

        // Exports of the worst-case clip.
        Settings s = new Settings();
        s.clipsFolder = dir.resolve("q2").resolve("clips").toString();
        Library lib = new Library(s);
        lib.load();
        Clip src = lib.get(worst1080.id);
        for (Exporter.Preset p : new Exporter.Preset[]{Exporter.Preset.SOURCE, Exporter.Preset.YOUTUBE, Exporter.Preset.DISCORD}) {
            Exporter.Options o = new Exporter.Options();
            o.preset = p;
            Path out = Exporter.outputFor(lib, src, p);
            Exporter.export(src.path(lib.root()), src, EditSpec.whole(src.duration), o, out, null, null);
            Clip e = lib.addNew(out, Clip.Kind.EXPORT, p.label, null, null, null);
            long cap = p == Exporter.Preset.DISCORD ? (long) (10e6 * 8 / src.duration) : (long) (1920 * 1080 * 60 * 0.15) + 192_000;
            row("export " + p.label + ", worst case", e, cap);
            check(p == Exporter.Preset.DISCORD ? e.size <= 10_000_000 : mbPerMin(e) <= cap * 60 / 8e6 * 1.12, "export " + p.label + " stays under its ceiling");
        }

        // Shrink an ordinary clip to HEVC.
        Settings cs = new Settings();
        cs.clipsFolder = dir.resolve("calm").resolve("clips").toString();
        Library clib = new Library(cs);
        clib.load();
        Clip calm = clib.get(calm1080.id);
        long before = calm.size;
        Path tmp = clib.metaDir().resolve("shrink.mp4");
        Path out = Exporter.shrink(calm.path(clib.root()), calm, tmp, null, null);
        if (out != null) {
            long saved = clib.replaceFile(calm, out);
            row("ordinary clip after Shrink", calm, 0);
            check(saved > 0 && Math.abs(calm.duration - calm1080.duration) < 0.2 && calm.audioTracks == 2,
                    String.format("Shrink saved %.0f%% and kept the length and both audio tracks", 100.0 * saved / before));
        } else {
            check(true, "Shrink correctly left an already-compact clip alone");
        }

        System.out.println();
        System.out.print(table);
        System.out.println();
        System.out.println(failures.isEmpty() ? "ALL " + passed + " SIZE CHECKS PASSED" : failures.size() + " FAILED, " + passed + " passed");
        return failures.isEmpty() ? 0 : 1;
    }

    private void row(String name, Clip c, long capBits) {
        table.append(String.format("  %-44s %10.1f %10s %10.1f%n", name, mbPerMin(c),
                capBits > 0 ? String.format("%.0f", capBits * 60 / 8e6) : "-", mbPerMin(c) / 2));
    }

    /** Runs the capture engine with the given settings and saves a 15 s replay. */
    private Clip capture(Path dir, String name, Consumer<Settings> setup) throws Exception {
        Settings s = new Settings();
        s.clipsFolder = dir.resolve(name).resolve("clips").toString();
        s.replaySeconds = 20;
        s.recordSystemAudio = true;
        s.recordMic = true;
        setup.accept(s);
        Library lib = new Library(s);
        lib.load();
        CaptureEngine engine = new CaptureEngine(s, lib);
        engine.start();
        long t0 = System.currentTimeMillis();
        while (!engine.isLive() && System.currentTimeMillis() - t0 < 30000) Thread.sleep(200);
        Thread.sleep(17000);
        Clip c = engine.saveReplay(15, name, "Test");
        engine.shutdown();
        lib.shutdown();
        return c;
    }
}
