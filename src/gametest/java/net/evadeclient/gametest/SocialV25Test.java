package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.api.ApiInteractions;
import net.evadeclient.api.ApiPrompts;
import net.evadeclient.api.ApiWidgets;
import net.evadeclient.api.ServerApi;
import net.evadeclient.config.CloudBackup;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.gui.OpenToFriendsScreen;
import net.evadeclient.gui.PlayerCardScreen;
import net.evadeclient.gui.ServerPromptScreen;
import net.evadeclient.gui.title.Discover;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.replay.RenderQueue;
import net.evadeclient.social.OpenToFriends;
import net.evadeclient.social.PlayerCards;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialJoin;
import net.evadeclient.social.SocialPresence;
import net.evadeclient.social.SocialState;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v2.5 social, servers and media, without the network: richer presence, Open to Friends (e4mc's chat line, world
 * invites), player cards and hiding, Server API v2 (prompts, widgets, player-card entries), cloud backup's packing
 * and encryption, the Discover list parser and the replay render queue. Loopback mode throughout.
 */
public final class SocialV25Test {
    private SocialV25Test() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static final String DEX = "11111111111111111111111111111111";

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            Friends.get().activate();
            SocialClient.setLoopback(true);
            UUID me = c.getSession().getUuidOrNull() != null ? c.getSession().getUuidOrNull() : new UUID(0, 2);
            SocialClient.injectForTest(SocialTest.state(me));
            OpenToFriends.resetForTest();
        });

        // 1. Richer presence: what you're doing, and a friend's activity shown as "On a server · ... · 40m".
        String presence = ctx.computeOnClient(c -> {
            SocialPresence.forceForTest(null); // counts as input just now
            String mine = SocialPresence.current(c).wire;
            SocialPresence.forceForTest(SocialPresence.Activity.AFK);
            String afk = SocialPresence.current(c).wire;
            SocialPresence.forceForTest(null);
            long since = System.currentTimeMillis() - 40 * 60_000L;
            SocialClient.injectForTest("{\"type\":\"friend.status\",\"uuid\":\"" + DEX + "\",\"name\":\"Dex\",\"online\":true,"
                    + "\"server\":\"play.example.net\",\"activity\":\"server\",\"activeSince\":" + since + "}");
            SocialState.Person dex = SocialClient.state().friends().stream().filter(p -> p.name().equals("Dex")).findFirst().orElseThrow();
            String line = SocialPresence.describe(dex);
            // An older client's friend.status (no activity) clears it: just "Online · server".
            SocialClient.injectForTest("{\"type\":\"friend.status\",\"uuid\":\"" + DEX + "\",\"name\":\"Dex\",\"online\":true,\"server\":\"play.example.net\"}");
            String plain = SocialPresence.describe(SocialClient.state().friends().stream().filter(p -> p.name().equals("Dex")).findFirst().orElseThrow());
            return mine + "|" + afk + "|" + line + "|" + plain;
        });
        check(presence.startsWith("singleplayer|afk|On a server") && presence.contains("40m") && presence.endsWith("|Online  ·  play.example.net"),
                "Presence: singleplayer, AFK, and a friend's activity with how long (" + presence + ")");
        check(SocialPresence.duration(65 * 60_000L).equals("1h 5m") && SocialPresence.duration(30_000L) == null, "Presence: durations read 1h 5m, nothing under a minute");

        // 2. Open to Friends: the pause-menu button, the "turn it on in the launcher" screen without e4mc.
        ctx.setScreen(() -> new GameMenuScreen(true));
        ctx.waitTicks(2);
        boolean button = ctx.computeOnClient(c -> Screens.getButtons(c.currentScreen).stream()
                .anyMatch(b -> b.getMessage().getString().startsWith("Open to Friends")));
        check(button, "Open to Friends: the pause menu has the button in singleplayer");
        ctx.runOnClient(c -> OpenToFriends.e4mcForTest(false));
        ctx.setScreen(() -> new OpenToFriendsScreen(null));
        ctx.waitTicks(2);
        ctx.takeScreenshot("v25-open-to-friends-no-e4mc");
        check(ctx.computeOnClient(c -> c.currentScreen instanceof OpenToFriendsScreen), "Open to Friends: explains how to turn e4mc on");

        // 3. e4mc's "Local game hosted on domain [...]" line: the address is read (also when e4mc hides it behind
        //    "click to copy"); other lines are left alone.
        String domains = ctx.computeOnClient(c -> {
            OpenToFriends.e4mcForTest(true);
            Text shown = Text.literal("quiet-fox.eu.e4mc.link").setStyle(Style.EMPTY.withClickEvent(new ClickEvent.CopyToClipboard("quiet-fox.eu.e4mc.link")));
            c.inGameHud.getChatHud().addMessage(Text.translatable("text.e4mc_minecraft.domainAssigned", shown));
            String a = OpenToFriends.announcedForTest();
            Text hidden = Text.literal("hidden, click to copy").setStyle(Style.EMPTY.withClickEvent(new ClickEvent.CopyToClipboard("Brave-Owl.us.e4mc.link")));
            OpenToFriends.onChat(Text.translatable("text.e4mc_minecraft.domainAssigned", hidden));
            String b = OpenToFriends.announcedForTest();
            OpenToFriends.onChat(Text.translatable("text.e4mc_minecraft.domainAssigned", Text.literal("evil.example.com")));
            String still = OpenToFriends.announcedForTest();
            OpenToFriends.onChat(Text.translatable("text.e4mc_minecraft.closeServer"));
            return a + "|" + b + "|" + still + "|" + OpenToFriends.announcedForTest();
        });
        check(domains.equals("quiet-fox.eu.e4mc.link|brave-owl.us.e4mc.link|brave-owl.us.e4mc.link|null"),
                "Open to Friends: reads e4mc's address, ignores other hosts, forgets it when e4mc stops (" + domains + ")");
        check(OpenToFriends.valid("a.e4mc.link") && !OpenToFriends.valid("play.example.net") && !OpenToFriends.valid("x.e4mc.link.evil.com"),
                "Open to Friends: only e4mc addresses count");

        // 4. A world invite from a friend: a toast, a [Join] line in chat, and an entry on the Requests tab. Only e4mc
        //    addresses from people you haven't blocked are taken.
        String invite = ctx.computeOnClient(c -> {
            OpenToFriends.resetForTest();
            long now = System.currentTimeMillis();
            SocialClient.injectForTest("{\"type\":\"world.invite\",\"from\":{\"uuid\":\"" + DEX + "\",\"name\":\"Dex\"},\"address\":\"Quiet-Fox.eu.e4mc.link\",\"world\":\"Island\",\"at\":" + now + "}");
            SocialClient.injectForTest("{\"type\":\"world.invite\",\"from\":{\"uuid\":\"" + DEX + "\",\"name\":\"Dex\"},\"address\":\"play.example.net\",\"world\":\"x\",\"at\":" + now + "}");
            return "";
        });
        ctx.waitTicks(2);
        invite = ctx.computeOnClient(c -> OpenToFriends.invites().size() + "|" + (OpenToFriends.invites().isEmpty() ? "" : OpenToFriends.invites().get(0).address())
                + "|" + SocialJoin.lastOfferLineForTest());
        check(invite.startsWith("1|quiet-fox.eu.e4mc.link|") && invite.contains("Dex invited you to their world Island [Join] [Dismiss]"),
                "Open to Friends: a world invite gives a Join line and a Requests entry; other addresses are ignored (" + invite + ")");
        ctx.setScreen(() -> { EvadeMenu.openFriendsForTest(1); return new EvadeMenu(); });
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-world-invite-requests");
        ctx.setScreen(() -> { EvadeMenu.openFriendsForTest(0); return new EvadeMenu(); });
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-friends-presence");
        ctx.setScreen(() -> null);

        // 5. Player cards: the list opens; hiding is per player, only on this screen, and can be undone.
        boolean hide = ctx.computeOnClient(c -> {
            UUID u = UUID.randomUUID();
            PlayerCards.hide(u);
            boolean hidden = PlayerCards.isHidden(u);
            PlayerCards.hide(c.player.getUuid()); // never yourself
            boolean self = PlayerCards.isHidden(c.player.getUuid());
            PlayerCards.show(u);
            return hidden && !self && !PlayerCards.isHidden(u) && PlayerCards.hiddenCount() == 0;
        });
        check(hide, "Player cards: hide and show a player (never yourself)");
        ctx.runOnClient(c -> PlayerCardScreen.openList(null));
        ctx.waitTicks(2);
        ctx.takeScreenshot("v25-player-cards");
        check(ctx.computeOnClient(c -> c.currentScreen instanceof PlayerCardScreen), "Player cards: the player list opens");
        ctx.setScreen(() -> null);

        // 6. Server API v2: widgets, player-card entries, prompts (answered on evade:reply), and the hello lists them.
        String api = ctx.computeOnClient(c -> {
            int n = ServerApi.handle("[{\"type\":\"economy.set\",\"id\":\"coins\",\"label\":\"Coins\",\"value\":1250},"
                    + "{\"type\":\"timer.set\",\"id\":\"round\",\"label\":\"Round\",\"seconds\":125},"
                    + "{\"type\":\"interaction.add\",\"id\":\"trade\",\"label\":\"Trade\",\"command\":\"/trade {player}\"},"
                    + "{\"type\":\"interaction.add\",\"id\":\"bad\",\"label\":\"Bad\",\"command\":\"say \\u00a7chi\"}]");
            String bal = ApiWidgets.balances().isEmpty() ? "" : ApiWidgets.balances().get(0).value();
            String clock = ApiWidgets.clock(ApiWidgets.timers().get(0).seconds(System.currentTimeMillis()));
            String cmd = ApiInteractions.entries().get(0).commandFor("Bob");
            return n + "|" + bal + "|" + clock + "|" + cmd + "|" + ApiInteractions.entries().size() + "|" + ServerApi.helloJson().contains("\"prompt\"");
        });
        check(api.matches("3\\|1,250\\|2:0[45]\\|trade Bob\\|1\\|true"), "Server API v2: balance, timer, one card entry (a bad one refused), hello lists prompt (" + api + ")");
        ctx.waitTicks(1);
        ctx.takeScreenshot("v25-server-widgets");
        ctx.runOnClient(c -> ServerApi.handle("{\"type\":\"prompt.input\",\"id\":\"isle\",\"title\":\"Name your island\",\"text\":\"Up to 24 letters\",\"max\":24}"));
        ctx.waitTicks(3);
        ctx.takeScreenshot("v25-server-prompt");
        check(ctx.computeOnClient(c -> c.currentScreen instanceof ServerPromptScreen), "Server API v2: a prompt shows once no other screen is open");
        ctx.runOnClient(c -> c.currentScreen.close());
        ctx.waitTicks(1);
        String reply = ctx.computeOnClient(c -> ApiPrompts.lastReplyForTest());
        check(reply != null && reply.contains("\"id\":\"isle\"") && reply.contains("\"cancelled\":true"), "Server API v2: closing a prompt answers 'cancelled' (" + reply + ")");
        String transfer = ctx.computeOnClient(c -> {
            ServerApi.handle("{\"type\":\"prompt.transfer\",\"id\":\"t1\",\"address\":\"not a host!\"}");
            return ApiPrompts.lastReplyForTest() + "|" + ApiPrompts.queuedForTest();
        });
        check(transfer.contains("\"accepted\":false") && transfer.endsWith("|0"), "Server API v2: a transfer to a bad address is refused at once (" + transfer + ")");
        ctx.runOnClient(c -> ServerApi.clear());

        // 7. Cloud backup: files pack, encrypt and come back only with the right passphrase and account; a backup
        //    can't write outside its own files.
        String backup = ctx.computeOnClient(c -> {
            try {
                Map<String, byte[]> files = new LinkedHashMap<>();
                files.put("config.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
                files.put("profiles/PvP.json", "{\"b\":2}".getBytes(StandardCharsets.UTF_8));
                files.put("../evil.json", "{}".getBytes(StandardCharsets.UTF_8));
                byte[] salt = new byte[16];
                byte[] blob = CloudBackup.encrypt(CloudBackup.pack(files), "correct horse".toCharArray(), salt, 1000, "abc");
                Map<String, byte[]> back = CloudBackup.unpack(CloudBackup.decrypt(blob, "correct horse".toCharArray(), salt, 1000, "abc"));
                boolean wrong = false, other = false;
                try { CloudBackup.decrypt(blob, "wrong pass".toCharArray(), salt, 1000, "abc"); } catch (Exception e) { wrong = true; }
                try { CloudBackup.decrypt(blob, "correct horse".toCharArray(), salt, 1000, "xyz"); } catch (Exception e) { other = true; }
                return back.keySet() + "|" + wrong + "|" + other + "|" + new String(back.get("profiles/PvP.json"), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "error " + e;
            }
        });
        check(backup.equals("[config.json, profiles/PvP.json]|true|true|{\"b\":2}"), "Cloud backup: encrypted round trip, wrong passphrase and other account refused, only its own files (" + backup + ")");

        // 8. Discover: the list keeps public addresses only, at most 24.
        int discover = ctx.computeOnClient(c -> Discover.parse(com.google.gson.JsonParser.parseString(
                "{\"servers\":[{\"name\":\"Good\",\"address\":\"play.example.net\",\"evade\":true},{\"name\":\"Bad\",\"address\":\"not a host!\"},{\"address\":\"x.example.net\"}]}")
                .getAsJsonObject()).size());
        check(discover == 1, "Discover: only named servers with valid addresses are listed (" + discover + ")");

        // 9. Render queue: queue, status, remove.
        String queue = ctx.computeOnClient(c -> {
            Path fake = c.runDirectory.toPath().resolve("evade").resolve("replays").resolve("queued-test.evrp");
            RenderQueue.add(fake, true);
            RenderQueue.add(fake, false); // the same replay again replaces it
            String s = RenderQueue.jobs().size() + "|" + RenderQueue.status() + "|" + RenderQueue.jobs().get(0).share();
            RenderQueue.remove(fake);
            return s + "|" + RenderQueue.jobs().size();
        });
        check(queue.equals("1|1 queued|false|0"), "Render queue: a replay is queued once, then removed (" + queue + ")");

        ctx.runOnClient(c -> {
            OpenToFriends.e4mcForTest(null);
            OpenToFriends.resetForTest();
        });
    }
}
