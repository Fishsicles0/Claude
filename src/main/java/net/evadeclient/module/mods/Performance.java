package net.evadeclient.module.mods;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.ParticleManagerAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.visuals.Particles;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.util.FrameStats;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Evade's built-in FPS boost. Everything here is client-side and only changes what your computer draws:
 * far entities and block entities are skipped, dropped item stacks render one item, particles are thinned and
 * capped, the game idles at a low frame rate when tabbed out or minimised, and a lighter-settings preset can be
 * applied in one click. Adaptive Quality can tighten the particle and distance limits for you while the frame rate
 * is under a target, and hand them back once it recovers.
 *
 * Nothing that tells you something is ever removed: map art, holograms, name tags, bosses and glowing entities
 * stay at any distance the game draws them, your own projectiles and fishing bobber are never culled, beacon beams
 * are never culled, and fishing bites, splashes, lava drips and fireworks are never thinned. Adaptive Quality only
 * ever changes the values in effect, never your settings, and never goes below its own floors.
 */
public class Performance extends Module {
    public final BooleanSetting entityCulling = add(new BooleanSetting("Entity Culling", true));
    public final NumberSetting entityDistance = add(new NumberSetting("Entity Distance", 64, 16, 160, 8));
    public final NumberSetting itemDistance = add(new NumberSetting("Item Distance", 32, 8, 128, 8)
            .describe("Dropped items farther away than this aren't drawn."));
    public final NumberSetting decorationDistance = add(new NumberSetting("Decoration Distance", 48, 8, 128, 8)
            .describe("Item frames, paintings, armor stands and display blocks farther away than this aren't drawn. Map art, holograms and named stands always are."));
    public final BooleanSetting cullProjectiles = add(new BooleanSetting("Cull Projectiles & XP", false)
            .describe("Also skip far arrows, tridents, thrown items and experience orbs. Your own are always drawn. Off: the game decides, as usual."));
    public final NumberSetting projectileDistance = add(new NumberSetting("Projectile & XP Distance", 48, 16, 160, 8)
            .describe("How far projectiles and experience orbs are drawn while Cull Projectiles & XP is on."));
    public final BooleanSetting alwaysPlayers = add(new BooleanSetting("Always Show Players", true));
    public final BooleanSetting blockEntityCulling = add(new BooleanSetting("Block Entity Culling", true));
    public final NumberSetting blockEntityDistance = add(new NumberSetting("Block Entity Distance", 40, 16, 64, 4));
    public final StatusSetting culledNow = add(new StatusSetting("Culled Right Now", this::culledNowText, "last frame"));
    public final BooleanSetting singleItems = add(new BooleanSetting("Render One Item Per Stack", true));
    public final NumberSetting particlePercent = add(new NumberSetting("Particles Kept %", 60, 10, 100, 10)
            .describe("Share of particles still drawn; the rest are skipped. Lower helps FPS where there are lots of them."));
    // Effects: the particles and extras that cost frames without telling you anything.
    public final NumberSetting particleDistance = add(new NumberSetting("Particle Distance", 32, 8, 96, 4));
    public final NumberSetting maxParticles = add(new NumberSetting("Max Particles", 4000, 500, 16000, 500)
            .describe("A hard limit on live particles: past it new ones are skipped until some fade. Fishing bites, splashes and fireworks always show."));
    public final BooleanSetting ambientParticles = add(new BooleanSetting("Hide Ambient Particles", true));
    /** Off by default: dripping lava is how you spot lava above you. */
    public final BooleanSetting lavaDrips = add(new BooleanSetting("Hide Lava Drips", false));
    public final BooleanSetting lessDust = add(new BooleanSetting("Less Block Break Dust", true));
    public final BooleanSetting rainSplashes = add(new BooleanSetting("Hide Rain Splashes", true));
    public final BooleanSetting mobFire = add(new BooleanSetting("Hide Fire On Mobs", true));
    public final BooleanSetting weather = add(new BooleanSetting("Hide Rain & Snow", false));
    /** On top of the game's "Reduce FPS when": applies only with "AFK" there (with "Minimized" the game keeps full speed). */
    public final BooleanSetting unfocusedCap = add(new BooleanSetting("Low FPS When Tabbed Out", true));
    public final NumberSetting unfocusedFps = add(new NumberSetting("Tabbed Out FPS", 30, 5, 60, 5));
    /** Only ever lowers the game's own 10 FPS while minimised; in a world it stays at {@link #MIN_WORLD_FPS} or more. */
    public final NumberSetting minimisedFps = add(new NumberSetting("Minimised FPS", 1, 1, 10, 1)
            .describe("Frame rate while the game is minimised (the game's own is 10). In a world it stays at 4 or more, so the game keeps running at full speed."));
    public final NumberSetting backgroundVolume = add(new NumberSetting("Volume When Tabbed Out %", 100, 0, 100, 10)
            .describe("Turns every sound down while the game isn't the focused window. Your volume settings are never changed."));
    /** On top of the game's "Reduce FPS when": applies only with "AFK" there, and only ever lowers the game's own cap. */
    public final BooleanSetting afkCap = add(new BooleanSetting("Low FPS When AFK", true));
    public final NumberSetting afkSeconds = add(new NumberSetting("AFK After (s)", 60, 15, 600, 15));
    public final NumberSetting afkFps = add(new NumberSetting("AFK FPS", 15, 5, 60, 5));
    public final BooleanSetting adaptive = add(new BooleanSetting("Adaptive Quality", false)
            .describe("While the frame rate is under Target FPS, thin particles first, then draw entities and block entities less far. Everything comes back once it recovers."));
    public final NumberSetting targetFps = add(new NumberSetting("Target FPS", 90, 30, 240, 10)
            .describe("The frame rate Adaptive Quality aims for. Never above your Max Framerate or, with VSync, your monitor's refresh rate."));
    public final ModeSetting adaptSpeed = add(new ModeSetting("Adapt Speed", "Normal", "Slow", "Normal", "Fast")
            .describe("How quickly Adaptive Quality reacts to the frame rate."));
    public final StatusSetting adaptiveNow = add(new StatusSetting("Adaptive Now", this::adaptiveNowText, "live"));
    public final BooleanSetting lighterOptions = add(new BooleanSetting("Lighter Video Settings", true));
    public final BooleanSetting lowEnd = add(new BooleanSetting("Low-End PC Mode", false));
    public final StatusSetting addons = add(new StatusSetting("FPS Mods Installed", Performance::installedAddons, "via installer"));

    private static final String[][] ADDONS = {
            {"sodium", "Sodium"}, {"lithium", "Lithium"}, {"ferritecore", "FerriteCore"},
            {"immediatelyfast", "ImmediatelyFast"}, {"entityculling", "EntityCulling"}, {"moreculling", "MoreCulling"},
            {"krypton", "Krypton"}, {"badoptimizations", "BadOptimizations"},
            {"sodium-extra", "Sodium Extra"}, {"fastipping", "Fast IP Ping"}, {"fastquit", "FastQuit"}};

    /** Which of the installer's performance mods are loaded (they stack with this module). */
    public static String installedAddons() {
        var loader = FabricLoader.getInstance();
        List<String> on = new ArrayList<>();
        for (String[] a : ADDONS) if (loader.isModLoaded(a[0])) on.add(a[1]);
        if (on.isEmpty()) return "None (re-run the installer)";
        return on.size() == ADDONS.length ? "All " + ADDONS.length : on.size() + " of " + ADDONS.length;
    }

    /** Entities skipped since the last read (for the HUD/tests). */
    private int culled;

    public Performance() {
        super("Performance", "Built-in FPS boost: culling, lighter rendering and an idle frame cap.", Category.MODS);
        setEnabled(true);
        // Video settings Evade changed are always handed back: on start (after a crash or a killed game) and on quit.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> recoverVideoOptions());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> restoreVideoOptions());
        // Volume When Tabbed Out also applies on the title screen and in menus, where onTick doesn't run.
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickBackgroundAudio());
    }

    public static Performance active() {
        if (EvadeClient.modules == null) return null;
        Performance p = EvadeClient.modules.get(Performance.class);
        return p != null && p.isEnabled() ? p : null;
    }

    public int takeCulled() { int c = culled; culled = 0; return c; }

    // ---- Adaptive Quality
    //
    // "pressure" runs from 0 (your settings exactly) to 1 (every limit at its floor). The first half thins particles
    // (Particles Kept %, Particle Distance), the second half pulls in entity and block entity distances. Only the
    // eff*() values below change; the settings themselves are never written.

    private static final double FLOOR_PARTICLE_PERCENT = 25, FLOOR_PARTICLE_DISTANCE = 16;
    private static final double FLOOR_ENTITY = 32, FLOOR_BLOCK_ENTITY = 24, FLOOR_ITEM = 16, FLOOR_DECORATION = 24, FLOOR_PROJECTILE = 24;
    /** Fresh in-game frames needed after a pause (screen, focus, minimised, idle cap) before measuring again. */
    private static final int ADAPT_WARMUP_TICKS = 30;

    private double pressure;
    private int adaptWarmup = ADAPT_WARMUP_TICKS, adaptCooldown;
    /** Set every frame by the inactivity limiter: the frame rate is being held down on purpose (AFK, tabbed out). */
    private boolean idleCapped;

    /** Called by the inactivity limiter each frame: whether an idle cap is holding the frame rate down. */
    public void noteIdleCap(boolean capped) { idleCapped = capped; }

    /** Adaptive pressure, 0 (your settings) to 1 (every limit at its floor). For tests and the status line. */
    public double adaptivePressure() { return pressure; }

    /** Test hook: set the pressure directly (the next tick moves it again, or resets it with Adaptive Quality off). */
    public void setAdaptivePressureForTest(double p) { pressure = Math.max(0, Math.min(1, p)); }

    /** Test hook: live particles counted at the last tick (what Max Particles starts each tick from). */
    public int liveParticlesForTest() { return liveParticles; }

    private double particleTighten() { return Math.min(1, pressure * 2); }

    private double distanceTighten() { return Math.max(0, pressure * 2 - 1); }

    /** {@code user} moved toward {@code floor} by {@code t}; never below the floor, never above your own value. */
    private static double tighten(double user, double floor, double t) {
        if (t <= 0 || user <= floor) return user;
        return user + (floor - user) * Math.min(1, t);
    }

    public double effParticlePercent() { return tighten(particlePercent.get(), FLOOR_PARTICLE_PERCENT, particleTighten()); }
    public double effParticleDistance() { return tighten(particleDistance.get(), FLOOR_PARTICLE_DISTANCE, particleTighten()); }
    public double effEntityDist() { return tighten(entityDistance.get(), FLOOR_ENTITY, distanceTighten()); }
    public double effBlockEntityDist() { return tighten(blockEntityDistance.get(), FLOOR_BLOCK_ENTITY, distanceTighten()); }
    public double effItemDist() { return tighten(itemDistance.get(), FLOOR_ITEM, distanceTighten()); }
    public double effDecorationDist() { return tighten(decorationDistance.get(), FLOOR_DECORATION, distanceTighten()); }
    public double effProjectileDist() { return tighten(projectileDistance.get(), FLOOR_PROJECTILE, distanceTighten()); }

    /**
     * Once per tick in a world: compare the last second's frame rate with the target and move the pressure. Paused
     * (and the measurement restarted) while a screen is open, the window is unfocused or minimised, or an idle cap
     * holds the frame rate down: those frames say nothing about how the game runs while you play.
     */
    private void adapt() {
        if (!adaptive.get()) {
            pressure = 0;
            adaptWarmup = ADAPT_WARMUP_TICKS;
            return;
        }
        boolean paused = mc.world == null || mc.currentScreen != null || mc.getOverlay() != null
                || !mc.isWindowFocused() || mc.getWindow().isMinimized() || idleCapped;
        if (paused) {
            adaptWarmup = ADAPT_WARMUP_TICKS;
            return;
        }
        if (adaptWarmup > 0) {
            adaptWarmup--;
            return;
        }
        if (--adaptCooldown > 0) return;
        double step;
        switch (adaptSpeed.get()) {
            case "Slow" -> { adaptCooldown = 40; step = 0.04; }
            case "Fast" -> { adaptCooldown = 10; step = 0.06; }
            default -> { adaptCooldown = 20; step = 0.05; }
        }
        float[] window = FrameStats.framesWithin(1000);
        if (window[0] < 5 || window[1] < 500) return; // not enough frames recorded yet
        double fps = 1000.0 * window[0] / window[1];

        // The frame rate can't go past your Max Framerate (or the monitor with VSync): aim a little under that.
        double limit = Double.MAX_VALUE;
        int maxFps = mc.options.getMaxFps().getValue();
        if (maxFps < 260) limit = maxFps;
        if (mc.options.getEnableVsync().getValue()) limit = Math.min(limit, FrameStats.refreshRate());
        double target = targetFps.get();
        double tightenBelow = Math.min(target, limit * 0.9);
        double relaxAbove = Math.max(tightenBelow * 1.02, Math.min(target * 1.15, limit * 0.95));

        if (fps < tightenBelow) {
            double shortfall = (tightenBelow - fps) / tightenBelow;
            pressure = Math.min(1, pressure + step * (1 + Math.min(3, shortfall * 10)));
        } else if (fps > relaxAbove) {
            pressure = Math.max(0, pressure - step);
        }
    }

    private String adaptiveNowText() {
        if (!isEnabled() || !adaptive.get()) return "Off";
        if (pressure <= 0.001) return "Full quality";
        int pct = (int) Math.round(pressure * 100);
        return pct + "% lighter (" + (pressure > 0.5 ? "particles, distances" : "particles") + ")";
    }

    // ---- Entity culling

    /** Entity and block entity draws skipped this tick, and the per-frame figures shown in "Culled Right Now". */
    private int tickEntities, tickBlockEntities, statTicks;
    private int shownEntities, shownBlockEntities;

    /**
     * Should this entity be skipped this frame? Each kind has its own distance (dropped items, decorations,
     * projectiles, everything else), big entities get a proportionally longer one (as in vanilla, which draws a
     * ghast four times as far as a cow), entities that carry information are never culled, and an entity only counts
     * as culled if the game would have drawn it at that distance.
     */
    public boolean cullEntity(Entity e, double cameraX, double cameraY, double cameraZ) {
        if (!entityCulling.get() || alwaysShown(e)) return false;
        double base = cullDistance(e);
        if (base <= 0) return false;
        double size = e.getBoundingBox().getAverageSideLength();
        if (Double.isNaN(size)) size = 1.0;
        double max = base * Math.max(1.0, size);
        double distSq = e.squaredDistanceTo(cameraX, cameraY, cameraZ);
        if (distSq <= max * max) return false;
        if (!e.shouldRender(distSq)) return false; // beyond the game's own limit too: nothing to save
        culled++;
        tickEntities++;
        return true;
    }

    /** The culling distance for this kind of entity, or -1 when it isn't culled at all. */
    private double cullDistance(Entity e) {
        if (e instanceof ItemEntity) return effItemDist();
        if (e instanceof ArmorStandEntity || e instanceof BlockAttachedEntity || e instanceof DisplayEntity) return effDecorationDist();
        if (e instanceof ProjectileEntity || e instanceof ExperienceOrbEntity) {
            if (!cullProjectiles.get()) return -1;
            // Your own arrows, pearls and fishing bobber: you always see where they went.
            if (e instanceof ProjectileEntity p && mc.player != null && p.getOwner() == mc.player) return -1;
            return effProjectileDist();
        }
        return effEntityDist();
    }

    /** Entities that stay visible as far as the game draws them, whatever the culling distance. */
    private boolean alwaysShown(Entity e) {
        if (e == mc.player || e == mc.getCameraEntity()) return true;
        if (alwaysPlayers.get() && e instanceof PlayerEntity) return true;
        if (e.hasPassengers() || e.hasVehicle()) return true;
        return e instanceof DisplayEntity.TextDisplayEntity                    // holograms
                || (e instanceof ItemFrameEntity frame && frame.containsMap()) // map art
                || e instanceof MannequinEntity                                // NPCs
                || e instanceof EnderDragonEntity || e instanceof WitherEntity
                || e.isCustomNameVisible()                                     // a name tag you're meant to see
                || mc.hasOutline(e);                                           // glowing
    }

    /** Should the block entity at {@code pos} be skipped? (Assumes the game's default 64-block limit.) */
    public boolean cullBlockEntity(BlockPos pos) {
        return cullBlockEntity(pos, 64);
    }

    /**
     * Should the block entity at {@code pos}, whose renderer draws it up to {@code vanillaDistance} blocks away, be
     * skipped? Renderers that ask to be seen farther than the default 64 blocks (end-gateway beams, moving pistons)
     * are never culled, and the cut-off never goes past the game's own.
     */
    public boolean cullBlockEntity(BlockPos pos, int vanillaDistance) {
        if (!blockEntityCulling.get() || mc.gameRenderer == null || vanillaDistance > 64) return false;
        double max = effBlockEntityDist();
        if (max >= vanillaDistance) return false;
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        double distSq = pos.getSquaredDistance(cam);
        if (distSq <= max * max) return false;
        if (distSq <= (double) vanillaDistance * vanillaDistance) tickBlockEntities++; // a real saving
        return true;
    }

    /**
     * Turns the per-tick totals into "per frame" figures for Culled Right Now, twice a second (the counters live in
     * the culling checks themselves, so no frame hook is needed).
     */
    private void updateCullStats() {
        if (++statTicks < 10) return;
        float framesPerWindow = Math.max(1f, mc.getCurrentFps() * statTicks / 20f);
        shownEntities = Math.round(tickEntities / framesPerWindow);
        shownBlockEntities = Math.round(tickBlockEntities / framesPerWindow);
        tickEntities = 0;
        tickBlockEntities = 0;
        statTicks = 0;
    }

    private String culledNowText() {
        if (!isEnabled()) return "Off";
        if (!entityCulling.get() && !blockEntityCulling.get()) return "Culling off";
        if (mc.world == null) return "Not in a world";
        return count(shownEntities, "entity", "entities") + ", " + count(shownBlockEntities, "block entity", "block entities");
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    // ---- Particles

    public boolean dropParticle() {
        double keep = effParticlePercent();
        return keep < 100 && ThreadLocalRandom.current().nextDouble(100) >= keep;
    }

    /** Particles alive at the end of the last tick, and particles let through since then (Max Particles). */
    private int liveParticles, addedSinceTick;
    /** The accessor mixin isn't applied (not registered): count through the F3 debug figure instead. */
    private static boolean accessorMissing;

    /** Past Max Particles: skip it. Otherwise count it in. */
    private boolean overParticleCap() {
        if (liveParticles + addedSinceTick >= maxParticles.getInt()) return true;
        addedSinceTick++;
        return false;
    }

    /** Live particles right now: the sum of every texture sheet's queue (the same figure F3 shows). Render thread. */
    private static int countLiveParticles(ParticleManager pm) {
        if (pm == null) return 0;
        if (!accessorMissing) {
            try {
                int n = 0;
                for (ParticleRenderer<?> r : ((ParticleManagerAccessor) pm).evade$particles().values()) n += r.size();
                return n;
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                accessorMissing = true;
                EvadeClient.LOG.warn("Performance: particle accessor unavailable, counting via the debug figure", e);
            }
        }
        try {
            return Integer.parseInt(pm.getDebugString().trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Ambient particles: campfire and other smoke, ash, spores, falling leaves and water drips. Pure decoration, and plentiful. */
    private static final Set<ParticleType<?>> AMBIENT = Set.of(
            ParticleTypes.CAMPFIRE_COSY_SMOKE, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
            ParticleTypes.LARGE_SMOKE, ParticleTypes.ASH, ParticleTypes.WHITE_ASH,
            ParticleTypes.SPORE_BLOSSOM_AIR, ParticleTypes.FALLING_SPORE_BLOSSOM,
            ParticleTypes.MYCELIUM, ParticleTypes.CRIMSON_SPORE, ParticleTypes.WARPED_SPORE,
            ParticleTypes.CHERRY_LEAVES, ParticleTypes.PALE_OAK_LEAVES, ParticleTypes.TINTED_LEAVES,
            ParticleTypes.DRIPPING_WATER, ParticleTypes.FALLING_WATER,
            ParticleTypes.DRIPPING_DRIPSTONE_WATER, ParticleTypes.FALLING_DRIPSTONE_WATER,
            ParticleTypes.UNDERWATER, ParticleTypes.FIREFLY);

    /** Lava dripping through a ceiling (or off dripstone): the warning that there's lava above you. */
    private static final Set<ParticleType<?>> LAVA_DRIPS = Set.of(
            ParticleTypes.DRIPPING_LAVA, ParticleTypes.FALLING_LAVA, ParticleTypes.LANDING_LAVA,
            ParticleTypes.DRIPPING_DRIPSTONE_LAVA, ParticleTypes.FALLING_DRIPSTONE_LAVA);

    /**
     * Particles that tell you something and cost next to nothing: never thinned, distance-culled or capped. The
     * fishing wake and the bobber's splash and bubbles are the bite, splashes show things entering water, and
     * explosions, the elder guardian's curse and the warden's sonic boom are single events the game always shows.
     */
    private static final Set<ParticleType<?>> CUES = Set.of(
            ParticleTypes.FISHING, ParticleTypes.SPLASH, ParticleTypes.BUBBLE,
            ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
            ParticleTypes.ELDER_GUARDIAN, ParticleTypes.SONIC_BOOM);

    /** Should a particle of this type at this spot be skipped? Type filters, distance, the budget, then the cap. */
    public boolean skipParticle(ParticleEffect effect, double x, double y, double z) {
        var type = effect.getType();
        if (CUES.contains(type)) return false;
        if (ambientParticles.get() && AMBIENT.contains(type)) return true;
        if (lavaDrips.get() && LAVA_DRIPS.contains(type)) return true;
        if (rainSplashes.get() && type == ParticleTypes.RAIN) return true;
        if (tooFar(x, y, z) || dropParticle()) return true;
        return overParticleCap();
    }

    /**
     * For particles created directly (block-break dust and the like). Dust is cut to a quarter. Fireworks are shown
     * whole at any distance the game draws them, and never count against the cap: the starter, its sparks and their
     * trails, and the flash.
     */
    public boolean skipParticle(Particle p) {
        if (Particles.isFireworkVisual(p)) return false;
        var box = p.getBoundingBox();
        double x = (box.minX + box.maxX) / 2, y = (box.minY + box.maxY) / 2, z = (box.minZ + box.maxZ) / 2;
        if (tooFar(x, y, z)) return true;
        if (lessDust.get() && p instanceof BlockDustParticle && ThreadLocalRandom.current().nextInt(4) != 0) return true;
        return overParticleCap();
    }

    private boolean tooFar(double x, double y, double z) {
        if (mc.gameRenderer == null) return false;
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        double d = effParticleDistance(), dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz > d * d;
    }

    public boolean hideMobFire() { return mobFire.get(); }
    public boolean hideWeather() { return weather.get(); }

    // ---- Idle frame rate and background audio

    /** Lowest minimised frame rate in a world: the game runs at most 10 ticks a frame, so 4 FPS keeps all 20 a second. */
    public static final int MIN_WORLD_FPS = 4;

    /** The frame-rate cap while minimised (only ever applied below the game's own 10). */
    public int minimisedCap(boolean inWorld) {
        int fps = minimisedFps.getInt();
        return inWorld ? Math.max(fps, MIN_WORLD_FPS) : Math.max(1, fps);
    }

    /** Below this every sound would be skipped outright; a sound started tabbed out must still come back. */
    private static final float SILENT_BUT_PLAYING = 1.0E-4f;
    /** The factor every sound's volume is multiplied by right now (1 = untouched). Read by the sound mixin. */
    private static float backgroundFactor = 1f;

    /** Volume When Tabbed Out: what every sound's volume is multiplied by right now (1 when focused or off). */
    public static float backgroundVolumeFactor() { return backgroundFactor; }

    /**
     * Every client tick, in a world or not: when the factor changes (focus gained or lost, the setting moved, the
     * module switched), sounds already playing are brought to the new volume. Nothing is written to options.txt.
     */
    private void tickBackgroundAudio() {
        float f = 1f;
        if (isEnabled() && mc.getWindow() != null && !mc.isWindowFocused()) {
            f = (float) (backgroundVolume.get() / 100.0);
            if (f < 1f) f = Math.max(f, SILENT_BUT_PLAYING);
        }
        if (f == backgroundFactor) return;
        backgroundFactor = f;
        var sounds = mc.getSoundManager();
        // MASTER refreshes every playing sound, whatever its category.
        if (sounds != null) sounds.refreshSoundVolumes(SoundCategory.MASTER);
    }

    // ---- Video settings: Lighter Video Settings and Low-End PC Mode.
    //
    // Each changed option is remembered with your own value and the value Evade set. Putting it back only touches
    // options that still hold Evade's value, so anything you changed yourself in the meantime is kept. The list is
    // also written to config/evade/video-restore.json, so a crash or a killed game can't leave Evade's values in
    // options.txt as if they were yours: the next start puts them back.

    /** The options Evade may change, by the id they are saved under. */
    private static final Map<String, Function<GameOptions, SimpleOption<?>>> OPTIONS = Map.ofEntries(
            Map.entry("clouds", GameOptions::getCloudRenderMode),
            Map.entry("entityShadows", GameOptions::getEntityShadows),
            Map.entry("particles", GameOptions::getParticles),
            Map.entry("weatherRadius", GameOptions::getWeatherRadius),
            Map.entry("cutoutLeaves", GameOptions::getCutoutLeaves),
            Map.entry("ao", GameOptions::getAo),
            Map.entry("improvedTransparency", GameOptions::getImprovedTransparency),
            Map.entry("chunkFade", GameOptions::getChunkFade),
            Map.entry("biomeBlendRadius", GameOptions::getBiomeBlendRadius),
            Map.entry("entityDistanceScaling", GameOptions::getEntityDistanceScaling));

    /** One option Evade changed: your value, and the value Evade put in its place. */
    private record Changed(String id, SimpleOption<Object> option, Object original, Object applied) {}

    private final List<Changed> lighterChanged = new ArrayList<>();
    private final List<Changed> lowEndChanged = new ArrayList<>();
    private boolean lighterApplied, lowEndApplied;

    @SuppressWarnings("unchecked")
    private static SimpleOption<Object> option(GameOptions o, String id) {
        return (SimpleOption<Object>) OPTIONS.get(id).apply(o);
    }

    private void change(List<Changed> list, String id, Object value) {
        SimpleOption<Object> option = option(mc.options, id);
        Object old = option.getValue();
        if (Objects.equals(old, value)) return;
        option.setValue(value);
        list.add(new Changed(id, option, old, option.getValue())); // what the game actually took
    }

    /** Puts back the options in {@code list} that still hold Evade's value. True if any changed. */
    private static boolean putBack(List<Changed> list) {
        boolean any = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            Changed c = list.get(i);
            if (!Objects.equals(c.option().getValue(), c.applied())) continue; // you changed it yourself: keep yours
            c.option().setValue(c.original());
            any = true;
        }
        list.clear();
        return any;
    }

    /** Lighter Video Settings: the same trade-offs as the game's own Fast preset, without touching the rest. */
    private void applyLighter(boolean on) {
        if (on == lighterApplied || mc.options == null) return;
        boolean restored = false;
        if (on) {
            var o = mc.options;
            change(lighterChanged, "clouds", CloudRenderMode.OFF);
            change(lighterChanged, "entityShadows", false);
            if (o.getParticles().getValue() == ParticlesMode.ALL) change(lighterChanged, "particles", ParticlesMode.DECREASED);
            if (o.getWeatherRadius().getValue() > 5) change(lighterChanged, "weatherRadius", 5);
        } else {
            restored = putBack(lighterChanged);
        }
        lighterApplied = on;
        videoChanged(restored);
    }

    /** Low-End PC Mode: the video settings that cost the most on weak hardware. */
    private void applyLowEnd(boolean on) {
        if (on == lowEndApplied || mc.options == null) return;
        boolean restored = false;
        if (on) {
            var o = mc.options;
            change(lowEndChanged, "cutoutLeaves", false);          // solid leaves
            change(lowEndChanged, "ao", false);                    // smooth lighting off
            change(lowEndChanged, "improvedTransparency", false);
            change(lowEndChanged, "chunkFade", 0.0);               // no chunk fade-in
            change(lowEndChanged, "biomeBlendRadius", 0);
            if (o.getEntityDistanceScaling().getValue() > 0.75) change(lowEndChanged, "entityDistanceScaling", 0.75);
        } else {
            restored = putBack(lowEndChanged);
        }
        lowEndApplied = on;
        videoChanged(restored);
    }

    /** Keep the restore file in step, and rewrite options.txt after putting your values back. */
    private void videoChanged(boolean restored) {
        saveRestoreFile();
        // The game may have saved options.txt while Evade's values were in place (closing a settings screen does).
        if (restored && mc.options != null) mc.options.write();
    }

    @Override
    public void onTick() {
        // follow the settings while Performance is on
        applyLighter(lighterOptions.get());
        applyLowEnd(lowEnd.get());
        // Max Particles: start the tick from what's actually alive (render thread, after the particles ticked).
        liveParticles = countLiveParticles(mc.particleManager);
        addedSinceTick = 0;
        updateCullStats();
        adapt();
    }

    @Override
    protected void onEnable() {
        applyLighter(lighterOptions.get());
        applyLowEnd(lowEnd.get());
        resetRuntime();
    }

    @Override
    protected void onDisable() {
        applyLowEnd(false);
        applyLighter(false);
        resetRuntime();
    }

    /** Adaptive Quality back to your settings, and the counters cleared (on enable, disable and leaving a world). */
    private void resetRuntime() {
        pressure = 0;
        adaptWarmup = ADAPT_WARMUP_TICKS;
        adaptCooldown = 0;
        idleCapped = false;
        liveParticles = 0;
        addedSinceTick = 0;
        tickEntities = tickBlockEntities = statTicks = 0;
        shownEntities = shownBlockEntities = 0;
    }

    /** On quit: hand every option back before the game shuts down, and save them as yours. */
    private void restoreVideoOptions() {
        try {
            boolean restored = putBack(lowEndChanged) | putBack(lighterChanged);
            lowEndApplied = false;
            lighterApplied = false;
            saveRestoreFile();
            if (restored && mc.options != null) mc.options.write();
        } catch (Exception e) {
            EvadeClient.LOG.warn("Performance: couldn't restore video settings on quit", e);
        }
    }

    private static Path restoreFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("video-restore.json");
    }

    /** Write what's currently changed (or delete the file when nothing is). */
    private void saveRestoreFile() {
        Path file = restoreFile();
        try {
            if (lighterChanged.isEmpty() && lowEndChanged.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            JsonObject root = new JsonObject();
            for (List<Changed> list : List.of(lighterChanged, lowEndChanged)) {
                for (Changed c : list) {
                    Optional<JsonElement> original = encode(c.option(), c.original()), applied = encode(c.option(), c.applied());
                    if (original.isEmpty() || applied.isEmpty()) continue;
                    JsonObject entry = new JsonObject();
                    entry.add("original", original.get());
                    entry.add("applied", applied.get());
                    root.add(c.id(), entry);
                }
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Performance: couldn't save {}", file, e);
        }
    }

    /**
     * On start: a restore file means the game last stopped while Evade's values were in place (a crash or a killed
     * process). Put your values back wherever Evade's are still set, save options.txt, and forget the file. If
     * Performance is on, it applies its settings again on the next world join, on top of your own values.
     */
    private void recoverVideoOptions() {
        Path file = restoreFile();
        if (!Files.exists(file) || mc.options == null) return;
        boolean restored = false;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : root.entrySet()) {
                if (!OPTIONS.containsKey(e.getKey()) || !e.getValue().isJsonObject()) continue;
                SimpleOption<Object> option = option(mc.options, e.getKey());
                JsonObject entry = e.getValue().getAsJsonObject();
                Optional<Object> original = decode(option, entry.get("original")), applied = decode(option, entry.get("applied"));
                if (original.isEmpty() || applied.isEmpty() || !Objects.equals(option.getValue(), applied.get())) continue;
                option.setValue(original.get());
                restored = true;
            }
            if (restored) {
                mc.options.write();
                EvadeClient.LOG.info("Performance: restored your video settings after an unclean shutdown");
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Performance: couldn't read {}", file, e);
        }
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }

    private static Optional<JsonElement> encode(SimpleOption<Object> option, Object value) {
        return option.getCodec().encodeStart(JsonOps.INSTANCE, value).result();
    }

    private static Optional<Object> decode(SimpleOption<Object> option, JsonElement json) {
        if (json == null) return Optional.empty();
        return option.getCodec().parse(JsonOps.INSTANCE, json).result();
    }
}
