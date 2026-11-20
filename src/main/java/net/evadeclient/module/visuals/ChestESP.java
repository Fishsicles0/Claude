package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights storage through walls. Double chests are drawn as one box, shulkers use their own colour,
 * and boxes fade with distance.
 */
public class ChestESP extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Both", "Outline", "Fill", "Both"));
    private final NumberSetting range = add(new NumberSetting("Range", 128, 16, 512, 16));
    private final BooleanSetting tracers = add(new BooleanSetting("Tracers", false));
    private final BooleanSetting chests = add(new BooleanSetting("Chests", true));
    private final BooleanSetting enderChests = add(new BooleanSetting("Ender Chests", true));
    private final BooleanSetting shulkers = add(new BooleanSetting("Shulkers", true));
    private final BooleanSetting barrels = add(new BooleanSetting("Barrels", true));
    private final BooleanSetting hoppers = add(new BooleanSetting("Hoppers etc", false));
    private final BooleanSetting spawners = add(new BooleanSetting("Spawners", true));
    private final NumberSetting width = add(new NumberSetting("Line Width", 2, 1, 5, 0.5));

    private record Target(Box box, int color) {}

    private final List<Target> cache = new ArrayList<>();
    private int refresh;

    public ChestESP() {
        super("ChestESP", "Shows chests and other storage through walls.", Category.VISUALS);
    }

    @Override
    public void onTick() {
        if (refresh-- > 0) return;
        refresh = 10; // rescan twice a second
        cache.clear();
        int r = Math.min(mc.options.getClampedViewDistance(), (int) Math.ceil(range.get() / 16));
        ChunkPos center = mc.player.getChunkPos();
        double maxSq = range.get() * range.get();
        for (int cx = center.x - r; cx <= center.x + r; cx++) {
            for (int cz = center.z - r; cz <= center.z + r; cz++) {
                WorldChunk chunk = (WorldChunk) mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be.getPos().getSquaredDistance(mc.player.getEntityPos()) > maxSq) continue;
                    int color = colorFor(be);
                    if (color == 0) continue;
                    Box box = boxFor(be);
                    if (box != null) cache.add(new Target(box, color));
                }
            }
        }
    }

    @Override
    public void onRender(RenderContext ctx) {
        boolean fill = !mode.is("Outline"), outline = !mode.is("Fill");
        float w = width.getFloat();
        double r = range.get();
        for (Target t : cache) {
            double dist = Math.sqrt(t.box.getCenter().squaredDistanceTo(ctx.cameraPos()));
            // Fade far boxes a little so nearby ones stand out.
            int alpha = (int) (255 * (1 - 0.6 * Math.min(1, dist / r)));
            if (fill) ctx.boxFilled(t.box, ColorUtil.withAlpha(t.color, alpha / 5));
            if (outline) ctx.boxOutline(t.box, ColorUtil.withAlpha(t.color, alpha), w);
            if (tracers.get()) ctx.line(ctx.crosshairPoint(), t.box.getCenter(), ColorUtil.withAlpha(t.color, alpha), 1.5f);
        }
    }

    private int colorFor(BlockEntity be) {
        if (be instanceof TrappedChestBlockEntity) return chests.get() ? 0xFFFF4040 : 0;
        if (be instanceof ChestBlockEntity) return chests.get() ? 0xFFFFA628 : 0;
        if (be instanceof EnderChestBlockEntity) return enderChests.get() ? 0xFFB040FF : 0;
        if (be instanceof ShulkerBoxBlockEntity sb) {
            if (!shulkers.get()) return 0;
            DyeColor dye = sb.getColor();
            return dye == null ? 0xFFC77DFF : 0xFF000000 | dye.getEntityColor();
        }
        if (be instanceof BarrelBlockEntity) return barrels.get() ? 0xFFA0703C : 0;
        if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity) return hoppers.get() ? 0xFF9A9A9A : 0;
        if (be instanceof MobSpawnerBlockEntity) return spawners.get() ? 0xFF40E0FF : 0;
        return 0;
    }

    private Box boxFor(BlockEntity be) {
        BlockPos pos = be.getPos();
        BlockState state = be.getCachedState();
        if (be instanceof ChestBlockEntity && state.contains(ChestBlock.CHEST_TYPE)) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            Box single = new Box(pos.getX() + 1 / 16.0, pos.getY(), pos.getZ() + 1 / 16.0,
                    pos.getX() + 15 / 16.0, pos.getY() + 14 / 16.0, pos.getZ() + 15 / 16.0);
            if (type == ChestType.SINGLE) return single;
            if (type == ChestType.LEFT) return null; // the RIGHT half draws the merged box
            Direction toOther = ChestBlock.getFacing(state);
            BlockPos other = pos.offset(toOther);
            Box otherBox = new Box(other.getX() + 1 / 16.0, other.getY(), other.getZ() + 1 / 16.0,
                    other.getX() + 15 / 16.0, other.getY() + 14 / 16.0, other.getZ() + 15 / 16.0);
            return single.union(otherBox);
        }
        if (be instanceof EnderChestBlockEntity) {
            return new Box(pos.getX() + 1 / 16.0, pos.getY(), pos.getZ() + 1 / 16.0,
                    pos.getX() + 15 / 16.0, pos.getY() + 14 / 16.0, pos.getZ() + 15 / 16.0);
        }
        return new Box(pos);
    }

    @Override
    public String getSuffix() { return String.valueOf(cache.size()); }
}
