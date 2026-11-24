package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cooldowns a server shows in Evade's Cooldown HUD ({@code cooldown.set / clear}), for its own abilities and
 * items: a name, an item icon and a length in seconds. They count down on the client and drop off when done.
 */
public final class ApiCooldowns {
    /** One server cooldown; times are {@link System#currentTimeMillis()} values. */
    public record ServerCooldown(String id, String name, ItemStack icon, long start, long end) {
        /** Ticks left (rounded up), at least 0. */
        public int remainingTicks(long now) { return (int) Math.max(0, (end - now + 49) / 50); }
        /** Full length in ticks, at least 1. */
        public int totalTicks() { return (int) Math.max(1, (end - start + 49) / 50); }
    }

    static final int MAX = 32;
    /** Longest cooldown a server can set: one hour. */
    static final double MAX_SECONDS = 3600;

    private static final Map<String, ServerCooldown> cooldowns = new LinkedHashMap<>();

    private ApiCooldowns() {}

    /** The running server cooldowns (finished ones are dropped first). Render/client thread only. */
    public static List<ServerCooldown> active() {
        if (cooldowns.isEmpty()) return List.of();
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(c -> c.end <= now);
        return new ArrayList<>(cooldowns.values());
    }

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "cooldown.set" -> {
                String id = ApiJson.id(o);
                if (id == null) return false;
                double secs = Math.min(MAX_SECONDS, ApiJson.num(o, "seconds", 0));
                if (secs <= 0) return cooldowns.remove(id) != null; // 0 ends it
                if (!cooldowns.containsKey(id) && cooldowns.size() >= MAX) return false;
                String name = ApiJson.str(o, "name", id, ApiJson.MAX_NAME);
                long now = System.currentTimeMillis();
                cooldowns.put(id, new ServerCooldown(id, name.isEmpty() ? id : name, icon(ApiJson.str(o, "icon", "", 128)),
                        now, now + Math.round(secs * 1000)));
                return true;
            }
            case "cooldown.clear" -> {
                String id = ApiJson.id(o);
                if (id == null) {
                    boolean any = !cooldowns.isEmpty();
                    cooldowns.clear();
                    return any;
                }
                return cooldowns.remove(id) != null;
            }
            default -> { return false; }
        }
    }

    /** The item {@code id} names ("minecraft:ender_pearl", or just "ender_pearl"), or a clock if there's none. */
    static ItemStack icon(String id) {
        Identifier ident = id == null || id.isEmpty() ? null : Identifier.tryParse(id.toLowerCase(java.util.Locale.ROOT));
        if (ident != null && Registries.ITEM.containsId(ident)) {
            Item item = Registries.ITEM.get(ident);
            if (item != Items.AIR) return new ItemStack(item);
        }
        return new ItemStack(Items.CLOCK);
    }

    static void clear() { cooldowns.clear(); }
}
