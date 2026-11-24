package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.mods.TimeChanger;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.world.World;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

/**
 * The world's day number (time of day / 24000, as F3 shows it) and optionally the in-game clock time. Both come
 * from the time the server sends, so Time Changer's sky never changes them. Nothing here is hidden information: the
 * server sends this time to every client and vanilla shows it in F3.
 */
public class DayCounterHud extends TextHudModule {
    private static final long DAY = 24000L;
    /** Longest the clock runs on by itself between two time updates from the server (2 s). */
    private static final long MAX_EXTRAPOLATE = 40;
    private static final long UNKNOWN = Long.MIN_VALUE;

    private final ModeSetting style = add(new ModeSetting("Style", "Day 12", "Day 12", "12", "Day 12 of world")
            .describe("How the day reads: \"Day 12\", just the number, or \"Day 12 of world\"."));
    private final BooleanSetting showTime = add(new BooleanSetting("Show Time", false)
            .describe("Adds the in-game clock time (6:00 AM at sunrise)."));
    private final ModeSetting timeFormat = add(new ModeSetting("Time Format", "12h", "12h", "24h")
            .describe("12-hour (6:30 PM) or 24-hour (18:30) game time."));
    private final BooleanSetting sameLine = add(new BooleanSetting("Same Line", true)
            .describe("Show the time after the day on one line, instead of on a second line."));
    private final BooleanSetting startAtOne = add(new BooleanSetting("Start At Day 1", false)
            .describe("Count the first day as Day 1 instead of Day 0 (F3 counts from 0)."));

    private long serverTimeOfDay;
    private boolean serverTicking;
    private long serverTimeAt;
    private WeakReference<World> serverWorld = new WeakReference<>(null);

    public DayCounterHud() {
        super("Day Counter", "The world's day number, and the in-game time if you like.", 4, 214);
    }

    /** Records the server's time; runs for every time update, whether the module is on or not. */
    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof WorldTimeUpdateS2CPacket p)) return;
        serverTimeOfDay = p.timeOfDay();
        serverTicking = p.tickDayTime();
        serverTimeAt = EvadeClient.ticks;
        if (serverWorld.get() != mc.world) serverWorld = new WeakReference<>(mc.world);
    }

    /** The server's time of day (counting days), run on to now, or {@link #UNKNOWN}. */
    private long timeOfDay() {
        World w = mc.world;
        if (w == null) return UNKNOWN;
        if (serverWorld.get() != w) {
            TimeChanger tc = EvadeClient.modules == null ? null : EvadeClient.modules.get(TimeChanger.class);
            return tc != null && tc.isEnabled() ? UNKNOWN : w.getTimeOfDay();
        }
        long since = serverTicking ? Math.max(0, Math.min(MAX_EXTRAPOLATE, EvadeClient.ticks - serverTimeAt)) : 0;
        return serverTimeOfDay + since;
    }

    @Override
    protected List<String> lines(boolean preview) {
        long tod = timeOfDay();
        if (tod == UNKNOWN) {
            if (!preview) return List.of();
            tod = 11 * DAY + 6000;
        }
        long day = Math.floorDiv(tod, DAY) + (startAtOne.get() ? 1 : 0);
        String dayText = switch (style.get()) {
            case "12" -> Long.toString(day);
            case "Day 12 of world" -> "Day " + day + " of world";
            default -> "Day " + day;
        };
        World w = mc.world;
        boolean fixed = w != null && w.getDimension().hasFixedTime();
        if (!showTime.get() || fixed) return List.of(dayText);
        String time = clock((int) Math.floorMod(tod, DAY));
        return sameLine.get() ? List.of(dayText + " · " + time) : List.of(dayText, time);
    }

    /** {@code t} (0..23999) as a clock time: tick 0 is 6:00 AM, 1000 ticks are an hour. */
    String clock(int t) {
        int hour = (t / 1000 + 6) % 24, minute = t % 1000 * 60 / 1000;
        if (timeFormat.is("24h")) return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        return String.format(Locale.ROOT, "%d:%02d %s", h12, minute, hour < 12 ? "AM" : "PM");
    }
}
