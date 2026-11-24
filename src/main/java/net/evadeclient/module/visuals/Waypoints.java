package net.evadeclient.module.visuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.evadeclient.EvadeClient;
import net.evadeclient.api.ApiWaypoints;
import net.evadeclient.command.CommandManager;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Label;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Projection;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Saved locations per server (or singleplayer world) and dimension, drawn as a box and beam with a label.
 * {@code .waypoint add <name> [x y z]}, {@code del}, {@code list}, {@code near [n]}, {@code color <name> <colour>},
 * {@code hide|show <name|all>}, {@code rename <old> <new>}, {@code share <name>}, {@code target <name|off>},
 * {@code clear}. Your latest deaths are kept as Death, Death 2, ... (newest first), at the spot the server reports.
 * Overworld points also show in the Nether (and the other way round) at the matching portal coordinates.
 *
 * <p>Saved in {@code config/evade/waypoints.json}. A file that can't be read is never overwritten: a copy is kept
 * next to it and saving stops (with a chat warning) until {@code .waypoint clear} starts a new one.
 */
public class Waypoints extends Module {
    /**
     * One saved point. {@code color} is ARGB, or 0 for the default colour; {@code hidden} points are kept but not
     * drawn; {@code created} is when it was made (epoch ms, 0 if unknown). Files written before these fields existed
     * load with 0, false and 0.
     */
    public record Point(String name, String dim, int x, int y, int z, int color, boolean hidden, long created,
                        String icon, int beam, int range) {
        /** {@link #beam}: follow the Beams setting, always draw one, never draw one. */
        public static final int BEAM_DEFAULT = 0, BEAM_ON = 1, BEAM_OFF = 2;

        public Point(String name, String dim, int x, int y, int z) { this(name, dim, x, y, z, 0, false, 0L); }

        public Point(String name, String dim, int x, int y, int z, int color, boolean hidden) {
            this(name, dim, x, y, z, color, hidden, 0L);
        }

        public Point(String name, String dim, int x, int y, int z, int color, boolean hidden, long created) {
            this(name, dim, x, y, z, color, hidden, created, null, BEAM_DEFAULT, 0);
        }

        public Point withColor(int argb) { return new Point(name, dim, x, y, z, argb, hidden, created, icon, beam, range); }
        public Point withHidden(boolean hide) { return new Point(name, dim, x, y, z, color, hide, created, icon, beam, range); }
        public Point withName(String newName) { return new Point(newName, dim, x, y, z, color, hidden, created, icon, beam, range); }
        public Point at(String newDim, int nx, int ny, int nz) { return new Point(name, newDim, nx, ny, nz, color, hidden, created, icon, beam, range); }
        /** An icon from {@link net.evadeclient.util.WaypointIcons} (null for none). */
        public Point withIcon(String newIcon) { return new Point(name, dim, x, y, z, color, hidden, created, newIcon, beam, range); }
        public Point withBeam(int newBeam) { return new Point(name, dim, x, y, z, color, hidden, created, icon, newBeam, range); }
        /** Hide this point further than {@code blocks} away (0: use Max Distance). */
        public Point withRange(int blocks) { return new Point(name, dim, x, y, z, color, hidden, created, icon, beam, blocks); }
    }

    /**
     * A point as drawn this tick: its own position, or for a point from the other dimension (Overworld and Nether
     * only) the matching position here, with {@code converted} set. {@code distance} is from your feet;
     * {@code target} marks the point set with {@code .wp target}. {@code server} marks a point a server added through
     * the Evade Server API (never saved; {@code beam} off means it asked for no beam). Sorted far to near, so near
     * labels end up on top.
     */
    public record Shown(Point point, int x, int y, int z, boolean converted, int color, double distance, boolean target,
                        boolean server, boolean beam) {
        public Shown(Point point, int x, int y, int z, boolean converted, int color, double distance, boolean target) {
            this(point, x, y, z, converted, color, distance, target, false, true);
        }
    }

    public static final int DEFAULT_COLOR = 0xFFE11D2E, DEATH_COLOR = 0xFFFF7A85;
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end";
    /** "Death", "Death 2": the names this mod gives death points (and "Death 14:05 (2)" from older versions). */
    private static final Pattern DEATH_NAME = Pattern.compile("Death( \\d{1,2}:\\d{2})?( \\(\\d+\\))?|Death \\d{1,2}");
    private static final int MAX_NAME = 32, NEAR_DEFAULT = 5, NEAR_MAX = 20;
    /** How much the Nether's coordinates are scaled in vanilla (used for the dimension you're not in). */
    private static final double NETHER_SCALE = 8;
    /** Points converted from the other dimension are drawn at this alpha. */
    private static final int CONVERTED_ALPHA = 0x99;
    private static final int MAX_XZ = 30_000_000, MAX_Y = 20_000;
    /** Longest per-point visibility range, in blocks. */
    public static final int MAX_RANGE = 100_000;
    private static final String[] COMPASS8 = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Colour names for {@code .wp color}, all swatches of {@link ColorSetting#PALETTE}. */
    private static final Map<String, Integer> COLOR_NAMES = new LinkedHashMap<>();
    static {
        COLOR_NAMES.put("red", 0xFFE11D2E);
        COLOR_NAMES.put("orange", 0xFFFF8A1F);
        COLOR_NAMES.put("yellow", 0xFFFFD21F);
        COLOR_NAMES.put("green", 0xFF3BE37A);
        COLOR_NAMES.put("cyan", 0xFF29D3F0);
        COLOR_NAMES.put("blue", 0xFF3D7BFF);
        COLOR_NAMES.put("purple", 0xFFA95CFF);
        COLOR_NAMES.put("pink", 0xFFFF5CC8);
        COLOR_NAMES.put("white", 0xFFFFFFFF);
        COLOR_NAMES.put("gray", 0xFFB8B8C0);
    }
    /** Swatches Random Colors picks from (the bright ones; never the dark greys or dark red). */
    private static final int[] RANDOM_SWATCHES = {
            0xFFE11D2E, 0xFFFF8A1F, 0xFFFFD21F, 0xFF3BE37A, 0xFF29D3F0, 0xFF3D7BFF, 0xFFA95CFF, 0xFFFF5CC8,
            0xFFFF4D5E, 0xFFFFFFFF};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, List<Point>>>() {}.getType();

    /** The registered instance, for {@link #color(Point)} (static so the Direction HUD can colour points too). */
    private static Waypoints instance;

    /** Opens the Waypoint Manager (a read-only row: never saved). */
    private final net.evadeclient.setting.StatusSetting manager = add((net.evadeclient.setting.StatusSetting)
            new net.evadeclient.setting.StatusSetting("Waypoint Manager", Waypoints::managerLabel, "click: open")
                    .onClick(() -> mc.setScreen(new net.evadeclient.gui.WaypointManagerScreen(mc.currentScreen)))
                    .describe("Search, edit, sort, share and delete your waypoints (also .wp gui)."));
    // -- deaths
    private final BooleanSetting deaths = add(new BooleanSetting("Death Points", true)
            .describe("Save a point where you die."));
    private final NumberSetting keepDeaths = add(new NumberSetting("Keep Deaths", 3, 1, 10, 1)
            .describe("How many of your latest deaths are kept in each world: the newest is Death, then Death 2, Death 3..."));
    private final BooleanSetting deathMessage = add(new BooleanSetting("Death Message", true)
            .describe("Say in chat where you died once you respawn."));
    private final BooleanSetting clearDeathOnArrival = add(new BooleanSetting("Clear Death On Arrival", false)
            .describe("Remove a death point once you walk back to it (within Arrival Distance)."));
    private final NumberSetting expireAfter = add(new NumberSetting("Expire After", 0, 0, 60, 5)
            .describe("Remove death points this many minutes after you died. 0 keeps them."));
    // -- colours and adding
    private final ColorSetting defaultColor = add(new ColorSetting("Default Color", DEFAULT_COLOR)
            .describe("Colour of points that don't have their own (set one with .wp color)."));
    private final ColorSetting deathColor = add(new ColorSetting("Death Color", DEATH_COLOR)
            .describe("Colour of death points."));
    private final BooleanSetting randomColors = add(new BooleanSetting("Random Colors", false)
            .describe("Give each new point its own colour, different from the points you already have."));
    private final KeySetting quickAddKey = add(new KeySetting("Quick Add Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Save a point where you stand (Waypoint 1, Waypoint 2, ...)."));
    // -- labels
    private final ModeSetting label = add(new ModeSetting("Label", "Name+Distance", "Name+Distance", "Name", "Distance")
            .describe("What each point's label shows."));
    private final BooleanSetting showCoordinates = add(new BooleanSetting("Show Coordinates", false)
            .describe("Add each point's coordinates to its label (not where the server hides coordinates)."));
    private final BooleanSetting yDifference = add(new BooleanSetting("Y Difference", false)
            .describe("Add how many blocks above or below you each point is."));
    private final NumberSetting labelScale = add(new NumberSetting("Label Scale", 1.0, 0.5, 2.0, 0.1)
            .describe("Size of the labels."));
    private final BooleanSetting detailsOnLook = add(new BooleanSetting("Details On Look", false)
            .describe("Labels show just the name until you look towards the point."));
    private final NumberSetting lookAngle = add(new NumberSetting("Look Angle", 10, 2, 45, 1)
            .describe("How close to a point you must look, in degrees, to see its details."));
    // -- markers in the world
    private final BooleanSetting outlineBox = add(new BooleanSetting("Outline Box", true)
            .describe("Outline the block each point is on."));
    private final NumberSetting fillOpacity = add(new NumberSetting("Fill Opacity", 0, 0, 80, 5)
            .describe("Fill the point's block with its colour, in percent. 0 leaves it clear."));
    private final NumberSetting lineWidth = add(new NumberSetting("Line Width", 2.0, 1.0, 5.0, 0.5)
            .describe("Thickness of the outline and of line beams."));
    private final BooleanSetting beams = add(new BooleanSetting("Beams", true)
            .describe("A beam rising from each point, so you can find it from far away."));
    private final ModeSetting beamStyle = add(new ModeSetting("Beam Style", "Line", "Line", "Pillar")
            .describe("A thin line, or a see-through pillar."));
    private final NumberSetting beamHeight = add(new NumberSetting("Beam Height", 0, 0, 384, 16)
            .describe("How tall beams are, in blocks. 0 reaches the build limit."));
    // -- which points show
    private final BooleanSetting otherDimension = add(new BooleanSetting("Other Dimension", true)
            .describe("Show Overworld points in the Nether at the matching spot (divided by 8), and Nether points in the Overworld (times 8)."));
    private final NumberSetting hideWithin = add(new NumberSetting("Hide Within", 0, 0, 32, 1)
            .describe("Hide points closer than this many blocks (your target always shows). 0 shows them all."));
    private final NumberSetting maxDistance = add(new NumberSetting("Max Distance", 0, 0, 10000, 100)
            .describe("Hide points further away than this many blocks (your target always shows). 0 shows them at any distance."));
    // -- navigation
    private final ModeSetting offScreenArrows = add(new ModeSetting("Off-Screen Arrows", "Target", "Off", "Target", "All")
            .describe("Arrows around the crosshair pointing to points that are off screen: your target only, or all of them."));
    private final NumberSetting arrowRadius = add(new NumberSetting("Arrow Radius", 70, 30, 160, 5)
            .describe("How far from the crosshair the arrows sit."));
    private final BooleanSetting compassMarkers = add(new BooleanSetting("Direction HUD Markers", true)
            .describe("Mark your points on the Direction HUD's compass strip."));
    private final NumberSetting arrivalDistance = add(new NumberSetting("Arrival Distance", 3, 1, 16, 1)
            .describe("How close counts as arriving: at your target (which then clears) and at a death point."));

    private Map<String, List<Point>> all = new LinkedHashMap<>();
    private boolean loaded;
    /** The file couldn't be read: it is never overwritten until `.waypoint clear` starts a new one. */
    private boolean loadFailed;
    private Path corruptCopy;
    private boolean refusedNoticeShown, saveErrorShown;
    private final List<String> pendingWarnings = new ArrayList<>();

    private ClientWorld keyWorld;
    private String key;
    private String trimmedKey;
    private int trimmedKeep = -1;
    /** Death points you have been well away from since they were made; only these clear on arrival. */
    private final Set<Point> armed = new HashSet<>();

    /** The point {@code .wp target} set, until you arrive, clear it or leave the world. Never saved. */
    private Point target;
    private boolean quickAddWasDown;

    // Death tracking: the connection and player entity seen last tick, and the server's last death position.
    private Object deathSession;
    private ClientPlayerEntity deathPlayer;
    private GlobalPos lastDeathPos;
    private boolean wasDead;
    /** Where you died, until you respawn and the Death Message is printed. */
    private GlobalPos deathToReport;
    /** {@link Point#created} of the death point saved as you died, until the server's position corrects it. */
    private long pendingDeath;
    private String pendingDeathKey;
    private long lastCreated;

    private List<Shown> shown = List.of();
    private long shownTick = Long.MIN_VALUE;
    private ClientWorld shownWorld;
    private boolean shownDirty = true;
    /** {@link ApiWaypoints#version()} the shown list was built with. */
    private int shownServerVersion = -1;

    public Waypoints() {
        super("Waypoints", "Saved locations with beams and distance.", Category.VISUALS);
        setEnabled(true);
        instance = this;
    }

    @Override
    protected void onEnable() {
        // Deaths while this was off aren't picked up later as new ones.
        deathPlayer = null;
    }

    // ------------------------------------------------------------------ storage

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("waypoints.json"); }

    private void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        int skipped = 0;
        Map<String, List<Point>> read = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (root.isJsonNull()) return; // empty file
            if (!root.isJsonObject()) throw new JsonParseException("expected an object of servers, found " + root);
            for (var e : root.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonArray()) { skipped++; continue; }
                List<Point> list = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) {
                    Point p = readPoint(el);
                    if (p == null) skipped++;
                    else list.add(p);
                }
                read.put(e.getKey(), list);
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable as a whole: keep a copy, start empty in memory, and never write over the original.
            loadFailed = true;
            corruptCopy = backup(f);
            EvadeClient.LOG.warn("Couldn't read waypoints from {}; saving is off until `.waypoint clear`. Copy kept at {}",
                    f, corruptCopy, e);
            pendingWarnings.add("§cCouldn't read waypoints.json, so waypoints won't be saved." + keptAs()
                    + " Fix the file and restart, or type §d.wp clear§c to start a new one.");
            return;
        }
        all = read;
        if (skipped > 0) {
            // Readable, but some entries are broken: those are dropped (the next save won't have them), so keep a copy.
            Path copy = backup(f);
            EvadeClient.LOG.warn("Skipped {} unreadable waypoint(s) in {}; copy kept at {}", skipped, f, copy);
            pendingWarnings.add("§c" + skipped + " waypoint" + (skipped == 1 ? "" : "s")
                    + " in waypoints.json couldn't be read and " + (skipped == 1 ? "was" : "were") + " skipped."
                    + (copy == null ? "" : " The original was kept as §d" + copy.getFileName() + "§c."));
        }
    }

    /** One saved point, or null if it's broken. Colours always load opaque. */
    private static Point readPoint(JsonElement el) {
        try {
            Point p = GSON.fromJson(el, Point.class);
            if (p == null || p.name() == null || p.dim() == null || p.name().isBlank()) return null;
            int color = p.color() == 0 ? 0 : p.color() | 0xFF000000;
            int beam = p.beam() >= Point.BEAM_DEFAULT && p.beam() <= Point.BEAM_OFF ? p.beam() : Point.BEAM_DEFAULT;
            return new Point(p.name().strip(), p.dim(), p.x(), p.y(), p.z(), color, p.hidden(), Math.max(0, p.created()),
                    net.evadeclient.util.WaypointIcons.canonical(p.icon()), beam, Math.max(0, Math.min(MAX_RANGE, p.range())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path backup(Path f) {
        Path copy = f.resolveSibling(f.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(f, copy, StandardCopyOption.REPLACE_EXISTING);
            return copy;
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't copy {} to {}", f, copy, e);
            return null;
        }
    }

    private String keptAs() {
        return corruptCopy == null ? "" : " A copy was kept as §d" + corruptCopy.getFileName() + "§c.";
    }

    /** Writes to a temporary file and moves it into place, so a crash mid-save never leaves half a file. */
    private void save() {
        shownDirty = true;
        if (loadFailed) {
            if (!refusedNoticeShown) {
                refusedNoticeShown = true;
                pendingWarnings.add("§cWaypoints aren't being saved: waypoints.json couldn't be read." + keptAs()
                        + " Type §d.wp clear§c to start a new file.");
            }
            return;
        }
        Path f = file();
        String json = GSON.toJson(all, FILE_TYPE);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // e.g. the file is briefly locked by a virus scanner on Windows: fall back to a plain write.
                Files.deleteIfExists(tmp);
                Files.writeString(f, json, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't save waypoints to {}", f, e);
            if (!saveErrorShown) {
                saveErrorShown = true;
                pendingWarnings.add("§cCouldn't save waypoints: " + e.getMessage());
            }
        }
    }

    /** Chat warnings from loading and saving, shown once there is a chat to show them in. */
    private void flushWarnings() {
        if (pendingWarnings.isEmpty() || mc.inGameHud == null || mc.player == null) return;
        for (String w : pendingWarnings) CommandManager.print(w);
        pendingWarnings.clear();
    }

    // ------------------------------------------------------------------ where we are

    /**
     * "server address", or "singleplayer:&lt;world folder&gt;": the folder, not the world's title, which any number of
     * worlds share ("New World"). Cached per world; the target is dropped when the key changes.
     */
    private String worldKey() {
        if (key == null || mc.world != keyWorld) {
            keyWorld = mc.world;
            String k = computeKey();
            if (!k.equals(key)) {
                target = null;
                armed.clear();
            }
            key = k;
            shownDirty = true;
        }
        return key;
    }

    private String computeKey() {
        var server = mc.getServer();
        if (mc.isInSingleplayer() && server != null) {
            String levelName = server.getSaveProperties().getLevelName();
            try {
                // The icon path is read the way vanilla's own client code reads it; ROOT is the fallback.
                Path root = server.getIconFile().map(Path::getParent)
                        .orElseGet(() -> server.getSavePath(WorldSavePath.ROOT))
                        .toAbsolutePath().normalize();
                Path folder = root.getFileName();
                if (folder != null) {
                    String k = "singleplayer:" + folder;
                    migrateLegacyKey(k, root, levelName);
                    return k;
                }
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Couldn't find the world folder for waypoints; using the world name", e);
            }
            return "singleplayer:" + levelName;
        }
        var info = mc.getCurrentServerEntry();
        return info == null ? "unknown" : info.address;
    }

    /**
     * Older versions keyed singleplayer worlds by title. Move those points to the folder key, unless a world folder
     * is itself named like that title: the old key is then that world's own key, and its points stay with it.
     */
    private void migrateLegacyKey(String newKey, Path root, String levelName) {
        load();
        String legacy = "singleplayer:" + levelName;
        if (legacy.equals(newKey) || all.containsKey(newKey) || !all.containsKey(legacy)) return;
        Path parent = root.getParent();
        if (parent != null) {
            try {
                if (Files.isDirectory(parent.resolve(levelName))) return;
            } catch (InvalidPathException ignored) {
                // a title no folder could have: the points can only be this world's
            }
        }
        all.put(newKey, all.remove(legacy));
        EvadeClient.LOG.info("Moved waypoints of '{}' to {}", levelName, newKey);
        save();
    }

    private String dim() { return mc.world.getRegistryKey().getValue().toString(); }

    /** The dimension whose points can be converted to {@code dim} (Overworld and Nether only), or null. */
    private static String paired(String dim) {
        return OVERWORLD.equals(dim) ? NETHER : NETHER.equals(dim) ? OVERWORLD : null;
    }

    private List<Point> list() {
        load();
        return all.computeIfAbsent(worldKey(), k -> new ArrayList<>());
    }

    private List<Point> inDim(String dim, boolean includeHidden) {
        List<Point> out = new ArrayList<>();
        load();
        for (Point p : all.getOrDefault(worldKey(), List.of()))
            if (p.dim().equals(dim) && (includeHidden || !p.hidden())) out.add(p);
        return out;
    }

    /** Coordinates are kept out of labels, lists and shares where the server hides them, and in Streamer Mode. */
    public boolean coordsHidden() {
        return (mc.player != null && mc.player.hasReducedDebugInfo()) || net.evadeclient.module.misc.StreamerMode.hidesCoordinates();
    }

    /**
     * Where {@code p} is in the current dimension, as {x, y, z, converted (0 or 1)}: its own spot, the matching spot
     * of a point from the paired dimension while Other Dimension is on, or null when it can't be shown here.
     */
    private int[] spotHere(Point p, String here) {
        if (p.dim().equals(here)) return new int[]{p.x(), p.y(), p.z(), 0};
        if (!otherDimension.get() || !p.dim().equals(paired(here))) return null;
        // This dimension's real scale (a datapack can change it); the other one is assumed vanilla.
        double hereScale = mc.world.getDimension().coordinateScale();
        double factor = (NETHER.equals(p.dim()) ? NETHER_SCALE : 1.0) / (hereScale > 0 ? hereScale : 1.0);
        int bottom = mc.world.getBottomY();
        int ceiling = bottom + mc.world.getDimension().logicalHeight() - 1;
        int x = MathHelper.floor(p.x() * factor), z = MathHelper.floor(p.z() * factor);
        int y = NETHER.equals(here)
                ? MathHelper.clamp(p.y(), bottom + 1, Math.max(bottom + 1, Math.min(126, ceiling - 1))) // under the roof
                : MathHelper.clamp(p.y(), bottom, ceiling);
        return new int[]{x, y, z, 1};
    }

    // ------------------------------------------------------------------ public API

    /** This world's points in the current dimension, hidden ones left out. */
    public List<Point> here() {
        if (mc.world == null) return List.of();
        return inDim(dim(), false);
    }

    /** Whether {@code p} is one of the death points this mod records. */
    public static boolean isDeath(Point p) { return p.name() != null && DEATH_NAME.matcher(p.name()).matches(); }

    /** The colour {@code p} is drawn in (opaque): its own, else Death Color for death points, else Default Color. */
    public static int color(Point p) {
        if (p.color() != 0) return p.color() | 0xFF000000;
        Waypoints w = instance;
        if (isDeath(p)) return deathColor();
        return (w == null ? DEFAULT_COLOR : w.defaultColor.get()) | 0xFF000000;
    }

    /** Same as {@link #color(Point)}. */
    public static int colorOf(Point p) { return color(p); }

    /** Death Color (opaque), for death markers drawn elsewhere. */
    public static int deathColor() {
        Waypoints w = instance;
        return (w == null ? DEATH_COLOR : w.deathColor.get()) | 0xFF000000;
    }

    /** Whether the Direction HUD should mark these points (Direction HUD Markers). */
    public boolean compassMarkers() { return compassMarkers.get(); }

    /** Whether death points are being saved (Death Points). */
    public boolean recordsDeaths() { return deaths.get(); }

    /** The point set with {@code .wp target}, or null. */
    public Point target() { return target; }

    /**
     * What is drawn this tick: points of this dimension plus converted ones from the other (when Other Dimension is
     * on), without hidden ones or ones outside Hide Within / Max Distance (the target always stays), far to near.
     * Rebuilt at most once a tick or after a change; the Direction HUD reads the same list.
     */
    public List<Shown> visible() {
        if (mc.player == null || mc.world == null) return List.of();
        long tick = EvadeClient.ticks;
        if (!shownDirty && tick == shownTick && shownWorld == mc.world && shownServerVersion == ApiWaypoints.version()) return shown;
        shownDirty = false;
        shownServerVersion = ApiWaypoints.version();
        shownTick = tick;
        shownWorld = mc.world;

        String here = dim();
        double max = maxDistance.get(), min = hideWithin.get();
        Vec3d me = mc.player.getEntityPos();
        List<Shown> out = new ArrayList<>();
        load();
        for (Point p : all.getOrDefault(worldKey(), List.of())) {
            boolean isTarget = p.equals(target);
            if (p.hidden() && !isTarget) continue;
            int[] at = spotHere(p, here);
            if (at == null) continue;
            double dx = at[0] + 0.5 - me.x, dy = at[1] - me.y, dz = at[2] + 0.5 - me.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (!isTarget && ((max > 0 && dist > max) || dist < min)) continue;
            if (!isTarget && p.range() > 0 && dist > p.range()) continue;
            out.add(new Shown(p, at[0], at[1], at[2], at[3] == 1, color(p), dist, isTarget));
        }
        // Points the server added: same filters, never saved, never a target.
        for (ApiWaypoints.ServerWaypoint w : ApiWaypoints.all()) {
            Point p = new Point(w.name(), w.dim() == null ? here : w.dim(), w.x(), w.y(), w.z(), w.color(), false, 0L);
            int[] at = spotHere(p, here);
            if (at == null) continue;
            double dx = at[0] + 0.5 - me.x, dy = at[1] - me.y, dz = at[2] + 0.5 - me.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if ((max > 0 && dist > max) || dist < min) continue;
            out.add(new Shown(p, at[0], at[1], at[2], at[3] == 1, w.color(), dist, false, true, w.beam()));
        }
        out.sort(Comparator.comparingDouble(Shown::distance).reversed());
        shown = out;
        return out;
    }

    /** Saves {@code name} at {@code pos} in this dimension, replacing a point of the same name (keeping its colour). */
    public void add(String name, BlockPos pos) {
        addPoint(name, pos);
    }

    private Point addPoint(String name, BlockPos pos) {
        List<Point> list = list();
        String d = dim();
        Integer color = null;
        boolean wasTarget = false;
        for (Iterator<Point> it = list.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (p.name().equalsIgnoreCase(name) && p.dim().equals(d)) {
                color = p.color();
                wasTarget |= p.equals(target);
                armed.remove(p);
                it.remove();
            }
        }
        if (color == null) color = randomColors.get() ? randomColor(list) : 0;
        Point added = new Point(name, d, pos.getX(), pos.getY(), pos.getZ(), color, false, stamp());
        list.add(added);
        if (wasTarget) target = added;
        save();
        return added;
    }

    /** Removes the point called {@code name} (any case) in this dimension. */
    public boolean remove(String name) {
        load();
        List<Point> list = all.get(worldKey());
        String d = dim();
        if (list == null) return false;
        boolean death = false, removed = false;
        for (Iterator<Point> it = list.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (!p.name().equalsIgnoreCase(name) || !p.dim().equals(d)) continue;
            it.remove();
            forget(p);
            death |= isDeath(p);
            removed = true;
        }
        if (!removed) return false;
        if (death) renumberDeaths(list);
        save();
        return true;
    }

    /**
     * Changes the point called {@code name} (any case; this dimension first, then the rest of this world) to what
     * {@code change} returns, and saves. False when there is no such point or no world.
     */
    public boolean update(String name, UnaryOperator<Point> change) {
        if (mc.world == null || name == null) return false;
        Point p = findOrNull(name);
        if (p == null) return false;
        Point updated = change.apply(p);
        if (updated == null) return false;
        if (!updated.equals(p)) replace(p, updated);
        return true;
    }

    /** A creation time unique within this session (two points made in one millisecond still sort). */
    private long stamp() {
        long now = System.currentTimeMillis();
        lastCreated = Math.max(now, lastCreated + 1);
        return lastCreated;
    }

    /** A bright palette colour none of this world's points uses yet (any of them once all are taken). */
    private static int randomColor(List<Point> list) {
        Set<Integer> used = new HashSet<>();
        for (Point p : list) used.add(color(p) & 0xFFFFFF);
        List<Integer> free = new ArrayList<>();
        for (int c : RANDOM_SWATCHES) if (!used.contains(c & 0xFFFFFF)) free.add(c);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return free.isEmpty() ? RANDOM_SWATCHES[r.nextInt(RANDOM_SWATCHES.length)] : free.get(r.nextInt(free.size()));
    }

    // ------------------------------------------------------------------ deaths

    /**
     * Saves a death the moment you die (at your own position), then moves it to the exact spot the server reports
     * once you respawn. A respawn with a new death spot but no death seen (instant respawn) saves it then. The first
     * player of a connection only notes the server's spot: that death is from before, and was saved back then.
     */
    private void trackDeaths() {
        ClientPlayerEntity player = mc.player;
        var session = mc.getNetworkHandler();
        if (session != deathSession) {
            deathSession = session;
            deathPlayer = null;
            pendingDeath = 0;
            deathToReport = null;
        }
        boolean dead = player.isDead() || player.getHealth() <= 0;
        if (player != deathPlayer) {
            GlobalPos server = player.getLastDeathPos().orElse(null);
            if (deathPlayer == null) {
                lastDeathPos = server;
                wasDead = dead; // already dead on joining: that death was saved before you left
            } else {
                if (server != null && !server.equals(lastDeathPos)) {
                    lastDeathPos = server;
                    deathToReport = server;
                    if (deaths.get()) placeDeath(server);
                }
                finishDeath();
            }
            deathPlayer = player;
        }
        if (dead && !wasDead) {
            BlockPos pos = player.getBlockPos();
            deathToReport = GlobalPos.create(mc.world.getRegistryKey(), pos);
            if (deaths.get()) {
                pendingDeath = addDeath(dim(), pos).created();
                pendingDeathKey = worldKey();
            }
        }
        wasDead = dead;
    }

    /** The server's death spot: moves the death saved as you died there, or saves it if none was. */
    private void placeDeath(GlobalPos at) {
        String d = at.dimension().getValue().toString();
        BlockPos pos = at.pos();
        if (pendingDeath != 0 && worldKey().equals(pendingDeathKey)) {
            List<Point> list = list();
            for (int i = 0; i < list.size(); i++) {
                Point p = list.get(i);
                if (p.created() != pendingDeath || !isDeath(p)) continue;
                if (!p.dim().equals(d) || p.x() != pos.getX() || p.y() != pos.getY() || p.z() != pos.getZ()) {
                    setAt(list, i, p.at(d, pos.getX(), pos.getY(), pos.getZ()));
                    save();
                }
                return;
            }
            return; // deleted while you were dead: leave it deleted
        }
        pendingDeath = addDeath(d, pos).created();
        pendingDeathKey = worldKey();
    }

    /** After a respawn: the Death Message, once the position is final. */
    private void finishDeath() {
        GlobalPos at = deathToReport;
        boolean saved = pendingDeath != 0;
        deathToReport = null;
        pendingDeath = 0;
        if (at == null || !deathMessage.get()) return;
        String where = coordsHidden() ? "" : " at §d" + at.pos().getX() + ", " + at.pos().getY() + ", " + at.pos().getZ()
                + " §8(" + dimName(at.dimension().getValue().toString()) + ")§7";
        CommandManager.print("§7You died" + where + "." + (saved ? " §d.wp target death§7 to head back." : ""));
    }

    private Point addDeath(String d, BlockPos pos) {
        List<Point> list = list();
        Point p = new Point("Death", d, pos.getX(), pos.getY(), pos.getZ(), 0, false, stamp());
        list.add(p);
        renumberDeaths(list);
        save();
        for (Point q : list) if (q.created() == p.created()) return q;
        return p;
    }

    private static boolean hasName(List<Point> list, String name) {
        for (Point p : list) if (p.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    /**
     * Keeps the newest Keep Deaths death points and names them Death, Death 2, ... newest first (older versions'
     * "Death 14:05" names included). Points without a creation time count as older, in list order.
     */
    private boolean renumberDeaths(List<Point> list) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) if (isDeath(list.get(i))) idx.add(i);
        if (idx.isEmpty()) return false;
        idx.sort((i, j) -> {
            long ci = list.get(i).created(), cj = list.get(j).created();
            return ci != cj ? Long.compare(cj, ci) : Integer.compare(j, i);
        });
        int keep = keepDeaths.getInt();
        boolean changed = false;
        List<Integer> drop = new ArrayList<>();
        for (int r = 0; r < idx.size(); r++) {
            int i = idx.get(r);
            if (r >= keep) { drop.add(i); continue; }
            String want = r == 0 ? "Death" : "Death " + (r + 1);
            Point p = list.get(i);
            if (!p.name().equals(want)) {
                setAt(list, i, p.withName(want));
                changed = true;
            }
        }
        drop.sort(Comparator.reverseOrder());
        for (int i : drop) {
            forget(list.remove(i));
            changed = true;
        }
        if (changed) shownDirty = true;
        return changed;
    }

    /** Removes death points older than Expire After (checked once a second). */
    private void expireDeaths() {
        int minutes = expireAfter.getInt();
        if (minutes <= 0 || EvadeClient.ticks % 20 != 0) return;
        List<Point> list = all.get(worldKey());
        if (list == null || list.isEmpty()) return;
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        boolean removed = false;
        for (Iterator<Point> it = list.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (!isDeath(p) || p.created() <= 0 || p.created() >= cutoff) continue;
            it.remove();
            forget(p);
            removed = true;
        }
        if (!removed) return;
        renumberDeaths(list);
        save();
    }

    /** Removes death points you have walked back to, once you had been well away from them. */
    private void clearReachedDeaths() {
        List<Point> list = all.get(worldKey());
        if (list == null || list.isEmpty()) return;
        String d = dim();
        double arrive = arrivalDistance.get(), arm = Math.max(8, arrive + 5);
        Vec3d me = mc.player.getEntityPos();
        List<String> reached = new ArrayList<>();
        for (Iterator<Point> it = list.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (!isDeath(p) || !p.dim().equals(d)) continue;
            double dx = p.x() + 0.5 - me.x, dy = p.y() - me.y, dz = p.z() + 0.5 - me.z;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq > arm * arm) {
                armed.add(p);
            } else if (sq <= arrive * arrive && armed.contains(p)) {
                it.remove();
                forget(p);
                reached.add(p.name());
            }
        }
        if (reached.isEmpty()) return;
        renumberDeaths(list);
        save();
        for (String n : reached) CommandManager.print("Reached §d" + n + "§7, removed it.");
    }

    // ------------------------------------------------------------------ Waypoint Manager API

    private static String managerLabel() {
        Waypoints w = instance;
        if (w == null || mc.world == null) return "Open";
        int n = w.allInWorld().size();
        return n + " point" + (n == 1 ? "" : "s") + " here";
    }

    /** Every saved point of this world or server, all dimensions, in saved order (a copy). Empty outside a world. */
    public List<Point> allInWorld() {
        if (mc.world == null) return List.of();
        return List.copyOf(list());
    }

    /** This world's key ("server address" or "singleplayer:&lt;folder&gt;"), shared with the World Map's save folder. */
    public String worldId() { return worldKey(); }

    /** The current dimension's id, or "" outside a world. */
    public String currentDim() { return mc.world == null ? "" : dim(); }

    /** Distance in blocks from you to {@code p} (to its matching spot for the paired dimension), or -1 when it can't be reached from here. */
    public double distanceTo(Point p) {
        if (mc.player == null || mc.world == null) return -1;
        int[] at = spotHere(p, dim());
        if (at == null) return -1;
        Vec3d me = mc.player.getEntityPos();
        double dx = at[0] + 0.5 - me.x, dy = at[1] - me.y, dz = at[2] + 0.5 - me.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Replace {@code old} (the same instance from {@link #allInWorld}) with {@code updated}, and save. False if it's gone. */
    public boolean replacePoint(Point old, Point updated) {
        if (mc.world == null || old == null || updated == null) return false;
        List<Point> list = list();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).equals(old)) continue;
            setAt(list, i, updated);
            save();
            return true;
        }
        return false;
    }

    /** Delete {@code p}; returns where it was (for {@link #restorePoint}), or -1 if it wasn't there. */
    public int deletePoint(Point p) {
        if (mc.world == null || p == null) return -1;
        List<Point> list = list();
        int i = list.indexOf(p);
        if (i < 0) return -1;
        list.remove(i);
        forget(p);
        if (isDeath(p)) renumberDeaths(list);
        save();
        return i;
    }

    /** Put a deleted point back at {@code index} (undo). */
    public void restorePoint(Point p, int index) {
        if (mc.world == null || p == null) return;
        List<Point> list = list();
        list.add(Math.max(0, Math.min(index, list.size())), p);
        shownDirty = true;
        save();
    }

    /** Make {@code p} the target (null clears it). */
    public void setTarget(Point p) {
        target = p;
        shownDirty = true;
    }

    /** {@code base}, or "base 2", "base 3"... whichever isn't taken in this world yet. */
    public String freeName(String base) {
        String b = clip(base == null || base.isBlank() ? "Waypoint" : base.strip());
        if (DEATH_NAME.matcher(b).matches()) b = "Point " + b;
        List<Point> list = list();
        if (!hasName(list, b)) return b;
        for (int n = 2; ; n++) {
            String c = clip(b + " " + n);
            if (!hasName(list, c)) return c;
        }
    }

    /** Save a new point in {@code dim} (null: this dimension). Returns it, or null outside a world. */
    public Point addAt(String name, String dim, BlockPos pos) {
        if (mc.world == null) return null;
        String d = dim == null ? dim() : dim;
        List<Point> list = list();
        int color = randomColors.get() ? randomColor(list) : 0;
        Point added = new Point(clip(name), d, pos.getX(), pos.getY(), pos.getZ(), color, false, stamp());
        list.add(added);
        save();
        return added;
    }

    /** "[Overworld] Base: X 1, Y 64, Z -3", the plain text a share puts in the chat box. */
    public static String shareText(Point p) {
        return "[" + dimName(p.dim()) + "] " + p.name() + ": X " + p.x() + ", Y " + p.y() + ", Z " + p.z();
    }

    /** "Overworld", "Nether", "End" or a tidied custom dimension name. */
    public static String dimensionName(String dim) { return dimName(dim); }

    // ------------------------------------------------------------------ commands

    public void command(String[] a) {
        load();
        flushWarnings();
        if (mc.player == null || mc.world == null) throw new CommandError("Join a world first.");
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        List<String> t = tokens(a, 2);
        switch (sub) {
            case "add", "set" -> commandAdd(t);
            case "del", "delete", "remove" -> {
                Point p = find(need(t, "Usage: .wp del <name>"));
                List<Point> list = list();
                list.remove(p);
                forget(p);
                if (isDeath(p)) renumberDeaths(list);
                save();
                CommandManager.print("Removed §d" + p.name());
            }
            case "color", "colour" -> {
                if (t.size() < 2) throw new CommandError("Usage: .wp color <name> <#RRGGBB|" + String.join("|", COLOR_NAMES.keySet()) + "|default>");
                String c = t.getLast();
                Integer argb = parseColor(c);
                if (argb == null) throw new CommandError("Not a colour: " + c + ". Use #RRGGBB, "
                        + String.join(", ", COLOR_NAMES.keySet()) + " or default.");
                Point p = find(String.join(" ", t.subList(0, t.size() - 1)));
                replace(p, p.withColor(argb));
                CommandManager.print("§d" + p.name() + "§7 is now " + (argb == 0 ? "the default colour."
                        : String.format(Locale.ROOT, "#%06X.", argb & 0xFFFFFF)));
            }
            case "hide", "show", "unhide" -> {
                boolean hide = sub.equals("hide");
                String name = need(t, "Usage: .wp " + sub + " <name|all>");
                if (name.equalsIgnoreCase("all")) {
                    List<Point> list = list();
                    int n = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).hidden() == hide) continue;
                        setAt(list, i, list.get(i).withHidden(hide));
                        n++;
                    }
                    if (n > 0) save();
                    CommandManager.print((hide ? "Hid " : "Unhid ") + n + " waypoint" + (n == 1 ? "" : "s") + ".");
                } else {
                    Point p = find(name);
                    if (p.hidden() != hide) replace(p, p.withHidden(hide));
                    CommandManager.print("§d" + p.name() + "§7 " + (hide ? "hidden." : "shown."));
                }
            }
            case "rename" -> commandRename(t);
            case "near", "nearest" -> commandNear(t);
            case "share" -> {
                Point p = find(need(t, "Usage: .wp share <name>"));
                if (coordsHidden()) throw new CommandError(net.evadeclient.module.misc.StreamerMode.hidesCoordinates()
                        ? "Streamer Mode is on: waypoint coordinates stay off chat." : "This server hides coordinates.");
                // Typed into chat for you to send (or not); after the chat this was typed in has closed.
                String text = shareText(p);
                mc.send(() -> mc.setScreen(new ChatScreen(text, false)));
            }
            case "target", "goto", "go" -> commandTarget(t);
            case "clear" -> {
                boolean fresh = loadFailed;
                if (fresh && corruptCopy == null && Files.exists(file()) && (corruptCopy = backup(file())) == null)
                    throw new CommandError("Couldn't keep a copy of the unreadable waypoints.json, so it was left alone."
                            + " Move it out of config/evade and try again.");
                loadFailed = false;
                refusedNoticeShown = false;
                all.remove(worldKey());
                armed.clear();
                target = null;
                pendingDeath = 0;
                save();
                if (fresh) {
                    CommandManager.print("Started a new waypoints file." + (corruptCopy == null ? ""
                            : " The unreadable one is kept as §d" + corruptCopy.getFileName() + "§7."));
                } else {
                    CommandManager.print("Waypoints cleared for this " + (mc.isInSingleplayer() ? "world." : "server."));
                }
            }
            case "list" -> commandList();
            case "gui", "manager", "menu", "edit" -> mc.send(() -> mc.setScreen(new net.evadeclient.gui.WaypointManagerScreen(null)));
            default -> throw new CommandError("Unknown: .wp " + a[1]
                    + ". Try add, del, list, near, color, hide, show, rename, share, target, clear or gui.");
        }
    }

    /** {@code .wp add [name] [x y z]}: coordinates may be relative to you ({@code ~}, {@code ~5}, {@code ~-3}). */
    private void commandAdd(List<String> t) {
        BlockPos me = mc.player.getBlockPos();
        BlockPos pos = me;
        if (t.size() >= 3) {
            BlockPos at = coords(t.subList(t.size() - 3, t.size()), me);
            if (at != null) {
                pos = at;
                t = t.subList(0, t.size() - 3);
            }
        }
        String name = clip(String.join(" ", t).strip());
        if (name.isEmpty()) name = nextName();
        checkName(name);
        boolean replaced = false;
        String d = dim();
        for (Point p : list()) if (p.name().equalsIgnoreCase(name) && p.dim().equals(d)) replaced = true;
        Point p = addPoint(name, pos);
        CommandManager.print("Waypoint §d" + p.name() + "§7 " + (replaced ? "moved" : "saved")
                + (coordsHidden() ? (pos.equals(me) ? " here." : ".") : " at " + pos.toShortString() + "."));
    }

    /** {@code .wp rename <old> <new>}: quote names with spaces, or the longest existing name at the start is old. */
    private void commandRename(List<String> t) {
        if (t.size() < 2) throw new CommandError("Usage: .wp rename <old> <new> (use \"quotes\" for names with spaces)");
        Point p = null;
        String newName = null;
        for (int k = t.size() - 1; k >= 1 && p == null; k--) {
            p = findOrNull(String.join(" ", t.subList(0, k)));
            if (p != null) newName = String.join(" ", t.subList(k, t.size()));
        }
        if (p == null) throw new CommandError("No waypoint called " + String.join(" ", t.subList(0, t.size() - 1)));
        newName = clip(newName.strip());
        if (newName.isEmpty()) throw new CommandError("Give the new name.");
        checkName(newName);
        if (newName.equals(p.name())) {
            CommandManager.print("§d" + p.name() + "§7 already has that name.");
            return;
        }
        for (Point q : list()) {
            if (q != p && q.dim().equals(p.dim()) && q.name().equalsIgnoreCase(newName))
                throw new CommandError("There is already a waypoint called " + q.name() + " in " + dimName(p.dim()) + ".");
        }
        boolean death = isDeath(p);
        String old = p.name();
        replace(p, p.withName(newName));
        if (death && renumberDeaths(list())) save();
        CommandManager.print("Renamed §d" + old + "§7 to §d" + newName + "§7.");
    }

    /** {@code .wp near [n]}: the nearest points here (and converted ones), with distance and direction. */
    private void commandNear(List<String> t) {
        int n = NEAR_DEFAULT;
        if (!t.isEmpty()) {
            try {
                n = Integer.parseInt(t.getFirst());
            } catch (NumberFormatException e) {
                throw new CommandError("Usage: .wp near [count]");
            }
            n = MathHelper.clamp(n, 1, NEAR_MAX);
        }
        String here = dim();
        Vec3d me = mc.player.getEntityPos();
        record Near(Point p, int[] at, double dist) {}
        List<Near> near = new ArrayList<>();
        for (Point p : list()) {
            int[] at = spotHere(p, here);
            if (at == null) continue;
            double dx = at[0] + 0.5 - me.x, dy = at[1] - me.y, dz = at[2] + 0.5 - me.z;
            near.add(new Near(p, at, Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
        if (near.isEmpty()) {
            CommandManager.print("No waypoints here. §d.wp add <name>");
            return;
        }
        near.sort(Comparator.comparingDouble(Near::dist));
        for (Near e : near.subList(0, Math.min(n, near.size()))) {
            String dir = direction(e.at[0] + 0.5 - me.x, e.at[2] + 0.5 - me.z);
            CommandManager.print("§d" + e.p.name() + tags(e.p, e.at[3] == 1) + "§7 " + Math.round(e.dist) + "m " + dir);
        }
    }

    /** {@code .wp target <name|off>}: arrows, the Direction HUD and an arrival notice lead you to it. */
    private void commandTarget(List<String> t) {
        String name = String.join(" ", t).strip();
        if (name.isEmpty()) {
            if (target == null) throw new CommandError("Usage: .wp target <name|off>");
            CommandManager.print("Target: §d" + target.name() + "§7. §d.wp target off§7 to clear it.");
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ((lower.equals("off") || lower.equals("none") || lower.equals("clear")) && findOrNull(name) == null) {
            boolean had = target != null;
            target = null;
            shownDirty = true;
            CommandManager.print(had ? "Target cleared." : "No target set.");
            return;
        }
        Point p = find(name);
        String here = dim();
        int[] at = spotHere(p, here);
        if (at == null) {
            if (p.dim().equals(paired(here)))
                throw new CommandError(p.name() + " is in the " + dimName(p.dim()) + ". Turn on Other Dimension to target it from here.");
            throw new CommandError(p.name() + " is in the " + dimName(p.dim()) + "; target it from there.");
        }
        target = p;
        shownDirty = true;
        Vec3d me = mc.player.getEntityPos();
        double dx = at[0] + 0.5 - me.x, dy = at[1] - me.y, dz = at[2] + 0.5 - me.z;
        long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        CommandManager.print("Target: §d" + p.name() + tags(p, at[3] == 1) + "§7, " + dist + "m " + direction(dx, dz)
                + ". §d.wp target off§7 to clear it.");
    }

    private void commandList() {
        String here = dim();
        List<Point> pts = inDim(here, true);
        String other = otherDimension.get() ? paired(here) : null;
        List<Point> converted = other == null ? List.of() : inDim(other, true);
        if (pts.isEmpty() && converted.isEmpty()) {
            int elsewhere = list().size();
            CommandManager.print("No waypoints here. §d.wp add <name>" + (elsewhere == 0 ? ""
                    : " §8(" + elsewhere + " in other dimensions)"));
            return;
        }
        boolean hideCoords = coordsHidden();
        Vec3d me = mc.player.getEntityPos();
        for (Point p : pts) printListed(p, spotHere(p, here), me, hideCoords);
        if (converted.isEmpty()) return;
        CommandManager.print("§8From the " + dimName(other) + ", at the matching spot here:");
        for (Point p : converted) printListed(p, spotHere(p, here), me, hideCoords);
    }

    private void printListed(Point p, int[] at, Vec3d me, boolean hideCoords) {
        if (at == null) return;
        double dx = at[0] + 0.5 - me.x, dy = at[1] - me.y, dz = at[2] + 0.5 - me.z;
        long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        CommandManager.print("§d" + p.name() + tags(p, at[3] == 1) + "§7 "
                + (hideCoords ? "" : at[0] + ", " + at[1] + ", " + at[2] + " ") + "(" + dist + "m)");
    }

    /** " (hidden)", " (target)" and the dimension a converted point is from, for chat lines. */
    private String tags(Point p, boolean converted) {
        StringBuilder b = new StringBuilder();
        if (converted) b.append(" §8(").append(dimName(p.dim())).append(")");
        if (p.hidden()) b.append(" §8(hidden)");
        if (p.equals(target)) b.append(" §8(target)");
        return b.toString();
    }

    /**
     * The words after {@code a[from - 1]}, with "double quoted" words kept together (quotes removed). Chat commands
     * arrive split on spaces, so they are joined back first.
     */
    static List<String> tokens(String[] a, int from) {
        String s = join(a, from, a.length);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(cur.toString().strip());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString().strip());
        return out;
    }

    private static String join(String[] a, int from, int to) {
        if (from >= a.length) return "";
        return String.join(" ", Arrays.copyOfRange(a, from, Math.max(from, to))).strip();
    }

    private static String need(List<String> t, String usage) {
        String s = String.join(" ", t).strip();
        if (s.isEmpty()) throw new CommandError(usage);
        return s;
    }

    private static String clip(String name) {
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
    }

    /** Death names are kept for the points this mod makes when you die. */
    private static void checkName(String name) {
        if (DEATH_NAME.matcher(name).matches())
            throw new CommandError("Names like " + name + " are kept for death points; pick another name.");
    }

    /**
     * Three coordinates, each a number or {@code ~} / {@code ~n} relative to {@code me}; null when they aren't all
     * coordinates (so the words are part of the name).
     */
    private static BlockPos coords(List<String> t, BlockPos me) {
        Integer x = coord(t.get(0), me.getX()), y = coord(t.get(1), me.getY()), z = coord(t.get(2), me.getZ());
        if (x == null || y == null || z == null) return null;
        if (Math.abs(x) > MAX_XZ || Math.abs(z) > MAX_XZ || Math.abs(y) > MAX_Y)
            throw new CommandError("Those coordinates are outside the world.");
        return new BlockPos(x, y, z);
    }

    private static Integer coord(String s, int base) {
        try {
            if (s.equals("~")) return base;
            if (s.startsWith("~")) return (int) Math.floor(base + Double.parseDouble(s.substring(1)));
            if (!s.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) return null;
            double v = Double.parseDouble(s);
            return Math.abs(v) > Integer.MAX_VALUE ? null : (int) Math.floor(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "Waypoint N" for the first N not taken in this world. */
    private String nextName() {
        List<Point> list = list();
        int n = inDim(dim(), true).size() + 1;
        while (hasName(list, "Waypoint " + n)) n++;
        return "Waypoint " + n;
    }

    /** The point called {@code name} (any case), or a chat error. See {@link #findOrNull}. */
    private Point find(String name) {
        Point p = findOrNull(name);
        if (p == null) throw new CommandError("No waypoint called " + name);
        return p;
    }

    /** The point called {@code name} (any case): in this dimension first, then the paired one, then anywhere here. */
    private Point findOrNull(String name) {
        String d = dim(), other = paired(d);
        Point inOther = null, elsewhere = null;
        for (Point p : list()) {
            if (!p.name().equalsIgnoreCase(name.strip())) continue;
            if (p.dim().equals(d)) return p;
            if (p.dim().equals(other)) { if (inOther == null) inOther = p; }
            else if (elsewhere == null) elsewhere = p;
        }
        return inOther != null ? inOther : elsewhere;
    }

    private void replace(Point old, Point updated) {
        List<Point> list = list();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != old) continue;
            setAt(list, i, updated);
            save();
            return;
        }
    }

    /** Puts {@code updated} at {@code i}, carrying over what refers to the old point (target, arrival arming). */
    private void setAt(List<Point> list, int i, Point updated) {
        Point old = list.set(i, updated);
        if (old.equals(target)) target = updated;
        if (armed.remove(old)) armed.add(updated);
        shownDirty = true;
    }

    /** Drops what refers to a removed point. */
    private void forget(Point p) {
        armed.remove(p);
        if (p.equals(target)) target = null;
        shownDirty = true;
    }

    /** "#RRGGBB", "RRGGBB", "0xRRGGBB" or a palette colour name as opaque ARGB; "default" as 0; null otherwise. */
    private static Integer parseColor(String s) {
        String t = s.strip().toLowerCase(Locale.ROOT);
        if (t.equals("default") || t.equals("reset") || t.equals("none")) return 0;
        if (t.equals("grey")) t = "gray";
        Integer named = COLOR_NAMES.get(t);
        if (named != null) return named;
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x")) t = t.substring(2);
        if (t.length() != 6 || !t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) return null;
        return Integer.parseInt(t, 16) | 0xFF000000;
    }

    private static String dimName(String dim) {
        if (OVERWORLD.equals(dim)) return "Overworld";
        if (NETHER.equals(dim)) return "Nether";
        if (END.equals(dim)) return "End";
        String path = dim.substring(dim.indexOf(':') + 1);
        StringBuilder b = new StringBuilder();
        for (String part : path.split("[_/]")) {
            if (part.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.isEmpty() ? dim : b.toString();
    }

    /** The 8-point compass direction of an offset (north is -Z), or "here" right on top of it. */
    private static String direction(double dx, double dz) {
        if (dx * dx + dz * dz < 1) return "here";
        double bearing = Math.toDegrees(Math.atan2(dx, -dz));
        return COMPASS8[Math.floorMod((int) Math.round(bearing / 45), 8)];
    }

    // ------------------------------------------------------------------ per tick and frame

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        load();
        flushWarnings();
        String k = worldKey();
        trackDeaths();
        // Keep Deaths applies as soon as it changes, and to each world as you join it.
        int keep = keepDeaths.getInt();
        if (!k.equals(trimmedKey) || keep != trimmedKeep) {
            trimmedKey = k;
            trimmedKeep = keep;
            List<Point> list = all.get(k);
            if (list != null && renumberDeaths(list)) save();
        }
        expireDeaths();
        boolean dead = mc.player.isDead() || mc.player.getHealth() <= 0;
        if (!dead && clearDeathOnArrival.get()) clearReachedDeaths();

        boolean quick = quickAddKey.isDown();
        if (quick && !quickAddWasDown) {
            Point p = addPoint(nextName(), mc.player.getBlockPos());
            Notifications.push("Waypoint", p.name() + " saved", true);
        }
        quickAddWasDown = quick;

        if (target != null && !dead) checkArrival();
    }

    /** Within Arrival Distance of the target: a notice, and the target clears (across dimensions, by X and Z). */
    private void checkArrival() {
        Vec3d me = mc.player.getEntityPos();
        double r = arrivalDistance.get();
        for (Shown s : visible()) {
            if (!s.target()) continue;
            double dx = s.x() + 0.5 - me.x, dz = s.z() + 0.5 - me.z, dy = s.converted() ? 0 : s.y() - me.y;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                Notifications.push("Waypoints", "Arrived at " + s.point().name(), true);
                target = null;
                shownDirty = true;
            }
            return;
        }
    }

    @Override
    public void onRender(RenderContext ctx) {
        List<Shown> list = visible();
        if (list.isEmpty() || mc.world == null) return;
        boolean outline = outlineBox.get(), beam = beams.get(), pillar = beamStyle.is("Pillar");
        float width = lineWidth.getFloat();
        int fill = Math.round(fillOpacity.getFloat() * 2.55f);
        int height = beamHeight.getInt();
        int buildTop = mc.world.getTopYInclusive() + 1;
        for (Shown s : list) {
            int c = s.converted() ? ColorUtil.withAlpha(s.color(), CONVERTED_ALPHA) : s.color();
            double x = s.x(), y = s.y(), z = s.z();
            if (fill > 0) {
                int a = s.converted() ? fill * CONVERTED_ALPHA / 255 : fill;
                ctx.boxFilled(new Box(x, y, z, x + 1, y + 1, z + 1), ColorUtil.withAlpha(c, a));
            }
            if (outline) ctx.boxOutline(x, y, z, x + 1, y + 1, z + 1, c, width);
            int own = s.server() ? Point.BEAM_DEFAULT : s.point().beam();
            if (own == Point.BEAM_OFF || (own == Point.BEAM_DEFAULT && !beam) || s.converted() || !s.beam()) continue;
            double top = height == 0 ? buildTop : y + 1 + height;
            if (top <= y + 1) continue;
            if (pillar) ctx.boxFilled(new Box(x + .375, y + 1, z + .375, x + .625, top, z + .625), ColorUtil.withAlpha(c, 0x60));
            else ctx.line(x + .5, y + 1, z + .5, x + .5, top, z + .5, ColorUtil.withAlpha(c, 0x90), width);
        }
    }

    @Override
    public void onHud(DrawContext ctx, float td) {
        if (mc.player == null || mc.world == null) return;
        List<Shown> list = visible();
        if (list.isEmpty()) return;
        var camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        boolean allDetails = !detailsOnLook.get();
        double cosLook = Math.cos(Math.toRadians(lookAngle.get()));
        String arrows = offScreenArrows.get();
        float scale = labelScale.getFloat();
        int myY = mc.player.getBlockY();
        for (Shown s : list) {
            double ax = s.x() + 0.5, ay = s.y() + 1.5, az = s.z() + 0.5;
            double[] sp = Projection.toScreen(new Vec3d(ax, ay, az));
            boolean onScreen = sp != null && sp[0] >= 0 && sp[0] <= sw && sp[1] >= 0 && sp[1] <= sh;
            int color = s.converted() ? ColorUtil.withAlpha(s.color(), CONVERTED_ALPHA) : s.color();
            if (onScreen) {
                boolean full = allDetails || s.target() || lookingAt(look, cam, ax, ay, az, cosLook);
                Label.drawAt(ctx, sp[0], sp[1], labelText(s, full, myY), color, scale);
                String icon = s.point().icon();
                if (icon != null && net.evadeclient.util.WaypointIcons.has(icon)) {
                    int px = scale >= 1.5f ? 2 : 1;
                    net.evadeclient.util.WaypointIcons.draw(ctx, icon, (int) sp[0] - 9 * px / 2,
                            (int) (sp[1] - 8 * scale) - 9 * px - 2, px, color);
                }
            } else if (arrows.equals("All") || (arrows.equals("Target") && s.target())) {
                drawArrow(ctx, s, cam, camera.getYaw(), sw, sh, color);
            }
        }
    }

    /** Whether the view direction is within Look Angle of the point (cosine compared, so no acos per point). */
    private static boolean lookingAt(Vec3d look, Vec3d cam, double x, double y, double z, double cosLimit) {
        double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        return (look.x * dx + look.y * dy + look.z * dz) / len >= cosLimit;
    }

    /**
     * The label: name (with the dimension a converted point is from, and a death's age), distance, height
     * difference and coordinates as the settings ask. Without {@code full} (Details On Look, not looked at) only
     * the name, or the distance in Distance mode.
     */
    private String labelText(Shown s, boolean full, int myY) {
        Point p = s.point();
        String mode = label.get();
        boolean name = !mode.equals("Distance"), dist = !mode.equals("Name");
        StringBuilder b = new StringBuilder();
        if (name) {
            b.append(s.converted() ? "§7" : "").append(p.name());
            if (s.converted()) b.append(" §8").append(NETHER.equals(p.dim()) ? "(N)" : "(OW)");
            if (full && p.created() > 0 && isDeath(p)) b.append(" §8· ").append(age(p.created()));
            if (!full) return b.toString();
        }
        if (dist) {
            if (!b.isEmpty()) b.append(' ');
            b.append("§7").append(Math.round(s.distance())).append('m');
            if (!full) return b.toString();
        }
        if (yDifference.get() && !s.converted()) {
            int dy = s.y() - myY;
            if (dy != 0) b.append(b.isEmpty() ? "" : " ").append("§7").append(dy > 0 ? "▲" : "▼").append(Math.abs(dy));
        }
        if (showCoordinates.get() && !coordsHidden())
            b.append(b.isEmpty() ? "" : " ").append("§8").append(s.x()).append(", ").append(s.y()).append(", ").append(s.z());
        return b.toString();
    }

    /** "just now", "4m ago", "3h ago", "2d ago". */
    private static String age(long created) {
        long minutes = Math.max(0, System.currentTimeMillis() - created) / 60_000L;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        return hours < 24 ? hours + "h ago" : hours / 24 + "d ago";
    }

    /**
     * An arrow on a circle around the crosshair pointing the way to an off-screen point (straight up is straight
     * ahead), with its distance under it.
     */
    private void drawArrow(DrawContext ctx, Shown s, Vec3d cam, float camYaw, int sw, int sh, int color) {
        double dx = s.x() + 0.5 - cam.x, dz = s.z() + 0.5 - cam.z;
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double rel = Math.toRadians(MathHelper.wrapDegrees(bearing - camYaw));
        float r = Math.max(10, Math.min(arrowRadius.getFloat(), Math.min(sw, sh) / 2f - 16));
        float ax = sw / 2f + (float) Math.sin(rel) * r, ay = sh / 2f - (float) Math.cos(rel) * r;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(ax, ay);
            m.rotate((float) rel);
            m.translate(-0.5f, 0f);
            // A dark rim, then the arrow: rows 1, 3, 5 and 7 wide with the tip pointing up.
            int rim = 0x90000000;
            ctx.fill(0, -3, 1, -2, rim);
            for (int i = 0; i < 4; i++) ctx.fill(-i - 1, -2 + i, i + 2, -1 + i, rim);
            ctx.fill(-4, 2, 5, 3, rim);
            for (int i = 0; i < 4; i++) ctx.fill(-i, -2 + i, i + 1, -1 + i, color | 0xFF000000);
        } finally {
            m.popMatrix();
        }
        var tr = mc.textRenderer;
        String d = Math.round(s.distance()) + "m";
        ctx.drawTextWithShadow(tr, d, Math.round(ax - tr.getWidth(d) / 2f), Math.round(ay + 6), 0xFFFFFFFF);
    }
}
