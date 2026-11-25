package net.evadeclient.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Add-ons: well-known mods tested next to Evade, one click each on the Mods page's Add-ons tab. Like everything else,
 * every file comes from Modrinth when the player installs it (never hosted or bundled by Evade, whatever its licence),
 * is SHA-512 checked, and brings the libraries Modrinth lists for it. An add-on only installs when Modrinth has a
 * Fabric 1.21.11 build of it; otherwise the tab says it isn't available yet instead of reaching for another version.
 * <p>
 * Checked on api.modrinth.com for Fabric 1.21.11 (2026-11-25), each jar's fabric.mod.json read for what it needs
 * and breaks:
 * <ul>
 *   <li>Distant Horizons 3.3.3 (LGPL-3.0). Carries the Fabric API parts it uses. Iris loads it; only shader packs
 *       made for it draw the far terrain.</li>
 *   <li>Flashback 0.39.10 (All Rights Reserved: only ever downloaded from Modrinth). Needs Fabric API; breaks
 *       Simple Voice Chat's API below 2.6.24 (Evade's is newer). Same job as Evade's Replay.</li>
 *   <li>Nvidium 0.4.3-beta13 (LGPL-3.0, beta only). NVIDIA mesh shaders (GTX 16-series / RTX and newer). Its
 *       fabric.mod.json accepts only Sodium 0.8.11 or 0.8.12, while Evade's FPS mods and Iris use Sodium 0.8.14, so
 *       {@link #check} holds it back until a build for the instance's Sodium is out. Idle while a shader pack is on.</li>
 *   <li>SkyHanni 7.42.0 (LGPL-2.1, published as betas; the only "release" for 1.21.11 is months older). Brings
 *       Fabric Language Kotlin (Apache-2.0) and the Hypixel Mod API (MIT); needs Fabric API 0.141.5 or newer.</li>
 *   <li>Litematica 0.26.16 and MiniHUD 0.38.16 (LGPL-3.0). Bring MaLiLib 0.27.20 (LGPL-3.0), which breaks Sodium
 *       below 0.8.7 and Iris below 1.10.7 (Evade's are newer).</li>
 *   <li>Axiom 6.1.5 (All Rights Reserved: only ever downloaded from Modrinth). Needs Fabric API. Its editor works
 *       in singleplayer and on servers that run the Axiom plugin.</li>
 * </ul>
 * Not offered: Coleweight (a Forge 1.8.9 mod, no Fabric build), and Tweakeroo / Item Scroller (free camera,
 * auto-clicking and inventory-moving tweaks many servers don't allow). Players can still add any of the Modrinth ones
 * themselves from Browse.
 */
final class Addons {
    private Addons() {}

    /**
     * One add-on. {@code newest}: take the newest build even when it's a beta (the project only publishes betas);
     * {@code nvidiaOnly}: needs an NVIDIA card with mesh shaders; {@code evadeModule}: the Evade module that does the
     * same job (the Mod check offers to switch it off); {@code tested}: what was checked next to Evade, in plain words.
     */
    record Addon(String key, String slug, String projectId, String name, String category, String what, String licence,
                 boolean newest, boolean nvidiaOnly, String evadeModule, List<String> tested) {
        /** The Modrinth page, for credit and the "Modrinth page" button. */
        String url() { return "https://modrinth.com/mod/" + slug; }
        /** True for a licence that doesn't allow sharing copies (downloaded from Modrinth only, as always). */
        boolean closed() { return !ModChecks.redistributable(licence); }
    }

    static final Addon DISTANT_HORIZONS = new Addon("distanthorizons", "distanthorizons", "uCdwusMi", "Distant Horizons", "Render distance",
            "See terrain far past your render distance, drawn in lower detail further out.", "LGPL-3.0",
            false, false, null, List.of(
                    "Loads with Sodium, Evade's FPS mods and Evade's own modules; it carries the Fabric API parts it needs.",
                    "With Shaders on, the far terrain only shows with packs made for it (Complementary, Photon). Evade Shaders doesn't support it yet.",
                    "Client-side: it builds the far view from chunks you've seen, so it works on any server."));
    static final Addon FLASHBACK = new Addon("flashback", "flashback", "4das1Fjq", "Flashback", "Replays & video",
            "Record replays, then make cinematic videos with keyframes and camera paths.", "All Rights Reserved",
            false, false, "Replay", List.of(
                    "Needs Fabric API, which every Evade instance has.",
                    "Evade has its own Replay. Keep one recording: the Mod check offers to switch Evade's Replay off in this instance.",
                    "Its keys show on Evade's Keybinds page, and Evade warns once if one lands on an Evade key.",
                    "Works with Simple Voice Chat 2.6.24 or newer (Evade installs a newer one)."));
    static final Addon NVIDIUM = new Addon("nvidium", "nvidium", "SfMw2IZN", "Nvidium", "Render distance",
            "Draws terrain with NVIDIA mesh shaders: far more FPS at long render distances.", "LGPL-3.0",
            true, true, null, List.of(
                    "Only for NVIDIA GTX 16-series, RTX or newer cards: the launcher checks this PC's graphics first.",
                    "Needs the exact Sodium build it was made for. The launcher compares it with this instance's Sodium before installing, and the Mod check flags a mismatch.",
                    "Switches itself off while a shader pack is on, so turn Shaders off to use it."));
    static final Addon SKYHANNI = new Addon("skyhanni", "skyhanni", "byNkmv5G", "SkyHanni", "Hypixel SkyBlock",
            "Hypixel SkyBlock helpers: timers, reminders, chat cleanup and lots of info panels.", "LGPL-2.1",
            true, false, null, List.of(
                    "Brings Fabric Language Kotlin and the Hypixel Mod API from Modrinth; needs Fabric API 0.141.5 or newer (Evade's is).",
                    "Installs its newest build: SkyHanni publishes its 1.21.11 updates as betas.",
                    "Evade's Hypixel tools stay on. If you use SkyHanni's own scoreboard or tab list, switch off Evade's Scoreboard or Tab Tweaks so only one restyles it."));
    static final Addon LITEMATICA = new Addon("litematica", "litematica", "bEpr0Arc", "Litematica", "Building",
            "Schematics: load a build as a see-through guide and place it block by block.", "LGPL-3.0",
            false, false, null, List.of(
                    "Brings MaLiLib from Modrinth (0.27.19 or newer, checked). MaLiLib needs Sodium 0.8.7 and Iris 1.10.7 or newer: Evade's are newer.",
                    "Uses Left Alt with its selection tool, like Evade's Freelook: Evade warns once, and either can be rebound.",
                    "Its Easy Place option, which some servers don't allow, is off unless you turn it on."));
    static final Addon MINIHUD = new Addon("minihud", "minihud", "UMxybHE8", "MiniHUD", "Building",
            "Info lines (light level, chunk, biome and more) and overlays like spawnable spots.", "LGPL-3.0",
            false, false, null, List.of(
                    "Brings MaLiLib from Modrinth, shared with Litematica (one copy).",
                    "Hidden until you press H; H + C opens its settings. No Evade key uses H.",
                    "Some info lines repeat Evade's HUD widgets (Coordinates, FPS): hide whichever you don't need."));
    static final Addon AXIOM = new Addon("axiom", "axiom", "N6n5dqoA", "Axiom", "Building",
            "A full building editor: tools, brushes, selections and an editor screen.", "All Rights Reserved",
            false, false, null, List.of(
                    "Needs Fabric API only, and loads with Sodium, Iris and Evade's modules.",
                    "Its editor works in singleplayer and on servers that run the Axiom server plugin; on other servers it stays off.",
                    "Downloaded from Modrinth when you install it, never hosted by Evade."));

    static final Addon[] ALL = {DISTANT_HORIZONS, NVIDIUM, FLASHBACK, SKYHANNI, LITEMATICA, MINIHUD, AXIOM};

    /** Asked for, but not offered: {name, Modrinth page or null, why}. */
    static final String[][] NOT_OFFERED = {
            {"Coleweight", null, "A Forge 1.8.9 mod: it can't run on Fabric 1.21.11, and there's no Fabric version."},
            {"Tweakeroo", "https://modrinth.com/mod/tweakeroo", "Has free camera and auto-clicking tweaks many servers don't allow. You can still add it from Browse Modrinth."},
            {"Item Scroller", "https://modrinth.com/mod/item-scroller", "Moves items for you, which many servers don't allow. You can still add it from Browse Modrinth."},
    };

    static Addon byKey(String key) {
        for (Addon a : ALL) if (a.key().equals(key)) return a;
        return null;
    }

    static Addon byProject(String projectId) {
        for (Addon a : ALL) if (a.projectId().equals(projectId)) return a;
        return null;
    }

    // ------------------------------------------------------------------ graphics

    /** What this PC's graphics mean for an NVIDIA-only add-on. */
    enum Gpu { OK, OLD, NONE, UNKNOWN }

    private static final Pattern NVIDIA = Pattern.compile("(?i)(nvidia|geforce|quadro|\\bgtx\\b|\\brtx\\b|titan)");
    /** Turing (2018) and newer have mesh shaders: RTX, GTX 16xx, Quadro RTX / T-series, the RTX A / Ada pro cards. */
    private static final Pattern MESH = Pattern.compile("(?i)(\\brtx\\b|gtx\\s*16\\d\\d|quadro\\s*(rtx|t\\d{3,4})|\\bt\\d{3,4}\\b|titan\\s*rtx|\\b[ahl]\\d{1,3}0\\b)");

    /** OK when one of the graphics is an NVIDIA card with mesh shaders; UNKNOWN when the system didn't say. */
    static Gpu nvidia(List<String> gpus) {
        if (gpus == null || gpus.isEmpty()) return Gpu.UNKNOWN;
        boolean any = false;
        for (String g : gpus) {
            if (g == null || !NVIDIA.matcher(g).find()) continue;
            any = true;
            if (MESH.matcher(g).find()) return Gpu.OK;
        }
        return any ? Gpu.OLD : Gpu.NONE;
    }

    // ------------------------------------------------------------------ availability

    enum State { AVAILABLE, NOT_FOR_VERSION, NEEDS_NVIDIA, OLD_NVIDIA, GPU_UNKNOWN, NEEDS_FPS_MODS, SODIUM_MISMATCH, OFFLINE }

    /** Whether an add-on can go into an instance now, the version it would get, and a line saying so. */
    record Status(State state, String version, String line) {
        boolean installable() { return state == State.AVAILABLE; }
    }

    /**
     * The decision, from what Modrinth and this PC said (pure: tested with made-up answers).
     *
     * @param version     the build that would be installed (null: none for Fabric 1.21.11)
     * @param gpu         this PC's graphics (only read for NVIDIA-only add-ons)
     * @param wantsSodium the Sodium build that build pins (its plain number), or null when it pins none
     * @param hasSodium   the Sodium this instance has or will get (plain number), or null when it has none
     */
    static Status decide(Addon a, String version, Gpu gpu, List<String> gpus, String wantsSodium, String hasSodium) {
        if (version == null) return new Status(State.NOT_FOR_VERSION, null, "Not available for " + InstallerCore.MC_VERSION + " yet");
        if (a.nvidiaOnly()) {
            String have = gpus == null || gpus.isEmpty() ? "" : String.join(", ", gpus);
            switch (gpu) {
                case UNKNOWN -> { return new Status(State.GPU_UNKNOWN, version, "Couldn't read this PC's graphics card, so it stays off (it needs NVIDIA)"); }
                case NONE -> { return new Status(State.NEEDS_NVIDIA, version, "Needs an NVIDIA card (GTX 16-series, RTX or newer). This PC has " + have); }
                case OLD -> { return new Status(State.OLD_NVIDIA, version, "Needs a GTX 16-series, RTX or newer card for mesh shaders. This PC has " + have); }
                default -> { }
            }
        }
        if (wantsSodium != null) {
            if (hasSodium == null) return new Status(State.NEEDS_FPS_MODS, version, "Needs Sodium: switch on FPS mods for this instance in Instances");
            if (!wantsSodium.equals(hasSodium))
                return new Status(State.SODIUM_MISMATCH, version, "Not available for this Sodium yet: " + a.name() + " " + version
                        + " needs Sodium " + wantsSodium + ", Evade uses " + hasSodium);
        }
        return new Status(State.AVAILABLE, version, "Version " + version + " for Fabric " + InstallerCore.MC_VERSION);
    }

    private static final Pattern PLAIN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:-(?:alpha|beta|rc|pre)[.\\d]*)?)");

    /** "mc1.21.11-0.8.12-fabric" and "0.8.12+mc1.21.11" -> "0.8.12": the version number in a Sodium build's name. */
    static String plainVersion(String v) {
        if (v == null) return null;
        String s = v.replaceFirst("^mc[\\d.]+-", "").split("\\+", 2)[0];
        Matcher m = PLAIN.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** The Sodium jar in an instance's mods folder (its plain version), or null. */
    static String instanceSodium(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "sodium*.jar")) {
            for (Path f : ds) {
                ModChecks.Meta m = ModChecks.read(f);
                if (m != null && "sodium".equals(m.id())) return plainVersion(m.version());
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static final String SODIUM_ID = "AANobbMI";

    /**
     * Asks Modrinth what an add-on would install in this instance. Call off the UI thread.
     *
     * @param perfOn whether the instance has FPS mods (and so Sodium) switched on
     */
    static Status check(InstallerCore core, Addon a, Path gameDir, boolean perfOn, List<String> gpus) {
        try {
            String query = "?game_versions=" + InstallerCore.enc("[\"" + InstallerCore.MC_VERSION + "\"]") + "&loaders=" + InstallerCore.enc("[\"fabric\"]");
            String url = "https://api.modrinth.com/v2/project/" + a.projectId() + "/version" + query;
            InstallerCore.ModFile f = a.newest() ? core.newestOnModrinth(a.name(), url) : core.latestOnModrinth(a.name(), url);
            String wants = null, has = null;
            if (f != null && f.requiredVersions().containsKey(SODIUM_ID)) {
                JsonObject v = core.getJson("https://api.modrinth.com/v2/version/" + f.requiredVersions().get(SODIUM_ID)).getAsJsonObject();
                wants = plainVersion(InstallerCore.str(v, "version_number"));
                if (perfOn) {
                    has = instanceSodium(gameDir);
                    if (has == null) { // not installed yet: the Sodium Play would install (the newest release)
                        InstallerCore.ModFile s = core.latestOnModrinth("Sodium", "https://api.modrinth.com/v2/project/sodium/version" + query);
                        has = s != null ? plainVersion(s.version()) : null;
                    }
                }
            }
            return decide(a, f != null ? f.version() : null, a.nvidiaOnly() ? nvidia(gpus) : Gpu.OK, gpus, wants, has);
        } catch (Exception e) {
            return new Status(State.OFFLINE, null, "Couldn't reach Modrinth");
        }
    }

    /** Installs an add-on (and its libraries) from Modrinth into the instance; the names of what went in. */
    static List<String> install(InstallerCore core, Path gameDir, Addon a, InstallerCore.Progress p) throws Exception {
        return Mods.install(core, gameDir, new Mods.Hit(a.projectId(), a.slug(), a.name(), "", "", 0, 0), a.newest(), p);
    }

    // ------------------------------------------------------------------ switching an Evade module off

    /**
     * The request file the game reads at start-up ({@code .evade/modules-off.json}, read by the client's ModCompat):
     * Evade modules to switch off once in this instance, because an add-on does the same job. The player can switch
     * them back on in the Evade menu; nothing is locked.
     */
    static final String OFF_FILE = "modules-off.json";

    static Path offFile(Path gameDir) { return gameDir.resolve(".evade").resolve(OFF_FILE); }

    /** Asks the game to switch {@code module} off the next time it starts in this instance. */
    static void requestOff(Path gameDir, String module) throws IOException {
        Set<String> want = requestedOff(gameDir);
        want.add(module);
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        want.forEach(arr::add);
        o.add("off", arr);
        Files.createDirectories(offFile(gameDir).getParent());
        InstallerCore.writeAtomic(offFile(gameDir), InstallerCore.GSON.toJson(o));
    }

    /** The modules waiting to be switched off (empty once the game has read the file). */
    static Set<String> requestedOff(Path gameDir) {
        Set<String> out = new LinkedHashSet<>();
        JsonObject o = InstallerCore.readJsonObject(offFile(gameDir));
        if (o.has("off") && o.get("off").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("off")) if (e.isJsonPrimitive() && out.size() < 64) out.add(e.getAsString());
        return out;
    }

    /** "distant horizons" -> Distant Horizons (for search and the command line). */
    static Addon find(String text) {
        if (text == null) return null;
        String t = text.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (Addon a : ALL) if (a.key().equals(t) || a.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(t)) return a;
        return null;
    }

    /** The add-ons whose Modrinth project is in {@code installed}. */
    static List<Addon> installed(Set<String> installed) {
        List<Addon> out = new ArrayList<>();
        for (Addon a : ALL) if (installed.contains(a.projectId())) out.add(a);
        return out;
    }
}
