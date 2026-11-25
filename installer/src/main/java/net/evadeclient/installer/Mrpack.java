package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Modpacks in Modrinth's .mrpack format, and short share codes.
 * <ul>
 * <li><b>Import</b> makes a NEW instance from a pack made for Minecraft 1.21.11 with Fabric (any other is refused
 * with a clear reason): every listed file is downloaded from an allowed host and checked against its SHA-512, the
 * pack's overrides are copied in, and Evade goes on top at the first Play. Parts Evade manages itself (Fabric API,
 * the FPS mods, Iris, Simple Voice Chat) are left to the instance's options instead of the pack's copies.</li>
 * <li><b>Export</b> writes an instance as a .mrpack: files Modrinth has are listed by URL and hash; the player's
 * other files go in overrides. Evade's own jar, switched-off mods, and jars whose licence doesn't allow sharing
 * copies are left out (each one noted).</li>
 * <li><b>Share codes</b> are a short text (a few hundred characters) naming the instance's Modrinth projects and
 * options; a friend pastes it to get a new instance with the newest version of each.</li>
 * </ul>
 */
final class Mrpack {
    private Mrpack() {}

    static final String PACK_FILE = "evade-pack.json";

    /** What the pack says about itself. */
    record Info(String name, String version, String summary, String minecraft, String loader, String loaderVersion, int files, String problem) {
        boolean ok() { return problem == null; }
    }

    /** The result of an import or export: the instance (import) or file (export), and what was left out or changed. */
    record Result(Launcher.Instance instance, Path file, int count, List<String> notes) {}

    // ------------------------------------------------------------------ reading

    static JsonObject index(ZipFile z) throws IOException {
        ZipEntry e = z.getEntry("modrinth.index.json");
        if (e == null) throw new IOException("This isn't a Modrinth modpack (no modrinth.index.json inside)");
        try (InputStream in = z.getInputStream(e)) {
            return JsonParser.parseString(new String(in.readNBytes(32 << 20), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("The modpack's index can't be read", ex);
        }
    }

    /** Reads a .mrpack's index and says whether Evade can use it. */
    static Info inspect(Path file) throws IOException {
        try (ZipFile z = new ZipFile(file.toFile())) {
            return info(index(z));
        } catch (java.util.zip.ZipException e) {
            throw new IOException("That file isn't a modpack (.mrpack)", e);
        }
    }

    static Info info(JsonObject idx) {
        String name = InstallerCore.str(idx, "name"), version = InstallerCore.str(idx, "versionId"), summary = InstallerCore.str(idx, "summary");
        JsonObject deps = idx.has("dependencies") && idx.get("dependencies").isJsonObject() ? idx.getAsJsonObject("dependencies") : new JsonObject();
        String mc = InstallerCore.str(deps, "minecraft");
        String loader = null, loaderVersion = null;
        for (String l : new String[]{"fabric-loader", "quilt-loader", "forge", "neoforge"}) {
            if (deps.has(l)) { loader = l; loaderVersion = InstallerCore.str(deps, l); break; }
        }
        int files = idx.has("files") && idx.get("files").isJsonArray() ? idx.getAsJsonArray("files").size() : 0;
        String problem = null;
        if (!"minecraft".equals(InstallerCore.str(idx, "game"))) problem = "This modpack isn't for Minecraft: Java Edition.";
        else if (idx.has("formatVersion") && idx.get("formatVersion").getAsInt() != 1) problem = "This modpack uses a newer .mrpack format than Evade understands.";
        else if (!InstallerCore.MC_VERSION.equals(mc))
            problem = "This modpack is for Minecraft " + (mc == null ? "an unknown version" : mc) + ". Evade Client runs on " + InstallerCore.MC_VERSION + " only, so it can't be imported.";
        else if (!"fabric-loader".equals(loader))
            problem = "This modpack uses " + (loader == null ? "no mod loader" : loaderName(loader)) + ". Evade Client runs on Fabric, so it can't be imported.";
        return new Info(name == null || name.isBlank() ? "Modpack" : name.strip(), version, summary, mc, loader, loaderVersion, files, problem);
    }

    private static String loaderName(String l) {
        return switch (l) {
            case "quilt-loader" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> l;
        };
    }

    // ------------------------------------------------------------------ import

    /** Modrinth project ids that Evade's own install handles (they're the instance's options, never the pack's copies). */
    private static String managedOption(String slug) {
        if (slug == null) return null;
        if (slug.equals("fabric-api")) return "Evade";
        return InstallerCore.optionForSlug(slug);
    }

    /** The project id in a Modrinth CDN URL (https://cdn.modrinth.com/data/<project>/versions/<version>/<file>), or null. */
    static String projectOfUrl(String url) {
        if (url == null || !url.startsWith("https://cdn.modrinth.com/data/")) return null;
        String rest = url.substring("https://cdn.modrinth.com/data/".length());
        int s = rest.indexOf('/');
        return s > 0 ? rest.substring(0, s) : null;
    }

    /**
     * A safe relative path inside an instance for a pack entry: no absolute paths, no "..", nothing in Evade's own
     * files. Returns null when the path isn't acceptable.
     */
    static Path safePath(Path root, String rel) {
        if (rel == null || rel.isBlank() || rel.startsWith("/") || rel.startsWith("\\") || rel.contains(":") || rel.contains("\\")) return null;
        for (String part : rel.split("/")) if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        Path p;
        try { p = root.resolve(rel).normalize(); } catch (java.nio.file.InvalidPathException e) { return null; }
        if (!p.startsWith(root) || p.equals(root)) return null;
        String top = root.relativize(p).getName(0).toString().toLowerCase(Locale.ROOT);
        if (top.startsWith("evade-") || top.equals(".evade") || top.equals("evade-install.json")) return null;
        return p;
    }

    /**
     * Imports a .mrpack into a new instance. Only Fabric 1.21.11 packs; everything downloaded is checked against its
     * SHA-512 and only from the hosts the format allows. The new instance installs Evade on its first Play.
     */
    static Result importPack(Launcher.Settings s, List<Launcher.Instance> all, Path file, int memoryGb, InstallerCore.Progress p) throws IOException {
        List<String> notes = new ArrayList<>();
        try (ZipFile z = new ZipFile(file.toFile())) {
            JsonObject idx = index(z);
            Info info = info(idx);
            if (!info.ok()) throw new IOException(info.problem());
            JsonArray files = idx.has("files") && idx.get("files").isJsonArray() ? idx.getAsJsonArray("files") : new JsonArray();

            // Which listed files are Evade-managed projects (looked up once for all Modrinth-hosted files).
            p.update(0.02, "Reading " + info.name());
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement e : files) {
                JsonObject f = e.getAsJsonObject();
                for (JsonElement u : f.has("downloads") ? f.getAsJsonArray("downloads") : new JsonArray()) {
                    String pid = projectOfUrl(u.getAsString());
                    if (pid != null) ids.add(pid);
                }
            }
            Map<String, ContentApi.Project> projects = new LinkedHashMap<>();
            try { projects = ContentApi.projects(ids); } catch (IOException e) { /* titles and managed checks fall back to file names */ }

            Launcher.Instance inst = Launcher.create(s, all, info.name(), null, memoryGb, false, false, false);
            Path root = inst.dir.toAbsolutePath().normalize();
            boolean ok = false;
            try {
                JsonObject modsRecord = new JsonObject();
                Set<String> options = new LinkedHashSet<>();
                int n = files.size(), i = 0, placed = 0;
                for (JsonElement e : files) {
                    JsonObject f = e.getAsJsonObject();
                    double a = 0.05 + 0.75 * i / Math.max(1, n), b = 0.05 + 0.75 * (i + 1) / Math.max(1, n);
                    i++;
                    String rel = InstallerCore.str(f, "path");
                    JsonObject env = f.has("env") && f.get("env").isJsonObject() ? f.getAsJsonObject("env") : null;
                    if (env != null && "unsupported".equals(InstallerCore.str(env, "client"))) continue; // server-only
                    Path target = safePath(root, rel);
                    if (target == null) { notes.add("Skipped " + rel + " (unsafe path)"); continue; }
                    JsonObject hashes = f.has("hashes") && f.get("hashes").isJsonObject() ? f.getAsJsonObject("hashes") : new JsonObject();
                    String sha512 = InstallerCore.str(hashes, "sha512");
                    String url = null, pid = null;
                    for (JsonElement u : f.has("downloads") ? f.getAsJsonArray("downloads") : new JsonArray()) {
                        if (ContentApi.allowed(u.getAsString(), ContentApi.PACK_HOSTS)) { url = u.getAsString(); break; }
                    }
                    if (url != null) pid = projectOfUrl(url);
                    ContentApi.Project proj = pid != null ? projects.get(pid) : null;
                    String option = proj != null ? managedOption(proj.slug()) : null;
                    String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (option == null && rel.startsWith("mods/") && (lower.startsWith("fabric-api") || lower.startsWith("evade-client"))) option = "Evade";
                    if (option != null) {
                        if (!option.equals("Evade")) options.add(option);
                        continue; // Evade's install adds its own copy
                    }
                    if (url == null) { notes.add("Skipped " + rel + " (no download from an allowed host)"); continue; }
                    if (sha512 == null) { notes.add("Skipped " + rel + " (no SHA-512 listed)"); continue; }
                    String label = proj != null ? proj.title() : target.getFileName().toString();
                    p.update(a, "Downloading " + label);
                    ContentApi.download(url, target, sha512, ContentApi.PACK_HOSTS, x -> p.update(a + (b - a) * x, null));
                    placed++;
                    if (pid != null && rel.startsWith("mods/") && rel.indexOf('/', 5) < 0) {
                        JsonObject v = new JsonObject();
                        v.addProperty("file", target.getFileName().toString());
                        v.addProperty("title", label);
                        v.addProperty("fromPack", info.name());
                        modsRecord.add(pid, v);
                    } else if (pid != null && proj != null && (rel.startsWith("resourcepacks/") || rel.startsWith("shaderpacks/")) && rel.indexOf('/', rel.indexOf('/') + 1) < 0) {
                        Content.remember(inst.dir, rel.startsWith("resourcepacks/") ? Content.Type.RESOURCEPACK : Content.Type.SHADER, pid,
                                target.getFileName().toString(), "", proj.title());
                    }
                }
                // Overrides: the pack's own files (config, options, packs...). client-overrides win over overrides.
                p.update(0.82, "Copying the pack's settings");
                for (String prefix : new String[]{"overrides/", "client-overrides/"}) placed += extract(z, prefix, root, notes);
                if (!modsRecord.keySet().isEmpty()) InstallerCore.writeAtomic(inst.dir.resolve(Mods.RECORD), InstallerCore.GSON.toJson(modsRecord));

                // Evade goes on top: the pack's managed parts become the instance's options.
                inst.perf = options.contains("FPS mods");
                inst.shaders = options.contains("Shaders");
                inst.voice = options.contains("Voice chat");
                if (options.contains("Pack features")) inst.packs = true;
                if (options.contains("Dynamic lights")) inst.lights = true;
                if (inst.shaders) notes.add("Shaders (Iris) is switched on: the pack uses it");
                inst.dirty = true;
                Launcher.save(inst);
                JsonObject meta = new JsonObject();
                meta.addProperty("name", info.name());
                if (info.version() != null) meta.addProperty("version", info.version());
                meta.addProperty("source", file.getFileName().toString());
                meta.addProperty("loader", info.loaderVersion());
                InstallerCore.writeAtomic(inst.dir.resolve(PACK_FILE), InstallerCore.GSON.toJson(meta));
                Launcher.refresh(inst);
                ok = true;
                p.update(1, "Imported");
                return new Result(inst, null, placed, notes);
            } finally {
                if (!ok) {
                    try { Launcher.delete(s, inst); } catch (IOException ignored) { }
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("That file isn't a modpack (.mrpack)", e);
        }
    }

    private static int extract(ZipFile z, String prefix, Path root, List<String> notes) throws IOException {
        int n = 0;
        var en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.getName().startsWith(prefix) || e.isDirectory()) continue;
            String rel = e.getName().substring(prefix.length());
            Path target = safePath(root, rel);
            if (target == null) { notes.add("Skipped " + rel + " (unsafe path)"); continue; }
            String lower = rel.toLowerCase(Locale.ROOT);
            if (lower.startsWith("mods/") && (target.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("evade-client")
                    || target.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("fabric-api"))) continue;
            Files.createDirectories(target.getParent());
            try (InputStream in = z.getInputStream(e); OutputStream out = Files.newOutputStream(target)) {
                long copied = in.transferTo(out);
                if (copied > ContentApi.MAX_FILE) throw new IOException(rel + " is too large");
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ export

    /** What goes in an exported pack besides the mods. */
    record ExportOptions(boolean config, boolean options, boolean resourcePacks, boolean shaderPacks, boolean servers) {
        static ExportOptions defaults() { return new ExportOptions(true, true, true, true, false); }
    }

    /** Words in file names that are never exported (sign-in caches and the like). */
    private static final String[] PRIVATE = {"token", "secret", "password", "account", "auth", "session", "credential", "cookie"};

    static boolean privateName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        for (String w : PRIVATE) if (l.contains(w)) return true;
        return false;
    }

    /** Writes the instance as a .mrpack at {@code out}. */
    static Result export(Launcher.Instance inst, Path out, ExportOptions o, InstallerCore.Progress p) throws IOException {
        Path game = inst.dir;
        List<String> notes = new ArrayList<>();
        JsonObject marker = InstallerCore.readMarker(game);
        // Candidate files by type: mods (on), and the pack folders.
        Map<Path, Content.Type> candidates = new LinkedHashMap<>();
        List<Path> overrides = new ArrayList<>();
        Path mods = game.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
                for (Path f : ds) {
                    String n = f.getFileName().toString(), l = n.toLowerCase(Locale.ROOT);
                    if (!Files.isRegularFile(f)) continue;
                    if (l.endsWith(".jar.disabled")) { notes.add("Left out " + n + " (switched off)"); continue; }
                    if (!l.endsWith(".jar")) continue;
                    if (l.startsWith("evade-client")) { notes.add("Left out Evade Client (it's installed by the Evade Launcher, not shared in packs)"); continue; }
                    candidates.put(f, Content.Type.MOD);
                }
            }
        }
        for (Content.Type t : new Content.Type[]{Content.Type.RESOURCEPACK, Content.Type.SHADER}) {
            if (t == Content.Type.RESOURCEPACK ? !o.resourcePacks() : !o.shaderPacks()) continue;
            Path dir = game.resolve(t.folder);
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path f : ds) if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) candidates.put(f, t);
                    else if (Files.isDirectory(f)) notes.add("Left out " + t.folder + "/" + f.getFileName() + " (a folder pack: zip it to share it)");
            }
        }
        p.update(0.05, "Looking files up on Modrinth");
        Map<String, Path> byHash = new LinkedHashMap<>();
        Map<Path, String> sha1 = new LinkedHashMap<>();
        for (Path f : candidates.keySet()) {
            String h = ContentApi.sha1(f);
            byHash.put(h, f);
            sha1.put(f, h);
        }
        Map<String, ContentApi.Version> known = ContentApi.identify(byHash.keySet());
        JsonArray files = new JsonArray();
        int i = 0;
        for (var en : candidates.entrySet()) {
            Path f = en.getKey();
            String rel = game.relativize(f).toString().replace('\\', '/');
            p.update(0.1 + 0.5 * i++ / Math.max(1, candidates.size()), "Checking " + f.getFileName());
            ContentApi.Version v = known.get(sha1.get(f));
            if (v != null && v.url() != null && ContentApi.allowed(v.url(), ContentApi.PACK_HOSTS)) {
                JsonObject e = new JsonObject();
                e.addProperty("path", rel);
                JsonObject h = new JsonObject();
                h.addProperty("sha1", sha1.get(f));
                h.addProperty("sha512", ContentApi.sha512(f));
                e.add("hashes", h);
                JsonObject env = new JsonObject();
                env.addProperty("client", "required");
                env.addProperty("server", en.getValue() == Content.Type.MOD ? "optional" : "unsupported");
                e.add("env", env);
                JsonArray dl = new JsonArray();
                // The file's own URL on Modrinth (the hash matched the primary file, or another file of that version).
                dl.add(v.sha1() != null && v.sha1().equalsIgnoreCase(sha1.get(f)) ? v.url() : fileUrl(v, f));
                e.add("downloads", dl);
                e.addProperty("fileSize", Files.size(f));
                files.add(e);
                continue;
            }
            // Not on Modrinth: only files whose licence allows sharing copies go in overrides.
            if (en.getValue() == Content.Type.MOD) {
                ModChecks.Meta m = ModChecks.read(f);
                String option = InstallerCore.optionForJar(marker, f.getFileName().toString());
                if (option != null && option.equals("Evade")) { notes.add("Left out " + f.getFileName() + " (installed by the Evade Launcher)"); continue; }
                if (m == null || !ModChecks.redistributable(m.license())) {
                    notes.add("Left out " + (m != null ? m.name() : f.getFileName()) + " (not on Modrinth, and its licence "
                            + (m == null || m.license() == null ? "isn't stated" : "(" + m.license() + ") doesn't allow sharing copies") + ")");
                    continue;
                }
                overrides.add(f);
            } else {
                notes.add("Left out " + rel + " (not on Modrinth, so its licence is unknown)");
            }
        }
        if (o.config() && Files.isDirectory(game.resolve("config"))) {
            Files.walkFileTree(game.resolve("config"), new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (privateName(game.resolve("config").relativize(f).toString())) notes.add("Left out config/" + game.resolve("config").relativize(f).toString().replace('\\', '/') + " (may hold private data)");
                    else if (a.size() < 16L << 20) overrides.add(f);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (o.options()) for (String n : new String[]{"options.txt", "optionsshaders.txt"}) if (Files.isRegularFile(game.resolve(n))) overrides.add(game.resolve(n));
        if (o.servers() && Files.isRegularFile(game.resolve("servers.dat"))) overrides.add(game.resolve("servers.dat"));

        JsonObject idx = new JsonObject();
        idx.addProperty("formatVersion", 1);
        idx.addProperty("game", "minecraft");
        idx.addProperty("versionId", "1.0.0");
        idx.addProperty("name", inst.name);
        idx.addProperty("summary", "Exported from the Evade Launcher");
        idx.add("files", files);
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", InstallerCore.MC_VERSION);
        InstallerCore.Installed in = InstallerCore.readInstalled(game);
        deps.addProperty("fabric-loader", in != null && in.loaderVersion() != null ? in.loaderVersion() : "0.18.6");
        idx.add("dependencies", deps);

        p.update(0.7, "Writing " + out.getFileName());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(InstallerCore.GSON.toJson(idx).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            Set<String> seen = new LinkedHashSet<>();
            for (Path f : overrides) {
                String rel = "overrides/" + game.relativize(f).toString().replace('\\', '/');
                if (!seen.add(rel)) continue;
                zip.putNextEntry(new ZipEntry(rel));
                try (InputStream fin = Files.newInputStream(f)) { fin.transferTo(zip); }
                zip.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        InstallerCore.move(tmp, out);
        p.update(1, "Exported");
        return new Result(inst, out, files.size() + overrides.size(), notes);
    }

    /** A version's file URL for a jar that isn't its primary file (best effort: the primary URL's folder + the name). */
    private static String fileUrl(ContentApi.Version v, Path f) {
        String u = v.url();
        int s = u.lastIndexOf('/');
        return s > 0 ? u.substring(0, s + 1) + java.net.URLEncoder.encode(f.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20") : u;
    }

    // ------------------------------------------------------------------ share codes

    /**
     * Instance share codes start with {@code EVI1-}. Launchers before 2.5 wrote {@code EVADE1-}, which is still read;
     * {@code EVADE1:} (with a colon) is the in-game menu's profile code, a different thing, and gets its own message.
     */
    static final String CODE_PREFIX = "EVI1-", OLD_CODE_PREFIX = "EVADE1-", PROFILE_CODE_PREFIX = "EVADE1:";

    /** What a share code holds: a name, Modrinth project ids by type, and the instance's Evade options. */
    record Share(String name, List<String> mods, List<String> resourcePacks, List<String> shaders, boolean perf, boolean shaderOption, boolean voice,
                 Set<String> optional, Set<String> shaderPacks) {
        /** {@code shaderPacks}: the {@link ShaderPacks} ticked for the Shaders option (null: the defaults). */
        Share {
            shaderPacks = ShaderPacks.clean(shaderPacks);
        }
        Share(String name, List<String> mods, List<String> resourcePacks, List<String> shaders, boolean perf, boolean shaderOption, boolean voice,
              Set<String> optional) {
            this(name, mods, resourcePacks, shaders, perf, shaderOption, voice, optional, null);
        }
        Share(String name, List<String> mods, List<String> resourcePacks, List<String> shaders, boolean perf, boolean shaderOption, boolean voice) {
            this(name, mods, resourcePacks, shaders, perf, shaderOption, voice, Set.of());
        }
    }

    /** The share code for an instance: its Modrinth projects (by id, never files) and options, deflated and base64url. */
    static String shareCode(Launcher.Instance inst, Map<Path, Updates.Known> known) {
        JsonObject o = new JsonObject();
        o.addProperty("n", inst.name);
        JsonArray m = new JsonArray(), r = new JsonArray(), sh = new JsonArray();
        // The shader packs the Shaders option added travel as the pack choice ("sp"), not as projects.
        Set<String> evadePacks = new java.util.HashSet<>();
        JsonObject marker = InstallerCore.readMarker(inst.dir);
        if (marker != null && marker.has("shaderPacks") && marker.get("shaderPacks").isJsonObject()) {
            for (String key : marker.getAsJsonObject("shaderPacks").keySet()) {
                String f = InstallerCore.packFile(marker.getAsJsonObject("shaderPacks"), key);
                if (f != null) evadePacks.add(f.toLowerCase(Locale.ROOT));
            }
        }
        for (Updates.Known k : known.values()) {
            if (k.current() == null || k.current().projectId() == null) continue;
            if (k.file().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".disabled")) continue;
            if (k.type() == Content.Type.SHADER && evadePacks.contains(k.file().getFileName().toString().toLowerCase(Locale.ROOT))) continue;
            String pid = k.current().projectId();
            JsonArray to = k.type() == Content.Type.MOD ? m : k.type() == Content.Type.RESOURCEPACK ? r : k.type() == Content.Type.SHADER ? sh : null;
            if (to != null && !to.contains(new com.google.gson.JsonPrimitive(pid))) to.add(pid);
        }
        o.add("m", m);
        if (!r.isEmpty()) o.add("r", r);
        if (!sh.isEmpty()) o.add("s", sh);
        o.addProperty("o", (inst.perf ? "p" : "") + (inst.shaders ? "s" : "") + (inst.voice ? "v" : ""));
        String extras = OptionalMods.letters(inst.optional);
        if (!extras.isEmpty()) o.addProperty("x", extras); // optional extras (older launchers ignore this key)
        if (!ShaderPacks.isDefault(inst.shaderPacks)) o.addProperty("sp", ShaderPacks.join(inst.shaderPacks)); // ticked shader packs
        byte[] raw = o.toString().getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = d.deflate(buf);
        d.end();
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(buf, n));
    }

    static Share readCode(String code) throws IOException {
        String c = code == null ? "" : code.strip().replaceAll("\\s+", "");
        if (c.startsWith(PROFILE_CODE_PREFIX))
            throw new IOException("That's an Evade profile code (for the in-game menu's Profiles page), not an instance code. Instance codes start with " + CODE_PREFIX);
        String prefix = c.startsWith(CODE_PREFIX) ? CODE_PREFIX : c.startsWith(OLD_CODE_PREFIX) ? OLD_CODE_PREFIX : null;
        if (prefix == null) throw new IOException("That isn't an Evade instance code (they start with " + CODE_PREFIX + ")");
        try {
            byte[] packed = Base64.getUrlDecoder().decode(c.substring(prefix.length()));
            Inflater inf = new Inflater(true);
            inf.setInput(packed);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                b.write(buf, 0, n);
                if (b.size() > 256_000) throw new IOException("Share code too large");
            }
            inf.end();
            JsonObject o = JsonParser.parseString(b.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            String opts = InstallerCore.str(o, "o") == null ? "pv" : InstallerCore.str(o, "o");
            return new Share(InstallerCore.str(o, "n") == null ? "Shared" : InstallerCore.str(o, "n"), ids(o, "m"), ids(o, "r"), ids(o, "s"),
                    opts.contains("p"), opts.contains("s"), opts.contains("v"), OptionalMods.fromLetters(InstallerCore.str(o, "x")),
                    o.has("sp") ? ShaderPacks.clean(List.of(InstallerCore.str(o, "sp").split(","))) : null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("That share code is damaged. Copy all of it and try again.", e);
        }
    }

    private static List<String> ids(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) for (JsonElement e : o.getAsJsonArray(k)) {
            String s = e.getAsString();
            if (s.matches("[A-Za-z0-9]{6,12}")) out.add(s);
        }
        return out;
    }

    /** Makes a new instance from a share code: each project's newest version for 1.21.11, mods with what they require. */
    static Result fromCode(InstallerCore core, Launcher.Settings s, List<Launcher.Instance> all, Share share, int memoryGb, InstallerCore.Progress p) throws IOException {
        List<String> notes = new ArrayList<>();
        Launcher.Instance inst = Launcher.create(s, all, share.name(), null, memoryGb, share.perf(), share.shaderOption(), share.voice());
        if ((share.optional() != null && !share.optional().isEmpty()) || !ShaderPacks.isDefault(share.shaderPacks())) {
            inst.optional = OptionalMods.clean(share.optional());
            inst.shaderPacks = ShaderPacks.clean(share.shaderPacks());
            Launcher.save(inst);
        }
        Map<String, ContentApi.Project> projects;
        try {
            List<String> ids = new ArrayList<>(share.mods());
            ids.addAll(share.resourcePacks());
            ids.addAll(share.shaders());
            projects = ContentApi.projects(ids);
        } catch (IOException e) {
            try { Launcher.delete(s, inst); } catch (IOException ignored) { }
            throw e;
        }
        int total = share.mods().size() + share.resourcePacks().size() + share.shaders().size(), i = 0, placed = 0;
        Set<String> have = new LinkedHashSet<>();
        for (Content.Type t : new Content.Type[]{Content.Type.MOD, Content.Type.RESOURCEPACK, Content.Type.SHADER}) {
            List<String> list = t == Content.Type.MOD ? share.mods() : t == Content.Type.RESOURCEPACK ? share.resourcePacks() : share.shaders();
            for (String pid : list) {
                double a = 0.05 + 0.9 * i / Math.max(1, total), b = 0.05 + 0.9 * (i + 1) / Math.max(1, total);
                i++;
                ContentApi.Project pr = projects.get(pid);
                if (pr == null) { notes.add("A project in the code isn't on Modrinth any more"); continue; }
                if (managedOption(pr.slug()) != null) continue; // the options cover it
                ShaderPacks.Pack chosen = t == Content.Type.SHADER && share.shaderOption() ? ShaderPacks.bySlug(pr.slug()) : null;
                if (chosen != null && share.shaderPacks().contains(chosen.key())) continue; // Play adds it with the Shaders option
                Mods.Hit hit = new Mods.Hit(pr.id(), pr.slug(), pr.title(), "", "", 0, 0);
                try {
                    InstallerCore.Progress sub = (f, m) -> p.update(a + (b - a) * f, m);
                    if (t == Content.Type.MOD) {
                        if (Mods.installedProjects(inst.dir).contains(pid)) continue;
                        Mods.install(core, inst.dir, hit, sub);
                    } else Content.installPack(inst.dir, t, hit, null, sub);
                    have.add(pid);
                    placed++;
                } catch (Exception e) {
                    notes.add(pr.title() + ": " + EvadeInstaller.friendly(e));
                }
            }
        }
        Launcher.refresh(inst);
        p.update(1, "Done");
        return new Result(inst, null, placed, notes);
    }
}
