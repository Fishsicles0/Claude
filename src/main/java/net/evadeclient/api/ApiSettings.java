package net.evadeclient.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Session limits on number settings from a {@code rules} message: {@code "settings": {"Zoom": {"Max Zoom": 4}}}
 * caps Zoom's Max Zoom at 4 while the player is connected. A number is an upper bound; {@code {"min": a, "max": b}}
 * gives either or both bounds. Only what Evade uses changes: the player's own value stays saved and is back as soon
 * as the limit lifts (a new {@code settings} object, or leaving the server).
 */
public final class ApiSettings {
    static final int MAX = 64;

    private static final Set<NumberSetting> limited = new LinkedHashSet<>();

    private ApiSettings() {}

    /** Replaces every limit with the ones in {@code settings}; returns how many settings are now limited. */
    static int apply(JsonObject settings) {
        clear();
        if (settings == null || EvadeClient.modules == null) return 0;
        for (var mod : settings.entrySet()) {
            Module m = EvadeClient.modules.get(mod.getKey());
            if (m == null || !mod.getValue().isJsonObject()) continue;
            for (var entry : mod.getValue().getAsJsonObject().entrySet()) {
                if (limited.size() >= MAX) return limited.size();
                NumberSetting s = find(m, entry.getKey());
                if (s == null) continue;
                double lo = Double.NaN, hi = Double.NaN;
                JsonElement v = entry.getValue();
                if (v.isJsonPrimitive() && ((JsonPrimitive) v).isNumber()) {
                    hi = v.getAsDouble();
                } else if (v.isJsonObject()) {
                    hi = ApiJson.num(v.getAsJsonObject(), "max", Double.NaN);
                    lo = ApiJson.num(v.getAsJsonObject(), "min", Double.NaN);
                }
                if (!Double.isFinite(lo)) lo = Double.NaN;
                if (!Double.isFinite(hi)) hi = Double.NaN;
                if (Double.isNaN(lo) && Double.isNaN(hi)) continue;
                if (!Double.isNaN(lo) && !Double.isNaN(hi) && lo > hi) continue;
                s.limit(lo, hi);
                limited.add(s);
            }
        }
        return limited.size();
    }

    /** {@code m}'s number setting called {@code name} (any case, spaces and symbols ignored), or null. */
    private static NumberSetting find(Module m, String name) {
        String n = ModuleManager.normalize(name);
        for (Setting<?> s : m.getSettings()) {
            if (s instanceof NumberSetting ns && !s.isHidden()
                    && (s.getName().equalsIgnoreCase(name) || ModuleManager.normalize(s.getName()).equals(n))) return ns;
        }
        return null;
    }

    /** Whether a server limits {@code s} right now. */
    public static boolean isLimited(Setting<?> s) {
        return s instanceof NumberSetting ns && ns.isLimited();
    }

    static void clear() {
        for (NumberSetting s : limited) s.clearLimit();
        limited.clear();
    }
}
