package net.evadeclient.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Mod Browser installed, by Modrinth project id, and file changes still waiting for the game to close:
 * config/evade/mod-browser.json. Thread-safe; every change is written straight away.
 */
public final class ModIndex {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A mod the browser put in the mods folder. {@code dependency}: added because another mod needed it. */
    public record Entry(String projectId, String file, String versionId, String versionNumber, String title,
                       String fabricId, String sha512, boolean dependency) {}

    /**
     * A file change that couldn't happen while the game ran (the jar was in use): delete {@code delete} (a file name in
     * the mods folder, or null), then, once it is gone, rename {@code from} to {@code to} (null when nothing moves).
     */
    public record Pending(String delete, String from, String to) {}

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();

    public ModIndex(Path file) {
        this.file = file;
        load();
    }

    public synchronized Map<String, Entry> entries() { return new LinkedHashMap<>(entries); }

    public synchronized Entry get(String projectId) { return entries.get(projectId); }

    public synchronized Entry byFile(String fileName) {
        for (Entry e : entries.values()) if (fileName.equals(e.file())) return e;
        return null;
    }

    public synchronized void put(Entry e) {
        entries.put(e.projectId(), e);
        save();
    }

    public synchronized void removeFile(String fileName) {
        if (entries.values().removeIf(e -> fileName.equals(e.file()))) save();
    }

    public synchronized List<Pending> pending() { return new ArrayList<>(pending); }

    public synchronized void addPending(Pending p) {
        pending.add(p);
        save();
    }

    public synchronized void setPending(List<Pending> list) {
        pending.clear();
        pending.addAll(list);
        save();
    }

    public synchronized boolean pendingDelete(String fileName) {
        for (Pending p : pending) if (fileName.equals(p.delete())) return true;
        return false;
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonObject o = root.getAsJsonObject();
            if (o.has("installed") && o.get("installed").isJsonObject()) {
                for (var en : o.getAsJsonObject("installed").entrySet()) {
                    if (!en.getValue().isJsonObject()) continue;
                    JsonObject e = en.getValue().getAsJsonObject();
                    String f = ModrinthApi.str(e, "file");
                    if (f == null || !ModManager.safeFileName(f)) continue;
                    entries.put(en.getKey(), new Entry(en.getKey(), f, ModrinthApi.str(e, "versionId"),
                            ModrinthApi.str(e, "version"), ModrinthApi.str(e, "title"), ModrinthApi.str(e, "fabricId"),
                            ModrinthApi.str(e, "sha512"), e.has("dependency") && e.get("dependency").getAsBoolean()));
                }
            }
            if (o.has("pending") && o.get("pending").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("pending")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject p = el.getAsJsonObject();
                    String d = ModrinthApi.str(p, "delete"), from = ModrinthApi.str(p, "from"), to = ModrinthApi.str(p, "to");
                    // Only plain file names in the mods folder: a hand-edited index can't reach anywhere else.
                    if (d != null && !ModManager.safeFileName(d)) continue;
                    if ((from == null) != (to == null)) continue;
                    if (from != null && (!ModManager.safeFileName(from) || !ModManager.safeFileName(to))) continue;
                    pending.add(new Pending(d, from, to));
                }
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Mod Browser: couldn't read {}: {}", file, e.toString());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("_note", "Mods added by Evade's Mod Browser. Safe to delete; it only stops update tracking.");
        JsonObject inst = new JsonObject();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("file", e.file());
            o.addProperty("versionId", e.versionId());
            o.addProperty("version", e.versionNumber());
            o.addProperty("title", e.title());
            o.addProperty("fabricId", e.fabricId());
            o.addProperty("sha512", e.sha512());
            o.addProperty("dependency", e.dependency());
            inst.add(e.projectId(), o);
        }
        root.add("installed", inst);
        JsonArray pend = new JsonArray();
        for (Pending p : pending) {
            JsonObject o = new JsonObject();
            if (p.delete() != null) o.addProperty("delete", p.delete());
            if (p.from() != null) { o.addProperty("from", p.from()); o.addProperty("to", p.to()); }
            pend.add(o);
        }
        root.add("pending", pend);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EvadeClient.LOG.warn("Mod Browser: couldn't save {}: {}", file, e.toString());
        }
    }
}
