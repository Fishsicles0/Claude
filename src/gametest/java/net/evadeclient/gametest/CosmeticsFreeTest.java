package net.evadeclient.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.cosmetics.CosmeticItem;
import net.evadeclient.cosmetics.CosmeticsApi;
import net.evadeclient.cosmetics.CosmeticsCatalog;
import net.evadeclient.cosmetics.CosmeticsEngine;
import net.evadeclient.cosmetics.Loadout;
import net.evadeclient.cosmetics.PlayerCosmetics;
import net.evadeclient.cosmetics.Rarity;
import net.evadeclient.cosmetics.Slot;
import net.evadeclient.cosmetics.Wardrobe;
import net.evadeclient.module.misc.Cosmetics;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Free capes and the cosmetics privacy rules: every cape (regular and special) is free for every Evade player (price 0,
 * owned with no account or purchase check, wearable without test mode), items without a price are never free, the
 * client asks evadeclient.com nothing while the Cosmetics mod is off, asks once when it is turned on and again after an
 * equip, has no timed /me poll, and keeps "wears nothing" answers for five minutes. Against a mock evadeclient.com.
 */
public final class CosmeticsFreeTest {
    private CosmeticsFreeTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    /** Every call the mock evadeclient.com gets, as "METHOD path". */
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    private static CompletableFuture<JsonObject> mock(String method, String path, JsonObject body, String token) {
        CALLS.add(method + " " + path);
        String answer = switch (path) {
            case "me" -> "{\"owned\":[],\"equipped\":{}}";
            case "equip" -> "{\"ok\":true,\"equipped\":{}}";
            case "catalog" -> "{\"version\":\"free-test\",\"items\":[]}";
            default -> "{}";
        };
        return CompletableFuture.completedFuture(JsonParser.parseString(answer).getAsJsonObject());
    }

    private static long count(String call) { return CALLS.stream().filter(call::equals).count(); }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            CosmeticsCatalog.ignoreRemoteForTest = true;
            CosmeticsCatalog.loadBundled();
            CosmeticsApi.transport = CosmeticsFreeTest::mock;
            Wardrobe.setTestMode(false);
            Wardrobe.resetForTest();
            Wardrobe.applyMe(JsonParser.parseString("{\"owned\":[]}").getAsJsonObject());
            if (!Cosmetics.get().isEnabled()) Cosmetics.get().setEnabled(true);
        });

        // ---- prices: every cape is free (special ones too); nothing else is; unpriced specials read as -1, not free
        String prices = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            int capes = 0, specialCapes = 0;
            for (CosmeticItem i : CosmeticsCatalog.all()) {
                if (!i.bundled) continue;
                if (i.slot == Slot.CAPE) {
                    capes++;
                    if (i.rarity == Rarity.SPECIAL) specialCapes++;
                    if (i.priceCents != 0 || !i.free()) b.append(i.id).append("=notfree ");
                } else if (i.free()) b.append(i.id).append("=free ");
                else if (i.rarity == Rarity.SPECIAL && i.priceCents != -1) b.append(i.id).append('=').append(i.priceCents).append(' ');
            }
            return capes + "/" + specialCapes + " " + b;
        });
        String[] pc = prices.split(" ", 2)[0].split("/");
        check(prices.endsWith(" ") && prices.split(" ", 2)[1].isEmpty() && Integer.parseInt(pc[0]) >= 17 && Integer.parseInt(pc[1]) >= 4,
                "Free capes: every cape (regular and special) is priced 0 and free, nothing else is, unpriced specials stay unpriced (" + prices + ")");

        // ---- ownership: no test mode, nothing owned, no account: every cape is still yours, nothing else is
        String owns = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            for (CosmeticItem i : CosmeticsCatalog.all()) {
                if (!i.bundled) continue;
                if (Wardrobe.owns(i.id) != (i.slot == Slot.CAPE)) b.append(i.id).append(' ');
            }
            return Wardrobe.testMode() + "|" + Wardrobe.owned().size() + "|" + b;
        });
        check(owns.equals("false|0|"), "Free capes: owned by everyone without an account or purchase; other items stay locked (" + owns + ")");
        String worn = ctx.computeOnClient(c -> {
            boolean cape = Wardrobe.equip(Slot.CAPE, "hearthfire_cape", -1);
            boolean wings = Wardrobe.equip(Slot.WINGS, "aurora_wings", -1);
            return cape + "|" + wings + "|" + Wardrobe.renderLoadout().get(Slot.CAPE) + "|" + Wardrobe.renderLoadout().get(Slot.WINGS);
        });
        check(worn.startsWith("true|false|") && worn.contains("hearthfire_cape") && worn.endsWith("|null"),
                "Free capes: a special cape is worn and drawn without test mode; a paid item isn't (" + worn + ")");

        // ---- equipping tells evadeclient.com, then asks /me once the change has landed
        ctx.waitTicks(5);
        check(count("POST equip") >= 1 && count("GET me") >= 1, "Cosmetics: an equip is saved, then /me is asked again " + CALLS);

        // ---- Cosmetics mod off: nothing at all is asked, not even on a forced refresh, a join-style refresh or a tick
        ctx.runOnClient(c -> { Cosmetics.get().setEnabled(false); CALLS.clear(); });
        ctx.runOnClient(c -> {
            Wardrobe.refresh(true);
            CosmeticsEngine.refreshAll();
            CosmeticsEngine.refreshCatalog();
            Wardrobe.equip(Slot.CAPE, "galaxy_flow_cape", -1);
        });
        ctx.waitTicks(120); // also past the 5 s that merges a join and an enable into one refresh
        check(CALLS.isEmpty(), "Cosmetics: with the mod off nothing is sent to evadeclient.com " + CALLS);

        // ---- turned on in a world: asks once; then no timed poll while it stays on
        ctx.runOnClient(c -> Cosmetics.get().setEnabled(true));
        ctx.waitTicks(5);
        long me = count("GET me");
        check(me == 1, "Cosmetics: turning the mod on asks for your cosmetics once " + CALLS);
        ctx.waitTicks(60);
        check(count("GET me") == me, "Cosmetics: no timed /me poll while the mod is on " + CALLS);

        // ---- other players: "wears something" is asked about again after a minute, "wears nothing" after five
        String ttl = ctx.computeOnClient(c -> {
            Loadout some = Loadout.EMPTY.with(Slot.CAPE, new Loadout.Worn("ember_mantle", -1));
            return PlayerCosmetics.staleForTest(some, 61_000) + "," + PlayerCosmetics.staleForTest(Loadout.EMPTY, 61_000) + ","
                    + PlayerCosmetics.staleForTest(Loadout.EMPTY, 4 * 60_000) + "," + PlayerCosmetics.staleForTest(Loadout.EMPTY, 5 * 60_000 + 1);
        });
        check(ttl.equals("true,false,false,true"), "Cosmetics: empty answers are kept 5 minutes, others 1 (" + ttl + ")");

        // ---- clean up
        ctx.runOnClient(c -> {
            Wardrobe.resetForTest();
            Wardrobe.applyMe(JsonParser.parseString("{\"owned\":[]}").getAsJsonObject());
            CosmeticsApi.transport = CosmeticsApi.NETWORK;
            PlayerCosmetics.clear();
        });
        CALLS.clear();
    }
}
