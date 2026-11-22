package net.evadeclient.voice;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: one 20 ms Opus frame from the speaker's microphone. */
public record VoiceUpPayload(int seq, boolean loopback, byte[] opus) implements CustomPayload {
    public static final Id<VoiceUpPayload> ID = new Id<>(Identifier.of("evade", "voice_up"));
    public static final int MAX_FRAME = 1024;
    public static final PacketCodec<RegistryByteBuf, VoiceUpPayload> CODEC = PacketCodec.of(
            (p, buf) -> { buf.writeVarInt(p.seq); buf.writeBoolean(p.loopback); buf.writeByteArray(p.opus); },
            buf -> new VoiceUpPayload(buf.readVarInt(), buf.readBoolean(), buf.readByteArray(MAX_FRAME)));

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
