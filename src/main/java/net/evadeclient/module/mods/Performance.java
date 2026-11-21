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
    public final BooleanSetting unfocusedCap = add(new BooleanSetting("Low FPS When Tabbed Out", true));
    public final NumberSetting unfocusedFps = add(new NumberSetting("Tabbed Out FPS", 20, 5, 60, 5));
    public final BooleanSetting lighterOptions = add(new BooleanSetting("Lighter Video Settings", true));

    private CloudRenderMode savedClouds;
    private Boolean savedShadows;
    private ParticlesMode savedParticles;

    /** Entities skipped since the last read (for the HUD/tests). */
    private int culled;

    public Performance() {
        super("Performance", "Built-in FPS boost: culling, lighter rendering and an idle frame cap.", Category.MODS);
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

    @Override
    protected void onEnable() {
        if (!lighterOptions.get() || mc.options == null) return;
        var o = mc.options;
        savedClouds = o.getCloudRenderMode().getValue();
        savedShadows = o.getEntityShadows().getValue();
        savedParticles = o.getParticles().getValue();
        o.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        o.getEntityShadows().setValue(false);
        if (savedParticles == ParticlesMode.ALL) o.getParticles().setValue(ParticlesMode.DECREASED);
    }

    @Override
    protected void onDisable() {
        if (mc.options == null) return;
        var o = mc.options;
        if (savedClouds != null) o.getCloudRenderMode().setValue(savedClouds);
        if (savedShadows != null) o.getEntityShadows().setValue(savedShadows);
        if (savedParticles != null) o.getParticles().setValue(savedParticles);
        savedClouds = null; savedShadows = null; savedParticles = null;
    }
}
