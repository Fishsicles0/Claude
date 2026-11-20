package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.RotationUtil;
import net.evadeclient.util.Targets;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.TridentItem;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;

/**
 * While you draw a bow, crossbow or trident, aims at the best target with arrow drop and the target's movement
 * taken into account.
 */
public class BowAimbot extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 60, 10, 120, 5));
    private final NumberSetting fov = add(new NumberSetting("FOV", 90, 10, 360, 5));
    private final BooleanSetting predict = add(new BooleanSetting("Predict Movement", true));
    private final BooleanSetting walls = add(new BooleanSetting("Through Walls", false));
    private final Targets targets = new Targets();

    public BowAimbot() {
        super("BowAimbot", "Aims your bow with arrow drop accounted for.", Category.COMBAT);
        targets.addTo(this::add);
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (!mc.player.isUsingItem()) return;
        ItemStack held = mc.player.getActiveItem();
        double speed;
        if (held.getItem() instanceof BowItem) speed = BowItem.getPullProgress(mc.player.getItemUseTime()) * 3.0;
        else if (held.getItem() instanceof CrossbowItem) speed = 3.15;
        else if (held.getItem() instanceof TridentItem) speed = 2.5;
        else return;
        if (speed < 0.3) return;

        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1f);
        LivingEntity t = mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(range.get()), targets::isValid).stream()
                .filter(e -> mc.player.distanceTo(e) <= range.get())
                .filter(e -> fov.get() >= 360 || RotationUtil.angleTo(eye, look, e.getBoundingBox().getCenter()) <= fov.get() / 2)
                .filter(e -> walls.get() || mc.player.canSee(e))
                .min(Comparator.comparingDouble(e -> RotationUtil.angleTo(eye, look, e.getBoundingBox().getCenter()))).orElse(null);
        if (t == null) return;

        Vec3d target = t.getLerpedPos(ctx.tickDelta).add(0, t.getHeight() * 0.5, 0);
        double flightTicks = eye.distanceTo(target) / speed;
        if (predict.get()) target = target.add(t.getVelocity().multiply(flightTicks, 0, flightTicks));
        double dx = target.x - eye.x, dz = target.z - eye.z, dy = target.y - eye.y;
        double h = Math.sqrt(dx * dx + dz * dz);
        double g = 0.05, v2 = speed * speed;
        double root = v2 * v2 - g * (g * h * h + 2 * dy * v2);
        if (root < 0) return; // out of range at this charge
        float pitch = (float) -Math.toDegrees(Math.atan((v2 - Math.sqrt(root)) / (g * h)));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        mc.player.setYaw(yaw);
        mc.player.setPitch(MathHelper.clamp(pitch, -90, 90));
    }
}
