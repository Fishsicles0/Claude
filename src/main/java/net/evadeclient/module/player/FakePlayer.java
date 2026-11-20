package net.evadeclient.module.player;

import com.mojang.authlib.GameProfile;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;

import java.util.UUID;

/**
 * Spawns a copy of you that only you can see. Use it to try out KillAura, CrystalAura, ESP, Nametags and
 * other modules without a second player. The server doesn't know it exists, so hits on it do nothing.
 */
public class FakePlayer extends Module {
    private static final int ID = -133713;
    private final BooleanSetting copyInventory = add(new BooleanSetting("Copy Armor & Items", true));
    private OtherClientPlayerEntity fake;

    public FakePlayer() {
        super("FakePlayer", "Spawns a client-side copy of you to test modules on.", Category.PLAYER);
    }

    @Override
    protected void onEnable() {
        fake = new OtherClientPlayerEntity(mc.world, new GameProfile(UUID.randomUUID(), "EvadeDummy"));
        fake.setId(ID);
        fake.refreshPositionAndAngles(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYaw(), mc.player.getPitch());
        fake.setHeadYaw(mc.player.getHeadYaw());
        fake.setBodyYaw(mc.player.getBodyYaw());
        if (copyInventory.get()) {
            for (EquipmentSlot s : EquipmentSlot.values()) fake.equipStack(s, mc.player.getEquippedStack(s).copy());
        }
        fake.setHealth(mc.player.getHealth());
        fake.setAbsorptionAmount(mc.player.getAbsorptionAmount());
        mc.world.addEntity(fake);
    }

    @Override
    protected void onDisable() {
        if (fake != null && mc.world != null) mc.world.removeEntity(ID, Entity.RemovalReason.DISCARDED);
        fake = null;
    }

    @Override
    public void onTick() {
        if (fake != null && (fake.isRemoved() || fake.getEntityWorld() != mc.world)) { fake = null; setEnabled(false); }
    }

    public static boolean isFake(Entity e) { return e != null && e.getId() == ID; }

    public OtherClientPlayerEntity entity() { return fake; }
}
