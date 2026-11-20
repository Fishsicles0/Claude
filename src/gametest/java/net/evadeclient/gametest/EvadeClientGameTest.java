package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.ClickGuiScreen;
import net.evadeclient.module.Module;
import net.evadeclient.module.combat.AimAssist;
import net.evadeclient.module.combat.AutoMend;
import net.evadeclient.module.combat.AutoPot;
import net.evadeclient.module.combat.AutoTotem;
import net.evadeclient.module.combat.Hitbox;
import net.evadeclient.module.combat.Reach;
import net.evadeclient.module.combat.TriggerBot;
import net.evadeclient.module.render.ChestESP;
import net.evadeclient.module.render.ChunkLocator;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Launches the real game, builds a test arena and checks each module actually does its job. */
public class EvadeClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            var server = sp.getServer();
            server.runCommand("time set day");
            server.runCommand("gamemode survival @a");
            server.runCommand("difficulty easy");
            server.runCommand("tp @a 0 -60 0 0 0");
            ctx.waitTicks(20);

            testAutoTotem(ctx, sp);
            testAutoPot(ctx, sp);
            testAutoMend(ctx, sp);
            testReach(ctx);
            testTriggerBotAndHitbox(ctx, sp);
            testAimAssist(ctx, sp);
            testVisuals(ctx, sp);
        }
    }

    private static <T extends Module> T enable(ClientGameTestContext ctx, Class<T> type) {
        return ctx.computeOnClient(c -> {
            T m = EvadeClient.modules.get(type);
            m.setEnabled(true);
            return m;
        });
    }

    private static void disable(ClientGameTestContext ctx, Class<? extends Module> type) {
        ctx.runOnClient(c -> EvadeClient.modules.get(type).setEnabled(false));
    }

    private static void setBool(ClientGameTestContext ctx, Module m, String name, boolean value) {
        ctx.runOnClient(c -> {
            for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) ((BooleanSetting) s).set(value);
        });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private void testAutoTotem(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("clear @a");
        sp.getServer().runCommand("item replace entity @a inventory.5 with totem_of_undying");
        sp.getServer().runCommand("item replace entity @a inventory.9 with totem_of_undying");
        ctx.waitTicks(5);
        enable(ctx, AutoTotem.class);
        ctx.waitTicks(10);
        boolean ok = ctx.computeOnClient(c -> c.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING));
        check(ok, "AutoTotem moves a totem into the offhand");
        disable(ctx, AutoTotem.class);
    }

    private void testAutoPot(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("clear @a");
        sp.getServer().runCommand("item replace entity @a inventory.0 with splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]");
        sp.getServer().runCommand("item replace entity @a hotbar.3 with splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]");
        sp.getServer().runCommand("effect clear @a");
        sp.getServer().runOnServer(s -> s.getPlayerManager().getPlayerList().getFirst().setHealth(6f));
        ctx.waitTicks(10);
        float before = ctx.computeOnClient(c -> c.player.getHealth());
        enable(ctx, AutoPot.class);
        ctx.waitTicks(30);
        float after = ctx.computeOnClient(c -> c.player.getHealth());
        disable(ctx, AutoPot.class);
        check(after > before, "AutoPot heals with a splash potion (" + before + " -> " + after + ")");
        int selected = ctx.computeOnClient(c -> c.player.getInventory().getSelectedSlot());
        check(selected == 0, "AutoPot restores the selected hotbar slot");
    }

    private void testAutoMend(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("clear @a");
        sp.getServer().runCommand("effect give @a resistance 60 4 true");
        sp.getServer().runCommand("item replace entity @a armor.chest with diamond_chestplate[damage=400,enchantments={\"minecraft:mending\":1}]");
        sp.getServer().runCommand("item replace entity @a armor.head with diamond_helmet[enchantments={\"minecraft:mending\":1}]");
        sp.getServer().runCommand("item replace entity @a hotbar.2 with experience_bottle 64");
        ctx.waitTicks(10);
        int before = ctx.computeOnClient(c -> c.player.getEquippedStack(EquipmentSlot.CHEST).getDamage());
        enable(ctx, AutoMend.class);
        ctx.waitTicks(100);
        disable(ctx, AutoMend.class);
        ctx.waitTicks(10);
        int after = ctx.computeOnClient(c -> c.player.getEquippedStack(EquipmentSlot.CHEST).getDamage());
        check(after < before, "AutoMend repairs Mending armour (damage " + before + " -> " + after + ")");
        // Helmet was full durability, so it should have been stripped and then put back on.
        enable(ctx, AutoMend.class);
        ctx.waitTicks(10);
        disable(ctx, AutoMend.class);
        boolean helmet = ctx.computeOnClient(c -> c.player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET));
        check(helmet, "AutoMend leaves the helmet equipped afterwards");
    }

    private void testReach(ClientGameTestContext ctx) {
        double vanilla = ctx.computeOnClient(c -> c.player.getEntityInteractionRange());
        enable(ctx, Reach.class);
        ctx.waitTicks(2);
        double extended = ctx.computeOnClient(c -> c.player.getEntityInteractionRange());
        disable(ctx, Reach.class);
        check(extended > vanilla, "Reach extends entity range (" + vanilla + " -> " + extended + ")");
    }

    private void testTriggerBotAndHitbox(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("clear @a");
        sp.getServer().runCommand("item replace entity @a hotbar.0 with netherite_sword");
        sp.getServer().runCommand("kill @e[type=!player]");
        sp.getServer().runCommand("tp @a 0 -60 0 0 0");
        sp.getServer().runCommand("summon zombie 0 -60 2.5 {NoAI:1b,Health:20f,PersistenceRequired:1b}");
        ctx.waitTicks(20);

        float hitboxBefore = ctx.computeOnClient(c -> zombies(c).getFirst().getTargetingMargin());
        Hitbox hitbox = enable(ctx, Hitbox.class);
        setBool(ctx, hitbox, "Mobs", true);
        float hitboxAfter = ctx.computeOnClient(c -> zombies(c).getFirst().getTargetingMargin());
        check(hitboxAfter > hitboxBefore, "Hitbox expands the targeting margin");

        TriggerBot tb = enable(ctx, TriggerBot.class);
        setBool(ctx, tb, "Mobs", true);
        ctx.waitTicks(60);
        float zombieHealth = ctx.computeOnClient(c -> {
            var list = zombies(c);
            return list.isEmpty() ? 0f : list.getFirst().getHealth();
        });
        disable(ctx, TriggerBot.class);
        check(zombieHealth < 20f, "TriggerBot attacks the target in the crosshair (zombie hp " + zombieHealth + ")");
    }

    private static java.util.List<net.minecraft.entity.mob.ZombieEntity> zombies(net.minecraft.client.MinecraftClient c) {
        return c.world.getEntitiesByClass(net.minecraft.entity.mob.ZombieEntity.class, c.player.getBoundingBox().expand(8), z -> z.isAlive());
    }

    private void testAimAssist(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("kill @e[type=!player]");
        sp.getServer().runCommand("tp @a 0 -60 0 0 0");
        // Zombie 30 degrees to the side.
        sp.getServer().runCommand("summon zombie -1.2 -60 2 {NoAI:1b,PersistenceRequired:1b}");
        ctx.waitTicks(20);
        AimAssist aa = enable(ctx, AimAssist.class);
        setBool(ctx, aa, "Mobs", true);
        setBool(ctx, aa, "Require Click", false);
        float before = ctx.computeOnClient(c -> c.player.getYaw());
        ctx.waitTicks(40);
        float after = ctx.computeOnClient(c -> c.player.getYaw());
        disable(ctx, AimAssist.class);
        check(Math.abs(after - before) > 5, "AimAssist turns towards the target (yaw " + before + " -> " + after + ")");
        boolean onTarget = ctx.computeOnClient(c -> c.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult);
        check(onTarget, "AimAssist ends with the crosshair on the target");
    }

    private void testVisuals(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 10");
        server.runCommand("setblock 3 -60 6 chest");
        server.runCommand("setblock -3 -60 6 ender_chest");
        server.runCommand("setblock 0 -60 12 red_shulker_box");
        server.runCommand("fill 6 -60 9 7 -60 9 barrel");
        server.runCommand("summon zombie 0 -60 4 {NoAI:1b,PersistenceRequired:1b}");
        server.runCommand("item replace entity @a armor.head with netherite_helmet[damage=200]");
        server.runCommand("item replace entity @a armor.chest with diamond_chestplate[damage=420]");
        server.runCommand("item replace entity @a armor.legs with netherite_leggings");
        server.runCommand("item replace entity @a armor.feet with diamond_boots[damage=100]");
        server.runCommand("item replace entity @a weapon.offhand with totem_of_undying");
        server.runCommand("item replace entity @a hotbar.1 with ender_pearl 16");
        server.runCommand("item replace entity @a hotbar.2 with wind_charge 32");
        server.runCommand("item replace entity @a hotbar.3 with golden_apple 12");
        server.runCommand("item replace entity @e[type=zombie] armor.head with iron_helmet");
        server.runCommand("item replace entity @e[type=zombie] armor.chest with diamond_chestplate[damage=300]");
        server.runCommand("item replace entity @e[type=zombie] weapon.mainhand with iron_spear");
        server.runCommand("effect give @a absorption 120 1");
        ctx.waitTicks(20);

        ctx.runOnClient(c -> {
            c.player.getItemCooldownManager().set(new ItemStack(Items.ENDER_PEARL), 400);
            c.player.getItemCooldownManager().set(new ItemStack(Items.WIND_CHARGE), 200);
        });
        enable(ctx, ChestESP.class);
        Hitbox hb = enable(ctx, Hitbox.class);
        setBool(ctx, hb, "Mobs", true);
        setBool(ctx, hb, "Show Boxes", true);
        enable(ctx, ChunkLocator.class);
        ctx.waitTicks(10);
        // Hit the zombie once so TargetHUD shows it as the fight target.
        ctx.runOnClient(c -> {
            var z = zombies(c).getFirst();
            net.evadeclient.util.TargetTracker.onAttack(z);
        });
        ctx.waitTicks(30);
        ctx.takeScreenshot("evade-world");

        ctx.setScreen(ClickGuiScreen::get);
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-clickgui");
        ctx.setScreen(() -> null);
        check(true, "visual screenshots taken");
    }
}
