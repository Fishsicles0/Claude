package net.evadeclient.schematic;

import net.evadeclient.EvadeClient;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * "Paste in singleplayer": puts a placement's blocks into your own world. Only ever on the game's built-in server
 * (singleplayer, or a world you opened to LAN, where you are the host), and only when your player there has
 * operator rights (cheats on), the same rights {@code /setblock} needs. The blocks are set on that server's own
 * thread, a slice per server tick, exactly as {@code /setblock} sets them (no neighbour updates, nothing dropped).
 * The last paste can be undone. On a multiplayer server this refuses before anything happens: nothing is ever sent
 * to a remote server.
 */
public final class SingleplayerPaste {
    /** Blocks set per server tick. */
    private static final int PER_TICK = 16384;
    /** Most blocks a paste may change (and undo remembers). */
    public static final int MAX_BLOCKS = 2_000_000;

    private record Job(RegistryKey<World> dim, long[] pos, BlockState[] states, boolean undo, Consumer<String> done) {}

    private static boolean registered;
    /** Server thread only. */
    private static final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private static Job running;
    private static int runningAt;
    private static long[] undoPos;
    private static BlockState[] undoStates;
    private static RegistryKey<World> undoDim;
    private static long[] collectPos;
    private static BlockState[] collectStates;
    private static int collectCount;

    private SingleplayerPaste() {}

    /** Why pasting isn't possible right now, or null when it is (the operator check happens on the server). */
    public static String unavailableReason() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return "Join a world first.";
        IntegratedServer server = mc.getServer();
        if (!mc.isIntegratedServerRunning() || server == null) return "Paste works only in your own singleplayer or LAN world, never on a server.";
        return null;
    }

    /**
     * Pastes the placement. {@code done} gets a short result line on the client thread. Returns false (and says why
     * through {@code done}) when it can't start.
     */
    public static boolean paste(Placement p, boolean includeAir, Consumer<String> done) {
        String why = unavailableReason();
        if (why != null) { done.accept(why); return false; }
        MinecraftClient mc = MinecraftClient.getInstance();
        Schematic s = p.schematic;
        BlockState[] pal = p.placedPalette();
        long n = 0;
        for (short b : s.blocks) if (b != 0 && (includeAir || !pal[b].isAir())) n++;
        if (n > MAX_BLOCKS) { done.accept("That's too many blocks to paste at once (" + n + "; the most is " + MAX_BLOCKS + ")."); return false; }
        long[] pos = new long[(int) n];
        BlockState[] states = new BlockState[(int) n];
        int[] xz = new int[2];
        BlockPos o = p.origin();
        int k = 0, sx = s.sizeX, sz = s.sizeZ;
        for (int i = 0; i < s.blocks.length; i++) {
            int b = s.blocks[i];
            if (b == 0 || (!includeAir && pal[b].isAir())) continue;
            int lx = i % sx, rest = i / sx, lz = rest % sz, ly = rest / sz;
            p.placedXZ(lx, lz, xz);
            pos[k] = BlockPos.asLong(o.getX() + xz[0], o.getY() + ly, o.getZ() + xz[1]);
            states[k++] = pal[b];
        }
        submit(mc, new Job(mc.world.getRegistryKey(), pos, states, false, done));
        return true;
    }

    /** Puts back what the last paste replaced. */
    public static void undo(Consumer<String> done) {
        String why = unavailableReason();
        if (why != null) { done.accept(why); return; }
        MinecraftClient mc = MinecraftClient.getInstance();
        submit(mc, new Job(mc.world.getRegistryKey(), null, null, true, done));
    }

    private static void submit(MinecraftClient mc, Job job) {
        MinecraftServer server = mc.getServer();
        UUID me = mc.player.getUuid();
        Consumer<String> reply = msg -> mc.execute(() -> job.done().accept(msg));
        server.execute(() -> {
            ensureRegistered();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(me);
            if (player == null || !player.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS))) {
                reply.accept("Paste needs cheats on in this world (the same rights /setblock needs).");
                return;
            }
            if (job.undo() && (undoPos == null || undoPos.length == 0)) {
                reply.accept("There's no paste to undo.");
                return;
            }
            jobs.add(new Job(job.dim(), job.pos(), job.states(), job.undo(), reply));
        });
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(SingleplayerPaste::tick);
        // A new world starts with no queue and nothing to undo.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            jobs.clear();
            running = null;
            undoPos = null;
            undoStates = null;
        });
    }

    private static void tick(MinecraftServer server) {
        if (running == null) {
            Job next = jobs.poll();
            if (next == null) return;
            if (next.undo()) {
                if (undoPos == null) { next.done().accept("There's no paste to undo."); return; }
                next = new Job(undoDim, undoPos, undoStates, true, next.done());
                undoPos = null;
                undoStates = null;
            } else {
                collectPos = new long[next.pos().length];
                collectStates = new BlockState[next.pos().length];
                collectCount = 0;
            }
            running = next;
            runningAt = 0;
        }
        Job job = running;
        ServerWorld world = server.getWorld(job.dim());
        if (world == null) {
            running = null;
            job.done().accept("That world isn't loaded any more.");
            return;
        }
        BlockPos.Mutable m = new BlockPos.Mutable();
        int end = Math.min(job.pos().length, runningAt + PER_TICK), changed = 0;
        try {
            for (int i = runningAt; i < end; i++) {
                m.set(job.pos()[i]);
                if (!world.isInBuildLimit(m)) continue;
                BlockState before = world.getBlockState(m);
                BlockState after = job.states()[i];
                if (before == after) continue;
                if (!job.undo()) {
                    collectPos[collectCount] = job.pos()[i];
                    collectStates[collectCount++] = before;
                }
                world.setBlockState(m, after, Block.NOTIFY_LISTENERS | Block.FORCE_STATE_AND_SKIP_CALLBACKS_AND_DROPS);
                changed++;
            }
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Schematic paste stopped", e);
            running = null;
            finishUndo(job);
            job.done().accept("Paste stopped after an error: " + e.getClass().getSimpleName());
            return;
        }
        runningAt = end;
        if (runningAt < job.pos().length) return;
        running = null;
        if (job.undo()) {
            job.done().accept("Undone: " + job.pos().length + " blocks put back.");
        } else {
            int n = collectCount;
            finishUndo(job);
            job.done().accept("Pasted: " + n + " blocks changed. Use .schem undo to put them back.");
        }
    }

    private static void finishUndo(Job job) {
        if (job.undo() || collectPos == null) return;
        undoPos = java.util.Arrays.copyOf(collectPos, collectCount);
        undoStates = java.util.Arrays.copyOf(collectStates, collectCount);
        undoDim = job.dim();
        collectPos = null;
        collectStates = null;
    }

    /** Whether a paste or undo is still running (for tests). */
    public static boolean busy() { return running != null || !jobs.isEmpty(); }
}
