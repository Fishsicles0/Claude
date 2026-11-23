package net.evadeclient.module.misc;

import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.replay.ReplayFile;
import net.evadeclient.replay.ReplayPlayer;
import net.evadeclient.replay.ReplayRecorder;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 3D replays: records the world as you play (blocks, players, mobs, items, effects, sounds), so afterwards you
 * can fly through it with a free camera, build camera paths and render cinematic video. Open saved replays from
 * the Replays button on the title screen. Recording runs in the background from the moment you join, so
 * "Start recording" (pause menu or key) works mid-match too.
 */
public class Replay extends Module {
    private final BooleanSetting autoRecord = add(new BooleanSetting("Record Every Session", false));
    private final BooleanSetting saveOnLeave = add(new BooleanSetting("Save When Leaving", true));
    private final KeySetting recordKey = add(new KeySetting("Start / Save Key", GLFW.GLFW_KEY_UNKNOWN));
    private final KeySetting markerKey = add(new KeySetting("Marker Key", GLFW.GLFW_KEY_UNKNOWN));
    private final BooleanSetting indicator = add(new BooleanSetting("Recording Indicator", true));
    /** With Simple Voice Chat: what others say near you goes into the replay (played back from where they stood). */
    private final BooleanSetting voiceChat = add(new BooleanSetting("Record Voice Chat", true));
    private final BooleanSetting ownVoice = add(new BooleanSetting("Record My Voice", true));
    private boolean recordWasDown, markerWasDown;

    public Replay() {
        super("Replay", "Record the world in 3D: fly through it afterwards, make camera paths, render video.", Category.MISC);
        setEnabled(true);
    }

    public boolean autoRecord() { return autoRecord.get(); }
    public boolean saveOnLeave() { return saveOnLeave.get(); }
    public boolean recordVoiceChat() { return voiceChat.get(); }
    public boolean recordOwnVoice() { return voiceChat.get() && ownVoice.get(); }

    @Override
    public void onTick() {
        if (ReplayPlayer.active()) return;
        boolean r = recordKey.isDown() && mc.currentScreen == null;
        if (r && !recordWasDown) toggleRecording();
        recordWasDown = r;
        boolean m = markerKey.isDown() && mc.currentScreen == null;
        if (m && !markerWasDown && ReplayRecorder.recording()) {
            ReplayRecorder.marker("Marker");
            net.evadeclient.util.Notifications.push("Replay", "Marker added", true);
        }
        markerWasDown = m;
    }

    /** Start recording, or save if already recording. */
    public static void toggleRecording() {
        if (ReplayRecorder.recording()) {
            var p = ReplayRecorder.save();
            if (p == null) net.evadeclient.util.Notifications.push("Replay", "Couldn't save the replay", false);
        } else if (ReplayRecorder.start()) {
            net.evadeclient.util.Notifications.push("Replay", "Recording. Open the pause menu to save", true);
        } else {
            net.evadeclient.util.Notifications.push("Replay", "Rejoin to record: replays start when you join", false);
        }
    }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        if (!indicator.get() || !ReplayRecorder.recording() || mc.options.hudHidden) return;
        int secs = ReplayRecorder.recordedTicks() / 20;
        String text = (ReplayRecorder.paused() ? "PAUSED " : "REC ") + String.format("%d:%02d", secs / 60, secs % 60);
        int w = mc.textRenderer.getWidth(text) + 18;
        int x = ctx.getScaledWindowWidth() - w - 6, y = 6;
        ctx.fill(x, y, x + w, y + 14, 0xAA000000);
        boolean blink = ReplayRecorder.paused() || (System.currentTimeMillis() / 600) % 2 == 0;
        if (blink) ctx.fill(x + 5, y + 4, x + 11, y + 10, ReplayRecorder.paused() ? 0xFFFFC04D : 0xFFE5484D);
        ctx.drawTextWithShadow(mc.textRenderer, text, x + 14, y + 3, 0xFFFFFFFF);
    }

    @Override public List<String> commandAliases() { return List.of("replay"); }

    @Override
    public List<String> commandHelp() {
        return List.of("§d.replay§7 [start|save|pause|marker <name>|list] - record the world in 3D");
    }

    @Override
    public void command(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "start" -> { if (!ReplayRecorder.recording()) toggleRecording(); }
            case "save", "stop" -> { if (ReplayRecorder.recording()) toggleRecording(); else CommandManager.print("§7Not recording."); }
            case "pause" -> { ReplayRecorder.togglePause(); CommandManager.print(ReplayRecorder.paused() ? "§7Recording paused." : "§7Recording resumed."); }
            case "marker" -> {
                String label = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "Marker";
                ReplayRecorder.marker(label);
                CommandManager.print("§7Marker: §f" + label);
            }
            case "list" -> {
                var files = ReplayFile.list();
                CommandManager.print(files.isEmpty() ? "§7No replays yet." : "§7" + files.size() + " replays in " + ReplayFile.dir());
            }
            default -> CommandManager.print(ReplayRecorder.recording() ? "§7Recording. §d.replay save§7 to save it." : "§7Not recording. §d.replay start§7 to start.");
        }
    }
}
