package net.evadeclient.module.visuals;

import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Projection;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/** Clear tags above players, visible through walls: name, health, distance and (optionally) armour and held item. */
public class Nametags extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 64, 8, 256, 8));
    private final NumberSetting scale = add(new NumberSetting("Scale", 1, 0.5, 2, 0.1));
    private final BooleanSetting health = add(new BooleanSetting("Health", true));
    private final BooleanSetting distance = add(new BooleanSetting("Distance", true));
    private final BooleanSetting items = add(new BooleanSetting("Armor & Items", true));

    public Nametags() { super("Nametags", "Clear player tags through walls.", Category.VISUALS); }

    /** Called from the HUD each frame. */
    public void draw(DrawContext ctx, float delta) {
        var tr = mc.textRenderer;
        double max = range.get() * range.get();
        List<PlayerEntity> list = mc.world.getPlayers().stream()
                .filter(p -> p != mc.player && !p.isSpectator() && mc.player.squaredDistanceTo(p) <= max)
                .sorted(Comparator.comparingDouble(p -> -mc.player.squaredDistanceTo(p)))
                .map(p -> (PlayerEntity) p).toList();
        for (PlayerEntity p : list) {
            Vec3d top = p.getLerpedPos(delta).add(0, p.getHeight() + 0.35, 0);
            double[] s = Projection.toScreen(top);
            if (s == null) continue;
            float sc = scale.getFloat();
            String name = p.getName().getString();
            StringBuilder line = new StringBuilder(name);
            float hp = p.getHealth() + p.getAbsorptionAmount();
            if (health.get()) line.append("  ").append(String.format("%.1f", hp / 2)).append("❤");
            if (distance.get()) line.append("  §7").append(Math.round(mc.player.distanceTo(p))).append("m");
            String text = line.toString();
            int w = tr.getWidth(text) + 8;
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate((float) s[0], (float) s[1]);
            m.scale(sc, sc);
            Theme.round(ctx, -w / 2, -12, w / 2, 0, 0xC00A0A0C);
            ctx.fill(-w / 2 + 2, -1, -w / 2 + 2 + Math.round((w - 4) * Math.min(1, hp / p.getMaxHealth())), 0, ColorUtil.healthColor(hp / p.getMaxHealth()));
            ctx.drawTextWithShadow(tr, text, -w / 2 + 4, -10, mc.player.isTeammate(p) ? 0xFF3BE37A : 0xFFFFFFFF);
            if (items.get()) {
                int x = -54;
                for (ItemStack it : new ItemStack[]{p.getMainHandStack(), p.getEquippedStack(HudDraw.ARMOR_TOP_DOWN[0]), p.getEquippedStack(HudDraw.ARMOR_TOP_DOWN[1]),
                        p.getEquippedStack(HudDraw.ARMOR_TOP_DOWN[2]), p.getEquippedStack(HudDraw.ARMOR_TOP_DOWN[3]), p.getOffHandStack()}) {
                    if (!it.isEmpty()) {
                        ctx.drawItem(it, x, -30);
                        ctx.drawStackOverlay(tr, it, x, -30);
                    }
                    x += 18;
                }
            }
            m.popMatrix();
        }
    }
}
