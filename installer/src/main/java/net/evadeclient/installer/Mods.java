package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An instance's mods: the jars in its mods folder (read from each jar's fabric.mod.json), switching them on and off
 * (x.jar &lt;-&gt; x.jar.disabled), removing them, and finding and installing more from Modrinth. Mods installed from
 * Modrinth are recorded in the instance's evade-mods.json, so their libraries aren't installed twice.
 */
final class Mods {
    private Mods() {}

    static final String RECORD = "evade-mods.json";
    private static final String SEARCH = "https://api.modrinth.com/v2/search";
    private static final String PROJECT = "https://api.modrinth.com/v2/project/";

    /** A jar in the mods folder. {@code option} is what manages it ("Evade", "FPS mods", ...) or null for the player's own. */
    record Entry(Path file, boolean enabled, String id, String name, String version, String description, String authors,
                 String option, long size) {
        String fileName() { return file.getFileName().toString(); }
        boolean managed() { return option != null; }
    }

    /** A Modrinth search result. */
    record Hit(String projectId, String slug, String title, String author, String description, long downloads, int color) {}

    record Page(List<Hit> hits, int total) {}

    // ------------------------------------------------------------------ installed mods

    /** Every mod jar in the instance (on or off), managed ones first, then by name. */
    static List<Entry> list(Path gameDir) {
        List<Entry> out = new ArrayList<>();
        JsonObject marker = InstallerCore.readMarker(gameDir);
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path f : ds) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean on = n.endsWith(".jar");
                if (!Files.isRegularFile(f) || !(on || n.endsWith(".jar.disabled"))) continue;
                out.add(read(f, on, InstallerCore.optionForJar(marker, f.getFileName().toString())));
            }
        } catch (IOException ignored) { }
        out.sort((a, b) -> {
            int ma = rank(a.option()), mb = rank(b.option());
            return ma != mb ? Integer.compare(ma, mb) : a.name().compareToIgnoreCase(b.name());
        });
        return out;
    }

    private static int rank(String option) {
        return option == null ? 3 : option.equals("Evade") ? 0 : 1;
    }

    /** One jar's details from its fabric.mod.json (the file name stands in when it has none). */
    static Entry read(Path f, boolean enabled, String option) {
        String file = f.getFileName().toString();
        String base = file.replaceAll("(?i)\\.jar(\\.disabled)?$", "");
        String id = null, name = base, version = "", desc = "", authors = "";
        long size = 0;
        try { size = Files.size(f); } catch (IOException ignored) { }
        try (ZipFile z = new ZipFile(f.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                try (InputStream in = z.getInputStream(e)) {
                    JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    id = InstallerCore.str(o, "id");
                    if (InstallerCore.str(o, "name") != null) name = InstallerCore.str(o, "name");
                    if (InstallerCore.str(o, "version") != null) version = InstallerCore.str(o, "version");
                    if (InstallerCore.str(o, "description") != null) desc = InstallerCore.str(o, "description");
                    if (o.has("authors") && o.get("authors").isJsonArray()) {
                        List<String> a = new ArrayList<>();
                        for (JsonElement x : o.getAsJsonArray("authors")) {
                            if (x.isJsonPrimitive()) a.add(x.getAsString());
                            else if (x.isJsonObject() && InstallerCore.str(x.getAsJsonObject(), "name") != null) a.add(InstallerCore.str(x.getAsJsonObject(), "name"));
                        }
                        authors = String.join(", ", a);
                    }
                }
            }
        } catch (Exception ignored) {
            // Not a readable jar: listed by its file name.
        }
        if (version.contains("${")) version = "";
        return new Entry(f, enabled, id, name, version, desc.replaceAll("\\s+", " ").strip(), authors, option, size);
    }

    /** Switches a mod on or off by renaming x.jar to x.jar.disabled and back. Returns the renamed entry. */
    static Entry setEnabled(Entry e, boolean on) throws IOException {
        if (e.enabled() == on) return e;
        String n = e.fileName();
        Path to = e.file().resolveSibling(on ? n.replaceAll("(?i)\\.disabled$", "") : n + ".disabled");
        if (Files.exists(to)) throw new IOException(to.getFileName() + " is already in the mods folder");
        InstallerCore.move(e.file(), to);
        return new Entry(to, on, e.id(), e.name(), e.version(), e.description(), e.authors(), e.option(), e.size());
    }

    /** Deletes a mod's jar and forgets it in evade-mods.json. */
    static void remove(Path gameDir, Entry e) throws IOException {
        Files.deleteIfExists(e.file());
        JsonObject rec = record(gameDir);
        String plain = e.fileName().replaceAll("(?i)\\.disabled$", "");
        for (String k : new ArrayList<>(rec.keySet())) {
            JsonObject v = rec.get(k).isJsonObject() ? rec.getAsJsonObject(k) : null;
            if (v != null && plain.equalsIgnoreCase(InstallerCore.str(v, "file"))) rec.remove(k);
        }
        InstallerCore.writeAtomic(gameDir.resolve(RECORD), InstallerCore.GSON.toJson(rec));
    }

    private static JsonObject record(Path gameDir) {
        return InstallerCore.readJsonObject(gameDir.resolve(RECORD));
    }

    /** Modrinth project ids already in the instance: installed from here, or added by the instance's options. */
    static Set<String> installedProjects(Path gameDir) {
        Set<String> out = new HashSet<>();
        Path mods = gameDir.resolve("mods");
        JsonObject rec = record(gameDir);
        for (String k : rec.keySet()) {
            JsonObject v = rec.get(k).isJsonObject() ? rec.getAsJsonObject(k) : null;
            String f = v != null ? InstallerCore.str(v, "file") : null;
            if (f != null && InstallerCore.plainName(f) && (Files.exists(mods.resolve(f)) || Files.exists(mods.resolve(f + ".disabled")))) out.add(k);
        }
        JsonObject marker = InstallerCore.readMarker(gameDir);
        if (marker != null && marker.has("addedProjects") && marker.get("addedProjects").isJsonObject()) {
            for (var e : marker.getAsJsonObject("addedProjects").entrySet()) {
                String f = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
                if (f != null && InstallerCore.plainName(f) && Files.exists(mods.resolve(f))) out.add(e.getKey());
            }
        }
        out.add(InstallerCore.FABRIC_API_ID); // always there in an Evade instance
        return out;
    }

    // ------------------------------------------------------------------ Modrinth

    /** Fabric mods for this Minecraft version, most relevant first ({@code query} blank: most downloaded). */
    static Page search(InstallerCore core, String query, int offset) throws IOException, InterruptedException {
        String facets = "[[\"categories:fabric\"],[\"versions:" + InstallerCore.MC_VERSION + "\"],[\"project_type:mod\"]]";
        String q = query == null ? "" : query.strip();
        String url = SEARCH + "?limit=20&offset=" + offset + "&index=" + (q.isEmpty() ? "downloads" : "relevance")
                + "&facets=" + InstallerCore.enc(facets) + (q.isEmpty() ? "" : "&query=" + InstallerCore.enc(q));
        JsonObject o;
        try {
            o = core.getJson(url).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IOException("Couldn't reach Modrinth", e);
        }
        List<Hit> hits = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("hits")) {
            JsonObject h = e.getAsJsonObject();
            int color = 0x3A3A44;
            try { if (h.has("color") && !h.get("color").isJsonNull()) color = h.get("color").getAsInt(); } catch (RuntimeException ignored) { }
            long dl = 0;
            try { dl = h.get("downloads").getAsLong(); } catch (RuntimeException ignored) { }
            String desc = InstallerCore.str(h, "description");
            hits.add(new Hit(InstallerCore.str(h, "project_id"), InstallerCore.str(h, "slug"), InstallerCore.str(h, "title"),
                    InstallerCore.str(h, "author"), desc == null ? "" : desc.replaceAll("\\s+", " ").strip(), dl, color));
        }
        int total = o.has("total_hits") ? o.get("total_hits").getAsInt() : hits.size();
        return new Page(hits, total);
    }

    /**
     * Installs the newest version of a Modrinth project made for Fabric on this Minecraft version, with the mods it
     * requires. Every file is checked against Modrinth's SHA-512 before it goes into the mods folder.
     *
     * @return the names of what was installed (the project first, then its libraries)
     */
    static List<String> install(InstallerCore core, Path gameDir, Hit hit, InstallerCore.Progress p) throws Exception {
        String option = InstallerCore.optionForSlug(hit.slug());
        if (option != null) throw new IOException(hit.title() + " comes with " + option + ": switch on " + option + " for this instance in Instances.");
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Set<String> have = installedProjects(gameDir);
        if (have.contains(hit.projectId())) throw new IOException(hit.title() + " is already installed");

        // Resolve the project and everything it needs first, so nothing is installed when part of it is missing.
        Map<String, InstallerCore.ModFile> plan = new LinkedHashMap<>();
        List<String[]> todo = new ArrayList<>();
        todo.add(new String[]{hit.projectId(), hit.title(), hit.slug()});
        String query = "?game_versions=" + InstallerCore.enc("[\"" + InstallerCore.MC_VERSION + "\"]") + "&loaders=" + InstallerCore.enc("[\"fabric\"]");
        while (!todo.isEmpty()) {
            String[] next = todo.remove(0);
            String pid = next[0];
            if (plan.containsKey(pid) || have.contains(pid)) continue;
            String title = next[1], slug = next[2];
            if (title == null || slug == null) {
                try {
                    JsonObject proj = core.getJson(PROJECT + pid).getAsJsonObject();
                    title = InstallerCore.str(proj, "title");
                    slug = InstallerCore.str(proj, "slug");
                } catch (IOException | RuntimeException e) {
                    throw new IOException("Couldn't reach Modrinth", e);
                }
            }
            String needOption = InstallerCore.optionForSlug(slug);
            if (needOption != null) {
                throw new IOException(hit.title() + " needs " + title + ", which comes with " + needOption + ". Switch on " + needOption
                        + " for this instance in Instances, then install it again.");
            }
            p.update(0.05 + 0.15 * plan.size() / (plan.size() + todo.size() + 1.0), "Finding " + title);
            InstallerCore.ModFile f;
            try {
                f = core.latestOnModrinth(title, PROJECT + pid + "/version" + query);
            } catch (IOException | RuntimeException e) {
                throw new IOException("Couldn't reach Modrinth", e);
            }
            if (f == null) {
                throw new IOException(pid.equals(hit.projectId()) ? hit.title() + " has no version for Fabric " + InstallerCore.MC_VERSION
                        : hit.title() + " needs " + title + ", which has no version for Fabric " + InstallerCore.MC_VERSION);
            }
            plan.put(pid, f);
            for (String dep : f.requires()) if (dep != null && !plan.containsKey(dep) && !have.contains(dep)) todo.add(new String[]{dep, null, null});
        }

        Path staging = gameDir.resolve(".evade-staging-mods");
        InstallerCore.deleteTree(staging);
        Files.createDirectories(staging);
        List<String> names = new ArrayList<>();
        try {
            int i = 0, n = plan.size();
            Map<String, Path> fetched = new LinkedHashMap<>();
            for (var e : plan.entrySet()) {
                InstallerCore.ModFile f = e.getValue();
                double a = 0.2 + 0.75 * i / n, b = 0.2 + 0.75 * (i + 1) / n;
                i++;
                p.update(a, "Downloading " + f.name() + " " + f.version());
                String file = f.filename();
                if (!InstallerCore.plainName(file) || !file.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IOException("Modrinth sent an unexpected file name: " + file);
                Path s = staging.resolve(file);
                core.download(f.url(), s, f.sha512(), "SHA-512", a, b, p);
                fetched.put(e.getKey(), s);
            }
            JsonObject rec = record(gameDir);
            for (var e : fetched.entrySet()) {
                InstallerCore.ModFile f = plan.get(e.getKey());
                InstallerCore.move(e.getValue(), mods.resolve(e.getValue().getFileName().toString()));
                JsonObject v = new JsonObject();
                v.addProperty("file", e.getValue().getFileName().toString());
                v.addProperty("version", f.version());
                v.addProperty("title", f.name());
                if (!e.getKey().equals(hit.projectId())) v.addProperty("libraryFor", hit.projectId());
                rec.add(e.getKey(), v);
                names.add(f.name());
            }
            InstallerCore.writeAtomic(gameDir.resolve(RECORD), InstallerCore.GSON.toJson(rec));
        } finally {
            try { InstallerCore.deleteTree(staging); } catch (IOException ignored) { }
        }
        p.update(1, "Installed");
        return names;
    }

    /** 12345678 -> "12.3M". */
    static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6).replace(".0M", "M");
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1e3).replace(".0K", "K");
        return Long.toString(n);
    }
}
