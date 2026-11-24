package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;

/** "In combat" for HUD visibility rules: you hit something or took damage in the last few seconds. */
public final class Combat {
    private static final long WINDOW_MS = 8_000;
    private static long last;

    private Combat() {}

    public static void hit() { last = System.currentTimeMillis(); }

    /** Called every client tick: taking damage counts as combat too. */
    public static void tick(MinecraftClient mc) {
        if (mc.player != null && mc.player.hurtTime > 0) hit();
    }

    public static boolean active() { return System.currentTimeMillis() - last < WINDOW_MS; }

    /** Test hook: forget any recent fight. */
    public static void reset() { last = 0; }
}
