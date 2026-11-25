package net.evadeclient.cosmetics;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Per-player motion for cosmetics, stepped once per rendered frame from the render-state extraction (so it costs
 * nothing for players without cosmetics): a spring on the vanilla cape values (capes lift when you run or fall, swing
 * with turns and settle with a little overshoot when you stop), the wing beat rate (faster when sprinting, falling or
 * flying) and fold (sneaking, eased), and a pet that trails your turns and looks where you look.
 */
public final class CosmeticMotion {
    /** The most a pet trails your head's turn, in degrees: small enough that it never swings into your head. */
    public static final float PET_LAG = 6f;

    private CosmeticMotion() {}

    public static void update(RenderData d, PlayerEntity p, PlayerEntityRenderState s, CosmeticItem cape) {
        long now = Util.getMeasuringTimeNano();
        float dt = d.lastNanos == 0 ? 0 : Math.min(0.1f, (now - d.lastNanos) / 1e9f);
        d.lastNanos = now;
        d.motion = true;
        if (dt <= 0) {
            d.capeLift = s.field_53536; d.capeSwing = s.field_53537; d.capeSide = s.field_53538;
            d.petYaw = s.bodyYaw + s.relativeHeadYaw;
            return;
        }

        // ---- cape: damped spring toward vanilla's own (already smoothed) cape values
        float stiff = cape == null ? 0.5f : cape.stiffness, lift = cape == null ? 1f : cape.lift, sway = cape == null ? 1f : cape.sway;
        float k = 60f + 200f * stiff, c = 2f * (float) Math.sqrt(k) * 0.55f;   // a little under-damped: settles with one soft overshoot
        float tLift = s.field_53536 * lift, tSwing = s.field_53537 * lift, tSide = s.field_53538 * sway;
        // fast running makes the hem flutter a little (it never changes how far the cape reaches)
        float flutter = s.field_53537 > 15 ? (float) Math.sin(d.time * 11.0) * s.field_53537 * 0.035f * (1.2f - stiff) : 0;
        for (float left = dt; left > 1e-5f; ) {
            float h = Math.min(left, 1 / 120f);
            left -= h;
            d.capeLiftV += (k * (tLift - d.capeLift) - c * d.capeLiftV) * h;
            d.capeSwingV += (k * (tSwing + flutter - d.capeSwing) - c * d.capeSwingV) * h;
            d.capeSideV += (k * (tSide - d.capeSide) - c * d.capeSideV) * h;
            d.capeLift += d.capeLiftV * h;
            d.capeSwing += d.capeSwingV * h;
            d.capeSide += d.capeSideV * h;
        }
        d.capeLift = MathHelper.clamp(d.capeLift, -12f, 40f);
        d.capeSwing = MathHelper.clamp(d.capeSwing, -10f, 160f);
        d.capeSide = MathHelper.clamp(d.capeSide, -30f, 30f);

        // ---- wings: beat faster when sprinting, falling or flying; fold while sneaking
        boolean falling = !p.isOnGround() && p.getVelocity().y < -0.25 && !p.isTouchingWater();
        boolean flying = p.getAbilities().flying;
        float rate = 1f + (p.isSprinting() ? 0.8f : 0f) + (falling || flying ? 1.4f : 0f);
        float amp = 1f + (p.isSprinting() || falling || flying ? 0.3f : 0f);
        float ease = 1f - (float) Math.exp(-dt * 5f);
        d.wingRate += (Math.min(2.4f, rate) - d.wingRate) * ease;
        d.wingAmp += (amp - d.wingAmp) * ease;
        d.wingClock += dt * d.wingRate;
        if (d.wingClock > 10_000f) d.wingClock -= 10_000f;
        d.fold = MathHelper.clamp(d.fold + (s.sneaking ? dt : -dt) / 0.25f, 0f, 1f);

        // ---- pet: rides beside your head (it turns with it, so it can never end up inside it), trailing your turns by
        // a few degrees like a follower, and looks where you look
        float headYaw = s.bodyYaw + s.relativeHeadYaw;
        if (!d.petInit) { d.petYaw = headYaw; d.petInit = true; }
        float diff = MathHelper.wrapDegrees(headYaw - d.petYaw);
        float pk = 28f, pc = 2f * (float) Math.sqrt(pk) * 0.85f;
        d.petYawV += (pk * diff - pc * d.petYawV) * dt;
        d.petYaw += d.petYawV * dt;
        d.petLag = MathHelper.clamp(MathHelper.wrapDegrees(headYaw - d.petYaw), -PET_LAG, PET_LAG);
        d.petYaw = headYaw - d.petLag;
        float look = 1f - (float) Math.exp(-dt * 7f);
        d.petLookYaw += (MathHelper.clamp(s.relativeHeadYaw, -50f, 50f) - d.petLookYaw) * look;
        d.petLookPitch += (MathHelper.clamp(s.pitch, -40f, 40f) - d.petLookPitch) * look;
    }
}
