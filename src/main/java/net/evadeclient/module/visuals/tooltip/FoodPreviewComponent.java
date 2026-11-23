package net.evadeclient.module.visuals.tooltip;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * A row of hunger shanks, the same sprites as the hunger bar: an empty shank behind each one, then full and half
 * shanks for the hunger restored (one shank = 2 points). Saturation follows as dim text. Foods that restore more than
 * {@link #MAX_SHANKS} shanks (custom items) show one shank and a multiplier instead of a row off the screen.
 */
public final class FoodPreviewComponent implements TooltipComponent {
    private static final Identifier EMPTY = Identifier.ofVanilla("hud/food_empty");
    private static final Identifier FULL = Identifier.ofVanilla("hud/food_full");
    private static final Identifier HALF = Identifier.ofVanilla("hud/food_half");
    private static final int ICON = 9;
    private static final int MAX_SHANKS = 10;
    private static final int TEXT_GAP = 4;
    private static final int TEXT_COLOR = 0xFFAAAAAA;

    private final FoodPreviewData data;

    public FoodPreviewComponent(FoodPreviewData data) {
        this.data = data;
    }

    private int shanks() {
        return Math.max(0, (data.nutrition() + 1) / 2);
    }

    private boolean condensed() {
        return shanks() > MAX_SHANKS;
    }

    /** "x12.5" next to the single shank of a condensed row, else null. */
    private String multiplier() {
        return condensed() ? "x" + decimal(data.nutrition() / 2f) : null;
    }

    private String saturationText() {
        return data.showSaturation() ? "Saturation " + decimal(data.saturation()) : null;
    }

    private int iconsWidth(TextRenderer tr) {
        if (shanks() == 0) return 0;
        if (!condensed()) return shanks() * ICON;
        return ICON + 1 + tr.getWidth(multiplier());
    }

    @Override
    public int getHeight(TextRenderer textRenderer) {
        return 11;
    }

    @Override
    public int getWidth(TextRenderer textRenderer) {
        int w = iconsWidth(textRenderer);
        String sat = saturationText();
        if (sat != null) w += (w > 0 ? TEXT_GAP : 0) + textRenderer.getWidth(sat);
        return w;
    }

    @Override
    public void drawText(DrawContext ctx, TextRenderer textRenderer, int x, int y) {
        String mult = multiplier();
        if (mult != null) ctx.drawText(textRenderer, mult, x + ICON + 1, y + 1, 0xFFFFFFFF, true);
        String sat = saturationText();
        if (sat != null) {
            int icons = iconsWidth(textRenderer);
            ctx.drawText(textRenderer, sat, x + icons + (icons > 0 ? TEXT_GAP : 0), y + 1, TEXT_COLOR, true);
        }
    }

    @Override
    public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext ctx) {
        int n = data.nutrition();
        if (n <= 0) return;
        if (condensed()) {
            ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, EMPTY, x, y, ICON, ICON);
            ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, FULL, x, y, ICON, ICON);
            return;
        }
        for (int i = 0; i < shanks(); i++) {
            int ix = x + i * ICON;
            ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, EMPTY, ix, y, ICON, ICON);
            if (i * 2 + 1 < n) ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, FULL, ix, y, ICON, ICON);
            else if (i * 2 + 1 == n) ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HALF, ix, y, ICON, ICON);
        }
    }

    /** 2.4, 6, 12.5: one decimal at most, none when it's a whole number. */
    static String decimal(float v) {
        float r = Math.round(v * 10f) / 10f;
        return r == (int) r ? Integer.toString((int) r) : String.format(Locale.ROOT, "%.1f", r);
    }
}
