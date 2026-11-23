package net.evadeclient.hud;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.Locale;

/** Small drawing helpers shared by the HUD elements. */
public final class HudDraw {
    public static final EquipmentSlot[] ARMOR_TOP_DOWN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private HudDraw() {}

    public static void panel(DrawContext ctx, int x, int y, int w, int h) {
        Theme.hudBox(ctx, x, y, w, h);
    }

    // ---- text in a widget's font ----------------------------------------------------------------------

    /**
     * {@code s} in the smooth HUD font when {@code smooth} (rasterised for the scale the current widget is drawn at,
     * see {@link HudElementModule#smoothStyle}), else plain (the pixel font).
     */
    public static MutableText styled(String s, boolean smooth) {
        MutableText t = Text.literal(s);
        return smooth ? t.setStyle(HudElementModule.smoothStyle()) : t;
    }

    /** Width of {@code s} in the font {@link #styled} picks. */
    public static int width(String s, boolean smooth) {
        var tr = MinecraftClient.getInstance().textRenderer;
        return smooth ? tr.getWidth(styled(s, true)) : tr.getWidth(s);
    }

    /** {@code s} cut to at most {@code maxWidth} pixels in the font {@link #styled} picks. */
    public static String trim(String s, int maxWidth, boolean smooth) {
        var tr = MinecraftClient.getInstance().textRenderer;
        return tr.getTextHandler().trimToWidth(s, Math.max(0, maxWidth), smooth ? HudElementModule.smoothStyle() : Style.EMPTY);
    }

    // ---- items ------------------------------------------------------------------------------------------

    /** Item icon with vanilla durability bar and count, plus a durability % label underneath. */
    public static void itemWithDurability(DrawContext ctx, ItemStack stack, int x, int y, boolean percent) {
        itemWithDurability(ctx, stack, x, y, percent, false);
    }

    /** Item icon with vanilla durability bar and count, plus a durability % label underneath in the widget's font. */
    public static void itemWithDurability(DrawContext ctx, ItemStack stack, int x, int y, boolean percent, boolean smooth) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawItem(stack, x, y);
        ctx.drawStackOverlay(tr, stack, x, y);
        if (percent && stack.isDamageable() && stack.getMaxDamage() > 0) {
            float frac = 1f - stack.getDamage() / (float) stack.getMaxDamage();
            Text label = styled(String.valueOf(Math.round(frac * 100)), smooth);
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

    /** A duration in ticks: "12.5s" under a minute, "1:05" from a minute up. */
    public static String seconds(int ticks) {
        if (ticks >= 1200) {
            int s = ticks / 20;
            return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    /** A count that fits a small label: 999, then 1.2k, 12k. */
    public static String shortCount(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k");
        return (n / 1000) + "k";
    }

    // ---- screen geometry in HUD units ------------------------------------------------------------------

    /** Width of the screen in HUD units (what {@link Hud} lays elements out in). */
    public static int screenWidth() {
        var mc = MinecraftClient.getInstance();
        return mc.getWindow() == null ? 0 : Math.round(mc.getWindow().getScaledWidth() / Hud.scale());
    }

    /** Height of the screen in HUD units. */
    public static int screenHeight() {
        var mc = MinecraftClient.getInstance();
        return mc.getWindow() == null ? 0 : Math.round(mc.getWindow().getScaledHeight() / Hud.scale());
    }

    /**
     * How far down (HUD units) vanilla's boss bars reach right now: the bottom of the last bar drawn, 0 when there
     * are none or the Boss Bar mod hides them. Follows the Boss Bar mod's scale and vanilla's own limit of a third
     * of the screen. Top-centre widgets at their default spots sit below this.
     */
    public static int bossBarBottom() {
        var mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null || mc.getWindow() == null) return 0;
        if (!(mc.inGameHud.getBossBarHud() instanceof net.evadeclient.mixin.BossBarHudAccessor bars)) return 0;
        int n = bars.evade$getBossBars().size();
        if (n == 0) return 0;
        var mod = net.evadeclient.module.visuals.BossBar.active();
        if (mod != null && mod.hide.get()) return 0;
        // Same walk as BossBarHud.render: a bar every 19 GUI pixels from y 12, stopping at a third of the screen.
        int limit = mc.getWindow().getScaledHeight() / 3, barY = 12, drawn = 0;
        while (drawn < n) {
            drawn++;
            barY += 19;
            if (barY >= limit) break;
        }
        float s = mod == null ? 1f : mod.scale.getFloat();
        float bottomGui = (12 + (drawn - 1) * 19 + 5) * s;
        return (int) Math.ceil(bottomGui / Hud.scale());
    }
}
