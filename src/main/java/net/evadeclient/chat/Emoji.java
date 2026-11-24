package net.evadeclient.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emoji shortcodes: {@code :skull:} becomes 💀. Plain Unicode characters that the game's own font already draws, so
 * everyone sees them, Evade or not. Only a known code between two colons is replaced, never anything else in the
 * message, and every emoji is no longer than its code, so a converted message never grows past the chat limit.
 */
public final class Emoji {
    private Emoji() {}

    /** One shortcode (without colons) and its emoji. */
    public record Entry(String code, String emoji) {}

    private static final String[][] TABLE = {
            {"skull", "💀"}, {"joy", "😂"}, {"sob", "😭"}, {"heart", "❤"}, {"fire", "🔥"}, {"thumbsup", "👍"},
            {"thumbsdown", "👎"}, {"smile", "😄"}, {"grin", "😁"}, {"wink", "😉"}, {"sunglasses", "😎"},
            {"thinking", "🤔"}, {"eyes", "👀"}, {"clap", "👏"}, {"pray", "🙏"}, {"wave", "👋"}, {"ok_hand", "👌"},
            {"100", "💯"}, {"star", "⭐"}, {"sparkles", "✨"}, {"tada", "🎉"}, {"trophy", "🏆"}, {"crown", "👑"},
            {"gem", "💎"}, {"crossed_swords", "⚔"}, {"shield", "🛡"}, {"bow", "🏹"}, {"pick", "⛏"}, {"axe", "🪓"},
            {"zap", "⚡"}, {"snowflake", "❄"}, {"sun", "☀"}, {"moon", "🌙"}, {"rainbow", "🌈"}, {"check", "✔"},
            {"x", "❌"}, {"warning", "⚠"}, {"question", "❓"}, {"exclamation", "❗"}, {"rage", "😡"}, {"cry", "😢"},
            {"scream", "😱"}, {"flushed", "😳"}, {"smirk", "😏"}, {"upside_down", "🙃"}, {"nerd", "🤓"},
            {"clown", "🤡"}, {"ghost", "👻"}, {"alien", "👽"}, {"robot", "🤖"}, {"rocket", "🚀"}, {"moneybag", "💰"},
            {"heart_eyes", "😍"}, {"kiss", "😘"}, {"sleeping", "😴"}, {"shrug", "🤷"}, {"facepalm", "🤦"},
            {"muscle", "💪"}, {"handshake", "🤝"}, {"coffee", "☕"}, {"cake", "🎂"}, {"pizza", "🍕"}, {"apple", "🍎"},
            {"cookie", "🍪"}, {"tree", "🌲"}, {"music", "🎵"}, {"video_game", "🎮"}, {"house", "🏠"}, {"bed", "🛏"},
            {"cat", "🐱"}, {"dog", "🐶"}, {"pig", "🐷"}, {"cow", "🐮"}, {"chicken", "🐔"}, {"bee", "🐝"},
            {"wolf", "🐺"}, {"fish", "🐟"}, {"wheat", "🌾"}, {"cactus", "🌵"}, {"boom", "💥"}, {"salute", "🫡"},
    };

    /** Every emoji, in picker order. */
    public static final List<Entry> ALL;
    private static final Map<String, String> BY_CODE = new LinkedHashMap<>();
    private static final Map<String, String> BY_EMOJI = new LinkedHashMap<>();

    static {
        List<Entry> all = new ArrayList<>();
        for (String[] e : TABLE) {
            // Never longer than ":code:", so converting can only shorten a message.
            if (e[1].length() > e[0].length() + 2 || BY_CODE.containsKey(e[0])) continue;
            all.add(new Entry(e[0], e[1]));
            BY_CODE.put(e[0], e[1]);
            BY_EMOJI.putIfAbsent(e[1], e[0]);
        }
        ALL = List.copyOf(all);
    }

    /** ":code:" not glued to a word before it (so "abc:x:" stays as typed). */
    private static final Pattern CODE = Pattern.compile("(?<![A-Za-z0-9_]):([A-Za-z0-9_+\\-]{1,32}):");

    /** The emoji for {@code code} (any case, without colons), or null. */
    public static String forCode(String code) {
        return code == null ? null : BY_CODE.get(code.toLowerCase(Locale.ROOT));
    }

    /** The shortcode of {@code emoji}, or null. */
    public static String codeOf(String emoji) { return emoji == null ? null : BY_EMOJI.get(emoji); }

    /** {@code text} with every known :code: replaced by its emoji and nothing else changed. */
    public static String convert(String text) {
        if (text == null || text.indexOf(':') < 0) return text;
        Matcher m = CODE.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String e = forCode(m.group(1));
            if (e == null) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, m.start()).append(e);
            last = m.end();
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    /** A replacement in the chat box: the new text and where the cursor goes. */
    public record Edit(String text, int cursor) {}

    /**
     * Live conversion while typing: when the text just before {@code cursor} is a complete known :code:, the box's
     * new text with that code replaced, and the cursor right after the emoji. Null when there is nothing to do.
     */
    public static Edit atCursor(String text, int cursor) {
        if (text == null || cursor < 3 || cursor > text.length() || text.charAt(cursor - 1) != ':') return null;
        int open = text.lastIndexOf(':', cursor - 2);
        if (open < 0 || cursor - open - 2 > 32) return null;
        Matcher m = CODE.matcher(text);
        m.region(open, cursor);
        m.useTransparentBounds(true);
        if (!m.matches()) return null;
        String e = forCode(m.group(1));
        if (e == null) return null;
        String next = text.substring(0, open) + e + text.substring(cursor);
        return new Edit(next, open + e.length());
    }
}
