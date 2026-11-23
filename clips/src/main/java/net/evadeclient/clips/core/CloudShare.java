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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Share links and phone sync through an Evade Clips Share server (clips-share/, a Cloudflare Worker anyone can
 * deploy). Uploading gives a link that plays inline in Discord; every uploaded clip also shows up in a private
 * gallery you open on your phone. No watermark is added, and links don't expire.
 * <p>
 * This PC has one account per server ({@link Settings#cloudAccounts}). A shared clip remembers which server it
 * went to ({@link Clip#shareBase}), so its link can still be changed or removed after switching servers. A
 * private clip's link carries that clip's own key, never the account's gallery token.
 */
public final class CloudShare {
    private static final Gson GSON = new Gson();
    private static final HttpClient HOOKS = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Upload part size override (tests use small parts to exercise multipart). 0 = what the server says. */
    public int partSizeOverride;
    /** Parts used by the last upload (for tests). */
    public volatile int lastParts;

    /** What to do when sharing a clip that already has a link (its edits changed, or it's on another server). */
    public enum Replace { REPLACE, KEEP_OLD, CANCEL }

    /**
     * Asked before a clip's existing link is replaced by a new upload (the old link stops working). Null replaces
     * without asking (tests, automatic phone sync).
     */
    public volatile java.util.function.Function<Clip, Replace> confirmReplace;

    public CloudShare(Settings settings) { this.settings = settings; }

    public boolean configured() { return !base().isEmpty(); }

    /** The share server set in Settings, without a trailing slash. */
    public String base() {
        String u = settings.cloudUrl == null ? "" : settings.cloudUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static boolean sameServer(String a, String b) { return Settings.normBase(a).equals(Settings.normBase(b)); }

    /** The server a shared clip lives on: where it was uploaded, else the start of its link, else the current one. */
    public String serverOf(Clip c) {
        if (c.shareBase != null && !c.shareBase.isBlank()) return c.shareBase;
        if (c.shareUrl != null) {
            int at = c.shareUrl.indexOf("/c/");
            if (at > 0) return c.shareUrl.substring(0, at);
        }
        return base();
    }

    /** True if the clip was shared to the server set in Settings now. */
    public boolean onCurrentServer(Clip c) { return c.shareId != null && sameServer(serverOf(c), base()); }

    /** Creates this PC's account on the server the first time it's needed (or switches to the one it has there). */
    public synchronized void ensureAccount() throws IOException {
        if (!configured()) throw new IOException("No share server set. Add one in Settings → Sharing & phone.");
        String b = base();
        if (!settings.cloudAccount.isBlank() && sameServer(settings.cloudBase, b)) return;
        if (settings.useCredsFor(b)) { settings.save(); return; }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(b + "/api/register")).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (!settings.cloudRegisterSecret.isBlank()) req.header("X-Register-Secret", settings.cloudRegisterSecret);
        JsonObject r = send(req.build());
        settings.putCreds(b, new Settings.Creds(r.get("account").getAsString(), r.get("key").getAsString(), r.get("viewToken").getAsString()));
        settings.save();
    }

    /**
     * This PC's account on {@code server}, the one a clip shared there belongs to. Never creates one: a new
     * account couldn't change a link made by the old one (after "Reset sharing").
     */
    private Settings.Creds credsFor(String server) throws IOException {
        Settings.Creds c = settings.credsFor(server);
        if (c == null) throw new IOException("This PC no longer has an account on " + server + " (sharing was reset), so this link can't be changed from here.");
        return c;
    }

    /** Who can watch a shared clip: "link" (anyone with the link) or "private" (only you, through your gallery). */
    public void setVisibility(Clip c, Library lib, boolean priv) throws IOException {
        if (c.shareId == null) return;
        String server = serverOf(c);
        Settings.Creds cr = credsFor(server);
        JsonObject r = send(authed(server, cr, "/api/clips/" + c.shareId).method("PATCH", json(Map.of("visibility", priv ? "private" : "link"))).build());
        c.sharePrivate = priv;
        c.shareBase = server;
        c.shareUrl = link(server, r, c.shareId);
        lib.update(c);
    }

    /**
     * The clip's link, safe to hand out. A private link saved by an older version carries the account's gallery
     * token ({@code ?t=}); it's swapped for the clip's own key first.
     */
    public String currentLink(Clip c, Library lib) throws IOException {
        if (c.shareUrl != null && c.shareId != null && c.shareUrl.contains("?t=")) setVisibility(c, lib, c.sharePrivate);
        return c.shareUrl;
    }

    /** True if {@link #currentLink} has to ask the server first. */
    public static boolean needsNewLink(Clip c) { return c.shareUrl != null && c.shareUrl.contains("?t="); }

    /** The clip's link from a server reply ({@code url}: "/c/<id>?k=<key>" for a private clip). */
    private static String link(String server, JsonObject r, String id) {
        if (r != null && r.has("url") && r.get("url").isJsonPrimitive()) {
            String u = r.get("url").getAsString();
            if (u.startsWith("/")) return server + u;
        }
        String key = r != null && r.has("key") && r.get("key").isJsonPrimitive() ? r.get("key").getAsString() : null;
        return server + "/c/" + id + (key != null && !key.isBlank() ? "?k=" + enc(key) : "");
    }

    /** New notices from the server (e.g. a clip removed by moderation, and why). Each is returned once. */
    public List<JsonObject> newNotices() throws IOException {
        if (!configured() || settings.cloudAccount.isBlank()) return List.of();
        JsonObject r = send(authed("/api/notices?unread=1").GET().build());
        List<JsonObject> out = new ArrayList<>();
        if (r.has("notices")) r.getAsJsonArray("notices").forEach(e -> out.add(e.getAsJsonObject()));
        return out;
    }

    /**
     * Downloads clips uploaded from the phone gallery (console captures, phone recordings) that aren't in the
     * library yet and weren't deleted here. Returns the new clips. One failed download doesn't stop the others,
     * and nothing half-downloaded is left behind.
     */
    public List<Clip> pullPhoneUploads(Library lib) throws IOException {
        List<Clip> added = new ArrayList<>();
        if (!configured() || settings.cloudAccount.isBlank()) return added;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Clip c : lib.all()) if (c.shareId != null) have.add(c.shareId);
        synchronized (settings) { have.addAll(settings.deletedPhoneIds); }
        String server = base();
        JsonArray clips = list().getAsJsonArray("clips");
        if (clips == null) return added;
        for (var el : clips) {
            JsonObject m = el.getAsJsonObject();
            String id = m.get("id").getAsString();
            if (!"phone".equals(m.has("origin") ? m.get("origin").getAsString() : "") || have.contains(id)) continue;
            boolean priv = m.has("visibility") && "private".equals(m.get("visibility").getAsString());
            String key = m.has("key") ? m.get("key").getAsString() : null;
            // Downloading (not a link anyone sees): a clip from before per-clip keys still opens with the gallery token.
            String q = !priv ? "" : key != null && !key.isBlank() ? "?k=" + enc(key) : "?t=" + enc(settings.cloudViewToken);
            Path dir = lib.folder(Clip.Kind.IMPORT).resolve("Phone");
            String title = m.has("title") ? m.get("title").getAsString() : "Phone clip";
            Path dest = null, part = null;
            boolean moved = false;
            try {
                Files.createDirectories(dir);
                dest = Library.unique(dir, Library.safe(title).isBlank() ? "Phone clip.mp4" : Library.safe(title) + ".mp4");
                part = dest.resolveSibling(dest.getFileName() + ".part");
                HttpResponse<Path> r;
                try {
                    r = http.send(HttpRequest.newBuilder(URI.create(server + "/v/" + id + ".mp4" + q)).timeout(Duration.ofMinutes(10)).build(),
                            HttpResponse.BodyHandlers.ofFile(part));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
                if (r.statusCode() != 200) { System.err.println("[clips] phone clip " + id + ": HTTP " + r.statusCode()); continue; }
                try { Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(part, dest); }
                moved = true;
                Clip c = lib.addNew(dest, Clip.Kind.IMPORT, title, m.has("game") ? m.get("game").getAsString() : "", null, null);
                c.shareId = id;
                c.shareBase = server;
                c.shareUrl = server + "/c/" + id + (priv && key != null && !key.isBlank() ? "?k=" + enc(key) : "");
                c.sharePrivate = priv;
                c.shareEditHash = editHash(c);
                if (!c.tags.contains("phone")) c.tags.add("phone");
                lib.update(c);
                added.add(c);
                moved = false;
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                System.err.println("[clips] couldn't download phone clip \"" + title + "\": " + e.getMessage());
            } finally {
                if (part != null) try { Files.deleteIfExists(part); } catch (IOException ignored) {}
                // Downloaded but not added (not a playable video): don't leave a stray file in the library folder.
                if (moved) try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
            }
        }
        return added;
    }

    /** Removes downloads a crash or power cut left half-finished (Imported/Phone/*.part). */
    public static void cleanPartials(Library lib) {
        Path dir = lib.folder(Clip.Kind.IMPORT).resolve("Phone");
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.newDirectoryStream(dir, "*.part")) {
            for (Path p : s) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        } catch (IOException ignored) {}
    }

    private String bearer() { return "Bearer " + settings.cloudAccount + "." + settings.cloudKey; }

    /** Private link to the phone gallery (show it as a QR code). */
    public String galleryUrl() throws IOException {
        ensureAccount();
        return base() + "/g/" + settings.cloudAccount + "?t=" + settings.cloudViewToken;
    }

    /**
     * A new phone-gallery link: the old one (and anything built from it) stops working. Private clips get their
     * own keys where they had none, and their links in the library are updated. Returns the new gallery link.
     */
    public String rotateGallery(Library lib) throws IOException {
        ensureAccount();
        String server = base();
        JsonObject r = send(authed("/api/account/rotate-gallery").POST(HttpRequest.BodyPublishers.noBody()).build());
        String token = r.get("viewToken").getAsString();
        settings.putCreds(server, new Settings.Creds(settings.cloudAccount, settings.cloudKey, token));
        settings.save();
        Map<String, String> links = new LinkedHashMap<>();
        if (r.has("clips")) for (var el : r.getAsJsonArray("clips")) {
            JsonObject o = el.getAsJsonObject();
            links.put(o.get("id").getAsString(), link(server, o, o.get("id").getAsString()));
        }
        boolean changed = false;
        for (Clip c : lib.all()) {
            if (c.shareId == null || !sameServer(serverOf(c), server)) continue;
            String l = links.get(c.shareId);
            if (l != null && !l.equals(c.shareUrl)) { c.shareUrl = l; c.shareBase = server; changed = true; }
        }
        if (changed) lib.save();
        lib.changed();
        return server + "/g/" + settings.cloudAccount + "?t=" + token;
    }

    /**
     * Uploads a clip (with its edits applied, as H.264 + one mixed audio track so it plays everywhere) and
     * returns its share link. Re-sharing an unchanged clip returns the existing link. If the clip already has a
     * link and changed, {@link #confirmReplace} is asked first; the old link is removed only after the new upload
     * worked.
     */
    public String share(Clip c, Library lib, DoubleConsumer progress, Ffmpeg.Job job) throws Exception {
        ensureAccount();
        String server = base();
        if (c.shareUrl != null && c.shareId != null && sameServer(serverOf(c), server) && unchanged(c)) return currentLink(c, lib);
        if (c.shareId != null) {
            var ask = confirmReplace;
            Replace choice = ask == null ? Replace.REPLACE : ask.apply(c);
            if (choice == Replace.KEEP_OLD && c.shareUrl != null) return c.shareUrl;
            if (choice != Replace.REPLACE) throw new IOException("Cancelled");
        }
        String oldId = c.shareId;
        String oldServer = oldId == null ? null : serverOf(c);
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
                    "game", c.game == null ? "" : c.game, "duration", dur, "width", w, "height", h, "size", size,
                    "visibility", c.sharePrivate ? "private" : "link"))).build());
            String clip = start.get("clip").getAsString(), uploadId = start.get("uploadId").getAsString();
            JsonObject done;
            try {
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
                        String path = "/api/uploads/" + clip + "/" + n + "?uploadId=" + enc(uploadId);
                        JsonObject part = retrying(job, () -> send(authed(path).timeout(Duration.ofMinutes(5))
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()));
                        parts.add(Map.of("partNumber", part.get("partNumber").getAsInt(), "etag", part.get("etag").getAsString()));
                        sent += len;
                        if (progress != null) progress.accept(renderShare + (1 - renderShare) * sent / (double) size);
                        if (len < partSize) break;
                    }
                }
                lastParts = parts.size();
                // Safe to repeat: a completion whose reply got lost is recorded once and answered the same way.
                done = retrying(job, () -> send(authed("/api/uploads/" + clip + "/complete").timeout(Duration.ofMinutes(2))
                        .POST(json(Map.of("uploadId", uploadId, "parts", parts))).build()));
            } catch (Exception e) {
                // Don't leave the upload (and the space it reserved) behind on the server.
                try { send(authed("/api/uploads/" + clip + "/abort").POST(json(Map.of("uploadId", uploadId))).build()); }
                catch (Exception ignored) {}
                throw e;
            }
            c.shareId = clip;
            c.shareBase = server;
            c.shareUrl = done.has("url") ? link(server, done, clip) : link(server, start, clip);
            c.shareEditHash = editHash(c);
            lib.update(c);
            try { putThumb(server, credsFor(server), clip, lib, c); }
            catch (Exception e) { System.err.println("[clips] link picture not uploaded: " + e.getMessage()); }
            // The new link works: now the replaced one can go.
            if (oldId != null && !(oldId.equals(clip) && sameServer(oldServer, server))) {
                try { send(authed(oldServer, credsFor(oldServer), "/api/clips/" + oldId).DELETE().build()); }
                catch (HttpError e) { if (e.status != 404) System.err.println("[clips] old link not removed: " + e.getMessage()); }
                catch (Exception e) { System.err.println("[clips] old link not removed: " + e.getMessage()); }
            }
            return c.shareUrl;
        } finally {
            if (tmp != null) Files.deleteIfExists(tmp);
        }
    }

    /** True if the clip's edits are what its shared copy was rendered with. */
    private static boolean unchanged(Clip c) {
        if (c.shareEditHash == editHash(c)) return true;
        // Shared by an older version, which hashed untouched edits too.
        return c.edit != null && GSON.toJson(c.edit).hashCode() == c.shareEditHash && c.edit.isUntouched(c.duration);
    }

    /**
     * Uploads the clip's current library thumbnail as its share link's picture (after "Set cover"). Waits for a
     * cover that's still being drawn.
     */
    public void uploadThumb(Clip c, Library lib) throws IOException {
        if (c.shareId == null) return;
        lib.awaitCover(c, 30_000);
        String server = serverOf(c);
        putThumb(server, credsFor(server), c.shareId, lib, c);
    }

    private void putThumb(String server, Settings.Creds cr, String id, Library lib, Clip c) throws IOException {
        Path thumb = lib.thumb(c);
        if (!Files.exists(thumb)) return;
        send(authed(server, cr, "/api/clips/" + id + "/thumb").PUT(HttpRequest.BodyPublishers.ofFile(thumb)).build());
    }

    /** Removes a clip from the server (its link stops working). A link that's already gone counts as removed. */
    public void unshare(Clip c, Library lib) throws IOException {
        if (c.shareId == null) return;
        String server = serverOf(c);
        try { send(authed(server, credsFor(server), "/api/clips/" + c.shareId).DELETE().build()); }
        catch (HttpError e) { if (e.status != 404) throw e; }
        c.shareId = null;
        c.shareUrl = null;
        c.shareBase = null;
        lib.update(c);
    }

    /**
     * Deletes one of this account's clips on the server by id (for uploads that are no longer in the library).
     * A library clip with that link loses it.
     */
    public void deleteRemote(String id, Library lib) throws IOException {
        ensureAccount();
        try { send(authed("/api/clips/" + enc(id)).DELETE().build()); }
        catch (HttpError e) { if (e.status != 404) throw e; }
        String server = base();
        for (Clip c : lib.all()) {
            if (id.equals(c.shareId) && sameServer(serverOf(c), server)) {
                c.shareId = null;
                c.shareUrl = null;
                c.shareBase = null;
                lib.update(c);
            }
        }
    }

    /**
     * Deletes everything this PC's account has on the server in use (clips, presets, notices, the phone gallery
     * and the account itself), then forgets the account. Library clips shared there lose their links; the videos on
     * this PC stay. Returns how many clips were removed from the server.
     */
    public int deleteAccount(Library lib) throws IOException {
        String server = base();
        if (settings.credsFor(server) == null) return 0;
        ensureAccount();
        int removed = 0;
        boolean gone = false;
        try {
            for (int i = 0; i < 50; i++) { // big accounts are deleted in steps of 300 clips
                JsonObject r = send(authed("/api/account").DELETE().build());
                if (!r.has("done") || r.get("done").getAsBoolean()) {
                    if (r.has("clips")) removed = r.get("clips").getAsInt();
                    gone = true;
                    break;
                }
            }
        } catch (HttpError e) {
            if (e.status != 401) throw e; // the server doesn't know this account any more: nothing left to delete
            gone = true;
        }
        if (!gone) throw new IOException("The share server is still deleting your clips. Try again in a minute.");
        forgetShares(lib, server);
        settings.forgetCreds(server);
        settings.save();
        return removed;
    }

    /**
     * "Reset sharing": forgets this PC's account on the server in use, so the next share creates a new one. Links
     * already shared keep working (they can't be changed from this PC any more).
     */
    public void resetSharing() {
        settings.forgetCreds(base());
        settings.save();
    }

    /** Clears the links of library clips shared to {@code server}. */
    private void forgetShares(Library lib, String server) {
        boolean changed = false;
        for (Clip c : lib.all()) {
            if (c.shareId == null || !sameServer(serverOf(c), server)) continue;
            c.shareId = null;
            c.shareUrl = null;
            c.shareBase = null;
            changed = true;
        }
        if (changed) lib.save();
        lib.changed();
    }

    /** Clips on the server for this account, plus storage use: {clips:[...], used, quota}. */
    public JsonObject list() throws IOException {
        ensureAccount();
        return send(authed("/api/clips").GET().build());
    }

    /** GET a JSON API path as this PC's account (e.g. community presets). */
    public JsonObject getJson(String path) throws IOException, InterruptedException {
        ensureAccount();
        return send(authed(path).GET().build());
    }

    /** POST JSON to an API path as this PC's account. */
    public JsonObject postJson(String path, JsonObject body) throws IOException, InterruptedException {
        ensureAccount();
        return send(authed(path).header("Content-Type", "application/json").POST(json(body)).build());
    }

    /**
     * Posts a shared clip to a Discord channel webhook: its title, game and link (without any private key), with
     * pings turned off. Throws with a readable reason when Discord refuses (a rate limit is reported, not retried).
     */
    public static void postWebhook(String url, Clip clip, String link) throws IOException {
        String clean = link == null ? "" : link;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        String title = clip.title == null || clip.title.isBlank() ? "Clip" : clip.title.strip();
        if (title.length() > 200) title = title.substring(0, 199) + "…";
        String game = clip.game == null || clip.game.isBlank() ? "" : " · " + clip.game.strip();
        JsonObject body = new JsonObject();
        body.addProperty("content", title + game + "\n" + clean);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        String target = url.trim() + (url.contains("?") ? "&" : "?") + "wait=true";
        HttpResponse<String> res;
        try {
            res = HOOKS.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        } catch (IllegalArgumentException e) {
            throw new IOException("That isn't a webhook link");
        }
        int sc = res.statusCode();
        if (sc >= 200 && sc < 300) return;
        if (sc == 429) throw new IOException("Discord is limiting posts to this channel right now. Try again in a minute.");
        if (sc == 401 || sc == 404) throw new IOException("Discord doesn't know this webhook any more. It may have been deleted; add it again in Settings.");
        String why = null;
        try {
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            if (o.has("message")) why = o.get("message").getAsString();
        } catch (Exception ignored) {}
        throw new IOException("Discord refused the post (HTTP " + sc + (why == null ? "" : ": " + why) + ")");
    }

    /** Hash of the edits a shared copy was rendered with; 0 for a clip shared as it is. */
    static int editHash(Clip c) { return c.edit == null || c.edit.isUntouched(c.duration) ? 0 : GSON.toJson(c.edit).hashCode(); }

    static String videoCodec(Path f) {
        return Ffmpeg.capture(List.of(Ffmpeg.ffprobe(), "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name",
                "-of", "csv=p=0", f.toString()), 15).trim();
    }

    // ------------------------------------------------------------------ http

    /** An error reply from the share server, with its HTTP status. */
    static final class HttpError extends IOException {
        final int status;
        HttpError(int status, String message) { super(message); this.status = status; }
    }

    private interface Call { JsonObject run() throws IOException; }

    /** Up to 3 tries with 1 s / 2 s pauses, for network errors and server errors (5xx); stops when cancelled. */
    private static JsonObject retrying(Ffmpeg.Job job, Call call) throws IOException {
        for (int attempt = 1; ; attempt++) {
            if (job != null && job.cancelled()) throw new IOException("Cancelled");
            try {
                return call.run();
            } catch (HttpError e) {
                if (e.status < 500 || attempt >= 3) throw e;
            } catch (IOException e) {
                if (attempt >= 3 || "Interrupted".equals(e.getMessage())) throw e;
            }
            try { Thread.sleep(1000L << (attempt - 1)); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
        }
    }

    private HttpRequest.Builder authed(String path) {
        return HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(60)).header("Authorization", bearer());
    }

    private static HttpRequest.Builder authed(String server, Settings.Creds cr, String path) {
        return HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + cr.account + "." + cr.key);
    }

    private static HttpRequest.BodyPublisher json(Object o) { return HttpRequest.BodyPublishers.ofString(GSON.toJson(o)); }

    private JsonObject send(HttpRequest r) throws IOException {
        HttpResponse<String> res;
        try { res = http.send(r, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
        int sc = res.statusCode();
        JsonObject o = null;
        try { o = JsonParser.parseString(res.body()).getAsJsonObject(); } catch (Exception ignored) {}
        if (sc == 401) throw new HttpError(401, "The share server doesn't know this PC any more. Reset sharing in Settings → Sharing & phone.");
        if (sc >= 300 || o == null) {
            String why = o != null && o.has("error") ? o.get("error").getAsString() : "Share server error (HTTP " + sc + ")";
            throw new HttpError(sc, why);
        }
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
