package net.evadeclient.cosmetics;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What other Evade players near you wear. Looked up in batches of up to 64 only for players already rendered near you
 * (and in the tab list), each answer kept for a minute; players with nothing are remembered for five minutes, so a
 * crowd of players without cosmetics costs one request every five minutes and nothing per frame. Vanilla players never appear in the
 * answer, so they never see or show anything.
 */
public final class PlayerCosmetics {
    public static final long CACHE_MS = 60_000;
    /** How long "wears nothing" is kept: most players near you aren't Evade players, so ask about them rarely. */
    public static final long EMPTY_CACHE_MS = 5 * 60_000;
    private static final long RETRY_MS = 30_000;

    private record Entry(Loadout loadout, long at) {}

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean inFlight;
    private static volatile long nextTry;
    private static volatile int requests;

    private PlayerCosmetics() {}

    /** Someone's loadout, or null when unknown or empty. Any thread; no network. */
    public static Loadout get(UUID id) {
        Entry e = CACHE.get(id);
        return e == null || e.loadout.isEmpty() ? null : e.loadout;
    }

    /** Whether anyone cached wears anything (the fast path: nothing to do when false). */
    public static boolean anyone() {
        for (Entry e : CACHE.values()) if (!e.loadout.isEmpty()) return true;
        return false;
    }

    /** Lookups sent since launch (tests, profiling). */
    public static int requests() { return requests; }

    /** Every second while the module is on: asks about nearby players we have no fresh answer for. */
    public static void tick(MinecraftClient mc, double range) {
        if (inFlight || mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;
        long now = System.currentTimeMillis();
        if (now < nextTry) return;
        CACHE.values().removeIf(e -> now - e.at > EMPTY_CACHE_MS * 2);
        List<UUID> want = new ArrayList<>();
        double r2 = range * range;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            UUID u = p.getUuid();
            if (p.squaredDistanceTo(mc.player) > r2 || mc.getNetworkHandler().getPlayerListEntry(u) == null) continue;
            Entry e = CACHE.get(u);
            if (e != null && now - e.at < ttl(e.loadout)) continue;
            want.add(u);
            if (want.size() >= CosmeticsApi.MAX_BATCH) break;
        }
        if (want.isEmpty()) return;
        inFlight = true;
        requests++;
        CosmeticsApi.players(want).whenComplete((res, err) -> {
            long t = System.currentTimeMillis();
            try {
                if (err != null || res == null) { nextTry = t + RETRY_MS; return; }
                apply(want, res, t);
            } finally {
                inFlight = false;
            }
        });
    }

    /** Stores a players answer: everyone asked about gets an entry (empty when the answer leaves them out). */
    static void apply(List<UUID> asked, JsonObject res, long now) {
        JsonObject players = res.has("players") && res.get("players").isJsonObject() ? res.getAsJsonObject("players") : new JsonObject();
        Map<String, JsonObject> byKey = new java.util.HashMap<>();
        for (var e : players.entrySet()) {
            if (e.getValue().isJsonObject()) byKey.put(e.getKey().replace("-", "").toLowerCase(java.util.Locale.ROOT), e.getValue().getAsJsonObject());
        }
        for (UUID u : asked) {
            JsonObject p = byKey.get(u.toString().replace("-", ""));
            Loadout l = p == null ? Loadout.EMPTY : Loadout.fromJson(p.get("equipped"));
            CACHE.put(u, new Entry(l, now));
        }
    }

    /** Test hook: pretend evadeclient.com said this player wears {@code l}. */
    public static void putForTest(UUID id, Loadout l) { CACHE.put(id, new Entry(l, System.currentTimeMillis())); }

    public static void clear() { CACHE.clear(); }

    /** Test hook: whether {@code id} would be asked about again {@code ageMs} after an answer of {@code l}. */
    public static boolean staleForTest(Loadout l, long ageMs) { return ageMs >= ttl(l); }

    /** How long an answer stays fresh: a minute for someone wearing something, five for nothing. */
    static long ttl(Loadout l) { return l.isEmpty() ? EMPTY_CACHE_MS : CACHE_MS; }
}
