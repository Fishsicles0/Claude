package de.maxhenkel.voicechat.api.events;

public interface ClientSoundEvent extends ClientEvent {
    short[] getRawAudio();
    void setRawAudio(short[] audio);
    boolean isWhispering();
}
