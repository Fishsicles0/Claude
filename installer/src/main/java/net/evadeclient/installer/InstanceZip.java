package net.evadeclient.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * An instance as one .zip, to keep or to move to another PC. The zip holds the player's own files (settings, mod
 * settings, their own mods, packs, servers and, optionally, worlds) and an {@code evade-export.json} with the
 * instance's options. Evade's own jars and install record are never in it: the imported instance installs them fresh
 * from evadeclient.com and Modrinth on its first Play. Importing checks every path (no absolute paths, no "..", nothing
 * outside the new folder, none of the launcher's own files) and caps the size, so a crafted zip can't write elsewhere
 * or fill the disk.
 */
final class InstanceZip {
    private InstanceZip() {}

    static final String MANIFEST = "evade-export.json";
    /** Top-level entries never exported: logs, caches, Evade's install record and staging, the launcher's files. */
    private static final Set<String> SKIP = Set.of("logs", "crash-reports", "debug", ".evade-staging", ".evade-staging-mods", ".fabric",
            "evade-install.json", Launcher.INSTANCE_FILE, MANIFEST, "screenshots", "downloads");
    /** Import limits: total unpacked size and number of entries. */
    static final long MAX_BYTES = 16L << 30;
    static final int MAX_ENTRIES = 200_000;

    record Result(Launcher.Instance instance, int files, long bytes) {}

    // ------------------------------------------------------------------ export

    /** Writes the instance to {@code zip}. {@code worlds}: include saves/. Returns the number of files written. */
    static int export(Launcher.Instance i, Path zip, boolean worlds, InstallerCore.Progress p) throws IOException {
        Path root = i.dir;
        Set<String> managed = new HashSet<>();
        for (Mods.Entry e : Mods.list(root)) if (e.managed()) managed.add(e.fileName());
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(dir);
                String top = rel.getName(0).toString();
                if (rel.getNameCount() == 1 && (SKIP.contains(top) || (!worlds && top.equals("saves")))) return FileVisitResult.SKIP_SUBTREE;
                // .evade holds the game's session files and world backups; only the instance's own settings go along
                if (top.equals(GameState.DIR) && rel.getNameCount() > 1) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(f);
                String top = rel.getName(0).toString(), name = f.getFileName().toString();
                if (rel.getNameCount() == 1 && SKIP.contains(top)) return FileVisitResult.CONTINUE;
                if (top.equals(GameState.DIR) && !(rel.getNameCount() == 2 && name.equals(InstanceExtras.FILE))) return FileVisitResult.CONTINUE;
                if (top.equals("mods") && rel.getNameCount() == 2 && managed.contains(name)) return FileVisitResult.CONTINUE;
                if (name.equals("session.lock")) return FileVisitResult.CONTINUE;
                files.add(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        int n = 0;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            out.putNextEntry(new ZipEntry(MANIFEST));
            out.write(InstallerCore.GSON.toJson(manifest(i, worlds)).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Path f : files) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                try (InputStream in = Files.newInputStream(f)) {
                    out.putNextEntry(new ZipEntry(rel));
                    in.transferTo(out);
                    out.closeEntry();
                    n++;
                } catch (IOException e) {
                    // a file the game holds open: leave it out
                }
                if (p != null) p.update(files.isEmpty() ? 1 : n / (double) files.size(), rel);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        return n;
    }

    static JsonObject manifest(Launcher.Instance i, boolean worlds) {
        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("name", i.name);
        o.addProperty("minecraft", InstallerCore.MC_VERSION);
        if (i.installed != null) o.addProperty("evade", i.installed.evadeVersion());
        o.addProperty("memoryGb", i.memoryGb);
        o.addProperty("perf", i.perf);
        o.addProperty("shaders", i.shaders);
        o.addProperty("voice", i.voice);
        o.addProperty("packs", i.packs);
        o.addProperty("lights", i.lights);
        o.add("optional", ContentApi.array(OptionalMods.clean(i.optional)));
        o.add("shaderPacks", ContentApi.array(ShaderPacks.clean(i.shaderPacks)));
        o.add("shaderPacksKnown", ContentApi.array(java.util.List.of(ShaderPacks.keys().split(","))));
        o.addProperty("worlds", worlds);
        o.addProperty("exported", Instant.now().toString());
        return o;
    }

    /** A file name for the export: the instance's name, made safe. */
    static String fileName(Launcher.Instance i) {
        String b = i.name.replaceAll("[^A-Za-z0-9 _.-]+", "").strip().replace(' ', '-');
        return (b.isEmpty() ? "instance" : b) + "-evade-instance.zip";
    }

    // ------------------------------------------------------------------ import

    /**
     * Unpacks an exported instance into a new instance. Throws with a plain message when the zip isn't an Evade
     * instance export or holds a path that isn't allowed.
     */
    static Result importZip(Launcher.Settings s, List<Launcher.Instance> all, Path zip, InstallerCore.Progress p) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            // The manifest may sit at the top or inside one folder (a zip re-packed by hand).
            String prefix = null;
            Enumeration<? extends ZipEntry> en = z.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("This zip has too many files to be an Evade instance.");
                String n = e.getName();
                if (n.equals(MANIFEST)) prefix = "";
                else if (prefix == null && n.endsWith("/" + MANIFEST) && n.indexOf('/') == n.length() - MANIFEST.length() - 1)
                    prefix = n.substring(0, n.length() - MANIFEST.length());
            }
            if (prefix == null) throw new IOException("This isn't an Evade instance export (it has no " + MANIFEST + ").");
            JsonObject m;
            try (InputStream in = z.getInputStream(z.getEntry(prefix + MANIFEST))) {
                m = JsonParser.parseString(new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("The instance details in this zip can't be read.");
            }
            String mc = InstallerCore.str(m, "minecraft");
            if (mc != null && !mc.equals(InstallerCore.MC_VERSION))
                throw new IOException("This instance was made for Minecraft " + mc + "; Evade runs on " + InstallerCore.MC_VERSION + ".");

            Path mcDir = s.minecraftDir();
            String wanted = InstallerCore.str(m, "name");
            String name = Launcher.uniqueName(all, wanted == null || wanted.isBlank() ? "Imported" : wanted, null);
            String id = Launcher.newId(mcDir, name);
            Path root = Launcher.instancesRoot(mcDir);
            Files.createDirectories(root);
            Path tmp = Files.createTempDirectory(root, ".import-");
            int files = 0;
            long bytes = 0;
            try {
                List<? extends ZipEntry> entries = java.util.Collections.list(z.entries());
                int k = 0;
                for (ZipEntry e : entries) {
                    k++;
                    String n = e.getName();
                    if (!n.startsWith(prefix)) continue;
                    String rel = n.substring(prefix.length());
                    if (rel.isEmpty() || rel.equals(MANIFEST)) continue;
                    Path dest = safeTarget(tmp, rel);
                    if (dest == null) throw new IOException("This zip has a file in a place it can't go: " + n);
                    if (e.isDirectory()) { Files.createDirectories(dest); continue; }
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = z.getInputStream(e); OutputStream out = Files.newOutputStream(dest)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            bytes += r;
                            if (bytes > MAX_BYTES) throw new IOException("This zip unpacks to more than 16 GB.");
                            out.write(buf, 0, r);
                        }
                    }
                    files++;
                    if (p != null) p.update(k / (double) entries.size(), rel);
                }
                Launcher.Instance i = new Launcher.Instance(id, root.resolve(id), name);
                i.memoryGb = InstallerCore.clampMemory(num(m, "memoryGb", s.defaultMemoryGb));
                i.perf = bool(m, "perf", true);
                i.shaders = bool(m, "shaders", false);
                i.voice = bool(m, "voice", true);
                i.packs = bool(m, "packs", true);
                i.lights = bool(m, "lights", false);
                i.optional = Launcher.readOptional(m);
                i.shaderPacks = Launcher.readShaderPacks(m);
                i.dirty = true;
                i.created = Instant.now().toString();
                Files.createDirectories(tmp.resolve("mods"));
                Files.move(tmp, i.dir);
                Launcher.save(i);
                if (Files.isRegularFile(InstanceExtras.file(i.dir))) {
                    // icon, notes, Java and window size come along; what was written to the old PC's profile doesn't
                    InstanceExtras x = InstanceExtras.load(i.dir);
                    x.javaDirWritten = null;
                    x.argsWritten = null;
                    x.sizeWritten = false;
                    x.iconWritten = false;
                    x.handledSession = 0;
                    x.handledReport = 0;
                    x.save(i.dir);
                }
                Launcher.refresh(i);
                return new Result(i, files, bytes);
            } catch (IOException | RuntimeException e) {
                try { InstallerCore.deleteTree(tmp); } catch (IOException ignored) { }
                throw e;
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("This file isn't a readable zip.");
        }
    }

    /**
     * Where a zip entry goes inside {@code root}, or null when it isn't allowed: absolute paths, drive letters,
     * backslashes, "." / ".." parts, anything that resolves outside {@code root}, and the launcher's own files
     * (install record, instance file, the game's session files; only .evade/launcher.json may come along).
     */
    static Path safeTarget(Path root, String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains(":") || rel.indexOf('\0') >= 0) return null;
        String clean = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
        String[] parts = clean.split("/");
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        String top = parts[0].toLowerCase(Locale.ROOT);
        if (parts.length == 1 && (top.equals("evade-install.json") || top.equals(Launcher.INSTANCE_FILE) || top.startsWith(".evade-staging"))) return null;
        if (top.equals(GameState.DIR) && !(parts.length == 1 || (parts.length == 2 && parts[1].equals(InstanceExtras.FILE)))) return null;
        Path p;
        try { p = root.resolve(clean).normalize(); } catch (java.nio.file.InvalidPathException e) { return null; }
        if (!p.startsWith(root) || p.equals(root)) return null;
        return p;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try { return o.has(k) ? o.get(k).getAsBoolean() : def; } catch (RuntimeException e) { return def; }
    }

    private static int num(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (RuntimeException e) { return def; }
    }
}
