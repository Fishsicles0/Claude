package net.evadeclient.clips.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Console captures and other outside clips, imported automatically. Point it at a USB drive or a sync folder:
 * PS5 ("PS5/CREATE/Video Clips/<Game>/…"), Xbox ("<Game>-2025_01_31-20_15_00.mp4") and Switch ("Nintendo/Album/…")
 * layouts get the right game name and a console tag. Each file is imported once (copied, the original stays).
 */
public final class WatchFolders {
    private static final Pattern XBOX_NAME = Pattern.compile("^(.+?)[-_ ]+\\d{4}[-_]\\d{2}[-_]\\d{2}.*$");

    private WatchFolders() {}

    /**
     * What identifies one imported file: its path, size and modification time. Kept in
     * {@link Settings#watchImported} for good, so a capture you delete isn't imported again (a file replaced by
     * a new capture under the same name is).
     */
    static String key(Path f) throws IOException {
        return f.toAbsolutePath().normalize() + "|" + Files.size(f) + "|" + Files.getLastModifiedTime(f).toMillis();
    }

    /** Imports new videos from every watched folder. Returns what was added. */
    public static List<Clip> scan(Settings s, Library lib) {
        List<Clip> added = new ArrayList<>();
        Set<String> seen;
        synchronized (s) { seen = new HashSet<>(s.watchImported); }
        // Older versions only remembered the path, on the clip itself.
        for (Clip c : lib.all()) if (c.importedFrom != null) seen.add(c.importedFrom);
        for (String folder : new ArrayList<>(s.watchFolders)) {
            Path root;
            try { root = Path.of(folder); } catch (RuntimeException e) { continue; }
            if (!Files.isDirectory(root)) continue; // drive not plugged in
            List<Path> files;
            try (Stream<Path> st = Files.walk(root, 6)) {
                files = st.filter(Files::isRegularFile).filter(p -> Library.VIDEO_EXT.contains(Library.ext(p)) && !Library.ext(p).equals("gif")).toList();
            } catch (IOException | java.io.UncheckedIOException e) { continue; }
            for (Path f : files) {
                String path = f.toAbsolutePath().normalize().toString();
                if (seen.contains(path)) continue;
                try {
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < 5000) continue; // still being written
                    String key = key(f);
                    if (seen.contains(key)) continue;
                    String[] gc = gameAndConsole(f);
                    Path dir = lib.folder(Clip.Kind.IMPORT).resolve("Console");
                    Files.createDirectories(dir);
                    Path dest = Library.unique(dir, f.getFileName().toString());
                    Files.copy(f, dest);
                    Clip c;
                    try {
                        c = lib.addNew(dest, Clip.Kind.IMPORT, null, gc[0], null, null);
                    } catch (IOException e) {
                        Files.deleteIfExists(dest);
                        throw e;
                    }
                    c.importedFrom = path;
                    c.created = Files.getLastModifiedTime(f).toMillis();
                    if (gc[1] != null && !c.tags.contains(gc[1])) c.tags.add(gc[1]);
                    if (!c.tags.contains("console") && gc[1] != null) c.tags.add("console");
                    lib.update(c);
                    added.add(c);
                    seen.add(key);
                    synchronized (s) { s.watchImported.add(key); }
                    s.save();
                } catch (IOException e) {
                    System.err.println("[clips] couldn't import " + f + ": " + e.getMessage());
                }
            }
        }
        return added;
    }

    /**
     * Lets everything in a watched folder be imported again on the next scan ("Re-import"), except files whose
     * clip is still in the library. Returns how many files were forgotten.
     */
    public static int forgetFolder(Settings s, String folder) {
        String prefix = Path.of(folder).toAbsolutePath().normalize().toString();
        String sep = prefix.endsWith(java.io.File.separator) ? prefix : prefix + java.io.File.separator;
        int before;
        synchronized (s) {
            before = s.watchImported.size();
            s.watchImported.removeIf(k -> k.startsWith(sep));
            before -= s.watchImported.size();
        }
        if (before > 0) s.save();
        return before;
    }

    /** {game, console tag} from where a capture lives and how it's named. */
    static String[] gameAndConsole(Path f) {
        String p = f.toString().replace('\\', '/');
        String lower = p.toLowerCase(Locale.ROOT);
        String file = f.getFileName().toString();
        String base = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        if (lower.contains("/ps5/") || lower.contains("/ps4/") || lower.contains("/create/video clips/") || lower.contains("/sharefactory/")) {
            String parent = f.getParent() == null ? "" : f.getParent().getFileName().toString();
            String console = lower.contains("/ps4/") ? "ps4" : "ps5";
            return new String[]{parent.equalsIgnoreCase("Video Clips") ? "" : parent, console};
        }
        if (lower.contains("/nintendo/album/")) return new String[]{"", "switch"};
        Matcher m = XBOX_NAME.matcher(base);
        if (lower.contains("xbox") || m.matches()) return new String[]{m.matches() ? m.group(1).trim() : "", "xbox"};
        String parent = f.getParent() == null ? "" : f.getParent().getFileName().toString();
        return new String[]{parent.matches("\\d+") ? "" : parent, null};
    }
}
