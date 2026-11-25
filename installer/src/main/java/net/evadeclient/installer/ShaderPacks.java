package net.evadeclient.installer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The shader packs the Shaders option can add next to Iris, each one ticked or not per instance. Every pack is
 * downloaded from Modrinth when Play installs it (newest version for {@value InstallerCore#MC_VERSION} with the Iris
 * loader, SHA-512 checked) and never bundled or hosted by Evade: BSL and Sildur's are All Rights Reserved and
 * Complementary and Photon have their own licences. Unticking a pack removes only the file Evade put there; the
 * player's own packs in shaderpacks/ are never touched.
 */
final class ShaderPacks {
    private ShaderPacks() {}

    /**
     * One pack. {@code key}: the short name in settings, share codes and {@code --shader-packs}; {@code slug}: its
     * Modrinth project; {@code variant}: for a project that publishes one version per edition (Sildur's: Lite, Medium,
     * High...), the edition to install, else null; {@code prefix}: how its file names start (lower case), to recognise
     * a pack an older install added.
     */
    record Pack(String key, String slug, String name, String sub, String perf, String tier, String author, String licence,
                String licenceUrl, String variant, String prefix, boolean defaultOn, boolean light) {
        String url() { return "https://modrinth.com/shader/" + slug; }
    }

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
    static final Pack[] ALL = {PHOTON, REIMAGINED, UNBOUND, BSL, SILDURS};

    static Pack byKey(String key) {
        if (key == null) return null;
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (Pack p : ALL) if (p.key().equals(k) || p.slug().equals(k)) return p;
        return switch (k) {
            case "complementary", "reimagined" -> REIMAGINED;
            case "unbound" -> UNBOUND;
            case "sildurs", "sildur", "sildurs-vibrant-shaders" -> SILDURS;
            default -> null;
        };
    }

    /** The pack a Modrinth project slug belongs to, or null. */
    static Pack bySlug(String slug) {
        if (slug == null) return null;
        for (Pack p : ALL) if (p.slug().equals(slug)) return p;
        return null;
    }

    /** The pack an older install's file was (by its name), or null. */
    static Pack byFile(String file) {
        if (file == null) return null;
        String l = file.toLowerCase(Locale.ROOT);
        for (Pack p : ALL) if (l.startsWith(p.prefix())) return p;
        return null;
    }

    /** Complementary Reimagined and Photon: what the Shaders option has always added. */
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

    /** The ticked packs as one line ("Photon and Complementary Reimagined"); "no packs" when none. */
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
     * {@code --shader-packs photon,bsl}: the packs named ({@code all}, {@code none} and {@code default} work too).
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
