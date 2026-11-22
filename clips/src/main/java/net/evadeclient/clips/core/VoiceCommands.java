package net.evadeclient.clips.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Hands-free control: say "clip that" and the last moments are saved. Speech is recognised offline (Vosk, on
 * this PC; audio never leaves it) against a handful of fixed phrases only, so normal talking, callouts and game
 * sound don't trigger anything.
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
    private static final float RATE = 16000;
    /** Words below this confidence don't count. */
    static final double MIN_CONFIDENCE = 0.75;

    private final Model model;
    private volatile boolean running;
    private Thread thread;

    public VoiceCommands(Path modelDir) throws IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        model = new Model(modelDir.toString());
    }

    /** Where the model lives (downloaded once, ~40 MB), or the folder given with -Dclips.voskModel. */
    public static Path modelDir() {
        String override = System.getProperty("clips.voskModel");
        if (override != null) return Path.of(override);
        return Settings.appDir().resolve("voice").resolve(MODEL_NAME);
    }

    public static boolean modelInstalled() { return Files.isRegularFile(modelDir().resolve("am").resolve("final.mdl")); }

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

    private Recognizer recognizer() throws IOException {
        StringBuilder grammar = new StringBuilder("[");
        for (String[] p : PHRASES) grammar.append('"').append(p[0]).append("\",");
        grammar.append("\"[unk]\"]");
        Recognizer r = new Recognizer(model, RATE, grammar.toString());
        r.setWords(true);
        return r;
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

    /** Recognises commands in 16 kHz mono 16-bit PCM (used by the live listener and the tests). */
    public void recognize(InputStream pcm, Consumer<Command> onCommand) throws IOException {
        try (Recognizer r = recognizer()) {
            byte[] buf = new byte[3200]; // 100 ms
            int n;
            while ((n = pcm.read(buf)) > 0) {
                if (r.acceptWaveForm(buf, n)) {
                    Command c = parse(r.getResult());
                    if (c != null) onCommand.accept(c);
                }
            }
            Command c = parse(r.getFinalResult());
            if (c != null) onCommand.accept(c);
        }
    }

    /** Starts listening on the microphone (default, or the first whose name contains {@code micName}). */
    public synchronized void start(String micName, Consumer<Command> onCommand) throws Exception {
        if (running) return;
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        TargetDataLine line = openMic(fmt, micName);
        line.start();
        running = true;
        thread = new Thread(() -> {
            try (Recognizer r = recognizer()) {
                byte[] buf = new byte[3200];
                while (running) {
                    int n = line.read(buf, 0, buf.length);
                    if (n > 0 && r.acceptWaveForm(buf, n)) {
                        Command c = parse(r.getResult());
                        if (c != null) onCommand.accept(c);
                    }
                }
            } catch (Exception e) {
                System.err.println("[clips] voice commands stopped: " + e.getMessage());
            } finally {
                line.stop();
                line.close();
                running = false;
            }
        }, "voice-commands");
        thread.setDaemon(true);
        thread.start();
    }

    private static TargetDataLine openMic(AudioFormat fmt, String micName) throws Exception {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        if (micName != null && !micName.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().toLowerCase(Locale.ROOT).contains(micName.toLowerCase(Locale.ROOT))) continue;
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(info)) {
                    TargetDataLine l = (TargetDataLine) m.getLine(info);
                    l.open(fmt);
                    return l;
                }
            }
        }
        TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
        l.open(fmt);
        return l;
    }

    public synchronized void stop() { running = false; }
    public boolean listening() { return running; }

    @Override public void close() { stop(); model.close(); }
}
