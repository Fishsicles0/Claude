package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.ItemCooldownManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads active item cooldowns: ender pearl, wind charge, spear, chorus fruit, disabled shield,
 * goat horn, and any cooldown a server adds (e.g. gapple cooldowns on PvP servers).
 */
public final class Cooldowns {
    /** Remaining and total ticks of one cooldown group. */
    public record Cooldown(Identifier group, int remaining, int total) {
        public float fraction() { return Math.min(1f, remaining / (float) total); }
    }

    // ItemCooldownManager.Entry is a package-private record (startTick, endTick); read it by component index
    // so this keeps working under any mapping names.
    private static Method startTick, endTick;

    /** {@link #activeCached()}'s result, the tick it was read on and the player it belongs to. */
    private static Map<Identifier, Cooldown> cached = Map.of();
    private static long cachedTick = Long.MIN_VALUE;
    private static ClientPlayerEntity cachedPlayer;

    private Cooldowns() {}

    /** The local player's active cooldowns, read fresh (empty without a player). */
    public static Map<Identifier, Cooldown> active() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return Map.of();
        Map<Identifier, Cooldown> out = new LinkedHashMap<>();
        var acc = (ItemCooldownManagerAccessor) player.getItemCooldownManager();
        int now = acc.evade$getTick();
        for (Map.Entry<Identifier, ?> e : acc.evade$getEntries().entrySet()) {
            int[] se = read(e.getValue());
            if (se != null && se[1] > now) {
                out.put(e.getKey(), new Cooldown(e.getKey(), se[1] - now, Math.max(1, se[1] - se[0])));
            }
        }
        return out;
    }

    /**
     * {@link #active()}, read at most once per client tick (and again when the player entity changes): the HUD
     * widget, its tick logic and every hotbar slot share one read. Unmodifiable. Render/client thread only.
     */
    public static Map<Identifier, Cooldown> activeCached() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            cachedPlayer = null;
            cached = Map.of();
            return cached;
        }
        if (cachedTick != EvadeClient.ticks || cachedPlayer != player) {
            cached = Collections.unmodifiableMap(active());
            cachedTick = EvadeClient.ticks;
            cachedPlayer = player;
        }
        return cached;
    }

    private static int[] read(Object entry) {
        try {
            if (startTick == null) {
                RecordComponent[] comps = entry.getClass().getRecordComponents();
                startTick = comps[0].getAccessor();
                endTick = comps[1].getAccessor();
                startTick.setAccessible(true);
                endTick.setAccessible(true);
            }
            return new int[]{(int) startTick.invoke(entry), (int) endTick.invoke(entry)};
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
