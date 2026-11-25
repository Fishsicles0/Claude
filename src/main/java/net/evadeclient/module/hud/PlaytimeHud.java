package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How long you've been playing: this session (since you joined the world or server you're in) and today in total,
 * with an optional reminder to take a break. Only your own clock is read; nothing is sent anywhere. Time is counted
 * while you're in a world, whether the widget is shown or not, so turning it on later still shows today's total.
 */
public class PlaytimeHud extends TextHudModule {
    /** What the HUD editor shows before you've played: 1 h 12 min this session, 2 h 40 min today. */
    private static final long SAMPLE_SESSION = 72 * 60, SAMPLE_TODAY = 160 * 60;

    private final BooleanSetting showSession = add(new BooleanSetting("Show Session", true)
            .describe("Time since you joined the world or server you're in now."));
    private final BooleanSetting showToday = add(new BooleanSetting("Show Today", true)
            .describe("Everything you've played today, across worlds, servers and restarts. Starts again at midnight."));
    private final ModeSetting format = add(new ModeSetting("Format", "1h 05m", "1h 05m", "1:05:00")
            .describe("Hours and minutes, or hours, minutes and seconds like a clock."));
    private final ModeSetting breakReminder = add(new ModeSetting("Break Reminder", "Off", "Off", "30 min", "1 hour", "2 hours")
            .describe("A gentle pop-up after this long in one session, and again each time that much more has passed."));

    /** Today's total, saved with the config so it survives a restart (never shown). */
    private final StringSetting todayDate = add(new StringSetting("Playtime Date", "", "").hidden());
    private final NumberSetting todaySeconds = add(new NumberSetting("Playtime Today", 0, 0, 86_400, 1).hidden());

    private ClientPlayNetworkHandler sessionHandler;
    private long sessionStartMs, lastCountMs, nextReminderSec;

    public PlaytimeHud() {
        super("Playtime", "How long you've played this session and today, with an optional break reminder.", 4, 244);
        ClientTickEvents.END_CLIENT_TICK.register(client -> count());
    }

    /** Runs every tick, shown or not: starts a session on a new connection and adds whole seconds to today. */
    private void count() {
        long now = System.currentTimeMillis();
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null || mc.world == null) {
            sessionHandler = null;
            lastCountMs = 0;
            return;
        }
        if (handler != sessionHandler) {
            sessionHandler = handler;
            sessionStartMs = now;
            lastCountMs = now;
            nextReminderSec = reminderSeconds();
        }
        String today = LocalDate.now().toString();
        if (!today.equals(todayDate.get())) {
            todayDate.set(today);
            todaySeconds.set(0.0);
        }
        long whole = (now - lastCountMs) / 1000;
        if (whole > 0) {
            todaySeconds.set(Math.min(86_400.0, todaySeconds.get() + whole));
            lastCountMs += whole * 1000;
        }
        long every = reminderSeconds();
        if (every > 0 && isEnabled()) {
            if (nextReminderSec <= 0) nextReminderSec = every;
            long session = sessionSeconds();
            if (session >= nextReminderSec) {
                Notifications.push("Playtime", "You've played for " + text(session, "1h 05m") + ". Time for a short break?", false);
                nextReminderSec = session + every;
            }
        }
    }

    private long reminderSeconds() {
        return switch (breakReminder.get()) {
            case "30 min" -> 30 * 60;
            case "1 hour" -> 60 * 60;
            case "2 hours" -> 2 * 60 * 60;
            default -> 0;
        };
    }

    /** Seconds since you joined the current world or server (0 when you're not in one). */
    public long sessionSeconds() {
        return sessionHandler == null ? 0 : Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 1000);
    }

    /** Seconds played today, this session included. */
    public long todaySecondsPlayed() {
        return LocalDate.now().toString().equals(todayDate.get()) ? todaySeconds.getInt() : 0;
    }

    /** {@code seconds} as "1h 05m" (or "12m" under an hour) or "1:05:00". */
    public static String text(long seconds, String style) {
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        if (style.equals("1:05:00")) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return h > 0 ? String.format(Locale.ROOT, "%dh %02dm", h, m) : m + "m";
    }

    @Override
    protected List<String> lines(boolean preview) {
        boolean live = sessionHandler != null;
        if (!live && !preview) return List.of();
        long session = live ? sessionSeconds() : SAMPLE_SESSION;
        long today = live ? todaySecondsPlayed() : SAMPLE_TODAY;
        List<String> out = new ArrayList<>(2);
        if (showSession.get()) out.add("Session: " + text(session, format.get()));
        if (showToday.get()) out.add("Today: " + text(today, format.get()));
        return out;
    }
}
