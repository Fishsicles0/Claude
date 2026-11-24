package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.social.FriendColors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Player list (tab) improvements: ping as a coloured number instead of signal bars, yourself and your friends first,
 * fake NPC entries hidden, a player count line, a size slider, and hiding the server's header or footer text. Purely
 * how the list is drawn from what the server already sent; nothing is sent to the server.
 */
public class TabTweaks extends Module {
    /** Vanilla's width for the ping bars plus the gap before them (the {@code 13} in {@code PlayerListHud.render}). */
    public static final int VANILLA_PING_SPACE = 13;

    public final BooleanSetting numericPing = add(new BooleanSetting("Numeric Ping", true)
            .describe("Show each player's ping in milliseconds, coloured by quality, instead of signal bars."));
    public final BooleanSetting pingColours = add(new BooleanSetting("Ping Colours", true)
            .describe("Colour the ping number by quality: green, yellow, orange, red. Off: plain grey."));
    public final BooleanSetting selfFirst = add(new BooleanSetting("Yourself First", false)
            .describe("Moves your own name to the top of the list."));
    public final BooleanSetting friendsFirst = add(new BooleanSetting("Friends First", false)
            .describe("Moves your Evade friends (and Highlighted Players) to the top, after you."));
    public final BooleanSetting hideNpcs = add(new BooleanSetting("Hide NPC Entries", false)
            .describe("Hides fake entries servers add for NPCs and decoration: ones without a valid player name or a real account."));
    public final BooleanSetting playerCount = add(new BooleanSetting("Player Count", false)
            .describe("A line above the list with how many players are in it."));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 1.5, 0.05)
            .describe("Size of the player list."));
    public final BooleanSetting hideHeader = add(new BooleanSetting("Hide Header", false)
            .describe("Hide the text the server puts above the player list."));
    public final BooleanSetting hideFooter = add(new BooleanSetting("Hide Footer", false)
            .describe("Hide the text the server puts below the player list."));

    private static TabTweaks instance;

    public TabTweaks() {
        super("Tab Tweaks", "Numeric ping, yourself and friends first, hide NPC entries, player count and size for the player list.", Category.VISUALS);
    }

    public static TabTweaks active() {
        TabTweaks t = instance;
        if (t == null) {
            if (EvadeClient.modules == null) return null;
            t = instance = EvadeClient.modules.get(TabTweaks.class);
            if (t == null) return null;
        }
        return t.isEnabled() ? t : null;
    }

    /** Whether pings are drawn as numbers right now. */
    public static boolean numeric() {
        TabTweaks t = active();
        return t != null && t.numericPing.get();
    }

    /** Whether the player list header is hidden right now. */
    public static boolean headerHidden() {
        TabTweaks t = active();
        return t != null && t.hideHeader.get();
    }

    /** Whether the player list footer is hidden right now. */
    public static boolean footerHidden() {
        TabTweaks t = active();
        return t != null && t.hideFooter.get();
    }

    /** "87ms"; ten seconds or more as "12s" so a lagging player doesn't stretch every column. */
    public static String pingText(int ms) {
        return ms >= 10_000 ? (ms / 1000) + "s" : ms + "ms";
    }

    /** {@link #pingColor}, or plain grey with Ping Colours off. */
    public static int shownPingColor(int ms) {
        TabTweaks t = active();
        return t != null && !t.pingColours.get() ? 0xFFAAAAAA : pingColor(ms);
    }

    /** Green under 150 ms, yellow under 300, gold under 600, red above (the same steps as the vanilla bars). */
    public static int pingColor(int ms) {
        if (ms < 150) return 0xFF55FF55;
        if (ms < 300) return 0xFFFFFF55;
        if (ms < 600) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    // ------------------------------------------------------------------ order, NPCs, count, scale

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** How many entries the list showed last frame (after Hide NPC Entries), for Player Count. */
    private static int shownCount;

    /**
     * Whether a list entry looks like a fake one a server added for an NPC or decoration: no valid Minecraft name, or a
     * version-2 UUID (real accounts are version 4, offline-mode players version 3).
     */
    public static boolean looksLikeNpc(PlayerListEntry e) {
        if (e == null || e.getProfile() == null) return true;
        UUID id = e.getProfile().id();
        String name = e.getProfile().name();
        return name == null || !NAME.matcher(name).matches() || (id != null && id.version() == 2);
    }

    /**
     * The list's entries as Tab Tweaks orders them: NPCs dropped (Hide NPC Entries), then you, then friends, then
     * everyone else in the server's order. The same list (same order) while nothing is switched on.
     */
    public static List<PlayerListEntry> arrange(List<PlayerListEntry> entries) {
        TabTweaks t = active();
        if (t == null || entries == null) {
            shownCount = entries == null ? 0 : entries.size();
            return entries;
        }
        boolean npc = t.hideNpcs.get(), self = t.selfFirst.get(), friends = t.friendsFirst.get();
        if (!npc && !self && !friends) {
            shownCount = entries.size();
            return entries;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID me = mc.player != null ? mc.player.getUuid() : null;
        List<PlayerListEntry> mine = new ArrayList<>(1), friendList = new ArrayList<>(), rest = new ArrayList<>(entries.size());
        for (PlayerListEntry e : entries) {
            boolean isMe = me != null && me.equals(e.getProfile().id());
            if (npc && !isMe && looksLikeNpc(e)) continue;
            if (self && isMe) mine.add(e);
            else if (friends && FriendColors.isFriend(e.getProfile().id(), e.getProfile().name())) friendList.add(e);
            else rest.add(e);
        }
        List<PlayerListEntry> out = new ArrayList<>(entries.size());
        out.addAll(mine);
        out.addAll(friendList);
        out.addAll(rest);
        shownCount = out.size();
        return out;
    }

    /**
     * The header as drawn: null with Hide Header on, and with Player Count on, "12 players" above it (or alone).
     */
    public static Text header(Text header) {
        TabTweaks t = active();
        if (t == null) return header;
        Text shown = t.hideHeader.get() ? null : header;
        if (!t.playerCount.get()) return shown;
        Text count = Text.literal(shownCount + (shownCount == 1 ? " player" : " players")).formatted(Formatting.GRAY);
        return shown == null ? count : Text.empty().append(count).append("\n").append(shown);
    }

    /** The list's size (Scale), 1 while the module is off. */
    public static float listScale() {
        TabTweaks t = active();
        return t == null ? 1f : (float) Math.max(0.5, Math.min(1.5, t.scale.get()));
    }

    /**
     * Space the ping column needs: the widest number in the list, plus one pixel at the right edge and two before
     * it, like the bars. Never narrower than vanilla, so the unknown-ping icon still fits.
     */
    public static int pingSpace() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var handler = mc.getNetworkHandler();
        if (handler == null) return VANILLA_PING_SPACE;
        TextRenderer tr = mc.textRenderer;
        int widest = 0;
        for (PlayerListEntry e : handler.getListedPlayerListEntries()) {
            if (e.getLatency() >= 0) widest = Math.max(widest, tr.getWidth(pingText(e.getLatency())));
        }
        return Math.max(VANILLA_PING_SPACE, widest + 3);
    }
}
