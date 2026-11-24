package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.gui.WorldMapScreen;
import net.evadeclient.map.MapShading;
import net.evadeclient.map.MapStore;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StatusSetting;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * A full-screen map of the ground you have explored: the surface of every chunk your client has loaded, saved per
 * server (or world) and dimension, shared with the Minimap. Opens with M (Open Key), {@code .map} or the menu.
 * Terrain only: no caves, no entities, nothing you haven't loaded, and only the team mates a server shares (as on
 * the Minimap). No map in the Nether. A server that switches the Minimap off switches this off too.
 */
public class WorldMap extends Module {
    private static WorldMap instance;

    private final StatusSetting open = add((StatusSetting) new StatusSetting("World Map", WorldMap::statusLabel, "click: open")
            .onClick(() -> WorldMapScreen.open(mc.currentScreen))
            .describe("Open the map of the ground you've explored (also the Open Key and .map)."));
    public final KeySetting openKey = add(new KeySetting("Open Key", GLFW.GLFW_KEY_M)
            .describe("Opens and closes the World Map."));
    public final ModeSetting shading = add(new ModeSetting("Height Shading", "Smooth", MapShading.MODES)
            .describe("Shade slopes so hills stand out: Classic uses three steps like a vanilla map, Smooth one per block."));
    public final BooleanSetting waterDepth = add(new BooleanSetting("Water Depth", true)
            .describe("Deeper water is drawn darker."));
    public final BooleanSetting showWaypoints = add(new BooleanSetting("Show Waypoints", true)
            .describe("Your waypoints (and any the server adds) on the map, with their names."));
    public final BooleanSetting showTeammates = add(new BooleanSetting("Show Teammates", true)
            .describe("Team mates the server shares with Evade, as dots: only players already loaded near you, never anyone else."));
    private final BooleanSetting saveToDisk = add(new BooleanSetting("Save To Disk", true)
            .describe("Keep the explored map between sessions (config/evade/maps). Off keeps it only until you leave."));
    private final NumberSetting memoryLimit = add(new NumberSetting("Memory Limit", 128, 32, 512, 32)
            .describe("Most megabytes of map kept in memory; older areas are saved and reloaded when you look at them."));

    public WorldMap() {
        super("World Map", "Full-screen map of the ground you've explored, with your waypoints.", Category.VISUALS);
        instance = this;
        setEnabled(true);
    }

    public static WorldMap get() { return instance; }

    /** Whether explored regions are written to disk. */
    public static boolean saveToDisk() { return instance == null || instance.saveToDisk.get(); }

    /** Memory Limit in regions (one region is about 1 MB). */
    public static int memoryRegions() { return instance == null ? 128 : instance.memoryLimit.getInt(); }

    private static String statusLabel() {
        if (mc.world == null) return "Join a world";
        if (mc.world.getDimension().hasCeiling()) return "No map here";
        return "Open";
    }

    private boolean keyWasDown;

    /** The map was just closed with the Open Key: don't reopen it while that key is still held. */
    public void holdOff() { keyWasDown = true; }

    @Override
    public void onTick() {
        MapStore.tick();
        boolean down = openKey.isDown();
        if (down && !keyWasDown && mc.currentScreen == null) WorldMapScreen.open(null);
        keyWasDown = down;
    }

    @Override
    protected void onDisable() {
        MapStore.released();
    }

    @Override public List<String> commandAliases() { return List.of("map", "worldmap"); }

    @Override
    public List<String> commandHelp() { return List.of("§d.map§7 [clear] - open the World Map, or forget this dimension's map"); }

    @Override
    public void command(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> mc.send(() -> WorldMapScreen.open(null));
            case "clear" -> {
                if (!isEnabled()) throw new CommandError("Turn on the World Map first");
                int n = MapStore.clearCurrent();
                CommandManager.print("§7Forgot the map of this dimension (" + n + " chunks).");
            }
            default -> throw new CommandError("Usage: .map [clear]");
        }
    }

    /** Whether the map may open right now (on, allowed by the server, in a world). */
    public boolean usable() {
        return isEnabled() && !net.evadeclient.util.ServerRules.isDisabled(this) && EvadeClient.modules != null;
    }
}
