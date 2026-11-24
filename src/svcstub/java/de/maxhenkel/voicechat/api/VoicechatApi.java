package de.maxhenkel.voicechat.api;

import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

public interface VoicechatApi {
    OpusEncoder createEncoder();
    OpusDecoder createDecoder();
    double getVoiceChatDistance();
}
