package net.evadeclient.clips.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

/**
 * Finds and runs ffmpeg / ffprobe. Every recording, thumbnail, preview frame and export goes through here, so
 * the app works the same on Windows, macOS and Linux.
 */
public final class Ffmpeg {
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private static volatile String ffmpeg, ffprobe;
    private static volatile List<String> encoders;

    private Ffmpeg() {}

    /** Points at a custom ffmpeg binary (from settings). Blank = auto-detect. */
    public static synchronized void configure(String customPath) {
        ffmpeg = ffprobe = null;
        encoders = null;
        if (customPath != null && !customPath.isBlank()) {
            Path p = Path.of(customPath.trim());
            if (Files.isDirectory(p)) p = p.resolve(exe("ffmpeg"));
            if (Files.isRegularFile(p)) {
                ffmpeg = p.toString();
                Path probe = p.resolveSibling(exe("ffprobe"));
                ffprobe = Files.isRegularFile(probe) ? probe.toString() : null;
            }
        }
    }

    private static String exe(String name) { return WINDOWS ? name + ".exe" : name; }

    /** Path to ffmpeg, or null when none can be found. */
    public static synchronized String ffmpeg() {
        if (ffmpeg == null) ffmpeg = locate("ffmpeg");
        return ffmpeg;
    }

    public static synchronized String ffprobe() {
        if (ffprobe == null) ffprobe = locate("ffprobe");
        return ffprobe;
    }

    public static boolean available() { return ffmpeg() != null && ffprobe() != null; }

    private static String locate(String name) {
        List<Path> candidates = new ArrayList<>();
        // Next to a configured ffmpeg, next to the app, then in the app-data folder.
        if (ffmpeg != null) candidates.add(Path.of(ffmpeg).resolveSibling(exe(name)));
        try {
            Path jar = Path.of(Ffmpeg.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            if (jar != null) {
                candidates.add(jar.resolve("ffmpeg").resolve(exe(name)));
                candidates.add(jar.resolve("ffmpeg").resolve("bin").resolve(exe(name)));
                candidates.add(jar.resolve(exe(name)));
            }
        } catch (Exception ignored) {}
        candidates.add(Settings.appDir().resolve("ffmpeg").resolve(exe(name)));
        candidates.add(Settings.appDir().resolve("ffmpeg").resolve("bin").resolve(exe(name)));
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) candidates.add(Path.of(dir).resolve(exe(name)));
        }
        if (MAC) { candidates.add(Path.of("/opt/homebrew/bin", name)); candidates.add(Path.of("/usr/local/bin", name)); }
        for (Path c : candidates) if (Files.isRegularFile(c) && Files.isExecutable(c)) return c.toString();
        return null;
    }

    // ------------------------------------------------------------------ first-run download (Windows)

    /** Closes the stream if no bytes arrive for stallMs, so a dead connection fails instead of hanging forever. */
    static java.io.InputStream stallGuard(java.io.InputStream in, long stallMs) {
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        Thread watch = new Thread(() -> {
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                if (System.currentTimeMillis() - last.get() > stallMs) {
                    try { in.close(); } catch (Exception ignored) {}
                    return;
                }
            }
        }, "ffmpeg-download-stall-watch");
        watch.setDaemon(true);
        watch.start();
        return new java.io.FilterInputStream(in) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                last.set(System.currentTimeMillis());
                return n;
            }
            @Override public void close() throws IOException {
                watch.interrupt();
                super.close();
            }
        };
    }

    static final String FFMPEG_ZIP = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

    /**
     * Downloads ffmpeg + ffprobe (Windows builds from gyan.dev, checked against their published SHA-256) into the
     * app folder, where {@link #ffmpeg()} looks for them. About 90 MB.
     */
    public static void downloadForWindows(DoubleConsumer progress) throws IOException, InterruptedException {
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(15)).build();
        String expected = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(FFMPEG_ZIP + ".sha256")).timeout(java.time.Duration.ofSeconds(30)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body().trim().split("\\s+")[0];
        Path bin = Settings.appDir().resolve("ffmpeg").resolve("bin");
        Files.createDirectories(bin);
        Path zip = bin.resolveSibling("ffmpeg-download.zip");
        var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(FFMPEG_ZIP)).timeout(java.time.Duration.ofSeconds(60)).build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("Download failed (HTTP " + res.statusCode() + ")");
        long total = res.headers().firstValueAsLong("content-length").orElse(-1);
        java.security.MessageDigest md;
        try { md = java.security.MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        try (var in = stallGuard(res.body(), 30_000); var out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (progress != null && total > 0) progress.accept(done / (double) total);
            }
        }
        if (!java.util.HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(expected)) {
            Files.deleteIfExists(zip);
            throw new IOException("The ffmpeg download didn't match its checksum. Try again.");
        }
        int found = 0;
        try (var z = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String file = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (!e.isDirectory() && (file.equals("ffmpeg.exe") || file.equals("ffprobe.exe"))) {
                    Files.copy(z, bin.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    found++;
                }
            }
        }
        Files.deleteIfExists(zip);
        if (found < 2) throw new IOException("The ffmpeg download didn't contain ffmpeg.exe and ffprobe.exe");
        configure("");
    }

    // ------------------------------------------------------------------ running

    public static ProcessBuilder builder(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.addAll(args);
        return new ProcessBuilder(cmd);
    }

    /** Like {@link #builder} but keeps stdin open so the process can be stopped cleanly by sending "q". */
    public static ProcessBuilder interactiveBuilder(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.add("-hide_banner");
        cmd.addAll(args);
        return new ProcessBuilder(cmd);
    }

    /** Runs ffmpeg to completion. Throws with the tail of its log on failure. */
    public static void run(List<String> args) throws IOException { run(args, -1, null); }

    /**
     * Runs ffmpeg, reporting progress 0..1 based on {@code durationSec} (pass -1 to skip). The callback runs on a
     * background thread.
     */
    public static void run(List<String> args, double durationSec, DoubleConsumer progress) throws IOException {
        run(args, durationSec, progress, null);
    }

    /** A cancellable long-running ffmpeg job (exports). */
    public static final class Job {
        private volatile Process process;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            Process p = process;
            if (p != null) p.destroyForcibly();
        }

        public boolean cancelled() { return cancelled; }
    }

    public static void run(List<String> args, double durationSec, DoubleConsumer progress, Job job) throws IOException {
        if (job != null && job.cancelled) throw new IOException("Cancelled");
        List<String> full = new ArrayList<>(List.of("-y", "-progress", "pipe:1", "-nostats", "-loglevel", "error"));
        full.addAll(args);
        Process p = builder(full).start();
        if (job != null) {
            job.process = p;
            if (job.cancelled) p.destroyForcibly();
        }
        StringBuilder err = new StringBuilder();
        Thread errReader = drain(p.getErrorStream(), err);
        // Watchdog: ffmpeg can hang after an encoder error instead of exiting. -progress prints every ~0.5 s,
        // so 90 s of silence means it's stuck.
        long[] lastOutput = {System.currentTimeMillis()};
        Thread dog = new Thread(() -> {
            while (p.isAlive()) {
                if (System.currentTimeMillis() - lastOutput[0] > 90_000) { p.destroyForcibly(); break; }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }, "ffmpeg-watchdog");
        dog.setDaemon(true);
        dog.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastOutput[0] = System.currentTimeMillis();
                if (progress != null && durationSec > 0 && line.startsWith("out_time_us=")) {
                    try {
                        double t = Long.parseLong(line.substring(12).trim()) / 1e6;
                        progress.accept(Math.max(0, Math.min(1, t / durationSec)));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        int code;
        try { code = p.waitFor(); errReader.join(2000); } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled");
        }
        if (job != null && job.cancelled) throw new IOException("Cancelled");
        if (code != 0) throw new IOException("ffmpeg failed: " + tail(err.toString()));
        if (progress != null) progress.accept(1);
    }

    /** Runs a command and returns stdout+stderr combined (for device lists and version checks). */
    public static String capture(List<String> cmd, long timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            Thread t = drain(p.getInputStream(), out);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
            t.join(1000);
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static Thread drain(java.io.InputStream in, StringBuilder into) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (into) {
                        into.append(line).append('\n');
                        if (into.length() > 64_000) into.delete(0, into.length() - 32_000);
                    }
                }
            } catch (IOException ignored) {}
        }, "ffmpeg-log");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static String tail(String log) {
        String[] lines = log.strip().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) sb.append(lines[i].strip()).append('\n');
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ probing

    /** What ffprobe says about a video file. */
    public record MediaInfo(double duration, int width, int height, double fps, boolean hasAudio, boolean hasVideo, int audioTracks, List<String> trackNames) {}

    public static MediaInfo probe(Path file) throws IOException {
        String out = capture(List.of(ffprobe(), "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                file.toString()), 20);
        JsonObject root;
        try { root = JsonParser.parseString(out.substring(Math.max(0, out.indexOf('{')))).getAsJsonObject(); }
        catch (Exception e) { throw new IOException("Not a video file: " + file.getFileName()); }
        double dur = 0;
        int w = 0, h = 0;
        double fps = 0;
        boolean audio = false, video = false;
        int audioTracks = 0;
        List<String> trackNames = new ArrayList<>();
        if (root.has("format") && root.getAsJsonObject("format").has("duration"))
            dur = parseD(root.getAsJsonObject("format").get("duration").getAsString());
        if (root.has("streams")) for (var el : root.getAsJsonArray("streams")) {
            JsonObject s = el.getAsJsonObject();
            String type = s.has("codec_type") ? s.get("codec_type").getAsString() : "";
            if (type.equals("video") && !video) {
                if (s.has("disposition") && s.getAsJsonObject("disposition").has("attached_pic")
                        && s.getAsJsonObject("disposition").get("attached_pic").getAsInt() == 1) continue;
                video = true;
                w = s.has("width") ? s.get("width").getAsInt() : 0;
                h = s.has("height") ? s.get("height").getAsInt() : 0;
                fps = s.has("avg_frame_rate") ? ratio(s.get("avg_frame_rate").getAsString()) : 0;
                if (fps <= 0 && s.has("r_frame_rate")) fps = ratio(s.get("r_frame_rate").getAsString());
                if (dur <= 0 && s.has("duration")) dur = parseD(s.get("duration").getAsString());
            } else if (type.equals("audio")) {
                audio = true;
                audioTracks++;
                JsonObject tags = s.has("tags") ? s.getAsJsonObject("tags") : new JsonObject();
                String title = tags.has("title") ? tags.get("title").getAsString()
                        : tags.has("handler_name") && !tags.get("handler_name").getAsString().startsWith("Sound") ? tags.get("handler_name").getAsString() : "";
                trackNames.add(title);
            }
        }
        if (!video && !audio) throw new IOException("No video in " + file.getFileName());
        return new MediaInfo(dur, w, h, fps > 0 && fps < 1000 ? fps : 30, audio, video, audioTracks, trackNames);
    }

    private static double parseD(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }

    private static double ratio(String r) {
        try {
            String[] p = r.split("/");
            double d = p.length > 1 ? Double.parseDouble(p[1]) : 1;
            return d == 0 ? 0 : Double.parseDouble(p[0]) / d;
        } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ encoders

    private static final java.util.Map<String, List<String>> ENCODERS = new java.util.HashMap<>();

    /** H.264 encoders that actually work on this machine, best first (hardware before software). */
    public static List<String> workingEncoders() { return workingEncoders("h264"); }

    /**
     * Encoders for {@code codec} ("h264" or "hevc") that actually work here, hardware first. Hardware encoders
     * (NVENC, AMF, Quick Sync, VideoToolbox) are listed only if a real test encode succeeds.
     */
    public static synchronized List<String> workingEncoders(String codec) {
        List<String> cached = ENCODERS.get(codec);
        if (cached != null) return cached;
        String prefix = codec.equals("hevc") ? "hevc" : "h264";
        List<String> found = new ArrayList<>();
        String list = capture(List.of(ffmpeg(), "-hide_banner", "-encoders"), 15);
        for (String hw : List.of("_nvenc", "_amf", "_qsv", "_videotoolbox")) {
            String enc = prefix + hw;
            if (list.contains(" " + enc + " ") && testEncoder(enc)) found.add(enc);
        }
        String sw = codec.equals("hevc") ? "libx265" : "libx264";
        if (list.contains(" " + sw + " ")) found.add(sw);
        if (found.isEmpty() && codec.equals("h264")) found.add("mpeg4");
        ENCODERS.put(codec, found);
        return found;
    }

    /** HEVC only pays off live when a GPU does it; x265 on the CPU would cost frames while playing. */
    public static boolean hardwareHevc() {
        for (String e : workingEncoders("hevc")) if (!e.startsWith("lib")) return true;
        return false;
    }

    public static boolean isHevc(String enc) { return enc.startsWith("hevc") || enc.equals("libx265"); }

    /** A listed hardware encoder can still fail (no GPU / driver), so encode a few frames to make sure. */
    private static boolean testEncoder(String enc) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg(), "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                "-i", "color=black:s=320x240:r=30:d=0.2"));
        cmd.addAll(List.of("-c:v", enc, "-f", "null", "-"));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    public static String bestEncoder() { return workingEncoders().get(0); }

    public static String encoderLabel(String enc) {
        String codec = isHevc(enc) ? " · HEVC" : "";
        String base = enc.replaceFirst("^(h264|hevc)", "");
        return switch (base) {
            case "_nvenc" -> "NVIDIA NVENC (GPU)" + codec;
            case "_amf" -> "AMD AMF (GPU)" + codec;
            case "_qsv" -> "Intel Quick Sync (GPU)" + codec;
            case "_videotoolbox" -> "Apple VideoToolbox (GPU)" + codec;
            default -> enc.equals("libx264") ? "x264 (CPU)" : enc.equals("libx265") ? "x265 (CPU) · HEVC" : enc;
        };
    }

    /** Bits per pixel per frame at each quality level; HEVC needs ~40% less for the same look. */
    private static final double[] BPP = {0.04, 0.07, 0.12};

    /**
     * Hard ceiling (bits/s) for live capture. Without one, fast motion, grass, particles or smoke can make a
     * quality-based encoder spend 200+ Mbit/s; with it a minute of 1080p60 "Balanced" stays around 65 MB
     * (H.264) or 40 MB (HEVC) however busy the scene.
     */
    public static long liveCapBits(String enc, int quality, int fps, int w, int h) {
        double bits = (double) w * h * fps * BPP[Math.max(1, Math.min(3, quality)) - 1];
        if (isHevc(enc)) bits *= 0.6;
        return Math.max(1_000_000, Math.round(bits));
    }

    /**
     * Encoder arguments for live capture at quality 1 (smallest) .. 3 (best). Quality-targeted (small files on
     * calm scenes) with a peak cap (bounded files on busy ones). Hardware presets are low-latency, so encoding
     * runs on the GPU's video engine and doesn't take frames from the game.
     */
    public static List<String> liveEncoderArgs(String enc, int quality, int fps, int w, int h) {
        return liveEncoderArgs(enc, quality, fps, w, h, 0);
    }

    /** Like above with a fixed bitrate ceiling ({@code capOverride} bits/s, 0 = from the quality level). */
    public static List<String> liveEncoderArgs(String enc, int quality, int fps, int w, int h, long capOverride) {
        int gop = Math.max(1, fps); // one keyframe a second, so replays can be cut on any second
        long cap = capOverride > 0 ? capOverride : liveCapBits(enc, quality, fps, w, h);
        String maxrate = cap / 1000 + "k", bufsize = cap / 1000 + "k", avg = cap * 6 / 10 / 1000 + "k";
        int q = Math.max(1, Math.min(3, quality)) - 1;
        List<String> a = new ArrayList<>(List.of("-c:v", enc));
        String base = enc.replaceFirst("^(h264|hevc)", "");
        switch (base) {
            case "_nvenc" -> a.addAll(List.of("-preset", "p4", "-tune", "ll", "-rc", "vbr", "-cq", String.valueOf(new int[]{30, 24, 19}[q]),
                    "-b:v", avg, "-maxrate", maxrate, "-bufsize", bufsize));
            case "_amf" -> a.addAll(List.of("-quality", "speed", "-rc", "vbr_peak", "-b:v", avg, "-maxrate", maxrate, "-bufsize", bufsize));
            case "_qsv" -> a.addAll(List.of("-preset", "veryfast", "-b:v", avg, "-maxrate", maxrate, "-bufsize", bufsize));
            case "_videotoolbox" -> a.addAll(List.of("-realtime", "1", "-b:v", avg, "-maxrate", maxrate, "-bufsize", bufsize));
            default -> {
                if (enc.equals("libx264")) a.addAll(List.of("-preset", quality == 3 ? "veryfast" : "ultrafast", "-tune", "zerolatency",
                        "-crf", String.valueOf(new int[]{30, 24, 20}[q]), "-maxrate", maxrate, "-bufsize", bufsize));
                else if (enc.equals("libx265")) a.addAll(List.of("-preset", "ultrafast", "-tune", "zerolatency",
                        "-crf", String.valueOf(new int[]{32, 27, 23}[q]), "-maxrate", maxrate, "-bufsize", bufsize, "-x265-params", "log-level=error"));
                else a.addAll(List.of("-q:v", "4", "-maxrate", maxrate, "-bufsize", bufsize));
            }
        }
        a.addAll(List.of("-g", String.valueOf(gop), "-keyint_min", String.valueOf(gop),
                "-force_key_frames", "expr:gte(t,n_forced*1)", "-pix_fmt", "yuv420p"));
        return a;
    }
}
