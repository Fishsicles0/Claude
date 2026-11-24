package net.evadeclient.installer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optional extras an instance can switch on, all off by default. Like the other options, every file comes from
 * Modrinth at install time (never hosted by Evade), is SHA-512 checked, and the libraries a mod needs come with it.
 * Checked on api.modrinth.com for Fabric 1.21.11 when these were added (2026-11-24):
 * <ul>
 *   <li><b>Faster start-up</b>: Lazy Language Loader 0.3.9 (LGPL-3.0), loads only the language in use.</li>
 *   <li><b>More FPS</b>: Particle Core 0.3.2 (MIT; brings Fzzy Config, custom licence, and Fabric Language Kotlin,
 *       Apache-2.0) and Entity View Distance 1.7.1 (LGPL-3.0).</li>
 *   <li><b>Singleplayer chunk speed</b> (experimental): C2ME 0.4.0-alpha and ScalableLux 0.3.0-alpha (both LGPL-3.0).
 *       Only alpha builds exist for 1.21.11, so this group takes each project's newest build rather than an older
 *       "release".</li>
 *   <li><b>Open to Friends</b>: e4mc 6.2 (MIT), the tunnel that lets friends join a singleplayer world without a
 *       server. The in-game Open to Friends button builds on it.</li>
 * </ul>
 * A mod Modrinth no longer has for this Minecraft version is simply left out, and the group says "not available".
 */
final class OptionalMods {
    private OptionalMods() {}

    /**
     * One switch: its key (saved in evade-instance.json and share codes), what the player sees, the Modrinth projects
     * (slug, name, then jar-name prefixes, as {@link InstallerCore}'s lists), whether to take the newest build even
     * when it's an alpha, and its command-line flag.
     */
    record Group(String key, String label, String sub, String[][] mods, boolean newest, boolean experimental, String flag, String credit) {
        /** The install marker's group name for this switch's files. */
        String group() { return "x-" + key; }
    }

    static final Group FAST_START = new Group("faststart", "Faster start-up", "Lazy Language Loader: loads only your language when the game starts",
            new String[][]{{"lazy-language-loader", "Lazy Language Loader", "lazy-language-loader", "lazy_language_loader", "lazylanguageloader"}},
            false, false, "--fast-start", "Lazy Language Loader (LGPL-3.0)");
    static final Group MORE_FPS = new Group("morefps", "More FPS", "Particle Core and Entity View Distance: fewer particles and far entities to draw",
            new String[][]{{"particle-core", "Particle Core", "particle_core", "particle-core", "particlecore"},
                    {"entity-view-distance", "Entity View Distance", "entity-view-distance", "entity_view_distance", "entityviewdistance"}},
            false, false, "--more-fps", "Particle Core (MIT), Entity View Distance (LGPL-3.0)");
    static final Group CHUNKS = new Group("chunks", "Singleplayer chunk speed", "C2ME and ScalableLux: faster world loading. Experimental",
            new String[][]{{"c2me-fabric", "C2ME", "c2me"}, {"scalablelux", "ScalableLux", "scalablelux"}},
            true, true, "--chunk-speed", "C2ME and ScalableLux (LGPL-3.0, alpha builds)");
    static final Group FRIENDS = new Group("friends", "Open to Friends", "e4mc: friends join your singleplayer world, no server needed",
            new String[][]{{"e4mc", "e4mc", "e4mc"}},
            false, false, "--open-to-friends", "e4mc (MIT)");

    static final Group[] GROUPS = {FAST_START, MORE_FPS, CHUNKS, FRIENDS};

    static Group byKey(String key) {
        for (Group g : GROUPS) if (g.key().equals(key)) return g;
        return null;
    }

    static Group byLabel(String label) {
        for (Group g : GROUPS) if (g.label().equals(label)) return g;
        return null;
    }

    /** The group whose marker name is {@code group} ("x-friends"), or null. */
    static Group byGroup(String group) {
        if (group == null || !group.startsWith("x-")) return null;
        return byKey(group.substring(2));
    }

    /** The group a Modrinth slug belongs to, or null. */
    static Group forSlug(String slug) {
        if (slug == null) return null;
        for (Group g : GROUPS) for (String[] m : g.mods()) if (m[0].equals(slug)) return g;
        return null;
    }

    /** The group an {@link InstallerCore} known-mod entry belongs to (the same array), or null. */
    static Group forEntry(String[] entry) {
        for (Group g : GROUPS) for (String[] m : g.mods()) if (m == entry) return g;
        return null;
    }

    /** Every project of every group, for {@link InstallerCore}'s list of mods it manages. */
    static List<String[]> allEntries() {
        List<String[]> out = new ArrayList<>();
        for (Group g : GROUPS) out.addAll(List.of(g.mods()));
        return out;
    }

    /** Only known keys, in the groups' order (unknown keys from a newer launcher's file are dropped). */
    static Set<String> clean(Collection<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys == null) return out;
        for (Group g : GROUPS) if (keys.contains(g.key())) out.add(g.key());
        return out;
    }

    /** The keys the command line asks for ({@code --fast-start}, {@code --open-to-friends}, ... or {@code --extras a,b}). */
    static Set<String> fromArgs(List<String> args) {
        Set<String> want = new LinkedHashSet<>();
        for (Group g : GROUPS) if (args.contains(g.flag())) want.add(g.key());
        int i = args.indexOf("--extras");
        if (i >= 0 && i + 1 < args.size()) {
            for (String k : args.get(i + 1).split(",")) {
                String key = k.strip().toLowerCase(Locale.ROOT);
                if (key.equals("all")) { for (Group g : GROUPS) want.add(g.key()); continue; }
                if (byKey(key) != null) want.add(key);
            }
        }
        return clean(want);
    }

    /** "Faster start-up, Open to Friends" (or "none"). */
    static String names(Collection<String> keys) {
        List<String> n = new ArrayList<>();
        for (Group g : GROUPS) if (keys != null && keys.contains(g.key())) n.add(g.label());
        return n.isEmpty() ? "none" : String.join(", ", n);
    }

    /** Share-code letters for the keys (f, m, c, o), and back. */
    static String letters(Collection<String> keys) {
        StringBuilder b = new StringBuilder();
        if (keys.contains(FAST_START.key())) b.append('f');
        if (keys.contains(MORE_FPS.key())) b.append('m');
        if (keys.contains(CHUNKS.key())) b.append('c');
        if (keys.contains(FRIENDS.key())) b.append('o');
        return b.toString();
    }

    static Set<String> fromLetters(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        if (s.indexOf('f') >= 0) out.add(FAST_START.key());
        if (s.indexOf('m') >= 0) out.add(MORE_FPS.key());
        if (s.indexOf('c') >= 0) out.add(CHUNKS.key());
        if (s.indexOf('o') >= 0) out.add(FRIENDS.key());
        return out;
    }
}
