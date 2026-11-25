package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Stops you misplacing blocks while you build: the first block you place after switching it on sets an anchor, and
 * from then on a placement outside the chosen layer, wall, line or column is simply not done (nothing is sent, so the
 * server never sees it). It only ever holds back your own clicks: it never places anything, never turns you, never
 * changes which way a block faces and never makes placing faster or reach further. Meant for a hold key: bind it in
 * the menu with Hold, and the lock lasts as long as you hold it.
 */
public class PlacementLock extends Module {
    private final ModeSetting mode = add(new ModeSetting("Lock To", "Layer", "Layer", "Wall", "Line", "Column", "Same Face")
            .describe("Layer: same height as the first block. Wall: the same upright plane. Line: one straight row. Column: straight up and down. Same Face: only against the side you clicked first."));
    private final BooleanSetting showGuide = add(new BooleanSetting("Show Guide", true)
            .describe("Outlines the first block and the allowed plane or line while the lock is on."));
    private final ColorSetting guideColor = add(new ColorSetting("Guide Color", 0xFF3DA5FF)
            .describe("Colour of the guide."));
    private final BooleanSetting tellMe = add(new BooleanSetting("Say When Blocked", true)
            .describe("Shows a short note above the hotbar when a placement is held back."));

    /** The first block placed in this lock, the face it was placed against, and the wall/line axis once known. */
    private BlockPos anchor;
    private Direction anchorFace;
    private Direction.Axis wallAxis, lineAxis;
    private int blocked;
    private long lastNote;

    public PlacementLock() {
        super("Placement Lock", "Hold a key to keep your block placements on one layer, wall, line or column.", Category.MODS);
        setHoldBind(true);
    }

    @Override
    protected void onEnable() { resetAnchor(); }

    @Override
    protected void onDisable() { resetAnchor(); }

    public void resetAnchor() {
        anchor = null;
        anchorFace = null;
        wallAxis = null;
        lineAxis = null;
    }

    /** The block a right click with this hit would place, or null when it wouldn't place a block. */
    public static BlockPos placementTarget(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (player == null || hit == null) return null;
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem)) return null;
        ItemPlacementContext ctx = new ItemPlacementContext(player, hand, stack, hit);
        return ctx.getBlockPos().toImmutable();
    }

    /**
     * Called by the interaction hook before your click is handled: whether this placement may go ahead. Returns
     * true when the module is off, the click wouldn't place a block, or the block is inside the lock.
     */
    public boolean allows(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!isEnabled()) return true;
        BlockPos target = placementTarget(player, hand, hit);
        if (target == null) return true;
        boolean ok = allowsTarget(target, hit.getSide(), player.getHorizontalFacing());
        if (!ok) {
            blocked++;
            long now = System.currentTimeMillis();
            if (tellMe.get() && now - lastNote > 800) {
                lastNote = now;
                player.sendMessage(Text.literal("Placement Lock: outside the " + mode.get().toLowerCase(java.util.Locale.ROOT)), true);
            }
        }
        return ok;
    }

    /** The lock rule itself, given where the block would go, the face clicked and the way you face. */
    public boolean allowsTarget(BlockPos target, Direction side, Direction facing) {
        if (anchor == null) return true;
        return switch (mode.get()) {
            case "Wall" -> {
                Direction.Axis axis = wallAxis != null ? wallAxis : facing.getAxis();
                yield axis == Direction.Axis.X ? target.getX() == anchor.getX() : target.getZ() == anchor.getZ();
            }
            case "Line" -> {
                int same = (target.getX() == anchor.getX() ? 1 : 0) + (target.getY() == anchor.getY() ? 1 : 0) + (target.getZ() == anchor.getZ() ? 1 : 0);
                if (lineAxis == null) yield same >= 2;
                yield switch (lineAxis) {
                    case X -> target.getY() == anchor.getY() && target.getZ() == anchor.getZ();
                    case Y -> target.getX() == anchor.getX() && target.getZ() == anchor.getZ();
                    case Z -> target.getX() == anchor.getX() && target.getY() == anchor.getY();
                };
            }
            case "Column" -> target.getX() == anchor.getX() && target.getZ() == anchor.getZ();
            case "Same Face" -> side == anchorFace;
            default -> target.getY() == anchor.getY(); // Layer
        };
    }

    /** Called after a placement went through: the first one sets the anchor, the second fixes a line's direction. */
    public void placed(BlockPos target, Direction side, Direction facing) {
        if (!isEnabled() || target == null) return;
        if (anchor == null) {
            anchor = target;
            anchorFace = side;
            // A wall runs across the way you face: facing north/south it keeps Z, facing east/west it keeps X.
            wallAxis = facing.getAxis() == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X;
            return;
        }
        if (mode.is("Line") && lineAxis == null && !target.equals(anchor)) {
            if (target.getX() != anchor.getX()) lineAxis = Direction.Axis.X;
            else if (target.getY() != anchor.getY()) lineAxis = Direction.Axis.Y;
            else lineAxis = Direction.Axis.Z;
        }
    }

    /** For the tests: the anchor (or null) and how many placements were held back. */
    public BlockPos anchorForTest() { return anchor; }
    public int blockedForTest() { return blocked; }

    @Override
    public String getSuffix() { return mode.get(); }

    @Override
    public void onRender(RenderContext ctx) {
        if (anchor == null || !showGuide.get()) return;
        int c = guideColor.get();
        int faint = (0x60 << 24) | (c & 0xFFFFFF);
        double x = anchor.getX(), y = anchor.getY(), z = anchor.getZ();
        ctx.boxOutline(x - 0.01, y - 0.01, z - 0.01, x + 1.01, y + 1.01, z + 1.01, c, 2f);
        int r = 6;
        switch (mode.get()) {
            case "Layer" -> {
                for (int i = -r; i <= r + 1; i++) {
                    ctx.line(x + i, y, z - r, x + i, y, z + r + 1, faint, 1f);
                    ctx.line(x - r, y, z + i, x + r + 1, y, z + i, faint, 1f);
                }
            }
            case "Wall" -> {
                boolean keepX = wallAxis == Direction.Axis.X;
                for (int i = -r; i <= r + 1; i++) {
                    if (keepX) {
                        ctx.line(x + 0.5, y + i, z - r, x + 0.5, y + i, z + r + 1, faint, 1f);
                        ctx.line(x + 0.5, y - r, z + i, x + 0.5, y + r + 1, z + i, faint, 1f);
                    } else {
                        ctx.line(x - r, y + i, z + 0.5, x + r + 1, y + i, z + 0.5, faint, 1f);
                        ctx.line(x + i, y - r, z + 0.5, x + i, y + r + 1, z + 0.5, faint, 1f);
                    }
                }
            }
            case "Column" -> ctx.line(x + 0.5, y - 16, z + 0.5, x + 0.5, y + 17, z + 0.5, c, 2f);
            case "Line" -> {
                if (lineAxis == null || lineAxis == Direction.Axis.X) ctx.line(x - 16, y + 0.5, z + 0.5, x + 17, y + 0.5, z + 0.5, faint, 2f);
                if (lineAxis == null || lineAxis == Direction.Axis.Y) ctx.line(x + 0.5, y - 16, z + 0.5, x + 0.5, y + 17, z + 0.5, faint, 2f);
                if (lineAxis == null || lineAxis == Direction.Axis.Z) ctx.line(x + 0.5, y + 0.5, z - 16, x + 0.5, y + 0.5, z + 17, faint, 2f);
            }
            default -> { }
        }
    }
}
