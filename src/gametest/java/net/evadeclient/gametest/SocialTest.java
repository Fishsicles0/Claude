package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.module.misc.PartyPing;
import net.evadeclient.social.SocialClient;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Set;
import java.util.UUID;

/**
 * Friends, parties and Party Ping without the network: SocialClient's loopback mode (a party with "Loopback" that
 * echoes every ping back), events injected as if evadeclient.com had sent them, and direct calls into the ping
 * receive path. Nothing leaves the game.
 */
public final class SocialTest {
    private SocialTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    /** A friends/requests/party state as the server sends it, for the menu and commands to show. */
    static final String STATE = """
            {"type":"state","state":{"me":{"uuid":"%s","name":"Player","allowRequests":"everyone","shareServer":true},
             "friends":[{"uuid":"11111111111111111111111111111111","name":"Dex","online":true,"server":"play.example.net","lastSeen":0},
                        {"uuid":"22222222222222222222222222222222","name":"Mira","online":true,"server":null,"lastSeen":0},
                        {"uuid":"33333333333333333333333333333333","name":"Kaito","online":false,"server":null,"lastSeen":%d}],
             "incoming":[{"uuid":"44444444444444444444444444444444","name":"Rowan","at":%d}],
             "outgoing":[{"uuid":"55555555555555555555555555555555","name":"Juno","at":%d}],
             "blocked":[],
             "party":{"id":"0123456789abcdef","leader":"%s","members":[
                 {"uuid":"%s","name":"Player","online":true,"server":"play.example.net","leader":true},
                 {"uuid":"11111111111111111111111111111111","name":"Dex","online":true,"server":"play.example.net","leader":false}],
                 "invited":[{"uuid":"22222222222222222222222222222222","name":"Mira"}]},
             "invites":[{"party":"fedcba9876543210","from":{"uuid":"22222222222222222222222222222222","name":"Mira"},"size":3,"at":%d}]}}""";

    static String state(UUID me) {
        String m = me.toString().replace("-", "");
        long now = System.currentTimeMillis();
        return STATE.formatted(m, now - 3 * 3600_000L, now - 120_000L, now - 600_000L, m, m, now - 60_000L);
    }

    /** The checks: run inside a world (Party Ping on). */
    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(PartyPing.class).setEnabled(true);
            SocialClient.setLoopback(true);
        });

        // 1. The receive path: a ping straight into PartyPing, as SocialClient hands it over on the render thread.
        int[] got = ctx.computeOnClient(c -> {
            var p = c.player;
            PartyPing.receive(new PartyPing.Ping(UUID.randomUUID(), "Dex", p.getX() + 6, p.getY(), p.getZ(),
                    c.world.getRegistryKey().getValue().toString(), "danger", System.currentTimeMillis()));
            PartyPing.receive(new PartyPing.Ping(UUID.randomUUID(), "Elsewhere", 0, 64, 0, "minecraft:the_end", "loot", System.currentTimeMillis()));
            return new int[]{PartyPing.active().size(), PartyPing.minimapDots().size()};
        });
        check(got[0] == 2 && got[1] == 1, "Party Ping: received pings become markers, only this dimension's on the minimap (" + got[0] + ", " + got[1] + ")");
        ctx.waitTicks(3);
        ctx.takeScreenshot("social-ping-marker");

        // 2. Send and receive in loopback: a ping at the crosshair goes out and comes back from "Loopback".
        ctx.runOnClient(c -> {
            c.player.setPitch(55);
            EvadeClient.modules.get(PartyPing.class).pingAtCrosshair("location", true);
        });
        ctx.waitTicks(2);
        String names = ctx.computeOnClient(c -> PartyPing.active().stream().map(PartyPing.Ping::name).sorted().toList().toString());
        check(names.contains("Loopback") && names.contains("You"), "Party Ping: a crosshair ping round-trips in loopback mode (" + names + ")");

        // 3. Pings fade out after 10 s.
        boolean expired = ctx.computeOnClient(c -> {
            PartyPing.receive(new PartyPing.Ping(UUID.randomUUID(), "Old", 0, 0, 0, c.world.getRegistryKey().getValue().toString(), "location",
                    System.currentTimeMillis() - PartyPing.LIFE_MS - 1));
            return PartyPing.active().stream().noneMatch(p -> p.name().equals("Old"));
        });
        check(expired, "Party Ping: pings older than 10 s are gone");

        // 4. A server that disables Party Ping (evade:rules): pings are dropped there, and it comes back on leaving the rule.
        int[] rules = ctx.computeOnClient(c -> {
            ServerRules.set(Set.of("party ping"));
            var pp = EvadeClient.modules.get(PartyPing.class);
            int before = pp.isEnabled() ? 1 : 0;
            PartyPing.receive(new PartyPing.Ping(UUID.randomUUID(), "Blocked", c.player.getX(), c.player.getY(), c.player.getZ(),
                    c.world.getRegistryKey().getValue().toString(), "location", System.currentTimeMillis()));
            int shown = (int) PartyPing.active().stream().filter(p -> p.name().equals("Blocked")).count();
            ServerRules.set(Set.of());
            return new int[]{before, shown, pp.isEnabled() ? 1 : 0};
        });
        check(rules[0] == 0 && rules[1] == 0 && rules[2] == 1, "Party Ping obeys evade:rules (enabled " + rules[0] + ", shown " + rules[1] + ", back " + rules[2] + ")");

        // 5. Server events: a state snapshot, a friend coming online, and a ping event, as the socket would deliver them.
        String st = ctx.computeOnClient(c -> {
            UUID me = c.getSession().getUuidOrNull() != null ? c.getSession().getUuidOrNull() : new UUID(0, 2);
            SocialClient.injectForTest(state(me));
            SocialClient.injectForTest("{\"type\":\"friend.status\",\"uuid\":\"33333333333333333333333333333333\",\"name\":\"Kaito\",\"online\":true,\"server\":\"mc.example.org\"}");
            SocialClient.injectForTest("{\"type\":\"ping\",\"from\":{\"uuid\":\"11111111111111111111111111111111\",\"name\":\"Dex\"},\"x\":"
                    + (c.player.getX() + 3) + ",\"y\":" + c.player.getY() + ",\"z\":" + c.player.getZ()
                    + ",\"dimension\":\"" + c.world.getRegistryKey().getValue() + "\",\"kind\":\"loot\",\"at\":0}");
            var s = SocialClient.state();
            return s.friends().size() + "/" + s.onlineFriends() + "/" + s.incoming().size() + "/" + s.invites().size() + "/"
                    + (s.party() == null ? 0 : s.party().members().size()) + "/" + s.friends().get(0).name();
        });
        ctx.waitTicks(2);
        boolean dexPing = ctx.computeOnClient(c -> PartyPing.active().stream().anyMatch(p -> p.name().equals("Dex") && p.kind().equals("loot")));
        check(st.equals("3/3/1/1/2/Dex") && dexPing, "Friends: state, friend.status and ping events apply (" + st + ", ping " + dexPing + ")");

        // 6. Commands work against that state without the network.
        ctx.runOnClient(c -> {
            EvadeClient.commands.handleChat(".friend list");
            EvadeClient.commands.handleChat(".party list");
            EvadeClient.commands.handleChat(".ping danger");
        });
        ctx.waitTicks(2);
    }

    /** The Friends page on each tab, in loopback mode with the sample state. */
    static void shots(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.runOnClient(c -> { c.options.getGuiScale().setValue(2); c.onResolutionChanged(); });
            ctx.runOnClient(c -> Friends.get().activate());
            run(ctx, sp);
            String[] tabs = {"friends", "requests", "party", "settings"};
            for (int t = 0; t < tabs.length; t++) {
                int tab = t;
                ctx.setScreen(() -> { EvadeMenu.openFriendsForTest(tab); return new EvadeMenu(); });
                ctx.waitTicks(10);
                ctx.runOnClient(c -> c.getToastManager().clear());
                ctx.takeScreenshot("social-menu-" + tabs[t]);
            }
            // Friends off: the intro card.
            ctx.runOnClient(c -> { SocialClient.setLoopback(false); Friends.get().deactivate(); });
            ctx.setScreen(() -> { EvadeMenu.openFriendsForTest(0); return new EvadeMenu(); });
            ctx.waitTicks(10);
            ctx.takeScreenshot("social-menu-off");
            ctx.setScreen(() -> null);
        }
    }
}
