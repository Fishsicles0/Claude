package net.evadeclient.module.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;

import java.util.ArrayList;
import java.util.List;

/** Active potion effects with icon, level and time left. Flashes when about to expire. */
public class PotionHud extends HudElementModule {
    private final BooleanSetting hideVanilla = add(new BooleanSetting("Hide Vanilla", true));
    private static final RenderPipeline GUI = RenderPipelines.GUI_TEXTURED;

    public PotionHud() {
        super("Potion Effects", "Active effects with time remaining.", -1, 240);
        setEnabled(true);
    }

    public boolean hidesVanilla() { return isEnabled() && hideVanilla.get(); }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        List<StatusEffectInstance> effects = new ArrayList<>(mc.player.getStatusEffects());
        if (effects.isEmpty() && preview) {
            effects.add(new StatusEffectInstance(StatusEffects.SPEED, 20 * 95, 1));
            effects.add(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 30, 1));
        }
        effects.sort((a, b) -> Integer.compare(b.getDuration(), a.getDuration()));
        var tr = mc.textRenderer;
        width = 110;
        height = Math.max(12, effects.size() * 22 + 2);
        if (effects.isEmpty()) return;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int y = top + 2;
        for (StatusEffectInstance e : effects) {
            boolean expiring = !e.isInfinite() && e.getDuration() < 200;
            boolean blinkOff = expiring && (System.currentTimeMillis() / 250) % 2 == 0;
            if (!blinkOff) ctx.drawGuiTexture(GUI, InGameHud.getEffectTexture(e.getEffectType()), left + 3, y + 1, 18, 18);
            String name = Text.translatable(e.getTranslationKey()).getString();
            if (e.getAmplifier() > 0) name += " " + (e.getAmplifier() + 1);
            ctx.drawTextWithShadow(tr, tr.trimToWidth(name, width - 28), left + 25, y + 2, Theme.TEXT);
            String time = e.isInfinite() ? "**:**" : StringHelper.formatTicks(e.getDuration(), mc.world.getTickManager().getTickRate());
            ctx.drawTextWithShadow(tr, time, left + 25, y + 11, expiring ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
            y += 22;
        }
    }
}
