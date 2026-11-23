package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.minecraft.util.Formatting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Says "gg" in chat when a match ends. Only the server's own end-of-game announcements count (anchored at the start
 * of a system message); player chat that a server relays as a system message never does. At most once every 10 s.
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
    /**
     * Player chat relayed as a system message: "<Steve> gg", "[MVP+] Steve: you won", "[TEAM] Steve » victory".
     * Group 1 is the name before the separator, so "Winner: Steve" can be told apart from a player called Winner.
     */
    private static final Pattern CHAT = Pattern.compile(
            "^(?:\\[[^\\]]{1,32}]\\s*){0,3}(?:<[A-Za-z0-9_]{1,16}>\\s|([A-Za-z0-9_]{1,16})\\s*(?::|»|>>|->|>)\\s)");

    private final ModeSetting message = add(new ModeSetting("Message", "gg", "gg", "GG", "Good game"));
    private final NumberSetting delay = add(new NumberSetting("Delay (s)", 1, 0.5, 5, 0.5)
            .describe("How long to wait after the game ends before saying it."));
    private final StringSetting custom = add(new StringSetting("Custom Message", "", "replaces Message when set")
            .maxLength(100)
            .describe("Your own words instead of the Message choice. Plain chat only: commands are never sent."));
    private long lastSent;
    private int sendIn = -1;

    public AutoGG() {
        super("AutoGG", "Says gg when a match ends.", Category.SERVER);
    }

    /** Called for every system (game) message we receive. */
    public static void onMessage(String text) {
        if (EvadeClient.modules == null || text == null) return;
        text = Formatting.strip(text); // legacy § colour codes would hide the start of the line from the patterns
        AutoGG gg = EvadeClient.modules.get(AutoGG.class);
        if (gg == null || !gg.isEnabled() || EvadeClient.mc().player == null) return;
        if (looksLikePlayerChat(text) || !matches(text)) return;
        if (System.currentTimeMillis() - gg.lastSent < 10_000) return;
        gg.lastSent = System.currentTimeMillis();
        gg.sendIn = Math.max(1, (int) Math.round(gg.delay.get() * 20)); // a short pause so it doesn't look scripted
    }

    /** Whether {@code text} is one of the end-of-game announcements AutoGG answers. */
    public static boolean matches(String text) {
        return text != null && END.matcher(Formatting.strip(text)).find();
    }

    /** Whether {@code text} reads like a player talking ("<Steve> ...", "[VIP] Steve: ..."), not the server. */
    public static boolean looksLikePlayerChat(String text) {
        Matcher m = CHAT.matcher(text);
        if (!m.find()) return false;
        String name = m.group(1);
        return name == null || !(name.equalsIgnoreCase("winner") || name.equalsIgnoreCase("winners"));
    }

    /** What will be said: the custom message when it is set (and isn't a command), else the Message choice. */
    public String text() {
        String c = custom.get() == null ? "" : custom.get().strip();
        return c.isEmpty() || c.startsWith("/") ? message.get() : c;
    }

    @Override
    protected void onDisable() { sendIn = -1; }

    @Override
    public void onTick() {
        if (sendIn > 0 && --sendIn == 0) {
            sendIn = -1;
            if (mc.player != null) mc.player.networkHandler.sendChatMessage(text());
        }
    }
}
