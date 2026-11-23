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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and runs ffmpeg / ffprobe. Every recording, thumbnail, preview frame and export goes through here, so
 * the app works the same on Windows, macOS and Linux.
 */
public final class Ffmpeg {
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private static volatile String ffmpeg, ffprobe;

    private Ffmpeg() {}

    /** Points at a custom ffmpeg binary (from settings). Blank = auto-detect. */
    public static synchronized void configure(String customPath) {
        ffmpeg = ffprobe = null;
        // A different ffmpeg can have different encoders: probe again next time.
        encoderGeneration++;
        ENCODERS.clear();
        encoderListing = null;
        if (customPath != null && !customPath.isBlank()) {
            try {
                Path p = Path.of(customPath.trim().replaceAll("^\"+|\"+$", ""));
                if (Files.isDirectory(p)) p = p.resolve(exe("ffmpeg"));
                if (Files.isRegularFile(p)) {
                    ffmpeg = p.toString();
                    Path probe = p.resolveSibling(exe("ffprobe"));
                    ffprobe = Files.isRegularFile(probe) ? probe.toString() : null;
                }
            } catch (java.nio.file.InvalidPathException e) {
                System.err.println("[clips] ignoring the ffmpeg location setting: " + e.getMessage());
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

    /**
     * Looks for {@code name} next to a configured ffmpeg, next to the app, in the app-data folder (where the
     * first-run download goes), then on the PATH. The app's own copies are checked first, so a broken PATH (a
     * quoted or invalid entry, common on Windows) can never hide them.
     */
    private static String locate(String name) {
        try {
            List<Path> own = new ArrayList<>();
            String configured = ffmpeg;
            if (configured != null) {
                try { own.add(Path.of(configured).resolveSibling(exe(name))); } catch (java.nio.file.InvalidPathException ignored) {}
            }
            try {
                Path jar = Path.of(Ffmpeg.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
                if (jar != null) {
                    own.add(jar.resolve("ffmpeg").resolve(exe(name)));
                    own.add(jar.resolve("ffmpeg").resolve("bin").resolve(exe(name)));
                    own.add(jar.resolve(exe(name)));
                }
            } catch (Exception ignored) {}
            try {
                own.add(Settings.appDir().resolve("ffmpeg").resolve(exe(name)));
                own.add(Settings.appDir().resolve("ffmpeg").resolve("bin").resolve(exe(name)));
            } catch (RuntimeException ignored) {}
            for (Path c : own) if (runnable(c)) return c.toString();

            String path = System.getenv("PATH");
            if (path != null) for (String entry : path.split(java.io.File.pathSeparator)) {
                // Windows PATH entries are sometimes quoted ("C:\Program Files\x") or plain invalid; skip what can't be a folder.
                String dir = entry.trim().replaceAll("^\"+|\"+$", "").trim();
                if (dir.isEmpty()) continue;
                try {
                    Path c = Path.of(dir).resolve(exe(name));
                    if (runnable(c)) return c.toString();
                } catch (java.nio.file.InvalidPathException ignored) {}
            }
            // GUI apps on macOS get a minimal PATH without Homebrew's folders.
            if (MAC) for (String dir : List.of("/opt/homebrew/bin", "/usr/local/bin")) {
                Path c = Path.of(dir, name);
                if (runnable(c)) return c.toString();
            }
            return null;
        } catch (RuntimeException e) {
            System.err.println("[clips] looking for " + name + ": " + e);
            return null;
        }
    }

    private static boolean runnable(Path c) {
        try { return Files.isRegularFile(c) && Files.isExecutable(c); } catch (RuntimeException e) { return false; }
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

    /**
     * Runs a short check command with its output discarded and returns whether it exited with 0 within
     * {@code timeoutSec}. A command that hangs (a GPU driver that never answers) is killed and counts as failed.
     */
    static boolean runsOk(List<String> cmd, long timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return false;
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

    /** Hands every line of {@code in} to {@code sink} on a background thread (e.g. ffmpeg's -progress output). */
    static Thread drainLines(java.io.InputStream in, java.util.function.Consumer<String> sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try { sink.accept(line); } catch (RuntimeException ignored) {}
                }
            } catch (IOException ignored) {}
        }, "ffmpeg-progress");
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

    // ------------------------------------------------------------------ readable errors

    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[[^\\]]*@ ?(0x)?[0-9a-fA-F]+\\]\\s*");
    private static final Pattern INPUT_INDEX = Pattern.compile("\\[in#(\\d+)");
    private static final Pattern GPU_ENCODER = Pattern.compile("nvenc|_amf|\\bamf\\b|_qsv|\\bqsv\\b|\\bmfx|vaapi|\\bva-api|videotoolbox|\\bcuda\\b|nvcuda");
    private static final Pattern ERROR_WORDS = Pattern.compile("error|failed|cannot|can't|could not|couldn't|not supported|no capable|unable");
    private static final Pattern AUDIO_DEVICE = Pattern.compile("@default_monitor@|\\.monitor\\b|audio=|\\[dshow|\\[pulse|\\[wasapi|\\[alsa|\\bdshow\\b|\\bwasapi\\b|pa_context|pulseaudio|audio device|audio only device");

    /** {@link #explain(String, List)} without track names (an audio failure is reported as the desktop audio). */
    public static String explain(String log) { return explain(log, List.of()); }

    /**
     * Turns an ffmpeg log into one sentence a player can act on: a desktop-audio / mic device that won't open,
     * a GPU encoder that won't start, a missing window or display. Otherwise the last line that mentions an
     * error, else the last line. {@code audioTitles} are the capture's audio tracks in input order ("Game",
     * "Mic", "Voice chat"; input 0 is the video), used to name the device that failed.
     */
    public static String explain(String log, List<String> audioTitles) {
        if (log == null || log.isBlank()) return "";
        String[] lines = log.strip().split("\n");
        String lower = log.toLowerCase(Locale.ROOT);

        // Which input failed to open (newer ffmpeg says "[in#2 @ ...] Error opening input").
        int failedInput = -1;
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            if (!l.contains("error opening input")) continue;
            Matcher m = INPUT_INDEX.matcher(l);
            if (m.find()) failedInput = Integer.parseInt(m.group(1));
        }
        boolean audioFailed = failedInput >= 1;
        if (failedInput < 0) {
            // Older ffmpeg doesn't number its inputs: look for one line that names an audio device and an error
            // ("audio=Stereo Mix: I/O error", "[pulse @ ..] pa_context_connect() failed").
            for (String line : lines) {
                String l = line.toLowerCase(Locale.ROOT);
                if (AUDIO_DEVICE.matcher(l).find() && ERROR_WORDS.matcher(l).find()) { audioFailed = true; break; }
            }
        }
        if (audioFailed) {
            String track = failedInput >= 1 && failedInput - 1 < audioTitles.size() ? audioTitles.get(failedInput - 1)
                    : audioTitles.size() == 1 ? audioTitles.get(0) : "Game";
            return switch (track) {
                case "Mic" -> "Couldn't open the microphone. Pick another one on the Record page or turn off the mic.";
                case "Voice chat" -> "Couldn't open the voice-chat device. Pick another one on the Record page or turn off voice chat.";
                default -> "Couldn't open the desktop-audio device. Pick another one on the Record page or turn off desktop audio.";
            };
        }
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            if (GPU_ENCODER.matcher(l).find() && ERROR_WORDS.matcher(l).find())
                return "The GPU encoder failed to start. Switch the encoder to x264 (CPU) on the Record page.";
        }
        if (lower.contains("can't find window") || lower.contains("cannot find window"))
            return "The Minecraft window isn't open. Open Minecraft with Evade Client to record.";
        if (lower.contains("cannot open display") || lower.contains("can't open display"))
            return "Couldn't open the X11 display. Screen capture on Linux needs an X11 session (\"on Xorg\" at login).";
        if (lower.contains("no space left"))
            return "The disk is full. Free some space or move the clips folder in Settings.";

        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].toLowerCase(Locale.ROOT).contains("error")) return readable(lines[i]);
        }
        for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].isBlank()) return readable(lines[i]);
        return "";
    }

    /** "[x11grab @ 0x55d1] Cannot open display :0" → "Cannot open display :0". */
    private static String readable(String line) {
        String s = LOG_PREFIX.matcher(line.strip()).replaceFirst("");
        return s.isEmpty() ? line.strip() : s;
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
                // Phone videos are stored landscape with a rotation flag; ffmpeg autorotates them when decoding,
                // so report the size as they play (portrait), not as they're stored.
                if (Math.abs(rotation(s)) % 180 == 90) { int t = w; w = h; h = t; }
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

    /** A video stream's rotation in degrees: the display matrix side data, else the older "rotate" tag. */
    private static int rotation(JsonObject stream) {
        try {
            if (stream.has("side_data_list")) for (var el : stream.getAsJsonArray("side_data_list")) {
                JsonObject sd = el.getAsJsonObject();
                if (sd.has("rotation")) return (int) Math.round(parseD(sd.get("rotation").getAsString()));
            }
            if (stream.has("tags")) {
                JsonObject tags = stream.getAsJsonObject("tags");
                if (tags.has("rotate")) return (int) Math.round(parseD(tags.get("rotate").getAsString()));
            }
        } catch (RuntimeException ignored) {}
        return 0;
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

    /**
     * Probed encoder lists by codec ("h264", "hevc", and "+capture" for the live-capture lists). Probing has its
     * own lock, so ffmpeg() / ffprobe() and everything else never wait behind a slow GPU test encode.
     */
    private static final java.util.Map<String, List<String>> ENCODERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object ENCODER_PROBE = new Object();
    private static volatile int encoderGeneration;
    private static volatile String encoderListing;

    /** The render node VA-API capture encodes on (the first GPU). */
    static final String VAAPI_DEVICE = "/dev/dri/renderD128";

    /** H.264 encoders that actually work on this machine, best first (hardware before software). */
    public static List<String> workingEncoders() { return workingEncoders("h264"); }

    /**
     * Encoders for {@code codec} ("h264" or "hevc") that actually work here, hardware first. Hardware encoders
     * (NVENC, AMF, Quick Sync, VideoToolbox) are listed only if a real test encode succeeds. These are the
     * encoders exports can use; live capture can also use {@link #captureEncoders}.
     */
    public static List<String> workingEncoders(String codec) { return encoders(codec, false); }

    /**
     * Encoders live capture can use: {@link #workingEncoders} plus VA-API (AMD / Intel GPUs on Linux), which
     * needs frames uploaded to the GPU first. Only the capture pipeline does that, so exports never pick it.
     */
    public static List<String> captureEncoders(String codec) { return encoders(codec, true); }

    private static List<String> encoders(String codec, boolean capture) {
        String c = "hevc".equals(codec) ? "hevc" : "h264";
        String key = capture ? c + "+capture" : c;
        List<String> cached = ENCODERS.get(key);
        if (cached != null) return cached;
        synchronized (ENCODER_PROBE) {
            cached = ENCODERS.get(key);
            if (cached != null) return cached;
            int generation = encoderGeneration;
            String list = encoderListing();
            List<String> found = new ArrayList<>(capture ? encoders(c, false) : probeEncoders(c, list));
            if (capture && !WINDOWS && !MAC) {
                String vaapi = c + "_vaapi";
                if (list.contains(" " + vaapi + " ") && testVaapi(vaapi)) {
                    // After the other GPU encoders, before the CPU ones.
                    int at = 0;
                    while (at < found.size() && !found.get(at).startsWith("lib") && !found.get(at).equals("mpeg4")) at++;
                    found.add(at, vaapi);
                }
            }
            List<String> result = List.copyOf(found);
            // Don't keep a result from a failed listing (ffmpeg missing or hung), or one made for an ffmpeg that
            // was swapped meanwhile.
            if (!list.isBlank() && generation == encoderGeneration) ENCODERS.put(key, result);
            return result;
        }
    }

    /** {@code ffmpeg -encoders}, cached per ffmpeg. Empty when ffmpeg is missing or doesn't answer in 10 s. */
    private static String encoderListing() {
        String cached = encoderListing;
        if (cached != null) return cached;
        int generation = encoderGeneration;
        String exe = ffmpeg();
        if (exe == null) return "";
        String list = capture(List.of(exe, "-hide_banner", "-encoders"), 10);
        if (!list.isBlank() && generation == encoderGeneration) encoderListing = list;
        return list;
    }

    private static List<String> probeEncoders(String codec, String list) {
        List<String> found = new ArrayList<>();
        for (String hw : List.of("_nvenc", "_amf", "_qsv", "_videotoolbox")) {
            String enc = codec + hw;
            if (list.contains(" " + enc + " ") && testEncoder(enc)) found.add(enc);
        }
        String sw = codec.equals("hevc") ? "libx265" : "libx264";
        if (list.contains(" " + sw + " ")) found.add(sw);
        if (found.isEmpty() && codec.equals("h264")) found.add("mpeg4");
        return found;
    }

    /** HEVC only pays off live when a GPU does it; x265 on the CPU would cost frames while playing. */
    public static boolean hardwareHevc() { return hardwareHevc(false); }

    /** Like {@link #hardwareHevc()}, counting the capture-only GPU encoders ({@code capture}) too. */
    public static boolean hardwareHevc(boolean capture) {
        for (String e : capture ? captureEncoders("hevc") : workingEncoders("hevc")) if (!e.startsWith("lib")) return true;
        return false;
    }

    public static boolean isHevc(String enc) { return enc.startsWith("hevc") || enc.equals("libx265"); }

    /** VA-API encoders take GPU frames: the capture graph ends in hwupload and the device is set up front. */
    public static boolean isVaapi(String enc) { return enc != null && enc.endsWith("_vaapi"); }

    /** ffmpeg options that open the VA-API device and hand it to the filter graph (for hwupload). */
    static List<String> vaapiDeviceArgs() {
        return List.of("-init_hw_device", "vaapi=evadeva:" + VAAPI_DEVICE, "-filter_hw_device", "evadeva");
    }

    /** A listed hardware encoder can still fail (no GPU / driver), so encode a few frames to make sure. */
    private static boolean testEncoder(String enc) {
        String exe = ffmpeg();
        if (exe == null) return false;
        return runsOk(List.of(exe, "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                "-i", "color=black:s=320x240:r=30:d=0.2", "-c:v", enc, "-f", "null", "-"), 10);
    }

    /** VA-API test encode through the same device setup and upload the capture uses. */
    private static boolean testVaapi(String enc) {
        String exe = ffmpeg();
        if (exe == null || !Files.exists(Path.of(VAAPI_DEVICE))) return false;
        List<String> cmd = new ArrayList<>(List.of(exe, "-hide_banner", "-loglevel", "error"));
        cmd.addAll(vaapiDeviceArgs());
        cmd.addAll(List.of("-f", "lavfi", "-i", "color=black:s=320x240:r=30:d=0.2", "-vf", "format=nv12,hwupload",
                "-c:v", enc, "-f", "null", "-"));
        return runsOk(cmd, 10);
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
            case "_vaapi" -> "VA-API (GPU)" + codec;
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
            case "_vaapi" -> a.addAll(List.of("-rc_mode", "VBR", "-b:v", avg, "-maxrate", maxrate, "-bufsize", bufsize));
            default -> {
                if (enc.equals("libx264")) a.addAll(List.of("-preset", quality == 3 ? "veryfast" : "ultrafast", "-tune", "zerolatency",
                        "-crf", String.valueOf(new int[]{30, 24, 20}[q]), "-maxrate", maxrate, "-bufsize", bufsize));
                else if (enc.equals("libx265")) a.addAll(List.of("-preset", "ultrafast", "-tune", "zerolatency",
                        "-crf", String.valueOf(new int[]{32, 27, 23}[q]), "-maxrate", maxrate, "-bufsize", bufsize, "-x265-params", "log-level=error"));
                else a.addAll(List.of("-q:v", "4", "-maxrate", maxrate, "-bufsize", bufsize));
            }
        }
        a.addAll(List.of("-g", String.valueOf(gop), "-keyint_min", String.valueOf(gop),
                "-force_key_frames", "expr:gte(t,n_forced*1)"));
        // VA-API gets GPU frames (the capture graph ends in hwupload); a pixel format here would force a download.
        if (!isVaapi(enc)) a.addAll(List.of("-pix_fmt", "yuv420p"));
        return a;
    }
}
