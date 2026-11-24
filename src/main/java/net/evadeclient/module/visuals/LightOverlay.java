package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.SpawnHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Marks the tops of blocks near you where monsters could spawn: no block light on the space above (or no more than
 * Spawn Light), a solid top, and room to stand. Red where they can spawn any time, yellow where only at night (open
 * sky), and optionally green where light already stops them. Depth-tested like the world, so a mark behind a wall is
 * hidden by it, and only within Range blocks. Uses the light levels the game already shows in F3. Toggle with F7.
 */
public class LightOverlay extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 16, 8, 24, 1)
            .describe("How far around you (in blocks) spots are marked."));
    private final ModeSetting style = add(new ModeSetting("Style", "Cross", "Cross", "Square", "Both")
            .describe("An X on each spot, a tinted square, or both."));
    private final NumberSetting spawnLight = add(new NumberSetting("Spawn Light", 0, 0, 7, 1)
            .describe("The highest block light monsters spawn at. 0 is right for the Overworld since 1.18."));
    private final ColorSetting dangerColor = add(new ColorSetting("Spawnable Color", 0xFFFF4D5E)
            .describe("Spots where monsters can spawn at any time (dark sky as well as no block light)."));
    private final ColorSetting nightColor = add(new ColorSetting("Night Only Color", 0xFFFFD21F)
            .describe("Spots under open sky: monsters spawn there only at night or in a storm."));
    private final BooleanSetting showSafe = add(new BooleanSetting("Show Safe", false)
            .describe("Also mark lit spots, where light already stops monsters, in Safe Color."));
    private final ColorSetting safeColor = add(new ColorSetting("Safe Color", 0xFF3BE37A)
            .describe("Colour of lit, safe spots with Show Safe on."));

    /** Most marks kept, so a huge open area can't slow the frame down. */
    private static final int MAX_MARKS = 6000;
    /** Rescan the area every this many ticks. */
    private static final int SCAN_TICKS = 10;

    /** Marks found by the last scan: x, y (the top face), z and colour, four ints each. */
    private int[] marks = new int[0];
    private int count;
    private int ticks;

    public LightOverlay() {
        super("Light Overlay", "Marks spots near you where monsters can spawn, by light level. Toggle with F7.",
                Category.VISUALS, GLFW.GLFW_KEY_F7);
    }

    @Override
    protected void onEnable() {
        ticks = SCAN_TICKS; // scan on the next tick
    }

    @Override
    protected void onDisable() {
        count = 0;
    }

    @Override
    public void onTick() {
        if (++ticks < SCAN_TICKS) return;
        ticks = 0;
        scan();
    }

    /** Finds every spawnable top face within Range of you. */
    public void scan() {
        count = 0;
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;
        int r = range.getInt(), maxLight = spawnLight.getInt();
        boolean safe = showSafe.get();
        BlockPos center = mc.player.getBlockPos();
        int bottom = world.getBottomY(), top = world.getTopYInclusive();
        int[] out = marks.length >= MAX_MARKS * 4 ? marks : new int[MAX_MARKS * 4];
        BlockPos.Mutable floor = new BlockPos.Mutable(), above = new BlockPos.Mutable();
        int danger = dangerColor.get(), night = nightColor.get(), ok = safeColor.get();
        int rr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rr) continue;
                for (int dy = -r; dy <= r / 2; dy++) {
                    int y = center.getY() + dy;
                    if (y < bottom || y + 1 > top) continue;
                    floor.set(center.getX() + dx, y, center.getZ() + dz);
                    above.set(floor.getX(), y + 1, floor.getZ());
                    if (!world.isChunkLoaded(floor.getX() >> 4, floor.getZ() >> 4)) continue;
                    BlockState ground = world.getBlockState(floor);
                    if (ground.isAir() || !ground.allowsSpawning(world, floor, EntityType.ZOMBIE)) continue;
                    BlockState space = world.getBlockState(above);
                    if (!SpawnHelper.isClearForSpawn(world, above, space, space.getFluidState(), EntityType.ZOMBIE)) continue;
                    int block = world.getLightLevel(LightType.BLOCK, above);
                    int color;
                    if (block > maxLight) {
                        if (!safe) continue;
                        color = ok;
                    } else {
                        color = world.getLightLevel(LightType.SKY, above) > 7 ? night : danger;
                    }
                    if (count >= MAX_MARKS) break;
                    int i = count++ * 4;
                    out[i] = floor.getX();
                    out[i + 1] = y + 1;
                    out[i + 2] = floor.getZ();
                    out[i + 3] = color;
                }
            }
        }
        marks = out;
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (count == 0) return;
        boolean cross = !style.is("Square"), square = !style.is("Cross");
        for (int k = 0; k < count; k++) {
            int i = k * 4;
            double x = marks[i], y = marks[i + 1] + 0.01, z = marks[i + 2];
            int c = marks[i + 3];
            if (square) ctx.boxFilledDepth(x + 0.05, y - 0.005, z + 0.05, x + 0.95, y, z + 0.95, (0x50 << 24) | (c & 0xFFFFFF));
            if (cross) {
                int lc = 0xE0000000 | (c & 0xFFFFFF);
                ctx.lineDepth(x + 0.15, y, z + 0.15, x + 0.85, y, z + 0.85, lc, 1.5f);
                ctx.lineDepth(x + 0.85, y, z + 0.15, x + 0.15, y, z + 0.85, lc, 1.5f);
            }
        }
        // Depth-tested layers aren't flushed by the world pass: draw them now.
        ctx.flush();
    }

    /** The colour the last scan marked the top of the block at (x, y - 1, z) with, or 0 (for the tests). */
    public int markColorAt(int x, int y, int z) {
        for (int k = 0; k < count; k++) {
            int i = k * 4;
            if (marks[i] == x && marks[i + 1] == y && marks[i + 2] == z) return marks[i + 3];
        }
        return 0;
    }

    /** How many spots the last scan marked (for the tests). */
    public int marksForTest() { return count; }
}
