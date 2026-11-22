package net.evadeclient.clips.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A clip's edits: which parts to keep (each at its own speed, or ramping between two), framing for vertical and
 * square video (including a facecam-on-top split), zoom keyframes, blurred areas, effects with sounds, audio,
 * captions and a title. Nothing touches the original file until export.
 */
public final class EditSpec {
    /** A kept part of the source, in source seconds. */
    public static final class Segment {
        public double start, end;
        public double speed = 1;
        /**
         * Speed at the end of the part, for a speed ramp (eased from {@link #speed} at the start); 0 = constant.
         * E.g. 1 → 0.25 slows smoothly into a shot.
         */
        public double rampTo;

        public Segment() {}
        public Segment(double start, double end, double speed) { this.start = start; this.end = end; this.speed = speed; }
        public double length() { return Math.max(0, end - start); }
        public boolean ramped() { return rampTo > 0 && Math.abs(rampTo - speed) > 1e-3; }
        public double outputLength() {
            double d = 0;
            for (Piece p : pieces()) d += p.outputLength();
            return d;
        }
        public Segment copy() { Segment s = new Segment(start, end, speed); s.rampTo = rampTo; return s; }

        /** Speed at source time t inside this part (eased in and out across a ramp). */
        public double speedAt(double t) {
            if (!ramped()) return speed;
            double u = length() <= 0 ? 0 : Math.max(0, Math.min(1, (t - start) / length()));
            double e = u * u * (3 - 2 * u); // smoothstep
            return speed + (rampTo - speed) * e;
        }

        /**
         * The part as constant-speed pieces: one for a plain part; for a ramp, short steps (about 0.15 s of source
         * each) that follow the curve closely enough to look and sound continuous.
         */
        public List<Piece> pieces() {
            List<Piece> out = new ArrayList<>();
            if (!ramped()) { out.add(new Piece(start, end, speed)); return out; }
            int n = (int) Math.max(4, Math.min(40, Math.round(length() / 0.15)));
            for (int i = 0; i < n; i++) {
                double a = start + length() * i / n, b = start + length() * (i + 1) / n;
                out.add(new Piece(a, b, speedAt((a + b) / 2)));
            }
            return out;
        }
    }

    /** A constant-speed stretch of the source. */
    public record Piece(double start, double end, double speed) {
        public double outputLength() { return Math.max(0, end - start) / speed; }
    }

    /** Text on screen between two source times. */
    public static final class Caption {
        public String text = "";
        public double start, end;
        /** "top", "center", "bottom". */
        public String position = "bottom";
        /**
         * "clean" (outlined white), "subtitle" (on a dark box), "meme" (big, uppercase, top/bottom),
         * "pop" (one bold word at a time that pops in, for auto-captions), "highlight" (bold, yellow).
         */
        public String style = "clean";

        public Caption() {}
        public Caption(String text, double start, double end, String position, String style) {
            this.text = text; this.start = start; this.end = end; this.position = position; this.style = style;
        }
        public Caption copy() { return new Caption(text, start, end, position, style); }
    }

    /**
     * A video on top of the main footage (facecam, reaction, a second angle): shown from source time
     * {@code start}, playing the overlay file from {@code inPoint}, at a position and size relative to the frame.
     */
    public static final class Overlay {
        public String file = "";
        public double start, inPoint, length;
        /** Centre of the overlay, 0..1 across / down the frame. */
        public double x = 0.82, y = 0.2;
        /** Width as a fraction of the frame width. */
        public double scale = 0.3;
        public double opacity = 1;
        public boolean audio;
        public double volume = 1;

        public Overlay copy() {
            Overlay o = new Overlay();
            o.file = file; o.start = start; o.inPoint = inPoint; o.length = length; o.x = x; o.y = y;
            o.scale = scale; o.opacity = opacity; o.audio = audio; o.volume = volume;
            return o;
        }
    }

    /**
     * A zoom keyframe at a source time: how far in, and where (centre of the view, 0..1 of the frame). Between
     * keyframes the view glides smoothly; before the first and after the last it holds.
     */
    public static final class ZoomKey {
        public double t, zoom = 1, cx = 0.5, cy = 0.5;
        public ZoomKey() {}
        public ZoomKey(double t, double zoom, double cx, double cy) { this.t = t; this.zoom = zoom; this.cx = cx; this.cy = cy; }
        public ZoomKey copy() { return new ZoomKey(t, zoom, cx, cy); }
    }

    /** A blurred or pixelated rectangle (names, chat, coordinates) between two source times. 0..1 of the frame. */
    public static final class Mask {
        public double start, end, x = 0.7, y = 0.05, w = 0.25, h = 0.1;
        /** "blur", "pixelate" or "black". */
        public String kind = "blur";
        public Mask copy() {
            Mask m = new Mask();
            m.start = start; m.end = end; m.x = x; m.y = y; m.w = w; m.h = h; m.kind = kind;
            return m;
        }
    }

    /**
     * A one-shot effect at a source time, with an optional sound: "shake", "punch" (quick zoom in and out),
     * "flash", "hitmarker", or "sound" (only the sound).
     */
    public static final class Effect {
        public String type = "shake";
        public double t, length = 0.35;
        /** 0..1. */
        public double strength = 0.6;
        /** A sound from the library (see {@link Sounds}), or "" for none. */
        public String sound = "";
        public double volume = 1;

        public Effect() {}
        public Effect(String type, double t, String sound) {
            this.type = type; this.t = t; this.sound = sound;
            this.length = switch (type) { case "flash" -> 0.25; case "hitmarker" -> 0.22; case "punch" -> 0.45; case "sound" -> 0; default -> 0.35; };
        }
        public Effect copy() {
            Effect e = new Effect(type, t, sound);
            e.length = length; e.strength = strength; e.volume = volume;
            return e;
        }
    }

    public List<Segment> segments = new ArrayList<>();
    public List<Overlay> overlays = new ArrayList<>();
    public List<Caption> captions = new ArrayList<>();
    public List<ZoomKey> zooms = new ArrayList<>();
    public List<Mask> masks = new ArrayList<>();
    public List<Effect> effects = new ArrayList<>();
    /** Color look: "none", "vibrant", "cinematic", "bw", "retro", "warm", "cool". */
    public String effect = "none";
    /** Level of the voice-chat track (e.g. Discord), when one was recorded. */
    public double voiceVolume = 1;
    /**
     * How slowed-down parts get their extra frames: "off" (repeat frames), "blend" (mix neighbouring frames) or
     * "flow" (optical flow: new in-between frames from the motion, smoothest and slowest to export).
     */
    public String smoothing = "blend";

    /** "original", "16:9", "9:16", "1:1", "4:5". */
    public String aspect = "original";
    /**
     * For aspects narrower than the source: "crop" (zoom into a part), "blur" (fit, blurred background) or
     * "split" (facecam on top, gameplay below; vertical only).
     */
    public String fitMode = "crop";
    /** Horizontal position of the crop window, 0 = left .. 1 = right. */
    public double cropX = 0.5;
    /** Zoom into the frame, 1 = none. */
    public double zoom = 1;
    /** Split layout: the facecam's area in the source frame (0..1), and how much of the height it takes. */
    public double camX = 0.72, camY = 0.04, camW = 0.26, camH = 0.3, camShare = 0.38;
    /** Split layout: a separate webcam recording to use on top instead of an area of the gameplay ("" = none). */
    public String camFile = "";

    public double volume = 1;
    /** Mic level for clips with a separate mic track (0 mutes your voice, keeps the game). */
    public double micVolume = 1;
    public boolean mute;
    public double fadeIn, fadeOut;

    public String titleText = "";
    /** "top", "center", "bottom". */
    public String titlePosition = "bottom";
    /** Seconds the title stays up from the start; 0 = the whole clip. */
    public double titleSeconds = 3;

    public static EditSpec whole(double duration) {
        EditSpec e = new EditSpec();
        e.segments.add(new Segment(0, duration, 1));
        return e;
    }

    /** Lists stay non-null after loading an older saved edit. */
    public EditSpec fill() {
        if (segments == null) segments = new ArrayList<>();
        if (overlays == null) overlays = new ArrayList<>();
        if (captions == null) captions = new ArrayList<>();
        if (zooms == null) zooms = new ArrayList<>();
        if (masks == null) masks = new ArrayList<>();
        if (effects == null) effects = new ArrayList<>();
        if (smoothing == null) smoothing = "blend";
        if (camFile == null) camFile = "";
        if (camShare <= 0) { camX = 0.72; camY = 0.04; camW = 0.26; camH = 0.3; camShare = 0.38; }
        return this;
    }

    public double outputDuration() {
        double d = 0;
        for (Segment s : segments) d += s.outputLength();
        return d;
    }

    /** Every kept part as constant-speed pieces, in order. */
    public List<Piece> pieces() {
        List<Piece> out = new ArrayList<>();
        for (Segment s : segments) out.addAll(s.pieces());
        return out;
    }

    public boolean isUntouched(double duration) {
        return segments.size() == 1 && segments.get(0).start <= 0.01 && segments.get(0).end >= duration - 0.01
                && segments.get(0).speed == 1 && !segments.get(0).ramped() && aspect.equals("original") && zoom == 1 && volume == 1 && micVolume == 1 && !mute
                && fadeIn == 0 && fadeOut == 0 && titleText.isBlank() && captions.isEmpty() && overlays.isEmpty() && "none".equals(effect) && voiceVolume == 1
                && zooms.isEmpty() && masks.isEmpty() && effects.isEmpty();
    }

    public EditSpec copy() {
        EditSpec e = new EditSpec();
        for (Segment s : segments) e.segments.add(s.copy());
        e.aspect = aspect; e.fitMode = fitMode; e.cropX = cropX; e.zoom = zoom;
        e.camX = camX; e.camY = camY; e.camW = camW; e.camH = camH; e.camShare = camShare; e.camFile = camFile;
        e.volume = volume; e.micVolume = micVolume; e.voiceVolume = voiceVolume; e.mute = mute;
        e.effect = effect; e.smoothing = smoothing;
        for (Caption c : captions) e.captions.add(c.copy());
        for (Overlay o : overlays) e.overlays.add(o.copy());
        for (ZoomKey z : zooms) e.zooms.add(z.copy());
        for (Mask m : masks) e.masks.add(m.copy());
        for (Effect f : effects) e.effects.add(f.copy());
        e.fadeIn = fadeIn; e.fadeOut = fadeOut;
        e.titleText = titleText; e.titlePosition = titlePosition; e.titleSeconds = titleSeconds;
        return e;
    }

    /** Segment containing source time t, or -1. */
    public int segmentAt(double t) {
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (t >= s.start - 1e-6 && t < s.end) return i;
        }
        return -1;
    }

    /** Splits the segment under t into two. Returns false if t is not inside one (or too close to an edge). */
    public boolean splitAt(double t) {
        int i = segmentAt(t);
        if (i < 0) return false;
        Segment s = segments.get(i);
        if (t - s.start < 0.1 || s.end - t < 0.1) return false;
        double mid = s.speedAt(t);
        Segment right = new Segment(t, s.end, s.ramped() ? mid : s.speed);
        if (s.ramped()) { right.rampTo = s.rampTo; s.rampTo = mid; }
        s.end = t;
        segments.add(i + 1, right);
        return true;
    }

    /**
     * Turns the time around t into a speed ramp: normal speed slowing into {@code slow} at t and back out again,
     * across {@code before} and {@code after} seconds. Returns false if t isn't inside a kept part.
     */
    public boolean rampAround(double t, double slow, double before, double after) {
        int i = segmentAt(t);
        if (i < 0) return false;
        Segment s = segments.get(i);
        double a = Math.max(s.start, t - before), b = Math.min(s.end, t + after);
        double hold = Math.min(0.35, (b - a) / 4);
        if (b - a < 0.4) return false;
        // in, hold, out: [a, t-hold/2] ramp 1 → slow, [t-hold/2, t+hold/2] slow, [t+hold/2, b] ramp slow → 1
        splitAt(a); splitAt(b);
        double h0 = Math.max(a + 0.1, t - hold / 2), h1 = Math.min(b - 0.1, t + hold / 2);
        splitAt(h0); splitAt(h1);
        for (Segment x : segments) {
            if (x.start >= a - 1e-6 && x.end <= h0 + 1e-6) { x.speed = 1; x.rampTo = slow; }
            else if (x.start >= h0 - 1e-6 && x.end <= h1 + 1e-6) { x.speed = slow; x.rampTo = 0; }
            else if (x.start >= h1 - 1e-6 && x.end <= b + 1e-6) { x.speed = slow; x.rampTo = 1; }
        }
        return true;
    }

    /** Where source time t lands in the export (cut parts collapse, speed changes stretch). */
    public double toOutput(double t) {
        double out = 0;
        for (Segment s : segments) {
            if (t >= s.end) { out += s.outputLength(); continue; }
            if (t < s.start) break;
            for (Piece p : s.pieces()) {
                if (t >= p.end()) out += p.outputLength();
                else { out += (t - p.start()) / p.speed(); break; }
            }
            break;
        }
        return out;
    }

    /** Zoom keyframes in time order. */
    public List<ZoomKey> sortedZooms() {
        List<ZoomKey> z = new ArrayList<>(zooms);
        z.sort((a, b) -> Double.compare(a.t, b.t));
        return z;
    }

    public void sort() {
        segments.sort((a, b) -> Double.compare(a.start, b.start));
        effects.sort((a, b) -> Double.compare(a.t, b.t));
    }
}
