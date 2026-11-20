package net.evadeclient.hud;

import net.evadeclient.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

/** Small drawing helpers shared by the HUD elements. */
public final class HudDraw {
    public static final EquipmentSlot[] ARMOR_TOP_DOWN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private HudDraw() {}

    public static void panel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, ColorUtil.HUD_BG);
        ctx.fill(x, y, x + w, y + 1, ColorUtil.ACCENT);
    }

    /** Item icon with vanilla durability bar and count, plus a durability % label underneath. */
    public static void itemWithDurability(DrawContext ctx, ItemStack stack, int x, int y, boolean percent) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawItem(stack, x, y);
        ctx.drawStackOverlay(tr, stack, x, y);
        if (percent && stack.isDamageable() && stack.getMaxDamage() > 0) {
            float frac = 1f - stack.getDamage() / (float) stack.getMaxDamage();
            String label = Math.round(frac * 100) + "";
            int lw = tr.getWidth(label);
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(x + 8 - lw * 0.375f, y + 17);
            ctx.getMatrices().scale(0.75f, 0.75f);
            ctx.drawTextWithShadow(tr, label, 0, 0, ColorUtil.healthColor(frac));
            ctx.getMatrices().popMatrix();
        }
    }

    /** Draws the four armour pieces in a row (helmet first). Returns the width used. */
    public static int armorRow(DrawContext ctx, LivingEntity e, int x, int y, boolean percent) {
        int dx = 0;
        for (EquipmentSlot slot : ARMOR_TOP_DOWN) {
            ItemStack s = e.getEquippedStack(slot);
            if (!s.isEmpty()) itemWithDurability(ctx, s, x + dx, y, percent);
            else ctx.fill(x + dx + 3, y + 3, x + dx + 13, y + 13, 0x30FFFFFF);
            dx += 18;
        }
        return dx;
    }

    public static void bar(DrawContext ctx, int x, int y, int w, int h, float frac, int color) {
        ctx.fill(x, y, x + w, y + h, 0x80000000);
        ctx.fill(x, y, x + Math.round(w * Math.max(0, Math.min(1, frac))), y + h, color);
    }

    public static String seconds(int ticks) {
        return String.format("%.1fs", ticks / 20.0);
    }
}
