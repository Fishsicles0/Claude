package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;

/** Lets modules see (and cancel) packets the client is about to send. */
public final class PacketHooks {
    /** Set while a module sends packets itself so they aren't filtered again. */
    private static int bypass;

    private PacketHooks() {}

    public static boolean bypassing() { return bypass > 0; }

    /** Send a packet that skips every module filter (e.g. Blink releasing its queue). */
    public static void sendRaw(Packet<?> packet) {
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null) return;
        bypass++;
        try { nh.sendPacket(packet); } finally { bypass--; }
    }
}
