package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;

/** Uses less hunger by not telling the server when you start sprinting. */
public class AntiHunger extends Module {
    public AntiHunger() { super("AntiHunger", "Lose hunger more slowly.", Category.SERVER); }

    @Override
    public boolean onSendPacket(Packet<?> packet) {
        return packet instanceof ClientCommandC2SPacket c && c.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING;
    }
}
