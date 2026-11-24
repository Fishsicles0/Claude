package net.evadeclient.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Map;

/**
 * Lenient readers for Evade Server API messages. A field that is missing or of the wrong type reads as the default;
 * strings are stripped of line breaks and cut to a length, numbers must be finite. Nothing here throws.
 */
public final class ApiJson {
    /** Longest id, name and text line a server can send (longer ones are cut). */
    public static final int MAX_ID = 64, MAX_NAME = 64, MAX_TEXT = 160;
    /** Coordinates further out than vanilla's world border (or this far up or down) are refused. */
    public static final double MAX_XZ = 30_000_000, MAX_Y = 20_000;

    private static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("red", 0xFFE11D2E), Map.entry("orange", 0xFFFF8A1F), Map.entry("yellow", 0xFFFFD21F),
            Map.entry("gold", 0xFFFFB020), Map.entry("green", 0xFF3BE37A), Map.entry("lime", 0xFF7CFC5A),
            Map.entry("cyan", 0xFF29D3F0), Map.entry("aqua", 0xFF29D3F0), Map.entry("blue", 0xFF3D7BFF),
            Map.entry("purple", 0xFFA95CFF), Map.entry("pink", 0xFFFF5CC8), Map.entry("white", 0xFFFFFFFF),
            Map.entry("gray", 0xFFB8B8C0), Map.entry("grey", 0xFFB8B8C0), Map.entry("black", 0xFF101014));

    private ApiJson() {}

    /** A string field, one line, at most {@code max} characters; {@code def} when absent or not a string/number. */
    public static String str(JsonObject o, String key, String def, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        return clean(e.getAsString(), max);
    }

    /** {@code s} on one line, trimmed and cut to {@code max} characters. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        return one.length() > max ? one.substring(0, max) : one;
    }

    /** The message's {@code id}, or null when it has none. */
    public static String id(JsonObject o) {
        String id = str(o, "id", null, MAX_ID);
        return id == null || id.isEmpty() ? null : id;
    }

    public static double num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) {
            // Numbers sent as strings ("12.5") are fine too.
            if (e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isString()) {
                try {
                    double d = Double.parseDouble(e.getAsString().strip());
                    return Double.isFinite(d) ? d : def;
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : def;
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        JsonPrimitive p = (JsonPrimitive) e;
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble() != 0;
        String s = p.getAsString().strip().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("on") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("off") || s.equals("no")) return false;
        return def;
    }

    /** Whether x, y and z are all present and inside the world. */
    public static boolean hasPos(JsonObject o) {
        double x = num(o, "x", Double.NaN), y = num(o, "y", Double.NaN), z = num(o, "z", Double.NaN);
        return !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)
                && Math.abs(x) <= MAX_XZ && Math.abs(z) <= MAX_XZ && Math.abs(y) <= MAX_Y;
    }

    /**
     * A colour: {@code "#RRGGBB"}, {@code "#AARRGGBB"}, the same without {@code #} or with {@code 0x}, a number
     * (RGB, or ARGB when it has alpha), or a name (red, orange, yellow, gold, green, lime, cyan, aqua, blue, purple,
     * pink, white, gray, black). Returned opaque unless an alpha was given; {@code def} when unreadable.
     */
    public static int color(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        JsonPrimitive p = (JsonPrimitive) e;
        if (p.isNumber()) {
            long v = p.getAsLong();
            return v > 0xFFFFFFL || v < 0 ? (int) v : (int) v | 0xFF000000;
        }
        String s = p.getAsString().strip().toLowerCase(Locale.ROOT);
        Integer named = NAMES.get(s);
        if (named != null) return named;
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x")) s = s.substring(2);
        try {
            if (s.length() == 6) return (int) Long.parseLong(s, 16) | 0xFF000000;
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return def;
    }

    /** {@code "nether"} / {@code "the_nether"} / {@code "minecraft:the_nether"} as a full dimension id; null if blank. */
    public static String dimension(JsonObject o, String key) {
        String d = str(o, key, null, 128);
        if (d == null || d.isEmpty()) return null;
        d = d.toLowerCase(Locale.ROOT);
        return switch (d) {
            case "overworld", "world" -> "minecraft:overworld";
            case "nether", "the_nether", "world_nether" -> "minecraft:the_nether";
            case "end", "the_end", "world_the_end" -> "minecraft:the_end";
            default -> d.indexOf(':') >= 0 ? d : "minecraft:" + d;
        };
    }
}
