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
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Small drawing helpers shared by the HUD elements. */
public final class HudDraw {
    public static final EquipmentSlot[] ARMOR_TOP_DOWN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private HudDraw() {}

    // ---- panels ----------------------------------------------------------------------------------------

    /** A widget's background panel in its Background Style ({@link Theme#hudStyle}), with the Evade accent edge. */
    public static void panel(DrawContext ctx, int x, int y, int w, int h) {
        panel(ctx, x, y, w, h, true);
    }

    /**
     * A widget's background panel in its Background Style ({@link Theme#hudStyle}) and opacity
     * ({@link Theme#hudOpacity}): a soft drop shadow, then the body (see {@link #panelBody}). {@code accentEdge}
     * gives the Evade style its accent top edge; sub-panels inside a widget (Keystrokes' keys) leave it off.
     */
    public static void panel(DrawContext ctx, int x, int y, int w, int h, boolean accentEdge) {
        panelShadow(ctx, x, y, w, h);
        panelBody(ctx, x, y, w, h, accentEdge);
    }

    /**
     * The panel's soft drop shadow alone, for widgets that draw several panels and want every shadow under every
     * body. Outline has none; it is a knockout shadow, so it never darkens the translucent body above it, and the
     * Classic theme (square corners) skips it.
     */
    public static void panelShadow(DrawContext ctx, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || "Outline".equals(Theme.hudStyle)) return;
        Theme.shadow(ctx, Theme.SH_HUD, x, y, x + w, y + h, Theme.alpha(0xFF000000, 0.5f * Theme.hudOpacity));
    }

    /**
     * The panel's body alone, with anti-aliased corners of radius {@link Theme#R_HUD}:
     * <ul>
     *   <li>Evade: the dark glass fill and, with {@code accentEdge}, an accent top edge that is brightest in the
     *       middle and fades out before the rounded corners.</li>
     *   <li>Solid: an opaque card fill.</li>
     *   <li>Glass: a light frosted fill, a faint white rim and a 1 px highlight just inside the top edge.</li>
     *   <li>Outline: a 1 px accent border only.</li>
     * </ul>
     */
    public static void panelBody(DrawContext ctx, int x, int y, int w, int h, boolean accentEdge) {
        if (w <= 0 || h <= 0) return;
        float o = Theme.hudOpacity;
        int x2 = x + w, y2 = y + h, r = Theme.R_HUD;
        switch (Theme.hudStyle) {
            case "Solid" -> Theme.round(ctx, x, y, x2, y2, Theme.hudFill(), r);
            case "Glass" -> {
                Theme.round(ctx, x, y, x2, y2, Theme.hudFill(), r);
                Theme.stroke(ctx, x, y, x2, y2, Theme.alpha(0x30FFFFFF, o), r);
                if (h > 3) Theme.fill(ctx, x + r, y + 1, x2 - r, y + 2, Theme.alpha(0x0FFFFFFF, o));
            }
            case "Outline" -> Theme.stroke(ctx, x, y, x2, y2, Theme.alpha(Theme.RED | 0xFF000000, 0.85f * o), r);
            default -> {
                Theme.round(ctx, x, y, x2, y2, Theme.hudFill(), r);
                if (accentEdge) Theme.fadeLine(ctx, x + r, y, x2 - r, Theme.alpha(Theme.RED | 0xFF000000, Math.max(0.35f, o)));
            }
        }
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
        String label = null;
        Integer color = null;
        if (percent && stack.isDamageable() && stack.getMaxDamage() > 0) {
            float frac = durability(stack);
            label = String.valueOf(Math.round(frac * 100));
            color = ColorUtil.healthColor(frac);
        }
        itemWithDurability(ctx, stack, x, y, label, color, null, smooth);
    }

    /**
     * Item icon with vanilla's durability bar, cooldown sweep and count, plus an optional small label centred under
     * it (e.g. durability).
     *
     * @param label     drawn at 3/4 size under the icon; null (or a null {@code color}) draws none
     * @param color     the label's colour; null draws no label
     * @param countText replaces vanilla's stack count in the corner (e.g. a total across the inventory); null keeps
     *                  vanilla's (nothing for a single item). Legacy formatting codes work ("§c0" is a red 0).
     */
    public static void itemWithDurability(DrawContext ctx, ItemStack stack, int x, int y, @Nullable String label,
                                          @Nullable Integer color, @Nullable String countText, boolean smooth) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawItem(stack, x, y);
        ctx.drawStackOverlay(tr, stack, x, y, countText);
        if (label != null && color != null) smallText(ctx, styled(label, smooth), x + 8, y + 17, color, 0);
    }

    /** Fraction of durability left, 1 for items that can't be damaged. */
    public static float durability(ItemStack stack) {
        if (!stack.isDamageable() || stack.getMaxDamage() <= 0) return 1f;
        return Math.max(0f, 1f - stack.getDamage() / (float) stack.getMaxDamage());
    }

    /**
     * {@code text} at 3/4 size, centred on {@code centerX} with its top at {@code y}. With {@code maxWidth > 0} it
     * shrinks further when needed so it is never wider than that.
     */
    public static void smallText(DrawContext ctx, Text text, float centerX, float y, int color, float maxWidth) {
        var tr = MinecraftClient.getInstance().textRenderer;
        int w = tr.getWidth(text);
        float s = 0.75f;
        if (maxWidth > 0 && w * s > maxWidth) s = maxWidth / Math.max(1, w);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(centerX - w * s / 2f, y);
        ctx.getMatrices().scale(s, s);
        ctx.drawTextWithShadow(tr, text, 0, 0, color);
        ctx.getMatrices().popMatrix();
    }

    /** Draws the four armour pieces in a row (helmet first). Returns the width used. */
    public static int armorRow(DrawContext ctx, LivingEntity e, int x, int y, boolean percent) {
        int dx = 0;
        for (EquipmentSlot slot : ARMOR_TOP_DOWN) {
            ItemStack s = e.getEquippedStack(slot);
            if (!s.isEmpty()) itemWithDurability(ctx, s, x + dx, y, percent);
            else Theme.round(ctx, x + dx + 3, y + 3, x + dx + 13, y + 13, 0x30FFFFFF, Theme.R_XS);
            dx += 18;
        }
        return dx;
    }

    /**
     * A progress bar as a capsule: a dark translucent track with the filled part ({@code frac} 0..1, from the left)
     * on top, both with fully rounded ends. A fill shorter than the bar is tall gets a smaller radius (a plain sliver
     * at 1 px), so it never pokes outside the track.
     */
    public static void bar(DrawContext ctx, int x, int y, int w, int h, float frac, int color) {
        if (w <= 0 || h <= 0) return;
        Theme.pill(ctx, x, y, x + w, y + h, 0x80000000);
        float f = Float.isNaN(frac) ? 0f : Math.max(0f, Math.min(1f, frac));
        Theme.pill(ctx, x, y, x + Math.round(w * f), y + h, color);
    }

    /** A duration in ticks: "12.5s" under a minute, "1:05" from a minute up. */
    public static String seconds(int ticks) {
        if (ticks >= 1200) {
            int s = ticks / 20;
            return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    /** The time formats a timer widget can offer (see {@link #time}). */
    public static final String[] TIME_FORMATS = {"Decimal", "Whole", "Clock", "Ticks"};

    /**
     * A duration in ticks in one of {@link #TIME_FORMATS}: Decimal "12.5s" (as {@link #seconds}), Whole "13s"
     * (rounded up, so it reads 1s until it is over), Clock "0:13", Ticks "250t". Whole switches to "1:05" from a
     * minute up, like Decimal.
     */
    public static String time(int ticks, String format) {
        int t = Math.max(0, ticks);
        int whole = (t + 19) / 20;
        return switch (format) {
            case "Whole" -> whole >= 60 ? String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60) : whole + "s";
            case "Clock" -> String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
            case "Ticks" -> t + "t";
            default -> seconds(t);
        };
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
     * are none or the Boss Bar mod hides them. Follows the Boss Bar mod's Scale, Y Offset, Max Bars and Mode and
     * vanilla's own limit of a third of the screen. Top-centre widgets at their default spots sit below this.
     */
    public static int bossBarBottom() {
        var mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null || mc.getWindow() == null) return 0;
        if (!(mc.inGameHud.getBossBarHud() instanceof net.evadeclient.mixin.BossBarHudAccessor bars)) return 0;
        int n = bars.evade$getBossBars().size();
        if (n == 0) return 0;
        // The Boss Bar mod's own walk: vanilla's layout and third-of-the-screen limit, plus Scale, Y Offset,
        // Max Bars and Mode (0 when hidden).
        int bottomGui = net.evadeclient.module.visuals.BossBar.bottomGui(n, mc.getWindow().getScaledHeight());
        return bottomGui == 0 ? 0 : (int) Math.ceil(bottomGui / Hud.scale());
    }
}
