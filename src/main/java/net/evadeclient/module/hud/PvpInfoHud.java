package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.util.TargetTracker;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Quick read on your current combat target: distance, health, and how worn their armour is. */
public class PvpInfoHud extends TextHudModule {
    public PvpInfoHud() {
        super("PvP Info", "Your combat target's distance, health and armour.", -4, 184);
    }

    @Override
    protected List<String> lines(boolean preview) {
        List<String> out = new ArrayList<>();
        if (preview) { out.add("Target  4.2m"); out.add("20.0 ❤  Armour 83%"); return out; }
        LivingEntity t = TargetTracker.get();
        if (t == null || mc.player == null) return out;
        out.add(t.getName().getString() + "  " + String.format("%.1fm", mc.player.distanceTo(t)));
        out.add(String.format("%.1f ❤  Armour %d%%", t.getHealth() + t.getAbsorptionAmount(), armorPct(t)));
        return out;
    }

    private int armorPct(LivingEntity e) {
        int cur = 0, max = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = e.getEquippedStack(slot);
            if (s.isEmpty() || !s.isDamageable()) continue;
            cur += s.getMaxDamage() - s.getDamage();
            max += s.getMaxDamage();
        }
        return max == 0 ? 0 : cur * 100 / max;
    }
}
