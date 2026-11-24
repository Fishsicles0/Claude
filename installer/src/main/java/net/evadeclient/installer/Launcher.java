package net.evadeclient.installer;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The launcher's model: its own settings, and the instances. An instance is a game folder with its own mods, settings
 * and worlds, and its own profile in the official Minecraft Launcher:
 * <pre>
 *   default instance   .minecraft/evade                    profile "evade-client"         ("Evade Client")
 *   other instances    .minecraft/evade-instances/&lt;id&gt;     profile "evade-client-&lt;id&gt;"    ("Evade Client — &lt;name&gt;")
 * </pre>
 * Each instance folder keeps its options in evade-instance.json; the default instance works without one, so a folder
 * set up by the old installer carries straight over. Sign-in and the game itself stay with the official launcher.
 */
final class Launcher {
    private Launcher() {}

    static final String INSTANCE_FILE = "evade-instance.json", DEFAULT_ID = "default", DEFAULT_NAME = "Main";
    static final String DASH = " — ";

    // ------------------------------------------------------------------ settings

    /** The launcher's own settings (not per instance). */
    static final class Settings {
        String minecraftDir;          // null: the standard .minecraft
        String selected = DEFAULT_ID; // instance Play starts
        int defaultMemoryGb = InstallerCore.clampMemory(InstallerCore.recommendedMemory());
        String javaPath = "";         // blank: the Minecraft Launcher's own Java
        boolean closeOnPlay = true;
        boolean checkUpdates = true;
        boolean clips = true;         // keep Evade Clips installed and up to date
        String clipsVersion;          // the Evade Clips version installed last

        Path minecraftDir() {
            return minecraftDir != null && !minecraftDir.isBlank() ? Paths.get(minecraftDir) : InstallerCore.defaultMinecraftDir();
        }
    }

    /** Where the launcher keeps its settings (overridable with -Devade.launcherData=...). */
    static Path dataDir() {
        String override = System.getProperty("evade.launcherData");
        if (override != null) return Paths.get(override);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), home = System.getProperty("user.home");
        if (os.contains("win") && System.getenv("APPDATA") != null) return Paths.get(System.getenv("APPDATA"), "EvadeLauncher");
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "EvadeLauncher");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(home, ".config")).resolve("evade-launcher");
    }

    static Settings loadSettings() {
        Settings s = new Settings();
        JsonObject o = InstallerCore.readJsonObject(dataDir().resolve("settings.json"));
        if (o.has("minecraftDir")) s.minecraftDir = InstallerCore.str(o, "minecraftDir");
        if (InstallerCore.str(o, "selected") != null) s.selected = InstallerCore.str(o, "selected");
        try { if (o.has("defaultMemoryGb")) s.defaultMemoryGb = InstallerCore.clampMemory(o.get("defaultMemoryGb").getAsInt()); } catch (RuntimeException ignored) { }
        if (InstallerCore.str(o, "javaPath") != null) s.javaPath = InstallerCore.str(o, "javaPath");
        s.closeOnPlay = bool(o, "closeOnPlay", true);
        s.checkUpdates = bool(o, "checkUpdates", true);
        s.clips = bool(o, "clips", true);
        s.clipsVersion = InstallerCore.str(o, "clipsVersion");
        return s;
    }

    static void saveSettings(Settings s) {
        JsonObject o = new JsonObject();
        if (s.minecraftDir != null) o.addProperty("minecraftDir", s.minecraftDir);
        o.addProperty("selected", s.selected);
        o.addProperty("defaultMemoryGb", s.defaultMemoryGb);
        o.addProperty("javaPath", s.javaPath == null ? "" : s.javaPath);
        o.addProperty("closeOnPlay", s.closeOnPlay);
        o.addProperty("checkUpdates", s.checkUpdates);
        o.addProperty("clips", s.clips);
        if (s.clipsVersion != null) o.addProperty("clipsVersion", s.clipsVersion);
        try {
            Files.createDirectories(dataDir());
            InstallerCore.writeAtomic(dataDir().resolve("settings.json"), InstallerCore.GSON.toJson(o));
        } catch (IOException e) {
            System.err.println("Couldn't save launcher settings: " + e.getMessage());
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try { return o.has(k) ? o.get(k).getAsBoolean() : def; } catch (RuntimeException e) { return def; }
    }

    // ------------------------------------------------------------------ instances

    /** One instance: a game folder, its options, and (read from disk) what's installed in it. */
    static final class Instance {
        final String id;
        final Path dir;
        String name;
        int memoryGb;
        boolean perf = true, shaders = false, voice = true;
        /** Options changed since the last install: Play brings the folder in line first. */
        boolean dirty;
        String created;
        /** The Java override last written to this instance's profile (so clearing the setting can take it out again). */
        String javaDirWritten;
        // read from disk
        InstallerCore.Installed installed;
        int modCount;
        /** Why Play updates this instance first (a newer Evade, Fabric or extra), or null when it's current. */
        String pending;

        Instance(String id, Path dir, String name) {
            this.id = id;
            this.dir = dir;
            this.name = name;
        }

        boolean isDefault() { return DEFAULT_ID.equals(id); }

        String profileKey() { return isDefault() ? InstallerCore.PROFILE_KEY : InstallerCore.PROFILE_KEY + "-" + id; }

        /** "Evade Client" for the untouched default instance (as the installer always named it), else "Evade Client — name". */
        String profileName() {
            return isDefault() && DEFAULT_NAME.equals(name) ? InstallerCore.PROFILE_NAME : InstallerCore.PROFILE_NAME + DASH + name;
        }
    }

    static Path instancesRoot(Path mc) { return mc.resolve("evade-instances"); }

    /** Every instance: the default one first, then the others oldest first. */
    static List<Instance> listInstances(Settings s) {
        Path mc = s.minecraftDir();
        List<Instance> out = new ArrayList<>();
        out.add(read(DEFAULT_ID, InstallerCore.defaultGameDir(mc), s));
        List<Instance> others = new ArrayList<>();
        Path root = instancesRoot(mc);
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path d : ds) {
                    if (Files.isRegularFile(d.resolve(INSTANCE_FILE))) others.add(read(d.getFileName().toString(), d, s));
                }
            } catch (IOException ignored) { }
        }
        others.sort(Comparator.comparing((Instance i) -> i.created == null ? "" : i.created).thenComparing(i -> i.name.toLowerCase(Locale.ROOT)));
        out.addAll(others);
        return out;
    }

    private static Instance read(String id, Path dir, Settings s) {
        JsonObject o = InstallerCore.readJsonObject(dir.resolve(INSTANCE_FILE));
        String name = InstallerCore.str(o, "name");
        Instance i = new Instance(id, dir, name != null && !name.isBlank() ? name : DEFAULT_ID.equals(id) ? DEFAULT_NAME : id);
        JsonObject marker = InstallerCore.readMarker(dir);
        if (o.size() == 0) {
            // A folder from the old installer: its options are whatever it installed, its memory whatever its profile says.
            if (marker != null) {
                i.perf = notBlank(InstallerCore.str(marker, "performanceMods"));
                i.voice = notBlank(InstallerCore.str(marker, "voiceChat"));
                i.shaders = notBlank(InstallerCore.str(marker, "shaders"));
            }
            i.memoryGb = profileMemory(s.minecraftDir(), i.profileKey(), s.defaultMemoryGb);
        } else {
            i.memoryGb = num(o, "memoryGb", s.defaultMemoryGb);
            i.perf = bool(o, "perf", true);
            i.shaders = bool(o, "shaders", false);
            i.voice = bool(o, "voice", true);
            i.dirty = bool(o, "dirty", false);
            i.created = InstallerCore.str(o, "created");
            i.javaDirWritten = InstallerCore.str(o, "javaDir");
        }
        refresh(i);
        return i;
    }

    /** Re-reads what's installed in the instance and how many mods it has. */
    static void refresh(Instance i) {
        i.installed = InstallerCore.readInstalled(i.dir);
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(i.dir.resolve("mods"), "*.jar")) {
            for (Path ignored : ds) n++;
        } catch (IOException ignored) { }
        i.modCount = n;
    }

    /** Works out {@link Instance#pending} against the release on evadeclient.com (unknown while it's being checked). */
    static void check(Instance i, InstallerCore.Release r) {
        i.pending = r == null ? null : InstallerCore.updateReason(i.dir, r, i.perf, i.voice, i.shaders);
    }

    static void save(Instance i) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("name", i.name);
        o.addProperty("memoryGb", i.memoryGb);
        o.addProperty("perf", i.perf);
        o.addProperty("shaders", i.shaders);
        o.addProperty("voice", i.voice);
        o.addProperty("dirty", i.dirty);
        o.addProperty("created", i.created != null ? i.created : Instant.now().toString());
        if (i.javaDirWritten != null) o.addProperty("javaDir", i.javaDirWritten);
        Files.createDirectories(i.dir);
        InstallerCore.writeAtomic(i.dir.resolve(INSTANCE_FILE), InstallerCore.GSON.toJson(o));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static int num(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (RuntimeException e) { return def; }
    }

    /** The -Xmx of an existing launcher profile, in GB, or {@code def}. */
    static int profileMemory(Path mc, String key, int def) {
        JsonObject p = InstallerCore.readProfile(mc, key);
        String args = p != null ? InstallerCore.str(p, "javaArgs") : null;
        if (args == null) return def;
        Matcher m = Pattern.compile("(?<!\\S)-Xmx(\\d+)([gGmM])").matcher(args);
        if (!m.find()) return def;
        int v = Integer.parseInt(m.group(1));
        return m.group(2).equalsIgnoreCase("m") ? Math.max(1, Math.round(v / 1024f)) : v;
    }

    /** A folder name for a new instance: lowercase letters, digits and dashes, not taken yet. */
    static String newId(Path mc, String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty() || base.equals(DEFAULT_ID)) base = "instance";
        if (base.length() > 32) base = base.substring(0, 32).replaceAll("-+$", "");
        String id = base;
        for (int n = 2; Files.exists(instancesRoot(mc).resolve(id)); n++) id = base + "-" + n;
        return id;
    }

    /** A name for an instance that no other instance has (case-insensitive): "PvP", "PvP 2", ... */
    static String uniqueName(List<Instance> all, String wanted, Instance except) {
        String base = wanted.strip();
        if (base.isEmpty()) base = "Instance";
        if (base.length() > 40) base = base.substring(0, 40).strip();
        String n = base;
        for (int k = 2; taken(all, n, except); k++) n = base + " " + k;
        return n;
    }

    private static boolean taken(List<Instance> all, String name, Instance except) {
        for (Instance i : all) if (i != except && i.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    /**
     * A new, empty instance (installed on its first Play). {@code copyFrom} names where its game settings come from:
     * another instance, {@code "vanilla"} for the normal .minecraft, or null for none.
     */
    static Instance create(Settings s, List<Instance> all, String name, String copyFrom, int memoryGb, boolean perf,
                           boolean shaders, boolean voice) throws IOException {
        Path mc = s.minecraftDir();
        String id = newId(mc, name);
        Instance i = new Instance(id, instancesRoot(mc).resolve(id), uniqueName(all, name, null));
        i.memoryGb = memoryGb;
        i.perf = perf;
        i.shaders = shaders;
        i.voice = voice;
        i.dirty = true;
        i.created = Instant.now().toString();
        Files.createDirectories(i.dir.resolve("mods"));
        if (copyFrom != null) copySettings(s, all, copyFrom, i);
        save(i);
        refresh(i);
        return i;
    }

    /** The files that hold a player's game settings, server list and mod settings. */
    private static final String[] SETTINGS_FILES = {"options.txt", "servers.dat", "optionsof.txt", "optionsshaders.txt", "hotbar.nbt"};

    /** Copies game settings (options, servers, mod config) from another instance or the normal .minecraft into {@code to}. */
    static void copySettings(Settings s, List<Instance> all, String from, Instance to) throws IOException {
        Path src = null;
        if ("vanilla".equals(from)) src = s.minecraftDir();
        else for (Instance i : all) if (i.id.equals(from)) src = i.dir;
        if (src == null || src.equals(to.dir)) return;
        for (String f : SETTINGS_FILES) {
            Path a = src.resolve(f);
            if (Files.isRegularFile(a)) Files.copy(a, to.dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        // Mod settings (Evade's layout, keybinds, ...) only come from another Evade instance, never from .minecraft.
        if (!"vanilla".equals(from) && Files.isDirectory(src.resolve("config"))) copyTree(src.resolve("config"), to.dir.resolve("config"), Set.of());
    }

    /** A copy of an instance: everything in its folder (worlds included) except logs and crash reports. */
    static Instance duplicate(Settings s, List<Instance> all, Instance src) throws IOException {
        Path mc = s.minecraftDir();
        String name = uniqueName(all, src.name + " copy", null);
        String id = newId(mc, name);
        Instance i = new Instance(id, instancesRoot(mc).resolve(id), name);
        i.memoryGb = src.memoryGb;
        i.perf = src.perf;
        i.shaders = src.shaders;
        i.voice = src.voice;
        i.dirty = src.dirty;
        i.created = Instant.now().toString();
        copyTree(src.dir, i.dir, Set.of("logs", "crash-reports", ".evade-staging", INSTANCE_FILE, "debug", "screenshots"));
        save(i);
        refresh(i);
        return i;
    }

    /** Renames an instance (its folder keeps its id) and its launcher profile. */
    static void rename(Settings s, Instance i, String name) throws IOException {
        i.name = name;
        save(i);
        if (InstallerCore.readProfile(s.minecraftDir(), i.profileKey()) != null) {
            InstallerCore.writeProfiles(options(s, i, false, false, false));
        }
    }

    /** Removes an instance: its launcher profile, then its folder (to the Recycle Bin / Trash where the system has one). */
    static void delete(Settings s, Instance i) throws IOException {
        if (i.isDefault()) throw new IOException("The main instance can't be deleted");
        InstallerCore.removeProfiles(s.minecraftDir(), i.profileKey());
        boolean trashed = false;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                trashed = java.awt.Desktop.getDesktop().moveToTrash(i.dir.toFile());
            }
        } catch (RuntimeException ignored) { }
        if (!trashed) InstallerCore.deleteTree(i.dir);
    }

    /**
     * The install options for an instance. {@code updateClips}: also install or update Evade Clips (it's one app for
     * every instance, so only when it's missing or out of date).
     */
    static InstallerCore.Options options(Settings s, Instance i, boolean copySettings, boolean updateClips, boolean writeJava) {
        String java = null;
        if (writeJava) {
            String want = s.javaPath == null ? "" : s.javaPath.strip();
            // Blank removes only an override the launcher wrote itself; one the player set in the launcher stays.
            if (!want.isEmpty()) java = want;
            else if (i.javaDirWritten != null) java = "";
        }
        return new InstallerCore.Options(s.minecraftDir(), i.dir, i.memoryGb, copySettings, i.voice, i.perf, updateClips, i.shaders,
                i.profileKey(), i.profileName(), java);
    }

    /** Copies a folder tree, leaving out top-level entries named in {@code skip}. */
    static void copyTree(Path from, Path to, Set<String> skip) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && dir.getParent().equals(from) && skip.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getParent().equals(from) && skip.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // a locked log or session.lock: skip it
            }
        });
    }

    // ------------------------------------------------------------------ the official launcher

    /** Whether the official Minecraft Launcher looks installed (best effort: a miss isn't certain). */
    static boolean officialLauncherFound() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA"), pf86 = System.getenv("ProgramFiles(x86)");
            if (local != null && Files.exists(Paths.get(local, "Programs", "Minecraft Launcher", "MinecraftLauncher.exe"))) return true;
            if (pf86 != null && Files.exists(Paths.get(pf86, "Minecraft Launcher", "MinecraftLauncher.exe"))) return true;
            // The Microsoft Store / Xbox app version keeps its data here.
            return local != null && Files.isDirectory(Paths.get(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe"));
        }
        if (os.contains("mac")) {
            return Files.exists(Paths.get("/Applications/Minecraft.app"))
                    || Files.exists(Paths.get(System.getProperty("user.home"), "Applications", "Minecraft.app"));
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Paths.get(dir, "minecraft-launcher"))) return true;
        }
        return Files.isDirectory(Paths.get(System.getProperty("user.home"), ".var", "app", "com.mojang.Minecraft"))
                || Files.isDirectory(Paths.get("/var/lib/flatpak/app/com.mojang.Minecraft"));
    }

    static final String GET_LAUNCHER = "https://www.minecraft.net/download";
}
