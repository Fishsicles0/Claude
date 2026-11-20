package net.evadeclient.module.render;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.TargetTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Draws a line from your crosshair to every player. Colour shows distance (red = close),
 * team colour, or a fixed colour; your current fight target is highlighted.
 */
public class PlayerTracers extends Module {
    private final ModeSetting color = add(new ModeSetting("Color", "Distance", "Distance", "Team", "Static"));
    private final ModeSetting point = add(new ModeSetting("Point", "Body", "Feet", "Body", "Head"));
    private final NumberSetting range = add(new NumberSetting("Range", 256, 16, 1024, 16));
    private final NumberSetting width = add(new NumberSetting("Line Width", 1.5, 0.5, 5, 0.5));
    private final BooleanSetting boxes = add(new BooleanSetting("Boxes", true));
    private final BooleanSetting highlightTarget = add(new BooleanSetting("Mark Target", true));
    private final BooleanSetting ignoreTeam = add(new BooleanSetting("Ignore Team", false));

    private int count;

    public PlayerTracers() {
        super("PlayerTracers", "Lines pointing to every player.", Category.VISUALS);
    }

    @Override
    public void onRender(RenderContext ctx) {
        Vec3d origin = ctx.crosshairPoint();
        double maxSq = range.get() * range.get();
        var target = TargetTracker.get();
        count = 0;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator() || !p.isAlive()) continue;
            if (ignoreTeam.get() && mc.player.isTeammate(p)) continue;
            Vec3d pos = p.getLerpedPos(ctx.tickDelta);
            double distSq = pos.squaredDistanceTo(ctx.cameraPos());
            if (distSq > maxSq) continue;
            count++;

            int c = colorFor(p, Math.sqrt(distSq));
            float w = width.getFloat();
            if (highlightTarget.get() && p == target) {
                c = 0xFFFFFFFF;
                w += 1;
            }
            double y = switch (point.get()) {
                case "Feet" -> 0;
                case "Head" -> p.getStandingEyeHeight();
                default -> p.getHeight() / 2;
            };
            ctx.line(origin, pos.add(0, y, 0), c, w);
            if (boxes.get()) {
                Box box = p.getBoundingBox().offset(pos.subtract(p.getEntityPos()));
                ctx.boxOutline(box, c, 1.5f);
            }
        }
    }

    private int colorFor(PlayerEntity p, double dist) {
        return switch (color.get()) {
            case "Team" -> {
                int team = p.getTeamColorValue();
                yield team == 0xFFFFFF ? 0xFFFFFFFF : 0xFF000000 | team;
            }
            case "Static" -> ColorUtil.ACCENT;
            default -> ColorUtil.healthColor((float) Math.min(1, dist / 60.0));
        };
    }

    @Override
    public String getSuffix() { return String.valueOf(count); }
}
