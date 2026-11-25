package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.HitConfirm;
import net.evadeclient.util.Projection;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Numbers that float up from things YOU hit, once the server confirms the hit (it names you as the cause in its
 * damage event). The number is your own swing's damage as your client knows it: your attack damage, the cooldown
 * and a critical hit, before the target's armour, enchantments and effects, which the client can't see. Projectile
 * hits, and swings it can't price, get a hit marker instead. It never shows or reads anyone's health, and it stays
 * where the hit happened instead of following the target.
 */
public class DamageNumbers extends Module {
    public final ModeSetting show = add(new ModeSetting("Show", "Your Damage", "Your Damage", "Hit Marker")
            .describe("Your Damage: your swing's damage before armour. Hit Marker: just a mark where each confirmed hit landed."));
    public final NumberSetting duration = add(new NumberSetting("Duration", 1.0, 0.4, 3.0, 0.1)
            .describe("Seconds each number stays up while it floats away."));
    public final NumberSetting size = add(new NumberSetting("Size", 1.0, 0.5, 2.5, 0.1)
            .describe("How big the numbers are drawn."));
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFFFF)
            .describe("Colour of normal damage numbers."));
    public final ColorSetting critColor = add(new ColorSetting("Crit Color", 0xFFFFB020)
            .describe("Colour of damage numbers from critical hits."));
    public final BooleanSetting shadow = add(new BooleanSetting("Text Shadow", true)
            .describe("Draws a dark drop shadow under the numbers."));
    public final BooleanSetting distanceScaling = add(new BooleanSetting("Distance Scaling", true)
            .describe("Numbers further away are drawn smaller."));

    public DamageNumbers() {
        super("Damage Numbers", "Floating numbers or hit markers on things you hit, once the server confirms the hit.", Category.VISUALS);
    }

    /** The damage your swing at {@code target} deals before its defences, as your client works it out. */
    public static float swingDamage(net.minecraft.entity.player.PlayerEntity p, Entity target) {
        float base = (float) p.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        float c = p.getAttackCooldownProgress(0.5f);
        base *= 0.2f + c * c * 0.8f;
        if (critical(p, target, c)) base *= 1.5f;
        return base;
    }

    /** Vanilla's critical hit rule, from your own state only. */
    public static boolean critical(net.minecraft.entity.player.PlayerEntity p, Entity target, float cooldown) {
        return cooldown > 0.9f && p.fallDistance > 0 && !p.isOnGround() && !p.isClimbing() && !p.isTouchingWater()
                && !p.hasStatusEffect(StatusEffects.BLINDNESS) && !p.hasVehicle() && target instanceof LivingEntity && !p.isSprinting();
    }

    @Override
    public void onAttack(Entity target) {
        if (mc.player == null) return;
        float c = mc.player.getAttackCooldownProgress(0.5f);
        HitConfirm.swing(target, swingDamage(mc.player, target), critical(mc.player, target, c));
    }

    /** Every module hears this, enabled or not: confirmed hits also drive Crosshair's hit marker. */
    @Override
    public void onServerPacket(Packet<?> packet) {
        if (packet instanceof EntityDamageS2CPacket p) HitConfirm.onDamage(p);
    }

    /** What is drawn for a hit: the number, or the marker. */
    public String label(HitConfirm.Hit h) {
        if (show.is("Hit Marker") || h.projectile() || h.damage() < 0) return "×";
        float d = h.damage();
        return Math.abs(d - Math.round(d)) < 0.05f ? String.valueOf(Math.round(d)) : String.format(Locale.ROOT, "%.1f", d);
    }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        long life = Math.round(duration.get() * 1000);
        var tr = mc.textRenderer;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (HitConfirm.Hit h : HitConfirm.recent(life)) {
            float t = (System.currentTimeMillis() - h.at()) / (float) life;
            Vec3d pos = h.pos().add(0, 0.25 + t * 0.6, 0);
            double[] p = Projection.toScreen(pos);
            if (p == null) continue;
            float s = size.getFloat();
            if (distanceScaling.get()) s *= (float) Math.max(0.5, Math.min(1.5, 6 / Math.max(0.001, pos.distanceTo(cam))));
            String text = label(h);
            int base = h.crit() ? critColor.get() : color.get();
            float alpha = t < 0.7f ? 1f : Math.max(0, 1 - (t - 0.7f) / 0.3f);
            int a = Math.round((base >>> 24) * alpha);
            if (a < 8) continue;
            int argb = a << 24 | (base & 0xFFFFFF);
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate((float) p[0], (float) p[1]);
            ctx.getMatrices().scale(s, s);
            ctx.drawText(tr, text, -tr.getWidth(text) / 2, -4, argb, shadow.get());
            ctx.getMatrices().popMatrix();
        }
    }
}
