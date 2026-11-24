package net.evadeclient.social;

import net.evadeclient.module.misc.Friends;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextVisitFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Friend Colours: your Evade friends' names (and the Highlighted Players you list) in your chosen colour, in chat and
 * in the tab list. Only how text is drawn on your screen changes: the rest of each line keeps its colours, click and
 * hover actions, and nothing is sent anywhere.
 */
public final class FriendColors {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static String namesKey;
    private static Pattern names;
    private static Set<String> highlightedLc = Set.of();
    private static String highlightedKey;

    private FriendColors() {}

    private static Friends on() {
        Friends f = Friends.get();
        return f != null && f.friendColors.get() ? f : null;
    }

    /** The colour (RGB) for friends' names, or -1 while Friend Colours is off. */
    public static int color() {
        Friends f = on();
        return f == null ? -1 : f.friendColor.get() & 0xFFFFFF;
    }

    /** The Highlighted Players list, lower case. */
    private static Set<String> highlighted(Friends f) {
        String raw = f.highlightedPlayers.get() == null ? "" : f.highlightedPlayers.get();
        if (!raw.equals(highlightedKey)) {
            Set<String> out = new LinkedHashSet<>();
            for (String w : raw.split("[,|\\s]+")) if (NAME.matcher(w).matches()) out.add(w.toLowerCase(Locale.ROOT));
            highlightedLc = Set.copyOf(out);
            highlightedKey = raw;
        }
        return highlightedLc;
    }

    /**
     * Whether a player is an Evade friend (by account) or on your Highlighted Players list (by name). Doesn't depend
     * on Friend Colours being on (Tab Tweaks' Friends First uses it too).
     */
    public static boolean isFriend(UUID uuid, String name) {
        Friends f = Friends.get();
        if (f == null) return false;
        if (uuid != null && SocialClient.state().isFriend(uuid)) return true;
        return name != null && highlighted(f).contains(name.toLowerCase(Locale.ROOT));
    }

    /** Whole-word, case-insensitive names of every friend and highlighted player, or null when there are none. */
    private static Pattern names(Friends f) {
        SocialState s = SocialClient.state();
        List<String> list = new ArrayList<>();
        for (SocialState.Person p : s.friends()) {
            String shown = MojangNames.shown(p.uuid(), p.name());
            if (NAME.matcher(shown).matches()) list.add(shown);
        }
        list.addAll(highlighted(f));
        String key = String.join(",", list);
        if (!key.equals(namesKey)) {
            List<String> quoted = new ArrayList<>();
            for (String n : list) quoted.add(Pattern.quote(n));
            names = quoted.isEmpty() ? null : Pattern.compile("(?<![A-Za-z0-9_])(?:" + String.join("|", quoted) + ")(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE);
            namesKey = key;
        }
        return names;
    }

    /** A chat line with every friend's name recoloured, or {@code message} itself when nothing matches or it's off. */
    public static Text chat(Text message) {
        Friends f = on();
        if (f == null || message == null) return message;
        Pattern p = names(f);
        if (p == null || !p.matcher(message.getString()).find()) return message;
        return recolour(message, p, f.friendColor.get() & 0xFFFFFF);
    }

    /** A tab-list name in the friend colour (only the player's own name inside it, so ranks keep theirs). */
    public static Text tab(PlayerListEntry entry, Text name) {
        Friends f = on();
        if (f == null || entry == null || name == null) return name;
        String n = entry.getProfile().name();
        if (n == null || !NAME.matcher(n).matches() || !isFriend(entry.getProfile().id(), n)) return name;
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(n) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        return recolour(name, p, f.friendColor.get() & 0xFFFFFF);
    }

    /**
     * {@code text} with each match of {@code p} in {@code rgb}. Legacy § codes are resolved into styles first, so the
     * rest keeps its colours; click and hover events stay where they were.
     */
    static Text recolour(Text text, Pattern p, int rgb) {
        StringBuilder flat = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        TextVisitFactory.visitFormatted(text, Style.EMPTY, (index, style, codePoint) -> {
            int before = flat.length();
            flat.appendCodePoint(codePoint);
            for (int k = before; k < flat.length(); k++) styles.add(style);
            return true;
        });
        String s = flat.toString();
        Matcher m = p.matcher(s);
        boolean[] hit = new boolean[s.length()];
        boolean any = false;
        while (m.find()) {
            for (int k = m.start(); k < m.end(); k++) hit[k] = true;
            any = true;
        }
        if (!any) return text;
        MutableText out = Text.empty();
        for (int i = 0; i < s.length(); ) {
            int j = i + 1;
            while (j < s.length() && hit[j] == hit[i] && styles.get(j).equals(styles.get(i))) j++;
            out.append(Text.literal(s.substring(i, j)).setStyle(hit[i] ? styles.get(i).withColor(rgb) : styles.get(i)));
            i = j;
        }
        return out;
    }
}
