package net.evadeclient.clips.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    /** Cover thumbnails being drawn, so a share-link picture upload can wait for the new one. */
    private final java.util.Map<String, Future<?>> coverJobs = new ConcurrentHashMap<>();
    /** Bumped when a cover is chosen, so a thumbnail of the old frame that finishes later can't replace it. */
    private final java.util.Map<String, Integer> coverGen = new ConcurrentHashMap<>();
    /** Set once the first scan of the clips folder has finished (until then the grid says "Loading your clips…"). */
    private volatile boolean loaded;
    private volatile long lastFullWarning;

    /**
     * Version of what's stored about clips. 2: imported clips are measured again once (portrait phone videos used
     * to be stored with their sideways size).
     */
    static final int FORMAT = 2;

    public Library(Settings settings) { this.settings = settings; }

    /** Something the user should hear about (storage clean-ups, an unreadable index). */
    public interface Notice { void note(String title, String body, boolean error); }
    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    public void onNotice(Notice n) { notices.add(n); }
    private void notice(String title, String body, boolean error) {
        if (notices.isEmpty()) { System.err.println("[clips] " + title + (body == null ? "" : ": " + body)); return; }
        for (Notice n : notices) {
            try { n.note(title, body, error); } catch (Exception e) { System.err.println("[clips] " + e); }
        }
    }

    /** True once the clips folder has been scanned at least once since the last {@link #load()} / {@link #loadIndex()}. */
    public boolean loaded() { return loaded; }

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

    private final List<java.util.function.Consumer<Clip>> addedListeners = new CopyOnWriteArrayList<>();
    /** Called for every new replay, recording or export (not for imports or rescans). */
    public void onAdded(java.util.function.Consumer<Clip> l) { addedListeners.add(l); }
    public void changed() { for (Runnable r : listeners) r.run(); }

    // ------------------------------------------------------------------ persistence

    /** Reads the index and scans the clips folder for added or removed files. */
    public void load() {
        loadIndex();
        rescan();
        sweepOldReplays();
    }

    /**
     * Reads {@code library.json} only (fast: no files are probed), so the grid can show the clips right away.
     * Entries that can't be read are skipped one by one instead of losing the whole index; an index that can't be
     * read at all is kept as {@code library.json.broken-<time>} before the folder is scanned again.
     */
    public synchronized void loadIndex() {
        loaded = false;
        clips.clear();
        Path f = metaDir().resolve("library.json");
        if (!Files.exists(f)) return;
        JsonArray list = null;
        String why = null;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (root != null && root.isJsonArray()) list = root.getAsJsonArray();
            else why = "not a list of clips";
        } catch (Exception e) {
            why = e.getMessage();
        }
        if (list == null) {
            Path kept = backupIndex(f);
            System.err.println("[clips] library index unreadable (" + why + "), rebuilding" + (kept == null ? "" : "; kept as " + kept.getFileName()));
            notice("Library index couldn't be read", "Your clips were found again, but titles, tags and edits may be missing."
                    + (kept == null ? "" : " The old index is kept as " + kept.getFileName() + "."), true);
            return;
        }
        int skipped = 0;
        Set<String> ids = new HashSet<>();
        for (JsonElement el : list) {
            Clip c;
            try { c = GSON.fromJson(el, Clip.class); }
            catch (Exception e) {
                skipped++;
                String id = el != null && el.isJsonObject() && el.getAsJsonObject().has("id") ? String.valueOf(el.getAsJsonObject().get("id")) : "?";
                System.err.println("[clips] skipped a library entry that can't be read (id " + id + "): " + e.getMessage());
                continue;
            }
            if (c == null || c.id == null || c.file == null || !ids.add(c.id)) continue;
            if (c.tags == null) c.tags = new ArrayList<>();
            if (c.markers == null) c.markers = new ArrayList<>();
            if (c.trackNames == null) c.trackNames = new ArrayList<>();
            if (c.markerLabels == null) c.markerLabels = new java.util.HashMap<>();
            if (c.kind == null) c.kind = Clip.Kind.IMPORT;
            c.tags.removeIf(java.util.Objects::isNull);
            c.markers.removeIf(java.util.Objects::isNull);
            clips.add(c);
        }
        // Keep the original so nothing is lost when the index is next written without the unreadable entries.
        if (skipped > 0) backupIndex(f);
    }

    private static Path backupIndex(Path f) {
        try {
            Path kept = f.resolveSibling("library.json.broken-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            Files.copy(f, kept, StandardCopyOption.REPLACE_EXISTING);
            return kept;
        } catch (IOException e) {
            System.err.println("[clips] couldn't keep a copy of the library index: " + e.getMessage());
            return null;
        }
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

    /** A video found in the folder, probed outside the library lock. */
    private record Probed(Path file, Ffmpeg.MediaInfo info) {}

    /**
     * Drops entries whose file is gone and adds videos that were put in the folder by hand (up to Imported/Console
     * and Imported/Phone). Files are probed outside the library lock, a few at a time, so saving a replay never
     * waits for a first scan of a big folder; new clips appear in batches as they're found.
     */
    public void rescan() {
        Set<String> known = new HashSet<>();
        synchronized (this) {
            // A name this system can't represent (e.g. "®" without a UTF-8 locale) mustn't take the library down.
            clips.removeIf(c -> { try { return !Files.exists(c.path(root())); } catch (java.nio.file.InvalidPathException e) { return false; } });
            for (Clip c : clips) known.add(c.file);
        }
        List<Path> fresh = new ArrayList<>();
        Path meta = metaDir();
        if (Files.isDirectory(root())) {
            try (Stream<Path> s = Files.walk(root(), 4)) {
                s.filter(p -> !p.startsWith(meta))
                        .filter(p -> VIDEO_EXT.contains(ext(p)))
                        .filter(Files::isRegularFile)
                        .filter(p -> { try { return !known.contains(rel(p)); } catch (RuntimeException e) { return false; } })
                        .forEach(fresh::add);
            } catch (IOException | java.io.UncheckedIOException e) {
                System.err.println("[clips] couldn't scan the clips folder: " + e.getMessage());
            }
        }
        if (!fresh.isEmpty()) {
            ExecutorService probes = Executors.newFixedThreadPool(Math.min(3, fresh.size()), r -> {
                Thread t = new Thread(r, "library-scan");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<Probed>> found = new ArrayList<>();
                for (Path p : fresh) found.add(probes.submit(() -> {
                    try { return new Probed(p, Ffmpeg.probe(p)); }
                    catch (IOException e) { System.err.println("[clips] skipped " + p.getFileName() + ": " + e.getMessage()); return null; }
                }));
                List<Probed> batch = new ArrayList<>();
                for (Future<Probed> f : found) {
                    Probed p;
                    try { p = f.get(); } catch (Exception e) { p = null; }
                    if (p != null) batch.add(p);
                    if (batch.size() >= 20) addBatch(batch);
                }
                addBatch(batch);
            } finally {
                probes.shutdownNow();
            }
        }
        synchronized (this) {
            for (Clip c : clips) {
                try { if (!Files.exists(thumb(c))) queueThumbnails(c); } catch (java.nio.file.InvalidPathException ignored) {}
            }
            save();
        }
        loaded = true;
        changed();
        upgradeFormat();
    }

    /** Adds probed files that are still unknown (another scan may have added them meanwhile). */
    private void addBatch(List<Probed> batch) {
        if (batch.isEmpty()) return;
        synchronized (this) {
            Set<String> known = new HashSet<>();
            for (Clip c : clips) known.add(c.file);
            for (Probed p : batch) {
                if (!Files.exists(p.file()) || known.contains(rel(p.file()))) continue;
                try { addProbed(p.file(), kindFor(p.file()), null, null, p.info()); }
                catch (IOException e) { System.err.println("[clips] skipped " + p.file().getFileName() + ": " + e.getMessage()); }
            }
        }
        batch.clear();
        changed();
    }

    /** Once per library: measures imported clips again (rotation-aware) and redraws their pictures if that changed. */
    private void upgradeFormat() {
        Path f = metaDir().resolve("format");
        int have = 1;
        try { if (Files.exists(f)) have = Integer.parseInt(Files.readString(f).trim()); } catch (Exception ignored) {}
        if (have >= FORMAT || !Ffmpeg.available() || !Files.isDirectory(root())) return;
        thumbs.submit(() -> {
            boolean any = false;
            for (Clip c : all()) {
                if (c.kind != Clip.Kind.IMPORT) continue;
                try {
                    Ffmpeg.MediaInfo info = Ffmpeg.probe(c.path(root()));
                    if (info.width() > 0 && (info.width() != c.width || info.height() != c.height)) {
                        c.width = info.width();
                        c.height = info.height();
                        any = true;
                        makeThumbnails(c);
                    }
                } catch (Exception ignored) {}
            }
            if (any) { save(); changed(); }
            try { Files.createDirectories(metaDir()); Files.writeString(f, String.valueOf(FORMAT)); } catch (IOException ignored) {}
        });
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
    public Clip addExisting(Path file, Clip.Kind kind, String title, String game) throws IOException {
        Ffmpeg.MediaInfo info = Ffmpeg.probe(file); // outside the lock: probing can take a moment
        synchronized (this) { return addProbed(file, kind, title, game, info); }
    }

    private synchronized Clip addProbed(Path file, Clip.Kind kind, String title, String game, Ffmpeg.MediaInfo info) throws IOException {
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
        return addNew(file, kind, title, game, markers, null, sourceId);
    }

    /** Like {@link #addNew(Path, Clip.Kind, String, String, List, String)} with marker names. */
    public Clip addNew(Path file, Clip.Kind kind, String title, String game, List<Double> markers,
                       java.util.Map<Double, String> labels, String sourceId) throws IOException {
        Ffmpeg.MediaInfo info = Ffmpeg.probe(file);
        Clip c;
        Cleanup cleaned;
        synchronized (this) {
            c = addProbed(file, kind, title, game, info);
            c.created = System.currentTimeMillis();
            if (markers != null) c.markers.addAll(markers);
            if (labels != null) labels.forEach((t, l) -> c.markerLabels.put(Clip.key(t), l));
            c.sourceId = sourceId;
            if (sourceId != null) {
                Clip src = get(sourceId);
                if (src != null) { c.tags.addAll(src.tags); c.game = src.game; }
            }
            cleaned = enforceLimit(c);
            save();
        }
        report(cleaned);
        sweepOldReplays();
        changed();
        for (var l : addedListeners) { try { l.accept(c); } catch (Exception e) { System.err.println("[clips] " + e); } }
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
        c.trackNames = new ArrayList<>(info.trackNames());
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

    /**
     * Swaps a clip's file for a smaller re-encode (see Exporter.shrink), keeping its title, tags, markers and
     * edits. Returns bytes saved.
     */
    public long replaceFile(Clip c, Path smaller) throws IOException {
        Path old = c.path(root());
        long before = c.size;
        Path dest = old.resolveSibling(prettyName(old) + ".mp4");
        if (!dest.equals(old) && Files.exists(dest)) dest = unique(old.getParent(), prettyName(old) + ".mp4");
        Files.move(smaller, dest.equals(old) ? old : dest, StandardCopyOption.REPLACE_EXISTING);
        if (!dest.equals(old)) Files.deleteIfExists(old);
        Ffmpeg.MediaInfo info = Ffmpeg.probe(dest);
        c.file = rel(dest);
        applyInfo(c, info, dest);
        if (!c.tags.contains("shrunk")) c.tags.add("shrunk");
        save();
        changed();
        return before - c.size;
    }

    /** Deletes clips (to the OS recycle bin where possible). Returns how many were removed. */
    public int delete(List<Clip> list) {
        int n = 0;
        boolean settingsChanged = false;
        synchronized (this) {
            for (Clip c : list) {
                // Locked: favourites only go when unfavourited first (protects them from bulk cleanups).
                if (c.favorite && settings.protectFavorites) continue;
                String phoneId = c.fromPhone() ? c.shareId : null;
                if (purge(c)) {
                    n++;
                    // Still on the server (its link wasn't removed): don't download it from the phone gallery again.
                    if (phoneId != null) {
                        synchronized (settings) {
                            settings.deletedPhoneIds.add(phoneId);
                            while (settings.deletedPhoneIds.size() > 5000) settings.deletedPhoneIds.remove(settings.deletedPhoneIds.iterator().next());
                        }
                        settingsChanged = true;
                    }
                }
            }
            save();
        }
        if (settingsChanged) settings.save();
        changed();
        return n;
    }

    /**
     * Removes one clip's file (to the recycle bin where the system has one), its pictures and its entry. Returns
     * false if the file couldn't be removed (the entry stays).
     */
    private synchronized boolean purge(Clip c) {
        Path p = c.path(root());
        boolean gone = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH))
                gone = Desktop.getDesktop().moveToTrash(p.toFile());
        } catch (Exception ignored) {}
        try { if (!gone) gone = Files.deleteIfExists(p) || !Files.exists(p); } catch (IOException ignored) {}
        if (gone) {
            clips.remove(c);
            deleteThumbs(c);
        }
        return gone;
    }

    /** Every cached picture of a clip in the thumbnails folder (thumbnail, strips, waveforms): "<id>.jpg" and "<id>-*". */
    private void deleteThumbs(Clip c) {
        try { Files.deleteIfExists(thumb(c)); } catch (IOException ignored) {}
        if (!Files.isDirectory(thumbsDir())) return;
        try (var s = Files.newDirectoryStream(thumbsDir(), c.id + "-*")) {
            for (Path p : s) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        } catch (IOException ignored) {}
    }

    /** What an automatic clean-up removed. */
    private record Cleanup(int count, long bytes, boolean full) {
        static final Cleanup NONE = new Cleanup(0, 0, false);
    }

    /** Kept by the storage limit: exports, imports, favourites, shared clips and the clip just added. */
    private static boolean keptByLimit(Clip c, Clip keep) {
        return c == keep || c.favorite || c.kind == Clip.Kind.EXPORT || c.kind == Clip.Kind.IMPORT || c.shareId != null;
    }

    /**
     * Keeps the library under the storage limit by deleting the oldest replays first, then the oldest recordings.
     * Exports, imports, favourites, shared clips and the clip that was just added are never deleted, and their
     * size is set aside first: if they alone are over the limit nothing is deleted and the user is told.
     */
    private synchronized Cleanup enforceLimit(Clip keep) {
        if (settings.storageLimitGb <= 0) return Cleanup.NONE;
        long limit = (long) (settings.storageLimitGb * 1024 * 1024 * 1024);
        long total = totalBytes();
        if (total <= limit) return Cleanup.NONE;
        long keptBytes = 0;
        List<Clip> victims = new ArrayList<>();
        for (Clip c : clips) {
            if (keptByLimit(c, keep)) keptBytes += c.size;
            else victims.add(c);
        }
        long budget = limit - keptBytes;
        if (budget <= 0) return new Cleanup(0, 0, true);
        victims.sort(Comparator.comparing((Clip c) -> c.kind != Clip.Kind.REPLAY).thenComparingLong(c -> c.created));
        long rest = total - keptBytes;
        int n = 0;
        long freed = 0;
        for (Clip v : victims) {
            if (rest <= budget) break;
            long size = v.size;
            if (!purge(v)) continue;
            rest -= size;
            freed += size;
            n++;
        }
        return new Cleanup(n, freed, false);
    }

    private void report(Cleanup c) {
        if (c.full()) {
            // Once an hour at most: every saved clip would say it again.
            if (System.currentTimeMillis() - lastFullWarning < 3_600_000L) return;
            lastFullWarning = System.currentTimeMillis();
            notice("Storage limit reached", "Exports, favorites and shared clips alone are over the "
                    + gb(settings.storageLimitGb) + " limit, so nothing was deleted. Raise it in Settings → Storage.", true);
        } else if (c.count() > 0) {
            System.err.println("[clips] storage limit: removed " + c.count() + " old clip(s)");
            notice("Storage limit: removed " + c.count() + " old clip" + (c.count() == 1 ? "" : "s"),
                    "Freed " + sizeText(c.bytes()) + " to stay under " + gb(settings.storageLimitGb), false);
        }
    }

    /**
     * Deletes instant replays older than {@link Settings#replayMaxAgeDays} (not favourites or shared clips;
     * recordings, exports and imports are never touched). Returns the bytes freed.
     */
    public long sweepOldReplays() {
        int days = settings.replayMaxAgeDays;
        if (days <= 0) return 0;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        long freed = 0;
        int n = 0;
        synchronized (this) {
            for (Clip c : new ArrayList<>(clips)) {
                if (c.kind != Clip.Kind.REPLAY || c.favorite || c.shareId != null || c.created >= cutoff) continue;
                long size = c.size;
                if (purge(c)) { freed += size; n++; }
            }
            if (n > 0) save();
        }
        if (n > 0) {
            changed();
            notice("Old replays cleaned", n + " replay" + (n == 1 ? "" : "s") + " older than " + days + " days · Freed " + sizeText(freed), false);
        }
        return freed;
    }

    private static String gb(double v) {
        if (v < 1) return sizeText((long) (v * 1024 * 1024 * 1024));
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v)) + " GB";
    }

    /** "1.2 GB", "340.5 MB" (the same wording as the app). */
    public static String sizeText(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    /**
     * Moves every clip (and the thumbnails) into {@code dest} and makes it the clips folder. Files are renamed in
     * place on the same drive and copied otherwise. If one can't be moved, the ones already moved go back and
     * nothing changes. Saving a clip waits until the move is done. Call {@link #load()} afterwards.
     */
    public synchronized void moveTo(Path dest, java.util.function.DoubleConsumer progress) throws IOException {
        Path from = root().toAbsolutePath().normalize();
        Path to = dest.toAbsolutePath().normalize();
        if (to.equals(from)) return;
        if (to.startsWith(from)) throw new IOException("Pick a folder outside the current clips folder.");
        Files.createDirectories(to);
        List<Clip> list = new ArrayList<>(clips);
        long total = 1, done = 0;
        for (Clip c : list) total += Math.max(0, c.size);
        List<Object[]> moved = new ArrayList<>(); // {clip, old file, source, destination}
        try {
            for (Clip c : list) {
                Path src = c.path(from);
                if (!Files.exists(src)) continue;
                Path dst = to.resolve(c.file.replace('/', java.io.File.separatorChar));
                Files.createDirectories(dst.getParent());
                if (Files.exists(dst)) dst = unique(dst.getParent(), dst.getFileName().toString());
                moveFile(src, dst);
                moved.add(new Object[]{c, c.file, src, dst});
                c.file = to.relativize(dst).toString().replace(java.io.File.separatorChar, '/');
                done += Math.max(0, c.size);
                if (progress != null) progress.accept(done / (double) total);
            }
        } catch (IOException e) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                Object[] m = moved.get(i);
                try { moveFile((Path) m[3], (Path) m[2]); ((Clip) m[0]).file = (String) m[1]; }
                catch (IOException ignored) { System.err.println("[clips] couldn't put back " + m[3]); }
            }
            throw e;
        }
        // The pictures come along (they're only a cache: anything that doesn't move is drawn again).
        Path oldThumbs = from.resolve(".evadeclips").resolve("thumbs"), newThumbs = to.resolve(".evadeclips").resolve("thumbs");
        if (Files.isDirectory(oldThumbs)) {
            Files.createDirectories(newThumbs);
            try (var s = Files.newDirectoryStream(oldThumbs)) {
                for (Path p : s) try { moveFile(p, newThumbs.resolve(p.getFileName().toString())); } catch (IOException ignored) {}
            } catch (IOException ignored) {}
        }
        settings.clipsFolder = to.toString();
        settings.save();
        save();
        // What's left of the old index would bring the moved clips back as missing files: keep a copy only.
        try { Files.move(from.resolve(".evadeclips").resolve("library.json"), from.resolve(".evadeclips").resolve("library.json.moved"), StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException ignored) {}
    }

    private static void moveFile(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Another drive: copy, then remove the original.
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
            try { Files.delete(src); }
            catch (IOException del) { Files.deleteIfExists(dst); throw del; }
        }
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
        makeCover(c);
        makeStrip(c);
    }

    /**
     * Makes the frame at {@code t} (seconds in the source) the clip's cover: the library thumbnail and the share
     * link's picture. The thumbnail is redrawn in the background; {@link #awaitCover} waits for it.
     */
    public void setCover(Clip c, double t) {
        c.coverAt = Math.max(0, Math.min(t, Math.max(0, c.duration - 0.05)));
        coverGen.merge(c.id, 1, Integer::sum);
        update(c);
        Future<?> f = thumbs.submit(() -> {
            try {
                makeCover(c);
                changed();
            } catch (Exception e) {
                System.err.println("[clips] cover failed for " + c.file + ": " + e.getMessage());
            }
        });
        coverJobs.put(c.id, f);
    }

    /** Waits (up to {@code ms}) for a cover chosen with {@link #setCover} to be drawn. */
    public void awaitCover(Clip c, long ms) {
        Future<?> f = coverJobs.get(c.id);
        if (f == null) return;
        try { f.get(ms, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        coverJobs.remove(c.id, f);
    }

    /** Where the cover comes from: the chosen frame, else the middle of the first part that's kept. */
    static double coverTime(Clip c) {
        if (c.coverAt >= 0 && c.coverAt < c.duration) return c.coverAt;
        double start = 0, end = c.duration;
        if (c.edit != null && c.edit.segments != null && !c.edit.segments.isEmpty()) {
            var s = c.edit.segments.get(0);
            if (s.end > s.start) { start = s.start; end = Math.min(c.duration, s.end); }
        }
        double at = (start + end) / 2;
        return Math.max(0, Math.min(at, Math.max(0, c.duration - 0.1)));
    }

    /** The 480×270 library thumbnail (written next to its final name first, so it's never read half-written). */
    void makeCover(Clip c) throws IOException {
        Files.createDirectories(thumbsDir());
        int gen = coverGen.getOrDefault(c.id, 0);
        Path tmp = thumbsDir().resolve(c.id + "-cover-" + System.nanoTime() + ".tmp.jpg");
        try {
            Ffmpeg.run(List.of("-ss", fmt(coverTime(c)), "-i", c.path(root()).toString(), "-frames:v", "1",
                    "-vf", "scale=480:270:force_original_aspect_ratio=decrease,pad=480:270:(ow-iw)/2:(oh-ih)/2:color=black",
                    "-q:v", "4", tmp.toString()));
            synchronized (coverGen) {
                if (Files.exists(tmp) && gen == coverGen.getOrDefault(c.id, 0)) moveInto(tmp, thumb(c));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** {@link #STRIP_FRAMES} frames side by side for hover-scrubbing. */
    void makeStrip(Clip c) throws IOException {
        if (c.duration <= 0.5) return;
        Files.createDirectories(thumbsDir());
        Path tmp = thumbsDir().resolve(c.id + "-strip-" + System.nanoTime() + ".tmp.jpg");
        try {
            double rate = STRIP_FRAMES / c.duration;
            Ffmpeg.run(List.of("-i", c.path(root()).toString(), "-vf",
                    "fps=" + fmt(rate) + ",scale=192:108:force_original_aspect_ratio=decrease,pad=192:108:(ow-iw)/2:(oh-ih)/2:color=black,tile=" + STRIP_FRAMES + "x1",
                    "-frames:v", "1", "-q:v", "5", tmp.toString()));
            if (Files.exists(tmp)) moveInto(tmp, strip(c));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveInto(Path tmp, Path dest) throws IOException {
        try { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); }
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
