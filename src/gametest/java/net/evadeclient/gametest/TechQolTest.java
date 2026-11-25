package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.hud.TechInfoHud;
import net.evadeclient.module.mods.BreakGuard;
import net.evadeclient.module.mods.PlacementLock;
import net.evadeclient.module.visuals.LightOverlay;
import net.evadeclient.module.visuals.TechOverlays;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.ServerRules;
import net.evadeclient.util.SlimeChunks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Technical-player helpers: the Tech Info HUD, Tech Overlays (slime chunks from the world's own seed or a typed one,
 * random tick range, region borders, block grid, beacon and conduit range, singleplayer structure boxes), Light
 * Overlay's Headroom, Placement Lock and Break Guard (which only hold back your own clicks), the server's off-switches
 * for all of them, and what the overlays cost to draw. Everything runs on the test's own singleplayer world.
 */
public final class TechQolTest {
    private TechQolTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    @SuppressWarnings("unchecked")
    private static <S extends Setting<?>> S setting(Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return (S) s;
        throw new AssertionError("No setting " + name + " on " + m.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(Module m, String name, Object value) {
        ((Setting) setting(m, name)).set(value);
    }

    private static void reset(Module m) {
        for (Setting<?> s : m.getSettings()) s.reset();
        m.setEnabled(false);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("gamemode survival @a");
        server.runCommand("tp @a 1200 -60 1200 0 30");
        ctx.waitTicks(40);
        // A stone floor to build on, and clear air above it.
        server.runCommand("fill 1190 -61 1190 1215 -61 1215 stone");
        server.runCommand("fill 1190 -60 1190 1215 -50 1215 air");
        ctx.waitTicks(10);

        techInfo(ctx);
        slimeSeeds(ctx);
        beaconAndConduit(ctx, sp);
        overlaysAndCost(ctx);
        lightHeadroom(ctx, sp);
        placementLock(ctx, sp);
        breakGuard(ctx, sp);
        serverRules(ctx);

        ctx.runOnClient(c -> {
            for (Class<? extends Module> k : List.of(TechInfoHud.class, TechOverlays.class, PlacementLock.class, BreakGuard.class))
                reset(EvadeClient.modules.get(k));
            reset(EvadeClient.modules.get(LightOverlay.class));
        });
        server.runCommand("gamemode creative @a");
        server.runCommand("clear @a");
    }

    // ------------------------------------------------------------------ Tech Info

    private static void techInfo(ClientGameTestContext ctx) {
        List<String> lines = ctx.computeOnClient(c -> {
            TechInfoHud h = EvadeClient.modules.get(TechInfoHud.class);
            h.setEnabled(true);
            set(h, "Difficulty", true);
            set(h, "Dimension", true);
            set(h, "Loaded Entities", true);
            set(h, "Loaded Chunks", true);
            set(h, "Weather", true);
            h.textForTest(); // asks the integrated server for the local difficulty
            return h.textForTest();
        });
        check(lines.stream().anyMatch(s -> s.matches("Light: \\d+ block, \\d+ sky")),
                "Tech Info: light at your feet as block and sky light (" + lines + ")");
        check(lines.contains("Slime Chunk: Yes") || lines.contains("Slime Chunk: No"),
                "Tech Info: singleplayer knows its own seed, so the slime chunk line is a yes or no");
        check(lines.stream().anyMatch(s -> s.startsWith("Moon: ") && !s.endsWith("Unknown")), "Tech Info: shows the moon phase");
        check(lines.contains("Dimension: Overworld") && lines.stream().anyMatch(s -> s.matches("Entities: \\d+"))
                        && lines.stream().anyMatch(s -> s.matches("Chunks: \\d+")) && lines.stream().anyMatch(s -> s.startsWith("Weather: ")),
                "Tech Info: dimension, loaded entities, loaded chunks and weather lines");
        ctx.waitTicks(30);
        String diff = ctx.computeOnClient(c -> {
            List<String> l = EvadeClient.modules.get(TechInfoHud.class).textForTest();
            return l.stream().filter(s -> s.startsWith("Difficulty: ")).findFirst().orElse("");
        });
        check(diff.contains("(local "), "Tech Info: local difficulty read from the integrated server in singleplayer (" + diff + ")");
        ctx.waitTicks(2);
        ctx.takeScreenshot("tech-info-hud");
    }

    // ------------------------------------------------------------------ slime chunks and seeds

    private static void slimeSeeds(ClientGameTestContext ctx) {
        Object[] r = ctx.computeOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            long worldSeed = c.getServer().getOverworld().getSeed();
            Long used = SlimeChunks.seed();
            set(o, "Seed", "12345");
            Long typedNum = SlimeChunks.typedSeed();
            Long stillWorld = SlimeChunks.seed();
            set(o, "Seed", "hello");
            Long typedText = SlimeChunks.typedSeed();
            set(o, "Seed", "");
            Long none = SlimeChunks.typedSeed();
            // Ground truth from the game's own roll for a few chunks.
            int agree = 0;
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                boolean mine = SlimeChunks.isSlimeChunk(worldSeed, x, z);
                boolean game = net.minecraft.util.math.random.ChunkRandom.getSlimeRandom(x, z, worldSeed, 987234911L).nextInt(10) == 0;
                if (mine == game) agree++;
            }
            set(o, "Slime Radius", 8.0);
            o.setEnabled(true);
            o.onTick();
            int found = o.slimeChunksForTest();
            return new Object[]{used != null && used == worldSeed, typedNum, stillWorld != null && stillWorld == worldSeed,
                    typedText, none, agree, found};
        });
        check((boolean) r[0], "Slime chunks: singleplayer uses the world's own seed");
        check(Long.valueOf(12345L).equals(r[1]) && Long.valueOf("hello".hashCode()).equals(r[3]) && r[4] == null,
                "Slime chunks: a typed seed reads like the game's seed box (number as is, text by its hash, empty = none)");
        check((boolean) r[2], "Slime chunks: in singleplayer the world's seed wins over a typed one");
        check((int) r[5] == 169, "Slime chunks: the slime roll matches the game's own for every chunk checked");
        check((int) r[6] > 0 && (int) r[6] < 289, "Slime chunks: some (not all) chunks within 8 are slime chunks (" + r[6] + ")");
    }

    // ------------------------------------------------------------------ beacon and conduit

    private static void beaconAndConduit(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        // A level-1 beacon: a 3x3 of iron under it.
        server.runCommand("fill 1205 -60 1200 1207 -60 1202 iron_block");
        server.runCommand("setblock 1206 -59 1201 beacon");
        // A full conduit frame: three 5x5 squares through its centre, hollowed out (42 blocks).
        server.runCommand("fill 1196 -57 1208 1200 -57 1212 prismarine");
        server.runCommand("fill 1198 -59 1208 1198 -55 1212 prismarine");
        server.runCommand("fill 1196 -59 1210 1200 -55 1210 prismarine");
        server.runCommand("fill 1197 -58 1209 1199 -56 1211 water");
        server.runCommand("setblock 1198 -57 1210 conduit");
        ctx.waitTicks(20);
        int[] lv = ctx.computeOnClient(c -> new int[]{
                TechOverlays.beaconLevel(c.world, new BlockPos(1206, -59, 1201)),
                TechOverlays.conduitFrame(c.world, new BlockPos(1198, -57, 1210))});
        check(lv[0] == 1, "Beacon Range: a 3x3 iron base counts as a level-1 pyramid (" + lv[0] + ")");
        check(lv[1] == 42, "Beacon Range: a full conduit frame counts 42 blocks (" + lv[1] + ")");

        int beacon = ctx.computeOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            o.setEnabled(true);
            c.crosshairTarget = new BlockHitResult(new Vec3d(1206.5, -58.5, 1201), Direction.NORTH, new BlockPos(1206, -59, 1201), false);
            o.onTick();
            return o.rangeRadiusForTest();
        });
        check(beacon == 20, "Beacon Range: looking at a level-1 beacon shows its 20-block effect area (" + beacon + ")");
        int conduit = ctx.computeOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            c.crosshairTarget = new BlockHitResult(new Vec3d(1198.5, -56.7, 1210.5), Direction.UP, new BlockPos(1198, -57, 1210), false);
            o.onTick();
            return o.rangeRadiusForTest();
        });
        check(conduit == 96, "Beacon Range: looking at a conduit with a full frame shows its 96-block reach (" + conduit + ")");
        int stone = ctx.computeOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            c.crosshairTarget = new BlockHitResult(new Vec3d(1206.5, -59, 1200.5), Direction.UP, new BlockPos(1206, -60, 1200), false);
            o.onTick();
            return o.rangeRadiusForTest();
        });
        check(stone == 96, "Beacon Range: the range stays up for a moment after you look away");
        ctx.runOnClient(c -> c.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, new Vec3d(1206.5, -58.5, 1201.5)));
        ctx.waitTicks(3);
        ctx.takeScreenshot("tech-beacon-range");
    }

    // ------------------------------------------------------------------ overlays and their cost

    /** A buffer that only counts vertices: lets onRender run outside the world pass. */
    private static final class Counter implements VertexConsumer {
        int vertices;
        @Override public VertexConsumer vertex(float x, float y, float z) { vertices++; return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(int argb) { return this; }
        @Override public VertexConsumer texture(float u, float v) { return this; }
        @Override public VertexConsumer overlay(int u, int v) { return this; }
        @Override public VertexConsumer light(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public VertexConsumer lineWidth(float w) { return this; }
    }

    private static void overlaysAndCost(ClientGameTestContext ctx) {
        Object[] r = ctx.computeOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            Counter counter = new Counter();
            VertexConsumerProvider vcp = layer -> counter;
            RenderContext rc = new RenderContext(new MatrixStack(), vcp, 1f, 0.016f);
            int[] each = new int[5];
            String[] names = {"Slime Chunks", "Random Tick Range", "Region Borders", "Block Grid", "Structure Boxes"};
            for (String n : names) set(o, n, false);
            set(o, "Beacon Range", false);
            o.onTick();
            o.onRender(rc);
            int none = o.linesForTest();
            for (int i = 0; i < 4; i++) {
                set(o, names[i], true);
                o.onTick();
                o.onRender(rc);
                each[i] = o.linesForTest();
                set(o, names[i], false);
            }
            // Everything on at once, drawn 200 times back to back: the per-frame cost.
            for (String n : names) set(o, n, true);
            set(o, "Beacon Range", true);
            o.requestStructures();
            o.onTick();
            for (int i = 0; i < 20; i++) o.onRender(rc); // warm up
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) o.onRender(rc);
            double us = (System.nanoTime() - t0) / 200 / 1000.0;
            return new Object[]{none, each, us, o.linesForTest(), counter.vertices, o.structuresAvailable()};
        });
        int[] each = (int[]) r[1];
        check((int) r[0] == 0, "Tech Overlays: nothing is drawn with every overlay off");
        check(each[0] > 0, "Tech Overlays: slime chunks are outlined (" + each[0] + " lines)");
        check(each[1] >= 96, "Tech Overlays: the random tick circle is drawn (" + each[1] + " lines)");
        // Region borders only show within 128 blocks of an edge: 1200 is 176 from 1024 and 336 from 1536.
        check(each[2] == 0, "Tech Overlays: no region border is drawn far from any region edge");
        check(each[3] == 2 * (2 * 8 + 2), "Tech Overlays: the block grid draws its lines around you (" + each[3] + ")");
        check((boolean) r[5], "Tech Overlays: structure boxes are available in singleplayer (own integrated server)");
        double us = (double) r[2];
        EvadeClient.LOG.info("[gametest] PERF Tech Overlays: everything on, {} lines, {} us per frame", r[3],
                String.format(Locale.ROOT, "%.1f", us));
        check(us < 1500, String.format(Locale.ROOT, "Tech Overlays: drawing every overlay costs under 1.5 ms a frame (%.1f us, %d lines)", us, (int) r[3]));

        // Region edge: stand near x = 1024 and the border shows.
        int region = ctx.computeOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            for (String n : new String[]{"Slime Chunks", "Random Tick Range", "Block Grid", "Structure Boxes", "Beacon Range"}) set(o, n, false);
            set(o, "Region Borders", true);
            c.player.setPosition(1030, c.player.getY(), 1200);
            o.onRender(new RenderContext(new MatrixStack(), layer -> new Counter(), 1f, 0.016f));
            c.player.setPosition(1200.5, c.player.getY(), 1200.5);
            return o.linesForTest();
        });
        check(region > 0, "Tech Overlays: a region border is drawn when you're near one (" + region + " lines)");
        ctx.runOnClient(c -> {
            TechOverlays o = EvadeClient.modules.get(TechOverlays.class);
            set(o, "Slime Chunks", true);
            set(o, "Random Tick Range", true);
            set(o, "Block Grid", true);
            c.player.setPitch(45f);
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot("tech-overlays");
        // Structure scan finished on the server thread without trouble (a flat world may have none nearby).
        int boxes = ctx.computeOnClient(c -> EvadeClient.modules.get(TechOverlays.class).structureBoxesForTest());
        check(boxes >= 0 && net.evadeclient.module.ModuleManager.CRASHED.stream().noneMatch(s -> s.startsWith("Tech")),
                "Tech Overlays: the singleplayer structure scan runs without errors (" + boxes + " boxes)");
        ctx.runOnClient(c -> reset(EvadeClient.modules.get(TechOverlays.class)));
    }

    // ------------------------------------------------------------------ Light Overlay headroom

    private static void lightHeadroom(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        // A dark slot one block high under a stone slab of roof.
        server.runCommand("fill 1210 -61 1190 1214 -58 1194 stone");
        server.runCommand("fill 1211 -60 1191 1213 -60 1193 air");
        ctx.waitTicks(10);
        int[] r = ctx.computeOnClient(c -> {
            LightOverlay lo = EvadeClient.modules.get(LightOverlay.class);
            lo.setEnabled(true);
            lo.scan();
            int any = lo.markColorAt(1212, -60, 1192);
            set(lo, "Headroom", "Tall Mobs");
            lo.scan();
            int tall = lo.markColorAt(1212, -60, 1192);
            reset(lo);
            return new int[]{any, tall};
        });
        check(r[0] != 0, "Light Overlay: a dark one-block-high gap is marked for any mob");
        check(r[1] == 0, "Light Overlay: with Headroom on Tall Mobs, a one-block-high gap isn't marked");
    }

    // ------------------------------------------------------------------ Placement Lock

    private static void placementLock(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("tp @a 1200.5 -60 1200.5 0 60");
        server.runCommand("item replace entity @a weapon.mainhand with stone 64");
        ctx.waitTicks(10);

        // The rules themselves, with an anchor at the origin.
        boolean[] rules = ctx.computeOnClient(c -> {
            PlacementLock pl = EvadeClient.modules.get(PlacementLock.class);
            pl.setEnabled(true);
            BlockPos a = new BlockPos(0, 64, 0);
            boolean[] out = new boolean[12];
            set(pl, "Lock To", "Layer");
            pl.resetAnchor();
            out[0] = pl.allowsTarget(new BlockPos(5, 70, 5), Direction.UP, Direction.NORTH); // no anchor: anything goes
            pl.placed(a, Direction.UP, Direction.NORTH);
            out[1] = pl.allowsTarget(new BlockPos(5, 64, -3), Direction.UP, Direction.NORTH);
            out[2] = !pl.allowsTarget(new BlockPos(0, 65, 0), Direction.UP, Direction.NORTH);
            set(pl, "Lock To", "Column");
            out[3] = pl.allowsTarget(new BlockPos(0, 70, 0), Direction.UP, Direction.NORTH);
            out[4] = !pl.allowsTarget(new BlockPos(1, 64, 0), Direction.EAST, Direction.NORTH);
            set(pl, "Lock To", "Wall"); // facing north at the anchor: the wall keeps Z
            out[5] = pl.allowsTarget(new BlockPos(3, 67, 0), Direction.EAST, Direction.EAST);
            out[6] = !pl.allowsTarget(new BlockPos(0, 64, 1), Direction.SOUTH, Direction.NORTH);
            set(pl, "Lock To", "Line");
            pl.resetAnchor();
            pl.placed(a, Direction.UP, Direction.NORTH);
            out[7] = pl.allowsTarget(new BlockPos(1, 64, 0), Direction.EAST, Direction.NORTH);
            out[8] = !pl.allowsTarget(new BlockPos(1, 65, 0), Direction.UP, Direction.NORTH);
            pl.placed(new BlockPos(1, 64, 0), Direction.EAST, Direction.NORTH); // the row now runs along X
            out[9] = !pl.allowsTarget(new BlockPos(0, 64, 1), Direction.SOUTH, Direction.NORTH);
            set(pl, "Lock To", "Same Face");
            out[10] = pl.allowsTarget(new BlockPos(9, 9, 9), Direction.UP, Direction.NORTH);
            out[11] = !pl.allowsTarget(new BlockPos(9, 9, 9), Direction.EAST, Direction.NORTH);
            set(pl, "Lock To", "Layer");
            pl.resetAnchor();
            return out;
        });
        String[] what = {"nothing is locked before the first block", "Layer allows the same height",
                "Layer holds back a block one higher", "Column allows straight up", "Column holds back a block beside",
                "Wall allows the same upright plane", "Wall holds back a block out of the plane",
                "Line allows a neighbour in a row", "Line holds back a diagonal", "Line keeps the row's direction once set",
                "Same Face allows the first side", "Same Face holds back another side"};
        for (int i = 0; i < rules.length; i++) check(rules[i], "Placement Lock: " + what[i]);

        // Real clicks: the first sets the anchor, one above it is held back (nothing sent), one beside it goes through.
        Object[] clicks = ctx.computeOnClient(c -> {
            PlacementLock pl = EvadeClient.modules.get(PlacementLock.class);
            BlockHitResult first = new BlockHitResult(new Vec3d(1202.5, -60.0, 1201.5), Direction.UP, new BlockPos(1202, -61, 1201), false);
            ActionResult a = c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, first);
            BlockPos anchor = pl.anchorForTest();
            BlockHitResult up = new BlockHitResult(new Vec3d(1202.5, -59.0, 1201.5), Direction.UP, new BlockPos(1202, -60, 1201), false);
            ActionResult b = c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, up);
            BlockHitResult beside = new BlockHitResult(new Vec3d(1203.5, -60.0, 1201.5), Direction.UP, new BlockPos(1203, -61, 1201), false);
            ActionResult d = c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, beside);
            return new Object[]{a.isAccepted(), anchor, b == ActionResult.FAIL, d.isAccepted(), pl.blockedForTest()};
        });
        check((boolean) clicks[0] && new BlockPos(1202, -60, 1201).equals(clicks[1]),
                "Placement Lock: the first real placement goes through and becomes the anchor (" + clicks[1] + ")");
        check((boolean) clicks[2] && (int) clicks[4] >= 1, "Placement Lock: a placement above the layer is held back");
        check((boolean) clicks[3], "Placement Lock: a placement on the same layer goes through");
        ctx.waitTicks(10);
        // Read on the server's own thread: the test pauses it between ticks, so the client thread must not wait on it.
        boolean[] world = server.computeOnServer(s -> new boolean[]{
                s.getOverworld().getBlockState(new BlockPos(1202, -60, 1201)).isOf(Blocks.STONE),
                s.getOverworld().getBlockState(new BlockPos(1202, -59, 1201)).isAir(),
                s.getOverworld().getBlockState(new BlockPos(1203, -60, 1201)).isOf(Blocks.STONE)});
        check(world[0] && world[1] && world[2], "Placement Lock: the server has exactly the allowed blocks, never the held-back one");
        ctx.runOnClient(c -> reset(EvadeClient.modules.get(PlacementLock.class)));
        server.runCommand("fill 1202 -60 1201 1203 -59 1201 air");
    }

    // ------------------------------------------------------------------ Break Guard

    private static void breakGuard(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("item replace entity @a weapon.mainhand with diamond_pickaxe");
        server.runCommand("setblock 1201 -60 1202 budding_amethyst");
        server.runCommand("setblock 1199 -61 1202 farmland");
        server.runCommand("setblock 1199 -60 1202 wheat[age=3]");
        ctx.waitTicks(10);
        Object[] r = ctx.computeOnClient(c -> {
            BreakGuard g = EvadeClient.modules.get(BreakGuard.class);
            g.setEnabled(true);
            boolean budding = g.guards(Blocks.BUDDING_AMETHYST.getDefaultState());
            boolean stone = g.guards(Blocks.STONE.getDefaultState());
            boolean young = g.guards(Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 3));
            boolean ripe = g.guards(Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7));
            boolean attack = c.interactionManager.attackBlock(new BlockPos(1201, -60, 1202), Direction.UP);
            boolean progress = c.interactionManager.updateBlockBreakingProgress(new BlockPos(1199, -60, 1202), Direction.UP);
            boolean stoneAttack = c.interactionManager.attackBlock(new BlockPos(1204, -61, 1204), Direction.UP);
            c.interactionManager.cancelBlockBreaking();
            return new Object[]{budding, stone, young, ripe, attack, progress, stoneAttack, g.blockedForTest()};
        });
        check((boolean) r[0] && !(boolean) r[1], "Break Guard: budding amethyst is protected by default, stone isn't");
        check((boolean) r[2] && !(boolean) r[3], "Break Guard: unripe wheat is protected, fully grown wheat isn't");
        check(!(boolean) r[4] && !(boolean) r[5] && (int) r[7] >= 2, "Break Guard: your clicks on protected blocks are held back");
        check((boolean) r[6], "Break Guard: other blocks break as normal");
        ctx.waitTicks(10);
        boolean kept = server.computeOnServer(s -> s.getOverworld().getBlockState(new BlockPos(1201, -60, 1202)).isOf(Blocks.BUDDING_AMETHYST));
        check(kept, "Break Guard: the protected block is still there on the server");
        ctx.runOnClient(c -> reset(EvadeClient.modules.get(BreakGuard.class)));
        server.runCommand("setblock 1201 -60 1202 air");
        server.runCommand("setblock 1199 -60 1202 air");
    }

    // ------------------------------------------------------------------ server off-switches

    private static void serverRules(ClientGameTestContext ctx) {
        boolean[] r = ctx.computeOnClient(c -> {
            List<Module> mods = List.of(EvadeClient.modules.get(TechInfoHud.class), EvadeClient.modules.get(TechOverlays.class),
                    EvadeClient.modules.get(PlacementLock.class), EvadeClient.modules.get(BreakGuard.class));
            for (Module m : mods) m.setEnabled(true);
            ServerRules.set(Set.of("tech info", "tech overlays", "placement lock", "break guard"));
            boolean allOff = mods.stream().noneMatch(Module::isEnabled);
            for (Module m : mods) m.setEnabled(true);
            boolean stayOff = mods.stream().noneMatch(Module::isEnabled);
            // A switched-off Placement Lock / Break Guard holds nothing back.
            PlacementLock pl = (PlacementLock) mods.get(2);
            BlockHitResult hit = new BlockHitResult(new Vec3d(1200.5, -60, 1200.5), Direction.UP, new BlockPos(1200, -61, 1200), false);
            boolean placeFree = pl.allows(c.player, Hand.MAIN_HAND, hit);
            boolean breakFree = ((BreakGuard) mods.get(3)).allows(c.player, new BlockPos(1201, -60, 1202));
            ServerRules.clear();
            boolean back = mods.stream().allMatch(Module::isEnabled);
            for (Module m : mods) m.setEnabled(false);
            return new boolean[]{allOff, stayOff, placeFree && breakFree, back};
        });
        check(r[0], "Server rules: a server can switch off Tech Info, Tech Overlays, Placement Lock and Break Guard");
        check(r[1], "Server rules: they can't be switched back on while the server forbids them");
        check(r[2], "Server rules: switched off, Placement Lock and Break Guard hold nothing back");
        check(r[3], "Server rules: leaving the server brings them back as they were");
    }
}
