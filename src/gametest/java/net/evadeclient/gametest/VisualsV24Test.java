package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.title.TitleBackground;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.hud.ConnectionWatchHud;
import net.evadeclient.module.hud.NetworkHud;
import net.evadeclient.module.hud.PaperDollHud;
import net.evadeclient.module.hud.PingGraphHud;
import net.evadeclient.module.hud.TpsHud;
import net.evadeclient.module.misc.TitleScreenModule;
import net.evadeclient.module.mods.Crosshair;
import net.evadeclient.module.visuals.DamageNumbers;
import net.evadeclient.module.visuals.ItemView;
import net.evadeclient.module.visuals.OldAnimations;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.HitConfirm;
import net.evadeclient.util.NetStats;
import net.evadeclient.util.Theme;
import net.evadeclient.util.ThemeStudio;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * v2.4 visuals and widgets: Item View (offsets, visual-only swing speed, shield hiding), the deeper 1.7 Animations,
 * Server TPS, Connection Watch, Network, Ping Graph, Paper Doll, the Title Screen backgrounds, theme codes, and
 * confirmed-hit Damage Numbers with the crosshair hit marker.
 */
public final class VisualsV24Test {
    private VisualsV24Test() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    @SuppressWarnings("unchecked")
    private static <S extends Setting<?>> S setting(net.evadeclient.module.Module m, String name) {
        for (Setting<?> s : m.getSettings()) if (s.getName().equals(name)) return (S) s;
        throw new AssertionError("No setting " + name + " on " + m.getName());
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        itemView(ctx, sp);
        oldAnimations(ctx, sp);
        widgets(ctx);
        hits(ctx, sp);
        titleBackgrounds(ctx);
        themes(ctx);
    }

    // ------------------------------------------------------------------ Item View

    private static void itemView(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with diamond_sword");
        sp.getServer().runCommand("item replace entity @a weapon.offhand with shield");
        ctx.waitTicks(3);
        float[] before = ctx.computeOnClient(c -> {
            ItemView iv = EvadeClient.modules.get(ItemView.class);
            iv.setEnabled(true);
            iv.swingSpeed.set(50.0);
            iv.mainX.set(-0.1);
            iv.mainScale.set(130.0);
            iv.offY.set(-0.1);
            return new float[]{c.player.getAttackCooldownProgress(0.5f), iv.visualSwing(0.1f, 6)};
        });
        ctx.waitTicks(2);
        float[] after = ctx.computeOnClient(c -> {
            ItemView iv = EvadeClient.modules.get(ItemView.class);
            return new float[]{iv.visualSwing(0.433f, 6), c.player.getAttackCooldownProgress(0.5f)};
        });
        check(after[0] > before[1] && after[0] < 0.433f,
                "Item View: Swing Speed 50% plays the swing slower than the game's (" + before[1] + " -> " + after[0] + " vs 0.433)");
        check(after[1] >= before[0], "Item View: the attack cooldown is untouched by Swing Speed (" + before[0] + " -> " + after[1] + ")");
        boolean[] hide = ctx.computeOnClient(c -> {
            ItemView iv = EvadeClient.modules.get(ItemView.class);
            ItemStack shield = c.player.getOffHandStack();
            iv.shield.set("Hide When Idle");
            boolean idle = iv.hides(c.player, Hand.OFF_HAND, shield);
            iv.shield.set("Normal");
            boolean normal = iv.hides(c.player, Hand.OFF_HAND, shield);
            boolean sword = iv.hides(c.player, Hand.MAIN_HAND, c.player.getMainHandStack());
            return new boolean[]{idle, normal, sword};
        });
        check(hide[0] && !hide[1] && !hide[2], "Item View: Shield Hide When Idle hides only the idle shield");
        ctx.waitTicks(2);
        ctx.takeScreenshot("v24-item-view");
        ctx.runOnClient(c -> {
            ItemView iv = EvadeClient.modules.get(ItemView.class);
            for (Setting<?> s : iv.getSettings()) s.reset();
            iv.setEnabled(false);
        });
    }

    // ------------------------------------------------------------------ 1.7 Animations

    private static void oldAnimations(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        boolean[] block = ctx.computeOnClient(c -> {
            OldAnimations o = EvadeClient.modules.get(OldAnimations.class);
            o.setEnabled(true);
            o.oldRod.set(true);
            o.oldBow.set(true);
            o.oldHurtTilt.set(true);
            o.oldSneak.set(true);
            o.oldHealthFlash.set(true);
            boolean idle = OldAnimations.swordBlocking(c.player);
            // Raise the shield on this client only (test setup; nothing is sent).
            c.player.setCurrentHand(Hand.OFF_HAND);
            return new boolean[]{idle, OldAnimations.swordBlocking(c.player)};
        });
        check(!block[0] && block[1], "1.7 Animations: Sword Block applies only while the off-hand shield is raised");
        ctx.waitTicks(2);
        ctx.takeScreenshot("v24-sword-block");
        ctx.runOnClient(c -> c.player.clearActiveItem());
        // Hurt (red armour, hurt tilt, heart flash) and sneak with every option on: frames must draw without errors.
        sp.getServer().runCommand("item replace entity @a armor.chest with iron_chestplate");
        sp.getServer().runCommand("damage @a 1 minecraft:generic");
        ctx.waitTicks(1);
        ctx.takeScreenshot("v24-old-hurt");
        ctx.runOnClient(c -> c.player.setSneaking(true));
        ctx.waitTicks(3);
        float[] eye = ctx.computeOnClient(c -> new float[]{
                (float) (c.gameRenderer.getCamera().getCameraPos().y - c.player.getY()), c.player.getStandingEyeHeight()});
        ctx.runOnClient(c -> c.player.setSneaking(false));
        check(eye[1] > 0, "1.7 Animations: sneak camera follows the eye height (" + eye[0] + " / " + eye[1] + ")");
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with fishing_rod");
        ctx.waitTicks(2);
        ctx.takeScreenshot("v24-old-rod");
        sp.getServer().runCommand("item replace entity @a armor.chest with air");
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with air");
        sp.getServer().runCommand("item replace entity @a weapon.offhand with air");
        sp.getServer().runCommand("effect give @a instant_health 1 5");
        ctx.runOnClient(c -> {
            OldAnimations o = EvadeClient.modules.get(OldAnimations.class);
            for (Setting<?> s : o.getSettings()) s.reset();
            o.setEnabled(false);
        });
        ctx.waitTicks(2);
    }

    // ------------------------------------------------------------------ HUD widgets

    private static void widgets(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            for (Class<? extends net.evadeclient.module.Module> t : List.of(TpsHud.class, ConnectionWatchHud.class,
                    NetworkHud.class, PingGraphHud.class, PaperDollHud.class))
                EvadeClient.modules.get(t).setEnabled(true);
        });
        ctx.waitTicks(40);
        String tps = ctx.computeOnClient(c -> EvadeClient.modules.get(TpsHud.class).textForTest().toString());
        check(tps.contains("TPS (est.)") && tps.matches(".*\\d.*"), "Server TPS: labelled as an estimate with a value (" + tps + ")");
        String net = ctx.computeOnClient(c -> EvadeClient.modules.get(NetworkHud.class).textForTest().toString());
        check(net.contains("In:") && net.contains("Out:") && net.contains("p/s"), "Network: packets in and out per second (" + net + ")");

        String quiet = ctx.computeOnClient(c -> EvadeClient.modules.get(ConnectionWatchHud.class).textForTest().toString());
        check(quiet.equals("[]"), "Connection Watch: hidden while the server is talking (and in singleplayer by default)");
        String warn = ctx.computeOnClient(c -> {
            ConnectionWatchHud cw = EvadeClient.modules.get(ConnectionWatchHud.class);
            net.evadeclient.setting.BooleanSetting sp = setting(cw, "In Singleplayer");
            sp.set(true);
            String out = "";
            // The built-in server keeps sending, so measure right after pretending it went quiet.
            for (int i = 0; i < 20 && !out.contains("not responding"); i++) {
                NetStats.fakeLastReceived(5.0);
                out = cw.textForTest().toString();
            }
            sp.set(false);
            return out;
        });
        check(warn.contains("Server not responding: 5."), "Connection Watch: \"Server not responding\" with the seconds count (" + warn + ")");

        ctx.runOnClient(c -> {
            PingGraphHud pg = EvadeClient.modules.get(PingGraphHud.class);
            pg.push(40);
            pg.push(60);
            pg.push(80);
        });
        ctx.waitTicks(3);
        String header = ctx.computeOnClient(c -> EvadeClient.modules.get(PingGraphHud.class).headerForTest());
        check(header.contains("Ping 80ms") && header.contains("avg 60") && header.contains("max 80") && header.contains("jitter 20"),
                "Ping Graph: current, average, worst and jitter from its samples (" + header + ")");
        ctx.takeScreenshot("v24-widgets");
        ctx.runOnClient(c -> {
            for (Class<? extends net.evadeclient.module.Module> t : List.of(TpsHud.class, ConnectionWatchHud.class,
                    NetworkHud.class, PingGraphHud.class, PaperDollHud.class))
                EvadeClient.modules.get(t).setEnabled(false);
        });
    }

    // ------------------------------------------------------------------ Damage Numbers and hit marker

    private static void hits(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.runOnClient(c -> {
            HitConfirm.clear();
            EvadeClient.modules.get(DamageNumbers.class).setEnabled(true);
            EvadeClient.modules.get(Crosshair.class).setEnabled(true);
            EvadeClient.modules.get(Crosshair.class).hitMarker.set(true);
            c.player.setYaw(0);
            c.player.setPitch(10);
        });
        sp.getServer().runCommand("execute as @p at @s run summon pig ~ ~ ~2 {NoAI:1b}");
        ctx.waitTicks(30);
        boolean attacked = ctx.computeOnClient(c -> {
            Entity pig = null;
            for (Entity e : c.world.getEntities()) if (e instanceof PigEntity) pig = e;
            if (pig == null) return false;
            c.interactionManager.attackEntity(c.player, pig);
            c.player.swingHand(Hand.MAIN_HAND);
            return true;
        });
        check(attacked, "Damage Numbers: a pig to hit is in the world");
        ctx.waitTicks(4);
        Object[] got = ctx.computeOnClient(c -> {
            List<HitConfirm.Hit> recent = HitConfirm.recent(3000);
            DamageNumbers dn = EvadeClient.modules.get(DamageNumbers.class);
            String label = recent.isEmpty() ? "" : dn.label(recent.getLast());
            return new Object[]{recent.size(), label, EvadeClient.modules.get(Crosshair.class).hitMarkerShowing()
                    || HitConfirm.sinceLastHit() < 3000};
        });
        check((int) got[0] == 1 && "1".equals(got[1]), "Damage Numbers: the server-confirmed hit shows your swing's damage (" + got[0] + " hits, \"" + got[1] + "\")");
        check((boolean) got[2], "Crosshair: the hit marker follows the confirmed hit");
        ctx.takeScreenshot("v24-damage-number");
        String marker = ctx.computeOnClient(c -> EvadeClient.modules.get(DamageNumbers.class)
                .label(new HitConfirm.Hit(1, c.player.getEntityPos(), System.currentTimeMillis(), true, -1, false)));
        check(marker.equals("×"), "Damage Numbers: a projectile hit gets a hit marker, not a number");
        sp.getServer().runCommand("kill @e[type=pig]");
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(DamageNumbers.class).setEnabled(false);
            EvadeClient.modules.get(Crosshair.class).hitMarker.set(false);
            HitConfirm.clear();
        });
    }

    // ------------------------------------------------------------------ Title Screen backgrounds

    private static void titleBackgrounds(ClientGameTestContext ctx) {
        Path img = TitleBackground.folder().resolve("zz-evade-test.png");
        try (NativeImage n = new NativeImage(64, 36, false)) {
            for (int x = 0; x < 64; x++) for (int y = 0; y < 36; y++) n.setColorArgb(x, y, 0xFF000000 | (x * 4) << 16 | (y * 7) << 8 | 0x60);
            n.writeTo(img);
        } catch (Exception e) {
            throw new AssertionError("Couldn't write a test background", e);
        }
        String migrated = ctx.computeOnClient(c -> {
            TitleScreenModule t = EvadeClient.modules.get(TitleScreenModule.class);
            com.google.gson.JsonObject old = new com.google.gson.JsonObject();
            old.addProperty("Panorama", false);
            t.onConfigLoaded(old);
            String m = t.background.get();
            t.background.reset();
            return m;
        });
        check(migrated.equals("Evade Grid"), "Title Screen: an old \"Panorama off\" config becomes Background Evade Grid");
        for (String mode : new String[]{"Evade Animated", "Solid Color", "Custom Image"}) {
            ctx.runOnClient(c -> {
                TitleScreenModule t = EvadeClient.modules.get(TitleScreenModule.class);
                t.background.set(mode);
                if (mode.equals("Custom Image")) t.imageFile.set("zz-evade-test.png");
                c.setScreen(new net.evadeclient.gui.EvadeTitleScreen());
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("v24-title-" + mode.toLowerCase().replace(' ', '-'));
        }
        boolean loaded = ctx.computeOnClient(c -> TitleBackground.imageLoaded());
        check(loaded, "Title Screen: Custom Image loads a .png from config/evade/backgrounds");
        ctx.runOnClient(c -> {
            c.setScreen(null);
            TitleScreenModule t = EvadeClient.modules.get(TitleScreenModule.class);
            t.background.reset();
            t.imageFile.reset();
        });
        try {
            Files.deleteIfExists(img);
        } catch (Exception ignored) {
            // Left behind in the test run's config only.
        }
    }

    // ------------------------------------------------------------------ theme codes

    private static void themes(ClientGameTestContext ctx) {
        Object[] r = ctx.computeOnClient(c -> {
            ClientSettings cs = EvadeClient.modules.get(ClientSettings.class);
            String bad = ThemeStudio.importCode(cs, "EVTH1:Nope,Red,E11D2E,0,0,0");
            String ok = ThemeStudio.importCode(cs, "EVTH1:Midnight,Custom,12AB34,0,202030,0");
            int accent = Theme.RED, card = Theme.CARD;
            String code = ThemeStudio.export(cs);
            String theme = cs.theme.get();
            return new Object[]{bad, ok, accent, card, code, theme};
        });
        check(r[0] != null, "Theme codes: an unknown preset is refused (" + r[0] + ")");
        check(r[1] == null && (int) r[2] == 0xFF12AB34 && (int) r[3] == 0xFF202030 && "Midnight".equals(r[5]),
                "Theme codes: EVTH1 applies the preset, a custom accent and a card colour (" + r[1] + ")");
        check("EVTH1:Midnight,Custom,12AB34,0,202030,0".equals(r[4]), "Theme codes: export gives the same code back (" + r[4] + ")");
        ctx.runOnClient(c -> c.setScreen(new net.evadeclient.gui.EvadeMenu()));
        ctx.waitTicks(5);
        ctx.takeScreenshot("v24-custom-theme");
        ctx.runOnClient(c -> {
            c.setScreen(null);
            ClientSettings cs = EvadeClient.modules.get(ClientSettings.class);
            cs.theme.reset();
            cs.accent.reset();
            cs.customAccent.reset();
            cs.windowColor.reset();
            cs.cardColor.reset();
            cs.textColor.reset();
            cs.applyTheme();
        });
        int red = ctx.computeOnClient(c -> Theme.RED);
        check(red == 0xFFE11D2E, "Theme codes: back to the default theme afterwards");
    }
}
