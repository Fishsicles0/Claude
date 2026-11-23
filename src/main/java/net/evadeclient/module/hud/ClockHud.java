package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.world.World;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The time: real-world (your computer's clock) or in-game. In-game time is the server's own clock, so it stays
 * right while Time Changer shows you a different sky.
 */
public class ClockHud extends TextHudModule {
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm a"),
            H12_SECONDS = DateTimeFormatter.ofPattern("h:mm:ss a"),
            H24 = DateTimeFormatter.ofPattern("HH:mm"),
            H24_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss"),
            DATE = DateTimeFormatter.ofPattern("EEE d MMM");
    /** Longest the in-game clock runs on by itself between two time updates from the server (2 s). */
    private static final long MAX_EXTRAPOLATE = 40;

    private final ModeSetting format = add(new ModeSetting("Format", "12h", "12h", "24h"));
    private final ModeSetting source = add(new ModeSetting("Source", "Real", "Real", "In-game")
            .describe("Your computer's clock, or the time of day in the world (from the server, so Time Changer doesn't change it)."));
    private final BooleanSetting showSeconds = add(new BooleanSetting("Show Seconds", false));
    private final BooleanSetting showDate = add(new BooleanSetting("Show Date", false)
            .describe("Today's date, or the day number in the world for in-game time."));

    /** The last time of day the server sent, the client tick it arrived and the world it was for. */
    private long serverTime;
    private long serverTimeAt;
    private boolean serverTicking;
    private World serverWorld;

    public ClockHud() { super("Clock", "Real-world or in-game time.", 4, 88); }

    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof WorldTimeUpdateS2CPacket p)) return;
        serverTime = p.timeOfDay();
        serverTicking = p.tickDayTime();
        serverTimeAt = EvadeClient.ticks;
        serverWorld = mc.world;
    }

    /** The world's time of day in ticks (counting up across days), as the server last said, run on to now. */
    private long gameTime() {
        if (mc.world == null) return 0;
        if (serverWorld != mc.world) return mc.world.getTimeOfDay();
        long since = serverTicking ? Math.max(0, Math.min(MAX_EXTRAPOLATE, EvadeClient.ticks - serverTimeAt)) : 0;
        return serverTime + since;
    }

    @Override
    protected List<String> lines(boolean preview) {
        boolean h12 = format.is("12h"), seconds = showSeconds.get();
        List<String> out = new ArrayList<>(2);
        if (source.is("In-game")) {
            long t = gameTime();
            long dayTicks = Math.floorMod(t, 24000L);
            int hour = (int) ((dayTicks / 1000 + 6) % 24);
            int minute = (int) (dayTicks % 1000 * 60 / 1000);
            int second = (int) (dayTicks % 1000 * 3600 / 1000 % 60);
            int shownHour = h12 ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
            String time = String.format(Locale.ROOT, h12 ? "%d:%02d" : "%02d:%02d", shownHour, minute);
            if (seconds) time += String.format(Locale.ROOT, ":%02d", second);
            if (h12) time += hour < 12 ? " AM" : " PM";
            out.add(time);
            if (showDate.get()) out.add("Day " + (Math.floorDiv(t, 24000L) + 1));
        } else {
            LocalDateTime now = LocalDateTime.now();
            out.add(now.format(h12 ? (seconds ? H12_SECONDS : H12) : (seconds ? H24_SECONDS : H24)));
            if (showDate.get()) out.add(now.format(DATE));
        }
        return out;
    }
}
