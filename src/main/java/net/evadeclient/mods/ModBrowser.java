package net.evadeclient.mods;

import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The Mod Browser's shared state for the session: the {@link ModManager} for Evade's own mods folder
 * ({@code <game dir>/mods}, the Evade instance), the icon cache, the background executor, and whether anything changed
 * that needs a restart.
 */
public final class ModBrowser {
    private static final AtomicInteger THREADS = new AtomicInteger();
    /** Every Modrinth call and file change runs here, never on the render thread. */
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Evade-ModBrowser-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private static ModBrowser instance;
    private static boolean startupDone;

    public final ModManager manager;
    public final IconCache icons = new IconCache();
    /** Mods were added, updated or removed this session: they load (or unload) on the next start. */
    private volatile boolean restartNeeded;

    private ModBrowser(Path mods, Path index) {
        manager = new ModManager(mods, index, new ModrinthApi());
    }

    public static synchronized ModBrowser get() {
        if (instance == null) {
            FabricLoader fl = FabricLoader.getInstance();
            instance = new ModBrowser(fl.getGameDir().resolve("mods"), fl.getConfigDir().resolve("evade").resolve("mod-browser.json"));
        }
        if (!startupDone) startup();
        return instance;
    }

    /**
     * Finish file changes left from the last session (jars that were in use when they were removed or updated). Call
     * once early in client start-up; {@link #get} also does it the first time if nobody did.
     */
    public static synchronized void startup() {
        if (startupDone) return;
        startupDone = true;
        ModBrowser b = get();
        EXECUTOR.execute(() -> {
            try {
                int n = b.manager.processPending();
                if (n > 0) EvadeClient.LOG.info("Mod Browser: finished {} pending mod change(s)", n);
            } catch (Exception e) {
                EvadeClient.LOG.warn("Mod Browser: pending changes failed: {}", e.toString());
            }
        });
    }

    public boolean restartNeeded() { return restartNeeded; }

    public void markRestartNeeded() { restartNeeded = true; }

    /**
     * Run {@code work} on the background executor and hand its result (or its error) to the client thread. A failure's
     * message is meant for the player.
     */
    public static <T> void async(Callable<T> work, Consumer<T> onDone, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            T result;
            try {
                result = work.call();
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                if (!(t instanceof java.io.IOException)) EvadeClient.LOG.warn("Mod Browser task failed", t);
                MinecraftClient.getInstance().execute(() -> onError.accept(msg));
                return;
            }
            MinecraftClient.getInstance().execute(() -> onDone.accept(result));
        });
    }
}
