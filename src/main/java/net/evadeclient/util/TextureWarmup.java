package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.TextureManagerWarmupAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.TextureContents;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Start-up: Evade's menu icons and shapes (textures/gui) are read and decoded from PNG on a background thread once
 * the game has started, then handed to the graphics card a few per tick. Without this each one is decoded on the
 * render thread the first time a menu draws it, which made the first open of the Evade menu stutter. Textures the
 * game has loaded already are left alone.
 */
public final class TextureWarmup {
    private TextureWarmup() {}

    private record Loaded(Identifier id, ResourceTexture texture, TextureContents contents) {}

    private static final ConcurrentLinkedQueue<Loaded> ready = new ConcurrentLinkedQueue<>();
    private static volatile boolean started;
    private static volatile int found, uploaded, skipped;
    private static volatile long decodeNanos;
    /** Uploads per client tick: small textures, so this stays well under a millisecond. */
    private static final int PER_TICK = 12;

    /** Starts the background decode once (after the game has started and resources are loaded). */
    public static void start(MinecraftClient mc) {
        if (started) return;
        started = true;
        ResourceManager rm = mc.getResourceManager();
        Thread t = new Thread(() -> decode(rm), "Evade texture warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void decode(ResourceManager rm) {
        long t0 = System.nanoTime();
        try {
            List<Identifier> ids = new ArrayList<>(rm.findResources("textures/gui",
                    id -> id.getNamespace().equals("evade") && id.getPath().endsWith(".png")).keySet());
            found = ids.size();
            for (Identifier id : ids) {
                ResourceTexture tex = new ResourceTexture(id);
                try {
                    ready.add(new Loaded(id, tex, tex.loadContents(rm)));
                } catch (Exception e) {
                    skipped++;
                }
            }
        } catch (Throwable e) {
            EvadeClient.LOG.warn("Texture warm-up: couldn't list Evade's textures", e);
        }
        decodeNanos = System.nanoTime() - t0;
    }

    /** Every client tick (render thread): upload a few decoded textures the game hasn't loaded yet. */
    public static void tick(MinecraftClient mc) {
        if (ready.isEmpty()) return;
        var tm = mc.getTextureManager();
        if (tm == null || mc.getOverlay() != null) return; // not during a resource reload
        for (int i = 0; i < PER_TICK; i++) {
            Loaded l = ready.poll();
            if (l == null) return;
            try {
                if (tm instanceof TextureManagerWarmupAccessor acc && acc.evade$textures().containsKey(l.id())) {
                    l.contents().close();
                    skipped++;
                    continue;
                }
                l.texture().reload(l.contents());
                tm.registerTexture(l.id(), (net.minecraft.client.texture.AbstractTexture) l.texture()); // already uploaded: no second load
                uploaded++;
            } catch (Throwable e) {
                skipped++;
                EvadeClient.LOG.warn("Texture warm-up: couldn't upload {}", l.id(), e);
            }
        }
    }

    /** {found, uploaded, skipped (already loaded or unreadable)} for tests. */
    public static int[] counts() { return new int[]{found, uploaded, skipped}; }

    /** Time the background thread spent finding and decoding, ms. */
    public static long decodeMs() { return decodeNanos / 1_000_000; }

    public static boolean done() { return started && decodeNanos > 0 && ready.isEmpty(); }
}
