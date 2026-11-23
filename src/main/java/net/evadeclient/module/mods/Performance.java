package net.evadeclient.module.mods;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.visuals.Particles;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.MannequinEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.ParticlesMode;
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
 * far entities and block entities are skipped, dropped item stacks render one item, particles are thinned,
 * the game idles at a low frame rate when tabbed out, and a lighter-settings preset can be applied in one click.
 *
 * Nothing that tells you something is ever removed: map art, holograms, name tags, bosses and glowing entities
 * stay at any distance the game draws them, beacon beams are never culled, and fishing bites, splashes, lava drips
 * and fireworks are never thinned.
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
    /** Off by default: dripping lava is how you spot lava above you. */
    public final BooleanSetting lavaDrips = add(new BooleanSetting("Hide Lava Drips", false));
    public final BooleanSetting lessDust = add(new BooleanSetting("Less Block Break Dust", true));
    public final BooleanSetting rainSplashes = add(new BooleanSetting("Hide Rain Splashes", true));
    public final BooleanSetting mobFire = add(new BooleanSetting("Hide Fire On Mobs", true));
    public final BooleanSetting weather = add(new BooleanSetting("Hide Rain & Snow", false));
    /** On top of the game's "Reduce FPS when": applies only with "AFK" there (with "Minimized" the game keeps full speed). */
    public final BooleanSetting unfocusedCap = add(new BooleanSetting("Low FPS When Tabbed Out", true));
    public final NumberSetting unfocusedFps = add(new NumberSetting("Tabbed Out FPS", 30, 5, 60, 5));
    /** On top of the game's "Reduce FPS when": applies only with "AFK" there, and only ever lowers the game's own cap. */
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
    }

    public static Performance active() {
        if (EvadeClient.modules == null) return null;
        Performance p = EvadeClient.modules.get(Performance.class);
        return p != null && p.isEnabled() ? p : null;
    }

    public int takeCulled() { int c = culled; culled = 0; return c; }

    // ---- Entity culling

    /**
     * Should this entity be skipped this frame? Big entities get a proportionally longer distance (as in vanilla,
     * which draws a ghast four times as far as a cow), entities that carry information are never culled, and an
     * entity only counts as culled if the game would have drawn it at that distance.
     */
    public boolean cullEntity(Entity e, double cameraX, double cameraY, double cameraZ) {
        if (!entityCulling.get() || alwaysShown(e)) return false;
        double size = e.getBoundingBox().getAverageSideLength();
        if (Double.isNaN(size)) size = 1.0;
        double max = entityDistance.get() * Math.max(1.0, size);
        double distSq = e.squaredDistanceTo(cameraX, cameraY, cameraZ);
        if (distSq <= max * max) return false;
        if (!e.shouldRender(distSq)) return false; // beyond the game's own limit too: nothing to save
        culled++;
        return true;
    }

    /** Entities that stay visible as far as the game draws them, whatever the culling distance. */
    private boolean alwaysShown(Entity e) {
        if (e == mc.player || e == mc.getCameraEntity()) return true;
        if (alwaysPlayers.get() && e instanceof PlayerEntity) return true;
        if (e.hasPassengers() || e.hasVehicle()) return true;
        return e instanceof BlockAttachedEntity                              // item frames (map art), paintings, leash knots
                || e instanceof DisplayEntity                                // text, item and block displays (holograms)
                || e instanceof ArmorStandEntity || e instanceof MannequinEntity // statues, holograms and NPCs
                || e instanceof EnderDragonEntity || e instanceof WitherEntity
                || e.isCustomNameVisible()                                   // a name tag you're meant to see
                || mc.hasOutline(e);                                         // glowing
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
        double max = blockEntityDistance.get();
        if (max >= vanillaDistance) return false;
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        return pos.getSquaredDistance(cam) > max * max;
    }

    // ---- Particles

    public boolean dropParticle() {
        double keep = particlePercent.get();
        return keep < 100 && ThreadLocalRandom.current().nextDouble(100) >= keep;
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
     * Particles that tell you something and cost next to nothing: never thinned or distance-culled. The fishing wake
     * and the bobber's splash and bubbles are the bite, splashes show things entering water, and explosions, the
     * elder guardian's curse and the warden's sonic boom are single events the game always shows.
     */
    private static final Set<ParticleType<?>> CUES = Set.of(
            ParticleTypes.FISHING, ParticleTypes.SPLASH, ParticleTypes.BUBBLE,
            ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
            ParticleTypes.ELDER_GUARDIAN, ParticleTypes.SONIC_BOOM);

    /** Should a particle of this type at this spot be skipped? Checks the type filters, distance, then the budget. */
    public boolean skipParticle(ParticleEffect effect, double x, double y, double z) {
        var type = effect.getType();
        if (CUES.contains(type)) return false;
        if (ambientParticles.get() && AMBIENT.contains(type)) return true;
        if (lavaDrips.get() && LAVA_DRIPS.contains(type)) return true;
        if (rainSplashes.get() && type == ParticleTypes.RAIN) return true;
        return tooFar(x, y, z) || dropParticle();
    }

    /**
     * For particles created directly (block-break dust and the like). Dust is cut to a quarter. Fireworks are shown
     * whole at any distance the game draws them: the starter, its sparks and their trails, and the flash.
     */
    public boolean skipParticle(Particle p) {
        if (Particles.isFireworkVisual(p)) return false;
        var box = p.getBoundingBox();
        double x = (box.minX + box.maxX) / 2, y = (box.minY + box.maxY) / 2, z = (box.minZ + box.maxZ) / 2;
        if (tooFar(x, y, z)) return true;
        return lessDust.get() && p instanceof BlockDustParticle && ThreadLocalRandom.current().nextInt(4) != 0;
    }

    private boolean tooFar(double x, double y, double z) {
        if (mc.gameRenderer == null) return false;
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        double d = particleDistance.get(), dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz > d * d;
    }

    public boolean hideMobFire() { return mobFire.get(); }
    public boolean hideWeather() { return weather.get(); }

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
    }

    @Override
    protected void onEnable() {
        applyLighter(lighterOptions.get());
        applyLowEnd(lowEnd.get());
    }

    @Override
    protected void onDisable() {
        applyLowEnd(false);
        applyLighter(false);
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
