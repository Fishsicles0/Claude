package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's look and list preferences, kept in {@code launcher-ui.json} beside its settings: theme, accent, text
 * size, reduced motion, how the Instances list is sorted, grouped and pinned, and which launcher version's "What's new"
 * was seen. A missing or broken file just gives the defaults (the dark Evade look).
 */
final class UiPrefs {
    static final String FILE = "launcher-ui.json";
    /** Sort orders for the Instances list. */
    static final String[] SORTS = {"manual", "name", "played", "time"};
    static final String[] SORT_NAMES = {"Created", "Name", "Last played", "Play time"};
    /** Text sizes offered, in percent. */
    static final int[] TEXT_SIZES = {100, 115, 130};

    boolean light;
    /** Index into {@link LauncherTheme#ACCENTS} (0 = Evade red). */
    int accent;
    int textSize = 100;
    boolean reduceMotion;
    String sort = "manual";
    final Set<String> pinned = new LinkedHashSet<>();
    /** Instance id -> group name (no entry: not in a group). */
    final Map<String, String> groups = new LinkedHashMap<>();
    final Set<String> collapsed = new LinkedHashSet<>();
    /** The launcher version whose "What's new" sheet was shown (null: none yet). */
    String seenVersion;

    static Path file() { return Launcher.dataDir().resolve(FILE); }

    static UiPrefs load() { return load(file()); }

    static UiPrefs load(Path f) {
        UiPrefs p = new UiPrefs();
        JsonObject o = InstallerCore.readJsonObject(f);
        try {
            p.light = o.has("light") && o.get("light").getAsBoolean();
            p.accent = o.has("accent") ? Math.floorMod(o.get("accent").getAsInt(), LauncherTheme.ACCENTS.length) : 0;
            int ts = o.has("textSize") ? o.get("textSize").getAsInt() : 100;
            p.textSize = ts == 115 || ts == 130 ? ts : 100;
            p.reduceMotion = o.has("reduceMotion") && o.get("reduceMotion").getAsBoolean();
            String s = InstallerCore.str(o, "sort");
            for (String k : SORTS) if (k.equals(s)) p.sort = k;
            p.seenVersion = InstallerCore.str(o, "seenVersion");
            if (o.has("pinned") && o.get("pinned").isJsonArray())
                for (JsonElement e : o.getAsJsonArray("pinned")) if (e.isJsonPrimitive()) p.pinned.add(e.getAsString());
            if (o.has("collapsed") && o.get("collapsed").isJsonArray())
                for (JsonElement e : o.getAsJsonArray("collapsed")) if (e.isJsonPrimitive()) p.collapsed.add(e.getAsString());
            if (o.has("groups") && o.get("groups").isJsonObject())
                for (var e : o.getAsJsonObject("groups").entrySet()) {
                    String g = e.getValue().isJsonPrimitive() ? cleanGroup(e.getValue().getAsString()) : null;
                    if (g != null) p.groups.put(e.getKey(), g);
                }
        } catch (RuntimeException ignored) { }
        return p;
    }

    void save() { save(file()); }

    void save(Path f) {
        JsonObject o = new JsonObject();
        o.addProperty("light", light);
        o.addProperty("accent", accent);
        o.addProperty("textSize", textSize);
        o.addProperty("reduceMotion", reduceMotion);
        o.addProperty("sort", sort);
        if (seenVersion != null) o.addProperty("seenVersion", seenVersion);
        JsonArray pin = new JsonArray();
        pinned.forEach(pin::add);
        o.add("pinned", pin);
        JsonArray col = new JsonArray();
        collapsed.forEach(col::add);
        o.add("collapsed", col);
        JsonObject gr = new JsonObject();
        groups.forEach(gr::addProperty);
        o.add("groups", gr);
        try {
            Files.createDirectories(f.getParent());
            InstallerCore.writeAtomic(f, InstallerCore.GSON.toJson(o));
        } catch (IOException e) {
            System.err.println("Couldn't save launcher look settings: " + e.getMessage());
        }
    }

    /** A group name as typed, trimmed to 24 characters; null when blank. */
    static String cleanGroup(String s) {
        if (s == null) return null;
        s = s.strip().replaceAll("\\s+", " ");
        if (s.length() > 24) s = s.substring(0, 24).strip();
        return s.isEmpty() ? null : s;
    }
}
