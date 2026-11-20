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

    void run() {
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
        // The test framework advances ticks only when asked, so measure how long a fixed number of ticks takes.
        long t0 = System.nanoTime();
        ctx.waitTicks(100);
        double base = (System.nanoTime() - t0) / 1e6;
        Timer tm = on(Timer.class);
        ctx.runOnClient(c -> { for (Setting<?> st : tm.getSettings()) if (st.getName().equals("Speed")) ((net.evadeclient.setting.NumberSetting) st).set(2.0); });
        long t1 = System.nanoTime();
        ctx.waitTicks(100);
        double fast = (System.nanoTime() - t1) / 1e6;
        off(Timer.class);
        check(fast < base * 0.75, String.format("Timer x2 makes 100 ticks take less real time (%.0f ms -> %.0f ms)", base, fast));
    }
}
