package net.evadeclient.module.misc;

import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/** Warns you when a piece of armour (or your held tool) is about to break. */
public class Protection extends Module {
    public final NumberSetting percent = add(new NumberSetting("Warn At %", 10, 2, 50, 1));
    public final BooleanSetting mainhand = add(new BooleanSetting("Held Item", true));
    private final Set<EquipmentSlot> warned = EnumSet.noneOf(EquipmentSlot.class);

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    public Protection() {
        super("Protection", "Alerts you when your armour durability gets critically low.", Category.MISC);
    }

    @Override
    protected void onDisable() { warned.clear(); }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        for (EquipmentSlot slot : ARMOR) check(slot, mc.player.getEquippedStack(slot));
        if (mainhand.get()) check(EquipmentSlot.MAINHAND, mc.player.getMainHandStack());
    }

    private void check(EquipmentSlot slot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) { warned.remove(slot); return; }
        int max = stack.getMaxDamage();
        if (max <= 0) return;
        float left = 100f * (max - stack.getDamage()) / max;
        if (left <= percent.get()) {
            if (warned.add(slot)) {
                String name = stack.getName().getString();
                CommandManager.print("§c⚠ " + name + "§7 is at §c" + Math.round(left) + "%§7 durability");
                Notifications.push("Low durability", name + " · " + Math.round(left) + "%", false);
            }
        } else if (left > percent.get() + 5) {
            warned.remove(slot); // repaired/replaced — allow a fresh warning later
        }
    }
}
