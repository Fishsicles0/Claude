package net.evadeclient.util;

import net.evadeclient.mixin.ItemCooldownManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
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

    private Cooldowns() {}

    public static Map<Identifier, Cooldown> active() {
        Map<Identifier, Cooldown> out = new LinkedHashMap<>();
        var acc = (ItemCooldownManagerAccessor) MinecraftClient.getInstance().player.getItemCooldownManager();
        int now = acc.evade$getTick();
        for (Map.Entry<Identifier, ?> e : acc.evade$getEntries().entrySet()) {
            int[] se = read(e.getValue());
            if (se != null && se[1] > now) {
                out.put(e.getKey(), new Cooldown(e.getKey(), se[1] - now, Math.max(1, se[1] - se[0])));
            }
        }
        return out;
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
