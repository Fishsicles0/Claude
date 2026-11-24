package net.evadeclient.clips.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hands-free control: say "clip that" and the last moments are saved. Speech is recognised offline (Vosk, on
 * this PC; audio never leaves it) against a handful of fixed phrases only, so normal talking, callouts and game
 * sound don't trigger anything.
 * <p>
 * The engine (~30 MB of natives for every OS) and the model (~40 MB) are downloaded the first time voice
 * commands are turned on, so the app itself stays small. Both are checked against checksums pinned here before
 * they're used, and the model is unpacked to a temporary folder and only moved into place once it's complete.
 */
public final class VoiceCommands implements AutoCloseable {
    public enum Command { CLIP, RECORD_START, RECORD_STOP, MARKER }

    /**
     * Phrases the recogniser listens for; everything else is "[unk]" and ignored. Every phrase is two words: a
     * single word ("clip", "mark") is said in passing far too often, and game sound is easily heard as one.
     */
    static final String[][] PHRASES = {
            {"clip that", "CLIP"}, {"clip it", "CLIP"}, {"save clip", "CLIP"}, {"save that", "CLIP"},
            {"start recording", "RECORD_START"}, {"stop recording", "RECORD_STOP"},
            {"mark that", "MARKER"}};

    /** Every word the recogniser knows (the words of the phrases). */
    static final Set<String> WORDS = words();

    public static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    /** SHA-256 and size of {@link #MODEL_URL}. Alphacephei publishes no SHA-256, so it's pinned here. */
    static final String MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    static final long MODEL_SIZE = 41_205_931;
    /** Written into the model folder last, once it's completely in place. */
    static final String MODEL_MARKER = ".complete";
    /** Words below this confidence don't count. */
    static final double MIN_CONFIDENCE = 0.75;

    /** Vosk and JNA from Maven Central: URL, file name, pinned SHA-256, size. */
    static final String[][] LIBS = {
            {"https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar", "vosk-0.3.45.jar",
                    "9e38e96e4448a41d889bb254b2f8424945554945c6e77e1cd97a3d8633fac2ba", "25861916"},
            {"https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.7.0/jna-5.7.0.jar", "jna-5.7.0.jar",
                    "c34e3e002cca6e197379c105b554255cc0638101ed7a09ef00334dade0013621", "1686295"}};

    /** Gives up on connecting after this long. */
    static final int CONNECT_TIMEOUT_MS = 15_000;
    /** Gives up on a download that sends nothing for this long (it's retried, carrying on where it stopped). */
    static final int STALL_TIMEOUT_MS = 30_000;

    /** The speech engine (voice/VoskEngine), loaded from the downloaded libraries. Hands back Vosk's result JSON. */
    public interface Engine extends AutoCloseable {
        void recognize(InputStream pcm16k, Consumer<String> results) throws Exception;

        /**
         * Starts listening on the microphone called {@code micName} (blank: the system default), or moves to it
         * when listening on another one; asking for the one already in use just keeps listening. {@code onError}
         * gets, in words for the user, anything that goes wrong once listening (null: they're logged), including
         * a note when the microphone asked for couldn't be opened and the default one is used instead.
         *
         * @return false when the default microphone is used instead of the one asked for
         */
        boolean start(String micName, Consumer<String> results, Consumer<String> onError) throws Exception;

        void stop();
        boolean listening();
        /** The device being listened to, or null for the system default (or when not listening). */
        String microphone();
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

    /**
     * The model is completely unpacked. Installs from this version carry the {@link #MODEL_MARKER}; a folder from
     * an older version (unpacked in place, so possibly cut short) counts when every part of the model is there,
     * and gets the marker then. A folder given with -Dclips.voskModel is taken as it is.
     */
    public static boolean modelInstalled() {
        Path dir = modelDir();
        if (!nonEmpty(dir.resolve("am").resolve("final.mdl"))) return false;
        if (System.getProperty("clips.voskModel") != null || Files.isRegularFile(dir.resolve(MODEL_MARKER))) return true;
        if (!Files.isDirectory(dir.resolve("conf")) || !Files.isDirectory(dir.resolve("graph"))
                || !nonEmpty(dir.resolve("ivector").resolve("final.ie")) || !hasFst(dir.resolve("graph"))) return false;
        try { Files.writeString(dir.resolve(MODEL_MARKER), "migrated\n", StandardCharsets.UTF_8); }
        catch (IOException ignored) {} // still usable; checked again next time
        return true;
    }

    private static boolean nonEmpty(Path f) {
        try { return Files.isRegularFile(f) && Files.size(f) > 0; } catch (IOException e) { return false; }
    }

    private static boolean hasFst(Path graph) {
        try (Stream<Path> s = Files.list(graph)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".fst") && nonEmpty(p));
        } catch (IOException e) { return false; }
    }

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

    /**
     * Downloads the engine libraries that are missing, each checked against its pinned SHA-256 before it's moved
     * into place. Only one download runs at a time (Settings and auto-captions can both ask).
     */
    public static synchronized void downloadLibs(DoubleConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(libDir());
        long all = 0;
        for (String[] l : LIBS) all += Long.parseLong(l[3]);
        long before = 0;
        for (String[] l : LIBS) {
            long size = Long.parseLong(l[3]);
            Path target = libDir().resolve(l[1]);
            if (!Files.isRegularFile(target)) {
                long base = before, total = all;
                Path part = target.resolveSibling(l[1] + ".part");
                fetchVerified(l[0], part, l[2], size, l[1],
                        p -> { if (progress != null) progress.accept((base + p * size) / total); });
                move(part, target);
            }
            before += size;
            if (progress != null) progress.accept(before / (double) all);
        }
    }

    /** Downloads the speech model, checks it against {@link #MODEL_SHA256} and unpacks it (once). */
    public static synchronized void downloadModel(DoubleConsumer progress) throws IOException, InterruptedException {
        if (modelInstalled()) { if (progress != null) progress.accept(1); return; }
        Path target = modelDir().toAbsolutePath().normalize();
        Path voice = target.getParent();
        Files.createDirectories(voice);
        Path zip = voice.resolve(MODEL_NAME + ".zip.part");
        fetchVerified(MODEL_URL, zip, MODEL_SHA256, MODEL_SIZE, "the speech model", progress);
        unpackModel(zip, target);
        Files.deleteIfExists(zip);
        if (!modelInstalled()) throw new IOException("The speech model was unpacked, but parts of it are missing.");
    }

    /**
     * Downloads {@code url} into {@code part} and checks its SHA-256 (a mismatch deletes it and fails). Bytes left
     * from an earlier, interrupted try are resumed; if the result doesn't match, it's fetched once more from
     * scratch in case those were the bad part.
     */
    private static void fetchVerified(String url, Path part, String sha256, long size, String what, DoubleConsumer progress)
            throws IOException, InterruptedException {
        boolean hadPartial = Files.isRegularFile(part) && Files.size(part) > 0;
        for (int round = 0; round < (hadPartial ? 2 : 1); round++) {
            download(url, part, size, progress);
            if (sha256(part).equalsIgnoreCase(sha256)) return;
            Files.deleteIfExists(part);
        }
        throw new IOException("The download of " + what + " didn't match its checksum, so it wasn't installed. Try again later.");
    }

    /**
     * GET {@code url} into {@code part}, carrying on from what's already there when the server allows it. Retries
     * on rate limits (429), server hiccups (5xx), dropped connections and stalls (nothing received for
     * {@link #STALL_TIMEOUT_MS}): waits 2, 4, 8, 16 s between tries. A try that gets further than any before
     * doesn't count against the limit, so a slow, flaky line still finishes.
     */
    private static void download(String url, Path part, long size, DoubleConsumer progress) throws IOException, InterruptedException {
        IOException last = null;
        long furthest = Files.isRegularFile(part) ? Files.size(part) : 0;
        for (int failures = 0; failures < 5; ) {
            long have = Files.isRegularFile(part) ? Files.size(part) : 0;
            if (size > 0 && have > size) { Files.delete(part); have = 0; }
            if (size > 0 && have == size) return; // complete already (an earlier run stopped before checking it)
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            boolean retry = true;
            try {
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(STALL_TIMEOUT_MS); // per read: a stalled download gives up, a slow one doesn't
                c.setInstanceFollowRedirects(true);
                c.setUseCaches(false);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                boolean resume = have > 0 && code == 206 && rangeStartsAt(c.getHeaderField("Content-Range"), have);
                if (code == 416 || (code == 206 && !resume)) {
                    // The server doesn't agree with what we have: throw it away and start over.
                    Files.deleteIfExists(part);
                    last = new IOException("Download failed (HTTP " + code + ")");
                } else if (code != 200 && !resume) {
                    last = new IOException("Download failed (HTTP " + code + ")");
                    retry = code == 429 || code == 408 || code >= 500;
                } else {
                    long length = c.getContentLengthLong();
                    long done = resume ? have : 0;
                    long total = length >= 0 ? done + length : size;
                    try (InputStream in = c.getInputStream();
                         OutputStream out = resume ? Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                                 : Files.newOutputStream(part)) { // 200: the whole file, from the start
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (Thread.interrupted()) throw new InterruptedException();
                            out.write(buf, 0, n);
                            done += n;
                            if (progress != null && total > 0) progress.accept(Math.min(1, done / (double) total));
                        }
                    } finally {
                        long now = Files.isRegularFile(part) ? Files.size(part) : 0;
                        if (now > furthest) { furthest = now; failures = -1; } // got further: this try doesn't count
                    }
                    if (total <= 0 || done >= total) return;
                    last = new IOException("The download was cut off");
                }
            } catch (SocketTimeoutException e) {
                last = new IOException("The download stalled (nothing received for " + STALL_TIMEOUT_MS / 1000 + " s)", e);
            } catch (IOException e) {
                last = e;
            } finally {
                c.disconnect();
            }
            if (!retry) break;
            failures++;
            if (failures > 0 && failures < 5) Thread.sleep(2000L << (failures - 1));
        }
        throw last != null ? last : new IOException("Download failed");
    }

    /** A Content-Range header ("bytes 1000-1999/2000") starts where our partial file ends. */
    static boolean rangeStartsAt(String contentRange, long from) {
        if (contentRange == null) return false;
        String r = contentRange.trim().toLowerCase(Locale.ROOT);
        return r.startsWith("bytes " + from + "-");
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        try (InputStream in = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), md)) {
            byte[] buf = new byte[1 << 16];
            while (in.read(buf) >= 0) { /* digesting */ }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Unpacks the (already verified) model zip into voice/.tmp-extract, then moves the finished folder onto
     * {@code target} and writes the marker last. An interruption leaves either the old state or a temp folder
     * that the next try clears, never a half-unpacked model that looks installed.
     */
    private static void unpackModel(Path zip, Path target) throws IOException {
        Path voice = target.getParent();
        Path tmp = voice.resolve(".tmp-extract").toAbsolutePath().normalize();
        deleteTree(tmp);
        Files.createDirectories(tmp);
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path p = tmp.resolve(e.getName()).normalize();
                if (!p.startsWith(tmp)) throw new IOException("Bad entry in model zip: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(p);
                else { Files.createDirectories(p.getParent()); Files.copy(z, p, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        // The zip holds a MODEL_NAME/ folder; a zip with the files at the top works too.
        Path unpacked = nonEmpty(tmp.resolve(MODEL_NAME).resolve("am").resolve("final.mdl")) ? tmp.resolve(MODEL_NAME)
                : nonEmpty(tmp.resolve("am").resolve("final.mdl")) ? tmp : null;
        if (unpacked == null) { deleteTree(tmp); throw new IOException("The speech model download has an unexpected layout."); }
        Path old = null;
        if (Files.exists(target)) {
            old = voice.resolve(".old-" + target.getFileName());
            deleteTree(old);
            move(target, old);
        }
        try {
            move(unpacked, target);
        } catch (IOException ex) {
            if (old != null) try { move(old, target); } catch (IOException ignored) {}
            throw ex;
        }
        Files.writeString(target.resolve(MODEL_MARKER), MODEL_SHA256 + "\n", StandardCharsets.UTF_8);
        deleteTree(old);
        deleteTree(tmp);
    }

    /**
     * Renames {@code from} to {@code to} (replacing a file there), atomically where the file system can. Retried
     * briefly, since a virus scanner looking at fresh files can hold them for a moment on Windows.
     */
    private static void move(Path from, Path to) throws IOException {
        IOException last = null;
        for (int i = 0; i < 5; i++) {
            if (i > 0) try { Thread.sleep(200L * i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            try {
                try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch (IOException e) { last = e; }
        }
        throw last != null ? last : new IOException("Couldn't move " + from + " to " + to);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    // ------------------------------------------------------------------ engine

    static String grammar() {
        StringBuilder g = new StringBuilder("[");
        for (String[] p : PHRASES) g.append('"').append(p[0]).append("\",");
        return g.append("\"[unk]\"]").toString();
    }

    /** A recognizer for free speech (auto-captions), with word timings. Needs the engine and model downloaded. */
    public static Engine dictation() throws IOException {
        if (!modelInstalled()) throw new IOException("The speech model isn't downloaded yet.");
        return loadEngine(modelDir(), null);
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
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); } catch (RuntimeException e) { return null; }
        if (!o.has("result") || !o.get("result").isJsonArray()) return null;
        StringBuilder words = new StringBuilder();
        for (JsonElement w : o.getAsJsonArray("result")) {
            if (!w.isJsonObject() || !w.getAsJsonObject().has("word")) return null;
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
        // "clip that" / "mark that" where "that" was heard as another command word (the recogniser only knows
        // command words, so an accent can turn "that" into "it" or "mark"). Two words still: a lone "clip" or
        // "mark" never counts, and "clip recording" is more likely talk about recording than a command.
        String[] t = text.split(" ");
        if (t.length == 2 && WORDS.contains(t[1]) && !t[1].equals("recording")) {
            if (t[0].equals("clip")) return Command.CLIP;
            if (t[0].equals("mark")) return Command.MARKER;
        }
        return null;
    }

    private static Set<String> words() {
        Set<String> w = new LinkedHashSet<>();
        for (String[] p : PHRASES) for (String word : p[0].split(" ")) w.add(word);
        return Set.copyOf(w);
    }

    /** Recognises commands in 16 kHz mono 16-bit PCM (used by the tests). */
    public void recognize(InputStream pcm, Consumer<Command> onCommand) throws IOException {
        try {
            engine.recognize(pcm, json -> { Command c = parse(json); if (c != null) onCommand.accept(c); });
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
    }

    /**
     * Starts listening on the microphone called {@code micName} (its name as the system shows it; blank for the
     * default), or switches to it when already listening on another one. Calling it again with the same
     * microphone keeps listening and just takes the new callbacks.
     * <p>
     * {@code onError} gets messages meant for the user, from the listening thread: the microphone going quiet
     * (unplugged), the engine failing, a command's action failing, and a note when the chosen microphone
     * couldn't be opened and the default one is used instead. Null logs them instead.
     *
     * @return false when the default microphone is used instead of the one asked for
     * @throws Exception when no microphone could be opened at all (the message says why, e.g. "no microphone
     *                   was found."), or the engine failed
     */
    public boolean start(String micName, Consumer<Command> onCommand, Consumer<String> onError) throws Exception {
        return engine.start(micName, json -> { Command c = parse(json); if (c != null) onCommand.accept(c); }, onError);
    }

    /** {@link #start(String, Consumer, Consumer)} with problems logged rather than shown. */
    public boolean start(String micName, Consumer<Command> onCommand) throws Exception {
        return start(micName, onCommand, null);
    }

    /** The microphone being listened to, or null for the system default (or when not listening). */
    public String microphone() { return engine.microphone(); }

    public void stop() { engine.stop(); }
    public boolean listening() { return engine.listening(); }

    @Override public void close() { engine.close(); }
}
