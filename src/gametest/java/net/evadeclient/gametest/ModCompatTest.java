package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.config.ModShare;
import net.evadeclient.module.Module;
import net.evadeclient.module.misc.Replay;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.ModCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Add-on compatibility and "nothing locked": the launcher's switch-off request, the add-on overlap and hotkey
 * notices (with made-up "loaded" mods), every module switching off and back on, and every module's settings
 * exporting to a mod code and importing back.
 */
public final class ModCompatTest {
    private ModCompatTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        // 1. "Turn Evade's off" from the launcher: read once, applied, file removed; unknown names skipped.
        String r = ctx.computeOnClient(c -> {
            Module replay = EvadeClient.modules.get(Replay.class);
            boolean was = replay.isEnabled();
            replay.setEnabled(true);
            try {
                Path f = FabricLoader.getInstance().getGameDir().resolve(".evade").resolve("gametest-" + ModCompat.OFF_FILE);
                Files.createDirectories(f.getParent());
                Files.writeString(f, "{\"off\":[\"Replay\",\"No Such Module\"]}", StandardCharsets.UTF_8);
                List<String> off = ModCompat.applyOffRequest(f);
                String out = off + " " + replay.isEnabled() + " " + Files.exists(f);
                return out;
            } catch (Exception e) {
                return e.toString();
            } finally {
                replay.setEnabled(was);
            }
        });
        check(r.equals("[Replay] false false"), "Mod compat: the launcher's switch-off request turns Evade's Replay off once (" + r + ")");

        // 2. Overlaps: Flashback next to Evade's Replay (on) is flagged; nothing when the add-on isn't there.
        String ov = ctx.computeOnClient(c -> {
            Module replay = EvadeClient.modules.get(Replay.class);
            boolean was = replay.isEnabled();
            replay.setEnabled(true);
            int with = ModCompat.overlaps(id -> id.equals("flashback")).size();
            int without = ModCompat.overlaps(id -> false).size();
            replay.setEnabled(false);
            int off = ModCompat.overlaps(id -> id.equals("flashback")).size();
            replay.setEnabled(was);
            return with + "," + without + "," + off;
        });
        check(ov.equals("1,0,0"), "Mod compat: Flashback overlaps Evade's Replay only while Replay is on (" + ov + ")");

        // 3. Litematica's own Left Alt hotkey vs Evade's Freelook default, which the Keybinds page can't see.
        List<String> keys = ctx.computeOnClient(c -> ModCompat.hotkeyClashes(Keybinds.all(), id -> id.equals("litematica")));
        check(keys.stream().anyMatch(k -> k.startsWith("Freelook") && k.contains("Litematica")), "Mod compat: Litematica's Left Alt clashes with Freelook (" + keys + ")");
        check(ctx.computeOnClient(c -> ModCompat.hotkeyClashes(Keybinds.all(), id -> false)).isEmpty(), "Mod compat: no hotkey clashes without those mods");

        // 4. Nothing locked: every module switches off and back on, and its settings export and import.
        List<String> stuck = ctx.computeOnClient(c -> {
            List<String> bad = new ArrayList<>();
            for (Module m : EvadeClient.modules.all()) {
                boolean was = m.isEnabled();
                try {
                    m.setEnabled(false);
                    if (m.isEnabled()) bad.add(m.getName() + " (won't switch off)");
                } catch (RuntimeException e) {
                    bad.add(m.getName() + " (" + e + ")");
                } finally {
                    m.setEnabled(was);
                }
            }
            return bad;
        });
        check(stuck.isEmpty(), "Nothing locked: every module can be switched off (" + stuck + ")");
        List<String> noCode = ctx.computeOnClient(c -> {
            List<String> bad = new ArrayList<>();
            for (Module m : EvadeClient.modules.all()) {
                String code = ModShare.code(m);
                if (code == null) { bad.add(m.getName() + " (no code)"); continue; }
                ModShare.Result res = ModShare.apply(code, m);
                if (!res.ok() || res.module() != m) bad.add(m.getName() + " (" + res.error() + ")");
            }
            return bad;
        });
        check(noCode.isEmpty(), "Nothing locked: every module's settings export to a mod code and import back (" + noCode + ")");
        ctx.waitTicks(2);
    }
}
