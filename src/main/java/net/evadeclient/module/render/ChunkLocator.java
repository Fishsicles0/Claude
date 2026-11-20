package net.evadeclient.module.render;

import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds chunks that show signs of players: stashes of storage, shulkers, rotated deepslate (natural deepslate
 * is always vertical), and player-made blocks deep underground. Highlights them and pings you in chat.
 * Section checks use the chunk palette, so scanning is very cheap.
 */
public class ChunkLocator extends Module {
    private final NumberSetting minStorage = add(new NumberSetting("Min Storage", 4, 1, 30, 1));
    private final BooleanSetting shulkers = add(new BooleanSetting("Shulkers", true));
    private final BooleanSetting deepslate = add(new BooleanSetting("Rotated Deepslate", true));
    private final BooleanSetting underground = add(new BooleanSetting("Deep Player Blocks", true));
    private final BooleanSetting spawners = add(new BooleanSetting("Spawners", false));
    private final BooleanSetting notify = add(new BooleanSetting("Notify", true));
    private final BooleanSetting followY = add(new BooleanSetting("Follow My Y", true));
    private final NumberSetting renderY = add(new NumberSetting("Render Y", 64, -64, 320, 1));
    private final NumberSetting perTick = add(new NumberSetting("Chunks/Tick", 4, 1, 32, 1));

    private record Hit(ChunkPos pos, String reason) {}

    private final Map<Long, Hit> found = new LinkedHashMap<>();
    private final Map<Long, Long> lastScan = new HashMap<>();
    private final List<ChunkPos> queue = new ArrayList<>();

    private static final Predicate<BlockState> ROTATED_DEEPSLATE = s ->
            s.isOf(Blocks.DEEPSLATE) && s.contains(PillarBlock.AXIS) && s.get(PillarBlock.AXIS) != Direction.Axis.Y;

    /** Blocks that don't generate naturally below y=0 (outside of very rare structures). */
    private static final Predicate<BlockState> PLAYER_BLOCK = s ->
            s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.FURNACE) || s.isOf(Blocks.BLAST_FURNACE)
                    || s.isOf(Blocks.SMOKER) || s.isOf(Blocks.ENCHANTING_TABLE) || s.isOf(Blocks.ANVIL)
                    || s.isOf(Blocks.HOPPER) || s.isOf(Blocks.OBSERVER) || s.isOf(Blocks.PISTON)
                    || s.isOf(Blocks.STICKY_PISTON) || s.isOf(Blocks.ENDER_CHEST) || s.isOf(Blocks.BEACON)
                    || s.isOf(Blocks.RESPAWN_ANCHOR)
                    || s.isIn(BlockTags.BEDS) || s.isIn(BlockTags.SHULKER_BOXES) || s.isOf(Blocks.NETHER_PORTAL);

    public ChunkLocator() {
        super("ChunkLocator", "Finds chunks with signs of player bases.", Category.VISUALS);
    }

    @Override
    protected void onEnable() {
        found.clear();
        lastScan.clear();
        queue.clear();
    }

    @Override
    public void onTick() {
        if (queue.isEmpty()) refillQueue();
        long now = System.currentTimeMillis();
        for (int i = 0; i < perTick.getInt() && !queue.isEmpty(); i++) {
            ChunkPos cp = queue.remove(queue.size() - 1);
            WorldChunk chunk = (WorldChunk) mc.world.getChunkManager().getChunk(cp.x, cp.z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            lastScan.put(cp.toLong(), now);
            String reason = scan(chunk);
            Long key = cp.toLong();
            if (reason == null) {
                found.remove(key);
            } else if (!found.containsKey(key)) {
                found.put(key, new Hit(cp, reason));
                if (notify.get()) {
                    CommandManager.print("§dChunk §f" + cp.getCenterX() + ", " + cp.getCenterZ()
                            + " §7(" + reason + ")");
                    mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.6f, 1.6f);
                }
            }
        }
    }

    private void refillQueue() {
        int r = mc.options.getClampedViewDistance();
        ChunkPos c = mc.player.getChunkPos();
        long now = System.currentTimeMillis();
        for (int x = c.x - r; x <= c.x + r; x++) {
            for (int z = c.z - r; z <= c.z + r; z++) {
                Long last = lastScan.get(ChunkPos.toLong(x, z));
                if (last == null || now - last > 10_000) queue.add(new ChunkPos(x, z));
            }
        }
        // Nearest chunks get scanned first (queue is consumed from the end).
        queue.sort((a, b) -> Double.compare(dist(b, c), dist(a, c)));
        // Forget chunks that are far away so memory doesn't grow forever.
        lastScan.keySet().removeIf(k -> {
            ChunkPos p = new ChunkPos(k);
            return Math.abs(p.x - c.x) > r + 4 || Math.abs(p.z - c.z) > r + 4;
        });
    }

    private static double dist(ChunkPos a, ChunkPos b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private String scan(WorldChunk chunk) {
        List<String> reasons = new ArrayList<>();

        int storage = 0, shulkerCount = 0, spawnerCount = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof ShulkerBoxBlockEntity) shulkerCount++;
            if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity
                    || be instanceof EnderChestBlockEntity || be instanceof HopperBlockEntity) storage++;
            if (be instanceof MobSpawnerBlockEntity) spawnerCount++;
        }
        if (storage >= minStorage.getInt()) reasons.add(storage + " storage");
        if (shulkers.get() && shulkerCount > 0) reasons.add(shulkerCount + " shulker" + (shulkerCount > 1 ? "s" : ""));
        if (spawners.get() && spawnerCount > 0) reasons.add("spawner");

        if (deepslate.get() || underground.get()) {
            ChunkSection[] sections = chunk.getSectionArray();
            int bottom = chunk.getBottomSectionCoord();
            boolean rotated = false, deepBlocks = false;
            for (int i = 0; i < sections.length; i++) {
                ChunkSection s = sections[i];
                if (s == null || s.isEmpty()) continue;
                int sectionY = (bottom + i) * 16;
                if (sectionY >= 16) break; // deepslate and our "deep" check only matter below y=16
                if (deepslate.get() && !rotated && s.hasAny(ROTATED_DEEPSLATE)) rotated = true;
                if (underground.get() && !deepBlocks && sectionY < 0 && s.hasAny(PLAYER_BLOCK)) deepBlocks = true;
            }
            if (rotated) reasons.add("rotated deepslate");
            if (deepBlocks) reasons.add("player blocks underground");
        }
        return reasons.isEmpty() ? null : String.join(", ", reasons);
    }

    @Override
    public void onRender(RenderContext ctx) {
        double y = followY.get() ? mc.player.getLerpedPos(ctx.tickDelta).y : renderY.get();
        int pulse = (int) (60 + 40 * Math.sin(System.currentTimeMillis() / 300.0));
        for (Hit h : found.values()) {
            double x1 = h.pos.getStartX(), z1 = h.pos.getStartZ();
            double x2 = x1 + 16, z2 = z1 + 16;
            int color = h.reason.contains("shulker") || h.reason.contains("storage") ? 0xFFFF5050 : 0xFFFFC040;
            ctx.flatQuad(x1, z1, x2, z2, y, ColorUtil.withAlpha(color, pulse));
            ctx.line(new Vec3d(x1, y, z1), new Vec3d(x2, y, z1), color, 2);
            ctx.line(new Vec3d(x2, y, z1), new Vec3d(x2, y, z2), color, 2);
            ctx.line(new Vec3d(x2, y, z2), new Vec3d(x1, y, z2), color, 2);
            ctx.line(new Vec3d(x1, y, z2), new Vec3d(x1, y, z1), color, 2);
        }
    }

    @Override
    public String getSuffix() { return String.valueOf(found.size()); }
}
