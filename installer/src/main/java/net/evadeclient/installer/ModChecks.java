package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Looks over an instance's mods before the game does: a mod that needs another that isn't there (or is switched
 * off), two mods that say they don't work together, two copies of the same mod, and mods that do the same job as
 * each other or as Evade itself (a short hand-kept list, e.g. two zoom mods). Fabric would stop the game with a crash
 * screen for the first three; the last is a warning only.
 */
final class ModChecks {
    private ModChecks() {}

    /** What a jar's fabric.mod.json says: its id, the ids it also provides (nested jars included), what it needs and breaks. */
    record Meta(Path file, String id, String name, Set<String> provides, Set<String> depends, Set<String> breaks, String license,
                String version, Map<String, List<String>> breakRanges) {
        Meta(Path file, String id, String name, Set<String> provides, Set<String> depends, Set<String> breaks, String license) {
            this(file, id, name, provides, depends, breaks, license, null, Map.of());
        }
    }

    enum Kind { MISSING, SWITCHED_OFF, BREAKS, DUPLICATE, OVERLAP }

    /**
     * A problem with the mods. {@code mods}: the jars involved; {@code needId}: the mod id that's missing (MISSING,
     * SWITCHED_OFF); {@code projectId}: the Modrinth project to install for it, when known.
     */
    record Problem(Kind kind, String title, String detail, List<Path> mods, String needId, String projectId, String option) {
        Problem(Kind kind, String title, String detail, List<Path> mods, String needId, String projectId) {
            this(kind, title, detail, mods, needId, projectId, null);
        }
        boolean warning() { return kind == Kind.OVERLAP; }
    }

    /** Ids every Fabric 1.21.11 game has without a mod jar for them. */
    private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras");

    /**
     * Mods that do the same job: {label, what Evade has built in (or null), mod ids...}. Two of them, or one plus
     * Evade's own, usually fight over the same key or screen.
     */
    static final String[][] OVERLAPS = {
            {"zoom", "Zoom", "ok_zoomer", "okzoomer", "zoomify", "logical_zoom", "wi_zoom", "zoomer_boomer", "simplezoom"},
            {"minimap", "Minimap", "xaerominimap", "xaerominimapfair", "journeymap", "voxelmap", "mapfrontiers"},
            {"freelook", "Freelook", "freelook", "perspective", "perspectivemod", "betterthirdperson", "f5fix"},
            {"toggle sprint", "Toggle Sprint", "togglesprint", "toggle-sprint", "sprinttoggle"},
            {"fullbright", "Fullbright", "fullbright", "gammautils", "lumen"},
            {"keystrokes HUD", "Keystrokes", "keystrokes", "keystrokesmod", "cleanerkeystrokes"},
            {"rendering engine", null, "sodium", "canvas", "vulkanmod", "optifabric", "optifine"},
            {"shader loader", null, "iris", "optifabric", "canvas", "oculus"},
    };

    /** Pairs known to crash together even though their mod files don't say so: {id, id, why}. */
    static final String[][] CONFLICTS = {
            {"optifabric", "sodium", "OptiFine replaces the same rendering code Sodium does: use one or the other (Evade's FPS mods are Sodium)."},
            {"optifabric", "iris", "Iris and OptiFine both load shaders: use Iris (Evade's Shaders option)."},
            {"vulkanmod", "sodium", "VulkanMod and Sodium both replace the renderer: use one."},
            {"vulkanmod", "iris", "VulkanMod can't load Iris shaders."},
    };

    // ------------------------------------------------------------------ version ranges

    /** Whether {@code version} falls in any of a "breaks" entry's ranges. An unknown version only counts for "*". */
    static boolean breaksVersion(List<String> ranges, String version) {
        for (String r : ranges) {
            String s = r == null ? "*" : r.trim();
            if (s.isEmpty() || s.equals("*")) return true;
            if (version == null) continue;
            boolean all = true, any = false;
            for (String part : s.split("\\s+")) {
                if (part.isEmpty()) continue;
                any = true;
                Boolean ok = matches(part, version);
                if (ok == null || !ok) { all = false; break; }
            }
            if (any && all) return true;
        }
        return false;
    }

    /** One Fabric version predicate (>=1.2, <0.140.0, =1.0, ~1.2, ^1.2, 1.2, 1.2.x). Null when it can't be read. */
    static Boolean matches(String pred, String version) {
        String op = "=";
        for (String o : new String[]{">=", "<=", ">", "<", "=", "~", "^"}) if (pred.startsWith(o)) { op = o; pred = pred.substring(o.length()); break; }
        if (pred.endsWith(".x") || pred.endsWith(".*")) {
            String pre = pred.substring(0, pred.length() - 1);
            return version.startsWith(pre);
        }
        int c;
        try { c = compareVersions(version, pred); } catch (RuntimeException e) { return null; }
        int[] v = nums(version), p = nums(pred);
        return switch (op) {
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            case "~" -> c >= 0 && v.length > 1 && p.length > 1 ? v[0] == p[0] && v[1] == p[1] : c >= 0 && v[0] == p[0];
            case "^" -> c >= 0 && v[0] == p[0];
            default -> c == 0;
        };
    }

    private static int[] nums(String v) {
        String core = v.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String d = parts[i].replaceAll("[^0-9].*$", "");
            out[i] = d.isEmpty() ? 0 : Integer.parseInt(d);
        }
        return out;
    }

    /** Semantic-ish comparison: numeric parts, then a release beats any pre-release ("1.0" > "1.0-beta.1"). */
    static int compareVersions(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        boolean preA = a.split("\\+", 2)[0].contains("-"), preB = b.split("\\+", 2)[0].contains("-");
        if (preA != preB) return preA ? -1 : 1;
        return 0;
    }

    // ------------------------------------------------------------------ reading jars

    static Meta read(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return null;
            Meta m;
            try (InputStream in = z.getInputStream(e)) { m = parse(jar, in.readNBytes(1 << 20)); }
            if (m == null) return null;
            // Jar-in-jar: the libraries a mod carries inside provide their ids too.
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry x = en.nextElement();
                if (!x.isDirectory() && x.getName().startsWith("META-INF/jars/") && x.getName().endsWith(".jar") && x.getSize() < 64L << 20) {
                    try (InputStream in = z.getInputStream(x)) { nested(in.readAllBytes(), m.provides(), 0); } catch (IOException ignored) { }
                }
            }
            return m;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void nested(byte[] jar, Set<String> provides, int depth) throws IOException {
        if (depth > 2) return;
        List<byte[]> inner = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("fabric.mod.json")) {
                    Meta m = parse(null, z.readNBytes(1 << 20));
                    if (m != null) { provides.add(m.id()); provides.addAll(m.provides()); }
                } else if (e.getName().startsWith("META-INF/jars/") && e.getName().endsWith(".jar")) {
                    byte[] b = z.readNBytes(64 << 20);
                    inner.add(b);
                }
            }
        }
        for (byte[] b : inner) nested(b, provides, depth + 1);
    }

    private static Meta parse(Path file, byte[] json) {
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = InstallerCore.str(o, "id");
            if (id == null) return null;
            String name = InstallerCore.str(o, "name");
            Set<String> provides = new LinkedHashSet<>(), depends = new LinkedHashSet<>(), breaks = new LinkedHashSet<>();
            if (o.has("provides") && o.get("provides").isJsonArray()) for (JsonElement x : o.getAsJsonArray("provides")) if (x.isJsonPrimitive()) provides.add(x.getAsString());
            if (o.has("depends") && o.get("depends").isJsonObject()) depends.addAll(o.getAsJsonObject("depends").keySet());
            Map<String, List<String>> breakRanges = new LinkedHashMap<>();
            if (o.has("breaks") && o.get("breaks").isJsonObject()) {
                for (var be : o.getAsJsonObject("breaks").entrySet()) {
                    breaks.add(be.getKey());
                    List<String> ranges = new ArrayList<>();
                    JsonElement v = be.getValue();
                    if (v.isJsonPrimitive()) ranges.add(v.getAsString());
                    else if (v.isJsonArray()) for (JsonElement x : v.getAsJsonArray()) if (x.isJsonPrimitive()) ranges.add(x.getAsString());
                    if (ranges.isEmpty()) ranges.add("*");
                    breakRanges.put(be.getKey(), ranges);
                }
            }
            String version = InstallerCore.str(o, "version");
            String license = null;
            JsonElement l = o.get("license");
            if (l != null && l.isJsonPrimitive()) license = l.getAsString();
            else if (l != null && l.isJsonArray() && !l.getAsJsonArray().isEmpty() && l.getAsJsonArray().get(0).isJsonPrimitive()) license = l.getAsJsonArray().get(0).getAsString();
            return new Meta(file, id, name != null ? name : id, provides, depends, breaks, license, version, breakRanges);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ checks

    /**
     * Every problem with the instance's mods. {@code metas}: each jar's metadata (switched off ones included);
     * {@code onPaths}: which jars are on; {@code projectFor}: a mod id → Modrinth project id guess (from the jars
     * Modrinth identified), used to offer "Install".
     */
    static List<Problem> check(Map<Path, Meta> metas, Set<Path> onPaths, Map<String, String> projectFor) {
        return check(metas, onPaths, projectFor, null);
    }

    /**
     * As above; with {@code gameDir}, a missing mod that one of Evade's options brings (Sodium with FPS mods, ...) is
     * fine while that option is on (Play installs it), and otherwise points at the option instead of Modrinth.
     */
    static List<Problem> check(Map<Path, Meta> metas, Set<Path> onPaths, Map<String, String> projectFor, Path gameDir) {
        List<Problem> out = new ArrayList<>();
        Map<String, Path> providerOn = new LinkedHashMap<>(), providerOff = new LinkedHashMap<>();
        Map<String, List<Path>> byId = new LinkedHashMap<>();
        for (Meta m : metas.values()) {
            boolean on = onPaths.contains(m.file());
            Map<String, Path> target = on ? providerOn : providerOff;
            target.putIfAbsent(m.id(), m.file());
            for (String p : m.provides()) target.putIfAbsent(p, m.file());
            if (on) byId.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(m.file());
        }
        // Missing or switched-off dependencies.
        Map<String, List<Path>> missing = new LinkedHashMap<>(), off = new LinkedHashMap<>();
        for (Meta m : metas.values()) {
            if (!onPaths.contains(m.file())) continue;
            for (String d : m.depends()) {
                if (BUILT_IN.contains(d) || providerOn.containsKey(d) || isFabricApiModule(d, providerOn)) continue;
                (providerOff.containsKey(d) ? off : missing).computeIfAbsent(d, k -> new ArrayList<>()).add(m.file());
            }
        }
        for (var e : missing.entrySet()) {
            String needers = names(metas, e.getValue());
            String option = gameDir == null ? null : Mods.optionForModId(e.getKey());
            if (option != null) {
                if (option.equals("Evade") || Mods.optionOn(gameDir, option)) continue; // Play installs it
                out.add(new Problem(Kind.MISSING, needers + (e.getValue().size() == 1 ? " needs " : " need ") + pretty(e.getKey()),
                        pretty(e.getKey()) + " comes with " + option + ". Switch on " + option + " for this instance in Instances.", e.getValue(), e.getKey(), null, option));
                continue;
            }
            out.add(new Problem(Kind.MISSING, needers + (e.getValue().size() == 1 ? " needs " : " need ") + pretty(e.getKey()),
                    pretty(e.getKey()) + " isn't installed, so the game won't start.", e.getValue(), e.getKey(), projectFor.get(e.getKey())));
        }
        for (var e : off.entrySet()) {
            Meta p = metas.get(providerOff.get(e.getKey()));
            String needers = names(metas, e.getValue());
            out.add(new Problem(Kind.SWITCHED_OFF, needers + (e.getValue().size() == 1 ? " needs " : " need ") + (p != null ? p.name() : pretty(e.getKey())),
                    (p != null ? p.name() : pretty(e.getKey())) + " is switched off. Switch it on, or switch these off too.",
                    List.of(providerOff.get(e.getKey())), e.getKey(), null));
        }
        // Declared incompatibilities.
        for (Meta m : metas.values()) {
            if (!onPaths.contains(m.file())) continue;
            for (String b : m.breaks()) {
                Path other = providerOn.get(b);
                if (other == null || other.equals(m.file())) continue;
                Meta o = metas.get(other);
                // A break usually names a version range (e.g. "<0.140.0"): only report it when the installed version is in it.
                String otherVersion = o != null && b.equals(o.id()) ? o.version() : null;
                if (!breaksVersion(m.breakRanges().getOrDefault(b, List.of("*")), otherVersion)) continue;
                out.add(new Problem(Kind.BREAKS, m.name() + " doesn't work with " + (o != null ? o.name() : pretty(b)),
                        m.name() + " says it breaks with " + (o != null ? o.name() : pretty(b)) + ". Switch one of them off.", List.of(m.file(), other), null, null));
            }
        }
        // Known pairs that crash.
        for (String[] c : CONFLICTS) {
            Path a = providerOn.get(c[0]), b = providerOn.get(c[1]);
            if (a == null || b == null || a.equals(b)) continue;
            out.add(new Problem(Kind.BREAKS, name(metas, a) + " and " + name(metas, b) + " don't work together", c[2], List.of(a, b), null, null));
        }
        // Two copies of one mod.
        for (var e : byId.entrySet()) {
            if (e.getValue().size() < 2) continue;
            out.add(new Problem(Kind.DUPLICATE, "Two copies of " + name(metas, e.getValue().get(0)),
                    "Fabric stops when a mod is there twice. Remove the older file.", e.getValue(), null, null));
        }
        // Same job twice (or a job Evade already does).
        Set<Path> evade = new LinkedHashSet<>();
        for (Meta m : metas.values()) if (onPaths.contains(m.file()) && m.id().equals("evade")) evade.add(m.file());
        for (String[] g : OVERLAPS) {
            List<Path> hits = new ArrayList<>();
            for (int i = 2; i < g.length; i++) {
                Path p = providerOn.get(g[i]);
                if (p != null && !hits.contains(p)) hits.add(p);
            }
            boolean withEvade = g[1] != null && !evade.isEmpty() && !hits.isEmpty();
            if (hits.size() >= 2) {
                out.add(new Problem(Kind.OVERLAP, "Two " + g[0] + " mods", names(metas, hits) + " do the same job and may fight over keys or screens. Keep one.", hits, null, null));
            } else if (withEvade) {
                out.add(new Problem(Kind.OVERLAP, name(metas, hits.get(0)) + " doubles up with Evade",
                        "Evade Client has " + g[1] + " built in. Switch one off (Evade's in its menu) so they don't share a key.", hits, null, null));
            }
        }
        return out;
    }

    /** Fabric API's own modules ("fabric-xyz-api-v1") are inside its jar, which we read nested ids from; also accept them when Fabric API is on. */
    private static boolean isFabricApiModule(String id, Map<String, Path> providerOn) {
        return id.startsWith("fabric-") && (providerOn.containsKey("fabric-api") || providerOn.containsKey("fabric"));
    }

    private static String name(Map<Path, Meta> metas, Path p) {
        Meta m = metas.get(p);
        return m != null ? m.name() : p.getFileName().toString();
    }

    private static String names(Map<Path, Meta> metas, Collection<Path> paths) {
        List<String> n = new ArrayList<>();
        for (Path p : paths) n.add(name(metas, p));
        if (n.size() <= 2) return String.join(" and ", n);
        return n.get(0) + ", " + n.get(1) + " and " + (n.size() - 2) + " more";
    }

    /** "cloth-config" -> "Cloth Config" (a readable name for a mod id we have no jar for). */
    static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("[-_]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    /**
     * The Modrinth project for a mod id nobody has, when it can be found: the id itself is often the project's slug
     * ("cloth-config"), or with underscores as dashes. Only Fabric mods for this Minecraft version count.
     */
    static ContentApi.Project findProject(String modId) {
        for (String slug : new LinkedHashSet<>(List.of(modId, modId.replace('_', '-'), modId.toLowerCase(Locale.ROOT)))) {
            try {
                ContentApi.Project p = ContentApi.project(slug);
                if (p != null && "mod".equals(p.type()) && ContentApi.newest(p.id(), List.of("fabric")) != null) return p;
            } catch (IOException ignored) { }
        }
        return null;
    }

    /** True when the licence is one that lets anyone share copies (an open-source one); unknown counts as not. */
    static boolean redistributable(String license) {
        if (license == null) return false;
        String l = license.strip().toUpperCase(Locale.ROOT);
        if (l.isEmpty() || l.contains("ARR") || l.contains("ALL RIGHTS") || l.contains("LICENSEREF") || l.contains("CUSTOM") || l.contains("PROPRIETARY")) return false;
        for (String ok : new String[]{"MIT", "LGPL", "GPL", "APACHE", "MPL", "BSD", "CC0", "UNLICENSE", "ISC", "ZLIB", "EUPL", "WTFPL", "0BSD", "AGPL", "OSL", "ARTISTIC"}) {
            if (l.contains(ok)) return true;
        }
        return false;
    }
}
