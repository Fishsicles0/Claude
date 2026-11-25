package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.World;

/**
 * Which chunks are slime chunks, the way the game decides it: from the world seed and the chunk's position. The seed
 * is only ever one the player really has: the singleplayer world's own seed, or one typed into Tech Overlays' Seed
 * setting (for example your own server's). It is never guessed or worked out from a server, and with no seed every
 * answer is "unknown".
 */
public final class SlimeChunks {
    /** The game's salt for the slime chunk roll. */
    private static final long SALT = 987234911L;

    private SlimeChunks() {}

    private static String parsedFrom = null;
    private static Long parsed = null;

    /**
     * The seed to use here, or null when there is none: the integrated server's seed in singleplayer, otherwise the
     * typed seed. Null outside the Overworld (slime chunks are an Overworld rule; swamps aside).
     */
    public static Long seed() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return null;
        IntegratedServer server = mc.getServer();
        if (server != null && mc.isIntegratedServerRunning()) {
            try {
                return server.getOverworld().getSeed();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return typedSeed();
    }

    /** Where the seed comes from right now, for the HUD: "world", "typed" or null. */
    public static String source() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getServer() != null && mc.isIntegratedServerRunning()) return "world";
        return typedSeed() != null ? "typed" : null;
    }

    /** The typed seed as the game reads a seed box: a whole number as is, any other text by its hash. */
    public static Long typedSeed() {
        var mods = net.evadeclient.EvadeClient.modules;
        var overlays = mods == null ? null : mods.get(net.evadeclient.module.visuals.TechOverlays.class);
        String t = overlays == null ? "" : overlays.typedSeedText();
        if (t.isEmpty()) return null;
        if (!t.equals(parsedFrom)) {
            parsedFrom = t;
            try {
                parsed = Long.parseLong(t);
            } catch (NumberFormatException e) {
                parsed = (long) t.hashCode();
            }
        }
        return parsed;
    }

    /** Whether chunk (cx, cz) is a slime chunk for this seed. */
    public static boolean isSlimeChunk(long seed, int cx, int cz) {
        return ChunkRandom.getSlimeRandom(cx, cz, seed, SALT).nextInt(10) == 0;
    }
}
