package net.evadeclient.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.evadeclient.EvadeClient;
import net.evadeclient.cosmetics.CosmeticAssets;
import net.evadeclient.cosmetics.CosmeticFx;
import net.evadeclient.cosmetics.CosmeticItem;
import net.evadeclient.cosmetics.CosmeticsApi;
import net.evadeclient.cosmetics.CosmeticsCatalog;
import net.evadeclient.cosmetics.CosmeticsEngine;
import net.evadeclient.cosmetics.CosmeticsPerf;
import net.evadeclient.cosmetics.EmoteWheelScreen;
import net.evadeclient.cosmetics.Emotes;
import net.evadeclient.cosmetics.Loadout;
import net.evadeclient.cosmetics.PlayerCosmetics;
import net.evadeclient.cosmetics.Rarity;
import net.evadeclient.cosmetics.Slot;
import net.evadeclient.cosmetics.Wardrobe;
import net.evadeclient.cosmetics.WardrobeScreen;
import net.evadeclient.module.misc.Cosmetics;
import net.evadeclient.util.Combat;
import net.evadeclient.util.FrameStats;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.gui.screen.TitleScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Evade cosmetics in the real game: every starter item drawn on you and on a second (fake) player, the hide settings,
 * the "Cosmetics" server rule, the Wardrobe (equip, colour, outfits, try-on), file hashes, the backend contract against a
 * mock, emotes stopping when you move, kill and win effects, and what it all costs per frame. Screenshots of each item:
 * build/run/clientGameTest/screenshots/cosmetic-*.png.
 */
public final class CosmeticsTest {
    private static final UUID BOT = UUID.fromString("0f0e0d0c-0b0a-4909-8807-060504030201");
    private static final int BOT_ID = 917_311;

    private CosmeticsTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("tp @a 0 -60 0 0 0");
        server.runCommand("clear @a");
        ctx.waitTicks(5);

        // ---- setup: test mode (every starter item unlocked locally), a mock evadeclient.com, nothing worn
        ctx.runOnClient(c -> {
            Wardrobe.setTestMode(true);
            Wardrobe.resetForTest();
            Emotes.moveCamera = false;
            CosmeticsApi.transport = CosmeticsTest::mock;
            // start from the bundled set: the live catalog fetched at start-up may still list an older release's files
            CosmeticsCatalog.ignoreRemoteForTest = true;
            CosmeticsCatalog.loadBundled();
            Cosmetics m = Cosmetics.get();
            m.setEnabled(true);
            m.showMine.set(true);
            m.showOthers.set(true);
            m.hideInCombat.set(false);
            m.particles.set(100.0);
            Combat.reset();
            CosmeticAssets.preloadBundled();
        });

        // ---- files: the starter set matches its catalog, and a file that doesn't match is refused
        List<String> bad = ctx.computeOnClient(c -> CosmeticAssets.verifyBundled());
        int items = ctx.computeOnClient(c -> CosmeticsCatalog.all().size());
        check(bad.isEmpty() && items >= 15, "Cosmetics: all " + items + " starter items match their sha256 (" + bad + ")");
        check(!CosmeticAssets.matches("tampered".getBytes(StandardCharsets.UTF_8), CosmeticAssets.sha256("original".getBytes(StandardCharsets.UTF_8)))
                && !CosmeticAssets.matches(new byte[]{1}, null), "Cosmetics: a file whose hash doesn't match (or has none) is refused");
        String loadErrors = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            for (CosmeticItem i : CosmeticsCatalog.all()) if (CosmeticAssets.state(i) != CosmeticAssets.State.READY) b.append(i.id).append('=').append(CosmeticAssets.error(i)).append(' ');
            return b.toString();
        });
        check(loadErrors.isEmpty(), "Cosmetics: every starter item loads (models, textures, thumbnails) " + loadErrors);
        // ---- special (animated) items: at least the regular items' 8 texels per unit (capes 16), 16+ frames, glow layer
        String hd = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (CosmeticItem i : CosmeticsCatalog.all()) {
                if (!i.bundled || i.rarity != Rarity.SPECIAL) continue;
                var m = CosmeticAssets.model(i);
                if (m == null || m.texture() == null) { b.append(i.id).append("=nomodel "); continue; }
                var t = m.texture();
                int perUnit = t.frameW / m.texW;
                if (!t.animated() || t.frames < 16 || perUnit < (i.slot == net.evadeclient.cosmetics.Slot.CAPE ? 16 : 8))
                    b.append(i.id).append('=').append(t.frames).append("f@").append(perUnit).append(' ');
                n++;
            }
            return n + " " + b;
        });
        check(hd.matches("\\d+ ") && Integer.parseInt(hd.trim()) >= 10,
                "Cosmetics: special items are animated at high resolution (16+ frames, 8+ texels per unit, capes 16) " + hd);
        testDownloadCache(ctx);
        // ---- tiers and prices: three tiers, old names read as regular; bundled regular items are priced, bundled
        //      special (animated) items wait for their price
        String tiers = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            // capes are free for everyone (priceCents 0, special ones too); other regular items are priced
            for (CosmeticItem i : CosmeticsCatalog.all()) if (i.bundled && (i.rarity == Rarity.PARTNER
                    || (i.slot == Slot.CAPE ? i.priceCents != 0
                        : (i.rarity == Rarity.REGULAR && i.priceCents <= 0) || (i.rarity == Rarity.SPECIAL && i.priceCents > 0)))) b.append(i.id).append(' ');
            return b.toString();
        });
        check(tiers.isEmpty() && Rarity.values().length == 3 && Rarity.parse("legendary") == Rarity.REGULAR && Rarity.parse("special") == Rarity.SPECIAL
                && Rarity.parse("Partner") == Rarity.PARTNER && Rarity.parse(null) == Rarity.REGULAR,
                "Cosmetics: regular / special / partner tiers, old names read as regular, starter items regular and priced " + tiers);

        // ---- the backend contract, against the mock
        String me = ctx.computeOnClient(c -> {
            CosmeticsCatalog.ignoreRemoteForTest = false; CosmeticsCatalog.mergeRemote(JsonParser.parseString(CATALOG).getAsJsonObject()); CosmeticsCatalog.ignoreRemoteForTest = true;
            Wardrobe.applyMe(JsonParser.parseString("{\"owned\":[\"tidal_weave\"],\"equipped\":{\"CAPE\":{\"id\":\"tidal_weave\"}}}").getAsJsonObject());
            return Wardrobe.owned() + "|" + CosmeticsCatalog.get("tidal_weave").priceCents + "|" + CosmeticsCatalog.get("tidal_weave").bundled;
        });
        check(me.equals("[tidal_weave]|555|true"), "Cosmetics: /me ownership and the catalog's prices apply, starter art stays bundled (" + me + ")");
        check(ctx.computeOnClient(c -> CosmeticsCatalog.get("tidal_weave").rarity) == Rarity.REGULAR, "Cosmetics: an old rarity name from the backend (rare) reads as regular");
        ctx.runOnClient(c -> PlayerCosmetics.putForTest(BOT, Loadout.EMPTY));
        CompletableFuture<JsonObject> players = ctx.computeOnClient(c -> CosmeticsApi.players(List.of(BOT)));
        check(players.join().getAsJsonObject("players").has(BOT.toString().replace("-", "")), "Cosmetics: players lookup sends undashed UUIDs");

        // ---- a second player next to you
        ctx.runOnClient(c -> {
            OtherClientPlayerEntity bot = new OtherClientPlayerEntity(c.world, new GameProfile(BOT, "CosmeticBot"));
            bot.setId(BOT_ID);
            double x = c.player.getX() + 1.6, y = c.player.getY(), z = c.player.getZ() + 0.4;
            bot.refreshPositionAndAngles(x, y, z, 160f, 0f);
            bot.lastX = x; bot.lastY = y; bot.lastZ = z;
            bot.setBodyYaw(160f);
            bot.setHeadYaw(160f);
            c.world.addEntity(bot);
            c.player.setYaw(0f);
            c.player.setPitch(0f);
            c.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            c.options.hudHidden = true;
        });
        ctx.waitTicks(5);

        // ---- each item, on you and on the bot (both drawn), with a screenshot
        List<String> ids = ctx.computeOnClient(c -> CosmeticsCatalog.all().stream().filter(i -> i.bundled).map(i -> i.id).toList());
        for (String id : ids) {
            Slot slot = ctx.computeOnClient(c -> CosmeticsCatalog.get(id).slot);
            ctx.runOnClient(c -> {
                Wardrobe.resetForTest();
                check(Wardrobe.equip(slot, id, -1), "Cosmetics: equip " + id);
                PlayerCosmetics.putForTest(BOT, Wardrobe.equipped());
                boolean front = slot == Slot.HAT || slot == Slot.MASK || slot == Slot.PET || slot == Slot.AURA || slot == Slot.EMOTE;
                c.options.setPerspective(slot == Slot.ITEM_SKIN ? Perspective.FIRST_PERSON : front ? Perspective.THIRD_PERSON_FRONT : Perspective.THIRD_PERSON_BACK);
                CosmeticsPerf.takeDrawn();
                CosmeticFx.takeSpawned();
                // earlier tests may leave you sneaking (toggle sneak) or "in combat"; auras pause then, by design
                c.options.sneakKey.setPressed(false);
                if (c.player != null) c.player.setSneaking(false);
                net.evadeclient.util.Combat.reset();
            });
            if (slot == Slot.AURA || slot == Slot.TRAIL) server.runCommand("effect clear @a");
            if (slot == Slot.ITEM_SKIN) {
                server.runCommand("tp @a 0 -60 0 0 0");
                server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
                ctx.runOnClient(c -> c.options.hudHidden = false);   // F1 hides your hand and item
            }
            if (slot == Slot.EMOTE) ctx.runOnClient(c -> check(Emotes.play(CosmeticsCatalog.get(id)), "Cosmetics: emote " + id + " plays"));
            if (slot == Slot.TRAIL) for (int i = 0; i < 8; i++) { server.runCommand("execute as @a at @s run tp @s ~0.3 ~ ~"); ctx.waitTicks(1); }
            if (slot == Slot.KILL_EFFECT) ctx.runOnClient(c -> check(CosmeticFx.kill(c.player.getEntityPos().add(1.5, 0, 2)), "Cosmetics: kill effect plays"));
            if (slot == Slot.WIN_EFFECT) ctx.runOnClient(c -> { CosmeticFx.resetWinCooldownForTest(); CosmeticFx.onMessage("You won the game!"); });
            ctx.waitTicks(slot == Slot.WIN_EFFECT ? 25 : slot == Slot.ITEM_SKIN ? 40 : slot == Slot.AURA ? 30 : 8);
            long drawn = ctx.computeOnClient(c -> CosmeticsPerf.takeDrawn());
            int spawned = ctx.computeOnClient(c -> CosmeticFx.takeSpawned());
            switch (slot.kind) {
                case MODEL, EMOTE -> check(drawn > 0, "Cosmetics: " + id + " drawn on you and the second player (" + drawn + " draws)");
                case ITEM -> check(drawn > 0, "Cosmetics: " + id + " replaces your sword in first person (" + drawn + " draws)");
                case PARTICLES, EFFECT -> check(spawned > 0, "Cosmetics: " + id + " spawns particles (" + spawned + ")");
            }
            if (slot == Slot.AURA) check(spawned > 0, "Cosmetics: aura " + id + " spawns a few motes (" + spawned + ")" + (spawned > 0 ? "" : ctx.computeOnClient(c -> " sneaking=" + c.player.isSneaking() + " invisible=" + c.player.isInvisible() + " combat=" + net.evadeclient.util.Combat.active() + " showMine=" + EvadeClient.modules.get(net.evadeclient.module.misc.Cosmetics.class).showMine.get() + " enabled=" + EvadeClient.modules.get(net.evadeclient.module.misc.Cosmetics.class).isEnabled() + " worn=" + Wardrobe.renderLoadout().get(Slot.AURA) + " equipped=" + Wardrobe.equipped().get(Slot.AURA) + " owns=" + Wardrobe.owns(id) + " item=" + (CosmeticsCatalog.get(id) == null ? "null" : CosmeticsCatalog.get(id).bundled + "/" + (CosmeticsCatalog.get(id).fx() != null)) + " spawnedTotal=" + net.evadeclient.cosmetics.CosmeticFx.takeSpawned())));
            ctx.takeScreenshot("cosmetic-" + id);
            if (slot == Slot.EMOTE) ctx.runOnClient(c -> Emotes.stop());
            if (slot == Slot.ITEM_SKIN) {
                ctx.runOnClient(c -> c.options.setPerspective(Perspective.THIRD_PERSON_FRONT));
                ctx.waitTicks(3);
                ctx.takeScreenshot("cosmetic-" + id + "-third-person");
                server.runCommand("clear @a"); ctx.runOnClient(c -> c.options.hudHidden = true); }
            if (slot == Slot.TRAIL) server.runCommand("tp @a 0 -60 0 0 0");
        }
        int wins = ctx.computeOnClient(c -> CosmeticFx.winEffects);
        check(wins >= 1, "Cosmetics: a \"You won the game!\" line plays your win effect");

        // ---- everything at once on both players, then the hide rules
        ctx.runOnClient(c -> {
            Wardrobe.resetForTest();
            Wardrobe.equip(Slot.CAPE, "starlit_veil", -1);
            Wardrobe.equip(Slot.HAT, "circuit_crown", -1);
            Wardrobe.equip(Slot.AURA, "ember_orbit", -1);
            Wardrobe.equip(Slot.PET, "soul_wisp", -1);
            Wardrobe.equip(Slot.BACK, "lantern_pack", -1);
            PlayerCosmetics.putForTest(BOT, Wardrobe.equipped().with(Slot.WINGS, new Loadout.Worn("aurora_wings", -1)).with(Slot.CAPE, null));
            c.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot("cosmetic-full-outfits");
        check(drawnOver(ctx) >= 2, "Cosmetics: you and the second player are both drawn");
        ctx.runOnClient(c -> Cosmetics.get().showMine.set(false));
        long othersOnly = drawnOver(ctx);
        ctx.runOnClient(c -> { Cosmetics.get().showMine.set(true); Cosmetics.get().showOthers.set(false); });
        long mineOnly = drawnOver(ctx);
        ctx.runOnClient(c -> Cosmetics.get().showMine.set(false));
        long none = drawnOver(ctx);
        check(othersOnly > 0 && mineOnly > 0 && none == 0, "Cosmetics: Show My / Show Others' Cosmetics hide each side (" + othersOnly + ", " + mineOnly + ", " + none + ")");
        ctx.runOnClient(c -> { Cosmetics.get().showMine.set(true); Cosmetics.get().showOthers.set(true); Cosmetics.get().hideInCombat.set(true); Combat.hit(); });
        long combat = drawnOver(ctx);
        ctx.runOnClient(c -> { Combat.reset(); Cosmetics.get().hideInCombat.set(false); });
        check(combat == 0, "Cosmetics: Hide In Combat hides everything during a fight");
        ctx.runOnClient(c -> { Cosmetics.get().showMine.set(false); c.world.getEntityById(BOT_ID).setInvisible(true); });
        long invisible = drawnOver(ctx);
        ctx.runOnClient(c -> { Cosmetics.get().showMine.set(true); c.world.getEntityById(BOT_ID).setInvisible(false); });
        check(invisible == 0, "Cosmetics: nothing is drawn on an invisible player");
        server.runCommand("item replace entity @a armor.head with minecraft:iron_helmet");
        ctx.waitTicks(3);
        boolean hatHidden = ctx.computeOnClient(c -> {
            var st = c.getEntityRenderDispatcher().getRenderer(c.player).getAndUpdateRenderState(c.player, 1f);
            var d = CosmeticsEngine.data((net.minecraft.client.render.entity.state.PlayerEntityRenderState) st);
            return d != null && !d.shows(Slot.HAT) && d.shows(Slot.CAPE);
        });
        server.runCommand("clear @a");
        check(hatHidden, "Cosmetics: a helmet hides your hat (armour always shows)");

        // ---- the server rule switches cosmetics off, leaving the server puts them back
        ctx.runOnClient(c -> ServerRules.apply("{\"disable\":[\"Cosmetics\"]}".getBytes(StandardCharsets.UTF_8)));
        long ruled = drawnOver(ctx);
        boolean off = ctx.computeOnClient(c -> !Cosmetics.get().isEnabled());
        ctx.runOnClient(c -> ServerRules.clear());
        long back = drawnOver(ctx);
        check(off && ruled == 0 && back > 0, "Cosmetics: the \"Cosmetics\" server rule hides every cosmetic until you leave");

        // ---- cost per frame: two players in full outfits vs nobody wearing anything
        double withUs = frameCost(ctx);
        ctx.runOnClient(c -> { Wardrobe.resetForTest(); PlayerCosmetics.putForTest(BOT, Loadout.EMPTY); });
        double emptyUs = frameCost(ctx);
        double lastFrameMs = ctx.computeOnClient(c -> (double) FrameStats.recent(0));
        EvadeClient.LOG.info("[gametest] Cosmetics cost: {} us/frame with two full outfits, {} us/frame with none (avg frame {} ms)",
                String.format("%.1f", withUs), String.format("%.1f", emptyUs), String.format("%.2f", lastFrameMs));
        check(withUs < 1500 && emptyUs < 60, "Cosmetics: cheap per frame (" + String.format("%.1f", withUs) + " us with outfits, "
                + String.format("%.1f", emptyUs) + " us with nothing worn)");

        // ---- engine v2 (2.7): animated + emissive textures, per-face UVs, bone parents, cape physics, wing beat, soft particles
        testEngineV2(ctx, sp);

        // ---- emotes stop when you move or attack
        server.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(20);
        boolean playing = ctx.computeOnClient(c -> Emotes.play(CosmeticsCatalog.get("wave")) && Emotes.current() != null);
        ctx.waitTicks(5);
        boolean still = ctx.computeOnClient(c -> Emotes.current() != null);
        String early = ctx.computeOnClient(c -> Emotes.lastStop);
        server.runCommand("tp @a 1.5 -60 0");
        ctx.waitTicks(4);
        boolean stopped = ctx.computeOnClient(c -> Emotes.current() == null);
        String moveStop = ctx.computeOnClient(c -> Emotes.lastStop);
        check(playing && still && stopped && moveStop.equals("moved"), "Cosmetics: an emote keeps playing while you stand still and stops when you move ("
                + playing + ", " + still + " " + early + ", " + stopped + " " + moveStop + ")");
        boolean attackStops = ctx.computeOnClient(c -> { Emotes.play(CosmeticsCatalog.get("drum_solo")); Emotes.onAttack(); return Emotes.current() == null; });
        check(attackStops, "Cosmetics: attacking ends an emote");
        int wheel = ctx.computeOnClient(c -> { EmoteWheelScreen.open(); int n = c.currentScreen instanceof EmoteWheelScreen w ? w.emotes().size() : -1; return n; });
        ctx.takeScreenshot("cosmetic-emote-wheel");
        ctx.runOnClient(c -> c.setScreen(null));
        int ownedEmotes = ctx.computeOnClient(c -> (int) CosmeticsCatalog.forSlot(Slot.EMOTE).stream().filter(i -> Wardrobe.owns(i.id)).count());
        check(wheel == Math.min(8, ownedEmotes) && wheel >= 2, "Cosmetics: the emote wheel lists your emotes (" + wheel + " of " + ownedEmotes + ")");

        // ---- Wardrobe: opens, equips, colours, saves and wears an outfit, try-on for locked items
        ctx.runOnClient(c -> { c.options.hudHidden = false; WardrobeScreen.openFromCurrent(); });
        ctx.waitTicks(5);
        String wardrobe = ctx.computeOnClient(c -> {
            if (!(c.currentScreen instanceof WardrobeScreen w)) return "not open";
            w.selectSlot(Slot.CAPE);
            w.clickItem(CosmeticsCatalog.get("chevron_standard"));
            w.applyColour(Slot.CAPE, 0x3BE37A);
            w.selectSlot(Slot.HAT);
            w.clickItem(CosmeticsCatalog.get("comet_cap"));
            w.clickOutfit(0, 1);                                  // save outfit 1
            w.clickItem(CosmeticsCatalog.get("comet_cap"));       // take the hat off
            w.selectSlot(Slot.CAPE);
            w.clickItem(CosmeticsCatalog.get("chevron_standard"));
            String afterOff = Wardrobe.equipped().toString();
            w.clickOutfit(0, 0);                                  // wear outfit 1 again
            return afterOff + "|" + Wardrobe.equipped();
        });
        check(wardrobe.startsWith("{}|") && wardrobe.contains("\"CAPE\":{\"id\":\"chevron_standard\",\"color\":\"#3BE37A\"}")
                && wardrobe.contains("comet_cap"), "Wardrobe: equips, colours, saves an outfit and wears it again (" + wardrobe + ")");
        ctx.waitTicks(5);
        ctx.takeScreenshot("cosmetic-wardrobe");
        // Tall hat + pet: nothing may be cut off at the top of the preview (checked on this screenshot).
        ctx.runOnClient(c -> {
            WardrobeScreen w = (WardrobeScreen) c.currentScreen;
            w.selectSlot(Slot.HAT); w.clickItem(CosmeticsCatalog.get("witch_hat"));
            w.selectSlot(Slot.PET); w.clickItem(CosmeticsCatalog.get("baby_dragon"));
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot("cosmetic-wardrobe-tall");
        ctx.runOnClient(c -> {
            WardrobeScreen w = (WardrobeScreen) c.currentScreen;
            w.selectSlot(Slot.PET); w.clickItem(CosmeticsCatalog.get("baby_dragon"));
            w.selectSlot(Slot.HAT); w.clickItem(CosmeticsCatalog.get("comet_cap"));
        });
        ctx.waitTicks(2);
        String locked = ctx.computeOnClient(c -> {
            WardrobeScreen w = (WardrobeScreen) c.currentScreen;
            Wardrobe.setTestMode(false);
            w.selectSlot(Slot.WINGS);
            w.clickItem(CosmeticsCatalog.get("aurora_wings"));
            boolean equipped = Wardrobe.equip(Slot.WINGS, "aurora_wings", -1);
            String r = (w.trying() != null ? w.trying().id : "none") + "|" + equipped + "|" + Wardrobe.renderLoadout().get(Slot.CAPE);
            return r;
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("cosmetic-wardrobe-try-on");
        check(locked.startsWith("aurora_wings|false|") && locked.contains("chevron_standard"),
                "Wardrobe: without test mode nothing unowned is worn (free capes still are); locked items are only tried on (" + locked + ")");
        ctx.runOnClient(c -> {
            Wardrobe.setTestMode(true);
            c.setScreen(null);
            Cosmetics.get().command(new String[]{"wardrobe"});
        });
        ctx.waitTicks(3);
        check(ctx.computeOnClient(c -> c.currentScreen instanceof WardrobeScreen), "Wardrobe: .wardrobe opens it");

        // ---- clean up
        ctx.runOnClient(c -> {
            c.setScreen(null);
            Wardrobe.resetForTest();
            Wardrobe.setTestMode(false);
            Emotes.stop();
            Emotes.moveCamera = true;
            CosmeticsApi.transport = CosmeticsApi.NETWORK;
            PlayerCosmetics.clear();
            var bot = c.world.getEntityById(BOT_ID);
            if (bot != null) bot.discard();
            c.options.setPerspective(Perspective.FIRST_PERSON);
            c.options.hudHidden = false;
        });
        ctx.waitTicks(2);
        check(ctx.computeOnClient(c -> !(c.currentScreen instanceof TitleScreen)), "Cosmetics: test cleaned up in game");
    }

    // ================================================================== engine v2 (animated / special items)

    private static final String V2_CAPE = "v2test_cape", V2_WINGS = "v2test_wings", V2_AURA = "v2test_aura";

    /**
     * The 2.7 engine on synthetic special items (served from the download cache, so the checks don't depend on the
     * starter art): an animated cape with an emissive layer, a parented per-face bone and a physics block; flap-eased
     * wings; a soft-particle aura. Checks: frames advance on the shared clock and upload only on change, the glow
     * layer is drawn, the cape spring moves when the wearer walks and settles, wings beat faster when sprinting and
     * fold when sneaking, soft particles spawn. Screenshots: cosmetic-v2-*.png.
     */
    private static void testEngineV2(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        String prep = ctx.computeOnClient(c -> {
            try {
                Cosmetics m = Cosmetics.get();
                if (!m.isEnabled()) m.setEnabled(true);
                m.showOthers.set(true);
                m.hideInCombat.set(false);
                JsonObject cat = new JsonObject();
                cat.addProperty("version", "v2test");
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                arr.add(v2Item(V2_CAPE, "CAPE", java.util.Map.of(
                        "item.json", ("{\"id\":\"" + V2_CAPE + "\",\"slot\":\"CAPE\",\"rarity\":\"special\","
                                + "\"animation\":{\"frames\":4,\"frametime\":2,\"interpolate\":true},"
                                + "\"physics\":{\"stiffness\":0.3,\"lift\":1.2,\"sway\":1.0}}").getBytes(StandardCharsets.UTF_8),
                        "model.json", ("{\"format\":\"evade-cosmetic-model/1\",\"space\":\"entity\",\"texture\":[64,32],\"bones\":["
                                + "{\"name\":\"cape\",\"attach\":\"body\",\"pivot\":[0,0,2],\"anim\":{\"type\":\"cape\"},"
                                + "\"cubes\":[{\"origin\":[-5,0,1],\"size\":[10,16,1],\"uv\":[0,0]}]},"
                                + "{\"name\":\"clasp\",\"parent\":\"cape\",\"pivot\":[0,1,1],\"glass\":true,"
                                + "\"cubes\":[{\"origin\":[-1.5,0.5,0.25],\"size\":[3,2.5,0.75],\"faces\":{"
                                + "\"north\":{\"uv\":[40,0,46,5]},\"south\":{\"uv\":[46,0,52,5],\"rotation\":180},\"up\":{\"uv\":[40,6,46,8]}}}]}]}")
                                .getBytes(StandardCharsets.UTF_8),
                        "texture.png", v2Strip(64, 32, 4, false),
                        "emissive.png", v2Strip(64, 32, 4, true),
                        "thumb.png", v2Strip(128, 128, 4, false))));
                arr.add(v2Item(V2_WINGS, "WINGS", java.util.Map.of(
                        "item.json", ("{\"id\":\"" + V2_WINGS + "\",\"slot\":\"WINGS\",\"rarity\":\"regular\"}").getBytes(StandardCharsets.UTF_8),
                        "model.json", ("{\"format\":\"evade-cosmetic-model/1\",\"space\":\"entity\",\"texture\":[64,32],\"bones\":["
                                + "{\"name\":\"wr\",\"attach\":\"body\",\"pivot\":[-2,2,2],\"anim\":{\"type\":\"wing\",\"side\":1,\"base\":20,\"amp\":25,\"speed\":1,\"ease\":\"flap\",\"lift\":8},"
                                + "\"cubes\":[{\"origin\":[-14,1,2],\"size\":[12,10,1],\"uv\":[0,0]}]},"
                                + "{\"name\":\"wr_tip\",\"parent\":\"wr\",\"pivot\":[-14,2,2],\"anim\":{\"type\":\"wing\",\"side\":1,\"base\":10,\"amp\":15,\"speed\":1,\"phase\":0.12,\"ease\":\"flap\"},"
                                + "\"cubes\":[{\"origin\":[-22,1,2],\"size\":[8,8,1],\"uv\":[0,12]}]},"
                                + "{\"name\":\"wl\",\"attach\":\"body\",\"pivot\":[2,2,2],\"anim\":{\"type\":\"wing\",\"side\":-1,\"base\":20,\"amp\":25,\"speed\":1,\"ease\":\"flap\",\"lift\":8},"
                                + "\"cubes\":[{\"origin\":[2,1,2],\"size\":[12,10,1],\"uv\":[0,0],\"mirror\":true}]}]}").getBytes(StandardCharsets.UTF_8),
                        "texture.png", v2Strip(64, 32, 1, false))));
                arr.add(v2Item(V2_AURA, "AURA", java.util.Map.of(
                        "item.json", ("{\"id\":\"" + V2_AURA + "\",\"slot\":\"AURA\",\"fx\":{\"type\":\"aura\",\"particle\":\"soft\","
                                + "\"colors\":[\"#FFE27A\",\"#FF5A3C\",\"#7A2BFF\"],\"rate\":30,\"size\":1.2,\"life\":1.4,\"fade\":0.3,\"sizeEnd\":0.3}}")
                                .getBytes(StandardCharsets.UTF_8))));
                cat.add("items", arr);
                CosmeticsCatalog.ignoreRemoteForTest = false; CosmeticsCatalog.mergeRemote(cat); CosmeticsCatalog.ignoreRemoteForTest = true;
                for (String id : List.of(V2_CAPE, V2_WINGS, V2_AURA)) CosmeticAssets.request(CosmeticsCatalog.get(id));
                return "ok";
            } catch (Exception e) {
                return e.toString();
            }
        });
        check(prep.equals("ok"), "Cosmetics v2: synthetic special items prepared (" + prep + ")");
        ctx.waitFor(c -> List.of(V2_CAPE, V2_WINGS, V2_AURA).stream().allMatch(id -> CosmeticAssets.state(CosmeticsCatalog.get(id)) != CosmeticAssets.State.LOADING), 600);
        String states = ctx.computeOnClient(c -> List.of(V2_CAPE, V2_WINGS, V2_AURA).stream()
                .map(id -> id + "=" + CosmeticAssets.state(CosmeticsCatalog.get(id)) + (CosmeticAssets.error(CosmeticsCatalog.get(id)) != null ? "(" + CosmeticAssets.error(CosmeticsCatalog.get(id)) + ")" : ""))
                .reduce((a, b) -> a + " " + b).orElse(""));
        check(!states.contains("FAILED") && !states.contains("LOADING"), "Cosmetics v2: animated, per-face and soft-particle items load (" + states + ")");

        // ---- animated texture: frames advance on the shared clock, one upload per change, none when nothing changes
        String anim = ctx.computeOnClient(c -> {
            var m = CosmeticAssets.model(CosmeticsCatalog.get(V2_CAPE));
            var t = m.texture();
            if (!t.animated() || t.frames != 4 || t.frameW != 64 || t.frameH != 32) return "bad strip " + t.frames + " " + t.frameW + "x" + t.frameH;
            t.update(0.0);
            int f0 = t.shownFrame, u0 = t.uploads;
            t.update(0.1);                       // same blend step: no upload
            int u1 = t.uploads;
            t.update(1.2);                       // interpolating toward frame 1: a blend step, uploaded
            int u2 = t.uploads;
            t.update(2.1);                       // frame 1
            int f1 = t.shownFrame;
            t.update(6.05);                      // frame 3
            int f3 = t.shownFrame;
            t.update(8.0);                       // wraps to frame 0
            int fw = t.shownFrame;
            return f0 + "," + f1 + "," + f3 + "," + fw + " uploads " + u0 + "->" + u1 + "->" + u2 + (m.glow() != null && m.glow().animated() ? " glow" : " NOGLOW");
        });
        check(anim.startsWith("0,1,3,0 ") && anim.endsWith(" glow"), "Cosmetics v2: animated texture plays its frames in order and wraps (" + anim + ")");
        String[] up = anim.substring(anim.indexOf("uploads ") + 8, anim.lastIndexOf(' ')).split("->");
        check(up[0].equals(up[1]) && Integer.parseInt(up[2]) == Integer.parseInt(up[1]) + 1,
                "Cosmetics v2: an animated texture uploads only when the frame or blend step changes (" + anim + ")");

        // ---- worn by the second player: drawn, glow layer drawn, the frame keeps moving in real time
        ctx.runOnClient(c -> {
            Wardrobe.resetForTest();
            if (!Cosmetics.get().isEnabled()) Cosmetics.get().setEnabled(true);
            PlayerCosmetics.putForTest(BOT, Loadout.EMPTY.with(Slot.CAPE, new Loadout.Worn(V2_CAPE, -1)).with(Slot.AURA, new Loadout.Worn(V2_AURA, -1)));
            c.player.setYaw(0f);
            c.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            CosmeticsPerf.takeDrawn();
            CosmeticFx.takeSpawned();
        });
        long glow0 = ctx.computeOnClient(c -> CosmeticsPerf.glowParts);
        int soft0 = ctx.computeOnClient(c -> net.evadeclient.cosmetics.SoftParticle.spawnedTotal);
        int up0 = ctx.computeOnClient(c -> CosmeticAssets.model(CosmeticsCatalog.get(V2_CAPE)).texture().uploads);
        ctx.waitTicks(20);
        ctx.takeScreenshot("cosmetic-v2-animated-cape-a");
        ctx.waitTicks(3);
        ctx.takeScreenshot("cosmetic-v2-animated-cape-b");
        long drawn = ctx.computeOnClient(c -> CosmeticsPerf.takeDrawn());
        long glow = ctx.computeOnClient(c -> CosmeticsPerf.glowParts) - glow0;
        int soft = ctx.computeOnClient(c -> net.evadeclient.cosmetics.SoftParticle.spawnedTotal) - soft0;
        int ups = ctx.computeOnClient(c -> CosmeticAssets.model(CosmeticsCatalog.get(V2_CAPE)).texture().uploads) - up0;
        String why = ctx.computeOnClient(c -> "bot " + (c.world.getEntityById(BOT_ID) != null) + ", data " + (CosmeticsEngine.dataFor(BOT) != null)
                + ", worn " + PlayerCosmetics.get(BOT) + ", on " + Cosmetics.get().isEnabled());
        check(drawn > 0 && glow > 0, "Cosmetics v2: the animated cape and its emissive layer are drawn in game (" + drawn + " draws, " + glow + " glow parts; " + why + ")");
        check(ups > 0 && ups <= 23 * 4, "Cosmetics v2: the frame advances in game, a bounded number of uploads (" + ups + " in ~23 ticks)");
        boolean stillOn = ctx.computeOnClient(c -> Cosmetics.get().isEnabled());
        check(soft > 0 && stillOn, "Cosmetics v2: a soft-particle aura spawns soft glow motes without crashing (" + soft + " spawned, module still on: " + stillOn + ")");

        // ---- cape physics: at rest it settles, walking lifts it (the spring follows vanilla's cape values)
        ctx.waitTicks(20);
        float rest = ctx.computeOnClient(c -> CosmeticsEngine.dataFor(BOT).capeSwing);
        float maxSwing = 0;
        for (int i = 0; i < 12; i++) {
            ctx.runOnClient(c -> {
                var bot = c.world.getEntityById(BOT_ID);
                // walk forward along the way it faces
                double yaw = Math.toRadians(bot.getYaw());
                bot.setPosition(bot.getX() - Math.sin(yaw) * 0.28, bot.getY(), bot.getZ() + Math.cos(yaw) * 0.28);
            });
            ctx.waitTicks(1);
            float sw = ctx.computeOnClient(c -> CosmeticsEngine.dataFor(BOT).capeSwing);
            maxSwing = Math.max(maxSwing, Math.abs(sw));
        }
        ctx.takeScreenshot("cosmetic-v2-cape-moving");
        check(maxSwing > Math.abs(rest) + 3, "Cosmetics v2: the cape swings out when the wearer moves (rest " + String.format("%.1f", rest) + ", moving " + String.format("%.1f", maxSwing) + " degrees)");
        ctx.waitTicks(40);
        float settled = ctx.computeOnClient(c -> CosmeticsEngine.dataFor(BOT).capeSwing);
        check(Math.abs(settled) < maxSwing * 0.5f, "Cosmetics v2: the cape settles when the wearer stops (" + String.format("%.1f", settled) + " degrees)");

        // ---- wings: beat faster when sprinting, fold when sneaking
        ctx.runOnClient(c -> PlayerCosmetics.putForTest(BOT, Loadout.EMPTY.with(Slot.WINGS, new Loadout.Worn(V2_WINGS, -1))));
        ctx.waitTicks(20);
        float idleRate = ctx.computeOnClient(c -> CosmeticsEngine.dataFor(BOT).wingRate);
        ctx.takeScreenshot("cosmetic-v2-wings-idle");
        ctx.runOnClient(c -> c.world.getEntityById(BOT_ID).setSprinting(true));
        ctx.waitTicks(25);
        float sprintRate = ctx.computeOnClient(c -> CosmeticsEngine.dataFor(BOT).wingRate);
        ctx.runOnClient(c -> { var b = c.world.getEntityById(BOT_ID); b.setSprinting(false); b.setSneaking(true); b.setPose(net.minecraft.entity.EntityPose.CROUCHING); });
        ctx.waitTicks(15);
        float fold = ctx.computeOnClient(c -> CosmeticsEngine.dataFor(BOT).fold);
        ctx.takeScreenshot("cosmetic-v2-wings-folded");
        ctx.runOnClient(c -> { var b = c.world.getEntityById(BOT_ID); b.setSneaking(false); b.setPose(net.minecraft.entity.EntityPose.STANDING); });
        check(sprintRate > idleRate * 1.4f, "Cosmetics v2: wings beat faster when sprinting (" + String.format("%.2f", idleRate) + " -> " + String.format("%.2f", sprintRate) + ")");
        check(fold > 0.9f, "Cosmetics v2: wings fold while sneaking (fold " + String.format("%.2f", fold) + ")");

        // ---- Wardrobe grid plays the animated thumbnail strip
        float[] tf = ctx.computeOnClient(c -> CosmeticAssets.thumbFrame(CosmeticsCatalog.get(V2_CAPE)));
        check(tf != null && tf[3] == 4, "Cosmetics v2: an animated item's thumbnail strip is read as 4 frames");

        // ---- clean up: nothing worn, the synthetic items stay in the catalog but unused
        ctx.runOnClient(c -> PlayerCosmetics.putForTest(BOT, Loadout.EMPTY));
    }

    private static JsonObject v2Item(String id, String slot, java.util.Map<String, byte[]> files) throws java.io.IOException {
        JsonObject it = new JsonObject();
        it.addProperty("id", id);
        it.addProperty("name", id);
        it.addProperty("slot", slot);
        it.addProperty("rarity", "special");
        JsonObject hashes = new JsonObject();
        Path dir = CosmeticAssets.cacheDir().resolve(id);
        Files.createDirectories(dir);
        for (var f : files.entrySet()) {
            Files.write(dir.resolve(f.getKey()), f.getValue());
            hashes.addProperty(f.getKey(), CosmeticAssets.sha256(f.getValue()));
        }
        JsonObject assets = new JsonObject();
        assets.addProperty("base", "https://evadeclient.com/cosmetics/" + id + "/");
        assets.add("files", hashes);
        it.add("assets", assets);
        return it;
    }

    /** A strip of {@code frames} w x h frames, each a different hue (the glow version: thin bright lines on black). */
    private static byte[] v2Strip(int w, int h, int frames, boolean glow) throws java.io.IOException {
        int[] hues = {0xFFE0303A, 0xFFF0A020, 0xFF30C0E0, 0xFF9050F0};
        try (var img = new net.minecraft.client.texture.NativeImage(w, h * frames, false)) {
            for (int f = 0; f < frames; f++) for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c;
                if (glow) c = (y + f * 3) % 8 == 0 && x % 4 != 0 ? 0xFFFFFFFF & (hues[f % 4] | 0xFF000000) : 0xFF000000;
                else c = ((x / 4 + y / 4) % 2 == 0) ? hues[f % 4] : (hues[f % 4] & 0xFF7F7F7F) | 0xFF000000;
                img.setColorArgb(x, y + f * h, c);
            }
            Path tmp = Files.createTempFile("evade-v2", ".png");
            img.writeTo(tmp);
            byte[] b = Files.readAllBytes(tmp);
            Files.deleteIfExists(tmp);
            return b;
        }
    }

    /** Player draws with cosmetics over a few ticks. */
    private static long drawnOver(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> CosmeticsPerf.takeDrawn());
        ctx.waitTicks(4);
        return ctx.computeOnClient(c -> CosmeticsPerf.takeDrawn());
    }

    /** Cosmetics time per frame (microseconds) over 40 ticks (frames counted from the frame times). */
    private static double frameCost(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> CosmeticsPerf.takeTotalMicros());
        long t0 = System.nanoTime();
        ctx.waitTicks(40);
        double total = ctx.computeOnClient(c -> CosmeticsPerf.takeTotalMicros());
        double ms = (System.nanoTime() - t0) / 1e6;
        double avg = ctx.computeOnClient(c -> {
            double sum = 0;
            for (int i = 0; i < 30; i++) sum += FrameStats.recent(i);
            return sum / 30;
        });
        double frames = Math.max(1, ms / Math.max(0.5, avg));
        return total / frames;
    }

    /** A cached download is used without the network when its hash matches, and refused when it doesn't. */
    private static void testDownloadCache(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            try {
                CosmeticItem ember = CosmeticsCatalog.get("ember_mantle");
                JsonObject cat = new JsonObject();
                cat.addProperty("version", "test");
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (String id : List.of("cached_cape", "tampered_cape")) {
                    JsonObject it = new JsonObject();
                    it.addProperty("id", id);
                    it.addProperty("name", id);
                    it.addProperty("slot", "cape");
                    JsonObject files = new JsonObject();
                    ember.files.forEach(files::addProperty);
                    JsonObject assets = new JsonObject();
                    assets.addProperty("base", "https://evadeclient.com/cosmetics/" + id + "/");
                    assets.add("files", files);
                    it.add("assets", assets);
                    arr.add(it);
                    Path dir = CosmeticAssets.cacheDir().resolve(id);
                    Files.createDirectories(dir);
                    for (String f : ember.files.keySet()) {
                        byte[] b = readBundled("ember_mantle", f);
                        if (id.startsWith("tampered") && f.equals("model.json")) b = (new String(b, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
                        Files.write(dir.resolve(f), b);
                    }
                }
                cat.add("items", arr);
                CosmeticsCatalog.ignoreRemoteForTest = false; CosmeticsCatalog.mergeRemote(cat); CosmeticsCatalog.ignoreRemoteForTest = true;
                CosmeticAssets.request(CosmeticsCatalog.get("cached_cape"));
                CosmeticAssets.request(CosmeticsCatalog.get("tampered_cape"));
                return "ok";
            } catch (Exception e) {
                return e.toString();
            }
        });
        check(r.equals("ok"), "Cosmetics: test cache prepared (" + r + ")");
        ctx.waitFor(c -> CosmeticAssets.state(CosmeticsCatalog.get("cached_cape")) != CosmeticAssets.State.LOADING
                && CosmeticAssets.state(CosmeticsCatalog.get("tampered_cape")) != CosmeticAssets.State.LOADING, 600);
        String states = ctx.computeOnClient(c -> CosmeticAssets.state(CosmeticsCatalog.get("cached_cape")) + "|"
                + CosmeticAssets.state(CosmeticsCatalog.get("tampered_cape")));
        // the tampered copy is never used: it is fetched again (and that copy must match too), so offline it fails
        check(states.startsWith("READY|") && !Files.exists(Path.of("never")), "Cosmetics: a downloaded item loads from the cache when its sha256 matches (" + states + ")");
        String tampered = ctx.computeOnClient(c -> {
            try {
                byte[] b = Files.readAllBytes(CosmeticAssets.cacheDir().resolve("tampered_cape").resolve("model.json"));
                return CosmeticAssets.state(CosmeticsCatalog.get("tampered_cape")) == CosmeticAssets.State.FAILED
                        || CosmeticAssets.matches(b, CosmeticsCatalog.get("tampered_cape").files.get("model.json")) ? "safe" : "USED TAMPERED";
            } catch (Exception e) {
                return e.toString();
            }
        });
        check(tampered.equals("safe"), "Cosmetics: a cached file that doesn't match its sha256 is never used (" + tampered + ")");
    }

    private static byte[] readBundled(String id, String file) throws java.io.IOException {
        try (var in = CosmeticsTest.class.getResourceAsStream("/assets/evade/cosmetics/" + id + "/" + file)) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ mock evadeclient.com

    private static final String CATALOG = "{\"version\":\"7\",\"items\":[{\"id\":\"tidal_weave\",\"name\":\"Tidal Weave\",\"slot\":\"CAPE\","
            + "\"rarity\":\"rare\",\"priceCents\":555,\"currency\":\"USD\",\"partner\":null,\"assets\":{\"base\":\"https://evadeclient.com/cosmetics/tidal_weave/\",\"files\":{}}}]}";

    private static CompletableFuture<JsonObject> mock(String method, String path, JsonObject body, String token) {
        JsonObject r = new JsonObject();
        if (path.startsWith("players")) {
            JsonObject players = new JsonObject();
            String q = java.net.URLDecoder.decode(path.substring(path.indexOf('=') + 1), StandardCharsets.UTF_8);
            for (String u : q.split(",")) {
                if (u.length() != 32) return CompletableFuture.failedFuture(new IllegalArgumentException("dashed uuid " + u));
                JsonObject p = new JsonObject();
                p.add("equipped", JsonParser.parseString("{\"CAPE\":{\"id\":\"ember_mantle\"}}"));
                players.add(u, p);
            }
            r.add("players", players);
        } else if (path.equals("me")) {
            r = JsonParser.parseString("{\"owned\":[],\"equipped\":{}}").getAsJsonObject();
        } else if (path.equals("link-code")) {
            r.addProperty("code", "EVD-TEST");
            r.addProperty("expiresAt", System.currentTimeMillis() + 600_000);
        } else if (path.equals("catalog")) {
            r = JsonParser.parseString(CATALOG).getAsJsonObject();
        } else {
            r.addProperty("ok", true);
        }
        return CompletableFuture.completedFuture(r);
    }
}
