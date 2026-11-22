package net.evadeclient.clips.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One video in the library. Edits are stored here and only rendered when the clip is exported. */
public final class Clip {
    public enum Kind { REPLAY, RECORDING, EXPORT, IMPORT }

    public String id;
    /** Path relative to the clips folder (forward slashes). */
    public String file;
    public String title;
    public String game;
    public long created;
    public double duration;
    public int width, height;
    public double fps;
    public long size;
    public boolean hasAudio;
    /** 2 when the mic was recorded on its own track (track 0 = game, 1 = mic). */
    public int audioTracks;
    /** Audio track titles in order ("Game", "Mic", "Voice chat"); empty for clips from elsewhere. */
    public List<String> trackNames = new ArrayList<>();
    public boolean favorite;
    public Kind kind = Kind.IMPORT;
    /** For exports: the clip it was made from. */
    public String sourceId;
    public List<String> tags = new ArrayList<>();
    /** Bookmarks in seconds (hotkey during recording, or added in the editor). */
    public List<Double> markers = new ArrayList<>();
    /** Names of markers, keyed by {@link #key(double)} of their time ("Kill Steve", "ACE", "Death", "Voice"). */
    public java.util.Map<String, String> markerLabels = new java.util.HashMap<>();

    public static String key(double t) { return String.format(java.util.Locale.ROOT, "%.2f", t); }
    public String label(double t) { return markerLabels == null ? null : markerLabels.get(key(t)); }
    /** Non-destructive edits. Null until the clip is first opened in the editor. */
    public EditSpec edit;
    /** Set once the clip has been shared: id and public link on the share server. */
    public String shareId, shareUrl;
    /** Edits the shared copy was rendered with, so a changed clip gets re-uploaded. */
    public int shareEditHash;
    /** Shared privately: only visible through your own gallery link. */
    public boolean sharePrivate;
    /** For clips imported from a watched folder: the original file, so it's imported once. */
    public String importedFrom;

    @Override public String toString() { return (title == null ? file : title) + (game == null || game.isBlank() ? "" : "  ·  " + game); }

    public Path path(Path clipsDir) { return clipsDir.resolve(file.replace('/', java.io.File.separatorChar)); }

    public EditSpec editOrDefault() {
        if (edit == null) edit = EditSpec.whole(duration);
        return edit.fill();
    }

    public String resolution() {
        if (height <= 0) return "";
        return switch (height) {
            case 2160 -> "4K";
            case 1440 -> "1440p";
            case 1080 -> "1080p";
            case 720 -> "720p";
            default -> width + "×" + height;
        };
    }
}
