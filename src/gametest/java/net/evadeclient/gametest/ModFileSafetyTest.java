package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.mods.AfterExitHelper;
import net.evadeclient.mods.ModManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Mod Browser's "finish when the game closes" helper (Windows), which replaced a hidden, encoded PowerShell
 * command: its plain command-line form, its file changes in a scratch mods folder, and that it refuses names that
 * would leave the mods folder. Pure file work: nothing is launched.
 */
public final class ModFileSafetyTest {
    private ModFileSafetyTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    public static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        try {
            Path mods = Files.createTempDirectory("evade-afterexit");
            Files.writeString(mods.resolve("old-1.0.jar"), "old");
            Files.writeString(mods.resolve("new-2.0.jar.staged"), "new");
            Files.writeString(mods.resolve("gone.jar"), "x");

            List<AfterExitHelper.Op> ops = List.of(new AfterExitHelper.Op("old-1.0.jar", "new-2.0.jar.staged", "new-2.0.jar"),
                    new AfterExitHelper.Op("gone.jar", null, null));
            check(AfterExitHelper.args(ops).equals(List.of("old-1.0.jar", "new-2.0.jar.staged", "new-2.0.jar", "gone.jar", "-", "-")),
                    "after-exit helper: plain, readable command-line arguments ('-' for none)");
            for (AfterExitHelper.Op op : ops) check(AfterExitHelper.apply(mods, op, 3), "after-exit helper: " + op + " done");
            check(!Files.exists(mods.resolve("old-1.0.jar")) && Files.readString(mods.resolve("new-2.0.jar")).equals("new")
                    && !Files.exists(mods.resolve("new-2.0.jar.staged")) && !Files.exists(mods.resolve("gone.jar")),
                    "after-exit helper: old jar deleted, update moved into place, removed jar gone");

            Path outside = mods.getParent().resolve("evade-afterexit-outside.jar");
            Files.writeString(outside, "keep");
            check(!AfterExitHelper.apply(mods, new AfterExitHelper.Op("../evade-afterexit-outside.jar", null, null), 1) && Files.exists(outside),
                    "after-exit helper: refuses a name outside the mods folder");
            check(!AfterExitHelper.apply(mods, new AfterExitHelper.Op(null, "new-2.0.jar", null), 1),
                    "after-exit helper: refuses a move with no target");
            check(!ModManager.safeFileName("..\\x.jar") && !ModManager.safeFileName("C:x.jar") && ModManager.safeFileName("sodium-0.6.jar"),
                    "mods folder names: ModManager and the helper share one check");
            Files.deleteIfExists(outside);
            Files.deleteIfExists(mods.resolve("new-2.0.jar"));
            Files.deleteIfExists(mods);
        } catch (java.io.IOException e) {
            throw new AssertionError("FAILED: after-exit helper scratch folder: " + e);
        }
    }
}
