package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.mods.TimeChanger;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

import java.lang.ref.WeakReference;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The time: real-world (your computer's clock, any of a few time zones, with an optional date and second zone),
 * in-game, or both, plus an alarm and an hourly chime. In-game time is the server's own clock, so it stays right
 * while Time Changer shows you a different sky. Everything here is drawn or played on your side only.
 */
public class ClockHud extends TextHudModule {
    private static final String OFF = "Off", CUSTOM = "Custom", HIDDEN = "Hidden", REPLACE = "Replace";
    /** Longest the in-game clock runs on by itself between two time updates from the server (2 s). */
    private static final long MAX_EXTRAPOLATE = 40;
    /** Time of day that can't be known (no world, or Time Changer covering a world the server hasn't timed yet). */
    private static final long UNKNOWN = Long.MIN_VALUE;
    private static final String NO_TIME = "--:--", INVALID = "Invalid pattern", BED = " ☾ Bed", DOT = " · ";

    /** A zone's short name ("PST", "CET", "UTC"), always in English so it reads the same everywhere. */
    private static final DateTimeFormatter ZONE_NAME = DateTimeFormatter.ofPattern("z", Locale.US);
    private static final String[] ZONE_CHOICES =
            {"UTC", "US Pacific", "US Eastern", "UK", "Central Europe", "India", "Japan", "Australia East"};
    private static final Map<String, ZoneId> ZONES = Map.of(
            "UTC", ZoneId.of("UTC"), // not ZoneOffset.UTC, whose short name prints as "Z"
            "US Pacific", ZoneId.of("America/Los_Angeles"),
            "US Eastern", ZoneId.of("America/New_York"),
            "UK", ZoneId.of("Europe/London"),
            "Central Europe", ZoneId.of("Europe/Berlin"),
            "India", ZoneId.of("Asia/Kolkata"),
            "Japan", ZoneId.of("Asia/Tokyo"),
            "Australia East", ZoneId.of("Australia/Sydney"));

    /** The Date choices are samples of what they show; the patterns behind them use your computer's language. */
    private static final String DATE_SHORT = "Mon 22 Nov";
    private static final Map<String, String> DATE_PATTERNS = Map.of(
            DATE_SHORT, "EEE d MMM",
            "22/11/2026", "dd/MM/yyyy",
            "11/22/2026", "MM/dd/yyyy",
            "2026-11-22", "yyyy-MM-dd",
            "Monday, 22 November", "EEEE, d MMMM");

    // ---- time --------------------------------------------------------------------------------------------
    private final ModeSetting format = add(new ModeSetting("Format", "12h", "12h", "24h", CUSTOM)
            .describe("12-hour or 24-hour time, or your own Custom Pattern."));
    private final BooleanSetting showSeconds = add(new BooleanSetting("Show Seconds", false)
            .describe("Adds seconds to the time, like 7:05:42."));
    private final BooleanSetting leadingZero = add(new BooleanSetting("Leading Zero", false)
            .describe("07:05 instead of 7:05."));
    private final ModeSetting ampmStyle = add(new ModeSetting("AM/PM Style", "AM/PM", "AM/PM", "am/pm", HIDDEN)
            .describe("How 12-hour time marks morning and afternoon (12h only)."));
    private final StringSetting customPattern = add(new StringSetting("Custom Pattern", "EEE HH:mm",
            ".set Clock CustomPattern <pattern>").maxLength(64)
            .describe("For Format: Custom. HH or h hour, mm minute, ss second, a AM/PM, EEE day, d date, MMM month, "
                    + "yyyy year, z zone; put other text in 'quotes'."));
    private final ModeSetting date = add(new ModeSetting("Date", OFF,
            OFF, DATE_SHORT, "22/11/2026", "11/22/2026", "2026-11-22", "Monday, 22 November")
            .describe("Today's date in the style shown. Day and month names follow your computer's language."));
    private final ModeSetting datePosition = add(new ModeSetting("Date Position", "Below", "Below", "Same Line")
            .describe("The date on its own line, or in front of the time."));

    // ---- zones -------------------------------------------------------------------------------------------
    private final ModeSetting timeZone = add(new ModeSetting("Time Zone", "Local", prepend("Local", ZONE_CHOICES))
            .describe("Local is your computer's time zone. The alarm and chime follow this zone too."));
    private final BooleanSetting showZone = add(new BooleanSetting("Show Zone", false)
            .describe("Adds the zone's short name after the time, e.g. PST."));
    private final ModeSetting secondZone = add(new ModeSetting("Second Zone", OFF, prepend(OFF, ZONE_CHOICES))
            .describe("A second line with the time somewhere else, e.g. UTC 05:41."));

    // ---- in-game time ------------------------------------------------------------------------------------
    private final ModeSetting gameTime = add(new ModeSetting("Game Time", OFF, OFF, "Extra Line", REPLACE)
            .describe("The time of day in the world, from the server, so Time Changer doesn't change it. "
                    + "Replace shows it instead of the real time."));
    private final ModeSetting gameStyle = add(new ModeSetting("Game Time Style", "Clock", "Clock", "Ticks", "Phase", "Countdown")
            .describe("Clock: 6:00 AM at sunrise. Ticks: 0 to 23999. Phase: Sunrise, Day, Sunset, Night or Midnight. "
                    + "Countdown: time left until night or day."));
    private final BooleanSetting showDay = add(new BooleanSetting("Show Day", true)
            .describe("The world's day number, as F3 shows it."));
    private final BooleanSetting sleepIndicator = add(new BooleanSetting("Sleep Indicator", true)
            .describe("Shows ☾ Bed with the game time once it's late (or stormy) enough to sleep."));

    // ---- alarm -------------------------------------------------------------------------------------------
    private final BooleanSetting alarm = add(new BooleanSetting("Alarm", false)
            .describe("A reminder at a set time in the Time Zone above, while you're in a world with the Clock on."));
    private final NumberSetting alarmHour = add(new NumberSetting("Alarm Hour", 7, 0, 23, 1)
            .describe("Hour of the alarm, 0 to 23."));
    private final NumberSetting alarmMinute = add(new NumberSetting("Alarm Minute", 0, 0, 59, 1)
            .describe("Minute of the alarm, 0 to 59."));
    private final BooleanSetting alarmSound = add(new BooleanSetting("Alarm Sound", true)
            .describe("Rings a bell three times with the alarm."));
    private final BooleanSetting hourlyChime = add(new BooleanSetting("Hourly Chime", false)
            .describe("A soft note at the start of every hour."));
    private final StatusSetting nextAlarm = add(new StatusSetting("Next Alarm", this::nextAlarmText, "")
            .describe("When the alarm will go off next."));

    /** The last time of day the server sent, whether its day runs on, the client tick it arrived and its world. */
    private long serverTimeOfDay;
    private boolean serverTicking;
    private long serverTimeAt;
    /** Weak, so a world that was left is never kept in memory by the clock. */
    private WeakReference<World> serverWorld = new WeakReference<>(null);

    /** Formatters, rebuilt only when the settings they are made from change (see {@link #refreshFormatters}). */
    private String fmtKey, dateKey;
    /** The real-time formatter (null when the Custom Pattern can't be used), the in-game one and the date one. */
    private DateTimeFormatter timeFmt, gameFmt, dateFmt;

    /** The minute the alarm last went off, so it rings once per minute it matches. */
    private LocalDateTime lastAlarm;
    /** The hour (days * 24 + hour) the chime last sounded for. */
    private long lastChimeHour = Long.MIN_VALUE;
    /** Bells still to ring for the current alarm, and ticks until the next one. */
    private int ringsLeft, ringCountdown;

    public ClockHud() { super("Clock", "Real-world or in-game time, with dates, time zones and an alarm.", 4, 88); }

    // ---- server time -------------------------------------------------------------------------------------

    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof WorldTimeUpdateS2CPacket p)) return;
        serverTimeOfDay = p.timeOfDay();
        serverTicking = p.tickDayTime();
        serverTimeAt = EvadeClient.ticks;
        if (serverWorld.get() != mc.world) serverWorld = new WeakReference<>(mc.world);
    }

    /**
     * The world's time of day in ticks (counting up across days) as the server last said, run on to now, or
     * {@link #UNKNOWN}.
     */
    private long timeOfDay() {
        World w = mc.world;
        if (w == null) return UNKNOWN;
        if (serverWorld.get() != w) {
            // A world whose first time update hasn't arrived yet: its own clock is still the server's, unless
            // Time Changer has already replaced it.
            return timeChangerOn() ? UNKNOWN : w.getTimeOfDay();
        }
        long since = serverTicking ? Math.max(0, Math.min(MAX_EXTRAPOLATE, EvadeClient.ticks - serverTimeAt)) : 0;
        return serverTimeOfDay + since;
    }

    /** Whether the day cycle is known to be stopped (the doDaylightCycle game rule is off). */
    private boolean dayFrozen() { return mc.world != null && serverWorld.get() == mc.world && !serverTicking; }

    private static boolean timeChangerOn() {
        if (EvadeClient.modules == null) return false;
        TimeChanger tc = EvadeClient.modules.get(TimeChanger.class);
        return tc != null && tc.isEnabled();
    }

    /** The in-game time in Game Time Style, or "--:--" where there's no day cycle to show. */
    private String gameText(World w, long tod) {
        if (w == null || tod == UNKNOWN || w.getDimension().hasFixedTime()) return NO_TIME;
        int t = (int) Math.floorMod(tod, 24000L);
        return switch (gameStyle.get()) {
            case "Ticks" -> t + " ticks";
            case "Phase" -> phase(t);
            case "Countdown" -> dayFrozen() ? "Day cycle off" : countdown(t);
            default -> {
                // Tick 0 is 6:00 AM; 1000 ticks are an hour.
                int hour = (t / 1000 + 6) % 24, minute = t % 1000 * 60 / 1000, second = t % 1000 * 3600 / 1000 % 60;
                yield gameFmt.format(LocalTime.of(hour, minute, second));
            }
        };
    }

    private static String phase(int t) {
        if (t >= 23000 || t < 1000) return "Sunrise";
        if (t < 12000) return "Day";
        if (t < 13000) return "Sunset";
        if (t >= 17000 && t < 19000) return "Midnight";
        return "Night";
    }

    /** "Night in m:ss" until tick 13000 (mobs start spawning), "Day in m:ss" until 23000 (sunrise). */
    private static String countdown(int t) {
        boolean night = t >= 13000 && t < 23000;
        int left = night ? 23000 - t : Math.floorMod(13000 - t, 24000);
        int secs = (left + 19) / 20; // whole seconds, rounded up so it shows 0:00 only at the change itself
        return String.format(Locale.ROOT, "%s in %d:%02d", night ? "Day" : "Night", secs / 60, secs % 60);
    }

    /**
     * Whether vanilla would let you sleep now: in a dimension with a normal day cycle and sky, between 12542 and
     * 23459 (12010 and 23991 in rain), or any time in a thunderstorm. Uses the server's time and weather, so
     * Time Changer and Weather Changer don't fool it.
     */
    private static boolean canSleep(World w, long tod) {
        if (w == null || tod == UNKNOWN) return false;
        DimensionType d = w.getDimension();
        if (d.hasFixedTime() || !d.hasSkyLight() || d.skybox() != DimensionType.Skybox.OVERWORLD) return false;
        if (w.isThundering()) return true;
        int t = (int) Math.floorMod(tod, 24000L);
        return w.isRaining() ? t >= 12010 && t <= 23991 : t >= 12542 && t <= 23459;
    }

    // ---- lines -------------------------------------------------------------------------------------------

    @Override
    protected List<String> lines(boolean preview) {
        refreshFormatters();
        List<String> out = new ArrayList<>(4);
        String game = gameTime.get();
        World w = mc.world;
        long tod = game.equals(OFF) ? UNKNOWN : timeOfDay();
        String bed = sleepIndicator.get() && canSleep(w, tod) ? BED : "";

        if (game.equals(REPLACE)) {
            out.add(gameText(w, tod) + bed);
            if (showDay.get() && tod != UNKNOWN) out.add("Day " + Math.floorDiv(tod, 24000L));
            return out;
        }

        // One reading of the clock for every line, so the time, date and second zone always agree.
        ZonedDateTime now = ZonedDateTime.now(zone(timeZone.get()));
        String time = format(timeFmt, now);
        if (time != null && showZone.get()) time += " " + ZONE_NAME.format(now);
        if (time == null) time = INVALID;
        String day = dateFmt == null ? null : format(dateFmt, now);
        if (day != null && datePosition.is("Same Line")) {
            out.add(day + DOT + time);
        } else {
            out.add(time);
            if (day != null) out.add(day);
        }
        ZoneId second = ZONES.get(secondZone.get());
        if (second != null) {
            ZonedDateTime there = now.withZoneSameInstant(second);
            String t = format(timeFmt, there);
            if (t != null) out.add(ZONE_NAME.format(there) + " " + t);
        }
        if (!game.equals(OFF)) {
            String g = gameText(w, tod);
            // A bare clock time under the real one needs saying which is which; the other styles say it themselves.
            boolean bareClock = gameStyle.is("Clock") || g.equals(NO_TIME);
            String head = showDay.get() && tod != UNKNOWN ? "Day " + Math.floorDiv(tod, 24000L) + DOT
                    : bareClock ? "Game " : "";
            out.add(head + g + bed);
        }
        return out;
    }

    /** {@code fmt} applied to {@code t}, or null when there's no usable formatter or the pattern can't print it. */
    private static String format(DateTimeFormatter fmt, TemporalAccessor t) {
        if (fmt == null) return null;
        try {
            return fmt.format(t);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static ZoneId zone(String name) {
        ZoneId z = ZONES.get(name);
        return z != null ? z : ZoneId.systemDefault();
    }

    /** Rebuilds the cached formatters when Format, its options, the Custom Pattern, Date or the language changed. */
    private void refreshFormatters() {
        Locale locale = Locale.getDefault();
        String mode = format.get(), style = ampmStyle.get(), custom = customPattern.get();
        boolean seconds = showSeconds.get(), zero = leadingZero.get();
        String key = mode + '|' + seconds + '|' + zero + '|' + style + '|' + custom + '|' + locale;
        if (!key.equals(fmtKey)) {
            fmtKey = key;
            // Custom's in-game clock (and nothing else) falls back to 24-hour time.
            gameFmt = clockFormatter(mode.equals("12h"), seconds, zero, style);
            if (mode.equals(CUSTOM)) {
                DateTimeFormatter f = null;
                if (!custom.isBlank()) {
                    try {
                        f = DateTimeFormatter.ofPattern(custom, locale);
                    } catch (IllegalArgumentException e) {
                        // shown as "Invalid pattern"
                    }
                }
                timeFmt = f;
            } else {
                timeFmt = gameFmt;
            }
        }
        String dk = date.get() + '|' + locale;
        if (!dk.equals(dateKey)) {
            dateKey = dk;
            String pattern = DATE_PATTERNS.get(date.get());
            dateFmt = pattern == null ? null : DateTimeFormatter.ofPattern(pattern, locale);
        }
    }

    /**
     * "h:mm AM", "H:mm", "hh:mm:ss am" and so on. AM/PM text is set explicitly (not the language's own), so
     * am/pm comes out lower case without touching anything else on the line.
     */
    private static DateTimeFormatter clockFormatter(boolean h12, boolean seconds, boolean zero, String ampm) {
        String hour = h12 ? (zero ? "hh" : "h") : (zero ? "HH" : "H");
        var b = new DateTimeFormatterBuilder().appendPattern(hour + ":mm" + (seconds ? ":ss" : ""));
        if (h12 && !ampm.equals(HIDDEN)) {
            boolean lower = ampm.equals("am/pm");
            b.appendLiteral(' ').appendText(ChronoField.AMPM_OF_DAY, Map.of(0L, lower ? "am" : "AM", 1L, lower ? "pm" : "PM"));
        }
        return b.toFormatter(Locale.US);
    }

    // ---- alarm and chime ---------------------------------------------------------------------------------

    @Override
    protected void onEnable() {
        ZonedDateTime now = ZonedDateTime.now(zone(timeZone.get()));
        // Joining a world on the hour doesn't chime straight away.
        lastChimeHour = now.getMinute() == 0 ? hourKey(now) : Long.MIN_VALUE;
        ringsLeft = 0;
    }

    @Override
    protected void onDisable() { ringsLeft = 0; }

    @Override
    public void onTick() {
        ZonedDateTime now = ZonedDateTime.now(zone(timeZone.get()));
        boolean alarmed = false;
        if (alarm.get() && now.getHour() == alarmHour.getInt() && now.getMinute() == alarmMinute.getInt()) {
            LocalDateTime minute = now.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
            if (!minute.equals(lastAlarm)) {
                lastAlarm = minute;
                alarmed = true;
                Notifications.push("Alarm", String.format(Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute()), true);
                if (alarmSound.get()) {
                    ringsLeft = 3;
                    ringCountdown = 0;
                }
            }
        }
        if (ringsLeft > 0 && --ringCountdown <= 0) {
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_BELL_USE, 1f));
            ringsLeft--;
            ringCountdown = 10;
        }
        if (now.getMinute() == 0) {
            long hour = hourKey(now);
            // No chime over the alarm's own bell.
            if (hourlyChime.get() && hour != lastChimeHour && !alarmed) {
                mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.5f));
            }
            lastChimeHour = hour;
        }
    }

    private static long hourKey(ZonedDateTime t) { return t.toLocalDate().toEpochDay() * 24 + t.getHour(); }

    /** "in 3h 12m" until the alarm, for the Next Alarm row. */
    private String nextAlarmText() {
        if (!alarm.get()) return "Off";
        ZonedDateTime now = ZonedDateTime.now(zone(timeZone.get()));
        ZonedDateTime at = now.with(LocalTime.of(alarmHour.getInt(), alarmMinute.getInt()));
        if (!at.isAfter(now)) at = at.plusDays(1);
        long mins = (Duration.between(now, at).getSeconds() + 59) / 60;
        String in = "in " + (mins >= 60 ? mins / 60 + "h " : "") + mins % 60 + "m";
        return isEnabled() ? in : in + " (Clock is off)";
    }

    // ---- config ------------------------------------------------------------------------------------------

    /**
     * Older configs had Source (Real / In-game) and Show Date (on/off, the day number for in-game time), and
     * 24-hour time always had a leading zero. Map them onto Game Time, Date, Show Day and Leading Zero. The old
     * keys are only ever in configs saved before this version (saving drops them), and a new setting is only
     * changed while it is still at its default, so imported profile codes migrate the same way.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        String source = string(settings, "Source"), oldDate = string(settings, "Show Date");
        boolean inGame = "In-game".equalsIgnoreCase(source);
        if (inGame && gameTime.isDefault()) gameTime.set(REPLACE);
        if (oldDate != null) {
            boolean on = "true".equalsIgnoreCase(oldDate);
            if (inGame) {
                if (showDay.isDefault()) showDay.set(on);
            } else if (on && date.isDefault()) {
                date.set(DATE_SHORT);
            }
        }
        boolean legacy = source != null || oldDate != null || !settings.has(leadingZero.getName());
        if (legacy && format.is("24h") && leadingZero.isDefault()) leadingZero.set(true);
    }

    private static String string(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
