package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.PacketHooks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds back your movement packets while on, so to other players you stand still, then sends them all when you
 * turn it off. Releases automatically after a limit to avoid timing out.
 */
public class Blink extends Module {
    private final NumberSetting limit = add(new NumberSetting("Max Packets", 200, 20, 1000, 10));
    private final List<Packet<?>> held = new ArrayList<>();

    public Blink() { super("Blink", "Freezes your position for others until turned off.", Category.MOVEMENT); }

    @Override
    public boolean onSendPacket(Packet<?> packet) {
        if (!(packet instanceof PlayerMoveC2SPacket)) return false;
        held.add(packet);
        if (held.size() >= limit.getInt()) setEnabled(false);
        return true;
    }

    @Override
    protected void onDisable() {
        List<Packet<?>> copy = new ArrayList<>(held);
        held.clear();
        copy.forEach(PacketHooks::sendRaw);
    }

    @Override
    public String getSuffix() { return String.valueOf(held.size()); }
}
