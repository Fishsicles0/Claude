package net.evadeclient.module.client;

import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.InvUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Your armour with durability, held items, and counts of your PvP supplies. Supplies wrap into extra rows (or
 * columns) instead of growing sideways, so the panel keeps its size at its corner. Hidden while you have nothing
 * to show.
 */
public class ArmorHud extends HudElementModule {
    private final ModeSetting layout = add(new ModeSetting("Layout", "Horizontal", "Horizontal", "Vertical"));
    private final BooleanSetting percent = add(new BooleanSetting("Durability %", true));
    private final BooleanSetting hands = add(new BooleanSetting("Hands", true));
    private final BooleanSetting supplies = add(new BooleanSetting("Supplies", true));

    private record Supply(ItemStack icon, Predicate<ItemStack> match) {}

    private static final List<Supply> SUPPLIES = List.of(
            new Supply(new ItemStack(Items.TOTEM_OF_UNDYING), s -> s.isOf(Items.TOTEM_OF_UNDYING)),
            new Supply(new ItemStack(Items.GOLDEN_APPLE), s -> s.isOf(Items.GOLDEN_APPLE)),
            new Supply(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), s -> s.isOf(Items.ENCHANTED_GOLDEN_APPLE)),
            new Supply(new ItemStack(Items.ENDER_PEARL), s -> s.isOf(Items.ENDER_PEARL)),
            new Supply(new ItemStack(Items.WIND_CHARGE), s -> s.isOf(Items.WIND_CHARGE)),
            new Supply(new ItemStack(Items.EXPERIENCE_BOTTLE), s -> s.isOf(Items.EXPERIENCE_BOTTLE)),
            new Supply(new ItemStack(Items.SPLASH_POTION), s -> s.isOf(Items.SPLASH_POTION)),
            new Supply(new ItemStack(Items.END_CRYSTAL), s -> s.isOf(Items.END_CRYSTAL)),
            new Supply(new ItemStack(Items.OBSIDIAN), s -> s.isOf(Items.OBSIDIAN)));
    /** Counts shown for the editor's sample supplies when you carry none. */
    private static final int[] SAMPLE_COUNTS = {1, 12, 3};

    /** A supply cell: icon, count label and its width (icon, gap, label, padding). */
    private record Cell(ItemStack icon, Text label, int width) {}

    public ArmorHud() {
        super("ArmorHUD", "Shows your armour durability and supplies.", -1, -24);
        setEnabled(true);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var p = mc.player;
        if (p == null) { empty = true; return; }
        var tr = mc.textRenderer;
        boolean smooth = smoothFont.get();
        List<ItemStack> items = new ArrayList<>();
        for (EquipmentSlot slot : HudDraw.ARMOR_TOP_DOWN) items.add(p.getEquippedStack(slot));
        if (hands.get()) {
            items.add(p.getMainHandStack());
            items.add(p.getOffHandStack());
        }

        List<Cell> cells = new ArrayList<>();
        if (supplies.get()) {
            for (Supply s : SUPPLIES) {
                int n = InvUtil.count(s.match);
                if (n > 0) cells.add(cell(s.icon, n, smooth));
            }
            if (preview && cells.isEmpty())
                for (int i = 0; i < SAMPLE_COUNTS.length; i++) cells.add(cell(SUPPLIES.get(i).icon, SAMPLE_COUNTS[i], smooth));
        }
        if (!preview && cells.isEmpty() && items.stream().allMatch(ItemStack::isEmpty)) { empty = true; return; }

        var absorb = p.getStatusEffect(StatusEffects.ABSORPTION);
        Text hint = absorb == null ? null : HudDraw.styled("Absorb " + HudDraw.seconds(absorb.getDuration()), smooth);
        int hintW = hint == null ? 0 : tr.getWidth(hint);

        boolean vertical = layout.is("Vertical");
        int step = vertical ? 20 : 18;
        int mainLen = items.size() * step;
        // Supply positions relative to the panel, wrapped so the block is never longer than the item row/column.
        int[][] at = new int[cells.size()][];
        int suppliesW = 0, suppliesH = 0;
        if (!vertical) {
            int cx = 0, cy = 0;
            for (int i = 0; i < cells.size(); i++) {
                int w = cells.get(i).width;
                if (cx > 0 && cx + w > mainLen) { cx = 0; cy += 18; }
                at[i] = new int[]{2 + cx, 27 + cy};
                cx += w;
                suppliesW = Math.max(suppliesW, cx);
            }
            suppliesH = cells.isEmpty() ? 0 : cy + 18;
            width = Math.max(mainLen, suppliesW) + 4;
            height = 28 + suppliesH;
        } else {
            int perCol = Math.max(1, mainLen / 18);
            int colX = 24, colW = 0;
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0 && i % perCol == 0) { colX += colW; colW = 0; }
                at[i] = new int[]{colX, 3 + (i % perCol) * 18};
                colW = Math.max(colW, cells.get(i).width);
            }
            suppliesW = cells.isEmpty() ? 0 : colX + colW - 22;
            suppliesH = Math.min(cells.size(), perCol) * 18;
            width = 22 + suppliesW;
            height = Math.max(mainLen, suppliesH) + 4;
        }
        if (hint != null) {
            height += 10;
            width = Math.max(width, hintW + 6);
        }
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);

        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            int x = vertical ? left + 3 : left + 2 + i * step;
            int y = vertical ? top + 3 + i * step : top + 3;
            if (s.isEmpty()) ctx.fill(x + 3, y + 3, x + 13, y + 13, 0x30FFFFFF);
            else HudDraw.itemWithDurability(ctx, s, x, y, percent.get(), smooth);
        }

        // Low durability warning: flash the panel border.
        for (EquipmentSlot slot : HudDraw.ARMOR_TOP_DOWN) {
            ItemStack s = p.getEquippedStack(slot);
            if (s.isDamageable() && s.getMaxDamage() > 0 && s.getDamage() / (float) s.getMaxDamage() > 0.85f
                    && (System.currentTimeMillis() / 400) % 2 == 0) {
                ctx.fill(left, top, left + width, top + 1, 0xFFFF3030);
                break;
            }
        }

        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            int x = left + at[i][0], y = top + at[i][1];
            ctx.drawItem(c.icon, x, y);
            ctx.drawTextWithShadow(tr, c.label, x + 18, y + 5, ColorUtil.TEXT);
        }

        // Absorption hint for gapple timing, inside the panel along its bottom.
        if (hint != null) ctx.drawTextWithShadow(tr, hint, left + 3, top + height - 11, 0xFFFFAA00);
    }

    private Cell cell(ItemStack icon, int count, boolean smooth) {
        Text label = HudDraw.styled(HudDraw.shortCount(count), smooth);
        return new Cell(icon, label, 16 + 2 + mc.textRenderer.getWidth(label) + 3);
    }
}
