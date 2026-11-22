package net.evadeclient.clips.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Share links and phone sync through an Evade Clips Share server (clips-share/, a Cloudflare Worker anyone can
 * deploy). Uploading gives a link that plays inline in Discord; every uploaded clip also shows up in a private
 * gallery you open on your phone. No watermark is added, and links don't expire.
 */
public final class CloudShare {
    private static final Gson GSON = new Gson();
    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Upload part size override (tests use small parts to exercise multipart). 0 = what the server says. */
    public int partSizeOverride;
    /** Parts used by the last upload (for tests). */
    public volatile int lastParts;

    public CloudShare(Settings settings) { this.settings = settings; }

    public boolean configured() { return !base().isEmpty(); }

    String base() {
        String u = settings.cloudUrl == null ? "" : settings.cloudUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** Creates this PC's account on the server the first time it's needed. */
    public synchronized void ensureAccount() throws IOException {
        if (!configured()) throw new IOException("No share server set. Add one in Settings → Sharing.");
        if (!settings.cloudAccount.isBlank() && settings.cloudBase.equals(base())) return;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + "/api/register")).POST(HttpRequest.BodyPublishers.noBody());
        if (!settings.cloudRegisterSecret.isBlank()) b.header("X-Register-Secret", settings.cloudRegisterSecret);
        JsonObject r = send(b.build());
        settings.cloudAccount = r.get("account").getAsString();
        settings.cloudKey = r.get("key").getAsString();
        settings.cloudViewToken = r.get("viewToken").getAsString();
        settings.cloudBase = base();
        settings.save();
    }

    private String bearer() { return "Bearer " + settings.cloudAccount + "." + settings.cloudKey; }

    /** Private link to the phone gallery (show it as a QR code). */
    public String galleryUrl() throws IOException {
        ensureAccount();
        return base() + "/g/" + settings.cloudAccount + "?t=" + settings.cloudViewToken;
    }

    /**
     * Uploads a clip (with its edits applied, as H.264 + one mixed audio track so it plays everywhere) and
     * returns its share link. Re-sharing an unchanged clip returns the existing link.
     */
    public String share(Clip c, Library lib, DoubleConsumer progress, Ffmpeg.Job job) throws Exception {
        ensureAccount();
        if (c.shareUrl != null && c.shareUrl.startsWith(base()) && c.shareEditHash == editHash(c)) return c.shareUrl;
        Path src = c.path(lib.root());
        Path upload = src;
        Path tmp = null;
        EditSpec edit = c.edit != null ? c.edit : EditSpec.whole(c.duration);
        boolean needsRender = !edit.isUntouched(c.duration) || c.audioTracks > 1 || !"h264".equals(videoCodec(src))
                || !Library.ext(src).equals("mp4");
        double renderShare = needsRender ? 0.5 : 0;
        int w = c.width, h = c.height;
        double dur = c.duration;
        if (needsRender) {
            tmp = lib.metaDir().resolve("share-" + c.id + ".mp4");
            Exporter.Options o = new Exporter.Options();
            o.preset = Exporter.Preset.SOURCE;
            o.gpu = true;
            Exporter.export(src, c, edit, o, tmp, p -> { if (progress != null) progress.accept(p * renderShare); }, job);
            upload = tmp;
            Ffmpeg.MediaInfo mi = Ffmpeg.probe(tmp);
            w = mi.width(); h = mi.height(); dur = mi.duration();
        }
        try {
            long size = Files.size(upload);
            JsonObject start = send(authed("/api/uploads").POST(json(Map.of("title", c.title == null ? "Clip" : c.title,
                    "game", c.game == null ? "" : c.game, "duration", dur, "width", w, "height", h, "size", size))).build());
            String clip = start.get("clip").getAsString(), uploadId = start.get("uploadId").getAsString();
            int partSize = partSizeOverride > 0 ? partSizeOverride : start.get("partSize").getAsInt();
            List<Map<String, Object>> parts = new ArrayList<>();
            byte[] buf = new byte[partSize];
            long sent = 0;
            try (InputStream in = Files.newInputStream(upload)) {
                for (int n = 1; ; n++) {
                    if (job != null && job.cancelled()) throw new IOException("Cancelled");
                    int len = in.readNBytes(buf, 0, partSize);
                    if (len <= 0) break;
                    byte[] body = len == partSize ? buf : java.util.Arrays.copyOf(buf, len);
                    JsonObject part = send(authed("/api/uploads/" + clip + "/" + n + "?uploadId=" + enc(uploadId))
                            .timeout(Duration.ofMinutes(5)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build());
                    parts.add(Map.of("partNumber", part.get("partNumber").getAsInt(), "etag", part.get("etag").getAsString()));
                    sent += len;
                    if (progress != null) progress.accept(renderShare + (1 - renderShare) * sent / (double) size);
                    if (len < partSize) break;
                }
            }
            lastParts = parts.size();
            send(authed("/api/uploads/" + clip + "/complete").POST(json(Map.of("uploadId", uploadId, "parts", parts))).build());
            Path thumb = lib.thumb(c);
            if (Files.exists(thumb)) send(authed("/api/clips/" + clip + "/thumb").PUT(HttpRequest.BodyPublishers.ofFile(thumb)).build());
            if (c.shareId != null) { try { unshare(c, lib); } catch (Exception ignored) {} }
            c.shareId = clip;
            c.shareUrl = base() + "/c/" + clip;
            c.shareEditHash = editHash(c);
            lib.update(c);
            return c.shareUrl;
        } finally {
            if (tmp != null) Files.deleteIfExists(tmp);
        }
    }

    /** Removes a clip from the server (its link stops working). */
    public void unshare(Clip c, Library lib) throws IOException {
        if (c.shareId == null) return;
        ensureAccount();
        send(authed("/api/clips/" + c.shareId).DELETE().build());
        c.shareId = null;
        c.shareUrl = null;
        lib.update(c);
    }

    /** Clips on the server for this account, plus storage use: {clips:[...], used, quota}. */
    public JsonObject list() throws IOException {
        ensureAccount();
        return send(authed("/api/clips").GET().build());
    }

    static int editHash(Clip c) { return c.edit == null ? 0 : GSON.toJson(c.edit).hashCode(); }

    static String videoCodec(Path f) {
        return Ffmpeg.capture(List.of(Ffmpeg.ffprobe(), "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name",
                "-of", "csv=p=0", f.toString()), 15).trim();
    }

    // ------------------------------------------------------------------ http

    private HttpRequest.Builder authed(String path) {
        return HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(60)).header("Authorization", bearer());
    }

    private static HttpRequest.BodyPublisher json(Object o) { return HttpRequest.BodyPublishers.ofString(GSON.toJson(o)); }

    private JsonObject send(HttpRequest r) throws IOException {
        HttpResponse<String> res;
        try { res = http.send(r, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
        JsonObject o;
        try { o = JsonParser.parseString(res.body()).getAsJsonObject(); }
        catch (Exception e) { throw new IOException("Share server error (HTTP " + res.statusCode() + ")"); }
        if (res.statusCode() == 401) throw new IOException("The share server doesn't know this PC any more. Reset sharing in Settings.");
        if (res.statusCode() >= 300) throw new IOException(o.has("error") ? o.get("error").getAsString() : "HTTP " + res.statusCode());
        return o;
    }

    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    /** The ids of clips in a {@link #list()} result. */
    public static List<String> ids(JsonObject list) {
        List<String> out = new ArrayList<>();
        JsonArray a = list.getAsJsonArray("clips");
        if (a != null) a.forEach(e -> out.add(e.getAsJsonObject().get("id").getAsString()));
        return out;
    }
}
