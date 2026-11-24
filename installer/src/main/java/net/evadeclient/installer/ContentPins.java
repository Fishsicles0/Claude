package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pins and "Undo last update" for the mods and packs the player added to an instance (Evade's own jars are never
 * pinned or rolled back: Play keeps them current).
 * <ul>
 *   <li><b>Pins</b> ({@code .evade/pins.json}): Modrinth project ids Update all (and the start-up update check) skip.
 *       Picking an older version from the version list pins that project, so the choice sticks.</li>
 *   <li><b>Undo last update</b> ({@code .evade/rollback/}): an update moves the file it replaces here instead of
 *       deleting it, and notes what replaced it. Undo puts the old files back, removes the new ones and points the
 *       records back. Only the last update (or Update all batch) is kept; the next one replaces it.</li>
 * </ul>
 */
final class ContentPins {
    private ContentPins() {}

    static final String PINS = "pins.json", ROLLBACK = "rollback", LAST = "last.json";

    // ------------------------------------------------------------------ pins

    static Path pinsFile(Path gameDir) { return GameState.dir(gameDir).resolve(PINS); }

    static Set<String> pins(Path gameDir) {
        Set<String> out = new LinkedHashSet<>();
        JsonObject o = InstallerCore.readJsonObject(pinsFile(gameDir));
        if (o.has("pinned") && o.get("pinned").isJsonArray()) for (JsonElement e : o.getAsJsonArray("pinned")) {
            if (e.isJsonPrimitive() && e.getAsString().matches("[A-Za-z0-9]{6,12}")) out.add(e.getAsString());
        }
        return out;
    }

    static boolean pinned(Path gameDir, String projectId) { return projectId != null && pins(gameDir).contains(projectId); }

    /** Pins or unpins a project; returns whether it's pinned now. */
    static boolean setPinned(Path gameDir, String projectId, boolean pin) throws IOException {
        Set<String> p = pins(gameDir);
        if (pin) p.add(projectId); else p.remove(projectId);
        JsonObject o = new JsonObject();
        o.add("pinned", ContentApi.array(p));
        Files.createDirectories(GameState.dir(gameDir));
        InstallerCore.writeAtomic(pinsFile(gameDir), InstallerCore.GSON.toJson(o));
        return pin;
    }

    /** The updates Update all applies: everything with an update except pinned projects. */
    static List<Updates.Known> unpinnedUpdates(Path gameDir, java.util.Collection<Updates.Known> known) {
        Set<String> p = pins(gameDir);
        List<Updates.Known> out = new ArrayList<>();
        for (Updates.Known k : known) {
            if (!k.hasUpdate()) continue;
            String pid = k.current() != null ? k.current().projectId() : null;
            if (pid != null && p.contains(pid)) continue;
            out.add(k);
        }
        return out;
    }

    // ------------------------------------------------------------------ rollback

    static Path rollbackDir(Path gameDir) { return GameState.dir(gameDir).resolve(ROLLBACK); }

    /** One file an update replaced: where the old one was (relative to the game folder), the new one, and the records. */
    record Item(String type, String oldRel, String newRel, String stored, String projectId, String oldVersion, String newVersion, String title) {}

    /** The update being made: old files go into its folder as they're replaced. Start with {@link #begin}. */
    static final class Batch {
        final Path gameDir, dir;
        final List<Item> items = new ArrayList<>();

        private Batch(Path gameDir, Path dir) {
            this.gameDir = gameDir;
            this.dir = dir;
        }

        /** Moves the file being replaced into the batch (instead of deleting it). Call before the new file goes in. */
        synchronized void keep(Path old, Path now, Content.Type type, String projectId, String oldVersion, String newVersion, String title) throws IOException {
            Files.createDirectories(dir);
            String stored = items.size() + "-" + old.getFileName();
            InstallerCore.move(old, dir.resolve(stored));
            items.add(new Item(type.name(), rel(old), rel(now), stored, projectId, oldVersion, newVersion, title));
            write();
        }

        private String rel(Path p) { return gameDir.relativize(p).toString().replace('\\', '/'); }

        private void write() throws IOException {
            JsonObject o = new JsonObject();
            o.addProperty("at", System.currentTimeMillis());
            JsonArray a = new JsonArray();
            for (Item i : items) a.add(InstallerCore.GSON.toJsonTree(i));
            o.add("items", a);
            InstallerCore.writeAtomic(dir.resolve(LAST), InstallerCore.GSON.toJson(o));
        }
    }

    /** Starts a new update batch: the previous one's kept files are let go (only the last update can be undone). */
    static Batch begin(Path gameDir) throws IOException {
        Path d = rollbackDir(gameDir);
        InstallerCore.deleteTree(d);
        Files.createDirectories(d);
        return new Batch(gameDir, d);
    }

    /** What "Undo last update" would put back, or an empty list. */
    static List<Item> last(Path gameDir) {
        List<Item> out = new ArrayList<>();
        JsonObject o = InstallerCore.readJsonObject(rollbackDir(gameDir).resolve(LAST));
        if (!o.has("items") || !o.get("items").isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray("items")) {
            try {
                Item i = InstallerCore.GSON.fromJson(e, Item.class);
                if (i != null && i.oldRel() != null && i.newRel() != null && i.stored() != null && Files.isRegularFile(rollbackDir(gameDir).resolve(i.stored())))
                    out.add(i);
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /**
     * Puts back what the last update replaced: each new file is removed, the old one moved back, and the records
     * point at it again. Returns how many files came back; the batch is gone afterwards.
     */
    static int undo(Path gameDir) throws IOException {
        List<Item> items = last(gameDir);
        Path d = rollbackDir(gameDir);
        int n = 0;
        for (Item i : items) {
            Path old = safe(gameDir, i.oldRel()), now = safe(gameDir, i.newRel());
            if (old == null || now == null) continue;
            if (!now.equals(old)) Files.deleteIfExists(now);
            // a switched-off mod may have been renamed since: take its .disabled copy away too
            if (!now.equals(old)) Files.deleteIfExists(now.resolveSibling(now.getFileName() + ".disabled"));
            Files.createDirectories(old.getParent());
            if (Files.exists(old)) Files.delete(old); // same file name: the new content goes
            InstallerCore.move(d.resolve(i.stored()), old);
            fixRecords(gameDir, i);
            n++;
        }
        InstallerCore.deleteTree(d);
        return n;
    }

    /** A path inside the game folder, or null for anything that would leave it. */
    private static Path safe(Path gameDir, String rel) {
        if (rel == null || rel.contains("..") || rel.startsWith("/") || rel.contains(":")) return null;
        Path p = gameDir.resolve(rel).normalize();
        return p.startsWith(gameDir.normalize()) ? p : null;
    }

    private static void fixRecords(Path gameDir, Item i) throws IOException {
        String oldName = plain(Path.of(i.oldRel()).getFileName().toString()), newName = plain(Path.of(i.newRel()).getFileName().toString());
        if (Content.Type.MOD.name().equals(i.type())) {
            JsonObject rec = InstallerCore.readJsonObject(gameDir.resolve(Mods.RECORD));
            boolean changed = false;
            for (String key : rec.keySet()) {
                if (!rec.get(key).isJsonObject()) continue;
                JsonObject o = rec.getAsJsonObject(key);
                if (newName.equalsIgnoreCase(InstallerCore.str(o, "file"))) {
                    o.addProperty("file", oldName);
                    if (i.oldVersion() != null) o.addProperty("version", i.oldVersion());
                    changed = true;
                }
            }
            if (changed) InstallerCore.writeAtomic(gameDir.resolve(Mods.RECORD), InstallerCore.GSON.toJson(rec));
        } else {
            Content.Type t;
            try { t = Content.Type.valueOf(i.type()); } catch (IllegalArgumentException e) { return; }
            Content.forget(gameDir, t, newName);
            if (i.projectId() != null) Content.remember(gameDir, t, i.projectId(), oldName, i.oldVersion() == null ? "" : i.oldVersion(), i.title() == null ? oldName : i.title());
            if (t == Content.Type.RESOURCEPACK) Updates.renameResourcePack(gameDir, newName, oldName);
        }
    }

    private static String plain(String n) { return n.replaceAll("(?i)\\.disabled$", ""); }

    /** "Sodium 0.6.13 → 0.6.12" lines for the undo confirmation. */
    static String describe(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item i : items) {
            String name = i.title() != null ? i.title() : Path.of(i.oldRel()).getFileName().toString();
            out.add(name + (i.newVersion() != null && i.oldVersion() != null ? " " + i.newVersion() + " → " + i.oldVersion() : ""));
        }
        return String.join(", ", out);
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
