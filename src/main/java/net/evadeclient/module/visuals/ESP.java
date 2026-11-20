package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Highlights players, mobs, animals and items through walls. Box mode draws outlines (optionally filled);
 * Glow mode uses the game's own glowing outline (like Chams).
 */
public class ESP extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Box", "Box", "Glow", "Both"));
    private final BooleanSetting players = add(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = add(new BooleanSetting("Mobs", true));
    private final BooleanSetting animals = add(new BooleanSetting("Animals", false));
    private final BooleanSetting items = add(new BooleanSetting("Items", false));
    private final BooleanSetting fill = add(new BooleanSetting("Fill", false));
    private final ColorSetting playerColor = add(new ColorSetting("Player Color", 0xFFE11D2E));
    private final ColorSetting mobColor = add(new ColorSetting("Mob Color", 0xFFFF8A1F));
    private final NumberSetting range = add(new NumberSetting("Range", 128, 16, 512, 16));

    public ESP() { super("ESP", "Shows players and mobs through walls.", Category.VISUALS); }

    public boolean glows(Entity e) { return isEnabled() && !mode.is("Box") && wanted(e); }

    private boolean wanted(Entity e) {
        if (e == mc.player || mc.player.squaredDistanceTo(e) > range.get() * range.get()) return false;
        if (e instanceof PlayerEntity p) return players.get() && !p.isSpectator();
        if (e instanceof Monster) return mobs.get();
        if (e instanceof AnimalEntity) return animals.get();
        if (e instanceof ItemEntity) return items.get();
        return false;
    }

    private int color(Entity e) {
        if (e instanceof PlayerEntity) return playerColor.get();
        if (e instanceof Monster) return mobColor.get();
        if (e instanceof ItemEntity) return 0xFFFFFFFF;
        return 0xFF3BE37A;
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (mode.is("Glow")) return;
        for (Entity e : mc.world.getEntities()) {
            if (!wanted(e)) continue;
            Vec3d pos = e.getLerpedPos(ctx.tickDelta);
            Box box = e.getBoundingBox().offset(pos.subtract(e.getEntityPos()));
            int c = color(e);
            if (fill.get()) ctx.boxFilled(box, ColorUtil.withAlpha(c, 40));
            ctx.boxOutline(box, c, 1.8f);
        }
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
