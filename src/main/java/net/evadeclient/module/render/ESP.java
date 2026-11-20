package net.evadeclient.module.render;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;

/** Draws the vanilla glowing outline around chosen entities (rendered through walls). */
public class ESP extends Module {
    private final BooleanSetting players = add(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = add(new BooleanSetting("Mobs", true));
    private final BooleanSetting animals = add(new BooleanSetting("Animals", false));
    private final BooleanSetting items = add(new BooleanSetting("Items", false));

    public ESP() {
        super("ESP", "Outlines entities through walls.", Category.RENDER);
    }

    public boolean shouldOutline(Entity e) {
        if (e == mc.player) return false;
        if (e instanceof PlayerEntity) return players.get();
        if (e instanceof Monster) return mobs.get();
        if (e instanceof AnimalEntity) return animals.get();
        if (e instanceof ItemEntity) return items.get();
        return false;
    }
}
