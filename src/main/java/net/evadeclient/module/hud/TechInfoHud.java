package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.SlimeChunks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.MoonPhase;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A list of technical lines for builders and farm makers, each switchable: the light where you stand, whether your
 * chunk is a slime chunk, the moon phase, weather, difficulty (and the local difficulty in singleplayer), dimension,
 * and how many entities and chunks your game has loaded. Everything comes from what your own game already knows (most
 * of it is in F3); position, biome and facing live in Coordinates, TPS and memory in their own widgets. Slime chunks
 * need a seed you really have: your singleplayer world's, or one typed into Tech Overlays' Seed setting.
 */
public class TechInfoHud extends TextHudModule {
    private final BooleanSetting light = add(new BooleanSetting("Light", true)
            .describe("Block light and sky light where you stand. Monsters spawn at 0 block light."));
    private final BooleanSetting slime = add(new BooleanSetting("Slime Chunk", true)
            .describe("Whether you're in a slime chunk. Needs your singleplayer world, or a seed typed into Tech Overlays."));
    private final BooleanSetting moon = add(new BooleanSetting("Moon Phase", true)
            .describe("Tonight's moon. Swamp slimes spawn most at a full moon, none at a new moon."));
    private final BooleanSetting weather = add(new BooleanSetting("Weather", false)
            .describe("Clear, rain or thunder, as your game shows it."));
    private final BooleanSetting difficulty = add(new BooleanSetting("Difficulty", false)
            .describe("The world's difficulty, plus the local difficulty where you stand in singleplayer."));
    private final BooleanSetting dimension = add(new BooleanSetting("Dimension", false)
            .describe("Which dimension you're in."));
    private final BooleanSetting entities = add(new BooleanSetting("Loaded Entities", false)
            .describe("How many entities your game has loaded, the same count F3 shows."));
    private final BooleanSetting chunks = add(new BooleanSetting("Loaded Chunks", false)
            .describe("How many chunks your game has loaded around you."));

    public TechInfoHud() {
        super("Tech Info", "Technical lines for builders: light, slime chunk, moon phase, weather and more.", -1, 220);
    }

    @Override
    protected List<String> lines(boolean preview) {
        ClientWorld w = mc.world;
        var p = mc.player;
        if (w == null || p == null) return preview ? sample() : List.of();
        List<String> out = new ArrayList<>(8);
        BlockPos feet = p.getBlockPos();
        if (light.get()) {
            out.add("Light: " + w.getLightLevel(LightType.BLOCK, feet) + " block, " + w.getLightLevel(LightType.SKY, feet) + " sky");
        }
        if (slime.get()) out.add("Slime Chunk: " + slimeText(w, p.getChunkPos()));
        if (moon.get() && !w.getDimension().hasFixedTime()) out.add("Moon: " + moonName(w, feet));
        if (weather.get()) out.add("Weather: " + (w.isThundering() ? "Thunder" : w.isRaining() ? "Rain" : "Clear"));
        if (difficulty.get()) out.add("Difficulty: " + difficultyText(w, feet));
        if (dimension.get()) out.add("Dimension: " + dimensionName(w));
        if (entities.get()) out.add("Entities: " + w.getRegularEntityCount());
        if (chunks.get()) out.add("Chunks: " + w.getChunkManager().getLoadedChunkCount());
        return out;
    }

    private static List<String> sample() {
        return List.of("Light: 0 block, 15 sky", "Slime Chunk: No", "Moon: Full Moon");
    }

    /** "Yes", "No", "Overworld only" or "needs a seed". */
    static String slimeText(ClientWorld w, ChunkPos chunk) {
        if (w.getRegistryKey() != World.OVERWORLD) return "Overworld only";
        Long seed = SlimeChunks.seed();
        if (seed == null) return "needs a seed";
        return SlimeChunks.isSlimeChunk(seed, chunk.x, chunk.z) ? "Yes" : "No";
    }

    static String moonName(ClientWorld w, BlockPos pos) {
        MoonPhase phase;
        try {
            phase = w.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.MOON_PHASE_VISUAL, pos);
        } catch (RuntimeException e) {
            phase = null;
        }
        return phase == null ? "Unknown" : title(phase.asString());
    }

    /** The last local difficulty the integrated server worked out for you (NaN for none), and when it was asked. */
    private volatile float localDifficulty = Float.NaN;
    private long localAskedAt = -100;

    private String difficultyText(ClientWorld w, BlockPos pos) {
        String base = title(w.getDifficulty().getName());
        // Local difficulty needs the chunk's inhabited time, which only the integrated server has (F3 reads it from
        // there too). It is asked for on the server's own thread once a second, never from this one.
        IntegratedServer server = mc.getServer();
        if (server == null || !mc.isIntegratedServerRunning()) { localDifficulty = Float.NaN; return base; }
        long now = net.evadeclient.EvadeClient.ticks;
        if (now - localAskedAt >= 20) {
            localAskedAt = now;
            var key = w.getRegistryKey();
            BlockPos at = pos.toImmutable();
            server.execute(() -> {
                ServerWorld sw = server.getWorld(key);
                if (sw == null || !sw.isChunkLoaded(at.getX() >> 4, at.getZ() >> 4)) return;
                LocalDifficulty ld = sw.getLocalDifficulty(at);
                localDifficulty = ld.getLocalDifficulty();
            });
        }
        float ld = localDifficulty;
        return Float.isNaN(ld) ? base : String.format(Locale.ROOT, "%s (local %.2f)", base, ld);
    }

    private static String dimensionName(ClientWorld w) {
        var key = w.getRegistryKey();
        if (key == World.OVERWORLD) return "Overworld";
        if (key == World.NETHER) return "Nether";
        if (key == World.END) return "The End";
        return key.getValue().toString();
    }

    /** "full_moon" or "normal" as "Full Moon", "Normal". */
    static String title(String id) {
        StringBuilder b = new StringBuilder(id.length());
        boolean up = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_') { b.append(' '); up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }
}
