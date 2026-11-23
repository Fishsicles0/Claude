package net.evadeclient.module.visuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Label;
import net.evadeclient.util.Projection;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import java.util.regex.Pattern;

/**
 * Saved locations per server (or singleplayer world) and dimension, drawn as a box and beam with a name and distance.
 * {@code .waypoint add <name>}, {@code del <name>}, {@code list}, {@code color <name> <#RRGGBB|default>},
 * {@code hide|show <name|all>}, {@code clear}. Your most recent deaths are kept as "Death HH:mm" points.
 * Overworld points also show in the Nether (and the other way round) at the matching portal coordinates.
 *
 * <p>Saved in {@code config/evade/waypoints.json}. A file that can't be read is never overwritten: a copy is kept
 * next to it and saving stops (with a chat warning) until {@code .waypoint clear} starts a new one.
 */
public class Waypoints extends Module {
    /**
     * One saved point. {@code color} is ARGB, or 0 for the default colour; {@code hidden} points are kept but not
     * drawn. Files written before these fields existed load with 0 and false.
     */
    public record Point(String name, String dim, int x, int y, int z, int color, boolean hidden) {
        public Point(String name, String dim, int x, int y, int z) { this(name, dim, x, y, z, 0, false); }

        public Point withColor(int argb) { return new Point(name, dim, x, y, z, argb, hidden); }
        public Point withHidden(boolean hide) { return new Point(name, dim, x, y, z, color, hide); }
    }

    /**
     * A point as drawn this tick: its own position, or for a point from the other dimension (Overworld and Nether
     * only) the matching position here, with {@code converted} set. Sorted far to near, so near labels end up on top.
     */
    public record Shown(Point point, int x, int y, int z, boolean converted, int color, double distance) {}

    public static final int DEFAULT_COLOR = 0xFFE11D2E, DEATH_COLOR = 0xFFFF7A85;
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    /** "Death", "Death 14:05" or "Death 14:05 (2)": the names this mod gives death points. */
    private static final Pattern DEATH_NAME = Pattern.compile("Death( \\d{1,2}:\\d{2})?( \\(\\d+\\))?");
    private static final DateTimeFormatter DEATH_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final double ARRIVAL = 3, ARM_DISTANCE = 8;
    private static final int MAX_NAME = 32;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, List<Point>>>() {}.getType();

    private final BooleanSetting deaths = add(new BooleanSetting("Death Points", true));
    private final NumberSetting keepDeaths = add(new NumberSetting("Keep Deaths", 3, 1, 10, 1)
            .describe("How many of your latest deaths are kept as points in each world; older ones are removed."));
    private final BooleanSetting clearDeathOnArrival = add(new BooleanSetting("Clear Death On Arrival", false)
            .describe("Remove a death point once you walk back to it (within 3 blocks)."));
    private final BooleanSetting beams = add(new BooleanSetting("Beams", true));
    private final BooleanSetting otherDimension = add(new BooleanSetting("Other Dimension", true)
            .describe("Show Overworld points in the Nether at the matching spot (divided by 8), and Nether points in the Overworld (times 8)."));
    private final NumberSetting maxDistance = add(new NumberSetting("Max Distance", 0, 0, 10000, 100)
            .describe("Hide points further away than this many blocks. 0 shows them at any distance."));
    private final NumberSetting labelScale = add(new NumberSetting("Label Scale", 1.0, 0.5, 2.0, 0.1)
            .describe("Size of the name and distance labels."));
    private final BooleanSetting showCoordinates = add(new BooleanSetting("Show Coordinates", false)
            .describe("Add each point's coordinates to its label (not where the server hides coordinates)."));

    private Map<String, List<Point>> all = new LinkedHashMap<>();
    private boolean loaded, wasDead;
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

    private List<Shown> shown = List.of();
    private long shownTick = Long.MIN_VALUE;
    private ClientWorld shownWorld;
    private boolean shownDirty = true;

    public Waypoints() {
        super("Waypoints", "Saved locations with beams and distance.", Category.VISUALS);
        setEnabled(true);
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
            return new Point(p.name().strip(), p.dim(), p.x(), p.y(), p.z(), color, p.hidden());
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
     * worlds share ("New World"). Cached per world.
     */
    private String worldKey() {
        if (key == null || mc.world != keyWorld) {
            keyWorld = mc.world;
            key = computeKey();
            armed.clear();
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

    private boolean coordsHidden() { return mc.player != null && mc.player.hasReducedDebugInfo(); }

    // ------------------------------------------------------------------ public API

    /** This world's points in the current dimension, hidden ones left out. */
    public List<Point> here() {
        if (mc.world == null) return List.of();
        return inDim(dim(), false);
    }

    /** Whether {@code p} is one of the death points this mod records. */
    public static boolean isDeath(Point p) { return p.name() != null && DEATH_NAME.matcher(p.name()).matches(); }

    /** The colour {@code p} is drawn in: its own, or the default (death points have their own default). */
    public static int colorOf(Point p) {
        if (p.color() != 0) return p.color() | 0xFF000000;
        return isDeath(p) ? DEATH_COLOR : DEFAULT_COLOR;
    }

    /**
     * What is drawn this tick: points of this dimension plus converted ones from the other (when Other Dimension is
     * on), without hidden ones or ones past Max Distance, far to near. Rebuilt at most once a tick or after a change.
     */
    public List<Shown> visible() {
        if (mc.player == null || mc.world == null) return List.of();
        long tick = EvadeClient.ticks;
        if (!shownDirty && tick == shownTick && shownWorld == mc.world) return shown;
        shownDirty = false;
        shownTick = tick;
        shownWorld = mc.world;

        String here = dim();
        String other = !otherDimension.get() ? null : OVERWORLD.equals(here) ? NETHER : NETHER.equals(here) ? OVERWORLD : null;
        int bottom = mc.world.getBottomY();
        int ceiling = bottom + mc.world.getDimension().logicalHeight() - 1;
        double max = maxDistance.get();
        Vec3d me = mc.player.getEntityPos();
        List<Shown> out = new ArrayList<>();
        load();
        for (Point p : all.getOrDefault(worldKey(), List.of())) {
            if (p.hidden()) continue;
            int x = p.x(), y = p.y(), z = p.z();
            boolean converted = false;
            if (!p.dim().equals(here)) {
                if (other == null || !p.dim().equals(other)) continue;
                converted = true;
                if (NETHER.equals(here)) {
                    x = Math.floorDiv(x, 8);
                    z = Math.floorDiv(z, 8);
                    y = MathHelper.clamp(y, bottom + 1, ceiling - 1); // under the Nether roof
                } else {
                    x *= 8;
                    z *= 8;
                    y = MathHelper.clamp(y, bottom, ceiling);
                }
            }
            double dx = x + 0.5 - me.x, dy = y + 0.5 - me.y, dz = z + 0.5 - me.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (max > 0 && dist > max) continue;
            out.add(new Shown(p, x, y, z, converted, colorOf(p), dist));
        }
        out.sort(Comparator.comparingDouble(Shown::distance).reversed());
        shown = out;
        return out;
    }

    /** Saves {@code name} at {@code pos} in this dimension, replacing a point of the same name (keeping its colour). */
    public void add(String name, BlockPos pos) {
        List<Point> list = list();
        String d = dim();
        int color = 0;
        for (Iterator<Point> it = list.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (p.name().equalsIgnoreCase(name) && p.dim().equals(d)) {
                color = p.color();
                it.remove();
            }
        }
        list.add(new Point(name, d, pos.getX(), pos.getY(), pos.getZ(), color, false));
        save();
    }

    /** Removes the point called {@code name} (any case) in this dimension. */
    public boolean remove(String name) {
        load();
        List<Point> list = all.get(worldKey());
        String d = dim();
        boolean r = list != null && list.removeIf(p -> p.name().equalsIgnoreCase(name) && p.dim().equals(d));
        if (r) save();
        return r;
    }

    // ------------------------------------------------------------------ deaths

    private void addDeath(BlockPos pos) {
        List<Point> list = list();
        String base = "Death " + LocalTime.now().format(DEATH_TIME);
        String name = base;
        for (int n = 2; hasName(list, name); n++) name = base + " (" + n + ")";
        list.add(new Point(name, dim(), pos.getX(), pos.getY(), pos.getZ(), 0, false));
        trimDeaths(list);
        save();
    }

    private static boolean hasName(List<Point> list, String name) {
        for (Point p : list) if (p.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Drops the oldest death points (the list is in the order they were made) beyond Keep Deaths. */
    private boolean trimDeaths(List<Point> list) {
        int keep = keepDeaths.getInt();
        int count = 0;
        for (Point p : list) if (isDeath(p)) count++;
        boolean changed = false;
        for (Iterator<Point> it = list.iterator(); count > keep && it.hasNext(); ) {
            Point p = it.next();
            if (!isDeath(p)) continue;
            it.remove();
            armed.remove(p);
            count--;
            changed = true;
        }
        return changed;
    }

    /** Removes death points you have walked back to, once you had been well away from them. */
    private void clearReachedDeaths() {
        List<Point> list = all.get(worldKey());
        if (list == null || list.isEmpty()) return;
        String d = dim();
        Vec3d me = mc.player.getEntityPos();
        List<String> reached = new ArrayList<>();
        for (Iterator<Point> it = list.iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (!isDeath(p) || !p.dim().equals(d)) continue;
            double dx = p.x() + 0.5 - me.x, dy = p.y() + 0.5 - me.y, dz = p.z() + 0.5 - me.z;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq > ARM_DISTANCE * ARM_DISTANCE) {
                armed.add(p);
            } else if (sq <= ARRIVAL * ARRIVAL && armed.remove(p)) {
                it.remove();
                reached.add(p.name());
            }
        }
        if (reached.isEmpty()) return;
        save();
        for (String n : reached) CommandManager.print("Reached §d" + n + "§7, removed it.");
    }

    // ------------------------------------------------------------------ commands

    public void command(String[] a) {
        load();
        flushWarnings();
        if (mc.player == null || mc.world == null) throw new CommandError("Join a world first.");
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "add", "set" -> {
                String name = a.length > 2 ? clip(join(a, 2, a.length)) : nextName();
                if (name.isEmpty()) name = nextName();
                BlockPos pos = mc.player.getBlockPos();
                add(name, pos);
                CommandManager.print("Waypoint §d" + name + "§7 saved" + (coordsHidden() ? " here." : " at " + pos.toShortString()));
            }
            case "del", "delete", "remove" -> {
                Point p = find(need(a, "Usage: .wp del <name>"));
                list().remove(p);
                armed.remove(p);
                save();
                CommandManager.print("Removed §d" + p.name());
            }
            case "color", "colour" -> {
                if (a.length < 4) throw new CommandError("Usage: .wp color <name> <#RRGGBB|default>");
                String hex = a[a.length - 1];
                Integer argb = parseColor(hex);
                if (argb == null) throw new CommandError("Not a colour: " + hex + ". Use #RRGGBB, or default.");
                Point p = find(join(a, 2, a.length - 1));
                replace(p, p.withColor(argb));
                CommandManager.print("§d" + p.name() + "§7 is now " + (argb == 0 ? "the default colour."
                        : String.format(Locale.ROOT, "#%06X.", argb & 0xFFFFFF)));
            }
            case "hide", "show", "unhide" -> {
                boolean hide = sub.equals("hide");
                String target = need(a, "Usage: .wp " + sub + " <name|all>");
                if (target.equalsIgnoreCase("all")) {
                    List<Point> list = list();
                    int n = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).hidden() == hide) continue;
                        list.set(i, list.get(i).withHidden(hide));
                        n++;
                    }
                    if (n > 0) save();
                    CommandManager.print((hide ? "Hid " : "Unhid ") + n + " waypoint" + (n == 1 ? "" : "s") + ".");
                } else {
                    Point p = find(target);
                    if (p.hidden() != hide) replace(p, p.withHidden(hide));
                    CommandManager.print("§d" + p.name() + "§7 " + (hide ? "hidden." : "shown."));
                }
            }
            case "clear" -> {
                boolean fresh = loadFailed;
                if (fresh && corruptCopy == null && Files.exists(file()) && (corruptCopy = backup(file())) == null)
                    throw new CommandError("Couldn't keep a copy of the unreadable waypoints.json, so it was left alone."
                            + " Move it out of config/evade and try again.");
                loadFailed = false;
                refusedNoticeShown = false;
                all.remove(worldKey());
                armed.clear();
                save();
                if (fresh) {
                    CommandManager.print("Started a new waypoints file." + (corruptCopy == null ? ""
                            : " The unreadable one is kept as §d" + corruptCopy.getFileName() + "§7."));
                } else {
                    CommandManager.print("Waypoints cleared for this " + (mc.isInSingleplayer() ? "world." : "server."));
                }
            }
            case "list" -> {
                List<Point> pts = inDim(dim(), true);
                if (pts.isEmpty()) CommandManager.print("No waypoints here. §d.wp add <name>");
                boolean hideCoords = coordsHidden();
                BlockPos me = mc.player.getBlockPos();
                for (Point p : pts) {
                    long dist = Math.round(Math.sqrt(me.getSquaredDistance(new BlockPos(p.x(), p.y(), p.z()))));
                    CommandManager.print("§d" + p.name() + (p.hidden() ? " §8(hidden)" : "") + "§7 "
                            + (hideCoords ? "" : p.x() + ", " + p.y() + ", " + p.z() + " ") + "(" + dist + "m)");
                }
            }
            default -> throw new CommandError("Unknown: .wp " + a[1] + ". Try add, del, list, color, hide, show or clear.");
        }
    }

    private static String join(String[] a, int from, int to) {
        return String.join(" ", Arrays.copyOfRange(a, from, Math.max(from, to))).strip();
    }

    private static String need(String[] a, String usage) {
        String s = a.length > 2 ? join(a, 2, a.length) : "";
        if (s.isEmpty()) throw new CommandError(usage);
        return s;
    }

    private static String clip(String name) {
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
    }

    /** "Waypoint N" for the first N not taken in this world. */
    private String nextName() {
        List<Point> list = list();
        int n = inDim(dim(), true).size() + 1;
        while (hasName(list, "Waypoint " + n)) n++;
        return "Waypoint " + n;
    }

    /** The point called {@code name} (any case): in this dimension first, then anywhere in this world. */
    private Point find(String name) {
        String d = dim();
        Point elsewhere = null;
        for (Point p : list()) {
            if (!p.name().equalsIgnoreCase(name)) continue;
            if (p.dim().equals(d)) return p;
            if (elsewhere == null) elsewhere = p;
        }
        if (elsewhere == null) throw new CommandError("No waypoint called " + name);
        return elsewhere;
    }

    private void replace(Point old, Point updated) {
        List<Point> list = list();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != old) continue;
            list.set(i, updated);
            if (armed.remove(old)) armed.add(updated);
            save();
            return;
        }
    }

    /** "#RRGGBB", "RRGGBB" or "0xRRGGBB" as opaque ARGB; "default" as 0; null if it isn't a colour. */
    private static Integer parseColor(String s) {
        String t = s.strip().toLowerCase(Locale.ROOT);
        if (t.equals("default") || t.equals("reset") || t.equals("none")) return 0;
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x")) t = t.substring(2);
        if (t.length() != 6 || !t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) return null;
        return Integer.parseInt(t, 16) | 0xFF000000;
    }

    // ------------------------------------------------------------------ per tick and frame

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        load();
        flushWarnings();
        boolean dead = mc.player.isDead() || mc.player.getHealth() <= 0;
        if (dead && !wasDead && deaths.get()) addDeath(mc.player.getBlockPos());
        wasDead = dead;
        // Keep Deaths applies as soon as it is lowered, and to each world as you join it.
        String k = worldKey();
        int keep = keepDeaths.getInt();
        if (!k.equals(trimmedKey) || keep != trimmedKeep) {
            trimmedKey = k;
            trimmedKeep = keep;
            List<Point> list = all.get(k);
            if (list != null && trimDeaths(list)) save();
        }
        if (!dead && clearDeathOnArrival.get()) clearReachedDeaths();
    }

    @Override
    public void onRender(RenderContext ctx) {
        boolean beam = beams.get();
        for (Shown s : visible()) {
            int c = s.converted() ? dimmed(s.color()) : s.color();
            ctx.boxOutline(s.x(), s.y(), s.z(), s.x() + 1, s.y() + 1, s.z() + 1, c, 2f);
            if (beam && !s.converted())
                ctx.line(s.x() + 0.5, s.y() + 1, s.z() + 0.5, s.x() + 0.5, s.y() + 256, s.z() + 0.5, (c & 0xFFFFFF) | 0x90000000, 2f);
        }
    }

    @Override
    public void onHud(DrawContext ctx, float td) {
        if (mc.player == null || mc.world == null) return;
        List<Shown> list = visible();
        if (list.isEmpty()) return;
        Vec3d me = mc.player.getEntityPos();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        boolean coords = showCoordinates.get() && !coordsHidden();
        boolean nether = NETHER.equals(dim());
        float scale = labelScale.getFloat();
        var matrices = ctx.getMatrices();
        for (Shown s : list) {
            Vec3d at = new Vec3d(s.x() + 0.5, s.y() + 1.5, s.z() + 0.5);
            int dist = (int) Math.round(at.distanceTo(me));
            // Far points are drawn at a fixed distance along the line of sight so the label stays readable.
            Vec3d draw = at.squaredDistanceTo(cam) > 64 * 64 ? cam.add(at.subtract(cam).normalize().multiply(64)) : at;
            String where = coords ? " §8" + s.x() + ", " + s.y() + ", " + s.z() : "";
            String text = s.converted()
                    ? "§7" + s.point().name() + " §8(" + (nether ? "Overworld" : "Nether") + ")§7 " + dist + "m" + where
                    : s.point().name() + " §7" + dist + "m" + where;
            int color = s.converted() ? dimmed(s.color()) : s.color();
            if (Math.abs(scale - 1f) < 0.001f) {
                Label.draw(ctx, draw, text, color);
                continue;
            }
            // Scale the label about its own anchor, so it grows in place.
            double[] sp = Projection.toScreen(draw);
            if (sp == null) continue;
            matrices.pushMatrix();
            matrices.translate((float) sp[0], (float) sp[1]);
            matrices.scale(scale, scale);
            matrices.translate((float) -sp[0], (float) -sp[1]);
            Label.draw(ctx, draw, text, color);
            matrices.popMatrix();
        }
    }

    /** Points from the other dimension are drawn at half strength. */
    private static int dimmed(int argb) { return (argb & 0xFFFFFF) | 0x80000000; }
}
