package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Custom colour and thickness for the outline of the block you're looking at, with optional fill. */
public class BlockOutline extends Module {
    private final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E));
    private final NumberSetting width = add(new NumberSetting("Thickness", 3, 1, 8, 0.5));
    private final BooleanSetting fill = add(new BooleanSetting("Fill", true));
    private final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", 0.15, 0.05, 0.6, 0.05));

    public BlockOutline() { super("Block Outline", "Custom block selection outline.", Category.MODS); }

    /** @return false to cancel the vanilla outline. */
    public boolean draw(WorldRenderContext ctx, OutlineRenderState state) {
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        BlockPos pos = state.pos();
        var consumers = ctx.consumers();
        VertexRendering.drawOutline(ctx.matrices(), consumers.getBuffer(RenderLayers.lines()), state.shape(),
                pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z, color.get(), width.getFloat());
        if (fill.get() && !state.shape().isEmpty()) {
            Box b = state.shape().getBoundingBox().offset(pos).expand(0.002);
            new net.evadeclient.render.RenderContext(ctx.matrices(), consumers, 0, 0)
                    .boxFilled(b, Theme.alpha(color.get() | 0xFF000000, fillAlpha.getFloat()));
        }
        return false;
    }
}
