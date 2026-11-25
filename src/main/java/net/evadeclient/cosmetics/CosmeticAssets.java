package net.evadeclient.cosmetics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item files: the bundled starter set is read from the jar, anything else downloads from the item's assets base on
 * evadeclient.com into config/evade/cosmetics-cache/. Every file must match the sha256 the catalog lists before it is
 * used (bundled ones too), so a broken download or a tampered cache never reaches the renderer. Textures are
 * registered with the game's texture manager on the render thread.
 */
public final class CosmeticAssets {
    public enum State { LOADING, READY, FAILED }

    /** Files an item can have; nothing else is ever requested or written. */
    public static final Set<String> FILES = Set.of("item.json", "model.json", "texture.png", "emissive.png", "thumb.png");
    private static final long MAX_FILE = 2 * 1024 * 1024;
    private static final String HOST = "https://evadeclient.com/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private record Loaded(State state, CosmeticModel model, Identifier thumb, String error, int thumbFrames) {
        Loaded(State state, CosmeticModel model, Identifier thumb, String error) { this(state, model, thumb, error, 1); }
    }

    private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();
    /** The item instance whose item.json data each loaded id was read into (the catalog can replace instances). */
    private static final Map<String, CosmeticItem> SOURCE = new ConcurrentHashMap<>();

    private CosmeticAssets() {}

    public static Path cacheDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("cosmetics-cache");
    }

    /** The item's model when it is ready to draw, else null (and its files start loading). Render thread. */
    public static CosmeticModel model(CosmeticItem item) {
        Loaded l = load(item);
        return l != null && l.state == State.READY ? l.model : null;
    }

    /** The thumbnail texture when loaded, else null (and loading starts). Render thread. */
    public static Identifier thumb(CosmeticItem item) {
        Loaded l = load(item);
        return l != null && l.state == State.READY ? l.thumb : null;
    }

    /**
     * Where to draw the thumbnail now: {frame a, frame b, blend, frames} for an animated strip (frames > 1), so the
     * Wardrobe grid can show frame a and fade frame b over it. Null when not loaded.
     */
    public static float[] thumbFrame(CosmeticItem item) {
        Loaded l = LOADED.get(item.id);
        if (l == null || l.state != State.READY || l.thumb == null) return null;
        if (l.thumbFrames <= 1) return new float[]{0, 0, 0, 1};
        float[] f = AnimatedTexture.frameAt(item.animation, AnimatedTexture.clockTicks());
        return new float[]{f[0], f[1], f[2], l.thumbFrames};
    }

    public static State state(CosmeticItem item) {
        Loaded l = LOADED.get(item.id);
        return l == null ? null : l.state;
    }

    /** Why an item failed to load (for the Wardrobe and tests), or null. */
    public static String error(CosmeticItem item) {
        Loaded l = LOADED.get(item.id);
        return l == null ? null : l.error;
    }

    /** Starts loading an item's files if that hasn't happened yet. Render thread. */
    public static void request(CosmeticItem item) { load(item); }

    private static Loaded load(CosmeticItem item) {
        Loaded l = LOADED.get(item.id);
        if (l != null) {
            // The catalog may have replaced this item with a new instance (start-up merge, reload) for the same files:
            // give it the item.json data the first one was given, or its effects, emote and physics would be missing.
            CosmeticItem src = SOURCE.get(item.id);
            if (src != null && src != item && src.files.equals(item.files)) {
                if (item.fx == null) item.fx = src.fx;
                if (item.emote == null) item.emote = src.emote;
                if (item.animation == null) item.animation = src.animation;
                item.stiffness = src.stiffness; item.lift = src.lift; item.sway = src.sway;
            }
            return l;
        }
        SOURCE.put(item.id, item);
        LOADED.put(item.id, new Loaded(State.LOADING, null, null, null));
        if (item.bundled) {
            // Small files inside the jar: read and set up right away.
            try {
                Map<String, byte[]> files = new java.util.HashMap<>();
                for (String f : item.files.keySet()) files.put(f, verified(item, f, readBundled(item.id, f)));
                install(item, files);
            } catch (Exception e) {
                fail(item, e);
            }
        } else {
            CompletableFuture.supplyAsync(() -> fetchAll(item)).whenComplete((files, err) ->
                    MinecraftClient.getInstance().execute(() -> {
                        if (err != null) { fail(item, err.getCause() != null ? err.getCause() : err); return; }
                        try { install(item, files); } catch (Exception e) { fail(item, e); }
                    }));
        }
        return LOADED.get(item.id);
    }

    private static void fail(CosmeticItem item, Throwable e) {
        EvadeClient.LOG.warn("Cosmetics: couldn't load {}: {}", item.id, e.toString());
        LOADED.put(item.id, new Loaded(State.FAILED, null, null, String.valueOf(e.getMessage())));
    }

    /** Parses item.json and model.json and registers the textures. Render thread. */
    private static void install(CosmeticItem item, Map<String, byte[]> files) throws IOException {
        byte[] itemJson = files.get("item.json");
        if (itemJson != null) {
            JsonObject o = JsonParser.parseString(new String(itemJson, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("fx") && o.get("fx").isJsonObject()) item.fx = o.getAsJsonObject("fx");
            if (o.has("emote") && o.get("emote").isJsonObject()) item.emote = Emotes.Animation.parse(o.getAsJsonObject("emote"));
            item.animation = AnimatedTexture.Spec.parse(o.get("animation"));
            if (o.has("physics") && o.get("physics").isJsonObject()) {
                JsonObject ph = o.getAsJsonObject("physics");
                item.stiffness = CosmeticModel.clamp(CosmeticModel.num(ph, "stiffness", 0.5f), 0f, 1f);
                item.lift = CosmeticModel.clamp(CosmeticModel.num(ph, "lift", 1f), 0f, 2f);
                item.sway = CosmeticModel.clamp(CosmeticModel.num(ph, "sway", 1f), 0f, 2f);
            }
        }
        CosmeticModel model = null;
        if (files.containsKey("model.json") && files.containsKey("texture.png")) {
            Identifier tex = Identifier.of("evade", "cosmetics/" + item.id);
            JsonObject mo = JsonParser.parseString(new String(files.get("model.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            model = new CosmeticModel(item.id, mo, tex);
            model.bake();
            float aspect = model.texH / (float) model.texW;
            model.tex = new AnimatedTexture(tex, files.get("texture.png"), item.animation, aspect, "Evade cosmetic " + item.id);
            if (files.containsKey("emissive.png")) {
                Identifier glow = Identifier.of("evade", "cosmetics/" + item.id + "/emissive");
                model.glow = new AnimatedTexture(glow, files.get("emissive.png"), item.animation, aspect, "Evade cosmetic glow " + item.id);
                if (model.glow.frameW != model.tex.frameW || model.glow.frameH != model.tex.frameH)
                    throw new IOException("emissive.png must be the same size as texture.png");
            }
        }
        Identifier thumb = null;
        int thumbFrames = 1;
        if (files.containsKey("thumb.png")) {
            thumb = Identifier.of("evade", "cosmetics/" + item.id + "/thumb");
            int[] wh = register(thumb, files.get("thumb.png"), "Evade cosmetic thumbnail " + item.id);
            // an animated item's thumbnail may be a strip of the same number of square frames
            if (item.animation != null && item.animation.frames() > 1 && wh[1] == wh[0] * item.animation.frames()) thumbFrames = item.animation.frames();
        }
        LOADED.put(item.id, new Loaded(State.READY, model, thumb, null, thumbFrames));
    }

    private static int[] register(Identifier id, byte[] png, String name) throws IOException {
        NativeImage img = NativeImage.read(png);
        if (img.getWidth() > 2048 || img.getHeight() > 4096) { img.close(); throw new IOException("texture too large"); }
        int[] wh = {img.getWidth(), img.getHeight()};
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> name, img));
        return wh;
    }

    // ------------------------------------------------------------------ files

    static byte[] readBundled(String id, String file) throws IOException {
        try (InputStream in = CosmeticAssets.class.getResourceAsStream("/assets/evade/cosmetics/" + id + "/" + file)) {
            if (in == null) throw new IOException("missing bundled file " + id + "/" + file);
            return in.readNBytes((int) MAX_FILE);
        }
    }

    /** The cached copy if it matches, else a fresh download (which must match too). Off-thread. */
    private static Map<String, byte[]> fetchAll(CosmeticItem item) {
        Map<String, byte[]> out = new java.util.HashMap<>();
        for (String f : item.files.keySet()) {
            if (!FILES.contains(f)) continue;
            Path cached = cacheDir().resolve(item.id).resolve(f);
            try {
                if (Files.isRegularFile(cached) && Files.size(cached) <= MAX_FILE) {
                    byte[] b = Files.readAllBytes(cached);
                    if (matches(b, item.files.get(f))) { out.put(f, b); continue; }
                }
                byte[] b = verified(item, f, download(item.base + f));
                Files.createDirectories(cached.getParent());
                Path tmp = cached.resolveSibling(f + ".tmp");
                Files.write(tmp, b);
                Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                out.put(f, b);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        return out;
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        if (!url.startsWith(HOST)) throw new IOException("assets must come from evadeclient.com");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION).GET().build();
        HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            byte[] b = in.readNBytes((int) MAX_FILE + 1);
            if (b.length > MAX_FILE) throw new IOException("file too large: " + url);
            return b;
        }
    }

    private static byte[] verified(CosmeticItem item, String file, byte[] data) throws IOException {
        if (!matches(data, item.files.get(file))) throw new IOException("sha256 mismatch for " + item.id + "/" + file);
        return data;
    }

    /** Whether {@code data} hashes to {@code sha256} (hex, any case). A missing hash never matches. */
    public static boolean matches(byte[] data, String sha256) {
        return sha256 != null && data != null && sha256(data).equalsIgnoreCase(sha256.trim());
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every bundled file checked against the bundled index: the files that don't match (empty = all good). */
    public static List<String> verifyBundled() {
        List<String> bad = new java.util.ArrayList<>();
        for (CosmeticItem item : CosmeticsCatalog.all()) {
            if (!item.bundled) continue;
            for (var e : item.files.entrySet()) {
                try {
                    if (!matches(readBundled(item.id, e.getKey()), e.getValue())) bad.add(item.id + "/" + e.getKey());
                } catch (IOException ex) {
                    bad.add(item.id + "/" + e.getKey() + " (" + ex.getMessage() + ")");
                }
            }
        }
        return bad;
    }

    /** Test hook: forget what was loaded (textures stay registered). */
    public static void resetForTest(String id) { LOADED.remove(id); SOURCE.remove(id); }

    /** Loads every bundled item now (Wardrobe preview, tests). Render thread. */
    public static void preloadBundled() {
        for (CosmeticItem item : CosmeticsCatalog.all()) if (item.bundled) request(item);
    }
}
