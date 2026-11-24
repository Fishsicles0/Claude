package net.evadeclient.module.visuals.tooltip;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.render.block.entity.model.BannerFlagBlockModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;

/**
 * A banner's flag drawn large in its tooltip, the way the loom shows its result: every pattern layer in its colour.
 */
public final class BannerPreviewComponent implements TooltipComponent {
    static final int W = 20, H = 40;

    private final BannerPreviewData data;
    private BannerFlagBlockModel model;

    public BannerPreviewComponent(BannerPreviewData data) {
        this.data = data;
    }

    @Override
    public int getHeight(TextRenderer textRenderer) {
        return H + 4;
    }

    @Override
    public int getWidth(TextRenderer textRenderer) {
        return W + 2;
    }

    @Override
    public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext ctx) {
        if (model == null) {
            model = new BannerFlagBlockModel(MinecraftClient.getInstance().getLoadedEntityModels().getModelPart(EntityModelLayers.STANDING_BANNER_FLAG));
        }
        ctx.addBannerResult(model, data.base(), data.patterns(), x + 1, y + 2, x + 1 + W, y + 2 + H);
    }
}
