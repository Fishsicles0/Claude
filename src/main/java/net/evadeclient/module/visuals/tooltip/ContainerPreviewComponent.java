package net.evadeclient.module.visuals.tooltip;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * Draws a {@link ContainerPreviewData}: the container's slots as a grid of vanilla slot frames with the items and
 * their counts (dyed in the box's colour when tinted), or the frameless Compact row of item kinds with totals.
 * Sits right under the item's name, where the game puts bundle contents.
 */
public final class ContainerPreviewComponent implements TooltipComponent {
    private static final Identifier SLOT = Identifier.ofVanilla("container/slot");
    /** A vanilla slot is 18x18 with the 16x16 item at +1,+1. */
    private static final int CELL = 18;
    private static final int HEADER_HEIGHT = 10;
    /** Space under the cells, so the next tooltip line doesn't touch them. */
    private static final int GAP = 2;
    /** How strongly a tinted grid's slots take the box colour (0 = plain grey, 1 = solid colour). */
    private static final float SLOT_TINT = 0.45f;
    private static final int PANEL_ALPHA = 0xB0;
    private static final int COMPACT_CELL_BG = 0x1CFFFFFF;
    private static final int HEADER_COLOR = 0xFFA8A8B4;

    private final ContainerPreviewData data;

    public ContainerPreviewComponent(ContainerPreviewData data) {
        this.data = data;
    }

    private int headerHeight() {
        return data.header() == null ? 0 : HEADER_HEIGHT;
    }

    /** The 1px border a tinted grid gets on every side. */
    private int border() {
        return data.tint() != 0 ? 1 : 0;
    }

    @Override
    public int getHeight(TextRenderer textRenderer) {
        return headerHeight() + data.rows() * CELL + 2 * border() + GAP;
    }

    @Override
    public int getWidth(TextRenderer textRenderer) {
        int cells = Math.min(data.columns(), Math.max(1, data.stacks().size())) * CELL + 2 * border();
        return data.header() == null ? cells : Math.max(cells, textRenderer.getWidth(data.header()));
    }

    @Override
    public void drawText(DrawContext ctx, TextRenderer textRenderer, int x, int y) {
        if (data.header() != null) ctx.drawText(textRenderer, data.header(), x, y, HEADER_COLOR, true);
    }

    @Override
    public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext ctx) {
        int cols = data.columns();
        int top = y + headerHeight();
        int b = border();
        if (data.tint() != 0) {
            int usedCols = Math.min(cols, Math.max(1, data.stacks().size()));
            ctx.fill(x, top, x + usedCols * CELL + 2 * b, top + data.rows() * CELL + 2 * b,
                    (PANEL_ALPHA << 24) | (data.tint() & 0xFFFFFF));
        }
        int slotColor = data.tint() == 0 ? 0xFFFFFFFF : mix(0xFFFFFFFF, data.tint() | 0xFF000000, SLOT_TINT);
        for (int i = 0; i < data.stacks().size(); i++) {
            int cx = x + b + (i % cols) * CELL;
            int cy = top + b + (i / cols) * CELL;
            if (data.slots()) ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, SLOT, cx, cy, CELL, CELL, slotColor);
            else ctx.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, COMPACT_CELL_BG);
            ItemStack stack = data.stacks().get(i);
            if (stack.isEmpty()) continue;
            ctx.drawItem(stack, cx + 1, cy + 1, i);
            ctx.drawStackOverlay(textRenderer, stack, cx + 1, cy + 1, data.counts() == null ? null : data.counts().get(i));
        }
    }

    /** Per-channel blend of two ARGB colours, {@code t} of the way from {@code a} to {@code b}. */
    private static int mix(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return r;
    }
}
