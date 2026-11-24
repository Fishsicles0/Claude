package net.evadeclient.installer;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Update checks for what the player added to an instance: their own mods (never the jars Evade and its options
 * manage, which Play keeps current) and their resource and shader packs. Each file's SHA-1 is looked up on Modrinth
 * ({@code version_files}) to find which project it is, then {@code version_files/update} gives that project's newest
 * version for Fabric on this Minecraft version. A file Modrinth doesn't know is a manual one and is left alone.
 */
final class Updates {
    private Updates() {}

    /** A file in the instance that Modrinth knows, and its newer version when there is one. */
    record Known(Path file, Content.Type type, String sha1, ContentApi.Version current, ContentApi.Version latest) {
        boolean hasUpdate() {
            return latest != null && latest.url() != null && latest.sha512() != null && (current == null || !latest.id().equals(current.id()))
                    && (latest.sha1() == null || !latest.sha1().equalsIgnoreCase(sha1));
        }
    }

    /** Files of the player's own mods (on or off) that the check covers: never managed ones. */
    static List<Path> playerMods(List<Mods.Entry> mods) {
        List<Path> out = new ArrayList<>();
        for (Mods.Entry e : mods) if (!e.managed()) out.add(e.file());
        return out;
    }

    /**
     * Looks every file up on Modrinth. The result maps each file Modrinth knows to its current version and, when
     * there is a newer one for this Minecraft version (and the type's loaders), the newer one.
     */
    static Map<Path, Known> check(Map<Path, Content.Type> files) throws IOException {
        Map<String, Path> byHash = new LinkedHashMap<>();
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (Path f : files.keySet()) {
            if (!Files.isRegularFile(f)) continue;
            try {
                String h = ContentApi.sha1(f);
                byHash.put(h, f);
                hashes.put(f, h);
            } catch (IOException ignored) { }
        }
        Map<Path, Known> out = new LinkedHashMap<>();
        if (byHash.isEmpty()) return out;
        Map<String, ContentApi.Version> current = ContentApi.identify(byHash.keySet());
        // One update request per type (they differ in loaders).
        Map<Content.Type, List<String>> perType = new LinkedHashMap<>();
        for (var e : current.entrySet()) {
            Content.Type t = files.get(byHash.get(e.getKey()));
            if (t != null) perType.computeIfAbsent(t, k -> new ArrayList<>()).add(e.getKey());
        }
        Map<String, ContentApi.Version> latest = new LinkedHashMap<>();
        for (var e : perType.entrySet()) latest.putAll(ContentApi.latest(e.getValue(), e.getKey().loaders));
        for (var e : current.entrySet()) {
            Path f = byHash.get(e.getKey());
            ContentApi.Version l = latest.get(e.getKey());
            // Only offer a version for the same project (a hash can belong to more than one; trust the project match).
            if (l != null && e.getValue().projectId() != null && !e.getValue().projectId().equals(l.projectId())) l = null;
            out.put(f, new Known(f, files.get(f), e.getKey(), e.getValue(), l));
        }
        return out;
    }

    /**
     * Replaces an installed file with its newer version: downloaded next to it and checked against Modrinth's
     * SHA-512 first, then the old file is removed. A switched-off mod stays switched off. Returns the new file.
     */
    static Path apply(Path gameDir, Known k, InstallerCore.Progress p) throws IOException {
        return apply(gameDir, k, p, null);
    }

    /**
     * As {@link #apply(Path, Known, InstallerCore.Progress)}, and with a {@code batch} the replaced file is kept there
     * (for "Undo last update") instead of deleted. {@code k.latest()} may also be an older version (the version list).
     */
    static Path apply(Path gameDir, Known k, InstallerCore.Progress p, ContentPins.Batch batch) throws IOException {
        if (!k.hasUpdate()) return k.file();
        ContentApi.Version v = k.latest();
        String name = Content.localName(v.filename());
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        boolean jar = k.type() == Content.Type.MOD;
        if (!InstallerCore.plainName(name) || (jar ? !lower.endsWith(".jar") : !lower.endsWith(".zip"))) throw new IOException("Modrinth sent an unexpected file name: " + name);
        if (jar) {
            // Never an Evade-managed jar, whatever the hash says.
            JsonObject marker = InstallerCore.readMarker(gameDir);
            if (InstallerCore.optionForJar(marker, k.file().getFileName().toString()) != null || InstallerCore.optionForJar(marker, name) != null)
                throw new IOException(name + " is kept up to date by Play");
        }
        boolean off = k.file().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".disabled");
        Path dir = k.file().getParent();
        Path target = dir.resolve(off ? name + ".disabled" : name);
        Path staging = dir.resolve(".evade-update-" + Long.toHexString(System.nanoTime()));
        Files.createDirectories(staging);
        try {
            Path tmp = staging.resolve(name);
            ContentApi.download(v.url(), tmp, v.sha512(), Set.of("cdn.modrinth.com"), f -> p.update(0.1 + 0.8 * f, "Downloading " + name));
            if (batch != null) {
                // keep the old file for Undo, then put the new one in its place
                String title = k.current() != null && k.current().name() != null ? k.current().name() : k.file().getFileName().toString();
                JsonObject rec = Content.record(gameDir);
                if (!jar && v.projectId() != null && rec.has(k.type().api) && rec.getAsJsonObject(k.type().api).has(v.projectId())) {
                    String t = InstallerCore.str(rec.getAsJsonObject(k.type().api).getAsJsonObject(v.projectId()), "title");
                    if (t != null) title = t;
                }
                batch.keep(k.file(), target, k.type(), v.projectId(), k.current() != null ? k.current().number() : null, v.number(), title);
                if (Files.exists(target)) Files.delete(tmp); // already there under the new name
                else InstallerCore.move(tmp, target);
            } else if (!target.equals(k.file()) && Files.exists(target)) Files.delete(k.file()); // the new file is already there
            else {
                InstallerCore.move(tmp, target);
                if (!target.equals(k.file())) Files.deleteIfExists(k.file());
            }
        } finally {
            try { InstallerCore.deleteTree(staging); } catch (IOException ignored) { }
        }
        // Keep the records pointing at the new file.
        String oldPlain = k.file().getFileName().toString().replaceAll("(?i)\\.disabled$", "");
        if (jar) {
            JsonObject rec = InstallerCore.readJsonObject(gameDir.resolve(Mods.RECORD));
            boolean found = false;
            for (String key : rec.keySet()) {
                if (!rec.get(key).isJsonObject()) continue;
                JsonObject o = rec.getAsJsonObject(key);
                if (oldPlain.equalsIgnoreCase(InstallerCore.str(o, "file"))) {
                    o.addProperty("file", name);
                    o.addProperty("version", v.number());
                    found = true;
                }
            }
            if (!found && v.projectId() != null) {
                JsonObject o = new JsonObject();
                o.addProperty("file", name);
                o.addProperty("version", v.number());
                rec.add(v.projectId(), o);
            }
            InstallerCore.writeAtomic(gameDir.resolve(Mods.RECORD), InstallerCore.GSON.toJson(rec));
        } else if (v.projectId() != null) {
            String title = name;
            JsonObject all = Content.record(gameDir);
            if (all.has(k.type().api) && all.getAsJsonObject(k.type().api).has(v.projectId())) {
                String t = InstallerCore.str(all.getAsJsonObject(k.type().api).getAsJsonObject(v.projectId()), "title");
                if (t != null) title = t;
            }
            Content.forget(gameDir, k.type(), oldPlain);
            Content.remember(gameDir, k.type(), v.projectId(), name, v.number(), title);
            if (k.type() == Content.Type.RESOURCEPACK) renameResourcePack(gameDir, oldPlain, name);
        }
        p.update(1, "Updated");
        return target;
    }

    /** options.txt names enabled resource packs by file: follow the pack to its new file name. */
    static void renameResourcePack(Path gameDir, String from, String to) {
        Path opt = gameDir.resolve("options.txt");
        if (!Files.isRegularFile(opt) || from.equals(to)) return;
        try {
            String s = Files.readString(opt);
            String n = s.replace("\"file/" + from + "\"", "\"file/" + to + "\"");
            if (!n.equals(s)) InstallerCore.writeAtomic(opt, n);
        } catch (IOException ignored) { }
    }
}
