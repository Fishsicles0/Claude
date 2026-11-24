package net.evadeclient.module.visuals.tooltip;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.render.MapRenderState;
import net.minecraft.item.map.MapState;

/**
 * A filled map's picture in its tooltip, drawn the way the cartography table draws it (the map texture and its
 * always-shown markers), scaled to {@link MapPreviewData#size()}. Nothing is drawn once the map data is gone (the
 * world changed), so the tooltip never shows a stale or wrong picture.
 */
public final class MapPreviewComponent implements TooltipComponent {
    private final MapPreviewData data;
    private final MapRenderState state = new MapRenderState();

    public MapPreviewComponent(MapPreviewData data) {
        this.data = data;
    }

    @Override
    public int getHeight(TextRenderer textRenderer) {
        return data.size() + 4;
    }

    @Override
    public int getWidth(TextRenderer textRenderer) {
        return data.size();
    }

    @Override
    public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        MapState map = mc.world.getMapState(data.id());
        if (map == null) return;
        mc.getMapRenderer().update(data.id(), map, state);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y + 2);
        m.scale(data.size() / 128f, data.size() / 128f);
        ctx.drawMap(state);
        m.popMatrix();
    }
}
