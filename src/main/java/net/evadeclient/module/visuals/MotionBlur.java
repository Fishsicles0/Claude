package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.util.Identifier;

/**
 * Cosmetic motion blur: each frame is blended with the previous blended frame, on your screen only. Runs after the
 * world is drawn and before the HUD, so text and menus stay sharp. Skipped while a menu is open, in Low-End PC
 * Mode, and when Distortion Effects is at 0% (the game's accessibility setting for motion-heavy effects).
 *
 * <p>The blend lives in the post effect {@code evade:motion_blur}. Its strength (and "start fresh" after a pause or
 * a resize) comes from a 1x1 control texture this module owns and updates, since post-effect uniforms are fixed
 * when the effect loads.
 */
public class MotionBlur extends Module {
    private static final Identifier EFFECT = Identifier.of("evade", "motion_blur");
    /** Where the effect's "Control" sampler ({@code "location": "evade:motion_blur_control"}) reads from. */
    private static final Identifier CONTROL = Identifier.of("evade", "textures/effect/motion_blur_control.png");

    private static MotionBlur instance;

    public final NumberSetting strength = add(new NumberSetting("Strength", 0.5, 0.1, 0.9, 0.05)
            .describe("How much of the previous frame stays on screen. Higher is blurrier."));
    public final BooleanSetting onlyWhileMoving = add(new BooleanSetting("Only While Moving", false)
            .describe("Blur only while you turn the camera or move fast, so a still view stays sharp. The blur fades in and out with your speed."));
    public final NumberSetting sensitivity = add(new NumberSetting("Motion Sensitivity", 1.0, 0.25, 3.0, 0.25)
            .describe("With Only While Moving: how little turning or speed it takes to reach full blur. Higher reacts to smaller movements."));

    private NativeImageBackedTexture control;
    private int controlValue = -1;
    private boolean ranLastFrame, failed, warned;
    private int lastWidth, lastHeight;
    private PostEffectProcessor lastEffect;
    /** Only While Moving: the camera at the last frame, and the smoothed 0..1 share of the blur in use. */
    private float lastYaw, lastPitch, motion;
    private double lastX, lastY, lastZ;
    private long lastFrameNs;

    public MotionBlur() {
        super("Motion Blur", "Blends frames together for smoother-looking motion. Can cause motion sickness.", Category.VISUALS);
        instance = this;
    }

    @Override
    protected void onEnable() {
        ranLastFrame = false;
        failed = false;
        if (!warned) {
            warned = true;
            Notifications.push("Motion Blur", "Can cause motion sickness; turn it off if you feel unwell", false);
        }
    }

    @Override
    protected void onDisable() {
        ranLastFrame = false;
    }

    /** Whether the blur was drawn on the last frame (for tests). */
    public boolean ranLastFrame() { return ranLastFrame; }

    /**
     * Called by GameRendererMixin once per frame after the world (and vanilla's own post effects) were drawn and
     * before the HUD, with the game renderer's frame-graph allocator; {@code null} on frames without a world.
     */
    public static void afterWorld(ObjectAllocator allocator) {
        MotionBlur m = instance;
        if (m == null) return;
        if (allocator == null || !m.isEnabled() || m.failed || !m.shouldRun()) {
            m.ranLastFrame = false;
            return;
        }
        m.apply(allocator);
    }

    private boolean shouldRun() {
        if (mc.world == null || mc.currentScreen != null) return false;
        if (mc.options.getDistortionEffectScale().getValue() <= 0) return false;
        Performance p = Performance.active();
        return p == null || !p.lowEnd.get();
    }

    private void apply(ObjectAllocator allocator) {
        try {
            if (!ensureControl()) return;
            PostEffectProcessor effect = mc.getShaderLoader().loadPostEffect(EFFECT, DefaultFramebufferSet.MAIN_ONLY);
            if (effect == null) {
                ranLastFrame = false;
                return;
            }
            Framebuffer fb = mc.getFramebuffer();
            // Start fresh (keep nothing of the old frame) after a pause, a resize or a resource reload.
            boolean seed = !ranLastFrame || effect != lastEffect || fb.textureWidth != lastWidth || fb.textureHeight != lastHeight;
            int value = (int) Math.round(strength.get() * motionFactor() * 255);
            setControl(seed ? 0 : value);
            FrameGraphBuilder graph = new FrameGraphBuilder();
            var targets = PostEffectProcessor.FramebufferSet.singleton(PostEffectProcessor.MAIN, graph.createObjectNode("main", fb));
            effect.render(graph, fb.textureWidth, fb.textureHeight, targets);
            graph.run(allocator);
            ranLastFrame = true;
            lastEffect = effect;
            lastWidth = fb.textureWidth;
            lastHeight = fb.textureHeight;
        } catch (RuntimeException e) {
            // Never take the game down for a cosmetic: stay off until the module is switched on again.
            failed = true;
            ranLastFrame = false;
            EvadeClient.LOG.error("Motion Blur failed and was paused", e);
        }
    }

    /**
     * 1 normally. With Only While Moving, how fast the camera turns and travels (full blur at about 180 degrees or
     * 20 blocks a second, divided by Motion Sensitivity), smoothed so the blur eases in and out instead of popping.
     */
    private float motionFactor() {
        var cam = mc.gameRenderer.getCamera();
        float yaw = cam.getYaw(), pitch = cam.getPitch();
        var pos = cam.getCameraPos();
        long now = System.nanoTime();
        double dt = lastFrameNs == 0 ? 0 : Math.min(0.25, (now - lastFrameNs) / 1e9);
        float target = 1f;
        if (onlyWhileMoving.get()) {
            target = 0f;
            if (dt > 0) {
                double turn = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - lastYaw)) + Math.abs(pitch - lastPitch);
                double travel = Math.sqrt(sq(pos.x - lastX) + sq(pos.y - lastY) + sq(pos.z - lastZ));
                double speed = Math.max(turn / dt / 180.0, travel / dt / 20.0) * sensitivity.get();
                target = (float) Math.min(1.0, speed);
            }
            // Ease towards the target: quick to start blurring, a little slower to settle.
            float rate = (float) Math.min(1.0, dt * (target > motion ? 14 : 6));
            motion += (target - motion) * rate;
        } else {
            motion = 1f;
        }
        lastYaw = yaw;
        lastPitch = pitch;
        lastX = pos.x;
        lastY = pos.y;
        lastZ = pos.z;
        lastFrameNs = now;
        return onlyWhileMoving.get() ? motion : target;
    }

    private static double sq(double v) { return v * v; }

    /** The share of Strength in use on the last frame (1 unless Only While Moving holds it lower; for tests). */
    public float motionForTest() { return motion; }

    /** Creates and registers the control texture before the effect first loads (it looks the texture up then). */
    private boolean ensureControl() {
        if (control != null) return true;
        NativeImage image = new NativeImage(1, 1, false);
        image.setColorArgb(0, 0, 0xFF000000);
        control = new NativeImageBackedTexture(() -> "Evade motion blur control", image);
        mc.getTextureManager().registerTexture(CONTROL, control);
        controlValue = 0;
        return true;
    }

    /** Red channel = share of the previous frame to keep, 0..255. Uploaded only when it changes. */
    private void setControl(int value) {
        if (value == controlValue) return;
        NativeImage image = control.getImage();
        if (image == null) return;
        image.setColorArgb(0, 0, 0xFF000000 | (Math.max(0, Math.min(255, value)) << 16));
        control.upload();
        controlValue = value;
    }
}
