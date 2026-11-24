package net.evadeclient.module.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.gui.screen.ReconfiguringScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextVisitFactory;
import net.minecraft.util.Formatting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat upgrades: timestamps, repeated messages stacked into one line with a (x3) counter, a longer history, click to
 * copy a message, highlighted mentions of your name (or your own keywords) with a ping, a filter that hides or
 * collapses lines you don't want to read, and chat that survives a server switch or a disconnect. Everything here only
 * changes how chat is shown to you; nothing is ever sent.
 */
public class BetterChat extends Module {
    // -- timestamps and copy
    private final BooleanSetting timestamps = add(new BooleanSetting("Timestamps", true)
            .describe("Shows the time each message arrived in front of it."));
    private final ModeSetting timeFormat = add(new ModeSetting("Time Format", "HH:mm", "HH:mm", "HH:mm:ss", "h:mm a", "h:mm:ss a")
            .describe("24-hour or 12-hour clock, with or without seconds."));
    private final ModeSetting timeBrackets = add(new ModeSetting("Time Brackets", "[ ]", "[ ]", "( )", "< >", "None")
            .describe("What goes around the timestamp."));
    private final ColorSetting timeColor = add(new ColorSetting("Time Color", 0xFF5A5A66)
            .describe("Colour of the timestamp (and of the copy button)."));
    private final BooleanSetting copyButton = add(new BooleanSetting("Copy Button", true)
            .describe("Click a message's timestamp (or the ⧉ after it without timestamps) to copy the message."));
    // -- stacking
    private final BooleanSetting antiSpam = add(new BooleanSetting("Stack Repeats", true)
            .describe("A repeated message is shown once with a (x3) counter instead of filling chat."));
    private final ModeSetting stackMode = add(new ModeSetting("Stack Mode", "Consecutive", "Consecutive", "Recent Lines")
            .describe("Consecutive: only a repeat of the newest line stacks. Recent Lines: a repeat of any of the last "
                    + "few lines moves down to the bottom and stacks."));
    private final NumberSetting lookBack = add(new NumberSetting("Look-back Lines", 5, 2, 20, 1)
            .describe("How many of the newest messages Recent Lines checks for a repeat."));
    private final ColorSetting counterColor = add(new ColorSetting("Counter Color", 0xFFB8B8C0)
            .describe("Colour of the (x3) counter."));
    // -- history
    private final NumberSetting historyLines = add(new NumberSetting("History Lines", 500, 100, 2000, 50)
            .describe("How many chat lines you can scroll back through. Vanilla keeps 100."));
    private final BooleanSetting keepChat = add(new BooleanSetting("Keep Chat On Server Switch", true)
            .describe("Keeps chat when a network moves you to another of its servers, instead of clearing it."));
    private final BooleanSetting keepOnDisconnect = add(new BooleanSetting("Keep On Disconnect", false)
            .describe("Keeps chat (and your sent-message history) when you leave a server or world, with a line "
                    + "marking where you left."));
    // -- mentions
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight Mentions", true)
            .describe("Colours your name in other players' chat, and your keywords in any line, with a bar beside it."));
    private final StringSetting keywords = add(new StringSetting("Mention Keywords", "", "words, separated by | or ,")
            .maxLength(200)
            .describe("Extra words that count as a mention, besides your name. Whole words, any case."));
    private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", 0xFFFFD21F)
            .describe("Colour of a mention and of the bar beside its line."));
    private final ModeSetting mentionSound = add(new ModeSetting("Mention Sound", "Pling", "Off", "Pling", "Bell", "XP")
            .describe("Plays a ping when you are mentioned (at most once a second)."));
    private final NumberSetting mentionVolume = add(new NumberSetting("Mention Volume", 0.6, 0.1, 1.0, 0.1)
            .describe("How loud the mention ping is."));
    // -- filter
    private final BooleanSetting filter = add(new BooleanSetting("Filter", false)
            .describe("Hides or collapses chat lines that contain one of your Filter Words."));
    private final StringSetting filterWords = add(new StringSetting("Filter Words", "", "text, separated by |")
            .describe("Text to filter, separated by |. Matches anywhere in a line, ignoring case. Start an entry with "
                    + "re: for a regular expression."));
    private final ModeSetting filterAction = add(new ModeSetting("Filter Action", "Hide", "Hide", "Collapse")
            .describe("Hide drops the line. Collapse shows [filtered] instead; hover it to read the line."));

    private static final Text COPY_TOOLTIP = Text.literal("Click to copy");
    private static final Text MENTION_TOOLTIP = Text.literal("Mentions you");
    private static final Text FILTERED = Text.literal("[filtered]").formatted(Formatting.DARK_GRAY);
    /** "<Steve> hi", "[VIP] Steve: hi", "[Team] Steve » hi": group 1 or 2 is who is talking. */
    private static final Pattern CHAT_PREFIX = Pattern.compile(
            "^(?:\\[[^\\]]{1,32}]\\s*){0,3}(?:<([A-Za-z0-9_]{1,16})>\\s|([A-Za-z0-9_]{1,16})\\s*(?::|»|>>|->|>)\\s)");
    private static final Pattern KEYWORD_SPLIT = Pattern.compile("[|,]");
    private static final long PING_COOLDOWN_MS = 1000;
    /** How many of the lines BetterChat added it remembers for stacking (Look-back Lines goes up to 20). */
    private static final int TRACKED = 20;

    /** Set by the MinecraftClient mixin while a server switch (reconfiguration or transfer) clears the HUD. */
    private static boolean switchingServer;

    /** A chat line this module added: what it said before decoration, and how many times it has been received. */
    private record Tracked(ChatHudLine line, String key, int count) {}

    /**
     * What happens to one incoming message: the text and indicator to show, and which of the stored messages
     * ({@code stackIndex}, newest first, -1 for none) it repeats and replaces.
     */
    public record Prepared(Text text, MessageIndicator indicator, int stackIndex, String key, int count, ChatHudLine replaces) {}

    /** The newest lines this module added, newest first, matched by identity (records compare by value). */
    private final Deque<Tracked> recent = new ArrayDeque<>();
    /** The line added last and its count, for the tests. */
    private Tracked last;
    /** Above 0 while this module adds a line of its own (the disconnect marker), which is never filtered or a mention. */
    private int ownLine;

    private String mentionKey;
    private Pattern mentionWords;
    private long lastPing;

    private String timeKey;
    private DateTimeFormatter timeFormatter;

    private String filterKey;
    private List<String> filterText = List.of();
    private List<Pattern> filterRegex = List.of();

    public BetterChat() {
        super("BetterChat", "Chat timestamps, stacked repeats, longer history, copy, mention highlights and a filter.", Category.MISC);
        // Filter: Hide drops matching lines before they reach chat. Action-bar (overlay) text is left alone.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !hides(message));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, receivedAt) -> !hides(message));
    }

    public static BetterChat active() {
        if (EvadeClient.modules == null) return null;
        BetterChat b = EvadeClient.modules.get(BetterChat.class);
        return b != null && b.isEnabled() ? b : null;
    }

    @Override
    protected void onDisable() { reset(); }

    /** Forget the lines added so far (chat was cleared, or the module turned off), so nothing stacks onto a stale one. */
    public void reset() {
        recent.clear();
        last = null;
    }

    /** From the chat mixin when chat is cleared: resets stacking even while the module is off. */
    public static void onChatCleared() {
        if (EvadeClient.modules == null) return;
        BetterChat b = EvadeClient.modules.get(BetterChat.class);
        if (b != null) b.reset();
    }

    /**
     * Mention Sound used to be on/off: a saved "off" becomes Off (anything else keeps the Pling default). Keywords
     * saved with commas keep working, since both , and | separate them.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement old = settings.get(mentionSound.getName());
        if (old != null && old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean() && !old.getAsBoolean())
            mentionSound.set("Off");
    }

    // ------------------------------------------------------------------ adding a message

    /**
     * From the chat mixin as {@code message} is about to be added: finds the stored message it repeats (never a
     * signed one, so the server can still delete those), and decorates it with the timestamp, counter, copy button,
     * mention colours or the filter's [filtered]. {@code lines} is chat's stored messages, newest first.
     */
    public Prepared prepare(Text message, MessageSignatureData signature, MessageIndicator indicator, List<ChatHudLine> lines) {
        String key = message.getString();
        String plain = Formatting.strip(key);
        boolean own = ownLine > 0 || isEvadeLine(plain);

        int index = -1, count = 1;
        ChatHudLine replaces = null;
        if (antiSpam.get() && signature == null) {
            int window = stackMode.is("Recent Lines") ? lookBack.getInt() : 1;
            for (int i = 0, n = Math.min(window, lines.size()); i < n; i++) {
                ChatHudLine line = lines.get(i);
                if (line.signature() != null) continue;
                Tracked t = tracked(line);
                if (t != null && t.key().equals(key)) {
                    index = i;
                    count = t.count() + 1;
                    replaces = line;
                    break;
                }
            }
        }

        boolean collapsed = !own && filter.get() && filterAction.is("Collapse") && filtered(plain);
        boolean mention = !own && !collapsed && highlight.get() && mentionsYou(plain);
        Text body = message;
        if (collapsed) {
            body = FILTERED.copy().styled(s -> s.withHoverEvent(new HoverEvent.ShowText(message)));
        } else if (mention) {
            body = recolour(message);
            ping();
        }

        MessageIndicator shown = indicator;
        // Only where the bar carries no trust information: system lines and verified chat (no indicator).
        if (mention && (indicator == null || indicator == MessageIndicator.system() || indicator == MessageIndicator.singlePlayer())) {
            shown = new MessageIndicator(highlightColor.get() & 0xFFFFFF, null, MENTION_TOOLTIP,
                    indicator == null ? null : indicator.loggedName());
        }
        return new Prepared(decorate(body, plain, count), shown, index, key, count, replaces);
    }

    /** From the chat mixin once a prepared message is in chat: {@code line} is its stored chat line. */
    public void added(ChatHudLine line, Prepared p) {
        if (p.replaces() != null) recent.removeIf(t -> t.line() == p.replaces());
        Tracked t = new Tracked(line, p.key(), p.count());
        recent.addFirst(t);
        while (recent.size() > TRACKED) recent.removeLast();
        last = t;
    }

    private Tracked tracked(ChatHudLine line) {
        for (Tracked t : recent) if (t.line() == line) return t;
        return null;
    }

    /** How many times the newest line BetterChat added has been received, stacked (for the tests). */
    public int stackCount() { return last == null ? 0 : last.count(); }

    /** The text of the newest line BetterChat added, as shown, or null (for the tests). */
    public String lastLineText() { return last == null ? null : last.line().content().getString(); }

    /** The timestamp, stack counter and copy button around {@code body}; {@code plain} is what Copy copies. */
    private Text decorate(Text body, String plain, int count) {
        MutableText out = Text.empty();
        Style chrome = Style.EMPTY.withColor(timeColor.get() & 0xFFFFFF);
        if (copyButton.get()) {
            chrome = chrome.withClickEvent(new ClickEvent.CopyToClipboard(plain == null ? "" : plain))
                    .withHoverEvent(new HoverEvent.ShowText(COPY_TOOLTIP));
        }
        if (timestamps.get()) out.append(Text.literal(timestamp()).setStyle(chrome));
        out.append(body);
        if (count > 1) out.append(Text.literal(" (x" + count + ")").withColor(counterColor.get() & 0xFFFFFF));
        if (copyButton.get() && !timestamps.get()) out.append(Text.literal(" ⧉").setStyle(chrome));
        return out;
    }

    /** "[14:05] ", "(2:05 PM) ", "14:05:09 ": the time now in the chosen format and brackets. */
    String timestamp() {
        String pattern = timeFormat.get();
        if (!pattern.equals(timeKey)) {
            try {
                timeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
            } catch (IllegalArgumentException e) {
                timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
            }
            timeKey = pattern;
        }
        String t = LocalTime.now().format(timeFormatter);
        return switch (timeBrackets.get()) {
            case "( )" -> "(" + t + ") ";
            case "< >" -> "<" + t + "> ";
            case "None" -> t + " ";
            default -> "[" + t + "] ";
        };
    }

    private static boolean isEvadeLine(String plain) { return plain != null && plain.startsWith("EVADE »"); }

    // ------------------------------------------------------------------ history and keeping chat

    /** How many messages chat keeps (vanilla's {@code limit} while the module is off). */
    public static int historyLimit(int limit) {
        BetterChat b = active();
        return b == null ? limit : Math.max(limit, b.historyLines.getInt());
    }

    /** From the MinecraftClient mixin: a server switch is clearing the HUD right now. */
    public static void setSwitchingServer(boolean switching) { switchingServer = switching; }

    /** Whether the chat clear happening right now is a server switch whose chat we keep. */
    public static boolean keepsChatNow() {
        BetterChat b = switchingServer ? active() : null;
        return b != null && b.keepChat.get();
    }

    /**
     * From the InGameHud mixin as the HUD is cleared on leaving a server or world, or on a server switch: true keeps
     * {@code chat} (Keep On Disconnect, or Keep Chat On Server Switch during a switch). A real disconnect then gets a
     * grey "--- Disconnected from ... ---" line.
     */
    public static boolean keepChatOnHudClear(ChatHud chat) {
        BetterChat b = active();
        if (b == null || chat == null) return false;
        boolean switching = switchingServer || mc.currentScreen instanceof ReconfiguringScreen;
        if (!b.keepOnDisconnect.get() && !(switching && b.keepChat.get())) return false;
        mc.getMessageHandler().processAll(); // what ChatHud.clear does first: show the messages still queued
        if (!switching) {
            b.ownLine++;
            try {
                chat.addMessage(b.leftLine());
            } finally {
                b.ownLine--;
            }
        }
        return true;
    }

    /** "--- Disconnected from Hypixel ---", or "--- Left the world ---" in singleplayer. */
    private Text leftLine() {
        ServerInfo info = mc.getCurrentServerEntry();
        String where = info == null ? null : serverName(info);
        String s = where == null ? "--- Left the world ---" : "--- Disconnected from " + where + " ---";
        return Text.literal(s).formatted(Formatting.DARK_GRAY);
    }

    /** The server's name in your list, or its address for an unnamed (direct) connection. */
    private static String serverName(ServerInfo info) {
        String name = info.name == null ? "" : Formatting.strip(info.name).strip();
        if (name.isEmpty() || name.equals(I18n.translate("selectServer.defaultName"))) {
            name = info.address == null ? "" : info.address.strip();
        }
        return name.isEmpty() ? null : name;
    }

    // ------------------------------------------------------------------ mentions

    /**
     * Whether a chat line mentions you: your name in someone else's chat message, or one of your keywords anywhere
     * except your own messages. Your own join, death and advancement messages never count.
     */
    public boolean mentionsYou(String text) {
        return mention(Formatting.strip(text)) != null;
    }

    /** A matcher on the first mention in {@code plain} (keep calling find() for the rest), or null for none. */
    private Matcher mention(String plain) {
        if (plain == null || plain.isEmpty() || isEvadeLine(plain)) return null; // Evade's own lines
        String me = mc.getSession() == null ? "" : mc.getSession().getUsername();
        Matcher prefix = CHAT_PREFIX.matcher(plain);
        String sender = null;
        int body = 0;
        if (prefix.find()) {
            sender = prefix.group(1) != null ? prefix.group(1) : prefix.group(2);
            body = prefix.end();
        }
        if (sender != null && sender.equalsIgnoreCase(me)) return null; // you said it
        Pattern words = mentionWords(sender != null ? me : "");
        if (words == null) return null;
        Matcher m = words.matcher(plain);
        m.region(body, plain.length());
        return m.find() ? m : null;
    }

    /** Your name (when given) and the keywords as whole words, case-insensitive; null when there is nothing to find. */
    private Pattern mentionWords(String name) {
        String kw = keywords.get() == null ? "" : keywords.get();
        String key = name + "\n" + kw;
        if (!key.equals(mentionKey)) {
            List<String> words = new ArrayList<>();
            if (name != null && !name.isBlank()) words.add(Pattern.quote(name));
            for (String w : KEYWORD_SPLIT.split(kw)) {
                w = w.strip();
                if (w.length() >= 2) words.add(Pattern.quote(w));
            }
            mentionWords = words.isEmpty() ? null : Pattern.compile("(?<![A-Za-z0-9_])(?:" + String.join("|", words)
                    + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            mentionKey = key;
        }
        return mentionWords;
    }

    /**
     * {@code message} with every mention in Highlight Color. Legacy § codes are resolved into styles first, so the
     * rest of the line keeps its colours; click and hover events stay where they were.
     */
    private Text recolour(Text message) {
        StringBuilder flat = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        TextVisitFactory.visitFormatted(message, Style.EMPTY, (index, style, codePoint) -> {
            int before = flat.length();
            flat.appendCodePoint(codePoint);
            for (int k = before; k < flat.length(); k++) styles.add(style);
            return true;
        });
        String s = flat.toString();
        Matcher m = mention(s);
        if (m == null) return message;
        boolean[] hit = new boolean[s.length()];
        do {
            for (int k = m.start(); k < m.end(); k++) hit[k] = true;
        } while (m.find());
        int rgb = highlightColor.get() & 0xFFFFFF;
        MutableText out = Text.empty();
        for (int i = 0; i < s.length(); ) {
            int j = i + 1;
            while (j < s.length() && hit[j] == hit[i] && styles.get(j).equals(styles.get(i))) j++;
            Style style = hit[i] ? styles.get(i).withColor(rgb) : styles.get(i);
            out.append(Text.literal(s.substring(i, j)).setStyle(style));
            i = j;
        }
        return out;
    }

    private void ping() {
        long now = System.currentTimeMillis();
        if (now - lastPing < PING_COOLDOWN_MS || AlertOutput.sound(mentionSound.get()) == null) return;
        lastPing = now;
        AlertOutput.play(mentionSound.get(), mentionSound.is("Pling") ? 1.6f : 1.0f, mentionVolume.getFloat());
    }

    // ------------------------------------------------------------------ filter

    /** Whether Hide should keep {@code message} out of chat altogether. */
    private boolean hides(Text message) {
        try {
            return message != null && isEnabled() && filter.get() && filterAction.is("Hide")
                    && filtered(Formatting.strip(message.getString()));
        } catch (RuntimeException e) {
            return false; // never lose a line to a broken pattern
        }
    }

    /** Whether {@code plain} contains one of the Filter Words (or matches one of its re: patterns); public for the tests. */
    public boolean filtered(String plain) {
        if (plain == null || plain.isEmpty() || isEvadeLine(plain)) return false;
        String raw = filterWords.get() == null ? "" : filterWords.get();
        if (raw.isBlank()) return false;
        if (!raw.equals(filterKey)) compileFilter(raw);
        String lower = plain.toLowerCase(Locale.ROOT);
        for (String t : filterText) if (lower.contains(t)) return true;
        for (Pattern p : filterRegex) if (p.matcher(plain).find()) return true;
        return false;
    }

    /** Splits Filter Words once per change; an invalid re: pattern is skipped with one warning. */
    private void compileFilter(String raw) {
        List<String> text = new ArrayList<>();
        List<Pattern> regex = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String w = part.strip();
            if (w.regionMatches(true, 0, "re:", 0, 3)) {
                String expr = w.substring(3).strip();
                if (expr.isEmpty()) continue;
                try {
                    regex.add(Pattern.compile(expr, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (PatternSyntaxException e) {
                    bad.add(expr);
                }
            } else if (!w.isEmpty()) {
                text.add(w.toLowerCase(Locale.ROOT));
            }
        }
        filterText = List.copyOf(text);
        filterRegex = List.copyOf(regex);
        filterKey = raw;
        if (!bad.isEmpty()) {
            EvadeClient.LOG.warn("BetterChat: skipped invalid filter pattern(s) {}", bad);
            Notifications.push("Chat filter", "Skipped invalid pattern: " + bad.getFirst(), false);
        }
    }
}
