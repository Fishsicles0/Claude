package net.evadeclient.clips.voice;

import net.evadeclient.clips.core.VoiceCommands;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Vosk speech recognition, restricted to the command phrases. Loaded at runtime from the downloaded Vosk
 * libraries (see VoiceCommands), so the app itself doesn't carry them.
 */
public final class VoskEngine implements VoiceCommands.Engine {
    private static final float RATE = 16000;
    /** Reads that return nothing in a row (with the back-off below, about 5 s) before the mic counts as gone. */
    private static final int MAX_EMPTY_READS = 25;
    private final Model model;
    private final String grammar;
    /** The microphone being listened to, or null. Changed only under the engine's lock. */
    private volatile Listener current;

    public VoskEngine(String modelDir, String grammar) throws IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        this.model = new Model(modelDir);
        this.grammar = grammar;
    }

    private Recognizer recognizer() throws IOException {
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

    @Override public synchronized boolean start(String micName, Consumer<String> results, Consumer<String> onError) throws Exception {
        String want = clean(micName);
        Listener old = current;
        if (old != null && old.active) {
            if (old.requested.equalsIgnoreCase(want)) {
                // Same microphone: keep listening, just with the new callbacks.
                old.results = results;
                old.onError = onError;
                return !old.fallback;
            }
            halt(old); // another microphone was picked: close this one first so the device is free
        }
        current = null;
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        Mic mic = openMic(fmt, want);
        try { mic.line().start(); }
        catch (RuntimeException e) { mic.line().close(); throw new IOException("the microphone wouldn't start (" + describe(e) + ").", e); }
        Listener l = new Listener(mic, want, results, onError);
        current = l;
        l.start();
        if (mic.fallback()) report(onError, "Voice commands couldn't open the microphone \"" + want
                + "\", so they're listening on your default microphone instead.");
        return !mic.fallback();
    }

    /** The device name as the app shows it ("Mic: …" on Linux) without the label, trimmed; "" means the default. */
    private static String clean(String micName) {
        String s = micName == null ? "" : micName.trim();
        if (s.regionMatches(true, 0, "Mic: ", 0, 5)) s = s.substring(5).trim();
        return s;
    }

    /** The opened line, the mixer it belongs to (null = system default), and whether it replaced the one asked for. */
    private record Mic(TargetDataLine line, String name, boolean fallback) {}

    /**
     * Opens the microphone called {@code want}: an exact (case-insensitive) name first, then a name containing it,
     * then one Windows cut short. A match that's busy or gone is skipped. Without a usable match (or with no name),
     * the system default is opened, flagged as a fallback when a name was asked for.
     */
    private static Mic openMic(AudioFormat fmt, String want) throws IOException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        if (!want.isEmpty()) {
            for (Mixer.Info mi : candidates(want, info)) {
                TargetDataLine l = null;
                try {
                    l = (TargetDataLine) AudioSystem.getMixer(mi).getLine(info);
                    l.open(fmt);
                    return new Mic(l, mi.getName(), false);
                } catch (Exception | LinkageError e) {
                    if (l != null) try { l.close(); } catch (Exception ignored) {}
                }
            }
        }
        try {
            TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(fmt);
            return new Mic(l, null, !want.isEmpty());
        } catch (IllegalArgumentException e) {
            throw new IOException("no microphone was found.");
        } catch (LineUnavailableException | SecurityException e) {
            throw new IOException("the microphone is in use or blocked (" + e.getMessage() + ").");
        }
    }

    /** Mixers that can record in this format, best name match first. */
    private static List<Mixer.Info> candidates(String want, DataLine.Info info) {
        String w = want.toLowerCase(Locale.ROOT);
        // A bare number (a device index from another API) only ever matches exactly, never as part of a name.
        boolean loose = w.length() >= 3 && !w.chars().allMatch(Character::isDigit);
        List<Mixer.Info> exact = new ArrayList<>(), contains = new ArrayList<>(), truncated = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            String n = mi.getName() == null ? "" : mi.getName().trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            List<Mixer.Info> bucket = n.equals(w) ? exact
                    : loose && n.contains(w) ? contains
                    : loose && n.length() >= 8 && w.startsWith(n) ? truncated // e.g. Windows' 31-character device names
                    : null;
            if (bucket == null) continue;
            try { if (!AudioSystem.getMixer(mi).isLineSupported(info)) continue; } catch (Exception | LinkageError e) { continue; }
            bucket.add(mi);
        }
        List<Mixer.Info> out = new ArrayList<>(exact);
        out.addAll(contains);
        out.addAll(truncated);
        return out;
    }

    /** Reads the microphone and feeds the recogniser until stopped, or until the microphone stops delivering. */
    private final class Listener extends Thread {
        final TargetDataLine line;
        final String requested;
        final boolean fallback;
        final String micName;
        volatile Consumer<String> results, onError;
        volatile boolean active = true;

        Listener(Mic mic, String requested, Consumer<String> results, Consumer<String> onError) {
            super("voice-commands");
            setDaemon(true);
            this.line = mic.line();
            this.micName = mic.name();
            this.fallback = mic.fallback();
            this.requested = requested;
            this.results = results;
            this.onError = onError;
        }

        @Override public void run() {
            String failure = null;
            try (Recognizer r = recognizer()) {
                byte[] buf = new byte[3200]; // 100 ms
                int empty = 0;
                while (active) {
                    int n = line.read(buf, 0, buf.length);
                    if (!active) break;
                    if (n <= 0) {
                        // A started line blocks until it has audio; nothing at all means the device went away.
                        if (++empty >= MAX_EMPTY_READS) {
                            failure = "Voice commands stopped: the microphone isn't sending any sound (was it unplugged?). "
                                    + "Turn voice commands off and on again to retry.";
                            break;
                        }
                        Thread.sleep(Math.min(200, 50L * empty));
                        continue;
                    }
                    empty = 0;
                    if (r.acceptWaveForm(buf, n)) {
                        String json = r.getResult();
                        try { results.accept(json); }
                        catch (RuntimeException e) { report(onError, "A voice command failed: " + describe(e)); }
                    }
                }
            } catch (InterruptedException e) {
                // stopping
            } catch (Throwable e) {
                if (active) failure = "Voice commands stopped: " + describe(e);
            } finally {
                // Free the device before reporting "not listening", so a restart can open it straight away.
                try { line.stop(); } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
                active = false;
            }
            if (failure != null) report(onError, failure);
        }

        /** Asks the thread to end; stopping the line wakes a read that's waiting for audio. */
        void shutdown() {
            active = false;
            try { line.stop(); } catch (Exception ignored) {}
        }
    }

    /** Stops a listener and waits briefly for it to let go of its microphone. Must hold the engine's lock. */
    private void halt(Listener l) {
        l.shutdown();
        try { l.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (l.isAlive()) try { l.line.close(); } catch (Exception ignored) {}
        if (current == l) current = null;
    }

    private static void report(Consumer<String> onError, String message) {
        if (onError == null) { System.err.println("[clips] " + message); return; }
        try { onError.accept(message); }
        catch (RuntimeException e) { System.err.println("[clips] " + message); }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    @Override public String microphone() {
        Listener l = current;
        return l != null && l.active ? l.micName : null;
    }

    @Override public synchronized void stop() {
        Listener l = current;
        if (l != null) halt(l);
        current = null;
    }

    @Override public boolean listening() {
        Listener l = current;
        return l != null && l.active;
    }

    @Override public void close() { stop(); model.close(); }
}
