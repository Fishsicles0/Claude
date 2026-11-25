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
        testDownloadCache(ctx);
        // ---- tiers and prices: three tiers, old names read as regular; every starter item is regular and priced
        String tiers = ctx.computeOnClient(c -> {
            StringBuilder b = new StringBuilder();
            for (CosmeticItem i : CosmeticsCatalog.all()) if (i.rarity != Rarity.REGULAR || i.priceCents <= 0) b.append(i.id).append(' ');
            return b.toString();
        });
        check(tiers.isEmpty() && Rarity.values().length == 3 && Rarity.parse("legendary") == Rarity.REGULAR && Rarity.parse("special") == Rarity.SPECIAL
                && Rarity.parse("Partner") == Rarity.PARTNER && Rarity.parse(null) == Rarity.REGULAR,
                "Cosmetics: regular / special / partner tiers, old names read as regular, starter items regular and priced " + tiers);

        // ---- the backend contract, against the mock
        String me = ctx.computeOnClient(c -> {
            CosmeticsCatalog.mergeRemote(JsonParser.parseString(CATALOG).getAsJsonObject());
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
            });
            if (slot == Slot.ITEM_SKIN) {
                server.runCommand("tp @a 0 -60 0 0 0");
                server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
                ctx.runOnClient(c -> c.options.hudHidden = false);   // F1 hides your hand and item
            }
            if (slot == Slot.EMOTE) ctx.runOnClient(c -> check(Emotes.play(CosmeticsCatalog.get(id)), "Cosmetics: emote " + id + " plays"));
            if (slot == Slot.TRAIL) for (int i = 0; i < 8; i++) { server.runCommand("execute as @a at @s run tp @s ~0.3 ~ ~"); ctx.waitTicks(1); }
            if (slot == Slot.KILL_EFFECT) ctx.runOnClient(c -> check(CosmeticFx.kill(c.player.getEntityPos().add(1.5, 0, 2)), "Cosmetics: kill effect plays"));
            if (slot == Slot.WIN_EFFECT) ctx.runOnClient(c -> { CosmeticFx.onMessage("You won the game!"); });
            ctx.waitTicks(slot == Slot.WIN_EFFECT ? 25 : slot == Slot.ITEM_SKIN ? 40 : 8);
            long drawn = ctx.computeOnClient(c -> CosmeticsPerf.takeDrawn());
            int spawned = ctx.computeOnClient(c -> CosmeticFx.takeSpawned());
            switch (slot.kind) {
                case MODEL, EMOTE -> check(drawn > 0, "Cosmetics: " + id + " drawn on you and the second player (" + drawn + " draws)");
                case ITEM -> check(drawn > 0, "Cosmetics: " + id + " replaces your sword in first person (" + drawn + " draws)");
                case PARTICLES, EFFECT -> check(spawned > 0, "Cosmetics: " + id + " spawns particles (" + spawned + ")");
            }
            if (slot == Slot.AURA) check(spawned > 0, "Cosmetics: aura " + id + " spawns a few motes (" + spawned + ")");
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
            Wardrobe.equip(Slot.PET, "pip_wisp", -1);
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

        // ---- emotes stop when you move or attack
        server.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(20);
        boolean playing = ctx.computeOnClient(c -> Emotes.play(CosmeticsCatalog.get("sunny_wave")) && Emotes.current() != null);
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
        check(wheel == 2, "Cosmetics: the emote wheel lists your emotes (" + wheel + ")");

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
        check(locked.startsWith("aurora_wings|false|null"), "Wardrobe: without test mode nothing unowned is worn; locked items are only tried on (" + locked + ")");
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
                CosmeticsCatalog.mergeRemote(cat);
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
