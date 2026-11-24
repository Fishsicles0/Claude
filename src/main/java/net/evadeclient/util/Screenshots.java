package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.ScreenshotTools;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * This game folder's screenshots/ for the gallery: listing, rename and delete-to-trash with undo. Only bare PNG file
 * names inside that folder are ever touched (the same rule as Screenshot Tools' chat buttons).
 *
 * <p>Deleting moves the file into {@code screenshots/.trash/} so Undo can put it back; files there are removed for
 * good after {@link #TRASH_DAYS} days (checked whenever the gallery opens). Blocking file work: call it off the render
 * thread where a folder can be large.
 */
public final class Screenshots {
    private Screenshots() {}

    public static final String TRASH = ".trash";
    public static final int TRASH_DAYS = 7;

    /** One screenshot: its file, size in bytes and last-modified time (ms). */
    public record Shot(Path file, long size, long modified) {
        public String name() { return file.getFileName().toString(); }
    }

    public static Path folder() { return ScreenshotTools.folder(); }

    public static Path trashFolder() { return folder().resolve(TRASH); }

    /** Every PNG in the screenshots folder (not the trash), newest first. */
    public static List<Shot> list() {
        Path dir = folder();
        List<Shot> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.{png,PNG}")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p) || p.getFileName().toString().startsWith(".")) continue;
                try {
                    out.add(new Shot(p, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                    // gone or unreadable: leave it out
                }
            }
        } catch (IOException e) {
            EvadeClient.LOG.warn("Screenshots: couldn't list {}", dir, e);
        }
        out.sort(Comparator.comparingLong(Shot::modified).reversed().thenComparing(Shot::name));
        return out;
    }

    /** {@code name} as a screenshot file name: trimmed, ".png" added when missing. Null if it can't be one. */
    public static String cleanName(String name) {
        if (name == null) return null;
        String n = name.strip();
        if (!n.toLowerCase(Locale.ROOT).endsWith(".png")) n = n + ".png";
        return ScreenshotTools.resolve(n) == null ? null : n;
    }

    /**
     * Renames {@code shot} to {@code newName} (".png" is added when left out). Returns the new path, or throws with a
     * message to show: a name that isn't allowed, or one already taken.
     */
    public static Path rename(Path shot, String newName) throws IOException {
        String n = cleanName(newName);
        if (n == null) throw new IOException("Use a plain file name (no / \\ : * ? \" < > |)");
        Path target = folder().resolve(n).normalize();
        if (!inFolder(shot)) throw new IOException("That file isn't in your screenshots folder");
        if (target.equals(shot)) return shot;
        // Same name in other letter case: allowed (a two-step move works on case-insensitive disks too).
        boolean caseOnly = target.toString().equalsIgnoreCase(shot.toString());
        if (!caseOnly && Files.exists(target)) throw new IOException(n + " already exists");
        if (caseOnly) {
            Path tmp = shot.resolveSibling(".evade-rename-" + System.nanoTime() + ".png");
            Files.move(shot, tmp);
            return Files.move(tmp, target);
        }
        return Files.move(shot, target);
    }

    /** A file moved to the trash: where it is now and where Undo puts it back. */
    public record Trashed(Path original, Path inTrash) {}

    /** Moves {@code shot} into the trash folder. */
    public static Trashed trash(Path shot) throws IOException {
        if (!inFolder(shot)) throw new IOException("That file isn't in your screenshots folder");
        Path bin = trashFolder();
        Files.createDirectories(bin);
        String name = shot.getFileName().toString();
        Path dest = bin.resolve(name);
        for (int i = 2; Files.exists(dest); i++) dest = bin.resolve(name.substring(0, name.length() - 4) + " (" + i + ").png");
        Path moved = Files.move(shot, dest);
        // The trash empties by age since deletion, not since the screenshot was taken.
        try { Files.setLastModifiedTime(moved, FileTime.fromMillis(System.currentTimeMillis())); } catch (IOException ignored) {}
        return new Trashed(shot, moved);
    }

    /** Undo: puts a trashed file back under its old name (or "name (2).png" when that is taken now). */
    public static Path restore(Trashed t) throws IOException {
        Path dest = t.original();
        String name = dest.getFileName().toString();
        for (int i = 2; Files.exists(dest); i++) dest = dest.resolveSibling(name.substring(0, name.length() - 4) + " (" + i + ").png");
        return Files.move(t.inTrash(), dest);
    }

    /** Removes trashed files older than {@link #TRASH_DAYS} days. Returns how many went. */
    public static int emptyOldTrash() {
        Path bin = trashFolder();
        if (!Files.isDirectory(bin)) return 0;
        long cutoff = System.currentTimeMillis() - TRASH_DAYS * 86_400_000L;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(bin, "*.{png,PNG}")) {
            for (Path p : ds) {
                try {
                    if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.delete(p);
                        n++;
                    }
                } catch (IOException ignored) {
                    // try again next time
                }
            }
        } catch (IOException ignored) {
            // no trash yet
        }
        return n;
    }

    /** Whether {@code p} is a file directly inside the screenshots folder. */
    private static boolean inFolder(Path p) {
        return p != null && folder().equals(p.toAbsolutePath().normalize().getParent());
    }
}
