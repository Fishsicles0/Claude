package net.evadeclient.util;

import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.tag.ItemTags;

import java.util.List;
import java.util.function.Consumer;

/** Shared "who should I target" settings so every combat module behaves the same way. */
public final class Targets {
    public final BooleanSetting players = new BooleanSetting("Players", true);
    public final BooleanSetting mobs = new BooleanSetting("Mobs", false);
    public final BooleanSetting animals = new BooleanSetting("Animals", false);
    public final BooleanSetting teammates = new BooleanSetting("Ignore Team", true);
    public final BooleanSetting invisible = new BooleanSetting("Ignore Invis", false);

    public void addTo(Consumer<BooleanSetting> adder) {
        for (BooleanSetting s : List.of(players, mobs, animals, teammates, invisible)) adder.accept(s);
    }

    public boolean isValid(Entity e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(e instanceof LivingEntity living) || e == mc.player || !living.isAlive() || living.isDead()) return false;
        if (e instanceof ArmorStandEntity) return false;
        if (invisible.get() && e.isInvisible()) return false;
        if (teammates.get() && mc.player.isTeammate(e)) return false;
        if (e instanceof PlayerEntity p) return players.get() && !p.isSpectator() && !p.isCreative()
                && !net.evadeclient.module.combat.AntiBot.isBot(p);
        if (e instanceof Monster) return mobs.get();
        if (e instanceof AnimalEntity) return animals.get();
        return false;
    }

    public static boolean isWeapon(ItemStack s) {
        return s.isIn(ItemTags.SWORDS) || s.getItem() instanceof AxeItem || s.getItem() instanceof MaceItem
                || s.getItem() instanceof TridentItem || s.isIn(ItemTags.SPEARS);
    }
}
