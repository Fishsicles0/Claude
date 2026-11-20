package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;

import java.util.ArrayList;
import java.util.List;

/** Tap your sprint/sneak key to toggle instead of holding it. Shows the state on screen. */
public class ToggleSprint extends TextHudModule {
    private final BooleanSetting sneak = add(new BooleanSetting("Toggle Sneak", false));
    private final BooleanSetting showHud = add(new BooleanSetting("Show Status", true));

    private boolean sprinting = true, sneaking;
    private boolean sprintWas, sneakWas;

    public ToggleSprint() {
        super("ToggleSprint", "Tap sprint or sneak to toggle them.", Category.MODS, 4, -2);
        setEnabled(true);
    }

    @Override
    public void onTick() {
        boolean sprintPhysical = physical(mc.options.sprintKey);
        if (sprintPhysical && !sprintWas && mc.currentScreen == null) sprinting = !sprinting;
        sprintWas = sprintPhysical;
        if (sprinting) mc.options.sprintKey.setPressed(true);

        if (sneak.get()) {
            boolean sneakPhysical = physical(mc.options.sneakKey);
            if (sneakPhysical && !sneakWas && mc.currentScreen == null) sneaking = !sneaking;
            sneakWas = sneakPhysical;
            if (sneaking) mc.options.sneakKey.setPressed(true);
        }
    }

    /** Whether the key bound to {@code bind} is physically held (ignores our own setPressed). */
    private boolean physical(net.minecraft.client.option.KeyBinding bind) {
        var key = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(bind);
        if (key.getCategory() == net.minecraft.client.util.InputUtil.Type.MOUSE) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), key.getCode()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
    }

    @Override
    protected void onDisable() {
        mc.options.sprintKey.setPressed(false);
        if (sneaking) mc.options.sneakKey.setPressed(false);
        sneaking = false;
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (!showHud.get() && !preview) return new ArrayList<>();
        if (sneak.get() && sneaking) return new ArrayList<>(List.of("Sneaking (Toggled)"));
        if (mc.player != null && mc.player.isSneaking()) return new ArrayList<>(List.of("Sneaking (Key Held)"));
        if (sprinting) return new ArrayList<>(List.of("Sprinting (Toggled)"));
        return preview ? new ArrayList<>(List.of("Sprinting (Toggled)")) : new ArrayList<>();
    }
}
