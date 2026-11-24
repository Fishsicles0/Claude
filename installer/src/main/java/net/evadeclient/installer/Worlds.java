package net.evadeclient.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * An instance's singleplayer worlds (its saves/ folder): what each is (name, game mode, last played, size, seed from
 * level.dat, which is gzipped NBT read with the launcher's own small NBT reader in {@link ServersDat}), and the safe
 * things to do with them: back up to a zip in {@code .evade/backups} (the newest {@value #KEEP} per world are kept),
 * restore a backup, duplicate, rename, and delete to the Recycle Bin / Trash. Nothing is changed while the game has
 * the world open (its session.lock is held).
 */
final class Worlds {
    private Worlds() {}

    /** Backups kept per world (older ones are removed when a new one is made). */
    static final int KEEP = 5;

    record World(Path dir, String folder, String name, long lastPlayed, String mode, boolean hardcore, boolean cheats,
                 String seed, long size, Path icon, String version, boolean locked) {}

    record Backup(Path file, String world, long time, long size) {}

    static Path savesDir(Path gameDir) { return gameDir.resolve("saves"); }

    static Path backupsDir(Path gameDir) { return gameDir.resolve(".evade").resolve("backups"); }

    /** Every world in the instance, most recently played first. */
    static List<World> list(Path gameDir) {
        List<World> out = new ArrayList<>();
        Path saves = savesDir(gameDir);
        if (!Files.isDirectory(saves)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(saves)) {
            for (Path d : ds) {
                if (!Files.isDirectory(d) || !Files.isRegularFile(d.resolve("level.dat"))) continue;
                out.add(read(d));
            }
        } catch (IOException ignored) { }
        out.sort(Comparator.comparingLong(World::lastPlayed).reversed());
        return out;
    }

    /** One world's details (best effort: a level.dat that can't be read still lists the world by its folder). */
    static World read(Path d) {
        String folder = d.getFileName().toString(), name = folder, mode = "", seed = null, version = null;
        long last = 0;
        boolean hardcore = false, cheats = false;
        try {
            Map<String, Object> root = ServersDat.read(Files.readAllBytes(d.resolve("level.dat")));
            if (root.get("Data") instanceof Map<?, ?> data) {
                if (data.get("LevelName") instanceof String s && !s.isBlank()) name = s;
                last = num(data.get("LastPlayed"));
                int gt = (int) num(data.get("GameType"));
                hardcore = num(data.get("hardcore")) != 0;
                cheats = num(data.get("allowCommands")) != 0;
                mode = hardcore ? "Hardcore" : switch (gt) { case 1 -> "Creative"; case 2 -> "Adventure"; case 3 -> "Spectator"; default -> "Survival"; };
                Object s = null;
                if (data.get("WorldGenSettings") instanceof Map<?, ?> wg) s = wg.get("seed");
                if (s == null) s = data.get("RandomSeed");
                if (s != null) seed = Long.toString(num(s));
                if (data.get("Version") instanceof Map<?, ?> v && v.get("Name") instanceof String vn) version = vn;
            }
        } catch (Exception ignored) { }
        if (last == 0) {
            try { last = Files.getLastModifiedTime(d.resolve("level.dat")).toMillis(); } catch (IOException ignored) { }
        }
        Path icon = Files.isRegularFile(d.resolve("icon.png")) ? d.resolve("icon.png") : null;
        return new World(d, folder, name, last, mode, hardcore, cheats, seed, size(d), icon, version, locked(d));
    }

    private static long num(Object o) {
        if (o instanceof ServersDat.Tagged t) o = t.value();
        return o instanceof Number n ? n.longValue() : 0;
    }

    static long size(Path dir) {
        long[] total = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) { total[0] += attrs.size(); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return total[0];
    }

    /** True while the game has the world open: it holds a lock on the world's session.lock. */
    static boolean locked(Path world) {
        Path lock = world.resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            // Windows refuses to even open a file another process locked.
            return InstallerCore.WINDOWS;
        }
    }

    private static void requireClosed(World w) throws IOException {
        if (locked(w.dir())) throw new IOException(w.name() + " is open in the game. Leave the world first.");
    }

    // ------------------------------------------------------------------ backups

    /** The world's backups, newest first. */
    static List<Backup> backups(Path gameDir, String folder) {
        List<Backup> out = new ArrayList<>();
        Path dir = backupsDir(gameDir).resolve(folder);
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) {
            for (Path f : ds) {
                try { out.add(new Backup(f, folder, Files.getLastModifiedTime(f).toMillis(), Files.size(f))); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        out.sort(Comparator.comparingLong(Backup::time).reversed());
        return out;
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    /** Zips the world into {@code .evade/backups/<folder>/<time>.zip} and keeps the newest {@value #KEEP}. */
    static Backup backup(Path gameDir, World w, java.util.function.DoubleConsumer progress) throws IOException {
        Path dir = backupsDir(gameDir).resolve(w.folder());
        Files.createDirectories(dir);
        Path out = dir.resolve(LocalDateTime.now().format(STAMP) + ".zip");
        for (int i = 2; Files.exists(out); i++) out = dir.resolve(LocalDateTime.now().format(STAMP) + "-" + i + ".zip");
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        long total = Math.max(1, w.size());
        long[] done = {0};
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            Path base = w.dir();
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    if (!d.equals(base)) {
                        zip.putNextEntry(new ZipEntry(rel(base, d) + "/"));
                        zip.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    if (f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                    ZipEntry e = new ZipEntry(rel(base, f));
                    e.setLastModifiedTime(a.lastModifiedTime());
                    zip.putNextEntry(e);
                    try (InputStream in = Files.newInputStream(f)) { in.transferTo(zip); }
                    zip.closeEntry();
                    done[0] += a.size();
                    if (progress != null) progress.accept(Math.min(1, done[0] / (double) total));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        InstallerCore.move(tmp, out);
        List<Backup> all = backups(gameDir, w.folder());
        for (int i = KEEP; i < all.size(); i++) Files.deleteIfExists(all.get(i).file());
        return new Backup(out, w.folder(), Files.getLastModifiedTime(out).toMillis(), Files.size(out));
    }

    private static String rel(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    /**
     * Puts a backup back. The world as it is now is backed up first (so a restore can be undone), then replaced. A
     * world that no longer exists is recreated in its folder.
     */
    static void restore(Path gameDir, Backup b, java.util.function.DoubleConsumer progress) throws IOException {
        Path saves = savesDir(gameDir);
        Path target = saves.resolve(b.world());
        if (!InstallerCore.plainName(b.world())) throw new IOException("Bad backup");
        if (Files.isDirectory(target)) {
            World now = read(target);
            requireClosed(now);
            backup(gameDir, now, null);
        }
        Path staging = saves.resolve(".evade-restore-" + Long.toHexString(System.nanoTime()));
        try {
            unzip(b.file(), staging);
            if (!Files.isRegularFile(staging.resolve("level.dat"))) throw new IOException("That backup has no level.dat");
            if (Files.isDirectory(target)) {
                Path aside = saves.resolve(".evade-old-" + Long.toHexString(System.nanoTime()));
                InstallerCore.move(target, aside);
                try {
                    InstallerCore.move(staging, target);
                } catch (IOException e) {
                    InstallerCore.move(aside, target);
                    throw e;
                }
                InstallerCore.deleteTree(aside);
            } else {
                Files.createDirectories(saves);
                InstallerCore.move(staging, target);
            }
        } finally {
            InstallerCore.deleteTree(staging);
        }
        if (progress != null) progress.accept(1);
    }

    /** Extracts a zip into {@code to}, refusing any entry that would land outside it. */
    static void unzip(Path zip, Path to) throws IOException {
        Files.createDirectories(to);
        Path root = to.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path p = root.resolve(e.getName()).normalize();
                if (!p.startsWith(root) || p.equals(root)) throw new IOException("Unsafe path in zip: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(p);
                else {
                    Files.createDirectories(p.getParent());
                    try (OutputStream out = Files.newOutputStream(p)) { in.transferTo(out); }
                    if (e.getLastModifiedTime() != null) Files.setLastModifiedTime(p, e.getLastModifiedTime());
                }
            }
        }
    }

    // ------------------------------------------------------------------ other actions

    /** A folder name in saves/ like {@code want} that isn't taken ("World", "World (2)", ...). */
    static String freeFolder(Path gameDir, String want) {
        String base = want.replaceAll("[\\\\/:*?\"<>|.]", "_").strip();
        if (base.isEmpty()) base = "World";
        if (base.length() > 60) base = base.substring(0, 60).strip();
        String n = base;
        for (int i = 2; Files.exists(savesDir(gameDir).resolve(n)); i++) n = base + " (" + i + ")";
        return n;
    }

    /** A copy of the world, named "<name> copy". */
    static World duplicate(Path gameDir, World w) throws IOException {
        requireClosed(w);
        String folder = freeFolder(gameDir, w.folder() + " copy");
        Path to = savesDir(gameDir).resolve(folder);
        Launcher.copyTree(w.dir(), to, java.util.Set.of("session.lock"));
        setName(to, w.name() + " copy");
        return read(to);
    }

    /** Renames the world (the name in the game's world list; its folder keeps its name). */
    static void rename(World w, String name) throws IOException {
        requireClosed(w);
        if (name == null || name.isBlank()) throw new IOException("Give the world a name");
        setName(w.dir(), name.strip());
    }

    /** Writes a new LevelName into level.dat, keeping everything else exactly; the old file stays as level.dat_old. */
    static void setName(Path world, String name) throws IOException {
        Path dat = world.resolve("level.dat");
        byte[] raw = Files.readAllBytes(dat);
        Map<String, Object> root = ServersDat.read(raw);
        if (!(root.get("Data") instanceof Map<?, ?> d)) throw new IOException("level.dat has no Data");
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) d;
        data.put("LevelName", name);
        byte[] plain = ServersDat.write(root);
        // Check it reads back the same before replacing the real file.
        Map<String, Object> again = ServersDat.read(plain);
        if (!(again.get("Data") instanceof Map<?, ?> d2) || !name.equals(d2.get("LevelName"))) throw new IOException("Couldn't write level.dat");
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(b)) { gz.write(plain); }
        Files.write(world.resolve("level.dat_old"), raw);
        InstallerCore.writeAtomic(dat, b.toByteArray());
    }

    /** Moves the world to the Recycle Bin / Trash (or, where there's none, into the instance's .evade/trash). */
    static void delete(Path gameDir, World w) throws IOException {
        requireClosed(w);
        if (!trash(w.dir(), gameDir)) throw new IOException("Couldn't delete " + w.name());
    }

    /** Sends a file or folder to the system's Recycle Bin / Trash, or into {@code gameDir/.evade/trash} when there isn't one. */
    static boolean trash(Path p, Path gameDir) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
                    && java.awt.Desktop.getDesktop().moveToTrash(p.toFile())) return true;
        } catch (RuntimeException ignored) { }
        try {
            Path bin = gameDir.resolve(".evade").resolve("trash");
            Files.createDirectories(bin);
            Path to = bin.resolve(System.currentTimeMillis() + "-" + p.getFileName());
            InstallerCore.move(p, to);
            Files.setLastModifiedTime(to, FileTime.fromMillis(System.currentTimeMillis()));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The data packs in a world's datapacks/ folder. */
    static List<Content.Pack> datapacks(Path gameDir, World w) {
        return Content.packs(gameDir, Content.Type.DATAPACK, w.folder());
    }
}
