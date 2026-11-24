package net.evadeclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.Setting;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Copy-paste codes for one mod's settings ({@code EVMD1:...}), separate from whole-profile codes ({@code EVADE1:}).
 * Same encoding as profile codes (gzipped JSON, base64url): {@code {"v":1,"mod":"Zoom","settings":{...},"x":..,"y":..}}
 * with only the settings that differ from default. Keybinds and hidden settings never travel, and importing applies
 * to the mod you have now: settings missing from the code go back to their default, values are checked and clamped.
 */
public final class ModShare {
    public static final String PREFIX = "EVMD1:";
    private static final Pattern PREFIX_PATTERN = Pattern.compile("(?i)EVMD(\\d{1,3}):");
    private static final int VERSION = 1, MAX_CHARS = 64_000, MAX_JSON = 200_000;

    private ModShare() {}

    /** The outcome of an import: the mod it went to, or a ready-made message for why not. */
    public record Result(Module module, String error) {
        public boolean ok() { return error == null; }
    }

    /** A code for {@code m}'s current settings (and HUD position), or null if it can't be made. */
    public static String code(Module m) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("v", VERSION);
            json.addProperty("mod", m.getName());
            JsonObject settings = new JsonObject();
            for (Setting<?> s : m.getSettings()) {
                if (!ProfileManager.travels(s) || s.isDefault()) continue;
                settings.add(s.getName(), s.toJson());
            }
            json.add("settings", settings);
            if (m instanceof HudElementModule h) {
                json.addProperty("x", h.x);
                json.addProperty("y", h.y);
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ProfileManager.gzip(json.toString()));
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't make a share code for {}", m.getName(), e);
            return null;
        }
    }

    /** Whether {@code text} has a mod code in it (anywhere, any case). */
    public static boolean looksLikeCode(String text) {
        return text != null && PREFIX_PATTERN.matcher(text).find();
    }

    /**
     * Apply a mod code. With {@code expected} set, the code must be for that mod (the Paste button on a mod's page);
     * with null it goes to whichever mod it names ({@code .import}). Saves the config on success.
     */
    public static Result apply(String text, Module expected) {
        if (text == null || text.isBlank()) return new Result(null, "Copy a mod code first");
        if (text.length() > MAX_CHARS * 2) return new Result(null, "That code is too large");
        Matcher m = PREFIX_PATTERN.matcher(text);
        if (!m.find()) {
            if (text.toUpperCase(java.util.Locale.ROOT).contains("EVADE1:")) return new Result(null, "That's a whole-profile code: import it on the Profiles page");
            return new Result(null, "Not a mod code (they start with " + PREFIX + ")");
        }
        int version = Integer.parseInt(m.group(1));
        if (version > VERSION) return new Result(null, "That code is from a newer Evade");
        if (version < 1) return new Result(null, "Not a mod code");
        List<String> parts = ProfileManager.payloadParts(text, m.end());
        JsonObject json = null;
        for (int k = parts.size(); k >= 1 && json == null; k--) json = decode(String.join("", parts.subList(0, k)));
        if (json == null) return new Result(null, "That code looks damaged or cut off");
        JsonElement v = json.get("v");
        if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() > VERSION)
            return new Result(null, "That code is from a newer Evade");
        JsonElement name = json.get("mod");
        Module mod = name != null && name.isJsonPrimitive() ? EvadeClient.modules.get(name.getAsString()) : null;
        if (mod == null) return new Result(null, "That code is for a mod this Evade doesn't have");
        if (expected != null && mod != expected) return new Result(null, "That code is for " + mod.getName() + ", not " + expected.getName());
        JsonObject settings = ConfigManager.object(json, "settings");
        for (Setting<?> s : mod.getSettings()) {
            if (!ProfileManager.travels(s)) continue;
            JsonElement val = settings == null ? null : settings.get(s.getName());
            if (val == null) { s.reset(); continue; }
            JsonElement plain = ProfileManager.bounded(val);
            s.loadJson(plain != null ? plain : s.normalizeJson(val));
        }
        if (mod instanceof HudElementModule h && json.has("x") && json.has("y")) {
            int[] def = EvadeClient.config.defaultPosition(h);
            h.x = ConfigManager.coord(json.get("x"), def[0]);
            h.y = ConfigManager.coord(json.get("y"), def[1]);
        }
        try {
            mod.onConfigLoaded(settings == null ? new JsonObject() : settings);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Mod code migration failed for {}", mod.getName(), e);
        }
        EvadeClient.config.save();
        return new Result(mod, null);
    }

    private static JsonObject decode(String payload) {
        if (payload.length() > MAX_CHARS) return null;
        byte[] raw = ProfileManager.decodeBase64(payload);
        if (raw == null) return null;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] bytes = gz.readNBytes(MAX_JSON + 1);
            if (bytes.length > MAX_JSON) return null;
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
