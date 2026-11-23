package net.evadeclient.module.hud;

import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

/** Shows the 27 main inventory slots on screen, optionally with your armour and offhand beside them. */
public class InventoryHud extends HudElementModule {
    private static final EquipmentSlot[] SIDE = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

    private final BooleanSetting slotBackgrounds = add(new BooleanSetting("Slot Backgrounds", true)
            .describe("A faint square behind every slot, so empty slots show."));
    private final BooleanSetting hideWhileOpen = add(new BooleanSetting("Hide While Inventory Open", true)
            .describe("Hide the viewer while an inventory or container screen is open."));
    private final BooleanSetting armorColumn = add(new BooleanSetting("Armor Column", false)
            .describe("Your armour and offhand in a column on the left."));

    public InventoryHud() { super("Inventory Viewer", "Your inventory on screen.", 4, -1); }

    @Override
    public boolean shouldShow() {
        if (hideWhileOpen.get() && mc.currentScreen instanceof HandledScreen<?>) return false;
        return super.shouldShow();
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        boolean side = armorColumn.get();
        int sideW = side ? 18 + 3 : 0;
        width = 9 * 18 + 6 + sideW;
        height = (side ? Math.max(3, SIDE.length) : 3) * 18 + 6;
        var p = mc.player;
        if (p == null) { empty = true; return; }
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        var inv = p.getInventory();
        var tr = mc.textRenderer;
        boolean bg = slotBackgrounds.get();
        if (side) {
            for (int i = 0; i < SIDE.length; i++) {
                int x = left + 3, y = top + 3 + i * 18;
                ItemStack s = p.getEquippedStack(SIDE[i]);
                if (bg) ctx.fill(x, y, x + 16, y + 16, 0x30FFFFFF);
                if (!s.isEmpty()) HudDraw.itemWithDurability(ctx, s, x, y, false, smoothFont.get());
            }
        }
        int gridTop = top + 3 + (height - 6 - 3 * 18) / 2;
        for (int i = 0; i < 27; i++) {
            int x = left + 3 + sideW + (i % 9) * 18, y = gridTop + (i / 9) * 18;
            if (bg) ctx.fill(x, y, x + 16, y + 16, 0x30FFFFFF);
            var s = inv.getStack(9 + i);
            if (!s.isEmpty()) {
                ctx.drawItem(s, x, y);
                ctx.drawStackOverlay(tr, s, x, y);
            }
        }
    }
}
