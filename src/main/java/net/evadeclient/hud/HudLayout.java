package net.evadeclient.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.hud.VanillaTextHud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A HUD layout on its own: where each widget sits, its scale, and whether it is shown. Used for the HUD editor's
 * undo history ({@link #snapshot}/{@link #restore}), the built-in layouts ({@link #PRESETS}) and HUD-only share codes
 * ({@link #shareCode}/{@link #importCode}), which never touch any mod's other settings.
 *
 * <p>Only HUD widgets are shown or hidden by a layout or code. Mods that merely have a movable part elsewhere (the
 * Scoreboard, Toggle Sprint's label) are moved and scaled but never switched on or off, and the Titles, Action Bar
 * and Subtitles placements are left as you set them.
 */
public final class HudLayout {
    private HudLayout() {}

    public static final String CODE_PREFIX = "EVHUD1:";
    /** Layout codes are small; anything larger is not one. */
    private static final int MAX_CODE_CHARS = 16_000, MAX_JSON_BYTES = 64_000, MAX_COORD = 10_000;

    /** The built-in layouts, in the order the editor lists them. */
    public static final List<String> PRESETS = List.of("Minimal", "PvP", "Survival", "Streamer");

    /** A widget's place in a preset: null x/y keeps its default spot. */
    private record Place(String name, Integer x, Integer y, double scale) {
        static Place at(String name) { return new Place(name, null, null, 1.0); }
        static Place at(String name, int x, int y) { return new Place(name, x, y, 1.0); }
        static Place scaled(String name, double s) { return new Place(name, null, null, s); }
    }

    private static List<Place> preset(String name) {
        return switch (name) {
            case "Minimal" -> List.of(Place.at("Watermark"), Place.at("FPS", 4, 4), Place.at("Coordinates", 4, 20),
                    Place.at("Potion Effects"));
            case "PvP" -> List.of(Place.at("Watermark"), Place.at("FPS", 4, 4), Place.at("CPS", 4, 20), Place.at("Ping", 4, 36),
                    Place.at("Combo Counter", 4, 52), Place.at("Reach Display", 4, 68), Place.at("Keystrokes"),
                    Place.at("ArmorHUD"), Place.at("Potion Effects"), Place.at("Cooldowns"));
            case "Survival" -> List.of(Place.at("Watermark"), Place.at("FPS", 4, 4), Place.at("Clock", 4, 20),
                    Place.at("Day Counter", 4, 36), Place.at("Coordinates", 4, 52), Place.at("Direction HUD"),
                    Place.at("Minimap"), Place.at("ArmorHUD"), Place.at("Potion Effects"));
            // No coordinates or server address on stream.
            case "Streamer" -> List.of(Place.scaled("Watermark", 1.2), Place.at("FPS", 4, 4), Place.at("CPS", 4, 20),
                    Place.at("Clock", 4, 36), Place.scaled("Keystrokes", 1.25), Place.at("ArmorHUD"), Place.at("Potion Effects"));
            default -> null;
        };
    }

    /** Whether a layout may show or hide {@code el} (a HUD widget, not a placement of vanilla text). */
    public static boolean ownsVisibility(HudElementModule el) {
        return el.getCategory() == Category.HUD && !(el instanceof VanillaTextHud);
    }

    // ---- snapshots (undo) -----------------------------------------------------------------------------

    /** Every widget's position, scale, visibility, lock and stack, as compact JSON. */
    public static String snapshot() {
        JsonObject root = new JsonObject();
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            JsonObject o = new JsonObject();
            o.addProperty("on", el.isEnabled());
            o.addProperty("x", el.x);
            o.addProperty("y", el.y);
            o.addProperty("s", el.scale.get());
            o.addProperty("l", el.locked.get());
            if (HudStacks.inStack(el)) {
                o.addProperty("g", el.stack.get());
                o.addProperty("o", el.stackOrder.get());
            }
            root.add(el.getName(), o);
        }
        return root.toString();
    }

    /** Put every widget back as {@link #snapshot} saw it (positions, scale, visibility and lock). */
    public static void restore(String snapshot) {
        JsonObject root = JsonParser.parseString(snapshot).getAsJsonObject();
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            JsonElement e = root.get(el.getName());
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            el.x = o.get("x").getAsInt();
            el.y = o.get("y").getAsInt();
            el.scale.set(o.get("s").getAsDouble());
            el.locked.set(o.get("l").getAsBoolean());
            el.stack.set(o.has("g") ? o.get("g").getAsString() : "");
            el.stackOrder.set(o.has("o") ? o.get("o").getAsDouble() : 0.0);
            boolean on = o.get("on").getAsBoolean();
            if (el.isEnabled() != on) el.setEnabled(on);
        }
    }

    // ---- presets --------------------------------------------------------------------------------------

    /** Apply a built-in layout: its widgets shown at its spots, every other HUD widget hidden. False if unknown. */
    public static boolean applyPreset(String name) {
        List<Place> places = preset(name);
        if (places == null) return false;
        // A built-in layout places each widget itself: stacks would pull them back together.
        HudStacks.ungroup(EvadeClient.modules.hudElements());
        Map<String, Place> byName = new LinkedHashMap<>();
        for (Place p : places) byName.put(p.name(), p);
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            Place p = byName.get(el.getName());
            if (p == null) {
                if (ownsVisibility(el) && el.isEnabled()) el.setEnabled(false);
                continue;
            }
            if (p.x() == null) el.resetPosition();
            else { el.x = p.x(); el.y = p.y(); }
            el.scale.set(p.scale());
            el.locked.set(false);
            if (!el.isEnabled()) el.setEnabled(true);
        }
        return true;
    }

    // ---- share codes ----------------------------------------------------------------------------------

    /** A copy-paste code with every widget's position, scale and (for HUD widgets) visibility. */
    public static String shareCode() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonObject els = new JsonObject();
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            JsonObject o = new JsonObject();
            if (ownsVisibility(el)) o.addProperty("on", el.isEnabled());
            o.addProperty("x", el.x);
            o.addProperty("y", el.y);
            o.addProperty("s", el.scale.get());
            els.add(el.getName(), o);
        }
        root.add("e", els);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                gz.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What importing a code did: how many widgets it placed, or why it couldn't (then nothing changed). */
    public record ImportResult(int applied, String error) {
        public boolean ok() { return error == null; }
    }

    /**
     * Apply a HUD code (it may have words or line breaks around it). Unknown widget names are skipped, so codes from
     * other Evade versions still apply to the widgets both have. Changes nothing when the code can't be read.
     */
    public static ImportResult importCode(String text) {
        if (text == null || text.isBlank()) return new ImportResult(0, "Nothing to paste: copy a HUD code first");
        int at = text.indexOf(CODE_PREFIX);
        if (at < 0) return new ImportResult(0, "Not a HUD code");
        // Chat apps break long codes over lines and people paste words around them: the code is the run of
        // base64 pieces after the prefix. Try all of them joined, then fewer, until one decodes.
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int total = 0;
        for (int i = at + CODE_PREFIX.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) { pieces.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) break;
            cur.append(c);
            if (++total > MAX_CODE_CHARS) return new ImportResult(0, "That code is too large to be a HUD layout");
        }
        if (!cur.isEmpty()) pieces.add(cur.toString());
        JsonObject root = null;
        boolean tooLarge = false;
        for (int n = pieces.size(); n >= 1 && root == null; n--) {
            try {
                byte[] raw = Base64.getUrlDecoder().decode(String.join("", pieces.subList(0, n)));
                byte[] json;
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    json = gz.readNBytes(MAX_JSON_BYTES + 1);
                }
                if (json.length > MAX_JSON_BYTES) { tooLarge = true; break; }
                root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                // not this many pieces
            }
        }
        if (tooLarge) return new ImportResult(0, "That code is too large to be a HUD layout");
        if (root == null) return new ImportResult(0, "The code looks damaged or cut off; copy it again");
        if (root.has("v") && root.get("v").isJsonPrimitive() && root.get("v").getAsInt() > 1)
            return new ImportResult(0, "That code is from a newer Evade; update to use it");
        if (!root.has("e") || !root.get("e").isJsonObject()) return new ImportResult(0, "The code looks damaged; copy it again");

        // Read everything first, so a bad entry changes nothing.
        record Change(HudElementModule el, Boolean on, int x, int y, double s) {}
        List<Change> changes = new ArrayList<>();
        JsonObject els = root.getAsJsonObject("e");
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            JsonElement e = els.get(el.getName());
            if (e == null || !e.isJsonObject()) continue;
            try {
                JsonObject o = e.getAsJsonObject();
                int x = coord(o.get("x").getAsInt()), y = coord(o.get("y").getAsInt());
                double s = o.has("s") ? o.get("s").getAsDouble() : 1.0;
                if (!Double.isFinite(s)) s = 1.0;
                Boolean on = ownsVisibility(el) && o.has("on") ? o.get("on").getAsBoolean() : null;
                changes.add(new Change(el, on, x, y, s));
            } catch (RuntimeException ex) {
                return new ImportResult(0, "The code looks damaged; copy it again");
            }
        }
        if (changes.isEmpty()) return new ImportResult(0, "That code has no widgets this Evade knows");
        for (Change c : changes) {
            c.el().x = c.x();
            c.el().y = c.y();
            c.el().scale.set(c.s());
            if (c.on() != null && c.el().isEnabled() != c.on()) c.el().setEnabled(c.on());
        }
        return new ImportResult(changes.size(), null);
    }

    /** A stored coordinate kept in range ({@link HudElementModule#CENTER} passes through). */
    private static int coord(int v) {
        if (v == HudElementModule.CENTER) return v;
        return Math.max(-MAX_COORD, Math.min(MAX_COORD, v));
    }
}
