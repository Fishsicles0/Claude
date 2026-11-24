package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The few Modrinth calls the launcher's content pages make besides search: looking files up by hash
 * ({@code version_files}), asking for their newest version ({@code version_files/update}), project details, and
 * downloads that are only kept when their SHA-512 matches. Hosts are fixed: api.modrinth.com for the API, and
 * Modrinth's CDN (plus the GitHub hosts the .mrpack format allows) for files.
 */
final class ContentApi {
    private ContentApi() {}

    static final String API = "https://api.modrinth.com/v2";
    /** Where a .mrpack may download files from (the format's allowed hosts). */
    static final Set<String> PACK_HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");
    private static final String USER_AGENT = LauncherInfo.USER_AGENT;
    /** No single file over this is downloaded (the biggest mods and packs are far smaller). */
    static final long MAX_FILE = 512L << 20;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    // ------------------------------------------------------------------ requests

    static JsonElement get(String url) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(30)).GET().build());
    }

    static JsonElement post(String url, JsonObject body) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    private static JsonElement send(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 404) throw new NotFound();
            if (res.statusCode() == 429) throw new IOException("Modrinth is busy right now. Try again in a minute.");
            if (res.statusCode() != 200) throw new IOException("Modrinth answered HTTP " + res.statusCode());
            return JsonParser.parseString(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled", e);
        } catch (IOException e) {
            if (e instanceof NotFound || e.getMessage() != null && e.getMessage().startsWith("Modrinth")) throw e;
            throw new IOException("Couldn't reach Modrinth", e);
        } catch (RuntimeException e) {
            throw new IOException("Modrinth sent something unexpected", e);
        }
    }

    /** HTTP 404: the project, version or file isn't on Modrinth. */
    static final class NotFound extends IOException {
        NotFound() { super("Not on Modrinth"); }
    }

    static String enc(String s) { return InstallerCore.enc(s); }

    static JsonArray array(Collection<String> values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    // ------------------------------------------------------------------ versions

    /** A Modrinth version's primary file and what it needs. */
    record Version(String id, String projectId, String number, String name, String type, String published, String url,
                   String filename, String sha1, String sha512, long size, List<String> requires, List<String> loaders,
                   List<String> gameVersions, String changelog) {}

    static Version version(JsonObject v) {
        JsonObject file = null;
        JsonArray files = v.has("files") && v.get("files").isJsonArray() ? v.getAsJsonArray("files") : new JsonArray();
        for (JsonElement f : files) {
            JsonObject o = f.getAsJsonObject();
            if (o.has("primary") && o.get("primary").getAsBoolean()) { file = o; break; }
        }
        if (file == null && !files.isEmpty()) file = files.get(0).getAsJsonObject();
        List<String> requires = new java.util.ArrayList<>();
        if (v.has("dependencies") && v.get("dependencies").isJsonArray()) {
            for (JsonElement d : v.getAsJsonArray("dependencies")) {
                JsonObject dep = d.getAsJsonObject();
                if ("required".equals(InstallerCore.str(dep, "dependency_type")) && InstallerCore.str(dep, "project_id") != null) requires.add(InstallerCore.str(dep, "project_id"));
            }
        }
        String sha1 = null, sha512 = null, url = null, name = null;
        long size = 0;
        if (file != null) {
            JsonObject h = file.has("hashes") && file.get("hashes").isJsonObject() ? file.getAsJsonObject("hashes") : new JsonObject();
            sha1 = InstallerCore.str(h, "sha1");
            sha512 = InstallerCore.str(h, "sha512");
            url = InstallerCore.str(file, "url");
            name = InstallerCore.str(file, "filename");
            try { size = file.get("size").getAsLong(); } catch (RuntimeException ignored) { }
        }
        return new Version(InstallerCore.str(v, "id"), InstallerCore.str(v, "project_id"), InstallerCore.str(v, "version_number"),
                InstallerCore.str(v, "name"), InstallerCore.str(v, "version_type"), InstallerCore.str(v, "date_published"), url, name, sha1, sha512, size,
                requires, strings(v, "loaders"), strings(v, "game_versions"), InstallerCore.str(v, "changelog"));
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new java.util.ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) for (JsonElement e : o.getAsJsonArray(k)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    /** Which Modrinth version each file is, by SHA-1 (files Modrinth doesn't know are left out). */
    static Map<String, Version> identify(Collection<String> sha1) throws IOException {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1.isEmpty()) return out;
        JsonObject body = new JsonObject();
        body.add("hashes", array(sha1));
        body.addProperty("algorithm", "sha1");
        JsonElement e = post(API + "/version_files", body);
        if (e.isJsonObject()) for (var en : e.getAsJsonObject().entrySet()) out.put(en.getKey(), version(en.getValue().getAsJsonObject()));
        return out;
    }

    /**
     * The newest version of each file's project for {@code loaders} on this Minecraft version, by SHA-1 (an empty
     * {@code loaders} list asks for any loader; files with no matching version are left out).
     */
    static Map<String, Version> latest(Collection<String> sha1, List<String> loaders) throws IOException {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1.isEmpty()) return out;
        JsonObject body = new JsonObject();
        body.add("hashes", array(sha1));
        body.addProperty("algorithm", "sha1");
        if (!loaders.isEmpty()) body.add("loaders", array(loaders));
        body.add("game_versions", array(List.of(InstallerCore.MC_VERSION)));
        JsonElement e = post(API + "/version_files/update", body);
        if (e.isJsonObject()) for (var en : e.getAsJsonObject().entrySet()) out.put(en.getKey(), version(en.getValue().getAsJsonObject()));
        return out;
    }

    /** The newest version of a project for {@code loaders} on this Minecraft version (releases first), or null. */
    static Version newest(String projectId, List<String> loaders) throws IOException {
        String url = API + "/project/" + enc(projectId) + "/version?game_versions=" + enc("[\"" + InstallerCore.MC_VERSION + "\"]")
                + (loaders.isEmpty() ? "" : "&loaders=" + enc(array(loaders).toString()));
        JsonArray all = get(url).getAsJsonArray();
        Version pick = null;
        for (JsonElement e : all) {
            Version v = version(e.getAsJsonObject());
            if (v.url() == null) continue;
            if ("release".equals(v.type())) return v;
            if (pick == null) pick = v;
        }
        return pick;
    }

    /** Every version of a project for {@code loaders} on this Minecraft version, newest first (the version list). */
    static List<Version> versions(String projectId, List<String> loaders) throws IOException {
        String url = API + "/project/" + enc(projectId) + "/version?game_versions=" + enc("[\"" + InstallerCore.MC_VERSION + "\"]")
                + (loaders.isEmpty() ? "" : "&loaders=" + enc(array(loaders).toString()));
        List<Version> out = new java.util.ArrayList<>();
        for (JsonElement e : get(url).getAsJsonArray()) {
            Version v = version(e.getAsJsonObject());
            if (v.url() != null && v.sha512() != null) out.add(v);
        }
        return out;
    }

    /** A project's title, slug, type, description and body (Markdown), or null when Modrinth has no such project. */
    record Project(String id, String slug, String title, String type, String description, String body, String iconUrl,
                   String license, String clientSide, List<String> categories) {}

    static Project project(String idOrSlug) throws IOException {
        try {
            return project(get(API + "/project/" + enc(idOrSlug)).getAsJsonObject());
        } catch (NotFound e) {
            return null;
        }
    }

    static Map<String, Project> projects(Collection<String> ids) throws IOException {
        Map<String, Project> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        List<String> list = List.copyOf(ids);
        for (int i = 0; i < list.size(); i += 100) {
            JsonElement e = get(API + "/projects?ids=" + enc(array(list.subList(i, Math.min(list.size(), i + 100))).toString()));
            for (JsonElement p : e.getAsJsonArray()) {
                Project pr = project(p.getAsJsonObject());
                out.put(pr.id(), pr);
            }
        }
        return out;
    }

    private static Project project(JsonObject o) {
        String license = null;
        if (o.has("license") && o.get("license").isJsonObject()) license = InstallerCore.str(o.getAsJsonObject("license"), "id");
        return new Project(InstallerCore.str(o, "id"), InstallerCore.str(o, "slug"), InstallerCore.str(o, "title"), InstallerCore.str(o, "project_type"),
                InstallerCore.str(o, "description"), InstallerCore.str(o, "body"), InstallerCore.str(o, "icon_url"), license,
                InstallerCore.str(o, "client_side"), strings(o, "categories"));
    }

    // ------------------------------------------------------------------ files

    static String sha1(Path f) throws IOException { return hash(f, "SHA-1"); }

    static String sha512(Path f) throws IOException { return hash(f, "SHA-512"); }

    static String hash(Path f, String algo) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** True when {@code url} is https on one of {@code hosts}. */
    static boolean allowed(String url, Set<String> hosts) {
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && hosts.contains(u.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Downloads {@code url} to {@code target} (through a .part file next to it) and keeps it only when its SHA-512
     * is {@code sha512}. The URL must be on {@code hosts}.
     */
    static void download(String url, Path target, String sha512, Set<String> hosts, java.util.function.DoubleConsumer progress) throws IOException {
        if (!allowed(url, hosts)) throw new IOException("Won't download from " + InstallerCore.host(url) + " (not an allowed host)");
        if (sha512 == null || sha512.length() != 128) throw new IOException("No SHA-512 listed for " + target.getFileName());
        HttpResponse<InputStream> res;
        try {
            res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled", e);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        boolean ok = false;
        try (InputStream raw = res.body()) {
            if (res.statusCode() != 200) throw new IOException("Couldn't download " + target.getFileName() + " (HTTP " + res.statusCode() + ")");
            long total = res.headers().firstValueAsLong("content-length").orElse(-1);
            if (total > MAX_FILE) throw new IOException(target.getFileName() + " is too large");
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            Files.createDirectories(target.getParent());
            try (InputStream in = InstallerCore.stallGuard(raw, InstallerCore.STALL_MS); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (done > MAX_FILE) throw new IOException(target.getFileName() + " is too large");
                    if (progress != null && total > 0) progress.accept(Math.min(1, done / (double) total));
                }
            }
            if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha512)) throw new IOException(target.getFileName() + " failed its SHA-512 check");
            InstallerCore.move(tmp, target);
            ok = true;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            if (!ok) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }
}
