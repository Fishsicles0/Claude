package net.evadeclient.module.client;

import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.TargetTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

/**
 * Info about whoever you're fighting or looking at: face, name, health + absorption, distance,
 * armour with durability, what they're holding, and whether you're winning the trade.
 */
public class TargetHud extends HudElementModule {
    private final BooleanSetting percent = add(new BooleanSetting("Durability %", true));
    private final BooleanSetting held = add(new BooleanSetting("Held Items", true));
    private final BooleanSetting winning = add(new BooleanSetting("Win Indicator", true));

    private float shownHealth = -1;

    public TargetHud() {
        super("TargetHUD", "Shows the armour and health of your opponent.", -1, -56);
        width = 160;
        height = 64;
        setEnabled(true);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        LivingEntity t = TargetTracker.get();
        if (t == null && preview) t = mc.player;
        if (t == null) { shownHealth = -1; return; }

        var tr = mc.textRenderer;
        width = 160;
        height = held.get() ? 64 : 46;
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);

        int textX = left + 4;
        if (t instanceof AbstractClientPlayerEntity player) {
            PlayerSkinDrawer.draw(ctx, player.getSkin(), left + 4, top + 4, 24);
            textX = left + 32;
        }

        float hp = t.getHealth(), max = t.getMaxHealth(), abs = t.getAbsorptionAmount();
        // Animate the bar towards the real value.
        if (shownHealth < 0) shownHealth = hp;
        shownHealth += (hp - shownHealth) * 0.15f;

        ctx.drawTextWithShadow(tr, t.getName().getString(), textX, top + 5, ColorUtil.TEXT);
        String dist = String.format("%.1fm", mc.player.distanceTo(t));
        ctx.drawTextWithShadow(tr, dist, left + width - 4 - tr.getWidth(dist), top + 5, ColorUtil.TEXT_DIM);

        String hpText = String.format("%.1f", hp / 2) + "❤" + (abs > 0 ? " §6+" + String.format("%.1f", abs / 2) : "");
        ctx.drawTextWithShadow(tr, hpText, textX, top + 16, ColorUtil.healthColor(hp / max));

        if (winning.get() && t != mc.player) {
            float mine = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            float theirs = hp + abs;
            String w = mine > theirs + 2 ? "§aWinning" : mine < theirs - 2 ? "§cLosing" : "§eEven";
            ctx.drawTextWithShadow(tr, w, left + width - 4 - tr.getWidth(w.substring(2)), top + 16, ColorUtil.TEXT);
        }

        int barY = top + 27;
        int barW = width - 8;
        HudDraw.bar(ctx, left + 4, barY, barW, 3, shownHealth / max, ColorUtil.healthColor(hp / max));
        if (abs > 0) HudDraw.bar(ctx, left + 4, barY + 3, barW, 1, abs / max, 0xFFFFC000);

        int rowY = top + 33;
        HudDraw.armorRow(ctx, t, left + 4, rowY, percent.get());
        if (held.get()) {
            ItemStack main = t.getMainHandStack(), off = t.getOffHandStack();
            int hx = left + 4 + 4 * 18 + 8;
            if (!main.isEmpty()) HudDraw.itemWithDurability(ctx, main, hx, rowY, false);
            if (!off.isEmpty()) HudDraw.itemWithDurability(ctx, off, hx + 18, rowY, false);
            if (t.isBlocking()) ctx.drawTextWithShadow(tr, "§bBlocking", hx + 38, rowY + 4, ColorUtil.TEXT);
            else if (t.isUsingItem()) ctx.drawTextWithShadow(tr, "§eUsing", hx + 38, rowY + 4, ColorUtil.TEXT);
        }
    }
}
