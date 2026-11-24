package net.evadeclient.module.misc;

import com.google.gson.JsonObject;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;

/**
 * Warns you before a piece of armour, your elytra or the item in either hand breaks: once when it gets low, and once
 * more (a title and a lower sound) when it is about to go. Each item is warned about once per stage until it is
 * repaired or replaced: scrolling away from a worn tool and back again doesn't warn twice.
 */
public class Protection extends Module {
    private final ModeSetting thresholdMode = add(new ModeSetting("Threshold Mode", "Percent", "Percent", "Uses Left")
            .describe("Warn at a share of an item's durability, or at a number of uses left."));
    public final NumberSetting percent = add(new NumberSetting("Warn At %", 10, 2, 50, 1)
            .describe("Percent mode: warn once an item is down to this much of its durability."));
    private final NumberSetting warnUses = add(new NumberSetting("Warn At Uses", 25, 5, 200, 5)
            .describe("Uses Left mode: warn once an item has this many uses left (an elytra, seconds of flight)."));
    private final NumberSetting elytraPercent = add(new NumberSetting("Elytra Warn At %", 15, 2, 50, 1)
            .describe("Percent mode only: when to warn about an elytra, which wears down by itself as you fly. Uses "
                    + "Left mode warns about it at Warn At Uses like everything else."));
    private final BooleanSetting critical = add(new BooleanSetting("Critical Warning", true)
            .describe("A second warning, as a title with a lower sound, just before the item breaks."));
    private final NumberSetting criticalPercent = add(new NumberSetting("Critical At %", 3, 1, 10, 1)
            .describe("Percent mode: when the critical warning comes (always below the first one). Uses Left mode "
                    + "gives it at a fifth of Warn At Uses."));
    private final BooleanSetting armor = add(new BooleanSetting("Armor", true)
            .describe("Watch your helmet, chestplate or elytra, leggings and boots."));
    public final BooleanSetting mainhand = add(new BooleanSetting("Held Item", true)
            .describe("Watch the item in your main hand."));
    private final BooleanSetting offhand = add(new BooleanSetting("Offhand", true)
            .describe("Watch the item in your off hand, such as a shield."));
    private final BooleanSetting onlyEnchanted = add(new BooleanSetting("Only Enchanted", false)
            .describe("Only warn about enchanted items, the ones worth repairing."));
    private final ModeSetting alert = add(new ModeSetting("Alert", "Chat + Toast", AlertOutput.MODES)
            .describe("Where the first warning shows: a chat line, a toast, both, or a title. The critical warning is "
                    + "always a title."));
    private final ModeSetting sound = add(new ModeSetting("Sound", "Anvil", "Off", "Anvil", "Pling", "Bell")
            .describe("A sound with each warning (lower for the critical one)."));
    private final NumberSetting volume = add(new NumberSetting("Volume", 0.7, 0.1, 1.0, 0.1)
            .describe("How loud the warning sound is."));

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    /** Key for the off hand in {@link #stages}; armour uses the slot's ordinal, the main hand {@link #HOTBAR} + its slot. */
    private static final int OFFHAND = -1;
    private static final int HOTBAR = 100;
    private static final int WARNED = 1, CRITICAL = 2;

    /** What a slot held when it was warned about, and how far: {@link #WARNED} or {@link #CRITICAL}. */
    private record Stage(Item item, int stage) {}

    /**
     * Keyed by slot and item rather than by the stack object, since the server replaces the stack object every time
     * it loses durability.
     */
    private final Map<Integer, Stage> stages = new HashMap<>();
    /** The connection seen last: a new server or world starts afresh. */
    private Object session;

    public Protection() {
        super("Protection", "Warns you before your armour, elytra or held items break.", Category.MISC);
    }

    /** Held Item used to cover both hands: a config that turned it off keeps the off hand off too. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has(mainhand.getName()) && !settings.has(offhand.getName())) offhand.set(mainhand.get());
    }

    @Override
    protected void onDisable() {
        stages.clear();
        session = null;
    }

    /** How far an armour slot has been warned about: 0 not, 1 warned, 2 critical (for the tests). */
    public int stage(EquipmentSlot slot) {
        Stage s = stages.get(slot.ordinal());
        return s == null ? 0 : s.stage();
    }

    @Override
    public void onTick() {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        Object s = mc.getNetworkHandler();
        if (s != session) {
            session = s;
            stages.clear();
        }
        if (armor.get()) {
            for (EquipmentSlot slot : ARMOR) check(slot.ordinal(), p.getEquippedStack(slot));
        } else {
            stages.keySet().removeIf(k -> k >= 0 && k < HOTBAR);
        }
        if (mainhand.get()) check(HOTBAR + p.getInventory().getSelectedSlot(), p.getMainHandStack());
        else stages.keySet().removeIf(k -> k >= HOTBAR);
        if (offhand.get()) check(OFFHAND, p.getOffHandStack());
        else stages.remove(OFFHAND);
    }

    private void check(int key, ItemStack stack) {
        // Empty, unbreakable or not enchanted (with Only Enchanted): nothing to say, and nothing to forget (you may
        // just have scrolled past it or taken it off for a moment).
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) return;
        if (onlyEnchanted.get() && !stack.hasEnchantments()) return;
        int max = stack.getMaxDamage();
        // An elytra stops working with one point left, so that last point is not a use.
        boolean glider = stack.contains(DataComponentTypes.GLIDER);
        int usable = glider ? max - 1 : max;
        if (usable <= 0) return;
        int uses = Math.max(0, usable - stack.getDamage());
        float pct = 100f * uses / usable;

        boolean byUses = thresholdMode.is("Uses Left");
        double value = byUses ? uses : pct;
        double warnAt = byUses ? warnUses.get() : glider ? elytraPercent.get() : percent.get();
        double critAt = byUses ? Math.max(1, Math.floor(warnUses.get() / 5)) : Math.min(criticalPercent.get(), warnAt - 1);
        double rearm = byUses ? 10 : 5;
        double critRearm = byUses ? Math.max(2, Math.floor(critAt / 2)) : 2;

        Item item = stack.getItem();
        Stage old = stages.get(key);
        int stage = old != null && old.item() == item ? old.stage() : 0;
        int next;
        if (stack.getDamage() <= 0 || value > warnAt + rearm) next = 0; // new, repaired or replaced
        else if (critical.get() && value <= critAt) next = CRITICAL;
        else if (value <= warnAt) next = stage == CRITICAL && (!critical.get() || value > critAt + critRearm) ? WARNED : Math.max(stage, WARNED);
        else next = Math.min(stage, WARNED); // just above the warning: stay warned until clearly repaired

        if (next > stage) warn(stack, next == CRITICAL, pct, uses);
        if (next == 0) stages.remove(key);
        else stages.put(key, new Stage(item, next));
    }

    /** "Elytra 12% · 52 uses": the first warning through Alert, the critical one always as a red title. */
    private void warn(ItemStack stack, boolean crit, float pct, int uses) {
        String name = Formatting.strip(stack.getName().getString());
        String detail = Math.round(pct) + "% · " + uses + (uses == 1 ? " use" : " uses");
        Text subtitle = Text.literal(detail + " left").formatted(Formatting.GRAY);
        String mode = alert.get();
        if (crit) {
            AlertOutput.title(Text.literal("⚠ " + name).formatted(Formatting.RED), subtitle);
            if (AlertOutput.toChat(mode)) AlertOutput.chat(line(name, " is about to break: ", detail));
            AlertOutput.play(sound.get(), 0.8f, volume.getFloat());
            return;
        }
        if (AlertOutput.toChat(mode)) AlertOutput.chat(line(name, " is at ", detail));
        if (AlertOutput.toToast(mode)) Notifications.push("Low durability", name + " " + detail, false);
        if (AlertOutput.toTitle(mode)) AlertOutput.title(Text.literal("⚠ " + name).formatted(Formatting.GOLD), subtitle);
        AlertOutput.play(sound.get(), 1.0f, volume.getFloat());
    }

    /** "⚠ Elytra is at 12% · 52 uses left", built from styled parts (the name has had its § codes stripped). */
    private static Text line(String name, String verb, String detail) {
        MutableText t = Text.literal("⚠ " + name).formatted(Formatting.RED);
        return t.append(Text.literal(verb).formatted(Formatting.GRAY))
                .append(Text.literal(detail).formatted(Formatting.RED))
                .append(Text.literal(" left").formatted(Formatting.GRAY));
    }
}
