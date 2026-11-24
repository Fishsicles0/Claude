package net.evadeclient.module.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.api.ApiCooldowns;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.hud.PotionHud;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Cooldowns;
import net.evadeclient.util.Targets;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Timers for everything with a cooldown: ender pearls, wind charges, spears, chorus fruit,
 * shields disabled by an axe, goat horns, server-imposed gapple cooldowns, your weapon charge,
 * gapple/potion effects, and cooldowns the server adds through the Evade Server API. Shows "READY" and can ping when something comes off cooldown, and can write the
 * time left on hotbar items.
 *
 * <p>Sits centred under the Direction HUD by default (below any boss bars), close to where you're looking.
 */
public class CooldownHud extends HudElementModule {
    private static final int DEFAULT_Y = 44;
    private static final int READY_COLOR = 0xFF55FF55, EXPIRING_COLOR = 0xFFFF5555;
    private static final Identifier ENDER_PEARL = Identifier.ofVanilla("ender_pearl"), WIND_CHARGE = Identifier.ofVanilla("wind_charge");

    /** The registered instance, for the hotbar hook (one per client). */
    private static CooldownHud instance;

    private final ModeSetting style = add(new ModeSetting("Style", "Rows", "Rows", "Compact", "Icons")
            .describe("Rows: icon, name, time and a bar. Compact: icon, time and a bar. Icons: a strip of icons with the time under each."));
    private final NumberSetting rowWidth = add(new NumberSetting("Width", 130, 80, 220, 5)
            .describe("Width of the panel in the Rows style."));
    private final ModeSetting timeFormat = add(new ModeSetting("Time Format", "Decimal", HudDraw.TIME_FORMATS)
            .describe("Decimal (12.5s), Whole seconds (13s), Clock (0:13) or game ticks (250t)."));
    private final ModeSetting sort = add(new ModeSetting("Sort", "Time Left", "Time Left", "Longest First", "Fixed")
            .describe("Time Left puts what's ready soonest on top, Longest First the opposite, Fixed keeps a set order. READY always goes last."));
    private final ModeSetting barColor = add(new ModeSetting("Bar Color", "Per Type", "Per Type", "Accent", "Fade")
            .describe("Per Type gives each kind of cooldown and effect its own colour, Accent uses the theme colour, Fade goes from red to green as it runs out."));
    private final BooleanSetting weapon = add(new BooleanSetting("Weapon Charge", true));
    private final ModeSetting effects = add(new ModeSetting("Effect Timers", "PvP Set", "PvP Set", "All Beneficial", "All", "Off")
            .describe("Timers for effects: the PvP set (gapple, regeneration, strength, speed, fire resistance, resistance), every good effect, every effect, or none. Hidden while Potion Effects is on, which already shows them."));
    private final BooleanSetting showAmplifier = add(new BooleanSetting("Show Amplifier", true)
            .describe("Add the effect's level to its name, e.g. Strength II."));
    private final NumberSetting expiringWarning = add(new NumberSetting("Expiring Warning", 3, 0, 10, 1)
            .describe("Effects with this many seconds or fewer left blink red. 0 turns it off."));
    private final BooleanSetting readyFlash = add(new BooleanSetting("Ready Flash", true));
    private final NumberSetting readyFlashTime = add(new NumberSetting("Ready Flash Time", 1.2, 0.3, 5.0, 0.1)
            .describe("Seconds READY stays up after something comes off cooldown."));
    private final ModeSetting readySound = add(new ModeSetting("Ready Sound", "Pling", "Pling", "Bell", "Orb", "Click", "Off")
            .describe("Sound played when something comes off cooldown."));
    private final NumberSetting soundVolume = add(new NumberSetting("Sound Volume", 50, 0, 100, 5));
    private final ModeSetting alertFor = add(new ModeSetting("Alert For", "All", "All", "Held Item", "Pearls & Wind Charges")
            .describe("Which cooldowns play the ready sound: all of them, only what's in your hands, or only ender pearls and wind charges."));
    private final NumberSetting minAlertLength = add(new NumberSetting("Min Alert Length", 1.0, 0, 10, 0.5)
            .describe("Cooldowns shorter than this many seconds never play the sound or flash READY."));
    private final BooleanSetting hotbarTimer = add(new BooleanSetting("Hotbar Timer", false)
            .describe("Write the time left on hotbar items while they're on cooldown."));
    private final ColorSetting hotbarTimerColor = add(new ColorSetting("Hotbar Timer Color", 0xFFFFFFFF));

    /**
     * One line of the widget. {@code sprite} is an effect drawn with its effect icon (else {@code icon} is drawn);
     * {@code rank} orders the Fixed sort; {@code expiring} rows blink red.
     */
    private record Row(ItemStack icon, @Nullable RegistryEntry<StatusEffect> sprite, String name, int remaining,
                       int total, int color, int rank, boolean expiring) {
        boolean ready() { return remaining == 0; }
    }

    private record EffectTimer(RegistryEntry<StatusEffect> effect, Item icon, String name, int color) {}

    /** The PvP set, drawn with item icons and short names. */
    private static final List<EffectTimer> EFFECTS = List.of(
            new EffectTimer(StatusEffects.ABSORPTION, Items.GOLDEN_APPLE, "Gapple", 0xFFFFC000),
            new EffectTimer(StatusEffects.REGENERATION, Items.GHAST_TEAR, "Regen", 0xFFFF6FA8),
            new EffectTimer(StatusEffects.STRENGTH, Items.BLAZE_POWDER, "Strength", 0xFFFF5040),
            new EffectTimer(StatusEffects.SPEED, Items.SUGAR, "Speed", 0xFF7FD4FF),
            new EffectTimer(StatusEffects.FIRE_RESISTANCE, Items.MAGMA_CREAM, "Fire Res", 0xFFFF9A3C),
            new EffectTimer(StatusEffects.RESISTANCE, Items.SHIELD, "Resist", 0xFFB0B0C0));

    /** Bar colours of well-known cooldowns for Bar Color: Per Type (others use the accent). */
    private static final Map<Identifier, Integer> TYPE_COLORS = Map.of(
            ENDER_PEARL, 0xFF2FD3A0,
            WIND_CHARGE, 0xFFB8D4FF,
            Identifier.ofVanilla("chorus_fruit"), 0xFFB57EDC,
            Identifier.ofVanilla("shield"), 0xFFB0B0C0,
            Identifier.ofVanilla("goat_horn"), 0xFFD9B26B,
            Identifier.ofVanilla("golden_apple"), 0xFFFFC000,
            Identifier.ofVanilla("enchanted_golden_apple"), 0xFFFF8AF0,
            Identifier.ofVanilla("totem_of_undying"), 0xFFF2D45C);

    /** Recently finished cooldowns -> time they finished, for the READY flash. */
    private final Map<Identifier, Long> justReady = new HashMap<>();
    /** Each cooldown active last tick and its full length in ticks, to spot the ones that just finished. */
    private Map<Identifier, Integer> lastTotals = Map.of();
    /** The player the state here belongs to; a new one (respawn, dimension change, new server) starts clean. */
    private ClientPlayerEntity lastPlayer;
    private final Map<Identifier, ItemStack> iconCache = new LinkedHashMap<>();
    /** Each effect's longest duration since it was (re)applied, so its bar runs from full to empty. */
    private final Map<RegistryEntry<StatusEffect>, Integer> maxSeen = new HashMap<>();
    private final Map<RegistryEntry<StatusEffect>, Integer> effectLast = new HashMap<>();

    public CooldownHud() {
        super("Cooldowns", "Timers for pearls, wind charges, spears, gapples and more.", CENTER, DEFAULT_Y);
        width = 130;
        setEnabled(true);
        instance = this;
    }

    /** Old configs: Ready Sound and Effect Timers were on/off switches; off becomes Off, on keeps the default. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        migrateSwitch(settings, readySound);
        migrateSwitch(settings, effects);
    }

    private static void migrateSwitch(JsonObject settings, ModeSetting mode) {
        JsonElement v = settings.get(mode.getName());
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) return;
        mode.set(v.getAsBoolean() ? mode.getDefault() : "Off");
    }

    /** At its default spot the widget moves down with the Direction HUD while boss bars are on screen. */
    @Override
    public int resolveY(int screenH) {
        if (atDefaultSpot()) return clampY(y + HudDraw.bossBarBottom(), screenH);
        return super.resolveY(screenH);
    }

    @Override
    protected void onEnable() { forget(); }

    @Override
    protected void onDisable() { forget(); }

    private void forget() {
        lastTotals = Map.of();
        justReady.clear();
        lastPlayer = null;
        iconCache.clear();
        maxSeen.clear();
        effectLast.clear();
    }

    // ---- tick: ready alerts and effect lengths ----------------------------------------------------------------

    @Override
    public void onTick() {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        Map<Identifier, Cooldowns.Cooldown> active = Cooldowns.activeCached();
        if (p != lastPlayer) {
            // A new player entity: cooldowns that vanished went with the old one, they didn't finish.
            lastPlayer = p;
            lastTotals = totals(active);
            justReady.clear();
            iconCache.clear();
            maxSeen.clear();
            effectLast.clear();
            trackEffects(p);
            return;
        }
        long now = System.currentTimeMillis();
        int minTicks = Math.round(minAlertLength.getFloat() * 20);
        boolean sound = false;
        for (Map.Entry<Identifier, Integer> e : lastTotals.entrySet()) {
            Identifier id = e.getKey();
            if (active.containsKey(id) || e.getValue() < minTicks) continue;
            justReady.put(id, now);
            sound |= alertsFor(p, id);
        }
        if (sound) playReadySound();
        justReady.keySet().removeAll(active.keySet()); // used again already: the new cooldown replaces READY
        lastTotals = totals(active);
        long flashMs = Math.round(readyFlashTime.get() * 1000);
        justReady.values().removeIf(t -> now - t > flashMs);
        trackEffects(p);
    }

    private static Map<Identifier, Integer> totals(Map<Identifier, Cooldowns.Cooldown> active) {
        Map<Identifier, Integer> out = new HashMap<>();
        for (Cooldowns.Cooldown cd : active.values()) out.put(cd.group(), cd.total());
        return out;
    }

    /** Whether Alert For covers the cooldown {@code id}. */
    private boolean alertsFor(ClientPlayerEntity p, Identifier id) {
        return switch (alertFor.get()) {
            case "Held Item" -> {
                var mgr = p.getItemCooldownManager();
                ItemStack main = p.getMainHandStack(), off = p.getOffHandStack();
                yield (!main.isEmpty() && id.equals(mgr.getGroup(main))) || (!off.isEmpty() && id.equals(mgr.getGroup(off)));
            }
            case "Pearls & Wind Charges" -> id.equals(ENDER_PEARL) || id.equals(WIND_CHARGE);
            default -> true;
        };
    }

    private void playReadySound() {
        float volume = soundVolume.getFloat() / 100f;
        if (volume <= 0) return;
        SoundEvent event;
        float pitch;
        switch (readySound.get()) {
            case "Off" -> { return; }
            case "Bell" -> { event = SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(); pitch = 1.2f; }
            case "Orb" -> { event = SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP; pitch = 1.3f; }
            case "Click" -> { event = SoundEvents.UI_BUTTON_CLICK.value(); pitch = 1.4f; }
            default -> { event = SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(); pitch = 2f; }
        }
        mc.getSoundManager().play(PositionedSoundInstance.ui(event, pitch, volume));
    }

    /** Remembers each timed effect's longest duration since it was applied (re-applying or extending resets it). */
    private void trackEffects(ClientPlayerEntity p) {
        Set<RegistryEntry<StatusEffect>> seen = new HashSet<>();
        for (StatusEffectInstance inst : p.getStatusEffects()) {
            if (inst.isInfinite()) continue;
            RegistryEntry<StatusEffect> type = inst.getEffectType();
            seen.add(type);
            int d = inst.getDuration();
            Integer last = effectLast.get(type);
            if (last == null || d > last) maxSeen.put(type, d); // new, or re-applied / extended
            effectLast.put(type, d);
        }
        maxSeen.keySet().retainAll(seen);
        effectLast.keySet().retainAll(seen);
    }

    private boolean showEffects() {
        if (effects.is("Off")) return false;
        PotionHud potions = EvadeClient.modules == null ? null : EvadeClient.modules.get(PotionHud.class);
        return potions == null || !potions.isEnabled();
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    private List<Row> rows(ClientPlayerEntity p, boolean preview) {
        List<Row> rows = new ArrayList<>();
        Map<Identifier, Cooldowns.Cooldown> active = Cooldowns.activeCached();
        List<Identifier> order = new ArrayList<>(active.keySet());
        order.sort(Comparator.comparing(Identifier::toString));
        for (int i = 0; i < order.size(); i++) {
            Cooldowns.Cooldown cd = active.get(order.get(i));
            ItemStack icon = iconFor(cd.group());
            rows.add(new Row(icon, null, icon.getName().getString(), cd.remaining(), cd.total(),
                    TYPE_COLORS.getOrDefault(cd.group(), Theme.RED), i, false));
        }
        // Cooldowns the server added through the Evade Server API, after the item cooldowns.
        List<ApiCooldowns.ServerCooldown> server = ApiCooldowns.active();
        long now = System.currentTimeMillis();
        for (int i = 0; i < server.size(); i++) {
            ApiCooldowns.ServerCooldown cd = server.get(i);
            int remaining = cd.remainingTicks(now);
            if (remaining <= 0) continue;
            rows.add(new Row(cd.icon(), null, cd.name(), remaining, cd.totalTicks(), Theme.RED, 5_000 + i, false));
        }
        if (readyFlash.get()) {
            List<Identifier> ready = new ArrayList<>(justReady.keySet());
            ready.sort(Comparator.comparing(Identifier::toString));
            for (int i = 0; i < ready.size(); i++) {
                ItemStack icon = iconFor(ready.get(i));
                rows.add(new Row(icon, null, icon.getName().getString(), 0, 1, READY_COLOR, i, false));
            }
        }

        if (weapon.get()) {
            ItemStack held = p.getMainHandStack();
            float progress = p.getAttackCooldownProgress(0f);
            if (Targets.isWeapon(held) && progress < 1f) {
                float perTick = p.getAttackCooldownProgressPerTick();
                int total = Math.max(1, Math.round(perTick));
                int remaining = Math.max(1, Math.round((1f - progress) * perTick));
                rows.add(new Row(held, null, "Charge", remaining, total, 0xFFFFFFFF, 10_000, false));
            }
        }

        if (showEffects()) addEffects(p, rows);

        if (rows.isEmpty() && preview) {
            rows.add(new Row(new ItemStack(Items.ENDER_PEARL), null, "Ender Pearl", 14, 20, TYPE_COLORS.get(ENDER_PEARL), 0, false));
            rows.add(new Row(new ItemStack(Items.WIND_CHARGE), null, "Wind Charge", 6, 10, TYPE_COLORS.get(WIND_CHARGE), 1, false));
        }

        Comparator<Row> by = switch (sort.get()) {
            case "Longest First" -> Comparator.comparingInt((Row r) -> -r.remaining);
            case "Fixed" -> Comparator.comparingInt(Row::rank);
            default -> Comparator.comparingInt(Row::remaining);
        };
        rows.sort(Comparator.comparing(Row::ready).thenComparing(by).thenComparingInt(Row::rank));
        return rows;
    }

    /** The PvP set first (item icons, short names), then, unless Effect Timers is PvP Set, the other effects. */
    private void addEffects(ClientPlayerEntity p, List<Row> rows) {
        int warnTicks = expiringWarning.getInt() * 20;
        Set<RegistryEntry<StatusEffect>> pvp = new HashSet<>();
        for (int i = 0; i < EFFECTS.size(); i++) {
            EffectTimer et = EFFECTS.get(i);
            pvp.add(et.effect);
            StatusEffectInstance inst = p.getStatusEffect(et.effect);
            if (inst == null || inst.isInfinite()) continue;
            rows.add(effectRow(inst, new ItemStack(et.icon), null, et.name, et.color, 20_000 + i, warnTicks));
        }
        String mode = effects.get();
        if (mode.equals("PvP Set")) return;
        for (StatusEffectInstance inst : p.getStatusEffects()) {
            RegistryEntry<StatusEffect> type = inst.getEffectType();
            if (pvp.contains(type) || inst.isInfinite() || !inst.shouldShowIcon()) continue;
            if (mode.equals("All Beneficial") && !type.value().isBeneficial()) continue;
            String name = I18n.translate(inst.getTranslationKey());
            int color = 0xFF000000 | type.value().getColor();
            rows.add(effectRow(inst, ItemStack.EMPTY, type, name, color, 30_000 + Registries.STATUS_EFFECT.getRawId(type.value()), warnTicks));
        }
    }

    private Row effectRow(StatusEffectInstance inst, ItemStack icon, @Nullable RegistryEntry<StatusEffect> sprite, String name,
                          int color, int rank, int warnTicks) {
        int d = inst.getDuration();
        int total = Math.max(1, Math.max(d, maxSeen.getOrDefault(inst.getEffectType(), d)));
        return new Row(icon, sprite, withLevel(name, inst), d, total, color, rank, warnTicks > 0 && d <= warnTicks);
    }

    /** "Strength II" with Show Amplifier (Roman numerals as vanilla writes them, plain numbers past X). */
    private String withLevel(String name, StatusEffectInstance inst) {
        int amp = inst.getAmplifier();
        if (!showAmplifier.get() || amp <= 0) return name;
        String key = "enchantment.level." + (amp + 1);
        return name + " " + (amp <= 9 && I18n.hasTranslation(key) ? I18n.translate(key) : String.valueOf(amp + 1));
    }

    /** Test hook: each row the widget would show right now as "name: time", in order. */
    public List<String> rowsForTest() {
        var p = mc.player;
        if (p == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Row r : rows(p, false)) out.add(r.name + ": " + label(r));
        return out;
    }

    // ---- rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var p = mc.player;
        if (p == null) { empty = true; return; }
        List<Row> rows = rows(p, preview);
        if (rows.isEmpty()) { empty = true; return; }

        boolean smooth = smoothFont.get();
        float partial = mc.getRenderTickCounter().getTickProgress(true);
        boolean blinkOff = (System.currentTimeMillis() / 250) % 2 == 0;
        switch (style.get()) {
            case "Compact" -> renderCompact(ctx, left, top, rows, smooth, partial, blinkOff);
            case "Icons" -> renderIcons(ctx, left, top, rows, smooth, partial, blinkOff);
            default -> renderRows(ctx, left, top, rows, smooth, partial, blinkOff);
        }
    }

    private void renderRows(DrawContext ctx, int left, int top, List<Row> rows, boolean smooth, float partial, boolean blinkOff) {
        var tr = mc.textRenderer;
        width = rowWidth.getInt();
        height = rows.size() * 20 + 3;
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);
        int y = top + 3;
        for (Row r : rows) {
            boolean dim = r.expiring && blinkOff;
            if (!dim) drawIcon(ctx, r, left + 3, y);
            Text label = HudDraw.styled(label(r), smooth);
            int lw = tr.getWidth(label);
            Text name = HudDraw.styled(HudDraw.trim(r.name, width - 30 - lw, smooth), smooth);
            ctx.drawTextWithShadow(tr, name, left + 22, y + 1, r.ready() ? ColorUtil.TEXT : textColor(r, ColorUtil.TEXT, dim));
            ctx.drawTextWithShadow(tr, label, left + width - 4 - lw, y + 1, textColor(r, ColorUtil.TEXT, dim));
            if (!r.ready()) HudDraw.bar(ctx, left + 22, y + 12, width - 26, 3, fraction(r, partial), barColor(r, partial));
            y += 20;
        }
    }

    /** Icon, time and bar, no name; as wide as the longest time. */
    private void renderCompact(DrawContext ctx, int left, int top, List<Row> rows, boolean smooth, float partial, boolean blinkOff) {
        var tr = mc.textRenderer;
        int textW = 0;
        for (Row r : rows) textW = Math.max(textW, HudDraw.width(stable(label(r)), smooth));
        width = 22 + Math.max(textW, 20) + 4;
        height = rows.size() * 18 + 4;
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);
        int y = top + 3;
        for (Row r : rows) {
            boolean dim = r.expiring && blinkOff;
            if (!dim) drawIcon(ctx, r, left + 3, y);
            Text label = HudDraw.styled(label(r), smooth);
            int lw = tr.getWidth(label);
            ctx.drawTextWithShadow(tr, label, left + width - 4 - lw, y + 2, textColor(r, ColorUtil.TEXT, dim));
            if (!r.ready()) HudDraw.bar(ctx, left + 22, y + 12, width - 26, 3, fraction(r, partial), barColor(r, partial));
            y += 18;
        }
    }

    /** A strip of icons with vanilla's white cooldown sweep and the time under each. */
    private void renderIcons(DrawContext ctx, int left, int top, List<Row> rows, boolean smooth, float partial, boolean blinkOff) {
        width = rows.size() * 18 + 4;
        height = 30;
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int x = left + 3 + i * 18, y = top + 3;
            boolean dim = r.expiring && blinkOff;
            if (!dim) drawIcon(ctx, r, x, y);
            if (!r.ready()) {
                // Same sweep as vanilla's hotbar: a translucent white block shrinking towards the bottom.
                float f = fraction(r, partial);
                int y0 = y + MathHelper.floor(16f * (1f - f));
                int y1 = y0 + MathHelper.ceil(16f * f);
                if (y1 > y0) ctx.fill(x, y0, x + 16, y1, 0x7FFFFFFF);
            }
            int color = textColor(r, ColorUtil.TEXT, dim);
            HudDraw.smallText(ctx, HudDraw.styled(label(r), smooth), x + 8, y + 18, color, 18);
        }
    }

    private void drawIcon(DrawContext ctx, Row r, int x, int y) {
        if (r.sprite != null) ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, InGameHud.getEffectTexture(r.sprite), x - 1, y - 1, 18, 18);
        else ctx.drawItem(r.icon, x, y);
    }

    private String label(Row r) {
        return r.ready() ? "READY" : HudDraw.time(r.remaining, timeFormat.get());
    }

    /** {@code s} with every digit as 0, so widths sized from it don't jump as the time counts down. */
    private static String stable(String s) {
        return s.replaceAll("[0-9]", "0");
    }

    private static int textColor(Row r, int normal, boolean dim) {
        int c = r.ready() ? READY_COLOR : r.expiring ? EXPIRING_COLOR : normal;
        return dim ? ColorUtil.withAlpha(c, 0x70) : c;
    }

    /** Share of the timer left, smoothed between ticks. */
    private static float fraction(Row r, float partial) {
        return MathHelper.clamp((r.remaining - partial) / (float) r.total, 0f, 1f);
    }

    private int barColor(Row r, float partial) {
        return switch (barColor.get()) {
            case "Accent" -> Theme.RED;
            case "Fade" -> ColorUtil.healthColor(1f - fraction(r, partial));
            default -> r.color;
        };
    }

    /** Item to show for a cooldown group: an item in your inventory using that group, else the item with that id. */
    private ItemStack iconFor(Identifier group) {
        ItemStack cached = iconCache.get(group);
        if (cached != null) return cached;
        var mgr = mc.player.getItemCooldownManager();
        for (int i = 0; i < 41; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && group.equals(mgr.getGroup(s))) {
                ItemStack icon = s.copyWithCount(1);
                iconCache.put(group, icon);
                return icon;
            }
        }
        ItemStack icon = Registries.ITEM.containsId(group) ? new ItemStack(Registries.ITEM.get(group)) : new ItemStack(Items.CLOCK);
        iconCache.put(group, icon);
        return icon;
    }

    // ---- hotbar ------------------------------------------------------------------------------------------------

    /**
     * Hotbar Timer: the time left (in Time Format) written small across a hotbar or off-hand item that is on
     * cooldown. Called by {@code CooldownHotbarMixin} after vanilla has drawn the slot.
     */
    public static void drawHotbarTimer(DrawContext ctx, PlayerEntity player, ItemStack stack, int x, int y) {
        CooldownHud m = instance;
        if (m == null || stack.isEmpty() || !m.isEnabled() || !m.hotbarTimer.get()) return;
        if (player == null || player != mc.player) return; // spectating someone: their cooldowns aren't known
        Cooldowns.Cooldown cd = Cooldowns.activeCached().get(player.getItemCooldownManager().getGroup(stack));
        if (cd == null) return;
        String text = HudDraw.time(cd.remaining(), m.timeFormat.get());
        var tr = mc.textRenderer;
        int w = tr.getWidth(text);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x + 8 - w * 0.375f, y + 5);
        ctx.getMatrices().scale(0.75f, 0.75f);
        ctx.drawTextWithShadow(tr, text, 0, 0, m.hotbarTimerColor.get());
        ctx.getMatrices().popMatrix();
    }
}
