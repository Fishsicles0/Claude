package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.Position;

import java.util.UUID;

public interface ClientReceiveSoundEvent extends ClientEvent {
    UUID getId();
    short[] getRawAudio();
    void setRawAudio(short[] audio);

    interface EntitySound extends ClientReceiveSoundEvent {
        UUID getEntityId();
        boolean isWhispering();
        float getDistance();
    }

    interface LocationalSound extends ClientReceiveSoundEvent {
        Position getPosition();
        float getDistance();
    }

    interface StaticSound extends ClientReceiveSoundEvent {
    }
}
