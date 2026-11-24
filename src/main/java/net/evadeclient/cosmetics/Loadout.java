package net.evadeclient.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** What someone wears: one item (and its colour, for colourable items) per slot. Immutable. */
public final class Loadout {
    /** One worn item. {@code color} is RGB (no alpha), or -1 for the item's own colours. */
    public record Worn(String id, int color) {}

    public static final Loadout EMPTY = new Loadout(new EnumMap<>(Slot.class));

    private final Map<Slot, Worn> slots;

    private Loadout(EnumMap<Slot, Worn> slots) { this.slots = Collections.unmodifiableMap(slots); }

    public Worn get(Slot s) { return slots.get(s); }
    public boolean isEmpty() { return slots.isEmpty(); }
    public Map<Slot, Worn> all() { return slots; }

    /** A copy with {@code slot} set to {@code worn} (null takes it off). */
    public Loadout with(Slot slot, Worn worn) {
        EnumMap<Slot, Worn> m = slots.isEmpty() ? new EnumMap<>(Slot.class) : new EnumMap<>(slots);
        if (worn == null) m.remove(slot); else m.put(slot, worn);
        return m.isEmpty() ? EMPTY : new Loadout(m);
    }

    /** The wire format: {@code {"CAPE": {"id": "...", "color": "#RRGGBB"}}} (color only when set). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (var e : slots.entrySet()) {
            JsonObject w = new JsonObject();
            w.addProperty("id", e.getValue().id());
            if (e.getValue().color() >= 0) w.addProperty("color", String.format("#%06X", e.getValue().color()));
            o.add(e.getKey().name(), w);
        }
        return o;
    }

    /** Reads the wire format; unknown slots and malformed entries are skipped. */
    public static Loadout fromJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) return EMPTY;
        EnumMap<Slot, Worn> m = new EnumMap<>(Slot.class);
        for (var e : json.getAsJsonObject().entrySet()) {
            Slot s = Slot.parse(e.getKey());
            if (s == null) continue;
            try {
                JsonElement v = e.getValue();
                String id;
                int color = -1;
                if (v.isJsonPrimitive()) {
                    id = v.getAsString();
                } else if (v.isJsonObject() && v.getAsJsonObject().has("id") && !v.getAsJsonObject().get("id").isJsonNull()) {
                    JsonObject w = v.getAsJsonObject();
                    id = w.get("id").getAsString();
                    if (w.has("color") && !w.get("color").isJsonNull()) color = parseColor(w.get("color"));
                } else continue;
                if (id == null || id.isBlank() || id.length() > 64) continue;
                m.put(s, new Worn(id, color));
            } catch (RuntimeException ignored) {
                // a bad entry: leave that slot empty
            }
        }
        return m.isEmpty() ? EMPTY : new Loadout(m);
    }

    /** "#RRGGBB", "RRGGBB" or a number, as RGB; -1 when it isn't a colour. */
    public static int parseColor(JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsInt() & 0xFFFFFF;
        String s = e.getAsString().trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return -1;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    @Override public boolean equals(Object o) { return o instanceof Loadout l && l.slots.equals(slots); }
    @Override public int hashCode() { return slots.hashCode(); }
    @Override public String toString() { return toJson().toString(); }
}
