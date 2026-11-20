package net.evadeclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves module state, keybinds and settings to config/evade/config.json. */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("config.json");

    public void save() {
        JsonObject root = new JsonObject();
        for (Module m : EvadeClient.modules.all()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", m.isEnabled());
            obj.addProperty("key", m.getKey());
            JsonObject settings = new JsonObject();
            for (Setting<?> s : m.getSettings()) settings.add(s.getName(), s.toJson());
            obj.add("settings", settings);
            root.add(m.getName(), obj);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            EvadeClient.LOG.error("Failed to save config", e);
        }
    }

    public void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
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
                if (obj.has("enabled")) m.setEnabled(obj.get("enabled").getAsBoolean());
            }
        } catch (Exception e) {
            EvadeClient.LOG.error("Failed to load config", e);
        }
    }
}
