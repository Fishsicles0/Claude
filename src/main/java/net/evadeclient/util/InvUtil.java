package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

/** Read-only inventory helpers for HUD readouts. */
public final class InvUtil {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private InvUtil() {}

    /** Total count of matching items in the main inventory, hotbar and offhand. */
    public static int count(Predicate<ItemStack> p) {
        if (mc.player == null) return 0;
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (p.test(s)) n += s.getCount();
        }
        ItemStack off = mc.player.getOffHandStack();
        if (p.test(off)) n += off.getCount();
        return n;
    }
}
