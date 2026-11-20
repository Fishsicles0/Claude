package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.evadeclient.util.PacketHooks;

/** Stops fall damage by telling the server you're on the ground while falling. */
public class NoFall extends Module {
    public NoFall() { super("NoFall", "Takes no fall damage.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.fallDistance > 2.5 && !mc.player.isGliding() && !mc.player.getAbilities().flying) {
            PacketHooks.sendRaw(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        }
    }
}
