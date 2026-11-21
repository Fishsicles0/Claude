package net.evadeclient.clips.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A clip's edits: which parts to keep (each at its own speed, so a kill can be slowed down), framing for
 * vertical/square video, audio and a title. Nothing touches the original file until export.
 */
public final class EditSpec {
    /** A kept part of the source, in source seconds. */
    public static final class Segment {
        public double start, end;
        public double speed = 1;

        public Segment() {}
        public Segment(double start, double end, double speed) { this.start = start; this.end = end; this.speed = speed; }
        public double length() { return Math.max(0, end - start); }
        public double outputLength() { return length() / speed; }
        public Segment copy() { return new Segment(start, end, speed); }
    }

    public List<Segment> segments = new ArrayList<>();

    /** "original", "16:9", "9:16", "1:1", "4:5". */
    public String aspect = "original";
    /** For aspects narrower than the source: "crop" (zoom into a part) or "blur" (fit, blurred background). */
    public String fitMode = "crop";
    /** Horizontal position of the crop window, 0 = left .. 1 = right. */
    public double cropX = 0.5;
    /** Zoom into the frame, 1 = none. */
    public double zoom = 1;

    public double volume = 1;
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

    public double outputDuration() {
        double d = 0;
        for (Segment s : segments) d += s.outputLength();
        return d;
    }

    public boolean isUntouched(double duration) {
        return segments.size() == 1 && segments.get(0).start <= 0.01 && segments.get(0).end >= duration - 0.01
                && segments.get(0).speed == 1 && aspect.equals("original") && zoom == 1 && volume == 1 && !mute
                && fadeIn == 0 && fadeOut == 0 && titleText.isBlank();
    }

    public EditSpec copy() {
        EditSpec e = new EditSpec();
        for (Segment s : segments) e.segments.add(s.copy());
        e.aspect = aspect; e.fitMode = fitMode; e.cropX = cropX; e.zoom = zoom;
        e.volume = volume; e.mute = mute; e.fadeIn = fadeIn; e.fadeOut = fadeOut;
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
        Segment right = new Segment(t, s.end, s.speed);
        s.end = t;
        segments.add(i + 1, right);
        return true;
    }

    public void sort() { segments.sort((a, b) -> Double.compare(a.start, b.start)); }
}
