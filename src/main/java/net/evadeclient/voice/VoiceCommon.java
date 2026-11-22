package net.evadeclient.voice;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Runs on both sides. Registers the voice packets, and on the server (dedicated, LAN or singleplayer)
 * relays each speaker's frames to nearby players who also run Evade. Audio never touches the disk and the
 * server never decodes it; it only forwards bytes.
 */
public class VoiceCommon implements ModInitializer {
    /** Players hear each other within this many blocks. */
    public static final double RANGE = 48;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(VoiceUpPayload.ID, VoiceUpPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VoiceDownPayload.ID, VoiceDownPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(VoiceUpPayload.ID, (payload, context) -> {
            ServerPlayerEntity speaker = context.player();
            if (payload.opus().length == 0) return;
            if (payload.loopback()) {
                // mic test: echo straight back to the speaker, centred
                if (ServerPlayNetworking.canSend(speaker, VoiceDownPayload.ID))
                    ServerPlayNetworking.send(speaker, new VoiceDownPayload(speaker.getUuid(), payload.seq(), false, payload.opus()));
            }
            var down = new VoiceDownPayload(speaker.getUuid(), payload.seq(), true, payload.opus());
            for (ServerPlayerEntity listener : speaker.getEntityWorld().getPlayers()) {
                if (listener == speaker || listener.squaredDistanceTo(speaker) > RANGE * RANGE) continue;
                if (ServerPlayNetworking.canSend(listener, VoiceDownPayload.ID)) ServerPlayNetworking.send(listener, down);
            }
        });
    }
}
