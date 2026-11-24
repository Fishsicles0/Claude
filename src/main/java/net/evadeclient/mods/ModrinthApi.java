package net.evadeclient.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Modrinth v2 API, and nothing else: every request goes to api.modrinth.com and every download comes from
 * cdn.modrinth.com (checked before and after redirects), so the Mod Browser can never be pointed at an arbitrary URL.
 * All calls block: run them on {@link ModBrowser#EXECUTOR}, never on the render thread.
 */
public final class ModrinthApi {
    public static final String GAME_VERSION = "1.21.11";
    public static final String LOADER = "fabric";
    static final String API = "https://api.modrinth.com/v2";
    /** Hosts a request or download may reach. */
    private static final Set<String> API_HOSTS = Set.of("api.modrinth.com");
    private static final Set<String> CDN_HOSTS = Set.of("cdn.modrinth.com");
    /** Largest mod file we will download, and largest icon. */
    static final long MAX_FILE = 256L << 20, MAX_ICON = 2L << 20;
    /** A download stops if no data arrives for this long. */
    static final long STALL_MS = 30_000;

    public static String userAgent() {
        return "EvadeClient/" + EvadeClient.VERSION + " (evadeclient.com)";
    }

    private final HttpClient http = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Evade-ModBrowser-Watchdog");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ data

    public enum Sort {
        RELEVANCE("relevance", "Relevance"), DOWNLOADS("downloads", "Downloads"), UPDATED("updated", "Updated");

        public final String index, label;

        Sort(String index, String label) { this.index = index; this.label = label; }
    }

    /** One search result. */
    public record Hit(String projectId, String slug, String title, String author, String description, long downloads,
                      String iconUrl, List<String> categories, String dateModified) {}

    public record SearchPage(List<Hit> hits, int offset, int total) {}

    /** A project's details (from /project/{id}). */
    public record Project(String id, String slug, String title, String description, String body, String iconUrl,
                          long downloads, List<String> categories, String clientSide, String serverSide) {}

    public record VersionFile(String url, String filename, String sha1, String sha512, long size, boolean primary) {}

    public record Dependency(String projectId, String versionId, String type) {}

    public record Version(String id, String projectId, String name, String versionNumber, String type, String published,
                          long downloads, List<String> gameVersions, List<String> loaders, List<VersionFile> files,
                          List<Dependency> dependencies) {
        /** The primary file, or the first one. */
        public VersionFile primaryFile() {
            for (VersionFile f : files) if (f.primary) return f;
            return files.isEmpty() ? null : files.get(0);
        }

        /** Whether this version is for {@link #GAME_VERSION} on Fabric. */
        public boolean compatible() {
            return gameVersions.contains(GAME_VERSION) && loaders.contains(LOADER);
        }
    }

    // ------------------------------------------------------------------ endpoints

    /** Search Fabric mods for this game version. {@code category} is a Modrinth category slug, or null for all. */
    public SearchPage search(String query, Sort sort, String category, int offset, int limit) throws IOException {
        JsonArray facets = new JsonArray();
        facets.add(facet("project_type:mod"));
        facets.add(facet("categories:" + LOADER));
        facets.add(facet("versions:" + GAME_VERSION));
        if (category != null && !category.isBlank()) facets.add(facet("categories:" + category));
        String url = API + "/search?limit=" + limit + "&offset=" + offset + "&index=" + sort.index
                + "&facets=" + enc(facets.toString()) + (query == null || query.isBlank() ? "" : "&query=" + enc(query.trim()));
        JsonObject o = get(url).getAsJsonObject();
        List<Hit> hits = new ArrayList<>();
        for (JsonElement e : arr(o, "hits")) {
            JsonObject h = e.getAsJsonObject();
            hits.add(new Hit(str(h, "project_id"), str(h, "slug"), orEmpty(str(h, "title")), orEmpty(str(h, "author")),
                    orEmpty(str(h, "description")), lng(h, "downloads"), str(h, "icon_url"), strings(h, "display_categories"),
                    str(h, "date_modified")));
        }
        return new SearchPage(hits, offset, o.has("total_hits") ? o.get("total_hits").getAsInt() : hits.size());
    }

    public Project project(String idOrSlug) throws IOException {
        return project(get(API + "/project/" + pathSeg(idOrSlug)).getAsJsonObject());
    }

    /** Several projects at once (titles and icons for a dependency list or the Installed tab). */
    public Map<String, Project> projects(Collection<String> ids) throws IOException {
        Map<String, Project> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        JsonArray a = new JsonArray();
        for (String id : ids) a.add(id);
        for (JsonElement e : get(API + "/projects?ids=" + enc(a.toString())).getAsJsonArray()) {
            Project p = project(e.getAsJsonObject());
            out.put(p.id(), p);
        }
        return out;
    }

    /** Versions of a project for this game version on Fabric, newest first. */
    public List<Version> versions(String projectId) throws IOException {
        String url = API + "/project/" + pathSeg(projectId) + "/version?game_versions=" + enc("[\"" + GAME_VERSION + "\"]")
                + "&loaders=" + enc("[\"" + LOADER + "\"]");
        List<Version> out = new ArrayList<>();
        for (JsonElement e : get(url).getAsJsonArray()) {
            Version v = version(e.getAsJsonObject());
            if (v.compatible() && v.primaryFile() != null) out.add(v);
        }
        return out;
    }

    public Version version(String versionId) throws IOException {
        return version(get(API + "/version/" + pathSeg(versionId)).getAsJsonObject());
    }

    /** The Modrinth version each file is (by sha1), for files that came from Modrinth. */
    public Map<String, Version> versionsByHash(Collection<String> sha1) throws IOException {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1.isEmpty()) return out;
        JsonObject body = new JsonObject();
        body.add("hashes", strArray(sha1));
        body.addProperty("algorithm", "sha1");
        JsonObject o = post(API + "/version_files", body).getAsJsonObject();
        for (var en : o.entrySet()) out.put(en.getKey(), version(en.getValue().getAsJsonObject()));
        return out;
    }

    /** The newest version for this game version on Fabric of each file's project (by sha1). */
    public Map<String, Version> latestByHash(Collection<String> sha1) throws IOException {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1.isEmpty()) return out;
        JsonObject body = new JsonObject();
        body.add("hashes", strArray(sha1));
        body.addProperty("algorithm", "sha1");
        body.add("loaders", strArray(List.of(LOADER)));
        body.add("game_versions", strArray(List.of(GAME_VERSION)));
        JsonObject o = post(API + "/version_files/update", body).getAsJsonObject();
        for (var en : o.entrySet()) out.put(en.getKey(), version(en.getValue().getAsJsonObject()));
        return out;
    }

    /** An icon's bytes (cdn.modrinth.com only, at most {@link #MAX_ICON}). */
    public byte[] icon(String url) throws IOException {
        URI uri = checked(url, CDN_HOSTS);
        HttpResponse<InputStream> res = send(HttpRequest.newBuilder(uri).header("User-Agent", userAgent())
                .timeout(Duration.ofSeconds(15)).GET().build());
        checkFinalHost(res, CDN_HOSTS);
        try (InputStream in = guard(res.body())) {
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
            byte[] b = in.readNBytes((int) MAX_ICON + 1);
            if (b.length > MAX_ICON) throw new IOException("icon too large");
            return b;
        }
    }

    /**
     * Download {@code f} to {@code target} (through a temporary file next to it), checking its sha512 against
     * Modrinth's. The target only appears once the whole file arrived and matched; on any failure nothing is left.
     */
    public void download(VersionFile f, Path target, java.util.function.DoubleConsumer progress) throws IOException {
        if (f.sha512() == null || f.sha512().length() != 128) throw new IOException("Modrinth didn't list a SHA-512 for " + f.filename());
        URI uri = checked(f.url(), CDN_HOSTS);
        HttpResponse<InputStream> res = send(HttpRequest.newBuilder(uri).header("User-Agent", userAgent())
                .timeout(Duration.ofSeconds(20)).GET().build());
        checkFinalHost(res, CDN_HOSTS);
        Path tmp = target.resolveSibling(target.getFileName() + ".evade-part");
        boolean ok = false;
        try (InputStream raw = res.body()) {
            if (res.statusCode() != 200) throw new IOException("Download failed (HTTP " + res.statusCode() + ")");
            long total = res.headers().firstValueAsLong("content-length").orElse(f.size());
            if (total > MAX_FILE) throw new IOException(f.filename() + " is too large");
            MessageDigest md = sha512();
            try (InputStream in = guard(raw); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (done > MAX_FILE) throw new IOException(f.filename() + " is too large");
                    if (progress != null && total > 0) progress.accept(Math.min(1, done / (double) total));
                }
            }
            String got = HexFormat.of().formatHex(md.digest());
            if (!got.equalsIgnoreCase(f.sha512())) throw new IOException(f.filename() + " failed its SHA-512 check");
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ok = true;
        } finally {
            if (!ok) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ http

    private JsonElement get(String url) throws IOException {
        return json(HttpRequest.newBuilder(checked(url, API_HOSTS)).header("User-Agent", userAgent())
                .header("Accept", "application/json").timeout(Duration.ofSeconds(15)).GET().build());
    }

    private JsonElement post(String url, JsonObject body) throws IOException {
        return json(HttpRequest.newBuilder(checked(url, API_HOSTS)).header("User-Agent", userAgent())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    private JsonElement json(HttpRequest req) throws IOException {
        HttpResponse<InputStream> res = send(req);
        checkFinalHost(res, API_HOSTS);
        try (InputStream in = guard(res.body())) {
            byte[] b = in.readNBytes(16 << 20);
            int code = res.statusCode();
            if (code == 404) throw new IOException("Not found on Modrinth");
            if (code == 429) throw new IOException("Modrinth is busy, try again in a minute");
            if (code != 200) throw new IOException("Modrinth error (HTTP " + code + ")");
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("Modrinth sent an unexpected reply", e);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("Modrinth took too long to answer", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("Couldn't reach Modrinth. Check your connection", e);
        }
    }

    /** {@code url} as a URI, if it is https on one of {@code hosts}. */
    static URI checked(String url, Set<String> hosts) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getPort() != -1 || uri.getUserInfo() != null)
            throw new IOException("Only Modrinth downloads are allowed");
        return uri;
    }

    /** Whether {@code url} is an icon the browser may fetch. */
    public static boolean isModrinthCdn(String url) {
        try {
            checked(url, CDN_HOSTS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void checkFinalHost(HttpResponse<InputStream> res, Set<String> hosts) throws IOException {
        URI u = res.uri();
        if (u == null || u.getHost() == null || !"https".equalsIgnoreCase(u.getScheme()) || !hosts.contains(u.getHost().toLowerCase(Locale.ROOT))) {
            try { res.body().close(); } catch (IOException ignored) {}
            throw new IOException("Modrinth redirected somewhere unexpected");
        }
    }

    /** {@code in}, closed by a watchdog if no data arrives for {@link #STALL_MS} (a read then fails instead of hanging). */
    private static InputStream guard(InputStream in) {
        AtomicLong last = new AtomicLong(System.nanoTime());
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        task[0] = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - last.get() > STALL_MS * 1_000_000L) {
                try { in.close(); } catch (IOException ignored) {}
                task[0].cancel(false);
            }
        }, 5, 5, TimeUnit.SECONDS);
        return new java.io.FilterInputStream(in) {
            @Override public int read() throws IOException { int r = super.read(); last.set(System.nanoTime()); return r; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int r = super.read(b, off, len);
                last.set(System.nanoTime());
                return r;
            }
            @Override public void close() throws IOException {
                task[0].cancel(false);
                super.close();
            }
        };
    }

    // ------------------------------------------------------------------ json helpers

    private static JsonArray facet(String f) {
        JsonArray a = new JsonArray();
        a.add(f);
        return a;
    }

    private static JsonArray strArray(Collection<String> s) {
        JsonArray a = new JsonArray();
        for (String x : s) a.add(x);
        return a;
    }

    static Project project(JsonObject p) {
        return new Project(str(p, "id"), str(p, "slug"), orEmpty(str(p, "title")), orEmpty(str(p, "description")),
                orEmpty(str(p, "body")), str(p, "icon_url"), lng(p, "downloads"), strings(p, "categories"),
                str(p, "client_side"), str(p, "server_side"));
    }

    static Version version(JsonObject v) {
        List<VersionFile> files = new ArrayList<>();
        for (JsonElement e : arr(v, "files")) {
            JsonObject f = e.getAsJsonObject();
            JsonObject h = f.has("hashes") && f.get("hashes").isJsonObject() ? f.getAsJsonObject("hashes") : new JsonObject();
            files.add(new VersionFile(str(f, "url"), str(f, "filename"), str(h, "sha1"), str(h, "sha512"), lng(f, "size"),
                    f.has("primary") && f.get("primary").getAsBoolean()));
        }
        List<Dependency> deps = new ArrayList<>();
        for (JsonElement e : arr(v, "dependencies")) {
            JsonObject d = e.getAsJsonObject();
            deps.add(new Dependency(str(d, "project_id"), str(d, "version_id"), str(d, "dependency_type")));
        }
        return new Version(str(v, "id"), str(v, "project_id"), orEmpty(str(v, "name")), orEmpty(str(v, "version_number")),
                orEmpty(str(v, "version_type")), str(v, "date_published"), lng(v, "downloads"), strings(v, "game_versions"),
                strings(v, "loaders"), files, deps);
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    private static long lng(JsonObject o, String k) {
        try {
            JsonElement e = o.get(k);
            return e == null || e.isJsonNull() ? 0 : e.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, k)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** An id or slug as one URL path segment (Modrinth ids and slugs are [A-Za-z0-9_.-]). */
    private static String pathSeg(String s) throws IOException {
        if (s == null || !s.matches("[A-Za-z0-9_.\\-]{1,64}")) throw new IOException("Bad Modrinth id");
        return s;
    }

    static MessageDigest sha512() {
        try { return MessageDigest.getInstance("SHA-512"); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
