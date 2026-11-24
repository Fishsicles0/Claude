package net.evadeclient.module.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.StringHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Active potion effects with icon, level and time left. Flashes when about to expire. Effects the server marks as
 * hidden (no icon) stay hidden, as in vanilla.
 */
public class PotionHud extends HudElementModule {
    private final BooleanSetting hideVanilla = add(new BooleanSetting("Hide Vanilla", true)
            .describe("Hide the vanilla effect icons in the top-right while this widget is showing."));
    private static final RenderPipeline GUI = RenderPipelines.GUI_TEXTURED;

    private record Row(StatusEffectInstance effect, String name) {}

    /** The rows shown this tick, longest first (rebuilt once per tick, not every frame). */
    private List<Row> rows = List.of();
    private long rowsTick = -1;

    public PotionHud() {
        super("Potion Effects", "Active effects with time remaining.", -1, -140);
        setEnabled(true);
    }

    /**
     * Whether vanilla's own effect icons should be hidden: only while this widget is actually on screen (so effects
     * never vanish completely, e.g. under Only In Combat, Hide In Menus or the F3 screen).
     */
    public boolean hidesVanilla() {
        if (!isEnabled() || !hideVanilla.get() || !shouldShow()) return false;
        if (mc.debugHudEntryList != null && mc.debugHudEntryList.isF3Enabled()) return false;
        return onScreen();
    }

    /** Whether the widget's whole box fits on the screen at its resolved position. */
    private boolean onScreen() {
        int sw = HudDraw.screenWidth(), sh = HudDraw.screenHeight();
        if (sw <= 0 || sh <= 0) return true;
        int x = resolveX(sw), y = resolveY(sh);
        return x >= 0 && y >= 0 && x + scaledWidth() <= sw + 1 && y + scaledHeight() <= sh + 1;
    }

    /** At its default spot, the widget sits just above Keystrokes (when that is at its own default spot too). */
    @Override
    public int resolveY(int screenH) {
        if (atDefaultSpot() && EvadeClient.modules != null) {
            int top = stackedAbove(EvadeClient.modules.get(KeystrokesHud.class), screenH);
            if (top != Integer.MIN_VALUE) return top;
        }
        return super.resolveY(screenH);
    }

    private static String name(StatusEffectInstance e) {
        String name = I18n.translate(e.getTranslationKey());
        int amp = e.getAmplifier();
        if (amp <= 0) return name;
        // Roman numerals as vanilla writes them (II..X); plain numbers beyond.
        String key = "enchantment.level." + (amp + 1);
        return name + " " + (amp <= 9 && I18n.hasTranslation(key) ? I18n.translate(key) : String.valueOf(amp + 1));
    }

    private List<Row> rows(boolean preview) {
        long tick = EvadeClient.ticks;
        if (!preview && tick == rowsTick) return rows;
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (StatusEffectInstance e : mc.player.getStatusEffects()) if (e.shouldShowIcon()) effects.add(e);
        if (effects.isEmpty() && preview) {
            effects.add(new StatusEffectInstance(StatusEffects.SPEED, 20 * 95, 1));
            effects.add(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 30, 1));
        }
        effects.sort((a, b) -> {
            if (a.isInfinite() != b.isInfinite()) return a.isInfinite() ? -1 : 1;
            return Integer.compare(b.getDuration(), a.getDuration());
        });
        List<Row> out = new ArrayList<>(effects.size());
        for (StatusEffectInstance e : effects) out.add(new Row(e, name(e)));
        if (preview) return out;
        rows = out;
        rowsTick = tick;
        return out;
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        List<Row> list = rows(preview);
        var tr = mc.textRenderer;
        boolean smooth = smoothFont.get();
        width = 110;
        height = Math.max(12, list.size() * 22 + 2);
        if (list.isEmpty()) { empty = true; return; }
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int y = top + 2;
        float tickRate = mc.world == null ? 20f : mc.world.getTickManager().getTickRate();
        for (Row r : list) {
            StatusEffectInstance e = r.effect;
            boolean expiring = !e.isInfinite() && e.getDuration() < 200;
            boolean blinkOff = expiring && (System.currentTimeMillis() / 250) % 2 == 0;
            if (!blinkOff) ctx.drawGuiTexture(GUI, InGameHud.getEffectTexture(e.getEffectType()), left + 3, y + 1, 18, 18);
            ctx.drawTextWithShadow(tr, HudDraw.styled(HudDraw.trim(r.name, width - 28, smooth), smooth), left + 25, y + 2, Theme.TEXT);
            String time = e.isInfinite() ? "**:**" : StringHelper.formatTicks(e.getDuration(), tickRate);
            ctx.drawTextWithShadow(tr, HudDraw.styled(time, smooth), left + 25, y + 11, expiring ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
            y += 22;
        }
    }
}
