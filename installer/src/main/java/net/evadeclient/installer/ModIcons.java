package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Real mod icons for the Mods page, the Browse results and the versions list:
 * <ol>
 *   <li>the icon inside the jar ({@code fabric.mod.json} "icon": a path, or a size→path map, where the size closest
 *       to twice the shown size is picked);</li>
 *   <li>else Modrinth's project icon, found by the jar's SHA-1 ({@code /v2/version_files} then {@code /v2/projects},
 *       batched; kept on disk for 7 days, misses included);</li>
 *   <li>else nothing, and the caller draws a neutral tile with the initial.</li>
 * </ol>
 * Everything runs on a background thread; {@link #get} only reads the cache and asks for a repaint when a picture
 * arrives, so the UI thread never waits.
 */
final class ModIcons {
    private ModIcons() {}

    /** Shown size of a mod icon in the lists (the picture is decoded for twice this). */
    static final int SIZE = 40;
    private static final long CACHE_MS = 7L * 24 * 3600 * 1000;
    private static final Image NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private static final Map<String, Image> images = new ConcurrentHashMap<>();
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    private static final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mod-icons");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Jars waiting for the Modrinth lookup (batched). */
    private static final List<Path> modrinthQueue = new ArrayList<>();

    /** The icon of an installed jar, or null while it's loading / when there is none. Never blocks. */
    static Image get(Path jar, Runnable repaint) {
        String key = "jar:" + jar.toAbsolutePath();
        Image img = images.get(key);
        if (img != null) return img == NONE ? null : img;
        if (pending.add(key)) pool.submit(() -> {
            Image found = fromJar(jar);
            if (found != null) done(key, found, repaint);
            else queueModrinth(jar, key, repaint);
        });
        return null;
    }

    /** A picture from a URL (Modrinth search results), or null while it loads. Never blocks. */
    static Image url(String url, Runnable repaint) {
        if (url == null || url.isBlank()) return null;
        String key = "url:" + url;
        Image img = images.get(key);
        if (img != null) return img == NONE ? null : img;
        if (pending.add(key)) pool.submit(() -> {
            Image found = null;
            try {
                Path cache = cacheDir().resolve("u-" + sha1(url.getBytes(StandardCharsets.UTF_8)) + ".png");
                byte[] data = fresh(cache) ? Files.readAllBytes(cache) : download(url);
                if (data != null) {
                    BufferedImage raw = ImageIO.read(new ByteArrayInputStream(data));
                    if (raw != null) {
                        found = scale(raw);
                        if (!fresh(cache)) { Files.createDirectories(cache.getParent()); ImageIO.write((BufferedImage) found, "png", cache.toFile()); }
                    }
                }
            } catch (Exception ignored) { }
            done(key, found, repaint);
        });
        return null;
    }

    private static void done(String key, Image img, Runnable repaint) {
        images.put(key, img == null ? NONE : img);
        pending.remove(key);
        if (img != null && repaint != null) SwingUtilities.invokeLater(repaint);
    }

    // ------------------------------------------------------------------ from the jar

    static Image fromJar(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return null;
            JsonObject o;
            try (InputStream in = z.getInputStream(e)) {
                o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            String path = iconPath(o.get("icon"), SIZE * 2);
            if (path == null) return null;
            ZipEntry ie = z.getEntry(path.startsWith("/") ? path.substring(1) : path);
            if (ie == null) return null;
            try (InputStream in = z.getInputStream(ie)) {
                BufferedImage raw = ImageIO.read(in);
                return raw == null ? null : scale(raw);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** "icon" as a path, or from a {"16": "a.png", "128": "b.png"} map the size closest to {@code want}. */
    static String iconPath(JsonElement icon, int want) {
        if (icon == null || icon.isJsonNull()) return null;
        if (icon.isJsonPrimitive()) return icon.getAsString();
        if (!icon.isJsonObject()) return null;
        String best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (var en : icon.getAsJsonObject().entrySet()) {
            try {
                int size = Integer.parseInt(en.getKey().strip());
                int diff = Math.abs(size - want) * (size < want ? 2 : 1); // prefer a bigger picture to a smaller one
                if (diff < bestDiff && en.getValue().isJsonPrimitive()) { bestDiff = diff; best = en.getValue().getAsString(); }
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }

    /** Down to twice the shown size, smoothly (pixel art stays pixel art when it's already small). */
    static BufferedImage scale(BufferedImage raw) {
        int t = SIZE * 2;
        if (raw.getWidth() <= t && raw.getHeight() <= t) return raw;
        BufferedImage cur = raw;
        int w = raw.getWidth(), h = raw.getHeight();
        while (w > t * 2 || h > t * 2) {
            w = Math.max(t, w / 2);
            h = Math.max(t, h / 2);
            cur = draw(cur, w, h);
        }
        return draw(cur, t, Math.max(1, Math.round(t * raw.getHeight() / (float) raw.getWidth())));
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ from Modrinth, by SHA-1

    private static void queueModrinth(Path jar, String key, Runnable repaint) {
        synchronized (modrinthQueue) {
            modrinthQueue.add(jar);
            if (modrinthQueue.size() > 1) return; // a batch is already on its way
        }
        pool.submit(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { } // let the rest of the list join
            List<Path> batch;
            synchronized (modrinthQueue) {
                batch = new ArrayList<>(modrinthQueue);
                modrinthQueue.clear();
            }
            lookup(batch, repaint);
        });
    }

    private static void lookup(List<Path> jars, Runnable repaint) {
        Map<String, Path> bySha = new java.util.LinkedHashMap<>();
        Map<String, Image> resolved = new java.util.HashMap<>();
        for (Path j : jars) {
            try {
                String sha = sha1(j);
                Path cached = cacheDir().resolve("m-" + sha + ".png"), miss = cacheDir().resolve("m-" + sha + ".none");
                if (fresh(cached)) resolved.put(key(j), ImageIO.read(cached.toFile()));
                else if (fresh(miss)) resolved.put(key(j), null);
                else bySha.put(sha, j);
            } catch (Exception e) {
                resolved.put(key(j), null);
            }
        }
        if (!bySha.isEmpty()) {
            try {
                JsonObject body = new JsonObject();
                JsonArray hashes = new JsonArray();
                for (String s : bySha.keySet()) hashes.add(s);
                body.add("hashes", hashes);
                body.addProperty("algorithm", "sha1");
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/version_files"))
                        .timeout(Duration.ofSeconds(8)).header("user-agent", "EvadeLauncher").header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                Map<String, String> projectOf = new java.util.HashMap<>();
                if (res.statusCode() == 200) {
                    JsonObject versions = JsonParser.parseString(res.body()).getAsJsonObject();
                    for (var e : versions.entrySet()) projectOf.put(e.getKey(), InstallerCore.str(e.getValue().getAsJsonObject(), "project_id"));
                }
                Map<String, String> iconOf = new java.util.HashMap<>();
                if (!projectOf.isEmpty()) {
                    JsonArray ids = new JsonArray();
                    for (String p : new java.util.LinkedHashSet<>(projectOf.values())) if (p != null) ids.add(p);
                    HttpResponse<String> pr = http.send(HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/projects?ids="
                                    + java.net.URLEncoder.encode(ids.toString(), StandardCharsets.UTF_8)))
                            .timeout(Duration.ofSeconds(8)).header("user-agent", "EvadeLauncher").GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (pr.statusCode() == 200)
                        for (JsonElement e : JsonParser.parseString(pr.body()).getAsJsonArray())
                            iconOf.put(InstallerCore.str(e.getAsJsonObject(), "id"), InstallerCore.str(e.getAsJsonObject(), "icon_url"));
                }
                Files.createDirectories(cacheDir());
                for (var en : bySha.entrySet()) {
                    String url = iconOf.get(projectOf.get(en.getKey()));
                    BufferedImage img = null;
                    if (url != null) {
                        byte[] data = download(url);
                        BufferedImage raw = data == null ? null : ImageIO.read(new ByteArrayInputStream(data));
                        if (raw != null) img = scale(raw);
                    }
                    if (img != null) ImageIO.write(img, "png", cacheDir().resolve("m-" + en.getKey() + ".png").toFile());
                    else if (res.statusCode() == 200) Files.writeString(cacheDir().resolve("m-" + en.getKey() + ".none"), "");
                    resolved.put(key(en.getValue()), img);
                }
            } catch (Exception e) {
                for (Path j : bySha.values()) resolved.putIfAbsent(key(j), null); // offline: try again next start
            }
        }
        for (var e : resolved.entrySet()) done(e.getKey(), e.getValue(), repaint);
    }

    private static String key(Path jar) { return "jar:" + jar.toAbsolutePath(); }

    // ------------------------------------------------------------------ helpers

    private static Path cacheDir() { return Launcher.dataDir().resolve("mod-icons"); }

    private static boolean fresh(Path f) {
        try { return Files.exists(f) && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < CACHE_MS; }
        catch (IOException e) { return false; }
    }

    private static byte[] download(String url) {
        try {
            if (!url.startsWith("https://")) return null;
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("user-agent", "EvadeLauncher").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 && r.body().length < 3_000_000 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha1(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha1(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
    }
}
