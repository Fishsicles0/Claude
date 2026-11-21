package net.evadeclient.clips.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The clip library: every video in the clips folder plus its metadata (title, game, tags, favourites, markers,
 * edits). The index lives inside the clips folder ({@code .evadeclips/library.json}) so the whole library moves
 * with the folder.
 */
public final class Library {
    public static final Set<String> VIDEO_EXT = Set.of("mp4", "mkv", "mov", "webm", "avi", "m4v", "flv", "ts", "gif");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Settings settings;
    private final List<Clip> clips = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "thumbnails");
        t.setDaemon(true);
        return t;
    });

    public Library(Settings settings) { this.settings = settings; }

    public Path root() { return settings.clipsDir(); }
    public Path metaDir() { return root().resolve(".evadeclips"); }
    public Path thumbsDir() { return metaDir().resolve("thumbs"); }
    public Path bufferDir() { return metaDir().resolve("buffer"); }
    public Path folder(Clip.Kind kind) {
        return root().resolve(switch (kind) {
            case REPLAY -> "Replays";
            case RECORDING -> "Recordings";
            case EXPORT -> "Exports";
            case IMPORT -> "Imported";
        });
    }

    public void onChange(Runnable r) { listeners.add(r); }
    public void changed() { for (Runnable r : listeners) r.run(); }

    // ------------------------------------------------------------------ persistence

    public synchronized void load() {
        clips.clear();
        Path f = metaDir().resolve("library.json");
        try {
            if (Files.exists(f)) {
                List<Clip> list = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Clip>>() {}.getType());
                if (list != null) for (Clip c : list) if (c != null && c.id != null && c.file != null) {
                    if (c.tags == null) c.tags = new ArrayList<>();
                    if (c.markers == null) c.markers = new ArrayList<>();
                    if (c.kind == null) c.kind = Clip.Kind.IMPORT;
                    clips.add(c);
                }
            }
        } catch (Exception e) {
            System.err.println("[clips] library index unreadable, rebuilding: " + e.getMessage());
        }
        rescan();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(metaDir());
            Path f = metaDir().resolve("library.json");
            Path tmp = metaDir().resolve("library.json.tmp");
            Files.writeString(tmp, GSON.toJson(new ArrayList<>(clips)), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[clips] could not save library: " + e.getMessage());
        }
    }

    /** Drops entries whose file is gone and adds videos that were put in the folder by hand. */
    public synchronized void rescan() {
        clips.removeIf(c -> !Files.exists(c.path(root())));
        Set<String> known = new HashSet<>();
        for (Clip c : clips) known.add(c.file);
        if (Files.isDirectory(root())) {
            try (Stream<Path> s = Files.walk(root(), 2)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !p.startsWith(metaDir()))
                        .filter(p -> VIDEO_EXT.contains(ext(p)))
                        .filter(p -> !known.contains(rel(p)))
                        .forEach(p -> {
                            try { addExisting(p, kindFor(p), null, null); }
                            catch (IOException e) { System.err.println("[clips] skipped " + p.getFileName() + ": " + e.getMessage()); }
                        });
            } catch (IOException ignored) {}
        }
        for (Clip c : clips) if (!Files.exists(thumb(c))) queueThumbnails(c);
        save();
        changed();
    }

    private Clip.Kind kindFor(Path p) {
        if (p.startsWith(folder(Clip.Kind.REPLAY))) return Clip.Kind.REPLAY;
        if (p.startsWith(folder(Clip.Kind.RECORDING))) return Clip.Kind.RECORDING;
        if (p.startsWith(folder(Clip.Kind.EXPORT))) return Clip.Kind.EXPORT;
        return Clip.Kind.IMPORT;
    }

    // ------------------------------------------------------------------ queries

    public List<Clip> all() { return new ArrayList<>(clips); }

    public Clip get(String id) {
        for (Clip c : clips) if (c.id.equals(id)) return c;
        return null;
    }

    public List<String> games() {
        Set<String> g = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Clip c : clips) if (c.game != null && !c.game.isBlank()) g.add(c.game);
        return new ArrayList<>(g);
    }

    public List<String> allTags() {
        Set<String> g = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Clip c : clips) g.addAll(c.tags);
        return new ArrayList<>(g);
    }

    public long totalBytes() {
        long t = 0;
        for (Clip c : clips) t += c.size;
        return t;
    }

    /** Filter + sort used by the library grid. */
    public List<Clip> query(String search, String filter, String game, String sort) {
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Clip> out = new ArrayList<>();
        for (Clip c : clips) {
            switch (filter == null ? "all" : filter) {
                case "favorites" -> { if (!c.favorite) continue; }
                case "replays" -> { if (c.kind != Clip.Kind.REPLAY) continue; }
                case "recordings" -> { if (c.kind != Clip.Kind.RECORDING) continue; }
                case "exports" -> { if (c.kind != Clip.Kind.EXPORT) continue; }
                case "imports" -> { if (c.kind != Clip.Kind.IMPORT) continue; }
                default -> {}
            }
            if (game != null && !game.isBlank() && !game.equalsIgnoreCase(c.game)) continue;
            if (!q.isEmpty()) {
                String hay = (c.title + " " + c.game + " " + String.join(" ", c.tags) + " " + c.file).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String word : q.split("\\s+")) if (!hay.contains(word)) { all = false; break; }
                if (!all) continue;
            }
            out.add(c);
        }
        Comparator<Clip> cmp = switch (sort == null ? "newest" : sort) {
            case "oldest" -> Comparator.comparingLong(c -> c.created);
            case "longest" -> Comparator.comparingDouble((Clip c) -> c.duration).reversed();
            case "largest" -> Comparator.comparingLong((Clip c) -> c.size).reversed();
            case "name" -> Comparator.comparing((Clip c) -> c.title == null ? "" : c.title, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingLong((Clip c) -> c.created).reversed();
        };
        out.sort(cmp);
        return out;
    }

    // ------------------------------------------------------------------ adding

    /** Registers a video that is already inside the clips folder. */
    public synchronized Clip addExisting(Path file, Clip.Kind kind, String title, String game) throws IOException {
        Ffmpeg.MediaInfo info = Ffmpeg.probe(file);
        Clip c = new Clip();
        c.id = UUID.randomUUID().toString().substring(0, 12);
        c.file = rel(file);
        c.kind = kind;
        c.created = Files.getLastModifiedTime(file).toMillis();
        c.title = title != null ? title : prettyName(file);
        c.game = game != null ? game : (kind == Clip.Kind.IMPORT ? "" : settings.defaultGame);
        applyInfo(c, info, file);
        clips.add(c);
        queueThumbnails(c);
        return c;
    }

    /** Adds a freshly captured or exported file, saves, enforces the storage limit and notifies listeners. */
    public Clip addNew(Path file, Clip.Kind kind, String title, String game, List<Double> markers, String sourceId) throws IOException {
        Clip c;
        synchronized (this) {
            c = addExisting(file, kind, title, game);
            c.created = System.currentTimeMillis();
            if (markers != null) c.markers.addAll(markers);
            c.sourceId = sourceId;
            if (sourceId != null) {
                Clip src = get(sourceId);
                if (src != null) { c.tags.addAll(src.tags); c.game = src.game; }
            }
            enforceLimit(c);
            save();
        }
        changed();
        return c;
    }

    /** Copies (or moves) outside videos into the library. */
    public List<Clip> importFiles(List<Path> files, boolean move) {
        List<Clip> added = new ArrayList<>();
        for (Path f : files) {
            if (!VIDEO_EXT.contains(ext(f))) continue;
            try {
                Path dest = f.startsWith(root()) ? f : unique(folder(Clip.Kind.IMPORT), f.getFileName().toString());
                if (!dest.equals(f)) {
                    Files.createDirectories(dest.getParent());
                    if (move) Files.move(f, dest); else Files.copy(f, dest);
                }
                Clip existing = null;
                for (Clip c : clips) if (c.file.equals(rel(dest))) existing = c;
                added.add(existing != null ? existing : addExisting(dest, Clip.Kind.IMPORT, null, null));
            } catch (IOException e) {
                System.err.println("[clips] import failed for " + f + ": " + e.getMessage());
            }
        }
        save();
        changed();
        return added;
    }

    private void applyInfo(Clip c, Ffmpeg.MediaInfo info, Path file) throws IOException {
        c.duration = info.duration();
        c.width = info.width();
        c.height = info.height();
        c.fps = info.fps();
        c.hasAudio = info.hasAudio();
        c.audioTracks = info.audioTracks();
        c.size = Files.size(file);
    }

    // ------------------------------------------------------------------ editing

    public void update(Clip c) {
        save();
        changed();
    }

    public void rename(Clip c, String title) {
        c.title = title.isBlank() ? prettyName(c.path(root())) : title.trim();
        update(c);
    }

    /** Deletes clips (to the OS recycle bin where possible). Returns how many were removed. */
    public int delete(List<Clip> list) {
        int n = 0;
        for (Clip c : list) {
            Path p = c.path(root());
            boolean gone = false;
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH))
                    gone = Desktop.getDesktop().moveToTrash(p.toFile());
            } catch (Exception ignored) {}
            try { if (!gone) gone = Files.deleteIfExists(p) || !Files.exists(p); } catch (IOException ignored) {}
            if (gone) {
                clips.remove(c);
                try { Files.deleteIfExists(thumb(c)); Files.deleteIfExists(strip(c)); } catch (IOException ignored) {}
                n++;
            }
        }
        save();
        changed();
        return n;
    }

    /**
     * Keeps the library under the storage limit by deleting the oldest unfavourited replays first, then other
     * unfavourited clips. Never deletes favourites, exports, or the clip that was just added.
     */
    private void enforceLimit(Clip keep) {
        if (settings.storageLimitGb <= 0) return;
        long limit = (long) (settings.storageLimitGb * 1024 * 1024 * 1024);
        if (totalBytes() <= limit) return;
        List<Clip> victims = new ArrayList<>();
        for (Clip c : clips) if (!c.favorite && c != keep && c.kind != Clip.Kind.EXPORT) victims.add(c);
        victims.sort(Comparator.comparing((Clip c) -> c.kind != Clip.Kind.REPLAY).thenComparingLong(c -> c.created));
        long total = totalBytes();
        List<Clip> remove = new ArrayList<>();
        for (Clip v : victims) {
            if (total <= limit) break;
            remove.add(v);
            total -= v.size;
        }
        for (Clip c : remove) {
            try { Files.deleteIfExists(c.path(root())); Files.deleteIfExists(thumb(c)); Files.deleteIfExists(strip(c)); }
            catch (IOException ignored) {}
            clips.remove(c);
        }
        if (!remove.isEmpty()) System.err.println("[clips] storage limit: removed " + remove.size() + " old clip(s)");
    }

    // ------------------------------------------------------------------ thumbnails

    public Path thumb(Clip c) { return thumbsDir().resolve(c.id + ".jpg"); }
    /** 10 frames side by side, for hover-scrubbing in the grid and the editor timeline. */
    public Path strip(Clip c) { return thumbsDir().resolve(c.id + "-strip.jpg"); }

    public static final int STRIP_FRAMES = 10;

    public void queueThumbnails(Clip c) {
        thumbs.submit(() -> {
            try {
                makeThumbnails(c);
                changed();
            } catch (Exception e) {
                System.err.println("[clips] thumbnail failed for " + c.file + ": " + e.getMessage());
            }
        });
    }

    void makeThumbnails(Clip c) throws IOException {
        Files.createDirectories(thumbsDir());
        Path src = c.path(root());
        double at = Math.min(c.duration * 0.4, Math.max(0, c.duration - 0.1));
        Ffmpeg.run(List.of("-ss", fmt(at), "-i", src.toString(), "-frames:v", "1",
                "-vf", "scale=480:270:force_original_aspect_ratio=decrease,pad=480:270:(ow-iw)/2:(oh-ih)/2:color=black",
                "-q:v", "4", thumb(c).toString()));
        if (c.duration > 0.5) {
            double rate = STRIP_FRAMES / c.duration;
            Ffmpeg.run(List.of("-i", src.toString(), "-vf",
                    "fps=" + fmt(rate) + ",scale=192:108:force_original_aspect_ratio=decrease,pad=192:108:(ow-iw)/2:(oh-ih)/2:color=black,tile=" + STRIP_FRAMES + "x1",
                    "-frames:v", "1", "-q:v", "5", strip(c).toString()));
        }
    }

    // ------------------------------------------------------------------ helpers

    public String rel(Path p) {
        Path r = root().toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize());
        return r.toString().replace(java.io.File.separatorChar, '/');
    }

    public static String ext(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** A filename in {@code dir} that doesn't exist yet ("name (2).mp4" style). */
    public static Path unique(Path dir, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        Path p = dir.resolve(name);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + " (" + i + ")" + ext);
        return p;
    }

    /** "Minecraft 2026-11-21 21-04-33.mp4" for a new capture. */
    public static String stampName(String game, String ext) {
        String g = game == null || game.isBlank() ? "Clip" : safe(game);
        return g + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date()) + "." + ext;
    }

    public static String safe(String s) { return s.replaceAll("[\\\\/:*?\"<>|]", "").trim(); }

    static String prettyName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static String fmt(double d) { return String.format(Locale.ROOT, "%.3f", d); }

    public void shutdown() { thumbs.shutdownNow(); }
}
