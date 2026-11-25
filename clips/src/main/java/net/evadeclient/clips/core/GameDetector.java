package net.evadeclient.clips.core;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Works out whether Minecraft is running, so clips get the right name and the replay buffer starts by itself.
 * Evade Clips only records Minecraft (with Evade Client): nothing else counts as a game. Minecraft Java is
 * recognised from its command line (Evade Client, Fabric, vanilla and other launchers); a Minecraft server,
 * an IDE or any other Java program is not a game, so it never starts the replay buffer.
 */
public final class GameDetector {
    /** A detected game: display name, process id. */
    public record Game(String name, long pid) {}

    /** The only game Evade Clips records. */
    public static final String MINECRAFT = "Minecraft";

    /** Command-line parts only a Minecraft <b>client</b> has (servers have none of them). */
    private static final List<String> MC_CLIENT = List.of("net.minecraft.client.main.main", "net.fabricmc.loader.impl.launch.knot.knotclient",
            "org.quiltmc.loader.impl.launch.knot.knotclient", "cpw.mods.bootstraplauncher", "--gamedir", "--assetsdir", "evadeclient");
    private static final List<String> MC_SERVER = List.of("server.jar", "minecraft_server", "nogui");
    /** The Java runtimes the official launcher installs, which only ever run the game (for when the command line is hidden). */
    private static final List<String> MC_RUNTIMES = List.of("/.minecraft/runtime/", "/minecraft launcher/runtime/", "/microsoft.4297127d64ec6_");

    private GameDetector() {}

    /**
     * "Minecraft" when the process is a Minecraft Java client, else null. {@code path} is the executable,
     * {@code cmdline} its full command line.
     */
    public static String identify(String path, String cmdline) {
        if (path == null) return null;
        String file = path.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String exe = file.endsWith(".exe") ? file.substring(0, file.length() - 4) : file;
        String cl = cmdline == null ? "" : cmdline.toLowerCase(Locale.ROOT);
        // Minecraft Java runs as java/javaw.
        if (exe.equals("java") || exe.equals("javaw")) return minecraftClient(path, cl) ? MINECRAFT : null;
        return null;
    }

    /**
     * A Java process is Minecraft only when its command line shows a game client (servers, IDEs and other Java
     * programs aren't). Windows doesn't show other programs' command lines, so there the official launcher's
     * own Java runtime is the sign.
     */
    private static boolean minecraftClient(String path, String cl) {
        if (cl.isBlank()) {
            String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
            for (String r : MC_RUNTIMES) if (p.contains(r)) return true;
            return false;
        }
        for (String s : MC_SERVER) if (cl.contains(s)) return false;
        for (String c : MC_CLIENT) if (cl.contains(c)) return true;
        return false;
    }

    /** A running Minecraft client (the most recently started one), if any. */
    public static Optional<Game> detect() {
        Game best = null;
        java.time.Instant newest = java.time.Instant.MIN;
        long self = ProcessHandle.current().pid();
        for (ProcessHandle ph : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
            if (ph.pid() == self) continue;
            ProcessHandle.Info info = ph.info();
            Optional<String> cmd = info.command();
            if (cmd.isEmpty()) continue;
            String name = identify(cmd.get(), info.commandLine().orElse(""));
            if (name == null) continue;
            java.time.Instant started = info.startInstant().orElse(java.time.Instant.EPOCH);
            if (best == null || started.isAfter(newest)) { best = new Game(name, ph.pid()); newest = started; }
        }
        if (best == null) {
            // Windows hides other programs' command lines: Minecraft started with any other Java is only known
            // because Evade Client told us its process id. It counts for exactly as long as that process runs.
            long pid = knownPid;
            if (pid > 0 && pid != self && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) best = new Game(MINECRAFT, pid);
        }
        return Optional.ofNullable(best);
    }

    private static volatile long knownPid;

    /** Evade Client's process (it sends it with every request to the app). */
    public static void minecraftPid(long pid) { knownPid = pid; }
}
