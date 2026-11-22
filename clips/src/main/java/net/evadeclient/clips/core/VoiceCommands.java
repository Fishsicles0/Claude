package net.evadeclient.clips.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hands-free control: say "clip that" and the last moments are saved. Speech is recognised offline (Vosk, on
 * this PC; audio never leaves it) against a handful of fixed phrases only, so normal talking, callouts and game
 * sound don't trigger anything.
 * <p>
 * The engine (~30 MB of natives for every OS) and the model (~40 MB) are downloaded the first time voice
 * commands are turned on, so the app itself stays small.
 */
public final class VoiceCommands implements AutoCloseable {
    public enum Command { CLIP, RECORD_START, RECORD_STOP, MARKER }

    /** Phrases the recogniser listens for; everything else is "[unk]" and ignored. */
    static final String[][] PHRASES = {
            {"clip that", "CLIP"}, {"clip it", "CLIP"}, {"save clip", "CLIP"}, {"save that", "CLIP"},
            {"start recording", "RECORD_START"}, {"stop recording", "RECORD_STOP"},
            {"mark that", "MARKER"}, {"marker", "MARKER"}, {"clip", "CLIP"}, {"mark", "MARKER"}};

    public static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    /** Words below this confidence don't count. */
    static final double MIN_CONFIDENCE = 0.75;

    /** Vosk and JNA from Maven Central, verified against Maven's SHA-1. */
    static final String[][] LIBS = {
            {"https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar", "vosk-0.3.45.jar"},
            {"https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.7.0/jna-5.7.0.jar", "jna-5.7.0.jar"}};

    /** The speech engine (voice/VoskEngine), loaded from the downloaded libraries. Hands back Vosk's result JSON. */
    public interface Engine extends AutoCloseable {
        void recognize(InputStream pcm16k, Consumer<String> results) throws Exception;
        void start(String micName, Consumer<String> results) throws Exception;
        void stop();
        boolean listening();
        @Override void close();
    }

    private final Engine engine;

    public VoiceCommands(Path modelDir) throws IOException {
        engine = loadEngine(modelDir, grammar());
    }

    // ------------------------------------------------------------------ downloads

    /** Where the model lives, or the folder given with -Dclips.voskModel. */
    public static Path modelDir() {
        String override = System.getProperty("clips.voskModel");
        if (override != null) return Path.of(override);
        return Settings.appDir().resolve("voice").resolve(MODEL_NAME);
    }

    public static Path libDir() {
        String o = System.getProperty("clips.voiceLibs");
        return o != null ? Path.of(o) : Settings.appDir().resolve("voice").resolve("lib");
    }

    public static boolean modelInstalled() { return Files.isRegularFile(modelDir().resolve("am").resolve("final.mdl")); }

    public static boolean libsInstalled() {
        for (String[] l : LIBS) if (!Files.isRegularFile(libDir().resolve(l[1]))) return false;
        return true;
    }

    /** Everything voice needs is on disk. */
    public static boolean ready() { return libsInstalled() && modelInstalled(); }

    /** Downloads whatever is missing: engine libraries, then the model. */
    public static void downloadAll(DoubleConsumer progress) throws IOException, InterruptedException {
        if (!libsInstalled()) downloadLibs(p -> { if (progress != null) progress.accept(p * 0.4); });
        if (!modelInstalled()) downloadModel(p -> { if (progress != null) progress.accept(0.4 + p * 0.6); });
    }

    public static void downloadLibs(DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(libDir());
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (int i = 0; i < LIBS.length; i++) {
            Path target = libDir().resolve(LIBS[i][1]);
            if (Files.isRegularFile(target)) continue;
            String sha1 = new String(fetch(http, LIBS[i][0] + ".sha1")).trim().split("\\s+")[0];
            byte[] body = fetch(http, LIBS[i][0]);
            String got;
            try { got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body)); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
            if (!got.equalsIgnoreCase(sha1)) throw new IOException("Checksum mismatch for " + LIBS[i][1]);
            Path part = target.resolveSibling(LIBS[i][1] + ".part");
            Files.write(part, body);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            if (progress != null) progress.accept((i + 1) / (double) LIBS.length);
        }
    }

    /** GET with retries (servers rate-limit with 429 / hiccup with 5xx): waits 2, 4, 8, 16 s between tries. */
    private static byte[] fetch(HttpClient http, String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (attempt > 0) Thread.sleep(2000L << (attempt - 1));
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) return r.body();
                last = new IOException("Download failed (HTTP " + r.statusCode() + ")");
                if (r.statusCode() != 429 && r.statusCode() < 500) break;
            } catch (IOException e) { last = e; }
        }
        throw last;
    }

    /** Downloads and unpacks the speech model (once). */
    public static void downloadModel(DoubleConsumer progress) throws IOException, InterruptedException {
        Path target = modelDir();
        Files.createDirectories(target.getParent());
        Path zip = target.getParent().resolve(MODEL_NAME + ".zip.part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(MODEL_URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("Download failed (HTTP " + r.statusCode() + ")");
        long total = r.headers().firstValueAsLong("content-length").orElse(-1);
        try (InputStream in = r.body(); var out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null && total > 0) progress.accept(done / (double) total);
            }
        }
        Path root = target.getParent().toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path p = root.resolve(e.getName()).normalize();
                if (!p.startsWith(root)) throw new IOException("Bad entry in model zip: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(p);
                else { Files.createDirectories(p.getParent()); Files.copy(z, p, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        Files.deleteIfExists(zip);
        if (!modelInstalled()) throw new IOException("Model unpacked to an unexpected place");
    }

    // ------------------------------------------------------------------ engine

    static String grammar() {
        StringBuilder g = new StringBuilder("[");
        for (String[] p : PHRASES) g.append('"').append(p[0]).append("\",");
        return g.append("\"[unk]\"]").toString();
    }

    private static Engine loadEngine(Path modelDir, String grammar) throws IOException {
        if (!libsInstalled()) throw new IOException("The speech engine isn't downloaded yet.");
        try {
            List<URL> urls = new ArrayList<>();
            for (String[] l : LIBS) urls.add(libDir().resolve(l[1]).toUri().toURL());
            urls.add(VoiceCommands.class.getProtectionDomain().getCodeSource().getLocation());
            ClassLoader loader = new ChildFirst(urls.toArray(new URL[0]), VoiceCommands.class.getClassLoader());
            Class<?> c = Class.forName("net.evadeclient.clips.voice.VoskEngine", true, loader);
            return (Engine) c.getConstructor(String.class, String.class).newInstance(modelDir.toString(), grammar);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Speech engine failed to start: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException | java.net.MalformedURLException e) {
            throw new IOException("Speech engine failed to load: " + e, e);
        }
    }

    /** Loads the engine and its libraries itself; everything else (the Engine interface, Java) from the app. */
    private static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!(name.startsWith("net.evadeclient.clips.voice.") || name.startsWith("org.vosk.") || name.startsWith("com.sun.jna.")))
                return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    /**
     * The command in a Vosk result JSON, or null. The utterance must be just the command: a short noise blip
     * before or after it (breath, click) is fine, but real speech around it ("can you clip my nails later")
     * means it was conversation, not a command.
     */
    static Command parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (!o.has("result")) return null;
        StringBuilder words = new StringBuilder();
        for (JsonElement w : o.getAsJsonArray("result")) {
            JsonObject wo = w.getAsJsonObject();
            String word = wo.get("word").getAsString();
            double len = wo.has("end") && wo.has("start") ? wo.get("end").getAsDouble() - wo.get("start").getAsDouble() : 1;
            if (word.equals("[unk]")) {
                if (len > 0.2) return null; // talking around the phrase
                continue;
            }
            if (wo.has("conf") && wo.get("conf").getAsDouble() < MIN_CONFIDENCE) return null;
            if (words.length() > 0) words.append(' ');
            words.append(word.toLowerCase(Locale.ROOT));
        }
        String text = words.toString();
        for (String[] p : PHRASES) if (text.equals(p[0])) return Command.valueOf(p[1]);
        // "clip that" / "mark that" where "that" was heard as some other command word (the recogniser only
        // knows command words, so an accent can turn "that" into "mark").
        String[] t = text.split(" ");
        if (t.length == 2 && t[0].equals("clip")) return Command.CLIP;
        if (t.length == 2 && t[0].equals("mark")) return Command.MARKER;
        return null;
    }

    /** Recognises commands in 16 kHz mono 16-bit PCM (used by the tests). */
    public void recognize(InputStream pcm, Consumer<Command> onCommand) throws IOException {
        try {
            engine.recognize(pcm, json -> { Command c = parse(json); if (c != null) onCommand.accept(c); });
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
    }

    /** Starts listening on the microphone (default, or the first whose name contains {@code micName}). */
    public void start(String micName, Consumer<Command> onCommand) throws Exception {
        engine.start(micName, json -> { Command c = parse(json); if (c != null) onCommand.accept(c); });
    }

    public void stop() { engine.stop(); }
    public boolean listening() { return engine.listening(); }

    @Override public void close() { engine.close(); }
}
