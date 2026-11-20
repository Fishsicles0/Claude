package net.evadeclient.module.player;

import net.evadeclient.mixin.MinecraftClientCooldownAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;

/** Removes the delay between right clicks for XP bottles, snowballs, eggs and other thrown items. */
public class FastUse extends Module {
    private final ModeSetting mode = add(new ModeSetting("Items", "Throwables", "Throwables", "XP Only", "All"));

    public FastUse() {
        super("FastUse", "No right-click delay for XP bottles and throwables.", Category.UTILITY);
    }

    @Override
    public void onTick() {
        var s = mc.player.getMainHandStack();
        boolean ok = switch (mode.get()) {
            case "XP Only" -> s.isOf(Items.EXPERIENCE_BOTTLE);
            case "All" -> true;
            default -> !(s.getItem() instanceof BlockItem) && !s.isEmpty();
        };
        if (ok) ((MinecraftClientCooldownAccessor) mc).evade$setItemUseCooldown(0);
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
