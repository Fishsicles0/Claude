package net.evadeclient.module.hud;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Your own player model, small, on the HUD: skin, armour, held items and cape, turned to face you, at a fixed angle
 * or spinning, in your live pose or standing still. Only ever your own player.
 */
public class PaperDollHud extends HudElementModule {
    public final NumberSetting size = add(new NumberSetting("Doll Size", 30, 16, 80, 2)
            .describe("How tall the model is drawn (the widget's Scale enlarges the whole box too)."));
    public final ModeSetting rotation = add(new ModeSetting("Rotation", "Follow Camera", "Follow Camera", "Fixed", "Spin")
            .describe("Follow Camera: the model looks where you look. Fixed: it stays at Angle. Spin: it turns slowly."));
    public final NumberSetting angle = add(new NumberSetting("Angle", 25, -180, 180, 5)
            .describe("Which way the model faces with Rotation Fixed: 0 faces you, 90 shows its side."));
    public final NumberSetting spinSpeed = add(new NumberSetting("Spin Speed", 30, 5, 180, 5)
            .describe("Degrees per second with Rotation Spin."));
    public final ModeSetting pose = add(new ModeSetting("Pose", "Live", "Live", "Standing")
            .describe("Live copies what you're doing (walking, sneaking, swinging). Standing keeps the model still and upright."));

    public PaperDollHud() {
        super("Paper Doll", "Your own player model on the HUD, facing you, fixed or spinning.", -1, 330);
    }

    @Override
    protected boolean defaultBackground() { return false; }

    /** The yaw offset (degrees, 0 = facing the viewer) for this frame. */
    public float bodyAngle() {
        return switch (rotation.get()) {
            case "Fixed" -> angle.getFloat();
            case "Spin" -> (float) ((System.currentTimeMillis() / 1000.0 * spinSpeed.get()) % 360.0);
            default -> 0f;
        };
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        int s = size.getInt();
        width = Math.round(s * 1.6f) + 4;
        height = Math.round(s * 2.3f) + 4;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        LivingEntity player = mc.player;
        if (player == null) return;

        // The model is drawn into its own picture on screen, outside the HUD's matrix: map the box to screen pixels.
        Matrix3x2f m = new Matrix3x2f(ctx.getMatrices());
        Vector2f a = m.transformPosition(new Vector2f(left + 2, top + 2));
        Vector2f b = m.transformPosition(new Vector2f(left + width - 2, top + height - 2));
        float k = (float) Math.sqrt(Math.abs(m.m00() * m.m11() - m.m01() * m.m10()));
        int x1 = Math.round(Math.min(a.x, b.x)), y1 = Math.round(Math.min(a.y, b.y));
        int x2 = Math.round(Math.max(a.x, b.x)), y2 = Math.round(Math.max(a.y, b.y));
        if (x2 - x1 < 2 || y2 - y1 < 2) return;

        float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
        var renderer = mc.getEntityRenderDispatcher().getRenderer(player);
        EntityRenderState state = renderer.getAndUpdateRenderState(player, tickDelta);
        state.light = 0xF000F0;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState l) {
            boolean still = pose.is("Standing");
            float headYaw = MathHelper.wrapDegrees(player.getHeadYaw() - player.getBodyYaw());
            float turn = bodyAngle();
            l.bodyYaw = 180f + turn;
            l.relativeHeadYaw = still ? 0 : rotation.is("Follow Camera") ? headYaw : 0;
            l.pitch = still ? 0 : MathHelper.clamp(player.getPitch(tickDelta), -60, 60);
            if (rotation.is("Follow Camera") && !still) l.bodyYaw = 180f - headYaw;
            l.width = l.width / l.baseScale;
            l.height = l.height / l.baseScale;
            l.baseScale = 1f;
            if (still) {
                l.pose = EntityPose.STANDING;
                l.limbSwingAmplitude = 0;
                l.limbSwingAnimationProgress = 0;
                if (l instanceof BipedEntityRenderState bs) {
                    bs.isInSneakingPose = false;
                    bs.handSwingProgress = 0;
                    bs.isUsingItem = false;
                    bs.leaningPitch = 0;
                    bs.isSwimming = false;
                    bs.isGliding = false;
                }
            }
        }
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
        Vector3f translation = new Vector3f(0, state.height / 2f + 0.0625f, 0);
        ctx.addEntity(state, s * k, translation, rot, null, x1, y1, x2, y2);
    }
}
