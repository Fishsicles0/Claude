/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** Small helpers for building the JSON bodies of Evade API messages. */
final class Json {
    private Json() {}

    static JsonObject message(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    static JsonArray strings(Collection<?> values) {
        JsonArray a = new JsonArray();
        for (Object v : values) if (v != null) a.add(v.toString());
        return a;
    }

    /** {@code 0xRRGGBB} -> {@code "#RRGGBB"} (alpha bits are dropped). */
    static String color(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Parses {@code "#RRGGBB"}, {@code "RRGGBB"} or {@code "0xRRGGBB"}; returns {@code fallback} when it isn't a colour. */
    static int parseColor(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.strip();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Converts a plain Java value (from code or from config.yml) to JSON. */
    static JsonElement value(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof JsonElement e) return e;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Character c) return new JsonPrimitive(c);
        if (v instanceof Collection<?> c) {
            JsonArray a = new JsonArray();
            for (Object o : c) a.add(value(o));
            return a;
        }
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) o.add(String.valueOf(e.getKey()), value(e.getValue()));
            return o;
        }
        return new JsonPrimitive(v.toString());
    }
}
