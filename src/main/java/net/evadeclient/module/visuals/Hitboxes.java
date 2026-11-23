package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;

/**
 * Restyles the game's own hitbox view (F3+B): box colour and line width, the eye-height line, the look arrow, and
 * which kinds of entity get a box. It only changes how vanilla draws what F3+B already shows: it never turns the view
 * on, never adds boxes vanilla doesn't draw (no fill, no enlarging, nothing through walls that vanilla hides).
 */
public class Hitboxes extends Module {
    /** Vanilla's colours in EntityHitboxDebugRenderer, which tell its boxes apart. */
    public static final int VANILLA_BOX = 0xFFFFFFFF, VANILLA_EYE = 0xFFFF0000, VANILLA_LOOK = 0xFF0000FF;

    public final ColorSetting boxColor = add(new ColorSetting("Box Color", 0xFFFFFFFF)
            .describe("Colour of the hitbox outline."));
    public final NumberSetting lineWidth = add(new NumberSetting("Line Width", 2.5, 1, 5, 0.5)
            .describe("Thickness of the hitbox lines. The game's own is 2.5."));
    public final BooleanSetting eyeLine = add(new BooleanSetting("Eye Line", true)
            .describe("The line at eye height on players and mobs."));
    public final ColorSetting eyeColor = add(new ColorSetting("Eye Line Color", 0xFFE11D2E)
            .describe("Colour of the eye-height line."));
    public final BooleanSetting lookVector = add(new BooleanSetting("Look Vector", true)
            .describe("The arrow showing where each entity is looking."));
    public final BooleanSetting players = add(new BooleanSetting("Players", true).describe("Show hitboxes of players."));
    public final BooleanSetting items = add(new BooleanSetting("Items", true).describe("Show hitboxes of dropped items."));
    public final BooleanSetting projectiles = add(new BooleanSetting("Projectiles", true)
            .describe("Show hitboxes of arrows, tridents, snowballs, pearls and other projectiles."));
    public final BooleanSetting others = add(new BooleanSetting("Other Entities", true)
            .describe("Show hitboxes of mobs, animals, vehicles, item frames and everything else."));

    public Hitboxes() {
        super("Hitboxes", "Colour, line width and filters for the F3+B hitbox view.", Category.VISUALS);
    }

    public static Hitboxes active() {
        if (EvadeClient.modules == null) return null;
        Hitboxes h = EvadeClient.modules.get(Hitboxes.class);
        return h != null && h.isEnabled() ? h : null;
    }

    /** Whether F3+B should draw {@code e}'s hitbox under the type filters. */
    public boolean shows(Entity e) {
        if (e instanceof PlayerEntity) return players.get();
        if (e instanceof ItemEntity) return items.get();
        if (e instanceof ProjectileEntity) return projectiles.get();
        return others.get();
    }

    /**
     * The style for one of vanilla's outlines, given the colour vanilla chose: the main box and the eye line get
     * their colours (a hidden eye line gets a style that draws nothing), every outline gets the line width.
     */
    public DrawStyle restyle(int vanillaStroke) {
        float width = lineWidth.getFloat();
        if (vanillaStroke == VANILLA_BOX) return DrawStyle.stroked(boxColor.get(), width);
        if (vanillaStroke == VANILLA_EYE) return eyeLine.get() ? DrawStyle.stroked(eyeColor.get(), width) : new DrawStyle(0, 0f, 0);
        return DrawStyle.stroked(vanillaStroke, width);
    }

    /** The colour for vanilla's centre point (white like the box, so it follows Box Color). */
    public int pointColor(int vanilla) {
        return vanilla == VANILLA_BOX ? boxColor.get() : vanilla;
    }
}
