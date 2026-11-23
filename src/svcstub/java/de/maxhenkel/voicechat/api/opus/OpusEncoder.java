package de.maxhenkel.voicechat.api.opus;

public interface OpusEncoder {
    byte[] encode(short[] pcm);
    void resetState();
    boolean isClosed();
    void close();
}
