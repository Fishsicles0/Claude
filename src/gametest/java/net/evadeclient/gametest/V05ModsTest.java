package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.combat.AutoWeapon;
import net.evadeclient.module.combat.AutoWeb;
import net.evadeclient.module.combat.HoleFiller;
import net.evadeclient.module.combat.KillAura;
import net.evadeclient.module.combat.Offhand;
import net.evadeclient.module.combat.SelfTrap;
import net.evadeclient.module.misc.AutoGG;
import net.evadeclient.module.misc.BetterChat;
import net.evadeclient.module.misc.Notifier;
import net.evadeclient.module.movement.AirJump;
import net.evadeclient.module.movement.AutoJump;
import net.evadeclient.module.movement.FastClimb;
import net.evadeclient.module.movement.InventoryMove;
import net.evadeclient.module.movement.NoSlow;
import net.evadeclient.module.movement.ReverseStep;
import net.evadeclient.module.player.AirPlace;
import net.evadeclient.module.player.AutoGap;
import net.evadeclient.module.player.AutoReplenish;
import net.evadeclient.module.player.ChestSwap;
import net.evadeclient.module.player.FakePlayer;
import net.evadeclient.module.player.FastUse;
import net.evadeclient.module.player.MiddleClickExtra;
import net.evadeclient.module.visuals.BetterTooltips;
import net.evadeclient.module.visuals.Breadcrumbs;
import net.evadeclient.module.visuals.HoleESP;
import net.evadeclient.module.visuals.LightOverlay;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.module.world.AutoBreed;
import net.evadeclient.module.world.AutoShearer;
import net.evadeclient.module.world.AutoSmelter;
import net.evadeclient.module.world.LiquidFiller;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** In-game checks for the mods added in v0.5.0. Server-side state is checked wherever the server decides the result. */
final class V05ModsTest {
    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext sp;

    V05ModsTest(ClientGameTestContext ctx, TestSingleplayerContext sp) { this.ctx = ctx; this.sp = sp; }

    void run() {
        // Start from a clean slate: earlier tests may leave modules on. Log them, then switch off everything
        // that isn't on by default.
        java.util.Set<String> defaults = java.util.Set.of("AntiBot", "Waypoints", "BetterTooltips", "Module List", "Client Settings",
                "ToggleSprint", "No Hurt Cam", "Zoom", "Freelook"); // on by default
        String left = client(c -> {
            List<String> on = new ArrayList<>();
            for (Module m : EvadeClient.modules.all()) {
                if (!m.isEnabled() || defaults.contains(m.getName()) || m instanceof net.evadeclient.module.HudElementModule) continue;
                on.add(m.getName());
                m.setEnabled(false);
            }
            return String.join(", ", on);
        });
        EvadeClient.LOG.info("[gametest] modules left on by earlier tests (now off): {}", left.isEmpty() ? "none" : left);
        ctx.waitTicks(5);
        autoWeapon();
        offhand();
        selfTrap();
        holeFillerAndEsp();
        autoWeb();
        noSlow();
        inventoryMove();
        airJump();
        reverseStep();
        fastClimb();
        autoJump();
        fakePlayer();
        autoReplenish();
        fastUse();
        middleClick();
        airPlace();
        autoGap();
        chestSwap();
        breadcrumbs();
        lightOverlay();
        waypoints();
        tooltips();
        autoBreed();
        autoShearer();
        autoSmelter();
        liquidFiller();
        betterChat();
        notifier();
        autoGG();
    }

    // ------------------------------------------------------------------ helpers

    private void cmd(String c) { sp.getServer().runCommand(c); }

    private static ServerPlayerEntity sp(net.minecraft.server.MinecraftServer s) { return s.getPlayerManager().getPlayerList().getFirst(); }

    private void reset() {
        // Client-side resets first. Nothing runs on the client between the teleport and the next tick, so the
        // client answers the teleport during a normal tick (same order as NewModsTest's reset).
        ctx.runOnClient(c -> {
            c.options.useKey.setPressed(false);
            if (c.currentScreen != null) c.setScreen(null);
            c.player.getInventory().setSelectedSlot(0);
        });
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

    private void bool(Module m, String name, boolean v) {
        ctx.runOnClient(c -> { for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((BooleanSetting) s).set(v); });
    }

    private void mode(Module m, String name, String v) {
        ctx.runOnClient(c -> { for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((ModeSetting) s).fromJson(new com.google.gson.JsonPrimitive(v)); });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private <T> T client(java.util.function.Function<MinecraftClient, T> f) { return ctx.computeOnClient(f::apply); }

    private <T> T server(java.util.function.Function<net.minecraft.server.MinecraftServer, T> f) { return sp.getServer().computeOnServer(f::apply); }

    private net.minecraft.block.BlockState block(BlockPos p) { return server(s -> s.getOverworld().getBlockState(p)); }

    // ------------------------------------------------------------------ combat

    private void autoWeapon() {
        reset();
        cmd("item replace entity @a hotbar.0 with stick");
        cmd("item replace entity @a hotbar.3 with netherite_sword");
        cmd("item replace entity @a hotbar.5 with wooden_sword");
        cmd("summon zombie 0.5 -60 2.5 {NoAI:1b}");
        ctx.waitTicks(10);
        on(AutoWeapon.class);
        ctx.runOnClient(c -> {
            var z = c.world.getEntitiesByClass(net.minecraft.entity.mob.ZombieEntity.class, c.player.getBoundingBox().expand(5), e -> true).getFirst();
            c.interactionManager.attackEntity(c.player, z);
        });
        ctx.waitTicks(5);
        off(AutoWeapon.class);
        int server = server(s -> sp(s).getInventory().getSelectedSlot());
        check(server == 3, "AutoWeapon switches to the strongest weapon when you attack (slot " + server + ", netherite sword)");
    }

    private void offhand() {
        reset();
        cmd("item replace entity @a inventory.0 with end_crystal 64");
        cmd("item replace entity @a inventory.1 with totem_of_undying");
        ctx.waitTicks(5);
        on(Offhand.class); // defaults: crystals, totem below 10 health
        ctx.waitTicks(10);
        boolean crystal = server(s -> sp(s).getOffHandStack().isOf(Items.END_CRYSTAL));
        sp.getServer().runOnServer(s -> sp(s).setHealth(6f));
        ctx.waitTicks(10);
        boolean totem = server(s -> sp(s).getOffHandStack().isOf(Items.TOTEM_OF_UNDYING));
        off(Offhand.class);
        check(crystal && totem, "Offhand keeps crystals in your offhand and switches to a totem at low health");
    }

    private void selfTrap() {
        reset();
        cmd("item replace entity @a hotbar.0 with obsidian 64");
        ctx.waitTicks(5);
        on(SelfTrap.class);
        ctx.waitTicks(40);
        off(SelfTrap.class);
        BlockPos feet = new BlockPos(0, -60, 0);
        int head = 0;
        for (Direction d : Direction.Type.HORIZONTAL) if (block(feet.up().offset(d)).isOf(Blocks.OBSIDIAN)) head++;
        boolean roof = block(feet.up(2)).isOf(Blocks.OBSIDIAN);
        check(head == 4 && roof, "SelfTrap boxes in your head with obsidian (" + head + "/4 sides, roof " + roof + ")");
    }

    /** Builds an obsidian-walled 1x1 hole at (3, -60, 0). */
    private BlockPos hole() {
        cmd("setblock 3 -61 0 obsidian");
        cmd("setblock 2 -60 0 obsidian");
        cmd("setblock 4 -60 0 obsidian");
        cmd("setblock 3 -60 1 obsidian");
        cmd("setblock 3 -60 -1 obsidian");
        ctx.waitTicks(5);
        return new BlockPos(3, -60, 0);
    }

    private void holeFillerAndEsp() {
        reset();
        BlockPos h = hole();
        HoleESP esp = on(HoleESP.class);
        ctx.waitTicks(10);
        int found = client(c -> esp.count());
        off(HoleESP.class);
        check(found >= 1, "HoleESP finds the safe hole next to you (" + found + ")");
        cmd("item replace entity @a hotbar.0 with obsidian 64");
        ctx.waitTicks(5);
        HoleFiller hf = on(HoleFiller.class);
        mode(hf, "Near", "Me");
        ctx.waitTicks(20);
        off(HoleFiller.class);
        check(block(h).isOf(Blocks.OBSIDIAN), "HoleFiller fills a safe hole with obsidian");
    }

    private void autoWeb() {
        reset();
        cmd("item replace entity @a hotbar.0 with cobweb 16");
        cmd("summon zombie 0.5 -60 3.5 {NoAI:1b}");
        ctx.waitTicks(10);
        AutoWeb w = on(AutoWeb.class);
        bool(w, "Mobs", true);
        ctx.waitTicks(10);
        off(AutoWeb.class);
        check(block(new BlockPos(0, -60, 3)).isOf(Blocks.COBWEB), "AutoWeb puts a cobweb on the target's feet");
    }

    // ------------------------------------------------------------------ movement

    /** Blocks walked forward in 15 ticks while eating. */
    private double walkWhileEating() {
        cmd("tp @a 0.5 -60 0.5 0 0");
        cmd("item replace entity @a hotbar.0 with cooked_beef 64");
        sp.getServer().runOnServer(s -> sp(s).getHungerManager().setFoodLevel(10));
        ctx.waitTicks(5);
        double z0 = client(c -> c.player.getZ());
        ctx.getInput().holdKey(o -> o.useKey);
        ctx.getInput().holdKey(o -> o.forwardKey);
        ctx.waitTicks(15);
        double z1 = client(c -> c.player.getZ());
        ctx.getInput().releaseKey(o -> o.forwardKey);
        ctx.getInput().releaseKey(o -> o.useKey);
        ctx.waitTicks(10);
        return z1 - z0;
    }

    private void noSlow() {
        reset();
        double slow = walkWhileEating();
        on(NoSlow.class);
        double fast = walkWhileEating();
        off(NoSlow.class);
        check(fast > slow * 3 && fast > 1.5, String.format("NoSlow walks at full speed while eating (%.2f vs %.2f blocks)", fast, slow));
    }

    private void inventoryMove() {
        reset();
        on(InventoryMove.class);
        ctx.setScreen(() -> new net.minecraft.client.gui.screen.ingame.InventoryScreen(MinecraftClient.getInstance().player));
        double z0 = client(c -> c.player.getZ());
        ctx.getInput().holdKey(o -> o.forwardKey);
        ctx.waitTicks(20);
        ctx.getInput().releaseKey(o -> o.forwardKey);
        double z1 = client(c -> c.player.getZ());
        boolean open = client(c -> c.currentScreen != null);
        ctx.setScreen(() -> null);
        off(InventoryMove.class);
        check(open && z1 - z0 > 2, String.format("InventoryMove walks with the inventory open (%.1f blocks)", z1 - z0));
    }

    private void airJump() {
        reset();
        AirJump aj = on(AirJump.class);
        mode(aj, "Mode", "Hold");
        ctx.getInput().holdKey(o -> o.jumpKey);
        ctx.waitTicks(20);
        double y = client(c -> c.player.getY());
        ctx.getInput().releaseKey(o -> o.jumpKey);
        off(AirJump.class);
        ctx.waitTicks(60);
        check(y > -57, String.format("AirJump keeps jumping in mid-air (%.1f blocks up; a normal jump is 1.25)", y + 60));
    }

    /** Ticks from walking off a 2-block ledge to landing. */
    private int dropTicks() {
        cmd("fill -1 -60 -1 1 -59 1 stone");
        cmd("tp @a 0.5 -58 0.5 0 0");
        ctx.waitTicks(10);
        ctx.getInput().holdKey(o -> o.forwardKey);
        int left = -1;
        for (int t = 0; t < 60; t++) {
            ctx.waitTick();
            boolean ground = client(c -> c.player.isOnGround());
            double y = client(c -> c.player.getY());
            if (left < 0 && !ground) left = t;
            if (left >= 0 && ground && y < -59.5) { ctx.getInput().releaseKey(o -> o.forwardKey); return t - left; }
        }
        ctx.getInput().releaseKey(o -> o.forwardKey);
        return 99;
    }

    private void reverseStep() {
        reset();
        int normal = dropTicks();
        cmd("fill -1 -60 -1 1 -59 1 air");
        on(ReverseStep.class);
        int fast = dropTicks();
        off(ReverseStep.class);
        cmd("fill -1 -60 -1 1 -59 1 air");
        check(fast < normal, "ReverseStep drops you down a ledge faster (" + fast + " vs " + normal + " ticks)");
    }

    private double climb() {
        cmd("fill 0 -60 2 0 -45 2 stone");
        cmd("fill 0 -60 1 0 -46 1 ladder[facing=north]");
        cmd("tp @a 0.5 -60 1.5 0 0");
        ctx.waitTicks(5);
        ctx.getInput().holdKey(o -> o.forwardKey);
        ctx.waitTicks(20);
        ctx.getInput().releaseKey(o -> o.forwardKey);
        double y = client(c -> c.player.getY()) + 60;
        cmd("fill 0 -60 1 0 -45 2 air");
        ctx.waitTicks(40);
        return y;
    }

    private void fastClimb() {
        reset();
        double normal = climb();
        on(FastClimb.class);
        double fast = climb();
        off(FastClimb.class);
        check(fast > normal + 1, String.format("FastClimb climbs ladders faster (%.1f vs %.1f blocks in 1s)", fast, normal));
    }

    private void autoJump() {
        reset();
        AutoJump aj = on(AutoJump.class);
        bool(aj, "Only Moving", false);
        double max = -60;
        for (int i = 0; i < 20; i++) { ctx.waitTick(); max = Math.max(max, client(c -> c.player.getY())); }
        off(AutoJump.class);
        check(max > -59.5, String.format("AutoJump jumps on its own (peak %.2f blocks)", max + 60));
    }

    // ------------------------------------------------------------------ player

    private void fakePlayer() {
        reset();
        cmd("item replace entity @a weapon.mainhand with netherite_sword");
        ctx.waitTicks(5);
        on(FakePlayer.class);
        ctx.waitTicks(2);
        boolean exists = client(c -> c.world.getEntityById(-133713) != null);
        cmd("tp @a 0.5 -60 2.5 180 0"); // step away from the dummy
        ctx.waitTicks(10);
        on(KillAura.class); // players are targeted by default
        ctx.waitTicks(30);
        boolean hit = client(c -> FakePlayer.isFake(net.evadeclient.util.TargetTracker.get()));
        off(KillAura.class);
        off(FakePlayer.class);
        ctx.waitTicks(2);
        boolean gone = client(c -> c.world.getEntityById(-133713) == null);
        check(exists && hit && gone, "FakePlayer spawns a test dummy that KillAura targets, and removes it when turned off");
    }

    private void autoReplenish() {
        reset();
        cmd("item replace entity @a hotbar.0 with cobblestone 5");
        cmd("item replace entity @a inventory.4 with cobblestone 64");
        ctx.waitTicks(5);
        on(AutoReplenish.class);
        ctx.waitTicks(15);
        off(AutoReplenish.class);
        int n = server(s -> sp(s).getInventory().getStack(0).getCount());
        check(n == 64, "AutoReplenish tops up a low hotbar stack from your inventory (5 -> " + n + ")");
    }

    private int bottlesThrown() {
        cmd("item replace entity @a hotbar.0 with experience_bottle 64");
        cmd("tp @a 0.5 -60 0.5 0 -90");
        ctx.waitTicks(5);
        ctx.getInput().holdKey(o -> o.useKey);
        ctx.waitTicks(20);
        ctx.getInput().releaseKey(o -> o.useKey);
        ctx.waitTicks(5);
        int left = server(s -> sp(s).getInventory().getStack(0).getCount());
        cmd("kill @e[type=experience_orb]");
        cmd("kill @e[type=experience_bottle]");
        return 64 - left;
    }

    private void fastUse() {
        reset();
        int normal = bottlesThrown();
        FastUse fu = on(FastUse.class);
        mode(fu, "Items", "XP Only");
        int fast = bottlesThrown();
        off(FastUse.class);
        check(fast >= normal * 2, "FastUse throws XP bottles without the click delay (" + fast + " vs " + normal + " in 1s)");
    }

    private void middleClick() {
        reset();
        cmd("item replace entity @a inventory.2 with ender_pearl 16");
        cmd("item replace entity @a hotbar.0 with diamond_sword");
        ctx.waitTicks(5);
        MiddleClickExtra m = on(MiddleClickExtra.class);
        boolean used = client(c -> m.use());
        ctx.waitTicks(5);
        off(MiddleClickExtra.class);
        int pearls = server(s -> sp(s).getInventory().count(Items.ENDER_PEARL));
        int slot = server(s -> sp(s).getInventory().getSelectedSlot());
        check(used && pearls == 15 && slot == 0, "MiddleClickExtra throws a pearl from your inventory and keeps your sword selected");
        cmd("kill @e[type=ender_pearl]");
    }

    private void airPlace() {
        reset();
        cmd("item replace entity @a hotbar.0 with stone 16");
        cmd("tp @a 0.5 -60 0.5 0 -90");
        ctx.waitTicks(5);
        AirPlace ap = on(AirPlace.class);
        ctx.waitTicks(2);
        BlockPos target = client(c -> ap.target());
        boolean placed = client(c -> ap.place());
        ctx.waitTicks(5);
        off(AirPlace.class);
        check(target != null && placed && block(target).isOf(Blocks.STONE), "AirPlace places a block in mid-air (" + (target == null ? "none" : target.toShortString()) + ")");
    }

    private void autoGap() {
        reset();
        cmd("item replace entity @a hotbar.2 with golden_apple 4");
        sp.getServer().runOnServer(s -> sp(s).setHealth(8f));
        ctx.waitTicks(5);
        on(AutoGap.class);
        ctx.waitTicks(60);
        off(AutoGap.class);
        int left = server(s -> sp(s).getInventory().count(Items.GOLDEN_APPLE));
        float absorb = server(s -> sp(s).getAbsorptionAmount());
        int slot = client(c -> c.player.getInventory().getSelectedSlot());
        check(left == 3 && absorb > 0 && slot == 0, "AutoGap eats a golden apple at low health and switches back");
    }

    private void chestSwap() {
        reset();
        cmd("item replace entity @a armor.chest with diamond_chestplate");
        cmd("item replace entity @a inventory.5 with elytra");
        ctx.waitTicks(5);
        on(ChestSwap.class);
        ctx.waitTicks(5);
        boolean elytra = server(s -> sp(s).getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA));
        on(ChestSwap.class);
        ctx.waitTicks(5);
        boolean back = server(s -> sp(s).getEquippedStack(EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE));
        check(elytra && back, "ChestSwap swaps your chestplate for an elytra and back");
    }

    // ------------------------------------------------------------------ visuals

    private void breadcrumbs() {
        reset();
        Breadcrumbs b = on(Breadcrumbs.class);
        ctx.getInput().holdKeyFor(o -> o.forwardKey, 20);
        int n = client(c -> b.size());
        off(Breadcrumbs.class);
        check(n > 5, "Breadcrumbs records a trail as you walk (" + n + " points)");
    }

    private void lightOverlay() {
        reset();
        cmd("fill -4 -60 -4 4 -58 4 air");
        cmd("fill 2 -58 -1 4 -58 1 stone"); // a roof: the spot under it gets no sky light
        ctx.waitTicks(10);
        LightOverlay lo = on(LightOverlay.class);
        ctx.waitTicks(15);
        boolean dark = client(c -> lo.spawnable(new BlockPos(3, -60, 0)));
        cmd("setblock 3 -60 0 torch");
        ctx.waitTicks(10);
        boolean lit = client(c -> lo.spawnable(new BlockPos(3, -60, 1)));
        off(LightOverlay.class);
        check(dark && !lit, "LightOverlay marks dark spawnable spots and clears them next to a torch");
    }

    private void waypoints() {
        reset();
        Waypoints w = client(c -> EvadeClient.modules.get(Waypoints.class));
        ctx.runOnClient(c -> EvadeClient.commands.handleChat(".waypoint add Base"));
        boolean added = client(c -> w.here().stream().anyMatch(p -> p.name().equals("Base") && p.x() == 0 && p.z() == 0));
        ctx.runOnClient(c -> EvadeClient.commands.handleChat(".waypoint del Base"));
        boolean removed = client(c -> w.here().stream().noneMatch(p -> p.name().equals("Base")));
        check(added && removed, "Waypoints: .waypoint add saves your position and .waypoint del removes it");
    }

    private void tooltips() {
        List<Text> lines = client(c -> {
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(
                    List.of(new ItemStack(Items.OBSIDIAN, 64), new ItemStack(Items.OBSIDIAN, 64), new ItemStack(Items.TOTEM_OF_UNDYING))));
            List<Text> out = new ArrayList<>();
            EvadeClient.modules.get(BetterTooltips.class).lines(box, out);
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.setDamage(561);
            EvadeClient.modules.get(BetterTooltips.class).lines(sword, out);
            return out;
        });
        String all = String.join(" | ", lines.stream().map(Text::getString).toList());
        check(all.contains("128x Obsidian") && all.contains("1x Totem") && all.contains("1000/1561"),
                "BetterTooltips totals shulker contents and shows durability (" + all + ")");
    }

    // ------------------------------------------------------------------ world

    private void autoBreed() {
        reset();
        cmd("item replace entity @a hotbar.1 with wheat 16");
        cmd("summon cow 2.5 -60 0.5 {NoAI:1b}");
        cmd("summon cow 0.5 -60 2.5 {NoAI:1b}");
        ctx.waitTicks(10);
        on(AutoBreed.class);
        ctx.waitTicks(30);
        off(AutoBreed.class);
        long inLove = server(s -> s.getOverworld().getEntitiesByClass(net.minecraft.entity.passive.CowEntity.class,
                new net.minecraft.util.math.Box(-8, -62, -8, 8, -50, 8), net.minecraft.entity.passive.AnimalEntity::isInLove).size());
        check(inLove == 2, "AutoBreed feeds both cows so they breed (" + inLove + "/2 in love)");
    }

    private void autoShearer() {
        reset();
        cmd("item replace entity @a hotbar.1 with shears");
        cmd("summon sheep 2.5 -60 0.5 {NoAI:1b}");
        ctx.waitTicks(10);
        on(AutoShearer.class);
        ctx.waitTicks(15);
        off(AutoShearer.class);
        boolean sheared = server(s -> s.getOverworld().getEntitiesByClass(net.minecraft.entity.passive.SheepEntity.class,
                new net.minecraft.util.math.Box(-8, -62, -8, 8, -50, 8), e -> true).stream().allMatch(net.minecraft.entity.passive.SheepEntity::isSheared));
        check(sheared, "AutoShearer shears a sheep in reach");
    }

    private void autoSmelter() {
        reset();
        cmd("setblock 0 -60 2 furnace");
        cmd("item replace entity @a inventory.0 with raw_iron 8");
        cmd("item replace entity @a inventory.1 with coal 4");
        ctx.waitTicks(5);
        on(AutoSmelter.class);
        ctx.runOnClient(c -> c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(new BlockPos(0, -60, 2)), Direction.UP, new BlockPos(0, -60, 2), false)));
        ctx.waitTicks(30);
        ctx.setScreen(() -> null);
        off(AutoSmelter.class);
        boolean loaded = server(s -> {
            var be = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) s.getOverworld().getBlockEntity(new BlockPos(0, -60, 2));
            return be.getStack(0).isOf(Items.RAW_IRON) || be.getStack(2).isOf(Items.IRON_INGOT);
        });
        boolean fueled = server(s -> sp(s).getInventory().count(Items.COAL) < 4);
        check(loaded && fueled, "AutoSmelter loads ore and fuel into an open furnace");
    }

    private void liquidFiller() {
        reset();
        cmd("item replace entity @a hotbar.0 with cobblestone 32");
        cmd("setblock 2 -61 0 water");
        ctx.waitTicks(10);
        on(LiquidFiller.class);
        ctx.waitTicks(20);
        off(LiquidFiller.class);
        check(block(new BlockPos(2, -61, 0)).isOf(Blocks.COBBLESTONE), "LiquidFiller fills a water source with cobblestone");
    }

    // ------------------------------------------------------------------ misc

    private void betterChat() {
        on(BetterChat.class);
        String text = client(c -> {
            var hud = c.inGameHud.getChatHud();
            for (int i = 0; i < 3; i++) hud.addMessage(Text.literal("evade spam test"));
            try {
                var f = net.minecraft.client.gui.hud.ChatHud.class.getDeclaredField("messages");
                f.setAccessible(true);
                @SuppressWarnings("unchecked") var msgs = (List<net.minecraft.client.gui.hud.ChatHudLine>) f.get(hud);
                long copies = msgs.stream().filter(l -> l.content().getString().contains("evade spam test")).count();
                return copies + "|" + msgs.getFirst().content().getString();
            } catch (ReflectiveOperationException e) { return "error " + e; }
        });
        off(BetterChat.class);
        check(text.startsWith("1|") && text.contains("(x3)") && text.matches("1\\|\\[\\d\\d:\\d\\d\\] .*"),
                "BetterChat stacks repeated messages with a timestamp (" + text + ")");
    }

    private void notifier() {
        reset();
        cmd("item replace entity @a weapon.offhand with totem_of_undying");
        ctx.waitTicks(5);
        Notifier n = on(Notifier.class);
        cmd("damage @p 100 minecraft:generic");
        ctx.waitTicks(10);
        int pops = client(c -> n.pops(c.player.getUuid()));
        off(Notifier.class);
        check(pops == 1, "Notifier counts a totem pop (" + pops + ")");
    }

    private void autoGG() {
        boolean yes = AutoGG.matches("Winner: EvadeDummy") && AutoGG.matches("1st Killer - Steve - 12");
        boolean no = !AutoGG.matches("Steve joined the game");
        check(yes && no, "AutoGG recognises end-of-game messages and ignores normal chat");
    }
}
