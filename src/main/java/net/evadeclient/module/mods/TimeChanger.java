package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.world.MoonPhase;
import net.minecraft.world.World;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Locale;

/**
 * Client-side time of day. Only changes what you see (sun, moon, stars, sky colour and how light the world looks);
 * the server, and everything that plays by its clock, keeps the real time.
 *
 * <p>Flow picks how the shown time moves: Fixed holds a preset, Server + Offset follows the server's clock shifted by
 * Offset, Custom Speed runs its own day from the preset at Day Speed, and Real Time follows your computer's clock
 * (06:00 is sunrise). The server's time is recorded from every time update whether the module is on or not (see
 * {@link #onServerPacket}), so turning it off restores the real time at once, run on to this moment.
 *
 * <p>Smooth Transition eases the sky to a new time when the module is switched on and when the preset or flow
 * changes; it always takes the shortest way round the clock ({@link #shortest}). Once there, the time is followed
 * exactly. The day number is kept from the server, so the moon keeps the server's phase unless Moon Phase picks one;
 * SkyRenderingMixin applies Moon Phase and Bright Stars.
 */
public class TimeChanger extends Module {
    private static final long DAY = 24000L;
    /** The presets in menu order, and the time of day each shows (Custom reads Custom Ticks). */
    private static final String[] PRESETS = {"Sunrise", "Morning", "Day", "Afternoon", "Sunset", "Dusk", "Night", "Midnight", "Custom"};
    private static final long[] PRESET_TICKS = {23000, 1000, 6000, 9000, 12500, 13200, 14500, 18000, -1};
    private static final String[] MOON = {"Server", "Full", "Waning Gibbous", "Third Quarter", "Waning Crescent", "New",
            "Waxing Crescent", "First Quarter", "Waxing Gibbous"};
    /** Server time this many world ticks old is no longer run on (a server that stopped sending it). */
    private static final long MAX_EXTRAPOLATE = 1200;
    private static final long UNKNOWN = Long.MIN_VALUE;
    /** Transition easing at Transition Speed 1: share of the remaining distance per tick, and the least step. */
    private static final double EASE_RATE = 0.12, EASE_MIN = 20;

    private final ModeSetting preset = add(new ModeSetting("Time", "Day", PRESETS)
            .describe("The time of day to show. Custom uses Custom Ticks. Custom Speed starts its day here."));
    private final NumberSetting custom = add(new NumberSetting("Custom Ticks", 6000, 0, 23999, 100)
            .describe("Time for the Custom preset: 0 is morning, 6000 noon, 12000 dusk, 18000 midnight."));
    private final ModeSetting flow = add(new ModeSetting("Flow", "Fixed", "Fixed", "Server + Offset", "Custom Speed", "Real Time")
            .describe("Fixed: hold the preset. Server + Offset: the server's time, shifted. Custom Speed: your own day "
                    + "from the preset at Day Speed. Real Time: your computer's clock (06:00 is sunrise)."));
    private final NumberSetting offset = add(new NumberSetting("Offset (ticks)", 0, -12000, 12000, 500)
            .describe("Server + Offset: how far ahead (or behind) of the server's time to show. 1000 ticks is an hour."));
    private final NumberSetting daySpeed = add(new NumberSetting("Day Speed", 1, 0, 20, 0.25)
            .describe("Custom Speed: how fast the day runs. 1 is normal, 0 stops it, 20 is a day a minute."));
    private final NumberSetting realOffset = add(new NumberSetting("Real Time Offset (h)", 0, -12, 12, 1)
            .describe("Real Time: hours to add to your clock, e.g. to show another time zone."));
    private final BooleanSetting smooth = add(new BooleanSetting("Smooth Transition", true)
            .describe("Ease the sky to the new time when switched on or when the preset or flow changes."));
    private final NumberSetting transitionSpeed = add(new NumberSetting("Transition Speed", 1, 0.25, 5, 0.25)
            .describe("How fast a transition plays. At 1 the longest takes about two seconds."));
    private final ModeSetting moon = add(new ModeSetting("Moon Phase", "Server", MOON)
            .describe("The moon to show at night. Server keeps the real phase."));
    private final BooleanSetting brightStars = add(new BooleanSetting("Bright Stars", false)
            .describe("Make the night stars up to four times brighter."));

    // ---- the server's clock, recorded whether the module is on or not --------------------------------------------
    private long serverTimeOfDay;
    /** The world's game time when that update arrived; it counts on with the world's own ticks (not while paused). */
    private long serverGameTime;
    private boolean serverTicking;
    private WeakReference<World> serverWorld = new WeakReference<>(null);

    // ---- what is shown ---------------------------------------------------------------------------------------------
    /** The world the state below belongs to; another world starts fresh. */
    private WeakReference<World> shownWorld = new WeakReference<>(null);
    /** The time of day last shown (0..24000), or NaN before the first. */
    private double shown = Double.NaN;
    /** Shown minus target while a transition plays (ticks, -12000..12000); eased to 0. */
    private double fade;
    /** The settings the current target was made from; a change starts a transition. */
    private String lastKey;
    /** Custom Speed's own running time of day, the preset it was started from, and the game time it last ran at. */
    private double speedTime = Double.NaN;
    private String speedSeed;
    private long speedGameTime;

    public TimeChanger() { super("Time Changer", "Change the time of day you see. The server's time is untouched.", Category.MODS); }

    // ------------------------------------------------------------------ server time

    /** Records the server's time; runs for every time update, before {@link #apply} re-applies ours. */
    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!(packet instanceof WorldTimeUpdateS2CPacket p) || mc.world == null) return;
        serverTimeOfDay = p.timeOfDay();
        serverTicking = p.tickDayTime();
        serverGameTime = p.time(); // vanilla has just set the world's game time to this
        if (serverWorld.get() != mc.world) serverWorld = new WeakReference<>(mc.world);
    }

    /** The server's time of day (counting days) as last sent, run on to now, or {@link #UNKNOWN} for this world. */
    private long serverNow(World w) {
        if (w == null || serverWorld.get() != w) return UNKNOWN;
        long since = serverTicking ? Math.max(0, Math.min(MAX_EXTRAPOLATE, w.getTime() - serverGameTime)) : 0;
        return serverTimeOfDay + since;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        ClientWorld w = mc.world;
        // Start from the time the world shows right now (the server's), so the transition begins where you are.
        shownWorld = new WeakReference<>(w);
        shown = w == null ? Double.NaN : wrap(w.getTimeOfDay());
        fade = 0;
        lastKey = null;
        speedSeed = null;
        apply();
    }

    @Override
    protected void onDisable() {
        ClientWorld w = mc.world;
        if (w != null) {
            long real = serverNow(w);
            if (real != UNKNOWN) w.getLevelProperties().setTimeOfDay(real);
            // Otherwise no update has arrived for this world yet; the first one sets the real time anyway.
        }
        shown = Double.NaN;
        fade = 0;
        lastKey = null;
        speedSeed = null;
        speedTime = Double.NaN;
    }

    @Override
    public void onTick() {
        ClientWorld w = mc.world;
        if (w == null) return;
        // Custom Speed runs on the world's own ticks, so it holds still while the game is paused.
        long gameTime = w.getTime();
        long dt = gameTime - speedGameTime;
        if (flow.is("Custom Speed") && !Double.isNaN(speedTime) && dt > 0 && dt <= 100) {
            speedTime = wrap(speedTime + daySpeed.get() * dt);
        }
        speedGameTime = gameTime;
        if (fade != 0) {
            double speed = transitionSpeed.get();
            double step = Math.max(Math.abs(fade) * EASE_RATE * speed, EASE_MIN * speed);
            fade = Math.abs(fade) <= step ? 0 : fade - Math.signum(fade) * step;
        }
        apply();
    }

    /**
     * Writes the time to show into the client world. Runs every tick, and right after the server sends its own time
     * (ClientPlayNetworkHandlerMixin), so the server's time never shows for a frame.
     */
    public void apply() {
        ClientWorld w = mc.world;
        if (w == null) return;
        if (shownWorld.get() != w) {
            // A new world or dimension: show its target at once, no transition from the last one.
            shownWorld = new WeakReference<>(w);
            shown = Double.NaN;
            fade = 0;
            lastKey = null;
        }
        if (flow.is("Custom Speed")) {
            String seed = preset.get() + "|" + custom.getInt();
            if (!seed.equals(speedSeed) || Double.isNaN(speedTime)) {
                speedSeed = seed;
                speedTime = presetTicks();
                speedGameTime = w.getTime();
            }
        } else {
            speedSeed = null; // coming back to Custom Speed starts again from the preset
        }

        double target = target(w);
        if (Double.isNaN(target)) return; // the server's time isn't known yet: leave the world's own time alone

        String key = key();
        if (!key.equals(lastKey)) {
            lastKey = key;
            fade = smooth.get() && !Double.isNaN(shown) ? -shortest(shown, target) : 0;
        }
        if (!smooth.get()) fade = 0;

        double value = target + fade;
        shown = wrap(value);
        // Keep the server's day number (so the moon keeps the server's phase) and show the chosen time of that day.
        long time = flow.is("Server + Offset") ? (long) Math.floor(value) : dayStart(w) + (long) Math.floor(shown);
        w.getLevelProperties().setTimeOfDay(time);
    }

    // ------------------------------------------------------------------ targets

    /** The time of day the settings ask for right now, or NaN when it can't be known yet. Not wrapped. */
    private double target(World w) {
        return switch (flow.get()) {
            case "Server + Offset" -> {
                long real = serverNow(w);
                yield real == UNKNOWN ? Double.NaN : real + offset.get();
            }
            case "Custom Speed" -> speedTime;
            case "Real Time" -> realTime();
            default -> presetTicks();
        };
    }

    /** What makes the target jump (rather than move on smoothly): a change here starts a transition. */
    private String key() {
        StringBuilder b = new StringBuilder(flow.get());
        switch (flow.get()) {
            case "Fixed", "Custom Speed" -> b.append('|').append(preset.get()).append('|').append(custom.getInt());
            case "Server + Offset" -> b.append('|').append(offset.getInt());
            case "Real Time" -> b.append('|').append(realOffset.getInt());
            default -> { }
        }
        return b.toString();
    }

    private long presetTicks() {
        int i = Arrays.asList(PRESETS).indexOf(preset.get());
        long t = i < 0 ? 6000 : PRESET_TICKS[i];
        return t < 0 ? custom.getInt() : t;
    }

    /** Your computer's clock as a time of day: 06:00 is tick 0 (sunrise), 12:00 is noon, plus Real Time Offset. */
    private double realTime() {
        double hours = LocalTime.now().toNanoOfDay() / 3.6e12;
        return (hours - 6 + realOffset.get()) * 1000.0;
    }

    /** The first tick of the day the server is on (or the world shows, before the server's time is known). */
    private long dayStart(World w) {
        long real = serverNow(w);
        long t = real == UNKNOWN ? w.getTimeOfDay() : real;
        return Math.floorDiv(t, DAY) * DAY;
    }

    // ------------------------------------------------------------------ clock maths

    /** {@code t} on the clock face, 0 (inclusive) to 24000 (exclusive). */
    static double wrap(double t) {
        double r = t % DAY;
        return r < 0 ? r + DAY : r;
    }

    /**
     * The shortest way round the clock from {@code from} to {@code to}, in ticks (-12000 to just under 12000):
     * {@code floorMod(to - from + 12000, 24000) - 12000}. Transitions always take this way, so going from Day to
     * Night runs through the sunset rather than all the way round through the morning; exactly half a day apart goes
     * backwards.
     */
    static double shortest(double from, double to) {
        return wrap(to - from + DAY / 2.0) - DAY / 2.0;
    }

    // ------------------------------------------------------------------ sky (SkyRenderingMixin)

    /** Time Changer while it's on, otherwise null. */
    public static TimeChanger active() {
        if (EvadeClient.modules == null) return null;
        TimeChanger tc = EvadeClient.modules.get(TimeChanger.class);
        return tc != null && tc.isEnabled() ? tc : null;
    }

    /** The moon phase to draw, or null to keep the world's own. */
    public MoonPhase moonPhase() {
        int i = Arrays.asList(MOON).indexOf(moon.get());
        return i <= 0 ? null : MoonPhase.values()[i - 1];
    }

    public boolean brightStars() { return brightStars.get(); }

    @Override
    public String getSuffix() {
        return switch (flow.get()) {
            case "Server + Offset" -> {
                int o = offset.getInt();
                yield o == 0 ? "Server" : String.format(Locale.ROOT, "Server %+d", o);
            }
            case "Custom Speed" -> preset.get() + " x" + BigDecimal.valueOf(daySpeed.get()).stripTrailingZeros().toPlainString();
            case "Real Time" -> "Real Time";
            default -> preset.get();
        };
    }
}
