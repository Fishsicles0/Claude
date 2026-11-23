package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

/** Custom colour and thickness for the outline of the block you're looking at, with optional fill. */
public class BlockOutline extends Module {
    /** How far the fill sits outside the block's faces, so it is drawn in front of them. */
    private static final double FILL_INSET = 0.002;

    private final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E));
    private final NumberSetting width = add(new NumberSetting("Thickness", 3, 1, 8, 0.5));
    private final BooleanSetting fill = add(new BooleanSetting("Fill", true));
    private final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", 0.15, 0.05, 0.6, 0.05));

    public BlockOutline() { super("Block Outline", "Custom block selection outline.", Category.MODS); }

    /**
     * Draws the outline (and fill) in place of vanilla's, from Fabric's before-block-outline hook.
     *
     * @return false to cancel the vanilla outline.
     */
    public boolean draw(WorldRenderContext ctx, OutlineRenderState state) {
        VoxelShape shape = state.shape();
        if (shape.isEmpty()) return false;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        BlockPos pos = state.pos();
        VertexConsumerProvider consumers = ctx.consumers();
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();

        // The fill goes first so the outline is drawn over it. It follows the real shape (each box of a stair,
        // fence or slab), and is depth-tested: only the faces you can actually see are tinted.
        if (fill.get()) {
            RenderContext rc = new RenderContext(ctx.matrices(), consumers, 0, 0);
            int c = Theme.alpha(color.get() | 0xFF000000, fillAlpha.getFloat());
            shape.forEachBox((x1, y1, z1, x2, y2, z2) -> rc.boxFilledDepth(
                    px + x1 - FILL_INSET, py + y1 - FILL_INSET, pz + z1 - FILL_INSET,
                    px + x2 + FILL_INSET, py + y2 + FILL_INSET, pz + z2 + FILL_INSET, c));
            rc.flush();
        }

        VertexRendering.drawOutline(ctx.matrices(), consumers.getBuffer(RenderLayers.lines()), shape,
                px - cam.x, py - cam.y, pz - cam.z, color.get(), width.getFloat());
        // Vanilla draws its outline straight away (the hook cancels that part of the method), so do the same.
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.drawCurrentLayer();
        return false;
    }
}
