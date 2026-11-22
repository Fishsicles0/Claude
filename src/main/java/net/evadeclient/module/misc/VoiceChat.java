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
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.voice.VoiceDownPayload;
import net.evadeclient.voice.VoiceUpPayload;
import net.evadeclient.voice.client.VoicePlayback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.util.UUID;

/**
 * Voice chat with two engines, picked per server:
 * <ul>
 *   <li><b>Simple Voice Chat</b> (by henkelmax, installed from Modrinth by the Evade Installer) wherever the server
 *       runs it. Evade then stays out of the way: no second microphone, no duplicate icons.</li>
 *   <li><b>Evade voice</b> on servers/LAN worlds that only have Evade's relay: proximity voice over the Minecraft
 *       connection, Opus encoded, fading with distance and panned by direction.</li>
 * </ul>
 */
public class VoiceChat extends Module {
    public enum Engine { SIMPLE_VOICE_CHAT, EVADE, NONE }

    private static final Identifier SVC_CHANNEL = Identifier.of("voicechat", "request_secret");
    private static Engine testOverride;

    public final KeySetting talkKey = add(new KeySetting("Talk Key", GLFW.GLFW_KEY_V));
    public final BooleanSetting pushToTalk = add(new BooleanSetting("Push To Talk", true));
    public final NumberSetting activation = add(new NumberSetting("Voice Activation Level", 20, 0, 100, 5));
    public final NumberSetting micVolume = add(new NumberSetting("Mic Volume %", 100, 0, 200, 10));
    public final NumberSetting voiceVolume = add(new NumberSetting("Voice Volume %", 100, 0, 200, 10));
    public final BooleanSetting micTest = add(new BooleanSetting("Hear Yourself (Mic Test)", false));
    public final BooleanSetting speakersHud = add(new BooleanSetting("Show Who's Talking", true));
    public final StatusSetting engineStatus = add((StatusSetting) new StatusSetting("Voice Engine", VoiceChat::engineLabel,
            svcInstalled() ? "click: voice menu" : "auto").onClick(VoiceChat::openSvcMenu));

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
        super("Voice Chat", "Voice chat: Simple Voice Chat where the server has it, Evade voice where it doesn't.", Category.MISC);
        setEnabled(true);
    }

    // ------------------------------------------------------------------ engine choice

    public static boolean svcInstalled() { return FabricLoader.getInstance().isModLoaded("voicechat"); }

    /** Does the server we're on run Simple Voice Chat? */
    public static boolean serverHasSvc() {
        try { return svcInstalled() && ClientPlayNetworking.canSend(SVC_CHANNEL); } catch (Exception e) { return false; }
    }

    /** Which engine handles voice on the current server. */
    public static Engine engine() {
        if (testOverride != null) return testOverride;
        if (serverHasSvc()) return Engine.SIMPLE_VOICE_CHAT;
        if (serverSupported()) return Engine.EVADE;
        return Engine.NONE;
    }

    /** Test hook: force an engine (null = automatic). */
    public static void overrideEngineForTest(Engine e) { testOverride = e; }

    private static String engineLabel() {
        if (EvadeClient.mc().getNetworkHandler() == null) return svcInstalled() ? "Simple Voice Chat ready" : "Evade voice";
        return switch (engine()) {
            case SIMPLE_VOICE_CHAT -> "Simple Voice Chat";
            case EVADE -> "Evade voice";
            case NONE -> "Not available here";
        };
    }

    /** Open Simple Voice Chat's menu by triggering its own "Voice Chat Menu" keybinding. */
    public static void openSvcMenu() {
        KeyBinding menu = findBinding("key.voice_chat");
        if (menu == null) {
            net.evadeclient.command.CommandManager.print("§7Voice engine: §c" + engineLabel()
                    + (svcInstalled() ? "" : "§7. Run the Evade Installer with voice chat on to add Simple Voice Chat."));
            return;
        }
        var mc = EvadeClient.mc();
        if (mc.currentScreen != null) mc.setScreen(null);
        KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(menu));
    }

    private static KeyBinding findBinding(String id) {
        var mc = EvadeClient.mc();
        if (mc.options == null) return null;
        for (KeyBinding k : mc.options.allKeys) if (id.equals(k.getId())) return k;
        return null;
    }

    /**
     * Is the talk key held? With Simple Voice Chat installed, V opens its menu, so Evade voice uses SVC's own
     * push-to-talk binding instead (or voice activation if that's unbound). Otherwise Evade's Talk Key.
     */
    private boolean talkHeld() {
        if (mc.currentScreen != null) return false;
        if (svcInstalled()) {
            KeyBinding ptt = findBinding("key.push_to_talk");
            return ptt != null && !ptt.isUnbound() && ptt.isPressed();
        }
        return talkKey.isDown();
    }

    private boolean usePushToTalk() {
        if (svcInstalled()) {
            KeyBinding ptt = findBinding("key.push_to_talk");
            return ptt != null && !ptt.isUnbound();
        }
        return pushToTalk.get();
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

    /** Only holds the microphone while Evade voice is the active engine (never alongside Simple Voice Chat). */
    private void captureLoop() {
        TargetDataLine line = null;
        byte[] buf = new byte[VoicePlayback.FRAME * 2];
        short[] pcm = new short[VoicePlayback.FRAME];
        boolean warned = false;
        while (capturing) {
            if (engine() != Engine.EVADE || mc.getNetworkHandler() == null) {
                if (line != null) { line.stop(); line.close(); line = null; }
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                continue;
            }
            if (line == null) {
                try {
                    AudioFormat fmt = new AudioFormat(VoicePlayback.RATE, 16, 1, true, false);
                    line = AudioSystem.getTargetDataLine(fmt);
                    line.open(fmt, VoicePlayback.FRAME * 2 * 8);
                    line.start();
                    micFailed = false;
                } catch (Exception e) {
                    line = null;
                    micFailed = true;
                    if (!warned) EvadeClient.LOG.warn("Voice: no microphone available ({}); you can still hear others", e.toString());
                    warned = true;
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    continue;
                }
            }
            int n = line.read(buf, 0, buf.length);
            if (n < buf.length) continue;
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((buf[i * 2] & 0xFF) | (buf[i * 2 + 1] << 8));
            processFrame(pcm);
        }
        if (line != null) { line.stop(); line.close(); }
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
        if (usePushToTalk() && testOverride == null) {
            talk = talkHeld();
        } else {
            if (level >= activation.get()) lastLoud = System.currentTimeMillis();
            talk = System.currentTimeMillis() - lastLoud < 300;
        }
        transmitting = talk;
        if (!talk || encoder == null || engine() != Engine.EVADE) return;
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

    /** Frames sent since the last call (for tests). */
    public int takeSent() { int n = framesSent; framesSent = 0; return n; }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        if (mc.player == null) return;
        Engine engine = engine();
        if (engine == Engine.SIMPLE_VOICE_CHAT) return; // Simple Voice Chat shows its own icons
        var tr = mc.textRenderer;
        int x = 4, y = ctx.getScaledWindowHeight() / 2 - 30;
        if (transmitting || (usePushToTalk() && talkHeld())) {
            String s = engine == Engine.NONE ? "Voice: not available on this server" : micFailed ? "Voice: no microphone found" : "Talking";
            int w = tr.getWidth(s) + 18;
            ctx.fill(x, y, x + w, y + 13, 0xB0000000);
            ctx.fill(x + 3, y + 3, x + 10, y + 10, engine == Engine.EVADE && !micFailed ? 0xFF22C55E : 0xFFE11D2E);
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
