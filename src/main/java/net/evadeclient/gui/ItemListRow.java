package net.evadeclient.gui;

import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * The value box of an {@link ItemListSetting} row in the Evade menu: the chosen items' icons (small), with "+3" when
 * more are chosen than fit and "none" when empty. A click on the row opens the item picker (the setting's click
 * action), like any clickable text row.
 */
public final class ItemListRow {
    private ItemListRow() {}

    private static final int ICON = 12, MAX_ICONS = 6;

    /** Draws the box ending at {@code right}, centred on {@code midY}; returns its width. */
    public static int draw(DrawContext ctx, ItemListSetting setting, int right, int midY, boolean hover) {
        List<Item> items = setting.items();
        MinecraftClient mc = MinecraftClient.getInstance();
        int shown = Math.min(MAX_ICONS, items.size());
        String more = items.size() > shown ? "+" + (items.size() - shown) : items.isEmpty() ? "none" : "";
        int moreW = more.isEmpty() ? 0 : mc.textRenderer.getWidth(more) + 4;
        int w = Math.max(36, shown * (ICON + 1) + moreW + 10);
        int x1 = right - w;
        Theme.roundBordered(ctx, x1, midY - 7, right, midY + 7, hover ? Theme.CONTROL_HOVER : Theme.CONTROL,
                hover ? Theme.BORDER_HOVER : Theme.BORDER);
        int x = x1 + 5;
        for (int i = 0; i < shown; i++) {
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(x, midY - ICON / 2f);
            ctx.getMatrices().scale(ICON / 16f, ICON / 16f);
            ctx.drawItem(new ItemStack(items.get(i)), 0, 0);
            ctx.getMatrices().popMatrix();
            x += ICON + 1;
        }
        if (!more.isEmpty()) {
            int tx = items.isEmpty() ? x1 + (w - mc.textRenderer.getWidth(more)) / 2 : x + 2;
            ctx.drawText(mc.textRenderer, more, tx, midY - 4, items.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT_DIM, false);
        }
        return w;
    }
}
