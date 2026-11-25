package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ItemListSetting;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Keeps you from breaking blocks you didn't mean to: chosen blocks (budding amethyst and spawners by default) and,
 * if you like, crops that haven't grown yet. Your own left click on such a block simply does nothing; hold sneak to
 * break it anyway. It only ever holds back your own input: it never breaks anything for you, never switches tools
 * and never makes mining faster.
 */
public class BreakGuard extends Module {
    private final ItemListSetting protectedBlocks = add(new ItemListSetting("Protected Blocks",
            "budding_amethyst,spawner,trial_spawner,vault,end_portal_frame", "Blocks you can't break by accident")
            .describe("Blocks your clicks won't break. Pick them like items."));
    private final BooleanSetting unripeCrops = add(new BooleanSetting("Protect Unripe Crops", true)
            .describe("Don't break wheat, carrots, potatoes and other crops until they are fully grown."));
    private final BooleanSetting sneakOverride = add(new BooleanSetting("Sneak To Break", true)
            .describe("Holding sneak lets you break a protected block after all."));
    private final BooleanSetting creativeToo = add(new BooleanSetting("In Creative Too", false)
            .describe("Also guard blocks in Creative mode."));
    private final BooleanSetting tellMe = add(new BooleanSetting("Say When Blocked", true)
            .describe("Shows a short note above the hotbar when a break is held back."));

    private int blocked;
    private long lastNote;

    public BreakGuard() {
        super("Break Guard", "Stops you breaking chosen blocks or unripe crops by accident. Sneak to break anyway.", Category.MODS);
    }

    /** Whether your click may start or keep breaking the block at {@code pos} (true when the module is off). */
    public boolean allows(ClientPlayerEntity player, BlockPos pos) {
        if (!isEnabled() || player == null || mc.world == null || pos == null) return true;
        if (player.isCreative() && !creativeToo.get()) return true;
        if (sneakOverride.get() && player.isSneaking()) return true;
        BlockState st = mc.world.getBlockState(pos);
        if (!guards(st)) return true;
        blocked++;
        long now = System.currentTimeMillis();
        if (tellMe.get() && now - lastNote > 1000) {
            lastNote = now;
            String what = st.getBlock().getName().getString();
            player.sendMessage(Text.literal("Break Guard: " + what + " is protected" + (sneakOverride.get() ? " (sneak to break)" : "")), true);
        }
        return false;
    }

    /** Whether this block is one the guard protects. */
    public boolean guards(BlockState st) {
        if (st.isAir()) return false;
        if (unripeCrops.get() && st.getBlock() instanceof CropBlock crop && !crop.isMature(st)) return true;
        return protectedBlocks.contains(st.getBlock().asItem());
    }

    /** How many breaks were held back (for the tests). */
    public int blockedForTest() { return blocked; }
}
