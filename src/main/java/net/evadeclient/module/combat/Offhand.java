package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.evadeclient.util.Targets;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Keeps the item you choose in your offhand: crystals, golden apples, a shield or totems.
 * Falls back to a totem when your health drops, and can swap to a gapple while you hold a sword.
 */
public class Offhand extends Module {
    private final ModeSetting item = add(new ModeSetting("Item", "Crystal", "Crystal", "Gapple", "Shield", "Totem"));
    private final NumberSetting totemHealth = add(new NumberSetting("Totem Health", 10, 0, 36, 1));
    private final BooleanSetting swordGap = add(new BooleanSetting("Sword Gapple", true));
    private final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 10, 1));
    private int wait;

    public Offhand() {
        super("Offhand", "Keeps crystals, gapples, a shield or a totem in your offhand.", Category.PVP);
    }

    @Override
    public void onTick() {
        if (wait-- > 0 || !InvUtil.canClick()) return;
        Item want = wanted();
        if (mc.player.getOffHandStack().isOf(want)) return;
        int slot = InvUtil.findAnywhere(s -> s.isOf(want));
        if (slot == -1 && want != Items.TOTEM_OF_UNDYING) {
            int t = InvUtil.findAnywhere(s -> s.isOf(Items.TOTEM_OF_UNDYING)); // nothing else left: totem is the best backup
            if (t != -1 && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) slot = t;
        }
        if (slot == -1) return;
        InvUtil.swap(slot, InvUtil.OFFHAND);
        wait = delay.getInt();
    }

    Item wanted() {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hp <= totemHealth.get() || mc.player.fallDistance > 10) return Items.TOTEM_OF_UNDYING;
        if (swordGap.get() && Targets.isWeapon(mc.player.getMainHandStack()) && mc.options.useKey.isPressed())
            return mc.player.getInventory().count(Items.ENCHANTED_GOLDEN_APPLE) > 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        return switch (item.get()) {
            case "Gapple" -> mc.player.getInventory().count(Items.ENCHANTED_GOLDEN_APPLE) > 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
            case "Shield" -> Items.SHIELD;
            case "Totem" -> Items.TOTEM_OF_UNDYING;
            default -> Items.END_CRYSTAL;
        };
    }

    @Override
    public String getSuffix() { return item.get(); }
}
