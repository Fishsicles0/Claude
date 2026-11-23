package net.evadeclient.clips.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Auto-captions: speech-to-text on your PC (the same offline engine as voice commands), with a time for every
 * word, turned into captions: one word at a time that pops in, short punchy phrases, or classic subtitles.
 */
public final class AutoCaptions {
    private AutoCaptions() {}

    public record Word(String text, double start, double end, double conf) {}

    /** Whose speech to caption. */
    public enum Source {
        AUTO("Your mic (or the whole clip)"), MIC("Your mic"), VOICE("Voice chat"), GAME("Game sound"), ALL("Everything");
        public final String label;
        Source(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** How the words become captions. */
    public enum Layout {
        WORD("One word at a time (pops in)"), PHRASE("Short phrases (2-3 words)"), SUBTITLE("Subtitles (full lines)");
        public final String label;
        Layout(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public static boolean ready() { return VoiceCommands.ready(); }

    /** Transcribes a stretch of a clip's audio (source seconds), returning the words with their times. */
    public static List<Word> transcribe(Path video, Clip clip, Source source, double from, double to, DoubleConsumer progress) throws Exception {
        List<String> args = new ArrayList<>(List.of(Ffmpeg.ffmpeg(), "-v", "error", "-ss", Library.fmt(Math.max(0, from)), "-t", Library.fmt(Math.max(0.1, to - from)),
                "-i", video.toString()));
        int tracks = Math.max(1, clip.audioTracks);
        int idx = switch (source) {
            case MIC -> trackIndex(clip, "Mic");
            case VOICE -> trackIndex(clip, "Voice chat");
            case GAME -> 0;
            case AUTO -> trackIndex(clip, "Mic") >= 0 ? trackIndex(clip, "Mic") : -1;
            case ALL -> -1;
        };
        if ((source == Source.MIC || source == Source.VOICE) && idx < 0)
            throw new IOException("This clip has no " + (source == Source.MIC ? "separate mic" : "voice chat") + " track.");
        // speech band, levelled, 16 kHz mono: what the recognizer wants
        String clean = "highpass=f=80,lowpass=f=7500,dynaudnorm=f=150";
        String graph;
        if (idx >= 0) graph = "[0:a:" + idx + "]" + clean;
        else if (tracks > 1) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < tracks; i++) in.append("[0:a:").append(i).append("]");
            graph = in + "amix=inputs=" + tracks + ":normalize=0," + clean;
        } else graph = "[0:a:0]" + clean;
        args.addAll(List.of("-filter_complex", graph + "[s]", "-map", "[s]", "-ac", "1", "-ar", "16000", "-f", "s16le", "-"));
        Process p = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        double total = Math.max(0.1, to - from);
        List<Word> words = new ArrayList<>();
        try (VoiceCommands.Engine engine = VoiceCommands.dictation(); InputStream pcm = new CountingStream(p.getInputStream(), total, progress)) {
            engine.recognize(pcm, json -> parse(json, from, words));
        } finally {
            p.destroy();
        }
        return words;
    }

    /** The audio track with this name (unnamed tracks count as Game, Mic, Voice chat by position), or -1. */
    private static int trackIndex(Clip clip, String name) {
        for (int i = 0; i < clip.audioTracks; i++) if (Exporter.trackName(clip, i).equalsIgnoreCase(name)) return i;
        return -1;
    }

    static void parse(String json, double offset, List<Word> out) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("result")) return;
            for (JsonElement el : o.getAsJsonArray("result")) {
                JsonObject w = el.getAsJsonObject();
                String text = w.get("word").getAsString();
                if (text.isBlank() || text.equals("[unk]")) continue;
                double conf = w.has("conf") ? w.get("conf").getAsDouble() : 1;
                out.add(new Word(text, offset + w.get("start").getAsDouble(), offset + w.get("end").getAsDouble(), conf));
            }
        } catch (RuntimeException ignored) {}
    }

    /** Words → captions in the chosen layout and look. Times stay in source seconds. */
    public static List<EditSpec.Caption> build(List<Word> words, Layout layout, String position) {
        List<EditSpec.Caption> out = new ArrayList<>();
        int max = switch (layout) { case WORD -> 1; case PHRASE -> 3; case SUBTITLE -> 8; };
        String style = switch (layout) { case WORD, PHRASE -> "pop"; case SUBTITLE -> "subtitle"; };
        List<Word> group = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (!group.isEmpty()) {
                Word last = group.get(group.size() - 1);
                boolean pause = w.start() - last.end() > (layout == Layout.SUBTITLE ? 0.8 : 0.45);
                if (group.size() >= max || pause || (layout == Layout.SUBTITLE && w.end() - group.get(0).start() > 3.5)) {
                    out.add(caption(group, i < words.size() ? w.start() : -1, style, position, layout));
                    group.clear();
                }
            }
            group.add(w);
        }
        if (!group.isEmpty()) out.add(caption(group, -1, style, position, layout));
        // every other pop caption in the highlight colour, so a run of single words reads as a rhythm
        if (layout != Layout.SUBTITLE) for (int i = 1; i < out.size(); i += 2) if (out.get(i).text.length() > 3) out.get(i).style = "highlight";
        return out;
    }

    private static EditSpec.Caption caption(List<Word> g, double nextStart, String style, String position, Layout layout) {
        StringBuilder sb = new StringBuilder();
        for (Word w : g) { if (sb.length() > 0) sb.append(' '); sb.append(w.text()); }
        String text = sb.toString();
        if (layout == Layout.SUBTITLE && !text.isEmpty()) text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        double start = g.get(0).start(), end = g.get(g.size() - 1).end();
        // stay up until the next words start (if that's soon), so captions don't flicker between words
        double hold = nextStart > 0 && nextStart - end < 0.6 ? nextStart : end + (layout == Layout.SUBTITLE ? 0.5 : 0.2);
        return new EditSpec.Caption(text, start, Math.max(start + 0.12, hold), position, style);
    }

    /** Reports progress as the audio streams through (2 bytes per sample, 16000 samples a second). */
    private static final class CountingStream extends java.io.FilterInputStream {
        private final double total;
        private final DoubleConsumer progress;
        private long read;

        CountingStream(InputStream in, double total, DoubleConsumer progress) { super(in); this.total = total; this.progress = progress; }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
                if (progress != null) progress.accept(Math.min(1, read / 32000.0 / total));
            }
            return n;
        }
    }
}
