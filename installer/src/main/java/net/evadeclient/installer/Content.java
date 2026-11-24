package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything the launcher can get from Modrinth besides the Evade-managed parts: mods, resource packs, shader packs,
 * data packs and modpacks. Searching uses the same Modrinth search as mods with a different project type; packs go
 * into the instance's resourcepacks/ or shaderpacks/ folder (or a world's datapacks/), each checked against
 * Modrinth's SHA-512 first. What was installed from here is recorded in {@value #RECORD} next to the mods' record.
 */
final class Content {
    private Content() {}

    static final String RECORD = "evade-content.json";

    /** A kind of Modrinth project, with where it goes in an instance. */
    enum Type {
        MOD("mod", "Mods", "mods", List.of("fabric")),
        RESOURCEPACK("resourcepack", "Resource packs", "resourcepacks", List.of("minecraft")),
        SHADER("shader", "Shaders", "shaderpacks", List.of("iris", "optifine")),
        DATAPACK("datapack", "Data packs", null, List.of("datapack")),
        MODPACK("modpack", "Modpacks", null, List.of("fabric"));

        final String api, label, folder;
        final List<String> loaders;

        Type(String api, String label, String folder, List<String> loaders) {
            this.api = api;
            this.label = label;
            this.folder = folder;
            this.loaders = loaders;
        }

        /** "mod", "resource pack", ... for messages. */
        String one() {
            return switch (this) {
                case MOD -> "mod";
                case RESOURCEPACK -> "resource pack";
                case SHADER -> "shader pack";
                case DATAPACK -> "data pack";
                case MODPACK -> "modpack";
            };
        }
    }

    /** Search order: Modrinth's index names. */
    enum Sort {
        RELEVANCE("relevance", "Relevance"), DOWNLOADS("downloads", "Downloads"), UPDATED("updated", "Recently updated"), NEWEST("newest", "Newest");

        final String api, label;

        Sort(String api, String label) {
            this.api = api;
            this.label = label;
        }
    }

    /** A few useful Modrinth categories per type (shown as filter chips): {category, label}. */
    static List<String[]> categories(Type t) {
        return switch (t) {
            case MOD -> List.of(new String[]{"optimization", "Performance"}, new String[]{"utility", "Utility"},
                    new String[]{"decoration", "Decoration"}, new String[]{"storage", "Storage"}, new String[]{"social", "Social"});
            case RESOURCEPACK -> List.of(new String[]{"16x", "16x"}, new String[]{"32x", "32x"}, new String[]{"combat", "PvP"},
                    new String[]{"simplistic", "Simple"}, new String[]{"realistic", "Realistic"}, new String[]{"gui", "GUI"});
            case SHADER -> List.of(new String[]{"potato", "Potato"}, new String[]{"low", "Low"}, new String[]{"medium", "Medium"},
                    new String[]{"high", "High"}, new String[]{"vanilla-like", "Vanilla-like"});
            case DATAPACK -> List.of(new String[]{"worldgen", "World gen"}, new String[]{"adventure", "Adventure"}, new String[]{"utility", "Utility"});
            case MODPACK -> List.of(new String[]{"optimization", "Performance"}, new String[]{"adventure", "Adventure"}, new String[]{"lightweight", "Lightweight"});
        };
    }

    /** Modrinth search facets for {@code t} on this Minecraft version (plus an optional category and client-only filter). */
    static String facets(Type t, String category, boolean clientOnly) {
        List<String> f = new ArrayList<>();
        f.add("[\"project_type:" + t.api + "\"]");
        f.add("[\"versions:" + InstallerCore.MC_VERSION + "\"]");
        switch (t) {
            case MOD, MODPACK -> f.add("[\"categories:fabric\"]");
            case SHADER -> f.add("[\"categories:iris\",\"categories:optifine\"]");
            case DATAPACK -> f.add("[\"categories:datapack\"]");
            default -> { }
        }
        if (category != null) f.add("[\"categories:" + category + "\"]");
        if (clientOnly && t == Type.MOD) f.add("[\"client_side!=unsupported\"]");
        return "[" + String.join(",", f) + "]";
    }

    /** One page (20) of Modrinth results. */
    static Mods.Page search(InstallerCore core, Type t, String query, Sort sort, String category, boolean clientOnly, int offset)
            throws IOException, InterruptedException {
        String q = query == null ? "" : query.strip();
        Sort s = sort == Sort.RELEVANCE && q.isEmpty() ? Sort.DOWNLOADS : sort;
        String url = "https://api.modrinth.com/v2/search?limit=20&offset=" + offset + "&index=" + s.api
                + "&facets=" + InstallerCore.enc(facets(t, category, clientOnly)) + (q.isEmpty() ? "" : "&query=" + InstallerCore.enc(q));
        JsonObject o;
        try {
            o = core.getJson(url).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IOException("Couldn't reach Modrinth", e);
        }
        List<Mods.Hit> hits = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("hits")) {
            JsonObject h = e.getAsJsonObject();
            int color = 0x3A3A44;
            try { if (h.has("color") && !h.get("color").isJsonNull()) color = h.get("color").getAsInt(); } catch (RuntimeException ignored) { }
            long dl = 0;
            try { dl = h.get("downloads").getAsLong(); } catch (RuntimeException ignored) { }
            String desc = InstallerCore.str(h, "description");
            hits.add(new Mods.Hit(InstallerCore.str(h, "project_id"), InstallerCore.str(h, "slug"), InstallerCore.str(h, "title"),
                    InstallerCore.str(h, "author"), desc == null ? "" : desc.replaceAll("\\s+", " ").strip(), dl, color, InstallerCore.str(h, "icon_url")));
        }
        int total = o.has("total_hits") ? o.get("total_hits").getAsInt() : hits.size();
        return new Mods.Page(hits, total);
    }

    // ------------------------------------------------------------------ packs

    /** A resource, shader or data pack in an instance (a zip or a folder). */
    record Pack(Path file, Type type, String name, String description, int packFormat, long size, boolean folder, String projectId) {
        String fileName() { return file.getFileName().toString(); }
    }

    /** The folder {@code t} installs into: the instance's resourcepacks/ or shaderpacks/, or a world's datapacks/. */
    static Path folder(Path gameDir, Type t, String world) {
        if (t == Type.DATAPACK) {
            if (world == null || !InstallerCore.plainName(world)) throw new IllegalArgumentException("Pick a world for the data pack");
            return gameDir.resolve("saves").resolve(world).resolve("datapacks");
        }
        return gameDir.resolve(t.folder);
    }

    /** The packs in a folder: zips and folders, newest first is left to the caller; sorted by name here. */
    static List<Pack> packs(Path gameDir, Type t, String world) {
        List<Pack> out = new ArrayList<>();
        Path dir;
        try { dir = folder(gameDir, t, world); } catch (IllegalArgumentException e) { return out; }
        if (!Files.isDirectory(dir)) return out;
        JsonObject all = record(gameDir);
        JsonObject rec = all.has(t.api) && all.get(t.api).isJsonObject() ? all.getAsJsonObject(t.api) : new JsonObject();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                boolean zip = n.toLowerCase(Locale.ROOT).endsWith(".zip") && Files.isRegularFile(f);
                boolean folder = Files.isDirectory(f) && !n.startsWith(".");
                if (!zip && !folder) continue;
                String[] meta = packMeta(f);
                long size = 0;
                try { size = zip ? Files.size(f) : folderSize(f); } catch (IOException ignored) { }
                String pid = null;
                for (var e : rec.entrySet()) if (e.getValue().isJsonObject() && n.equalsIgnoreCase(InstallerCore.str(e.getValue().getAsJsonObject(), "file"))) pid = e.getKey();
                out.add(new Pack(f, t, n.replaceAll("(?i)\\.zip$", ""), meta[0], meta[1] == null ? -1 : parseInt(meta[1]), size, folder, pid));
            }
        } catch (IOException ignored) { }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    static long folderSize(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        }
    }

    /** {description, pack_format} from a pack's pack.mcmeta (both null when there's none). */
    static String[] packMeta(Path f) {
        String json = null;
        try {
            if (Files.isDirectory(f)) {
                Path m = f.resolve("pack.mcmeta");
                if (Files.isRegularFile(m) && Files.size(m) < 256_000) json = Files.readString(m);
            } else {
                try (ZipFile z = new ZipFile(f.toFile())) {
                    ZipEntry e = z.getEntry("pack.mcmeta");
                    if (e != null && e.getSize() < 256_000) try (InputStream in = z.getInputStream(e)) { json = new String(in.readNBytes(256_000), StandardCharsets.UTF_8); }
                }
            }
            if (json == null) return new String[2];
            JsonObject pack = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("pack");
            String desc = null;
            JsonElement d = pack.get("description");
            if (d != null && d.isJsonPrimitive()) desc = d.getAsString();
            else if (d != null && d.isJsonObject()) desc = InstallerCore.str(d.getAsJsonObject(), "text");
            else if (d != null && d.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement x : d.getAsJsonArray()) {
                    if (x.isJsonPrimitive()) sb.append(x.getAsString());
                    else if (x.isJsonObject() && InstallerCore.str(x.getAsJsonObject(), "text") != null) sb.append(InstallerCore.str(x.getAsJsonObject(), "text"));
                }
                desc = sb.toString();
            }
            if (desc != null) desc = desc.replaceAll("§.", "").replaceAll("\\s+", " ").strip();
            return new String[]{desc, InstallerCore.str(pack, "pack_format")};
        } catch (Exception e) {
            return new String[2];
        }
    }

    static JsonObject record(Path gameDir) {
        return InstallerCore.readJsonObject(gameDir.resolve(RECORD));
    }

    /** Modrinth project ids of the packs of type {@code t} installed from the launcher that are still there. */
    static Set<String> installedProjects(Path gameDir, Type t, String world) {
        Set<String> out = new HashSet<>();
        for (Pack p : packs(gameDir, t, world)) if (p.projectId() != null) out.add(p.projectId());
        return out;
    }

    /**
     * Installs the newest version of a resource, shader or data pack made for this Minecraft version into its folder
     * (a data pack into {@code world}'s datapacks/), checked against Modrinth's SHA-512. Returns the file name.
     */
    static String installPack(Path gameDir, Type t, Mods.Hit hit, String world, InstallerCore.Progress p) throws IOException {
        if (t == Type.MOD || t == Type.MODPACK) throw new IllegalArgumentException(t.name());
        Path dir = folder(gameDir, t, world);
        p.update(0.05, "Finding " + hit.title());
        ContentApi.Version v = ContentApi.newest(hit.projectId(), t.loaders);
        if (v == null) throw new IOException(hit.title() + " has no " + t.one() + " for Minecraft " + InstallerCore.MC_VERSION);
        String file = localName(v.filename());
        if (!InstallerCore.plainName(file) || !file.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IOException("Modrinth sent an unexpected file name: " + file);
        Files.createDirectories(dir);
        ContentApi.download(v.url(), dir.resolve(file), v.sha512(), Set.of("cdn.modrinth.com"), f -> p.update(0.1 + 0.85 * f, "Downloading " + hit.title()));
        remember(gameDir, t, hit.projectId(), file, v.number(), hit.title());
        if (t == Type.RESOURCEPACK) enableResourcePack(gameDir, file);
        p.update(1, "Installed");
        return file;
    }

    /** A file name this computer's file system accepts (characters it can't store become "_"). */
    static String localName(String name) {
        if (name == null) return null;
        try {
            java.nio.file.Path.of(name);
            return name;
        } catch (java.nio.file.InvalidPathException e) {
            return name.replaceAll("[^\\x20-\\x7E]", "_");
        }
    }

    /** Notes a pack installed from Modrinth in {@value #RECORD}. */
    static synchronized void remember(Path gameDir, Type t, String projectId, String file, String version, String title) throws IOException {
        JsonObject rec = record(gameDir);
        JsonObject of = rec.has(t.api) && rec.get(t.api).isJsonObject() ? rec.getAsJsonObject(t.api) : new JsonObject();
        JsonObject v = new JsonObject();
        v.addProperty("file", file);
        v.addProperty("version", version);
        v.addProperty("title", title);
        of.add(projectId, v);
        rec.add(t.api, of);
        InstallerCore.writeAtomic(gameDir.resolve(RECORD), InstallerCore.GSON.toJson(rec));
    }

    /** Forgets {@code file} in the record (it was removed or replaced). */
    static synchronized void forget(Path gameDir, Type t, String file) throws IOException {
        JsonObject rec = record(gameDir);
        if (!rec.has(t.api) || !rec.get(t.api).isJsonObject()) return;
        JsonObject of = rec.getAsJsonObject(t.api);
        for (String k : new ArrayList<>(of.keySet())) {
            if (of.get(k).isJsonObject() && file.equalsIgnoreCase(InstallerCore.str(of.getAsJsonObject(k), "file"))) of.remove(k);
        }
        InstallerCore.writeAtomic(gameDir.resolve(RECORD), InstallerCore.GSON.toJson(rec));
    }

    /** Removes a pack (to the Recycle Bin / Trash where the system has one) and forgets it. */
    static void removePack(Path gameDir, Pack p) throws IOException {
        if (!Worlds.trash(p.file(), gameDir)) throw new IOException("Couldn't remove " + p.fileName());
        forget(gameDir, p.type(), p.fileName());
    }

    /**
     * Adds a newly installed resource pack to the instance's enabled packs in options.txt (at the top of the list,
     * which is the highest priority), so it's on next time the game starts. Done only when options.txt exists; a
     * fresh instance picks packs in game.
     */
    static void enableResourcePack(Path gameDir, String file) {
        Path opt = gameDir.resolve("options.txt");
        if (!Files.isRegularFile(opt)) return;
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(opt, StandardCharsets.UTF_8));
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (!l.startsWith("resourcePacks:")) continue;
                com.google.gson.JsonArray arr;
                try { arr = com.google.gson.JsonParser.parseString(l.substring(14)).getAsJsonArray(); } catch (RuntimeException e) { return; }
                String id = "file/" + file;
                for (JsonElement e : arr) if (e.isJsonPrimitive() && e.getAsString().equals(id)) return;
                arr.add(id); // the game lists packs bottom to top: the last one wins
                lines.set(i, "resourcePacks:" + arr);
                InstallerCore.writeAtomic(opt, String.join("\n", lines) + "\n");
                return;
            }
        } catch (IOException ignored) { }
    }
}
