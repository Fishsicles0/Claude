package net.evadeclient.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Does the actual installation. The official Minecraft Launcher handles login, downloading vanilla 1.21.11 and
 * the Fabric libraries; we only add a version file, the mods, and a launcher profile.
 */
public final class InstallerCore {
    public static final String MC_VERSION = "1.21.11";
    /** Overridable with -Devade.site=... (tests point it at a local server). */
    public static final String SITE = System.getProperty("evade.site", "https://evadeclient.com");
    public static final String PROFILE_KEY = "evade-client", PROFILE_NAME = "Evade Client";
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
    /**
     * Resource pack features (on by default): mods that read the extra pack formats many resource packs use, so those
     * packs look as their artists made them. Connected and emissive textures, custom item textures, random and emissive
     * entity textures, custom entity models, and custom skies. All are client-side rendering only, and all come from
     * Modrinth at install time (LGPL, MIT and GPL; never hosted by us). Skyboxify brings YACL as a library.
     */
    private static final String[][] PACK_FEATURES = {
            {"continuity", "Continuity", "continuity"},
            {"cit-resewn-continuation", "CIT Resewn", "citresewn"},
            {"entitytexturefeatures", "Entity Texture Features", "entity_texture_features", "entitytexturefeatures"},
            {"entity-model-features", "Entity Model Features", "entity_model_features", "entitymodelfeatures"},
            {"skyboxify", "Skyboxify", "skyboxify"},
    };
    /**
     * Dynamic lights (off by default): held torches and glowing items light up what you can already see. Custom
     * Lambda License, so it's only ever downloaded from Modrinth when the player turns it on.
     */
    private static final String[] DYNAMIC_LIGHTS = {"lambdynamiclights", "LambDynamicLights", "lambdynamiclights"};
    /**
     * Every mod the installer adds besides Fabric API and Evade, with the filename prefixes of its jars. A jar belongs
     * to the entry with the longest matching prefix, so "sodium" never claims Sodium Extra's jar.
     */
    private static final List<String[]> KNOWN = new ArrayList<>(List.of(PERF));
    static {
        KNOWN.addAll(List.of(PACK_FEATURES));
        KNOWN.add(DYNAMIC_LIGHTS);
        KNOWN.add(new String[]{"iris", "Iris", "iris"});
        KNOWN.add(new String[]{"simple-voice-chat", "Simple Voice Chat", "voicechat"});
    }
    /** Fabric API: every mod's shared dependency, which we already install ourselves. */
    static final String FABRIC_API_ID = "P7dR8mSH";
    /** Sodium's Modrinth project id (Iris pins an exact Sodium build) and the shader pack the Shaders option adds. */
    private static final String SODIUM_ID = "AANobbMI", SHADER_PACK = "photon-shader", LIGHT_PACK = "complementary-reimagined";
    private static final String USER_AGENT = LauncherInfo.USER_AGENT;
    /** Groups of extra mods, recorded per file in the install marker. */
    private static final String PERF_GROUP = "perf", VOICE_GROUP = "voice", SHADERS_GROUP = "shaders",
            PACKS_GROUP = "packs", LIGHTS_GROUP = "lights";


    public interface Progress {
        void update(double fraction, String message);
    }

    // ------------------------------------------------------------------ memory

    private static long totalMemoryBytes() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** This PC's RAM in whole GB (rounded, so a 16 GB PC that reports 15.8 GB counts as 16), or 0 if Java can't tell. */
    public static int totalMemoryGb() {
        long b = totalMemoryBytes();
        return b <= 0 ? 0 : (int) Math.max(1, Math.round(b / (double) (1L << 30)));
    }

    /** Memory to give Minecraft on this PC: enough for Evade plus the FPS mods, without the long GC pauses
     *  that an oversized heap causes. 2 GB under 6 GB of RAM, 3 GB under 10, 4 GB under 20, else 6 GB. */
    public static int recommendedMemory() {
        long b = totalMemoryBytes();
        long gb = b > 0 ? b >> 30 : 8;
        return gb < 6 ? 2 : gb < 10 ? 3 : gb < 20 ? 4 : 6;
    }

    /** The most memory worth offering: everything but 2 GB for Windows and the rest (never under 2 GB). */
    public static int maxMemoryGb() {
        int total = totalMemoryGb();
        return total <= 0 ? Integer.MAX_VALUE : Math.max(2, total - 2);
    }

    /** The memory choices for this PC: the tier around the recommendation, without amounts the PC can't spare. */
    public static int[] memoryOptions() {
        int rec = recommendedMemory(), max = maxMemoryGb();
        int[] tier = rec == 3 ? new int[]{2, 3, 4, 6} : new int[]{2, 4, 6, 8};
        return java.util.Arrays.stream(tier).filter(v -> v <= max || v == rec).toArray();
    }

    /** A memory amount kept within what this PC can give. */
    public static int clampMemory(int gb) {
        return Math.max(1, Math.min(gb, maxMemoryGb()));
    }

    /**
     * Starting heap: the full amount (a fixed-size heap has no resize hitches), unless that would leave this PC under
     * 4 GB for everything else, where the game starts at half and grows only if it needs to.
     */
    static int initialHeapGb(int xmxGb) {
        int total = totalMemoryGb();
        return total > 0 && total - xmxGb < 4 ? Math.max(1, xmxGb / 2) : xmxGb;
    }

    /**
     * What to install where. {@code profileKey}/{@code profileName} name the launcher profile (the default instance
     * keeps the original "evade-client" profile); {@code javaDir} is the profile's Java override: null leaves the
     * profile's own setting alone, blank removes it, anything else sets it.
     */
    public record Options(Path minecraftDir, Path gameDir, int memoryGb, boolean copySettings, boolean voiceChat,
                          boolean performanceMods, boolean installClips, boolean shaders,
                          String profileKey, String profileName, String javaDir,
                          boolean packFeatures, boolean dynamicLights) {
        /** Resource pack features on and dynamic lights off: the defaults. */
        public Options(Path minecraftDir, Path gameDir, int memoryGb, boolean copySettings, boolean voiceChat,
                       boolean performanceMods, boolean installClips, boolean shaders,
                       String profileKey, String profileName, String javaDir) {
            this(minecraftDir, gameDir, memoryGb, copySettings, voiceChat, performanceMods, installClips, shaders,
                    profileKey, profileName, javaDir, true, false);
        }
        public Options(Path minecraftDir, Path gameDir, int memoryGb, boolean copySettings, boolean voiceChat,
                       boolean performanceMods, boolean installClips, boolean shaders) {
            this(minecraftDir, gameDir, memoryGb, copySettings, voiceChat, performanceMods, installClips, shaders,
                    PROFILE_KEY, PROFILE_NAME, null);
        }
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
                          ModFile iris, ModFile irisSodium, ModFile shaderPack,
                          java.util.List<ModFile> packFeatures, ModFile dynamicLights) {
        public Release(String evadeVersion, String evadeUrl, String evadeSha256, String loaderVersion,
                       String fabricApiVersion, String fabricApiUrl, String fabricApiSha512, String fabricApiFile,
                       ModFile voiceChat, java.util.List<ModFile> performance,
                       String clipsVersion, String clipsUrl, String clipsSha256, String ffmpegUrl, String ffmpegShaUrl,
                       ModFile iris, ModFile irisSodium, ModFile shaderPack) {
            this(evadeVersion, evadeUrl, evadeSha256, loaderVersion, fabricApiVersion, fabricApiUrl, fabricApiSha512, fabricApiFile,
                    voiceChat, performance, clipsVersion, clipsUrl, clipsSha256, ffmpegUrl, ffmpegShaUrl, iris, irisSodium, shaderPack,
                    java.util.List.of(), null);
        }
    }

    /** A mod file on Modrinth, with the project ids of the mods it needs. */
    public record ModFile(String name, String version, String url, String sha512, String filename,
                          String projectId, java.util.List<String> requires, java.util.Map<String, String> requiredVersions) {}

    public record Installed(String evadeVersion, String loaderVersion, String fabricApiVersion) {}

    /** An extra mod to install, and the option ("perf", "voice", "shaders") it comes from. */
    private record Extra(ModFile file, String group) {}


    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
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
        JsonObject o = readMarker(gameDir);
        return o == null ? null : new Installed(str(o, "evade"), str(o, "loader"), str(o, "fabricApi"));
    }

    /** The previous install's marker (evade-install.json), or null. */
    static JsonObject readMarker(Path gameDir) {
        try {
            Path f = gameDir.resolve("evade-install.json");
            if (!Files.exists(f)) return null;
            JsonElement e = JsonParser.parseString(Files.readString(f));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ release lookup

    /**
     * Looks up what to install. A failure names the source that couldn't be reached (evadeclient.com, Fabric or
     * Modrinth); the optional extras never fail the lookup, they're just left out.
     */
    public Release fetchRelease() throws IOException, InterruptedException {
        JsonObject site;
        try {
            site = getJson(SITE + "/version.json?t=" + System.currentTimeMillis()).getAsJsonObject(); // never a cached copy
        } catch (IOException | RuntimeException e) {
            throw new IOException("Couldn't reach " + host(SITE), e);
        }
        String evadeVersion = str(site, "version"), file0 = str(site, "file");
        if (evadeVersion == null || file0 == null) throw new IOException(host(SITE) + " sent an incomplete version list");
        String evadeSha = str(site, "sha256");
        String evadeUrl = SITE + "/" + file0 + bust(evadeSha);

        String loader = null;
        try {
            for (JsonElement e : getJson(FABRIC_META).getAsJsonArray()) {
                JsonObject l = e.getAsJsonObject().getAsJsonObject("loader");
                if (l.get("stable").getAsBoolean()) { loader = l.get("version").getAsString(); break; }
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Couldn't reach Fabric (meta.fabricmc.net)", e);
        }
        if (loader == null) throw new IOException("No stable Fabric Loader found for " + MC_VERSION);

        String query = "?game_versions=" + enc("[\"" + MC_VERSION + "\"]") + "&loaders=" + enc("[\"fabric\"]");
        JsonObject api = null;
        String apiVersion = null, apiUrl = null, apiSha = null, apiFile = null;
        try {
            JsonArray versions = getJson(MODRINTH + query).getAsJsonArray();
            for (JsonElement e : versions) {
                JsonObject v = e.getAsJsonObject();
                if ("release".equals(str(v, "version_type"))) { api = v; break; }
            }
            if (api == null && !versions.isEmpty()) api = versions.get(0).getAsJsonObject();
            if (api != null) {
                JsonObject file = null;
                for (JsonElement f : api.getAsJsonArray("files")) {
                    if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
                }
                if (file == null) file = api.getAsJsonArray("files").get(0).getAsJsonObject();
                apiVersion = str(api, "version_number");
                apiUrl = str(file, "url");
                apiSha = file.getAsJsonObject("hashes").get("sha512").getAsString();
                apiFile = str(file, "filename");
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("Couldn't reach Modrinth (Fabric API)", e);
        }
        if (api == null || apiUrl == null || apiFile == null) throw new IOException("No Fabric API release found for " + MC_VERSION);

        ModFile voice = null;
        try { voice = latestOnModrinth("Simple Voice Chat", SVC + query); } catch (Exception e) { /* optional: install without voice chat */ }

        java.util.List<ModFile> perf = modsWithLibraries(PERF, query);
        java.util.List<ModFile> packFeatures = modsWithLibraries(PACK_FEATURES, query);
        java.util.List<ModFile> lightsList = modsWithLibraries(new String[][]{DYNAMIC_LIGHTS}, query);
        // LambDynamicLights only needs Fabric API; anything more (a library we couldn't add) leaves it out.
        ModFile dynamicLights = lightsList.size() == 1 ? lightsList.get(0) : null;

        // Shaders (optional): Iris, the exact Sodium build it asks for, and the Photon shader pack (Complementary Reimagined is added beside it as a lighter choice).
        ModFile iris = null, irisSodium = null, pack = null;
        try { iris = latestOnModrinth("Iris", "https://api.modrinth.com/v2/project/iris/version" + query); } catch (Exception e) { /* no shaders */ }
        if (iris != null) {
            String sodiumVersion = iris.requiredVersions().get(SODIUM_ID);
            try {
                if (sodiumVersion != null) irisSodium = modFileFromVersion("Sodium", getJson("https://api.modrinth.com/v2/version/" + sodiumVersion).getAsJsonObject());
            } catch (Exception e) { /* fall back below */ }
            if (irisSodium == null) {
                // No exact build named (or it couldn't be fetched): the newest Sodium, which the FPS mods also use.
                for (ModFile f : perf) if (SODIUM_ID.equals(f.projectId())) irisSodium = f;
                if (irisSodium == null) {
                    try { irisSodium = latestOnModrinth("Sodium", "https://api.modrinth.com/v2/project/sodium/version" + query); } catch (Exception e) { /* below */ }
                }
            }
            // Iris can't run without Sodium: no Sodium, no shaders.
            if (irisSodium == null) iris = null;
        }
        if (iris != null) {
            try {
                pack = latestOnModrinth("Photon", "https://api.modrinth.com/v2/project/" + SHADER_PACK
                        + "/version?loaders=" + enc("[\"iris\"]"));
            } catch (Exception e) { /* Iris still installs; the pack is reported as skipped */ }
        }

        // Evade Clips: the Windows .exe on Windows (it finds Java itself), the jar everywhere else.
        String clipsVersion = null, clipsUrl = null, clipsSha = null, ffUrl = null, ffSha = null;
        if (site.has("clips") && site.get("clips").isJsonObject()) {
            JsonObject c = site.getAsJsonObject("clips");
            clipsVersion = str(c, "version");
            JsonObject pick = WINDOWS && c.has("windows") ? c.getAsJsonObject("windows") : c.getAsJsonObject("jar");
            if (pick != null && str(pick, "file") != null) { clipsSha = str(pick, "sha256"); clipsUrl = SITE + "/" + str(pick, "file") + bust(clipsSha); }
            if (WINDOWS && c.has("ffmpeg")) {
                JsonObject f = c.getAsJsonObject("ffmpeg");
                ffUrl = str(f, "url");
                ffSha = str(f, "sha256url");
            }
        }
        return new Release(evadeVersion, evadeUrl, evadeSha, loader, apiVersion, apiUrl, apiSha, apiFile, voice, perf,
                clipsVersion, clipsUrl, clipsSha, ffUrl, ffSha, iris, irisSodium, pack, packFeatures, dynamicLights);
    }

    /**
     * The newest build of each listed mod for this Minecraft version, plus the libraries they need (e.g. Cloth Config
     * for MoreCulling), resolved from Modrinth's dependency lists. A mod with no build is skipped, and so is one whose
     * library couldn't be found: Minecraft refuses to start when a required library is missing.
     */
    private java.util.List<ModFile> modsWithLibraries(String[][] list, String query) {
        java.util.List<ModFile> out = new java.util.ArrayList<>();
        for (String[] m : list) {
            try {
                ModFile f = latestOnModrinth(m[1], "https://api.modrinth.com/v2/project/" + m[0] + "/version" + query);
                if (f != null) out.add(f);
            } catch (Exception e) { /* optional: skip that one */ }
        }
        Set<String> have = new HashSet<>(), missing = new HashSet<>();
        for (ModFile f : out) have.add(f.projectId());
        for (int i = 0; i < out.size(); i++) {
            for (String dep : out.get(i).requires()) {
                if (dep == null || dep.equals(FABRIC_API_ID) || have.contains(dep) || missing.contains(dep)) continue;
                try {
                    String title = str(getJson("https://api.modrinth.com/v2/project/" + dep).getAsJsonObject(), "title");
                    ModFile lib = latestOnModrinth(title != null ? title : dep, "https://api.modrinth.com/v2/project/" + dep + "/version" + query);
                    if (lib != null) { out.add(lib); have.add(dep); } else missing.add(dep);
                } catch (Exception e) {
                    missing.add(dep);
                }
            }
        }
        for (boolean changed = true; changed; ) {
            Set<String> ids = new HashSet<>();
            for (ModFile f : out) ids.add(f.projectId());
            changed = out.removeIf(f -> f.requires().stream().anyMatch(d -> d != null && !d.equals(FABRIC_API_ID) && !ids.contains(d)));
        }
        return out;
    }

    /** Newest release for this Minecraft version (falling back to the newest beta), or null if there's none. */
    ModFile latestOnModrinth(String name, String url) throws IOException, InterruptedException {
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
    static ModFile modFileFromVersion(String name, JsonObject pick) {
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

    /** True when ffmpeg is already where the built-in Evade Clips looks (its data folder or the old separate app's). */
    static boolean ffmpegPresent() {
        String exe = WINDOWS ? "ffmpeg.exe" : "ffmpeg";
        for (Path d : new Path[]{clipsDataDir().resolve("ffmpeg").resolve("bin"), clipsDataDir().resolve("ffmpeg"),
                clipsAppDir().resolve("ffmpeg").resolve("bin"), clipsAppDir().resolve("ffmpeg")})
            if (Files.isRegularFile(d.resolve(exe))) return true;
        return false;
    }

    /**
     * Evade Clips is built into the launcher now, so installing it only means getting ffmpeg (Windows; it records
     * and edits the video) into Clips' data folder, where the app finds it. Skipped when it's already there. A failed
     * or stalled download is reported but harmless: Clips offers the download itself when it's opened.
     */
    void ensureFfmpeg(Release r, Progress p) throws Exception {
        if (!WINDOWS || r.ffmpegUrl() == null || ffmpegPresent()) return;
        Path bin = clipsDataDir().resolve("ffmpeg").resolve("bin");
        Files.createDirectories(bin);
        Path zip = bin.resolveSibling("ffmpeg-download.zip");
        try {
            p.update(0.9, "Downloading ffmpeg for Evade Clips (records and edits video)");
            String expected = null;
            if (r.ffmpegShaUrl() != null) {
                HttpResponse<String> sha = http.send(request(r.ffmpegShaUrl()), HttpResponse.BodyHandlers.ofString());
                if (sha.statusCode() == 200) expected = sha.body().trim().split("\\s+")[0];
            }
            download(r.ffmpegUrl(), zip, expected, "SHA-256", 0.9, 0.96, p, "Downloading ffmpeg");
            unzipFfmpeg(zip, bin);
        } finally {
            try { Files.deleteIfExists(zip); Files.deleteIfExists(zip.resolveSibling(zip.getFileName() + ".part")); } catch (Exception ignored) {}
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

    /**
     * Installs or updates Evade Client. Everything is downloaded and checked into a staging folder before the mods
     * folder is touched, so a failed download leaves the working install as it was.
     *
     * @return what was left out (extras that couldn't be downloaded or aren't available right now), for the player
     */
    public List<String> install(Options o, Release r, Progress p) throws Exception {
        Path mc = o.minecraftDir();
        Path game = o.gameDir();
        Path mods = game.resolve("mods");
        Files.createDirectories(mc);
        Files.createDirectories(mods);
        List<String> skipped = new ArrayList<>();
        JsonObject prev = readMarker(game);

        // 1. Fabric's version file for the launcher (fetched now, written once the mods are in place).
        p.update(0.05, "Setting up Fabric Loader " + r.loaderVersion());
        JsonObject profile;
        try {
            profile = getJson(FABRIC_META + "/" + r.loaderVersion() + "/profile/json").getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IOException("Couldn't reach Fabric (meta.fabricmc.net)", e);
        }
        profile.addProperty("id", VERSION_ID);

        Path staging = game.resolve(".evade-staging");
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            Path aside = staging.resolve("old");

            // 2. Fabric API and Evade Client: both downloaded and hash-checked first, then swapped in together.
            Path api = staging.resolve(safeName(r.fabricApiFile()));
            p.update(0.15, "Downloading Fabric API " + r.fabricApiVersion());
            download(r.fabricApiUrl(), api, r.fabricApiSha512(), "SHA-512", 0.15, 0.55, p);
            Path evade = staging.resolve(safeName("evade-client-" + r.evadeVersion() + ".jar"));
            p.update(0.55, "Downloading Evade Client v" + r.evadeVersion());
            download(r.evadeUrl(), evade, r.evadeSha256(), "SHA-256", 0.55, 0.75, p);
            swapIn(mods, aside, List.of(api, evade), n -> n.startsWith("evade-client") || n.startsWith("fabric-api"));
            Set<String> placed = new HashSet<>(List.of(lower(api.getFileName().toString()), lower(evade.getFileName().toString())));

            Path versionDir = mc.resolve("versions").resolve(VERSION_ID);
            Files.createDirectories(versionDir);
            writeAtomic(versionDir.resolve(VERSION_ID + ".json"), GSON.toJson(profile));
            // Some launchers expect a jar next to the json even though inheritsFrom supplies the real one.
            Path stub = versionDir.resolve(VERSION_ID + ".jar");
            if (!Files.exists(stub)) Files.write(stub, new byte[0]);

            // 3. Extras: FPS mods, shaders and voice chat (each optional; a failure never stops the install).
            JsonObject marker = installExtras(o, r, game, mods, staging, aside, prev, placed, skipped, p);

            // 4. Carry over the player's settings and server list the first time.
            if (o.copySettings()) {
                p.update(0.83, "Copying your settings");
                for (String f : new String[]{"options.txt", "servers.dat"}) {
                    Path src = mc.resolve(f), dst = game.resolve(f);
                    if (Files.exists(src) && !Files.exists(dst)) Files.copy(src, dst);
                }
            }

            // 5. Evade Clips is built into the launcher: it only needs ffmpeg (Windows). Optional: reported, not fatal.
            String clips = prev != null ? str(prev, "clips") : null;
            if (o.installClips()) {
                try {
                    ensureFfmpeg(r, p);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    System.err.println("ffmpeg: " + e.getMessage());
                    skipped.add("ffmpeg for Evade Clips (Clips downloads it when you open it)");
                }
            }

            marker.addProperty("evade", r.evadeVersion());
            marker.addProperty("loader", r.loaderVersion());
            marker.addProperty("fabricApi", r.fabricApiVersion());
            marker.addProperty("minecraft", MC_VERSION);
            marker.addProperty("installedAt", Instant.now().toString());
            if (clips != null) marker.addProperty("clips", clips);
            writeAtomic(game.resolve("evade-install.json"), GSON.toJson(marker));

            // 6. Launcher profile.
            p.update(0.97, "Adding the Evade Client profile");
            writeProfiles(o);
            // 7. Windows: run Minecraft's Java on the fast graphics card (laptops often default to the weak one).
            if (WINDOWS) preferHighPerformanceGpu(mc);
        } finally {
            try { deleteTree(staging); } catch (Exception ignored) {}
        }
        p.update(1.0, "Done");
        return skipped;
    }

    /**
     * FPS mods, shaders (Iris, its Sodium and the shader pack) and voice chat. All are downloaded first; then each one
     * that arrived replaces its old copy, one that didn't keeps its old copy, anything whose required mods are missing
     * is left out (Minecraft won't start otherwise), and extras the player turned off are removed. An option that's on
     * but can't be installed right now keeps what's already there.
     *
     * @return the install marker so far: which files were added, for which option and project, and the shader pack
     */
    private JsonObject installExtras(Options o, Release r, Path game, Path mods, Path staging, Path aside, JsonObject prev,
                                     Set<String> placed, List<String> skipped, Progress p) throws Exception {
        // What the last install added.
        Set<String> prevAdded = new HashSet<>();
        Map<String, String> prevGroup = new HashMap<>(), prevByProject = new HashMap<>();
        if (prev != null) {
            if (prev.has("addedFiles") && prev.get("addedFiles").isJsonArray()) {
                for (JsonElement f : prev.getAsJsonArray("addedFiles")) {
                    String n = f.isJsonPrimitive() ? f.getAsString() : null;
                    if (plainName(n) && lower(n).endsWith(".jar")) prevAdded.add(lower(n));
                }
            }
            if (prev.has("addedGroups") && prev.get("addedGroups").isJsonObject()) {
                for (var e : prev.getAsJsonObject("addedGroups").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) prevGroup.put(lower(e.getKey()), e.getValue().getAsString());
                }
            }
            if (prev.has("addedProjects") && prev.get("addedProjects").isJsonObject()) {
                for (var e : prev.getAsJsonObject("addedProjects").entrySet()) {
                    String n = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
                    if (plainName(n)) prevByProject.put(e.getKey(), n);
                }
            }
        }

        List<ModFile> perfList = r.performance() == null ? List.of() : r.performance();
        ModFile perfSodium = null;
        for (ModFile f : perfList) if (SODIUM_ID.equals(f.projectId())) perfSodium = f;
        boolean perfOn = o.performanceMods(), voiceOn = o.voiceChat(), shadersOn = o.shaders();
        boolean perfAvail = !perfList.isEmpty(), voiceAvail = r.voiceChat() != null;
        List<ModFile> packList = r.packFeatures() == null ? List.of() : r.packFeatures();
        boolean packsOn = o.packFeatures(), lightsOn = o.dynamicLights();
        boolean packsAvail = !packList.isEmpty(), lightsAvail = r.dynamicLights() != null;
        if (packsOn && !packsAvail) skipped.add("Resource pack features aren't available right now");
        if (lightsOn && !lightsAvail) skipped.add("Dynamic lights aren't available right now");
        ModFile irisSodium = r.irisSodium() != null ? r.irisSodium() : perfOn ? perfSodium : null;
        boolean shadersGo = shadersOn && r.iris() != null && irisSodium != null;
        if (perfOn && !perfAvail) skipped.add("FPS mods aren't available right now");
        if (voiceOn && !voiceAvail) skipped.add("Voice chat isn't available right now");
        if (shadersOn && r.iris() == null) skipped.add("Shaders aren't available right now");
        else if (shadersOn && irisSodium == null) skipped.add("Shaders skipped: Sodium could not be found");

        // Options that are on but can't be installed this time keep what's already there.
        Set<String> keepGroups = new HashSet<>();
        if (perfOn && !perfAvail) keepGroups.add(PERF_GROUP);
        if (voiceOn && !voiceAvail) keepGroups.add(VOICE_GROUP);
        if (shadersOn && !shadersGo) keepGroups.add(SHADERS_GROUP);
        if (packsOn && !packsAvail) keepGroups.add(PACKS_GROUP);
        if (lightsOn && !lightsAvail) keepGroups.add(LIGHTS_GROUP);

        List<Extra> wanted = new ArrayList<>();
        if (perfOn) for (ModFile f : perfList) wanted.add(new Extra(f, PERF_GROUP));
        if (shadersGo) {
            // Iris needs one exact Sodium build: it takes the place of the latest Sodium.
            wanted.removeIf(e -> SODIUM_ID.equals(e.file().projectId()));
            wanted.add(new Extra(irisSodium, SHADERS_GROUP));
            wanted.add(new Extra(r.iris(), SHADERS_GROUP));
        } else if (keepGroups.contains(SHADERS_GROUP) && anyJar(mods, "iris")) {
            // The installed Iris stays, so its Sodium does too (a newer Sodium could be one it doesn't support).
            wanted.removeIf(e -> SODIUM_ID.equals(e.file().projectId()));
        }
        if (voiceOn && voiceAvail) wanted.add(new Extra(r.voiceChat(), VOICE_GROUP));
        if (packsOn) for (ModFile f : packList) wanted.add(new Extra(f, PACKS_GROUP));
        if (lightsOn && lightsAvail) wanted.add(new Extra(r.dynamicLights(), LIGHTS_GROUP));
        Set<String> seen = new HashSet<>();
        wanted.removeIf(e -> e.file().projectId() != null && !seen.add(e.file().projectId()));
        Map<String, String> names = new HashMap<>();
        names.put(SODIUM_ID, "Sodium");
        for (Extra e : wanted) if (e.file().projectId() != null) names.put(e.file().projectId(), e.file().name());

        // A. Download everything into staging.
        ModFile pack = shadersGo ? r.shaderPack() : null;
        int total = wanted.size() + (pack != null ? 1 : 0), idx = 0;
        Map<Extra, Path> fetched = new LinkedHashMap<>();
        List<Extra> failed = new ArrayList<>();
        for (Extra e : wanted) {
            ModFile f = e.file();
            double a = 0.75 + 0.08 * idx / Math.max(1, total), b = 0.75 + 0.08 * (idx + 1) / Math.max(1, total);
            idx++;
            p.update(a, "Downloading " + f.name() + " " + f.version());
            try {
                Path dir = staging.resolve("extra-" + idx);
                Files.createDirectories(dir);
                Path s = dir.resolve(safeName(f.filename()));
                download(f.url(), s, f.sha512(), "SHA-512", a, b, p);
                fetched.put(e, s);
            } catch (InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                failed.add(e);
            }
        }

        // B. What failed keeps its old copy (an older version beats none). Then leave out anything whose required
        //    mods won't be there.
        Set<String> keep = new HashSet<>();
        Map<Extra, List<Path>> kept = new LinkedHashMap<>();
        for (Extra e : failed) {
            List<Path> old = oldCopies(mods, e.file(), placed, keep, prevByProject);
            kept.put(e, old);
            for (Path q : old) keep.add(lower(q.getFileName().toString()));
        }
        Set<String> present = new HashSet<>();
        present.add(FABRIC_API_ID);
        for (Extra e : fetched.keySet()) present.add(e.file().projectId());
        for (var en : kept.entrySet()) if (!en.getValue().isEmpty()) present.add(en.getKey().file().projectId());
        Set<Extra> dropped = new HashSet<>();
        Set<String> causes = new HashSet<>();
        for (boolean changed = true; changed; ) {
            changed = false;
            List<Extra> candidates = new ArrayList<>(fetched.keySet());
            for (var en : kept.entrySet()) if (!en.getValue().isEmpty() && !dropped.contains(en.getKey())) candidates.add(en.getKey());
            for (Extra e : candidates) {
                String need = null;
                for (String d : e.file().requires()) if (d != null && !present.contains(d)) { need = d; break; }
                if (need == null) continue;
                changed = true;
                dropped.add(e);
                present.remove(e.file().projectId());
                fetched.remove(e);
                List<Path> old = kept.remove(e);
                if (old != null) for (Path q : old) keep.remove(lower(q.getFileName().toString()));
                causes.add(need);
                skipped.add(e.file().name() + " skipped: it needs " + names.getOrDefault(need, "a mod") + ", which couldn't be downloaded");
            }
        }

        // C. Swap in what arrived.
        JsonArray added = new JsonArray();
        JsonObject groups = new JsonObject(), projects = new JsonObject();
        List<String> perfNames = new ArrayList<>(), packNames = new ArrayList<>();
        String voiceVersion = "", lightsVersion = "";
        boolean irisReady = false;
        Set<Extra> swapFailed = new HashSet<>();
        for (var en : fetched.entrySet()) {
            ModFile f = en.getKey().file();
            String name = en.getValue().getFileName().toString();
            try {
                swapIn(mods, aside, List.of(en.getValue()), n -> isOldCopy(n, f, placed, keep, prevByProject));
            } catch (IOException ex) {
                // Couldn't replace it (the swap put the old files back): keep what's there.
                List<Path> old = oldCopies(mods, f, placed, keep, prevByProject);
                kept.put(en.getKey(), old);
                swapFailed.add(en.getKey());
                for (Path q : old) keep.add(lower(q.getFileName().toString()));
                continue;
            }
            placed.add(lower(name));
            record(added, groups, projects, name, en.getKey().group(), f.projectId());
            if (en.getKey().group().equals(VOICE_GROUP)) voiceVersion = f.version();
            else if (en.getKey().group().equals(PERF_GROUP)) perfNames.add(f.name() + " " + f.version());
            else if (en.getKey().group().equals(PACKS_GROUP)) packNames.add(f.name() + " " + f.version());
            else if (en.getKey().group().equals(LIGHTS_GROUP)) lightsVersion = f.version();
            if (r.iris() != null && f.projectId() != null && f.projectId().equals(r.iris().projectId())) irisReady = true;
        }
        for (var en : kept.entrySet()) {
            ModFile f = en.getKey().file();
            List<Path> old = en.getValue();
            // A library whose absence already took a mod down was reported with that mod.
            boolean library = KNOWN.stream().noneMatch(k -> k[1].equals(f.name()));
            if (!(old.isEmpty() && library && causes.contains(f.projectId()))) {
                skipped.add(f.name() + (!old.isEmpty() ? " wasn't updated (kept the old one)"
                        : swapFailed.contains(en.getKey()) ? " couldn't be installed" : " couldn't be downloaded"));
            }
            for (Path q : old) record(added, groups, projects, q.getFileName().toString(), en.getKey().group(), f.projectId());
            if (!old.isEmpty() && en.getKey().group().equals(VOICE_GROUP) && prev != null && str(prev, "voiceChat") != null) voiceVersion = str(prev, "voiceChat");
            if (!old.isEmpty() && r.iris() != null && f.projectId() != null && f.projectId().equals(r.iris().projectId())) irisReady = true;
        }

        // D. Remove our extras that are no longer wanted (turned off, superseded, or left out above). The player's own
        //    mods are never touched.
        Map<String, String> projectOf = new HashMap<>();
        for (var e : prevByProject.entrySet()) projectOf.put(lower(e.getValue()), e.getKey());
        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path q : ds) leftovers.add(q);
        }
        for (Path q : leftovers) {
            String n = q.getFileName().toString(), l = lower(n);
            if (!l.endsWith(".jar") || !Files.isRegularFile(q) || placed.contains(l) || keep.contains(l)) continue;
            String[] owner = knownOwner(l);
            if (owner == null && !prevAdded.contains(l)) continue;
            String group = prevGroup.getOrDefault(l, groupOf(owner));
            boolean sodium = owner != null && owner[1].equals("Sodium");
            if (keepGroups.contains(group) || (sodium && (keepGroups.contains(PERF_GROUP) || keepGroups.contains(SHADERS_GROUP)))) {
                record(added, groups, projects, n, group, projectOf.get(l));
                if (group.equals(VOICE_GROUP) && prev != null && str(prev, "voiceChat") != null) voiceVersion = str(prev, "voiceChat");
                if (owner != null && owner[1].equals("Iris")) irisReady = true;
                continue;
            }
            try {
                Files.deleteIfExists(q);
            } catch (IOException ex) {
                skipped.add("An old copy of " + (owner != null ? owner[1] : n) + " couldn't be removed");
            }
        }

        // E. Shader pack: into shaderpacks/. The previous pack is replaced, and Iris follows it to the new version when
        //    it was still using ours (a pack the player picked themselves is left alone).
        Path packs = game.resolve("shaderpacks");
        String prevPack = prev != null ? str(prev, "shaderPackFile") : null;
        if (!plainName(prevPack)) prevPack = null;
        String prevPackVersion = prev != null && str(prev, "shaders") != null ? str(prev, "shaders") : "";
        if (prevPack == null && !prevPackVersion.isBlank() && r.shaderPack() != null && r.shaderPack().filename() != null) {
            prevPack = guessOldPack(packs, r.shaderPack().filename(), prevPackVersion);
        }
        boolean prevPackThere = prevPack != null && Files.isRegularFile(packs.resolve(prevPack));
        String packFile = null, packVersion = "", lightPack = null;
        if (shadersGo && irisReady && pack != null) {
            double a = 0.75 + 0.08 * idx / Math.max(1, total);
            p.update(a, "Downloading " + pack.name() + " " + pack.version());
            try {
                String name = safeName(pack.filename());
                Path dir = staging.resolve("pack");
                Files.createDirectories(dir);
                download(pack.url(), dir.resolve(name), pack.sha512(), "SHA-512", a, 0.83, p);
                Files.createDirectories(packs);
                move(dir.resolve(name), packs.resolve(name));
                packFile = name;
                packVersion = pack.version();
                if (prevPackThere && !prevPack.equalsIgnoreCase(name)) {
                    try { Files.deleteIfExists(packs.resolve(prevPack)); } catch (IOException ignored) { /* harmless extra pack */ }
                }
                pointIrisAt(game.resolve("config").resolve("iris.properties"), prevPack, name);
                lightPack = installLightPack(packs, staging, prev, p);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                skipped.add("The shader pack couldn't be downloaded");
                if (prevPackThere) { packFile = prevPack; packVersion = prevPackVersion; }
            }
        } else if (shadersOn) {
            // Shaders are on but couldn't all be set up this time: the pack that's there stays.
            if (shadersGo && irisReady && r.shaderPack() == null) skipped.add("The shader pack isn't available right now");
            if (prevPackThere) { packFile = prevPack; packVersion = prevPackVersion; }
        } else if (prevPackThere) {
            try { Files.deleteIfExists(packs.resolve(prevPack)); } catch (IOException ignored) { /* harmless extra pack */ }
        }
        String prevLight = prev != null ? str(prev, "lightPackFile") : null;
        if (lightPack == null && plainName(prevLight)) {
            // Kept while shaders stay on (a failed download this time keeps the old one); removed with shaders off.
            if (shadersOn) lightPack = Files.isRegularFile(packs.resolve(prevLight)) ? prevLight : null;
            else try { Files.deleteIfExists(packs.resolve(prevLight)); } catch (IOException ignored) { /* harmless */ }
        }

        JsonObject marker = new JsonObject();
        marker.addProperty("voiceChat", voiceVersion);
        marker.addProperty("performanceMods", String.join(", ", perfNames));
        marker.addProperty("packFeatures", String.join(", ", packNames));
        marker.addProperty("dynamicLights", lightsVersion);
        marker.addProperty("shaders", packVersion);
        if (packFile != null) marker.addProperty("shaderPackFile", packFile);
        if (lightPack != null) marker.addProperty("lightPackFile", lightPack);
        marker.add("addedFiles", added);
        marker.add("addedGroups", groups);
        marker.add("addedProjects", projects);
        return marker;
    }

    private static void record(JsonArray added, JsonObject groups, JsonObject projects, String file, String group, String projectId) {
        for (JsonElement e : added) if (e.getAsString().equalsIgnoreCase(file)) return;
        added.add(file);
        groups.addProperty(file, group);
        if (projectId != null && !projects.has(projectId)) projects.addProperty(projectId, file);
    }

    /** Jars in mods/ that are older copies of f (not ones placed or kept in this run). */
    private static List<Path> oldCopies(Path mods, ModFile f, Set<String> placed, Set<String> keep, Map<String, String> prevByProject) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path q : ds) if (Files.isRegularFile(q) && isOldCopy(q.getFileName().toString(), f, placed, keep, prevByProject)) out.add(q);
        }
        return out;
    }

    /**
     * Whether the jar called {@code name} is another copy of f: same file name, the file the last install added for f's
     * project, the same name stem before the version, or a known prefix of f's.
     */
    private static boolean isOldCopy(String name, ModFile f, Set<String> placed, Set<String> keep, Map<String, String> prevByProject) {
        String l = lower(name);
        if (!l.endsWith(".jar") || placed.contains(l) || keep.contains(l)) return false;
        if (f.filename() != null && l.equals(lower(f.filename()))) return true;
        String was = f.projectId() != null ? prevByProject.get(f.projectId()) : null;
        if (was != null && l.equals(lower(was))) return true;
        String st = f.filename() != null ? stem(f.filename()) : null;
        if (st != null && st.equals(stem(name))) return true;
        String[] owner = knownOwner(l);
        return owner != null && owner[1].equals(f.name());
    }

    /**
     * The part of a mod's file name before its version ("sodium-fabric-" for sodium-fabric-0.6.13+mc1.21.11.jar), used
     * to find older copies of the same mod; null when the name has no usable stem.
     */
    static String stem(String file) {
        String n = lower(file);
        if (n.endsWith(".jar")) n = n.substring(0, n.length() - 4);
        for (int i = 1; i < n.length(); i++) {
            char c = n.charAt(i), b = n.charAt(i - 1);
            if (Character.isDigit(c) && (b == '-' || b == '_' || b == '+' || b == ' ')) {
                String s = n.substring(0, i);
                return s.length() >= 4 && s.chars().filter(Character::isLetter).count() >= 3 ? s : null;
            }
        }
        return null;
    }

    /**
     * Which instance option adds the Modrinth project {@code slug} ("FPS mods", "Shaders" or "Voice chat"), or null
     * when it's not one the installer manages. Those are switched with the option, not installed by hand.
     */
    static String optionForSlug(String slug) {
        if (slug == null) return null;
        if (slug.equals("iris")) return "Shaders";
        if (slug.equals("simple-voice-chat")) return "Voice chat";
        for (String[] m : PERF) if (m[0].equals(slug)) return "FPS mods";
        for (String[] m : PACK_FEATURES) if (m[0].equals(slug)) return "Pack features";
        if (slug.equals(DYNAMIC_LIGHTS[0])) return "Dynamic lights";
        return null;
    }

    /** The option group a known mod (a {@link #KNOWN} entry, or null for a library) belongs to. */
    private static String groupOf(String[] owner) {
        if (owner == null) return PERF_GROUP;
        if (owner[1].equals("Iris")) return SHADERS_GROUP;
        if (owner[1].equals("Simple Voice Chat")) return VOICE_GROUP;
        if (owner == DYNAMIC_LIGHTS) return LIGHTS_GROUP;
        for (String[] m : PACK_FEATURES) if (m == owner) return PACKS_GROUP;
        return PERF_GROUP;
    }

    /** The option a jar in mods/ belongs to, from the install marker's record and the known names; null for the player's own. */
    static String optionForJar(JsonObject marker, String fileName) {
        String l = lower(fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName);
        if (l.startsWith("evade-client") || l.startsWith("fabric-api")) return "Evade";
        if (marker != null && marker.has("addedGroups") && marker.get("addedGroups").isJsonObject()) {
            for (var e : marker.getAsJsonObject("addedGroups").entrySet()) {
                if (lower(e.getKey()).equals(l) && e.getValue().isJsonPrimitive()) return groupLabel(e.getValue().getAsString());
            }
        }
        String[] owner = knownOwner(l);
        if (owner == null) return null;
        return groupLabel(groupOf(owner));
    }

    private static String groupLabel(String group) {
        return switch (group) {
            case SHADERS_GROUP -> "Shaders";
            case VOICE_GROUP -> "Voice chat";
            case PACKS_GROUP -> "Pack features";
            case LIGHTS_GROUP -> "Dynamic lights";
            default -> "FPS mods";
        };
    }

    /**
     * Why the instance in {@code gameDir} should be updated before playing, or null when it's current: not installed,
     * a newer Evade / Fabric Loader / Fabric API, or a newer version of an extra mod its options ask for.
     */
    static String updateReason(Path gameDir, Release r, boolean perf, boolean voice, boolean shaders) {
        return updateReason(gameDir, r, perf, voice, shaders, false, false);
    }

    static String updateReason(Path gameDir, Release r, boolean perf, boolean voice, boolean shaders, boolean packs, boolean lights) {
        JsonObject m = readMarker(gameDir);
        if (m == null) return "Evade Client isn't installed in this instance yet";
        if (r == null) return null;
        if (!r.evadeVersion().equals(str(m, "evade"))) return "Evade Client v" + r.evadeVersion() + " is out";
        if (!r.loaderVersion().equals(str(m, "loader"))) return "Fabric Loader " + r.loaderVersion() + " is out";
        if (!r.fabricApiVersion().equals(str(m, "fabricApi"))) return "Fabric API " + shortVersion(r.fabricApiVersion()) + " is out";
        if (voice && r.voiceChat() != null && !r.voiceChat().version().equals(str(m, "voiceChat"))) return "Simple Voice Chat " + r.voiceChat().version() + " is out";
        if (perf && r.performance() != null) {
            Set<String> have = new HashSet<>();
            String names = str(m, "performanceMods");
            if (names != null) for (String n : names.split(",\\s*")) have.add(n.trim());
            boolean irisSodium = shaders && r.iris() != null;
            for (ModFile f : r.performance()) {
                if (irisSodium && SODIUM_ID.equals(f.projectId())) continue; // shaders bring their own Sodium build
                if (!have.contains(f.name() + " " + f.version())) return f.name() + " " + f.version() + " is out";
            }
        }
        if (shaders && r.shaderPack() != null && !r.shaderPack().version().equals(str(m, "shaders"))) return r.shaderPack().name() + " " + r.shaderPack().version() + " is out";
        if (packs && r.packFeatures() != null && !r.packFeatures().isEmpty()) {
            Set<String> have = new HashSet<>();
            // A library both groups need is recorded once, under whichever group asked first.
            for (String key : new String[]{"packFeatures", "performanceMods"}) {
                String names = str(m, key);
                if (names != null) for (String n : names.split(",\\s*")) have.add(n.trim());
            }
            for (ModFile f : r.packFeatures()) if (!have.contains(f.name() + " " + f.version())) return f.name() + " " + f.version() + " is out";
        }
        if (lights && r.dynamicLights() != null && !r.dynamicLights().version().equals(str(m, "dynamicLights")))
            return r.dynamicLights().name() + " " + r.dynamicLights().version() + " is out";
        return null;
    }

    static String shortVersion(String v) {
        int plus = v == null ? -1 : v.indexOf('+');
        return plus > 0 ? v.substring(0, plus) : v;
    }

    /** The known extra mod (see {@link #KNOWN}) that a lowercase jar name belongs to, or null. */
    private static String[] knownOwner(String lowerName) {
        String[] best = null;
        int bestLen = -1;
        for (String[] m : KNOWN) {
            for (int i = 2; i < m.length; i++) {
                String px = lower(m[i]);
                if (lowerName.startsWith(px) && px.length() > bestLen) { best = m; bestLen = px.length(); }
            }
        }
        return best;
    }

    private static boolean anyJar(Path dir, String prefix) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path q : ds) {
                String[] owner = knownOwner(lower(q.getFileName().toString()));
                if (lower(q.getFileName().toString()).endsWith(".jar") && owner != null && owner[2].equals(prefix)) return true;
            }
        }
        return false;
    }

    /**
     * Installers before 1.4 didn't record the pack's file name, only its version: find that file by the new pack's
     * name prefix ("complementaryreimagined_") and the old version. Null unless exactly one file matches.
     */
    private static String guessOldPack(Path packs, String newName, String oldVersion) {
        String n = lower(newName), v = lower(oldVersion.trim());
        int cut = n.indexOf('_');
        if (cut <= 0 || v.isEmpty() || !Files.isDirectory(packs)) return null;
        String prefix = n.substring(0, cut + 1), found = null;
        // The version as a whole token: "r5.1" must not match "r5.11".
        java.util.regex.Pattern version = java.util.regex.Pattern.compile("(?<![0-9a-z])" + java.util.regex.Pattern.quote(v) + "(?![0-9a-z])");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(packs)) {
            for (Path q : ds) {
                String l = lower(q.getFileName().toString());
                if (!Files.isRegularFile(q) || l.equals(n) || !l.startsWith(prefix) || !version.matcher(l.substring(prefix.length())).find()) continue;
                if (found != null) return null;
                found = q.getFileName().toString();
            }
        } catch (IOException e) {
            return null;
        }
        return found;
    }

    /**
     * Points Iris at the new shader pack: on a first install (no iris.properties yet), or when Iris was still set to the
     * pack we installed last time. Only that one line changes; a pack the player picked themselves is left alone.
     */
    static void pointIrisAt(Path cfg, String prevPack, String pack) throws IOException {
        if (!Files.exists(cfg)) {
            Files.createDirectories(cfg.getParent());
            Files.writeString(cfg, "enableShaders=true\nshaderPack=" + propertyValue(pack) + "\n", StandardCharsets.ISO_8859_1);
            return;
        }
        if (prevPack == null || prevPack.equals(pack)) return;
        // Iris writes it with java.util.Properties: ISO-8859-1 with \\u escapes, so the bytes round-trip unchanged.
        List<String> lines = new ArrayList<>(Files.readAllLines(cfg, StandardCharsets.ISO_8859_1));
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).stripLeading();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            Properties one = new Properties();
            try { one.load(new StringReader(lines.get(i))); } catch (IllegalArgumentException e) { continue; }
            if (prevPack.equals(one.getProperty("shaderPack"))) {
                lines.set(i, "shaderPack=" + propertyValue(pack));
                changed = true;
            }
        }
        if (changed) writeAtomic(cfg, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.ISO_8859_1);
    }

    /** A value escaped the way java.util.Properties writes it. */
    static String propertyValue(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '=', ':', '#', '!' -> b.append('\\').append(c);
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\f' -> b.append("\\f");
                case ' ' -> b.append(i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) b.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /**
     * Adds (or refreshes) the Evade Client profile. An existing profile is updated in place: only the fields the
     * installer owns change, so the player's own Java, resolution and JVM arguments survive a reinstall. In existing JVM
     * arguments only the -Xms/-Xmx values are replaced; the default G1 flags are written only for a new profile.
     */
    /**
     * Adds or refreshes the instance's profile in the launcher's profile list (and in the Microsoft Store launcher's
     * list when there is one), marking it the most recently used so the Minecraft Launcher selects it.
     */
    public static void writeProfiles(Options o) throws IOException {
        Path mc = o.minecraftDir();
        addProfile(mc.resolve("launcher_profiles.json"), true, o);
        Path store = mc.resolve("launcher_profiles_microsoft_store.json");
        if (Files.exists(store)) addProfile(store, false, o);
    }

    /** Removes a profile from both of the launcher's profile lists (a missing one is fine). */
    public static void removeProfiles(Path mc, String key) throws IOException {
        for (Path file : List.of(mc.resolve("launcher_profiles.json"), mc.resolve("launcher_profiles_microsoft_store.json"))) {
            if (!Files.exists(file)) continue;
            JsonObject root;
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                if (!parsed.isJsonObject()) continue;
                root = parsed.getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (!root.has("profiles") || !root.get("profiles").isJsonObject() || !root.getAsJsonObject("profiles").has(key)) continue;
            root.getAsJsonObject("profiles").remove(key);
            writeAtomic(file, GSON.toJson(root));
        }
    }

    /** A profile from the launcher's profile list, or null. */
    public static JsonObject readProfile(Path mc, String key) {
        JsonObject root = readJsonObject(mc.resolve("launcher_profiles.json"));
        JsonElement p = root.has("profiles") && root.get("profiles").isJsonObject() ? root.getAsJsonObject("profiles").get(key) : null;
        return p != null && p.isJsonObject() ? p.getAsJsonObject() : null;
    }

    private static void addProfile(Path file, boolean createIfMissing, Options o) throws IOException {
        JsonObject root;
        if (Files.exists(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                if (!parsed.isJsonObject()) throw new IOException("not a JSON object");
                root = parsed.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                throw new IOException("Your Minecraft Launcher's profile list (" + file.getFileName() + ") couldn't be read. "
                        + "Open the Minecraft Launcher once, close it, and try again.", e);
            }
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
        JsonElement existing = profiles.get(o.profileKey());
        JsonObject prof = existing != null && existing.isJsonObject() ? existing.getAsJsonObject().deepCopy() : new JsonObject();
        prof.addProperty("name", o.profileName());
        prof.addProperty("type", "custom");
        if (!prof.has("created")) prof.addProperty("created", now);
        prof.addProperty("lastUsed", now); // newest, so the launcher selects it
        prof.addProperty("lastVersionId", VERSION_ID);
        prof.addProperty("icon", iconDataUri());
        prof.addProperty("gameDir", o.gameDir().toAbsolutePath().toString());
        prof.addProperty("javaArgs", javaArgs(str(prof, "javaArgs"), o.memoryGb()));
        if (o.javaDir() != null) {
            if (o.javaDir().isBlank()) prof.remove("javaDir");
            else prof.addProperty("javaDir", o.javaDir());
        }
        profiles.add(o.profileKey(), prof);

        writeAtomic(file, GSON.toJson(root));
    }

    /**
     * Sets Windows' per-app graphics preference to "High performance" for every Java the Minecraft Launcher ships
     * (Settings > System > Display > Graphics shows the same choice). On a laptop with two GPUs this moves the game
     * off the integrated one; with a single GPU it changes nothing. Only the current user's registry is touched, and a
     * choice the player already made for that file is kept. Never fails the install.
     */
    static void preferHighPerformanceGpu(Path minecraftDir) {
        try {
            java.util.List<Path> javas = new java.util.ArrayList<>();
            java.util.List<Path> roots = new java.util.ArrayList<>(java.util.List.of(minecraftDir.resolve("runtime")));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                roots.add(Paths.get(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"));
            }
            for (Path root : roots) {
                if (!Files.isDirectory(root)) continue;
                try (var walk = Files.walk(root, 7)) {
                    walk.filter(f -> {
                        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        return (n.equals("javaw.exe") || n.equals("java.exe")) && f.getParent() != null
                                && f.getParent().getFileName().toString().equalsIgnoreCase("bin");
                    }).forEach(javas::add);
                }
            }
            String key = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
            for (Path java : javas) {
                String path = java.toAbsolutePath().toString();
                Process q = new ProcessBuilder("reg", "query", key, "/v", path).redirectErrorStream(true).start();
                q.getInputStream().readAllBytes();
                if (q.waitFor() == 0) continue; // already set, by the player or a previous install
                Process add = new ProcessBuilder("reg", "add", key, "/v", path, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f")
                        .redirectErrorStream(true).start();
                add.getInputStream().readAllBytes();
                add.waitFor();
            }
        } catch (Exception e) {
            // A preference, not a requirement: the game runs either way.
        }
    }

    /**
     * The lighter second pack (Complementary Reimagined) next to Photon, for laptops and older graphics: Evade's
     * Shaders setting switches between them. Downloaded from Modrinth, never bundled. Null if it couldn't be added;
     * that never fails the install.
     */
    private String installLightPack(Path packs, Path staging, JsonObject prev, Progress p) {
        try {
            ModFile light = latestOnModrinth("Complementary Reimagined", "https://api.modrinth.com/v2/project/" + LIGHT_PACK
                    + "/version?loaders=" + enc("[\"iris\"]"));
            if (light == null || light.filename() == null) return null;
            String name = safeName(light.filename());
            String prevLight = prev != null ? str(prev, "lightPackFile") : null;
            if (name.equalsIgnoreCase(prevLight) && Files.isRegularFile(packs.resolve(name))) return name;
            Path dir = staging.resolve("lightpack");
            Files.createDirectories(dir);
            p.update(0.83, "Downloading " + light.name() + " " + light.version());
            download(light.url(), dir.resolve(name), light.sha512(), "SHA-512", 0.83, 0.85, p);
            move(dir.resolve(name), packs.resolve(name));
            if (plainName(prevLight) && !prevLight.equalsIgnoreCase(name)) {
                try { Files.deleteIfExists(packs.resolve(prevLight)); } catch (IOException ignored) { /* harmless */ }
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Windows: closes a running Evade Clips so its file can be replaced. Asks politely first (like closing the
     * window), then ends it and the Java it started. Waits until the file is free, at most about 8 seconds.
     */
    static void closeRunningClips(Path exe) {
        String image = exe.getFileName().toString();
        // Evade-Clips.exe only starts Java (javaw.exe) and exits; that Java keeps the .exe open. So match every
        // process whose command line or program path mentions the file, not just the image name.
        String needle = exe.toAbsolutePath().toString().replace("'", "''");
        String ps = "$n='" + needle + "'; Get-CimInstance Win32_Process | Where-Object { "
                + "($_.CommandLine -and $_.CommandLine.IndexOf($n, [StringComparison]::OrdinalIgnoreCase) -ge 0) -or "
                + "($_.ExecutablePath -and $_.ExecutablePath -ieq $n) } | ForEach-Object { %s }";
        try {
            run("taskkill", "/T", "/IM", image);
            powershell(String.format(ps, "$null = $_.CimInstance; try { (Get-Process -Id $_.ProcessId).CloseMainWindow() | Out-Null } catch {}"));
            for (int i = 0; i < 10 && locked(exe); i++) Thread.sleep(300);
            if (locked(exe)) {
                run("taskkill", "/F", "/T", "/IM", image);
                powershell(String.format(ps, "Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue"));
            }
            for (int i = 0; i < 17 && locked(exe); i++) Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Not fatal: the caller falls back to updating it on the next start.
        }
    }

    private static void powershell(String script) throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
    }

    private static void run(String... cmd) throws Exception {
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        pr.getInputStream().readAllBytes();
        pr.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** True while another program holds {@code file} open (Windows refuses to open it for writing). */
    private static boolean locked(Path file) {
        if (!Files.exists(file)) return false;
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** A query naming the file's checksum, so no cache can answer with an older build of it. */
    private static String bust(String sha) {
        return sha == null || sha.length() < 10 ? "?t=" + System.currentTimeMillis() : "?v=" + sha.substring(0, 10);
    }

    /** Low-pause G1 settings: short GC pauses, no forced full GCs. */
    private static final String G1_FLAGS = "-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC "
            + "-XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=37 -XX:+DisableExplicitGC -XX:G1NewSizePercent=20 "
            + "-XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=16M -XX:G1MixedGCCountTarget=4 "
            + "-XX:InitiatingHeapOccupancyPercent=15 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:MaxTenuringThreshold=1 "
            + "-XX:+PerfDisableSharedMem";

    /** The profile's JVM arguments for this memory choice, keeping everything else the player set. */
    static String javaArgs(String existing, int memoryGb) {
        String xmx = "-Xmx" + memoryGb + "G", xms = "-Xms" + initialHeapGb(memoryGb) + "G";
        if (existing == null || existing.isBlank()) return xms + " " + xmx + " " + G1_FLAGS;
        String args = existing.trim();
        java.util.regex.Pattern mx = java.util.regex.Pattern.compile("(?<!\\S)-Xmx\\S*"), ms = java.util.regex.Pattern.compile("(?<!\\S)-Xms\\S*");
        args = mx.matcher(args).find() ? mx.matcher(args).replaceAll(java.util.regex.Matcher.quoteReplacement(xmx)) : xmx + " " + args;
        args = ms.matcher(args).replaceAll(java.util.regex.Matcher.quoteReplacement(xms));
        return args;
    }

    private static String iconDataUri() throws IOException {
        try (InputStream in = InstallerCore.class.getResourceAsStream("/evade/icon-128.png")) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ file helpers

    /**
     * Moves staged files into dir, replacing the old files {@code isOld} picks out (given lowercase names) and any file
     * with a staged file's name. Old files are moved aside first and put back if anything fails, so the folder is never
     * left half-replaced (Minecraft refuses to start with two copies of a mod, or with none of a needed one).
     */
    private static void swapIn(Path dir, Path aside, List<Path> staged, Predicate<String> isOld) throws IOException {
        Set<String> stagedNames = new HashSet<>();
        for (Path s : staged) stagedNames.add(lower(s.getFileName().toString()));
        List<Path> old = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) {
                String l = lower(f.getFileName().toString());
                if (Files.isRegularFile(f) && (stagedNames.contains(l) || (l.endsWith(".jar") && isOld.test(l)))) old.add(f);
            }
        }
        List<Path[]> movedAside = new ArrayList<>();
        List<Path> movedIn = new ArrayList<>();
        try {
            Files.createDirectories(aside);
            for (Path f : old) {
                Path to = aside.resolve(f.getFileName().toString());
                for (int i = 1; Files.exists(to); i++) to = aside.resolve(f.getFileName() + "." + i);
                move(f, to);
                movedAside.add(new Path[]{f, to});
            }
            for (Path s : staged) {
                Path t = dir.resolve(s.getFileName().toString());
                move(s, t);
                movedIn.add(t);
            }
        } catch (IOException | RuntimeException e) {
            for (Path t : movedIn) try { Files.deleteIfExists(t); } catch (IOException ignored) {}
            for (int i = movedAside.size() - 1; i >= 0; i--) {
                try { move(movedAside.get(i)[1], movedAside.get(i)[0]); } catch (IOException ignored) {}
            }
            throw e;
        }
        for (Path[] m : movedAside) try { Files.deleteIfExists(m[1]); } catch (IOException ignored) {}
    }

    /** Renames a file into place, atomically where the file system can. */
    static void move(Path from, Path to) throws IOException {
        // On Windows, antivirus (Defender) briefly locks a program that was just written while it scans it, so a
        // move right after a download can fail for a second or two. Retry for a while before giving up.
        long deadline = System.currentTimeMillis() + (WINDOWS ? 8000 : 0);
        while (true) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                if (e instanceof java.nio.file.NoSuchFileException || System.currentTimeMillis() >= deadline) throw e;
                try { Thread.sleep(400); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    static void writeAtomic(Path file, String content) throws IOException {
        writeAtomic(file, content, StandardCharsets.UTF_8);
    }

    static void writeAtomic(Path file, byte[] content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content);
        move(tmp, file);
    }

    private static void writeAtomic(Path file, String content, java.nio.charset.Charset cs) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, cs);
        move(tmp, file);
    }

    static JsonObject readJsonObject(Path f) {
        try {
            if (Files.exists(f)) {
                JsonElement e = JsonParser.parseString(Files.readString(f));
                if (e.isJsonObject()) return e.getAsJsonObject();
            }
        } catch (Exception ignored) {}
        return new JsonObject();
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    /** A bare file name that stays inside the folder it's put in (no paths, drive letters or streams). */
    static boolean plainName(String n) {
        return n != null && !n.isBlank() && !n.contains("/") && !n.contains("\\") && !n.contains(":")
                && !n.equals(".") && !n.equals("..");
    }

    private static String safeName(String n) throws IOException {
        if (!plainName(n)) throw new IOException("The download server sent an unexpected file name: " + n);
        return n;
    }

    private static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    // ------------------------------------------------------------------ http helpers

    /** A page as text (HTTP 200 only). */
    String getText(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + host(url));
        return res.body();
    }

    JsonElement getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + host(url));
        return JsonParser.parseString(res.body());
    }

    void download(String url, Path target, String expectedHash, String algo,
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
        if (res.statusCode() != 200) {
            try { res.body().close(); } catch (IOException ignored) {}
            throw new IOException("Couldn't download " + target.getFileName() + " from " + host(url) + " (HTTP " + res.statusCode()
                    + "). Try again in a few minutes.");
        }
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
                    msg = String.format(Locale.ROOT, "%s · %d%s MB · %.1f MB/s", label, done >> 20,
                            total > 0 ? " of " + (total >> 20) : "", done / 1048576.0 / secs);
                }
                if (total > 0) p.update(from + (to - from) * Math.min(1.0, done / (double) total), msg);
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
        move(tmp, target);
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

    static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).GET().build();
    }

    static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null; }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null ? h : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
