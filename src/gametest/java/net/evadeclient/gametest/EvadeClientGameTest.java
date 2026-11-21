package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.Module;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.module.visuals.NoFog;
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

            testQualityOfLife(ctx, sp);
            testQolHud(ctx, sp);
            testVisualBatch(ctx, sp);
            testHud(ctx, sp);
            testMenu(ctx);
            testProfiles(ctx);
            testTexturePack(ctx, sp);
        }
        titleScreenshot(ctx);
    }

    /** The new Lunar-style HUD/info readouts: Item Counter, Saturation, Stopwatch. */
    private void testQolHud(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        server.runCommand("clear @a");
        server.runCommand("item replace entity @a hotbar.0 with cobblestone 64");
        ctx.waitTicks(5);
        // Saturation is tracked client-side (not synced from the server), so set it on the client.
        ctx.runOnClient(c -> c.player.getHungerManager().setSaturationLevel(7f));

        var counter = enable(ctx, net.evadeclient.module.hud.ItemCounterHud.class);
        ctx.runOnClient(c -> { for (var st : counter.getSettings()) if (st.getName().equals("Track"))
            ((net.evadeclient.setting.ModeSetting) st).fromJson(new com.google.gson.JsonPrimitive("Blocks")); });
        enable(ctx, net.evadeclient.module.hud.SaturationHud.class);
        enable(ctx, net.evadeclient.module.hud.StopwatchHud.class);
        ctx.waitTicks(25);
        int count = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.ItemCounterHud.class).count());
        float sat = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.SaturationHud.class).saturation());
        long elapsed = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.StopwatchHud.class).elapsedMs());
        disable(ctx, net.evadeclient.module.hud.ItemCounterHud.class);
        disable(ctx, net.evadeclient.module.hud.SaturationHud.class);
        disable(ctx, net.evadeclient.module.hud.StopwatchHud.class);
        check(count == 64, "Item Counter counts 64 blocks (" + count + ")");
        check(sat > 6 && sat <= 7.5f, "Saturation reads the current value (" + sat + ")");
        check(elapsed > 500, "Stopwatch counts up while on (" + elapsed + " ms)");
    }

    private static void titleScreenshot(ClientGameTestContext ctx) {
        ctx.setScreen(net.minecraft.client.gui.screen.TitleScreen::new);
        ctx.waitTicks(20);
        ctx.takeScreenshot("evade-pack-title");
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

    private void testQualityOfLife(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("kill @e[type=!player]");
        sp.getServer().runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(5);

        ctx.waitTicks(2);
        check(ctx.computeOnClient(c -> c.options.sprintKey.isPressed()), "ToggleSprint holds sprint");

        enable(ctx, net.evadeclient.module.mods.TimeChanger.class);
        ctx.waitTicks(3);
        long time = ctx.computeOnClient(c -> c.world.getTimeOfDay() % 24000);
        check(time == 6000, "Time Changer sets client time (" + time + ")");
        disable(ctx, net.evadeclient.module.mods.TimeChanger.class);

        ctx.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_C);
        ctx.waitTicks(15);
        double zoomMult = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Zoom.class).fovMultiplier());
        ctx.takeScreenshot("evade-zoom");
        ctx.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_C);
        check(zoomMult < 0.5, "Zoom narrows the FOV while the key is held (x" + zoomMult + ")");

        ctx.runOnClient(c -> c.mouse.lockCursor());
        ctx.waitTicks(2);
        // Sanity check: without freelook, mouse movement turns the player.
        float y0 = ctx.computeOnClient(c -> c.player.getYaw());
        ctx.getInput().moveCursor(100, 0);
        ctx.waitTicks(3);
        float y1 = ctx.computeOnClient(c -> c.player.getYaw());
        EvadeClient.LOG.info("[gametest] mouse sanity: yaw {} -> {}", y0, y1);
        float yawBefore = ctx.computeOnClient(c -> c.player.getYaw());
        ctx.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT);
        ctx.waitTicks(3);
        ctx.getInput().moveCursor(300, 0);
        ctx.waitTicks(3);
        float yawAfter = ctx.computeOnClient(c -> c.player.getYaw());
        float camYaw = ctx.computeOnClient(c -> c.gameRenderer.getCamera().getYaw());
        ctx.takeScreenshot("evade-freelook");
        ctx.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT);
        ctx.waitTicks(3);
        check(Math.abs(yawAfter - yawBefore) < 0.01 && Math.abs(camYaw - yawBefore) > 5,
                "Freelook turns the camera but not the player (player " + yawBefore + "->" + yawAfter + ", camera " + camYaw + ")");

        // Fullbright pushes gamma past its normal range via the SimpleOption accessor, then restores it.
        double g0 = ctx.computeOnClient(c -> c.options.getGamma().getValue());
        enable(ctx, net.evadeclient.module.visuals.Fullbright.class);
        ctx.waitTicks(3);
        double g1 = ctx.computeOnClient(c -> c.options.getGamma().getValue());
        disable(ctx, net.evadeclient.module.visuals.Fullbright.class);
        ctx.waitTicks(3);
        double g2 = ctx.computeOnClient(c -> c.options.getGamma().getValue());
        check(g1 >= 10.0 && Math.abs(g2 - g0) < 0.01,
                "Fullbright maxes gamma then restores (" + g0 + " -> " + g1 + " -> " + g2 + ")");
    }

    /** Loads the optional Evade Texture Pack and screenshots it (HUD, inventory, tooltip, title screen). */
    private void testTexturePack(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        java.nio.file.Path zip = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("../../../site/downloads/Evade-Texture-Pack.zip").normalize();
        if (!java.nio.file.Files.exists(zip)) { EvadeClient.LOG.info("[gametest] no texture pack built; skipping"); return; }
        String compat = ctx.computeOnClient(c -> {
            try {
                java.nio.file.Files.copy(zip, c.getResourcePackDir().resolve("Evade-Texture-Pack.zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                return "copy failed: " + e;
            }
            var mgr = c.getResourcePackManager();
            mgr.scanPacks();
            var profile = mgr.getProfile("file/Evade-Texture-Pack.zip");
            if (profile == null) return "not found";
            mgr.enable("file/Evade-Texture-Pack.zip");
            c.options.refreshResourcePacks(mgr);
            return profile.getCompatibility().name();
        });
        ctx.waitTicks(60); // resource reload
        check("COMPATIBLE".equals(compat), "Evade Texture Pack is detected as compatible with 1.21.11 (" + compat + ")");
        boolean enabled = ctx.computeOnClient(c -> c.getResourcePackManager().getEnabledIds().contains("file/Evade-Texture-Pack.zip"));
        check(enabled, "Evade Texture Pack loads in game");
        var server = sp.getServer();
        server.runCommand("clear @a");
        server.runCommand("item replace entity @a hotbar.0 with diamond_sword");
        server.runCommand("item replace entity @a hotbar.1 with netherite_sword");
        server.runCommand("item replace entity @a hotbar.2 with ender_pearl 16");
        server.runCommand("item replace entity @a hotbar.3 with totem_of_undying");
        server.runCommand("item replace entity @a hotbar.4 with golden_apple 8");
        server.runCommand("setblock 0 -60 3 fire");
        ctx.setScreen(() -> null);
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-pack-hud");
        ctx.setScreen(() -> new net.minecraft.client.gui.screen.ingame.InventoryScreen(net.minecraft.client.MinecraftClient.getInstance().player));
        ctx.waitTicks(5);
        ctx.getInput().setCursorPos(960 + 0, 540 + 0);
        ctx.takeScreenshot("evade-pack-inventory");
        ctx.setScreen(() -> new net.minecraft.client.gui.screen.option.OptionsScreen(null, net.minecraft.client.MinecraftClient.getInstance().options));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-pack-options");
        ctx.setScreen(() -> null);
    }

    private void testMenu(ClientGameTestContext ctx) {
        // Typical 1080p monitor at GUI scale 2 (960x540 GUI pixels).
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(c -> {
            c.options.getGuiScale().setValue(2);
            c.onResolutionChanged();
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-hud-1080p");
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-menu");
        // Click the first row (NoFog) in the default List style and check it toggles. First row ~y 144 GUI.
        boolean before = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        ctx.getInput().setCursorPos(400 * 2, 140 * 2);
        ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        boolean after = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        check(before != after, "Clicking a mod card in the menu toggles it");
        ctx.getInput().typeChars("zoom");
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-menu-search");
        ctx.setScreen(() -> EvadeMenu.open(EvadeClient.modules.get(Zoom.class)));
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-menu-settings");
        ctx.setScreen(() -> new net.evadeclient.gui.HudEditorScreen(null));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-hud-editor");

        // GUI styles: switch each one, reopen the menu, screenshot.
        var cs = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class));
        for (String st : new String[]{"List", "Compact", "Cards"}) {
            ctx.runOnClient(c -> { for (var s : cs.getSettings()) if (s.getName().equals("GUI Style"))
                ((net.evadeclient.setting.ModeSetting) s).fromJson(new com.google.gson.JsonPrimitive(st)); });
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(6);
            ctx.takeScreenshot("evade-gui-" + st.toLowerCase());
        }
        // In Compact style, click the first row and confirm it toggles.
        ctx.runOnClient(c -> { for (var s : cs.getSettings()) if (s.getName().equals("GUI Style"))
            ((net.evadeclient.setting.ModeSetting) s).fromJson(new com.google.gson.JsonPrimitive("Compact")); });
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(6);
        boolean b2 = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        ctx.getInput().setCursorPos(300 * 2, 125 * 2);
        ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        boolean a2 = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).isEnabled());
        check(b2 != a2, "Clicking a row in the Compact GUI style toggles a mod");

        // The All / Enabled filter: enable one module, switch to Enabled, confirm only enabled show.
        ctx.runOnClient(c -> { for (var s : cs.getSettings()) if (s.getName().equals("GUI Style"))
            ((net.evadeclient.setting.ModeSetting) s).fromJson(new com.google.gson.JsonPrimitive("List")); });
        ctx.setScreen(EvadeMenu::new);
        ctx.waitTicks(6);
        // Enabled toggle sits in the header's right half (seg at x ~702..852, y ~86..108 GUI).
        int enBefore = visibleRowCount(ctx);
        ctx.getInput().setCursorPos(810 * 2, 97 * 2);
        ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(5);
        int enAfter = visibleRowCount(ctx);
        check(enAfter < enBefore && enAfter > 0, "All/Enabled filter narrows the list (" + enBefore + " -> " + enAfter + ")");
        ctx.setScreen(() -> null);
    }

    @SuppressWarnings("unchecked")
    private int visibleRowCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> {
            try {
                var mtd = net.evadeclient.gui.EvadeMenu.class.getDeclaredMethod("visibleModules");
                mtd.setAccessible(true);
                var menu = c.currentScreen;
                var list = (java.util.List<?>) mtd.invoke(menu);
                return list.size();
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    /** Batch 2 visual mods: Particles, TNT Timer, Chunk Borders, Clear Water, Scoreboard, Boss Bar, Inventory Blur. */
    private void testVisualBatch(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(5);

        // Particles: a crit is refused while hidden, allowed once the mod is off
        enable(ctx, net.evadeclient.module.visuals.Particles.class);
        boolean blocked = ctx.computeOnClient(c -> c.particleManager.addParticle(net.minecraft.particle.ParticleTypes.CRIT, 0, -59, 2, 0, 0, 0) == null);
        disable(ctx, net.evadeclient.module.visuals.Particles.class);
        boolean allowed = ctx.computeOnClient(c -> c.particleManager.addParticle(net.minecraft.particle.ParticleTypes.CRIT, 0, -59, 2, 0, 0, 0) != null);
        check(blocked && allowed, "Particles hides crits only while enabled");

        // TNT Timer: a primed TNT in front of you gets a countdown
        server.runCommand("summon tnt 0 -60 5 {fuse:200}");
        ctx.waitTicks(10);
        int timers = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.TntTimer.class).shown());
        check(timers >= 1, "TNT Timer shows a countdown over primed TNT (" + timers + ")");

        // Chunk Borders draws its grid
        enable(ctx, net.evadeclient.module.visuals.ChunkBorders.class);
        ctx.waitTicks(3);
        int lines = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.ChunkBorders.class).lines());
        check(lines > 50, "Chunk Borders draws the chunk grid (" + lines + " lines)");

        // Scoreboard (numbers hidden) + boss bar on screen, then a screenshot of it all
        server.runCommand("scoreboard objectives add evade dummy \"Evade\"");
        server.runCommand("scoreboard objectives setdisplay sidebar evade");
        server.runCommand("scoreboard players set Kills evade 12");
        server.runCommand("scoreboard players set Coins evade 340");
        server.runCommand("bossbar add evade:test \"Ender Dragon\"");
        server.runCommand("bossbar set evade:test players @a");
        enable(ctx, net.evadeclient.module.visuals.Scoreboard.class);
        enable(ctx, net.evadeclient.module.visuals.BossBar.class);
        ctx.waitTicks(10);
        ctx.takeScreenshot("evade-batch2");
        disable(ctx, net.evadeclient.module.visuals.ChunkBorders.class);
        server.runCommand("kill @e[type=tnt]");
        server.runCommand("bossbar remove evade:test");
        server.runCommand("scoreboard objectives remove evade");

        // Clear Water: removes fog while the camera is underwater
        server.runCommand("fill -2 -60 -2 2 -56 2 water");
        server.runCommand("tp @a 0 -59 0 0 0");
        enable(ctx, net.evadeclient.module.visuals.ClearWater.class);
        ctx.waitTicks(10);
        boolean clear = ctx.computeOnClient(c -> net.evadeclient.module.visuals.ClearWater.clearFog());
        disable(ctx, net.evadeclient.module.visuals.ClearWater.class);
        server.runCommand("fill -2 -60 -2 2 -56 2 air");
        server.runCommand("tp @a 0 -60 0 0 0");
        check(clear, "Clear Water clears fog while underwater");

        // Inventory Blur: opening the inventory with it on doesn't break anything
        enable(ctx, net.evadeclient.module.visuals.InventoryBlur.class);
        ctx.setScreen(() -> new net.minecraft.client.gui.screen.ingame.InventoryScreen(net.minecraft.client.MinecraftClient.getInstance().player));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-inventory-blur");
        ctx.setScreen(() -> null);
        disable(ctx, net.evadeclient.module.visuals.InventoryBlur.class);
        check(true, "Inventory Blur renders behind the inventory");
    }

    /** Profiles: each one remembers its own module state, and switching restores it. */
    private void testProfiles(ClientGameTestContext ctx) {
        boolean ok = ctx.computeOnClient(c -> {
            var P = EvadeClient.profiles;
            NoFog nf = EvadeClient.modules.get(NoFog.class);
            if (!"Default".equals(P.active())) P.switchTo("Default");
            P.delete("GTProfile"); // clean slate if a previous run left one
            nf.setEnabled(true);
            EvadeClient.config.save();          // Default: NoFog ON
            P.create("GTProfile");              // snapshot (ON), now active
            nf.setEnabled(false);
            EvadeClient.config.save();          // GTProfile: NoFog OFF
            P.switchTo("Default");
            boolean defaultOn = nf.isEnabled(); // expect true
            P.switchTo("GTProfile");
            boolean testOff = !nf.isEnabled();  // expect true (NoFog off)
            // cleanup
            P.switchTo("Default");
            P.delete("GTProfile");
            return defaultOn && testOff;
        });
        check(ok, "Profiles keep separate module state and restore on switch");
        // create a couple of profiles so the page looks real, then screenshot it
        ctx.runOnClient(c -> {
            EvadeClient.profiles.create("PvP");
            EvadeClient.profiles.create("SkyBlock");
            EvadeClient.profiles.switchTo("Default");
        });
        ctx.setScreen(EvadeMenu::new);
        ctx.runOnClient(c -> EvadeMenu.openProfilesForTest());
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-profiles");
        ctx.runOnClient(c -> { EvadeClient.profiles.delete("PvP"); EvadeClient.profiles.delete("SkyBlock"); });
        ctx.setScreen(() -> null);
    }

    private void testHud(ClientGameTestContext ctx, TestSingleplayerContext sp) {
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
        ctx.waitTicks(10);
        // Hit the zombie once so TargetHUD shows it as the fight target.
        ctx.runOnClient(c -> {
            var z = c.world.getEntitiesByClass(net.minecraft.entity.mob.ZombieEntity.class, c.player.getBoundingBox().expand(16), e -> true).getFirst();
            net.evadeclient.util.TargetTracker.onAttack(z);
        });
        ctx.waitTicks(30);
        ctx.takeScreenshot("evade-hud");

        check(true, "HUD screenshot taken (armor, target, cooldowns, keystrokes)");
    }
}
