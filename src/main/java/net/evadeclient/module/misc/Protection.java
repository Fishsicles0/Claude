package net.evadeclient.module.misc;

import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Warns you once when a piece of armour, or the item in either hand, is about to break. Each item is warned about
 * once until it is repaired or replaced: scrolling away from a worn tool and back again doesn't warn twice.
 */
public class Protection extends Module {
    public final NumberSetting percent = add(new NumberSetting("Warn At %", 10, 2, 50, 1));
    public final BooleanSetting mainhand = add(new BooleanSetting("Held Item", true)
            .describe("Also watch the items in your main hand and off hand."));

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    /** Key for the off hand in {@link #warned}; armour uses the slot's ordinal, the main hand its hotbar slot. */
    private static final int OFFHAND = -1;
    private static final int HOTBAR = 100;

    /**
     * What each slot held when it was warned about. Keyed by slot and item rather than by the stack object, since
     * the server replaces the stack object every time it loses durability.
     */
    private final Map<Integer, Item> warned = new HashMap<>();

    public Protection() {
        super("Protection", "Alerts you when your armour durability gets critically low.", Category.MISC);
    }

    @Override
    protected void onDisable() { warned.clear(); }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        for (EquipmentSlot slot : ARMOR) check(slot.ordinal(), mc.player.getEquippedStack(slot));
        if (mainhand.get()) {
            check(HOTBAR + mc.player.getInventory().getSelectedSlot(), mc.player.getMainHandStack());
            check(OFFHAND, mc.player.getOffHandStack());
        }
    }

    private void check(int key, ItemStack stack) {
        // Empty or unbreakable: nothing to say, and nothing to forget (you may just have scrolled past it).
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) return;
        int max = stack.getMaxDamage();
        if (max <= 0) return;
        float left = 100f * (max - stack.getDamage()) / max;
        Item item = stack.getItem();
        if (left <= percent.get()) {
            if (warned.put(key, item) != item) {
                String name = stack.getName().getString();
                CommandManager.print("§c⚠ " + name + "§7 is at §c" + Math.round(left) + "%§7 durability");
                Notifications.push("Low durability", name + " · " + Math.round(left) + "%", false);
            }
        } else if (left > percent.get() + 5) {
            warned.remove(key); // repaired or replaced: allow a fresh warning later
        }
    }
}
