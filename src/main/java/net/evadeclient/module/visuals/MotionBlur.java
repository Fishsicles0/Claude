package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.mods.Performance;
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

    private NativeImageBackedTexture control;
    private int controlValue = -1;
    private boolean ranLastFrame, failed, warned;
    private int lastWidth, lastHeight;
    private PostEffectProcessor lastEffect;

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
            setControl(seed ? 0 : (int) Math.round(strength.get() * 255));
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
