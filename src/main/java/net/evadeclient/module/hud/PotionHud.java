package net.evadeclient.module.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.StringHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Active potion effects with icon, name, level and time left, as a list or a row. Sorts by time, name or helpful
 * first, can blink effects about to run out, colour them by effect or by helpful/harmful, hide permanent or beacon
 * effects, cap how many show, and shrink to one line each (Compact). Effects the server marks as hidden (no icon)
 * stay hidden, as in vanilla.
 */
public class PotionHud extends HudElementModule {
    private final BooleanSetting hideVanilla = add(new BooleanSetting("Hide Vanilla", true)
            .describe("Hide the vanilla effect icons in the top-right while this widget is showing."));
    private final ModeSetting layout = add(new ModeSetting("Layout", "Vertical", "Vertical", "Horizontal")
            .describe("Effects in a column, or side by side in a row."));
    private final ModeSetting sort = add(new ModeSetting("Sort", "Longest First", "Longest First", "Shortest First", "Name", "Helpful First")
            .describe("The order effects are listed in. Permanent effects count as the longest."));
    private final BooleanSetting compact = add(new BooleanSetting("Compact", false)
            .describe("One small line per effect: icon, name and time side by side."));
    private final BooleanSetting showIcon = add(new BooleanSetting("Show Icon", true)
            .describe("The effect's icon."));
    private final BooleanSetting showName = add(new BooleanSetting("Show Name", true)
            .describe("The effect's name."));
    private final BooleanSetting showLevel = add(new BooleanSetting("Show Level", true)
            .describe("The level after the name (II, III...). Level I is never written, as in vanilla."));
    private final ModeSetting levelStyle = add(new ModeSetting("Level Style", "Roman", "Roman", "Number")
            .describe("Write levels as Roman numerals (II) or numbers (2)."));
    private final BooleanSetting showDuration = add(new BooleanSetting("Show Duration", true)
            .describe("The time left. Permanent effects show **:**, as in vanilla."));
    private final BooleanSetting blink = add(new BooleanSetting("Blink When Expiring", true)
            .describe("Flash an effect's icon and turn its time red when it is about to run out."));
    private final NumberSetting blinkBelow = add(new NumberSetting("Blink Below", 10, 1, 60, 1)
            .describe("Seconds left at which an effect starts to blink."));
    private final ModeSetting colorBy = add(new ModeSetting("Name Color", "Theme", "Theme", "Effect", "Helpful/Harmful")
            .describe("Colour of the effect names: plain, the effect's own colour, or green for helpful and red for harmful."));
    private final BooleanSetting hideInfinite = add(new BooleanSetting("Hide Permanent", false)
            .describe("Leave out effects that never run out (e.g. from a server or a mod)."));
    private final BooleanSetting hideAmbient = add(new BooleanSetting("Hide Beacon Effects", false)
            .describe("Leave out effects from a beacon or a conduit."));
    private final NumberSetting maxShown = add(new NumberSetting("Max Shown", 0, 0, 20, 1)
            .describe("Show at most this many effects (the first ones in the sort order). 0 shows them all."));
    private static final RenderPipeline GUI = RenderPipelines.GUI_TEXTURED;

    private record Row(StatusEffectInstance effect, String name, String time, int nameW, int timeW) {}

    /** The rows shown this tick, sorted (rebuilt once per tick, not every frame). */
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

    /** "IV" for 4, up to 3999; plain digits beyond. */
    public static String roman(int n) {
        if (n <= 0 || n >= 4000) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { sb.append(r[i]); n -= v[i]; }
        return sb.toString();
    }

    /** The name with its level as chosen (level I is left out, as vanilla does). */
    private String name(StatusEffectInstance e) {
        String name = showName.get() ? I18n.translate(e.getTranslationKey()) : "";
        int level = e.getAmplifier() + 1;
        if (!showLevel.get() || level <= 1) return name;
        String lv = levelStyle.is("Number") ? String.valueOf(level) : roman(level);
        return name.isEmpty() ? lv : name + " " + lv;
    }

    private Comparator<StatusEffectInstance> order() {
        // Permanent effects count as the longest.
        Comparator<StatusEffectInstance> longest = (a, b) -> {
            if (a.isInfinite() != b.isInfinite()) return a.isInfinite() ? -1 : 1;
            return Integer.compare(b.getDuration(), a.getDuration());
        };
        return switch (sort.get()) {
            case "Shortest First" -> longest.reversed();
            case "Name" -> Comparator.comparing((StatusEffectInstance e) -> I18n.translate(e.getTranslationKey()))
                    .thenComparing(longest);
            case "Helpful First" -> Comparator.comparingInt((StatusEffectInstance e) -> rank(e.getEffectType().value().getCategory()))
                    .thenComparing(longest);
            default -> longest;
        };
    }

    private static int rank(StatusEffectCategory c) {
        return c == StatusEffectCategory.BENEFICIAL ? 0 : c == StatusEffectCategory.NEUTRAL ? 1 : 2;
    }

    private List<Row> rows(boolean preview) {
        long tick = EvadeClient.ticks;
        if (!preview && tick == rowsTick) return rows;
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (StatusEffectInstance e : mc.player.getStatusEffects()) {
            if (!e.shouldShowIcon()) continue;
            if (hideInfinite.get() && e.isInfinite()) continue;
            if (hideAmbient.get() && e.isAmbient()) continue;
            effects.add(e);
        }
        if (effects.isEmpty() && preview) {
            effects.add(new StatusEffectInstance(StatusEffects.SPEED, 20 * 95, 1));
            effects.add(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 30, 1));
            effects.add(new StatusEffectInstance(StatusEffects.POISON, 20 * 6, 0));
        }
        effects.sort(order());
        int max = maxShown.getInt();
        if (max > 0 && effects.size() > max) effects = new ArrayList<>(effects.subList(0, max));
        float tickRate = mc.world == null ? 20f : mc.world.getTickManager().getTickRate();
        boolean smooth = smoothFont.get();
        List<Row> out = new ArrayList<>(effects.size());
        for (StatusEffectInstance e : effects) {
            String n = name(e);
            String t = !showDuration.get() ? "" : e.isInfinite() ? "**:**" : StringHelper.formatTicks(e.getDuration(), tickRate);
            out.add(new Row(e, n, t, n.isEmpty() ? 0 : HudDraw.width(n, smooth), t.isEmpty() ? 0 : HudDraw.width(t, smooth)));
        }
        if (preview) return out;
        rows = out;
        rowsTick = tick;
        return out;
    }

    /** Whether an effect is about to run out (for Blink When Expiring). */
    private boolean expiring(StatusEffectInstance e) {
        return blink.get() && !e.isInfinite() && e.getDuration() < blinkBelow.getInt() * 20;
    }

    private int nameColor(StatusEffectInstance e) {
        var type = e.getEffectType().value();
        return switch (colorBy.get()) {
            case "Effect" -> Theme.lerpColor(0xFF000000 | type.getColor(), 0xFFFFFFFF, 0.25f); // lifted a little, so dark ones read
            case "Helpful/Harmful" -> switch (type.getCategory()) {
                case BENEFICIAL -> Theme.OK;
                case HARMFUL -> Theme.DANGER;
                default -> Theme.TEXT;
            };
            default -> Theme.TEXT;
        };
    }

    /** Size of one effect's tile: {w, h}. */
    private int[] tile(Row r) {
        boolean icon = showIcon.get();
        boolean hasName = r.nameW > 0, hasTime = r.timeW > 0;
        if (compact.get()) {
            int w = 3 + (icon ? 11 : 0);
            if (hasName) w += r.nameW + (hasTime ? 4 : 0);
            if (hasTime) w += r.timeW;
            return new int[]{Math.max(w + 3, icon ? 16 : 12), 12};
        }
        int textW = Math.max(r.nameW, r.timeW);
        int w = (icon ? 25 : 4) + textW + (textW > 0 ? 4 : 0);
        int h = icon ? 22 : (hasName && hasTime ? 22 : 12);
        return new int[]{Math.max(w, icon ? 24 : 12), h};
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        List<Row> list = rows(preview);
        if (list.isEmpty()) {
            width = 110;
            height = 12;
            empty = true;
            return;
        }
        boolean horizontal = layout.is("Horizontal");
        int[][] sizes = new int[list.size()][];
        int w = 0, h = 0;
        for (int i = 0; i < list.size(); i++) {
            sizes[i] = tile(list.get(i));
            if (horizontal) { w += sizes[i][0] + (i > 0 ? 2 : 0); h = Math.max(h, sizes[i][1]); }
            else { w = Math.max(w, sizes[i][0]); h += sizes[i][1]; }
        }
        width = horizontal ? w : Math.max(w, compact.get() ? 40 : 60);
        height = h + (horizontal ? 0 : 2);
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int x = left, y = top + (horizontal ? 0 : 1);
        for (int i = 0; i < list.size(); i++) {
            drawTile(ctx, list.get(i), x, y, horizontal ? sizes[i][0] : width, sizes[i][1]);
            if (horizontal) x += sizes[i][0] + 2;
            else y += sizes[i][1];
        }
    }

    private void drawTile(DrawContext ctx, Row r, int x, int y, int w, int h) {
        var tr = mc.textRenderer;
        boolean smooth = smoothFont.get();
        StatusEffectInstance e = r.effect;
        boolean expiring = expiring(e);
        boolean blinkOff = expiring && (System.currentTimeMillis() / 250) % 2 == 0;
        int timeColor = expiring ? Theme.RED_BRIGHT : Theme.TEXT_DIM;
        boolean icon = showIcon.get();
        if (compact.get()) {
            int cx = x + 3;
            if (icon) {
                if (!blinkOff) ctx.drawGuiTexture(GUI, InGameHud.getEffectTexture(e.getEffectType()), cx, y + 1, 9, 9);
                cx += 11;
            }
            if (r.nameW > 0) {
                ctx.drawTextWithShadow(tr, HudDraw.styled(r.name, smooth), cx, y + 2, nameColor(e));
                cx += r.nameW + 4;
            }
            if (r.timeW > 0) ctx.drawTextWithShadow(tr, HudDraw.styled(r.time, smooth), cx, y + 2, timeColor);
            return;
        }
        int tx = x + (icon ? 25 : 4);
        if (icon && !blinkOff) ctx.drawGuiTexture(GUI, InGameHud.getEffectTexture(e.getEffectType()), x + 3, y + (h - 18) / 2, 18, 18);
        int maxW = Math.max(0, x + w - tx - 2);
        if (r.nameW > 0 && r.timeW > 0) {
            ctx.drawTextWithShadow(tr, HudDraw.styled(HudDraw.trim(r.name, maxW, smooth), smooth), tx, y + 2, nameColor(e));
            ctx.drawTextWithShadow(tr, HudDraw.styled(r.time, smooth), tx, y + 11, timeColor);
        } else if (r.nameW > 0) {
            ctx.drawTextWithShadow(tr, HudDraw.styled(HudDraw.trim(r.name, maxW, smooth), smooth), tx, y + (h - 8) / 2, nameColor(e));
        } else if (r.timeW > 0) {
            ctx.drawTextWithShadow(tr, HudDraw.styled(r.time, smooth), tx, y + (h - 8) / 2, timeColor);
        }
    }

    /** Test hook: the lines this widget would show right now, as "name|time", in order. */
    public List<String> rowsForTest() {
        List<String> out = new ArrayList<>();
        for (Row r : rows(true)) out.add(r.name + "|" + r.time);
        return out;
    }
}
