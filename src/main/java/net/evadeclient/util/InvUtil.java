package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.function.Predicate;

/**
 * Inventory helpers. "Inventory index" means {@link PlayerInventory} index:
 * 0-8 hotbar, 9-35 main, 40 offhand.
 */
public final class InvUtil {
    public static final int OFFHAND = 40;
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private InvUtil() {}

    public static int findHotbar(Predicate<ItemStack> p) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (p.test(inv.getStack(i))) return i;
        return -1;
    }

    /** Searches main inventory first (keeps your hotbar intact), then the hotbar. */
    public static int findAnywhere(Predicate<ItemStack> p) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) if (p.test(inv.getStack(i))) return i;
        for (int i = 0; i < 9; i++) if (p.test(inv.getStack(i))) return i;
        return -1;
    }

    public static int count(Predicate<ItemStack> p) {
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

    public static int findEmptyMain() {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    /** Player-screen-handler slot id for an inventory index. */
    public static int toHandlerSlot(int invIndex) {
        if (invIndex < 9) return 36 + invIndex;
        if (invIndex == OFFHAND) return 45;
        return invIndex;
    }

    /** Clicking the player inventory is only safe when no other container is open. */
    public static boolean canClick() {
        return mc.player.currentScreenHandler == mc.player.playerScreenHandler
                && mc.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    /** Swap an inventory slot with a hotbar slot (0-8) or the offhand (40) in one click. */
    public static void swap(int invIndex, int hotbarOrOffhand) {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                toHandlerSlot(invIndex), hotbarOrOffhand, SlotActionType.SWAP, mc.player);
    }

    public static void quickMove(int handlerSlot) {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,
                handlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    /**
     * Use the item in hotbar {@code slot} with a temporary pitch, then restore slot and rotation.
     * The server reads the rotation from the use packet, so the throw goes where we pointed.
     */
    public static void useHotbarItem(int slot, Float pitch) {
        PlayerInventory inv = mc.player.getInventory();
        int prevSlot = inv.getSelectedSlot();
        float prevPitch = mc.player.getPitch();
        if (pitch != null) mc.player.setPitch(pitch);
        inv.setSelectedSlot(slot);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        inv.setSelectedSlot(prevSlot);
        if (pitch != null) mc.player.setPitch(prevPitch);
    }

    /** Find the item in the hotbar, or swap it in from the main inventory. Returns hotbar slot or -1. */
    public static int ensureInHotbar(Predicate<ItemStack> p, boolean refill) {
        int slot = findHotbar(p);
        if (slot != -1 || !refill || !canClick()) return slot;
        int from = -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36 && from == -1; i++) if (p.test(inv.getStack(i))) from = i;
        if (from == -1) return -1;
        int target = -1;
        for (int i = 0; i < 9 && target == -1; i++) if (inv.getStack(i).isEmpty()) target = i;
        if (target == -1) target = 8; // overwrite (swap) the last hotbar slot
        swap(from, target);
        return target;
    }
}
