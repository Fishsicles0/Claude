package net.evadeclient.module.misc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.EvadeRenderLayers;
import net.evadeclient.render.RenderContext;
import net.evadeclient.schematic.Materials;
import net.evadeclient.schematic.Placement;
import net.evadeclient.schematic.Schematic;
import net.evadeclient.schematic.SchematicIO;
import net.evadeclient.schematic.SchematicVerifier;
import net.evadeclient.schematic.SingleplayerPaste;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.util.InvUtil;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.ServerRules;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Schematics: Evade's own building helper. Load a {@code .litematic}, Sponge {@code .schem} or structure {@code .nbt}
 * from the game folder's {@code schematics} folder, place it (at your feet or the block you look at), nudge, turn
 * and mirror it, and see its blocks as see-through ghosts where they still need to go. The verifier marks wrong
 * blocks, wrong orientations and blocks in the way; the material list says what you need, what's placed and what
 * you carry. Mark two corners to save what you see as a schematic of your own.
 *
 * <p>Everything here only draws and reads what the client already has: it never places, breaks or clicks anything,
 * never picks blocks for you and changes no reach. A server can switch it off with Evade's server rules. The one
 * exception, "Paste in Singleplayer", only works in your own singleplayer/LAN world with cheats on, on the built-in
 * server (see {@link SingleplayerPaste}).
 */
public class Schematics extends Module {
    private static Schematics instance;

    // -- schematic
    private final StatusSetting schematicRow = add((StatusSetting) new StatusSetting("Schematic", this::schematicLabel, "click: choose")
            .onClick(() -> net.evadeclient.gui.SchematicBrowserScreen.open(mc.currentScreen))
            .describe("The schematic you're building. Click to pick one from your schematics folder (also .schem list)."));
    private final StatusSetting folderRow = add((StatusSetting) new StatusSetting("Schematics Folder", () -> "schematics", "click: open folder")
            .onClick(Schematics::openFolder)
            .describe("Opens the game folder's schematics folder: put .litematic, .schem and .nbt files here."));
    private final StatusSetting materialsRow = add((StatusSetting) new StatusSetting("Material List", this::materialsLabel, "click: open")
            .onClick(() -> net.evadeclient.gui.MaterialListScreen.open(mc.currentScreen))
            .describe("What the schematic needs, what's already placed and what you carry; copy it as text (also .schem materials)."));
    private final ModeSetting placeAt = add(new ModeSetting("Place At", "Feet", "Feet", "Looked-at Block")
            .describe("Where a newly loaded schematic starts: its corner at your feet, or next to the block you look at."));
    // -- ghost
    private final BooleanSetting ghost = add(new BooleanSetting("Ghost Blocks", true)
            .describe("Shows the blocks still to place as see-through coloured holograms."));
    private final NumberSetting opacity = add(new NumberSetting("Ghost Opacity", 40, 10, 90, 5)
            .describe("How solid the ghost blocks look, in percent."));
    private final NumberSetting range = add(new NumberSetting("Ghost Range", 48, 16, 128, 8)
            .describe("Ghosts and marks are drawn only this many blocks around you, to keep it light."));
    private final NumberSetting maxBlocks = add(new NumberSetting("Max Ghost Blocks", 15000, 1000, 50000, 1000)
            .describe("The most ghost blocks drawn at once (the nearest ones). Lower it if a big build costs FPS."));
    private final BooleanSetting showBox = add(new BooleanSetting("Show Box", true)
            .describe("Outlines the schematic's whole area, and the selection you're marking."));
    // -- verifier
    private final BooleanSetting verifier = add(new BooleanSetting("Verifier", false)
            .describe("Checks the build against the blocks you can see: missing blue, wrong red, wrong way orange, in the way magenta."));
    private final ModeSetting correct = add(new ModeSetting("Correct Blocks", "Hidden", "Hidden", "Faint")
            .describe("With the Verifier on: hide blocks that are already right, or tint them faint green."));
    private final BooleanSetting showExtra = add(new BooleanSetting("Show Extra Blocks", true)
            .describe("With the Verifier on: mark blocks standing where the schematic wants air."));
    private final ColorSetting missingColor = add(new ColorSetting("Missing Color", 0xFF3D7BFF).opaque()
            .describe("Verifier colour for blocks that still need placing."));
    private final ColorSetting wrongColor = add(new ColorSetting("Wrong Block Color", 0xFFFF4D5E).opaque()
            .describe("Verifier colour for a different block than the schematic's."));
    private final ColorSetting stateColor = add(new ColorSetting("Wrong State Color", 0xFFFF8A1F).opaque()
            .describe("Verifier colour for the right block facing or set up the wrong way."));
    private final ColorSetting extraColor = add(new ColorSetting("Extra Block Color", 0xFFFF3DF0).opaque()
            .describe("Verifier colour for blocks where the schematic wants air."));
    // -- layers
    private final ModeSetting layers = add(new ModeSetting("Layers", "All", "All", "Single", "Below", "Above")
            .describe("Show every layer, just one, everything up to it, or everything from it up."));
    private final NumberSetting layer = add(new NumberSetting("Layer", 0, 0, 1023, 1)
            .describe("The layer Single/Below/Above use, counted from the schematic's bottom (0)."));
    // -- keys
    private final BooleanSetting lockPlacement = add(new BooleanSetting("Lock Placement", false)
            .describe("Stops the nudge, turn and mirror keys from moving the schematic by accident."));
    private final KeySetting nudgeForward = add(new KeySetting("Nudge Forward", GLFW.GLFW_KEY_UP)
            .describe("Moves the schematic one block the way you face."));
    private final KeySetting nudgeBack = add(new KeySetting("Nudge Back", GLFW.GLFW_KEY_DOWN)
            .describe("Moves the schematic one block towards you."));
    private final KeySetting nudgeLeft = add(new KeySetting("Nudge Left", GLFW.GLFW_KEY_LEFT)
            .describe("Moves the schematic one block to your left."));
    private final KeySetting nudgeRight = add(new KeySetting("Nudge Right", GLFW.GLFW_KEY_RIGHT)
            .describe("Moves the schematic one block to your right."));
    private final KeySetting nudgeUp = add(new KeySetting("Nudge Up", GLFW.GLFW_KEY_PAGE_UP)
            .describe("Moves the schematic one block up."));
    private final KeySetting nudgeDown = add(new KeySetting("Nudge Down", GLFW.GLFW_KEY_PAGE_DOWN)
            .describe("Moves the schematic one block down."));
    private final KeySetting rotateKey = add(new KeySetting("Rotate Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Turns the schematic a quarter turn clockwise (also .schem rotate)."));
    private final KeySetting mirrorKey = add(new KeySetting("Mirror Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Steps the mirror: none, front-back, left-right (also .schem mirror)."));
    private final KeySetting layerUpKey = add(new KeySetting("Layer Up Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Shows the next layer up (switches Layers to Single if it's on All)."));
    private final KeySetting layerDownKey = add(new KeySetting("Layer Down Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Shows the next layer down (switches Layers to Single if it's on All)."));
    private final KeySetting cornerKey = add(new KeySetting("Selection Corner Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Marks the block you look at as a corner of the area to save: first corner, then second, in turn."));
    // -- singleplayer
    private final BooleanSetting pasteAir = add(new BooleanSetting("Paste Air", true)
            .describe("Paste in Singleplayer also clears blocks where the schematic has air."));
    private final StatusSetting pasteRow = add((StatusSetting) new StatusSetting("Paste in Singleplayer", Schematics::pasteLabel, "click: paste")
            .onClick(() -> pasteCommand())
            .describe("Your own singleplayer or LAN world only, with cheats on: builds the placed schematic for you. Never on servers."));

    private static final int RESCAN_TICKS = 10, KEY_REPEAT_DELAY = 8, KEY_REPEAT_EVERY = 2;

    private Placement placement;
    private int[] paletteCounts = new int[0];
    private final SchematicVerifier verifierPass = new SchematicVerifier();
    private SchematicVerifier.Options lastOptions;
    private int idleTicks;
    private int scanRevision = -1;
    private String loading;
    private String worldKey;
    private BlockPos corner1, corner2;
    private boolean nextCornerIsSecond;
    private final java.util.Map<KeySetting, Integer> held = new java.util.HashMap<>();
    private JsonObject store;
    private boolean storeDirty;
    private long storeSavedAt;
    private List<Materials.Entry> materialsCache;
    private long materialsAt = -1;

    public Schematics() {
        super("Schematics", "Load a schematic, see it as ghost blocks, check your build and list its materials.", Category.MISC);
        instance = this;
    }

    public static Schematics get() { return instance; }

    // ------------------------------------------------------------------ folder and files

    /** The game folder's {@code schematics} folder (created when needed). */
    public static Path folder() { return FabricLoader.getInstance().getGameDir().resolve("schematics"); }

    public static void openFolder() {
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            Util.getOperatingSystem().open(dir.toFile());
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Couldn't open {}", dir, e);
        }
    }

    /** The schematic files in the folder (and one level of subfolders), newest first, as paths relative to it. */
    public static List<String> files() {
        Path dir = folder();
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> s = Files.walk(dir, 2)) {
                s.filter(Files::isRegularFile).filter(p -> SchematicIO.supported(p.getFileName().toString()))
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .limit(500)
                        .forEach(p -> out.add(dir.relativize(p).toString().replace('\\', '/')));
            }
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Couldn't list {}", dir, e);
        }
        return out;
    }

    /** A file inside the folder by relative name, or null if the name points outside it. */
    static Path resolve(String name) {
        if (name == null || name.isBlank()) return null;
        Path dir = folder().toAbsolutePath().normalize();
        try {
            Path p = dir.resolve(name.strip()).normalize();
            return p.startsWith(dir) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ state

    public Placement placement() { return placement; }
    public SchematicVerifier verifier() { return verifierPass; }
    public boolean loading() { return loading != null; }
    public BlockPos corner1() { return corner1; }
    public BlockPos corner2() { return corner2; }

    /** Whether the server you're on has switched Schematics off. */
    public boolean blockedByServer() { return ServerRules.isDisabled(this); }

    private String schematicLabel() {
        if (loading != null) return "Loading " + loading + "...";
        if (placement == null) return "None loaded";
        Schematic s = placement.schematic;
        return s.name + " (" + placement.sizeX() + "x" + placement.sizeY() + "x" + placement.sizeZ() + ")";
    }

    private String materialsLabel() {
        if (placement == null) return "No schematic";
        List<Materials.Entry> m = materials();
        return Materials.totalLeft(m) + " blocks left, " + m.size() + " kinds";
    }

    private static String pasteLabel() {
        String why = SingleplayerPaste.unavailableReason();
        return why == null ? "Your own world" : "Singleplayer only";
    }

    /** The current material list (recomputed at most once a second). */
    public List<Materials.Entry> materials() {
        if (placement == null) return List.of();
        if (materialsCache != null && EvadeClient.ticks - materialsAt < 20 && materialsAt >= 0) return materialsCache;
        var r = verifierPass.last();
        int[] placed = r != null && verifierPass.placement() == placement ? r.placedByPalette() : null;
        materialsCache = Materials.list(placement.schematic, paletteCounts, placed, item -> InvUtil.count(st -> st.isOf(item)));
        materialsAt = EvadeClient.ticks;
        return materialsCache;
    }

    /** Forget the cached material list (after a pass or a load). */
    private void materialsChanged() { materialsCache = null; }

    // ------------------------------------------------------------------ loading and placing

    /** Loads {@code file} (relative to the folder) in the background and places it. Feedback goes to chat/toasts. */
    public void load(String file) {
        if (blockedByServer()) { Notifications.push("Schematics", "Disabled by this server", false); return; }
        Path p = resolve(file);
        if (p == null || !Files.isRegularFile(p)) { CommandManager.print("§cNo schematic called §f" + file + "§c in the schematics folder."); return; }
        String rel = folder().toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/');
        loading = rel;
        Thread t = new Thread(() -> {
            Schematic s = null;
            String error = null;
            try {
                s = SchematicIO.read(p);
            } catch (SchematicIO.BadFile e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = "the file couldn't be read";
            } catch (RuntimeException | OutOfMemoryError e) {
                error = "it failed to load (" + e.getClass().getSimpleName() + ")";
            }
            Schematic done = s;
            String err = error;
            mc.execute(() -> {
                if (!rel.equals(loading)) return; // another load replaced this one
                loading = null;
                if (err != null) {
                    Notifications.push("Schematics", "Couldn't load " + rel + ": " + err, false);
                    CommandManager.print("§cCouldn't load §f" + rel + "§c: " + err);
                    return;
                }
                use(done, rel, null);
            });
        }, "Evade schematic loader");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Makes {@code s} the active schematic. Its saved placement in this world is used when there is one, else
     * {@code at} (null: where Place At says).
     */
    public void use(Schematic s, String file, BlockPos at) {
        BlockPos origin = at;
        Placement saved = savedPlacement(s, file);
        if (origin == null && saved != null) {
            placement = saved;
        } else {
            placement = new Placement(s, file, origin != null ? origin : defaultOrigin());
        }
        paletteCounts = Materials.paletteCounts(s);
        verifierPass.clear();
        scanRevision = -1;
        materialsChanged();
        rememberPlacement();
        String extra = s.unknownStates > 0 ? " (" + s.unknownStates + " unknown block types left out)" : "";
        Notifications.push("Schematics", "Loaded " + s.name + " (" + s.sizeX + "x" + s.sizeY + "x" + s.sizeZ + ")" + extra, true);
    }

    public void unload() {
        placement = null;
        loading = null;
        verifierPass.clear();
        materialsChanged();
        if (worldKey != null) { store().getAsJsonObject("last").remove(worldKey); storeDirty = true; }
    }

    private BlockPos defaultOrigin() {
        if (mc.player == null) return BlockPos.ORIGIN;
        if (placeAt.is("Looked-at Block")) {
            BlockPos look = lookedAt();
            if (look != null) return look;
        }
        return mc.player.getBlockPos();
    }

    /** The air block next to the face you look at (where you'd put a block), or null. */
    private BlockPos lookedAt() {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b.getBlockPos().offset(b.getSide());
        return null;
    }

    private BlockPos lookedAtBlock() {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
        return null;
    }

    public void moveTo(BlockPos p) {
        if (placement == null) return;
        placement.setOrigin(p);
        rememberPlacement();
    }

    public void nudge(int dx, int dy, int dz) {
        if (placement == null) return;
        placement.move(dx, dy, dz);
        rememberPlacement();
    }

    public void rotate(boolean clockwise) {
        if (placement == null) return;
        placement.rotate(clockwise);
        verifierPass.clear();
        materialsChanged();
        rememberPlacement();
    }

    public void setMirror(int m) {
        if (placement == null) return;
        placement.setMirror(m);
        verifierPass.clear();
        materialsChanged();
        rememberPlacement();
    }

    public void setLayerMode(String mode) { layers.set(mode); }
    public void setLayer(int l) { layer.set((double) Math.max(0, l)); }

    // ------------------------------------------------------------------ per tick

    @Override
    protected void onEnable() {
        try { Files.createDirectories(folder()); } catch (IOException ignored) {}
        // Bring back the schematic placed in this world last time (before anything loaded after this replaces it).
        if (mc.world != null && mc.player != null) {
            worldKey = currentWorldKey();
            restoreForWorld();
        } else {
            worldKey = null;
        }
    }

    @Override
    protected void onDisable() {
        flushStore();
        verifierPass.clear();
        scanRevision = -1;
    }

    @Override
    public void onTick() {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;
        String key = currentWorldKey();
        if (!key.equals(worldKey)) {
            worldKey = key;
            restoreForWorld();
        }
        handleKeys();
        if (storeDirty && System.currentTimeMillis() - storeSavedAt > 2000) flushStore();
        if (placement == null) return;

        SchematicVerifier.Options o = options();
        boolean changed = placement.revision() != scanRevision || !o.equals(lastOptions) || verifierPass.placement() != placement;
        if (verifierPass.running() && changed) {
            startPass(o);
        } else if (verifierPass.running()) {
            if (verifierPass.step(world, SchematicVerifier.BUDGET)) { idleTicks = 0; materialsChanged(); }
        } else if (changed || ++idleTicks >= RESCAN_TICKS) {
            startPass(o);
        }
    }

    private void startPass(SchematicVerifier.Options o) {
        Vec3d p = mc.player.getEntityPos();
        verifierPass.start(placement, o, p.x, p.y, p.z);
        lastOptions = o;
        scanRevision = placement.revision();
        idleTicks = 0;
        if (verifierPass.step(mc.world, SchematicVerifier.BUDGET)) materialsChanged();
    }

    /** Runs a whole verifier pass right now (tests, and after a command so the answer is current). */
    public SchematicVerifier.Result verifyNow() {
        if (placement == null || mc.world == null || mc.player == null) return null;
        Vec3d p = mc.player.getEntityPos();
        SchematicVerifier.Options o = options();
        var r = verifierPass.runNow(placement, o, mc.world, p.x, p.y, p.z);
        lastOptions = o;
        scanRevision = placement.revision();
        idleTicks = 0;
        materialsChanged();
        return r;
    }

    public SchematicVerifier.Options options() {
        int mode = switch (layers.get()) {
            case "Single" -> SchematicVerifier.Options.LAYERS_SINGLE;
            case "Below" -> SchematicVerifier.Options.LAYERS_BELOW;
            case "Above" -> SchematicVerifier.Options.LAYERS_ABOVE;
            default -> SchematicVerifier.Options.LAYERS_ALL;
        };
        int alpha = (int) Math.round(opacity.get() * 2.55);
        return new SchematicVerifier.Options(ghost.get(), verifier.get(), correct.is("Faint"), showExtra.get(), mode,
                layer.getInt(), range.getInt(), maxBlocks.getInt(), alpha, missingColor.get(), wrongColor.get(),
                stateColor.get(), extraColor.get(), 0xFF3BE37A);
    }

    private void handleKeys() {
        if (mc.currentScreen != null) { held.clear(); return; }
        if (pressed(cornerKey)) markCorner();
        if (placement == null) return;
        if (pressed(layerUpKey)) stepLayer(1);
        if (pressed(layerDownKey)) stepLayer(-1);
        if (lockPlacement.get()) return;
        Direction f = mc.player.getHorizontalFacing();
        Direction left = f.rotateYCounterclockwise();
        if (pressed(nudgeForward)) nudge(f.getOffsetX(), 0, f.getOffsetZ());
        if (pressed(nudgeBack)) nudge(-f.getOffsetX(), 0, -f.getOffsetZ());
        if (pressed(nudgeLeft)) nudge(left.getOffsetX(), 0, left.getOffsetZ());
        if (pressed(nudgeRight)) nudge(-left.getOffsetX(), 0, -left.getOffsetZ());
        if (pressed(nudgeUp)) nudge(0, 1, 0);
        if (pressed(nudgeDown)) nudge(0, -1, 0);
        if (pressed(rotateKey)) rotate(true);
        if (pressed(mirrorKey)) setMirror((placement.mirror() + 1) % 3);
    }

    /** A key press, with key repeat while held. */
    private boolean pressed(KeySetting k) {
        if (k.get() == GLFW.GLFW_KEY_UNKNOWN) return false;
        if (!k.isDown()) { held.remove(k); return false; }
        int t = held.merge(k, 1, Integer::sum);
        return t == 1 || (t > KEY_REPEAT_DELAY && (t - KEY_REPEAT_DELAY) % KEY_REPEAT_EVERY == 0);
    }

    private void stepLayer(int d) {
        if (layers.is("All")) layers.set("Single");
        int max = placement == null ? 1023 : placement.sizeY() - 1;
        setLayer(Math.max(0, Math.min(max, layer.getInt() + d)));
    }

    private void markCorner() {
        BlockPos b = lookedAtBlock();
        if (b == null) { Notifications.push("Schematics", "Look at a block to mark a corner", false); return; }
        if (!nextCornerIsSecond) corner1 = b; else corner2 = b;
        Notifications.push("Schematics", (nextCornerIsSecond ? "Corner 2" : "Corner 1") + " at " + b.getX() + ", " + b.getY() + ", " + b.getZ(), true);
        nextCornerIsSecond = !nextCornerIsSecond;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void onRender(RenderContext ctx) {
        Vec3d cam = ctx.cameraPos();
        if (placement != null) {
            BlockPos o = placement.origin();
            var r = verifierPass.last();
            if (r != null && verifierPass.placement() == placement && r.mesh().quads() > 0) {
                VertexConsumer vc = ctx.consumers().getBuffer(EvadeRenderLayers.QUADS_DEPTH);
                r.mesh().emit(vc, ctx.matrices().peek(), (float) (o.getX() - cam.x), (float) (o.getY() - cam.y), (float) (o.getZ() - cam.z));
                ctx.flush();
            }
            if (showBox.get()) {
                int x1 = o.getX(), y1 = o.getY(), z1 = o.getZ();
                ctx.boxOutline(x1, y1, z1, x1 + placement.sizeX(), y1 + placement.sizeY(), z1 + placement.sizeZ(), 0xB029D3F0, 1.5f);
                if (!layers.is("All")) {
                    int ly = y1 + Math.min(layer.getInt(), placement.sizeY() - 1);
                    ctx.boxOutline(x1, ly, z1, x1 + placement.sizeX(), ly + 1, z1 + placement.sizeZ(), 0xC0FFD21F, 1.5f);
                }
            }
        }
        if (showBox.get() && corner1 != null) {
            BlockPos a = corner1, b = corner2 != null ? corner2 : corner1;
            ctx.boxOutline(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1, 0xE0FFD21F, 2f);
        }
    }

    @Override
    public String getSuffix() { return placement == null ? null : placement.schematic.name; }

    // ------------------------------------------------------------------ saving a selection

    /**
     * Saves the marked area as {@code name}.litematic (or .nbt), from the blocks the client has. Returns the file
     * name written. Throws {@link CommandError} with the reason it can't.
     */
    public String saveSelection(String name, boolean nbt) {
        if (corner1 == null || corner2 == null) throw new CommandError("Mark two corners first (.schem pos1 / .schem pos2, or the Selection Corner Key).");
        ClientWorld w = mc.world;
        if (w == null) throw new CommandError("Join a world first.");
        int minX = Math.min(corner1.getX(), corner2.getX()), maxX = Math.max(corner1.getX(), corner2.getX());
        int minZ = Math.min(corner1.getZ(), corner2.getZ()), maxZ = Math.max(corner1.getZ(), corner2.getZ());
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) throw new CommandError("Part of the area isn't loaded. Move closer so you can see all of it.");
        String clean = cleanName(name);
        if (clean == null) throw new CommandError("Use a plain name: letters, numbers, spaces, - and _.");
        String ext = nbt ? ".nbt" : ".litematic";
        Path dir = folder();
        Path file = dir.resolve(clean + ext);
        for (int i = 2; Files.exists(file) && i < 1000; i++) file = dir.resolve(clean + " (" + i + ")" + ext);
        Schematic s;
        try {
            s = SchematicIO.capture(w, clean, mc.getSession().getUsername(), corner1.getX(), corner1.getY(), corner1.getZ(),
                    corner2.getX(), corner2.getY(), corner2.getZ());
        } catch (SchematicIO.BadFile e) {
            throw new CommandError("Can't save that: " + e.getMessage() + ".");
        }
        try {
            if (nbt) SchematicIO.writeStructure(s, file); else SchematicIO.writeLitematic(s, file);
        } catch (IOException e) {
            throw new CommandError("Couldn't write the file: " + e.getMessage());
        }
        return file.getFileName().toString();
    }

    /** A safe file name (without extension), or null. */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.toLowerCase(Locale.ROOT).endsWith(".litematic")) s = s.substring(0, s.length() - 10);
        else if (s.toLowerCase(Locale.ROOT).endsWith(".nbt")) s = s.substring(0, s.length() - 4);
        if (s.isEmpty() || s.length() > 64 || s.startsWith(".")) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '(' || c == ')' || c == '.')) return null;
        }
        return s;
    }

    public void setCorner(int which, BlockPos p) {
        if (which == 1) corner1 = p; else corner2 = p;
    }

    // ------------------------------------------------------------------ paste (singleplayer only)

    private void pasteCommand() {
        if (placement == null) { Notifications.push("Schematics", "Load a schematic first", false); return; }
        if (blockedByServer()) { Notifications.push("Schematics", "Disabled by this server", false); return; }
        SingleplayerPaste.paste(placement, pasteAir.get(), msg -> {
            Notifications.push("Schematics", msg, !msg.startsWith("Paste needs") && !msg.contains("never on a server"));
            CommandManager.print("§7" + msg);
        });
    }

    // ------------------------------------------------------------------ per-world memory

    private String currentWorldKey() {
        String base;
        var server = mc.getServer();
        if (mc.isInSingleplayer() && server != null) base = "sp:" + server.getSaveProperties().getLevelName();
        else {
            var e = mc.getCurrentServerEntry();
            base = "mp:" + (e == null ? "unknown" : e.address.toLowerCase(Locale.ROOT));
        }
        return base + "|" + mc.world.getRegistryKey().getValue();
    }

    private static Path storeFile() { return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("schematics.json"); }

    private JsonObject store() {
        if (store == null) {
            try {
                Path f = storeFile();
                store = Files.isRegularFile(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            } catch (IOException | RuntimeException e) {
                store = new JsonObject();
            }
            if (!store.has("last") || !store.get("last").isJsonObject()) store.add("last", new JsonObject());
            if (!store.has("placements") || !store.get("placements").isJsonObject()) store.add("placements", new JsonObject());
        }
        return store;
    }

    private void rememberPlacement() {
        if (placement == null || mc.world == null) return;
        if (worldKey == null) worldKey = currentWorldKey();
        JsonObject p = new JsonObject();
        BlockPos o = placement.origin();
        p.addProperty("x", o.getX());
        p.addProperty("y", o.getY());
        p.addProperty("z", o.getZ());
        p.addProperty("rotation", placement.rotation());
        p.addProperty("mirror", placement.mirror());
        JsonObject placements = store().getAsJsonObject("placements");
        placements.add(worldKey + "|" + placement.file, p);
        // Keep the file small: drop the oldest entries past 200.
        while (placements.size() > 200) placements.remove(placements.keySet().iterator().next());
        store().getAsJsonObject("last").addProperty(worldKey, placement.file);
        storeDirty = true;
    }

    private Placement savedPlacement(Schematic s, String file) {
        if (mc.world == null) return null;
        if (worldKey == null) worldKey = currentWorldKey();
        try {
            var el = store().getAsJsonObject("placements").get(worldKey + "|" + file);
            if (el == null || !el.isJsonObject()) return null;
            JsonObject p = el.getAsJsonObject();
            Placement out = new Placement(s, file, new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()));
            out.setRotation(p.has("rotation") ? p.get("rotation").getAsInt() : 0);
            out.setMirror(p.has("mirror") ? p.get("mirror").getAsInt() : 0);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** On joining a world (or turning on): bring back the schematic you had placed here. */
    private void restoreForWorld() {
        placement = null;
        verifierPass.clear();
        materialsChanged();
        corner1 = corner2 = null;
        try {
            var el = store().getAsJsonObject("last").get(worldKey);
            if (el != null && el.isJsonPrimitive()) {
                String file = el.getAsString();
                if (resolve(file) != null && Files.isRegularFile(resolve(file))) load(file);
            }
        } catch (RuntimeException ignored) {}
    }

    private void flushStore() {
        if (!storeDirty || store == null) return;
        storeDirty = false;
        storeSavedAt = System.currentTimeMillis();
        try {
            Path f = storeFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("schematics.json.tmp");
            Files.writeString(tmp, store.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            EvadeClient.LOG.warn("Couldn't save schematic placements", e);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public List<String> commandAliases() { return List.of("schem", "schematic", "schematics"); }

    @Override
    public List<String> commandHelp() {
        return List.of("§d.schem§7 - schematics: list, load <file>, place [feet|look], move <x y z>, rotate, mirror, layer <n|all|up|down>, verify, materials, pos1, pos2, save <name> [nbt], unload, folder, paste, undo");
    }

    @Override
    public void command(String[] a) {
        if (blockedByServer()) throw new CommandError("Schematics is disabled by this server.");
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        if (!isEnabled() && !sub.equals("folder") && !sub.equals("list")) {
            setEnabled(true);
            if (!isEnabled()) throw new CommandError("Schematics couldn't be turned on.");
        }
        switch (sub) {
            case "info" -> {
                if (placement == null) { CommandManager.print("No schematic loaded. §d.schem list§7 shows your files."); return; }
                BlockPos o = placement.origin();
                CommandManager.print("§f" + placement.schematic.name + "§7 (" + placement.schematic.format + ") at " + o.getX() + ", " + o.getY() + ", " + o.getZ()
                        + ", turned " + placement.rotation() * 90 + "°, mirror " + Placement.mirrorName(placement.mirror()));
            }
            case "list", "ls" -> {
                List<String> f = files();
                if (f.isEmpty()) CommandManager.print("No schematics yet. Put .litematic, .schem or .nbt files in §fschematics§7 (§d.schem folder§7).");
                else CommandManager.print("Schematics: §f" + String.join("§7, §f", f.subList(0, Math.min(20, f.size()))) + (f.size() > 20 ? "§7 and " + (f.size() - 20) + " more" : ""));
            }
            case "load", "open" -> {
                if (a.length < 3) throw new CommandError("Usage: .schem load <file>");
                String name = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                String match = files().stream().filter(f -> f.equalsIgnoreCase(name) || SchematicIO.baseName(f).equalsIgnoreCase(name)).findFirst().orElse(name);
                load(match);
            }
            case "unload", "clear" -> { unload(); CommandManager.print("Schematic unloaded."); }
            case "place", "here" -> {
                need();
                boolean look = a.length > 2 && a[2].toLowerCase(Locale.ROOT).startsWith("look");
                BlockPos p = look ? lookedAt() : mc.player.getBlockPos();
                if (p == null) throw new CommandError("Look at a block first.");
                moveTo(p);
                CommandManager.print("Placed at " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ".");
            }
            case "move", "nudge" -> {
                need();
                if (a.length < 5) throw new CommandError("Usage: .schem move <x> <y> <z> (blocks to move by)");
                try { nudge(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])); }
                catch (NumberFormatException e) { throw new CommandError("Usage: .schem move <x> <y> <z> (whole numbers)"); }
                BlockPos o = placement.origin();
                CommandManager.print("Moved to " + o.getX() + ", " + o.getY() + ", " + o.getZ() + ".");
            }
            case "rotate", "turn" -> {
                need();
                String dir = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                boolean ccw = dir.equals("ccw") || dir.equals("left") || dir.startsWith("counter") || dir.startsWith("anti");
                rotate(!ccw);
                CommandManager.print("Turned to " + placement.rotation() * 90 + "°.");
            }
            case "mirror", "flip" -> {
                need();
                String m = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                int v = switch (m) {
                    case "none", "off" -> Placement.MIRROR_NONE;
                    case "x", "front-back", "frontback" -> Placement.MIRROR_X;
                    case "z", "left-right", "leftright" -> Placement.MIRROR_Z;
                    default -> (placement.mirror() + 1) % 3;
                };
                setMirror(v);
                CommandManager.print("Mirror: " + Placement.mirrorName(placement.mirror()) + ".");
            }
            case "layer", "layers" -> {
                need();
                String m = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                switch (m) {
                    case "all", "off" -> layers.set("All");
                    case "up", "+" -> stepLayer(1);
                    case "down", "-" -> stepLayer(-1);
                    case "single", "below", "above" -> layers.set(Character.toUpperCase(m.charAt(0)) + m.substring(1));
                    default -> {
                        try { setLayer(Integer.parseInt(m)); if (layers.is("All")) layers.set("Single"); }
                        catch (NumberFormatException e) { throw new CommandError("Usage: .schem layer <number|all|up|down|single|below|above>"); }
                    }
                }
                CommandManager.print("Layers: " + layers.get() + (layers.is("All") ? "" : " " + layer.getInt()) + ".");
            }
            case "verify", "check" -> {
                need();
                if (!verifier.get()) verifier.set(true);
                var r = verifyNow();
                if (r == null) return;
                CommandManager.print(String.format(Locale.ROOT, "§aCorrect %d§7, §9missing %d§7, §cwrong %d§7, §6wrong state %d§7, §dextra %d§7%s",
                        r.count(SchematicVerifier.CORRECT), r.count(SchematicVerifier.MISSING), r.count(SchematicVerifier.WRONG),
                        r.count(SchematicVerifier.WRONG_STATE), r.count(SchematicVerifier.EXTRA),
                        r.count(SchematicVerifier.UNLOADED) > 0 ? ", not loaded " + r.count(SchematicVerifier.UNLOADED) : ""));
            }
            case "materials", "mats", "list-materials" -> {
                need();
                mc.send(() -> net.evadeclient.gui.MaterialListScreen.open(null));
            }
            case "pos1", "pos2", "corner1", "corner2" -> {
                int which = sub.endsWith("1") ? 1 : 2;
                boolean look = a.length > 2 && a[2].toLowerCase(Locale.ROOT).startsWith("look");
                BlockPos p = look ? lookedAtBlock() : mc.player.getBlockPos();
                if (p == null) throw new CommandError("Look at a block first.");
                setCorner(which, p);
                CommandManager.print("Corner " + which + " at " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ".");
            }
            case "save" -> {
                if (a.length < 3) throw new CommandError("Usage: .schem save <name> [nbt]");
                boolean nbt = a[a.length - 1].equalsIgnoreCase("nbt");
                int end = nbt ? a.length - 1 : a.length;
                if (end <= 2) throw new CommandError("Usage: .schem save <name> [nbt]");
                String name = String.join(" ", java.util.Arrays.copyOfRange(a, 2, end));
                String file = saveSelection(name, nbt);
                CommandManager.print("Saved §f" + file + "§7 in the schematics folder.");
            }
            case "folder" -> openFolder();
            case "paste" -> pasteCommand();
            case "undo" -> SingleplayerPaste.undo(msg -> CommandManager.print("§7" + msg));
            default -> throw new CommandError("Unknown: .schem " + sub + ". Try .help for the list.");
        }
    }

    private void need() {
        if (mc.player == null || mc.world == null) throw new CommandError("Join a world first.");
        if (placement == null) throw new CommandError(loading != null ? "Still loading the schematic..." : "Load a schematic first (.schem list, .schem load <file>).");
    }
}
