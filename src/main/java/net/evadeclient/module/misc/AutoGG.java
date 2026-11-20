package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;

import java.util.Locale;
import java.util.regex.Pattern;

/** Says "gg" in chat when a match ends (detects common end-of-game messages). At most once every 10 seconds. */
public class AutoGG extends Module {
    private static final Pattern END = Pattern.compile(
            "(1st killer|winner[s]?:|you won|victory!|game over|you died! want to play again|has won the game|#1 )", Pattern.CASE_INSENSITIVE);
    private final ModeSetting message = add(new ModeSetting("Message", "gg", "gg", "GG", "Good game"));
    private long lastSent;
    private int sendIn = -1;

    public AutoGG() {
        super("AutoGG", "Says gg when a match ends.", Category.MISC);
    }

    /** Called for every chat or system message we receive. */
    public static void onMessage(String text) {
        if (EvadeClient.modules == null) return;
        AutoGG gg = EvadeClient.modules.get(AutoGG.class);
        if (gg == null || !gg.isEnabled() || EvadeClient.mc().player == null) return;
        if (!matches(text) || System.currentTimeMillis() - gg.lastSent < 10_000) return;
        gg.lastSent = System.currentTimeMillis();
        gg.sendIn = 20; // wait a second so it doesn't look scripted
    }

    public static boolean matches(String text) {
        return END.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    @Override
    public void onTick() {
        if (sendIn > 0 && --sendIn == 0) {
            mc.player.networkHandler.sendChatMessage(message.get());
            sendIn = -1;
        }
    }
}
