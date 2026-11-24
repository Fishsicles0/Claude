package net.evadeclient.social;

import com.google.gson.JsonObject;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Friend direct messages and party chat, carried over the social connection (never through the Minecraft server).
 * Conversations live in memory only: nothing is written to disk, and evadeclient.com relays each message to whoever is
 * online and forgets it. Plain text, 256 characters at most; blocked players can't reach you.
 *
 * <p>Render thread, except {@link #receive} (socket thread), which hands over to the render thread.
 */
public final class SocialChat {
    /** The conversation key of party chat. */
    public static final UUID PARTY = new UUID(0, 0);
    public static final int MAX_CHARS = 256;
    private static final int KEEP = 100;
    /** Formatting codes, control and invisible/bidi characters: never sent or shown, so a message stays plain text. */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}§​-‏ -‮⁠-⁯﻿￹-￻]");

    /** One message: who sent it (null for you), as shown, and when. */
    public record Line(UUID from, String name, String text, long at, boolean mine) {}

    private static final Map<UUID, Deque<Line>> CONVOS = new LinkedHashMap<>();
    private static final Map<UUID, Integer> UNREAD = new LinkedHashMap<>();
    private static UUID open;
    private static UUID lastFrom;

    private SocialChat() {}

    /** {@code text} as a message: plain, one line, trimmed, cut to 256 characters (code points). Empty when nothing is left. */
    public static String clean(String text) {
        if (text == null) return "";
        String s = UNSAFE.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (s.codePointCount(0, s.length()) > MAX_CHARS) s = s.substring(0, s.offsetByCodePoints(0, MAX_CHARS));
        return s;
    }

    // ------------------------------------------------------------------ reading

    /** The messages of one conversation (a friend's UUID or {@link #PARTY}), oldest first. */
    public static synchronized List<Line> lines(UUID convo) {
        Deque<Line> d = CONVOS.get(convo);
        return d == null ? List.of() : List.copyOf(d);
    }

    /** Conversations with at least one message, most recent first. */
    public static synchronized List<UUID> conversations() {
        List<UUID> out = new ArrayList<>(CONVOS.keySet());
        out.sort((a, b) -> Long.compare(last(b), last(a)));
        return out;
    }

    private static long last(UUID c) {
        Deque<Line> d = CONVOS.get(c);
        return d == null || d.isEmpty() ? 0 : d.peekLast().at();
    }

    public static synchronized int unread(UUID convo) { return UNREAD.getOrDefault(convo, 0); }

    public static synchronized int totalUnread() {
        int n = 0;
        for (int v : UNREAD.values()) n += v;
        return n;
    }

    /** The conversation the Friends page shows (its messages count as read), or null. */
    public static synchronized UUID open() { return open; }

    public static synchronized void setOpen(UUID convo) {
        open = convo;
        if (convo != null) UNREAD.remove(convo);
    }

    /** The Friends page is drawing {@code convo} right now (each frame): what arrives there meanwhile is read at once. */
    public static synchronized void viewing(UUID convo) {
        open = convo;
        viewedAt = System.currentTimeMillis();
        UNREAD.remove(convo);
    }

    /** Whether {@code convo} is on screen in the Friends page. */
    private static synchronized boolean onScreen(UUID convo) {
        return convo.equals(open) && System.currentTimeMillis() - viewedAt < 500;
    }

    private static long viewedAt;

    /** Who last messaged you directly (for {@code .reply}), or null. */
    public static synchronized UUID lastFrom() { return lastFrom; }

    /** Forgets every conversation (Friends switched off or signed out). */
    public static synchronized void clear() {
        CONVOS.clear();
        UNREAD.clear();
        open = null;
        lastFrom = null;
    }

    private static synchronized void add(UUID convo, Line line, boolean unread) {
        Deque<Line> d = CONVOS.computeIfAbsent(convo, k -> new ArrayDeque<>());
        d.addLast(line);
        while (d.size() > KEEP) d.removeFirst();
        if (unread) UNREAD.merge(convo, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends {@code text} to a friend ({@code to}) or to your party ({@link #PARTY}). Completes when evadeclient.com has
     * handed it on, and then it shows in the conversation; fails at once (no request) when the text is empty.
     */
    public static CompletableFuture<JsonObject> send(UUID to, String text) {
        String t = clean(text);
        if (t.isEmpty()) return CompletableFuture.failedFuture(new SocialClient.SocialException("Type a message", 400, 0));
        if (to == null) return CompletableFuture.failedFuture(new SocialClient.SocialException("Pick a friend to message", 400, 0));
        JsonObject b = new JsonObject();
        b.addProperty("text", t);
        CompletableFuture<JsonObject> f;
        if (PARTY.equals(to)) {
            f = SocialClient.call("party.msg", b);
        } else {
            b.addProperty("uuid", SocialState.hex(to));
            f = SocialClient.call("msg", b);
        }
        return f.thenApply(r -> {
            SocialState s = SocialClient.state();
            add(to, new Line(s.me(), "You", t, System.currentTimeMillis(), true), false);
            return r;
        });
    }

    // ------------------------------------------------------------------ receiving

    /** A msg or party.msg event (any thread). Messages from yourself (another game you're signed in on) are dropped. */
    static void receive(boolean party, JsonObject o) {
        JsonObject from = SocialState.obj(o, "from");
        UUID u = SocialState.uuid(SocialState.str(from, "uuid"));
        String text = clean(SocialState.str(o, "text"));
        if (u == null || text.isEmpty()) return;
        String name = MojangNames.shown(u, SocialState.str(from, "name"));
        long at = SocialState.lng(o, "at");
        MinecraftClient.getInstance().execute(() -> deliver(party, u, name.isEmpty() ? "Someone" : name, text, at > 0 ? at : System.currentTimeMillis()));
    }

    /** Render thread: files the message, then the toast and the chat line (each optional). Public for the tests. */
    public static void deliver(boolean party, UUID from, String name, String text, long at) {
        SocialState s = SocialClient.state();
        if (from.equals(s.me())) return;
        if (s.blocked().stream().anyMatch(p -> p.uuid().equals(from))) return; // blocked a moment ago
        if (!party && !s.isFriend(from) && s.loaded()) return; // not friends any more
        UUID convo = party ? PARTY : from;
        boolean reading = onScreen(convo);
        add(convo, new Line(from, name, text, at, false), !reading);
        if (!party) synchronized (SocialChat.class) { lastFrom = from; }
        Friends f = Friends.get();
        boolean toast = f == null || f.messageToasts.get(), chat = f == null || f.messagesInChat.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        // Streamer Mode: the toast says a message came, not what it says.
        String preview = net.evadeclient.module.misc.StreamerMode.hidesMessagePreviews() ? "New message (hidden: Streamer Mode)" : text;
        if (toast && !reading) Notifications.push(party ? "Party · " + name : name, preview, Notifications.Level.SUCCESS, 5000);
        if (chat && mc.inGameHud != null && mc.world != null) mc.inGameHud.getChatHud().addMessage(chatLine(party, name, text));
    }

    /**
     * "[Evade] Party > Steve: hi" / "[Evade] Steve > you: hi", with a click that starts a reply in the chat box. In
     * Streamer Mode the text is replaced by "(hidden)" and shown only when you hover over it.
     */
    public static Text chatLine(boolean party, String name, String text) {
        if (net.evadeclient.module.misc.StreamerMode.hidesMessagePreviews()) return hiddenLine(party, name, text);
        String reply = party ? ".pc " : ".msg " + name + " ";
        Style click = Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand(reply))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(party ? "Click to reply to your party" : "Click to reply to " + name)));
        MutableText out = Text.literal("[Evade] ").formatted(Formatting.DARK_GRAY).setStyle(click.withColor(Formatting.DARK_GRAY));
        out.append(Text.literal(party ? "Party > " + name : name + " > you").setStyle(click.withColor(party ? Formatting.BLUE : Formatting.LIGHT_PURPLE)));
        out.append(Text.literal(": ").setStyle(click.withColor(Formatting.GRAY)));
        out.append(Text.literal(text).setStyle(click.withColor(Formatting.WHITE)));
        return out;
    }

    /** {@link #chatLine} with the text kept off screen: hover "(message hidden)" to read it, click to reply. */
    private static Text hiddenLine(boolean party, String name, String text) {
        String reply = party ? ".pc " : ".msg " + name + " ";
        Style click = Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand(reply));
        MutableText out = Text.literal("[Evade] ").setStyle(click.withColor(Formatting.DARK_GRAY));
        out.append(Text.literal(party ? "Party > " + name : name + " > you").setStyle(click.withColor(party ? Formatting.BLUE : Formatting.LIGHT_PURPLE)));
        out.append(Text.literal(": ").setStyle(click.withColor(Formatting.GRAY)));
        out.append(Text.literal("(message hidden: hover to read)").setStyle(click.withColor(Formatting.DARK_GRAY).withItalic(true)
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(text)))));
        return out;
    }

    /** Prints a sent message in chat (from {@code .msg} and {@code .pc}), so a conversation reads both ways there. */
    public static void echo(UUID to, String toName, String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        boolean party = PARTY.equals(to);
        MutableText out = Text.literal("[Evade] ").formatted(Formatting.DARK_GRAY);
        out.append(Text.literal(party ? "Party > You" : "You > " + toName).formatted(party ? Formatting.BLUE : Formatting.LIGHT_PURPLE));
        out.append(Text.literal(": ").formatted(Formatting.GRAY));
        out.append(Text.literal(clean(text)).formatted(Formatting.GRAY));
        mc.inGameHud.getChatHud().addMessage(out);
    }

    /** For commands: prints a failed send in red. */
    static void failed(Throwable e) { CommandManager.print("§c" + SocialClient.message(e)); }
}
