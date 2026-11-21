package net.evadeclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves module state, keybinds and settings to config/evade/config.json, and to the active profile. */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("config.json");

    /** Set by EvadeClient after construction; when present, every save mirrors into the active profile. */
    public ProfileManager profiles;

    public void save() {
        saveTo(file);
        if (profiles != null) profiles.mirrorFrom(file);
    }

    public void load() {
        loadFrom(file);
    }

    /** Serialize the whole client state to {@code target}. */
    public void saveTo(Path target) {
        JsonObject root = new JsonObject();
        for (Module m : EvadeClient.modules.all()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", m.isEnabled());
            obj.addProperty("key", m.getKey());
            JsonObject settings = new JsonObject();
            for (Setting<?> s : m.getSettings()) settings.add(s.getName(), s.toJson());
            obj.add("settings", settings);
            if (m instanceof HudElementModule h) {
                obj.addProperty("x", h.x);
                obj.addProperty("y", h.y);
            }
            root.add(m.getName(), obj);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(root));
        } catch (IOException e) {
            EvadeClient.LOG.error("Failed to save config", e);
        }
    }

    /** Apply client state from {@code source} if it exists. */
    public void loadFrom(Path source) {
        if (!Files.exists(source)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
            for (Module m : EvadeClient.modules.all()) {
                if (!root.has(m.getName())) continue;
                JsonObject obj = root.getAsJsonObject(m.getName());
                if (obj.has("key")) m.setKey(obj.get("key").getAsInt());
                if (obj.has("settings")) {
                    JsonObject settings = obj.getAsJsonObject("settings");
                    for (Setting<?> s : m.getSettings()) {
                        JsonElement v = settings.get(s.getName());
                        if (v != null) {
                            try { s.fromJson(v); } catch (Exception ignored) {}
                        }
                    }
                }
                if (m instanceof HudElementModule h && obj.has("x") && obj.has("y")) {
                    h.x = obj.get("x").getAsInt();
                    h.y = obj.get("y").getAsInt();
                }
                if (obj.has("enabled")) m.setEnabled(obj.get("enabled").getAsBoolean());
            }
        } catch (Exception e) {
            EvadeClient.LOG.error("Failed to load config", e);
        }
    }

    public Path file() { return file; }
}
