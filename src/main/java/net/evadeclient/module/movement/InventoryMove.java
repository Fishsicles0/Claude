package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/** Keeps WASD, jump and sprint working while an inventory or chest is open. Typing screens are left alone. */
public class InventoryMove extends Module {
    private final BooleanSetting sneak = add(new BooleanSetting("Sneak", false));

    public InventoryMove() {
        super("InventoryMove", "Walk around with your inventory or a chest open.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        var s = mc.currentScreen;
        if (s == null || s instanceof ChatScreen || s instanceof AbstractSignEditScreen || s instanceof AnvilScreen) return;
        for (KeyBinding k : new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey,
                mc.options.rightKey, mc.options.jumpKey, mc.options.sprintKey}) update(k);
        if (sneak.get()) update(mc.options.sneakKey);
    }

    private void update(KeyBinding k) {
        InputUtil.Key key = InputUtil.fromTranslationKey(k.getBoundKeyTranslationKey());
        if (key.getCategory() != InputUtil.Type.KEYSYM) return;
        k.setPressed(InputUtil.isKeyPressed(mc.getWindow(), key.getCode()));
    }
}
