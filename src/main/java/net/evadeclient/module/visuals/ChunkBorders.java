package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.ChunkPos;

/**
 * Draws chunk boundaries around you: a grid on your current chunk and corner posts on the ones next to it. The grid
 * is fixed to the world (rings on even heights, brighter where 16-block sections meet) and stays inside the build
 * height.
 */
public class ChunkBorders extends Module {
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E)
            .describe("Colour of the chunk border lines."));
    public final NumberSetting height = add(new NumberSetting("Height", 32, 8, 96, 4)
            .describe("How many blocks above and below you the grid reaches."));
    private final BooleanSetting sectionLines = add(new BooleanSetting("Section Lines", true)
            .describe("Brighter rings every 16 blocks of height, where the world's sections meet."));
    private final BooleanSetting throughWalls = add(new BooleanSetting("Through Walls", true)
            .describe("Draw the grid over blocks. Off: blocks in front of a line hide it."));
    private int lines;

    public ChunkBorders() {
        super("Chunk Borders", "Shows chunk boundaries on a single key.", Category.VISUALS);
    }

    /** How many lines the last frame drew (for tests). */
    public int lines() { return lines; }

    @Override
    public void onRender(RenderContext ctx) {
        lines = 0;
        if (mc.player == null || mc.world == null) return;
        ChunkPos chunk = mc.player.getChunkPos();
        int cx = chunk.getStartX(), cz = chunk.getStartZ();
        double py = mc.player.getY();
        int h = height.getInt();
        int bottom = mc.world.getBottomY(), top = mc.world.getTopYInclusive() + 1;
        // Snapped to even heights so the rings don't move as you walk up and down; clamped to the build height.
        double y0 = Math.max(bottom, Math.floor((py - h) / 2.0) * 2);
        double y1 = Math.min(top, Math.ceil((py + h) / 2.0) * 2);
        if (y1 <= y0) return;

        boolean depth = !throughWalls.get();
        int main = color.get();
        int rgb = main & 0xFFFFFF;
        int faint = (0x60 << 24) | rgb, grid = (0xA0 << 24) | rgb;
        boolean sections = sectionLines.get();

        // corner posts of the 3x3 chunks around you
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
            double x = cx + dx * 16, z = cz + dz * 16;
            boolean inner = (dx == 0 || dx == 1) && (dz == 0 || dz == 1);
            line(ctx, depth, x, y0, z, x, y1, z, inner ? main : faint, inner ? 2.5f : 1.5f);
        }
        // current chunk: vertical lines every 2 blocks along each edge
        for (int i = 2; i < 16; i += 2) {
            line(ctx, depth, cx + i, y0, cz, cx + i, y1, cz, grid, 1f);
            line(ctx, depth, cx + i, y0, cz + 16, cx + i, y1, cz + 16, grid, 1f);
            line(ctx, depth, cx, y0, cz + i, cx, y1, cz + i, grid, 1f);
            line(ctx, depth, cx + 16, y0, cz + i, cx + 16, y1, cz + i, grid, 1f);
        }
        // current chunk: horizontal rings every 2 blocks of height, stronger on section boundaries
        for (double y = y0; y <= y1; y += 2) {
            boolean section = sections && (((int) y) & 15) == 0;
            int c = section ? main : grid;
            float w = section ? 2f : 1f;
            line(ctx, depth, cx, y, cz, cx + 16, y, cz, c, w);
            line(ctx, depth, cx + 16, y, cz, cx + 16, y, cz + 16, c, w);
            line(ctx, depth, cx + 16, y, cz + 16, cx, y, cz + 16, c, w);
            line(ctx, depth, cx, y, cz + 16, cx, y, cz, c, w);
        }
        // The world-render pass only flushes the overlay layers; the depth-tested lines are drawn here.
        if (depth) ctx.flush();
    }

    private void line(RenderContext ctx, boolean depth, double ax, double ay, double az, double bx, double by, double bz,
                      int color, float width) {
        if (depth) ctx.lineDepth(ax, ay, az, bx, by, bz, color, width);
        else ctx.line(ax, ay, az, bx, by, bz, color, width);
        lines++;
    }
}
