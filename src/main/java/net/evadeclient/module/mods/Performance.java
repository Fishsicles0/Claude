package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticlesMode;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Evade's built-in FPS boost. Everything here is client-side and only changes what your computer draws:
 * far entities and block entities are skipped, dropped item stacks render one item, particles are thinned,
 * the game idles at a low frame rate when tabbed out, and a lighter-settings preset can be applied in one click.
 */
public class Performance extends Module {
    public final BooleanSetting entityCulling = add(new BooleanSetting("Entity Culling", true));
    public final NumberSetting entityDistance = add(new NumberSetting("Entity Distance", 64, 16, 160, 8));
    public final BooleanSetting alwaysPlayers = add(new BooleanSetting("Always Show Players", true));
    public final BooleanSetting blockEntityCulling = add(new BooleanSetting("Block Entity Culling", true));
    public final NumberSetting blockEntityDistance = add(new NumberSetting("Block Entity Distance", 40, 16, 64, 4));
    public final BooleanSetting singleItems = add(new BooleanSetting("Render One Item Per Stack", true));
    public final NumberSetting particlePercent = add(new NumberSetting("Particles Kept %", 60, 10, 100, 10));
    // Effects: the particles and extras that cost frames without telling you anything.
    public final NumberSetting particleDistance = add(new NumberSetting("Particle Distance", 32, 8, 96, 4));
    public final BooleanSetting ambientParticles = add(new BooleanSetting("Hide Ambient Particles", true));
    public final BooleanSetting lessDust = add(new BooleanSetting("Less Block Break Dust", true));
    public final BooleanSetting rainSplashes = add(new BooleanSetting("Hide Rain Splashes", true));
    public final BooleanSetting mobFire = add(new BooleanSetting("Hide Fire On Mobs", true));
    public final BooleanSetting weather = add(new BooleanSetting("Hide Rain & Snow", false));
    public final BooleanSetting unfocusedCap = add(new BooleanSetting("Low FPS When Tabbed Out", true));
    public final NumberSetting unfocusedFps = add(new NumberSetting("Tabbed Out FPS", 20, 5, 60, 5));
    public final BooleanSetting afkCap = add(new BooleanSetting("Low FPS When AFK", true));
    public final NumberSetting afkSeconds = add(new NumberSetting("AFK After (s)", 60, 15, 600, 15));
    public final NumberSetting afkFps = add(new NumberSetting("AFK FPS", 15, 5, 60, 5));
    public final BooleanSetting lighterOptions = add(new BooleanSetting("Lighter Video Settings", true));
    public final BooleanSetting lowEnd = add(new BooleanSetting("Low-End PC Mode", false));
    public final net.evadeclient.setting.StatusSetting addons = add(new net.evadeclient.setting.StatusSetting(
            "FPS Mods Installed", Performance::installedAddons, "via installer"));

    private static final String[][] ADDONS = {
            {"sodium", "Sodium"}, {"lithium", "Lithium"}, {"ferritecore", "FerriteCore"},
            {"immediatelyfast", "ImmediatelyFast"}, {"entityculling", "EntityCulling"}, {"moreculling", "MoreCulling"},
            {"krypton", "Krypton"}, {"badoptimizations", "BadOptimizations"},
            {"sodium-extra", "Sodium Extra"}, {"fastipping", "Fast IP Ping"}, {"fastquit", "FastQuit"}};

    /** Which of the installer's performance mods are loaded (they stack with this module). */
    public static String installedAddons() {
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        java.util.List<String> on = new java.util.ArrayList<>();
        for (String[] a : ADDONS) if (loader.isModLoaded(a[0])) on.add(a[1]);
        if (on.isEmpty()) return "None (re-run the installer)";
        return on.size() == ADDONS.length ? "All " + ADDONS.length : on.size() + " of " + ADDONS.length;
    }

    private CloudRenderMode savedClouds;
    private Boolean savedShadows;
    private ParticlesMode savedParticles;
    private Integer savedWeatherRadius;

    /** Entities skipped since the last read (for the HUD/tests). */
    private int culled;

    public Performance() {
        super("Performance", "Built-in FPS boost: culling, lighter rendering and an idle frame cap.", Category.MODS);
        setEnabled(true);
    }

    public static Performance active() {
        if (EvadeClient.modules == null) return null;
        Performance p = EvadeClient.modules.get(Performance.class);
        return p != null && p.isEnabled() ? p : null;
    }

    public int takeCulled() { int c = culled; culled = 0; return c; }

    /** Should this entity be skipped this frame? */
    public boolean cullEntity(Entity e, double cameraX, double cameraY, double cameraZ) {
        if (!entityCulling.get() || e == mc.player || e == mc.getCameraEntity()) return false;
        if (alwaysPlayers.get() && e instanceof PlayerEntity) return false;
        if (e.hasPassengers() || e.hasVehicle()) return false;
        double max = entityDistance.get();
        boolean cull = e.squaredDistanceTo(cameraX, cameraY, cameraZ) > max * max;
        if (cull) culled++;
        return cull;
    }

    public boolean cullBlockEntity(net.minecraft.util.math.BlockPos pos) {
        if (!blockEntityCulling.get() || mc.gameRenderer == null) return false;
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        double max = blockEntityDistance.get();
        return pos.getSquaredDistance(cam) > max * max;
    }

    public boolean dropParticle() {
        double keep = particlePercent.get();
        return keep < 100 && ThreadLocalRandom.current().nextDouble(100) >= keep;
    }

    /** Ambient particles: campfire and other smoke, ash, spores, falling leaves and drips. Pure decoration, and plentiful. */
    private static final java.util.Set<net.minecraft.particle.ParticleType<?>> AMBIENT = java.util.Set.of(
            net.minecraft.particle.ParticleTypes.CAMPFIRE_COSY_SMOKE, net.minecraft.particle.ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
            net.minecraft.particle.ParticleTypes.LARGE_SMOKE, net.minecraft.particle.ParticleTypes.ASH, net.minecraft.particle.ParticleTypes.WHITE_ASH,
            net.minecraft.particle.ParticleTypes.SPORE_BLOSSOM_AIR, net.minecraft.particle.ParticleTypes.FALLING_SPORE_BLOSSOM,
            net.minecraft.particle.ParticleTypes.MYCELIUM, net.minecraft.particle.ParticleTypes.CRIMSON_SPORE, net.minecraft.particle.ParticleTypes.WARPED_SPORE,
            net.minecraft.particle.ParticleTypes.CHERRY_LEAVES, net.minecraft.particle.ParticleTypes.PALE_OAK_LEAVES, net.minecraft.particle.ParticleTypes.TINTED_LEAVES,
            net.minecraft.particle.ParticleTypes.DRIPPING_WATER, net.minecraft.particle.ParticleTypes.FALLING_WATER,
            net.minecraft.particle.ParticleTypes.DRIPPING_LAVA, net.minecraft.particle.ParticleTypes.FALLING_LAVA, net.minecraft.particle.ParticleTypes.LANDING_LAVA,
            net.minecraft.particle.ParticleTypes.DRIPPING_DRIPSTONE_WATER, net.minecraft.particle.ParticleTypes.FALLING_DRIPSTONE_WATER,
            net.minecraft.particle.ParticleTypes.DRIPPING_DRIPSTONE_LAVA, net.minecraft.particle.ParticleTypes.FALLING_DRIPSTONE_LAVA,
            net.minecraft.particle.ParticleTypes.UNDERWATER, net.minecraft.particle.ParticleTypes.FIREFLY);

    /** Should a particle of this type at this spot be skipped? Checks the type filters, distance, then the budget. */
    public boolean skipParticle(net.minecraft.particle.ParticleEffect effect, double x, double y, double z) {
        var type = effect.getType();
        if (ambientParticles.get() && AMBIENT.contains(type)) return true;
        if (rainSplashes.get() && (type == net.minecraft.particle.ParticleTypes.RAIN || type == net.minecraft.particle.ParticleTypes.SPLASH)) return true;
        return tooFar(x, y, z) || dropParticle();
    }

    /** For particles created directly (block-break dust and the like). Dust is cut to a quarter. */
    public boolean skipParticle(net.minecraft.client.particle.Particle p) {
        var box = p.getBoundingBox();
        double x = (box.minX + box.maxX) / 2, y = (box.minY + box.maxY) / 2, z = (box.minZ + box.maxZ) / 2;
        if (tooFar(x, y, z)) return true;
        return lessDust.get() && p instanceof net.minecraft.client.particle.BlockDustParticle && ThreadLocalRandom.current().nextInt(4) != 0;
    }

    private boolean tooFar(double x, double y, double z) {
        if (mc.gameRenderer == null) return false;
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        double d = particleDistance.get(), dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz > d * d;
    }

    public boolean hideMobFire() { return mobFire.get(); }
    public boolean hideWeather() { return weather.get(); }

    // ---- Low-End PC Mode: the video settings that cost the most on weak hardware, restored exactly afterwards.
    private final java.util.List<Runnable> lowEndRestore = new java.util.ArrayList<>();
    private boolean lowEndApplied;

    private <T> void lowEndSet(net.minecraft.client.option.SimpleOption<T> option, T value) {
        T old = option.getValue();
        if (java.util.Objects.equals(old, value)) return;
        lowEndRestore.add(() -> option.setValue(old));
        option.setValue(value);
    }

    private void applyLowEnd(boolean on) {
        if (on == lowEndApplied || mc.options == null) return;
        var o = mc.options;
        if (on) {
            lowEndSet(o.getCutoutLeaves(), false);          // solid leaves
            lowEndSet(o.getAo(), false);                    // smooth lighting off
            lowEndSet(o.getImprovedTransparency(), false);
            lowEndSet(o.getChunkFade(), 0.0);               // no chunk fade-in
            lowEndSet(o.getBiomeBlendRadius(), 0);
            if (o.getEntityDistanceScaling().getValue() > 0.75) lowEndSet(o.getEntityDistanceScaling(), 0.75);
        } else {
            for (int i = lowEndRestore.size() - 1; i >= 0; i--) lowEndRestore.get(i).run();
            lowEndRestore.clear();
        }
        lowEndApplied = on;
    }

    @Override
    public void onTick() {
        applyLowEnd(lowEnd.get()); // follow the setting while Performance is on
    }

    @Override
    protected void onEnable() {
        applyLowEnd(lowEnd.get());
        if (!lighterOptions.get() || mc.options == null) return;
        var o = mc.options;
        savedClouds = o.getCloudRenderMode().getValue();
        savedShadows = o.getEntityShadows().getValue();
        savedParticles = o.getParticles().getValue();
        savedWeatherRadius = o.getWeatherRadius().getValue();
        if (savedWeatherRadius > 5) o.getWeatherRadius().setValue(5); // the same as the game's own Fast preset
        o.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        o.getEntityShadows().setValue(false);
        if (savedParticles == ParticlesMode.ALL) o.getParticles().setValue(ParticlesMode.DECREASED);
    }

    @Override
    protected void onDisable() {
        applyLowEnd(false);
        if (mc.options == null) return;
        var o = mc.options;
        if (savedClouds != null) o.getCloudRenderMode().setValue(savedClouds);
        if (savedShadows != null) o.getEntityShadows().setValue(savedShadows);
        if (savedParticles != null) o.getParticles().setValue(savedParticles);
        if (savedWeatherRadius != null) o.getWeatherRadius().setValue(savedWeatherRadius);
        savedWeatherRadius = null;
        savedClouds = null; savedShadows = null; savedParticles = null;
    }
}
