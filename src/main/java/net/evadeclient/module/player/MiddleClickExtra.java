package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

/** Middle-click to throw a pearl (or wind charge, XP bottle, firework) without switching to it. */
public class MiddleClickExtra extends Module {
    private final ModeSetting item = add(new ModeSetting("Item", "Pearl", "Pearl", "Wind Charge", "XP Bottle", "Firework"));
    private boolean wasDown;

    public MiddleClickExtra() {
        super("MiddleClickExtra", "Middle-click throws a pearl without switching items.", Category.UTILITY);
    }

    @Override
    public void onTick() {
        boolean down = mc.currentScreen == null
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (down && !wasDown) use();
        wasDown = down;
    }

    /** Throws the chosen item once. Public so it can be triggered from tests or a keybind. */
    public boolean use() {
        Item it = switch (item.get()) {
            case "Wind Charge" -> Items.WIND_CHARGE;
            case "XP Bottle" -> Items.EXPERIENCE_BOTTLE;
            case "Firework" -> Items.FIREWORK_ROCKET;
            default -> Items.ENDER_PEARL;
        };
        if (it == Items.FIREWORK_ROCKET && !mc.player.isGliding()) return false;
        if (mc.player.getItemCooldownManager().isCoolingDown(it.getDefaultStack())) return false;
        int slot = InvUtil.ensureInHotbar(s -> s.isOf(it), true);
        if (slot == -1) return false;
        InvUtil.useHotbarItem(slot, null);
        return true;
    }

    @Override
    public String getSuffix() { return item.get(); }
}
