package net.evadeclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The colours you picked most recently in the colour picker (newest first), shared by every colour setting and kept
 * across restarts in {@code config/evade/recent-colors.json}. Not part of profiles: it is a convenience, not a setting.
 */
public final class ColorHistory {
    /** How many recent colours are kept and shown. */
    public static final int MAX = 10;

    private static List<Integer> recent;
    /** Test hook: where the list is saved (null = the config folder). */
    public static Path FILE_OVERRIDE;

    private ColorHistory() {}

    private static Path file() {
        return FILE_OVERRIDE != null ? FILE_OVERRIDE
                : FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("recent-colors.json");
    }

    /** The recent colours, newest first (ARGB). */
    public static synchronized List<Integer> get() {
        if (recent == null) load();
        return List.copyOf(recent);
    }

    /** Remember {@code argb} as the newest recent colour (moving it up if it is already there) and save. */
    public static synchronized void add(int argb) {
        if (recent == null) load();
        recent.remove(Integer.valueOf(argb));
        recent.addFirst(argb);
        while (recent.size() > MAX) recent.removeLast();
        save();
    }

    /** Forget every recent colour (tests). */
    public static synchronized void clear() {
        recent = new ArrayList<>();
        save();
    }

    /** Drop the in-memory copy so the next {@link #get} reads the file again (tests). */
    public static synchronized void reload() { recent = null; }

    private static void load() {
        recent = new ArrayList<>();
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            JsonElement json = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!json.isJsonArray()) return;
            for (JsonElement e : json.getAsJsonArray()) {
                if (recent.size() >= MAX) break;
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                    int c = (int) e.getAsLong();
                    if (!recent.contains(c)) recent.add(c);
                }
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't read recent colours", e);
        }
    }

    private static void save() {
        JsonArray arr = new JsonArray();
        for (int c : recent) arr.add(c);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't save recent colours", e);
        }
    }
}
