package net.evadeclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.Notifications;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Your own cosmetics: what you own (evadeclient.com says, via GET /me), what you wear and up to six saved outfits
 * (config/evade/wardrobe.json). Nothing is owned until the backend says so. Test mode (the JVM flag
 * {@code -Devade.cosmetics.test=true}, and the in-game tests) owns every bundled item locally so each can be previewed;
 * it never tells the server anything it didn't already allow.
 */
public final class Wardrobe {
    public static final int OUTFITS = 6;
    private static final long REFRESH_MS = 5 * 60_000;

    public record Outfit(String name, Loadout loadout) {}

    private static volatile Set<String> owned = Set.of();
    private static volatile Loadout equipped = Loadout.EMPTY;
    private static final Outfit[] outfits = new Outfit[OUTFITS];
    private static volatile boolean testMode = Boolean.getBoolean("evade.cosmetics.test");
    private static volatile String status = "Not signed in";
    private static volatile long lastRefresh;
    private static volatile boolean refreshing, loaded;

    private Wardrobe() {}

    public static boolean testMode() { return testMode; }

    /** Test hook (and the dev flag): own every bundled item locally. */
    public static void setTestMode(boolean on) { testMode = on; }

    public static String status() { return testMode ? "Test mode: every starter item is unlocked on this PC" : status; }

    public static boolean owns(String id) {
        if (id == null) return false;
        if (testMode) { CosmeticItem it = CosmeticsCatalog.get(id); if (it != null && it.bundled) return true; }
        return owned.contains(id);
    }

    public static Set<String> owned() { return Collections.unmodifiableSet(owned); }

    public static Loadout equipped() { return equipped; }

    /** What to draw on you: what you wear, owned items only. */
    public static Loadout renderLoadout() {
        Loadout l = equipped;
        if (l.isEmpty()) return l;
        for (var e : l.all().entrySet()) {
            if (!owns(e.getValue().id())) l = l.with(e.getKey(), null);
        }
        return l;
    }

    public static Outfit outfit(int i) { return i >= 0 && i < OUTFITS ? outfits[i] : null; }

    // ------------------------------------------------------------------ changes

    /** Wear {@code id} in {@code slot} (null takes it off), {@code color} RGB or -1. Owned items only. Render thread. */
    public static boolean equip(Slot slot, String id, int color) {
        if (id != null) {
            CosmeticItem it = CosmeticsCatalog.get(id);
            if (it == null || it.slot != slot || !owns(id)) return false;
            if (!it.colourable) color = -1;
        }
        equipped = equipped.with(slot, id == null ? null : new Loadout.Worn(id, color));
        save();
        sync(slot, id, color);
        return true;
    }

    /** Wears a whole loadout (an outfit), slot by slot; items you don't own are skipped. */
    public static void wear(Loadout l) {
        for (Slot s : Slot.values()) {
            Loadout.Worn w = l.get(s), now = equipped.get(s);
            if (w != null && !owns(w.id())) continue;
            if (w == null ? now == null : w.equals(now)) continue;
            equipped = equipped.with(s, w);
            sync(s, w == null ? null : w.id(), w == null ? -1 : w.color());
        }
        save();
    }

    public static void saveOutfit(int i, String name) {
        if (i < 0 || i >= OUTFITS) return;
        outfits[i] = new Outfit(name == null || name.isBlank() ? "Outfit " + (i + 1) : name, equipped);
        save();
    }

    public static void clearOutfit(int i) {
        if (i < 0 || i >= OUTFITS) return;
        outfits[i] = null;
        save();
    }

    /** Tells evadeclient.com (others see it from there). Test mode keeps it on this PC. */
    private static void sync(Slot slot, String id, int color) {
        if (testMode || !CosmeticsApi.signedIn()) return;
        CosmeticsApi.equip(slot, id, color).whenComplete((r, err) -> MinecraftClient.getInstance().execute(() -> {
            if (err != null) Notifications.push("Wardrobe", "Couldn't save on evadeclient.com: " + CosmeticsApi.reason(err), false);
        }));
    }

    // ------------------------------------------------------------------ backend

    /** Asks evadeclient.com what you own and wear (at most every few minutes unless {@code force}). Render thread. */
    public static void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (refreshing || (!force && now - lastRefresh < REFRESH_MS)) return;
        refreshing = true;
        lastRefresh = now;
        if (!CosmeticsApi.signedIn()) status = "Signing in...";
        CosmeticsApi.me().whenComplete((r, err) -> MinecraftClient.getInstance().execute(() -> {
            refreshing = false;
            if (err != null) {
                status = CosmeticsApi.reason(err);
                if (status.startsWith("Signing in")) lastRefresh = System.currentTimeMillis() - REFRESH_MS + 15_000;
                return;
            }
            applyMe(r);
        }));
    }

    /** Applies a GET /me answer: {owned: [ids], equipped: {SLOT: {id, color?}}}. */
    public static void applyMe(JsonObject r) {
        Set<String> o = new HashSet<>();
        if (r.has("owned") && r.get("owned").isJsonArray()) for (JsonElement e : r.getAsJsonArray("owned")) if (e.isJsonPrimitive()) o.add(e.getAsString());
        owned = Set.copyOf(o);
        if (r.has("equipped")) {
            Loadout server = Loadout.fromJson(r.get("equipped"));
            if (!testMode) equipped = server;
        }
        status = o.isEmpty() ? "Signed in: nothing owned yet" : "Signed in: " + o.size() + (o.size() == 1 ? " item" : " items") + " owned";
        save();
    }

    // ------------------------------------------------------------------ file

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("wardrobe.json");
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            equipped = Loadout.fromJson(o.get("equipped"));
            if (o.has("outfits") && o.get("outfits").isJsonArray()) {
                JsonArray a = o.getAsJsonArray("outfits");
                for (int i = 0; i < OUTFITS && i < a.size(); i++) {
                    if (!a.get(i).isJsonObject()) continue;
                    JsonObject of = a.get(i).getAsJsonObject();
                    String name = of.has("name") ? of.get("name").getAsString() : "Outfit " + (i + 1);
                    outfits[i] = new Outfit(name.length() > 24 ? name.substring(0, 24) : name, Loadout.fromJson(of.get("loadout")));
                }
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Wardrobe: couldn't read wardrobe.json ({}), starting fresh", e.toString());
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.add("equipped", equipped.toJson());
        JsonArray a = new JsonArray();
        for (Outfit of : outfits) {
            if (of == null) { a.add(JsonNull.INSTANCE); continue; }
            JsonObject j = new JsonObject();
            j.addProperty("name", of.name());
            j.add("loadout", of.loadout().toJson());
            a.add(j);
        }
        o.add("outfits", a);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("wardrobe.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Wardrobe: couldn't save", e);
        }
    }

    /** Test hook: start from nothing worn and no outfits (file included). */
    public static void resetForTest() {
        equipped = Loadout.EMPTY;
        java.util.Arrays.fill(outfits, null);
        save();
    }
}
