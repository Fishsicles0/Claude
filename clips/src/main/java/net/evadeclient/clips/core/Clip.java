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
    public boolean favorite;
    public Kind kind = Kind.IMPORT;
    /** For exports: the clip it was made from. */
    public String sourceId;
    public List<String> tags = new ArrayList<>();
    /** Bookmarks in seconds (hotkey during recording, or added in the editor). */
    public List<Double> markers = new ArrayList<>();
    /** Non-destructive edits. Null until the clip is first opened in the editor. */
    public EditSpec edit;

    public Path path(Path clipsDir) { return clipsDir.resolve(file.replace('/', java.io.File.separatorChar)); }

    public EditSpec editOrDefault() {
        if (edit == null) edit = EditSpec.whole(duration);
        return edit;
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
