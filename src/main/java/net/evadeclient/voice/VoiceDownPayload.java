package net.evadeclient.voice;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Server -> client: a voice frame from {@code speaker}. Non-positional frames (mic test) play centred. */
public record VoiceDownPayload(UUID speaker, int seq, boolean positional, byte[] opus) implements CustomPayload {
    public static final Id<VoiceDownPayload> ID = new Id<>(Identifier.of("evade", "voice_down"));
    public static final PacketCodec<RegistryByteBuf, VoiceDownPayload> CODEC = PacketCodec.of(
            (p, buf) -> { buf.writeUuid(p.speaker); buf.writeVarInt(p.seq); buf.writeBoolean(p.positional); buf.writeByteArray(p.opus); },
            buf -> new VoiceDownPayload(buf.readUuid(), buf.readVarInt(), buf.readBoolean(), buf.readByteArray(VoiceUpPayload.MAX_FRAME)));

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
