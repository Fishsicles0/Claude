package net.evadeclient.installer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Deleting an instance with a way back. The folder first moves aside (next to the instances, not listed) and its
 * profile leaves the Minecraft Launcher; for {@link #UNDO_MS} an Undo puts both back. After that, or when the
 * launcher quits, the folder goes to the Recycle Bin / Trash where the system has one (deleted where it hasn't).
 */
final class InstanceTrash {
    private InstanceTrash() {}

    static final long UNDO_MS = 10_000;
    static final String HOLD = "evade-instances-deleted";

    /** What deleting loses, counted before asking. */
    record Summary(int worlds, int screenshots, int mods, long bytes, boolean sizeComplete) {
        String text(String name) { // name kept for callers that want it in the sentence
            StringBuilder b = new StringBuilder();
            b.append(worlds == 0 ? "No worlds" : worlds == 1 ? "1 world" : worlds + " worlds");
            b.append(", ").append(screenshots == 1 ? "1 screenshot" : screenshots + " screenshots");
            b.append(" and ").append(mods == 1 ? "1 mod" : mods + " mods");
            b.append(" (").append(sizeComplete ? "" : "over ").append(size(bytes)).append(") go to the ")
                    .append(InstallerCore.WINDOWS ? "Recycle Bin" : "Trash").append(", and its profile leaves the Minecraft Launcher. ")
                    .append("Undo for 10 seconds.");
            return b.toString();
        }
    }

    /** A deleted instance waiting out its Undo time. */
    record Pending(String id, String name, Path original, Path held, long at) {}

    static Summary summarize(Path dir) {
        int worlds = count(dir.resolve("saves"), true), shots = count(dir.resolve("screenshots"), false), mods = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.resolve("mods"), "*.{jar,disabled}")) {
            for (Path ignored : ds) mods++;
        } catch (IOException ignored) { }
        long[] size = {0, 0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    size[0] += a.size();
                    return ++size[1] > 50_000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return new Summary(worlds, shots, mods, size[0], size[1] <= 50_000);
    }

    private static int count(Path d, boolean dirs) {
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path p : ds) if (dirs ? Files.isRegularFile(p.resolve("level.dat")) : Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".png")) n++;
        } catch (IOException ignored) { }
        return n;
    }

    static String size(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return (b / (1024 * 1024)) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f GB", b / (1024.0 * 1024 * 1024));
    }

    /** Moves the instance aside and takes its profile out. Undo with {@link #restore}, finish with {@link #finish}. */
    static Pending hold(Launcher.Settings s, Launcher.Instance i) throws IOException {
        if (i.isDefault()) throw new IOException("The main instance can't be deleted");
        Path hold = s.minecraftDir().resolve(HOLD);
        Files.createDirectories(hold);
        Path to = hold.resolve(i.id + "-" + System.currentTimeMillis());
        InstallerCore.move(i.dir, to);
        try {
            InstallerCore.removeProfiles(s.minecraftDir(), i.profileKey());
        } catch (IOException e) {
            InstallerCore.move(to, i.dir);
            throw e;
        }
        return new Pending(i.id, i.name, i.dir, to, System.currentTimeMillis());
    }

    /** Puts a held instance back (its profile is written again by the caller). */
    static void restore(Pending p) throws IOException {
        if (Files.exists(p.original())) throw new IOException("A new instance took its folder");
        InstallerCore.move(p.held(), p.original());
    }

    /** Sends a held folder to the Recycle Bin / Trash (or deletes it where there's none). */
    static void finish(Pending p) {
        if (!Files.exists(p.held())) return;
        trash(p.held());
    }

    /** Held folders a closed launcher left behind: finished on start. */
    static void finishLeftovers(Launcher.Settings s) {
        Path hold = s.minecraftDir().resolve(HOLD);
        if (!Files.isDirectory(hold)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(hold)) {
            for (Path p : ds) trash(p);
        } catch (IOException ignored) { }
    }

    private static void trash(Path p) {
        boolean trashed = false;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH))
                trashed = java.awt.Desktop.getDesktop().moveToTrash(p.toFile());
        } catch (RuntimeException ignored) { }
        if (!trashed) {
            try { InstallerCore.deleteTree(p); } catch (IOException e) { System.err.println("Couldn't remove " + p + ": " + e.getMessage()); }
        }
    }
}
