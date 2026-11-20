package net.evadeclient.module.world;

import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.evadeclient.util.InvUtil;
import net.evadeclient.util.LitematicReader;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.PacketHooks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a schematic for you by placing its missing blocks around you.
 *
 * <p>If Litematica is installed and has a schematic placed, the Printer follows Litematica's placement exactly.
 * Otherwise it reads .litematic files itself: {@code .printer load <name>} puts the schematic at your feet.
 * Places bottom-up, from your hotbar (or swaps blocks in from your inventory), and handles the common block
 * states: facing, axis and slab half.
 */
public class Printer extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 2, 6, 0.5));
    private final NumberSetting perTick = add(new NumberSetting("Blocks/Tick", 2, 1, 8, 1));
    private final NumberSetting delay = add(new NumberSetting("Delay Ticks", 0, 0, 10, 1));
    private final BooleanSetting useLitematica = add(new BooleanSetting("Use Litematica", true));
    private final BooleanSetting refill = add(new BooleanSetting("Pull From Inventory", true));
    private final BooleanSetting swapBack = add(new BooleanSetting("Swap Back", true));
    private final BooleanSetting showMissing = add(new BooleanSetting("Show Missing", true));

    /** Schematic loaded by Evade itself (used when Litematica isn't driving the Printer). */
    private LitematicReader.Schematic loaded;
    private BlockPos origin = BlockPos.ORIGIN;
    private int wait;
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final List<BlockPos> missing = new ArrayList<>();
    private final List<BlockPos> wrong = new ArrayList<>();

    public Printer() {
        super("Printer", "Builds a schematic for you (Litematica or .litematic).", Category.WORLD);
    }

    // ------------------------------------------------------------------ schematics folder

    /** Evade's own schematics folder (inside the game folder the profile runs from). */
    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    /** Every folder the Printer reads: Evade's, plus the normal .minecraft/schematics when installed via the installer. */
    public static List<Path> folders() {
        List<Path> out = new ArrayList<>();
        Path game = FabricLoader.getInstance().getGameDir();
        out.add(game.resolve("schematics"));
        Path parent = game.getParent();
        if (parent != null && game.getFileName() != null && game.getFileName().toString().equals("evade")) {
            out.add(parent.resolve("schematics"));
        }
        return out;
    }

    public static void ensureFolder() {
        try { Files.createDirectories(folder()); } catch (IOException ignored) {}
    }

    public static List<Path> listFiles() {
        List<Path> files = new ArrayList<>();
        for (Path dir : folders()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir, 3)) {
                s.filter(p -> p.toString().endsWith(".litematic")).sorted().forEach(files::add);
            } catch (IOException ignored) {}
        }
        return files;
    }

    // ------------------------------------------------------------------ commands

    public void command(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "help";
        switch (sub) {
            case "list" -> {
                List<Path> files = listFiles();
                if (files.isEmpty()) CommandManager.print("No .litematic files. Put them in " + folder() + " (§d.printer folder§r opens it).");
                else files.forEach(f -> CommandManager.print("§7- §f" + f.getFileName().toString().replace(".litematic", "")));
            }
            case "load" -> {
                if (a.length < 3) { CommandManager.print("Usage: .printer load <name>"); return; }
                String want = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)).toLowerCase();
                Path match = listFiles().stream().filter(p -> p.getFileName().toString().toLowerCase().replace(".litematic", "").equals(want)).findFirst()
                        .orElse(listFiles().stream().filter(p -> p.getFileName().toString().toLowerCase().contains(want)).findFirst().orElse(null));
                if (match == null) { CommandManager.print("§cNo schematic named " + want + ". Try .printer list"); return; }
                try {
                    load(LitematicReader.read(match), mc.player.getBlockPos());
                    CommandManager.print("Loaded §d" + loaded.name() + "§r (" + loaded.size() + " blocks) at your feet. Turn on Printer to build it.");
                } catch (Exception e) {
                    CommandManager.print("§cCouldn't read " + match.getFileName() + ": " + e.getMessage());
                }
            }
            case "here" -> { origin = mc.player.getBlockPos(); attempts.clear(); CommandManager.print("Schematic moved to your feet."); }
            case "unload" -> { loaded = null; attempts.clear(); CommandManager.print("Schematic unloaded."); }
            case "folder" -> { ensureFolder(); Util.getOperatingSystem().open(folder().toFile()); CommandManager.print("Opened " + folder()); }
            case "materials" -> materials();
            case "info" -> CommandManager.print(source() == null ? "No schematic. Use .printer load <name> or place one with Litematica."
                    : "Using " + (litematicaWorld() != null ? "Litematica's placement" : "§d" + loaded.name() + "§r at " + origin.toShortString())
                    + ". Missing in range: " + missing.size());
            default -> {
                CommandManager.print("§d.printer list§7, §d.printer load <name>§7, §d.printer here§7, §d.printer materials§7,");
                CommandManager.print("§d.printer folder§7, §d.printer unload§7, §d.printer info");
            }
        }
    }

    public void load(LitematicReader.Schematic s, BlockPos at) {
        loaded = s;
        origin = at;
        attempts.clear();
    }

    /** Count of each block the loaded schematic still needs, versus what you're carrying. */
    private void materials() {
        if (loaded == null) { CommandManager.print("Load a schematic first (.printer load <name>)."); return; }
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (var e : loaded.blocks().entrySet()) {
            BlockPos world = origin.add(e.getKey());
            if (mc.world.getBlockState(world).equals(e.getValue())) continue;
            Item item = e.getValue().getBlock().asItem();
            if (item != Items.AIR) need.merge(item, 1, Integer::sum);
        }
        if (need.isEmpty()) { CommandManager.print("§aNothing missing. The build is complete."); return; }
        CommandManager.print("Still needed:");
        need.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(15).forEach(e -> {
            int have = InvUtil.count(st -> st.isOf(e.getKey()));
            String col = have >= e.getValue() ? "§a" : "§c";
            CommandManager.print("§7- §f" + new ItemStack(e.getKey()).getName().getString() + " " + col + have + "/" + e.getValue());
        });
    }

    // ------------------------------------------------------------------ Litematica integration

    /**
     * Litematica's schematic world (all placed schematics, at their real positions), or null.
     * A schematic loaded with ".printer load" takes priority; otherwise Litematica is used only if it has a placement.
     */
    private BlockView litematicaWorld() {
        if (loaded != null || !useLitematica.get() || !FabricLoader.getInstance().isModLoaded("litematica")) return null;
        try {
            Object mgr = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null);
            Object list = mgr.getClass().getMethod("getAllSchematicsPlacements").invoke(mgr);
            if (!(list instanceof java.util.Collection<?> c) || c.isEmpty()) return null;
            Object w = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld").invoke(null);
            return w instanceof BlockView bv ? bv : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Target state at a world position, from Litematica or the loaded schematic. */
    private BlockState target(BlockView lite, BlockPos world) {
        if (lite != null) {
            BlockState s = lite.getBlockState(world);
            return s == null || s.isAir() ? null : s;
        }
        return loaded == null ? null : loaded.blocks().get(world.subtract(origin));
    }

    private Object source() { return litematicaWorld() != null ? "litematica" : loaded; }

    // ------------------------------------------------------------------ printing

    @Override
    public void onTick() {
        BlockView lite = litematicaWorld();
        if (lite == null && loaded == null) return;
        missing.clear();
        wrong.clear();

        double r = range.get();
        int ri = (int) Math.ceil(r);
        BlockPos c = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(c.add(-ri, -ri, -ri), c.add(ri, ri, ri))) {
            BlockState want = target(lite, p);
            if (want == null) continue;
            BlockState have = mc.world.getBlockState(p);
            if (have.equals(want)) continue;
            BlockPos im = p.toImmutable();
            if (!have.isReplaceable()) { wrong.add(im); continue; }
            missing.add(im);
            if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) <= r * r && attempts.getOrDefault(im, 0) < 3) todo.add(im);
        }
        if (wait-- > 0) return;
        // bottom-up, nearest first, so every block has something to sit on
        todo.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));
        int placed = 0;
        for (BlockPos p : todo) {
            if (placed >= perTick.getInt()) break;
            BlockState want = target(lite, p);
            if (want == null) continue;
            if (!BlockUtil.canPlace(p, want)) continue;
            Item item = want.getBlock().asItem();
            if (item == Items.AIR) continue;
            int slot = InvUtil.ensureInHotbar(st -> st.isOf(item), refill.get());
            if (slot == -1) continue;
            if (placeState(p, want, slot)) {
                placed++;
                attempts.merge(p, 1, Integer::sum);
            }
        }
        if (placed > 0) wait = delay.getInt();
    }

    /** Place {@code want} at {@code pos}, choosing the click face/rotation that gives the right block state. */
    private boolean placeState(BlockPos pos, BlockState want, int slot) {
        Direction clickSide = null;   // side of the neighbour we click (pointing towards pos)
        Float yaw = null, pitch = null;
        boolean topHalf = false;

        if (want.contains(Properties.AXIS)) {
            Direction.Axis axis = want.get(Properties.AXIS);
            for (Direction d : Direction.values()) if (d.getAxis() == axis && solid(pos.offset(d))) { clickSide = d; break; }
        }
        if (want.contains(Properties.HORIZONTAL_FACING)) {
            // most blocks (furnaces, chests, stairs...) face back towards the player who placed them
            Direction face = want.get(Properties.HORIZONTAL_FACING);
            yaw = face.getOpposite().getPositiveHorizontalDegrees();
            pitch = 0f;
        } else if (want.contains(Properties.FACING)) {
            // pistons, observers, dispensers face the opposite of where the player looks... except observers
            Direction face = want.get(Properties.FACING);
            Direction look = want.getBlock() instanceof net.minecraft.block.ObserverBlock ? face : face.getOpposite();
            yaw = look.getAxis().isHorizontal() ? look.getPositiveHorizontalDegrees() : mc.player.getYaw();
            pitch = look == Direction.UP ? -90f : look == Direction.DOWN ? 90f : 0f;
        }
        if (want.getBlock() instanceof SlabBlock && want.contains(SlabBlock.TYPE)) topHalf = want.get(SlabBlock.TYPE) == SlabType.TOP;
        if (want.contains(Properties.BLOCK_HALF)) topHalf = want.get(Properties.BLOCK_HALF) == net.minecraft.block.enums.BlockHalf.TOP;

        boolean needsAxis = want.contains(Properties.AXIS);
        boolean needsHalf = (want.getBlock() instanceof SlabBlock && want.contains(SlabBlock.TYPE) && want.get(SlabBlock.TYPE) != SlabType.DOUBLE)
                || want.contains(Properties.BLOCK_HALF);
        if (clickSide == null && !needsAxis) {
            if (topHalf && solid(pos.up())) clickSide = Direction.UP;
            else if (!topHalf && solid(pos.down())) clickSide = Direction.DOWN;
            else if (!needsHalf) clickSide = BlockUtil.findSupport(pos);
        }

        BlockPos against;
        Direction face;
        Vec3d hit;
        if (clickSide != null) {
            // normal placement: click the neighbouring block's face
            against = pos.offset(clickSide);
            face = clickSide.getOpposite();
            hit = Vec3d.ofCenter(against).add(Vec3d.of(face.getVector()).multiply(0.5));
            if (face.getAxis().isHorizontal()) hit = hit.add(0, topHalf ? 0.25 : -0.25, 0);
        } else {
            // "air place": click the empty spot itself with the face that produces the right state
            // (an axis block takes the clicked face's axis; a slab clicked from below goes on top).
            against = pos;
            if (needsAxis) face = Direction.from(want.get(Properties.AXIS), Direction.AxisDirection.POSITIVE);
            else face = topHalf ? Direction.DOWN : Direction.UP;
            hit = Vec3d.ofCenter(pos).add(Vec3d.of(face.getVector()).multiply(0.5));
            if (face.getAxis().isHorizontal()) hit = hit.add(0, topHalf ? 0.25 : -0.25, 0);
        }

        var inv = mc.player.getInventory();
        int prevSlot = inv.getSelectedSlot();
        float prevYaw = mc.player.getYaw(), prevPitch = mc.player.getPitch();
        float y = yaw != null ? yaw : RotationUtil.rotationsTo(mc.player.getEyePos(), hit)[0];
        float pt = pitch != null ? pitch : RotationUtil.rotationsTo(mc.player.getEyePos(), hit)[1];
        inv.setSelectedSlot(slot);
        mc.player.setYaw(y);
        mc.player.setPitch(pt);
        PacketHooks.sendRaw(new PlayerMoveC2SPacket.LookAndOnGround(y, pt, mc.player.isOnGround(), mc.player.horizontalCollision));
        ActionResult res = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
        if (res.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.setYaw(prevYaw);
        mc.player.setPitch(prevPitch);
        if (swapBack.get()) inv.setSelectedSlot(prevSlot);
        return res.isAccepted();
    }

    private boolean solid(BlockPos p) {
        var s = mc.world.getBlockState(p);
        return !s.isReplaceable() && !s.getOutlineShape(mc.world, p).isEmpty();
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (!showMissing.get()) return;
        int n = 0;
        for (BlockPos p : missing) { if (n++ > 400) break; ctx.boxOutline(new Box(p).contract(0.05), 0x90FFFFFF, 1.2f); }
        for (BlockPos p : wrong) { if (n++ > 600) break; ctx.boxOutline(new Box(p), 0xFFE11D2E, 1.8f); }
    }

    public int missingCount() { return missing.size(); }

    @Override
    public String getSuffix() {
        if (litematicaWorld() != null) return "Litematica";
        return loaded == null ? "No schematic" : loaded.name() + " " + missing.size();
    }
}
