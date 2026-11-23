package net.evadeclient.module.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Formatting;
import net.minecraft.util.StringHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Says "gg" in chat when a match ends, and optionally "glhf" when one starts. Only the server's own announcements
 * count by default (system messages, anchored at the start of the line); player chat that a server relays as a
 * system message never does. A cooldown keeps it to one message per game.
 */
public class AutoGG extends Module {
    /**
     * End-of-game announcements: "1st Killer - Steve - 12", "Winner: Steve", "Winners - Red", "WINNER!", "VICTORY!",
     * "GAME OVER!", "You won!", "You won the match", "Steve has won the game!". Not "You won 50 coins".
     */
    private static final Pattern END = Pattern.compile(
            "^\\s*(?:1st Killer\\b|Winners?\\s*[:\\-»!]|VICTORY!?\\s*$|GAME OVER!?\\s*$"
                    + "|You won(?:[!.]|\\s*$| the (?:game|match|round|duel|fight|battle)\\b)"
                    + "|.+ has won the (?:game|match)!?\\s*$)",
            Pattern.CASE_INSENSITIVE);
    /** Start-of-game announcements most minigame servers use. */
    private static final Pattern START = Pattern.compile(
            "^\\s*(?:The (?:game|match) (?:starts|will start|begins) in 1 second!?|Cages opened!.*"
                    + "|The (?:game|match) has (?:started|begun)!?|Let the games begin!?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** Hypixel's end-of-game summary lines. */
    private static final Pattern HYPIXEL_END = Pattern.compile("^\\s*(?:1st Killer -|Winners?\\s*[:\\-]|Reward Summary\\s*$)");
    /** Hypixel's start of a game ("Cages opened! FIGHT!" in SkyWars). */
    private static final Pattern HYPIXEL_START = Pattern.compile("^\\s*(?:The game starts in 1 second!|Cages opened!)");
    /**
     * Player chat relayed as a system message: "<Steve> gg", "[MVP+] Steve: you won", "[TEAM] Steve » victory".
     * Group 1 is the name before the separator, so "Winner: Steve" can be told apart from a player called Winner.
     */
    private static final Pattern CHAT = Pattern.compile(
            "^(?:\\[[^\\]]{1,32}]\\s*){0,3}(?:<[A-Za-z0-9_]{1,16}>\\s|([A-Za-z0-9_]{1,16})\\s*(?::|»|>>|->|>)\\s)");
    /** Chat lines (and so chat commands) can't be longer than this. */
    private static final int MAX_CHAT = 256;
    /** Fixed guard between two "glhf"s, separate from the gg Cooldown. */
    private static final long GL_GUARD_MS = 30_000;

    private final ModeSetting message = add(new ModeSetting("Message", "gg", "gg", "GG", "Good game", "gg wp", "Custom")
            .describe("What to say. Custom picks from Custom Text."));
    private final StringSetting customText = add(new StringSetting("Custom Text", "gg|gg wp|good game",
            ".set AutoGG CustomText <text>")
            .describe("Your own messages for Message: Custom, split with | (e.g. gg|gg wp). Plain chat only: commands are never sent."));
    private final BooleanSetting randomize = add(new BooleanSetting("Randomize", true)
            .describe("Pick a random Custom Text message each game (never the same twice in a row); off goes through them in order."));
    private final ModeSetting triggerSource = add(new ModeSetting("Trigger Source", "System Only", "System Only", "System + Chat")
            .describe("System Only answers just the server's own announcements. System + Chat also reads player chat, so anyone typing a match line can set it off."));
    private final ModeSetting preset = add(new ModeSetting("Server Preset", "Auto", "Auto", "Hypixel", "Generic", "Custom")
            .describe("Which lines end a game. Auto uses Hypixel's on Hypixel and the generic ones elsewhere. Custom uses Custom Trigger."));
    private final StringSetting customTrigger = add(new StringSetting("Custom Trigger", "", "a regular expression")
            .describe("For Server Preset: Custom, a regular expression for your server's end-of-game line (case doesn't matter; start it with ^ to match only the start)."));
    private final NumberSetting delay = add(new NumberSetting("Delay (s)", 1, 0, 5, 0.25)
            .describe("How long to wait after the game ends (or starts) before saying it."));
    private final NumberSetting cooldown = add(new NumberSetting("Cooldown (s)", 10, 3, 60, 1)
            .describe("The least time between two gg messages, so one game never gets two."));
    private final ModeSetting channel = add(new ModeSetting("Channel", "Default", "Default", "All Chat (/ac)", "Party (/pc)")
            .describe("Where to say it: your current chat, all chat (/ac) or party chat (/pc)."));
    private final BooleanSetting goodLuck = add(new BooleanSetting("Good Luck Message", false)
            .describe("Also say GL Text when the server announces that a game is starting."));
    private final StringSetting glText = add(new StringSetting("GL Text", "glhf", ".set AutoGG GLText <text>")
            .describe("What to say at the start of a game. Plain chat only."));

    private long lastSent, lastGl;
    /** What will be said once {@link #sendIn} ticks have passed (null: nothing queued). */
    private String pending;
    private int sendIn = -1;
    /** The Custom Text message used last (for Randomize's no-repeat and the in-order cycle). */
    private int lastPick = -1;

    /** The Custom Trigger last compiled, and the result (null: not valid). */
    private String compiledFor;
    private Pattern compiled;
    /** The last invalid Custom Trigger we warned about, so each one warns once. */
    private String warnedFor;

    public AutoGG() {
        super("AutoGG", "Says gg when a match ends.", Category.SERVER);
    }

    private static AutoGG instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(AutoGG.class);
    }

    // ------------------------------------------------------------------ triggers

    /** Every system (game) message we receive, from the GAME listener. */
    public static void onGame(String text) {
        AutoGG gg = instance();
        if (gg == null || !gg.isEnabled() || text == null || EvadeClient.mc().player == null) return;
        String line = Formatting.strip(text); // legacy § colour codes would hide the start of the line from the patterns
        // Player chat relayed as a system line never counts, whatever the preset.
        if (looksLikePlayerChat(line)) return;
        if (gg.find(gg.endPattern(), line)) gg.gameEnded();
        else if (gg.goodLuck.get() && gg.startPattern().matcher(line).find()) gg.gameStarted();
    }

    /** Every player chat message we receive (CHAT listener). Only read with Trigger Source: System + Chat. */
    public static void onChat(String text) {
        AutoGG gg = instance();
        if (gg == null || !gg.isEnabled() || text == null || EvadeClient.mc().player == null) return;
        if (!gg.triggerSource.is("System + Chat")) return;
        String line = Formatting.strip(text);
        // Test what was said, without the "<Steve> " or "[VIP] Steve: " in front.
        Matcher m = CHAT.matcher(line);
        if (m.find()) line = line.substring(m.end());
        if (gg.find(gg.endPattern(), line)) gg.gameEnded();
    }

    /** Whether {@code text} is an end-of-game announcement for the current Server Preset (Clips uses this too). */
    public static boolean matches(String text) {
        if (text == null) return false;
        AutoGG gg = instance();
        String line = Formatting.strip(text);
        return gg == null ? END.matcher(line).find() : gg.find(gg.endPattern(), line);
    }

    /**
     * {@code p.matcher(line).find()}, but a Custom Trigger that takes too long (a pattern that backtracks without
     * end) counts as no match, warns once and falls back to Generic, so a typo can never freeze the game.
     */
    private boolean find(Pattern p, String line) {
        if (p == END || p == HYPIXEL_END) return p.matcher(line).find();
        try {
            return p.matcher(new Deadline(line, System.nanoTime() + 20_000_000L)).find();
        } catch (Deadline.Expired e) {
            compiled = null; // compiledFor stays, so this Custom Trigger isn't compiled again until it changes
            warn(compiledFor, "is too slow", "matching took over 20 ms");
            return END.matcher(line).find();
        }
    }

    /** Tells you once per Custom Trigger text that it can't be used (details in the log). */
    private void warn(String trigger, String why, String detail) {
        if (trigger == null || trigger.equals(warnedFor)) return;
        warnedFor = trigger;
        EvadeClient.LOG.warn("AutoGG Custom Trigger {} ({}); using Generic", why, detail);
        Notifications.push("AutoGG", "Custom Trigger " + why + ", using Generic", false);
    }

    /** Text that stops a regex search (by throwing) once the deadline has passed. */
    private static final class Deadline implements CharSequence {
        static final class Expired extends RuntimeException {
            Expired() { super(null, null, false, false); }
        }

        private final CharSequence text;
        private final long until;
        private int reads;

        Deadline(CharSequence text, long until) {
            this.text = text;
            this.until = until;
        }

        @Override
        public char charAt(int index) {
            // The clock is read every 256 characters: a runaway search reads millions.
            if ((++reads & 255) == 0 && System.nanoTime() > until) throw new Expired();
            return text.charAt(index);
        }

        @Override public int length() { return text.length(); }
        @Override public CharSequence subSequence(int start, int end) { return new Deadline(text.subSequence(start, end), until); }
        @Override public String toString() { return text.toString(); }
    }

    /** Whether {@code text} reads like a player talking ("<Steve> ...", "[VIP] Steve: ..."), not the server. */
    public static boolean looksLikePlayerChat(String text) {
        Matcher m = CHAT.matcher(text);
        if (!m.find()) return false;
        String name = m.group(1);
        return name == null || !(name.equalsIgnoreCase("winner") || name.equalsIgnoreCase("winners"));
    }

    private void gameEnded() {
        long now = System.currentTimeMillis();
        if (now - lastSent < (long) (cooldown.get() * 1000)) return;
        lastSent = now;
        queue(nextMessage());
    }

    private void gameStarted() {
        long now = System.currentTimeMillis();
        if (now - lastGl < GL_GUARD_MS) return;
        lastGl = now;
        queue(glText.get());
    }

    /** Say {@code text} after Delay (a short pause so it doesn't look scripted). */
    private void queue(String text) {
        String clean = clean(text);
        if (clean == null) return;
        pending = clean;
        sendIn = Math.max(1, (int) Math.round(delay.get() * 20));
    }

    // ------------------------------------------------------------------ presets

    /** The preset in use: Auto resolves to Hypixel on a Hypixel address, otherwise Generic. */
    public String activePreset() {
        if (!preset.is("Auto")) return preset.get();
        MinecraftClient mc = EvadeClient.mc();
        ServerInfo entry = mc.isInSingleplayer() ? null : mc.getCurrentServerEntry();
        return entry != null && entry.address != null && entry.address.toLowerCase(Locale.ROOT).contains("hypixel")
                ? "Hypixel" : "Generic";
    }

    private Pattern endPattern() {
        return switch (activePreset()) {
            case "Hypixel" -> HYPIXEL_END;
            case "Custom" -> {
                Pattern p = customPattern();
                yield p == null ? END : p;
            }
            default -> END;
        };
    }

    private Pattern startPattern() {
        return activePreset().equals("Hypixel") ? HYPIXEL_START : START;
    }

    /** Custom Trigger compiled (cached by its text), or null when it is empty or not a valid expression. */
    private Pattern customPattern() {
        String src = customTrigger.get() == null ? "" : customTrigger.get().strip();
        if (!src.equals(compiledFor)) {
            compiledFor = src;
            compiled = null;
            if (!src.isEmpty()) {
                try {
                    compiled = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    warn(src, "isn't valid", e.getDescription());
                }
            }
        }
        return compiled;
    }

    // ------------------------------------------------------------------ messages

    /** The next thing to say: the Message choice, or one of the Custom Text messages. */
    public String nextMessage() {
        if (!message.is("Custom")) return message.get();
        List<String> pool = pool();
        int n = pool.size();
        int pick;
        if (n == 1) pick = 0;
        else if (randomize.get()) {
            pick = ThreadLocalRandom.current().nextInt(lastPick >= 0 && lastPick < n ? n - 1 : n);
            if (lastPick >= 0 && lastPick < n && pick >= lastPick) pick++; // never the same one twice in a row
        } else pick = (lastPick + 1) % n;
        lastPick = pick;
        return pool.get(pick);
    }

    /** Custom Text split on |, trimmed, without empty parts or anything that looks like a command; "gg" if none. */
    private List<String> pool() {
        List<String> out = new ArrayList<>();
        String raw = customText.get() == null ? "" : customText.get();
        for (String part : raw.split("\\|")) {
            String s = part.strip();
            if (!s.isEmpty() && !s.startsWith("/")) out.add(s);
        }
        if (out.isEmpty()) out.add("gg");
        return out;
    }

    /** {@code text} without characters chat refuses, cut to what fits the Channel; null when nothing is left. */
    private String clean(String text) {
        if (text == null) return null;
        String s = StringHelper.stripInvalidChars(text).strip();
        if (s.isEmpty() || s.startsWith("/")) return null;
        int max = MAX_CHAT - prefix().length();
        if (s.length() > max) {
            int cut = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
            s = s.substring(0, cut).strip();
        }
        return s.isEmpty() ? null : s;
    }

    /** The chat command in front of the message for the Channel ("" for Default). */
    private String prefix() {
        return switch (channel.get()) {
            case "All Chat (/ac)" -> "ac ";
            case "Party (/pc)" -> "pc ";
            default -> "";
        };
    }

    /** "Custom Message" (one message) became Custom Text (a pool) with Message: Custom. */
    @Override
    public void onConfigLoaded(JsonObject saved) {
        if (saved == null || saved.has("Custom Text") || !saved.has("Custom Message")) return;
        JsonElement old = saved.get("Custom Message");
        if (!old.isJsonPrimitive()) return;
        String s = old.getAsString().strip();
        if (s.isEmpty() || s.startsWith("/")) return;
        customText.set(s);
        message.set("Custom");
    }

    @Override
    protected void onDisable() {
        sendIn = -1;
        pending = null;
    }

    @Override
    public void onTick() {
        if (sendIn > 0 && --sendIn == 0) {
            sendIn = -1;
            String msg = pending;
            pending = null;
            if (msg == null || mc.player == null) return;
            String prefix = prefix();
            if (prefix.isEmpty()) mc.player.networkHandler.sendChatMessage(msg);
            else mc.player.networkHandler.sendChatCommand(prefix + msg);
        }
    }
}
