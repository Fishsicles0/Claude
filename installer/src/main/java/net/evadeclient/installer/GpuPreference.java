package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Windows: the "Faster graphics card" switch in Settings &gt; Launcher. When it's on (the default), installing sets
 * Windows' per-app graphics preference to "High performance" for the Java the Minecraft Launcher runs the game with
 * (HKCU\Software\Microsoft\DirectX\UserGpuPreferences, the same choice as Settings &gt; System &gt; Display &gt;
 * Graphics). On a laptop with two GPUs this moves the game off the integrated one; with one GPU it changes nothing.
 * Only the current user's registry is touched, a choice the player already made for a file is kept, and every value
 * Evade adds is remembered so that turning the switch off removes exactly those (see {@link #undo}). Runs {@code reg}
 * with plain, visible arguments. Never fails the install.
 */
final class GpuPreference {
    private GpuPreference() {}

    static final String KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";

    /** Where the values Evade added are listed. */
    static Path record() { return Launcher.dataDir().resolve("gpu-preferences.json"); }

    /** Sets "High performance" for every Minecraft Java runtime found that has no preference yet. */
    static void apply(Path minecraftDir) {
        if (!InstallerCore.WINDOWS) return;
        try {
            List<Path> roots = new ArrayList<>(List.of(minecraftDir.resolve("runtime")));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) roots.add(Paths.get(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"));
            List<String> added = recorded();
            for (Path java : javas(roots)) {
                String path = java.toAbsolutePath().toString();
                if (reg("query", KEY, "/v", path) == 0) continue; // already set, by the player or a previous install
                if (reg("add", KEY, "/v", path, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f") == 0 && !added.contains(path)) added.add(path);
            }
            save(added);
        } catch (Exception e) {
            // A preference, not a requirement: the game runs either way.
        }
    }

    /** Removes the values Evade added (the switch was turned off). Values the player set themselves stay. */
    static void undo() {
        if (!InstallerCore.WINDOWS) return;
        List<String> left = new ArrayList<>();
        for (String path : recorded()) {
            try {
                if (reg("delete", KEY, "/v", path, "/f") != 0 && reg("query", KEY, "/v", path) == 0) left.add(path);
            } catch (Exception e) {
                left.add(path);
            }
        }
        save(left);
    }

    /** java.exe / javaw.exe in a bin folder under any of {@code roots}. */
    static List<Path> javas(List<Path> roots) throws Exception {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root, 7)) {
                walk.filter(f -> {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    return (n.equals("javaw.exe") || n.equals("java.exe")) && f.getParent() != null
                            && f.getParent().getFileName().toString().equalsIgnoreCase("bin");
                }).forEach(out::add);
            }
        }
        return out;
    }

    private static List<String> recorded() {
        List<String> out = new ArrayList<>();
        JsonObject o = InstallerCore.readJsonObject(record());
        if (o.has("added") && o.get("added").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("added")) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    private static void save(List<String> added) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        added.forEach(a::add);
        o.add("added", a);
        try {
            Files.createDirectories(record().getParent());
            InstallerCore.writeAtomic(record(), InstallerCore.GSON.toJson(o));
        } catch (Exception ignored) { }
    }

    private static int reg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("reg"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }
}
