package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.combat.AutoAnvil;
import net.evadeclient.module.combat.AutoCity;
import net.evadeclient.module.combat.BedAura;
import net.evadeclient.module.combat.KillAura;
import net.evadeclient.module.movement.AutoSprint;
import net.evadeclient.module.world.Excavator;
import net.evadeclient.module.world.PacketMine;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** In-game checks for the mods added and improved in v0.6.0. */
final class V06ModsTest {
    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext sp;

    V06ModsTest(ClientGameTestContext ctx, TestSingleplayerContext sp) { this.ctx = ctx; this.sp = sp; }

    void run() {
        killAuraMulti();
        autoCity();
        autoAnvil();
        bedAura();
        packetMine();
        excavator();
        autoSprint();
    }

    // ------------------------------------------------------------------ helpers

    private void cmd(String c) { sp.getServer().runCommand(c); }

    private static ServerPlayerEntity sp(net.minecraft.server.MinecraftServer s) { return s.getPlayerManager().getPlayerList().getFirst(); }

    private void reset() {
        ctx.runOnClient(c -> { c.options.attackKey.setPressed(false); c.options.useKey.setPressed(false); if (c.currentScreen != null) c.setScreen(null); });
        cmd("kill @e[type=!player]");
        cmd("clear @a");
        cmd("effect clear @a");
        cmd("gamemode survival @a");
        cmd("fill -8 -61 -8 8 -50 8 air");
        cmd("fill -8 -61 -8 8 -61 8 grass_block");
        cmd("tp @a 0.5 -60 0.5 0 0");
        sp.getServer().runOnServer(s -> { sp(s).setHealth(20f); sp(s).getHungerManager().setFoodLevel(20); });
        ctx.waitTicks(10);
    }

    private <T extends Module> T on(Class<T> type) {
        return ctx.computeOnClient(c -> { T m = EvadeClient.modules.get(type); m.setEnabled(true); return m; });
    }

    private void off(Class<? extends Module> type) { ctx.runOnClient(c -> EvadeClient.modules.get(type).setEnabled(false)); }

    private void num(Module m, String name, double v) {
        ctx.runOnClient(c -> { for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((NumberSetting) s).set(v); });
    }

    private void bool(Module m, String name, boolean v) {
        ctx.runOnClient(c -> { for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((BooleanSetting) s).set(v); });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private <T> T client(java.util.function.Function<MinecraftClient, T> f) { return ctx.computeOnClient(f::apply); }

    private <T> T server(java.util.function.Function<net.minecraft.server.MinecraftServer, T> f) { return sp.getServer().computeOnServer(f::apply); }

    private net.minecraft.block.BlockState block(BlockPos p) { return server(s -> s.getOverworld().getBlockState(p)); }

    // ------------------------------------------------------------------ improvements

    private void killAuraMulti() {
        reset();
        cmd("item replace entity @a weapon.mainhand with netherite_sword");
        cmd("summon zombie 1.5 -60 0.5 {NoAI:1b,Health:6f}");
        cmd("summon zombie -0.5 -60 0.5 {NoAI:1b,Health:6f}");
        cmd("summon zombie 0.5 -60 1.5 {NoAI:1b,Health:6f}");
        ctx.waitTicks(10);
        KillAura ka = on(KillAura.class);
        bool(ka, "Mobs", true);
        num(ka, "Max Targets", 5);
        ctx.waitTicks(80);
        off(KillAura.class);
        int alive = client(c -> c.world.getEntitiesByClass(net.minecraft.entity.mob.ZombieEntity.class, c.player.getBoundingBox().expand(8), z -> z.isAlive()).size());
        check(alive == 0, "KillAura with Max Targets clears a crowd of 3 (left " + alive + ")");
    }

    // ------------------------------------------------------------------ combat

    /** A zombie in a 1x1 obsidian hole at (3,-60,0), the wall toward the player at (2,-60,0). */
    private void autoCity() {
        reset();
        cmd("setblock 3 -61 0 obsidian");
        cmd("setblock 4 -60 0 obsidian");
        cmd("setblock 3 -60 1 obsidian");
        cmd("setblock 3 -60 -1 obsidian");
        cmd("setblock 2 -60 0 obsidian");
        cmd("summon zombie 3.5 -60 0.5 {NoAI:1b}");
        cmd("tp @a 0.5 -60 0.5 0 0");
        cmd("gamemode creative @a");  // obsidian takes ~9s to mine in survival; creative verifies the targeting + break trigger
        cmd("item replace entity @a weapon.mainhand with netherite_pickaxe");
        ctx.waitTicks(10);
        AutoCity ac = on(AutoCity.class);
        bool(ac, "Mobs", true);
        ctx.waitTicks(40);
        off(AutoCity.class);
        cmd("gamemode survival @a");
        check(block(new BlockPos(2, -60, 0)).isAir(), "AutoCity mines the wall of an enemy's hole");
    }

    private void autoAnvil() {
        reset();
        cmd("item replace entity @a hotbar.0 with anvil 16");
        cmd("summon zombie 1.5 -60 0.5 {NoAI:1b}");
        cmd("setblock 2 -58 0 stone");  // support beside the target's head so the anvil has a face to place against
        cmd("tp @a 0.5 -60 0.5 0 0");
        ctx.waitTicks(10);
        AutoAnvil aa = on(AutoAnvil.class);
        bool(aa, "Mobs", true);
        num(aa, "Delay", 0);
        num(aa, "Height", 3);
        ctx.waitTicks(40);
        off(AutoAnvil.class);
        // The anvil is placed in the air above the zombie, so it immediately falls, or lands as a block below.
        boolean falling = server(s -> !s.getOverworld().getEntitiesByClass(net.minecraft.entity.FallingBlockEntity.class,
                new net.minecraft.util.math.Box(0, -61, -1, 3, -45, 2), e -> true).isEmpty());
        boolean landed = false;
        for (int y = -60; y <= -56; y++) if (block(new BlockPos(1, y, 0)).getBlock() == Blocks.ANVIL) landed = true;
        int used = server(s -> sp(s).getInventory().count(net.minecraft.item.Items.ANVIL));
        check((falling || landed) && used < 16, "AutoAnvil drops an anvil above the target (used " + (16 - used) + ")");
    }

    private void bedAura() {
        reset();
        cmd("item replace entity @a hotbar.0 with red_bed");  // beds don't stack past 1
        cmd("item replace entity @a hotbar.1 with red_bed");
        cmd("item replace entity @a hotbar.2 with red_bed");
        cmd("summon zombie 1.5 -60 0.5 {NoAI:1b}");
        cmd("tp @a 0.5 -60 0.5 0 0");
        ctx.waitTicks(10);
        BedAura ba = on(BedAura.class);
        bool(ba, "Mobs", true);
        ctx.waitTicks(30);
        // A bed should now sit near the zombie (overworld: it won't explode, but placement is the testable part).
        boolean bed = false;
        for (BlockPos p : BlockPos.iterate(new BlockPos(-2, -60, -3), new BlockPos(4, -59, 3)))
            if (block(p).getBlock() instanceof net.minecraft.block.BedBlock) bed = true;
        int left = server(s -> sp(s).getInventory().count(net.minecraft.item.Items.RED_BED));
        off(BedAura.class);
        check(bed && left < 3, "BedAura places a bed next to the target (" + (3 - left) + " placed)");
    }

    // ------------------------------------------------------------------ world

    private void packetMine() {
        reset();
        cmd("setblock 0 -60 3 stone");
        cmd("item replace entity @a weapon.mainhand with netherite_pickaxe");
        ctx.waitTicks(5);
        PacketMine pm = on(PacketMine.class);
        // Point it at the stone, then face the other way: it should still finish the block.
        ctx.runOnClient(c -> pm.setTarget(new BlockPos(0, -60, 3), Direction.UP));
        cmd("tp @a 0.5 -60 0.5 180 0");
        ctx.waitTicks(80);
        off(PacketMine.class);
        check(block(new BlockPos(0, -60, 3)).isAir(), "PacketMine finishes a block while you look away");
    }

    private void excavator() {
        reset();
        // A 3x3x3 block of stone exactly where the Excavator digs: one block ahead (+Z), 3 wide, 3 tall, 3 deep.
        cmd("fill -1 -60 1 1 -58 3 stone");
        cmd("item replace entity @a weapon.mainhand with netherite_pickaxe");
        cmd("tp @a 0.5 -60 0.5 0 0");
        ctx.waitTicks(10);
        Excavator ex = on(Excavator.class);
        num(ex, "Width", 3);
        num(ex, "Height", 3);
        num(ex, "Depth", 3);
        ctx.waitTicks(180);
        off(Excavator.class);
        int left = server(s -> {
            int n = 0;
            for (BlockPos p : BlockPos.iterate(new BlockPos(-1, -60, 1), new BlockPos(1, -58, 3)))
                if (!s.getOverworld().getBlockState(p).isAir()) n++;
            return n;
        });
        // Everything within reach should be gone; a couple of far/high corners of the 27 may remain.
        check(left <= 4, "Excavator digs out the box in front of you (" + (27 - left) + "/27 cleared)");
    }

    // ------------------------------------------------------------------ movement

    private void autoSprint() {
        reset();
        on(AutoSprint.class);
        ctx.getInput().holdKey(o -> o.forwardKey);
        ctx.waitTicks(15);
        boolean sprinting = client(c -> c.player.isSprinting());
        ctx.getInput().releaseKey(o -> o.forwardKey);
        off(AutoSprint.class);
        check(sprinting, "AutoSprint sprints automatically while you walk forward");
    }
}
