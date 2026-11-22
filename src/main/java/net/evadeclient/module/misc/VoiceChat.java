package net.evadeclient.module.misc;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.voice.VoiceDownPayload;
import net.evadeclient.voice.VoiceUpPayload;
import net.evadeclient.voice.client.VoicePlayback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.util.UUID;

/**
 * Proximity voice chat between Evade players. Works in singleplayer/LAN worlds hosted with Evade and on any
 * Fabric server that has Evade installed. Voices fade with distance and pan with direction.
 */
public class VoiceChat extends Module {
    public final KeySetting talkKey = add(new KeySetting("Talk Key", GLFW.GLFW_KEY_V));
    public final BooleanSetting pushToTalk = add(new BooleanSetting("Push To Talk", true));
    public final NumberSetting activation = add(new NumberSetting("Voice Activation Level", 20, 0, 100, 5));
    public final NumberSetting micVolume = add(new NumberSetting("Mic Volume %", 100, 0, 200, 10));
    public final NumberSetting voiceVolume = add(new NumberSetting("Voice Volume %", 100, 0, 200, 10));
    public final BooleanSetting micTest = add(new BooleanSetting("Hear Yourself (Mic Test)", false));
    public final BooleanSetting speakersHud = add(new BooleanSetting("Show Who's Talking", true));

    private final VoicePlayback playback = new VoicePlayback(() -> voiceVolume.get() / 100.0);
    private volatile boolean capturing;
    private Thread captureThread;
    private OpusEncoder encoder;
    private int seq;
    private volatile int micLevel;           // 0..100, for the HUD meter
    private volatile long lastLoud;          // voice activation hang time
    private volatile boolean transmitting;
    private volatile boolean micFailed;
    public volatile int framesSent;

    public VoiceChat() {
        super("Voice Chat", "Proximity voice with other Evade players (server or LAN needs Evade).", Category.MISC);
    }

    public VoicePlayback playback() { return playback; }

    /** Does the server we're on relay Evade voice? */
    public static boolean serverSupported() {
        try { return ClientPlayNetworking.canSend(VoiceUpPayload.ID); } catch (Exception e) { return false; }
    }

    /** Hook the incoming voice packets (once, at startup). */
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(VoiceDownPayload.ID, (payload, context) -> {
            VoiceChat v = EvadeClient.modules.get(VoiceChat.class);
            if (v != null && v.isEnabled()) v.playback.receive(payload);
        });
    }

    @Override
    protected void onEnable() {
        try {
            encoder = new OpusEncoder(VoicePlayback.RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24000);
            encoder.setComplexity(5);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        } catch (Exception e) {
            EvadeClient.LOG.error("Voice: couldn't create the Opus encoder", e);
        }
        playback.start();
        capturing = true;
        micFailed = false;
        captureThread = new Thread(this::captureLoop, "Evade Voice mic");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    @Override
    protected void onDisable() {
        capturing = false;
        if (captureThread != null) captureThread.interrupt();
        playback.stop();
        transmitting = false;
    }

    @Override
    protected boolean alwaysRunHooks() { return true; }

    private void captureLoop() {
        TargetDataLine line;
        try {
            AudioFormat fmt = new AudioFormat(VoicePlayback.RATE, 16, 1, true, false);
            line = AudioSystem.getTargetDataLine(fmt);
            line.open(fmt, VoicePlayback.FRAME * 2 * 8);
            line.start();
        } catch (Exception e) {
            micFailed = true;
            EvadeClient.LOG.warn("Voice: no microphone available ({}); you can still hear others", e.toString());
            return;
        }
        byte[] buf = new byte[VoicePlayback.FRAME * 2];
        short[] pcm = new short[VoicePlayback.FRAME];
        while (capturing) {
            int n = line.read(buf, 0, buf.length);
            if (n < buf.length) continue;
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((buf[i * 2] & 0xFF) | (buf[i * 2 + 1] << 8));
            processFrame(pcm);
        }
        line.stop();
        line.close();
    }

    /** Push one 20 ms frame through gain, the talk decision, Opus and the network. Public for the mic test path. */
    public void processFrame(short[] input) {
        short[] pcm = input.clone();
        double gain = micVolume.get() / 100.0;
        long sum = 0;
        for (int i = 0; i < pcm.length; i++) {
            int v = (int) Math.max(-32768, Math.min(32767, pcm[i] * gain));
            pcm[i] = (short) v;
            sum += (long) v * v;
        }
        double rms = Math.sqrt(sum / (double) pcm.length);
        int level = (int) Math.max(0, Math.min(100, (20 * Math.log10(Math.max(1, rms)) - 20) * 100 / 70)); // ~20..90 dB -> 0..100
        micLevel = level;

        boolean talk;
        if (pushToTalk.get()) {
            talk = talkKey.isDown() && mc.currentScreen == null;
        } else {
            if (level >= activation.get()) lastLoud = System.currentTimeMillis();
            talk = System.currentTimeMillis() - lastLoud < 300;
        }
        transmitting = talk;
        if (!talk || encoder == null || !serverSupported()) return;
        try {
            byte[] out = new byte[VoiceUpPayload.MAX_FRAME];
            int len = encoder.encode(pcm, 0, VoicePlayback.FRAME, out, 0, out.length);
            if (len <= 0) return;
            byte[] frame = java.util.Arrays.copyOf(out, len);
            var payload = new VoiceUpPayload(seq++, micTest.get(), frame);
            mc.execute(() -> { if (mc.getNetworkHandler() != null) ClientPlayNetworking.send(payload); });
            framesSent++;
        } catch (Exception e) {
            EvadeClient.LOG.debug("Voice: encode failed", e);
        }
    }

    /** Test hook: force a talk decision without a key (used by the automated tests). */
    public void forceTalkForTest() { pushToTalk.set(false); activation.set(0.0); }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        if (mc.player == null) return;
        var tr = mc.textRenderer;
        int x = 4, y = ctx.getScaledWindowHeight() / 2 - 30;
        if (transmitting || (pushToTalk.get() && talkKey.isDown() && mc.currentScreen == null)) {
            String s = !serverSupported() ? "Voice: this server has no Evade voice" : micFailed ? "Voice: no microphone found" : "Talking";
            int w = tr.getWidth(s) + 18;
            ctx.fill(x, y, x + w, y + 13, 0xB0000000);
            ctx.fill(x + 3, y + 3, x + 10, y + 10, serverSupported() && !micFailed ? 0xFF22C55E : 0xFFE11D2E);
            ctx.fill(x + 3, y + 10, x + 3 + (int) (7 * micLevel / 100.0), y + 11, 0xFFFFFFFF);
            ctx.drawTextWithShadow(tr, s, x + 14, y + 3, 0xFFFFFFFF);
            y += 16;
        }
        if (!speakersHud.get() || mc.world == null) return;
        for (UUID id : playback.speaking().keySet()) {
            var p = mc.world.getPlayerByUuid(id);
            String name = p != null ? p.getName().getString() : "Someone";
            int w = tr.getWidth(name) + 18;
            ctx.fill(x, y, x + w, y + 13, 0xB0000000);
            ctx.fill(x + 3, y + 3, x + 10, y + 10, 0xFF22C55E);
            ctx.drawTextWithShadow(tr, name, x + 14, y + 3, 0xFFFFFFFF);
            y += 15;
        }
    }
}
