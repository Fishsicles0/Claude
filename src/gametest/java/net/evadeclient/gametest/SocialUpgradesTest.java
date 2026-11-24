package net.evadeclient.gametest;

import com.mojang.authlib.GameProfile;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.misc.BetterChat;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.module.misc.HypixelStats;
import net.evadeclient.module.visuals.TabTweaks;
import net.evadeclient.social.FriendColors;
import net.evadeclient.social.SocialChat;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialJoin;
import net.evadeclient.social.SocialState;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * v2.3 social upgrades without the network: friend messages and party chat (received, sent, commands, limits),
 * Join and "Bring party here" offers (never connecting), Friend Colours in chat and the tab list, the Tab Tweaks order /
 * NPC / count options, BetterChat's Coords Guard and Hypixel clean-up, and the Chat tab of the Friends page. Uses
 * SocialClient's loopback mode and events injected as if evadeclient.com had sent them, like {@link SocialTest}.
 */
public final class SocialUpgradesTest {
    private SocialUpgradesTest() {}

    private static final UUID DEX = SocialState.uuid("11111111111111111111111111111111");
    private static final UUID MIRA = SocialState.uuid("22222222222222222222222222222222");

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    /** Runs inside a world, after {@link SocialTest#run}. Leaves loopback mode off and settings as found. */
    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            Friends.get().activate();
            SocialClient.setLoopback(true);
            SocialChat.clear();
            UUID me = c.getSession().getUuidOrNull() != null ? c.getSession().getUuidOrNull() : new UUID(0, 2);
            SocialClient.injectForTest(SocialTest.state(me));
        });

        // 1. Messages in: a friend's DM and party chat land in their conversations, unread, cleaned to plain text;
        //    a stranger's message is dropped.
        String in = ctx.computeOnClient(c -> {
            long now = System.currentTimeMillis();
            SocialClient.injectForTest("{\"type\":\"msg\",\"from\":{\"uuid\":\"11111111111111111111111111111111\",\"name\":\"Dex\"},"
                    + "\"text\":\"hi \\u00a7cthere\\u202e\",\"at\":" + now + "}");
            SocialClient.injectForTest("{\"type\":\"party.msg\",\"from\":{\"uuid\":\"11111111111111111111111111111111\",\"name\":\"Dex\"},"
                    + "\"text\":\"party time\",\"at\":" + now + "}");
            SocialClient.injectForTest("{\"type\":\"msg\",\"from\":{\"uuid\":\"99999999999999999999999999999999\",\"name\":\"Stranger\"},"
                    + "\"text\":\"spam\",\"at\":" + now + "}");
            var dm = SocialChat.lines(DEX);
            return dm.size() + "|" + (dm.isEmpty() ? "" : dm.get(0).text()) + "|" + SocialChat.unread(DEX) + "|"
                    + SocialChat.lines(SocialChat.PARTY).size() + "|" + SocialChat.lines(SocialState.uuid("99999999999999999999999999999999")).size()
                    + "|" + SocialChat.totalUnread() + "|" + (SocialChat.lastFrom() != null && SocialChat.lastFrom().equals(DEX));
        });
        check(in.equals("1|hi cthere|1|1|0|2|true"), "Messages: DM and party chat arrive as plain text, unread; strangers dropped (" + in + ")");

        // 2. Messages out: .msg, .reply and .pc in loopback mode; the 256-character cap; an empty message never goes out.
        ctx.runOnClient(c -> {
            EvadeClient.commands.handleChat(".msg Dex hello there");
            EvadeClient.commands.handleChat(".reply second");
            EvadeClient.commands.handleChat(".pc on my way");
        });
        ctx.waitTicks(3);
        String out = ctx.computeOnClient(c -> {
            long mine = SocialChat.lines(DEX).stream().filter(SocialChat.Line::mine).count();
            long party = SocialChat.lines(SocialChat.PARTY).stream().filter(SocialChat.Line::mine).count();
            boolean empty = SocialChat.send(DEX, " § ").isCompletedExceptionally();
            int capped = SocialChat.clean("x".repeat(300)).length();
            return mine + "|" + party + "|" + empty + "|" + capped;
        });
        check(out.equals("2|1|true|256"), "Messages: .msg, .reply and .pc send; empty refused; 256 characters at most (" + out + ")");

        // 3. Join offers: a friend starting to play on a shared server and a party summon put [Join] in chat behind
        //    one-time codes; a made-up code does nothing; invalid addresses are refused. Nothing connects.
        String join = ctx.computeOnClient(c -> {
            int before = SocialJoin.offersForTest();
            SocialClient.injectForTest("{\"type\":\"friend.status\",\"uuid\":\"22222222222222222222222222222222\",\"name\":\"Mira\",\"online\":true,\"server\":\"mc.example.org\"}");
            SocialClient.injectForTest("{\"type\":\"party.summon\",\"from\":{\"uuid\":\"11111111111111111111111111111111\",\"name\":\"Dex\"},\"server\":\"Play.Example.net\",\"at\":0}");
            return before + "|" + SocialJoin.validAddress("play.example.net:25570") + SocialJoin.validAddress("bad host/x")
                    + SocialJoin.validAddress("") + SocialJoin.validAddress(null);
        });
        ctx.waitTicks(2);
        String join2 = ctx.computeOnClient(c -> {
            var summon = SocialJoin.pendingSummon();
            boolean chatHasJoin = SocialJoin.lastOfferLineForTest().contains("[Join]")
                    && SocialJoin.lastOfferLineForTest().contains("play.example.net");
            return SocialJoin.offersForTest() + "|" + (summon == null ? "none" : summon.who() + "@" + summon.address()) + "|" + chatHasJoin
                    + "|" + (c.world != null);
        });
        int before = Integer.parseInt(join.split("\\|")[0]);
        check(join.endsWith("|truefalsefalsefalse") && join2.startsWith((before + 2) + "|Dex@play.example.net|true|true"),
                "Join: friend-online and party-summon offers in chat, valid addresses only, nothing connects (" + join + " / " + join2 + ")");
        ctx.runOnClient(c -> c.getNetworkHandler().sendChatCommand(SocialJoin.COMMAND + " join 0000000000000000")); // not ours: ignored
        ctx.waitTicks(1);
        check(ctx.computeOnClient(c -> c.world != null && !(c.currentScreen instanceof net.minecraft.client.gui.screen.ConfirmScreen)),
                "Join: a made-up offer code opens nothing");

        // 4. Friend Colours: a friend's name in chat and in the tab list takes the colour; the rest keeps its style.
        String colours = ctx.computeOnClient(c -> {
            Friends f = Friends.get();
            int rgb = f.friendColor.get() & 0xFFFFFF;
            Text line = FriendColors.chat(Text.literal("<Dex> hi Mira"));
            List<String> coloured = new ArrayList<>();
            line.visit((style, s) -> {
                if (style.getColor() != null && style.getColor().getRgb() == rgb) coloured.add(s);
                return Optional.empty();
            }, Style.EMPTY);
            PlayerListEntry dex = new PlayerListEntry(new GameProfile(DEX, "Dex"), false);
            Text tab = FriendColors.tab(dex, Text.literal("[VIP] Dex"));
            List<String> tabColoured = new ArrayList<>();
            tab.visit((style, s) -> {
                if (style.getColor() != null && style.getColor().getRgb() == rgb) tabColoured.add(s);
                return Optional.empty();
            }, Style.EMPTY);
            f.friendColors.set(false);
            boolean offSame = FriendColors.chat(Text.literal("<Dex> hi")).getString().equals("<Dex> hi")
                    && FriendColors.chat(Text.literal("<Dex> hi")).getSiblings().isEmpty();
            f.friendColors.set(true);
            f.highlightedPlayers.set("Notch, jeb_");
            boolean highlighted = FriendColors.isFriend(null, "notch") && !FriendColors.isFriend(null, "Dinnerbone");
            f.highlightedPlayers.set("");
            return coloured + "|" + tabColoured + "|" + offSame + "|" + highlighted;
        });
        check(colours.equals("[Dex, Mira]|[Dex]|true|true"), "Friend Colours: chat and tab names coloured, off when off, Highlighted Players (" + colours + ")");

        // 5. Tab Tweaks: yourself first, friends next, NPC entries hidden, player count line, scale.
        String tab = ctx.computeOnClient(c -> {
            TabTweaks t = EvadeClient.modules.get(TabTweaks.class);
            boolean wasOn = t.isEnabled();
            t.setEnabled(true);
            t.selfFirst.set(true);
            t.friendsFirst.set(true);
            t.hideNpcs.set(true);
            t.playerCount.set(true);
            UUID me = c.player.getUuid();
            List<PlayerListEntry> list = List.of(
                    new PlayerListEntry(new GameProfile(UUID.fromString("4a1b2c3d-0000-4000-8000-000000000001"), "Alex"), false),
                    new PlayerListEntry(new GameProfile(UUID.fromString("4a1b2c3d-0000-2000-8000-000000000002"), "abcdefghij"), false),
                    new PlayerListEntry(new GameProfile(UUID.fromString("4a1b2c3d-0000-4000-8000-000000000003"), "§8[NPC]"), false),
                    new PlayerListEntry(new GameProfile(DEX, "Dex"), false),
                    new PlayerListEntry(new GameProfile(me, c.player.getGameProfile().name()), false));
            List<String> names = TabTweaks.arrange(list).stream().map(e -> e.getProfile().name()).toList();
            String header = TabTweaks.header(Text.literal("Welcome")).getString();
            t.scale.set(0.75);
            float k = TabTweaks.listScale();
            t.scale.reset();
            t.selfFirst.reset();
            t.friendsFirst.reset();
            t.hideNpcs.reset();
            t.playerCount.reset();
            List<String> plain = TabTweaks.arrange(list).stream().map(e -> e.getProfile().name()).toList();
            t.setEnabled(wasOn);
            return names + "|" + header.replace("\n", "/") + "|" + k + "|" + plain.size();
        });
        String meName = ctx.computeOnClient(c -> c.player.getGameProfile().name());
        check(tab.equals("[" + meName + ", Dex, Alex]|3 players/Welcome|0.75|5"),
                "Tab Tweaks: you first, friends next, NPCs hidden, player count, scale; untouched when off (" + tab + ")");

        // 6. BetterChat: Coords Guard holds a leaking message back once, then lets the same one through; Hypixel clean-up.
        String guard = ctx.computeOnClient(c -> {
            BetterChat b = EvadeClient.modules.get(BetterChat.class);
            boolean wasOn = b.isEnabled();
            b.setEnabled(true);
            boolean first = b.guard("base is at 1234 70 -5678 come", 1235.4, -5676.2);
            boolean second = b.guard("base is at 1234 70 -5678 come", 1235.4, -5676.2);
            boolean other = b.guard("i have 1234 coins and -5678 debt", 10, 20); // not where you are
            boolean spawn = BetterChat.leaksCoords("at 12 -40", 12, -40);
            boolean oneAxis = BetterChat.leaksCoords("x is 1234", 1234, -5678);
            boolean command = b.guard(".msg Dex 1234 -5678", 1234, -5678);
            String noise = "" + BetterChat.hypixelNoise("[MVP+] Steve joined the lobby!")
                    + BetterChat.hypixelNoise(">>> [MVP++] Steve slid into the lobby! <<<")
                    + BetterChat.hypixelNoise("[Mystery Box] Steve found a Legendary Cloak!")
                    + BetterChat.hypixelNoise("Watchdog has banned 4,321 players in the last 7 days.")
                    + BetterChat.hypixelNoise("[MVP+] Steve: I joined the lobby!")
                    + BetterChat.hypixelNoise("Steve: buy ranks at store.hypixel.net");
            b.setEnabled(wasOn);
            return first + "|" + second + "|" + other + "|" + spawn + "|" + oneAxis + "|" + command + "|" + noise;
        });
        check(guard.equals("false|true|true|false|false|true|truetruetruetruefalsefalse"),
                "BetterChat: Coords Guard once then send anyway, spawn/one axis/commands ignored; Hypixel clean-up rules (" + guard + ")");

        // 7. Hypixel Stats' chat stat never changes chat off Hypixel.
        boolean hs = ctx.computeOnClient(c -> {
            Text t = Text.literal("[VIP] Dex: gg");
            return HypixelStats.chatDecorate(t) == t;
        });
        check(hs, "Hypixel Stats: Show In Chat leaves chat alone off Hypixel");

        // 8. The Chat tab: the conversation list, then Dex's conversation open (unread cleared).
        ctx.runOnClient(c -> SocialChat.setOpen(null));
        ctx.setScreen(() -> { EvadeMenu.openFriendsForTest(3); return new EvadeMenu(); });
        ctx.waitTicks(8);
        ctx.runOnClient(c -> c.getToastManager().clear());
        ctx.takeScreenshot("social-chat-list");
        ctx.runOnClient(c -> SocialChat.setOpen(DEX));
        ctx.waitTicks(8);
        ctx.takeScreenshot("social-chat-open");
        check(ctx.computeOnClient(c -> SocialChat.unread(DEX) == 0), "Friends page: opening a conversation marks it read");
        ctx.setScreen(() -> null);

        ctx.runOnClient(c -> {
            EvadeMenu.openAllForTest(); // later tests expect the menu to open on its usual page
            SocialChat.clear();
            SocialJoin.dismissSummon();
            SocialClient.setLoopback(false);
        });
    }
}
