package net.evadeclient.chat;

import com.google.common.collect.MapMaker;
import net.evadeclient.module.misc.ChatHeads;
import net.evadeclient.module.misc.ChatTabs;
import net.evadeclient.module.misc.SmoothChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ColorHelper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Evade remembers about each chat message for Chat Heads and Chat Tabs: the text as it arrived (before
 * BetterChat's timestamp and counter), and the player who sent it, if one can be told. Only how chat is drawn on your
 * screen uses this; nothing is sent.
 *
 * <p>The chat mixins call in at three points: {@link #received} as a message starts to be added (the raw text),
 * {@link #onAdd} with the text about to be stored (adds the head's space and the record), and {@link #rows} when a
 * message is wrapped into screen lines (marks the first line, where the head is drawn).
 */
public final class ChatLines {
    private ChatLines() {}

    /** One stored chat message: its plain text as received and who sent it (both may be unknown). */
    public static final class Info {
        public final String plain;
        public final UUID sender;
        final boolean headed;
        private SkinTextures skin;
        /** Chat Tabs: which tabs it belongs to, for the tab list version {@link #tabsVersion}. */
        public long tabBits;
        public int tabsVersion = -1;

        Info(String plain, UUID sender, boolean headed) {
            this.plain = plain;
            this.sender = sender;
            this.headed = headed;
        }
    }

    /** Stored message content (the exact Text object in its ChatHudLine) to its record. Weak, identity keys. */
    private static final Map<Text, Info> BY_CONTENT = new MapMaker().weakKeys().makeMap();
    /** The first wrapped screen line of a message with a head, to that message. Weak, identity keys. */
    private static final Map<OrderedText, Info> FIRST_ROW = new MapMaker().weakKeys().makeMap();

    /** The message the chat mixin is adding right now, as received (set just before {@link #onAdd}). */
    private static Text pendingRaw;
    /** The signed sender of the chat message about to be added, and its text (from the receive event). */
    private static UUID pendingSender;
    private static String pendingSenderText;
    /** True while a new message (not a re-wrap of old ones) is being wrapped into screen lines. */
    private static boolean fresh;
    /** Whether the newest message wrapped into screen lines got a head (for the tests). */
    private static boolean lastHeaded;

    /**
     * Who is talking at the start of a line, after up to three [tags], an optional channel ("Party >", "From") and
     * more tags: "&lt;Steve&gt; hi", "[VIP] Steve: hi", "Party &gt; [MVP+] Steve: hi", "Guild &gt; Steve [Officer]: hi",
     * "[Evade] Steve &gt; you: hi". Group 1 or 2 is the name.
     */
    private static final Pattern SENDER = Pattern.compile(
            "^(?:\\[[^\\]]{1,32}]\\s*){0,3}(?:(?:Party|Guild|Officer|Co-op|From|To)(?:\\s*>)?\\s+)?(?:\\[[^\\]]{1,32}]\\s*){0,2}"
                    + "(?:<([A-Za-z0-9_]{1,16})>\\s|([A-Za-z0-9_]{3,16})(?:\\s*\\[[^\\]]{1,24}])?\\s*(?::|»|>>|->|>)\\s)");

    // ------------------------------------------------------------------ hooks

    /** From ChatHudMixin as a message starts to be added: the text as it arrived, before any decoration. */
    public static void received(Text raw) { pendingRaw = raw; }

    /** From the chat receive event: the signed sender of the message about to be added. */
    public static void signedSender(UUID sender, Text message) {
        pendingSender = sender;
        pendingSenderText = sender == null || message == null ? null : message.getString();
    }

    /**
     * From the ChatHud mixin with the text about to be stored: records the message and, with Chat Heads on, puts the
     * head's space in front of it. Returns the text to store. Does nothing while neither Chat Heads nor Chat Tabs is
     * on.
     */
    public static Text onAdd(Text text) {
        Text raw = pendingRaw != null ? pendingRaw : text;
        pendingRaw = null;
        UUID signed = pendingSender;
        String signedText = pendingSenderText;
        pendingSender = null;
        pendingSenderText = null;
        if (text == null) return null;
        ChatHeads heads = ChatHeads.active();
        ChatTabs tabs = ChatTabs.active();
        if (heads == null && tabs == null) return text;

        String rawString = raw.getString();
        String plain = Formatting.strip(rawString);
        UUID sender = signed != null && rawString.equals(signedText) ? signed : null;
        if (sender == null && heads != null) sender = senderByName(plain);
        boolean headed = heads != null && (sender != null || !heads.onlyPlayers());
        Text out = headed ? Text.empty().append(Text.literal(heads.spacer())).append(text) : text;
        Info info = new Info(plain, sender, headed && sender != null);
        BY_CONTENT.put(out, info);
        if (tabs != null) tabs.onNewMessage(info);
        return out;
    }

    /** The record for a stored chat line, or null for one added while both modules were off. */
    public static Info info(ChatHudLine line) {
        return line == null ? null : BY_CONTENT.get(line.content());
    }

    /** Plain text to match a line by: as received when known, else as stored. */
    public static String plain(ChatHudLine line) {
        Info i = info(line);
        return i != null ? i.plain : Formatting.strip(line.content().getString());
    }

    /** From the ChatHud mixin around wrapping a new message (not a re-wrap) into screen lines. */
    public static void setFresh(boolean value) { fresh = value; }

    /** From the ChatHud mixin when {@code line} has been wrapped into {@code rows} (first line first). */
    public static void rows(ChatHudLine line, List<OrderedText> rows) {
        if (rows == null || rows.isEmpty()) return;
        if (fresh) SmoothChat.linesAdded(rows.size());
        Info info = info(line);
        lastHeaded = info != null && info.headed;
        if (lastHeaded) FIRST_ROW.put(rows.getFirst(), info);
    }

    // ------------------------------------------------------------------ heads

    /** The player list entry for a name at the start of {@code plain}, as its account id, or null. */
    static UUID senderByName(String plain) {
        String name = senderName(plain);
        if (name == null || name.equalsIgnoreCase("you")) return null;
        ClientPlayNetworkHandler nh = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry e = nh == null ? null : nh.getPlayerListEntry(name);
        return e == null ? null : e.getProfile().id();
    }

    /** The name of whoever is talking at the start of {@code plain}, or null (public for the tests). */
    public static String senderName(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        Matcher m = SENDER.matcher(plain);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /**
     * From the chat line drawers before a line's text: draws the sender's face in the space in front of it. {@code y}
     * is the text's top in chat's own coordinates, {@code opacity} the line's.
     */
    public static void drawHead(DrawContext ctx, int y, float opacity, OrderedText row) {
        if (row == null || FIRST_ROW.isEmpty()) return;
        Info info = FIRST_ROW.get(row);
        if (info == null || info.sender == null) return;
        ChatHeads heads = ChatHeads.active();
        if (heads == null || opacity <= 0.02f) return;
        SkinTextures skin = skin(info);
        if (skin == null) return;
        int size = heads.size();
        PlayerSkinDrawer.draw(ctx, skin, 0, y + (8 - size) / 2, size, ColorHelper.getWhite(Math.min(1f, opacity)));
    }

    /** The sender's skin from the player list (kept once seen, so it stays after they leave), or the default one. */
    private static SkinTextures skin(Info info) {
        ClientPlayNetworkHandler nh = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry e = nh == null ? null : nh.getPlayerListEntry(info.sender);
        if (e != null) info.skin = e.getSkinTextures();
        return info.skin != null ? info.skin : DefaultSkinHelper.getSkinTextures(info.sender);
    }

    /** Whether the newest message put on screen got a head (for the tests). */
    public static boolean lastHeadedForTest() { return lastHeaded; }
}
