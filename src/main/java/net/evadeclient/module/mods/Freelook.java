package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/** Hold a key to look around your player without changing where you're facing or walking. */
public class Freelook extends Module {
    private final KeySetting key = add(new KeySetting("Freelook Key", GLFW.GLFW_KEY_LEFT_ALT));
    private final BooleanSetting thirdPerson = add(new BooleanSetting("Third Person", true));
    private final BooleanSetting invert = add(new BooleanSetting("Invert Pitch", false));

    private boolean active;
    private float yaw, pitch;
    private Perspective savedPerspective;

    public Freelook() {
        super("Freelook", "Look around without turning.", Category.MODS);
        setEnabled(true);
    }

    @Override
    public void onTick() {
        boolean down = key.isDown();
        if (down && !active) {
            active = true;
            yaw = mc.player.getYaw();
            pitch = mc.player.getPitch();
            savedPerspective = mc.options.getPerspective();
            if (thirdPerson.get() && savedPerspective.isFirstPerson()) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        } else if (!down && active) {
            stop();
        }
    }

    @Override
    protected void onDisable() { if (active) stop(); }

    private void stop() {
        active = false;
        if (savedPerspective != null) mc.options.setPerspective(savedPerspective);
    }

    public boolean active() { return active && isEnabled(); }

    /** Receives mouse movement while active (same units vanilla passes to changeLookDirection). */
    public void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15) * (invert.get() ? -1 : 1), -90f, 90f);
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
}
