package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.WorldMapScreen;
import net.evadeclient.map.MapStore;
import net.evadeclient.module.hud.MinimapHud;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.module.visuals.WorldMap;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Benchmark;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * World Map and the shared explored-map cache: chunks you load are mapped (and only those), the map opens, shows
 * coordinates and biome under the cursor (coordinates hidden with reduced debug info), a click adds a waypoint and a
 * right-click opens its menu, tiles survive a save and reload, the Minimap reads explored tiles beyond the loaded
 * area, a server that switches the Minimap off switches the World Map off too, and the Nether isn't mapped.
 */
public final class MapsTest {
    private MapsTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static WorldMapScreen screen(ClientGameTestContext ctx) {
        return ctx.computeOnClient(c -> c.currentScreen instanceof WorldMapScreen s ? s : null);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        boolean miniWas = ctx.computeOnClient(c -> EvadeClient.modules.get(MinimapHud.class).isEnabled());
        ctx.runOnClient(c -> {
            EvadeClient.modules.get(WorldMap.class).setEnabled(true);
            EvadeClient.modules.get(MinimapHud.class).setEnabled(true);
            for (Setting<?> s : EvadeClient.modules.get(MinimapHud.class).getSettings())
                if (s.getName().equals("Chunk Grid")) ((BooleanSetting) s).set(true);
            MapStore.takeStats();
        });
        server.runCommand("tp @a 8 -60 8 0 90");
        sp.getClientWorld().waitForChunksRender();
        ctx.waitTicks(60);
        int[] home = ctx.computeOnClient(c -> new int[]{MapStore.revision(0, 0), MapStore.exploredChunks(), MapStore.column(8, 8)});
        check(home[0] != 0 && home[1] > 20 && home[2] != 0, "World Map maps the loaded chunks around you (" + home[1] + " chunks)");

        // Explore a second spot 30 chunks east.
        server.runCommand("tp @a 488 -60 8 0 90");
        sp.getClientWorld().waitForChunksRender();
        ctx.waitTicks(60);
        String stats = ctx.computeOnClient(c -> MapStore.takeStats());
        EvadeClient.LOG.info("[gametest] MAPCOST {}", stats);
        boolean[] spots = ctx.computeOnClient(c -> new boolean[]{
                MapStore.revision(488 >> 4, 0) != 0, MapStore.revision(0, 0) != 0,
                MapStore.revision(5000 >> 4, 5000 >> 4) == 0 && MapStore.column(5000, 5000) == 0,
                MapStore.revision(244 >> 4, 3000 >> 4) == 0});
        check(spots[0] && spots[1], "Both explored spots are on the map");
        check(spots[2] && spots[3], "Chunks never loaded stay blank");
        double us = Double.parseDouble(stats.substring(0, stats.indexOf(' ')));
        check(us < 3000, "Mapping stays within its per-tick budget (" + stats + ")");
        // The first spot is out of range now but still explored: the Minimap draws it from the shared tiles.
        boolean unloadedButExplored = ctx.computeOnClient(c -> !c.world.isChunkLoaded(0, 0) && MapStore.revision(0, 0) != 0);
        check(unloadedButExplored || ctx.computeOnClient(c -> c.options.getClampedViewDistance() >= 30),
                "Explored tiles outlive the loaded chunks (the Minimap reads them beyond the loaded area)");
        int painted = ctx.computeOnClient(c -> EvadeClient.modules.get(MinimapHud.class).paintedChunks());
        check(painted > 0, "Minimap paints from the shared map (" + painted + " chunks)");

        // Open the World Map.
        ctx.runOnClient(c -> WorldMapScreen.open(null));
        ctx.waitTicks(10);
        WorldMapScreen map = screen(ctx);
        check(map != null, "The World Map opens");
        ctx.runOnClient(c -> map.setViewForTest(260, 8, 0.75f));
        ctx.waitTicks(20);
        check(ctx.computeOnClient(c -> map.texturesForTest()) > 0, "The World Map uploads region tiles");
        float[] at = ctx.computeOnClient(c -> map.screenAtForTest(490.5, 8.5));
        List<String> info = ctx.computeOnClient(c -> map.cursorInfo(at[0], at[1]));
        check(info.size() == 2 && info.get(0).startsWith("X 490") && info.get(0).endsWith("Z 8") && !info.get(1).isBlank(),
                "Cursor shows coordinates and the biome of a loaded chunk " + info);
        float[] far = ctx.computeOnClient(c -> map.screenAtForTest(260.5, 3000.5));
        check(ctx.computeOnClient(c -> map.cursorInfo(far[0], far[1])).isEmpty(), "Nothing is shown over unexplored ground");
        float[] old = ctx.computeOnClient(c -> map.screenAtForTest(8.5, 8.5));
        List<String> oldInfo = ctx.computeOnClient(c -> map.cursorInfo(old[0], old[1]));
        check(oldInfo.size() == 1 && oldInfo.get(0).equals("X 8  Z 8"), "Explored but unloaded ground: coordinates only, no biome " + oldInfo);
        ctx.takeScreenshot("world-map");

        // Reduced debug info hides the coordinates under the cursor.
        server.runCommand("gamerule reduced_debug_info true");
        ctx.waitTicks(5);
        List<String> hidden = ctx.computeOnClient(c -> map.cursorInfo(at[0], at[1]));
        check(hidden.equals(List.of("Coordinates hidden")), "Reduced debug info hides cursor coordinates " + hidden);
        ctx.takeScreenshot("world-map-reduced-debug");
        server.runCommand("gamerule reduced_debug_info false");
        ctx.waitTicks(5);

        // A click adds a waypoint there; a right-click on it opens its menu.
        int before = ctx.computeOnClient(c -> EvadeClient.modules.get(Waypoints.class).allInWorld().size());
        float[] spot = ctx.computeOnClient(c -> map.screenAtForTest(470.5, 20.5));
        ctx.runOnClient(c -> {
            map.mouseClicked(new Click(spot[0], spot[1], new MouseInput(0, 0)), false);
            map.mouseReleased(new Click(spot[0], spot[1], new MouseInput(0, 0)));
        });
        Waypoints.Point added = ctx.computeOnClient(c -> {
            var all = EvadeClient.modules.get(Waypoints.class).allInWorld();
            return all.size() == before + 1 ? all.get(all.size() - 1) : null;
        });
        check(added != null && Math.abs(added.x() - 470) <= 1 && Math.abs(added.z() - 20) <= 1, "Clicking the map adds a waypoint there (" + added + ")");
        ctx.runOnClient(c -> map.mouseClicked(new Click(spot[0], spot[1], new MouseInput(1, 0)), false));
        ctx.waitTicks(2);
        check(added.equals(ctx.computeOnClient(c -> map.menuForTest())), "Right-clicking a waypoint opens its menu");
        ctx.takeScreenshot("world-map-waypoint-menu");
        ctx.runOnClient(c -> { map.mouseClicked(new Click(2, 200, new MouseInput(0, 0)), false); EvadeClient.modules.get(Waypoints.class).deletePoint(added); });

        // Save and reload from disk.
        int[] probe = ctx.computeOnClient(c -> new int[]{MapStore.column(490, 8), MapStore.column(8, 8), MapStore.column(-20, 30)});
        ctx.runOnClient(c -> MapStore.flushForTest());
        Path dir = ctx.computeOnClient(c -> MapStore.folderForTest());
        long files;
        try (var s = Files.list(dir)) { files = s.filter(p -> p.toString().endsWith(".evmap")).count(); } catch (Exception e) { files = 0; }
        check(files > 0, "Explored regions are saved to " + dir + " (" + files + " files)");
        int afterDrop = ctx.computeOnClient(c -> { MapStore.reloadForTest(); return MapStore.exploredChunks(); });
        check(afterDrop == 0, "Map memory is dropped before the reload");
        ctx.runOnClient(c -> { MapStore.column(490, 8); MapStore.column(8, 8); MapStore.column(-20, 30); });
        ctx.waitTicks(3);
        ctx.runOnClient(c -> MapStore.settleForTest());
        ctx.waitTicks(3);
        int[] again = ctx.computeOnClient(c -> new int[]{MapStore.column(490, 8), MapStore.column(8, 8), MapStore.column(-20, 30)});
        check(again[1] == probe[1] && again[2] == probe[2] && again[1] != 0, "Saved tiles read back the same after a reload");

        // A server that switches the Minimap off switches the World Map off too (and closes it).
        ctx.runOnClient(c -> ServerRules.apply("{\"disable\":[\"Minimap\"]}".getBytes(StandardCharsets.UTF_8)));
        ctx.waitTicks(3);
        boolean[] blocked = ctx.computeOnClient(c -> new boolean[]{EvadeClient.modules.get(WorldMap.class).isEnabled(), c.currentScreen instanceof WorldMapScreen});
        ctx.runOnClient(c -> WorldMapScreen.open(null));
        ctx.waitTicks(2);
        boolean reopened = ctx.computeOnClient(c -> c.currentScreen instanceof WorldMapScreen);
        ctx.runOnClient(c -> ServerRules.apply("{\"disable\":[]}".getBytes(StandardCharsets.UTF_8)));
        ctx.waitTicks(3);
        check(!blocked[0] && !blocked[1] && !reopened, "Disabling the Minimap by server rules disables the World Map");
        check(ctx.computeOnClient(c -> EvadeClient.modules.get(WorldMap.class).isEnabled()), "Lifting the rule brings the World Map back");

        // The Nether is never mapped.
        server.runCommand("execute in minecraft:the_nether run tp @a 0 64 0");
        ctx.waitTicks(80);
        boolean netherMapped = ctx.computeOnClient(c -> MapStore.mapping());
        ctx.runOnClient(c -> WorldMapScreen.open(null));
        ctx.waitTicks(5);
        ctx.takeScreenshot("world-map-nether");
        ctx.runOnClient(c -> c.setScreen(null));
        check(!netherMapped, "No map in the Nether");
        server.runCommand("execute in minecraft:overworld run tp @a 0 -60 0 0 0");
        sp.getClientWorld().waitForChunksRender();
        ctx.waitTicks(20);

        // FPS cost: a 5 s benchmark with the map off, then with the World Map and the Minimap on.
        ctx.runOnClient(c -> { EvadeClient.modules.get(WorldMap.class).setEnabled(false); EvadeClient.modules.get(MinimapHud.class).setEnabled(false); Benchmark.setQuiet(true); });
        ctx.waitTicks(10);
        ctx.runOnClient(c -> Benchmark.start(5));
        ctx.waitTicks(20 * 6 + 10);
        Benchmark.Result off = ctx.computeOnClient(c -> Benchmark.last());
        ctx.runOnClient(c -> { EvadeClient.modules.get(WorldMap.class).setEnabled(true); EvadeClient.modules.get(MinimapHud.class).setEnabled(true); });
        ctx.waitTicks(40);
        ctx.runOnClient(c -> { MapStore.takeStats(); Benchmark.start(5); });
        ctx.waitTicks(20 * 6 + 10);
        Benchmark.Result on = ctx.computeOnClient(c -> Benchmark.last());
        String onStats = ctx.computeOnClient(c -> MapStore.takeStats());
        ctx.runOnClient(c -> Benchmark.setQuiet(false));
        if (off != null && on != null) EvadeClient.LOG.info("[gametest] MAPFPS off {} FPS (1% low {}), on {} FPS (1% low {}); mapping {}",
                String.format(Locale.ROOT, "%.0f", off.avgFps()), String.format(Locale.ROOT, "%.0f", off.low1()),
                String.format(Locale.ROOT, "%.0f", on.avgFps()), String.format(Locale.ROOT, "%.0f", on.low1()), onStats);
        check(on != null && on.avgFps() > 0, "Benchmark runs with the maps on");

        ctx.runOnClient(c -> {
            for (Setting<?> s : EvadeClient.modules.get(MinimapHud.class).getSettings())
                if (s.getName().equals("Chunk Grid")) ((BooleanSetting) s).set(false);
            EvadeClient.modules.get(MinimapHud.class).setEnabled(miniWas);
        });
    }
}
