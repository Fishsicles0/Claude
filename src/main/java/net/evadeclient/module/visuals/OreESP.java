package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outlines ores through walls (the X-ray idea, without hiding the world). Scans loaded chunks a few at a time and
 * skips sections whose palette has no ores, so it stays light.
 */
public class OreESP extends Module {
    private final BooleanSetting diamond = add(new BooleanSetting("Diamond", true));
    private final BooleanSetting debris = add(new BooleanSetting("Ancient Debris", true));
    private final BooleanSetting emerald = add(new BooleanSetting("Emerald", true));
    private final BooleanSetting gold = add(new BooleanSetting("Gold", true));
    private final BooleanSetting iron = add(new BooleanSetting("Iron", false));
    private final BooleanSetting redstone = add(new BooleanSetting("Redstone", false));
    private final BooleanSetting lapis = add(new BooleanSetting("Lapis", false));
    private final BooleanSetting coal = add(new BooleanSetting("Coal", false));
    private final BooleanSetting copper = add(new BooleanSetting("Copper", false));
    private final NumberSetting radius = add(new NumberSetting("Chunk Radius", 4, 1, 12, 1));

    private record Ore(BlockPos pos, int color) {}

    private final Map<Long, List<Ore>> found = new HashMap<>();
    private final List<ChunkPos> queue = new ArrayList<>();

    public OreESP() { super("OreESP", "Shows ores through walls.", Category.VISUALS); }

    @Override
    protected void onEnable() { found.clear(); queue.clear(); }

    @Override
    public void onTick() {
        ChunkPos c = mc.player.getChunkPos();
        int r = radius.getInt();
        if (queue.isEmpty()) {
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) queue.add(new ChunkPos(c.x + x, c.z + z));
            found.keySet().removeIf(k -> { ChunkPos p = new ChunkPos(k); return Math.abs(p.x - c.x) > r || Math.abs(p.z - c.z) > r; });
        }
        for (int i = 0; i < 2 && !queue.isEmpty(); i++) scan(queue.remove(queue.size() - 1));
    }

    private void scan(ChunkPos cp) {
        WorldChunk chunk = (WorldChunk) mc.world.getChunkManager().getChunk(cp.x, cp.z, ChunkStatus.FULL, false);
        if (chunk == null) return;
        List<Ore> list = new ArrayList<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> color(st) != 0)) continue;
            int baseY = (bottom + i) * 16;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int col = color(s.getBlockState(x, y, z));
                if (col != 0) list.add(new Ore(new BlockPos(cp.getStartX() + x, baseY + y, cp.getStartZ() + z), col));
            }
        }
        found.put(cp.toLong(), list);
    }

    private int color(BlockState s) {
        Block b = s.getBlock();
        if (diamond.get() && (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE)) return 0xFF29D3F0;
        if (debris.get() && b == Blocks.ANCIENT_DEBRIS) return 0xFFA0703C;
        if (emerald.get() && (b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE)) return 0xFF3BE37A;
        if (gold.get() && (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE || b == Blocks.NETHER_GOLD_ORE)) return 0xFFFFD21F;
        if (iron.get() && (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE)) return 0xFFD8AF93;
        if (redstone.get() && (b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE)) return 0xFFFF2020;
        if (lapis.get() && (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE)) return 0xFF3D5BFF;
        if (coal.get() && (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE)) return 0xFF505050;
        if (copper.get() && (b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE)) return 0xFFE07A4A;
        return 0;
    }

    @Override
    public void onRender(RenderContext ctx) {
        for (List<Ore> list : found.values()) {
            for (Ore o : list) {
                if (o.pos.getSquaredDistance(mc.player.getEntityPos()) > 64 * 64) continue;
                Box b = new Box(o.pos);
                ctx.boxFilled(b, ColorUtil.withAlpha(o.color, 35));
                ctx.boxOutline(b, o.color, 1.5f);
            }
        }
    }

    public int count() { return found.values().stream().mapToInt(List::size).sum(); }

    @Override
    public String getSuffix() { return String.valueOf(count()); }
}
