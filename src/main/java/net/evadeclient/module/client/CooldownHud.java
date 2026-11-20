package net.evadeclient.module.client;

import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Cooldowns;
import net.evadeclient.util.Targets;
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
 */
public class CooldownHud extends HudElementModule {
    private final BooleanSetting weapon = add(new BooleanSetting("Weapon Charge", true));
    private final BooleanSetting effects = add(new BooleanSetting("Effect Timers", true));
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
    private final Map<Identifier, ItemStack> iconCache = new LinkedHashMap<>();

    public CooldownHud() {
        super("Cooldowns", "Timers for pearls, wind charges, spears, gapples and more.", 4, 16);
        width = 130;
        setEnabled(true);
    }

    @Override
    public void onTick() {
        Map<Identifier, Cooldowns.Cooldown> active = Cooldowns.active();
        for (Identifier id : lastActive) {
            if (!active.containsKey(id)) {
                justReady.put(id, System.currentTimeMillis());
                if (readySound.get()) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.5f, 2f);
            }
        }
        lastActive = Set.copyOf(active.keySet());
        justReady.values().removeIf(t -> System.currentTimeMillis() - t > 1200);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        List<Row> rows = new ArrayList<>();

        for (Cooldowns.Cooldown cd : Cooldowns.active().values()) {
            ItemStack icon = iconFor(cd.group());
            rows.add(new Row(icon, icon.getName().getString(), cd.remaining(), cd.total(), ColorUtil.ACCENT));
        }
        if (readyFlash.get()) {
            for (Identifier id : justReady.keySet()) {
                ItemStack icon = iconFor(id);
                rows.add(new Row(icon, icon.getName().getString(), 0, 1, 0xFF55FF55));
            }
        }

        if (weapon.get()) {
            ItemStack held = mc.player.getMainHandStack();
            float progress = mc.player.getAttackCooldownProgress(0f);
            if (Targets.isWeapon(held) && progress < 1f) {
                float perTick = mc.player.getAttackCooldownProgressPerTick();
                int total = Math.max(1, Math.round(perTick));
                int remaining = Math.max(1, Math.round((1f - progress) * perTick));
                rows.add(new Row(held, "Charge", remaining, total, 0xFFFFFFFF));
            }
        }

        if (effects.get()) {
            for (EffectTimer et : EFFECTS) {
                StatusEffectInstance inst = mc.player.getStatusEffect(et.effect);
                if (inst != null && !inst.isInfinite()) {
                    rows.add(new Row(new ItemStack(et.icon), et.name, inst.getDuration(), Math.max(inst.getDuration(), 20 * 120), et.color));
                }
            }
        }

        if (rows.isEmpty()) {
            if (!preview) return;
            rows.add(new Row(new ItemStack(Items.ENDER_PEARL), "Ender Pearl", 14, 20, ColorUtil.ACCENT));
            rows.add(new Row(new ItemStack(Items.WIND_CHARGE), "Wind Charge", 6, 10, ColorUtil.ACCENT));
        }

        var tr = mc.textRenderer;
        width = 130;
        height = rows.size() * 20 + 3;
        HudDraw.panel(ctx, left, top, width, height);
        int y = top + 3;
        for (Row r : rows) {
            ctx.drawItem(r.icon, left + 3, y);
            boolean ready = r.remaining == 0;
            String label = ready ? "§aREADY" : HudDraw.seconds(r.remaining);
            String name = tr.trimToWidth(r.name, width - 30 - tr.getWidth(label));
            ctx.drawTextWithShadow(tr, name, left + 22, y + 1, ColorUtil.TEXT);
            ctx.drawTextWithShadow(tr, label, left + width - 4 - tr.getWidth(ready ? "READY" : label), y + 1, ColorUtil.TEXT);
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
