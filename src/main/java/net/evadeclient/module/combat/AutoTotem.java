package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Keeps a Totem of Undying in your offhand. "Always" re-equips instantly after a pop;
 * "Smart" only takes over the offhand when you're in danger, leaving your shield/gapple otherwise.
 */
public class AutoTotem extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Always", "Always", "Smart"));
    private final NumberSetting health = add(new NumberSetting("Health", 10, 1, 20, 1));
    private final BooleanSetting fallCheck = add(new BooleanSetting("Fall Check", true));
    private final BooleanSetting elytraCheck = add(new BooleanSetting("Elytra Check", true));
    private final NumberSetting delay = add(new NumberSetting("Delay Ticks", 0, 0, 10, 1));
    private final BooleanSetting hotbarTotem = add(new BooleanSetting("Hotbar Slot", false));
    private final NumberSetting hotbarSlot = add(new NumberSetting("Hotbar #", 9, 1, 9, 1));

    private int wait;

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem in your offhand.", Category.PVP);
    }

    @Override
    public void onTick() {
        if (wait > 0) { wait--; return; }
        if (!InvUtil.canClick()) return;

        if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) && inDanger()) {
            int slot = InvUtil.findAnywhere(AutoTotem::isTotem);
            if (slot != -1) {
                InvUtil.swap(slot, InvUtil.OFFHAND);
                wait = delay.getInt();
                return;
            }
        }

        // Optional second totem in a hotbar slot for instant manual swaps.
        if (hotbarTotem.get()) {
            int hb = hotbarSlot.getInt() - 1;
            if (!mc.player.getInventory().getStack(hb).isOf(Items.TOTEM_OF_UNDYING)) {
                int from = -1;
                for (int i = 9; i < 36 && from == -1; i++) if (isTotem(mc.player.getInventory().getStack(i))) from = i;
                if (from != -1) {
                    InvUtil.swap(from, hb);
                    wait = delay.getInt();
                }
            }
        }
    }

    private boolean inDanger() {
        if (mode.is("Always")) return true;
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (hp <= health.get()) return true;
        if (fallCheck.get() && mc.player.fallDistance > 6 && !mc.player.isGliding()) return true;
        return elytraCheck.get() && mc.player.isGliding();
    }

    private static boolean isTotem(ItemStack s) { return s.isOf(Items.TOTEM_OF_UNDYING); }

    @Override
    public String getSuffix() {
        if (mc.player == null) return mode.get();
        return mode.get() + " " + InvUtil.count(AutoTotem::isTotem);
    }
}
