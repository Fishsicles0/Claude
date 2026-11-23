package net.evadeclient.module.client;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.hud.PotionHud;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Cooldowns;
import net.evadeclient.util.Targets;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Timers for everything with a cooldown: ender pearls, wind charges, spears, chorus fruit,
 * shields disabled by an axe, goat horns, server-imposed gapple cooldowns, your weapon charge,
 * and gapple/potion effects. Shows "READY" and can ping when something comes off cooldown.
 *
 * <p>Sits centred under the Direction HUD by default (below any boss bars), close to where you're looking.
 */
public class CooldownHud extends HudElementModule {
    private static final int DEFAULT_Y = 44;

    private final BooleanSetting weapon = add(new BooleanSetting("Weapon Charge", true));
    private final BooleanSetting effects = add(new BooleanSetting("Effect Timers", false)
            .describe("Timers for gapple, regeneration, strength and other effects. Hidden while Potion Effects is on, which already shows them."));
    private final BooleanSetting readyFlash = add(new BooleanSetting("Ready Flash", true));
    private final BooleanSetting readySound = add(new BooleanSetting("Ready Sound", true));

    private record Row(ItemStack icon, String name, int remaining, int total, int color) {}

    private record EffectTimer(RegistryEntry<StatusEffect> effect, Item icon, String name, int color) {}

    private static final List<EffectTimer> EFFECTS = List.of(
            new EffectTimer(StatusEffects.ABSORPTION, Items.GOLDEN_APPLE, "Gapple", 0xFFFFC000),
            new EffectTimer(StatusEffects.REGENERATION, Items.GHAST_TEAR, "Regen", 0xFFFF6FA8),
            new EffectTimer(StatusEffects.STRENGTH, Items.BLAZE_POWDER, "Strength", 0xFFFF5040),
            new EffectTimer(StatusEffects.SPEED, Items.SUGAR, "Speed", 0xFF7FD4FF),
            new EffectTimer(StatusEffects.FIRE_RESISTANCE, Items.MAGMA_CREAM, "Fire Res", 0xFFFF9A3C),
            new EffectTimer(StatusEffects.RESISTANCE, Items.SHIELD, "Resist", 0xFFB0B0C0));

    /** Recently finished cooldowns -> time they finished, for the READY flash. */
    private final Map<Identifier, Long> justReady = new HashMap<>();
    private Set<Identifier> lastActive = Set.of();
    /** The player the cooldowns above belong to; a new one (respawn, dimension change, new server) starts clean. */
    private ClientPlayerEntity lastPlayer;
    private final Map<Identifier, ItemStack> iconCache = new LinkedHashMap<>();
    /** Each tracked effect's duration when it was (re)applied, so its bar runs from full to empty. */
    private final Map<RegistryEntry<StatusEffect>, Integer> effectStart = new HashMap<>();
    private final Map<RegistryEntry<StatusEffect>, Integer> effectLast = new HashMap<>();

    public CooldownHud() {
        super("Cooldowns", "Timers for pearls, wind charges, spears, gapples and more.", CENTER, DEFAULT_Y);
        width = 130;
        setEnabled(true);
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
        lastActive = Set.of();
        justReady.clear();
        lastPlayer = null;
        effectStart.clear();
        effectLast.clear();
    }

    @Override
    public void onTick() {
        Map<Identifier, Cooldowns.Cooldown> active = Cooldowns.active();
        if (mc.player != lastPlayer) {
            // A new player entity: cooldowns that vanished went with the old one, they didn't finish.
            lastPlayer = mc.player;
            lastActive = Set.copyOf(active.keySet());
            justReady.clear();
            iconCache.clear();
            effectStart.clear();
            effectLast.clear();
            return;
        }
        for (Identifier id : lastActive) {
            if (!active.containsKey(id)) {
                justReady.put(id, System.currentTimeMillis());
                if (readySound.get()) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.5f, 2f);
            }
        }
        lastActive = Set.copyOf(active.keySet());
        justReady.values().removeIf(t -> System.currentTimeMillis() - t > 1200);

        for (EffectTimer et : EFFECTS) {
            StatusEffectInstance inst = mc.player.getStatusEffect(et.effect);
            if (inst == null || inst.isInfinite()) {
                effectStart.remove(et.effect);
                effectLast.remove(et.effect);
                continue;
            }
            int d = inst.getDuration();
            Integer last = effectLast.get(et.effect);
            if (last == null || d > last) effectStart.put(et.effect, d); // new, or re-applied / extended
            effectLast.put(et.effect, d);
        }
    }

    private boolean showEffects() {
        if (!effects.get()) return false;
        PotionHud potions = EvadeClient.modules == null ? null : EvadeClient.modules.get(PotionHud.class);
        return potions == null || !potions.isEnabled();
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        List<Row> rows = new ArrayList<>();
        var p = mc.player;
        if (p == null) { empty = true; return; }

        for (Cooldowns.Cooldown cd : Cooldowns.active().values()) {
            ItemStack icon = iconFor(cd.group());
            rows.add(new Row(icon, icon.getName().getString(), cd.remaining(), cd.total(), Theme.RED));
        }
        if (readyFlash.get()) {
            for (Identifier id : justReady.keySet()) {
                ItemStack icon = iconFor(id);
                rows.add(new Row(icon, icon.getName().getString(), 0, 1, 0xFF55FF55));
            }
        }

        if (weapon.get()) {
            ItemStack held = p.getMainHandStack();
            float progress = p.getAttackCooldownProgress(0f);
            if (Targets.isWeapon(held) && progress < 1f) {
                float perTick = p.getAttackCooldownProgressPerTick();
                int total = Math.max(1, Math.round(perTick));
                int remaining = Math.max(1, Math.round((1f - progress) * perTick));
                rows.add(new Row(held, "Charge", remaining, total, 0xFFFFFFFF));
            }
        }

        if (showEffects()) {
            for (EffectTimer et : EFFECTS) {
                StatusEffectInstance inst = p.getStatusEffect(et.effect);
                if (inst != null && !inst.isInfinite()) {
                    int d = inst.getDuration();
                    int total = Math.max(d, effectStart.getOrDefault(et.effect, d));
                    rows.add(new Row(new ItemStack(et.icon), et.name, d, Math.max(1, total), et.color));
                }
            }
        }

        if (rows.isEmpty()) {
            if (!preview) { empty = true; return; }
            rows.add(new Row(new ItemStack(Items.ENDER_PEARL), "Ender Pearl", 14, 20, Theme.RED));
            rows.add(new Row(new ItemStack(Items.WIND_CHARGE), "Wind Charge", 6, 10, Theme.RED));
        }

        var tr = mc.textRenderer;
        boolean smooth = smoothFont.get();
        width = 130;
        height = rows.size() * 20 + 3;
        if (background.get()) HudDraw.panel(ctx, left, top, width, height);
        int y = top + 3;
        for (Row r : rows) {
            ctx.drawItem(r.icon, left + 3, y);
            boolean ready = r.remaining == 0;
            Text label = HudDraw.styled(ready ? "READY" : HudDraw.seconds(r.remaining), smooth);
            int lw = tr.getWidth(label);
            Text name = HudDraw.styled(HudDraw.trim(r.name, width - 30 - lw, smooth), smooth);
            ctx.drawTextWithShadow(tr, name, left + 22, y + 1, ColorUtil.TEXT);
            ctx.drawTextWithShadow(tr, label, left + width - 4 - lw, y + 1, ready ? 0xFF55FF55 : ColorUtil.TEXT);
            if (!ready) HudDraw.bar(ctx, left + 22, y + 12, width - 26, 2, r.remaining / (float) r.total, r.color);
            y += 20;
        }
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
}
