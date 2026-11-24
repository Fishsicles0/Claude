package net.evadeclient.installer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

/**
 * Lays out the Instances list from the player's preferences: pinned instances first, then one section per group
 * (alphabetical; a collapsed group shows only its header), then the instances in no group. Inside each section the
 * chosen sort applies: as created (Main first), by name, by last played, or by play time.
 */
final class InstanceOrganizer {
    private InstanceOrganizer() {}

    /** One line of the list: a section header (instance null) or an instance. */
    record Row(String header, int count, boolean collapsed, Launcher.Instance instance) {
        boolean isHeader() { return instance == null; }
    }

    static final String PINNED = "Pinned";

    /**
     * @param lastPlayed epoch ms an instance was last played (0: never)
     * @param playTime   total ms played
     */
    static List<Row> rows(List<Launcher.Instance> all, UiPrefs p, ToLongFunction<Launcher.Instance> lastPlayed,
                          ToLongFunction<Launcher.Instance> playTime) {
        Comparator<Launcher.Instance> order = comparator(p.sort, all, lastPlayed, playTime);
        List<Launcher.Instance> pinned = new ArrayList<>(), loose = new ArrayList<>();
        TreeMap<String, List<Launcher.Instance>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Launcher.Instance i : all) {
            if (p.pinned.contains(i.id)) pinned.add(i);
            else {
                String g = p.groups.get(i.id);
                if (g == null) loose.add(i);
                else groups.computeIfAbsent(g, k -> new ArrayList<>()).add(i);
            }
        }
        List<Row> out = new ArrayList<>();
        boolean sections = !groups.isEmpty() || (!pinned.isEmpty() && pinned.size() < all.size());
        section(out, sections ? PINNED : null, pinned, order, p);
        for (var e : groups.entrySet()) section(out, e.getKey(), e.getValue(), order, p);
        section(out, sections && !loose.isEmpty() ? "Other" : null, loose, order, p);
        return out;
    }

    private static void section(List<Row> out, String header, List<Launcher.Instance> list, Comparator<Launcher.Instance> order, UiPrefs p) {
        if (list.isEmpty()) return;
        list.sort(order);
        boolean shut = header != null && p.collapsed.contains(header);
        if (header != null) out.add(new Row(header, list.size(), shut, null));
        if (!shut) for (Launcher.Instance i : list) out.add(new Row(null, 0, false, i));
    }

    static Comparator<Launcher.Instance> comparator(String sort, List<Launcher.Instance> all, ToLongFunction<Launcher.Instance> lastPlayed,
                                                    ToLongFunction<Launcher.Instance> playTime) {
        Comparator<Launcher.Instance> byName = Comparator.comparing(i -> i.name.toLowerCase(Locale.ROOT));
        return switch (sort) {
            case "name" -> byName;
            case "played" -> Comparator.comparingLong(lastPlayed).reversed().thenComparing(byName);
            case "time" -> Comparator.comparingLong(playTime).reversed().thenComparing(byName);
            default -> Comparator.comparingInt(all::indexOf); // as listed: Main, then oldest first
        };
    }

    /** The groups in use, alphabetical. */
    static List<String> groupNames(UiPrefs p) {
        TreeMap<String, Boolean> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String g : p.groups.values()) m.put(g, true);
        return new ArrayList<>(m.keySet());
    }

    /** Forgets pins and groups of instances that are gone (deleted outside the launcher). */
    static boolean prune(UiPrefs p, List<Launcher.Instance> all) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Launcher.Instance i : all) ids.add(i.id);
        boolean changed = p.pinned.retainAll(ids);
        changed |= p.groups.keySet().retainAll(ids);
        java.util.Set<String> used = new java.util.HashSet<>(p.groups.values());
        used.add(PINNED);
        used.add("Other");
        changed |= p.collapsed.removeIf(c -> used.stream().noneMatch(u -> u.equalsIgnoreCase(c)));
        return changed;
    }
}
