package de.maxhenkel.voicechat.api.opus;

public interface OpusDecoder {
    short[] decode(byte[] opus);
    void resetState();
    boolean isClosed();
    void close();
}
