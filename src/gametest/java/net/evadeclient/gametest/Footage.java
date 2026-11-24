package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Films real Minecraft gameplay for the Evade Clips screenshots and site: a few staged PvP scenes in a normal world,
 * played from the player's view with the real HUD. Every frame is rendered on its own (with in-between tick
 * positions for smooth 30 fps), so the footage is smooth however slow the machine is.
 * <p>
 * Run with {@code scripts/film-footage.sh}; frames go to build/footage/&lt;scene&gt;/ and are encoded to MP4 there.
 * Opponents are mannequins (player-shaped, with the game's built-in skins) moved by commands.
 */
final class Footage {
    private static final int W = 1280, H = 720;
    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext sp;
    private final Path root = Path.of(System.getProperty("evade.footage.dir", "../footage")).toAbsolutePath();
    private Path dir;
    private int frame, tick;

    private Footage(ClientGameTestContext ctx, TestSingleplayerContext sp) { this.ctx = ctx; this.sp = sp; }

    static void film(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(c -> {
            c.setSeed("evadeclips");
            c.setCheatsEnabled(true);
            c.setGenerateStructures(false);
        }).create()) {
            new Footage(ctx, sp).run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void cmd(String c) {
        try { sp.getServer().runCommand(c); } catch (Throwable t) { System.out.println("[footage] command failed: " + c + " (" + t.getMessage() + ")"); }
    }

    /** A flat, open spot in the plains nearest (x, z): no trees overhead, no water, no terrain edits needed. */
    private BlockPos spot(int x0, int z0) {
        BlockPos p = sp.getServer().computeOnServer(server -> {
            var w = server.getOverworld();
            var found = w.locateBiome(b -> b.matchesKey(net.minecraft.world.biome.BiomeKeys.PLAINS)
                    || b.matchesKey(net.minecraft.world.biome.BiomeKeys.SUNFLOWER_PLAINS)
                    || b.matchesKey(net.minecraft.world.biome.BiomeKeys.MEADOW), new BlockPos(x0, 80, z0), 3000, 32, 64);
            BlockPos c = found != null ? found.getFirst() : new BlockPos(x0, 80, z0);
            // generate the area first: unloaded chunks have no heightmap, so every spot would look bad
            for (int cx = (c.getX() - 80) >> 4; cx <= (c.getX() + 80) >> 4; cx++)
                for (int cz = (c.getZ() - 80) >> 4; cz <= (c.getZ() + 80) >> 4; cz++) w.getChunk(cx, cz);
            BlockPos best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int dx0 = -64; dx0 <= 64; dx0 += 8) for (int dz0 = -64; dz0 <= 64; dz0 += 8) {
                int x = c.getX() + dx0, z = c.getZ() + dz0;
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                int lo = y, hi = y;
                boolean bad = false;
                for (int dx = -12; dx <= 12 && !bad; dx += 3) for (int dz = -12; dz <= 12 && !bad; dz += 3) {
                    int yy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                    BlockPos g = new BlockPos(x + dx, yy - 1, z + dz);
                    bad = !w.getBlockState(g).isOf(Blocks.GRASS_BLOCK) || !w.getFluidState(g).isEmpty()
                            || w.getTopY(Heightmap.Type.MOTION_BLOCKING, x + dx, z + dz) != yy; // leaves overhead
                    lo = Math.min(lo, yy); hi = Math.max(hi, yy);
                }
                if (!bad && hi - lo < bestScore) { bestScore = hi - lo; best = new BlockPos(x, y, z); }
            }
            System.out.println("[footage] spot near " + x0 + "," + z0 + ": " + best + " (height range " + bestScore + ")");
            return best != null ? best : c.withY(w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()));
        });
        // short grass and flowers stay; only clear the few blocks where the action is
        return p;
    }

    private void player(BlockPos p, float yaw, String time) {
        cmd("kill @e[type=!player]");
        cmd("time set " + time);
        cmd(String.format("tp @p %d.5 %d %d.5 %.1f 5", p.getX(), p.getY(), p.getZ(), yaw));
        cmd("clear @p");
        cmd("effect clear @p");
        String[][] armor = {{"head", "netherite_helmet"}, {"chest", "netherite_chestplate"}, {"legs", "netherite_leggings"}, {"feet", "netherite_boots"}};
        for (String[] a : armor) cmd("item replace entity @p armor." + a[0] + " with " + a[1] + "[enchantments={protection:4}]");
        cmd("item replace entity @p hotbar.0 with netherite_sword[enchantments={sharpness:5}]");
        cmd("item replace entity @p hotbar.1 with ender_pearl 16");
        cmd("item replace entity @p hotbar.2 with end_crystal 64");
        cmd("item replace entity @p hotbar.3 with obsidian 64");
        cmd("item replace entity @p hotbar.4 with golden_apple 23");
        cmd("item replace entity @p hotbar.5 with white_wool 64");
        cmd("item replace entity @p hotbar.6 with cooked_beef 48");
        cmd("item replace entity @p hotbar.7 with water_bucket");
        cmd("item replace entity @p hotbar.8 with totem_of_undying");
        cmd("item replace entity @p weapon.offhand with totem_of_undying");
        cmd("effect give @p resistance infinite 4 true");
        cmd("effect give @p regeneration infinite 2 true");
        cmd("effect give @p saturation infinite 0 true");
        sp.getServer().runOnServer(server -> {
            for (var pl : server.getPlayerManager().getPlayerList()) {
                pl.setHealth(pl.getMaxHealth());
                pl.getHungerManager().setFoodLevel(20);
            }
        });
        ctx.runOnClient(c -> { c.player.getInventory().setSelectedSlot(0); release(c); });
        ctx.waitTicks(5);
        ctx.waitTicks(140); // let the terrain around the new spot finish building
        ctx.runOnClient(c -> c.inGameHud.getChatHud().clear(false));
    }

    /** A player-shaped opponent. */
    private void foe(double x, double y, double z, String name, String skin, String armor, boolean totem) {
        String eq = String.format("head:{id:\"%1$s_helmet\",count:1},chest:{id:\"%1$s_chestplate\",count:1},legs:{id:\"%1$s_leggings\",count:1},feet:{id:\"%1$s_boots\",count:1},mainhand:{id:\"%2$s_sword\",count:1}",
                armor, armor.equals("netherite") ? "netherite" : "diamond");
        if (totem) eq += ",offhand:{id:\"totem_of_undying\",count:1}";
        cmd(String.format(java.util.Locale.ROOT, "summon mannequin %.2f %.2f %.2f {Tags:[\"foe\"],CustomName:\"%s\",CustomNameVisible:1b,hide_description:1b,"
                + "profile:{texture:\"entity/player/wide/%s\"},attributes:[{id:\"minecraft:max_health\",base:44}],Health:44f,equipment:{%s}}", x, y, z, name, skin, eq));
    }

    /** Opponents face you and walk towards you until they're in sword range. */
    private int chaseTick;
    private boolean foesHit = true;

    private void chase(double speed) {
        if (++chaseTick % 17 == 0 && foesHit) cmd("execute as @e[tag=foe,sort=nearest,limit=1] at @s if entity @p[distance=..3.3] run damage @p 3 minecraft:mob_attack by @s");
        cmd("execute as @e[tag=foe] at @s run rotate @s facing entity @p eyes");
        if (speed > 0) cmd("execute as @e[tag=foe] at @s if entity @p[distance=3.1..] facing entity @p feet run tp @s ^ ^ ^" + speed);
        else if (foesHit) cmd("execute as @e[tag=foe] at @s if entity @p[distance=..2.2] facing entity @p feet run tp @s ^ ^ ^-0.08");
    }

    private static void release(MinecraftClient c) {
        c.options.forwardKey.setPressed(false);
        c.options.backKey.setPressed(false);
        c.options.leftKey.setPressed(false);
        c.options.rightKey.setPressed(false);
        c.options.jumpKey.setPressed(false);
        c.options.sprintKey.setPressed(false);
    }

    private static List<LivingEntity> foes(MinecraftClient c) {
        return c.world.getEntitiesByClass(LivingEntity.class, c.player.getBoundingBox().expand(40),
                e -> e.getType() == EntityType.MANNEQUIN && e.isAlive() && e.getHealth() > 0);
    }

    /** Turns the camera towards a point, at most {@code step} degrees per tick (like a mouse flick). */
    private static float aim(MinecraftClient c, Vec3d at, float step) {
        Vec3d eye = c.player.getEyePos();
        Vec3d d = at.subtract(eye);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI);
        float dy = MathHelper.wrapDegrees(yaw - c.player.getYaw());
        float dp = pitch - c.player.getPitch();
        c.player.setYaw(c.player.getYaw() + MathHelper.clamp(dy * 0.45f, -step, step));
        c.player.setPitch(c.player.getPitch() + MathHelper.clamp(dp * 0.45f, -step, step));
        return Math.abs(dy);
    }

    /** One tick of fighting the nearest opponent: chase, strafe, jump for crits and swing when the sword is charged. */
    private void fight(MinecraftClient c, int t) {
        var all = foes(c);
        if (all.isEmpty()) { release(c); return; }
        LivingEntity f = all.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(c.player))).get();
        double dist = Math.sqrt(f.squaredDistanceTo(c.player));
        float off = aim(c, f.getEyePos().add(0, -0.15, 0), 20f);
        boolean far = dist > 3.6;
        c.options.forwardKey.setPressed(dist > 3.0);
        c.options.backKey.setPressed(dist < 2.3);
        c.options.sprintKey.setPressed(far);
        boolean close = dist < 4.5;
        c.options.leftKey.setPressed(close && (t / 14) % 2 == 0);
        c.options.rightKey.setPressed(close && (t / 14) % 2 == 1);
        float cd = c.player.getAttackCooldownProgress(0);
        c.options.jumpKey.setPressed(close && cd > 0.55f && cd < 0.8f && c.player.isOnGround());
        if (dist < 3.6 && off < 20 && cd >= 0.95f && !c.player.isOnGround() && c.player.getVelocity().y < 0 || dist < 3.4 && off < 20 && cd >= 1f && t % 4 == 0) {
            c.interactionManager.attackEntity(c.player, f);
            c.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Films {@code ticks} game ticks to frames, calling {@code each} before every tick. 3 frames per 2 ticks = 30 fps. */
    private boolean stopWhenClear = true;

    private void shoot(String name, int ticks, IntConsumer each) throws Exception {
        dir = root.resolve(name);
        Files.createDirectories(dir);
        try (var old = Files.list(dir)) { for (Path f : old.toList()) Files.delete(f); }
        frame = 0;
        long t0 = System.currentTimeMillis();
        int quiet = 0;
        for (tick = 0; tick < ticks && quiet < 32; tick++) {
            if (tick > 40 && stopWhenClear) quiet = ctx.computeOnClient(c -> foes(c).isEmpty()) ? quiet + 1 : 0;
            each.accept(tick);
            ctx.waitTick();
            float[] deltas = tick % 2 == 0 ? new float[]{0f, 0.6667f} : new float[]{0.3333f};
            for (float d : deltas) {
                ctx.takeScreenshot(TestScreenshotOptions.of(String.format("f%05d", frame++)).disableCounterPrefix()
                        .withDestinationDir(dir).withSize(W, H).withTickDelta(d));
            }
        }
        ctx.runOnClient(Footage::release);
        System.out.println("[footage] " + name + ": " + frame + " frames in " + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    // ------------------------------------------------------------------ scenes

    private void run() throws Exception {
        cmd("gamerule doDaylightCycle false");
        cmd("gamerule advance_time false");
        cmd("gamerule doWeatherCycle false");
        cmd("gamerule advance_weather false");
        cmd("gamerule doMobSpawning false");
        cmd("gamerule spawn_mobs false");
        cmd("gamerule doImmediateRespawn true");
        cmd("gamerule sendCommandFeedback false");
        cmd("gamerule send_command_feedback false");
        cmd("gamerule commandBlockOutput false");
        cmd("gamerule command_block_output false");
        cmd("gamerule logAdminCommands false");
        cmd("gamerule log_admin_commands false");
        cmd("weather clear");
        cmd("difficulty peaceful");
        ctx.runOnClient(c -> {
            var perf = EvadeClient.modules.get(net.evadeclient.module.mods.Performance.class);
            if (perf != null) perf.setEnabled(false);
            // The plain game HUD: hotbar, hearts and armor (the FPS counter would show this headless renderer's speed).
            for (var m : EvadeClient.modules.all())
                if (m.getCategory() == net.evadeclient.module.Category.HUD && m.isEnabled()) m.setEnabled(false);
            for (String n : new String[]{"ToggleSprint", "Toggle Sprint", "Keystrokes", "CPS", "FPS", "Coordinates", "ArrayList", "Module List"}) {
                var m = EvadeClient.modules.get(n);
                if (m != null && m.isEnabled()) m.setEnabled(false);
            }
            c.options.getFov().setValue(90);
            c.options.getViewDistance().setValue(8);
            c.options.getBobView().setValue(true);
        });
        // Film at the window's real size, so the HUD and crosshair are laid out for 1280x720.
        ctx.getInput().resizeWindow(W, H);
        ctx.waitTicks(20);
        System.out.println("[footage] window " + ctx.computeOnClient(c -> c.getWindow().getFramebufferWidth() + "x" + c.getWindow().getFramebufferHeight()));
        String only = System.getProperty("evade.footage.only", "");

        if (only.isEmpty() || only.contains("clutch")) clutch();
        if (only.isEmpty() || only.contains("crystal")) crystal();
        if (only.isEmpty() || only.contains("bridge")) bridge();
        if (only.isEmpty() || only.contains("pearl")) pearl();
        if (only.isEmpty() || only.contains("totem")) totem();
        if (only.isEmpty() || only.contains("sunset")) sunset();
    }

    /** 1v3: three players rush you; you take them one at a time. */
    private void clutch() throws Exception {
        BlockPos p = spot(0, 0);
        player(p, 0, "1500");
        foe(p.getX() - 3.5, p.getY(), p.getZ() + 8, "Kai", "kai", "iron", false);
        foe(p.getX() + 0.5, p.getY(), p.getZ() + 10, "Zuri", "zuri", "iron", false);
        foe(p.getX() + 4.5, p.getY(), p.getZ() + 9, "Efe", "efe", "iron", false);
        shoot("clutch", 300, t -> { chase(0.2); ctx.runOnClient(c -> fight(c, t)); });
    }

    /** Crystal PvP: a crystal on the obsidian between you, popped the moment it lands; their totems go, then they do. */
    private void crystal() throws Exception {
        BlockPos p = spot(400, 0);
        player(p, 0, "3000");
        int x = p.getX(), y = p.getY(), z = p.getZ();
        cmd(String.format("fill %d %d %d %d %d %d obsidian", x - 3, y - 1, z - 2, x + 3, y - 1, z + 6));
        cmd(String.format("fill %d %d %d %d %d %d air", x - 3, y, z - 2, x + 3, y + 3, z + 6));
        foe(x + 0.5, y, z + 4.2, "Steve", "steve", "netherite", true);
        BlockPos pad = new BlockPos(x, y - 1, z + 2);
        double homeZ = z + 0.5;
        shoot("crystal", 230, t -> {
            chase(0.14);
            if (t % 46 == 45 && t < 180) cmd("item replace entity @e[tag=foe] weapon.offhand with totem_of_undying");
            int cycle = t % 46;
            ctx.runOnClient(c -> {
                var foes = foes(c);
                // walk back to your spot after the blast pushes you
                c.options.forwardKey.setPressed(c.player.getZ() < homeZ - 0.4 && cycle > 24);
                Vec3d look = cycle < 20 ? Vec3d.ofCenter(pad).add(0, 0.5, 0) : foes.isEmpty() ? Vec3d.ofCenter(pad).add(0, 1, 0) : foes.get(0).getEyePos();
                aim(c, look, 18f);
                if (cycle == 12) c.player.getInventory().setSelectedSlot(2);
                else if (cycle == 18) {
                    c.interactionManager.interactBlock(c.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(pad).add(0, 0.5, 0), Direction.UP, pad, false));
                    c.player.swingHand(Hand.MAIN_HAND);
                } else if (cycle == 19) c.player.getInventory().setSelectedSlot(0);
                else if (cycle >= 20 && cycle <= 30 && cycle % 2 == 0) {
                    var crystals = c.world.getEntitiesByClass(EndCrystalEntity.class, c.player.getBoundingBox().expand(8), e -> true);
                    if (!crystals.isEmpty()) {
                        c.interactionManager.attackEntity(c.player, crystals.get(0));
                        c.player.swingHand(Hand.MAIN_HAND);
                    }
                } else if (cycle > 32 && !foes.isEmpty() && foes.get(0).squaredDistanceTo(c.player) < 13 && c.player.getAttackCooldownProgress(0) >= 1f) {
                    c.interactionManager.attackEntity(c.player, foes.get(0));
                    c.player.swingHand(Hand.MAIN_HAND);
                }
            });
        });
    }

    /** A bridge fight high above the ground: they come at you across the bridge and get knocked off. */
    private void bridge() throws Exception {
        BlockPos g = spot(-400, 200);
        BlockPos p = g.up(34);
        cmd(String.format("fill %d %d %d %d %d %d white_wool", p.getX(), p.getY() - 1, p.getZ() - 4, p.getX(), p.getY() - 1, p.getZ() + 26));
        cmd(String.format("fill %d %d %d %d %d %d red_wool", p.getX() - 2, p.getY() - 1, p.getZ() + 27, p.getX() + 2, p.getY() - 1, p.getZ() + 31));
        cmd(String.format("fill %d %d %d %d %d %d blue_wool", p.getX() - 2, p.getY() - 1, p.getZ() - 9, p.getX() + 2, p.getY() - 1, p.getZ() - 5));
        player(p, 0, "2000");
        cmd("item replace entity @p hotbar.0 with netherite_sword[enchantments={sharpness:5,knockback:2}]");
        foe(p.getX() + 0.5, p.getY(), p.getZ() + 14.5, "Alex", "alex", "diamond", false);
        foesHit = false; // one knock and you'd be off the bridge yourself
        shoot("bridge", 170, t -> {
            chase(0.18);
            ctx.runOnClient(c -> {
                var foes = foes(c);
                if (foes.isEmpty() || foes.get(0).getY() < c.player.getY() - 3) {
                    release(c);
                    if (!foes.isEmpty()) aim(c, foes.get(0).getEyePos(), 6f); // watch them fall
                    return;
                }
                LivingEntity f = foes.get(0);
                double dist = Math.sqrt(f.squaredDistanceTo(c.player));
                // stay on the bridge: walk straight, aim slightly to the side so hits knock them off it
                boolean walk = dist > 3.3;
                aim(c, f.getEyePos().add(walk ? 0 : 1.6, -0.3, 0), 14f);
                c.options.forwardKey.setPressed(walk);
                c.options.sprintKey.setPressed(dist > 5);
                c.player.setPosition(p.getX() + 0.5, c.player.getY(), c.player.getZ()); // stay on the 1-wide bridge
                c.player.setVelocity(0, c.player.getVelocity().y, c.player.getVelocity().z);
                float cd = c.player.getAttackCooldownProgress(0);
                if (!walk && cd >= 1f) {
                    c.interactionManager.attackEntity(c.player, f);
                    c.player.swingHand(Hand.MAIN_HAND);
                }
            });
        });
    }

    /** Three on you: pearl away over open ground, then look back at them coming. */
    private void pearl() throws Exception {
        foesHit = true;
        BlockPos p = spot(0, -400);
        // throw towards the most open direction (no trees or hills for 30 blocks)
        float away = sp.getServer().computeOnServer(server -> {
            var w = server.getOverworld();
            float best = 0;
            int bestScore = Integer.MIN_VALUE;
            for (int deg = 0; deg < 360; deg += 15) {
                double r = Math.toRadians(deg);
                int score = 0;
                for (int d = 4; d <= 32; d += 2) {
                    int x = p.getX() + (int) Math.round(-Math.sin(r) * d), z = p.getZ() + (int) Math.round(Math.cos(r) * d);
                    int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                    score -= Math.max(0, top - p.getY()) * 3 + Math.abs(top - p.getY());
                }
                if (score > bestScore) { bestScore = score; best = deg; }
            }
            return best;
        });
        float toward = away + 180;
        player(p, toward, "5000");
        double fr = Math.toRadians(toward);
        double fx = -Math.sin(fr), fz = Math.cos(fr), sx = Math.cos(fr), sz = Math.sin(fr);
        foe(p.getX() + 0.5 + fx * 6 - sx * 2.5, p.getY(), p.getZ() + 0.5 + fz * 6 - sz * 2.5, "Kai", "kai", "diamond", false);
        foe(p.getX() + 0.5 + fx * 7.5, p.getY(), p.getZ() + 0.5 + fz * 7.5, "Noor", "noor", "diamond", false);
        foe(p.getX() + 0.5 + fx * 6 + sx * 2.5, p.getY(), p.getZ() + 0.5 + fz * 6 + sz * 2.5, "Sunny", "sunny", "diamond", false);
        shoot("pearl", 170, t -> {
            chase(t < 60 ? 0.12 : 0.22);
            ctx.runOnClient(c -> {
                if (t < 30) { fight(c, t); return; }
                release(c);
                if (t < 48) { // spin round to the open side and aim up a little
                    c.player.setYaw(c.player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(away - c.player.getYaw()) * 0.35f, -16f, 16f));
                    c.player.setPitch(MathHelper.lerp(0.3f, c.player.getPitch(), -14f));
                    if (t == 40) c.player.getInventory().setSelectedSlot(1);
                } else if (t == 48) {
                    c.interactionManager.interactItem(c.player, Hand.MAIN_HAND);
                    c.player.swingHand(Hand.MAIN_HAND);
                } else if (t < 90) {
                    var pearls = c.world.getEntitiesByClass(net.minecraft.entity.projectile.thrown.EnderPearlEntity.class, c.player.getBoundingBox().expand(64), e -> true);
                    if (!pearls.isEmpty()) aim(c, pearls.get(0).getEntityPos(), 6f);
                    else c.player.setPitch(MathHelper.lerp(0.2f, c.player.getPitch(), 5f));
                    if (t == 70) c.player.getInventory().setSelectedSlot(0);
                } else {
                    var foes = foes(c);
                    if (!foes.isEmpty()) aim(c, foes.get(1 % foes.size()).getEyePos(), 9f);
                }
            });
        });
    }

    /** Their totems keep popping; you keep swinging. */
    private void totem() throws Exception {
        BlockPos p = spot(-400, -400);
        player(p, 0, "4000");
        foe(p.getX() + 0.5, p.getY(), p.getZ() + 5.5, "Noor", "noor", "diamond", true);
        shoot("totem", 240, t -> {
            chase(0.1);
            if (t > 0 && t % 40 == 0 && t < 150) cmd("item replace entity @e[tag=foe] weapon.offhand with totem_of_undying");
            ctx.runOnClient(c -> fight(c, t));
        });
    }

    /** A duel at sunset. */
    private void sunset() throws Exception {
        BlockPos p = spot(400, 400);
        player(p, -90, "12150");
        foe(p.getX() + 9.5, p.getY(), p.getZ() + 0.5, "Ari", "ari", "diamond", false);
        foe(p.getX() + 12.5, p.getY(), p.getZ() + 4.5, "Makena", "makena", "iron", false);
        shoot("sunset", 260, t -> { chase(0.17); ctx.runOnClient(c -> fight(c, t)); });
    }
}
