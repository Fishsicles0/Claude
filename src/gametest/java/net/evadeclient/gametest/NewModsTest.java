package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.combat.AutoArmor;
import net.evadeclient.module.combat.KillAura;
import net.evadeclient.module.combat.Surround;
import net.evadeclient.module.combat.Velocity;
import net.evadeclient.module.movement.Blink;
import net.evadeclient.module.movement.Fly;
import net.evadeclient.module.movement.Jesus;
import net.evadeclient.module.movement.NoFall;
import net.evadeclient.module.movement.SafeWalk;
import net.evadeclient.module.movement.Scaffold;
import net.evadeclient.module.movement.Step;
import net.evadeclient.module.movement.Timer;
import net.evadeclient.module.player.AutoEat;
import net.evadeclient.module.player.AutoRespawn;
import net.evadeclient.module.player.AutoTool;
import net.evadeclient.module.player.ChestStealer;
import net.evadeclient.module.visuals.Freecam;
import net.evadeclient.module.world.AutoFarm;
import net.evadeclient.module.world.Nuker;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** In-game checks for the mods added in v0.4.0. */
final class NewModsTest {
    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext sp;

    NewModsTest(ClientGameTestContext ctx, TestSingleplayerContext sp) { this.ctx = ctx; this.sp = sp; }

    void run() throws java.io.IOException {
        reset();
        killAura();
        velocity();
        fly();
        step();
        noFall();
        jesus();
        safeWalk();
        scaffold();
        surround();
        autoArmor();
        blink();
        nuker();
        autoFarm();
        chestStealer();
        autoEat();
        autoTool();
        autoRespawn();
        freecam();
        timer();
        printer();
    }

    // ------------------------------------------------------------------ helpers

    private void cmd(String c) { sp.getServer().runCommand(c); }

    private ServerPlayerEntity sp(net.minecraft.server.MinecraftServer s) { return s.getPlayerManager().getPlayerList().getFirst(); }

    private void reset() {
        cmd("kill @e[type=!player]");
        cmd("clear @a");
        cmd("effect clear @a");
        cmd("gamemode survival @a");
        cmd("fill -8 -61 -8 8 -55 8 air");
        cmd("fill -8 -61 -8 8 -61 8 grass_block");
        cmd("tp @a 0.5 -60 0.5 0 0");
        sp.getServer().runOnServer(s -> { sp(s).setHealth(20f); sp(s).getHungerManager().setFoodLevel(20); });
        ctx.waitTicks(10);
    }

    private <T extends Module> T on(Class<T> type) {
        return ctx.computeOnClient(c -> { T m = EvadeClient.modules.get(type); m.setEnabled(true); return m; });
    }

    private void off(Class<? extends Module> type) { ctx.runOnClient(c -> EvadeClient.modules.get(type).setEnabled(false)); }

    private void bool(Module m, String name, boolean v) {
        ctx.runOnClient(c -> { for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((BooleanSetting) s).set(v); });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private <T> T client(java.util.function.Function<MinecraftClient, T> f) { return ctx.computeOnClient(f::apply); }

    // ------------------------------------------------------------------ combat

    private void killAura() {
        reset();
        cmd("item replace entity @a weapon.mainhand with netherite_sword");
        cmd("summon zombie 2 -60 0.5 {NoAI:1b,Health:20f}");
        ctx.waitTicks(10);
        KillAura ka = on(KillAura.class);
        bool(ka, "Mobs", true);
        ctx.waitTicks(60);
        off(KillAura.class);
        int alive = client(c -> c.world.getEntitiesByClass(net.minecraft.entity.mob.ZombieEntity.class, c.player.getBoundingBox().expand(8), z -> z.isAlive()).size());
        check(alive == 0, "KillAura kills a mob beside you without aiming at it");
    }

    private void velocity() {
        reset();
        on(Velocity.class); // defaults: 0% horizontal, 0% vertical
        knock(new Vec3d(1.5, 1.2, 0));
        ctx.waitTicks(3);
        double y = client(c -> c.player.getY());
        off(Velocity.class);
        check(y < -59.5, "Velocity cancels knockback sent by the server (y stayed at " + String.format("%.2f", y) + ")");
        // control: without Velocity the same push launches you
        knock(new Vec3d(0, 1.2, 0));
        ctx.waitTicks(4);
        double y2 = client(c -> c.player.getY());
        check(y2 > -59, "Without Velocity the same knockback launches you (y " + String.format("%.2f", y2) + ")");
        ctx.waitTicks(40);
    }

    /** Server sends the player a knockback packet, exactly like a hit would. */
    private void knock(Vec3d v) {
        sp.getServer().runOnServer(s -> sp(s).networkHandler.sendPacket(
                new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(sp(s).getId(), v)));
    }

    // ------------------------------------------------------------------ movement

    private void fly() {
        reset();
        on(Fly.class);
        ctx.waitTicks(3);
        boolean flying = client(c -> c.player.getAbilities().flying);
        off(Fly.class);
        ctx.waitTicks(3);
        boolean after = client(c -> c.player.getAbilities().flying);
        check(flying && !after, "Fly lets you fly in survival and stops when turned off");
    }

    private void step() {
        reset();
        on(Step.class);
        ctx.waitTicks(2);
        double h = client(c -> c.player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.STEP_HEIGHT));
        off(Step.class);
        check(h >= 1.0, "Step raises step height (" + h + ")");
    }

    private void noFall() {
        reset();
        on(NoFall.class);
        cmd("tp @a 0.5 -40 0.5");
        ctx.waitTicks(80);
        float hp = client(c -> c.player.getHealth());
        off(NoFall.class);
        check(hp >= 20f, "NoFall: a 20-block fall does no damage (health " + hp + ")");
        reset();
        cmd("tp @a 0.5 -40 0.5");
        ctx.waitTicks(80);
        float hp2 = client(c -> c.player.getHealth());
        check(hp2 < 20f, "Without NoFall the same fall hurts (health " + hp2 + ")");
    }

    private void jesus() {
        reset();
        cmd("fill -3 -62 -3 3 -61 3 water");
        cmd("tp @a 0.5 -60 0.5");
        on(Jesus.class);
        ctx.waitTicks(30);
        double y = client(c -> c.player.getY());
        boolean wet = client(c -> c.player.isSubmergedInWater());
        off(Jesus.class);
        check(y > -61.2 && !wet, "Jesus keeps you standing on water (y " + String.format("%.2f", y) + ")");
        cmd("fill -3 -62 -3 3 -61 3 grass_block");
    }

    private void safeWalk() {
        reset();
        cmd("fill -8 -61 -8 8 -61 8 air");
        cmd("fill -1 -61 -1 1 -61 1 stone");
        cmd("tp @a 0.5 -60 0.5 0 0");
        ctx.waitTicks(5);
        on(SafeWalk.class);
        ctx.getInput().holdKeyFor(o -> o.forwardKey, 40);
        double y = client(c -> c.player.getY());
        off(SafeWalk.class);
        check(y > -60.5, "SafeWalk stops you walking off an edge (y " + String.format("%.2f", y) + ")");
    }

    private void scaffold() {
        reset();
        cmd("fill -8 -61 -8 8 -61 8 air");
        cmd("setblock 0 -61 0 stone");
        cmd("item replace entity @a hotbar.0 with cobblestone 64");
        cmd("tp @a 0.5 -60 0.5 0 0");
        ctx.waitTicks(5);
        on(Scaffold.class);
        ctx.getInput().holdKeyFor(o -> o.forwardKey, 30);
        off(Scaffold.class);
        int placed = client(c -> {
            int n = 0;
            for (int z = 1; z <= 6; z++) if (c.world.getBlockState(new BlockPos(0, -61, z)).isOf(Blocks.COBBLESTONE)) n++;
            return n;
        });
        double y = client(c -> c.player.getY());
        check(placed >= 2 && y > -60.5, "Scaffold bridges forward over a gap (" + placed + " blocks placed)");
    }

    // ------------------------------------------------------------------ combat blocks/armour

    private void surround() {
        reset();
        cmd("item replace entity @a hotbar.0 with obsidian 64");
        ctx.waitTicks(5);
        on(Surround.class);
        ctx.waitTicks(20);
        off(Surround.class);
        int n = client(c -> {
            int k = 0;
            BlockPos f = c.player.getBlockPos();
            for (Direction d : Direction.Type.HORIZONTAL) if (c.world.getBlockState(f.offset(d)).isOf(Blocks.OBSIDIAN)) k++;
            return k;
        });
        check(n == 4, "Surround places obsidian on all 4 sides (" + n + "/4)");
    }

    private void autoArmor() {
        reset();
        cmd("give @a diamond_chestplate");
        cmd("give @a iron_helmet");
        cmd("item replace entity @a armor.head with leather_helmet");
        ctx.waitTicks(5);
        on(AutoArmor.class);
        ctx.waitTicks(30);
        off(AutoArmor.class);
        boolean chest = client(c -> c.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE));
        boolean head = client(c -> c.player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET));
        check(chest && head, "AutoArmor equips better armour (diamond chest, iron over leather helmet)");
    }

    private void blink() {
        reset();
        on(Blink.class);
        ctx.waitTicks(2);
        ctx.getInput().holdKeyFor(o -> o.forwardKey, 20);
        double clientZ = client(c -> c.player.getZ());
        double serverZ = sp.getServer().computeOnServer(s -> sp(s).getZ());
        off(Blink.class);
        ctx.waitTicks(10);
        double serverAfter = sp.getServer().computeOnServer(s -> sp(s).getZ());
        check(clientZ > 2 && serverZ < 1 && serverAfter > 2,
                "Blink holds your position on the server, then releases it (client " + String.format("%.1f", clientZ) + ", server " + String.format("%.1f", serverZ) + " -> " + String.format("%.1f", serverAfter) + ")");
    }

    // ------------------------------------------------------------------ world

    private void nuker() {
        reset();
        cmd("gamemode creative @a");
        cmd("fill 1 -60 1 2 -58 2 dirt"); // 12 blocks, all within Nuker's default 4-block range
        ctx.waitTicks(10);
        Nuker nk = on(Nuker.class);
        // Flatten = only blocks at or above your feet ("All" would also dig out the floor under you).
        ctx.runOnClient(c -> { for (Setting<?> st : nk.getSettings()) if (st.getName().equals("Mode")) st.fromJson(new com.google.gson.JsonPrimitive("Flatten")); });
        ctx.waitTicks(20);
        off(Nuker.class);
        int left = client(c -> {
            int k = 0;
            for (BlockPos p : BlockPos.iterate(1, -60, 1, 2, -58, 2)) if (c.world.getBlockState(p).isOf(Blocks.DIRT)) {
                k++;
            }
            return k;
        });

        cmd("gamemode survival @a");
        check(left == 0, "Nuker (Flatten) clears the blocks around you (" + left + " left of 12)");
    }

    private void autoFarm() {
        reset();
        cmd("fill 1 -61 0 3 -61 0 farmland");
        cmd("fill 1 -60 0 3 -60 0 wheat[age=7]");
        cmd("item replace entity @a hotbar.1 with wheat_seeds 16");
        ctx.waitTicks(10);
        on(AutoFarm.class);
        ctx.waitTicks(40);
        off(AutoFarm.class);
        int replanted = client(c -> {
            int k = 0;
            for (int x = 1; x <= 3; x++) {
                var st = c.world.getBlockState(new BlockPos(x, -60, 0));
                if (st.isOf(Blocks.WHEAT) && st.get(net.minecraft.block.CropBlock.AGE) == 0) k++;
            }
            return k;
        });
        check(replanted == 3, "AutoFarm harvests grown wheat and replants it (" + replanted + "/3)");
    }

    private void chestStealer() {
        reset();
        cmd("setblock 0 -60 2 chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},{Slot:5b,id:\"minecraft:emerald\",count:3}]}");
        ctx.waitTicks(10);
        on(ChestStealer.class);
        ctx.runOnClient(c -> c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(new BlockPos(0, -60, 2)), Direction.UP, new BlockPos(0, -60, 2), false)));
        ctx.waitTicks(30);
        off(ChestStealer.class);
        boolean got = client(c -> c.player.getInventory().count(Items.DIAMOND) == 5 && c.player.getInventory().count(Items.EMERALD) == 3);
        boolean closed = client(c -> c.currentScreen == null);
        check(got && closed, "ChestStealer empties a chest into your inventory and closes it");
        cmd("setblock 0 -60 2 air");
    }

    // ------------------------------------------------------------------ player

    private void autoEat() {
        reset();
        cmd("item replace entity @a hotbar.4 with bread 8");
        sp.getServer().runOnServer(s -> sp(s).getHungerManager().setFoodLevel(6));
        ctx.waitTicks(10);
        on(AutoEat.class);
        ctx.waitTicks(80);
        off(AutoEat.class);
        int food = client(c -> c.player.getHungerManager().getFoodLevel());
        int slot = client(c -> c.player.getInventory().getSelectedSlot());
        check(food > 6 && slot == 0, "AutoEat eats when hungry and switches back (food " + food + ")");
    }

    private void autoTool() {
        reset();
        cmd("setblock 0 -60 2 stone");
        cmd("item replace entity @a hotbar.3 with iron_pickaxe");
        cmd("tp @a 0.5 -60 0.5 0 30");
        ctx.waitTicks(10);
        on(AutoTool.class);
        ctx.getInput().holdMouse(0);
        ctx.waitTicks(5);
        int sel = client(c -> c.player.getInventory().getSelectedSlot());
        ctx.getInput().releaseMouse(0);
        ctx.waitTicks(5);
        int after = client(c -> c.player.getInventory().getSelectedSlot());
        off(AutoTool.class);
        check(sel == 3 && after == 0, "AutoTool picks the pickaxe while mining stone and swaps back (" + sel + " -> " + after + ")");
        cmd("setblock 0 -60 2 air");
    }

    private void autoRespawn() {
        reset();
        on(AutoRespawn.class);
        cmd("kill @a");
        ctx.waitTicks(40);
        boolean alive = client(c -> c.player.isAlive() && !(c.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen));
        off(AutoRespawn.class);
        check(alive, "AutoRespawn skips the death screen");
        reset();
    }

    // ------------------------------------------------------------------ visuals / misc

    private void freecam() {
        reset();
        on(Freecam.class);
        ctx.getInput().holdKeyFor(o -> o.forwardKey, 20);
        double camZ = client(c -> c.gameRenderer.getCamera().getCameraPos().z);
        double playerZ = client(c -> c.player.getZ());
        off(Freecam.class);
        check(camZ > 3 && playerZ < 1, "Freecam moves the camera but not the player (camera z " + String.format("%.1f", camZ) + ", player z " + String.format("%.1f", playerZ) + ")");
    }

    private void timer() {
        reset();
        // The test framework keeps the client in step with the integrated server (which always ticks at 20/s), so
        // wall-clock time can't show Timer's effect here. Instead check the hook itself: the game's tick counter is
        // given 500 ms and must count 10 ticks normally and 20 with Timer at x2.
        int base = client(c -> new net.minecraft.client.render.RenderTickCounter.Dynamic(20f, 0L, f -> f).beginRenderTick(500L, true));
        Timer tm = on(Timer.class);
        ctx.runOnClient(c -> { for (Setting<?> st : tm.getSettings()) if (st.getName().equals("Speed")) ((net.evadeclient.setting.NumberSetting) st).set(2.0); });
        int fast = client(c -> new net.minecraft.client.render.RenderTickCounter.Dynamic(20f, 0L, f -> f).beginRenderTick(500L, true));
        off(Timer.class);
        check(base == 10 && fast == 20, "Timer x2 doubles the game ticks counted in 500 ms (" + base + " -> " + fast + ")");
    }

    // ------------------------------------------------------------------ printer

    private void printer() throws java.io.IOException {
        reset();
        // A small build with several block states: floor, a log lying east-west, a furnace facing south,
        // an upper slab, and enough block types that the packed data spans across longs.
        java.util.Map<BlockPos, net.minecraft.block.BlockState> blocks = new java.util.LinkedHashMap<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) blocks.put(new BlockPos(x, 0, z), Blocks.STONE_BRICKS.getDefaultState());
        blocks.put(new BlockPos(0, 1, 0), Blocks.OAK_LOG.getDefaultState().with(net.minecraft.state.property.Properties.AXIS, Direction.Axis.X));
        blocks.put(new BlockPos(2, 1, 0), Blocks.FURNACE.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, Direction.SOUTH));
        blocks.put(new BlockPos(0, 1, 2), Blocks.OAK_PLANKS.getDefaultState());
        blocks.put(new BlockPos(2, 1, 2), Blocks.GLASS.getDefaultState());
        blocks.put(new BlockPos(1, 2, 0), Blocks.SMOOTH_STONE_SLAB.getDefaultState().with(net.minecraft.block.SlabBlock.TYPE, net.minecraft.block.enums.SlabType.TOP));
        java.nio.file.Path file = ctx.computeOnClient(c -> {
            java.nio.file.Path dir = net.evadeclient.module.world.Printer.folder();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = dir.resolve("evadetest.litematic");
            try (var out = java.nio.file.Files.newOutputStream(f)) {
                net.minecraft.nbt.NbtIo.writeCompressed(net.evadeclient.util.LitematicReader.write("evadetest", blocks, new BlockPos(3, 3, 3)), out);
            }
            return f;
        });
        // the reader must give back exactly what was written
        var read = ctx.computeOnClient(c -> net.evadeclient.util.LitematicReader.read(file));
        check(read.blocks().equals(blocks), "Printer reads .litematic files exactly (" + read.size() + " blocks round-trip)");

        cmd("item replace entity @a hotbar.0 with stone_bricks 64");
        cmd("item replace entity @a hotbar.1 with oak_log 8");
        cmd("item replace entity @a inventory.0 with furnace 4"); // must be pulled in from the inventory
        cmd("item replace entity @a hotbar.3 with oak_planks 8");
        cmd("item replace entity @a hotbar.4 with glass 8");
        cmd("item replace entity @a hotbar.5 with smooth_stone_slab 8");
        cmd("tp @a 1.5 -60 4.5 180 20");
        ctx.waitTicks(10);
        // place the schematic 2 blocks in front of the player, as ".printer here" would at that spot
        ctx.runOnClient(c -> {
            net.evadeclient.EvadeClient.commands.handleChat(".printer load evadetest");
            EvadeClient.modules.get(net.evadeclient.module.world.Printer.class).load(read, new BlockPos(0, -60, 0));
        });
        on(net.evadeclient.module.world.Printer.class);
        ctx.waitTicks(80);
        ctx.takeScreenshot("evade-printer");
        off(net.evadeclient.module.world.Printer.class);
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (var e : blocks.entrySet()) {
            BlockPos w = e.getKey().add(0, -60, 0);
            var have = sp.getServer().computeOnServer(sv -> sv.getOverworld().getBlockState(w));
            if (!have.equals(e.getValue())) bad.add(w.toShortString() + " wanted " + e.getValue() + " got " + have);
        }
        bad.forEach(b -> EvadeClient.LOG.info("[gametest] printer mismatch: {}", b));
        check(bad.isEmpty(), "Printer builds the schematic with the right block states (" + (blocks.size() - bad.size()) + "/" + blocks.size() + " correct on the server)");
        printerWithLitematica(file, blocks);
    }

    /** If Litematica is installed in the test game, check the Printer follows Litematica's own placement. */
    private void printerWithLitematica(java.nio.file.Path file, java.util.Map<BlockPos, net.minecraft.block.BlockState> blocks) throws java.io.IOException {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) {
            EvadeClient.LOG.info("[gametest] Litematica not installed in the test game; skipping the Litematica check");
            return;
        }
        BlockPos origin = new BlockPos(-6, -60, 0);
        cmd("item replace entity @a hotbar.0 with stone_bricks 64");
        cmd("item replace entity @a hotbar.1 with oak_log 8");
        cmd("item replace entity @a inventory.0 with furnace 4");
        cmd("item replace entity @a hotbar.3 with oak_planks 8");
        cmd("item replace entity @a hotbar.4 with glass 8");
        cmd("item replace entity @a hotbar.5 with smooth_stone_slab 8");
        cmd("tp @a -4.5 -60 4.5 180 20");
        ctx.waitTicks(10);
        String placed;
        try { placed = ctx.computeOnClient(c -> {
            EvadeClient.commands.handleChat(".printer unload"); // so only Litematica drives the Printer
            Class<?> schem = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
            Object schematic = schem.getMethod("createFromFile", java.nio.file.Path.class, String.class)
                    .invoke(null, file.getParent(), file.getFileName().toString());
            if (schematic == null) return "Litematica could not read Evade's file";
            Object placement = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement")
                    .getMethod("createFor", schem, BlockPos.class, String.class, boolean.class, boolean.class)
                    .invoke(null, schematic, origin, "evadetest", true, true);
            Object mgr = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null);
            mgr.getClass().getMethod("addSchematicPlacement", placement.getClass(), boolean.class).invoke(mgr, placement, false);
            return "ok";
        }); } catch (ReflectiveOperationException e) { placed = "reflection failed: " + e; }
        check("ok".equals(placed), "Litematica reads Evade's .litematic file and places it (" + placed + ")");
        ctx.waitTicks(40); // Litematica fills its schematic world per chunk
        on(net.evadeclient.module.world.Printer.class);
        ctx.waitTicks(100);
        String suffix = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.world.Printer.class).getSuffix());
        off(net.evadeclient.module.world.Printer.class);
        int ok = 0;
        for (var e : blocks.entrySet()) {
            BlockPos w = e.getKey().add(origin);
            var have = sp.getServer().computeOnServer(sv -> sv.getOverworld().getBlockState(w));
            if (have.equals(e.getValue())) ok++;
            else EvadeClient.LOG.info("[gametest] litematica printer mismatch {} wanted {} got {}", w.toShortString(), e.getValue(), have);
        }
        check(ok == blocks.size() && "Litematica".equals(suffix),
                "Printer builds Litematica's placement (" + ok + "/" + blocks.size() + " correct, source: " + suffix + ")");
        // Clean up: remove the placement so Litematica stops tracking it for the rest of the run.
        int left;
        try { left = ctx.computeOnClient(c -> {
            Object mgr = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null);
            mgr.getClass().getMethod("clear").invoke(mgr);
            return ((java.util.List<?>) mgr.getClass().getMethod("getAllSchematicsPlacements").invoke(mgr)).size();
        }); } catch (ReflectiveOperationException e) { throw new AssertionError("could not remove the Litematica placement", e); }
        if (left != 0) throw new AssertionError("Litematica placement still loaded after cleanup (" + left + ")");
        ctx.waitTicks(10);
    }
}
