package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Import from another launcher: finds Minecraft 1.21.x instances in the folders other launchers commonly use (read
 * only: nothing there is ever changed), and makes an Evade instance from one. What comes over: game settings and
 * keybinds (options.txt), the server list, resource and shader packs, worlds (optional), and the mods, re-fetched from
 * Modrinth by their file hash as their Fabric build for {@value InstallerCore#MC_VERSION} (so a mod made for another
 * version or loader comes over as the right one, and Evade's managed mods switch on the matching option instead).
 * Mods Modrinth doesn't know are listed, not copied. What the player sees never names another launcher.
 */
final class LauncherImport {
    private LauncherImport() {}

    /** An instance found in another launcher's folder: its name, game folder and what's in it. */
    record Found(String name, Path gameDir, String minecraft, int mods, int worlds, int packs) {
        String summary() {
            List<String> p = new ArrayList<>();
            p.add(minecraft == null ? "Minecraft 1.21" : "Minecraft " + minecraft);
            p.add(mods + (mods == 1 ? " mod" : " mods"));
            if (worlds > 0) p.add(worlds + (worlds == 1 ? " world" : " worlds"));
            if (packs > 0) p.add(packs + (packs == 1 ? " pack" : " packs"));
            return String.join("  ·  ", p);
        }
    }

    // ------------------------------------------------------------------ where to look

    /**
     * The folders holding other launchers' instances on this OS (one instance per sub-folder). {@code
     * -Devade.importRoots=dir1;dir2} replaces the list (the self-test points it at fake launcher folders).
     */
    static List<Path> roots() {
        String forced = System.getProperty("evade.importRoots");
        List<Path> out = new ArrayList<>();
        if (forced != null) {
            for (String s : forced.split(";")) if (!s.isBlank()) out.add(Paths.get(s.strip()));
            return out;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), home = System.getProperty("user.home");
        if (os.contains("win")) {
            String app = System.getenv("APPDATA");
            if (app != null) for (String s : new String[]{"PrismLauncher/instances", "PolyMC/instances", "ModrinthApp/profiles",
                    "com.modrinth.theseus/profiles", "ATLauncher/instances", "gdlauncher_next/instances", "gdlauncher_carbon/data/instances"})
                out.add(Paths.get(app, s.split("/")));
            out.add(Paths.get(home, "curseforge", "minecraft", "Instances"));
            out.add(Paths.get(home, "MultiMC", "instances"));
        } else if (os.contains("mac")) {
            Path as = Paths.get(home, "Library", "Application Support");
            for (String s : new String[]{"PrismLauncher/instances", "PolyMC/instances", "ModrinthApp/profiles", "com.modrinth.theseus/profiles",
                    "ATLauncher/instances", "gdlauncher_next/instances", "gdlauncher_carbon/data/instances"}) out.add(as.resolve(s));
            out.add(Paths.get(home, "Documents", "curseforge", "minecraft", "Instances"));
        } else {
            Path share = Paths.get(home, ".local", "share");
            for (String s : new String[]{"PrismLauncher/instances", "PolyMC/instances", "multimc/instances", "ModrinthApp/profiles",
                    "com.modrinth.theseus/profiles", "ATLauncher/instances"}) out.add(share.resolve(s));
            out.add(Paths.get(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher", "instances"));
            out.add(Paths.get(home, ".config", "gdlauncher_next", "instances"));
        }
        return out;
    }

    /** Every 1.21.x instance under the roots (unreadable or other-version ones are skipped), names made unique. */
    static List<Found> detect(List<Path> roots) {
        List<Found> out = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path inst : ds) {
                    if (!Files.isDirectory(inst) || inst.getFileName().toString().startsWith(".") || inst.getFileName().toString().startsWith("_")) continue;
                    try {
                        Found f = read(inst);
                        if (f != null && seen.add(f.gameDir().toAbsolutePath().normalize())) out.add(f);
                    } catch (RuntimeException ignored) { /* a broken folder: skip it */ }
                }
            } catch (IOException ignored) { }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** One instance folder, whatever launcher made it (its game folder may be inside), or null when it isn't 1.21.x. */
    static Found read(Path inst) {
        Path game = inst;
        String name = inst.getFileName().toString(), mc = null;
        // folder-and-config layouts
        for (String sub : new String[]{".minecraft", "minecraft"}) {
            if (Files.isDirectory(inst.resolve(sub))) { game = inst.resolve(sub); break; }
        }
        if (Files.isDirectory(inst.resolve("instance")) && Files.isRegularFile(inst.resolve("instance.json"))) game = inst.resolve("instance");
        Properties cfg = props(inst.resolve("instance.cfg"));
        if (cfg.getProperty("name") != null && !cfg.getProperty("name").isBlank()) name = cfg.getProperty("name").strip();
        JsonObject pack = InstallerCore.readJsonObject(inst.resolve("mmc-pack.json"));
        if (pack.has("components") && pack.get("components").isJsonArray()) {
            for (JsonElement e : pack.getAsJsonArray("components")) {
                if (e.isJsonObject() && "net.minecraft".equals(InstallerCore.str(e.getAsJsonObject(), "uid"))) mc = InstallerCore.str(e.getAsJsonObject(), "version");
            }
        }
        JsonObject j = InstallerCore.readJsonObject(inst.resolve("minecraftinstance.json"));
        if (InstallerCore.str(j, "gameVersion") != null) mc = InstallerCore.str(j, "gameVersion");
        if (InstallerCore.str(j, "name") != null) name = InstallerCore.str(j, "name");
        j = InstallerCore.readJsonObject(inst.resolve("profile.json"));
        if (j.has("metadata") && j.get("metadata").isJsonObject()) {
            JsonObject m = j.getAsJsonObject("metadata");
            if (InstallerCore.str(m, "game_version") != null) mc = InstallerCore.str(m, "game_version");
            if (InstallerCore.str(m, "name") != null) name = InstallerCore.str(m, "name");
        }
        if (InstallerCore.str(j, "game_version") != null) mc = InstallerCore.str(j, "game_version");
        j = InstallerCore.readJsonObject(inst.resolve("instance.json"));
        if (j.has("launcher") && j.get("launcher").isJsonObject() && InstallerCore.str(j.getAsJsonObject("launcher"), "name") != null)
            name = InstallerCore.str(j.getAsJsonObject("launcher"), "name");
        if (mc == null && InstallerCore.str(j, "id") != null && InstallerCore.str(j, "id").matches("\\d+\\.\\d+(\\.\\d+)?")) mc = InstallerCore.str(j, "id");
        if (InstallerCore.str(j, "name") != null && !j.has("launcher")) name = InstallerCore.str(j, "name");
        if (j.has("game_configuration") && j.get("game_configuration").isJsonObject()) {
            JsonObject gc = j.getAsJsonObject("game_configuration");
            if (gc.has("version") && gc.get("version").isJsonObject() && InstallerCore.str(gc.getAsJsonObject("version"), "release") != null)
                mc = InstallerCore.str(gc.getAsJsonObject("version"), "release");
        }
        JsonObject conf = InstallerCore.readJsonObject(inst.resolve("config.json"));
        if (conf.has("loader") && conf.get("loader").isJsonObject() && InstallerCore.str(conf.getAsJsonObject("loader"), "mcVersion") != null)
            mc = InstallerCore.str(conf.getAsJsonObject("loader"), "mcVersion");
        // No launcher file said: the game's own options.txt knows which version last wrote it.
        if (mc == null) mc = fromDataVersion(game.resolve("options.txt"));
        if (!Files.isRegularFile(game.resolve("options.txt")) && !Files.isDirectory(game.resolve("mods")) && !Files.isDirectory(game.resolve("saves"))) return null;
        if (mc == null || !is121(mc)) return null;
        return new Found(name, game, mc, count(game.resolve("mods"), ".jar"), countDirs(game.resolve("saves")),
                count(game.resolve("resourcepacks"), ".zip") + count(game.resolve("shaderpacks"), ".zip"));
    }

    /** "1.21", "1.21.4": the versions this imports from (Minecraft keeps their settings format). */
    static boolean is121(String mc) { return mc.equals("1.21") || mc.startsWith("1.21."); }

    /**
     * The Minecraft version family from options.txt's data version: 3953 (1.21) up to before the next major release
     * counts as "1.21". Null when there's no options.txt or it's another version.
     */
    static String fromDataVersion(Path options) {
        if (!Files.isRegularFile(options)) return null;
        try (var lines = Files.lines(options)) {
            for (String l : (Iterable<String>) lines::iterator) {
                if (!l.startsWith("version:")) continue;
                int dv = Integer.parseInt(l.substring(8).strip());
                return dv >= 3953 && dv < 4700 ? "1.21" : null;
            }
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    private static Properties props(Path f) {
        Properties p = new Properties();
        if (!Files.isRegularFile(f)) return p;
        try (var r = Files.newBufferedReader(f)) { p.load(r); } catch (IOException | RuntimeException ignored) { }
        return p;
    }

    private static int count(Path dir, String ext) {
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext) && Files.isRegularFile(p)) n++;
        } catch (IOException ignored) { }
        return n;
    }

    private static int countDirs(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (Files.isRegularFile(p.resolve("level.dat"))) n++;
        } catch (IOException ignored) { }
        return n;
    }

    // ------------------------------------------------------------------ importing

    /** What an import made, and what didn't come over (each a line for the player). */
    record Result(Launcher.Instance instance, int mods, List<String> notes) {}

    /** Settings files copied as they are (keybinds live in options.txt). */
    static final String[] FILES = {"options.txt", "servers.dat", "optionsshaders.txt", "hotbar.nbt"};

    /**
     * Makes a new Evade instance from {@code f}. Nothing in the other launcher's folder is changed.
     *
     * @param worlds also copy the singleplayer worlds
     */
    static Result importInstance(InstallerCore core, Launcher.Settings s, List<Launcher.Instance> all, Found f, boolean worlds,
                                 InstallerCore.Progress p) throws IOException {
        List<String> notes = new ArrayList<>();
        Launcher.Instance inst = Launcher.create(s, all, f.name(), null, s.defaultMemoryGb, true, false, true);
        try {
            p.update(0.03, "Copying settings and keybinds");
            for (String n : FILES) {
                Path a = f.gameDir().resolve(n);
                if (Files.isRegularFile(a)) Files.copy(a, inst.dir.resolve(n), StandardCopyOption.REPLACE_EXISTING);
            }
            p.update(0.08, "Copying resource and shader packs");
            copyDir(f.gameDir().resolve("resourcepacks"), inst.dir.resolve("resourcepacks"));
            copyDir(f.gameDir().resolve("shaderpacks"), inst.dir.resolve("shaderpacks"));
            if (worlds && Files.isDirectory(f.gameDir().resolve("saves"))) {
                p.update(0.12, "Copying worlds");
                copyDir(f.gameDir().resolve("saves"), inst.dir.resolve("saves"));
            }
        } catch (IOException e) {
            try { Launcher.delete(s, inst); } catch (IOException ignored) { }
            throw e;
        }
        int placed = refetchMods(core, f.gameDir().resolve("mods"), inst, notes, (x, m) -> p.update(0.2 + 0.78 * x, m));
        Launcher.save(inst);
        Launcher.refresh(inst);
        p.update(1, "Done");
        return new Result(inst, placed, notes);
    }

    /**
     * The mods in {@code from} (switched-on jars), found on Modrinth by SHA-1 and installed as their newest Fabric build
     * for this Minecraft version. Mods an Evade option manages switch that option on instead; Fabric API and Evade come
     * with every install. Returns how many were installed.
     */
    static int refetchMods(InstallerCore core, Path from, Launcher.Instance inst, List<String> notes, InstallerCore.Progress p) {
        if (!Files.isDirectory(from)) return 0;
        Map<String, Path> byHash = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(from, "*.jar")) {
            for (Path j : ds) {
                String n = j.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.startsWith("evade-client") || n.startsWith("fabric-api")) continue;
                try { byHash.put(ContentApi.sha1(j), j); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        if (byHash.isEmpty()) return 0;
        p.update(0.02, "Looking your mods up on Modrinth");
        Map<String, ContentApi.Version> ids;
        Map<String, ContentApi.Project> projects;
        try {
            ids = ContentApi.identify(byHash.keySet());
            Set<String> pids = new LinkedHashSet<>();
            for (ContentApi.Version ver : ids.values()) if (ver.projectId() != null) pids.add(ver.projectId());
            projects = ContentApi.projects(pids);
        } catch (IOException e) {
            notes.add("Your mods weren't copied: " + EvadeInstaller.friendly(e) + ". Add them from the Mods page later.");
            return 0;
        }
        List<String> unknown = new ArrayList<>();
        for (var e : byHash.entrySet()) if (!ids.containsKey(e.getKey())) unknown.add(e.getValue().getFileName().toString());
        if (!unknown.isEmpty()) notes.add(unknown.size() + (unknown.size() == 1 ? " mod isn't" : " mods aren't") + " on Modrinth, so "
                + (unknown.size() == 1 ? "it wasn't" : "they weren't") + " copied: " + String.join(", ", unknown.subList(0, Math.min(4, unknown.size())))
                + (unknown.size() > 4 ? "…" : ""));
        int placed = 0, k = 0;
        Set<String> done = new LinkedHashSet<>();
        for (ContentApi.Version ver : ids.values()) {
            double a = (double) k / ids.size(), b = (double) (k + 1) / ids.size();
            k++;
            ContentApi.Project pr = projects.get(ver.projectId());
            if (pr == null || !done.add(pr.id()) || InstallerCore.FABRIC_API_ID.equals(pr.id())) continue;
            if (pr.type() != null && !pr.type().equals("mod")) continue;
            String option = InstallerCore.optionForSlug(pr.slug());
            if (option != null) {
                switchOn(inst, option);
                continue;
            }
            try {
                if (Mods.installedProjects(inst.dir).contains(pr.id())) continue;
                Mods.install(core, inst.dir, new Mods.Hit(pr.id(), pr.slug(), pr.title(), "", "", 0, 0), (x, m) -> p.update(a + (b - a) * x, m));
                placed++;
            } catch (Exception ex) {
                String why = EvadeInstaller.friendly(ex);
                notes.add(why.startsWith(pr.title()) ? why : pr.title() + ": " + why);
            }
        }
        return placed;
    }

    /** Turns on the Evade option that manages a mod the other instance had ("Shaders" for Iris, ...). */
    static void switchOn(Launcher.Instance i, String option) {
        switch (option) {
            case "FPS mods" -> i.perf = true;
            case "Shaders" -> i.shaders = true;
            case "Voice chat" -> i.voice = true;
            case "Pack features" -> i.packs = true;
            case "Dynamic lights" -> i.lights = true;
            default -> {
                OptionalMods.Group g = OptionalMods.byLabel(option);
                if (g != null) i.optional.add(g.key());
            }
        }
        i.optional = OptionalMods.clean(i.optional);
    }

    /** Copies a folder's contents (session.lock files left out: they belong to a running game). */
    static void copyDir(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) { return FileVisitResult.CONTINUE; }
        });
    }
}
