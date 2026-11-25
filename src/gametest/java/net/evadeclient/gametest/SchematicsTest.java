package net.evadeclient.gametest;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.MaterialListScreen;
import net.evadeclient.gui.SchematicBrowserScreen;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.hud.SchematicMaterialsHud;
import net.evadeclient.module.misc.Schematics;
import net.evadeclient.schematic.Materials;
import net.evadeclient.schematic.Placement;
import net.evadeclient.schematic.Schematic;
import net.evadeclient.schematic.SchematicIO;
import net.evadeclient.schematic.SchematicVerifier;
import net.evadeclient.schematic.SingleplayerPaste;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Benchmark;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schematics: the three file readers (and their limits on bad files), loading from the schematics folder, placing,
 * turning and mirroring, the ghost mesh and layer view, the verifier's counts, the material list and its HUD line,
 * saving a marked area, server rules switching it off, the singleplayer paste and its undo, and that nothing the mod
 * does ever sends a packet that places, breaks or clicks anything.
 */
public final class SchematicsTest {
    private SchematicsTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static final String TEST_PREFIX = "evade-test-";
    private static final BlockPos O = new BlockPos(20, -60, 20);

    /** Packets that would place, break, use or click something: none may ever be sent while Schematics works. */
    private static final Set<String> FORBIDDEN_SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger PACKETS_SEEN = new AtomicInteger();
    private static final String SNIFFER = "evade_schematics_test_sniffer";

    /** The 4 x 3 x 3 test house (x, y, z). 16 blocks: 12 on the floor (one of them stairs), 3 on layer 1, 1 on layer 2. */
    static Schematic house() {
        BlockState stone = Blocks.STONE.getDefaultState(), air = Blocks.AIR.getDefaultState();
        BlockState stairs = Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST);
        BlockState planks = Blocks.OAK_PLANKS.getDefaultState(), glass = Blocks.GLASS.getDefaultState();
        BlockState slab = Blocks.OAK_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.DOUBLE);
        BlockState[] palette = {null, air, stone, stairs, planks, glass, slab};
        int sx = 4, sy = 3, sz = 3;
        short[] blocks = new short[sx * sy * sz];
        java.util.Arrays.fill(blocks, (short) 1);
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) blocks[(0 * sz + z) * sx + x] = 2;
        blocks[(0 * sz + 2) * sx + 3] = 3;
        blocks[(1 * sz) * sx + 0] = 4;
        blocks[(1 * sz) * sx + 2] = 5;
        blocks[(1 * sz) * sx + 3] = 6;
        blocks[(2 * sz) * sx + 0] = 4;
        return new Schematic("house", "Litematic", "test", sx, sy, sz, palette, blocks, 1, 0);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        parseTests(ctx);

        // The test files, in the real schematics folder (removed at the end).
        Path dir = ctx.computeOnClient(c -> Schematics.folder());
        try {
            Files.createDirectories(dir);
            Schematic h = house();
            Files.write(dir.resolve(TEST_PREFIX + "house.litematic"), SchematicIO.gzip(SchematicIO.litematicTag(h)));
            Files.write(dir.resolve(TEST_PREFIX + "house.schem"), SchematicIO.gzip(SchematicIO.spongeTag(h, 3)));
            Files.write(dir.resolve(TEST_PREFIX + "house.nbt"), SchematicIO.gzip(SchematicIO.structureTag(h)));
            Files.write(dir.resolve(TEST_PREFIX + "broken.litematic"), "not a schematic".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve(TEST_PREFIX + "big.litematic"), SchematicIO.gzip(SchematicIO.litematicTag(checkerboard(64, 12, 64))));
        } catch (Exception e) {
            throw new AssertionError("FAILED: couldn't write the test schematics: " + e);
        }

        server.runCommand("fill 4 -60 4 60 -45 60 air");
        server.runCommand("fill 4 -61 4 60 -61 60 grass_block");
        server.runCommand("tp @a 17 -60 16 -40 35");
        ctx.waitTicks(10);

        installSniffer(ctx);
        try {
            folderAndLoading(ctx);
            ghostAndLayers(ctx);
            verifierAndMaterials(ctx, server);
            rotationAndMirror(ctx);
            saveSelection(ctx, dir);
            screens(ctx);
            serverRules(ctx);
            paste(ctx, sp);
            fpsCost(ctx);
            int seen = PACKETS_SEEN.get();
            check(seen > 0, "Schematics: the packet watcher saw the client's own packets (" + seen + ")");
            check(FORBIDDEN_SEEN.isEmpty(), "Schematics: no packet that places, breaks, uses or clicks anything was ever sent " + FORBIDDEN_SEEN);
        } finally {
            removeSniffer(ctx);
            ctx.runOnClient(c -> {
                c.setScreen(null);
                Schematics m = EvadeClient.modules.get(Schematics.class);
                m.unload();
                for (Setting<?> s : m.getSettings()) s.reset();
                m.setEnabled(false);
                EvadeClient.modules.get(SchematicMaterialsHud.class).setEnabled(false);
            });
            try (var s = Files.list(dir)) {
                for (Path p : s.toList()) if (p.getFileName().toString().startsWith(TEST_PREFIX)) Files.deleteIfExists(p);
            } catch (Exception ignored) {}
            server.runCommand("fill 4 -60 4 60 -45 60 air");
            server.runCommand("clear @a stone 5");
            server.runCommand("tp @a 0 -60 0 0 0");
            ctx.waitTicks(5);
        }
        List<String> crashed = ctx.computeOnClient(c -> ModuleManager.CRASHED.stream().filter(e -> e.startsWith("Schematic")).toList());
        check(crashed.isEmpty(), "Schematics: never crashed " + crashed);
    }

    // ------------------------------------------------------------------ files

    static Schematic checkerboard(int sx, int sy, int sz) {
        BlockState[] palette = {null, Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState(), Blocks.OAK_PLANKS.getDefaultState()};
        short[] b = new short[sx * sy * sz];
        int i = 0;
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++)
            b[i++] = (short) ((x + y + z) % 2 == 0 ? (y % 3 == 0 ? 3 : 2) : 1);
        return new Schematic("big", "Litematic", "test", sx, sy, sz, palette, b, 1, 0);
    }

    /** 10 x 10 x 10 of 37 different states at random: every packed value width up to 6 bits crosses long boundaries. */
    static Schematic random() {
        List<BlockState> states = new ArrayList<>();
        states.add(Blocks.AIR.getDefaultState());
        for (var id : Registries.BLOCK.getIds()) {
            String p = id.getPath();
            if (p.endsWith("_wool") || p.endsWith("_concrete")) states.add(Registries.BLOCK.get(id).getDefaultState());
        }
        states.add(Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST));
        states.add(Blocks.OAK_LOG.getDefaultState().with(Properties.AXIS, Direction.Axis.X));
        states.add(Blocks.REDSTONE_WIRE.getDefaultState());
        states.add(Blocks.GLASS.getDefaultState());
        states.add(Blocks.CHEST.getDefaultState());
        BlockState[] palette = new BlockState[states.size() + 1];
        for (int i = 0; i < states.size(); i++) palette[i + 1] = states.get(i);
        Random r = new Random(42);
        short[] b = new short[1000];
        for (int i = 0; i < b.length; i++) b[i] = (short) (1 + r.nextInt(states.size()));
        return new Schematic("random", "Litematic", "test", 10, 10, 10, palette, b, 1, 0);
    }

    private static boolean same(Schematic a, Schematic b) {
        if (a.sizeX != b.sizeX || a.sizeY != b.sizeY || a.sizeZ != b.sizeZ) return false;
        for (int y = 0; y < a.sizeY; y++) for (int z = 0; z < a.sizeZ; z++) for (int x = 0; x < a.sizeX; x++) {
            BlockState sa = a.stateAt(x, y, z), sb = b.stateAt(x, y, z);
            if (sa == null ? sb != null : sa != sb) return false;
        }
        return true;
    }

    /** Throws BadFile (and nothing else) for {@code bytes}. */
    private static boolean refused(byte[] bytes, String name) {
        try {
            SchematicIO.read(bytes, name);
            return false;
        } catch (SchematicIO.BadFile e) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void parseTests(ClientGameTestContext ctx) {
        Map<String, Boolean> r = ctx.computeOnClient(c -> {
            Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            try {
                // Bit packing: values running across long boundaries, every width from 1 to 13 bits.
                boolean packOk = true;
                Random rnd = new Random(7);
                for (int bits = 1; bits <= 13 && packOk; bits++) {
                    int[] v = new int[517];
                    for (int i = 0; i < v.length; i++) v[i] = rnd.nextInt(1 << bits);
                    long[] packed = SchematicIO.pack(v, bits);
                    packOk = packed.length == (v.length * bits + 63) / 64;
                    for (int i = 0; i < v.length && packOk; i++) packOk = SchematicIO.unpack(packed, i, bits, (1L << bits) - 1) == v[i];
                }
                out.put("tightly packed values round-trip for 1-13 bits, across long boundaries", packOk);

                Schematic h = house(), rand = random();
                out.put(".litematic round-trips (house)", same(h, SchematicIO.read(SchematicIO.gzip(SchematicIO.litematicTag(h)), "h.litematic")));
                out.put(".litematic round-trips (37 states, 6-bit values)", same(rand, SchematicIO.read(SchematicIO.gzip(SchematicIO.litematicTag(rand)), "r.litematic")));
                out.put("structure .nbt round-trips", same(rand, SchematicIO.read(SchematicIO.gzip(SchematicIO.structureTag(rand)), "r.nbt")));
                Schematic v2 = SchematicIO.read(SchematicIO.gzip(SchematicIO.spongeTag(rand, 2)), "r.schem");
                Schematic v3 = SchematicIO.read(SchematicIO.gzip(SchematicIO.spongeTag(rand, 3)), "r.schem");
                out.put("Sponge v2 .schem reads", same(rand, v2) && v2.format.equals("Sponge v2"));
                out.put("Sponge v3 .schem reads", same(rand, v3) && v3.format.equals("Sponge v3"));
                // Uncompressed NBT is accepted too.
                java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompound(SchematicIO.structureTag(h), new java.io.DataOutputStream(raw));
                out.put("uncompressed .nbt reads", same(h, SchematicIO.read(raw.toByteArray(), "h.nbt")));

                // A hand-made two-region litematic, one region with a negative size.
                NbtCompound root = new NbtCompound();
                NbtCompound regions = new NbtCompound();
                regions.put("A", region(0, 0, 0, 2, 1, 1, new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1, 1}));
                regions.put("B", region(3, 0, 0, -2, 1, 1, new String[]{"minecraft:air", "minecraft:glass"}, new int[]{1, 0}));
                root.put("Regions", regions);
                root.putInt("Version", 6);
                Schematic multi = SchematicIO.read(SchematicIO.gzip(root), "multi.litematic");
                out.put("multi-region litematic (negative size) lands where it should", multi.sizeX == 4 && multi.regions == 2
                        && multi.stateAt(0, 0, 0) == Blocks.STONE.getDefaultState() && multi.stateAt(1, 0, 0) == Blocks.STONE.getDefaultState()
                        && multi.stateAt(2, 0, 0) == Blocks.GLASS.getDefaultState() && multi.stateAt(3, 0, 0).isAir());

                // Bad files are refused with a reason, never an exception that could reach the game.
                byte[] good = SchematicIO.gzip(SchematicIO.litematicTag(h));
                out.put("bad: text instead of NBT", refused("hello, world".getBytes(StandardCharsets.UTF_8), "x.litematic"));
                out.put("bad: empty file", refused(new byte[0], "x.schem"));
                out.put("bad: cut-off gzip", refused(java.util.Arrays.copyOf(good, good.length / 2), "x.litematic"));
                NbtCompound huge = new NbtCompound(), hr = new NbtCompound();
                hr.put("A", region(0, 0, 0, 5000, 1, 1, new String[]{"minecraft:air"}, new int[0]));
                huge.put("Regions", hr);
                out.put("bad: region too big", refused(SchematicIO.gzip(huge), "x.litematic"));
                NbtCompound shortData = new NbtCompound(), sr = new NbtCompound();
                NbtCompound reg = region(0, 0, 0, 40, 40, 40, new String[]{"minecraft:air", "minecraft:stone"}, new int[]{1});
                sr.put("A", reg);
                shortData.put("Regions", sr);
                out.put("bad: block data cut short", refused(SchematicIO.gzip(shortData), "x.litematic"));
                NbtCompound sponge = new NbtCompound();
                sponge.putShort("Width", (short) 3); sponge.putShort("Height", (short) 1); sponge.putShort("Length", (short) 1);
                NbtCompound pal = new NbtCompound(); pal.putInt("minecraft:stone", 0);
                sponge.put("Palette", pal);
                sponge.putByteArray("BlockData", new byte[]{(byte) 0x80, (byte) 0x80});
                out.put("bad: damaged varints", refused(SchematicIO.gzip(sponge), "x.schem"));
                NbtCompound sponge3 = SchematicIO.spongeTag(h, 3);
                sponge3.getCompoundOrEmpty("Schematic").putShort("Width", (short) 9000);
                out.put("bad: Sponge size over the cap", refused(SchematicIO.gzip(sponge3), "x.schem"));
                // Recoverable oddities load: an unknown block name and an out-of-range structure state.
                NbtCompound unk = new NbtCompound();
                unk.putShort("Width", (short) 1); unk.putShort("Height", (short) 1); unk.putShort("Length", (short) 2);
                NbtCompound upal = new NbtCompound(); upal.putInt("minecraft:not_a_real_block", 0); upal.putInt("minecraft:stone", 1);
                unk.put("Palette", upal);
                unk.putByteArray("BlockData", new byte[]{0, 1});
                Schematic u = SchematicIO.read(SchematicIO.gzip(unk), "u.schem");
                out.put("unknown block names load as air and are counted", u.unknownStates == 1 && u.stateAt(0, 0, 1) == Blocks.STONE.getDefaultState());
                NbtCompound st = SchematicIO.structureTag(h);
                st.getListOrEmpty("blocks").getCompoundOrEmpty(0).putInt("state", 999);
                Schematic sOdd = SchematicIO.read(SchematicIO.gzip(st), "o.nbt");
                out.put("a structure block with a bad palette index is skipped", sOdd.stateAt(0, 0, 0) == null && sOdd.solidBlocks() == h.solidBlocks() - 1);
            } catch (Throwable t) {
                out.put("parse tests ran without an exception (" + t + ")", false);
            }
            return out;
        });
        for (var e : r.entrySet()) check(e.getValue(), "Schematics files: " + e.getKey());
    }

    private static NbtCompound region(int px, int py, int pz, int sx, int sy, int sz, String[] palette, int[] values) {
        NbtCompound r = new NbtCompound();
        NbtCompound pos = new NbtCompound(); pos.putInt("x", px); pos.putInt("y", py); pos.putInt("z", pz);
        NbtCompound size = new NbtCompound(); size.putInt("x", sx); size.putInt("y", sy); size.putInt("z", sz);
        r.put("Position", pos);
        r.put("Size", size);
        NbtList pal = new NbtList();
        for (String n : palette) { NbtCompound e = new NbtCompound(); e.putString("Name", n); pal.add(e); }
        r.put("BlockStatePalette", pal);
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        r.putLongArray("BlockStates", SchematicIO.pack(values, bits));
        return r;
    }

    // ------------------------------------------------------------------ in the world

    private static Schematics mod() { return EvadeClient.modules.get(Schematics.class); }

    @SuppressWarnings("unchecked")
    private static <S extends Setting<?>> S setting(String name) {
        for (Setting<?> s : mod().getSettings()) if (s.getName().equals(name)) return (S) s;
        throw new AssertionError("no setting " + name);
    }

    private static boolean waitLoaded(ClientGameTestContext ctx, String file) {
        for (int i = 0; i < 100; i++) {
            ctx.waitTick();
            boolean done = ctx.computeOnClient(c -> !mod().loading() && mod().placement() != null && mod().placement().file.equals(file));
            if (done) return true;
        }
        return false;
    }

    private static void folderAndLoading(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> mod().setEnabled(true));
        ctx.waitTicks(2);
        List<String> files = ctx.computeOnClient(c -> Schematics.files());
        check(files.contains(TEST_PREFIX + "house.litematic") && files.contains(TEST_PREFIX + "house.schem") && files.contains(TEST_PREFIX + "house.nbt"),
                "Schematics: the schematics folder lists .litematic, .schem and .nbt files");

        for (String f : List.of(TEST_PREFIX + "house.schem", TEST_PREFIX + "house.nbt", TEST_PREFIX + "house.litematic")) {
            ctx.runOnClient(c -> mod().load(f));
            boolean ok = waitLoaded(ctx, f);
            String info = ctx.computeOnClient(c -> {
                Placement p = mod().placement();
                if (p == null) return "none";
                // A placement saved by an earlier run may be turned: start square.
                mod().setMirror(Placement.MIRROR_NONE);
                p.setRotation(0);
                return p.schematic.format + " " + p.schematic.sizeX + "x" + p.schematic.sizeY + "x" + p.schematic.sizeZ + " " + p.schematic.solidBlocks();
            });
            check(ok && info.endsWith("4x3x3 16"), "Schematics: loads " + f + " from the folder (" + info + ")");
        }
        // A broken file: a message, the loaded schematic stays, nothing crashes.
        ctx.runOnClient(c -> mod().load(TEST_PREFIX + "broken.litematic"));
        ctx.waitTicks(20);
        boolean kept = ctx.computeOnClient(c -> !mod().loading() && mod().placement() != null && mod().placement().file.equals(TEST_PREFIX + "house.litematic"));
        check(kept, "Schematics: a broken file is refused and the loaded schematic stays");
        boolean outside = ctx.computeOnClient(c -> { mod().load("../options.txt"); return !mod().loading(); });
        check(outside, "Schematics: names that point outside the folder are refused");
        ctx.runOnClient(c -> mod().moveTo(O));
    }

    private static void ghostAndLayers(ClientGameTestContext ctx) {
        int[] g = ctx.computeOnClient(c -> {
            var r = mod().verifyNow();
            return new int[]{r.mesh().blocks(), r.mesh().quads(), r.count(SchematicVerifier.MISSING)};
        });
        check(g[0] == 16 && g[1] > 0 && g[2] == 16, "Schematics: all 16 blocks are ghosts on empty ground (" + g[0] + " blocks, " + g[1] + " faces)");
        ctx.waitTicks(5);
        ctx.takeScreenshot("schematics-ghost");
        boolean noCrash = ctx.computeOnClient(c -> mod().isEnabled() && ModuleManager.CRASHED.stream().noneMatch(e -> e.startsWith("Schematics")));
        check(noCrash, "Schematics: ghost blocks draw without errors");

        int[][] spans = ctx.computeOnClient(c -> {
            ModeSetting layers = setting("Layers");
            NumberSetting layer = setting("Layer");
            int[][] out = new int[3][];
            layers.set("Single"); layer.set(1.0);
            out[0] = mod().verifyNow().mesh().layerSpan();
            layers.set("Below"); layer.set(0.0);
            out[1] = mod().verifyNow().mesh().layerSpan();
            layers.set("Above"); layer.set(2.0);
            out[2] = mod().verifyNow().mesh().layerSpan();
            layers.set("All");
            return out;
        });
        check(spans[0] != null && spans[0][0] == 1 && spans[0][1] == 1, "Schematics: layer view Single shows just that layer");
        check(spans[1] != null && spans[1][0] == 0 && spans[1][1] == 0, "Schematics: layer view Below shows the layers up to it");
        check(spans[2] != null && spans[2][0] == 2 && spans[2][1] == 2, "Schematics: layer view Above shows the layers from it up");
        int quads = ctx.computeOnClient(c -> {
            ((BooleanSetting) setting("Ghost Blocks")).set(false);
            int q = mod().verifyNow().mesh().quads();
            ((BooleanSetting) setting("Ghost Blocks")).set(true);
            return q;
        });
        check(quads == 0, "Schematics: Ghost Blocks off (verifier off) draws nothing");
    }

    private static void verifierAndMaterials(ClientGameTestContext ctx, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server) {
        server.runCommand("setblock 20 -60 20 stone");                           // correct
        server.runCommand("setblock 21 -60 20 dirt");                            // wrong block
        server.runCommand("setblock 23 -60 22 oak_stairs[facing=west]");         // right block, wrong way
        server.runCommand("setblock 21 -59 20 cobblestone");                     // extra: the schematic wants air
        int stoneBefore = ctx.computeOnClient(c -> net.evadeclient.util.InvUtil.count(st -> st.isOf(Items.STONE)));
        server.runCommand("give @a stone 5");
        ctx.waitTicks(10);
        int carried = stoneBefore + 5, toGet = Math.max(0, 10 - carried);
        int[] v = ctx.computeOnClient(c -> {
            ((BooleanSetting) setting("Verifier")).set(true);
            var r = mod().verifyNow();
            return new int[]{r.count(SchematicVerifier.CORRECT), r.count(SchematicVerifier.MISSING), r.count(SchematicVerifier.WRONG),
                    r.count(SchematicVerifier.WRONG_STATE), r.count(SchematicVerifier.EXTRA), r.count(SchematicVerifier.UNLOADED)};
        });
        check(v[0] == 1 && v[1] == 13 && v[2] == 1 && v[3] == 1 && v[4] == 1 && v[5] == 0,
                "Schematics: verifier counts correct 1, missing 13, wrong 1, wrong state 1, extra 1 (" + java.util.Arrays.toString(v) + ")");
        ctx.waitTicks(3);
        ctx.takeScreenshot("schematics-verifier");

        Map<Item, int[]> m = ctx.computeOnClient(c -> {
            Map<Item, int[]> out = new java.util.HashMap<>();
            for (Materials.Entry e : mod().materials()) out.put(e.item(), new int[]{e.needed(), e.placed(), e.left(), e.inventory(), e.missing()});
            return out;
        });
        check(m.size() == 5, "Schematics: material list has 5 items (" + m.size() + ")");
        int[] stone = m.get(Items.STONE), stairs = m.get(Items.OAK_STAIRS), slab = m.get(Items.OAK_SLAB), planks = m.get(Items.OAK_PLANKS);
        check(stone != null && stone[0] == 11 && stone[1] == 1 && stone[2] == 10 && stone[3] == carried && stone[4] == toGet,
                "Schematics: stone needed 11, placed 1, left 10, carried " + carried + ", still to get " + toGet);
        check(stairs != null && stairs[0] == 1 && stairs[1] == 1 && stairs[2] == 0, "Schematics: placed stairs count as placed even facing the wrong way");
        check(slab != null && slab[0] == 2, "Schematics: a double slab needs two slabs");
        check(planks != null && planks[0] == 2 && planks[1] == 0, "Schematics: planks needed 2");
        String text = ctx.computeOnClient(c -> Materials.text("house", mod().materials()));
        check(text.contains("Stone | 11 | 1 | 10 | " + carried + " | " + toGet), "Schematics: the copied list has one plain line per item");

        List<String> hud = ctx.computeOnClient(c -> {
            var h = EvadeClient.modules.get(SchematicMaterialsHud.class);
            h.setEnabled(true);
            return h.textForTest();
        });
        check(!hud.isEmpty() && hud.get(0).equals("Materials left: 15"), "Schematics: the HUD line shows materials left " + hud);
    }

    private static void rotationAndMirror(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            Schematics s = mod();
            Placement p = s.placement();
            StringBuilder out = new StringBuilder();
            s.rotate(true);
            BlockState st = p.stateAtWorld(O.getX(), O.getY(), O.getZ() + 3);
            out.append(p.sizeX() == 3 && p.sizeZ() == 4).append(',')
                    .append(st != null && st.isOf(Blocks.OAK_STAIRS) && st.get(StairsBlock.FACING) == Direction.SOUTH).append(',');
            s.rotate(true); s.rotate(true); s.rotate(true);
            out.append(p.rotation() == 0 && p.stateAtWorld(O.getX() + 3, O.getY(), O.getZ() + 2).get(StairsBlock.FACING) == Direction.EAST).append(',');
            s.setMirror(Placement.MIRROR_X);
            BlockState m = p.stateAtWorld(O.getX(), O.getY(), O.getZ() + 2);
            out.append(m != null && m.isOf(Blocks.OAK_STAIRS) && m.get(StairsBlock.FACING) == Direction.WEST).append(',');
            s.setMirror(Placement.MIRROR_Z);
            BlockState mz = p.stateAtWorld(O.getX() + 3, O.getY(), O.getZ());
            out.append(mz != null && mz.isOf(Blocks.OAK_STAIRS) && mz.get(StairsBlock.FACING) == Direction.EAST).append(',');
            // Every position maps back: placed -> local -> placed for each turn and mirror.
            boolean inverse = true;
            int[] a = new int[2], b = new int[2];
            for (int mir = 0; mir < 3; mir++) for (int rot = 0; rot < 4; rot++) {
                s.setMirror(mir);
                p.setRotation(rot);
                for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) {
                    p.placedXZ(x, z, a);
                    p.localXZ(a[0], a[1], b);
                    inverse &= b[0] == x && b[1] == z && a[0] >= 0 && a[1] >= 0 && a[0] < p.sizeX() && a[1] < p.sizeZ();
                }
            }
            out.append(inverse).append(',');
            s.setMirror(Placement.MIRROR_NONE);
            s.rotate(true); s.rotate(false);
            p.setRotation(0);
            // Nudge moves the origin and the ghosts follow.
            s.nudge(1, 0, 0);
            out.append(p.origin().equals(O.add(1, 0, 0)));
            s.nudge(-1, 0, 0);
            s.verifyNow();
            return out.toString();
        });
        String[] parts = r.split(",");
        check(parts[0].equals("true") && parts[1].equals("true"), "Schematics: a quarter turn swaps the size and turns the stairs (east to south)");
        check(parts[2].equals("true"), "Schematics: four quarter turns come back to the start");
        check(parts[3].equals("true"), "Schematics: mirror front-back flips X and the stairs (east to west)");
        check(parts[4].equals("true"), "Schematics: mirror left-right flips Z");
        check(parts[5].equals("true"), "Schematics: every turn and mirror maps each position inside the box, and back");
        check(parts[6].equals("true"), "Schematics: nudging moves the placement");
    }

    private static void saveSelection(ClientGameTestContext ctx, Path dir) {
        String saved = ctx.computeOnClient(c -> {
            Schematics s = mod();
            s.setCorner(1, new BlockPos(20, -61, 20));
            s.setCorner(2, new BlockPos(23, -59, 22));
            String a = s.saveSelection(TEST_PREFIX + "saved", false);
            String b = s.saveSelection(TEST_PREFIX + "saved", true);
            try {
                s.setCorner(2, new BlockPos(20, -61, 20 + 16 * 40));
                s.saveSelection(TEST_PREFIX + "far", false);
                return a + "|" + b + "|saved-unloaded";
            } catch (net.evadeclient.command.CommandManager.CommandError e) {
                s.setCorner(2, new BlockPos(23, -59, 22));
                return a + "|" + b + "|refused";
            }
        });
        String[] f = saved.split("\\|");
        check(f[2].equals("refused"), "Schematics: saving an area that isn't loaded is refused");
        try {
            Schematic lit = SchematicIO.read(dir.resolve(f[0]));
            Schematic nbt = SchematicIO.read(dir.resolve(f[1]));
            String world = ctx.computeOnClient(c -> {
                StringBuilder ok = new StringBuilder();
                boolean match = true;
                for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 4; x++)
                    match &= c.world.getBlockState(new BlockPos(20 + x, -61 + y, 20 + z)) == lit.stateAt(x, y, z)
                            && lit.stateAt(x, y, z) == nbt.stateAt(x, y, z);
                return ok.append(match).toString();
            });
            check(lit.sizeX == 4 && lit.sizeY == 3 && lit.sizeZ == 3 && world.equals("true"),
                    "Schematics: a marked area saves as .litematic and .nbt with the blocks you see (" + f[0] + ", " + f[1] + ")");
        } catch (Exception e) {
            throw new AssertionError("FAILED: Schematics: saved files read back: " + e);
        }
    }

    private static void screens(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            c.player.setYaw(-40);
            c.player.setPitch(35);
            MaterialListScreen.open(null);
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("schematics-material-list");
        int rows = ctx.computeOnClient(c -> c.currentScreen instanceof MaterialListScreen s ? s.rows().size() : -1);
        check(rows == 5, "Schematics: the material list screen shows the 5 items (" + rows + ")");
        String clip = ctx.computeOnClient(c -> {
            ((MaterialListScreen) c.currentScreen).copy();
            return c.keyboard.getClipboard();
        });
        check(clip.startsWith("Materials for house"), "Schematics: Copy puts the list on the clipboard");
        ctx.runOnClient(c -> SchematicBrowserScreen.open(null));
        ctx.waitTicks(10);
        ctx.takeScreenshot("schematics-browser");
        boolean browser = ctx.computeOnClient(c -> c.currentScreen instanceof SchematicBrowserScreen);
        check(browser, "Schematics: the schematic browser opens");
        ctx.runOnClient(c -> c.setScreen(null));
        ctx.waitTicks(2);
    }

    private static void serverRules(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            Schematics s = mod();
            StringBuilder out = new StringBuilder();
            ServerRules.set(Set.of("schematics"));
            out.append(!s.isEnabled()).append(',');
            s.setEnabled(true);
            out.append(!s.isEnabled()).append(',');
            boolean refused;
            try { s.command(new String[]{"schem", "verify"}); refused = false; }
            catch (net.evadeclient.command.CommandManager.CommandError e) { refused = true; }
            out.append(refused).append(',');
            out.append(EvadeClient.modules.get(SchematicMaterialsHud.class).textForTest().isEmpty()).append(',');
            ServerRules.set(Set.of());
            out.append(s.isEnabled());
            return out.toString();
        });
        String[] p = r.split(",");
        check(p[0].equals("true"), "Schematics: a server rule switches Schematics off");
        check(p[1].equals("true"), "Schematics: it can't be switched back on while the rule stands");
        check(p[2].equals("true"), "Schematics: .schem commands are refused while the server forbids it");
        check(p[3].equals("true"), "Schematics: the materials HUD line hides while the server forbids it");
        check(p[4].equals("true"), "Schematics: it comes back on when the rule lifts");
        // Coming back on re-reads this world's saved placement.
        boolean back = false;
        for (int i = 0; i < 60 && !back; i++) {
            ctx.waitTick();
            back = ctx.computeOnClient(c -> mod().placement() != null && !mod().loading());
        }
        check(back, "Schematics: the placement saved for this world comes back");
        ctx.runOnClient(c -> mod().moveTo(O));
    }

    private static void paste(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        BlockPos at = new BlockPos(40, -60, 40);
        boolean cheats = sp.getServer().computeOnServer(s -> s.getPlayerManager().areCheatsAllowed());
        if (!cheats) {
            String[] msg = new String[1];
            ctx.runOnClient(c -> { mod().moveTo(at); SingleplayerPaste.paste(mod().placement(), true, m -> msg[0] = m); });
            ctx.waitTicks(5);
            String m = ctx.computeOnClient(c -> msg[0]);
            boolean untouched = ctx.computeOnClient(c -> c.world.getBlockState(at).isAir());
            check(m != null && m.startsWith("Paste needs cheats") && untouched, "Schematics: paste is refused without cheats (" + m + ")");
            sp.getServer().runOnServer(s -> s.getPlayerManager().setCheatsAllowed(true));
        }
        try {
            String[] msg = new String[1];
            ctx.runOnClient(c -> { mod().moveTo(at); SingleplayerPaste.paste(mod().placement(), true, m -> msg[0] = m); });
            for (int i = 0; i < 40 && ctx.computeOnClient(c -> msg[0] == null); i++) ctx.waitTick();
            ctx.waitTicks(5);
            int[] v = ctx.computeOnClient(c -> {
                var r = mod().verifyNow();
                return new int[]{r.count(SchematicVerifier.CORRECT), r.count(SchematicVerifier.MISSING) + r.count(SchematicVerifier.WRONG) + r.count(SchematicVerifier.WRONG_STATE)};
            });
            check(v[0] == 16 && v[1] == 0, "Schematics: paste in singleplayer builds it (" + ctx.computeOnClient(c -> msg[0]) + "; correct " + v[0] + ")");
            ctx.takeScreenshot("schematics-pasted");
            msg[0] = null;
            ctx.runOnClient(c -> SingleplayerPaste.undo(m -> msg[0] = m));
            for (int i = 0; i < 40 && ctx.computeOnClient(c -> msg[0] == null); i++) ctx.waitTick();
            ctx.waitTicks(5);
            int correct = ctx.computeOnClient(c -> mod().verifyNow().count(SchematicVerifier.CORRECT));
            check(correct == 0, "Schematics: undo puts back what the paste replaced (" + ctx.computeOnClient(c -> msg[0]) + ")");
        } finally {
            if (!cheats) sp.getServer().runOnServer(s -> s.getPlayerManager().setCheatsAllowed(false));
        }
        String mp = ctx.computeOnClient(c -> SingleplayerPaste.unavailableReason());
        check(mp == null, "Schematics: paste is offered in singleplayer (and checks for the built-in server)");
        ctx.runOnClient(c -> mod().moveTo(O));
    }

    private static void fpsCost(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> { mod().unload(); Benchmark.setQuiet(true); });
        ctx.waitTicks(10);
        ctx.runOnClient(c -> Benchmark.start(4));
        ctx.waitTicks(20 * 5 + 10);
        Benchmark.Result off = ctx.computeOnClient(c -> Benchmark.last());
        ctx.runOnClient(c -> mod().load(TEST_PREFIX + "big.litematic"));
        boolean loaded = waitLoaded(ctx, TEST_PREFIX + "big.litematic");
        int[] mesh = ctx.computeOnClient(c -> {
            mod().moveTo(new BlockPos(c.player.getBlockX() - 32, -60, c.player.getBlockZ() - 32));
            var r = mod().verifyNow();
            return new int[]{r.mesh().blocks(), r.mesh().quads()};
        });
        ctx.waitTicks(20);
        ctx.takeScreenshot("schematics-big-ghost");
        ctx.runOnClient(c -> Benchmark.start(4));
        ctx.waitTicks(20 * 5 + 10);
        Benchmark.Result on = ctx.computeOnClient(c -> Benchmark.last());
        ctx.runOnClient(c -> Benchmark.setQuiet(false));
        if (off != null && on != null) EvadeClient.LOG.info("[gametest] SCHEMFPS off {} FPS (1% low {}), on {} FPS (1% low {}) with {} ghost blocks / {} faces",
                String.format(Locale.ROOT, "%.0f", off.avgFps()), String.format(Locale.ROOT, "%.0f", off.low1()),
                String.format(Locale.ROOT, "%.0f", on.avgFps()), String.format(Locale.ROOT, "%.0f", on.low1()), mesh[0], mesh[1]);
        check(loaded && mesh[0] == 15000, "Schematics: a 24,576-block checkerboard is capped at Max Ghost Blocks (" + mesh[0] + " blocks, " + mesh[1] + " faces)");
        check(on != null && on.avgFps() > 0, "Schematics: the benchmark runs with a big ghost on screen");
    }

    // ------------------------------------------------------------------ packet watcher

    private static Channel channel(ClientConnection conn) throws IllegalAccessException {
        for (Class<?> k = conn.getClass(); k != null; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields())
                if (Channel.class.isAssignableFrom(f.getType())) { f.setAccessible(true); return (Channel) f.get(conn); }
        return null;
    }

    private static boolean forbidden(Object msg) {
        return msg instanceof PlayerInteractBlockC2SPacket || msg instanceof PlayerInteractItemC2SPacket
                || msg instanceof PlayerActionC2SPacket || msg instanceof CreativeInventoryActionC2SPacket
                || msg instanceof ClickSlotC2SPacket || msg instanceof PickItemFromBlockC2SPacket
                || msg instanceof UpdateSelectedSlotC2SPacket || msg instanceof HandSwingC2SPacket;
    }

    private static void installSniffer(ClientGameTestContext ctx) {
        FORBIDDEN_SEEN.clear();
        PACKETS_SEEN.set(0);
        boolean ok = ctx.computeOnClient(c -> {
            try {
                Channel ch = channel(c.getNetworkHandler().getConnection());
                if (ch == null) return false;
                if (ch.pipeline().get(SNIFFER) != null) ch.pipeline().remove(SNIFFER);
                ch.pipeline().addLast(SNIFFER, new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext hc, Object msg, ChannelPromise promise) throws Exception {
                        PACKETS_SEEN.incrementAndGet();
                        if (forbidden(msg)) FORBIDDEN_SEEN.add(msg.getClass().getSimpleName());
                        super.write(hc, msg, promise);
                    }
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        check(ok, "Schematics: packet watcher installed on the client connection");
    }

    private static void removeSniffer(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            try {
                Channel ch = channel(c.getNetworkHandler().getConnection());
                if (ch != null && ch.pipeline().get(SNIFFER) != null) ch.pipeline().remove(SNIFFER);
            } catch (Exception ignored) {}
        });
    }
}
