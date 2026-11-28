package net.evadeclient.gui;

import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
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
        var cs = net.evadeclient.EvadeClient.modules == null ? null
                : net.evadeclient.EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        boolean smooth = (cs == null || cs.smoothFont.get()) && !Theme.square;
        int shown = Math.min(MAX_ICONS, items.size());
        String more = items.size() > shown ? "+" + (items.size() - shown) : items.isEmpty() ? "None" : "";
        int moreW = more.isEmpty() ? 0 : UiFont.width(mc.textRenderer, more, Role.SMALL, smooth) + 4;
        int w = Math.max(44, shown * (ICON + 2) + moreW + 12);
        int x1 = right - w;
        int h = 18;
        // A kit dropdown-like trigger: raised e3 surface, brighter while hovered.
        Paint.surface(ctx, x1, midY - h / 2f, right, midY + h / 2f, Tokens.R_SM,
                Tokens.E3.noAmbient().withFill(hover ? Tokens.N300 : Tokens.N250), hover ? 1f : 0f, Float.NaN);
        int x = x1 + 6;
        for (int i = 0; i < shown; i++) {
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(x, midY - ICON / 2f);
            ctx.getMatrices().scale(ICON / 16f, ICON / 16f);
            ctx.drawItem(new ItemStack(items.get(i)), 0, 0);
            ctx.getMatrices().popMatrix();
            x += ICON + 2;
        }
        if (!more.isEmpty()) {
            int tx = items.isEmpty() ? x1 + (w - UiFont.width(mc.textRenderer, more, Role.SMALL, smooth)) / 2 : x + 2;
            float ty = UiFont.centredY(Role.SMALL, midY, smooth);
            UiFont.draw(ctx, mc.textRenderer, more, tx, Math.round(ty), items.isEmpty() ? Tokens.N600 : Tokens.N800, Role.SMALL, smooth);
        }
        return w;
    }
}
