package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.EnumSet;
import java.util.Set;

/**
 * Repairs Mending armour by throwing XP bottles at your feet.
 * "Strip Repaired" takes fully repaired pieces off so every orb goes into the damaged ones,
 * then puts them back on when it's done.
 */
public class AutoMend extends Module {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final NumberSetting threshold = add(new NumberSetting("Start Below %", 70, 5, 99, 1));
    private final NumberSetting bottles = add(new NumberSetting("Bottles/Tick", 2, 1, 6, 1));
    private final BooleanSetting strip = add(new BooleanSetting("Strip Repaired", true));
    private final NumberSetting enemyRange = add(new NumberSetting("Pause Enemy", 8, 0, 24, 1));
    private final NumberSetting minHealth = add(new NumberSetting("Min Health", 8, 0, 20, 1));
    private final BooleanSetting refill = add(new BooleanSetting("Refill Hotbar", true));
    private final BooleanSetting onGround = add(new BooleanSetting("Ground Only", true));
    private final BooleanSetting autoDisable = add(new BooleanSetting("Auto Disable", false));

    private boolean mending;
    private final Set<EquipmentSlot> stripped = EnumSet.noneOf(EquipmentSlot.class);

    public AutoMend() {
        super("AutoMend", "Repairs Mending armour with XP bottles.", Category.PVP);
    }

    @Override
    protected void onDisable() {
        mending = false;
    }

    @Override
    public void onTick() {
        boolean anyLow = false, anyDamaged = false;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = mc.player.getEquippedStack(slot);
            if (!mendable(s)) continue;
            if (s.isDamaged()) anyDamaged = true;
            if (durability(s) * 100 < threshold.get()) anyLow = true;
        }
        if (anyLow) mending = true;
        if (!anyDamaged) mending = false;

        if (!mending) {
            if (reequip()) return;
            if (autoDisable.get() && stripped.isEmpty() && !anyDamaged) setEnabled(false);
            return;
        }
        if (unsafe()) return;

        // Take off repaired pieces so they don't soak up XP.
        if (strip.get() && InvUtil.canClick()) {
            for (EquipmentSlot slot : ARMOR) {
                ItemStack s = mc.player.getEquippedStack(slot);
                if (mendable(s) && !s.isDamaged() && InvUtil.findEmptyMain() != -1) {
                    InvUtil.quickMove(armorHandlerSlot(slot));
                    stripped.add(slot);
                    return; // one inventory action per tick
                }
            }
        }

        int slot = InvUtil.ensureInHotbar(st -> st.isOf(Items.EXPERIENCE_BOTTLE), refill.get());
        if (slot == -1) return;
        for (int i = 0; i < bottles.getInt() && mc.player.getInventory().getStack(slot).isOf(Items.EXPERIENCE_BOTTLE); i++) {
            InvUtil.useHotbarItem(slot, 90f);
        }
    }

    private boolean unsafe() {
        if (mc.currentScreen != null) return true;
        if (onGround.get() && !mc.player.isOnGround()) return true;
        if (mc.player.getHealth() < minHealth.get()) return true;
        double r = enemyRange.get();
        if (r > 0) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p != mc.player && !p.isSpectator() && !mc.player.isTeammate(p) && mc.player.squaredDistanceTo(p) < r * r) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Put stripped armour back on, one piece per tick. Returns true if it did something. */
    private boolean reequip() {
        if (stripped.isEmpty() || !InvUtil.canClick()) return false;
        for (EquipmentSlot slot : EnumSet.copyOf(stripped)) {
            if (!mc.player.getEquippedStack(slot).isEmpty()) { stripped.remove(slot); continue; }
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                EquippableComponent eq = s.get(DataComponentTypes.EQUIPPABLE);
                if (eq != null && eq.slot() == slot && mendable(s)) {
                    InvUtil.quickMove(InvUtil.toHandlerSlot(i));
                    stripped.remove(slot);
                    return true;
                }
            }
            stripped.remove(slot); // couldn't find it any more
        }
        return false;
    }

    private static int armorHandlerSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            default -> 8;
        };
    }

    static float durability(ItemStack s) {
        return s.getMaxDamage() == 0 ? 1f : 1f - s.getDamage() / (float) s.getMaxDamage();
    }

    private static boolean mendable(ItemStack s) {
        if (s.isEmpty() || !s.isDamageable()) return false;
        for (var e : s.getEnchantments().getEnchantments()) {
            if (e.matchesKey(Enchantments.MENDING)) return true;
        }
        return false;
    }

    @Override
    public String getSuffix() { return mending ? "Mending" : null; }
}
