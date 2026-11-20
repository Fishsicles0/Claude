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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Your armour with durability, held items, and counts of your PvP supplies. */
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

    public ArmorHud() {
        super("ArmorHUD", "Shows your armour durability and supplies.", -1, -1);
        setEnabled(true);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var p = mc.player;
        List<ItemStack> items = new ArrayList<>();
        for (EquipmentSlot slot : HudDraw.ARMOR_TOP_DOWN) items.add(p.getEquippedStack(slot));
        if (hands.get()) {
            items.add(p.getMainHandStack());
            items.add(p.getOffHandStack());
        }

        boolean vertical = layout.is("Vertical");
        int step = vertical ? 20 : 18;
        int mainLen = items.size() * step;

        List<int[]> counts = new ArrayList<>();
        List<ItemStack> icons = new ArrayList<>();
        if (supplies.get()) {
            for (Supply s : SUPPLIES) {
                int n = InvUtil.count(s.match);
                if (n > 0 || preview && icons.size() < 3) {
                    icons.add(s.icon);
                    counts.add(new int[]{n});
                }
            }
        }

        width = vertical ? 22 + (icons.isEmpty() ? 0 : 40) : Math.max(mainLen, icons.size() * 30) + 4;
        height = vertical ? Math.max(mainLen, icons.size() * 18) + 4 : (icons.isEmpty() ? 28 : 46);
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);

        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            int x = vertical ? left + 3 : left + 2 + i * step;
            int y = vertical ? top + 3 + i * step : top + 3;
            if (s.isEmpty()) ctx.fill(x + 3, y + 3, x + 13, y + 13, 0x30FFFFFF);
            else HudDraw.itemWithDurability(ctx, s, x, y, percent.get());
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

        var tr = mc.textRenderer;
        for (int i = 0; i < icons.size(); i++) {
            int x = vertical ? left + 24 : left + 2 + i * 30;
            int y = vertical ? top + 3 + i * 18 : top + 27;
            ctx.drawItem(icons.get(i), x, y);
            int n = counts.get(i)[0];
            ctx.drawTextWithShadow(tr, String.valueOf(n), x + 17, y + 5, n == 0 ? 0xFFFF5555 : ColorUtil.TEXT);
        }

        // Absorption hint for gapple timing.
        var absorb = p.getStatusEffect(StatusEffects.ABSORPTION);
        if (absorb != null && !vertical && !supplies.get()) {
            ctx.drawTextWithShadow(tr, "§6Absorb " + HudDraw.seconds(absorb.getDuration()), left + 2, top + height + 2, ColorUtil.TEXT);
        }
    }
}
