package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat upgrades: timestamps, repeated messages stacked into one line with a (x3) counter, a longer history, click to
 * copy a message, highlighted mentions of your name (or your own keywords) with a ping, and chat that survives a
 * proxy server switch.
 */
public class BetterChat extends Module {
    private final BooleanSetting timestamps = add(new BooleanSetting("Timestamps", true));
    private final BooleanSetting antiSpam = add(new BooleanSetting("Stack Repeats", true));
    private final NumberSetting historyLines = add(new NumberSetting("History Lines", 500, 100, 2000, 50)
            .describe("How many chat messages you can scroll back through. Vanilla keeps 100."));
    private final BooleanSetting copyButton = add(new BooleanSetting("Copy Button", true)
            .describe("Click a message's timestamp (or the ⧉ after it without timestamps) to copy the message."));
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight Mentions", true)
            .describe("Marks chat that mentions your name or one of your keywords with a gold bar."));
    private final BooleanSetting mentionSound = add(new BooleanSetting("Mention Sound", true)
            .describe("Plays a ping when you are mentioned."));
    private final StringSetting keywords = add(new StringSetting("Mention Keywords", "", "words, comma-separated")
            .maxLength(200)
            .describe("Extra words that count as a mention, besides your name."));
    private final BooleanSetting keepChat = add(new BooleanSetting("Keep Chat On Server Switch", true)
            .describe("Keeps chat when a network moves you to another of its servers, instead of clearing it."));

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Style COPY_HINT_STYLE = Style.EMPTY.withColor(Formatting.DARK_GRAY);
    private static final Text COPY_TOOLTIP = Text.literal("Click to copy");
    /** "<Steve> hi", "[VIP] Steve: hi", "[Team] Steve » hi": group 1 or 2 is who is talking. */
    private static final Pattern CHAT_PREFIX = Pattern.compile(
            "^(?:\\[[^\\]]{1,32}]\\s*){0,3}(?:<([A-Za-z0-9_]{1,16})>\\s|([A-Za-z0-9_]{1,16})\\s*(?::|»|>>|->|>)\\s)");
    private static final long PING_COOLDOWN_MS = 1000;

    /** Set by the MinecraftClient mixin while a server switch (reconfiguration or transfer) clears the HUD. */
    private static boolean switchingServer;

    private String lastText;
    private int repeats;
    /** The chat line this module added last; a repeat is only stacked onto it if it is still the newest line. */
    private ChatHudLine lastLine;
    private String mentionKey;
    private Pattern mentionWords;
    private long lastPing;

    public BetterChat() {
        super("BetterChat", "Chat timestamps, stacked repeats, longer history, copy and mention highlights.", Category.MISC);
    }

    public static BetterChat active() {
        if (EvadeClient.modules == null) return null;
        BetterChat b = EvadeClient.modules.get(BetterChat.class);
        return b != null && b.isEnabled() ? b : null;
    }

    @Override
    protected void onDisable() { reset(); }

    /** Forget the last line (chat was cleared, or the module turned off), so nothing is stacked onto a stale one. */
    public void reset() {
        lastText = null;
        repeats = 0;
        lastLine = null;
    }

    /** From the chat mixin when chat is cleared: resets stacking even while the module is off. */
    public static void onChatCleared() {
        if (EvadeClient.modules == null) return;
        BetterChat b = EvadeClient.modules.get(BetterChat.class);
        if (b != null) b.reset();
    }

    /**
     * Whether {@code message} repeats the last line this module added, and that line is still the newest one in
     * chat ({@code newest}), so it should be removed and replaced by a stacked copy.
     */
    public boolean repeatsLast(Text message, ChatHudLine newest) {
        String s = message.getString();
        if (antiSpam.get() && lastLine != null && newest == lastLine && s.equals(lastText)) {
            repeats++;
            return true;
        }
        lastText = s;
        repeats = 1;
        lastLine = null;
        return false;
    }

    /** From the chat mixin once a decorated message has been added: {@code line} is that message's chat line. */
    public void added(ChatHudLine line) { lastLine = line; }

    /** How many times the newest stacked message has been received in a row (for the tests). */
    public int stackCount() { return repeats; }

    /** The text of the last line this module added, as shown, or null (for the tests). */
    public String lastLineText() { return lastLine == null ? null : lastLine.content().getString(); }

    /** The timestamp, stack counter, copy button and mention bar around {@code message}. */
    public Text decorate(Text message) {
        String plain = Formatting.strip(message.getString()); // without legacy § codes, as it reads
        MutableText out = Text.empty();
        if (highlight.get() && mentionsYou(plain)) {
            out.append(Text.literal("▌ ").formatted(Formatting.GOLD));
            if (mentionSound.get()) ping();
        }
        Style copy = copyButton.get()
                ? COPY_HINT_STYLE.withClickEvent(new ClickEvent.CopyToClipboard(plain)).withHoverEvent(new HoverEvent.ShowText(COPY_TOOLTIP))
                : COPY_HINT_STYLE;
        if (timestamps.get()) out.append(Text.literal("[" + LocalTime.now().format(TIME) + "] ").setStyle(copy));
        out.append(message);
        if (antiSpam.get() && repeats > 1) out.append(Text.literal(" (x" + repeats + ")").formatted(Formatting.GRAY));
        if (copyButton.get() && !timestamps.get()) out.append(Text.literal(" ⧉").setStyle(copy));
        return out;
    }

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
     * Whether a chat line mentions you: your name in someone else's chat message, or one of your keywords anywhere
     * except your own messages. Your own join, death and advancement messages never count.
     */
    public boolean mentionsYou(String text) {
        String plain = Formatting.strip(text);
        if (plain == null || plain.isEmpty() || plain.startsWith("EVADE »")) return false; // Evade's own lines
        String me = mc.getSession() == null ? "" : mc.getSession().getUsername();
        Matcher prefix = CHAT_PREFIX.matcher(plain);
        String sender = null;
        String body = plain;
        if (prefix.find()) {
            sender = prefix.group(1) != null ? prefix.group(1) : prefix.group(2);
            body = plain.substring(prefix.end());
        }
        if (sender != null && sender.equalsIgnoreCase(me)) return false; // you said it
        Pattern words = mentionWords(sender != null ? me : "");
        return words != null && words.matcher(body).find();
    }

    /** Your name (when given) and the keywords as whole words, case-insensitive; null when there is nothing to find. */
    private Pattern mentionWords(String name) {
        String kw = keywords.get() == null ? "" : keywords.get();
        String key = name + "\n" + kw;
        if (!key.equals(mentionKey)) {
            List<String> words = new ArrayList<>();
            if (name != null && !name.isBlank()) words.add(Pattern.quote(name));
            for (String w : kw.split(",")) {
                w = w.strip();
                if (w.length() >= 2) words.add(Pattern.quote(w));
            }
            mentionWords = words.isEmpty() ? null : Pattern.compile("(?<![A-Za-z0-9_])(?:" + String.join("|", words)
                    + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            mentionKey = key;
        }
        return mentionWords;
    }

    private void ping() {
        long now = System.currentTimeMillis();
        if (now - lastPing < PING_COOLDOWN_MS) return;
        lastPing = now;
        Runnable play = () -> mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.6f));
        if (mc.isOnThread()) play.run(); else mc.execute(play);
    }
}
