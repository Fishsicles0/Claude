package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.OminousBottleAmplifierComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.component.type.SuspiciousStewEffectsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.HeldItemContext;
import net.minecraft.util.math.MathHelper;

/**
 * Gives potions (and tipped arrows, optionally stews and ominous bottles) the enchantment shine, so they stand out in
 * your inventory and hotbar. Filters pick which ones (kind, effect type, minimum level) and where they shine; optional
 * potion-coloured slot tints and small level / duration badges make them readable at a glance. Purely cosmetic: it
 * only reads the item's own data, which the game already shows in its tooltip.
 */
public class ShinyPots extends Module {
    private static ShinyPots INSTANCE;

    public final BooleanSetting drinkable = add(new BooleanSetting("Drinkable", true)
            .describe("Potions you drink."));
    public final BooleanSetting splash = add(new BooleanSetting("Splash", true)
            .describe("Splash potions."));
    public final BooleanSetting lingering = add(new BooleanSetting("Lingering", true)
            .describe("Lingering potions."));
    public final BooleanSetting tippedArrows = add(new BooleanSetting("Tipped Arrows", true)
            .describe("Arrows tipped with a potion."));
    public final BooleanSetting stewsAndBottles = add(new BooleanSetting("Stews & Ominous Bottles", false)
            .describe("Suspicious stews and ominous bottles too. A stew keeps its effect secret, so it only shines with Effect Type Any and Min Level 1, and never gets a tint or badge."));
    public final ModeSetting effectType = add(new ModeSetting("Effect Type", "Any", "Any", "Beneficial", "Harmful")
            .describe("Only items with at least one effect of this kind shine."));
    public final NumberSetting minLevel = add(new NumberSetting("Min Level", 1, 1, 5, 1)
            .describe("Only items with an effect at this level or higher shine (2 = Strength II and up)."));
    public final ModeSetting showOn = add(new ModeSetting("Show On", "Everywhere", "Everywhere", "Inventory", "Inventory+Hand")
            .describe("Where the shine shows: everywhere (also on the ground and in item frames), only in inventories and the hotbar, or there and in your own hands."));
    public final BooleanSetting slotTint = add(new BooleanSetting("Slot Tint", false)
            .describe("Colours the slot behind each potion in its potion colour."));
    public final ModeSetting tintStyle = add(new ModeSetting("Tint Style", "Fill", "Fill", "Outline", "Underline")
            .describe("Fill the slot, outline it, or draw a line under the item."));
    public final NumberSetting tintOpacity = add(new NumberSetting("Tint Opacity", 35, 5, 100, 5)
            .describe("How strong the slot tint is, in percent."));
    public final BooleanSetting tintHotbar = add(new BooleanSetting("Tint Hotbar", true)
            .describe("Tints hotbar slots too, not only inventories."));
    public final ModeSetting badge = add(new ModeSetting("Badge", "Off", "Off", "Level", "Duration", "Both")
            .describe("Small text on potion slots: the level (II and up) in the top corner, the time left in the bottom one."));

    /** What the item renderer is drawing right now (ItemModelManagerMixin); null outside item rendering. */
    private static ItemDisplayContext renderCtx;
    private static HeldItemContext renderHeld;
    /** The outer values while item renders nest (a bundle drawing its selected item), restored on the way out. */
    private static final ItemDisplayContext[] SAVED_CTX = new ItemDisplayContext[16];
    private static final HeldItemContext[] SAVED_HELD = new HeldItemContext[16];
    private static int depth;

    /**
     * The effect data of a potion that passes the filters: its colour, highest level, longest time left in ticks
     * (already scaled for tipped arrows and lingering potions), whether any effect lasts forever, and whether its
     * effect is secret (suspicious stews: shine only, no tint or badge).
     */
    private record Match(int color, int level, int duration, boolean infinite, boolean secret) {
        static final Match SECRET = new Match(0, 1, 0, false, true);
    }

    public ShinyPots() {
        super("ShinyPots", "Makes potions glint, with optional slot tints and level and time badges.", Category.VISUALS);
        INSTANCE = this;
    }

    /** Whether the module is on (kept for callers that only need that). */
    public static boolean on() {
        ShinyPots s = INSTANCE;
        return s != null && s.isEnabled();
    }

    private static ShinyPots active() {
        ShinyPots s = INSTANCE;
        return s != null && s.isEnabled() ? s : null;
    }

    // ================================================================ glint (ItemStackMixin)

    /** Whether {@code stack} should glint here (it has no glint of its own); ItemStackMixin, RETURN of hasGlint. */
    public static boolean shouldGlint(ItemStack stack) {
        ShinyPots s = active();
        return s != null && s.showsHere() && s.match(stack) != null;
    }

    private boolean showsHere() {
        if (showOn.is("Everywhere") || !mc.isOnThread()) return true;
        ItemDisplayContext ctx = renderCtx;
        if (ctx == null || ctx == ItemDisplayContext.GUI) return true;
        if (!showOn.is("Inventory+Hand")) return false;
        boolean hand = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        HeldItemContext held = renderHeld;
        return hand && held != null && mc.player != null && held.getEntity() == mc.player;
    }

    /** ItemModelManagerMixin, HEAD of clearAndUpdate: an item is about to be prepared for drawing. */
    public static void enterRender(ItemDisplayContext ctx, HeldItemContext held) {
        if (!mc.isOnThread()) return;
        if (depth < SAVED_CTX.length) {
            SAVED_CTX[depth] = renderCtx;
            SAVED_HELD[depth] = renderHeld;
        }
        depth++;
        renderCtx = ctx;
        renderHeld = held;
    }

    /** ItemModelManagerMixin, RETURN of clearAndUpdate. */
    public static void exitRender() {
        if (!mc.isOnThread() || depth == 0) return;
        depth--;
        if (depth < SAVED_CTX.length) {
            renderCtx = SAVED_CTX[depth];
            renderHeld = SAVED_HELD[depth];
            SAVED_CTX[depth] = null;
            SAVED_HELD[depth] = null;
        }
    }

    // ================================================================ filters

    /** The effect data of {@code stack} if it is a potion-like item that passes every filter, else null. */
    private Match match(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item == Items.SUSPICIOUS_STEW) {
            if (!stewsAndBottles.get() || !effectType.is("Any") || minLevel.getInt() > 1) return null;
            SuspiciousStewEffectsComponent stew = stack.get(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS);
            return stew != null && !stew.effects().isEmpty() ? Match.SECRET : null;
        }
        if (item == Items.OMINOUS_BOTTLE) {
            if (!stewsAndBottles.get() || !categoryMatches(StatusEffectCategory.NEUTRAL)) return null;
            OminousBottleAmplifierComponent amp = stack.get(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER);
            int level = (amp == null ? 0 : amp.value()) + 1;
            if (level < minLevel.getInt()) return null;
            return new Match(0xFF000000 | StatusEffects.BAD_OMEN.value().getColor(), level, OminousBottleAmplifierComponent.DURATION, false, false);
        }
        PotionContentsComponent pc = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (pc == null || !pc.hasEffects() || !kindEnabled(item)) return null;

        float scale = stack.getOrDefault(DataComponentTypes.POTION_DURATION_SCALE, 1f);
        int level = 0, duration = 0;
        boolean infinite = false;
        int firstColor = 0;
        for (StatusEffectInstance e : pc.getEffects()) {
            StatusEffect type = e.getEffectType().value();
            if (firstColor == 0) firstColor = 0xFF000000 | type.getColor();
            if (!categoryMatches(type.getCategory()) || e.getAmplifier() + 1 < minLevel.getInt()) continue;
            level = Math.max(level, e.getAmplifier() + 1);
            if (type.isInstant()) continue;
            if (e.isInfinite()) infinite = true;
            else duration = Math.max(duration, MathHelper.floor(e.getDuration() * scale));
        }
        if (level == 0) return null;
        int color = pc.customColor().orElseGet(() -> PotionContentsComponent.mixColors(pc.getEffects()).orElse(0));
        if (color == 0) color = firstColor;
        return new Match(color, level, duration, infinite, false);
    }

    private boolean kindEnabled(Item item) {
        if (item == Items.SPLASH_POTION) return splash.get();
        if (item == Items.LINGERING_POTION) return lingering.get();
        if (item == Items.TIPPED_ARROW) return tippedArrows.get();
        return drinkable.get(); // potions, and anything else carrying potion contents
    }

    private boolean categoryMatches(StatusEffectCategory category) {
        return switch (effectType.get()) {
            case "Beneficial" -> category == StatusEffectCategory.BENEFICIAL;
            case "Harmful" -> category == StatusEffectCategory.HARMFUL;
            default -> true;
        };
    }

    // ================================================================ slot tint

    /** ShinyPotsSlotMixin: the potion colour behind an inventory slot's item. Uses the filters, not Show On. */
    public static void drawSlotTint(DrawContext ctx, ItemStack stack, int x, int y) {
        ShinyPots s = active();
        if (s != null && s.slotTint.get()) s.drawTint(ctx, stack, x, y);
    }

    /** ShinyPotsHotbarMixin: the same behind a hotbar item, when Tint Hotbar is on. */
    public static void drawHotbarTint(DrawContext ctx, ItemStack stack, int x, int y) {
        ShinyPots s = active();
        if (s != null && s.slotTint.get() && s.tintHotbar.get()) s.drawTint(ctx, stack, x, y);
    }

    private void drawTint(DrawContext ctx, ItemStack stack, int x, int y) {
        Match m = match(stack);
        if (m == null || m.secret()) return;
        int c = ColorUtil.withAlpha(m.color(), Math.round(tintOpacity.getFloat() * 255 / 100f));
        switch (tintStyle.get()) {
            case "Outline" -> ctx.drawStrokedRectangle(x, y, 16, 16, c);
            case "Underline" -> ctx.fill(x, y + 14, x + 16, y + 16, c);
            default -> ctx.fill(x, y, x + 16, y + 16, c);
        }
    }

    // ================================================================ badges

    /**
     * DrawContextMixin, TAIL of drawStackOverlay: half-size badges on a potion's slot, its level (II and up) top left
     * and its time left bottom left. Instant and endless effects get no time.
     */
    public static void drawBadge(DrawContext ctx, TextRenderer tr, ItemStack stack, int x, int y) {
        ShinyPots s = active();
        if (s == null || s.badge.is("Off") || stack.isEmpty()) return;
        Match m = s.match(stack);
        if (m == null || m.secret()) return;
        boolean both = s.badge.is("Both");
        String level = (both || s.badge.is("Level")) && m.level() >= 2 ? roman(m.level()) : null;
        String time = (both || s.badge.is("Duration")) && !m.infinite() && m.duration() > 0 ? duration(m.duration()) : null;
        if (level == null && time == null) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(0.5f, 0.5f);
        if (level != null) ctx.drawTextWithShadow(tr, level, 1, 1, 0xFFFFD866);
        if (time != null) ctx.drawTextWithShadow(tr, time, 1, 32 - tr.fontHeight, 0xFFFFFFFF);
        ctx.getMatrices().popMatrix();
    }

    /** m:ss below an hour, whole hours above ("1h"), at the world's tick rate. */
    private static String duration(int ticks) {
        float tickRate = mc.world != null ? mc.world.getTickManager().getTickRate() : 20f;
        int seconds = MathHelper.floor(ticks / Math.max(1f, tickRate));
        if (seconds >= 3600) return (seconds / 3600) + "h";
        return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(n);
        };
    }
}
