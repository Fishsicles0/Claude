package net.evadeclient.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every item Evade knows: the bundled starter set (assets/evade/cosmetics/catalog.json, always there) plus whatever the
 * catalog at evadeclient.com lists. An item is only drawn when it is in here, so a loadout naming an unknown id shows
 * nothing.
 */
public final class CosmeticsCatalog {
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static volatile Map<String, CosmeticItem> items = Map.of();
    private static volatile String remoteVersion;

    private CosmeticsCatalog() {}

    public static CosmeticItem get(String id) { return id == null ? null : items.get(id); }
    public static Collection<CosmeticItem> all() { return items.values(); }
    public static String remoteVersion() { return remoteVersion; }

    public static List<CosmeticItem> forSlot(Slot slot) {
        List<CosmeticItem> out = new ArrayList<>();
        for (CosmeticItem i : items.values()) if (i.slot == slot) out.add(i);
        return out;
    }

    /** Reads the bundled index (startup). */
    public static void loadBundled() {
        try (InputStream in = CosmeticsCatalog.class.getResourceAsStream("/assets/evade/cosmetics/catalog.json")) {
            if (in == null) throw new IllegalStateException("bundled cosmetics index missing");
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, CosmeticItem> m = new LinkedHashMap<>();
            for (JsonElement e : root.getAsJsonArray("items")) {
                CosmeticItem it = parse(e.getAsJsonObject(), true);
                if (it != null) m.put(it.id, it);
            }
            items = m;
        } catch (Exception e) {
            EvadeClient.LOG.error("Cosmetics: couldn't read the bundled catalog", e);
        }
    }

    /**
     * Merges the catalog from {@code GET /api/cosmetics/catalog}. A bundled item stays bundled unless the catalog lists
     * different files for it (an updated version, which then downloads). Items the catalog drops stay if bundled.
     */
    /** Tests: keep the bundled catalog (the live site may still serve an older one while a release is being tested). */
    public static volatile boolean ignoreRemoteForTest;

    public static void mergeRemote(JsonObject root) {
        if (ignoreRemoteForTest) return;
        Map<String, CosmeticItem> m = new LinkedHashMap<>(items);
        for (JsonElement e : root.getAsJsonArray("items")) {
            if (!e.isJsonObject()) continue;
            CosmeticItem it = parse(e.getAsJsonObject(), false);
            if (it == null) continue;
            CosmeticItem old = m.get(it.id);
            if (old != null && old.bundled && (it.files.isEmpty() || old.files.equals(it.files))) {
                // same art as the bundled copy: keep reading it from the jar, take the shop details
                m.put(it.id, new CosmeticItem(old.id, it.name, old.description, old.slot, it.rarity, old.colourable, old.defaultColor,
                        it.partner, it.priceCents, it.currency, null, old.files, true));
            } else if (!it.files.isEmpty() && it.base != null) {
                m.put(it.id, it);
            }
        }
        items = m;
        remoteVersion = root.has("version") && root.get("version").isJsonPrimitive() ? root.get("version").getAsString() : "?";
    }

    /**
     * One catalog entry. Bundled: {@code files} at the top level. Remote: {@code assets.base} and {@code assets.files}
     * (sha256 per file name); {@code files} or {@code sha256} at the top level are accepted too.
     */
    static CosmeticItem parse(JsonObject o, boolean bundled) {
        try {
            String id = o.get("id").getAsString();
            Slot slot = Slot.parse(o.get("slot").getAsString());
            if (!ID.matcher(id).matches() || slot == null) return null;
            String name = o.has("name") ? o.get("name").getAsString() : id;
            String desc = o.has("description") && !o.get("description").isJsonNull() ? o.get("description").getAsString() : "";
            Rarity rarity = Rarity.parse(o.has("rarity") ? o.get("rarity").getAsString() : null);
            boolean colourable = o.has("colourable") && o.get("colourable").getAsBoolean();
            int def = o.has("defaultColor") && !o.get("defaultColor").isJsonNull() ? Loadout.parseColor(o.get("defaultColor")) : -1;
            String partner = null;
            if (o.has("partner") && o.get("partner").isJsonObject()) {
                JsonObject p = o.getAsJsonObject("partner");
                partner = p.has("name") ? p.get("name").getAsString() : p.has("id") ? p.get("id").getAsString() : null;
            }
            if (partner != null && rarity == Rarity.REGULAR) rarity = Rarity.PARTNER;
            int price = o.has("priceCents") && !o.get("priceCents").isJsonNull() ? o.get("priceCents").getAsInt() : 0;
            String currency = o.has("currency") && !o.get("currency").isJsonNull() ? o.get("currency").getAsString() : "USD";
            String base = null;
            JsonObject filesObj = o.has("files") && o.get("files").isJsonObject() ? o.getAsJsonObject("files")
                    : o.has("sha256") && o.get("sha256").isJsonObject() ? o.getAsJsonObject("sha256") : null;
            if (o.has("assets") && o.get("assets").isJsonObject()) {
                JsonObject a = o.getAsJsonObject("assets");
                if (a.has("base")) base = a.get("base").getAsString();
                if (a.has("files") && a.get("files").isJsonObject()) filesObj = a.getAsJsonObject("files");
                else if (a.has("sha256") && a.get("sha256").isJsonObject()) filesObj = a.getAsJsonObject("sha256");
            }
            if (base != null && !base.endsWith("/")) base += "/";
            Map<String, String> files = new HashMap<>();
            if (filesObj != null) for (var f : filesObj.entrySet()) {
                if (CosmeticAssets.FILES.contains(f.getKey()) && f.getValue().isJsonPrimitive()) files.put(f.getKey(), f.getValue().getAsString());
            }
            if (!bundled && base != null && !base.startsWith("https://evadeclient.com/")) return null;
            return new CosmeticItem(id, name, desc, slot, rarity, colourable, def, partner, price, currency, base, files, bundled);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
