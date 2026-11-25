package net.evadeclient.installer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The shader packs the Shaders option can add next to Iris, each one ticked or not per instance. Evade Shaders is
 * Evade's own pack, free with Evade Client: it comes from evadeclient.com (the file, version and SHA-256 that
 * version.json names). Every other pack is downloaded from Modrinth when Play installs it (newest version for
 * {@value InstallerCore#MC_VERSION} with the Iris loader, SHA-512 checked) and never bundled or hosted by Evade: BSL and
 * Sildur's are All Rights Reserved and Complementary and Photon have their own licences. Unticking a pack removes only
 * the file Evade put there; the player's own packs in shaderpacks/ are never touched.
 */
final class ShaderPacks {
    private ShaderPacks() {}

    /**
     * One pack. {@code key}: the short name in settings, share codes and {@code --shader-packs}; {@code slug}: its
     * Modrinth project (null for Evade Shaders, which isn't on Modrinth); {@code variant}: for a project that publishes one version per edition (Sildur's: Lite, Medium,
     * High...), the edition to install, else null; {@code prefix}: how its file names start (lower case), to recognise
     * a pack an older install added.
     */
    record Pack(String key, String slug, String name, String sub, String perf, String tier, String author, String licence,
                String licenceUrl, String variant, String prefix, boolean defaultOn, boolean light) {
        /** Evade's own pack (from evadeclient.com), not a Modrinth one. */
        boolean ours() { return slug == null; }
        /** Its page: on evadeclient.com for Evade Shaders, else on Modrinth. */
        String url() { return ours() ? InstallerCore.SITE + "/download#evade-shaders" : "https://modrinth.com/shader/" + slug; }
        /** Where it's downloaded from, as shown to players ("evadeclient.com", "modrinth.com/shader/bsl-shaders"). */
        String home() { return ours() ? "evadeclient.com" : "modrinth.com/shader/" + slug; }
    }

    static final Pack EVADE = new Pack("evade-shaders", null, "Evade Shaders",
            "Evade's own pack: fast on laptops, with Potato to Ultra levels", "Any PC: pick a level in its options", "Evade",
            "Evade Client", "Free with Evade Client", null, null, "evade shaders", true, true);

    static final Pack PHOTON = new Pack("photon", "photon-shader", "Photon",
            "Semi-realistic light, clouds and water, made for playing", "High-end: wants a strong graphics card", "High-end",
            "SixthSurge", "Photon Shaders licence (custom)", "https://github.com/sixthsurge/photon/blob/main/LICENSE",
            null, "photon", true, false);
    static final Pack REIMAGINED = new Pack("complementary-reimagined", "complementary-reimagined", "Complementary Reimagined",
            "The vanilla look made better: soft shadows, clouds and water", "Lighter: runs well on most PCs and laptops", "Light",
            "EminGT", "Complementary licence 1.8 (custom)", "https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/License.txt",
            null, "complementaryreimagined", true, true);
    static final Pack UNBOUND = new Pack("complementary-unbound", "complementary-unbound", "Complementary Unbound",
            "Realistic light, fog and reflections from the Complementary team", "Mid to high-end graphics", "Realistic",
            "EminGT", "Complementary licence (custom)", "https://github.com/ComplementaryDevelopment/ComplementaryShadersV4/blob/main/License.txt",
            null, "complementaryunbound", false, false);
    static final Pack BSL = new Pack("bsl", "bsl-shaders", "BSL",
            "Warm, bright colours with soft shadows and light shafts", "Mid-range graphics", "Mid-range",
            "CaptTatsu", "All Rights Reserved", null, null, "bsl_", false, false);
    static final Pack SILDURS = new Pack("sildurs-vibrant", "sildurs-vibrant-shaders", "Sildur's Vibrant",
            "Vivid colours and lighting, made to run on almost anything", "Scales from low-end to high-end (Medium edition)", "Any PC",
            "Sildur", "All Rights Reserved", null, "Medium", "sildur's vibrant shaders", false, true);

    /** Every pack, in the order shown (and the order Iris is pointed at: the first ticked pack becomes the active one). */
    static final Pack[] ALL = {EVADE, PHOTON, REIMAGINED, UNBOUND, BSL, SILDURS};

    /**
     * The packs launchers before Evade Shaders offered. Settings saved by them (no {@code shaderPacksKnown}) only chose
     * among these, so a default-on pack added since is ticked for them too (see {@link #withNewDefaults}).
     */
    static final java.util.List<String> LEGACY_KEYS = java.util.List.of("photon", "complementary-reimagined", "complementary-unbound", "bsl", "sildurs-vibrant");

    /**
     * {@code stored} plus every default-on pack the settings never offered ({@code known}: the keys the launcher that
     * saved them knew; null: {@link #LEGACY_KEYS}). A pack the player unticked stays unticked, since it was known.
     */
    static Set<String> withNewDefaults(Collection<String> stored, Collection<String> known) {
        Set<String> knew = new java.util.HashSet<>(known != null ? known : LEGACY_KEYS);
        java.util.List<String> out = new ArrayList<>(stored);
        for (Pack p : ALL) if (p.defaultOn() && !knew.contains(p.key())) out.add(p.key());
        return clean(out);
    }

    static Pack byKey(String key) {
        if (key == null) return null;
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (Pack p : ALL) if (p.key().equals(k) || k.equals(p.slug())) return p;
        return switch (k) {
            case "evade", "evadeshaders", "evade-shader" -> EVADE;
            case "complementary", "reimagined" -> REIMAGINED;
            case "unbound" -> UNBOUND;
            case "sildurs", "sildur", "sildurs-vibrant-shaders" -> SILDURS;
            default -> null;
        };
    }

    /** The pack a Modrinth project slug belongs to, or null. */
    static Pack bySlug(String slug) {
        if (slug == null) return null;
        for (Pack p : ALL) if (slug.equals(p.slug())) return p;
        return null;
    }

    /** The pack an older install's file was (by its name), or null. */
    static Pack byFile(String file) {
        if (file == null) return null;
        String l = file.toLowerCase(Locale.ROOT);
        if (l.startsWith("evadeshaders")) return EVADE;
        for (Pack p : ALL) if (l.startsWith(p.prefix())) return p;
        return null;
    }

    /** Evade Shaders (the active one), Photon and Complementary Reimagined. */
    static Set<String> defaults() {
        Set<String> out = new LinkedHashSet<>();
        for (Pack p : ALL) if (p.defaultOn()) out.add(p.key());
        return out;
    }

    /** Known keys only, in display order (null: the defaults). */
    static Set<String> clean(Collection<String> keys) {
        if (keys == null) return defaults();
        Set<String> want = new LinkedHashSet<>();
        for (String k : keys) {
            Pack p = byKey(k);
            if (p != null) want.add(p.key());
        }
        Set<String> out = new LinkedHashSet<>();
        for (Pack p : ALL) if (want.contains(p.key())) out.add(p.key());
        return out;
    }

    static boolean isDefault(Set<String> keys) { return clean(keys).equals(defaults()); }

    /** The ticked packs as one line ("Evade Shaders, Photon and Complementary Reimagined"); "no packs" when none. */
    static String names(Set<String> keys) {
        List<String> n = new ArrayList<>();
        for (String k : clean(keys)) n.add(byKey(k).name());
        if (n.isEmpty()) return "no packs";
        if (n.size() == 1) return n.get(0);
        return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
    }

    /** The Shaders row's line on the instance page. */
    static String rowText(Set<String> keys) {
        Set<String> c = clean(keys);
        return c.isEmpty() ? "Iris only: your own packs from shaderpacks/" : "Iris with " + names(c);
    }

    /** All keys, comma-separated (for help text). */
    static String keys() {
        List<String> out = new ArrayList<>();
        for (Pack p : ALL) out.add(p.key());
        return String.join(",", out);
    }

    /**
     * {@code --shader-packs evade-shaders,photon,bsl}: the packs named ({@code all}, {@code none} and {@code default} work too).
     * Throws IllegalArgumentException naming an unknown pack.
     */
    static Set<String> parse(String arg) {
        String a = arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || a.equals("none")) return new LinkedHashSet<>();
        if (a.equals("default") || a.equals("defaults")) return defaults();
        if (a.equals("all")) return clean(List.of(keys().split(",")));
        Set<String> out = new LinkedHashSet<>();
        for (String s : a.split("[,\\s]+")) {
            if (s.isBlank()) continue;
            Pack p = byKey(s);
            if (p == null) throw new IllegalArgumentException("Unknown shader pack \"" + s + "\". Choose from: " + keys() + " (or all, none)");
            out.add(p.key());
        }
        return clean(out);
    }

    /** Stored form (settings, instance zips, share codes): the keys, comma-separated. */
    static String join(Set<String> keys) { return String.join(",", clean(keys)); }
}
