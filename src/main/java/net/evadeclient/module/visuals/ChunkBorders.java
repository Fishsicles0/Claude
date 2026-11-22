package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

/** Draws chunk boundaries around you: a grid on your current chunk and corner posts on the ones next to it. */
public class ChunkBorders extends Module {
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E));
    public final NumberSetting height = add(new NumberSetting("Height", 32, 8, 96, 4));
    private int lines;

    public ChunkBorders() {
        super("Chunk Borders", "Shows chunk boundaries on a single key.", Category.VISUALS);
    }

    public int lines() { return lines; }

    @Override
    public void onRender(RenderContext ctx) {
        lines = 0;
        if (mc.player == null) return;
        int cx = mc.player.getChunkPos().getStartX(), cz = mc.player.getChunkPos().getStartZ();
        double py = Math.floor(mc.player.getY());
        int h = height.getInt();
        double y0 = py - h, y1 = py + h;
        int main = color.get(), faint = (0x60 << 24) | (main & 0xFFFFFF), grid = (0xA0 << 24) | (main & 0xFFFFFF);

        // corner posts of the 3x3 chunks around you
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
            double x = cx + dx * 16, z = cz + dz * 16;
            boolean inner = (dx == 0 || dx == 1) && (dz == 0 || dz == 1);
            ctx.line(new Vec3d(x, y0, z), new Vec3d(x, y1, z), inner ? main : faint, inner ? 2.5f : 1.5f);
            lines++;
        }
        // current chunk: vertical lines every 2 blocks along each edge
        for (int i = 2; i < 16; i += 2) {
            ctx.line(new Vec3d(cx + i, y0, cz), new Vec3d(cx + i, y1, cz), grid, 1f);
            ctx.line(new Vec3d(cx + i, y0, cz + 16), new Vec3d(cx + i, y1, cz + 16), grid, 1f);
            ctx.line(new Vec3d(cx, y0, cz + i), new Vec3d(cx, y1, cz + i), grid, 1f);
            ctx.line(new Vec3d(cx + 16, y0, cz + i), new Vec3d(cx + 16, y1, cz + i), grid, 1f);
            lines += 4;
        }
        // current chunk: horizontal rings every 2 blocks of height
        for (double y = y0; y <= y1; y += 2) {
            ctx.line(new Vec3d(cx, y, cz), new Vec3d(cx + 16, y, cz), grid, 1f);
            ctx.line(new Vec3d(cx + 16, y, cz), new Vec3d(cx + 16, y, cz + 16), grid, 1f);
            ctx.line(new Vec3d(cx + 16, y, cz + 16), new Vec3d(cx, y, cz + 16), grid, 1f);
            ctx.line(new Vec3d(cx, y, cz + 16), new Vec3d(cx, y, cz), grid, 1f);
            lines += 4;
        }
    }
}
