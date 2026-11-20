package net.evadeclient.module.hud;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

/** Shows the 27 main inventory slots on screen. */
public class InventoryHud extends HudElementModule {
    public InventoryHud() { super("Inventory Viewer", "Your inventory on screen.", -1, 330); }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        width = 9 * 18 + 6;
        height = 3 * 18 + 6;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        var inv = mc.player.getInventory();
        var tr = mc.textRenderer;
        for (int i = 0; i < 27; i++) {
            int x = left + 3 + (i % 9) * 18, y = top + 3 + (i / 9) * 18;
            ctx.fill(x, y, x + 16, y + 16, 0x30FFFFFF);
            var s = inv.getStack(9 + i);
            if (!s.isEmpty()) {
                ctx.drawItem(s, x, y);
                ctx.drawStackOverlay(tr, s, x, y);
            }
        }
    }
}
