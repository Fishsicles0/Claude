package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;

/** Jump again in mid-air. "Press" jumps once per key press, "Hold" keeps you rising while held. */
public class AirJump extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Press", "Press", "Hold"));
    private boolean wasDown;

    public AirJump() {
        super("AirJump", "Jump in mid-air.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        boolean down = mc.options.jumpKey.isPressed();
        if (!mc.player.isOnGround() && !mc.player.isTouchingWater() && !mc.player.isClimbing()) {
            if (mode.is("Hold") ? down : down && !wasDown) mc.player.jump();
        }
        wasDown = down;
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
