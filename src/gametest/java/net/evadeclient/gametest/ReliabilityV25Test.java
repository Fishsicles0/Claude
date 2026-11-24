package net.evadeclient.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.SafeStartScreen;
import net.evadeclient.map.MapStore;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.visuals.NoFog;
import net.evadeclient.module.visuals.ShinyPots;
import net.evadeclient.module.visuals.WorldMap;
import net.evadeclient.util.Guard;
import net.evadeclient.util.LauncherBridge;
import net.evadeclient.util.SafeStart;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v2.5 reliability: guarded hooks switch off only the module that failed, Safe Start's file half (failed-start
 * counter, the launcher's one-shot request, the suspect from a crash report, the kept module list), the Safe Start
 * screen, and the start-up and memory budgets (boot time and heap at the title screen against this machine's recent
 * runs, and the World Map's memory limit). Leaves every module, setting and file as it found them.
 */
public final class ReliabilityV25Test {
    private ReliabilityV25Test() {}

    /** Allowed growth over the median of recent runs, and an absolute slack so timing noise alone can't fail it. */
    private static final double BUDGET_GROWTH = 1.15;
    private static final long BOOT_SLACK_MS = 1500, HEAP_SLACK_MB = 24;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        guards(ctx);
        safeStartFiles(ctx);
        suspect(ctx);
        safeStartScreen(ctx);
        budgets(ctx);
        worldMapMemory(ctx);
    }

    // ------------------------------------------------------------------ guarded hooks

    private static void guards(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            ShinyPots sp = EvadeClient.modules.get(ShinyPots.class);
            boolean was = sp.isEnabled();
            int crashedBefore = ModuleManager.CRASHED.size();
            sp.setEnabled(true);
            Guard.run(ShinyPots.class, "gametest", () -> { throw new IllegalStateException("guard test"); });
            boolean off = !sp.isEnabled();
            boolean recorded = ModuleManager.CRASHED.stream().anyMatch(e -> e.startsWith(sp.getName() + " (gametest)") && e.contains("guard test"));
            boolean caught = Guard.CAUGHT.contains(sp.getName() + " (gametest)");
            // values fall back, and a failure while the module is already off is not a new crash
            boolean fb = Guard.test(ShinyPots.class, "gametest", () -> { throw new RuntimeException("x"); }, true)
                    && Guard.getFloat(ShinyPots.class, "gametest", () -> { throw new RuntimeException("x"); }, 0.5f) == 0.5f
                    && Guard.getInt(ShinyPots.class, "gametest", () -> { throw new RuntimeException("x"); }, 7) == 7
                    && "a".equals(Guard.get(ShinyPots.class, "gametest", () -> { throw new RuntimeException("x"); }, "a"));
            int crashedAfter = ModuleManager.CRASHED.size();
            // a body that works passes its value straight through
            boolean pass = Guard.getFloat(ShinyPots.class, "gametest", () -> 2f, 0f) == 2f;
            // leave the run's "no module crashed" check meaningful: take out only what this test caused
            ModuleManager.CRASHED.removeIf(e -> e.contains("guard test"));
            Guard.CAUGHT.remove(sp.getName() + " (gametest)");
            sp.setEnabled(was);
            return off + "|" + recorded + "|" + caught + "|" + fb + "|" + (crashedAfter - crashedBefore) + "|" + pass;
        });
        check(r.equals("true|true|true|true|1|true"),
                "Guarded hook: an exception switches off only its module, is recorded once, and values fall back (" + r + ")");
        int guarded = ctx.computeOnClient(c -> countGuardedMixins());
        check(guarded >= 25, "Module-owned mixin hooks run through Guard (" + guarded + " mixin classes)");
    }

    /** Evade mixin classes whose code calls Guard (read from the mixin config and the class files). */
    private static int countGuardedMixins() {
        int n = 0;
        try (var in = EvadeClient.class.getClassLoader().getResourceAsStream("evade.mixins.json")) {
            JsonObject cfg = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> names = new ArrayList<>();
            for (String k : new String[]{"mixins", "client"}) {
                if (cfg.has(k)) for (var e : cfg.getAsJsonArray(k)) names.add(e.getAsString());
            }
            for (String name : names) {
                String res = "net/evadeclient/mixin/" + name.replace('.', '/') + ".class";
                try (var c = EvadeClient.class.getClassLoader().getResourceAsStream(res)) {
                    if (c == null) continue;
                    String bytes = new String(c.readAllBytes(), StandardCharsets.ISO_8859_1);
                    if (bytes.contains("net/evadeclient/util/Guard")) n++;
                }
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't read the mixin list", e);
        }
        return n;
    }

    // ------------------------------------------------------------------ Safe Start files

    private static void safeStartFiles(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            try {
                Path d = Files.createTempDirectory("evade-safestart");
                long now = 1_800_000_000_000L;
                StringBuilder b = new StringBuilder();
                SafeStart.Decision d1 = SafeStart.begin(d, now, true);          // first start
                SafeStart.Decision d2 = SafeStart.begin(d, now + 1000, true);   // the first never reached the title
                SafeStart.Decision d3 = SafeStart.begin(d, now + 2000, true);   // nor the second: safe start
                b.append(d1.safe()).append(d1.failedStarts()).append(',')
                        .append(d2.safe()).append(d2.failedStarts()).append(',')
                        .append(d3.safe()).append(d3.reason()).append(d3.failedStarts()).append(',');
                SafeStart.Decision noCount = SafeStart.begin(d, now + 3000, false);
                b.append(noCount.safe()).append(',');
                SafeStart.markReached(d);
                b.append(Files.exists(d.resolve(SafeStart.MARKER))).append(',');
                SafeStart.Decision fresh = SafeStart.begin(d, now + 4000, true);
                b.append(fresh.safe()).append(fresh.failedStarts()).append(',');
                SafeStart.markReached(d);
                // the launcher's request, exactly as the launcher writes it
                Files.writeString(d.resolve(SafeStart.REQUEST), "{\"requested\": " + (now - 60_000) + ", \"by\": \"launcher\"}");
                SafeStart.Decision l = SafeStart.begin(d, now, true);
                b.append(l.safe()).append(l.reason()).append(Files.exists(d.resolve(SafeStart.REQUEST))).append(',');
                SafeStart.markReached(d);
                // acted on once: the next start is normal
                b.append(SafeStart.begin(d, now + 5000, true).safe()).append(',');
                SafeStart.markReached(d);
                // stale (11 minutes), from the future, or damaged: ignored, and removed
                Files.writeString(d.resolve(SafeStart.REQUEST), "{\"requested\": " + (now - 11 * 60_000) + ", \"by\": \"launcher\"}");
                boolean stale = SafeStart.takeRequest(d, now);
                Files.writeString(d.resolve(SafeStart.REQUEST), "{\"requested\": " + (now + 5 * 60_000) + ", \"by\": \"launcher\"}");
                boolean future = SafeStart.takeRequest(d, now);
                Files.writeString(d.resolve(SafeStart.REQUEST), "not json");
                boolean junk = SafeStart.takeRequest(d, now);
                b.append(stale).append(future).append(junk).append(Files.exists(d.resolve(SafeStart.REQUEST))).append(',');
                // the kept list merges with one an earlier safe start left
                SafeStart.Suspect none = SafeStart.Suspect.NONE;
                SafeStart.writeState(d, new SafeStart.Decision(true, "crash", 2), List.of("Zoom", "Freelook"), none, now);
                SafeStart.writeState(d, new SafeStart.Decision(true, "crash", 3), List.of("Keystrokes"), none, now);
                JsonArray mods = JsonParser.parseString(Files.readString(d.resolve(SafeStart.STATE))).getAsJsonObject().getAsJsonArray("modules");
                b.append(mods.size());
                try (var s = Files.list(d)) { for (Path p : s.toList()) Files.deleteIfExists(p); }
                Files.deleteIfExists(d);
                return b.toString();
            } catch (Exception e) {
                return "error " + e;
            }
        });
        check(r.equals("false0,false1,truecrash2,false,false,false0,truelauncherfalse,false,falsefalsefalsefalse,3"),
                "Safe Start: two starts that never reach the title screen trigger it; the launcher's request works once and stale ones are ignored (" + r + ")");

        String live = ctx.computeOnClient(c -> {
            Path dir = LauncherBridge.dir();
            return (dir != null && !Files.exists(dir.resolve(SafeStart.MARKER))) + "|" + SafeStart.decision().safe() + "|"
                    + (SafeStart.readyMillis() > 0) + "|" + (SafeStart.heapAtTitleMb() > 0);
        });
        check(live.equals("true|false|true|true"), "This start reached the title screen: marker cleared, not a safe start, boot time and heap measured (" + live + ")");
    }

    private static void suspect(ClientGameTestContext ctx) {
        String r = ctx.computeOnClient(c -> {
            String report = """
                    ---- Minecraft Crash Report ----
                    Description: Rendering overlay
                    java.lang.NullPointerException: boom
                    \tat net.evadeclient.module.visuals.NoFog.apply(NoFog.java:120)
                    \tat net.minecraft.client.render.FogRenderer.handler$zzz000$evade$evade$clearFog(FogRenderer.java:1)
                    \tat net.evadeclient.mixin.FogRendererMixin.evade$clearFog(FogRendererMixin.java:40)
                    """;
            SafeStart.Suspect s = SafeStart.suspectIn(report, EvadeClient.modules.all(), "crash-test.txt");
            SafeStart.Suspect none = SafeStart.suspectIn("[12:00:00] [Render thread/INFO]: all good", EvadeClient.modules.all(), "x.log.gz");
            return s.mixin() + "|" + s.modules() + "|" + none.any();
        });
        String noFog = ctx.computeOnClient(c -> EvadeClient.modules.get(NoFog.class).getName());
        check(r.equals("FogRendererMixin|[" + noFog + "]|false"), "Safe Start names the suspect mixin and module from a crash report (" + r + ")");
    }

    private static void safeStartScreen(ClientGameTestContext ctx) {
        // A pretend safe start that switched Shiny Pots off: the screen lists it and turns it back on.
        boolean wasOn = ctx.computeOnClient(c -> {
            ShinyPots sp = EvadeClient.modules.get(ShinyPots.class);
            boolean was = sp.isEnabled();
            sp.setEnabled(false);
            SafeStart.writeState(LauncherBridge.dir(), new SafeStart.Decision(true, "launcher", 0), List.of(sp.getName()), SafeStart.Suspect.NONE, System.currentTimeMillis());
            return was;
        });
        ctx.setScreen(() -> new SafeStartScreen(null));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-safe-start");
        String r = ctx.computeOnClient(c -> {
            boolean open = c.currentScreen instanceof SafeStartScreen;
            List<String> waiting = SafeStart.waitingModules();
            ShinyPots sp = EvadeClient.modules.get(ShinyPots.class);
            SafeStart.restore(sp.getName());
            boolean back = sp.isEnabled() && SafeStart.state() == null; // all back on: the list is cleared
            sp.setEnabled(false);
            return open + "|" + waiting + "|" + back;
        });
        ctx.setScreen(() -> null);
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(ShinyPots.class).setEnabled(wasOn);
            SafeStart.finish();
        });
        String name = ctx.computeOnClient(c -> EvadeClient.modules.get(ShinyPots.class).getName());
        check(r.equals("true|[" + name + "]|true"), "Safe Start screen lists the switched-off modules and turns them back on (" + r + ")");
    }

    // ------------------------------------------------------------------ budgets

    /**
     * Boot time (JVM start to the first title screen) and heap in use there, against the median of this machine's last
     * five passing runs: more than 15% over (plus a little slack for noise) fails. The history lives in the test's game
     * folder ({@code evade-test-budgets.json}); the first runs only record.
     */
    private static void budgets(ClientGameTestContext ctx) {
        long boot = ctx.computeOnClient(c -> SafeStart.readyMillis());
        long heap = ctx.computeOnClient(c -> SafeStart.heapAtTitleMb());
        Path file = FabricLoader.getInstance().getGameDir().resolve("evade-test-budgets.json");
        List<long[]> runs = new ArrayList<>();
        try {
            if (Files.isRegularFile(file)) for (var e : JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("runs")) {
                JsonObject o = e.getAsJsonObject();
                runs.add(new long[]{o.get("bootMs").getAsLong(), o.get("heapMb").getAsLong()});
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Budget history unreadable, starting over", e);
            runs.clear();
        }
        EvadeClient.LOG.info("[gametest] Start-up: {} ms to the title screen, {} MB heap in use there ({} earlier runs)", boot, heap, runs.size());
        if (runs.size() >= 3) {
            long medBoot = median(runs, 0), medHeap = median(runs, 1);
            check(boot <= Math.max(medBoot * BUDGET_GROWTH, medBoot + BOOT_SLACK_MS),
                    "Boot time within budget: " + boot + " ms (median of recent runs " + medBoot + " ms, +15%)");
            check(heap <= Math.max(medHeap * BUDGET_GROWTH, medHeap + HEAP_SLACK_MB),
                    "Heap at the title screen within budget: " + heap + " MB (median of recent runs " + medHeap + " MB, +15%)");
        } else {
            check(boot > 0 && heap > 0, "Start-up measured (" + boot + " ms, " + heap + " MB); budget starts after 3 runs");
        }
        runs.add(new long[]{boot, heap});
        while (runs.size() > 5) runs.remove(0);
        JsonObject out = new JsonObject();
        JsonArray arr = new JsonArray();
        for (long[] x : runs) {
            JsonObject o = new JsonObject();
            o.addProperty("bootMs", x[0]);
            o.addProperty("heapMb", x[1]);
            arr.add(o);
        }
        out.add("runs", arr);
        try { Files.writeString(file, out.toString()); } catch (Exception e) { EvadeClient.LOG.warn("Couldn't save the budget history", e); }
    }

    private static long median(List<long[]> runs, int i) {
        long[] v = runs.stream().mapToLong(x -> x[i]).sorted().toArray();
        return v[v.length / 2];
    }

    /** The World Map keeps at most Memory Limit regions in memory, however many get touched. */
    private static void worldMapMemory(ClientGameTestContext ctx) {
        boolean was = ctx.computeOnClient(c -> {
            WorldMap wm = EvadeClient.modules.get(WorldMap.class);
            boolean on = wm.isEnabled();
            wm.setEnabled(true);
            return on;
        });
        ctx.waitTicks(5);
        int cap = ctx.computeOnClient(c -> MapStore.capacity());
        String r = ctx.computeOnClient(c -> {
            try {
                Method region = MapStore.class.getDeclaredMethod("regionFor", int.class, int.class, boolean.class);
                region.setAccessible(true);
                for (int i = 0; i < cap + 12; i++) region.invoke(null, 4000 + i, 4000, true);
                return "ok " + regions();
            } catch (Exception e) {
                return "error " + e;
            }
        });
        ctx.waitTicks(5);
        int after = ctx.computeOnClient(c -> {
            try { return regions(); } catch (Exception e) { return -1; }
        });
        ctx.runOnClient(c -> EvadeClient.modules.get(WorldMap.class).setEnabled(was));
        check(r.startsWith("ok ") && after >= 0 && after <= cap,
                "World Map honours its Memory Limit (" + r.substring(Math.min(r.length(), 3)) + " regions touched, " + after + " kept, limit " + cap + ")");
    }

    private static int regions() throws ReflectiveOperationException {
        Field f = MapStore.class.getDeclaredField("regions");
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(null)).size();
    }
}
