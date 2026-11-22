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
            // Performance is on by default; tests start without it (its particle budget would make them random)
            // and turn it on where they test it.
            disable(ctx, net.evadeclient.module.mods.Performance.class);
            String addons = ctx.computeOnClient(c -> net.evadeclient.module.mods.Performance.installedAddons());
            check(addons.startsWith("All") && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("moreculling"),
                    "Performance mods load alongside Evade (" + addons + ", MoreCulling too)");

            testQualityOfLife(ctx, sp);
            testQolHud(ctx, sp);
            testHudCost(ctx); // early, before anything triggers a chunk rebuild that would skew the timing
            testVisualBatch(ctx, sp);
            testRenderBatch(ctx, sp);
            testPerformance(ctx, sp);
            testFrameTime(ctx);
            testClips(ctx, sp);
            testVoice(ctx);
            testHud(ctx, sp);
            testMenu(ctx);
            testProfiles(ctx);
            testIcons(ctx, sp);
        }
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
        testThemesAndClips(ctx, cs);

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
        server.runCommand("fill 98 -61 98 102 -56 102 glass");
        server.runCommand("fill 99 -60 99 101 -56 101 water");
        server.runCommand("tp @a 100 -59 100 0 0");
        enable(ctx, net.evadeclient.module.visuals.ClearWater.class);
        ctx.waitTicks(10);
        boolean clear = ctx.computeOnClient(c -> net.evadeclient.module.visuals.ClearWater.clearFog());
        disable(ctx, net.evadeclient.module.visuals.ClearWater.class);
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

    /** Deeper render mods: Nick Hider, Hit Color, ShinyPots, EnchantGlint, Item Physics, 1.7 Animations. */
    private void testRenderBatch(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 30");
        ctx.waitTicks(3);

        // Nick Hider: your real name is swapped wherever it's drawn
        String shown = ctx.computeOnClient(c -> {
            var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
            nh.nick.set("Streamer");
            nh.setEnabled(true);
            StringBuilder b = new StringBuilder();
            net.minecraft.text.Text.literal("<" + c.getSession().getUsername() + "> hi").asOrderedText()
                    .accept((i, style, cp) -> { b.appendCodePoint(cp); return true; });
            return b.toString();
        });
        check(shown.equals("<Streamer> hi"), "Nick Hider replaces your name on screen (" + shown + ")");
        disable(ctx, net.evadeclient.module.visuals.NickHider.class);

        // Hit Color repaints the hurt overlay, and restores red when turned off
        enable(ctx, net.evadeclient.module.visuals.HitColor.class);
        ctx.waitTicks(3);
        int painted = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.HitColor.class).current());
        disable(ctx, net.evadeclient.module.visuals.HitColor.class);
        int restored = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.HitColor.class).current());
        check((painted & 0xFFFFFF) == 0x3B82F6 && restored == 0xB2FF0000,
                "Hit Color repaints the hurt flash and restores it (" + Integer.toHexString(painted) + " -> " + Integer.toHexString(restored) + ")");

        // ShinyPots: potions glint only while it's on
        boolean[] glint = ctx.computeOnClient(c -> {
            var pot = net.minecraft.item.ItemStack.EMPTY;
            pot = net.minecraft.item.Items.POTION.getDefaultStack();
            pot.set(net.minecraft.component.DataComponentTypes.POTION_CONTENTS,
                    new net.minecraft.component.type.PotionContentsComponent(net.minecraft.potion.Potions.STRENGTH));
            boolean before = pot.hasGlint();
            var sp2 = EvadeClient.modules.get(net.evadeclient.module.visuals.ShinyPots.class);
            sp2.setEnabled(true);
            boolean after = pot.hasGlint();
            sp2.setEnabled(false);
            return new boolean[]{before, after};
        });
        check(!glint[0] && glint[1], "ShinyPots makes potions glint");

        // EnchantGlint swaps in a recoloured glint texture, and restores the original on disable
        enable(ctx, net.evadeclient.module.visuals.EnchantGlint.class);
        ctx.waitTicks(3);
        boolean tinted = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.visuals.EnchantGlint.class).isApplied());

        // Item Physics + 1.7 Animations + glint, all on screen at once
        server.runCommand("summon item 0 -60 2 {Item:{id:\"minecraft:diamond_sword\",count:1},PickupDelay:32767}");
        server.runCommand("summon item 1 -60 2.5 {Item:{id:\"minecraft:golden_apple\",count:1},PickupDelay:32767}");
        server.runCommand("summon item -1 -60 2.5 {Item:{id:\"minecraft:stone\",count:1},PickupDelay:32767}");
        server.runCommand("item replace entity @a weapon.mainhand with diamond_sword[enchantments={sharpness:5}]");
        enable(ctx, net.evadeclient.module.visuals.ItemPhysics.class);
        enable(ctx, net.evadeclient.module.visuals.OldAnimations.class);
        ctx.waitTicks(20);
        ctx.takeScreenshot("evade-render-batch");
        disable(ctx, net.evadeclient.module.visuals.EnchantGlint.class);
        ctx.waitTicks(2);
        boolean restoredGlint = ctx.computeOnClient(c -> !EvadeClient.modules.get(net.evadeclient.module.visuals.EnchantGlint.class).isApplied());
        check(tinted && restoredGlint, "EnchantGlint recolours the glint and restores it");
        disable(ctx, net.evadeclient.module.visuals.ItemPhysics.class);
        disable(ctx, net.evadeclient.module.visuals.OldAnimations.class);
        server.runCommand("kill @e[type=item]");
        server.runCommand("clear @a");
        check(true, "Item Physics and 1.7 Animations render without errors");
    }

    /** Evade's own HUD cost: with every HUD element on, it should stay a small slice of a frame. */
    private void testHudCost(ClientGameTestContext ctx) {
        java.util.List<String> turnedOn = ctx.computeOnClient(c -> {
            java.util.List<String> on = new java.util.ArrayList<>();
            for (var el : EvadeClient.modules.hudElements()) if (!el.isEnabled()) { el.setEnabled(true); on.add(el.getName()); }
            return on;
        });
        int all = ctx.computeOnClient(c -> EvadeClient.modules.hudElements().size());
        ctx.waitTicks(20);
        ctx.runOnClient(c -> { net.evadeclient.util.FrameStats.takeHudMicros(); net.evadeclient.hud.Hud.PROFILE = new java.util.HashMap<>(); });
        ctx.waitTicks(100);
        double us = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
        String top = ctx.computeOnClient(c -> {
            var p = net.evadeclient.hud.Hud.PROFILE; net.evadeclient.hud.Hud.PROFILE = null;
            long total = p.values().stream().mapToLong(Long::longValue).sum();
            return p.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                    .map(e -> e.getKey() + " " + (100 * e.getValue() / Math.max(1, total)) + "%").reduce((a, b) -> a + ", " + b).orElse("");
        });
        EvadeClient.LOG.info("[gametest] HUDPROFILE {}", top);
        StringBuilder ab = new StringBuilder();
        for (int round = 0; round < 2; round++) for (boolean noCache : new boolean[]{true, false}) {
            ctx.runOnClient(c -> { net.evadeclient.module.TextHudModule.NO_CACHE = noCache; net.evadeclient.util.FrameStats.takeHudMicros(); });
            ctx.waitTicks(80);
            double t = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.takeHudMicros());
            ab.append(noCache ? " everyFrame=" : " perTick=").append(String.format(java.util.Locale.ROOT, "%.0fus", t));
        }
        ctx.runOnClient(c -> net.evadeclient.module.TextHudModule.NO_CACHE = false);
        EvadeClient.LOG.info("[gametest] CACHEAB{}", ab);
        ctx.runOnClient(c -> { for (var el : EvadeClient.modules.hudElements()) if (turnedOn.contains(el.getName())) el.setEnabled(false); });
        EvadeClient.LOG.info("[gametest] HUDCOST {} HUD elements on: {} us per frame", all, String.format(java.util.Locale.ROOT, "%.0f", us));
        check(us > 0 && us < 4000, String.format(java.util.Locale.ROOT, "Evade's HUD stays light with all %d elements on (%.0f us per frame)", all, us));
    }

    /** Frame Time Graph: frames are being recorded and summarised. */
    private void testFrameTime(ClientGameTestContext ctx) {
        enable(ctx, net.evadeclient.module.hud.FrameTimeHud.class);
        ctx.waitTicks(60);
        int frames = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.count());
        float[] s = ctx.computeOnClient(c -> net.evadeclient.util.FrameStats.summary(net.evadeclient.util.FrameStats.SIZE));
        ctx.takeScreenshot("evade-frametime");
        disable(ctx, net.evadeclient.module.hud.FrameTimeHud.class);
        check(frames > 60 && s[0] > 1 && s[1] > 0 && s[1] <= s[0] + 0.5f && s[2] > 0,
                String.format(java.util.Locale.ROOT, "Frame Time Graph tracks frames (%d frames, %.0f FPS avg, %.0f 1%% low, %.1f ms worst)", frames, s[0], s[1], s[2]));
    }

    /** Performance: benchmark FPS with 400 distant mobs, culling off vs on, plus the lighter-settings preset. */
    private void testPerformance(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        for (int x = -20; x < 20; x += 2) for (int z = 70; z < 110; z += 2)
            server.runCommand("summon cow " + x + " -60 " + z + " {NoAI:1b,Silent:1b}");
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        ctx.waitTicks(100);
        int fpsOff = ctx.computeOnClient(c -> c.getCurrentFps());

        var perf = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Performance.class));
        setBool(ctx, perf, "Low FPS When Tabbed Out", false); // the test window may not have focus
        setBool(ctx, perf, "Lighter Video Settings", false);  // measure culling on its own
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        ctx.runOnClient(c -> perf.takeCulled());
        ctx.waitTicks(100);
        int fpsOn = ctx.computeOnClient(c -> c.getCurrentFps());
        int culled = ctx.computeOnClient(c -> perf.takeCulled());
        EvadeClient.LOG.info("[gametest] BENCHMARK 400 cows at 70-110 blocks: {} FPS off -> {} FPS on ({} entity draws skipped)", fpsOff, fpsOn, culled);
        ctx.takeScreenshot("evade-performance");
        check(culled > 0, "Performance culls distant entities (" + fpsOff + " -> " + fpsOn + " FPS)");

        // far block entities are skipped, near ones aren't
        boolean be = ctx.computeOnClient(c -> perf.cullBlockEntity(new net.minecraft.util.math.BlockPos(0, -60, 90))
                && !perf.cullBlockEntity(new net.minecraft.util.math.BlockPos(0, -60, 5)));
        check(be, "Performance culls far block entities only");

        // lighter video settings apply on enable and restore on disable
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Lighter Video Settings", true);
        ctx.runOnClient(c -> c.options.getCloudRenderMode().setValue(net.minecraft.client.option.CloudRenderMode.FANCY));
        var before = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        var during = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        var after = ctx.computeOnClient(c -> c.options.getCloudRenderMode().getValue());
        check(before == net.minecraft.client.option.CloudRenderMode.FANCY && during == net.minecraft.client.option.CloudRenderMode.OFF && after == before,
                "Performance's lighter settings apply and restore (" + before + " -> " + during + " -> " + after + ")");
        // Low-End PC Mode: applies the heavy video settings and restores them exactly.
        Object[] before2 = ctx.computeOnClient(c -> new Object[]{c.options.getAo().getValue(), c.options.getCutoutLeaves().getValue(), c.options.getBiomeBlendRadius().getValue()});
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Low-End PC Mode", true);
        ctx.waitTicks(3);
        Object[] during2 = ctx.computeOnClient(c -> new Object[]{c.options.getAo().getValue(), c.options.getCutoutLeaves().getValue(), c.options.getBiomeBlendRadius().getValue()});
        ctx.takeScreenshot("evade-lowend");
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        setBool(ctx, perf, "Low-End PC Mode", false);
        Object[] after2 = ctx.computeOnClient(c -> new Object[]{c.options.getAo().getValue(), c.options.getCutoutLeaves().getValue(), c.options.getBiomeBlendRadius().getValue()});
        check(Boolean.FALSE.equals(during2[0]) && Boolean.FALSE.equals(during2[1]) && Integer.valueOf(0).equals(during2[2])
                        && java.util.Arrays.equals(before2, after2),
                "Low-End PC Mode applies and restores video settings (" + java.util.Arrays.toString(before2) + " -> "
                        + java.util.Arrays.toString(during2) + " -> " + java.util.Arrays.toString(after2) + ")");

        // AFK cap: two minutes without input drops to the AFK frame rate; any input lifts it straight away.
        enable(ctx, net.evadeclient.module.mods.Performance.class);
        int[] afk = ctx.computeOnClient(c -> {
            var lim = c.getInactivityFpsLimiter();
            try { lim.getClass().getMethod("evade$pretendIdle", long.class).invoke(lim, 120_000L); }
            catch (Exception e) { throw new AssertionError(e); }
            int idle = lim.update();
            lim.onInput();
            return new int[]{idle, lim.update()};
        });
        disable(ctx, net.evadeclient.module.mods.Performance.class);
        check(afk[0] <= 15 && afk[1] > 15, "Performance idles at a low FPS when AFK and recovers on input (" + afk[0] + " -> " + afk[1] + ")");
        setBool(ctx, perf, "Low FPS When Tabbed Out", true);
        server.runCommand("kill @e[type=cow]");
    }

    /** Clips: record a few seconds, save to MP4, then decode the file back to prove it's real video. */
    private void testClips(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("kill @e[type=!player]");
        server.runCommand("tp @a 0 -60 0 0 0");
        server.runCommand("summon tnt 0 -60 6 {fuse:400}");
        var clips = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.misc.Clips.class));
        ctx.runOnClient(c -> {
            for (var st : clips.getSettings()) {
                if (st.getName().equals("Quality")) ((net.evadeclient.setting.ModeSetting) st).fromJson(new com.google.gson.JsonPrimitive("480p"));
                if (st.getName().equals("Clip FPS")) ((net.evadeclient.setting.NumberSetting) st).set(15.0);
            }
            clips.setEnabled(true);
        });
        ctx.waitTicks(100); // ~5 seconds of footage
        int buffered = ctx.computeOnClient(c -> clips.bufferedFrames());
        var future = ctx.computeOnClient(c -> clips.save());
        for (int i = 0; i < 120 && !future.isDone(); i++) ctx.waitTicks(20);
        java.io.File file = future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
        String info = "none";
        boolean ok = false;
        if (file != null && file.length() > 10_000) {
            try {
                var frame = org.jcodec.api.FrameGrab.getFrameFromFile(file, 10);
                info = file.length() / 1024 + " KB, frame " + frame.getWidth() + "x" + frame.getHeight();
                ok = frame.getHeight() == 480;
            } catch (Exception e) { info = "decode failed: " + e; }
        }
        ctx.runOnClient(c -> clips.setEnabled(false));
        server.runCommand("kill @e[type=tnt]");
        check(buffered > 20 && ok, "Clips saves a playable MP4 (" + buffered + " frames buffered, " + info + ")");
    }

    /** Voice: Simple Voice Chat is installed alongside Evade, this server runs it, and Evade's shortcut opens its menu. */
    private void testVoice(ClientGameTestContext ctx) {
        boolean installed = ctx.computeOnClient(c -> net.evadeclient.module.misc.VoiceChat.installed());
        boolean onServer = ctx.computeOnClient(c -> net.evadeclient.module.misc.VoiceChat.serverHasVoice());
        ctx.runOnClient(c -> net.evadeclient.module.misc.VoiceChat.openMenu());
        ctx.waitTicks(5);
        String screen = ctx.computeOnClient(c -> c.currentScreen == null ? "none" : c.currentScreen.getClass().getName());
        ctx.setScreen(() -> null);
        check(installed && onServer && screen.startsWith("de.maxhenkel.voicechat"),
                "Voice chat: Simple Voice Chat runs here and Evade opens its menu (" + screen.substring(screen.lastIndexOf('.') + 1) + ")");
    }

    /** Evade Icons: a verified Evade user's tab-list name gets the icon glyph, and the tab list shows it. */
    private void testIcons(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String[] names = ctx.computeOnClient(c -> {
            var me = c.player.getUuid();
            var entry = c.getNetworkHandler().getPlayerListEntry(me);
            var hud = c.inGameHud.getPlayerListHud();
            String plain = hud.getPlayerName(entry).getString();
            net.evadeclient.module.misc.EvadeIcons.putForTest(me, true);
            String evade = hud.getPlayerName(entry).getString();
            return new String[]{plain, evade};
        });
        var server = sp.getServer();
        server.runCommand("scoreboard objectives add evadelist dummy");
        server.runCommand("scoreboard objectives setdisplay list evadelist");
        ctx.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-tab-icons");
        ctx.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
        server.runCommand("scoreboard objectives remove evadelist");
        ctx.runOnClient(c -> net.evadeclient.module.misc.EvadeIcons.putForTest(c.player.getUuid(), false));
        check(!names[0].startsWith("\ue000") && names[1].equals("\ue000 " + names[0]),
                "Evade Icons puts the Evade logo before Evade users' tab names (\"" + names[0] + "\" -> logo + name)");
    }

    private static void setMode(net.evadeclient.module.Module m, String name, String value) {
        for (var s : m.getSettings()) if (s.getName().equals(name))
            ((net.evadeclient.setting.ModeSetting) s).fromJson(new com.google.gson.JsonPrimitive(value));
    }

    /**
     * Theme presets and accent colours (screenshots for the site), then two short clips for the website: the menu
     * opening, hovering, toggling and changing theme/style, and the HUD editor dragging an element. Animations run
     * on a fixed 30 fps clock here so the recordings are smooth whatever the test machine's speed.
     */
    private void testThemesAndClips(ClientGameTestContext ctx, net.evadeclient.module.client.ClientSettings cs) {
        ctx.runOnClient(c -> setMode(cs, "GUI Style", "List"));
        String[][] looks = {{"Midnight", "Red"}, {"OLED", "Purple"}, {"Classic", "Green"}, {"Modern", "Blue"}};
        for (String[] l : looks) {
            ctx.runOnClient(c -> { setMode(cs, "Theme", l[0]); setMode(cs, "Accent Color", l[1]); });
            ctx.setScreen(EvadeMenu::new);
            ctx.waitTicks(6);
            ctx.takeScreenshot("evade-theme-" + l[0].toLowerCase());
        }
        int[] seen = ctx.computeOnClient(c -> {
            setMode(cs, "Theme", "Classic"); setMode(cs, "Accent Color", "Blue"); cs.applyTheme();
            int classicRed = net.evadeclient.util.Theme.RED, classicSq = net.evadeclient.util.Theme.square ? 1 : 0, classicCard = net.evadeclient.util.Theme.CARD;
            setMode(cs, "Theme", "Modern"); setMode(cs, "Accent Color", "Red"); cs.applyTheme();
            return new int[]{classicRed, classicSq, classicCard, net.evadeclient.util.Theme.RED, net.evadeclient.util.Theme.square ? 1 : 0, net.evadeclient.util.Theme.CARD};
        });
        check(seen[0] == 0xFF2F7BFF && seen[1] == 1 && seen[3] == 0xFFE11D2E && seen[4] == 0 && seen[2] != seen[5],
                "Theme presets and accent colours restyle the client (Classic + Blue -> square corners, blue accent; back to Modern + Red)");

        // --- clip 1: the menu
        java.util.concurrent.atomic.AtomicLong t = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        EvadeMenu.clock = t::get;
        var in = ctx.getInput();
        Runnable frame = () -> { t.addAndGet(33); ctx.takeScreenshot("clip-menu"); };
        try {
            in.setCursorPos(1880, 1040);
            ctx.setScreen(EvadeMenu::new);
            for (int i = 0; i < 14; i++) frame.run();
            for (int i = 0; i <= 30; i++) { in.setCursorPos(800, (140 + i * 4) * 2); frame.run(); }
            in.setCursorPos(800, 140 * 2);
            for (int i = 0; i < 6; i++) frame.run();
            in.pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 16; i++) frame.run();
            in.pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
            for (int i = 0; i < 14; i++) frame.run();
            in.setCursorPos(1880, 1040);
            String[][] steps = {{"Theme", "Midnight"}, {"Accent Color", "Purple"}, {"Theme", "Classic"}, {"Accent Color", "Green"},
                    {"Theme", "OLED"}, {"Accent Color", "Blue"}, {"GUI Style", "Cards"}, {"GUI Style", "Compact"},
                    {"Theme", "Modern"}, {"Accent Color", "Red"}, {"GUI Style", "List"}};
            for (String[] st : steps) {
                ctx.runOnClient(c -> setMode(cs, st[0], st[1]));
                for (int i = 0; i < 18; i++) frame.run();
            }
        } finally {
            EvadeMenu.clock = System::currentTimeMillis;
        }

        // --- clip 2: the HUD editor (drag an element, watch it snap, resize it, put it back)
        ctx.setScreen(() -> new net.evadeclient.gui.HudEditorScreen(null));
        ctx.waitTicks(3);
        int[] el = ctx.computeOnClient(c -> {
            var w = c.getWindow();
            for (var e : EvadeClient.modules.hudElements()) if (e.isEnabled() && e.scaledWidth() > 30)
                return new int[]{EvadeClient.modules.hudElements().indexOf(e), e.x, e.y,
                        e.resolveX(w.getScaledWidth()) + e.scaledWidth() / 2, e.resolveY(w.getScaledHeight()) + e.scaledHeight() / 2,
                        Math.round(e.scale.getFloat() * 100)};
            return null;
        });
        Runnable shot = () -> ctx.takeScreenshot("clip-hud");
        int sx = el[3] * 2, sy = el[4] * 2;
        in.setCursorPos(sx, sy);
        for (int i = 0; i < 10; i++) shot.run();
        in.holdMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        int[][] path = {{960, 540}, {1500, 300}, {sx, sy}};
        int px = sx, py = sy;
        for (int[] to : path) {
            for (int i = 1; i <= 30; i++) {
                double k = i / 30.0, e = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
                in.setCursorPos(px + (to[0] - px) * e, py + (to[1] - py) * e);
                shot.run();
            }
            for (int i = 0; i < 8; i++) shot.run();
            px = to[0]; py = to[1];
        }
        in.releaseMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 0; i < 4; i++) { in.scroll(1); shot.run(); shot.run(); }
        for (int i = 0; i < 4; i++) { in.scroll(-1); shot.run(); shot.run(); }
        for (int i = 0; i < 10; i++) shot.run();
        ctx.runOnClient(c -> {
            var e = EvadeClient.modules.hudElements().get(el[0]);
            e.x = el[1]; e.y = el[2];
            e.scale.fromJson(new com.google.gson.JsonPrimitive(el[5] / 100.0));
        });
        ctx.setScreen(() -> null);
        ctx.runOnClient(c -> setMode(cs, "GUI Style", "Compact"));
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
