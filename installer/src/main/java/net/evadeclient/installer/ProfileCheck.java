package net.evadeclient.installer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * After Play: did the Minecraft Launcher start the Evade instance's profile, or something else (the player's normal
 * game, another profile, an old one-file Evade in {@code .minecraft/mods})? The Evade mod's heartbeat in the instance
 * folder is the proof the right one runs; a game log written elsewhere, or a Minecraft process with no heartbeat,
 * means another profile was started.
 */
final class ProfileCheck {
    private ProfileCheck() {}

    /** No heartbeat this long after Play while Minecraft runs: another profile was started. */
    static final long NO_BEAT_MS = 90_000;
    /** Another game folder's log written this long after Play (and no heartbeat): another profile was started. */
    static final long OTHER_LOG_MS = 20_000;

    /** Old Evade jars from one-file installs, in the normal {@code .minecraft/mods} (which every plain profile loads). */
    static List<Path> oldEvadeJars(Path mc) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mc.resolve("mods"), "*.jar")) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.startsWith("evade-client") || n.startsWith("evadeclient") || n.startsWith("evade_client")) out.add(p);
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Switches the old jars off (renamed to .jar.disabled, so nothing is lost). Returns how many. */
    static int disable(List<Path> jars) throws IOException {
        int n = 0;
        for (Path p : jars) {
            if (!Files.isRegularFile(p)) continue;
            Path to = p.resolveSibling(p.getFileName() + ".disabled");
            if (Files.exists(to)) to = p.resolveSibling(p.getFileName() + "." + System.currentTimeMillis() + ".disabled");
            InstallerCore.move(p, to);
            n++;
        }
        return n;
    }

    /** Whether some Minecraft game process runs (best effort: other users' process details may be hidden). */
    static boolean minecraftRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(p -> {
                ProcessHandle.Info in = p.info();
                String cmd = in.commandLine().orElse(in.command().orElse("")).toLowerCase(Locale.ROOT);
                return cmd.contains("net.minecraft.client.main.main") || cmd.contains("knotclient")
                        || (cmd.contains("java") && cmd.contains("--gamedir"));
            });
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A game folder other than {@code selected} whose log was written after {@code since}: the normal .minecraft or
     * another instance. Null when there's none.
     */
    static Path otherGameStarted(Path mc, List<Launcher.Instance> all, Launcher.Instance selected, long since) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(mc);
        for (Launcher.Instance i : all) if (i != selected) dirs.add(i.dir);
        for (Path d : dirs) {
            Path log = d.resolve("logs").resolve("latest.log");
            try {
                if (Files.isRegularFile(log) && Files.getLastModifiedTime(log).toMillis() > since && !same(d, selected.dir)) return d;
            } catch (IOException ignored) { }
        }
        return null;
    }

    static boolean same(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The verdict at {@code now} for a Play at {@code launchedAt}: null while all is well (or it's too early to say),
     * else why we think another profile was started.
     */
    static String verdict(GameState.Session s, Path instanceDir, long launchedAt, long now, Path otherLog, boolean mcRunning) {
        if (s != null && s.started() >= launchedAt) {
            if (s.gameDir() != null && !same(Path.of(s.gameDir()), instanceDir)) return "The running Evade uses another game folder.";
            return null;
        }
        long t = now - launchedAt;
        if (otherLog != null && t > OTHER_LOG_MS) return "A game started in " + otherLog.getFileName() + " instead.";
        if (mcRunning && t > NO_BEAT_MS) return "Minecraft is running, but not this Evade instance.";
        return null;
    }
}
