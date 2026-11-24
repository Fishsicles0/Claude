package net.evadeclient.replay;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.evadeclient.EvadeClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple Voice Chat plugin (its "voicechat" entrypoint, so this class only ever loads when that mod is installed):
 * hands the voice audio you hear, and your own, to {@link ReplayVoice} for recording, and lends it Opus.
 */
public final class VoiceChatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() { return "evade_replays"; }

    @Override
    public void initialize(VoicechatApi api) {
        double distance = 48;
        try { distance = api.getVoiceChatDistance(); } catch (Throwable ignored) {}
        ReplayVoice.setCodec(new Opus(api), distance);
        EvadeClient.LOG.info("Replays: Simple Voice Chat found, voice chat will be recorded into replays");
    }

    @Override
    public void registerEvents(EventRegistration events) {
        events.registerEvent(ClientReceiveSoundEvent.EntitySound.class,
                e -> ReplayVoice.recordEntity(e.getEntityId(), e.getDistance(), e.isWhispering(), e.getRawAudio()));
        events.registerEvent(ClientReceiveSoundEvent.LocationalSound.class,
                e -> ReplayVoice.recordLocation(e.getId(), e.getPosition().getX(), e.getPosition().getY(), e.getPosition().getZ(), e.getDistance(), e.getRawAudio()));
        events.registerEvent(ClientReceiveSoundEvent.StaticSound.class,
                e -> ReplayVoice.recordStatic(e.getId(), e.getRawAudio()));
        events.registerEvent(ClientSoundEvent.class,
                e -> ReplayVoice.recordOwn(e.isWhispering(), e.getRawAudio()));
    }

    /** One Opus encoder or decoder per speaker; recording and playback never share one. */
    private static final class Opus implements ReplayVoice.Codec {
        private final VoicechatApi api;
        private final Map<String, OpusEncoder> encoders = new HashMap<>();
        private final Map<String, OpusDecoder> decoders = new HashMap<>();

        Opus(VoicechatApi api) { this.api = api; }

        @Override
        public synchronized byte[] encode(String speaker, short[] pcm) {
            return encoders.computeIfAbsent(speaker, k -> api.createEncoder()).encode(pcm);
        }

        @Override
        public synchronized short[] decode(String speaker, byte[] opus) {
            return decoders.computeIfAbsent(speaker, k -> api.createDecoder()).decode(opus);
        }

        @Override
        public synchronized void resetDecoders() {
            for (OpusDecoder d : decoders.values()) try { d.close(); } catch (Throwable ignored) {}
            decoders.clear();
        }
    }
}
