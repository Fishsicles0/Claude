package net.evadeclient.module.mods;

import net.evadeclient.mixin.InteractionManagerAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Custom colour and thickness for the outline of the block you're looking at, with optional fill. Purely cosmetic:
 * it draws over the same block vanilla would outline, nothing more.
 */
public class BlockOutline extends Module {
    /** How far the fill sits outside the block's faces, so it is drawn in front of them. */
    private static final double FILL_INSET = 0.002;
    /** A pause this long between outline frames means the outline went away (looked at air, menu, ...). */
    private static final long GAP_NANOS = 150_000_000L;

    private final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E)
            .describe("Colour of the outline (and of the fill while Sync Fill With Outline is on)."));
    private final ModeSetting colorMode = add(new ModeSetting("Color Mode", "Static", "Static", "Chroma", "Pulse", "Break Progress")
            .describe("Static uses Color, Chroma cycles the rainbow, Pulse breathes in and out, Break Progress fades from start to end colour as you mine."));
    private final NumberSetting chromaSpeed = add(new NumberSetting("Chroma Speed", 1.0, 0.2, 5.0, 0.1)
            .describe("How fast Chroma cycles: 1 is one full cycle every 4 seconds."));
    private final NumberSetting pulseSpeed = add(new NumberSetting("Pulse Speed", 1.0, 0.2, 4.0, 0.1)
            .describe("Pulses per second in Pulse mode."));
    private final ColorSetting breakStartColor = add(new ColorSetting("Break Start Color", 0xFFE11D2E)
            .describe("Break Progress colour when you start mining a block."));
    private final ColorSetting breakEndColor = add(new ColorSetting("Break End Color", 0xFF3BE37A)
            .describe("Break Progress colour just before the block breaks."));
    private final NumberSetting width = add(new NumberSetting("Thickness", 3, 1, 8, 0.5)
            .describe("Width of the outline."));
    private final ModeSetting outlineShape = add(new ModeSetting("Outline Shape", "Exact", "Exact", "Bounding Box", "Full Block")
            .describe("Exact follows the block's shape (stairs, fences), Bounding Box draws one box around it, Full Block always a whole cube."));
    private final BooleanSetting fill = add(new BooleanSetting("Fill", true)
            .describe("Tint the faces of the block too."));
    private final ColorSetting fillColor = add(new ColorSetting("Fill Color", 0xFFE11D2E)
            .describe("Colour of the fill when Sync Fill With Outline is off."));
    private final BooleanSetting syncFill = add(new BooleanSetting("Sync Fill With Outline", true)
            .describe("Fill with the outline's colour (including Chroma and Break Progress) instead of Fill Color."));
    private final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", 0.15, 0.05, 0.6, 0.05)
            .describe("How strong the fill tint is."));
    private final ModeSetting fillShape = add(new ModeSetting("Fill Shape", "Exact", "Exact", "Bounding Box")
            .describe("Exact tints each part of the block's shape, Bounding Box fills the outline's box."));
    private final ModeSetting fillAnimation = add(new ModeSetting("Fill Animation", "None", "None", "Grow From Center", "Rise From Bottom")
            .describe("While you mine a block, its fill grows with the mining progress."));
    private final BooleanSetting smooth = add(new BooleanSetting("Smooth Movement", false)
            .describe("Glide the outline from block to block instead of jumping."));
    private final NumberSetting moveSpeed = add(new NumberSetting("Movement Speed", 12, 2, 30, 1)
            .describe("How quickly Smooth Movement catches up with the block you look at."));
    private final BooleanSetting fadeWithDistance = add(new BooleanSetting("Fade With Distance", false)
            .describe("Fade the outline and fill as the block gets further away, down to 40% at the edge of your reach."));
    private final BooleanSetting hideInThirdPerson = add(new BooleanSetting("Hide In Third Person", false)
            .describe("Use the vanilla outline while in third person."));

    /** The outline box Smooth Movement shows, in world coordinates (null: jump straight to the target). */
    private Box animBox;
    private ClientWorld animWorld;
    private long lastDraw;

    public BlockOutline() { super("Block Outline", "Custom block selection outline.", Category.MODS); }

    @Override
    protected void onDisable() { resetAnimation(); }

    private void resetAnimation() {
        animBox = null;
        animWorld = null;
        lastDraw = 0;
    }

    @Override
    public void onTick() {
        // Looking away from blocks (or a new world) drops the glide, so the next block starts where it is.
        boolean targeting = mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
        if (!targeting || mc.world != animWorld) animBox = null;
    }

    /**
     * Draws the outline (and fill) in place of vanilla's, from Fabric's before-block-outline hook.
     *
     * @return false to cancel the vanilla outline, true to let it draw.
     */
    public boolean draw(WorldRenderContext ctx, OutlineRenderState state) {
        if (hideInThirdPerson.get() && !mc.options.getPerspective().isFirstPerson()) {
            resetAnimation();
            return true;
        }
        VoxelShape exact = state.shape();
        if (exact.isEmpty()) return false;
        BlockPos pos = state.pos();
        float tickDelta = mc.getRenderTickCounter().getTickProgress(true);

        VoxelShape outline = switch (outlineShape.get()) {
            case "Bounding Box" -> VoxelShapes.cuboid(exact.getBoundingBox());
            case "Full Block" -> VoxelShapes.fullCube();
            default -> exact;
        };
        Box target = outline.getBoundingBox();
        Box shown = smoothBox(target.offset(pos));
        Mapping map = new Mapping(target, shown);

        // Colours: the mode picks the base, Pulse and Fade With Distance scale the alpha of outline and fill alike.
        float progress = breakProgress(pos, tickDelta);
        int base = color.get();
        long ms = Util.getMeasuringTimeMs();
        if (colorMode.is("Chroma")) {
            float hue = (float) ((ms * chromaSpeed.get()) % 4000.0 / 4000.0);
            base = (base & 0xFF000000) | (java.awt.Color.HSBtoRGB(hue, 0.9f, 1f) & 0xFFFFFF);
        } else if (colorMode.is("Break Progress") && progress >= 0) {
            base = Theme.lerpColor(breakStartColor.get(), breakEndColor.get(), progress);
        }
        float alphaMul = 1f;
        if (colorMode.is("Pulse")) {
            double wave = 0.5 + 0.5 * Math.sin(ms / 1000.0 * pulseSpeed.get() * Math.PI * 2);
            alphaMul *= (float) (0.4 + 0.6 * wave);
        }
        if (fadeWithDistance.get()) alphaMul *= distanceFade(pos, tickDelta);
        int lineColor = Theme.alpha(base, alphaMul);

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        VertexConsumerProvider consumers = ctx.consumers();

        // The fill goes first so the outline is drawn over it. It is depth-tested: only the faces you can actually
        // see are tinted.
        if (fill.get()) {
            int fillBase = (syncFill.get() ? base : fillColor.get()) | 0xFF000000;
            int c = Theme.alpha(fillBase, fillAlpha.getFloat() * alphaMul);
            VoxelShape fillShapeV = fillShape.is("Bounding Box") ? VoxelShapes.cuboid(target) : exact;
            boolean animate = progress >= 0 && !fillAnimation.is("None");
            if (c >>> 24 > 0 && !(animate && progress <= 0)) drawFill(ctx, consumers, fillShapeV, map, animate ? progress : -1, c);
        }

        drawEdges(ctx.matrices(), consumers.getBuffer(RenderLayers.lines()), outline, map, cam, lineColor, width.getFloat());
        // Vanilla draws its outline straight away (the hook cancels that part of the method), so do the same.
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.drawCurrentLayer();
        return false;
    }

    // ------------------------------------------------------------------ smooth movement

    /** Eases the shown box toward {@code target} (world coordinates) with Smooth Movement, else returns the target. */
    private Box smoothBox(Box target) {
        long now = System.nanoTime();
        boolean gap = lastDraw == 0 || now - lastDraw > GAP_NANOS;
        float dt = gap ? 0f : Math.min(0.1f, (now - lastDraw) / 1_000_000_000f);
        lastDraw = now;
        if (!smooth.get() || animBox == null || gap || mc.world != animWorld) {
            animWorld = mc.world;
            animBox = target;
            return target;
        }
        double k = 1 - Math.exp(-moveSpeed.get() * dt);
        Box b = new Box(
                MathHelper.lerp(k, animBox.minX, target.minX), MathHelper.lerp(k, animBox.minY, target.minY),
                MathHelper.lerp(k, animBox.minZ, target.minZ), MathHelper.lerp(k, animBox.maxX, target.maxX),
                MathHelper.lerp(k, animBox.maxY, target.maxY), MathHelper.lerp(k, animBox.maxZ, target.maxZ));
        // Close enough: land exactly, so the exact shape is drawn without any scaling.
        if (near(b, target)) b = target;
        animBox = b;
        return b;
    }

    private static boolean near(Box a, Box b) {
        double e = 1e-3;
        return Math.abs(a.minX - b.minX) < e && Math.abs(a.minY - b.minY) < e && Math.abs(a.minZ - b.minZ) < e
                && Math.abs(a.maxX - b.maxX) < e && Math.abs(a.maxY - b.maxY) < e && Math.abs(a.maxZ - b.maxZ) < e;
    }

    /**
     * Maps the block's local shape coordinates (inside {@code from}) onto the box being shown in the world
     * ({@code to}): a plain offset when they match, a move and stretch while Smooth Movement is gliding.
     */
    private record Mapping(double fx, double fy, double fz, double sx, double sy, double sz, Box to) {
        Mapping(Box from, Box to) {
            this(from.minX, from.minY, from.minZ, scale(to.getLengthX(), from.getLengthX()),
                    scale(to.getLengthY(), from.getLengthY()), scale(to.getLengthZ(), from.getLengthZ()), to);
        }

        private static double scale(double to, double from) { return from < 1e-6 ? 1 : to / from; }

        double x(double lx) { return to.minX + (lx - fx) * sx; }
        double y(double ly) { return to.minY + (ly - fy) * sy; }
        double z(double lz) { return to.minZ + (lz - fz) * sz; }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Fills each box of {@code shape}. With {@code progress} at 0..1 the fill shows the mining progress: grown from
     * the centre, or risen from the bottom, relative to the whole shape's bounds.
     */
    private void drawFill(WorldRenderContext ctx, VertexConsumerProvider consumers, VoxelShape shape, Mapping map,
                          float progress, int c) {
        RenderContext rc = new RenderContext(ctx.matrices(), consumers, 0, 0);
        Box bounds = shape.getBoundingBox();
        boolean grow = progress >= 0 && fillAnimation.is("Grow From Center");
        boolean rise = progress >= 0 && fillAnimation.is("Rise From Bottom");
        Vec3d mid = bounds.getCenter();
        double cut = bounds.minY + progress * bounds.getLengthY();
        shape.forEachBox((x1, y1, z1, x2, y2, z2) -> {
            if (grow) {
                x1 = mid.x + (x1 - mid.x) * progress; x2 = mid.x + (x2 - mid.x) * progress;
                y1 = mid.y + (y1 - mid.y) * progress; y2 = mid.y + (y2 - mid.y) * progress;
                z1 = mid.z + (z1 - mid.z) * progress; z2 = mid.z + (z2 - mid.z) * progress;
            } else if (rise) {
                // Like a level filling up: parts above it wait their turn instead of being squashed.
                if (y1 >= cut) return;
                y2 = Math.min(y2, cut);
            }
            rc.boxFilledDepth(
                    map.x(x1) - FILL_INSET, map.y(y1) - FILL_INSET, map.z(z1) - FILL_INSET,
                    map.x(x2) + FILL_INSET, map.y(y2) + FILL_INSET, map.z(z2) + FILL_INSET, c);
        });
        rc.flush();
    }

    /** The shape's edges as lines, like vanilla's outline but through {@code map}. */
    private static void drawEdges(MatrixStack matrices, VertexConsumer lines, VoxelShape shape, Mapping map, Vec3d cam,
                                  int color, float lineWidth) {
        MatrixStack.Entry entry = matrices.peek();
        shape.forEachEdge((x1, y1, z1, x2, y2, z2) -> {
            // Subtract the camera in double first: far from the origin a float can't hold the world coordinate.
            float ax = (float) (map.x(x1) - cam.x), ay = (float) (map.y(y1) - cam.y), az = (float) (map.z(z1) - cam.z);
            float bx = (float) (map.x(x2) - cam.x), by = (float) (map.y(y2) - cam.y), bz = (float) (map.z(z2) - cam.z);
            float nx = bx - ax, ny = by - ay, nz = bz - az;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) return;
            nx /= len; ny /= len; nz /= len;
            lines.vertex(entry, ax, ay, az).color(color).normal(entry, nx, ny, nz).lineWidth(lineWidth);
            lines.vertex(entry, bx, by, bz).color(color).normal(entry, nx, ny, nz).lineWidth(lineWidth);
        });
    }

    // ------------------------------------------------------------------ inputs

    /**
     * How far you have mined the block at {@code pos}, 0 to 1 (smoothed between ticks), or -1 when you aren't
     * mining it. Only reads the game's own progress.
     */
    private float breakProgress(BlockPos pos, float tickDelta) {
        var im = mc.interactionManager;
        if (im == null || mc.player == null || mc.world == null || !im.isBreakingBlock()) return -1;
        if (im instanceof InteractionManagerAccessor acc) {
            if (!pos.equals(acc.evade$getBreakingPos())) return -1;
            BlockState bs = mc.world.getBlockState(pos);
            // Progress moves once a tick; add this tick's share so the fill grows every frame.
            float step = bs.isAir() ? 0 : bs.calcBlockBreakingDelta(mc.player, mc.world, pos);
            return MathHelper.clamp(acc.evade$getBreakingProgress() + step * tickDelta, 0f, 1f);
        }
        // Without the accessor: vanilla keeps mining the block under the crosshair, in tenths.
        int stage = im.getBlockBreakingProgress();
        return stage < 0 ? 0f : MathHelper.clamp(stage / 10f, 0f, 1f);
    }

    /** 1 up close, easing to 0.4 at the edge of your reach. */
    private float distanceFade(BlockPos pos, float tickDelta) {
        if (mc.player == null) return 1f;
        Vec3d eye = mc.player.getCameraPosVec(tickDelta);
        Vec3d point = mc.crosshairTarget instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)
                ? hit.getPos() : Vec3d.ofCenter(pos);
        double reach = Math.max(1.5, mc.player.getBlockInteractionRange());
        double f = MathHelper.clamp((eye.distanceTo(point) - 1) / (reach - 1), 0, 1);
        return (float) MathHelper.lerp(f, 1.0, 0.4);
    }
}
