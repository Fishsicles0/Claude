package net.evadeclient.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Does the actual installation. The official Minecraft Launcher handles login, downloading vanilla 1.21.11 and
 * the Fabric libraries; we only add a version file, the mods, and a launcher profile.
 */
public final class InstallerCore {
    public static final String MC_VERSION = "1.21.11";
    /** Overridable with -Devade.site=... (tests point it at a local server). */
    public static final String SITE = System.getProperty("evade.site", "https://evadeclient.com");
    public static final String PROFILE_KEY = "evade-client";
    public static final String VERSION_ID = "evade-client-" + MC_VERSION;
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/" + MC_VERSION;
    private static final String MODRINTH = "https://api.modrinth.com/v2/project/fabric-api/version";
    /** Simple Voice Chat by henkelmax. All Rights Reserved, so we never host it: players get it straight from Modrinth. */
    private static final String SVC = "https://api.modrinth.com/v2/project/simple-voice-chat/version";

    /**
     * Performance mods added from Modrinth (never hosted by us): Modrinth slug, display name, and the filename
     * prefixes to clear out on reinstall. All are tested alongside Evade in its in-game test suite.
     */
    private static final String[][] PERF = {
            {"sodium", "Sodium", "sodium"},
            {"lithium", "Lithium", "lithium"},
            {"ferrite-core", "FerriteCore", "ferritecore"},
            {"immediatelyfast", "ImmediatelyFast", "ImmediatelyFast", "immediatelyfast"},
            {"entityculling", "EntityCulling", "entityculling"},
            {"moreculling", "MoreCulling", "moreculling"},
            {"krypton", "Krypton", "krypton"},
            {"badoptimizations", "BadOptimizations", "BadOptimizations", "badoptimizations"},
            {"sodium-extra", "Sodium Extra", "sodium-extra"},
            {"fast-ip-ping", "Fast IP Ping", "fast-ip-ping"},
            {"fastquit", "FastQuit", "fastquit"},
    };
    /** Fabric API: every mod's shared dependency, which we already install ourselves. */
    private static final String FABRIC_API_ID = "P7dR8mSH";
    /** Sodium's Modrinth project id (Iris pins an exact Sodium build) and the shader pack the Shaders option adds. */
    private static final String SODIUM_ID = "AANobbMI", SHADER_PACK = "complementary-reimagined";
    private static final String USER_AGENT = "EvadeInstaller/1.4 (+https://evadeclient.com)";

    public interface Progress {
        void update(double fraction, String message);
    }

    /** Memory to give Minecraft on this PC: enough for Evade plus the FPS mods, without the long GC pauses
     *  that an oversized heap causes. 2 GB under 6 GB of RAM, 3 GB under 10, 4 GB under 20, else 6 GB. */
    public static int recommendedMemory() {
        long gb = 8;
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            gb = os.getTotalMemorySize() >> 30;
        } catch (Throwable ignored) {}
        return gb < 6 ? 2 : gb < 10 ? 3 : gb < 20 ? 4 : 6;
    }

    public record Options(Path minecraftDir, Path gameDir, int memoryGb, boolean copySettings, boolean voiceChat,
                          boolean performanceMods, boolean installClips, boolean shaders) {
        public Options(Path minecraftDir, Path gameDir, int memoryGb, boolean copySettings, boolean voiceChat,
                       boolean performanceMods, boolean installClips) {
            this(minecraftDir, gameDir, memoryGb, copySettings, voiceChat, performanceMods, installClips, false);
        }
    }

    /** What's available to install (fetched before installing so the UI can show versions). */
    public record Release(String evadeVersion, String evadeUrl, String evadeSha256, String loaderVersion,
                          String fabricApiVersion, String fabricApiUrl, String fabricApiSha512, String fabricApiFile,
                          ModFile voiceChat, java.util.List<ModFile> performance,
                          String clipsVersion, String clipsUrl, String clipsSha256, String ffmpegUrl, String ffmpegShaUrl,
                          ModFile iris, ModFile irisSodium, ModFile shaderPack) {}

    /** A mod file on Modrinth, with the project ids of the mods it needs. */
    public record ModFile(String name, String version, String url, String sha512, String filename,
                          String projectId, java.util.List<String> requires, java.util.Map<String, String> requiredVersions) {}

    public record Installed(String evadeVersion, String loaderVersion, String fabricApiVersion) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final HttpClient http = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ------------------------------------------------------------------ locations

    public static Path defaultMinecraftDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : home, ".minecraft");
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "minecraft");
        return Paths.get(home, ".minecraft");
    }

    /** Evade gets its own game folder so its mods and config never touch the player's normal setup. */
    public static Path defaultGameDir(Path minecraftDir) {
        return minecraftDir.resolve("evade");
    }

    public static Installed readInstalled(Path gameDir) {
        try {
            Path f = gameDir.resolve("evade-install.json");
            if (!Files.exists(f)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            return new Installed(str(o, "evade"), str(o, "loader"), str(o, "fabricApi"));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ release lookup

    public Release fetchRelease() throws IOException, InterruptedException {
        JsonObject site = getJson(SITE + "/version.json").getAsJsonObject();
        String evadeVersion = str(site, "version");
        String evadeUrl = SITE + "/" + str(site, "file");
        String evadeSha = str(site, "sha256");

        String loader = null;
        for (JsonElement e : getJson(FABRIC_META).getAsJsonArray()) {
            JsonObject l = e.getAsJsonObject().getAsJsonObject("loader");
            if (l.get("stable").getAsBoolean()) { loader = l.get("version").getAsString(); break; }
        }
        if (loader == null) throw new IOException("No stable Fabric Loader found for " + MC_VERSION);

        String query = "?game_versions=" + enc("[\"" + MC_VERSION + "\"]") + "&loaders=" + enc("[\"fabric\"]");
        JsonArray versions = getJson(MODRINTH + query).getAsJsonArray();
        JsonObject api = null;
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if ("release".equals(str(v, "version_type"))) { api = v; break; }
        }
        if (api == null && !versions.isEmpty()) api = versions.get(0).getAsJsonObject();
        if (api == null) throw new IOException("No Fabric API release found for " + MC_VERSION);
        JsonObject file = null;
        for (JsonElement f : api.getAsJsonArray("files")) {
            if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
        }
        if (file == null) file = api.getAsJsonArray("files").get(0).getAsJsonObject();

        ModFile voice = null;
        try { voice = latestOnModrinth("Simple Voice Chat", SVC + query); } catch (Exception e) { /* optional: install without voice chat */ }

        java.util.List<ModFile> perf = new java.util.ArrayList<>();
        for (String[] m : PERF) {
            try {
                ModFile f = latestOnModrinth(m[1], "https://api.modrinth.com/v2/project/" + m[0] + "/version" + query);
                if (f != null) perf.add(f);
            } catch (Exception e) { /* optional: skip that one */ }
        }
        // Libraries those mods need (e.g. Cloth Config for MoreCulling), resolved from Modrinth's dependency lists.
        java.util.Set<String> have = new java.util.HashSet<>();
        for (ModFile f : perf) have.add(f.projectId());
        for (int i = 0; i < perf.size(); i++) {
            for (String dep : perf.get(i).requires()) {
                if (dep == null || dep.equals(FABRIC_API_ID) || !have.add(dep)) continue;
                try {
                    String title = str(getJson("https://api.modrinth.com/v2/project/" + dep).getAsJsonObject(), "title");
                    ModFile lib = latestOnModrinth(title, "https://api.modrinth.com/v2/project/" + dep + "/version" + query);
                    if (lib != null) perf.add(lib);
                } catch (Exception e) { /* its mod will simply not load without it; skip */ }
            }
        }

        // Shaders (optional): Iris, the exact Sodium build it asks for, and Complementary Reimagined.
        ModFile iris = null, irisSodium = null, pack = null;
        try {
            iris = latestOnModrinth("Iris", "https://api.modrinth.com/v2/project/iris/version" + query);
            String sodiumVersion = iris == null ? null : iris.requiredVersions().get(SODIUM_ID);
            if (sodiumVersion != null) irisSodium = modFileFromVersion("Sodium", getJson("https://api.modrinth.com/v2/version/" + sodiumVersion).getAsJsonObject());
            pack = latestOnModrinth("Complementary Reimagined", "https://api.modrinth.com/v2/project/" + SHADER_PACK
                    + "/version?loaders=" + java.net.URLEncoder.encode("[\"iris\"]", java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { /* optional: shaders just won't be offered */ }

        // Evade Clips: the Windows .exe on Windows (it finds Java itself), the jar everywhere else.
        String clipsVersion = null, clipsUrl = null, clipsSha = null, ffUrl = null, ffSha = null;
        if (site.has("clips") && site.get("clips").isJsonObject()) {
            JsonObject c = site.getAsJsonObject("clips");
            clipsVersion = str(c, "version");
            JsonObject pick = WINDOWS && c.has("windows") ? c.getAsJsonObject("windows") : c.getAsJsonObject("jar");
            if (pick != null) { clipsUrl = SITE + "/" + str(pick, "file"); clipsSha = str(pick, "sha256"); }
            if (WINDOWS && c.has("ffmpeg")) {
                JsonObject f = c.getAsJsonObject("ffmpeg");
                ffUrl = str(f, "url");
                ffSha = str(f, "sha256url");
            }
        }
        return new Release(evadeVersion, evadeUrl, evadeSha, loader, str(api, "version_number"),
                str(file, "url"), file.getAsJsonObject("hashes").get("sha512").getAsString(), str(file, "filename"), voice, perf,
                clipsVersion, clipsUrl, clipsSha, ffUrl, ffSha, iris, irisSodium, pack);
    }

    /** Newest release for this Minecraft version (falling back to the newest beta), or null if there's none. */
    private ModFile latestOnModrinth(String name, String url) throws IOException, InterruptedException {
        JsonArray versions = getJson(url).getAsJsonArray();
        JsonObject pick = null;
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if ("release".equals(str(v, "version_type"))) { pick = v; break; }
        }
        if (pick == null && !versions.isEmpty()) pick = versions.get(0).getAsJsonObject();
        if (pick == null) return null;
        return modFileFromVersion(name, pick);
    }

    /** A Modrinth version object as a ModFile: primary file, hash, and the projects (and exact versions) it requires. */
    private static ModFile modFileFromVersion(String name, JsonObject pick) {
        JsonObject file = null;
        for (JsonElement f : pick.getAsJsonArray("files")) if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
        if (file == null) file = pick.getAsJsonArray("files").get(0).getAsJsonObject();
        java.util.List<String> requires = new java.util.ArrayList<>();
        java.util.Map<String, String> versions = new java.util.HashMap<>();
        for (JsonElement d : pick.getAsJsonArray("dependencies")) {
            JsonObject dep = d.getAsJsonObject();
            if (!"required".equals(str(dep, "dependency_type")) || str(dep, "project_id") == null) continue;
            requires.add(str(dep, "project_id"));
            if (str(dep, "version_id") != null) versions.put(str(dep, "project_id"), str(dep, "version_id"));
        }
        return new ModFile(name, str(pick, "version_number"), str(file, "url"),
                file.getAsJsonObject("hashes").get("sha512").getAsString(), str(file, "filename"), str(pick, "project_id"), requires, versions);
    }

    // ------------------------------------------------------------------ install

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Where Evade Clips keeps its settings (and where it records its install location for Evade Client). */
    public static Path clipsDataDir() {
        String override = System.getProperty("evade.clipsData");
        if (override != null) return Paths.get(override);
        if (WINDOWS && System.getenv("APPDATA") != null) return Paths.get(System.getenv("APPDATA"), "EvadeClips");
        return Paths.get(System.getProperty("user.home"), ".evade-clips");
    }

    /** Where the Evade Clips app itself is installed. */
    public static Path clipsAppDir() {
        String override = System.getProperty("evade.clipsApp");
        if (override != null) return Paths.get(override);
        if (WINDOWS && System.getenv("LOCALAPPDATA") != null) return Paths.get(System.getenv("LOCALAPPDATA"), "Programs", "Evade Clips");
        return clipsDataDir().resolve("app");
    }

    /**
     * Installs Evade Clips: the app, ffmpeg (Windows, if not already there), a Start Menu / app-menu entry, start
     * at login (in the tray), and the location file Evade Client reads to start it with Minecraft.
     */
    void installClips(Release r, Progress p) throws Exception {
        Path app = clipsAppDir();
        Files.createDirectories(app);
        boolean exe = r.clipsUrl().endsWith(".exe");
        Path target = app.resolve(exe ? "Evade-Clips.exe" : "EvadeClips.jar");
        p.update(0.86, "Downloading Evade Clips " + r.clipsVersion());
        download(r.clipsUrl(), target, r.clipsSha256(), "SHA-256", 0.86, 0.9, p);

        // Where Evade Client finds the app to start it with Minecraft.
        JsonObject loc = new JsonObject();
        if (exe) loc.addProperty("exe", target.toString());
        else {
            loc.addProperty("jar", target.toString());
            loc.addProperty("java", Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        }
        Files.createDirectories(clipsDataDir());
        Files.writeString(clipsDataDir().resolve("location.json"), GSON.toJson(loc));

        p.update(0.96, "Adding Evade Clips to your apps");
        try { shortcuts(target, exe); } catch (Exception e) { System.err.println("Shortcut creation skipped: " + e.getMessage()); }

        // ffmpeg last: it's big (about 100 MB) and its host can be slow, so it never blocks the rest of the install.
        // If it fails or stalls, Evade Clips downloads it itself the first time it opens.
        if (r.ffmpegUrl() != null && !Files.exists(app.resolve("ffmpeg").resolve("bin").resolve("ffmpeg.exe"))) {
            Path zip = app.resolve("ffmpeg-download.zip");
            try {
                p.update(0.965, "Downloading ffmpeg (records and edits video)");
                String expected = null;
                if (r.ffmpegShaUrl() != null) {
                    HttpResponse<String> sha = http.send(request(r.ffmpegShaUrl()), HttpResponse.BodyHandlers.ofString());
                    if (sha.statusCode() == 200) expected = sha.body().trim().split("\\s+")[0];
                }
                download(r.ffmpegUrl(), zip, expected, "SHA-256", 0.965, 0.995, p, "Downloading ffmpeg");
                unzipFfmpeg(zip, app.resolve("ffmpeg").resolve("bin"));
            } catch (Exception e) {
                p.update(0.995, "ffmpeg didn't finish downloading. Evade Clips will get it when you open it.");
            } finally {
                try { Files.deleteIfExists(zip); Files.deleteIfExists(zip.resolveSibling(zip.getFileName() + ".part")); } catch (Exception ignored) {}
            }
        }
    }

    /** Pulls ffmpeg.exe and ffprobe.exe out of an ffmpeg build zip (whatever folder they're in). */
    static void unzipFfmpeg(Path zip, Path binDir) throws IOException {
        Files.createDirectories(binDir);
        int found = 0;
        try (var z = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String file = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (!e.isDirectory() && (file.equals("ffmpeg.exe") || file.equals("ffprobe.exe"))) {
                    Files.copy(z, binDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                    found++;
                }
            }
        }
        if (found < 2) throw new IOException("The ffmpeg download didn't contain ffmpeg.exe and ffprobe.exe");
    }

    /** Start Menu + login (Windows), or app menu + autostart entries (Linux). */
    private static void shortcuts(Path target, boolean exe) throws Exception {
        if (WINDOWS) {
            String appData = System.getenv("APPDATA");
            if (appData == null) return;
            Path programs = Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs");
            Path startup = programs.resolve("Startup");
            String ps = "$w=New-Object -ComObject WScript.Shell;"
                    + lnk(programs.resolve("Evade Clips.lnk"), target, "")
                    + lnk(startup.resolve("Evade Clips.lnk"), target, "--minimized");
            Process pr = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).redirectErrorStream(true).start();
            pr.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            pr.waitFor();
        } else if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String cmd = "\"" + java + "\" -jar \"" + target + "\"";
            String home = System.getProperty("user.home");
            Path apps = Paths.get(home, ".local", "share", "applications"), auto = Paths.get(home, ".config", "autostart");
            Files.createDirectories(apps);
            Files.createDirectories(auto);
            String entry = "[Desktop Entry]\nType=Application\nName=Evade Clips\nComment=Record, store and edit gameplay clips\nTerminal=false\nCategories=AudioVideo;Game;\n";
            Files.writeString(apps.resolve("evade-clips.desktop"), entry + "Exec=" + cmd + "\n");
            Files.writeString(auto.resolve("evade-clips.desktop"), entry + "Exec=" + cmd + " --minimized\n");
        }
    }

    private static String lnk(Path at, Path target, String args) {
        return "$s=$w.CreateShortcut('" + at.toString().replace("'", "''") + "');$s.TargetPath='" + target.toString().replace("'", "''")
                + "';$s.Arguments='" + args + "';$s.WorkingDirectory='" + target.getParent().toString().replace("'", "''") + "';$s.Save();";
    }

    public void install(Options o, Release r, Progress p) throws Exception {
        Path mc = o.minecraftDir();
        Path game = o.gameDir();
        Files.createDirectories(mc);
        Files.createDirectories(game.resolve("mods"));

        // 1. Version file for the launcher (Fabric's profile JSON, renamed to our id).
        p.update(0.05, "Setting up Fabric Loader " + r.loaderVersion());
        JsonObject profile = getJson(FABRIC_META + "/" + r.loaderVersion() + "/profile/json").getAsJsonObject();
        profile.addProperty("id", VERSION_ID);
        Path versionDir = mc.resolve("versions").resolve(VERSION_ID);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve(VERSION_ID + ".json"), GSON.toJson(profile));
        // Some launchers expect a jar next to the json even though inheritsFrom supplies the real one.
        Path stub = versionDir.resolve(VERSION_ID + ".jar");
        if (!Files.exists(stub)) Files.write(stub, new byte[0]);

        // 2. Mods: remove old copies, download fresh ones with hash checks.
        Path mods = game.resolve("mods");
        deleteMatching(mods, "evade-client");
        deleteMatching(mods, "fabric-api");
        p.update(0.15, "Downloading Fabric API " + r.fabricApiVersion());
        download(r.fabricApiUrl(), mods.resolve(r.fabricApiFile()), r.fabricApiSha512(), "SHA-512", 0.15, 0.55, p);
        p.update(0.55, "Downloading Evade Client v" + r.evadeVersion());
        download(r.evadeUrl(), mods.resolve("evade-client-" + r.evadeVersion() + ".jar"), r.evadeSha256(), "SHA-256", 0.55, 0.75, p);

        // Voice chat: Simple Voice Chat, fetched from Modrinth (optional; a failure here doesn't stop the install).
        deleteMatching(mods, "voicechat");
        String voiceInstalled = "";
        StringBuilder perfInstalled = new StringBuilder();
        for (String[] m : PERF) for (int i = 2; i < m.length; i++) deleteMatching(mods, m[i]);
        JsonArray managed = new JsonArray();
        try {
            Path old = game.resolve("evade-install.json");
            if (Files.exists(old)) {
                JsonObject prev = JsonParser.parseString(Files.readString(old)).getAsJsonObject();
                if (prev.has("addedFiles")) for (JsonElement f : prev.getAsJsonArray("addedFiles")) {
                    String name = f.getAsString();
                    if (!name.contains("/") && !name.contains("\\") && name.endsWith(".jar")) Files.deleteIfExists(mods.resolve(name));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<ModFile> toInstall = new java.util.ArrayList<>(o.performanceMods() && r.performance() != null ? r.performance() : java.util.List.of());
        boolean shaders = o.shaders() && r.iris() != null;
        if (shaders) {
            // Iris needs one exact Sodium build: use it in place of (or in addition to) the latest Sodium.
            if (r.irisSodium() != null) { toInstall.removeIf(f -> SODIUM_ID.equals(f.projectId())); toInstall.add(r.irisSodium()); }
            toInstall.add(r.iris());
        }
        deleteMatching(mods, "iris");
        if (!toInstall.isEmpty()) {
            int i = 0, n = toInstall.size();
            for (ModFile f : toInstall) {
                double a = 0.75 + 0.05 * i / Math.max(1, n), b = 0.75 + 0.05 * (i + 1) / Math.max(1, n);
                p.update(a, "Downloading " + f.name() + " " + f.version());
                try {
                    download(f.url(), mods.resolve(f.filename()), f.sha512(), "SHA-512", a, b, p);
                    managed.add(f.filename());
                    if (perfInstalled.length() > 0) perfInstalled.append(", ");
                    perfInstalled.append(f.name()).append(' ').append(f.version());
                } catch (Exception e) {
                    p.update(b, f.name() + " couldn't be downloaded; run the installer again later to add it.");
                }
                i++;
            }
        }
        if (o.voiceChat() && r.voiceChat() != null) {
            ModFile v = r.voiceChat();
            p.update(0.80, "Downloading voice chat (Simple Voice Chat " + v.version() + ")");
            try {
                download(v.url(), mods.resolve(v.filename()), v.sha512(), "SHA-512", 0.80, 0.82, p);
                managed.add(v.filename());
                voiceInstalled = v.version();
            } catch (Exception e) {
                p.update(0.8, "Voice chat couldn't be downloaded; run the installer again later to add it.");
            }
        }

        // Shader pack: into shaderpacks/, and switched on in Iris the first time (later choices are left alone).
        if (shaders && r.shaderPack() != null) {
            ModFile pk = r.shaderPack();
            p.update(0.82, "Downloading " + pk.name() + " " + pk.version());
            try {
                Path packs = game.resolve("shaderpacks");
                Files.createDirectories(packs);
                download(pk.url(), packs.resolve(pk.filename()), pk.sha512(), "SHA-512", 0.82, 0.83, p);
                Path irisCfg = game.resolve("config").resolve("iris.properties");
                if (!Files.exists(irisCfg)) {
                    Files.createDirectories(irisCfg.getParent());
                    Files.writeString(irisCfg, "enableShaders=true\nshaderPack=" + pk.filename() + "\n");
                }
            } catch (Exception e) {
                p.update(0.83, "The shader pack couldn't be downloaded; run the installer again later to add it.");
            }
        }

        // 3. Carry over the player's settings and server list the first time.
        if (o.copySettings()) {
            p.update(0.82, "Copying your settings");
            for (String f : new String[]{"options.txt", "servers.dat"}) {
                Path src = mc.resolve(f), dst = game.resolve(f);
                if (Files.exists(src) && !Files.exists(dst)) Files.copy(src, dst);
            }
        }

        // 4. Evade Clips: clipping in the client sends clips here.
        if (o.installClips() && r.clipsUrl() != null) installClips(r, p);

        // 5. Launcher profile.
        p.update(0.97, "Adding the Evade Client profile");
        addProfile(mc.resolve("launcher_profiles.json"), true, o);
        Path store = mc.resolve("launcher_profiles_microsoft_store.json");
        if (Files.exists(store)) addProfile(store, false, o);

        JsonObject marker = new JsonObject();
        marker.addProperty("evade", r.evadeVersion());
        marker.addProperty("loader", r.loaderVersion());
        marker.addProperty("fabricApi", r.fabricApiVersion());
        marker.addProperty("voiceChat", voiceInstalled);
        marker.addProperty("performanceMods", perfInstalled.toString());
        marker.addProperty("shaders", shaders && r.shaderPack() != null ? r.shaderPack().version() : "");
        marker.add("addedFiles", managed);
        marker.addProperty("minecraft", MC_VERSION);
        marker.addProperty("installedAt", Instant.now().toString());
        if (o.installClips() && r.clipsVersion() != null) marker.addProperty("clips", r.clipsVersion());
        Files.writeString(game.resolve("evade-install.json"), GSON.toJson(marker));
        p.update(1.0, "Done");
    }

    private void addProfile(Path file, boolean createIfMissing, Options o) throws IOException {
        JsonObject root;
        if (Files.exists(file)) {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            // Keep a backup the first time we touch the player's launcher config.
            Path backup = file.resolveSibling(file.getFileName() + ".evade-backup");
            if (!Files.exists(backup)) Files.copy(file, backup);
        } else if (createIfMissing) {
            root = new JsonObject();
            root.add("settings", new JsonObject());
            root.addProperty("version", 3);
        } else {
            return;
        }
        if (!root.has("profiles") || !root.get("profiles").isJsonObject()) root.add("profiles", new JsonObject());
        JsonObject profiles = root.getAsJsonObject("profiles");

        String now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        JsonObject existing = profiles.has(PROFILE_KEY) ? profiles.getAsJsonObject(PROFILE_KEY) : null;
        JsonObject prof = new JsonObject();
        prof.addProperty("name", "Evade Client");
        prof.addProperty("type", "custom");
        prof.addProperty("created", existing != null && existing.has("created") ? existing.get("created").getAsString() : now);
        prof.addProperty("lastUsed", now); // newest, so the launcher selects it
        prof.addProperty("lastVersionId", VERSION_ID);
        prof.addProperty("icon", iconDataUri());
        prof.addProperty("gameDir", o.gameDir().toAbsolutePath().toString());
        // Low-pause G1 settings: a fixed-size heap (no resize hitches), short GC pauses, no forced full GCs.
        prof.addProperty("javaArgs", "-Xms" + o.memoryGb() + "G -Xmx" + o.memoryGb() + "G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC "
                + "-XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=37 -XX:+DisableExplicitGC -XX:G1NewSizePercent=20 "
                + "-XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=16M -XX:G1MixedGCCountTarget=4 "
                + "-XX:InitiatingHeapOccupancyPercent=15 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:MaxTenuringThreshold=1 "
                + "-XX:+PerfDisableSharedMem");
        profiles.add(PROFILE_KEY, prof);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String iconDataUri() throws IOException {
        try (InputStream in = InstallerCore.class.getResourceAsStream("/evade/icon-128.png")) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ http helpers

    private JsonElement getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + host(url));
        return JsonParser.parseString(res.body());
    }

    private void download(String url, Path target, String expectedHash, String algo,
                          double from, double to, Progress p) throws Exception {
        download(url, target, expectedHash, algo, from, to, p, null);
    }

    /** A download stops (instead of hanging forever) if no data arrives for this long. */
    static final long STALL_MS = 30_000;

    /**
     * Downloads to target (via a .part file), checking the hash. With a label, the status line shows live
     * progress ("label · 34 of 101 MB · 2.1 MB/s") so big downloads don't look frozen.
     */
    private void download(String url, Path target, String expectedHash, String algo,
                          double from, double to, Progress p, String label) throws Exception {
        HttpResponse<InputStream> res = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " downloading from " + host(url));
        long total = res.headers().firstValueAsLong("content-length").orElse(-1);
        MessageDigest md = MessageDigest.getInstance(algo);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        long start = System.nanoTime(), lastMsg = 0;
        try (InputStream in = stallGuard(res.body(), STALL_MS); var out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                long now = System.nanoTime();
                String msg = null;
                if (label != null && now - lastMsg > 250_000_000L) {
                    lastMsg = now;
                    double secs = Math.max(0.001, (now - start) / 1e9);
                    msg = String.format(Locale.ROOT, "%s \u00b7 %d%s MB \u00b7 %.1f MB/s", label, done >> 20,
                            total > 0 ? " of " + (total >> 20) : "", done / 1048576.0 / secs);
                }
                if (total > 0) p.update(from + (to - from) * done / (double) total, msg);
                else if (msg != null) p.update(from, msg);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("The download from " + host(url) + " stopped (" + e.getMessage() + "). Check your connection and try again.", e);
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (expectedHash != null && !expectedHash.isBlank() && !actual.equalsIgnoreCase(expectedHash)) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download check failed for " + target.getFileName() + " (file was corrupted or changed). Try again.");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Closes the stream if no bytes arrive for stallMs, so a dead connection fails instead of hanging forever. */
    static InputStream stallGuard(InputStream in, long stallMs) {
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        Thread watch = new Thread(() -> {
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                if (System.currentTimeMillis() - last.get() > stallMs) {
                    try { in.close(); } catch (Exception ignored) {}
                    return;
                }
            }
        }, "download-stall-watch");
        watch.setDaemon(true);
        watch.start();
        return new java.io.FilterInputStream(in) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                last.set(System.currentTimeMillis());
                return n;
            }
            @Override public void close() throws IOException {
                watch.interrupt();
                super.close();
            }
        };
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).GET().build();
    }

    private static void deleteMatching(Path dir, String prefix) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, prefix + "*.jar")) {
            for (Path f : ds) Files.deleteIfExists(f);
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String host(String url) { return URI.create(url).getHost(); }
}
