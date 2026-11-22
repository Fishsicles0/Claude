package net.evadeclient.clips.voice;

import net.evadeclient.clips.core.VoiceCommands;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.InputStream;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Vosk speech recognition, restricted to the command phrases. Loaded at runtime from the downloaded Vosk
 * libraries (see VoiceCommands), so the app itself doesn't carry them.
 */
public final class VoskEngine implements VoiceCommands.Engine {
    private static final float RATE = 16000;
    private final Model model;
    private final String grammar;
    private volatile boolean running;

    public VoskEngine(String modelDir, String grammar) throws java.io.IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        this.model = new Model(modelDir);
        this.grammar = grammar;
    }

    private Recognizer recognizer() throws java.io.IOException {
        // no grammar = free dictation (auto-captions); a grammar = only the command phrases
        Recognizer r = grammar == null ? new Recognizer(model, RATE) : new Recognizer(model, RATE, grammar);
        r.setWords(true);
        return r;
    }

    @Override public void recognize(InputStream pcm, Consumer<String> results) throws Exception {
        try (Recognizer r = recognizer()) {
            byte[] buf = new byte[3200]; // 100 ms
            int n;
            while ((n = pcm.read(buf)) > 0) if (r.acceptWaveForm(buf, n)) results.accept(r.getResult());
            results.accept(r.getFinalResult());
        }
    }

    @Override public synchronized void start(String micName, Consumer<String> results) throws Exception {
        if (running) return;
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        TargetDataLine line = openMic(fmt, micName);
        line.start();
        running = true;
        Thread t = new Thread(() -> {
            try (Recognizer r = recognizer()) {
                byte[] buf = new byte[3200];
                while (running) {
                    int n = line.read(buf, 0, buf.length);
                    if (n > 0 && r.acceptWaveForm(buf, n)) results.accept(r.getResult());
                }
            } catch (Exception e) {
                System.err.println("[clips] voice commands stopped: " + e.getMessage());
            } finally {
                line.stop();
                line.close();
                running = false;
            }
        }, "voice-commands");
        t.setDaemon(true);
        t.start();
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

    @Override public synchronized void stop() { running = false; }
    @Override public boolean listening() { return running; }
    @Override public void close() { stop(); model.close(); }
}
